//
// Created by polina on 5/30/26.
//

#ifndef MOBSTR_H264_PACKETIZER_HPP
#define MOBSTR_H264_PACKETIZER_HPP

#include "rtp_packetizer.hpp"

#define DEFAULT_MTU 1400

enum H264_NAL_TYPES {
    NON_IDR = 1,
    IDR = 5,
    SPS = 7,
    PPS  = 8,
    SEI = 6,
    END_OF_STREAM = 11,
    UNKNOWN = -1
};

struct NaluInfo
{
    size_t startPos{0};
    size_t startCodeLength{0};
    size_t size{0};
    int type{-1};
    bool aggregate{false};
    bool split{false};
};

class H264Packetizer : public RtpPacketizer
{
public:
    H264Packetizer();
    std::vector<RtpPacket> processFrame(const uint8_t* data, size_t size, uint32_t flags, uint32_t timestamp) override;

private:
    std::vector<RtpPacket> m_packets;
    std::vector<NaluInfo> m_nalus;

    ssize_t findStartCode(const uint8_t* data, const size_t size, const size_t offset, uint8_t& startCodeLength);
    std::vector<NaluInfo> getMultipleNalus(const uint8_t* data, const size_t size, const size_t packetSize);
    std::vector<NaluInfo> getSingleNalu(const uint8_t* data, const size_t size, const size_t packetSize, const H264_NAL_TYPES& type);

    std::vector<RtpPacket> getFuRtpPackets(const uint8_t* data, const size_t size,const uint32_t timestamp, const size_t packetSize, const std::vector<NaluInfo>& nalus);
    std::vector<RtpPacket> getSingleNalRtpPackets(const uint8_t* data, const size_t size, const uint32_t timestamp, const size_t packetSize, const std::vector<NaluInfo>& nalus);
    std::vector<RtpPacket> getAggregateRtpPackets(const uint8_t* data, const size_t size, const uint32_t timestamp, const size_t packetSize, const std::vector<NaluInfo>& nalus);


    std::vector<NaluInfo> getNalus(const uint8_t *data, size_t size, uint32_t flags);


};


#endif //MOBSTR_H264_PACKETIZER_HPP
