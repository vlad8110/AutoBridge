// AutoBridge native side: runs one Android Auto session over an accepted TCP socket using aasdk,
// and forwards video/audio to Kotlin (dev.autobridge.Native.Listener) and touch/mic/sensors back to the phone.
#include <jni.h>
#include <android/log.h>
#include <thread>
#include <atomic>
#include <array>
#include <climits>

#include <aasdk/TCP/TCPWrapper.hpp>
#include <aasdk/TCP/TCPEndpoint.hpp>
#include <aasdk/Transport/TCPTransport.hpp>
#include <aasdk/Transport/SSLWrapper.hpp>
#include <aasdk/Messenger/Cryptor.hpp>
#include <aasdk/Messenger/MessageInStream.hpp>
#include <aasdk/Messenger/MessageOutStream.hpp>
#include <aasdk/Messenger/Messenger.hpp>
#include <aasdk/Channel/Control/ControlServiceChannel.hpp>
#include <aasdk/Channel/MediaSink/Video/Channel/VideoChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/MediaAudioChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/GuidanceAudioChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/SystemAudioChannel.hpp>
#include <aasdk/Channel/MediaSource/Audio/MicrophoneAudioChannel.hpp>
#include <aasdk/Channel/InputSource/InputSourceService.hpp>
#include <aasdk/Channel/SensorSource/SensorSourceService.hpp>
#include <aasdk/Channel/MediaPlaybackStatus/MediaPlaybackStatusService.hpp>
#include <aasdk/Channel/Channel.hpp>
#include <aasdk/Messenger/MessageId.hpp>
#include <aap_protobuf/service/navigationstatus/NavigationStatusMessageId.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationStatus.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationState.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationCurrentPosition.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationNextTurnEvent.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationNextTurnDistanceEvent.pb.h>
#include <aap_protobuf/service/control/message/AuthResponse.pb.h>
#include <aap_protobuf/service/control/message/ServiceDiscoveryResponse.pb.h>
#include <aap_protobuf/service/inputsource/message/InputReport.pb.h>
#include <aap_protobuf/service/sensorsource/message/SensorBatch.pb.h>
#include <aap_protobuf/service/media/source/message/MicrophoneResponse.pb.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "AutoBridge", __VA_ARGS__)

namespace ctl = aap_protobuf::service::control::message;
namespace media = aap_protobuf::service::media;
namespace sensor = aap_protobuf::service::sensorsource::message;
namespace input = aap_protobuf::service::inputsource::message;
using aasdk::channel::SendPromise;
using aasdk::common::DataConstBuffer;
using aasdk::error::Error;
using aasdk::messenger::ChannelId;

static JavaVM *gVm;

struct Config {
    int resolution;      // VideoCodecResolutionType
    int codecW, codecH;  // pixel size of that resolution
    int marginW, marginH, dpi;
};

class Session;

// Calls into the Kotlin listener from the io thread (attached once in Session::run).
struct Listener {
    jobject obj = nullptr;
    jmethodID onVideo, onAudio, onAudioState, onMic, onVideoFocus, onEnded;
    jmethodID onNowPlaying, onPlayback, onNavStatus, onNavStep, onNavLegacyTurn, onNavDistance;
    JNIEnv *env = nullptr;

