package com.mojang.blaze3d.audio;

import net.minecraft.util.math.Vec3d;
import org.lwjgl.openal.AL10;

import com.example.testmod.TListenerTransform;

public class Listener {
    private float gain = 1.0F;
    private TListenerTransform transform = TListenerTransform.INITIAL;

    public void setTransform(TListenerTransform listenerTransform) {
        this.transform = listenerTransform;
        Vec3d vec3 = listenerTransform.position();
        Vec3d vec32 = listenerTransform.forward();
        Vec3d vec33 = listenerTransform.up();
        AL10.alListener3f(4100, (float) vec3.x, (float) vec3.y, (float) vec3.z);
        AL10.alListenerfv(4111, new float[]{
                (float) vec32.x, (float) vec32.y, (float) vec32.z,
                (float) vec33.x, (float) vec33.y, (float) vec33.z
        });
    }

    public void setGain(float f) {
        AL10.alListenerf(4106, f);
        this.gain = f;
    }

    public float getGain() {
        return this.gain;
    }

    public void reset() {
        this.setTransform(TListenerTransform.INITIAL);
    }

    public TListenerTransform getTransform() {
        return this.transform;
    }
}   