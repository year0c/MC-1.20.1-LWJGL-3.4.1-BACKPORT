package net.minecraft.client.sounds;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonDeserializer;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.audio.ListenerTransform;
import com.mojang.logging.LogUtils;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.Camera;
import net.minecraft.client.sound.SoundInstance;   
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.floatprovider.ConstantFloatProvider;
import net.minecraft.util.math.floatprovider.FloatSupplier;
import net.minecraft.util.math.floatprovider.MultipliedFloatSupplier;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.profiler.Profiler;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class SoundManager implements ResourceReloader {
   public static final Sound EMPTY_SOUND = new Sound("minecraft:empty", ConstantFloatProvider.create(1.0F), ConstantFloatProvider.create(1.0F), 1, Sound.RegistrationType.FILE, false, false, 16);
   public static final Identifier INTENTIONALLY_EMPTY_SOUND_LOCATION = new Identifier("minecraft", "intentionally_empty");
   public static final Sound INTENTIONALLY_EMPTY_SOUND = new Sound(
      INTENTIONALLY_EMPTY_SOUND_LOCATION.toString(), ConstantFloatProvider.create(1.0F), ConstantFloatProvider.create(1.0F), 1, Sound.RegistrationType.FILE, false, false, 16
   );
   static final Logger LOGGER = LogUtils.getLogger();
   private static final String SOUNDS_PATH = "sounds.json";
   private static final Gson GSON = new GsonBuilder()
      .registerTypeAdapter(TSoundEntry.class, (JsonDeserializer<TSoundEntry>) (json, typeOfT, context) -> {
         JsonObject obj = json.getAsJsonObject();
         boolean replace = obj.has("replace") && obj.get("replace").getAsBoolean();
         Text subtitle = obj.has("subtitle") ? Text.translatable(obj.get("subtitle").getAsString()) : null;

         List<TSound> sounds = new ArrayList<>();
         JsonArray soundsArr = obj.getAsJsonArray("sounds");

         for (JsonElement element : soundsArr) {
            if (element.isJsonPrimitive()) {
               sounds.add(TSound.fromSimple(element.getAsString()));
            } else {
               sounds.add(TSound.fromJson(element.getAsJsonObject()));
            }
         }

         return new TSoundEntry(sounds, subtitle, replace);
      })
      .create();
   private static final TypeToken<Map<String, TSoundEntry>> SOUND_EVENT_REGISTRATION_TYPE = new TypeToken<Map<String, TSoundEntry>>() {};
   private final Map<Identifier, TSoundEventSet> registry = Maps.newHashMap();
   private final Set<Identifier> validSoundEvents = Sets.newHashSet();
   private final SoundEngine soundEngine;
   private final Map<Identifier, Resource> soundCache = new HashMap<>();

   public SoundManager(GameOptions options) {
      this.soundEngine = new SoundEngine(this, options, ResourceFactory.fromMap(this.soundCache));
   }

   @Override
   public CompletableFuture<Void> reload(ResourceReloader.Synchronizer synchronizer, ResourceManager resourceManager, Profiler prepareProfiler, Profiler applyProfiler, Executor prepareExecutor, Executor applyExecutor) {
      return CompletableFuture.supplyAsync(() -> prepare(resourceManager, prepareProfiler), prepareExecutor)
         .thenApplyAsync(preparations -> {
            apply(preparations, resourceManager, applyProfiler);
            return null;
         }, applyExecutor);
   }

   private SoundManager.Preparations prepare(ResourceManager resourceManager, Profiler profiler) {
      SoundManager.Preparations preparations = new SoundManager.Preparations();
      profiler.push("list");
      preparations.listResources(resourceManager);
      profiler.pop();

      Map<Identifier, Resource> resources = resourceManager.findResources("sounds", id -> id.getPath().equals("sounds.json"));

      for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
         String namespace = entry.getKey().getNamespace();
         profiler.push(namespace);

         try (Reader reader = new InputStreamReader(entry.getValue().getInputStream(), StandardCharsets.UTF_8)) {
            profiler.push("parse");
            Map<String, TSoundEntry> map = GSON.fromJson(reader, SOUND_EVENT_REGISTRATION_TYPE);
            profiler.pop();
            profiler.push("register");

            if (map != null) {
               for (Entry<String, TSoundEntry> e : map.entrySet()) {
                  preparations.handleRegistration(new Identifier(namespace, e.getKey()), e.getValue());
               }
            }

            profiler.pop();
         } catch (Exception e) {
            LOGGER.warn("Invalid {} in resourcepack: '{}'", SOUNDS_PATH, namespace, e);
         }

         profiler.pop();
      }

      return preparations;
   }

   private void apply(SoundManager.Preparations preparations, ResourceManager resourceManager, Profiler profiler) {
      preparations.apply(this.registry, this.soundCache, this.soundEngine);
      this.validSoundEvents.clear();
      this.validSoundEvents.addAll(this.registry.keySet());

      if (LOGGER.isDebugEnabled()) {
         for (Identifier id : this.registry.keySet()) {
            if (Registries.SOUND_EVENT.get(id) == null) {
               LOGGER.debug("Not having sound event for: {}", id);
            }
         }
      }

      this.soundEngine.reload();
   }

   public boolean hasSound(Identifier id) {
      return this.validSoundEvents.contains(id);
   }

   public List<String> getAvailableSoundDevices() {
      return this.soundEngine.getAvailableSoundDevices();
   }

   public ListenerTransform getListenerTransform() {
      return this.soundEngine.getListenerTransform();
   }

   static boolean validateSoundResource(TSound sound, Identifier resourceLocation, ResourceFactory resourceFactory) {
      Identifier soundPath = sound.getIdentifier();
      if (resourceFactory.getResource(soundPath).isEmpty()) {
         LOGGER.warn("File {} does not exist, cannot add it to event {}", soundPath, resourceLocation);
         return false;
      }
      return true;
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

   public void stopAll() {
      this.soundEngine.stopAll();
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

   public void addListener(SoundEngine.SoundEventListener soundEventListener) {
      this.soundEngine.addEventListener(soundEventListener);
   }

   public void removeListener(SoundEngine.SoundEventListener soundEventListener) {
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

   // --- TWeightedSound (internal) ---

   interface TWeightedSound {
      int getWeight();
      TSound getSound(Random random);
      void preloadIfRequired(SoundEngine engine);
   }

   // --- TSound (internal) ---

   static class TSound implements TWeightedSound {
      private final Identifier location;
      private final FloatSupplier volume;
      private final FloatSupplier pitch;
      private final int weight;
      private final Type type;
      private final boolean stream;
      private final boolean preload;
      private final int attenuationDistance;

      TSound(String name, FloatSupplier volume, FloatSupplier pitch, int weight, Type type, boolean stream, boolean preload, int attenuationDistance) {
         this.location = new Identifier(name);
         this.volume = volume;
         this.pitch = pitch;
         this.weight = weight;
         this.type = type;
         this.stream = stream;
         this.preload = preload;
         this.attenuationDistance = attenuationDistance;
      }

      static TSound fromSimple(String name) {
         return new TSound(name, ConstantFloatProvider.create(1.0F), ConstantFloatProvider.create(1.0F), 1, Type.FILE, false, false, 16);
      }

      static TSound fromJson(JsonObject obj) {
         String name = obj.get("name").getAsString();
         float volume = obj.has("volume") ? obj.get("volume").getAsFloat() : 1.0F;
         float pitch = obj.has("pitch") ? obj.get("pitch").getAsFloat() : 1.0F;
         int weight = obj.has("weight") ? obj.get("weight").getAsInt() : 1;
         boolean stream = obj.has("stream") && obj.get("stream").getAsBoolean();
         boolean preload = obj.has("preload") && obj.get("preload").getAsBoolean();
         int attenuation = obj.has("attenuation") ? obj.get("attenuation").getAsInt() : 16;
         return new TSound(name, ConstantFloatProvider.create(volume), ConstantFloatProvider.create(pitch), weight, Type.FILE, stream, preload, attenuation);
      }

      Identifier getLocation() {
         return this.location;
      }

      Identifier getIdentifier() {
         return new Identifier(this.location.getNamespace(), "sounds/" + this.location.getPath() + ".ogg");
      }

      FloatSupplier getVolume() {
         return this.volume;
      }

      FloatSupplier getPitch() {
         return this.pitch;
      }

      @Override
      public int getWeight() {
         return this.weight;
      }

      Type getType() {
         return this.type;
      }

      boolean isStreamed() {
         return this.stream;
      }

      boolean shouldPreload() {
         return this.preload;
      }

      int getAttenuationDistance() {
         return this.attenuationDistance;
      }

      @Override
      public TSound getSound(Random random) {
         return this;
      }

      @Override
      public void preloadIfRequired(SoundEngine engine) {
         if (this.preload) {
            engine.requestPreload(this);
         }
      }

      @Override
      public String toString() {
         return "TSound[" + this.location + "]";
      }

      enum Type {
         FILE,
         SOUND_EVENT
      }
   }

   // --- TSoundEventSet (internal) ---

   record TSoundEventSet(Identifier id, @Nullable Text subtitle, TSoundSet sounds) {}

   // --- TSoundSet (internal) ---

   static class TSoundSet {
      private final List<TWeightedSound> sounds = new ArrayList<>();
      private int totalWeight;

      TSoundSet() {}

      void addSound(TWeightedSound sound) {
         this.sounds.add(sound);
         this.totalWeight += sound.getWeight();
      }

      int getWeight() {
         return this.totalWeight;
      }

      TSound getSound(Random random) {
         if (this.sounds.isEmpty()) {
            return null;
         }

         int i = this.sounds.size() == 1 ? 0 : random.nextInt(this.totalWeight);
         for (TWeightedSound sound : this.sounds) {
            i -= sound.getWeight();
            if (i < 0) {
               return sound.getSound(random);
            }
         }

         return this.sounds.get(this.sounds.size() - 1).getSound(random);
      }

      void preloadIfRequired(SoundEngine engine) {
         for (TWeightedSound sound : this.sounds) {
            sound.preloadIfRequired(engine);
         }
      }
   }

   // --- Preparations ---

   protected static class Preparations {
      final Map<Identifier, TSoundEventSet> registry = Maps.newHashMap();
      private Map<Identifier, Resource> soundCache = Map.of();

      void listResources(ResourceManager resourceManager) {
         this.soundCache = resourceManager.findResources("sounds", id -> id.getPath().endsWith(".ogg"));
      }

      void handleRegistration(Identifier id, TSoundEntry soundEntry) {
         TSoundEventSet soundEventSet = this.registry.get(id);
         boolean isNew = soundEventSet == null;
         if (isNew || soundEntry.replace()) {
            if (!isNew) {
               SoundManager.LOGGER.debug("Replaced sound event location {}", id);
            }
            soundEventSet = new TSoundEventSet(id, soundEntry.subtitle(), new TSoundSet());
            this.registry.put(id, soundEventSet);
         }

         if (soundEventSet == null) {
            return;
         }

         ResourceFactory resourceFactory = ResourceFactory.fromMap(this.soundCache);

         for (final TSound sound : soundEntry.sounds()) {
            final Identifier soundLocation = sound.getLocation();
            TWeightedSound weighted;
            switch (sound.getType()) {
               case FILE:
                  if (!SoundManager.validateSoundResource(sound, id, resourceFactory)) {
                     continue;
                  }
                  weighted = sound;
                  break;
               case SOUND_EVENT:
                  weighted = new TWeightedSound() {
                     @Override
                     public int getWeight() {
                        TSoundEventSet ses = Preparations.this.registry.get(soundLocation);
                        return ses == null ? 0 : ses.sounds().getWeight();
                     }

                     @Override
                     public TSound getSound(Random random) {
                        TSoundEventSet ses = Preparations.this.registry.get(soundLocation);
                        if (ses == null) {
                           return null;
                        }
                        TSound resolved = ses.sounds().getSound(random);
                        return new TSound(
                           resolved.getLocation().toString(),
                           new MultipliedFloatSupplier(resolved.getVolume(), sound.getVolume()),
                           new MultipliedFloatSupplier(resolved.getPitch(), sound.getPitch()),
                           sound.getWeight(),
                           TSound.Type.FILE,
                           resolved.isStreamed() || sound.isStreamed(),
                           false,
                           16
                        );
                     }

                     @Override
                     public void preloadIfRequired(SoundEngine engine) {
                        TSoundEventSet ses = Preparations.this.registry.get(soundLocation);
                        if (ses != null) {
                           ses.sounds().preloadIfRequired(engine);
                        }
                     }
                  };
                  break;
               default:
                  throw new IllegalStateException("Unknown sound type: " + sound.getType());
            }

            soundEventSet.sounds().addSound(weighted);
         }
      }

      void apply(Map<Identifier, TSoundEventSet> map, Map<Identifier, Resource> cache, SoundEngine engine) {
         map.clear();
         cache.clear();
         cache.putAll(this.soundCache);

         for (Entry<Identifier, TSoundEventSet> entry : this.registry.entrySet()) {
            map.put(entry.getKey(), entry.getValue());
            entry.getValue().sounds().preloadIfRequired(engine);
         }
      }
   }
}   