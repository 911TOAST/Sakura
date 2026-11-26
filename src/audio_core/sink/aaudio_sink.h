// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <list>
#include <string>

#include "audio_core/sink/sink.h"

namespace Core {
class System;
}

namespace AudioCore::Sink {
class SinkStream;

/**
 * Raw AAudio sink — Android's native C audio API, with no wrapper library.
 *
 * Much lighter than the Oboe backend (no oboe:: dependency at all) and uses the
 * same low-latency path: MMAP exclusive mode when the device grants it, shared
 * low-latency mode otherwise. Available on every device this app targets
 * (minSdk 26, AAudio's introduction level).
 */
class AAudioSink final : public Sink {
public:
    explicit AAudioSink();
    ~AAudioSink() override;

    /**
     * Create a new sink stream.
     *
     * @param system          - Core system.
     * @param system_channels - Number of channels the audio system expects.
     *                          May differ from the device's channel count.
     * @param name            - Name of this stream.
     * @param type            - Type of this stream, render/in/out.
     *
     * @return A pointer to the created SinkStream
     */
    SinkStream* AcquireSinkStream(Core::System& system, u32 system_channels,
                                  const std::string& name, StreamType type) override;

    /**
     * Close a given stream.
     *
     * @param stream - The stream to close.
     */
    void CloseStream(SinkStream* stream) override;

    /**
     * Close all streams.
     */
    void CloseStreams() override;

    /**
     * Get the device volume. Set from calls to the IAudioDevice service.
     *
     * @return Volume of the device.
     */
    f32 GetDeviceVolume() const override;

    /**
     * Set the device volume. Set from calls to the IAudioDevice service.
     *
     * @param volume - New volume of the device.
     */
    void SetDeviceVolume(f32 volume) override;

    /**
     * Set the system volume. Comes from the audio system using this stream.
     *
     * @param volume - New volume of the system.
     */
    void SetSystemVolume(f32 volume) override;

    /**
     * Open a throwaway stream to learn the device's channel count, then close it.
     *
     * @param is_input - Query an input (capture) device instead of output.
     * @return Either 2 or 6 for output, 2 for input on failure.
     */
    static s32 QueryChannelCount(bool is_input);

private:
    /// List of streams managed by this sink
    std::list<SinkStreamPtr> sink_streams{};
};

} // namespace AudioCore::Sink
