cmake_minimum_required(VERSION 3.18)
project(wboard_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

include(FetchContent)

# Share downloaded whisper.cpp source across ABIs during multi-ABI builds
set(FETCHCONTENT_BASE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../.cxx_deps")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper_src
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.6.tar.gz
)
FetchContent_MakeAvailable(whisper_src)

add_library(
    wboard_whisper
    SHARED
    wboard_whisper_jni.cpp
)

target_include_directories(
    wboard_whisper
    PRIVATE
    "${whisper_src_SOURCE_DIR}/include"
    "${whisper_src_SOURCE_DIR}/ggml/include"
)

target_compile_options(
    wboard_whisper
    PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

target_link_options(
    wboard_whisper
    PRIVATE
    -Wl,--gc-sections
)

find_library(LOG_LIB log)

target_link_libraries(
    wboard_whisper
    PRIVATE
    whisper
    ${LOG_LIB}
    android
)
