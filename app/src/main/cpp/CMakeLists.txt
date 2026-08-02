cmake_minimum_required(VERSION 3.22.1)
project("mobstr")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(cmake/live555.cmake)

set(PROJECT_SOURCES
        native-lib.cpp
        src/h264_access_unit.cpp
        src/live555_server.cpp
        src/media_codec_h264_source.cpp
        src/stream_controller.cpp
)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ${PROJECT_SOURCES}
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        src
)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -Wall -Wextra -Wpedantic)

target_link_libraries(
        ${CMAKE_PROJECT_NAME}
        live555
        mediandk
        android
        log
)
