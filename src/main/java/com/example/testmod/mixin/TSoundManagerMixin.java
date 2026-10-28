package com.example.testmod.mixin;

import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.client.render.Camera;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SoundManager.class)
public abstract class TSoundManagerMixin {

    @Unique
    private net.minecraft.client.sounds.SoundManager newManager;

    @Unique
    private net.minecraft.client.sounds.SoundManager getNewManager() {
        if (this.newManager == null) {
            this.newManager = new net.minecraft.client.sounds.SoundManager(
                net.minecraft.client.MinecraftClient.getInstance().options
            );
        }
        return this.newManager;
    }

    @Overwrite
    public void tick(boolean paused) {
        this.getNewManager().tick(paused);
    }

    @Overwrite
    public void close() {
        this.getNewManager().destroy();
    }

    @Overwrite
    public void play(SoundInstance sound) {
        this.getNewManager().play(sound);
    }

    @Overwrite
    public void play(SoundInstance sound, int delay) {
        this.getNewManager().playDelayed(sound, delay);
    }

    @Overwrite
    public void playNextTick(TickableSoundInstance sound) {
        this.getNewManager().queueTickingSound(sound);
    }

    @Overwrite
    public void stop(SoundInstance sound) {
        this.getNewManager().stop(sound);
    }

    @Overwrite
    public void stopAll() {
        this.getNewManager().stopAll();
    }   

    @Overwrite
    public void stopSounds(Identifier id, SoundCategory category) {
        this.getNewManager().stop(id, category);
    }

    @Overwrite
    public boolean isPlaying(SoundInstance sound) {
        return this.getNewManager().isActive(sound);
    }

    @Overwrite
    public void pauseAll() {
        this.getNewManager().pause();
    }

    @Overwrite
    public void resumeAll() {
        this.getNewManager().resume();
    }

    @Overwrite
    public void updateListenerPosition(Camera camera) {
        this.getNewManager().updateSource(camera);
    }

    @Overwrite
    public void updateSoundVolume(SoundCategory category, float volume) {
        this.getNewManager().updateSourceVolume(category, volume);
    }
}   