package com.mojang.blaze3d.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.testmod.TAudioDataStream;

public class Channel {
    private static final Logger LOGGER = LoggerFactory.getLogger(Channel.class);
    private static final int QUEUED_BUFFER_COUNT = 4;
    public static final int BUFFER_DURATION_SECONDS = 1;

    private final int sourceId;
    private final AtomicBoolean initialized = new AtomicBoolean(true);
    private int streamingBufferSize = 16384;
    private @Nullable TAudioDataStream stream;

    static @Nullable Channel create() {
        int[] sources = new int[1];
        AL10.alGenSources(sources);
        return checkALError("Allocate new source") ? null : new Channel(sources[0]);
    }

    private Channel(int sourceId) {
        this.sourceId = sourceId;
    }

    public void destroy() {
        if (this.initialized.compareAndSet(true, false)) {
            AL10.alSourceStop(this.sourceId);
            checkALError("Stop");

            if (this.stream != null) {
                try {
                    this.stream.close();
                } catch (IOException e) {
                    LOGGER.error("Failed to close audio stream", e);
                }

                this.removeProcessedBuffers();
                this.stream = null;
            }

            AL10.alDeleteSources(new int[] { this.sourceId });
            checkALError("Cleanup");
        }
    }

    public void play() {
        AL10.alSourcePlay(this.sourceId);
    }

    private int getState() {
        return !this.initialized.get() ? 4116 : AL10.alGetSourcei(this.sourceId, 4112);
    }

    public void pause() {
        if (this.getState() == 4114) {
            AL10.alSourcePause(this.sourceId);
        }
    }

    public void unpause() {
        if (this.getState() == 4115) {
            AL10.alSourcePlay(this.sourceId);
        }
    }

    public void stop() {
        if (this.initialized.get()) {
            AL10.alSourceStop(this.sourceId);
            checkALError("Stop");
        }
    }

    public boolean playing() {
        return this.getState() == 4114;
    }

    public boolean stopped() {
        return this.getState() == 4116;
    }

    public void setSelfPosition(float x, float y, float z) {
        AL10.alSourcefv(this.sourceId, 4100, new float[] { x, y, z });
    }

    public void setPitch(float pitch) {
        AL10.alSourcef(this.sourceId, 4099, pitch);
    }

    public void setLooping(boolean looping) {
        AL10.alSourcei(this.sourceId, 4103, looping ? 1 : 0);
    }

    public void setVolume(float volume) {
        AL10.alSourcef(this.sourceId, 4106, volume);
    }

    public void disableAttenuation() {
        AL10.alSourcei(this.sourceId, 53248, 0);
    }

    public void linearAttenuation(float distance) {
        AL10.alSourcei(this.sourceId, 53248, 53251);
        AL10.alSourcef(this.sourceId, 4131, distance);
        AL10.alSourcef(this.sourceId, 4129, 1.0F);
        AL10.alSourcef(this.sourceId, 4128, 0.0F);
    }

    public void setRelative(boolean relative) {
        AL10.alSourcei(this.sourceId, 514, relative ? 1 : 0);
    }

    public void attachStaticBuffer(SoundBuffer soundBuffer) {
        soundBuffer.getAlBuffer().ifPresent(
                bufferId -> AL10.alSourcei(this.sourceId, 4105, bufferId)
        );
    }

    public void attachBufferStream(TAudioDataStream audioStream) {
        this.stream = audioStream;
        AudioFormat audioFormat = audioStream.getFormat();
        this.streamingBufferSize = calculateBufferSize(
                audioFormat,
                BUFFER_DURATION_SECONDS
        );
        this.pumpBuffers(QUEUED_BUFFER_COUNT);
    }

    private static int calculateBufferSize(AudioFormat audioFormat, int seconds) {
        return (int) (
                seconds
                        * audioFormat.getSampleSizeInBits() / 8.0F
                        * audioFormat.getChannels()
                        * audioFormat.getSampleRate()
        );
    }

    private void pumpBuffers(int count) {
        if (this.stream == null) {
            return;
        }

        try {
            for (int index = 0; index < count; index++) {
                ByteBuffer audioData = this.stream.getBuffer(this.streamingBufferSize);
                if (audioData == null) {
                    break;
                }

                new SoundBuffer(audioData, this.stream.getFormat())
                        .releaseAlBuffer()
                        .ifPresent(bufferId ->
                                AL10.alSourceQueueBuffers(
                                        this.sourceId,
                                        new int[] { bufferId }
                                )
                        );
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read from audio stream", e);
        }
    }

    public void updateStream() {
        if (this.stream != null) {
            int processedCount = this.removeProcessedBuffers();
            this.pumpBuffers(processedCount);
        }
    }

    private int removeProcessedBuffers() {
        int processedCount = AL10.alGetSourcei(this.sourceId, 4118);
        if (processedCount > 0) {
            int[] processedIds = new int[processedCount];
            AL10.alSourceUnqueueBuffers(this.sourceId, processedIds);
            checkALError("Unqueue buffers");

            AL10.alDeleteBuffers(processedIds);
            checkALError("Remove processed buffers");
        }

        return processedCount;
    }

    private static boolean checkALError(String operation) {
        int errorCode = AL10.alGetError();
        if (errorCode != AL10.AL_NO_ERROR) {
            LOGGER.error("OpenAL error during {}: {}", operation, errorCode);
            return true;
        }

        return false;
    }
}