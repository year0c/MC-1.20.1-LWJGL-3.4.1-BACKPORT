package com.mojang.blaze3d.audio;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

import javax.sound.sampled.AudioFormat;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;

public final class SoundBuffer {
    private @Nullable ByteBuffer sourceData;
    private final AudioFormat sourceFormat;
    private int openAlBufferId;
    private boolean ownsOpenAlBuffer;

    public SoundBuffer(ByteBuffer sourceData, AudioFormat sourceFormat) {
        this.sourceData = sourceData;
        this.sourceFormat = sourceFormat;
    }

    OptionalInt getAlBuffer() {
        if (this.ownsOpenAlBuffer) {
            return OptionalInt.of(this.openAlBufferId);
        }

        ByteBuffer bufferData = this.sourceData;
        if (bufferData == null) {
            return OptionalInt.empty();
        }

        int openAlFormat = resolveOpenAlFormat(this.sourceFormat);
        int createdId = AL10.alGenBuffers();

        if (AL10.alGetError() != AL10.AL_NO_ERROR || createdId == 0) {
            return OptionalInt.empty();
        }

        AL10.alBufferData(
                createdId,
                openAlFormat,
                bufferData,
                (int) this.sourceFormat.getSampleRate()
        );

        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            AL10.alDeleteBuffers(createdId);
            return OptionalInt.empty();
        }

        this.openAlBufferId = createdId;
        this.ownsOpenAlBuffer = true;
        this.sourceData = null;

        return OptionalInt.of(createdId);
    }

    public OptionalInt releaseAlBuffer() {
        OptionalInt result = getAlBuffer();

        if (result.isPresent()) {
            this.ownsOpenAlBuffer = false;
        }

        return result;
    }

    public void discardAlBuffer() {
        if (!this.ownsOpenAlBuffer) {
            return;
        }

        AL10.alDeleteBuffers(this.openAlBufferId);
        if (AL10.alGetError() == AL10.AL_NO_ERROR) {
            this.ownsOpenAlBuffer = false;
            this.openAlBufferId = 0;
        }
    }

    private static int resolveOpenAlFormat(AudioFormat sourceFormat) {
        int channelTotal = sourceFormat.getChannels();
        int bitsPerSample = sourceFormat.getSampleSizeInBits();

        if (channelTotal == 1 && bitsPerSample == 8) {
            return AL10.AL_FORMAT_MONO8;
        }
        if (channelTotal == 1 && bitsPerSample == 16) {
            return AL10.AL_FORMAT_MONO16;
        }
        if (channelTotal == 2 && bitsPerSample == 8) {
            return AL10.AL_FORMAT_STEREO8;
        }
        if (channelTotal == 2 && bitsPerSample == 16) {
            return AL10.AL_FORMAT_STEREO16;
        }

        throw new IllegalArgumentException("Unsupported audio format: " + sourceFormat);
    }
}