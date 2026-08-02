#pragma once

#include <cstdint>
#include <span>
#include <vector>

struct ParsedH264AccessUnit {
    std::vector<std::vector<uint8_t>> nals;
    uint8_t nalLengthSize{0};
};

ParsedH264AccessUnit parseH264AccessUnit(
    std::span<const uint8_t> data, uint8_t nalLengthSize, bool codecConfig);
