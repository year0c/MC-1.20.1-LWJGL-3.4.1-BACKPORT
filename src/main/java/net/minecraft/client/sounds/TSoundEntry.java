package net.minecraft.client.sounds;

import java.util.List;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

public record TSoundEntry(List<SoundManager.TSound> sounds, @Nullable Text subtitle, boolean replace) {
   public TSoundEntry {
      sounds = List.copyOf(sounds);
   }
}   