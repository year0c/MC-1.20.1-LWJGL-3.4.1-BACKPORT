package com.mojang.blaze3d.audio;

import net.minecraft.util.math.Vec3d;

public record ListenerTransform(Vec3d position, Vec3d forward, Vec3d up) {
   public static final ListenerTransform INITIAL = new ListenerTransform(Vec3d.ZERO, new Vec3d(0.0, 0.0, -1.0), new Vec3d(0.0, 1.0, 0.0));

   public Vec3d right() {
      return this.forward.crossProduct(this.up);
   }
}   