package com.mojang.blaze3d.audio;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.ALUtil;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.testmod.TListener;

public final class Library {
    private static final Logger LOG = LoggerFactory.getLogger(Library.class);

    private static final int NO_DEVICE = 0;
    private static final int FALLBACK_SOURCE_COUNT = 30;
    private static final int HRTF_REQUEST = 0x1992;
    private static final int MIX_RATE = 0x1007;
    private static final int SOURCE_DISTANCE_MODEL = 0x0200;

    private long deviceHandle;
    private long contextHandle;
    private boolean disconnectQuerySupported;
    private @Nullable String rememberedDefaultDevice;

    private final TListener audioListener = new TListener();
    private ChannelPool staticPool = new LimitedChannelPool(0);
    private ChannelPool streamPool = new LimitedChannelPool(0);

    public Library() {
        rememberedDefaultDevice = queryDefaultDevice();
    }

    public void init(@Nullable String preferredDevice, boolean requestHrtf) {
        deviceHandle = openDevice(preferredDevice);

        ALCCapabilities alcCaps = ALC.createCapabilities(deviceHandle);
        if (alcGetError(deviceHandle, "Create ALC capabilities")
                || !alcCaps.OpenALC11) {
            throw new IllegalStateException("OpenAL 1.1 capabilities are unavailable");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attributes = makeContextAttributes(stack, alcCaps, requestHrtf);
            contextHandle = ALC10.alcCreateContext(deviceHandle, attributes);
        }

        if (contextHandle == 0L || alcGetError(deviceHandle, "Create context")) {
            throw new IllegalStateException("Could not create an OpenAL context");
        }

        if (!ALC10.alcMakeContextCurrent(contextHandle)) {
            throw new IllegalStateException("Could not activate the OpenAL context");
        }

        ALCapabilities alCaps = AL.createCapabilities(alcCaps);
        throwIfAlError("Initialize OpenAL capabilities");

        if (!alCaps.AL_EXT_source_distance_model) {
            throw new IllegalStateException("Per-source distance models are unavailable");
        }

        AL10.alEnable(SOURCE_DISTANCE_MODEL);
        throwIfAlError("Enable source distance models");

        int availableSources = readSourceCapacity();
        int streamLimit = clamp((int) Math.sqrt(availableSources), 2, 8);
        int staticLimit = clamp(availableSources - streamLimit, 8, 255);

        staticPool = new LimitedChannelPool(staticLimit);
        streamPool = new LimitedChannelPool(streamLimit);

        disconnectQuerySupported =
                ALC10.alcIsExtensionPresent(deviceHandle, "ALC_EXT_disconnect");

        LOG.info("OpenAL ready: {}", currentDeviceName());
    }

    private IntBuffer makeContextAttributes(
            MemoryStack stack,
            ALCCapabilities alcCaps,
            boolean requestHrtf
    ) {
        IntBuffer result = stack.callocInt(5);

        if (requestHrtf && alcCaps.ALC_SOFT_HRTF) {
            result.put(HRTF_REQUEST).put(1);
        }

        result.put(MIX_RATE).put(44100);
        result.put(0);
        return result.flip();
    }

    private int readSourceCapacity() {
        int reported = ALC10.alcGetInteger(deviceHandle, 0x1010); // ALC_MONO_SOURCES
        if (alcGetError(deviceHandle, "Query source capacity") || reported <= 0) {
            return FALLBACK_SOURCE_COUNT;
        }
        return reported;
    }

    public static @Nullable String queryDefaultDevice() {
        if (!ALC10.alcIsExtensionPresent(NO_DEVICE, "ALC_ENUMERATE_ALL_EXT")) {
            return null;
        }

        return ALC10.alcGetString(NO_DEVICE, 0x1012); // ALC_DEFAULT_ALL_DEVICES_SPECIFIER
    }

    public String currentDeviceName() {
        String result = ALC10.alcGetString(deviceHandle, 0x1013); // ALC_ALL_DEVICES_SPECIFIER
        if (result == null) {
            result = ALC10.alcGetString(deviceHandle, 0x1004); // ALC_DEFAULT_DEVICE_SPECIFIER
        }
        return result == null ? "Unknown" : result;
    }

