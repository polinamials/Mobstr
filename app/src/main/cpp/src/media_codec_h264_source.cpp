#include "media_codec_h264_source.hpp"

#include "h264_access_unit.hpp"

#include <algorithm>
#include <cstring>
#include <limits>
#include <ranges>

namespace
{
    constexpr size_t maxQueuedAccessUnits = 3;

    uint8_t nalType(const std::vector<uint8_t> &nal)
    {
        return nal.empty() ? 0 : nal[0] & 0x1f;
    }
}

MediaCodecH264Source *MediaCodecH264Source::createNew(
    UsageEnvironment &environment, size_t maxNalSize)
{
    return new MediaCodecH264Source(environment, maxNalSize);
}

MediaCodecH264Source::MediaCodecH264Source(
    UsageEnvironment &environment, size_t maxNalSize)
    : FramedSource(environment),
      m_maxNalSize(std::max<size_t>(maxNalSize, 64 * 1024)),
      m_maxQueuedBytes(10 * 1024 * 1024),
      m_scheduler(environment.taskScheduler())
{
    m_dataTrigger = m_scheduler.createEventTrigger(onDataAvailable);
}

MediaCodecH264Source::~MediaCodecH264Source()
{
    if (m_dataTrigger)
        m_scheduler.deleteEventTrigger(m_dataTrigger);
}

bool MediaCodecH264Source::submitAccessUnit(
    std::span<const uint8_t> bytes, int64_t presentationTimeUs,
    bool codecConfig, bool keyFrame)
{
    ParsedH264AccessUnit parsed =
        parseH264AccessUnit(bytes, m_nalLengthSize, codecConfig);
    if (parsed.nals.empty())
        return false;

    AccessUnit unit;
    bool hasVideo = false;
    {
        std::lock_guard lock(m_mutex);
        ++m_accessUnits;
        m_nalLengthSize = parsed.nalLengthSize;
        for (const auto &nal : parsed.nals)
        {
            const uint8_t type = nalType(nal);
            if (type == 7)
                m_sps = nal;
            else if (type == 8)
                m_pps = nal;
            hasVideo |= type >= 1 && type <= 5;
            keyFrame |= type == 5;
        }

        if (hasVideo && m_waitingForKeyFrame && !keyFrame)
        {
            ++m_droppedAccessUnits;
            return false;
        }
        if (keyFrame)
            m_waitingForKeyFrame = false;

        // Parameter sets in-band before each IDR make late joins and recovery
        // independent of whether a receiver retained the SDP configuration.
        if (keyFrame)
        {
            const bool hasSps = std::ranges::any_of(parsed.nals, [](const auto &nal)
                                                    { return nalType(nal) == 7; });
            const bool hasPps = std::ranges::any_of(parsed.nals, [](const auto &nal)
                                                    { return nalType(nal) == 8; });
            if (!hasPps && !m_pps.empty())
                parsed.nals.insert(parsed.nals.begin(), m_pps);
            if (!hasSps && !m_sps.empty())
                parsed.nals.insert(parsed.nals.begin(), m_sps);
        }

        unit.nals = std::move(parsed.nals);
        unit.presentationTime = mapPresentationTime(presentationTimeUs, hasVideo);
        unit.endsAccessUnit = hasVideo;
        for (const auto &nal : unit.nals)
            unit.bytes += nal.size();

        const bool full = m_queue.size() >= maxQueuedAccessUnits ||
                          m_queuedBytes + unit.bytes > m_maxQueuedBytes;
        if (full && hasVideo)
        {
            m_droppedAccessUnits += m_queue.size();
            m_queue.clear();
            m_queuedBytes = 0;
            if (!keyFrame)
            {
                ++m_droppedAccessUnits;
                m_waitingForKeyFrame = true;
                return true;
            }
        }
        else if (full)
        {
            while (!m_queue.empty())
            {
                m_queuedBytes -= m_queue.front().bytes;
                m_queue.pop_front();
                ++m_droppedAccessUnits;
            }
        }
        m_queuedBytes += unit.bytes;
        m_queue.emplace_back(std::move(unit));
    }

    // triggerEvent() is LIVE555's only cross-thread API. Coalescing signals
    // avoids triggering the same event again before its callback has run.
    if (m_dataTrigger && !m_triggerPending.exchange(true))
        m_scheduler.triggerEvent(m_dataTrigger, this);
    return false;
}

void MediaCodecH264Source::prepareForNewClient()
{
    std::lock_guard lock(m_mutex);
    m_queue.clear();
    m_queuedBytes = 0;
    m_current = {};
    m_currentNal = 0;
    m_waitingForKeyFrame = true;
}

MediaCodecH264Source::ParameterSets MediaCodecH264Source::parameterSets() const
{
    std::lock_guard lock(m_mutex);
    return {m_sps, m_pps};
}

void MediaCodecH264Source::doGetNextFrame()
{
    deliverFrame();
}

unsigned MediaCodecH264Source::maxFrameSize() const
{
    return static_cast<unsigned>(
        std::min<size_t>(m_maxNalSize, std::numeric_limits<unsigned>::max()));
}

void MediaCodecH264Source::onDataAvailable(void *context)
{
    auto *source = static_cast<MediaCodecH264Source *>(context);
    source->m_triggerPending = false;
    source->deliverFrame();
}

void MediaCodecH264Source::deliverFrame()
{
    if (!isCurrentlyAwaitingData())
        return;

    if (m_currentNal >= m_current.nals.size())
    {
        std::lock_guard lock(m_mutex);
        if (m_queue.empty())
            return;
        m_current = std::move(m_queue.front());
        m_queue.pop_front();
        m_queuedBytes -= m_current.bytes;
        m_currentNal = 0;
    }

    const auto &nal = m_current.nals[m_currentNal];
    fFrameSize = static_cast<unsigned>(std::min<size_t>(nal.size(), fMaxSize));
    fNumTruncatedBytes = static_cast<unsigned>(nal.size() - fFrameSize);
    std::memcpy(fTo, nal.data(), fFrameSize);
    fPresentationTime = m_current.presentationTime;
    fDurationInMicroseconds = 0;
    m_lastNalEndsAccessUnit =
        m_current.endsAccessUnit && m_currentNal + 1 == m_current.nals.size();
    ++m_currentNal;
    FramedSource::afterGetting(this);
}

timeval MediaCodecH264Source::mapPresentationTime(
    int64_t presentationTimeUs, bool videoFrame)
{
    if (videoFrame && presentationTimeUs <= m_lastVideoPtsUs)
        presentationTimeUs = m_lastVideoPtsUs + 1;
    if (videoFrame)
        m_lastVideoPtsUs = presentationTimeUs;

    timeval now{};
    gettimeofday(&now, nullptr);
    const int64_t wallUs = int64_t(now.tv_sec) * 1'000'000 + now.tv_usec;
    const int64_t deltaUs = presentationTimeUs - m_basePtsUs;
    if (m_basePtsUs < 0 || deltaUs < -1'000'000 || deltaUs > 86'400'000'000LL)
    {
        m_basePtsUs = presentationTimeUs;
        m_baseWallUs = wallUs;
    }

    const int64_t mappedUs = m_baseWallUs + presentationTimeUs - m_basePtsUs;
    return {
        static_cast<time_t>(mappedUs / 1'000'000),
        static_cast<suseconds_t>(mappedUs % 1'000'000),
    };
}
