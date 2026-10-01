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
# 🎮 GPU VULKAN
# ============================================================================
# 1) Encabezados de Vulkan (incluye vulkan.hpp, que el NDK no trae)
FetchContent_Declare(
    vulkan_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
    GIT_TAG        v1.4.304
    GIT_SHALLOW    TRUE
    SOURCE_SUBDIR  cumulus_no_build
)
FetchContent_MakeAvailable(vulkan_headers)
set(Vulkan_INCLUDE_DIR "${vulkan_headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)
message(STATUS "Vulkan headers: ${Vulkan_INCLUDE_DIR}")

# 1b) Encabezados SPIR-V (llama.cpp los pide con find_package(SPIRV-Headers))
FetchContent_Declare(
    spirv_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
    GIT_TAG        vulkan-sdk-1.4.304.0
    GIT_SHALLOW    TRUE
    SOURCE_SUBDIR  cumulus_no_build
)
FetchContent_MakeAvailable(spirv_headers)
set(_SPIRV_CFG_DIR "${CMAKE_BINARY_DIR}/cumulus_spirv_cfg")
file(WRITE "${_SPIRV_CFG_DIR}/SPIRV-HeadersConfig.cmake"
"if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES \"${spirv_headers_SOURCE_DIR}/include\")
endif()
set(SPIRV-Headers_FOUND TRUE)
")
set(SPIRV-Headers_DIR "${_SPIRV_CFG_DIR}" CACHE PATH "" FORCE)
message(STATUS "SPIRV-Headers: ${spirv_headers_SOURCE_DIR}/include")
# Copia spirv/ junto a los encabezados de Vulkan, que ggml-vulkan ya incluye
file(COPY "${spirv_headers_SOURCE_DIR}/include/spirv"
     DESTINATION "${vulkan_headers_SOURCE_DIR}/include")

# 2) Compilador de shaders glslc (del sistema o, si no hay, el del NDK)
if (EXISTS "/usr/bin/glslc")
    set(Vulkan_GLSLC_EXECUTABLE "/usr/bin/glslc" CACHE FILEPATH "" FORCE)
elseif (DEFINED CMAKE_ANDROID_NDK AND EXISTS "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64/glslc")
    set(Vulkan_GLSLC_EXECUTABLE "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64/glslc" CACHE FILEPATH "" FORCE)
endif()
message(STATUS "glslc: ${Vulkan_GLSLC_EXECUTABLE}")

set(GGML_VULKAN ON  CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(CUMULUS_HAS_VULKAN ON)
set(CUMULUS_HAS_OPENCL OFF)
message(STATUS "GPU Android: Vulkan ACTIVADO (CPU como respaldo)")

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        ${LLAMA_GIT_TAG}
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

set(CUMULUS_NEW_LOAD_API OFF)
set(CUMULUS_USE_MEMORY_API OFF)
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
endif()

add_library(
    cumulus_native
    SHARED
    native-lib.cpp
)

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
