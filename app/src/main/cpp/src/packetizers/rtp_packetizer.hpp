//
// Created by polina on 5/30/26.
//

#ifndef MOBSTR_RTP_PACKETIZER_HPP
#define MOBSTR_RTP_PACKETIZER_HPP
#include <cstdint>
#include <cstddef>
#include <vector>

struct RtpPacket
{
    std::vector<uint8_t> buffer;
    size_t bufferSize{0};
};

class RtpPacketizer
{
public:
    virtual ~RtpPacketizer() = default;
    virtual std::vector<RtpPacket> processFrame(const uint8_t* data, size_t size, uint32_t flags, uint32_t timestamp) = 0;

    uint16_t m_sequenceNumber{0};
    uint32_t m_ssrc{0};
    std::array<uint8_t, 12> getRtpHeader(uint32_t timestamp, bool padding);
};

#endif //MOBSTR_RTP_PACKETIZER_HPP
