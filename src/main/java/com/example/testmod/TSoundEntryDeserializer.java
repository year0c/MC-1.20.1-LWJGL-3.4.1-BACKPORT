package com.example.testmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.text.Text;
import net.minecraft.util.math.floatprovider.ConstantFloatProvider;
import net.minecraft.client.sound.Sound;

public class TSoundEntryDeserializer implements JsonDeserializer<TSoundEntry> {
   @Override
   public TSoundEntry deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
      JsonObject obj = json.getAsJsonObject();
      boolean replace = obj.has("replace") && obj.get("replace").getAsBoolean();
      Text subtitle = obj.has("subtitle") ? Text.translatable(obj.get("subtitle").getAsString()) : null;

      List<Sound> sounds = new ArrayList<>();
      JsonArray soundsArr = obj.getAsJsonArray("sounds");

      for (JsonElement element : soundsArr) {
         if (element.isJsonPrimitive()) {
            String name = element.getAsString();
            sounds.add(new Sound(name, ConstantFloatProvider.create(1.0F), ConstantFloatProvider.create(1.0F), 1, Sound.RegistrationType.FILE, false, false, 16));
         } else {
            JsonObject soundObj = element.getAsJsonObject();
            String name = soundObj.get("name").getAsString();
            float volume = soundObj.has("volume") ? soundObj.get("volume").getAsFloat() : 1.0F;
            float pitch = soundObj.has("pitch") ? soundObj.get("pitch").getAsFloat() : 1.0F;
            int weight = soundObj.has("weight") ? soundObj.get("weight").getAsInt() : 1;
            boolean stream = soundObj.has("stream") && soundObj.get("stream").getAsBoolean();
            boolean preload = soundObj.has("preload") && soundObj.get("preload").getAsBoolean();
            int attenuation = soundObj.has("attenuation") ? soundObj.get("attenuation").getAsInt() : 16;
            sounds.add(new Sound(name, ConstantFloatProvider.create(volume), ConstantFloatProvider.create(pitch), weight, Sound.RegistrationType.FILE, stream, preload, attenuation));
         }
      }

      return new TSoundEntry(sounds, subtitle, replace);
   }
}   