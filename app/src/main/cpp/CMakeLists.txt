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

set(CUMULUS_HAS_VULKAN OFF)
set(CUMULUS_HAS_OPENCL OFF)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
message(STATUS "GPU Android desactivada, compilando por CPU")

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        ${LLAMA_GIT_TAG}
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

set(CUMULUS_NEW_LOAD_API OFF)
if (EXISTS "${llama_SOURCE_DIR}/include/llama.h")
    file(READ "${llama_SOURCE_DIR}/include/llama.h" _LLAMA_H_CONTENT)
    if (NOT _LLAMA_H_CONTENT MATCHES "llama_load_model_from_file")
        set(CUMULUS_NEW_LOAD_API ON)
        message(STATUS "llama.cpp con API nueva: llama_model_load_from_file")
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
