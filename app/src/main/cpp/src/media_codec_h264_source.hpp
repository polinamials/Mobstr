#pragma once

#include <FramedSource.hh>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <span>
#include <vector>

class MediaCodecH264Source final : public FramedSource
{
public:
    struct ParameterSets
    {
        std::vector<uint8_t> sps;
        std::vector<uint8_t> pps;
    };

    static MediaCodecH264Source *createNew(
        UsageEnvironment &environment, size_t maxNalSize);

    bool submitAccessUnit(
        std::span<const uint8_t> bytes, int64_t presentationTimeUs,
        bool codecConfig, bool keyFrame);
    void prepareForNewClient();
    ParameterSets parameterSets() const;
    bool lastNalEndsAccessUnit() const { return m_lastNalEndsAccessUnit; }
    uint64_t accessUnits() const { return m_accessUnits.load(); }
    uint64_t droppedAccessUnits() const { return m_droppedAccessUnits.load(); }

private:
    struct AccessUnit
    {
        std::vector<std::vector<uint8_t>> nals;
        timeval presentationTime{};
        size_t bytes{0};
        bool endsAccessUnit{false};
    };

    MediaCodecH264Source(UsageEnvironment &environment, size_t maxNalSize);
    ~MediaCodecH264Source() override;

    void doGetNextFrame() override;
    unsigned maxFrameSize() const override;
    void deliverFrame();
    timeval mapPresentationTime(int64_t presentationTimeUs, bool videoFrame);

    static void onDataAvailable(void *context);

    const size_t m_maxNalSize;
    const size_t m_maxQueuedBytes;
    TaskScheduler &m_scheduler;
    EventTriggerId m_dataTrigger{0};
    std::atomic<bool> m_triggerPending{false};
    mutable std::mutex m_mutex;
    std::deque<AccessUnit> m_queue;
    size_t m_queuedBytes{0};
    AccessUnit m_current;
    size_t m_currentNal{0};
    uint8_t m_nalLengthSize{0};
    std::vector<uint8_t> m_sps;
    std::vector<uint8_t> m_pps;
    bool m_lastNalEndsAccessUnit{false};
    int64_t m_basePtsUs{-1};
    int64_t m_baseWallUs{0};
    int64_t m_lastVideoPtsUs{-1};
    bool m_waitingForKeyFrame{false};
    std::atomic<uint64_t> m_accessUnits{0};
    std::atomic<uint64_t> m_droppedAccessUnits{0};
};
