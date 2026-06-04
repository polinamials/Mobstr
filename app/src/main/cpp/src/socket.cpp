//
// Created by polina on 5/30/26.
//

#include <arpa/inet.h>
#include "socket.hpp"
#include <android/log.h>
#include <string>

#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "MOBSTR_SOCKET"

Socket::Socket(const std::string& ip, const uint16_t port)
{
    m_socket = socket(AF_INET, SOCK_DGRAM, 0);
    if (m_socket == -1) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,"Failed to create socket descriptor");
        return;
    }

    m_address.sin_family = AF_INET;
    m_address.sin_port = htons(port);

    if (inet_pton(AF_INET, ip.c_str(), &m_address.sin_addr) <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,"Invalid or unsupported IP address string");
        return;
    }
}

Socket::~Socket()
{
    if (m_socket != -1) {
        close(m_socket);
    }
}

ssize_t Socket::pushData(const uint8_t *data, const size_t dataSize) {
    if (m_socket == -1) return -1;
    return sendto(m_socket, data, dataSize, 0,
                  (const struct sockaddr*)&m_address, sizeof(m_address));
}
