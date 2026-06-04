//
// Created by polina on 5/30/26.
//

#ifndef MOBSTR_SOCKET_HPP
#define MOBSTR_SOCKET_HPP

#include <cstring>
#include <string>
#include <iostream>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>


class Socket
{
public:
    Socket(const std::string& ip, uint16_t port);
    ~Socket();

    ssize_t pushData(const uint8_t *data, size_t dataSize);


private:
    int m_socket;
    sockaddr_in m_address;

};


#endif //MOBSTR_SOCKET_HPP
