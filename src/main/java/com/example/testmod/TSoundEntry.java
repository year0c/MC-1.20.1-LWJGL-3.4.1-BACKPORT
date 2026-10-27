package com.example.testmod;

import java.util.List;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.sound.Sound;
import net.minecraft.text.Text;

public record TSoundEntry(List<Sound> sounds, @Nullable Text subtitle, boolean replace) {
   public TSoundEntry {
      sounds = List.copyOf(sounds);
   }
}   