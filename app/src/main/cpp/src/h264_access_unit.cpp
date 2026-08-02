#include "h264_access_unit.hpp"

#include <array>

namespace {
size_t startCodeSize(std::span<const uint8_t> data, size_t offset) {
    if (offset + 4 <= data.size() && data[offset] == 0 && data[offset + 1] == 0 &&
        data[offset + 2] == 0 && data[offset + 3] == 1)
        return 4;
    if (offset + 3 <= data.size() && data[offset] == 0 && data[offset + 1] == 0 &&
        data[offset + 2] == 1)
        return 3;
    return 0;
}

void appendNal(
    ParsedH264AccessUnit& result, std::span<const uint8_t> nal,
    bool trimAnnexBTrailingZeros = false) {
    while (trimAnnexBTrailingZeros && !nal.empty() && nal.back() == 0)
        nal = nal.first(nal.size() - 1);
    if (!nal.empty())
        result.nals.emplace_back(nal.begin(), nal.end());
}

bool parseAvcConfiguration(
    std::span<const uint8_t> data, ParsedH264AccessUnit& result) {
    // ISO/IEC 14496-15 AVCDecoderConfigurationRecord (avcC).
    if (data.size() < 7 || data[0] != 1 || (data[4] & 0xfc) != 0xfc ||
        (data[5] & 0xe0) != 0xe0)
        return false;

    result.nalLengthSize = static_cast<uint8_t>((data[4] & 0x03) + 1);
    size_t offset = 6;
    const unsigned spsCount = data[5] & 0x1f;
    unsigned parameterSetCount = 0;

    auto readParameterSets = [&](unsigned count) {
        for (unsigned i = 0; i < count; ++i) {
            if (offset + 2 > data.size())
                return false;
            const size_t size = (size_t(data[offset]) << 8) | data[offset + 1];
            offset += 2;
            if (!size || size > data.size() - offset)
                return false;
            appendNal(result, data.subspan(offset, size));
            offset += size;
            ++parameterSetCount;
        }
        return true;
    };

    if (!readParameterSets(spsCount) || offset >= data.size())
        return false;
    const unsigned ppsCount = data[offset++];
    return readParameterSets(ppsCount) && parameterSetCount > 0;
}

bool parseAnnexB(std::span<const uint8_t> data, ParsedH264AccessUnit& result) {
    size_t offset = 0;
    while (offset < data.size() && !startCodeSize(data, offset))
        ++offset;
    if (offset == data.size())
        return false;

    while (offset < data.size()) {
        const size_t prefix = startCodeSize(data, offset);
        if (!prefix) {
            ++offset;
            continue;
        }

        const size_t begin = offset + prefix;
        size_t end = begin;
        while (end < data.size() && !startCodeSize(data, end))
            ++end;
        appendNal(result, data.subspan(begin, end - begin), true);
        offset = end;
    }
    return !result.nals.empty();
}

bool parseLengthPrefixed(
    std::span<const uint8_t> data, uint8_t lengthSize, ParsedH264AccessUnit& result) {
    if (lengthSize < 1 || lengthSize > 4)
        return false;

    size_t offset = 0;
    while (offset + lengthSize <= data.size()) {
        uint32_t size = 0;
        for (uint8_t i = 0; i < lengthSize; ++i)
            size = (size << 8) | data[offset++];
        if (!size || size > data.size() - offset)
            return false;
        result.nals.emplace_back(data.begin() + offset, data.begin() + offset + size);
        offset += size;
    }
    return offset == data.size() && !result.nals.empty();
}
}

ParsedH264AccessUnit parseH264AccessUnit(
    std::span<const uint8_t> data, uint8_t nalLengthSize, bool codecConfig) {
    ParsedH264AccessUnit result;
    result.nalLengthSize = nalLengthSize;
    if (data.empty())
        return result;

    if (codecConfig && parseAvcConfiguration(data, result))
        return result;

    result.nals.clear();
    if (parseAnnexB(data, result))
        return result;

    // Only avcC can select a one-byte length. Guessing it would mistake NAL
    // type bytes such as 0x06 (SEI) for a payload length.
    const std::array<uint8_t, 3> candidates{nalLengthSize, 4, 2};
    for (const uint8_t candidate : candidates) {
        if (!candidate)
            continue;
        result.nals.clear();
        if (parseLengthPrefixed(data, candidate, result)) {
            result.nalLengthSize = candidate;
            return result;
        }
    }

    result.nals = {{data.begin(), data.end()}};
    return result;
}
