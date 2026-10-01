cmake_minimum_required(VERSION 3.22.1)

project("cumulus_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

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
# 🎮 GPU OPENCL (backend oficial de Qualcomm para GPUs Adreno / Snapdragon)
# ============================================================================
# 1) Encabezados oficiales de OpenCL (Khronos)
FetchContent_Declare(
    opencl_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
    GIT_TAG        v2024.10.24
    GIT_SHALLOW    TRUE
    SOURCE_SUBDIR  cumulus_no_build
)
FetchContent_MakeAvailable(opencl_headers)
message(STATUS "OpenCL headers: ${opencl_headers_SOURCE_DIR}")

# 2) Cargador OpenCL (solo para enlazar; en el teléfono se usa el del sistema
#    /vendor/lib64/libOpenCL.so, que se excluye del APK en build.gradle)
set(OPENCL_ICD_LOADER_HEADERS_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPENCL_ICD_LOADER_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    opencl_icd_loader
    GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-ICD-Loader.git
    GIT_TAG        v2024.10.24
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(opencl_icd_loader)
if (NOT TARGET OpenCL::OpenCL)
    add_library(OpenCL::OpenCL ALIAS OpenCL)
endif()
set(OpenCL_INCLUDE_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
set(OpenCL_LIBRARY "OpenCL" CACHE STRING "" FORCE)

set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL ON  CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
set(CUMULUS_HAS_VULKAN OFF)
set(CUMULUS_HAS_OPENCL ON)
message(STATUS "GPU Android: OpenCL (Adreno) ACTIVADO (CPU como respaldo)")

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        ${LLAMA_GIT_TAG}
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

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
    file(GLOB CUMULUS_MTMD_MODEL_SOURCES "${CUMULUS_MTMD_DIR}/models/*.cpp")
    add_library(
        cumulus_mtmd
        STATIC
        ${CUMULUS_MTMD_DIR}/mtmd.cpp
        ${CUMULUS_MTMD_DIR}/mtmd-audio.cpp
        ${CUMULUS_MTMD_DIR}/mtmd-image.cpp
        ${CUMULUS_MTMD_DIR}/mtmd-helper.cpp
        ${CUMULUS_MTMD_DIR}/clip.cpp
        ${CUMULUS_MTMD_MODEL_SOURCES}
    )
    set_target_properties(cumulus_mtmd PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_include_directories(cumulus_mtmd PUBLIC  "${CUMULUS_MTMD_DIR}")
    target_include_directories(cumulus_mtmd PRIVATE "${llama_SOURCE_DIR}" "${llama_SOURCE_DIR}/vendor")
    target_compile_features(cumulus_mtmd PRIVATE cxx_std_17)
    target_compile_options(cumulus_mtmd PRIVATE -Wno-cast-qual -Wno-missing-prototypes)
    target_link_libraries(cumulus_mtmd PUBLIC llama ggml)
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

