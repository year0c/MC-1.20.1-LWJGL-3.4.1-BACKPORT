package com.mojang.blaze3d.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTLinearDistance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Channel {
    private static final Logger LOGGER = LoggerFactory.getLogger(Channel.class);
    private static final int INITIAL_STREAM_BUFFERS = 4;

    private final int sourceId;
    private final AtomicBoolean active = new AtomicBoolean(true);

    private int chunkBytes = 16_384;
    private @Nullable AudioStream audioStream;

    private Channel(int sourceId) {
        this.sourceId = sourceId;
    }

    public static @Nullable Channel create() {
        int[] generatedSources = new int[1];
        AL10.alGenSources(generatedSources);

        if (hasOpenAlError("Create audio source")) {
            return null;
        }

        return new Channel(generatedSources[0]);
    }

    public int getSourceId() {
        return sourceId;
    }

    public void destroy() {
        if (!active.compareAndSet(true, false)) {
            return;
        }

        AL10.alSourceStop(sourceId);
        reportOpenAlError("Stop audio source");

        if (audioStream != null) {
            try {
                audioStream.close();
            } catch (IOException exception) {
                LOGGER.error("Failed to close audio stream", exception);
            }

            discardQueuedBuffers();
            audioStream = null;
        }

        AL10.alDeleteSources(new int[]{sourceId});
        reportOpenAlError("Delete audio source");
    }

    public void startPlayback() {
        AL10.alSourcePlay(sourceId);
    }

    public void pausePlayback() {
        if (readPlaybackState() == AL10.AL_PLAYING) {
            AL10.alSourcePause(sourceId);
        }
    }

    public void resumePlayback() {
        if (readPlaybackState() == AL10.AL_PAUSED) {
            AL10.alSourcePlay(sourceId);
        }
    }

    public void stopPlayback() {
        if (active.get()) {
            AL10.alSourceStop(sourceId);
            reportOpenAlError("Stop audio source");
        }
    }

    public boolean isPlaying() {
        return readPlaybackState() == AL10.AL_PLAYING;
    }

    public boolean isStopped() {
        return readPlaybackState() == AL10.AL_STOPPED;
    }

    private int readPlaybackState() {
        return active.get()
                ? AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE)
                : AL10.AL_STOPPED;
    }

    public void setPosition(float x, float y, float z) {
        AL10.alSource3f(sourceId, AL10.AL_POSITION, x, y, z);
    }

    public void setPitch(float pitch) {
        AL10.alSourcef(sourceId, AL10.AL_PITCH, pitch);
    }

    public void setLooping(boolean looping) {
        AL10.alSourcei(sourceId, AL10.AL_LOOPING, looping ? AL10.AL_TRUE : AL10.AL_FALSE);
    }

    public void setVolume(float volume) {
        AL10.alSourcef(sourceId, AL10.AL_GAIN, volume);
    }

    public void disableAttenuation() {
        AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 0.0f);
    }

    public void useLinearAttenuation(float rolloff) {
        AL10.alDistanceModel(EXTLinearDistance.AL_LINEAR_DISTANCE);
        AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, rolloff);
        AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 1.0F);
        AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, 1_000_000.0F);
    }

    public void setRelativeToListener(boolean relative) {
        AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, relative ? AL10.AL_TRUE : AL10.AL_FALSE);
    }

    public void attachStaticBuffer(SoundBuffer buffer) {
        buffer.getAlBuffer().ifPresent(
                bufferId -> AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId)
        );
    }

    public void attachStream(AudioStream stream) {
        audioStream = stream;
        chunkBytes = computeChunkSize(stream.getFormat(), 1);
        queueStreamBuffers(INITIAL_STREAM_BUFFERS);
    }

    private static int computeChunkSize(AudioFormat format, int seconds) {
        return (int) (
                seconds
                        * (format.getSampleSizeInBits() / 8.0F)
                        * format.getChannels()
                        * format.getSampleRate()
        );
    }

    private void queueStreamBuffers(int count) {
        if (audioStream == null) {
            return;
        }

        try {
            for (int index = 0; index < count; index++) {
                ByteBuffer data = audioStream.getBuffer(chunkBytes);
                if (data == null) {
                    break;
                }

                new SoundBuffer(data, audioStream.getFormat())
                        .releaseAlBuffer()
                        .ifPresent(bufferId ->
                                AL10.alSourceQueueBuffers(sourceId, new int[]{bufferId})
                        );
            }
        } catch (IOException exception) {
            LOGGER.error("Failed to read audio stream", exception);
        }
    }

    public void refreshStream() {
        if (audioStream != null) {
            queueStreamBuffers(discardQueuedBuffers());
        }
    }

    private int discardQueuedBuffers() {
        int processedCount = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
        if (processedCount <= 0) {
            return 0;
        }

        int[] processedIds = new int[processedCount];
        AL10.alSourceUnqueueBuffers(sourceId, processedIds);
        reportOpenAlError("Unqueue audio buffers");

        AL10.alDeleteBuffers(processedIds);
        reportOpenAlError("Delete processed audio buffers");

        return processedCount;
    }

    private static boolean hasOpenAlError(String operation) {
        int errorCode = AL10.alGetError();
        if (errorCode == AL10.AL_NO_ERROR) {
            return false;
        }

        LOGGER.error("{} failed with OpenAL error code {}", operation, errorCode);
        return true;
    }

    private static void reportOpenAlError(String operation) {
        hasOpenAlError(operation);
    }
}