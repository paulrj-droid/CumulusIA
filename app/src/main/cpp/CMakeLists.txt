cmake_minimum_required(VERSION 3.22.1)

project("cumulus_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# llama.cpp queda DENTRO de libcumulus_native.so (estático): así no choca con el
# libllama.so / libggml.so que trae GenieX (SDK de Qualcomm)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ============================================================================
# ⚡ VELOCIDAD: el motor siempre se compila optimizado (-O3), aunque el APK sea
#    "debug". Sin esto, en debug el motor queda sin optimizar y es ~10 veces más lento.
# ============================================================================
set(CMAKE_C_FLAGS_DEBUG   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
# Instrucciones rápidas del Snapdragon 8 Elite (S25): dotprod + i8mm + fp16
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)

set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)

if (DEFINED ENV{LLAMA_GIT_TAG} AND NOT "$ENV{LLAMA_GIT_TAG}" STREQUAL "")
    set(LLAMA_GIT_TAG "$ENV{LLAMA_GIT_TAG}")
    message(STATUS "llama.cpp tag (desde env): ${LLAMA_GIT_TAG}")
else()
    set(LLAMA_GIT_TAG "b9222")
    message(STATUS "llama.cpp tag (por defecto): ${LLAMA_GIT_TAG}")
endif()

# ============================================================================
# ⚡ NPU HEXAGON (Snapdragon): backend oficial ggml-hexagon de llama.cpp.
#    El contenedor de GitHub (snapdragon-toolchain) trae el Hexagon SDK 6.6.
# ============================================================================
set(CUMULUS_HAS_HEXAGON OFF)
set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
if (DEFINED ENV{HEXAGON_SDK_ROOT} AND NOT DEFINED HEXAGON_SDK_ROOT)
    set(HEXAGON_SDK_ROOT "$ENV{HEXAGON_SDK_ROOT}" CACHE PATH "")
endif()
# NPU/GPU de los GGUF ahora los maneja GenieX (Qualcomm): nuestro motor queda solo en CPU
set(CUMULUS_ENABLE_ACCEL OFF)
if (CUMULUS_ENABLE_ACCEL AND HEXAGON_SDK_ROOT AND IS_DIRECTORY "${HEXAGON_SDK_ROOT}" AND EXISTS "${HEXAGON_SDK_ROOT}/build/cmake/hexagon_fun.cmake")
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    # Igual que el preset oficial de llama.cpp "arm64-android-snapdragon"
    # (sin PREBUILT_LIB_DIR el hexagon_fun.cmake del SDK falla con "string FIND")
    set(PREBUILT_LIB_DIR "android_aarch64" CACHE STRING "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
    set(CUMULUS_HAS_HEXAGON ON)
    message(STATUS "NPU Hexagon ACTIVADA (SDK: ${HEXAGON_SDK_ROOT})")
else()
    message(STATUS "NPU Hexagon desactivada: no se encontró el Hexagon SDK")
endif()

# ============================================================================
# 🎮 GPU VULKAN (Adreno). OpenCL quedó fuera: era más lento que la CPU.
# ============================================================================
# Lo que necesita ggml-vulkan para compilar en Android:
#  1) vulkan.hpp  -> el NDK solo trae vulkan.h, así que se bajan los Vulkan-Headers
#  2) SPIRV-Headers
#  3) glslc       -> viene dentro del NDK (shader-tools)
#  4) libvulkan   -> la del sistema Android (sysroot del NDK)
#  5) compilador del PC (gcc/clang) para generar los shaders: GitHub Actions lo trae
set(CUMULUS_HAS_VULKAN OFF)
set(CUMULUS_HAS_OPENCL OFF)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    vulkan_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
    GIT_TAG        vulkan-sdk-1.4.350.1
    GIT_SHALLOW    TRUE
    SOURCE_SUBDIR  cumulus_no_build
)
FetchContent_MakeAvailable(vulkan_headers)

FetchContent_Declare(
    spirv_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
    GIT_TAG        vulkan-sdk-1.4.350.1
    GIT_SHALLOW    TRUE
    SOURCE_SUBDIR  cumulus_no_build
)
FetchContent_MakeAvailable(spirv_headers)

# glslc del NDK
if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(_cumulus_host_tag "windows-x86_64")
    set(_cumulus_exe ".exe")
elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(_cumulus_host_tag "darwin-x86_64")
    set(_cumulus_exe "")
