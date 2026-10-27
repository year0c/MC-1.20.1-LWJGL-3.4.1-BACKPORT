package com.mojang.blaze3d.audio;

import java.nio.ByteBuffer;
import java.util.OptionalInt;
import javax.sound.sampled.AudioFormat;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;

public class SoundBuffer {
   private @Nullable ByteBuffer data;
   private final AudioFormat format;
   private boolean hasAlBuffer;
   private int alBuffer;

   public SoundBuffer(ByteBuffer byteBuffer, AudioFormat audioFormat) {
      this.data = byteBuffer;
      this.format = audioFormat;
   }

   OptionalInt getAlBuffer() {
      if (!this.hasAlBuffer) {
         if (this.data == null) {
            return OptionalInt.empty();
         }

         int format = audioFormatToOpenAl(this.format);
         int[] buffers = new int[1];

         AL10.alGenBuffers(buffers);
         if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            return OptionalInt.empty();
         }

         AL10.alBufferData(buffers[0], format, this.data, (int) this.format.getSampleRate());
         if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            AL10.alDeleteBuffers(buffers);
            return OptionalInt.empty();
         }

         this.alBuffer = buffers[0];
         this.hasAlBuffer = true;
         this.data = null;
      }

      return OptionalInt.of(this.alBuffer);
   }

   public void discardAlBuffer() {
      if (this.hasAlBuffer) {
         AL10.alDeleteBuffers(new int[]{this.alBuffer});
         if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            return;
         }
      }

      this.hasAlBuffer = false;
   }

   public OptionalInt releaseAlBuffer() {
      OptionalInt optionalInt = this.getAlBuffer();
      this.hasAlBuffer = false;
      return optionalInt;
   }

   private static int audioFormatToOpenAl(AudioFormat format) {
      int channels = format.getChannels();
      int sampleSize = format.getSampleSizeInBits();

      if (channels == 1 && sampleSize == 8) {
         return AL10.AL_FORMAT_MONO8;
      }
      if (channels == 1 && sampleSize == 16) {
         return AL10.AL_FORMAT_MONO16;
      }
      if (channels == 2 && sampleSize == 8) {
         return AL10.AL_FORMAT_STEREO8;
      }
      if (channels == 2 && sampleSize == 16) {
         return AL10.AL_FORMAT_STEREO16;
      }

      throw new IllegalArgumentException("Unsupported audio format: " + format);
   }
}