package net.minecraft.client.sounds;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.google.gson.JsonDeserializer;   

import com.mojang.blaze3d.audio.ListenerTransform;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executor;

import net.minecraft.SharedConstants;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.Camera;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.Sound.RegistrationType;
import net.minecraft.client.sound.SoundEngine;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.Weighted;
import net.minecraft.util.math.floatprovider.ConstantFloatProvider;
import net.minecraft.util.math.floatprovider.MultipliedFloatSupplier;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.client.sounds.SoundManager.Preparations;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.example.testmod.TSoundEntry;
import com.example.testmod.TSoundEntryDeserializer;

public class SoundManager implements ResourceReloader {
   public static final Sound EMPTY_SOUND = new Sound("minecraft:empty", ConstantFloatProvider.create(1.0F), ConstantFloatProvider.create(1.0F), 1, RegistrationType.FILE, false, false, 16);
   public static final Identifier INTENTIONALLY_EMPTY_SOUND_LOCATION = new Identifier("minecraft", "intentionally_empty");
   public static final WeightedSoundSet INTENTIONALLY_EMPTY_SOUND_EVENT = new WeightedSoundSet(INTENTIONALLY_EMPTY_SOUND_LOCATION, null);
   public static final Sound INTENTIONALLY_EMPTY_SOUND = new Sound(
      INTENTIONALLY_EMPTY_SOUND_LOCATION.toString(), ConstantFloatProvider.create(1.0F), ConstantFloatProvider.create(1.0F), 1, RegistrationType.FILE, false, false, 16
   );
   static final Logger LOGGER = LogUtils.getLogger();
   private static final String SOUNDS_PATH = "sounds.json";
   private static final Gson GSON = new GsonBuilder()
   .registerTypeAdapter(TSoundEntry.class, (JsonDeserializer<TSoundEntry>) (json, typeOfT, context) -> {
      JsonObject obj = json.getAsJsonObject();
      boolean replace = obj.has("replace") && obj.get("replace").getAsBoolean();
      Text subtitle = obj.has("subtitle") ? Text.translatable(obj.get("subtitle").getAsString()) : null;

      List<Sound> sounds = new ArrayList<>();
      JsonArray soundsArr = obj.getAsJsonArray("sounds");

      for (JsonElement element : soundsArr) {
         if (element.isJsonPrimitive()) {
            String name = element.getAsString();
            sounds.add(new Sound(name, ConstantFloatProvider.create(1.0F), ConstantFloatProvider.create(1.0F), 1, RegistrationType.FILE, false, false, 16));
         } else {
            JsonObject soundObj = element.getAsJsonObject();
            String name = soundObj.get("name").getAsString();
            float volume = soundObj.has("volume") ? soundObj.get("volume").getAsFloat() : 1.0F;
            float pitch = soundObj.has("pitch") ? soundObj.get("pitch").getAsFloat() : 1.0F;
            int weight = soundObj.has("weight") ? soundObj.get("weight").getAsInt() : 1;
            boolean stream = soundObj.has("stream") && soundObj.get("stream").getAsBoolean();
            boolean preload = soundObj.has("preload") && soundObj.get("preload").getAsBoolean();
            int attenuation = soundObj.has("attenuation") ? soundObj.get("attenuation").getAsInt() : 16;
            sounds.add(new Sound(name, ConstantFloatProvider.create(volume), ConstantFloatProvider.create(pitch), weight, RegistrationType.FILE, stream, preload, attenuation));
         }
      }

      return new TSoundEntry(sounds, subtitle, replace);
   })
   .create();   
   private static final TypeToken<Map<String, TSoundEntry>> SOUND_EVENT_REGISTRATION_TYPE = new TypeToken<Map<String, TSoundEntry>>() {};
   private final Map<Identifier, WeightedSoundSet> registry = Maps.newHashMap();
   private final SoundEngine soundEngine;
   private final Map<Identifier, Resource> soundCache = new HashMap<>();

   public SoundManager(GameOptions options) {
      this.soundEngine = new SoundEngine(this, options, ResourceFactory.fromMap(this.soundCache));
   }

