#include "live555_server.hpp"

#include "media_codec_h264_source.hpp"

#include <BasicUsageEnvironment.hh>
#include <GroupsockHelper.hh>
#include <H264VideoRTPSink.hh>
#include <H264VideoStreamDiscreteFramer.hh>
#include <OnDemandServerMediaSubsession.hh>
#include <RTSPServer.hh>
#include <StreamReplicator.hh>

#include <algorithm>
#include <cstring>
#include <functional>
#include <string_view>
#include <unordered_set>

namespace
{
    constexpr int64_t statsIntervalUs = 250'000;
    constexpr int64_t auxSdpCheckIntervalUs = 10'000;
    constexpr unsigned auxSdpMaxChecks = 500;

    class MobstrRTSPClientConnection final : public RTSPServer::RTSPClientConnection
    {
    public:
        MobstrRTSPClientConnection(
            RTSPServer &server, int socket, const sockaddr_storage &address)
            : RTSPClientConnection(server, socket, address) {}

    private:
        void handleRequestBytes(int newBytesRead) override
        {
            if (newBytesRead > 0)
                normalizeUdpTransport(newBytesRead);
            RTSPClientConnection::handleRequestBytes(newBytesRead);
        }

        void normalizeUdpTransport(int &newBytesRead)
        {
            constexpr std::string_view token = "Transport: RTP/AVP/UDP";
            constexpr std::string_view upstreamToken = "Transport: RTP/AVP";
            constexpr size_t removedBytes = token.size() - upstreamToken.size();

            size_t total = fRequestBytesAlreadySeen + static_cast<size_t>(newBytesRead);
            auto *begin = reinterpret_cast<char *>(fRequestBuffer);
            auto *match = std::search(begin, begin + total, token.begin(), token.end());
            if (match == begin + total)
                return;

            // Some FFmpeg builds spell UDP as RTP/AVP/UDP. Normalize that alias
            // in Mobstr's adapter before upstream LIVE555 parses the request.
            const size_t eraseAt = size_t(match - begin) + upstreamToken.size();
            std::memmove(begin + eraseAt, begin + eraseAt + removedBytes,
                         total - eraseAt - removedBytes);
            const size_t removedFromOldData = eraseAt < fRequestBytesAlreadySeen
                                                  ? std::min(removedBytes,
                                                             size_t(fRequestBytesAlreadySeen) - eraseAt)
                                                  : 0;
            fRequestBytesAlreadySeen -= static_cast<unsigned>(removedFromOldData);
            total -= removedBytes;
            newBytesRead = static_cast<int>(total - fRequestBytesAlreadySeen);
        }
    };

    class MobstrRTSPServer final : public RTSPServer
    {
    public:
        static MobstrRTSPServer *createNew(
            UsageEnvironment &environment, Port port, unsigned reclamationSeconds)
        {
            const int ipv4 = setUpOurSocket(environment, port, AF_INET);
            const int ipv6 = setUpOurSocket(environment, port, AF_INET6);
            if (ipv4 < 0 && ipv6 < 0)
                return nullptr;

            return new MobstrRTSPServer(
                environment, ipv4, ipv6, port, reclamationSeconds);
        }

    private:
        MobstrRTSPServer(
            UsageEnvironment &environment, int ipv4, int ipv6, Port port,
            unsigned reclamationSeconds)
            : RTSPServer(environment, ipv4, ipv6, port, nullptr, reclamationSeconds) {}

        ClientConnection *createNewClientConnection(
            int socket, const sockaddr_storage &address) override
        {
            return new MobstrRTSPClientConnection(*this, socket, address);
        }
    };

    class MediaCodecH264Framer final : public H264VideoStreamDiscreteFramer
    {
    public:
        static MediaCodecH264Framer *createNew(
            UsageEnvironment &environment, FramedSource *input, MediaCodecH264Source &source)
        {
            return new MediaCodecH264Framer(environment, input, source);
        }

    private:
        MediaCodecH264Framer(
            UsageEnvironment &environment, FramedSource *input, MediaCodecH264Source &source)
            : H264VideoStreamDiscreteFramer(environment, input, False, False),
              m_source(source) {}

        Boolean nalUnitEndsAccessUnit(u_int8_t) override
        {
            // LIVE555 otherwise assumes every VCL NAL ends a picture. MediaCodec
            // can emit several NALs for one access unit, so preserve its boundary.
            return m_source.lastNalEndsAccessUnit() ? True : False;
        }

        MediaCodecH264Source &m_source;
    };