else()
    set(_cumulus_host_tag "linux-x86_64")
    set(_cumulus_exe "")
endif()
set(_cumulus_glslc "${ANDROID_NDK}/shader-tools/${_cumulus_host_tag}/glslc${_cumulus_exe}")

# ⚠️ IMPORTANTE (llama.cpp issue #28635): el glslc de NDK 26 o anterior genera
# shaders que la GPU Adreno 830 (Galaxy S25) rechaza -> la app se cierra.
# Con el glslc de NDK 28/29 funciona. Se busca el NDK más nuevo instalado y se usa
# SU glslc (el resto de la app sigue compilando con el NDK normal).
set(_cumulus_ndk_roots "")
foreach(_envvar ANDROID_HOME ANDROID_SDK_ROOT)
    if (DEFINED ENV{${_envvar}})
        list(APPEND _cumulus_ndk_roots "$ENV{${_envvar}}/ndk")
    endif()
endforeach()
get_filename_component(_cumulus_ndk_parent "${ANDROID_NDK}" DIRECTORY)
list(APPEND _cumulus_ndk_roots "${_cumulus_ndk_parent}")
set(_cumulus_best_ver "0")
foreach(_root IN LISTS _cumulus_ndk_roots)
    file(GLOB _cands "${_root}/*/shader-tools/${_cumulus_host_tag}/glslc${_cumulus_exe}")
    foreach(_c IN LISTS _cands)
        string(REGEX MATCH "/([0-9]+\\.[0-9]+\\.[0-9]+)/shader-tools/" _m "${_c}")
        if (CMAKE_MATCH_1 AND CMAKE_MATCH_1 VERSION_GREATER _cumulus_best_ver)
            set(_cumulus_best_ver "${CMAKE_MATCH_1}")
            set(_cumulus_glslc "${_c}")
        endif()
    endforeach()
endforeach()
if (DEFINED ENV{CUMULUS_GLSLC} AND EXISTS "$ENV{CUMULUS_GLSLC}")
    set(_cumulus_glslc "$ENV{CUMULUS_GLSLC}")
    set(_cumulus_best_ver "env")
endif()
message(STATUS "glslc elegido (NDK ${_cumulus_best_ver}): ${_cumulus_glslc}")
if (_cumulus_best_ver VERSION_LESS "28.0.0" AND NOT _cumulus_best_ver STREQUAL "env")
    message(WARNING "⚠️ glslc de NDK ${_cumulus_best_ver}: puede cerrar la app en Adreno 830. Instala NDK 29 (ver workflow).")
endif()

# libvulkan.so del sistema (según el minSdk)
find_library(CUMULUS_VULKAN_LIB vulkan)

