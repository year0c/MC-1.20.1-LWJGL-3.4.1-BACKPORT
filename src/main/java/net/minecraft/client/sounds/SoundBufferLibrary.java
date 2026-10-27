package net.minecraft.client.sounds;

import com.google.common.collect.Maps;
import com.mojang.blaze3d.audio.SoundBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.util.Util;
import net.minecraft.client.sound.Sound;
import net.minecraft.util.Identifier;
import net.minecraft.resource.ResourceFactory;

public class SoundBufferLibrary {
   private final ResourceFactory resourceManager;
   private final Map<Identifier, CompletableFuture<SoundBuffer>> cache = Maps.newHashMap();

   public SoundBufferLibrary(ResourceFactory resourceProvider) {
      this.resourceManager = resourceProvider;
   }

   public CompletableFuture<SoundBuffer> getCompleteBuffer(Identifier resourceLocation) {
      return (CompletableFuture<SoundBuffer>)this.cache.computeIfAbsent(resourceLocation, resourceLocationx -> CompletableFuture.supplyAsync(() -> {
         try {
            InputStream inputStream = this.resourceManager.getResource(resourceLocationx).get().getInputStream();

            SoundBuffer var5;
            try {
               FiniteAudioStream finiteAudioStream = new JOrbisAudioStream(inputStream);

               try {
                  ByteBuffer byteBuffer = finiteAudioStream.readAll();
                  var5 = new SoundBuffer(byteBuffer, finiteAudioStream.getFormat());
               } catch (Throwable var8) {
                  try {
                     finiteAudioStream.close();
                  } catch (Throwable var7) {
                     var8.addSuppressed(var7);
                  }

                  throw var8;
               }

               finiteAudioStream.close();
            } catch (Throwable var9) {
               if (inputStream != null) {
                  try {
                     inputStream.close();
                  } catch (Throwable var6) {
                     var9.addSuppressed(var6);
                  }
               }

               throw var9;
            }

            if (inputStream != null) {
               inputStream.close();
            }

            return var5;
         } catch (IOException iOException) {
            throw new CompletionException(iOException);
         }
      }, Util.getIoWorkerExecutor()));
   }

   public CompletableFuture<AudioStream> getStream(Identifier resourceLocation, boolean bl) {
      return CompletableFuture.supplyAsync(() -> {
         try {
            InputStream inputStream = this.resourceManager.getResource(resourceLocation).get().getInputStream();
            return (AudioStream)(bl ? new LoopingAudioStream(JOrbisAudioStream::new, inputStream) : new JOrbisAudioStream(inputStream));
         } catch (IOException iOException) {
            throw new CompletionException(iOException);
         }
      }, Util.getIoWorkerExecutor());
   }

   public void clear() {
      this.cache.values().forEach(completableFuture -> completableFuture.thenAccept(SoundBuffer::discardAlBuffer));
      this.cache.clear();
   }

   public CompletableFuture<?> preload(Collection<Sound> collection) {
      List<CompletableFuture<?>> futures = new ArrayList<>();
      for (Sound sound : collection) {
         futures.add(this.getCompleteBuffer(sound.getIdentifier()));
      }
      return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
   }    
}   

