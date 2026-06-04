// stream_controller.hpp
#pragma once
#include <memory>
#include <string>
#include <atomic>
#include <thread>
#include <media/NdkMediaCodec.h>
#include "socket.hpp"
#include "packetizers/rtp_packetizer.hpp"

class StreamController {
public:
    StreamController(const std::string& ip, uint16_t port);
    ~StreamController();

    ANativeWindow* initializeEncoder(int32_t width, int32_t height);

    void startStreaming();
    void stopStreaming();

private:
    void packetHandlingLoop();

    Socket m_socket;
    std::unique_ptr<RtpPacketizer> m_packetizer;
    AMediaCodec* m_encoder{nullptr};

    std::thread m_workerThread;
    std::atomic<bool> m_isStreaming{false};
    int32_t m_width{640};
    int32_t m_height{480};
};