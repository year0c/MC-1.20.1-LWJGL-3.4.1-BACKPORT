package net.minecraft.client.sounds;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import com.mojang.blaze3d.audio.Library;
import com.mojang.blaze3d.audio.Listener;
import com.mojang.blaze3d.audio.ListenerTransform;
import com.mojang.blaze3d.audio.Library.Pool;
import com.mojang.logging.LogUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.util.Util;
import net.minecraft.client.render.Camera;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundInstance.AttenuationType;
import net.minecraft.client.sounds.ChannelAccess.ChannelHandle;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class SoundEngine {
   private static final Marker MARKER = MarkerFactory.getMarker("SOUNDS");
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Set<Identifier> ONLY_WARN_ONCE = Sets.newHashSet();
   public static final String MISSING_SOUND = "FOR THE DEBUG!";
   public static final String OPEN_AL_SOFT_PREFIX = "OpenAL Soft on ";
   public static final int OPEN_AL_SOFT_PREFIX_LENGTH = "OpenAL Soft on ".length();
   private final SoundManager soundManager;
   private final GameOptions options;
   private boolean loaded;
   private final Library library = new Library();
   private final Listener listener = this.library.getListener();
   private final SoundBufferLibrary soundBuffers;
   private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "Sound engine");
      thread.setDaemon(true);
      return thread;
   });
   private final ChannelAccess channelAccess = new ChannelAccess(this.library, this.executor);
   private int tickCount;
   private long lastDeviceCheckTime;
   private final AtomicReference<DeviceCheckState> devicePoolState = new AtomicReference<>(DeviceCheckState.NO_CHANGE);
   private final Map<SoundInstance, ChannelHandle> instanceToChannel = Maps.newHashMap();
   private final Multimap<SoundCategory, SoundInstance> instanceBySource = HashMultimap.create();
   private final List<TickableSoundInstance> tickingSounds = Lists.newArrayList();
   private final Map<SoundInstance, Integer> queuedSounds = Maps.newHashMap();
   private final Map<SoundInstance, Integer> soundDeleteTime = Maps.newHashMap();
   private final List<SoundEventListener> listeners = Lists.newArrayList();
   private final List<TickableSoundInstance> queuedTickableSounds = Lists.newArrayList();
   private final List<SoundManager.TSound> preloadQueue = Lists.newArrayList();

   public interface SoundEventListener {
      void onPlaySound(SoundInstance soundInstance, float distance);
   }

   public SoundEngine(SoundManager soundManager, GameOptions options, ResourceFactory resourceFactory) {
      this.soundManager = soundManager;
      this.options = options;
      this.soundBuffers = new SoundBufferLibrary(resourceFactory);
   }

   public void reload() {
      ONLY_WARN_ONCE.clear();

      for (net.minecraft.sound.SoundEvent soundEvent : Registries.SOUND_EVENT) {
         Identifier id = soundEvent.getId();
         if (!this.soundManager.hasSound(id)) {
            LOGGER.warn("Missing sound for event: {}", id);
            ONLY_WARN_ONCE.add(id);
         }
      }

      this.destroy();
      this.loadLibrary();
   }

   private synchronized void loadLibrary() {
      if (!this.loaded) {
         System.out.println("[SE] loadLibrary() called");
         try {
               String device = this.options.getSoundDevice().getValue();
               System.out.println("[SE] device=" + device);
               this.library.init(device.isEmpty() ? null : device, this.options.getDirectionalAudio().getValue());
               this.listener.reset();
               this.listener.setGain(this.options.getSoundVolume(SoundCategory.MASTER));
               this.soundBuffers.preload(this.preloadQueue).thenRun(this.preloadQueue::clear);
               this.loaded = true;
               System.out.println("[SE] OpenAL initialized on: " + this.library.getCurrentDeviceName());
         } catch (RuntimeException e) {
               System.out.println("[SE] FAILED: " + e.getMessage());
               e.printStackTrace();
         }
      }
   }   

   private float getVolume(@Nullable SoundCategory category) {
      return category != null && category != SoundCategory.MASTER ? this.options.getSoundVolume(category) : 1.0F;
   }

   public void updateCategoryVolume(SoundCategory category, float volume) {
      if (this.loaded) {
         if (category == SoundCategory.MASTER) {
            this.listener.setGain(volume);
         } else {
            this.instanceToChannel.forEach((soundInstance, channelHandle) -> {
               float vol = this.calculateVolume(soundInstance);
               channelHandle.execute(channel -> {
                  if (vol <= 0.0F) {
                     channel.stop();
                  } else {
                     channel.setVolume(vol);
                  }
               });
            });
         }
      }
   }

   public void destroy() {
      if (this.loaded) {
         this.stopAll();
         this.soundBuffers.clear();
         this.library.cleanup();
         this.loaded = false;
      }
   }

   public void emergencyShutdown() {
      if (this.loaded) {
         this.library.cleanup();
      }
   }

   public void stop(SoundInstance soundInstance) {
      if (this.loaded) {
         ChannelHandle channelHandle = this.instanceToChannel.get(soundInstance);
         if (channelHandle != null) {
            channelHandle.execute(channel -> channel.stop());
         }
      }
   }

   public void stopAll() {
      if (this.loaded) {
         try {
            this.executor.submit(() -> {}).get();
         } catch (Exception ignored) {}
         this.instanceToChannel.values().forEach(channelHandle -> channelHandle.execute(channel -> channel.stop()));
         this.instanceToChannel.clear();
         this.channelAccess.clear();
         this.queuedSounds.clear();
         this.tickingSounds.clear();
         this.instanceBySource.clear();
         this.soundDeleteTime.clear();
         this.queuedTickableSounds.clear();
      }
   }

   public void addEventListener(SoundEventListener soundEventListener) {
      this.listeners.add(soundEventListener);
   }

   public void removeEventListener(SoundEventListener soundEventListener) {
      this.listeners.remove(soundEventListener);
   }

   private boolean shouldChangeDevice() {
      if (this.library.isCurrentDeviceDisconnected()) {
         LOGGER.info("Audio device was lost!");
         return true;
      }

      long now = System.currentTimeMillis();
      boolean shouldCheck = now - this.lastDeviceCheckTime >= 1000L;
      if (shouldCheck) {
         this.lastDeviceCheckTime = now;
         if (this.devicePoolState.compareAndSet(DeviceCheckState.NO_CHANGE, DeviceCheckState.ONGOING)) {
            String device = this.options.getSoundDevice().getValue();
            Util.getIoWorkerExecutor().execute(() -> {
               if (device.isEmpty()) {
                  if (this.library.hasDefaultDeviceChanged()) {
                     LOGGER.info("System default audio device has changed!");
                     this.devicePoolState.compareAndSet(DeviceCheckState.ONGOING, DeviceCheckState.CHANGE_DETECTED);
                  }
               } else if (!this.library.getCurrentDeviceName().equals(device) && this.library.getAvailableSoundDevices().contains(device)) {
                  LOGGER.info("Preferred audio device has become available!");
                  this.devicePoolState.compareAndSet(DeviceCheckState.ONGOING, DeviceCheckState.CHANGE_DETECTED);
               }

               this.devicePoolState.compareAndSet(DeviceCheckState.ONGOING, DeviceCheckState.NO_CHANGE);
            });
         }
      }

      return this.devicePoolState.compareAndSet(DeviceCheckState.CHANGE_DETECTED, DeviceCheckState.NO_CHANGE);
   }

   public void tick(boolean paused) {
      if (this.shouldChangeDevice()) {
         this.reload();
      }

      if (!paused) {
         this.tickNonPaused();
      }

      this.channelAccess.scheduleTick();
   }

   private void tickNonPaused() {
      this.tickCount++;
      for (TickableSoundInstance tickableSoundInstance : this.queuedTickableSounds) {
         this.play((SoundInstance) tickableSoundInstance);
      }
      this.queuedTickableSounds.clear();

      for (TickableSoundInstance tickableSoundInstance : this.tickingSounds) {
         tickableSoundInstance.tick();
         float volume = this.calculateVolume((SoundInstance) tickableSoundInstance);
         float pitch = this.calculatePitch((SoundInstance) tickableSoundInstance);
         Vec3d pos = new Vec3d(tickableSoundInstance.getX(), tickableSoundInstance.getY(), tickableSoundInstance.getZ());
         ChannelHandle channelHandle = this.instanceToChannel.get((SoundInstance) tickableSoundInstance);
         if (channelHandle != null) {
            channelHandle.execute(channel -> {
               channel.setVolume(volume);
               channel.setPitch(pitch);
               channel.setSelfPosition(pos);
            });
         }
      }

      Iterator<Entry<SoundInstance, ChannelHandle>> iterator = this.instanceToChannel.entrySet().iterator();

      while (iterator.hasNext()) {
         Entry<SoundInstance, ChannelHandle> entry = iterator.next();
         ChannelHandle channelHandle = entry.getValue();
         SoundInstance soundInstance = entry.getKey();
         float volume = this.options.getSoundVolume(soundInstance.getCategory());
         if (volume <= 0.0F) {
            channelHandle.execute(channel -> channel.stop());
            iterator.remove();
         } else if (channelHandle.isStopped()) {
            int deleteTime = this.soundDeleteTime.getOrDefault(soundInstance, 0);
            if (deleteTime <= this.tickCount) {
               if (shouldLoopManually(soundInstance)) {
                  this.queuedSounds.put(soundInstance, this.tickCount + soundInstance.getRepeatDelay());
               }

               iterator.remove();
               LOGGER.debug(MARKER, "Removed channel {} because it's not playing anymore", channelHandle);
               this.soundDeleteTime.remove(soundInstance);

               try {
                  this.instanceBySource.remove(soundInstance.getCategory(), soundInstance);
               } catch (RuntimeException ignored) {}

               if (soundInstance instanceof TickableSoundInstance) {
                  this.tickingSounds.remove((TickableSoundInstance) soundInstance);
               }
            }
         }
      }

      Iterator<Entry<SoundInstance, Integer>> iterator2 = this.queuedSounds.entrySet().iterator();

      while (iterator2.hasNext()) {
         Entry<SoundInstance, Integer> entry = iterator2.next();
         if (this.tickCount >= entry.getValue()) {
            SoundInstance soundInstance = entry.getKey();
            if (soundInstance instanceof TickableSoundInstance) {
               ((TickableSoundInstance) soundInstance).tick();
            }

            this.play(soundInstance);
            iterator2.remove();
         }
      }
   }

   private static boolean requiresManualLooping(SoundInstance soundInstance) {
      return soundInstance.getRepeatDelay() > 0;
   }

   private static boolean shouldLoopManually(SoundInstance soundInstance) {
      return soundInstance.isRepeatable() && requiresManualLooping(soundInstance);
   }

   private static boolean shouldLoopAutomatically(SoundInstance soundInstance) {
      return soundInstance.isRepeatable() && !requiresManualLooping(soundInstance);
   }

   public boolean isActive(SoundInstance soundInstance) {
      if (!this.loaded) {
         return false;
      }
      return this.soundDeleteTime.containsKey(soundInstance) && this.soundDeleteTime.get(soundInstance) <= this.tickCount
         ? true
         : this.instanceToChannel.containsKey(soundInstance);
   }

   public void play(SoundInstance soundInstance) {
      if (this.loaded) {
         if (soundInstance.canPlay()) {
            Identifier id = soundInstance.getId();
            if (!this.soundManager.hasSound(id)) {
               if (ONLY_WARN_ONCE.add(id)) {
                  LOGGER.warn(MARKER, "Unable to play unknown soundEvent: {}", id);
               }
               return;
            }

            Sound sound = soundInstance.getSound();
            if (sound == SoundManager.EMPTY_SOUND) {
               if (ONLY_WARN_ONCE.add(id)) {
                  LOGGER.warn(MARKER, "Unable to play empty soundEvent: {}", id);
               }
               return;
            }

            float volume = soundInstance.getVolume();
            float attenuationDistance = Math.max(volume, 1.0F) * 16.0F;
            SoundCategory category = soundInstance.getCategory();
            float finalVolume = this.calculateVolume(volume, category);
            float pitch = this.calculatePitch(soundInstance);
            AttenuationType attenuation = soundInstance.getAttenuationType();
            boolean relative = soundInstance.isRelative();

            if (finalVolume == 0.0F && !soundInstance.shouldAlwaysPlay()) {
               LOGGER.debug(MARKER, "Skipped playing sound {}, volume was zero.", sound.getIdentifier());
               return;
            }

            Vec3d pos = new Vec3d(soundInstance.getX(), soundInstance.getY(), soundInstance.getZ());

            if (!this.listeners.isEmpty()) {
               float distance = !relative && attenuation != AttenuationType.NONE ? attenuationDistance : Float.POSITIVE_INFINITY;
               for (SoundEventListener soundEventListener : this.listeners) {
                  soundEventListener.onPlaySound(soundInstance, distance);
               }
            }

            if (this.listener.getGain() <= 0.0F) {
               LOGGER.debug(MARKER, "Skipped playing soundEvent: {}, master volume was zero", id);
               return;
            }

            boolean loopAutomatically = shouldLoopAutomatically(soundInstance);
            boolean shouldStream = sound.isStreamed();
            CompletableFuture<ChannelHandle> future = this.channelAccess
               .createHandle(shouldStream ? Pool.STREAMING : Pool.STATIC);
            ChannelHandle channelHandle = future.join();

            if (channelHandle == null) {
               LOGGER.warn("Failed to create new sound handle");
               return;
            }

            LOGGER.debug(MARKER, "Playing sound {} for event {}", sound.getIdentifier(), id);
            this.soundDeleteTime.put(soundInstance, this.tickCount + 20);
            this.instanceToChannel.put(soundInstance, channelHandle);
            this.instanceBySource.put(category, soundInstance);
            channelHandle.execute(channel -> {
               channel.setPitch(pitch);
               channel.setVolume(finalVolume);
               if (attenuation == AttenuationType.LINEAR) {
                  channel.linearAttenuation(attenuationDistance);
               } else {
                  channel.disableAttenuation();
               }

               channel.setLooping(loopAutomatically && !shouldStream);
               channel.setSelfPosition(pos);
               channel.setRelative(relative);
            });

            if (!shouldStream) {
               this.soundBuffers.getCompleteBuffer(sound.getIdentifier()).thenAccept(soundBuffer -> channelHandle.execute(channel -> {
                  channel.attachStaticBuffer(soundBuffer);
                  channel.play();
               }));
            } else {
               this.soundBuffers.getStream(sound.getIdentifier(), loopAutomatically).thenAccept(audioStream -> channelHandle.execute(channel -> {
                  channel.attachBufferStream(audioStream);
                  channel.play();
               }));
            }

            if (soundInstance instanceof TickableSoundInstance) {
               this.tickingSounds.add((TickableSoundInstance) soundInstance);
            }
         }
      }
   }

   public void queueTickingSound(TickableSoundInstance tickableSoundInstance) {
      this.queuedTickableSounds.add(tickableSoundInstance);
   }

   public void requestPreload(SoundManager.TSound sound) {
      this.preloadQueue.add(sound);
   }

   private float calculatePitch(SoundInstance soundInstance) {
      return MathHelper.clamp(soundInstance.getPitch(), 0.5F, 2.0F);
   }

   private float calculateVolume(SoundInstance soundInstance) {
      return this.calculateVolume(soundInstance.getVolume(), soundInstance.getCategory());
   }

   private float calculateVolume(float volume, SoundCategory category) {
      return MathHelper.clamp(volume * this.getVolume(category), 0.0F, 1.0F);
   }

   public void pause() {
      if (this.loaded) {
         this.channelAccess.executeOnChannels(stream -> stream.forEach(channel -> channel.pause()));
      }
   }

   public void resume() {
      if (this.loaded) {
         this.channelAccess.executeOnChannels(stream -> stream.forEach(channel -> channel.unpause()));
      }
   }

   public void playDelayed(SoundInstance soundInstance, int delay) {
      this.queuedSounds.put(soundInstance, this.tickCount + delay);
   }

   public void updateSource(Camera camera) {
      if (this.loaded) {
         Vec3d pos = camera.getPos();
         float yawRad = (float) Math.toRadians(camera.getYaw());
         float pitchRad = (float) Math.toRadians(camera.getPitch());
         float cosPitch = (float) Math.cos(pitchRad);
         float sinPitch = (float) Math.sin(pitchRad);
         float cosYaw = (float) Math.cos(yawRad);
         float sinYaw = (float) Math.sin(yawRad);

         Vec3d forward = new Vec3d(-sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch).normalize();
         Vec3d up = forward.crossProduct(new Vec3d(0.0, 1.0, 0.0)).crossProduct(forward).normalize();

         this.executor.execute(() -> this.listener.setTransform(new ListenerTransform(pos, forward, up)));
      }
   }

   public void stop(@Nullable Identifier id, @Nullable SoundCategory category) {
      if (category != null) {
         for (SoundInstance soundInstance : this.instanceBySource.get(category)) {
            if (id == null || soundInstance.getId().equals(id)) {
               this.stop(soundInstance);
            }
         }
      } else if (id == null) {
         this.stopAll();
      } else {
         for (SoundInstance soundInstance : this.instanceToChannel.keySet()) {
            if (id == null || soundInstance.getId().equals(id)) {
               this.stop(soundInstance);
            }
         }
      }
   }

   public String getDebugString() {
      return this.library.getDebugString();
   }

   public List<String> getAvailableSoundDevices() {
      return this.library.getAvailableSoundDevices();
   }

   public ListenerTransform getListenerTransform() {
      return this.listener.getTransform();
   }

   enum DeviceCheckState {
      ONGOING,
      CHANGE_DETECTED,
      NO_CHANGE
   }
}   