if (CUMULUS_ENABLE_ACCEL AND EXISTS "${_cumulus_glslc}" AND CUMULUS_VULKAN_LIB)
    set(Vulkan_INCLUDE_DIR "${vulkan_headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)
    set(Vulkan_LIBRARY "${CUMULUS_VULKAN_LIB}" CACHE FILEPATH "" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE "${_cumulus_glslc}" CACHE FILEPATH "" FORCE)

    # ggml-vulkan pide find_package(SPIRV-Headers): se le entrega un config mínimo
    set(_cumulus_spirv_cfg "${CMAKE_BINARY_DIR}/cumulus-spirv")
    file(WRITE "${_cumulus_spirv_cfg}/SPIRV-HeadersConfig.cmake"
"if (NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES \"${spirv_headers_SOURCE_DIR}/include\")
endif()
include_directories(\"${spirv_headers_SOURCE_DIR}/include\")
set(SPIRV-Headers_FOUND TRUE)
")
    set(SPIRV-Headers_DIR "${_cumulus_spirv_cfg}" CACHE PATH "" FORCE)

    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(CUMULUS_HAS_VULKAN ON)
    message(STATUS "GPU Android: Vulkan ACTIVADO (glslc: ${_cumulus_glslc})")
else()
    message(WARNING "Vulkan desactivado: falta glslc (${_cumulus_glslc}) o libvulkan. Solo CPU.")
endif()

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        ${LLAMA_GIT_TAG}
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

# Los shaders de Vulkan generan archivos enormes (100+ MB): se compilan de a 2
# para que GitHub Actions no se quede sin memoria
if (TARGET ggml-vulkan)
    set_property(GLOBAL APPEND PROPERTY JOB_POOLS cumulus_vk_pool=2)
    set_property(TARGET ggml-vulkan PROPERTY JOB_POOL_COMPILE cumulus_vk_pool)
endif()

set(CUMULUS_NEW_LOAD_API OFF)
set(CUMULUS_USE_MEMORY_API OFF)
set(CUMULUS_HAS_CHAT_TEMPLATE OFF)
if (EXISTS "${llama_SOURCE_DIR}/include/llama.h")
    file(READ "${llama_SOURCE_DIR}/include/llama.h" _LLAMA_H_CONTENT)
    # La función nueva ya existe (aunque la vieja siga como «deprecated»): se usa la nueva y no sale el aviso
    if (_LLAMA_H_CONTENT MATCHES "llama_model_load_from_file" OR NOT _LLAMA_H_CONTENT MATCHES "llama_load_model_from_file")
        set(CUMULUS_NEW_LOAD_API ON)
        message(STATUS "llama.cpp con API nueva: llama_model_load_from_file")
    endif()
    if (NOT _LLAMA_H_CONTENT MATCHES "llama_kv_cache_clear")
        set(CUMULUS_USE_MEMORY_API ON)
        message(STATUS "llama.cpp con API nueva de memoria: llama_memory_clear")
    endif()
    if (_LLAMA_H_CONTENT MATCHES "llama_model_chat_template")
        set(CUMULUS_HAS_CHAT_TEMPLATE ON)
        message(STATUS "llama.cpp con plantillas de chat del modelo")
    endif()
endif()

add_library(
    cumulus_native
    SHARED
    native-lib.cpp
)

# ============================================================================
# 👁️ VISIÓN (mtmd de llama.cpp): permite que modelos -VL lean imágenes
#    usando su archivo mmproj. Se compila solo la librería (sin las
#    herramientas de consola, que necesitan llama-common).
# ============================================================================
set(CUMULUS_MTMD_DIR "${llama_SOURCE_DIR}/tools/mtmd")
if (EXISTS "${CUMULUS_MTMD_DIR}/mtmd.h")
    # Se toman todos los .cpp de mtmd (cambian entre versiones de llama.cpp),
    # menos las herramientas de consola
    file(GLOB CUMULUS_MTMD_SOURCES "${CUMULUS_MTMD_DIR}/*.cpp")
    list(FILTER CUMULUS_MTMD_SOURCES EXCLUDE REGEX ".*(-cli|deprecation-warning)\\.cpp$")
    file(GLOB CUMULUS_MTMD_MODEL_SOURCES "${CUMULUS_MTMD_DIR}/models/*.cpp")
    add_library(
        cumulus_mtmd
        STATIC
        ${CUMULUS_MTMD_SOURCES}
        ${CUMULUS_MTMD_MODEL_SOURCES}
    )
    set_target_properties(cumulus_mtmd PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_include_directories(cumulus_mtmd PUBLIC "${CUMULUS_MTMD_DIR}" "${llama_SOURCE_DIR}/vendor")
    target_include_directories(cumulus_mtmd PRIVATE "${llama_SOURCE_DIR}")
    target_compile_features(cumulus_mtmd PRIVATE cxx_std_17)
    target_compile_options(cumulus_mtmd PRIVATE -Wno-cast-qual -Wno-missing-prototypes)
    target_link_libraries(cumulus_mtmd PUBLIC llama ggml)
    # Versiones nuevas de llama.cpp separan estas librerías auxiliares
    foreach(_vendor_lib vendor::hash vendor::miniaudio vendor::stb vendor::sheredom)
        if (TARGET ${_vendor_lib})
            target_link_libraries(cumulus_mtmd PRIVATE ${_vendor_lib})
        endif()
    endforeach()
    target_link_libraries(cumulus_native cumulus_mtmd)
    target_compile_definitions(cumulus_native PRIVATE CUMULUS_HAS_MTMD)
    message(STATUS "Visión (mtmd) ACTIVADA")
else()
    message(STATUS "Visión (mtmd) no disponible en este tag de llama.cpp")
endif()

# ============================================================================
# 💬 PLANTILLA DE CHAT DE CADA GGUF (Jinja): el motor Jinja de llama.cpp ejecuta la plantilla
#    oficial que trae cada modelo (tokenizer.chat_template), así cualquier familia usa sus
#    propias etiquetas. Se compila solo el motor Jinja (no todo llama-common).
# ============================================================================
set(CUMULUS_JINJA_DIR "${llama_SOURCE_DIR}/common")
if (EXISTS "${CUMULUS_JINJA_DIR}/jinja/runtime.h" AND EXISTS "${CUMULUS_JINJA_DIR}/jinja/caps.h"
        AND EXISTS "${CUMULUS_JINJA_DIR}/unicode.cpp" AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/cumulus-jinja.cpp")
    file(GLOB CUMULUS_JINJA_SOURCES "${CUMULUS_JINJA_DIR}/jinja/*.cpp")
    set(CUMULUS_JINJA_EXTRA "${CUMULUS_JINJA_DIR}/unicode.cpp")
    if (EXISTS "${CUMULUS_JINJA_DIR}/json.h" AND EXISTS "${CUMULUS_JINJA_DIR}/json.cpp")
        list(APPEND CUMULUS_JINJA_EXTRA "${CUMULUS_JINJA_DIR}/json.cpp")
    endif()
    add_library(cumulus_jinja STATIC ${CUMULUS_JINJA_SOURCES} ${CUMULUS_JINJA_EXTRA} cumulus-jinja.cpp)
    set_target_properties(cumulus_jinja PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_include_directories(cumulus_jinja PRIVATE "${CUMULUS_JINJA_DIR}" "${llama_SOURCE_DIR}/vendor")
    target_compile_features(cumulus_jinja PRIVATE cxx_std_17)
    target_compile_options(cumulus_jinja PRIVATE -Wno-cast-qual -Wno-missing-prototypes)
    if (EXISTS "${CUMULUS_JINJA_DIR}/json.h")
        target_compile_definitions(cumulus_jinja PRIVATE CUMULUS_JINJA_COMMON_JSON)
    endif()
    if (TARGET vendor::nlohmann)
        target_link_libraries(cumulus_jinja PRIVATE vendor::nlohmann)
    endif()
    target_link_libraries(cumulus_jinja PRIVATE ggml)
    target_link_libraries(cumulus_native cumulus_jinja)
    target_compile_definitions(cumulus_native PRIVATE CUMULUS_HAS_JINJA)
    message(STATUS "Plantillas Jinja de los GGUF ACTIVADAS")
else()
    message(STATUS "Plantillas Jinja no disponibles en este tag de llama.cpp (se usa la detección de llama.cpp)")
endif()

if (CUMULUS_HAS_VULKAN)
    target_compile_definitions(cumulus_native PRIVATE CUMULUS_HAS_VULKAN)
endif()
if (CUMULUS_HAS_OPENCL)
    target_compile_definitions(cumulus_native PRIVATE CUMULUS_HAS_OPENCL)
endif()
if (CUMULUS_HAS_HEXAGON)
    target_compile_definitions(cumulus_native PRIVATE CUMULUS_HAS_HEXAGON)
endif()
if (CUMULUS_NEW_LOAD_API)
    target_compile_definitions(cumulus_native PRIVATE CUMULUS_USE_NEW_LOAD_API)
endif()
if (CUMULUS_USE_MEMORY_API)
    target_compile_definitions(cumulus_native PRIVATE CUMULUS_USE_MEMORY_API)
endif()
if (CUMULUS_HAS_CHAT_TEMPLATE)
    target_compile_definitions(cumulus_native PRIVATE CUMULUS_HAS_CHAT_TEMPLATE)
endif()
if (EXISTS "${llama_SOURCE_DIR}/ggml/include/ggml-backend.h")
    target_compile_definitions(cumulus_native PRIVATE CUMULUS_HAS_GGML_BACKEND)
endif()

find_library(
    log-lib
    log
)

target_link_libraries(
    cumulus_native
    llama
    ${log-lib}
)

# El motor ggml-hexagon queda enlazado dentro de libllama/libggml (no hace falta enlazarlo aparte).
# Las librerías que corren DENTRO de la NPU (libggml-htp-v79.so, etc.) las copia
# el workflow de GitHub a jniLibs para que vayan en el APK.

# 📏 Páginas de memoria de 16 KB: Google Play lo exige a las apps con código nativo que apuntan a Android 15 o más
#    (sin esto, la librería del motor se arma para páginas de 4 KB y Play Console la marca como incompatible)
target_link_options(cumulus_native PRIVATE "-Wl,-z,max-page-size=16384")
