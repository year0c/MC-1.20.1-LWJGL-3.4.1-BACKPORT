package net.minecraft.client.sounds;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.mojang.blaze3d.audio.Channel;
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
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;
import net.minecraft.client.render.Camera;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.client.sound.SoundInstance.Attenuation;
import net.minecraft.client.sounds.ChannelAccess.ChannelHandle;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.sounds.SoundEvent;
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
   private static final float PITCH_MIN = 0.5F;
   private static final float PITCH_MAX = 2.0F;
   private static final float VOLUME_MIN = 0.0F;
   private static final float VOLUME_MAX = 1.0F;
   private static final int MIN_SOURCE_LIFETIME = 20;
   private static final Set<Identifier> ONLY_WARN_ONCE = Sets.newHashSet();
   private static final long DEFAULT_DEVICE_CHECK_INTERVAL_MS = 1000L;
   public static final String MISSING_SOUND = "FOR THE DEBUG!";
   public static final String OPEN_AL_SOFT_PREFIX = "OpenAL Soft on ";
   public static final int OPEN_AL_SOFT_PREFIX_LENGTH = "OpenAL Soft on ".length();
   private final SoundManager soundManager;
   private final GameOptions options;
   private boolean loaded;
   private final Library library = new Library();
   private final Listener listener = this.library.getListener();
   private final SoundBufferLibrary soundBuffers;
   private final SoundEngineExecutor executor = new SoundEngineExecutor();
   private final ChannelAccess channelAccess = new ChannelAccess(this.library, this.executor);
   private int tickCount;
   private long lastDeviceCheckTime;
   private final AtomicReference<SoundEngine.DeviceCheckState> devicePoolState = new AtomicReference<>(SoundEngine.DeviceCheckState.NO_CHANGE);
   private final Map<SoundInstance, ChannelHandle> instanceToChannel = Maps.newHashMap();
   private final Multimap<SoundCategory, SoundInstance> instanceBySource = HashMultimap.create();
   private final List<TickableSoundInstance> tickingSounds = Lists.newArrayList();
   private final Map<SoundInstance, Integer> queuedSounds = Maps.newHashMap();
   private final Map<SoundInstance, Integer> soundDeleteTime = Maps.newHashMap();
   private final List<SoundEventListener> listeners = Lists.newArrayList();
   private final List<TickableSoundInstance> queuedTickableSounds = Lists.newArrayList();
   private final List<Sound> preloadQueue = Lists.newArrayList();

   public SoundEngine(SoundManager soundManager, GameOptions options, ResourceFactory resourceFactory) {
      this.soundManager = soundManager;
      this.options = options;
      this.soundBuffers = new SoundBufferLibrary(resourceFactory);
   }

   public void reload() {
      ONLY_WARN_ONCE.clear();

      for (SoundEvent soundEvent : Registries.SOUND_EVENT) {
         Identifier id = soundEvent.id();
         if (this.soundManager.getSoundEvent(id) == null) {
            LOGGER.warn("Missing sound for event: {}", id);
            ONLY_WARN_ONCE.add(id);
         }
      }

      this.destroy();
      this.loadLibrary();
   }

   private synchronized void loadLibrary() {
      if (!this.loaded) {
         try {
            String device = this.options.getSoundDevice();
            this.library.init(device.isEmpty() ? null : device, this.options.isDirectionalAudioEnabled());
            this.listener.reset();
            this.listener.setGain(this.options.getSoundCategoryVolume(SoundCategory.MASTER));
            this.soundBuffers.preload(this.preloadQueue).thenRun(this.preloadQueue::clear);
            this.loaded = true;
            LOGGER.info(MARKER, "Sound engine started");
         } catch (RuntimeException e) {
            LOGGER.error(MARKER, "Error starting SoundSystem. Turning off sounds & music", e);
         }
      }
   }

   private float getVolume(@Nullable SoundCategory category) {
      return category != null && category != SoundCategory.MASTER ? this.options.getSoundCategoryVolume(category) : 1.0F;
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
            channelHandle.execute(Channel::stop);
         }
      }
   }

   public void stopAll() {
      if (this.loaded) {
         this.executor.flush();
         this.instanceToChannel.values().forEach(channelHandle -> channelHandle.execute(Channel::stop));
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
         if (this.devicePoolState.compareAndSet(SoundEngine.DeviceCheckState.NO_CHANGE, SoundEngine.DeviceCheckState.ONGOING)) {
            String device = this.options.getSoundDevice();
            Util.getIoWorkerExecutor().execute(() -> {
               if (device.isEmpty()) {
                  if (this.library.hasDefaultDeviceChanged()) {
                     LOGGER.info("System default audio device has changed!");
                     this.devicePoolState.compareAndSet(SoundEngine.DeviceCheckState.ONGOING, SoundEngine.DeviceCheckState.CHANGE_DETECTED);
                  }
               } else if (!this.library.getCurrentDeviceName().equals(device) && this.library.getAvailableSoundDevices().contains(device)) {
                  LOGGER.info("Preferred audio device has become available!");
                  this.devicePoolState.compareAndSet(SoundEngine.DeviceCheckState.ONGOING, SoundEngine.DeviceCheckState.CHANGE_DETECTED);
               }

               this.devicePoolState.compareAndSet(SoundEngine.DeviceCheckState.ONGOING, SoundEngine.DeviceCheckState.NO_CHANGE);
            });
         }
      }

      return this.devicePoolState.compareAndSet(SoundEngine.DeviceCheckState.CHANGE_DETECTED, SoundEngine.DeviceCheckState.NO_CHANGE);
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
      this.queuedTickableSounds.stream().filter(SoundInstance::canPlaySound).forEach(this::play);
      this.queuedTickableSounds.clear();

      for (TickableSoundInstance tickableSoundInstance : this.tickingSounds) {
         if (!tickableSoundInstance.canPlaySound()) {
            this.stop(tickableSoundInstance);
         }

         tickableSoundInstance.tick();
         if (tickableSoundInstance.isStopped()) {
            this.stop(tickableSoundInstance);
         } else {
            float volume = this.calculateVolume(tickableSoundInstance);
            float pitch = this.calculatePitch(tickableSoundInstance);
            Vec3d pos = new Vec3d(tickableSoundInstance.getX(), tickableSoundInstance.getY(), tickableSoundInstance.getZ());
            ChannelHandle channelHandle = this.instanceToChannel.get(tickableSoundInstance);
            if (channelHandle != null) {
               channelHandle.execute(channel -> {
                  channel.setVolume(volume);
                  channel.setPitch(pitch);
                  channel.setSelfPosition(pos);
               });
            }
         }
      }

      Iterator<Entry<SoundInstance, ChannelHandle>> iterator = this.instanceToChannel.entrySet().iterator();

      while (iterator.hasNext()) {
         Entry<SoundInstance, ChannelHandle> entry = iterator.next();
         ChannelHandle channelHandle = entry.getValue();
         SoundInstance soundInstance = entry.getKey();
         float volume = this.options.getSoundCategoryVolume(soundInstance.getCategory());
         if (volume <= 0.0F) {
            channelHandle.execute(Channel::stop);
            iterator.remove();
         } else if (channelHandle.isStopped()) {
            int deleteTime = this.soundDeleteTime.getOrDefault(soundInstance, 0);
            if (deleteTime <= this.tickCount) {
               if (shouldLoopManually(soundInstance)) {
                  this.queuedSounds.put(soundInstance, this.tickCount + soundInstance.getDelay());
               }

               iterator.remove();
               LOGGER.debug(MARKER, "Removed channel {} because it's not playing anymore", channelHandle);
               this.soundDeleteTime.remove(soundInstance);

               try {
                  this.instanceBySource.remove(soundInstance.getCategory(), soundInstance);
               } catch (RuntimeException ignored) {
               }

               if (soundInstance instanceof TickableSoundInstance) {
                  this.tickingSounds.remove(soundInstance);
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
               ((TickableSoundInstance)soundInstance).tick();
            }

            this.play(soundInstance);
            iterator2.remove();
         }
      }
   }

   private static boolean requiresManualLooping(SoundInstance soundInstance) {
      return soundInstance.getDelay() > 0;
   }

   private static boolean shouldLoopManually(SoundInstance soundInstance) {
      return soundInstance.isLooping() && requiresManualLooping(soundInstance);
   }

   private static boolean shouldLoopAutomatically(SoundInstance soundInstance) {
      return soundInstance.isLooping() && !requiresManualLooping(soundInstance);
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
         if (soundInstance.canPlaySound()) {
            WeightedSoundSet weightedSoundSet = soundInstance.resolve(this.soundManager);
            Identifier id = soundInstance.getLocation();
            if (weightedSoundSet == null) {
               if (ONLY_WARN_ONCE.add(id)) {
                  LOGGER.warn(MARKER, "Unable to play unknown soundEvent: {}", id);
               }
            } else {
               Sound sound = soundInstance.getSound();
               if (sound != SoundManager.INTENTIONALLY_EMPTY_SOUND) {
                  if (sound == SoundManager.EMPTY_SOUND) {
                     if (ONLY_WARN_ONCE.add(id)) {
                        LOGGER.warn(MARKER, "Unable to play empty soundEvent: {}", id);
                     }
                  } else {
                     float volume = soundInstance.getVolume();
                     float attenuationDistance = Math.max(volume, 1.0F) * sound.getAttenuationDistance();
                     SoundCategory category = soundInstance.getCategory();
                     float finalVolume = this.calculateVolume(volume, category);
                     float pitch = this.calculatePitch(soundInstance);
                     Attenuation attenuation = soundInstance.getAttenuation();
                     boolean relative = soundInstance.isRelative();
                     if (finalVolume == 0.0F && !soundInstance.canStartSilent()) {
                        LOGGER.debug(MARKER, "Skipped playing sound {}, volume was zero.", sound.getLocation());
                     } else {
                        Vec3d pos = new Vec3d(soundInstance.getX(), soundInstance.getY(), soundInstance.getZ());
                        if (!this.listeners.isEmpty()) {
                           float distance = !relative && attenuation != Attenuation.NONE ? attenuationDistance : Float.POSITIVE_INFINITY;

                           for (SoundEventListener soundEventListener : this.listeners) {
                              soundEventListener.onPlaySound(soundInstance, weightedSoundSet, distance);
                           }
                        }

                        if (this.listener.getGain() <= 0.0F) {
                           LOGGER.debug(MARKER, "Skipped playing soundEvent: {}, master volume was zero", id);
                        } else {
                           boolean loopAutomatically = shouldLoopAutomatically(soundInstance);
                           boolean shouldStream = sound.shouldStream();
                           CompletableFuture<ChannelHandle> future = this.channelAccess
                              .createHandle(shouldStream ? Pool.STREAMING : Pool.STATIC);
                           ChannelHandle channelHandle = future.join();
                           if (channelHandle == null) {
                              if (SharedConstants.IS_RUNNING_IN_IDE) {
                                 LOGGER.warn("Failed to create new sound handle");
                              }
                           } else {
                              LOGGER.debug(MARKER, "Playing sound {} for event {}", sound.getLocation(), id);
                              this.soundDeleteTime.put(soundInstance, this.tickCount + 20);
                              this.instanceToChannel.put(soundInstance, channelHandle);
                              this.instanceBySource.put(category, soundInstance);
                              channelHandle.execute(channel -> {
                                 channel.setPitch(pitch);
                                 channel.setVolume(finalVolume);
                                 if (attenuation == Attenuation.LINEAR) {
                                    channel.linearAttenuation(attenuationDistance);
                                 } else {
                                    channel.disableAttenuation();
                                 }

                                 channel.setLooping(loopAutomatically && !shouldStream);
                                 channel.setSelfPosition(pos);
                                 channel.setRelative(relative);
                              });
                              if (!shouldStream) {
                                 this.soundBuffers.getCompleteBuffer(sound.getPath()).thenAccept(soundBuffer -> channelHandle.execute(channel -> {
                                    channel.attachStaticBuffer(soundBuffer);
                                    channel.play();
                                 }));
                              } else {
                                 this.soundBuffers.getStream(sound.getPath(), loopAutomatically).thenAccept(audioStream -> channelHandle.execute(channel -> {
                                    channel.attachBufferStream(audioStream);
                                    channel.play();
                                 }));
                              }

                              if (soundInstance instanceof TickableSoundInstance) {
                                 this.tickingSounds.add((TickableSoundInstance)soundInstance);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public void queueTickingSound(TickableSoundInstance tickableSoundInstance) {
      this.queuedTickableSounds.add(tickableSoundInstance);
   }

   public void requestPreload(Sound sound) {
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
         this.channelAccess.executeOnChannels(stream -> stream.forEach(Channel::pause));
      }
   }

   public void resume() {
      if (this.loaded) {
         this.channelAccess.executeOnChannels(stream -> stream.forEach(Channel::unpause));
      }
   }

   public void playDelayed(SoundInstance soundInstance, int delay) {
      this.queuedSounds.put(soundInstance, this.tickCount + delay);
   }

   public void updateSource(Camera camera) {
      if (this.loaded && camera.isInitialized()) {
         Vec3d pos = camera.getPos();
         Vec3d forward = camera.getRotationVec(1.0F);
         Vec3d up = forward.crossProduct(Vec3d.UP).crossProduct(forward).normalize();
         this.executor.execute(() -> this.listener.setTransform(new ListenerTransform(pos, forward, up)));
   }

   public void stop(@Nullable Identifier id, @Nullable SoundCategory category) {
      if (category != null) {
         for (SoundInstance soundInstance : this.instanceBySource.get(category)) {
            if (id == null || soundInstance.getLocation().equals(id)) {
               this.stop(soundInstance);
            }
         }
      } else if (id == null) {
         this.stopAll();
      } else {
         for (SoundInstance soundInstance : this.instanceToChannel.keySet()) {
            if (soundInstance.getLocation().equals(id)) {
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
      NO_CHANGE;
   }
}   