package com.mojang.blaze3d.audio;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.util.math.Vec3d;
import org.jspecify.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;

public class Channel {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int QUEUED_BUFFER_COUNT = 4;
    public static final int BUFFER_DURATION_SECONDS = 1;
    private final int source;
    private final AtomicBoolean initialized = new AtomicBoolean(true);
    private int streamingBufferSize = 16384;
    @Nullable
    private AudioStream stream;

    @Nullable
    static Channel create() {
        int[] is = new int[1];
        AL10.alGenSources(is);
        return OpenAlUtil.checkALError("Allocate new source") ? null : new Channel(is[0]);
    }

    private Channel(int i) {
        this.source = i;
    }

    public void destroy() {
        if (this.initialized.compareAndSet(true, false)) {
            AL10.alSourceStop(this.source);
            OpenAlUtil.checkALError("Stop");
            if (this.stream != null) {
                try {
                    this.stream.close();
                } catch (IOException e) {
                    LOGGER.error("Failed to close audio stream", e);
                }

                this.removeProcessedBuffers();
                this.stream = null;
            }

            AL10.alDeleteSources(new int[]{this.source});
            OpenAlUtil.checkALError("Cleanup");
        }
    }

    public void play() {
        AL10.alSourcePlay(this.source);
    }

    private int getState() {
        return !this.initialized.get() ? AL10.AL_STOPPED : AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE);
    }

    public void pause() {
        if (this.getState() == AL10.AL_PLAYING) {
            AL10.alSourcePause(this.source);
        }
    }

    public void unpause() {
        if (this.getState() == AL10.AL_PAUSED) {
            AL10.alSourcePlay(this.source);
        }
    }

    public void stop() {
        if (this.initialized.get()) {
            AL10.alSourceStop(this.source);
            OpenAlUtil.checkALError("Stop");
        }
    }

    public boolean playing() {
        return this.getState() == AL10.AL_PLAYING;
    }

    public boolean stopped() {
        return this.getState() == AL10.AL_STOPPED;
    }

    public void setSelfPosition(Vec3d vec3) {
        AL10.alSourcefv(this.source, AL10.AL_POSITION, new float[]{(float)vec3.x, (float)vec3.y, (float)vec3.z});
    }

    public void setPitch(float f) {
        AL10.alSourcef(this.source, AL10.AL_PITCH, f);
    }

    public void setLooping(boolean bl) {
        AL10.alSourcei(this.source, AL10.AL_LOOPING, bl ? 1 : 0);
    }

    public void setVolume(float f) {
        AL10.alSourcef(this.source, AL10.AL_GAIN, f);
    }

    public void disableAttenuation() {
        AL10.alSourcei(this.source, 53248, 0);
    }

    public void linearAttenuation(float f) {
        AL10.alSourcei(this.source, 53248, 53251);
        AL10.alSourcef(this.source, 4131, f);
        AL10.alSourcef(this.source, 4129, 1.0F);
        AL10.alSourcef(this.source, 4128, 0.0F);
    }   

    public void attachStaticBuffer(SoundBuffer soundBuffer) {
        soundBuffer.getAlBuffer().ifPresent(i -> AL10.alSourcei(this.source, AL10.AL_BUFFER, i));
    }

    public void attachBufferStream(AudioStream audioStream) {
        this.stream = audioStream;
        AudioFormat audioFormat = audioStream.getFormat();
        this.streamingBufferSize = calculateBufferSize(audioFormat, BUFFER_DURATION_SECONDS);
        this.pumpBuffers(QUEUED_BUFFER_COUNT);
    }

    public void setRelative(boolean bl) {
        AL10.alSourcei(this.source, 514, bl ? 1 : 0);
    }

    private static int calculateBufferSize(AudioFormat audioFormat, int seconds) {
        return (int)(seconds * audioFormat.getSampleSizeInBits() / 8.0F * audioFormat.getChannels() * audioFormat.getSampleRate());
    }

    private static int getOpenALFormat(AudioFormat format) {
        boolean stereo = format.getChannels() == 2;
        if (format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED && format.getSampleSizeInBits() == 16) {
            return stereo ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
        }
        throw new IllegalArgumentException("Unsupported audio format: " + format);
    }

    private void pumpBuffers(int i) {
        AudioStream stream = this.stream;
        if (stream != null) {
            try {
                for (int j = 0; j < i; j++) {
                    ByteBuffer byteBuffer = stream.read(this.streamingBufferSize);
                    if (byteBuffer != null) {
                        int[] bufferId = new int[1];
                        AL10.alGenBuffers(bufferId);
                        byteBuffer.rewind();
                        AL10.alBufferData(bufferId[0], getOpenALFormat(stream.getFormat()), byteBuffer, (int) stream.getFormat().getSampleRate());
                        AL10.alSourceQueueBuffers(this.source, bufferId);
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to read from audio stream", e);
            }
        }
    }

    public void updateStream() {
        if (this.stream != null) {
            int i = this.removeProcessedBuffers();
            this.pumpBuffers(i);
        }
    }

    private int removeProcessedBuffers() {
        int i = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_PROCESSED);
        if (i > 0) {
            int[] is = new int[i];
            AL10.alSourceUnqueueBuffers(this.source, is);
            OpenAlUtil.checkALError("Unqueue buffers");
            AL10.alDeleteBuffers(is);
            OpenAlUtil.checkALError("Remove processed buffers");
        }

        return i;
    }
}   