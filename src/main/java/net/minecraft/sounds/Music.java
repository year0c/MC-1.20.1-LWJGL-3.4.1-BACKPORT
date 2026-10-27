package net.minecraft.sounds;

import net.minecraft.util.Identifier;

public record Music(
        Identifier sound,
        int minDelay,
        int maxDelay,
        boolean replaceCurrentMusic
) {
    public Music {
        if (sound == null) {
            throw new IllegalArgumentException("Music sound id cannot be null");
        }
        if (minDelay < 0 || maxDelay < 0) {
            throw new IllegalArgumentException("Music delays cannot be negative");
        }
    }
}