   @Override
   public void reload(ResourceReloader.Synchronizer synchronizer, ResourceManager resourceManager, Profiler prepareProfiler, Profiler applyProfiler, Executor prepareExecutor, Executor applyExecutor) {
      SoundManager.Preparations preparations = prepare(resourceManager, prepareProfiler);
      apply(preparations, resourceManager, applyProfiler);
   }

   private SoundManager.Preparations prepare(ResourceManager resourceManager, Profiler profiler) {
      SoundManager.Preparations preparations = new SoundManager.Preparations();
      profiler.push("list");
      preparations.listResources(resourceManager);
      profiler.pop();

      for (String namespace : resourceManager.getNamespaces()) {
         profiler.push(namespace);

         try {
            List<Resource> resourceStack = resourceManager.findAllResources(new Identifier(namespace, SOUNDS_PATH));   

            for (Resource resource : resourceStack) {
               profiler.push(namespace);

               try {
                  Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);

                  profiler.push("parse");
                  Map<String, TSoundEntry> map = GSON.fromJson(reader, SOUND_EVENT_REGISTRATION_TYPE);
                  profiler.pop();
                  profiler.push("register");

                  if (map != null) {
                     for (Entry<String, TSoundEntry> entry : map.entrySet()) {
                        preparations.handleRegistration(new Identifier(namespace, entry.getKey()), entry.getValue());
                     }
                  }

                  profiler.pop();
                  reader.close();
               } catch (RuntimeException e) {
                  LOGGER.warn("Invalid {} in resourcepack: '{}'", "sounds.json", namespace, e);
               }

               profiler.pop();
            }
         } catch (IOException e) {
         }

         profiler.pop();
      }