    public synchronized boolean defaultDeviceChanged() {
        String latest = queryDefaultDevice();
        if (Objects.equals(rememberedDefaultDevice, latest)) {
            return false;
        }

        rememberedDefaultDevice = latest;
        return true;
    }

    private static long openDevice(@Nullable String preferredName) {
        OptionalLong opened = tryOpenDevice(preferredName);
        if (opened.isEmpty()) {
            opened = tryOpenDevice(queryDefaultDevice());
        }
        if (opened.isEmpty()) {
            opened = tryOpenDevice(null);
        }

        return opened.orElseThrow(
                () -> new IllegalStateException("Could not open an OpenAL device")
        );
    }

    private static OptionalLong tryOpenDevice(@Nullable String name) {
        long candidate = ALC10.alcOpenDevice(name);
        if (candidate == 0L) {
            return OptionalLong.empty();
        }

        if (alcGetError(candidate, "Open device")) {
            ALC10.alcCloseDevice(candidate);
            return OptionalLong.empty();
        }

        return OptionalLong.of(candidate);
    }

    public void cleanup() {
        staticPool.cleanup();
        streamPool.cleanup();

        if (contextHandle != 0L) {
            ALC10.alcMakeContextCurrent(0L);
            ALC10.alcDestroyContext(contextHandle);
            contextHandle = 0L;
        }

        if (deviceHandle != 0L) {
            ALC10.alcCloseDevice(deviceHandle);
            deviceHandle = 0L;
        }

        disconnectQuerySupported = false;
    }

    public TListener getListener() {
        return audioListener;
    }

    public @Nullable Channel acquireChannel(PoolKind kind) {
        return kind == PoolKind.STREAM ? streamPool.acquire() : staticPool.acquire();
    }

    public void releaseChannel(Channel channel) {
        if (!staticPool.release(channel) && !streamPool.release(channel)) {
            throw new IllegalArgumentException("Channel is not held by this library");
        }
    }

    public String debugSummary() {
        return String.format(
                Locale.ROOT,
                "Sounds: %d/%d + %d/%d",
                staticPool.used(),
                staticPool.capacity(),
                streamPool.used(),
                streamPool.capacity()
        );
    }

    public List<String> availableDeviceNames() {
        List<String> names = ALUtil.getStringList(NO_DEVICE, 0x1013);
        return names == null ? Collections.emptyList() : new ArrayList<>(names);
    }

    public boolean currentDeviceDisconnected() {
        return disconnectQuerySupported
                && ALC11.alcGetInteger(deviceHandle, 0x0313) == 0;
    }

    private static boolean alcGetError(long device, String operation) {
        int error = ALC10.alcGetError(device);
        if (error == ALC10.ALC_NO_ERROR) {
            return false;
        }

        LOG.error("ALC error during {}: 0x{}", operation, Integer.toHexString(error));
        return true;
    }

    private static void throwIfAlError(String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            throw new IllegalStateException(
                    "OpenAL error during " + operation + ": 0x" + Integer.toHexString(error)
            );
        }
    }

    private static int clamp(int value, int lower, int upper) {
        return Math.max(lower, Math.min(value, upper));
    }

    private interface ChannelPool {
        @Nullable Channel acquire();

        boolean release(Channel channel);

        void cleanup();

        int capacity();

        int used();
    }

    private static final class LimitedChannelPool implements ChannelPool {
        private final int maximum;
        private final Set<Channel> leased =
                Collections.newSetFromMap(new IdentityHashMap<>());

        private LimitedChannelPool(int maximum) {
            this.maximum = maximum;
        }

        @Override
        public @Nullable Channel acquire() {
            if (leased.size() >= maximum) {
                if (Boolean.getBoolean("fabric.development")) {
                    LOG.warn("Audio channel limit reached ({})", maximum);
                }
                return null;
            }

            Channel created = Channel.create();
            if (created != null) {
                leased.add(created);
            }
            return created;
        }

        @Override
        public boolean release(Channel channel) {
            if (!leased.remove(channel)) {
                return false;
            }

            channel.destroy();
            return true;
        }

        @Override
        public void cleanup() {
            for (Channel channel : leased) {
                channel.destroy();
            }
            leased.clear();
        }

        @Override
        public int capacity() {
            return maximum;
        }

        @Override
        public int used() {
            return leased.size();
        }
    }

    public enum PoolKind {
        STATIC,
        STREAM
    }
}