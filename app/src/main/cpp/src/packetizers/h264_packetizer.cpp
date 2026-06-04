//
// Created by polina on 5/30/26.
//

#include <android/log.h>
#include "h264_packetizer.hpp"
#include <algorithm>
#include <random>
#include <arpa/inet.h>

#define LOG_TAG "MOBSTR_H264_PACKETIZER"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

H264Packetizer::H264Packetizer()
{
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<uint32_t> dis(1, 0xFFFFFFFF);

    m_ssrc = dis(gen);
    m_sequenceNumber = dis(gen);
}


std::vector<RtpPacket> H264Packetizer::processFrame(const uint8_t *data, size_t size, uint32_t flags, uint32_t timestamp) {

    m_nalus = getNalus(data, size, flags);
    std::vector<RtpPacket> outputPackets;

    if (m_nalus.empty()) {
        return outputPackets;
    }

    if (m_nalus.size() > 1)
    {
        // Do aggregation
        std::vector<RtpPacket> aggrPackets = getAggregateRtpPackets(data, size, timestamp, DEFAULT_MTU, m_nalus);
        outputPackets.insert(outputPackets.end(), aggrPackets.begin(), aggrPackets.end());

        // Handle packets that shouldn't be aggregated
        std::vector<NaluInfo> remainingNalus;
        for (const auto& nal : m_nalus) {
            if (!nal.aggregate) {
                remainingNalus.push_back(nal);
            }
        }

        if (!remainingNalus.empty()) {
            // Loop non-aggregatable NALs and either split them into FUs or send as single packet
            for (const auto& nal : remainingNalus) {
                std::vector<NaluInfo> standaloneWrapper = { nal };
                if (nal.split) {
                    std::vector<RtpPacket> fuPackets = getFuRtpPackets(data, size, timestamp, DEFAULT_MTU, standaloneWrapper);
                    outputPackets.insert(outputPackets.end(), fuPackets.begin(), fuPackets.end());
                } else {
                    std::vector<RtpPacket> singlePackets = getSingleNalRtpPackets(data, size, timestamp, DEFAULT_MTU, standaloneWrapper);
                    outputPackets.insert(outputPackets.end(), singlePackets.begin(), singlePackets.end());
                }
            }
        }
    }
    else
    {
        // Process single NAL: either send whole or split into FUs
        if (m_nalus[0].split) {
            outputPackets = getFuRtpPackets(data, size, timestamp, DEFAULT_MTU, m_nalus);
        } else {
            outputPackets = getSingleNalRtpPackets(data, size, timestamp, DEFAULT_MTU, m_nalus);
        }
    }

    return outputPackets;
}

#include "h264_packetizer.hpp"
#include <algorithm>

std::vector<NaluInfo> H264Packetizer::getMultipleNalus(const uint8_t* data, const size_t size, const size_t packetSize)
{
    // Reference: uvgRTP scl() function
    uint8_t startCodeLength{0};
    ssize_t offset = findStartCode(data, size, 0, startCodeLength);
    std::vector<NaluInfo> nals;

    while(offset > -1)
    {
        NaluInfo naluInfo;
        naluInfo.startPos = offset;
        naluInfo.startCodeLength = startCodeLength;
        naluInfo.type = data[offset + startCodeLength] & 0x1F;
        naluInfo.split = false;
        naluInfo.aggregate = false;

        nals.push_back(naluInfo);
        offset = findStartCode(data, size, offset + startCodeLength, startCodeLength);
    }

    for (size_t i = 0; i < nals.size(); ++i)
    {
        if (nals.size() > i + 1)
        {
            nals.at(i).size = nals[i + 1].startPos - nals[i].startPos;
        }
        else
        {
            nals.at(i).size = size - nals[i].startPos;
        }

        if (nals.at(i).size > packetSize) {
            nals.at(i).split = true;
        }
    }

    size_t aggregateSize = 0;
    for (size_t i = 0; i < nals.size(); ++i) {
        if (!nals[i].split && (aggregateSize + nals[i].size + sizeof(uint16_t) <= packetSize)) {
            aggregateSize += nals[i].size + sizeof(uint16_t);
            nals[i].aggregate = true;
        } else {
            break;
        }
    }

    return nals;
}

