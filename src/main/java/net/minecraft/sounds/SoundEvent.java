package net.minecraft.sounds;

import net.minecraft.util.Identifier;

import java.util.Optional;

public record SoundEvent(Identifier id, Optional<Float> range) {
    public SoundEvent {
        if (id == null) {
            throw new IllegalArgumentException("Sound event id cannot be null");
        }
        range = range == null ? Optional.empty() : range;
    }
}