package net.minecraft.sounds;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import net.minecraft.util.Identifier;

import java.lang.reflect.Type;
import java.util.Optional;

public record SoundEvent(Identifier id, Optional<Float> range) {
    public SoundEvent {
        if (id == null) {
            throw new IllegalArgumentException("Sound event id cannot be null");
        }
        range = range == null ? Optional.empty() : range;
    }

    public float getRange(float volume) {
        return range.orElseGet(() -> volume > 1.0F ? 16.0F * volume : 16.0F);
    }

    public static final GsonAdapter ADAPTER = new GsonAdapter();

    public static class GsonAdapter implements JsonSerializer<SoundEvent>, JsonDeserializer<SoundEvent> {
        @Override
        public JsonElement serialize(SoundEvent src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.addProperty("sound_id", src.id().toString());
            src.range().ifPresent(r -> obj.addProperty("range", r));
            return obj;
        }

        @Override
        public SoundEvent deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
            JsonObject obj = json.getAsJsonObject();
            Identifier id = new Identifier(obj.get("sound_id").getAsString());
            Optional<Float> range = obj.has("range")
                ? Optional.of(obj.get("range").getAsFloat())
                : Optional.empty();
            return new SoundEvent(id, range);
        }
    }
}   