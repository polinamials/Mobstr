#pragma once

#include <android/native_window.h>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <media/NdkMediaCodec.h>
#include <memory>
#include <string>
#include <thread>

class Live555Server;

class StreamController {
public:
    StreamController(uint16_t rtspPort, size_t packetSize, int32_t bitrate);
    ~StreamController();

    ANativeWindow* initializeEncoder(int32_t width, int32_t height);
    void startStreaming();
    void stopStreaming();
    std::string diagnostics() const;

private:
    void codecLoop();
    void requestKeyFrame();

    const uint16_t m_rtspPort;
    const size_t m_packetSize;
    const int32_t m_bitrate;
    std::unique_ptr<Live555Server> m_server;
    AMediaCodec* m_encoder{nullptr};
    std::thread m_codecThread;
    std::atomic<bool> m_streaming{false};
};