    class MobstrH264VideoRtpSink final : public H264VideoRTPSink
    {
    public:
        static MobstrH264VideoRtpSink *createNew(
            UsageEnvironment &environment, Groupsock *socket, unsigned char payloadType,
            const MediaCodecH264Source::ParameterSets &parameters)
        {
            return new MobstrH264VideoRtpSink(
                environment, socket, payloadType, parameters.sps.data(), parameters.sps.size(),
                parameters.pps.data(), parameters.pps.size());
        }

        uint64_t packetsSent() const { return fPacketCount; }

    private:
        MobstrH264VideoRtpSink(
            UsageEnvironment &environment, Groupsock *socket, unsigned char payloadType,
            const uint8_t *sps, size_t spsSize, const uint8_t *pps, size_t ppsSize)
            : H264VideoRTPSink(
                  environment, socket, payloadType,
                  spsSize ? sps : nullptr, static_cast<unsigned>(spsSize),
                  ppsSize ? pps : nullptr, static_cast<unsigned>(ppsSize)) {}
    };

    class LiveH264Subsession final : public OnDemandServerMediaSubsession
    {
    public:
        static LiveH264Subsession *createNew(
            UsageEnvironment &environment, StreamReplicator &replicator,
            MediaCodecH264Source &source, unsigned bitrateKbps, size_t packetSize,
            std::function<void()> requestKeyFrame, std::atomic<uint64_t> &packetsSent,
            std::atomic<uint64_t> &sendErrors, std::atomic<uint32_t> &clientCount,
            std::atomic<uint32_t> &receiverLossPermille,
            std::atomic<uint64_t> &receiverPacketsLost)
        {
            return new LiveH264Subsession(
                environment, replicator, source, bitrateKbps, packetSize,
                std::move(requestKeyFrame), packetsSent, sendErrors, clientCount,
                receiverLossPermille, receiverPacketsLost);
        }

    private:
        LiveH264Subsession(
            UsageEnvironment &environment, StreamReplicator &replicator,
            MediaCodecH264Source &source, unsigned bitrateKbps, size_t packetSize,
            std::function<void()> requestKeyFrame, std::atomic<uint64_t> &packetsSent,
            std::atomic<uint64_t> &sendErrors, std::atomic<uint32_t> &clientCount,
            std::atomic<uint32_t> &receiverLossPermille,
            std::atomic<uint64_t> &receiverPacketsLost)
            : OnDemandServerMediaSubsession(environment, True),
              m_replicator(replicator),
              m_source(source),
              m_bitrateKbps(bitrateKbps),
              m_packetSize(packetSize),
              m_requestKeyFrame(std::move(requestKeyFrame)),
              m_packetsSent(packetsSent),
              m_sendErrors(sendErrors),
              m_clientCount(clientCount),
              m_receiverLossPermille(receiverLossPermille),
              m_receiverPacketsLost(receiverPacketsLost)
        {
            scheduleStats();
        }

        ~LiveH264Subsession() override
        {
            envir().taskScheduler().unscheduleDelayedTask(m_statsTask);
            envir().taskScheduler().unscheduleDelayedTask(nextTask());
            delete[] m_auxSdpLine;
            m_clientCount = 0;
        }

        FramedSource *createNewStreamSource(unsigned, unsigned &estimatedBitrate) override
        {
            estimatedBitrate = m_bitrateKbps;
            return MediaCodecH264Framer::createNew(
                envir(), m_replicator.createStreamReplica(), m_source);
        }

        RTPSink *createNewRTPSink(
            Groupsock *socket, unsigned char payloadType, FramedSource *) override
        {
            auto *sink = MobstrH264VideoRtpSink::createNew(
                envir(), socket, payloadType, m_source.parameterSets());
            const unsigned packetSize = static_cast<unsigned>(m_packetSize);
            sink->setPacketSizes(std::min(1000u, packetSize), packetSize);
            sink->estimatedBitrate() = m_bitrateKbps;

            // This OS-level buffer handles the burst traffic from I-frames
            const unsigned sendBufferSize = std::max(256u * 1024, m_bitrateKbps * 125 / 4);
            increaseSendBufferTo(envir(), socket->socketNum(), sendBufferSize);

            sink->setOnSendErrorFunc(onSendError, &m_sendErrors);
            return sink;
        }

        Groupsock *createGroupsock(
            const sockaddr_storage &address, Port port) override
        {
            return new Groupsock(envir(), address, port, 255);
        }

