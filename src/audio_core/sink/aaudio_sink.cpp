// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <span>
#include <utility>

#include <aaudio/AAudio.h>
#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

#include "audio_core/common/common.h"
#include "audio_core/sink/aaudio_sink.h"
#include "audio_core/sink/sink_stream.h"
#include "common/logging.h"
#include "core/core.h"

namespace AudioCore::Sink {

namespace {

bool ApiLevelAtLeast(s32 level) {
#if defined(__ANDROID__)
    return android_get_device_api_level() >= level;
#else
    return true;
#endif
}

} // Anonymous namespace

class AAudioSinkStream final : public SinkStream {
public:
    explicit AAudioSinkStream(Core::System& system_, StreamType type_, const std::string& name_,
                              u32 system_channels_)
        : SinkStream(system_, type_) {
        name = name_;
        system_channels = system_channels_;

        this->OpenStream();
    }

    ~AAudioSinkStream() override {
        LOG_INFO(Audio_Sink, "Destroyed AAudio stream");
    }

    void Finalize() override {
        this->Stop();
        this->CloseStreamInternal();
    }

    void Start(bool resume = false) override {
        if (!stream || !paused) {
            return;
        }

        paused = false;

        if (AAudioStream_requestStart(stream) != AAUDIO_OK) {
            LOG_CRITICAL(Audio_Sink, "Error starting AAudio stream");
        }
    }

    void Stop() override {
        if (!stream || paused) {
            return;
        }

        this->SignalPause();

        if (AAudioStream_requestStop(stream) != AAUDIO_OK) {
            LOG_CRITICAL(Audio_Sink, "Error stopping AAudio stream");
        }
    }

protected:
    aaudio_data_callback_result_t OnAudioReady(void* audio_data, s32 num_buffer_frames) {
        const size_t num_channels = this->GetDeviceChannels();
        const size_t frame_size = num_channels;
        const size_t num_frames = static_cast<size_t>(num_buffer_frames);

        if (type == StreamType::In) {
            std::span<const s16> input_buffer{reinterpret_cast<const s16*>(audio_data),
                                              num_frames * frame_size};
            this->ProcessAudioIn(input_buffer, num_frames);
        } else {
            std::span<s16> output_buffer{reinterpret_cast<s16*>(audio_data),
                                         num_frames * frame_size};
            this->ProcessAudioOutAndRender(output_buffer, num_frames);
        }

        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    void OnErrorAfterClose(AAudioStream* closed_stream, aaudio_result_t error) {
        LOG_INFO(Audio_Sink, "AAudio stream error {}, reinitializing", AAudio_convertResultToText(error));

        if (closed_stream) {
            AAudioStream_close(closed_stream);
        }
        stream = nullptr;

        if (this->OpenStream() && !paused) {
            AAudioStream_requestStart(stream);
        }
    }

private:
    static aaudio_data_callback_result_t DataCallbackThunk(AAudioStream*, void* user_data,
                                                           void* audio_data, s32 num_frames) {
        return static_cast<AAudioSinkStream*>(user_data)->OnAudioReady(audio_data, num_frames);
    }

    static void ErrorCallbackThunk(AAudioStream* stream, void* user_data, aaudio_result_t error) {
        static_cast<AAudioSinkStream*>(user_data)->OnErrorAfterClose(stream, error);
    }

    bool OpenStream() {
        const bool is_input = type == StreamType::In;
        const s32 expected_channels = AAudioSink::QueryChannelCount(is_input);

        AAudioStreamBuilder* builder{};
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
            LOG_ERROR(Audio_Sink, "Failed to create AAudio stream builder");
            return false;
        }

        AAudioStreamBuilder_setDirection(
            builder, is_input ? AAUDIO_DIRECTION_INPUT : AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSampleRate(builder, TargetSampleRate);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setChannelCount(builder, expected_channels);
        AAudioStreamBuilder_setBufferCapacityInFrames(builder, TargetSampleCount * 2);
        AAudioStreamBuilder_setDataCallback(builder, DataCallbackThunk, this);
        AAudioStreamBuilder_setErrorCallback(builder, ErrorCallbackThunk, this);

        // Usage requires API 28; older versions simply ignore it, but the symbol
        // must not be called at all on 26/27.
        if (!is_input && ApiLevelAtLeast(28)) {
            AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
            // Exclusive (MMAP) mode is the native fast path. The device may
            // refuse it; fall back to shared below.
            AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        }

        aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream);
        if (result != AAUDIO_OK && !is_input && ApiLevelAtLeast(28)) {
            LOG_INFO(Audio_Sink,
                     "Exclusive AAudio stream refused ({}), retrying in shared mode",
                     AAudio_convertResultToText(result));
            AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
            result = AAudioStreamBuilder_openStream(builder, &stream);
        }
        AAudioStreamBuilder_delete(builder);

        if (result != AAUDIO_OK) {
            stream = nullptr;
            LOG_ERROR(Audio_Sink, "Failed to open AAudio stream: {}",
                      AAudio_convertResultToText(result));
            return false;
        }

        this->SetStreamProperties();
        return true;
    }

