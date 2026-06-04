//
// Created by polina on 5/30/26.
//
#include "rtp_packetizer.hpp"
#include <array>
#include <cstdint>

std::array<uint8_t, 12> RtpPacketizer::getRtpHeader(uint32_t timestamp, bool padding)
{
    std::array<uint8_t, 12> header;

    header[0] = 0x80;
    if (padding) {
        header[0] |= 0x20;
    }

    header[1] = 96;

    header[2] = static_cast<uint8_t>((m_sequenceNumber >> 8) & 0xFF);
    header[3] = static_cast<uint8_t>(m_sequenceNumber & 0xFF);

    m_sequenceNumber++;

    header[4] = static_cast<uint8_t>((timestamp >> 24) & 0xFF);
    header[5] = static_cast<uint8_t>((timestamp >> 16) & 0xFF);
    header[6] = static_cast<uint8_t>((timestamp >> 8) & 0xFF);
    header[7] = static_cast<uint8_t>(timestamp & 0xFF);

    header[8] = static_cast<uint8_t>((m_ssrc >> 24) & 0xFF);
    header[9] = static_cast<uint8_t>((m_ssrc >> 16) & 0xFF);
    header[10] = static_cast<uint8_t>((m_ssrc >> 8) & 0xFF);
    header[11] = static_cast<uint8_t>(m_ssrc & 0xFF);

    return header;
}