        char const *getAuxSDPLine(RTPSink *sink, FramedSource *source) override
        {
            if (m_auxSdpLine)
                return m_auxSdpLine;
            if (const char *line = sink->auxSDPLine())
            {
                m_auxSdpLine = strDup(line);
                return m_auxSdpLine;
            }

            // If DESCRIBE arrives before MediaCodec emits SPS/PPS, briefly run the
            // dummy sink so LIVE555 can learn them from the discrete framer.
            m_auxDone = 0;
            m_auxChecks = 0;
            m_dummySink = sink;
            m_dummySink->startPlaying(*source, onDummyFinished, this);
            checkAuxSdp(this);
            envir().taskScheduler().doEventLoop(&m_auxDone);
            m_dummySink = nullptr;
            return m_auxSdpLine;
        }

        void startStream(
            unsigned clientSessionId, void *streamToken, TaskFunc *rrHandler,
            void *rrHandlerData, unsigned short &sequenceNumber, unsigned &timestamp,
            ServerRequestAlternativeByteHandler *alternativeHandler,
            void *alternativeHandlerData) override
        {
            const bool firstClient = m_clients.empty();
            if (firstClient)
                m_source.prepareForNewClient();
            m_requestKeyFrame();

            OnDemandServerMediaSubsession::startStream(
                clientSessionId, streamToken, rrHandler, rrHandlerData, sequenceNumber,
                timestamp, alternativeHandler, alternativeHandlerData);
            RTPSink *sink = nullptr;
            RTCPInstance *rtcp = nullptr;
            getRTPSinkandRTCP(streamToken, sink, rtcp);
            m_activeSink = static_cast<MobstrH264VideoRtpSink *>(sink);
            if (m_activeSink && m_clients.insert(clientSessionId).second)
                m_clientCount = m_clients.size();
        }

        void deleteStream(unsigned clientSessionId, void *&streamToken) override
        {
            if (m_clients.erase(clientSessionId))
                m_clientCount = m_clients.size();
            if (m_clients.empty())
                m_activeSink = nullptr;
            OnDemandServerMediaSubsession::deleteStream(clientSessionId, streamToken);
        }

        static void onSendError(void *context)
        {
            ++*static_cast<std::atomic<uint64_t> *>(context);
        }

        static void onDummyFinished(void *context)
        {
            auto *self = static_cast<LiveH264Subsession *>(context);
            self->envir().taskScheduler().unscheduleDelayedTask(self->nextTask());
            self->m_auxDone = 1;
        }

        static void checkAuxSdp(void *context)
        {
            auto *self = static_cast<LiveH264Subsession *>(context);
            self->nextTask() = nullptr;
            const char *line = self->m_dummySink ? self->m_dummySink->auxSDPLine() : nullptr;
            if (line)
            {
                self->m_auxSdpLine = strDup(line);
                self->m_auxDone = 1;
            }
            else if (++self->m_auxChecks >= auxSdpMaxChecks)
            {
                self->envir() << "Timed out waiting for H.264 SPS/PPS\n";
                self->m_auxDone = 1;
            }
            else
            {
                self->nextTask() = self->envir().taskScheduler().scheduleDelayedTask(
                    auxSdpCheckIntervalUs, checkAuxSdp, self);
            }
        }

        static void sampleStats(void *context)
        {
            static_cast<LiveH264Subsession *>(context)->sampleStats();
        }

        void sampleStats()
        {
            m_statsTask = nullptr;
            if (m_activeSink)
            {
                m_packetsSent = m_activeSink->packetsSent();
                uint32_t lossRatio = 0;
                uint64_t packetsLost = 0;
                RTPTransmissionStatsDB::Iterator iterator(
                    m_activeSink->transmissionStatsDB());
                while (RTPTransmissionStats *stats = iterator.next())
                {
                    lossRatio = std::max<uint32_t>(lossRatio, stats->packetLossRatio());
                    packetsLost += stats->totNumPacketsLost();
                }
                m_receiverLossPermille = (lossRatio * 1000 + 127) / 256;
                m_receiverPacketsLost = packetsLost;
            }
            scheduleStats();
        }

        void scheduleStats()
        {
            m_statsTask = envir().taskScheduler().scheduleDelayedTask(
                statsIntervalUs, sampleStats, this);
        }