    void SetStreamProperties() {
        ASSERT(stream);

        std::ignore = AAudioStream_setBufferSizeInFrames(stream, TargetSampleCount * 2);
        device_channels = AAudioStream_getChannelCount(stream);

        const auto sample_rate = AAudioStream_getSampleRate(stream);
        const auto buffer_capacity = AAudioStream_getBufferCapacityInFrames(stream);
        const auto sharing = AAudioStream_getSharingMode(stream) == AAUDIO_SHARING_MODE_EXCLUSIVE
                                 ? "exclusive"
                                 : "shared";

        LOG_INFO(Audio_Sink, "Opened AAudio stream with {} channels sample rate {} capacity {} mode {}",
                 device_channels, sample_rate, buffer_capacity, sharing);
    }

    void CloseStreamInternal() {
        if (stream) {
            AAudioStream_close(stream);
            stream = nullptr;
        }
    }

    AAudioStream* stream{};
};

AAudioSink::AAudioSink() {
    // TODO: This is not generally knowable
    // The channel count is distinct based on direction and can change
    device_channels = AAudioSink::QueryChannelCount(false);
}

AAudioSink::~AAudioSink() = default;

SinkStream* AAudioSink::AcquireSinkStream(Core::System& system, u32 system_channels,
                                          const std::string& name, StreamType type) {
    SinkStreamPtr& stream = sink_streams.emplace_back(
        std::make_unique<AAudioSinkStream>(system, type, name, system_channels));

    return stream.get();
}

void AAudioSink::CloseStream(SinkStream* to_remove) {
    sink_streams.remove_if([&](auto& stream) { return stream.get() == to_remove; });
}

void AAudioSink::CloseStreams() {
    sink_streams.clear();
}

f32 AAudioSink::GetDeviceVolume() const {
    if (sink_streams.empty()) {
        return 1.0f;
    }

    return sink_streams.front()->GetDeviceVolume();
}

void AAudioSink::SetDeviceVolume(f32 volume) {
    for (auto& stream : sink_streams) {
        stream->SetDeviceVolume(volume);
    }
}

void AAudioSink::SetSystemVolume(f32 volume) {
    for (auto& stream : sink_streams) {
        stream->SetSystemVolume(volume);
    }
}

s32 AAudioSink::QueryChannelCount(bool is_input) {
    AAudioStreamBuilder* builder{};
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        LOG_ERROR(Audio_Sink, "Failed to create AAudio stream builder for channel query");
        return 2;
    }

    AAudioStreamBuilder_setDirection(
        builder, is_input ? AAUDIO_DIRECTION_INPUT : AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, TargetSampleRate);

    AAudioStream* temp_stream{};
    const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &temp_stream);
    AAudioStreamBuilder_delete(builder);

    if (result == AAUDIO_OK && temp_stream) {
        const s32 channels = AAudioStream_getChannelCount(temp_stream);
        AAudioStream_close(temp_stream);
        return channels >= 6 ? 6 : 2;
    }

    if (temp_stream) {
        AAudioStream_close(temp_stream);
    }

    LOG_ERROR(Audio_Sink, "Failed to open {} stream for channel query. Using default channel count 2",
              is_input ? "input" : "output");
    return 2;
}

} // namespace AudioCore::Sink
