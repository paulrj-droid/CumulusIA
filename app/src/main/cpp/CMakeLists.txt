cmake_minimum_required(VERSION 3.22.1)

project("cumulus_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

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

set(CUMULUS_HAS_HEXAGON OFF)
if (DEFINED ENV{HEXAGON_SDK_ROOT} AND NOT DEFINED HEXAGON_SDK_ROOT)
    set(HEXAGON_SDK_ROOT "$ENV{HEXAGON_SDK_ROOT}" CACHE PATH "")
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

# libvulkan.so del sistema (según el minSdk)
find_library(CUMULUS_VULKAN_LIB vulkan)

if (EXISTS "${_cumulus_glslc}" AND CUMULUS_VULKAN_LIB)
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
    if (NOT _LLAMA_H_CONTENT MATCHES "llama_load_model_from_file")
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

if (GGML_HEXAGON)
    target_link_libraries(
        cumulus_native
        ggml-hexagon
        ggml-base
    )
endif()
