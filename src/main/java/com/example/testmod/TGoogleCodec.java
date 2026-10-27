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
import net.minecraft.sounds.Music;

import java.io.IOException;
import java.util.Optional;

public final class TGoogleCodec implements TypeAdapterFactory {
    private static final TypeAdapter<SoundEvent> SOUND_EVENT_ADAPTER =
            new SoundEventAdapter();

    private static final TypeAdapter<Music> MUSIC_ADAPTER =
            new MusicJsonAdapter();

    @Override
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        Class<?> rawType = type.getRawType();

        if (rawType == SoundEvent.class) {
            return (TypeAdapter<T>) SOUND_EVENT_ADAPTER;
        }

        if (rawType == Music.class) {
            return (TypeAdapter<T>) MUSIC_ADAPTER;
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

    private static final class MusicJsonAdapter extends TypeAdapter<Music> {
        @Override
        public void write(JsonWriter out, Music music) throws IOException {
            if (music == null) {
                out.nullValue();
                return;
            }

            out.beginObject();
            out.name("sound").value(music.sound().toString());
            out.name("min_delay").value(music.minDelay());
            out.name("max_delay").value(music.maxDelay());
            out.name("replace_current_music").value(music.replaceCurrentMusic());
            out.endObject();
        }

        @Override
        public Music read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            Identifier sound = null;
            Integer minDelay = null;
            Integer maxDelay = null;
            boolean replaceCurrentMusic = false;

            in.beginObject();
            while (in.hasNext()) {
                switch (in.nextName()) {
                    case "sound" -> {
                        String text = in.nextString();
                        sound = Identifier.tryParse(text);
                        if (sound == null) {
                            throw new JsonParseException("Invalid sound id: " + text);
                        }
                    }
                    case "min_delay" -> minDelay = in.nextInt();
                    case "max_delay" -> maxDelay = in.nextInt();
                    case "replace_current_music" ->
                            replaceCurrentMusic = in.nextBoolean();
                    default -> in.skipValue();
                }
            }
            in.endObject();

            if (sound == null || minDelay == null || maxDelay == null) {
                throw new JsonParseException(
                        "Music requires sound, min_delay, and max_delay"
                );
            }
            if (minDelay < 0 || maxDelay < 0) {
                throw new JsonParseException("Music delays cannot be negative");
            }

            return new Music(
                    sound, minDelay, maxDelay, replaceCurrentMusic
            );
        }
    }
}