std::vector<NaluInfo> H264Packetizer::getSingleNalu(const uint8_t* data, const size_t size, const size_t packetSize, const H264_NAL_TYPES& type)
{
    NaluInfo naluInfo;
    naluInfo.type = type;

    uint8_t startCodeLength{0};
    naluInfo.startPos = findStartCode(data, size, 0, startCodeLength);

    naluInfo.startCodeLength = startCodeLength;
    naluInfo.size = size;
    naluInfo.aggregate = false;
    naluInfo.split = (size > packetSize);

    std::vector<NaluInfo> nals;
    nals.push_back(naluInfo);
    return nals;
}

std::vector<NaluInfo> H264Packetizer::getNalus(const uint8_t *data, size_t size, uint32_t flags) {

    switch(flags)
    {
        case 1: return getSingleNalu(data, size, DEFAULT_MTU, H264_NAL_TYPES::IDR); break;
        case 0: return getSingleNalu(data, size, DEFAULT_MTU, H264_NAL_TYPES::NON_IDR); break;
        case 4: return getSingleNalu(data, size, DEFAULT_MTU, H264_NAL_TYPES::END_OF_STREAM);
        default: return getMultipleNalus(data, size, DEFAULT_MTU); break;
    }
};

ssize_t H264Packetizer::findStartCode(const uint8_t* data, const size_t size, const size_t offset, uint8_t& startCodeLength)
{
    // Same idea as uvgRTP's find_h26x_start_code() but using std::search
    if (offset + 3 > size || data == nullptr) {
        startCodeLength = 0;
        return -1;
    }

    const uint8_t fourByte[] = {0x00, 0x00, 0x00, 0x01};
    const uint8_t threeByte[] = {0x00, 0x00, 0x01};

    const uint8_t* startPos = data + offset;
    const uint8_t* endPos = data + size;

    const uint8_t* found = std::search(startPos, endPos, fourByte, fourByte + 4);

    if (found != endPos) {
        startCodeLength = 4;
        return std::distance(data, found);
    }

    found = std::search(startPos, endPos, threeByte, threeByte + 3);
    if (found != endPos) {
        startCodeLength = 3;
        return std::distance(data, found);
    }

    return -1;
}

std::vector<RtpPacket> H264Packetizer::getFuRtpPackets(const uint8_t* data, const size_t size, const uint32_t timestamp, const size_t packetSize, const std::vector<NaluInfo>& nalus)
{
    std::vector<RtpPacket> allPackets;

    if (!data || size == 0 || nalus.empty()) {
        return allPackets;
    }

    // 12 for the RTP header, 2 for the FU header
    size_t maxFuPayloadSize = packetSize - 12 - 2;

    for (const auto& nal : nalus)
    {
        if (!nal.split) {
            continue;
        }

        // Get the NAL's header
        size_t headerPos = nal.startPos + nal.startCodeLength;
        uint8_t originalNalHeader = data[headerPos];

        uint8_t nriBits = originalNalHeader & 0x60;
        uint8_t fuIndicator = nriBits | 28;

        size_t payloadStartOffset = headerPos + 1;
        // Total data size  - start code length - 1-byte original NAL header
        size_t totalPayloadRemaining = nal.size - nal.startCodeLength - 1;

        size_t currentOffset = payloadStartOffset;
        bool isFirstFragment = true;

        while (totalPayloadRemaining > 0)
        {
            size_t currentChunkSize = (totalPayloadRemaining > maxFuPayloadSize) ? maxFuPayloadSize : totalPayloadRemaining;
            totalPayloadRemaining -= currentChunkSize;

            bool isLastFragment = (totalPayloadRemaining == 0);

            uint8_t fuHeader = 0x00;
            if (isFirstFragment) {
                fuHeader |= 0x80;
                isFirstFragment = false;
            } else if (isLastFragment) {
                fuHeader |= 0x40;
            }
            fuHeader |= (nal.type & 0x1F);

            RtpPacket packet;
            packet.bufferSize = 12 + 2 + currentChunkSize;
            packet.buffer.resize(packet.bufferSize);

            // TODO: the last fragment might actually need padding. Should double check this
            std::array<uint8_t, 12> rtpHeader = getRtpHeader(timestamp, false);

            if (isLastFragment) {
                rtpHeader[1] |= 0x80;
            }

            std::memcpy(packet.buffer.data(), rtpHeader.data(), 12);

            packet.buffer[12] = fuIndicator;
            packet.buffer[13] = fuHeader;

            std::memcpy(packet.buffer.data() + 14, data + currentOffset, currentChunkSize);

            currentOffset += currentChunkSize;
            allPackets.push_back(packet);
        }
    }

    return allPackets;
}


