package com.mojang.blaze3d.audio;

import java.nio.ByteBuffer;
import java.util.OptionalInt;
import javax.sound.sampled.AudioFormat;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;

public final class SoundBuffer {
    private @Nullable ByteBuffer pcmData;
    private final AudioFormat audioFormat;
    private boolean ownsBuffer;
    private int bufferId;

    public SoundBuffer(ByteBuffer pcmData, AudioFormat audioFormat) {
        this.pcmData = pcmData;
        this.audioFormat = audioFormat;
    }

    public OptionalInt getAlBuffer() {
        if (ownsBuffer) {
            return OptionalInt.of(bufferId);
        }

        if (pcmData == null) {
            return OptionalInt.empty();
        }

        int openAlFormat = resolveOpenAlFormat(audioFormat);
        if (openAlFormat == 0) {
            return OptionalInt.empty();
        }

        int[] generatedIds = new int[1];
        AL10.alGenBuffers(generatedIds);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            return OptionalInt.empty();
        }

        int createdId = generatedIds[0];
        AL10.alBufferData(
                createdId,
                openAlFormat,
                pcmData,
                (int) audioFormat.getSampleRate()
        );

        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            AL10.alDeleteBuffers(generatedIds);
            AL10.alGetError(); // Clear any error from cleanup.
            return OptionalInt.empty();
        }

        bufferId = createdId;
        ownsBuffer = true;
        pcmData = null;
        return OptionalInt.of(bufferId);
    }

    public void discardAlBuffer() {
        if (!ownsBuffer) {
            return;
        }

        AL10.alDeleteBuffers(new int[]{bufferId});
        if (AL10.alGetError() == AL10.AL_NO_ERROR) {
            ownsBuffer = false;
            bufferId = 0;
        }
    }

    public OptionalInt releaseAlBuffer() {
        OptionalInt result = getAlBuffer();
        if (result.isPresent()) {
            // Ownership transfers to the caller; this object won't delete it.
            ownsBuffer = false;
            bufferId = 0;
        }
        return result;
    }

    private static int resolveOpenAlFormat(AudioFormat format) {
        int channels = format.getChannels();
        int bitsPerSample = format.getSampleSizeInBits();

        if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                && format.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) {
            return 0;
        }

        if (channels == 1 && bitsPerSample == 8) {
            return AL10.AL_FORMAT_MONO8;
        }
        if (channels == 1 && bitsPerSample == 16) {
            return AL10.AL_FORMAT_MONO16;
        }
        if (channels == 2 && bitsPerSample == 8) {
            return AL10.AL_FORMAT_STEREO8;
        }
        if (channels == 2 && bitsPerSample == 16) {
            return AL10.AL_FORMAT_STEREO16;
        }

        return 0;
    }
}