    void bind(JNIEnv *e, jobject l) {
        obj = e->NewGlobalRef(l);
        jclass c = e->GetObjectClass(l);
        onVideo = e->GetMethodID(c, "onVideo", "([BJ)V");
        onAudio = e->GetMethodID(c, "onAudio", "(I[B)V");
        onAudioState = e->GetMethodID(c, "onAudioState", "(IZ)V");
        onMic = e->GetMethodID(c, "onMic", "(Z)V");
        onVideoFocus = e->GetMethodID(c, "onVideoFocus", "(Z)V");
        onEnded = e->GetMethodID(c, "onEnded", "(Ljava/lang/String;)V");
        onNowPlaying = e->GetMethodID(c, "onNowPlaying", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[BI)V");
        onPlayback = e->GetMethodID(c, "onPlayback", "(II)V");
        onNavStatus = e->GetMethodID(c, "onNavStatus", "(I)V");
        onNavStep = e->GetMethodID(c, "onNavStep", "(IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        onNavLegacyTurn = e->GetMethodID(c, "onNavLegacyTurn", "(IIILjava/lang/String;)V");
        onNavDistance = e->GetMethodID(c, "onNavDistance", "(IIIIIIIILjava/lang/String;)V");
    }
    jstring str(const std::string &v, bool has) { return has ? env->NewStringUTF(v.c_str()) : nullptr; }
    void drop(std::initializer_list<jobject> refs) { for (auto r : refs) if (r) env->DeleteLocalRef(r); }
    void nowPlaying(const std::string *song, const std::string *artist, const std::string *album, const std::string *art, int durationSec) {
        jstring a = song ? str(*song, true) : nullptr, b = artist ? str(*artist, true) : nullptr, c = album ? str(*album, true) : nullptr;
        jbyteArray d = nullptr;
        if (art && !art->empty()) {
            d = env->NewByteArray((jsize) art->size());
            env->SetByteArrayRegion(d, 0, (jsize) art->size(), reinterpret_cast<const jbyte *>(art->data()));
        }
        env->CallVoidMethod(obj, onNowPlaying, a, b, c, d, durationSec);
        drop({a, b, c, d});
    }
    void playback(int state, int positionSec) { env->CallVoidMethod(obj, onPlayback, state, positionSec); }
    void navStatus(int status) { env->CallVoidMethod(obj, onNavStatus, status); }
    void navStep(int type, int exit, const std::string &road, const std::string &cue, const std::string &dest) {
        jstring a = str(road, !road.empty()), b = str(cue, !cue.empty()), c = str(dest, !dest.empty());
        env->CallVoidMethod(obj, onNavStep, type, exit, a, b, c);
        drop({a, b, c});
    }
    void navLegacyTurn(int event, int side, int exit, const std::string &road) {
        jstring a = str(road, !road.empty());
        env->CallVoidMethod(obj, onNavLegacyTurn, event, side, exit, a);
        drop({a});
    }
    void navDistance(int m, int e3, int unit, int sec, int destM, int destE3, int destUnit, int destSec, const std::string &road) {
        jstring a = str(road, !road.empty());
        env->CallVoidMethod(obj, onNavDistance, m, e3, unit, sec, destM, destE3, destUnit, destSec, a);
        drop({a});
    }
    jbyteArray bytes(const DataConstBuffer &b) {
        jbyteArray a = env->NewByteArray((jsize) b.size);
        env->SetByteArrayRegion(a, 0, (jsize) b.size, reinterpret_cast<const jbyte *>(b.cdata));
        return a;
    }
    void video(const DataConstBuffer &b, uint64_t ts) {
        jbyteArray a = bytes(b);
        env->CallVoidMethod(obj, onVideo, a, (jlong) ts);
        env->DeleteLocalRef(a);
    }
    void audio(int stream, const DataConstBuffer &b) {
        jbyteArray a = bytes(b);
        env->CallVoidMethod(obj, onAudio, stream, a);
        env->DeleteLocalRef(a);
    }
    void audioState(int stream, bool on) { env->CallVoidMethod(obj, onAudioState, stream, on); }
    void mic(bool open) { env->CallVoidMethod(obj, onMic, open); }
    void videoFocus(bool projected) { env->CallVoidMethod(obj, onVideoFocus, projected); }
    void ended(const std::string &why) {
        jstring s = env->NewStringUTF(why.c_str());
        env->CallVoidMethod(obj, onEnded, s);
        env->DeleteLocalRef(s);
    }
};

// Send result handler: any real send error ends the session.
static SendPromise::Pointer promise(Session &s, const char *what);
static void fail(Session &s, const Error &e, const char *what);

// Shared bits every channel handler needs.
struct Base {
    Session &s;
    const char *name;  // shown in the error, so a photo of the screen says which part failed
    Base(Session &session, const char *name) : s(session), name(name) {}
    SendPromise::Pointer promise() { return ::promise(s, name); }
    void fail(const Error &e) { ::fail(s, e, name); }
};

class Session {
public:
    boost::asio::io_service io;
    boost::asio::io_service::strand strand{io};
    std::unique_ptr<boost::asio::io_service::work> work{new boost::asio::io_service::work(io)};
    aasdk::tcp::TCPWrapper tcpWrapper;
    aasdk::transport::ITransport::Pointer transport;
    aasdk::messenger::ICryptor::Pointer cryptor;
    aasdk::messenger::IMessenger::Pointer messenger;
    Config cfg;
    Listener listener;
    std::atomic<bool> ended{false};
    std::thread thread;
    std::vector<std::shared_ptr<void>> keep;  // handlers, kept alive for the session
    std::function<void()> starter;

    // Set by handlers so JNI calls can reach the right channel.
    std::shared_ptr<aasdk::channel::inputsource::InputSourceService> inputChannel;
    std::shared_ptr<aasdk::channel::sensorsource::SensorSourceService> sensorChannel;
    std::shared_ptr<aasdk::channel::mediasource::IMediaSourceService> micChannel;
    std::shared_ptr<aasdk::channel::mediasink::video::IVideoMediaSinkService> videoChannel;
    // Truck.toArray(): speed mm/s, gear, parking brake, battery %, range m. INT_MIN = unknown.
    int truck[5] = {INT_MIN, INT_MIN, INT_MIN, INT_MIN, INT_MIN};
    bool offered[5] = {};  // which of those Android Auto was told this car has
    bool moving = false, night = false, micOpen = false, sensorsReady = false, videoReady = false, inputReady = false;

    void end(const std::string &why) {
        if (ended.exchange(true)) return;
        LOG("session ended: %s", why.c_str());
        listener.ended(why);
    }

    void run() {
        JNIEnv *env;
        gVm->AttachCurrentThread(&env, nullptr);
        listener.env = env;
        try { io.run(); } catch (const std::exception &e) { end(std::string("session: ") + e.what()); }
        env->DeleteGlobalRef(listener.obj);
        gVm->DetachCurrentThread();
    }

    void sendSensors() {
        if (!sensorsReady) return;
        sensor::SensorBatch batch;
        // Bit flags from DrivingStatus: while moving, block keyboard, settings and long messages; video stays on.
        batch.add_driving_status_data()->set_status(moving ? (2 | 8 | 16) : 0);
        batch.add_night_mode_data()->set_night_mode(night);
        if (offered[0] && truck[0] != INT_MIN) batch.add_speed_data()->set_speed_e3(truck[0]);
        if (offered[1] && truck[1] != INT_MIN) batch.add_gear_data()->set_gear((sensor::Gear) truck[1]);
        if (offered[2] && truck[2] != INT_MIN) batch.add_parking_brake_data()->set_parking_brake(truck[2] != 0);
        if ((offered[3] && truck[3] != INT_MIN) || (offered[4] && truck[4] != INT_MIN)) {
            auto *fuel = batch.add_fuel_data();  // Android Auto's "fuel" carries the battery on an EV
            if (truck[3] != INT_MIN) fuel->set_fuel_level(truck[3]);
            if (truck[4] != INT_MIN) fuel->set_range(truck[4]);
        }
        sensorChannel->sendSensorEventIndication(batch, ::promise(*this, "sensors"));
    }

    void sendVideoFocus(bool projected, bool unsolicited) {
        if (!videoReady) return;
        media::video::message::VideoFocusNotification n;
        n.set_focus(projected ? media::video::message::VIDEO_FOCUS_PROJECTED : media::video::message::VIDEO_FOCUS_NATIVE);
        n.set_unsolicited(unsolicited);
        videoChannel->sendVideoFocusIndication(n, ::promise(*this, "video focus"));
    }
};

static SendPromise::Pointer promise(Session &s, const char *what) {
    auto p = SendPromise::defer(s.strand);
    Session *sp = &s;
    p->then([] {}, [sp, what](const Error &e) { fail(*sp, e, what); });
    return p;
}

static void fail(Session &s, const Error &e, const char *what) {
    if (e.getCode() != aasdk::error::ErrorCode::OPERATION_ABORTED) s.end(std::string(what) + ": " + e.what());
}

template <typename C>
static void openResponse(Session &s, C &channel) {
    ctl::ChannelOpenResponse r;
    r.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    channel->sendChannelOpenResponse(r, ::promise(s, "channel open"));
}

// ---- Video ----
struct Video : Base, aasdk::channel::mediasink::video::IVideoMediaSinkServiceEventHandler,
               std::enable_shared_from_this<Video> {
    std::shared_ptr<aasdk::channel::mediasink::video::channel::VideoChannel> ch;
    int32_t sessionId = 0;
    Video(Session &s) : Base(s, "video"), ch(std::make_shared<aasdk::channel::mediasink::video::channel::VideoChannel>(s.strand, s.messenger)) {}

    void fill(ctl::ServiceDiscoveryResponse &r) {
        auto *svc = r.add_channels();
        svc->set_id((int) ch->getId());
        auto *sink = svc->mutable_media_sink_service();
        sink->set_available_type(media::shared::message::MEDIA_CODEC_VIDEO_H264_BP);
        sink->set_available_while_in_call(true);
        auto *v = sink->add_video_configs();
        v->set_codec_resolution((media::sink::message::VideoCodecResolutionType) s.cfg.resolution);
        v->set_frame_rate(media::sink::message::VIDEO_FPS_60);
        v->set_width_margin(s.cfg.marginW);
        v->set_height_margin(s.cfg.marginH);
        v->set_density(s.cfg.dpi);
    }
    void onChannelOpenRequest(const ctl::ChannelOpenRequest &) override { openResponse(s, ch); ch->receive(shared_from_this()); }
    void onMediaChannelSetupRequest(const media::shared::message::Setup &) override {
        media::shared::message::Config c;
        c.set_status(media::shared::message::Config::STATUS_READY);
        c.set_max_unacked(4);
        c.add_configuration_indices(0);
        auto p = SendPromise::defer(s.strand);
        p->then([this] { s.videoReady = true; s.sendVideoFocus(true, true); }, [this](const Error &e) { fail(e); });
        ch->sendChannelSetupResponse(c, std::move(p));
        ch->receive(shared_from_this());
    }
    void onMediaChannelStartIndication(const media::shared::message::Start &i) override { sessionId = i.session_id(); ch->receive(shared_from_this()); }
    void onMediaChannelStopIndication(const media::shared::message::Stop &) override { ch->receive(shared_from_this()); }
    void onMediaWithTimestampIndication(aasdk::messenger::Timestamp::ValueType ts, const DataConstBuffer &b) override {
        s.listener.video(b, ts);
        media::source::message::Ack ack;
        ack.set_session_id(sessionId);
        ack.set_ack(1);
        ch->sendMediaAckIndication(ack, promise());
        ch->receive(shared_from_this());
    }
    void onMediaIndication(const DataConstBuffer &b) override { onMediaWithTimestampIndication(0, b); }
    void onVideoFocusRequest(const media::video::message::VideoFocusRequestNotification &r) override {
        bool projected = r.mode() != media::video::message::VIDEO_FOCUS_NATIVE;
        s.listener.videoFocus(projected);  // phone's "exit" button asks for the car's own UI
        s.sendVideoFocus(projected, false);
        ch->receive(shared_from_this());
    }
    void onChannelError(const Error &e) override { fail(e); }
};

// ---- Audio out (media, guidance, system) ----
struct Audio : Base, aasdk::channel::mediasink::audio::IAudioMediaSinkServiceEventHandler,
               std::enable_shared_from_this<Audio> {
    std::shared_ptr<aasdk::channel::mediasink::audio::AudioMediaSinkService> ch;
    int stream, rate, channels;
    int32_t sessionId = 0;
    Audio(Session &s, int stream, std::shared_ptr<aasdk::channel::mediasink::audio::AudioMediaSinkService> c, int rate, int channels)
        : Base(s, "audio"), ch(std::move(c)), stream(stream), rate(rate), channels(channels) {}

    void fill(ctl::ServiceDiscoveryResponse &r) {
        using T = media::sink::message::AudioStreamType;
        static const T types[] = {media::sink::message::AUDIO_STREAM_MEDIA, media::sink::message::AUDIO_STREAM_GUIDANCE,
                                  media::sink::message::AUDIO_STREAM_SYSTEM_AUDIO};
        auto *svc = r.add_channels();
        svc->set_id((int) ch->getId());
        auto *sink = svc->mutable_media_sink_service();
        sink->set_available_type(media::shared::message::MEDIA_CODEC_AUDIO_PCM);
        sink->set_audio_type(types[stream]);
        sink->set_available_while_in_call(true);
        auto *a = sink->add_audio_configs();
        a->set_sampling_rate(rate);
        a->set_number_of_bits(16);
        a->set_number_of_channels(channels);
    }
    void onChannelOpenRequest(const ctl::ChannelOpenRequest &) override { openResponse(s, ch); ch->receive(shared_from_this()); }
    void onMediaChannelSetupRequest(const media::shared::message::Setup &) override {
        media::shared::message::Config c;
        c.set_status(media::shared::message::Config::STATUS_READY);
        c.set_max_unacked(1);
        c.add_configuration_indices(0);
        ch->sendChannelSetupResponse(c, promise());
        ch->receive(shared_from_this());
    }
    void onMediaChannelStartIndication(const media::shared::message::Start &i) override {
        sessionId = i.session_id();
        s.listener.audioState(stream, true);
        ch->receive(shared_from_this());
    }
    void onMediaChannelStopIndication(const media::shared::message::Stop &) override {
        s.listener.audioState(stream, false);
        ch->receive(shared_from_this());
    }
    void onMediaWithTimestampIndication(aasdk::messenger::Timestamp::ValueType, const DataConstBuffer &b) override {
        s.listener.audio(stream, b);
        media::source::message::Ack ack;
        ack.set_session_id(sessionId);
        ack.set_ack(1);
        ch->sendMediaAckIndication(ack, promise());
        ch->receive(shared_from_this());
    }
    void onMediaIndication(const DataConstBuffer &b) override { onMediaWithTimestampIndication(0, b); }
    void onChannelError(const Error &e) override { fail(e); }
};

// ---- Microphone (Google Assistant, voice replies) ----
struct Mic : Base, aasdk::channel::mediasource::IMediaSourceServiceEventHandler, std::enable_shared_from_this<Mic> {
    std::shared_ptr<aasdk::channel::mediasource::audio::MicrophoneAudioChannel> ch;
    Mic(Session &s) : Base(s, "mic"), ch(std::make_shared<aasdk::channel::mediasource::audio::MicrophoneAudioChannel>(s.strand, s.messenger)) {}

    void fill(ctl::ServiceDiscoveryResponse &r) {
        auto *svc = r.add_channels();
        svc->set_id((int) ch->getId());
        auto *src = svc->mutable_media_source_service();
        src->set_available_type(media::shared::message::MEDIA_CODEC_AUDIO_PCM);
        auto *a = src->mutable_audio_config();
        a->set_sampling_rate(16000);
        a->set_number_of_bits(16);
        a->set_number_of_channels(1);
    }
    void onChannelOpenRequest(const ctl::ChannelOpenRequest &) override { openResponse(s, ch); ch->receive(shared_from_this()); }
    void onMediaChannelSetupRequest(const media::shared::message::Setup &) override {
        media::shared::message::Config c;
        c.set_status(media::shared::message::Config::STATUS_READY);
        c.set_max_unacked(1);
        c.add_configuration_indices(0);
        ch->sendChannelSetupResponse(c, promise());
        ch->receive(shared_from_this());
    }
    void onMediaSourceOpenRequest(const media::source::message::MicrophoneRequest &r) override {
        s.micOpen = r.open();
        s.listener.mic(r.open());
        media::source::message::MicrophoneResponse resp;
        resp.set_status(0);
        resp.set_session_id(0);
        ch->sendMicrophoneOpenResponse(resp, promise());
        ch->receive(shared_from_this());
    }
    void onMediaChannelAckIndication(const media::source::message::Ack &) override { ch->receive(shared_from_this()); }
    void onChannelError(const Error &e) override { fail(e); }
};

// ---- Touch input ----
struct Input : Base, aasdk::channel::inputsource::IInputSourceServiceEventHandler, std::enable_shared_from_this<Input> {
    std::shared_ptr<aasdk::channel::inputsource::InputSourceService> ch;
    Input(Session &s) : Base(s, "touch"), ch(std::make_shared<aasdk::channel::inputsource::InputSourceService>(s.strand, s.messenger)) {}

    void fill(ctl::ServiceDiscoveryResponse &r) {
        auto *svc = r.add_channels();
        svc->set_id((int) ch->getId());
        // The touch panel is the visible area (video frame minus the cropped margins);
        // Android Auto maps touches onto its UI area, not the whole frame.
        // Steering-wheel media keys, passed on from the truck's media buttons (NowPlaying.kt).
        for (int k : {85, 87, 88, 126, 127}) svc->mutable_input_source_service()->add_keycodes_supported(k);
        auto *t = svc->mutable_input_source_service()->add_touchscreen();
        t->set_width(s.cfg.codecW - s.cfg.marginW);
        t->set_height(s.cfg.codecH - s.cfg.marginH);
    }
    void onChannelOpenRequest(const ctl::ChannelOpenRequest &) override {
        openResponse(s, ch);
        s.inputReady = true;
        ch->receive(shared_from_this());
    }
    void onKeyBindingRequest(const media::sink::message::KeyBindingRequest &) override {
        media::sink::message::KeyBindingResponse r;
        r.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        ch->sendKeyBindingResponse(r, promise());
        ch->receive(shared_from_this());
    }
    void onChannelError(const Error &e) override { fail(e); }
};

// ---- Now playing: song, artist, art and position, for the truck's media card and dashboard ----
struct NowPlaying : Base, aasdk::channel::mediaplaybackstatus::IMediaPlaybackStatusServiceEventHandler, std::enable_shared_from_this<NowPlaying> {
    std::shared_ptr<aasdk::channel::mediaplaybackstatus::MediaPlaybackStatusService> ch;
    NowPlaying(Session &s) : Base(s, "now playing"), ch(std::make_shared<aasdk::channel::mediaplaybackstatus::MediaPlaybackStatusService>(s.strand, s.messenger)) {}

    void fill(ctl::ServiceDiscoveryResponse &r) {
        auto *svc = r.add_channels();
        svc->set_id((int) ch->getId());
        svc->mutable_media_playback_service();
    }
    void onChannelOpenRequest(const ctl::ChannelOpenRequest &) override { openResponse(s, ch); ch->receive(shared_from_this()); }
    void onMetadataUpdate(const aap_protobuf::service::mediaplayback::message::MediaPlaybackMetadata &m) override {
        s.listener.nowPlaying(m.has_song() ? &m.song() : nullptr, m.has_artist() ? &m.artist() : nullptr,
                              m.has_album() ? &m.album() : nullptr, m.has_album_art() ? &m.album_art() : nullptr,
                              m.has_duration_seconds() ? (int) m.duration_seconds() : -1);
        ch->receive(shared_from_this());
    }
    void onPlaybackUpdate(const aap_protobuf::service::mediaplayback::message::MediaPlaybackStatus &p) override {
        s.listener.playback(p.has_state() ? (int) p.state() : -1, p.has_playback_seconds() ? (int) p.playback_seconds() : -1);
        ch->receive(shared_from_this());
    }
    void onChannelError(const Error &e) override { fail(e); }
};

// ---- Turn-by-turn, for the truck's cluster (ClusterNav.kt) ----
// Our own channel: aasdk's NavigationStatusService drops the newer NavigationState and
// CurrentPosition messages, which current Android Auto sends instead of the old turn events.
namespace nav = aap_protobuf::service::navigationstatus;
struct NavChannel : aasdk::channel::Channel, std::enable_shared_from_this<NavChannel> {
    NavChannel(boost::asio::io_service::strand &st, aasdk::messenger::IMessenger::Pointer m)
        : Channel(st, std::move(m), ChannelId::NAVIGATION_STATUS) {}
};
static int e3(const std::string &v) { char *end; double d = strtod(v.c_str(), &end); return end == v.c_str() ? -1 : (int) (d * 1000 + 0.5); }

struct Navigation : Base, std::enable_shared_from_this<Navigation> {
    std::shared_ptr<NavChannel> ch;
    Navigation(Session &s) : Base(s, "navigation"), ch(std::make_shared<NavChannel>(s.strand, s.messenger)) {}

    void fill(ctl::ServiceDiscoveryResponse &r) {
        auto *svc = r.add_channels();
        svc->set_id((int) ch->getId());
        auto *n = svc->mutable_navigation_status_service();
        n->set_minimum_interval_ms(1000);
        n->set_type(nav::NavigationStatusService::ENUM);  // turn types, not pictures: GM's cluster draws its own arrows
    }
    void receive() {
        auto p = aasdk::messenger::ReceivePromise::defer(s.strand);
        auto self = shared_from_this();
        p->then([self](aasdk::messenger::Message::Pointer m) { self->handle(*m); self->receive(); },
                [self](const Error &e) { self->fail(e); });
        s.messenger->enqueueReceive(ch->getId(), std::move(p));
    }
    void handle(aasdk::messenger::Message &m) {
        aasdk::messenger::MessageId id(m.getPayload());
        DataConstBuffer b(m.getPayload(), id.getSizeOf());
        switch (id.getId()) {
            case ctl::ControlMessageType::MESSAGE_CHANNEL_OPEN_REQUEST: {
                ctl::ChannelOpenResponse r;
                r.set_status(aap_protobuf::shared::STATUS_SUCCESS);
                auto msg = std::make_shared<aasdk::messenger::Message>(ch->getId(), aasdk::messenger::EncryptionType::ENCRYPTED,
                                                                         aasdk::messenger::MessageType::CONTROL);
                msg->insertPayload(aasdk::messenger::MessageId(ctl::ControlMessageType::MESSAGE_CHANNEL_OPEN_RESPONSE).getData());
                msg->insertPayload(r);
                ch->send(std::move(msg), promise());
                break;
            }
            case nav::INSTRUMENT_CLUSTER_NAVIGATION_STATUS: {
                nav::message::NavigationStatus st;
                if (st.ParseFromArray(b.cdata, b.size)) s.listener.navStatus(st.status());
                break;
            }
            case nav::INSTRUMENT_CLUSTER_NAVIGATION_STATE: {
                nav::message::NavigationState st;
                if (!st.ParseFromArray(b.cdata, b.size) || st.steps_size() == 0) break;
                auto &step = st.steps(0);
                std::string cue = step.has_cue() && step.cue().alternate_text_size() ? step.cue().alternate_text(0) : "";
                std::string dest = st.destinations_size() ? st.destinations(0).address() : "";
                s.listener.navStep(step.maneuver().type(), step.maneuver().roundabout_exit_number(), step.road().name(), cue, dest);
                break;
            }
            case nav::INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION: {
                nav::message::NavigationCurrentPosition p;
                if (!p.ParseFromArray(b.cdata, b.size)) break;
                auto &sd = p.step_distance();
                int destM = -1, destE3 = -1, destUnit = 0, destSec = -1;
                if (p.destination_distances_size()) {
                    auto &d = p.destination_distances(0);
                    destM = d.distance().has_meters() ? d.distance().meters() : -1;
                    destE3 = d.distance().has_display_value() ? e3(d.distance().display_value()) : -1;
                    destUnit = d.distance().display_units();
                    destSec = d.has_time_to_arrival_seconds() ? (int) d.time_to_arrival_seconds() : -1;
                }
                s.listener.navDistance(sd.distance().has_meters() ? sd.distance().meters() : -1,
                                       sd.distance().has_display_value() ? e3(sd.distance().display_value()) : -1,
                                       sd.distance().display_units(), sd.has_time_to_step_seconds() ? (int) sd.time_to_step_seconds() : -1,
                                       destM, destE3, destUnit, destSec, p.current_road().name());
                break;
            }
            case nav::INSTRUMENT_CLUSTER_NAVIGATION_TURN_EVENT: {
                nav::message::NavigationNextTurnEvent t;
                if (t.ParseFromArray(b.cdata, b.size))
                    s.listener.navLegacyTurn(t.event(), t.turn_side(), t.turn_number(), t.road());
                break;
            }
            case nav::INSTRUMENT_CLUSTER_NAVIGATION_DISTANCE_EVENT: {
                nav::message::NavigationNextTurnDistanceEvent d;
                if (d.ParseFromArray(b.cdata, b.size))
                    s.listener.navDistance(d.distance_meters(), d.has_display_distance_e3() ? d.display_distance_e3() : -1,
                                           d.display_distance_unit(), d.time_to_turn_seconds(), -1, -1, 0, -1, "");
                break;
            }
            default:
                LOG("navigation: message %d ignored", id.getId());
        }
    }
};

// ---- Sensors: driving status (required by Android Auto) and night mode ----
struct Sensors : Base, aasdk::channel::sensorsource::ISensorSourceServiceEventHandler, std::enable_shared_from_this<Sensors> {
    std::shared_ptr<aasdk::channel::sensorsource::SensorSourceService> ch;
    Sensors(Session &s) : Base(s, "sensors"), ch(std::make_shared<aasdk::channel::sensorsource::SensorSourceService>(s.strand, s.messenger)) {}

    void fill(ctl::ServiceDiscoveryResponse &r) {
        auto *svc = r.add_channels();
        svc->set_id((int) ch->getId());
        auto *src = svc->mutable_sensor_source_service();
        src->add_sensors()->set_sensor_type(sensor::SENSOR_DRIVING_STATUS_DATA);
        src->add_sensors()->set_sensor_type(sensor::SENSOR_NIGHT_MODE);
        // An EV that charges on J1772, CCS1 and (with the owner's adapter) Tesla NACS, so Maps can pick chargers.
        src->add_supported_fuel_types(sensor::FUEL_TYPE_ELECTRIC);
        src->add_supported_ev_connector_types(sensor::EV_CONNECTOR_TYPE_J1772);
        src->add_supported_ev_connector_types(sensor::EV_CONNECTOR_TYPE_COMBO_1);
        src->add_supported_ev_connector_types(sensor::EV_CONNECTOR_TYPE_TESLA_SUPERCHARGER);
        if (s.offered[0]) src->add_sensors()->set_sensor_type(sensor::SENSOR_SPEED);
        if (s.offered[1]) src->add_sensors()->set_sensor_type(sensor::SENSOR_GEAR);
        if (s.offered[2]) src->add_sensors()->set_sensor_type(sensor::SENSOR_PARKING_BRAKE);
        if (s.offered[3] || s.offered[4]) src->add_sensors()->set_sensor_type(sensor::SENSOR_FUEL);
    }
    void onChannelOpenRequest(const ctl::ChannelOpenRequest &) override { openResponse(s, ch); ch->receive(shared_from_this()); }
    void onSensorStartRequest(const sensor::SensorRequest &) override {
        sensor::SensorStartResponseMessage r;
        r.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        auto p = SendPromise::defer(s.strand);
        p->then([this] { s.sensorsReady = true; s.sendSensors(); }, [this](const Error &e) { fail(e); });
        ch->sendSensorStartResponse(r, std::move(p));
        ch->receive(shared_from_this());
    }
    void onChannelError(const Error &e) override { fail(e); }
};

// ---- Control channel: version, TLS handshake, service discovery, focus, ping, shutdown ----
struct Control : Base, aasdk::channel::control::IControlServiceChannelEventHandler, std::enable_shared_from_this<Control> {
    std::shared_ptr<aasdk::channel::control::ControlServiceChannel> ch;
    std::function<void(ctl::ServiceDiscoveryResponse &)> fillAll;
    Control(Session &s) : Base(s, "control"), ch(std::make_shared<aasdk::channel::control::ControlServiceChannel>(s.strand, s.messenger)) {}

    void start() {
        ch->sendVersionRequest(promise());
        ch->receive(shared_from_this());
    }
    void onVersionResponse(uint16_t major, uint16_t minor, aap_protobuf::shared::MessageStatus status) override {
        LOG("phone protocol %d.%d status %d", major, minor, status);
        if (status == aap_protobuf::shared::STATUS_NO_COMPATIBLE_VERSION) return s.end("incompatible Android Auto version");
        s.cryptor->doHandshake();
        ch->sendHandshake(s.cryptor->readHandshakeBuffer(), promise());
        ch->receive(shared_from_this());
    }
    void onHandshake(const DataConstBuffer &payload) override {
        s.cryptor->writeHandshakeBuffer(payload);
        if (!s.cryptor->doHandshake()) {
            ch->sendHandshake(s.cryptor->readHandshakeBuffer(), promise());
        } else {
            LOG("TLS handshake done");
            ctl::AuthResponse auth;
            auth.set_status(aap_protobuf::shared::STATUS_SUCCESS);
            ch->sendAuthComplete(auth, promise());
        }
        ch->receive(shared_from_this());
    }
    void onServiceDiscoveryRequest(const ctl::ServiceDiscoveryRequest &) override {
        ctl::ServiceDiscoveryResponse r;
        r.set_display_name("AutoBridge");
        r.set_driver_position(ctl::DRIVER_POSITION_LEFT);
        auto *hu = r.mutable_headunit_info();
        hu->set_make("AutoBridge");
        hu->set_model("AAOS");
        hu->set_year("2025");
        hu->set_vehicle_id("autobridge");
        hu->set_head_unit_make("AutoBridge");
        hu->set_head_unit_model("AutoBridge");
        hu->set_head_unit_software_build("1");
        hu->set_head_unit_software_version("1.0");
        fillAll(r);
        ch->sendServiceDiscoveryResponse(r, promise());
        ch->receive(shared_from_this());
    }
    void onAudioFocusRequest(const ctl::AudioFocusRequest &r) override {
        ctl::AudioFocusNotification n;
        n.set_focus_state(r.audio_focus_type() == ctl::AUDIO_FOCUS_RELEASE ? ctl::AUDIO_FOCUS_STATE_LOSS : ctl::AUDIO_FOCUS_STATE_GAIN);
        ch->sendAudioFocusResponse(n, promise());
        ch->receive(shared_from_this());
    }
    void onNavigationFocusRequest(const ctl::NavFocusRequestNotification &) override {
        ctl::NavFocusNotification n;
        n.set_focus_type(ctl::NAV_FOCUS_PROJECTED);
        ch->sendNavigationFocusResponse(n, promise());
        ch->receive(shared_from_this());
    }
    void onPingRequest(const ctl::PingRequest &r) override {
        ctl::PingResponse resp;
        resp.set_timestamp(r.timestamp());
        ch->sendPingResponse(resp, promise());
        ch->receive(shared_from_this());
    }
    void onByeByeRequest(const ctl::ByeByeRequest &) override {
        auto p = SendPromise::defer(s.strand);
        p->then([this] { s.end("phone disconnected"); }, [this](const Error &e) { fail(e); });
        ch->sendShutdownResponse(ctl::ByeByeResponse(), std::move(p));
    }
    void onByeByeResponse(const ctl::ByeByeResponse &) override { s.end("phone disconnected"); }
    void onBatteryStatusNotification(const ctl::BatteryStatusNotification &) override { ch->receive(shared_from_this()); }
    void onVoiceSessionRequest(const ctl::VoiceSessionNotification &) override { ch->receive(shared_from_this()); }
    void onPingResponse(const ctl::PingResponse &) override { ch->receive(shared_from_this()); }
    void onChannelError(const Error &e) override { fail(e); }
};

static Session *start(JNIEnv *env, int fd, const Config &cfg, const jint *truck, jobject listener) {
    auto *s = new Session();
    s->cfg = cfg;
    s->listener.bind(env, listener);
    for (int i = 0; i < 5; i++) { s->truck[i] = truck[i]; s->offered[i] = truck[i] != INT_MIN; }

    auto socket = std::make_shared<boost::asio::ip::tcp::socket>(s->io, boost::asio::ip::tcp::v4(), fd);
    auto endpoint = std::make_shared<aasdk::tcp::TCPEndpoint>(s->tcpWrapper, std::move(socket));
    s->transport = std::make_shared<aasdk::transport::TCPTransport>(s->io, endpoint);
    s->cryptor = std::make_shared<aasdk::messenger::Cryptor>(std::make_shared<aasdk::transport::SSLWrapper>());
    s->cryptor->init();
    s->messenger = std::make_shared<aasdk::messenger::Messenger>(
        s->io,
        std::make_shared<aasdk::messenger::MessageInStream>(s->io, s->transport, s->cryptor),
        std::make_shared<aasdk::messenger::MessageOutStream>(s->io, s->transport, s->cryptor));

    namespace aud = aasdk::channel::mediasink::audio::channel;
    auto video = std::make_shared<Video>(*s);
    auto audios = std::vector<std::shared_ptr<Audio>>{
        std::make_shared<Audio>(*s, 0, std::make_shared<aud::MediaAudioChannel>(s->strand, s->messenger), 48000, 2),
        std::make_shared<Audio>(*s, 1, std::make_shared<aud::GuidanceAudioChannel>(s->strand, s->messenger), 16000, 1),
        std::make_shared<Audio>(*s, 2, std::make_shared<aud::SystemAudioChannel>(s->strand, s->messenger), 16000, 1)};
    auto mic = std::make_shared<Mic>(*s);
    auto in = std::make_shared<Input>(*s);
    auto sens = std::make_shared<Sensors>(*s);
    auto playing = std::make_shared<NowPlaying>(*s);
    auto navigation = std::make_shared<Navigation>(*s);
    auto control = std::make_shared<Control>(*s);

    s->videoChannel = video->ch;
    s->inputChannel = in->ch;
    s->sensorChannel = sens->ch;
    s->micChannel = mic->ch;
    control->fillAll = [=](ctl::ServiceDiscoveryResponse &r) {
        video->fill(r);
        for (auto &a : audios) a->fill(r);
        mic->fill(r);
        in->fill(r);
        sens->fill(r);
        playing->fill(r);
        navigation->fill(r);
    };
    s->keep = {video, mic, in, sens, playing, navigation, control};
    for (auto &a : audios) s->keep.push_back(a);

    s->strand.dispatch([=] {
        video->ch->receive(video);
        for (auto &a : audios) a->ch->receive(a);
        mic->ch->receive(mic);
        in->ch->receive(in);
        sens->ch->receive(sens);
        playing->ch->receive(playing);
        navigation->receive();
        control->start();
    });
    s->thread = std::thread([s] { s->run(); });
    return s;
}

static void stop(Session *s) {
    s->ended = true;  // no onEnded after an explicit stop
    s->strand.dispatch([s] {
        try {
            s->messenger->stop();
            s->transport->stop();
            s->cryptor->deinit();
        } catch (...) {}
        s->work.reset();
    });
    // Give aasdk a moment to flush, then force the loop down.
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    s->io.stop();
    if (s->thread.joinable()) s->thread.join();
    s->keep.clear();
    delete s;
}

// ---- JNI ----
extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) { gVm = vm; return JNI_VERSION_1_6; }

// cfg = [resolution, codecW, codecH, marginW, marginH, dpi]
JNIEXPORT jlong JNICALL Java_dev_autobridge_Native_start(JNIEnv *env, jobject, jint fd, jintArray cfgArr, jintArray truckArr, jobject listener) {
    jint c[6], t[5];
    env->GetIntArrayRegion(cfgArr, 0, 6, c);
    env->GetIntArrayRegion(truckArr, 0, 5, t);
    return (jlong) start(env, fd, Config{c[0], c[1], c[2], c[3], c[4], c[5]}, t, listener);
}

JNIEXPORT void JNICALL Java_dev_autobridge_Native_stop(JNIEnv *, jobject, jlong h) { stop((Session *) h); }

JNIEXPORT void JNICALL Java_dev_autobridge_Native_touch(JNIEnv *env, jobject, jlong h, jint action, jint index,
                                                         jintArray ids, jintArray xs, jintArray ys) {
    auto *s = (Session *) h;
    jsize n = env->GetArrayLength(ids);
    std::vector<jint> id(n), x(n), y(n);
    env->GetIntArrayRegion(ids, 0, n, id.data());
    env->GetIntArrayRegion(xs, 0, n, x.data());
    env->GetIntArrayRegion(ys, 0, n, y.data());
    input::InputReport report;
    report.set_timestamp(std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
    auto *t = report.mutable_touch_event();
    t->set_action((input::PointerAction) action);
    t->set_action_index(index);
    for (jsize i = 0; i < n; i++) {
        auto *p = t->add_pointer_data();
        p->set_x(x[i]);
        p->set_y(y[i]);
        p->set_pointer_id(id[i]);
    }
    // Touches before the phone opens the input channel would be encrypted before TLS is up; drop them.
    s->strand.dispatch([s, report] { if (s->inputReady) s->inputChannel->sendInputReport(report, ::promise(*s, "touch")); });
}

JNIEXPORT void JNICALL Java_dev_autobridge_Native_mic(JNIEnv *env, jobject, jlong h, jbyteArray pcm, jint len) {
    auto *s = (Session *) h;
    aasdk::common::Data data(len);
    env->GetByteArrayRegion(pcm, 0, len, reinterpret_cast<jbyte *>(data.data()));
    uint64_t ts = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    s->strand.dispatch([s, data = std::move(data), ts] {
        if (s->micOpen) s->micChannel->sendMediaSourceWithTimestampIndication(ts, data, ::promise(*s, "mic"));
    });
}

JNIEXPORT void JNICALL Java_dev_autobridge_Native_sensors(JNIEnv *env, jobject, jlong h, jboolean moving, jboolean night, jintArray truckArr) {
    auto *s = (Session *) h;
    std::array<jint, 5> t;
    env->GetIntArrayRegion(truckArr, 0, 5, t.data());
    s->strand.dispatch([s, moving, night, t] {
        s->moving = moving; s->night = night;
        std::copy(t.begin(), t.end(), s->truck);
        s->sendSensors();
    });
}

JNIEXPORT void JNICALL Java_dev_autobridge_Native_key(JNIEnv *, jobject, jlong h, jint keycode) {
    auto *s = (Session *) h;
    input::InputReport report;
    report.set_timestamp(std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
    auto *k = report.mutable_key_event();
    for (bool down : {true, false}) {
        auto *key = k->add_keys();
        key->set_keycode(keycode);
        key->set_down(down);
        key->set_metastate(0);
    }
    s->strand.dispatch([s, report] { if (s->inputReady) s->inputChannel->sendInputReport(report, ::promise(*s, "key")); });
}

JNIEXPORT void JNICALL Java_dev_autobridge_Native_videoFocus(JNIEnv *, jobject, jlong h, jboolean projected) {
    auto *s = (Session *) h;
    s->strand.dispatch([s, projected] { s->sendVideoFocus(projected, true); });
}

}
