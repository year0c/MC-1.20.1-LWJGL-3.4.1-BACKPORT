package net.minecraft.client.sounds;

import com.google.common.collect.Maps;
import com.mojang.blaze3d.audio.SoundBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.sound.Sound;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

public class SoundBufferLibrary {
   private final ResourceFactory resourceManager;
   private final Map<Identifier, CompletableFuture<SoundBuffer>> cache = Maps.newHashMap();

   public SoundBufferLibrary(ResourceFactory resourceProvider) {
      this.resourceManager = resourceProvider;
   }

   public CompletableFuture<SoundBuffer> getCompleteBuffer(Identifier resourceLocation) {
      return (CompletableFuture<SoundBuffer>) this.cache.computeIfAbsent(resourceLocation, id -> CompletableFuture.supplyAsync(() -> {
         try {
            InputStream inputStream = this.resourceManager.getResource(id).get().getInputStream();
            try (var stream = new JOrbisAudioStream(inputStream)) {
               ByteBuffer byteBuffer = stream.readAll();
               return new SoundBuffer(byteBuffer, stream.getFormat());
            } finally {
               inputStream.close();
            }
         } catch (IOException e) {
            throw new CompletionException(e);
         }
      }, Util.getIoWorkerExecutor()));
   }

   public CompletableFuture<AudioStream> getStream(Identifier resourceLocation, boolean bl) {
      return CompletableFuture.supplyAsync(() -> {
         try {
            InputStream inputStream = this.resourceManager.getResource(resourceLocation).get().getInputStream();
            return (AudioStream) (bl ? new LoopingAudioStream(JOrbisAudioStream::new, inputStream) : new JOrbisAudioStream(inputStream));
         } catch (IOException e) {
            throw new CompletionException(e);
         }
      }, Util.getIoWorkerExecutor());
   }

   public void clear() {
      this.cache.values().forEach(future -> future.thenAccept(buffer -> buffer.discardAlBuffer()));
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