      return preparations;
   }

   private void apply(SoundManager.Preparations preparations, ResourceManager resourceManager, Profiler profiler) {
      preparations.apply(this.registry, this.soundCache, this.soundEngine);
      if (SharedConstants.IS_RUNNING_IN_IDE) {
         for (Identifier id : this.registry.keySet()) {
            WeightedSoundSet weighedSoundEvents = this.registry.get(id);
            if (weighedSoundEvents.getSubtitle() != null && Text.isTranslationKey(weighedSoundEvents.getSubtitle()) && Registries.SOUND_EVENT.containsKey(id)) {
               LOGGER.error("Missing subtitle {} for sound event: {}", weighedSoundEvents.getSubtitle(), id);
            }
         }
      }

      if (LOGGER.isDebugEnabled()) {
         for (Identifier id : this.registry.keySet()) {
            if (!Registries.SOUND_EVENT.containsKey(id)) {
               LOGGER.debug("Not having sound event for: {}", id);
            }
         }
      }

      this.soundEngine.reload();
   }

   public List<String> getAvailableSoundDevices() {
      return this.soundEngine.getAvailableSoundDevices();
   }

   public ListenerTransform getListenerTransform() {
      return this.soundEngine.getListenerTransform();
   }

   static boolean validateSoundResource(Sound sound, Identifier resourceLocation, ResourceFactory resourceFactory) {
      Identifier soundPath = sound.getPath();
      if (resourceFactory.getResource(soundPath).isEmpty()) {
         LOGGER.warn("File {} does not exist, cannot add it to event {}", soundPath, resourceLocation);
         return false;
      }
      return true;
   }

   @Nullable
   public WeightedSoundSet getSoundEvent(Identifier id) {
      return this.registry.get(id);
   }

   public Collection<Identifier> getAvailableSounds() {
      return this.registry.keySet();
   }

   public void queueTickingSound(TickableSoundInstance tickableSoundInstance) {
      this.soundEngine.queueTickingSound(tickableSoundInstance);
   }

   public void play(SoundInstance soundInstance) {
      this.soundEngine.play(soundInstance);
   }

   public void playDelayed(SoundInstance soundInstance, int delay) {
      this.soundEngine.playDelayed(soundInstance, delay);
   }

   public void updateSource(Camera camera) {
      this.soundEngine.updateSource(camera);
   }

   public void pause() {
      this.soundEngine.pause();
   }

   public void stop() {
      this.soundEngine.stopAll();
   }

   public void destroy() {
      this.soundEngine.destroy();
   }

   public void emergencyShutdown() {
      this.soundEngine.emergencyShutdown();
   }

   public void tick(boolean paused) {
      this.soundEngine.tick(paused);
   }

   public void resume() {
      this.soundEngine.resume();
   }

   public void updateSourceVolume(SoundCategory category, float volume) {
      if (category == SoundCategory.MASTER && volume <= 0.0F) {
         this.stop();
      }
      this.soundEngine.updateCategoryVolume(category, volume);
   }

   public void stop(SoundInstance soundInstance) {
      this.soundEngine.stop(soundInstance);
   }

   public boolean isActive(SoundInstance soundInstance) {
      return this.soundEngine.isActive(soundInstance);
   }

   public void addListener(SoundEventListener soundEventListener) {
      this.soundEngine.addEventListener(soundEventListener);
   }

   public void removeListener(SoundEventListener soundEventListener) {
      this.soundEngine.removeEventListener(soundEventListener);
   }

   public void stop(@Nullable Identifier id, @Nullable SoundCategory category) {
      this.soundEngine.stop(id, category);
   }

   public String getDebugString() {
      return this.soundEngine.getDebugString();
   }

   public void reload() {
      this.soundEngine.reload();
   }

   protected static class Preparations {
      final Map<Identifier, WeightedSoundSet> registry = Maps.newHashMap();
      private Map<Identifier, Resource> soundCache = Map.of();

      void listResources(ResourceManager resourceManager) {
         this.soundCache = Sound.SOUND_LISTER.listMatchingResources(resourceManager);
      }

      void handleRegistration(Identifier id, TSoundEntry soundEntry) {
         WeightedSoundSet weighedSoundEvents = this.registry.get(id);
         boolean isNew = weighedSoundEvents == null;
         if (isNew || soundEntry.replace()) {
            if (!isNew) {
               SoundManager.LOGGER.debug("Replaced sound event location {}", id);
            }
            weighedSoundEvents = new WeightedSoundSet(id, soundEntry.subtitle());
            this.registry.put(id, weighedSoundEvents);
         }

         ResourceFactory resourceFactory = ResourceFactory.fromMap(this.soundCache);

         for (final Sound sound : soundEntry.sounds()) {
            final Identifier soundLocation = sound.getLocation();
            Weighted<Sound> weighted;
            switch (sound.getType()) {
               case FILE:
                  if (!SoundManager.validateSoundResource(sound, id, resourceFactory)) {
                     continue;
                  }
                  weighted = sound;
                  break;
               case SOUND_EVENT:
                  weighted = new Weighted<Sound>() {
                     @Override
                     public int getWeight() {
                        WeightedSoundSet wse = Preparations.this.registry.get(soundLocation);
                        return wse == null ? 0 : wse.getWeight();
                     }

                     public Sound getSound(Random random) {
                        WeightedSoundSet wse = Preparations.this.registry.get(soundLocation);
                        if (wse == null) {
                           return SoundManager.EMPTY_SOUND;
                        }
                        Sound resolved = wse.getSound(random);
                        return new Sound(
                           resolved.getLocation().toString(),
                           MultipliedFloatSupplier.create(resolved.getVolume(), sound.getVolume()),
                           MultipliedFloatSupplier.create(resolved.getPitch(), sound.getPitch()),
                           sound.getWeight(),
                           RegistrationType.FILE,
                           resolved.shouldStream() || sound.shouldStream(),
                           resolved.shouldPreload(),
                           resolved.getAttenuationDistance()
                        );
                     }

                     @Override
                     public void preloadIfRequired(SoundEngine engine) {
                        WeightedSoundSet wse = Preparations.this.registry.get(soundLocation);
                        if (wse != null) {
                           wse.preloadIfRequired(engine);
                        }
                     }
                  };
                  break;
               default:
                  throw new IllegalStateException("Unknown Sound type: " + sound.getType());
            }

            weighedSoundEvents.addSound(weighted);
         }
      }

      public void apply(Map<Identifier, WeightedSoundSet> map, Map<Identifier, Resource> cache, SoundEngine engine) {
         map.clear();
         cache.clear();
         cache.putAll(this.soundCache);

         for (Entry<Identifier, WeightedSoundSet> entry : this.registry.entrySet()) {
            map.put(entry.getKey(), entry.getValue());
            entry.getValue().preloadIfRequired(engine);
         }
      }
   }
}   