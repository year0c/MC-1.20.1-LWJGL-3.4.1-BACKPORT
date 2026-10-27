package com.example.testmod;

import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryStack;

public final class TListener {
    public TListener() {
    }

    public void setPosition(float x, float y, float z) {
        AL10.alListener3f(AL10.AL_POSITION, x, y, z);
    }

    public void setGain(float gain) {
        AL10.alListenerf(AL10.AL_GAIN, gain);
    }

    public void setOrientation(
            float atX, float atY, float atZ,
            float upX, float upY, float upZ
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            AL10.alListenerfv(
                    AL10.AL_ORIENTATION,
                    stack.floats(atX, atY, atZ, upX, upY, upZ)
            );
        }
    }
}
