package com.example.testmod;

import net.minecraft.util.math.Vec3d;

public record TListenerTransform(Vec3d position, Vec3d forward, Vec3d up) {
    public static final TListenerTransform INITIAL =
            new TListenerTransform(Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO);
}