#pragma once

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <span>
#include <thread>

class MediaCodecH264Source;
class TaskScheduler;

class Live555Server {
public:
    Live555Server(uint16_t port, size_t packetSize, int32_t bitrate);
    ~Live555Server();

    bool start(size_t maxNalSize);
    void stop();
    void submitAccessUnit(
        std::span<const uint8_t> bytes, int64_t presentationTimeUs,
        bool codecConfig, bool keyFrame);
    bool takeKeyFrameRequest() { return m_keyFrameRequested.exchange(false); }

    uint16_t port() const { return m_port; }
    uint64_t packetsSent() const { return m_packetsSent.load(); }
    uint64_t sendErrors() const { return m_sendErrors.load(); }
    uint64_t accessUnits() const;
    uint32_t clientCount() const { return m_clientCount.load(); }
    uint32_t receiverLossPermille() const { return m_receiverLossPermille.load(); }
    uint64_t receiverPacketsLost() const { return m_receiverPacketsLost.load(); }

private:
    void run(size_t maxNalSize);
    static void stopEventLoop(void* context);

    const uint16_t m_port;
    const size_t m_packetSize;
    const int32_t m_bitrate;
    std::thread m_thread;
    std::mutex m_startMutex;
    std::condition_variable m_startReady;
    bool m_ready{false};
    bool m_started{false};
    std::atomic<bool> m_stopRequested{false};
    std::atomic<TaskScheduler*> m_scheduler{nullptr};
    std::atomic<uint32_t> m_stopTrigger{0};
    std::atomic<MediaCodecH264Source*> m_source{nullptr};
    std::atomic<bool> m_keyFrameRequested{false};
    std::atomic<uint64_t> m_accessUnits{0};
    std::atomic<uint64_t> m_packetsSent{0};
    std::atomic<uint64_t> m_sendErrors{0};
    std::atomic<uint32_t> m_clientCount{0};
    std::atomic<uint32_t> m_receiverLossPermille{0};
    std::atomic<uint64_t> m_receiverPacketsLost{0};
    std::atomic_char m_watchVariable{0};
};
