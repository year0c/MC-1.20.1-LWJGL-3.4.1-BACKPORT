package com.mojang.blaze3d.audio;

import org.lwjgl.openal.AL10;

public final class Listener {
    private ListenerTransform transform = ListenerTransform.INITIAL;

    public void setTransform(ListenerTransform next) {
        this.transform = next;

        AL10.alListener3f(
                AL10.AL_POSITION,
                next.x(),
                next.y(),
                next.z()
        );

        AL10.alListenerfv(AL10.AL_ORIENTATION, new float[] {
                next.forwardX(),
                next.forwardY(),
                next.forwardZ(),
                next.upX(),
                next.upY(),
                next.upZ()
        });
    }

    public ListenerTransform getTransform() {
        return transform;
    }

    public record ListenerTransform(
            float x,
            float y,
            float z,
            float forwardX,
            float forwardY,
            float forwardZ,
            float upX,
            float upY,
            float upZ
    ) {
        public static final ListenerTransform INITIAL = new ListenerTransform(
                0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, -1.0f,
                0.0f, 1.0f, 0.0f
        );
    }
}