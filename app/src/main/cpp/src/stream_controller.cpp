#include "stream_controller.hpp"
#include "packetizers/h264_packetizer.hpp"
#include <media/NdkMediaFormat.h>
#include <android/log.h>
#include <memory>

#define LOG_TAG "MOBSTR_STREAM_CONTROLLER"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

StreamController::StreamController(const std::string& ip, uint16_t port, size_t packetSize)
        : m_socket(ip, port) {
    // Assume H264 for now
    m_packetizer = std::make_unique<H264Packetizer>(packetSize);
}

StreamController::~StreamController() {
    stopStreaming();
}

ANativeWindow* StreamController::initializeEncoder(int32_t width, int32_t height) {
    m_width = width;
    m_height = height;

    m_encoder = AMediaCodec_createEncoderByType("video/avc");
    if (!m_encoder) {
        LOGE("Failed to create H.264 hardware encoder.");
        return nullptr;
    }

    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, m_width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, m_height);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, 0x7F000789);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, 2000000);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, 30);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_LATENCY, 1);

    media_status_t status = AMediaCodec_configure(m_encoder, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(format);

    if (status != AMEDIA_OK) {
        LOGE("Failed to configure hardware encoder parameters.");
        return nullptr;
    }

    // Get input surface
    ANativeWindow* inputSurface = nullptr;
    status = AMediaCodec_createInputSurface(m_encoder, &inputSurface);
    if (status != AMEDIA_OK || !inputSurface) {
        LOGE("Failed to create encoder input surface.");
        return nullptr;
    }

    // Start the encoder
    AMediaCodec_start(m_encoder);
    return inputSurface;
}

void StreamController::startStreaming() {
    if (m_isStreaming) return;

    m_isStreaming = true;
    // Start a background thread to encode the video
    m_workerThread = std::thread(&StreamController::packetHandlingLoop, this);
    LOGI("Streaming pipeline successfully started.");
}

void StreamController::stopStreaming() {
    if (!m_isStreaming) return;

    m_isStreaming = false;
    if (m_workerThread.joinable()) {
        m_workerThread.join();
    }

    if (m_encoder) {
        AMediaCodec_stop(m_encoder);
        AMediaCodec_delete(m_encoder);
        m_encoder = nullptr;
    }
    LOGI("Streaming pipeline stopped.");
}

void StreamController::packetHandlingLoop() {
    AMediaCodecBufferInfo bufferInfo;

    while (m_isStreaming) {
        ssize_t outBufferIdx = AMediaCodec_dequeueOutputBuffer(m_encoder, &bufferInfo, 10000);

        if (outBufferIdx >= 0) {
            size_t outBufferSize = 0;
            uint8_t* compressedData = AMediaCodec_getOutputBuffer(m_encoder, outBufferIdx, &outBufferSize);

            if (compressedData != nullptr && bufferInfo.size > 0) {
                uint8_t* naluPayload = compressedData + bufferInfo.offset;

                auto rtpTimestamp = static_cast<uint32_t>(bufferInfo.presentationTimeUs * 90 / 1000);

                std::vector<RtpPacket> packets = m_packetizer->processFrame(naluPayload, bufferInfo.size,bufferInfo.flags, rtpTimestamp);

                for (const auto& packet : packets) {
                    m_socket.pushData(packet.buffer.data(), packet.bufferSize);
                }
            }

            // Release buffer once we take the data out of it
            AMediaCodec_releaseOutputBuffer(m_encoder, outBufferIdx, false);
        }
    }
}