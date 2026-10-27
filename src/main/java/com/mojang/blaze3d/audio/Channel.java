package com.mojang.blaze3d.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.testmod.AudioDataStream;

public class Channel {
   private static final Logger LOGGER = LoggerFactory.getLogger(Channel.class);
   private static final int QUEUED_BUFFER_COUNT = 4;
   public static final int BUFFER_DURATION_SECONDS = 1;

   private final int source;
   private final AtomicBoolean initialized = new AtomicBoolean(true);
   private int streamingBufferSize = 16384;
   private @Nullable AudioDataStream stream;

   static @Nullable Channel create() {
      int[] sources = new int[1];
      AL10.alGenSources(sources);
      return checkALError("Allocate new source") ? null : new Channel(sources[0]);
   }

   private Channel(int source) {
      this.source = source;
   }

   public void destroy() {
      if (this.initialized.compareAndSet(true, false)) {
         AL10.alSourceStop(this.source);
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

         AL10.alDeleteSources(new int[]{this.source});
         checkALError("Cleanup");
      }
   }

   public void play() {
      AL10.alSourcePlay(this.source);
   }

   private int getState() {
      return !this.initialized.get() ? 4116 : AL10.alGetSourcei(this.source, 4112);
   }

   public void pause() {
      if (this.getState() == 4114) {
         AL10.alSourcePause(this.source);
      }
   }

   public void unpause() {
      if (this.getState() == 4115) {
         AL10.alSourcePlay(this.source);
      }
   }

   public void stop() {
      if (this.initialized.get()) {
         AL10.alSourceStop(this.source);
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
      AL10.alSourcefv(this.source, 4100, new float[]{x, y, z});
   }

   public void setPitch(float pitch) {
      AL10.alSourcef(this.source, 4099, pitch);
   }

   public void setLooping(boolean looping) {
      AL10.alSourcei(this.source, 4103, looping ? 1 : 0);
   }

   public void setVolume(float volume) {
      AL10.alSourcef(this.source, 4106, volume);
   }

   public void disableAttenuation() {
      AL10.alSourcei(this.source, 53248, 0);
   }

   public void linearAttenuation(float distance) {
      AL10.alSourcei(this.source, 53248, 53251);
      AL10.alSourcef(this.source, 4131, distance);
      AL10.alSourcef(this.source, 4129, 1.0F);
      AL10.alSourcef(this.source, 4128, 0.0F);
   }

   public void setRelative(boolean relative) {
      AL10.alSourcei(this.source, 514, relative ? 1 : 0);
   }

   public void attachStaticBuffer(SoundBuffer soundBuffer) {
      soundBuffer.getAlBuffer().ifPresent(
            buffer -> AL10.alSourcei(this.source, 4105, buffer)
      );
   }

   public void attachBufferStream(AudioDataStream audioStream) {
      this.stream = audioStream;
      AudioFormat audioFormat = audioStream.getFormat();
      this.streamingBufferSize = calculateBufferSize(audioFormat, BUFFER_DURATION_SECONDS);
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
      if (this.stream != null) {
         try {
            for (int j = 0; j < count; j++) {
               ByteBuffer byteBuffer = this.stream.getBuffer(this.streamingBufferSize);
               if (byteBuffer != null) {
                  new SoundBuffer(byteBuffer, this.stream.getFormat())
                        .releaseAlBuffer()
                        .ifPresent(buffer ->
                              AL10.alSourceQueueBuffers(this.source, new int[]{buffer})
                        );
               }
            }
         } catch (IOException e) {
            LOGGER.error("Failed to read from audio stream", e);
         }
      }
   }

   public void updateStream() {
      if (this.stream != null) {
         int processed = this.removeProcessedBuffers();
         this.pumpBuffers(processed);
      }
   }

   private int removeProcessedBuffers() {
      int processed = AL10.alGetSourcei(this.source, 4118);
      if (processed > 0) {
         int[] buffers = new int[processed];
         AL10.alSourceUnqueueBuffers(this.source, buffers);
         checkALError("Unqueue buffers");

         AL10.alDeleteBuffers(buffers);
         checkALError("Remove processed buffers");
      }

      return processed;
   }

   private static boolean checkALError(String operation) {
      int error = AL10.alGetError();
      if (error != AL10.AL_NO_ERROR) {
         LOGGER.error("OpenAL error during {}: {}", operation, error);
         return true;
      }

      return false;
   }
}