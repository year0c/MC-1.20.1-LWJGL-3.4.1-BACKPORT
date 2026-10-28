package net.minecraft.client.resources.sounds;

import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sound.Sound;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

public interface SoundInstance {
   Identifier getLocation();

   default boolean hasSound(SoundManager soundManager) {
      return soundManager.hasSound(this.getLocation());
   }

   Sound getSound();

   SoundCategory getCategory();

   Attenuation getAttenuation();

   boolean isLooping();

   boolean isRelative();

   int getDelay();

   float getVolume();

   float getPitch();

   double getX();

   double getY();

   double getZ();

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