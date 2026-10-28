package net.minecraft.client.resources.sounds;

import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sound.WeightedSoundSet;   
import net.minecraft.client.sound.Sound;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import org.jspecify.annotations.Nullable;

public interface SoundInstance {
   Identifier getLocation();

   @Nullable
   WeightedSoundSet resolve(SoundManager soundManager);

   Sound getSound();

   SoundCategory getCategory();

   boolean isLooping();

   boolean isRelative();

   int getDelay();

   float getVolume();

   float getPitch();

   double getX();

   double getY();

   double getZ();

   Attenuation getAttenuation();

   default boolean canStartSilent() {
      return false;
   }

   default boolean canPlaySound() {
      return true;
   }

   static Random createUnseededRandom() {
      return Random.create();
   }

   enum Attenuation {
      NONE,
      LINEAR
   }
}   