set(LIVE555_ROOT ${CMAKE_CURRENT_LIST_DIR}/../third_party/live555)

file(GLOB LIVE555_SOURCES CONFIGURE_DEPENDS
        ${LIVE555_ROOT}/UsageEnvironment/*.cpp
        ${LIVE555_ROOT}/BasicUsageEnvironment/*.cpp
        ${LIVE555_ROOT}/groupsock/*.cpp
        ${LIVE555_ROOT}/groupsock/*.c
        ${LIVE555_ROOT}/liveMedia/*.cpp
        ${LIVE555_ROOT}/liveMedia/*.c
)

add_library(live555 STATIC ${LIVE555_SOURCES})

target_include_directories(live555 SYSTEM PUBLIC
        ${LIVE555_ROOT}/UsageEnvironment/include
        ${LIVE555_ROOT}/BasicUsageEnvironment/include
        ${LIVE555_ROOT}/groupsock/include
        ${LIVE555_ROOT}/liveMedia/include
)

target_compile_definitions(live555 PUBLIC
        BSD=1
        NO_OPENSSL=1
        SOCKLEN_T=socklen_t
        _FILE_OFFSET_BITS=64
        _LARGEFILE_SOURCE=1
)

target_compile_definitions(live555 PRIVATE
        ALLOW_RTSP_SERVER_PORT_REUSE=1
)

set_target_properties(live555 PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(live555 PRIVATE -w)
