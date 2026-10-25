package com.example.testmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.text.Text;

public class TestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            handler.getPlayer().sendMessage(Text.literal("Hello, world!"));
        });
    }
}