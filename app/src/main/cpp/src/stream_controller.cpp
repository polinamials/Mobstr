#include "stream_controller.hpp"

#include "live555_server.hpp"

#include <algorithm>
#include <android/log.h>
#include <media/NdkMediaFormat.h>
#include <sstream>
#include <span>

namespace
{
    constexpr char logTag[] = "MOBSTR_STREAM";
}

StreamController::StreamController(
    uint16_t rtspPort, size_t packetSize, int32_t bitrate)
    : m_rtspPort(rtspPort), m_packetSize(packetSize), m_bitrate(bitrate) {}

StreamController::~StreamController()
{
    stopStreaming();
}

ANativeWindow *StreamController::initializeEncoder(int32_t width, int32_t height)
{
    const size_t rawFrameBytes = size_t(width) * size_t(height) * 3 / 2;
    const size_t maxNalSize = std::clamp<size_t>(rawFrameBytes, 64 * 1024, 32 * 1024 * 1024);

    // Start the RTSP server first so port binding failures abort immediately
    // without spinning up the hardware encoder.
    m_server = std::make_unique<Live555Server>(m_rtspPort, m_packetSize, m_bitrate);
    if (!m_server->start(maxNalSize))
    {
        __android_log_print(
            ANDROID_LOG_ERROR, logTag, "Failed to start LIVE555 RTSP server on port %u",
            m_rtspPort);
        m_server.reset();
        return nullptr;
    }

    m_encoder = AMediaCodec_createEncoderByType("video/avc");
    if (!m_encoder)
    {
        m_server.reset();
        return nullptr;
    }

    AMediaFormat *format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, 0x7f000789);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, m_bitrate);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, 30);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BITRATE_MODE, 2);
    AMediaFormat_setInt32(format, "priority", 0);
    AMediaFormat_setInt32(format, "max-bframes", 0);

    const media_status_t configured = AMediaCodec_configure(
        m_encoder, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(format);
    if (configured != AMEDIA_OK)
    {
        m_server.reset();
        AMediaCodec_delete(m_encoder);
        m_encoder = nullptr;
        return nullptr;
    }

    ANativeWindow *surface = nullptr;
    if (AMediaCodec_createInputSurface(m_encoder, &surface) != AMEDIA_OK || !surface)
    {
        m_server.reset();
        AMediaCodec_stop(m_encoder);
        AMediaCodec_delete(m_encoder);
        m_encoder = nullptr;
        return nullptr;
    }

    if (AMediaCodec_start(m_encoder) != AMEDIA_OK)
    {
        m_server.reset();
        ANativeWindow_release(surface);
        AMediaCodec_stop(m_encoder);
        AMediaCodec_delete(m_encoder);
        m_encoder = nullptr;
        return nullptr;
    }

    return surface;
}

void StreamController::startStreaming()
{
    if (!m_encoder || !m_server || m_streaming.exchange(true))
        return;
    m_codecThread = std::thread(&StreamController::codecLoop, this);
}

void StreamController::codecLoop()
{
    AMediaCodecBufferInfo info{};
    while (m_streaming)
    {
        if (m_server->takeKeyFrameRequest())
            requestKeyFrame();

        const ssize_t index = AMediaCodec_dequeueOutputBuffer(m_encoder, &info, 10'000);
        if (index < 0)
            continue;

        size_t capacity = 0;
        uint8_t *buffer = AMediaCodec_getOutputBuffer(m_encoder, index, &capacity);
        if (buffer && info.offset >= 0 && info.size > 0 &&
            size_t(info.offset) <= capacity && size_t(info.size) <= capacity - size_t(info.offset))
        {
            m_server->submitAccessUnit(
                std::span(buffer + info.offset, size_t(info.size)),
                info.presentationTimeUs,
                info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG,
                info.flags & AMEDIACODEC_BUFFER_FLAG_KEY_FRAME);
        }
        AMediaCodec_releaseOutputBuffer(m_encoder, index, false);
    }
}

void StreamController::requestKeyFrame()
{
    AMediaFormat *parameters = AMediaFormat_new();
    AMediaFormat_setInt32(parameters, "request-sync", 0);
    AMediaCodec_setParameters(m_encoder, parameters);
    AMediaFormat_delete(parameters);
}

void StreamController::stopStreaming()
{
    m_streaming = false;
    if (m_codecThread.joinable())
        m_codecThread.join();

    if (m_server)
    {
        m_server->stop();
        m_server.reset();
    }
    if (m_encoder)
    {
        AMediaCodec_stop(m_encoder);
        AMediaCodec_delete(m_encoder);
        m_encoder = nullptr;
    }
}

std::string StreamController::diagnostics() const
{
    if (!m_server)
        return "Stream stopped";

    const uint32_t clients = m_server->clientCount();
    std::ostringstream out;
    out << "RTSP port: " << m_server->port() << '\n'
        << "Client: ";
    if (!clients)
        out << "waiting";
    else if (clients == 1)
        out << "connected";
    else
        out << clients << " connected";
    out << '\n'
        << "Encoded access units: " << m_server->accessUnits() << '\n'
        << "RTP packets sent: " << m_server->packetsSent() << '\n'
        << "Sender packet drops: " << m_server->sendErrors() << '\n'
        << "Receiver-reported loss: " << (m_server->receiverLossPermille() / 10.0)
        << "% (" << m_server->receiverPacketsLost() << " packets)";
    return out.str();
}