std::vector<RtpPacket> H264Packetizer::getSingleNalRtpPackets(const uint8_t* data, const size_t size, const uint32_t timestamp, const size_t packetSize, const std::vector<NaluInfo>& nalus)
{
    std::vector<RtpPacket> allPackets;

    if (!data || size == 0 || nalus.empty()) {
        return allPackets;
    }

    for (size_t i = 0; i < nalus.size(); ++i)
    {
        const auto& nal = nalus[i];

        if (nal.split || nal.aggregate) {
            continue;
        }

        size_t headerPos = nal.startPos + nal.startCodeLength;
        size_t singleNalSize = nal.size - nal.startCodeLength;
        size_t actualDataSize = 12 + singleNalSize;

        bool needsPadding = (actualDataSize < packetSize);
        size_t paddingBytes = needsPadding ? (packetSize - actualDataSize) : 0;

        RtpPacket packet;
        packet.bufferSize = needsPadding ? packetSize : actualDataSize;
        packet.buffer.resize(packet.bufferSize);

        std::array<uint8_t, 12> rtpHeader = getRtpHeader(timestamp, needsPadding);

        if (i == nalus.size() - 1) {
            rtpHeader[1] |= 0x80;
        }

        // Copy RTP header into final buffer
        std::memcpy(packet.buffer.data(), rtpHeader.data(), 12);
        // Copy NAL payload into final buffer
        std::memcpy(packet.buffer.data() + 12, data + headerPos, singleNalSize);

        if (needsPadding) {
            std::memset(packet.buffer.data() + actualDataSize, 0, paddingBytes);

            // The final byte must be the total padding count
            packet.buffer[packetSize - 1] = static_cast<uint8_t>(paddingBytes);
        }

        allPackets.push_back(packet);
    }

    return allPackets;
}

std::vector<RtpPacket> H264Packetizer::getAggregateRtpPackets(const uint8_t* data, const size_t size, const uint32_t timestamp, const size_t packetSize, const std::vector<NaluInfo>& nalus)
{
    std::vector<RtpPacket> allPackets;

    std::vector<NaluInfo> aggrUnits;
    uint8_t highestNri = 0;

    for (const auto& nal : nalus) {
        if (nal.aggregate) {
            aggrUnits.push_back(nal);

            uint8_t headerByte = data[nal.startPos + nal.startCodeLength];
            uint8_t nri = headerByte & 0x60;
            if (nri > highestNri) {
                highestNri = nri;
            }
        }
    }

    if (aggrUnits.empty()) {
        return allPackets;
    }

    // 12-byte RTP header + 1 byte STAP-A Indicator
    size_t actualDataSize = 12 + 1;
    for (const auto& nal : aggrUnits) {
        size_t singleNalSize = nal.size - nal.startCodeLength;
        actualDataSize += 2 + singleNalSize; // 2-byte size prefix + NAL data
    }

    bool needsPadding = (actualDataSize < packetSize);
    size_t paddingBytes = needsPadding ? (packetSize - actualDataSize) : 0;

    RtpPacket packet;
    packet.bufferSize = needsPadding ? packetSize : actualDataSize;
    packet.buffer.resize(packet.bufferSize);

    std::array<uint8_t, 12> rtpHeader = getRtpHeader(timestamp, needsPadding);
    std::memcpy(packet.buffer.data(), rtpHeader.data(), 12);

    packet.buffer[12] = highestNri | 24;

    size_t currentOffset = 12 + 1;
    for (const auto& nal : aggrUnits) {
        size_t headerPos = nal.startPos + nal.startCodeLength;
        size_t singleNalSize = nal.size - nal.startCodeLength;

        uint16_t networkSize = htons(static_cast<uint16_t>(singleNalSize));

        std::memcpy(packet.buffer.data() + currentOffset, &networkSize, 2);
        currentOffset += 2;

        std::memcpy(packet.buffer.data() + currentOffset, data + headerPos, singleNalSize);
        currentOffset += singleNalSize;
    }

    if (needsPadding) {
        std::memset(packet.buffer.data() + currentOffset, 0, paddingBytes);
        packet.buffer[packetSize - 1] = static_cast<uint8_t>(paddingBytes);
    }

    allPackets.push_back(packet);
    return allPackets;
}