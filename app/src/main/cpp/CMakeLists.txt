cmake_minimum_required(VERSION 3.31.6)

project(aichat-native VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "AIchat only supports arm64-v8a")
endif()

list(PREPEND CMAKE_PREFIX_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
set(
    "SPIRV-Headers_DIR"
    "${CMAKE_CURRENT_SOURCE_DIR}/cmake"
    CACHE PATH "Bundled SPIR-V package metadata" FORCE
)
set(
    Vulkan_GLSLC_EXECUTABLE
    "${ANDROID_NDK}/shader-tools/darwin-x86_64/glslc"
    CACHE FILEPATH "Android NDK glslc" FORCE
)

set(GGML_SYSTEM_ARCH ARM)
set(GGML_CPU_KLEIDIAI ON)
set(GGML_OPENMP ON)
set(
    GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
    "${CMAKE_CURRENT_SOURCE_DIR}/cmake/host-toolchain.cmake"
    CACHE FILEPATH "Host Vulkan shader generator toolchain" FORCE
)

add_subdirectory(llama.cpp build-llama)
set(LLAMA_INSTALL_VERSION 0.0.0)
set(MTMD_VIDEO OFF CACHE BOOL "Disable ffmpeg-based video support" FORCE)
add_subdirectory(llama.cpp/tools/mtmd build-mtmd EXCLUDE_FROM_ALL)

if(TARGET ggml-vulkan)
    target_include_directories(
        ggml-vulkan
        PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/vulkan-headers/include"
            "${ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include"
    )
endif()

add_library(aichat-native SHARED aichat_jni.cpp)

target_include_directories(aichat-native PRIVATE
    llama.cpp
    llama.cpp/common
    llama.cpp/include
    llama.cpp/ggml/include
    llama.cpp/tools/mtmd
)

target_link_libraries(aichat-native
    llama
    llama-common
    mtmd
    android
    log
)
