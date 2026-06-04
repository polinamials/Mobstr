cmake_minimum_required(VERSION 3.22.1)
project("mobstr")

set(PROJECT_SOURCES
        native-lib.cpp
        src/socket.cpp
        src/stream_controller.cpp
        src/packetizers/h264_packetizer.cpp
        src/packetizers/rtp_packetizer.cpp
)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ${PROJECT_SOURCES}
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        src
        src/packetizers
)

target_link_libraries(
        ${CMAKE_PROJECT_NAME}
        mediandk
        android
        log
)