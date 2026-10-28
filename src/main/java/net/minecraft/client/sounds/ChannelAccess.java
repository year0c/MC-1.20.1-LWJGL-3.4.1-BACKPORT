package net.minecraft.client.sounds;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import com.mojang.blaze3d.audio.Library.Pool;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

public class ChannelAccess {
   private final Set<ChannelAccess.ChannelHandle> channels = Sets.newIdentityHashSet();
   final Library library;
   final Executor executor;

   public ChannelAccess(Library library, Executor executor) {
      this.library = library;
      this.executor = executor;
   }

   public CompletableFuture<ChannelAccess.ChannelHandle> createHandle(Pool pool) {
      CompletableFuture<ChannelAccess.ChannelHandle> completableFuture = new CompletableFuture<>();
      this.executor.execute(() -> {
         Channel channel = this.library.acquireChannel(pool);
         if (channel != null) {
            ChannelAccess.ChannelHandle channelHandle = new ChannelAccess.ChannelHandle(channel);
            this.channels.add(channelHandle);
            completableFuture.complete(channelHandle);
         } else {
            completableFuture.complete(null);
         }
      });
      return completableFuture;
   }

   @SuppressWarnings("null")
   public void executeOnChannels(Consumer<Stream<Channel>> consumer) {
      this.executor.execute(() -> consumer.accept(this.channels.stream().map(channelHandle -> channelHandle.channel).filter(Objects::nonNull)));
   }

   public void scheduleTick() {
      this.executor.execute(() -> {
         for (Iterator<ChannelAccess.ChannelHandle> it = this.channels.iterator(); it.hasNext(); ) {
            ChannelAccess.ChannelHandle channelHandle = it.next();
            Channel channel = channelHandle.channel;
            if (channel != null) {
               channel.updateStream();
               if (channel.stopped()) {
                  channelHandle.release();
                  it.remove();
               }
            }
         }
      });
   }

   public void clear() {
      this.channels.forEach(handle -> handle.release());
      this.channels.clear();
   }

   public class ChannelHandle {
      @Nullable
      Channel channel;
      private boolean stopped;

      public boolean isStopped() {
         return this.stopped;
      }

      public ChannelHandle(final Channel channel) {
         this.channel = channel;
      }

      public void execute(Consumer<Channel> consumer) {
         ChannelAccess.this.executor.execute(() -> {
            if (this.channel != null) {
               consumer.accept(this.channel);
            }
         });
      }

      public void release() {
         this.stopped = true;
         ChannelAccess.this.library.releaseChannel(this.channel);
         this.channel = null;
      }
   }
}   