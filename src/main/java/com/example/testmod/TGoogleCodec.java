package com.example.testmod;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import net.minecraft.util.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.io.IOException;
import java.util.Optional;

public final class TGoogleCodec implements TypeAdapterFactory {
    private static final TypeAdapter<SoundEvent> SOUND_EVENT_ADAPTER =
            new SoundEventAdapter();

    @Override
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        if (type.getRawType() == SoundEvent.class) {
            return (TypeAdapter<T>) SOUND_EVENT_ADAPTER;
        }

        return null;
    }

    private static final class SoundEventAdapter extends TypeAdapter<SoundEvent> {
        @Override
        public void write(JsonWriter out, SoundEvent value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            out.beginObject();
            out.name("sound_id").value(value.id().toString());
            if (value.range().isPresent()) {
                out.name("range").value(value.range().get());
            }
            out.endObject();
        }

        @Override
        public SoundEvent read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            Identifier id = null;
            Optional<Float> range = Optional.empty();

            in.beginObject();
            while (in.hasNext()) {
                switch (in.nextName()) {
                    case "sound_id" -> {
                        String text = in.nextString();
                        id = Identifier.tryParse(text);
                        if (id == null) {
                            throw new JsonParseException("Invalid sound_id: " + text);
                        }
                    }
                    case "range" -> {
                        if (in.peek() == JsonToken.NULL) {
                            in.nextNull();
                        } else {
                            range = Optional.of((float) in.nextDouble());
                        }
                    }
                    default -> in.skipValue();
                }
            }
            in.endObject();

            if (id == null) {
                throw new JsonParseException("Missing required field: sound_id");
            }

            return new SoundEvent(id, range);
        }
    }
}   