        StreamReplicator &m_replicator;
        MediaCodecH264Source &m_source;
        const unsigned m_bitrateKbps;
        const size_t m_packetSize;
        std::function<void()> m_requestKeyFrame;
        std::atomic<uint64_t> &m_packetsSent;
        std::atomic<uint64_t> &m_sendErrors;
        std::atomic<uint32_t> &m_clientCount;
        std::atomic<uint32_t> &m_receiverLossPermille;
        std::atomic<uint64_t> &m_receiverPacketsLost;
        std::unordered_set<unsigned> m_clients;
        MobstrH264VideoRtpSink *m_activeSink{nullptr};
        TaskToken m_statsTask{nullptr};
        char *m_auxSdpLine{nullptr};
        RTPSink *m_dummySink{nullptr};
        EventLoopWatchVariable m_auxDone{0};
        unsigned m_auxChecks{0};
    };
}

Live555Server::Live555Server(uint16_t port, size_t packetSize, int32_t bitrate)
    : m_port(port),
      m_packetSize(std::clamp<size_t>(packetSize, 256, 1472)),
      m_bitrate(bitrate) {}

Live555Server::~Live555Server()
{
    stop();
}

bool Live555Server::start(size_t maxNalSize)
{
    std::unique_lock lock(m_startMutex);
    if (m_thread.joinable())
        return m_started;

    m_ready = false;
    m_started = false;
    m_stopRequested = false;
    m_watchVariable = 0;
    m_thread = std::thread(&Live555Server::run, this, maxNalSize);
    m_startReady.wait(lock, [this]
                      { return m_ready; });
    return m_started;
}

void Live555Server::stop()
{
    if (!m_thread.joinable())
        return;

    if (!m_stopRequested.exchange(true))
    {
        TaskScheduler *scheduler = m_scheduler.load();
        const auto trigger = static_cast<EventTriggerId>(m_stopTrigger.load());
        if (scheduler && trigger)
            scheduler->triggerEvent(trigger, this);
    }
    m_thread.join();
}

void Live555Server::submitAccessUnit(
    std::span<const uint8_t> bytes, int64_t presentationTimeUs,
    bool codecConfig, bool keyFrame)
{
    if (MediaCodecH264Source *source = m_source.load())
    {
        if (source->submitAccessUnit(bytes, presentationTimeUs, codecConfig, keyFrame))
            m_keyFrameRequested = true;
        m_accessUnits = source->accessUnits();
    }
}

uint64_t Live555Server::accessUnits() const
{
    return m_accessUnits.load();
}

void Live555Server::stopEventLoop(void *context)
{
    static_cast<Live555Server *>(context)->m_watchVariable = 1;
}

void Live555Server::run(size_t maxNalSize)
{
    TaskScheduler *scheduler = BasicTaskScheduler::createNew();
    UsageEnvironment *environment = BasicUsageEnvironment::createNew(*scheduler);
    m_scheduler = scheduler;
    m_stopTrigger = scheduler->createEventTrigger(stopEventLoop);

    // The RTP sink must be able to hold one complete encoder NAL before it
    // fragments that NAL into RFC 6184 FU-A packets.
    OutPacketBuffer::maxSize = static_cast<unsigned>(
        std::min<size_t>(std::max<size_t>(maxNalSize, 64 * 1024), 32 * 1024 * 1024));
    auto *source = MediaCodecH264Source::createNew(
        *environment, OutPacketBuffer::maxSize);
    auto *replicator = StreamReplicator::createNew(*environment, source, False);
    m_source = source;

    RTSPServer *server = MobstrRTSPServer::createNew(*environment, Port(m_port), 60);
    ServerMediaSession *session = nullptr;
    if (server)
    {
        session = ServerMediaSession::createNew(
            *environment, "live", "Mobstr Camera", "Mobstr H.264 camera stream",
            False, "a=recvonly\r\n");
        session->addSubsession(LiveH264Subsession::createNew(
            *environment, *replicator, *source,
            static_cast<unsigned>(std::max(m_bitrate / 1000, 1)), m_packetSize,
            [this]
            { m_keyFrameRequested = true; }, m_packetsSent, m_sendErrors,
            m_clientCount, m_receiverLossPermille, m_receiverPacketsLost));
        server->addServerMediaSession(session);
    }

    {
        std::lock_guard lock(m_startMutex);
        m_started = server != nullptr;
        m_ready = true;
    }
    m_startReady.notify_all();

    if (server)
        scheduler->doEventLoop(&m_watchVariable);
    else
        *environment << "LIVE555 failed to bind RTSP port " << m_port << ": "
                     << environment->getResultMsg() << '\n';

    m_source = nullptr;
    if (server)
        Medium::close(server);
    Medium::close(replicator);
    scheduler->deleteEventTrigger(static_cast<EventTriggerId>(m_stopTrigger.load()));
    m_stopTrigger = 0;
    m_scheduler = nullptr;
    environment->reclaim();
    delete scheduler;
}
