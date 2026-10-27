package com.mojang.blaze3d.audio;

import net.minecraft.util.math.Vec3d;
import org.lwjgl.openal.AL10;

public class Listener {
   private float gain = 1.0F;
   private ListenerTransform transform = ListenerTransform.INITIAL;

   public void setTransform(ListenerTransform listenerTransform) {
      this.transform = listenerTransform;
      Vec3d position = listenerTransform.position();
      Vec3d forward = listenerTransform.forward();
      Vec3d up = listenerTransform.up();
      AL10.alListener3f(AL10.AL_POSITION, (float)position.x, (float)position.y, (float)position.z);
      AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{(float)forward.x, (float)forward.y, (float)forward.z, (float)up.x, (float)up.y, (float)up.z});
   }

   public void setGain(float f) {
      AL10.alListenerf(AL10.AL_GAIN, f);
      this.gain = f;
   }

   public float getGain() {
      return this.gain;
   }

   public void reset() {
      this.setTransform(ListenerTransform.INITIAL);
   }

   public ListenerTransform getTransform() {
      return this.transform;
   }
}   