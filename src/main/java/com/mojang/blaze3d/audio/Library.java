package com.mojang.blaze3d.audio;

import java.nio.IntBuffer;
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

public class Library {
    static final Logger LOGGER = LoggerFactory.getLogger(Library.class);

    private static final int NO_DEVICE = 0;
    private static final int DEFAULT_CHANNEL_COUNT = 30;

    private long currentDevice;
    private long context;
    private boolean supportsDisconnections;
    private @Nullable String defaultDeviceName;

    private static final ChannelPool EMPTY = new ChannelPool() {
        @Override
        public @Nullable Channel acquire() {
            return null;
        }

        @Override
        public boolean release(Channel channel) {
            return false;
        }

        @Override
        public void cleanup() {
        }

        @Override
        public int getMaxCount() {
            return 0;
        }

        @Override
        public int getUsedCount() {
            return 0;
        }
    };

    private ChannelPool staticChannels = EMPTY;
    private ChannelPool streamingChannels = EMPTY;
    private final TListener listener = new TListener();

    public Library() {
        this.defaultDeviceName = getDefaultDeviceName();
    }

    public void init(@Nullable String deviceName, boolean hrtfEnabled) {
        this.currentDevice = openDeviceOrFallback(deviceName);
        this.supportsDisconnections = false;

        ALCCapabilities alcCapabilities = ALC.createCapabilities(this.currentDevice);
        if (checkALCError(this.currentDevice, "Get capabilities")) {
            throw new IllegalStateException("Failed to get OpenAL capabilities");
        }

        if (!alcCapabilities.OpenALC11) {
            throw new IllegalStateException("OpenAL 1.1 not supported");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attributes = createAttributes(
                    stack,
                    alcCapabilities.ALC_SOFT_HRTF && hrtfEnabled
            );
            this.context = ALC10.alcCreateContext(this.currentDevice, attributes);
        }

        if (checkALCError(this.currentDevice, "Create context")) {
            throw new IllegalStateException("Unable to create OpenAL context");
        }

        if (!ALC10.alcMakeContextCurrent(this.context)) {
            throw new IllegalStateException("Unable to make OpenAL context current");
        }

        int channelCount = getChannelCount();
        int streamingCount = clamp((int) Math.sqrt(channelCount), 2, 8);
        int staticCount = clamp(channelCount - streamingCount, 8, 255);

        this.staticChannels = new CountingChannelPool(staticCount);
        this.streamingChannels = new CountingChannelPool(streamingCount);

        ALCapabilities alCapabilities = AL.createCapabilities(alcCapabilities);
        checkALError("Initialization");

        if (!alCapabilities.AL_EXT_source_distance_model) {
            throw new IllegalStateException("AL_EXT_source_distance_model is not supported");
        }

        AL10.alEnable(0x0200); // AL_SOURCE_DISTANCE_MODEL

        if (!AL10.alIsExtensionPresent("AL_EXT_LINEAR_DISTANCE")) {
            throw new IllegalStateException("AL_EXT_LINEAR_DISTANCE is not supported");
        }

        checkALError("Enable per-source distance models");

        LOGGER.info("OpenAL initialized on device {}", getCurrentDeviceName());
        this.supportsDisconnections =
                ALC10.alcIsExtensionPresent(this.currentDevice, "ALC_EXT_disconnect");
    }

    private IntBuffer createAttributes(MemoryStack stack, boolean hrtfEnabled) {
        IntBuffer attributes = stack.callocInt(7);
        int attributesSize = ALC10.alcGetInteger(this.currentDevice, 0x1008); // ALC_ATTRIBUTES_SIZE

        if (attributesSize > 0) {
            attributes.put(0x1992).put(hrtfEnabled ? 1 : 0); // ALC_HRTF_SOFT
            attributes.put(0x1996).put(0); // ALC_HRTF_ID_SOFT
        }

        attributes.put(0x1007).put(44100); // ALC_FREQUENCY
        return attributes.put(0).flip();
    }

    private int getChannelCount() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int size = ALC10.alcGetInteger(this.currentDevice, 0x1002); // ALC_ATTRIBUTES_SIZE
            if (checkALCError(this.currentDevice, "Get attributes size")) {
                throw new IllegalStateException("Failed to get OpenAL attributes");
            }

            if (size <= 0) {
                return DEFAULT_CHANNEL_COUNT;
            }

            IntBuffer attributes = stack.mallocInt(size);
            ALC10.alcGetIntegerv(this.currentDevice, 0x1003, attributes); // ALC_ALL_ATTRIBUTES
            if (checkALCError(this.currentDevice, "Get attributes")) {
                throw new IllegalStateException("Failed to get OpenAL attributes");
            }

            for (int i = 0; i + 1 < size; ) {
                int key = attributes.get(i++);
                if (key == 0) {
                    break;
                }

                int value = attributes.get(i++);
                if (key == 0x1010) { // ALC_MONO_SOURCES
                    return value;
                }
            }

            return DEFAULT_CHANNEL_COUNT;
        }
    }

    public static @Nullable String getDefaultDeviceName() {
        if (!ALC10.alcIsExtensionPresent(NO_DEVICE, "ALC_ENUMERATE_ALL_EXT")) {
            return null;
        }

        return ALC10.alcGetString(NO_DEVICE, 0x1012); // ALC_DEFAULT_ALL_DEVICES_SPECIFIER
    }

    public String getCurrentDeviceName() {
        String name = ALC10.alcGetString(this.currentDevice, 0x1013); // ALC_ALL_DEVICES_SPECIFIER
        if (name == null) {
            name = ALC10.alcGetString(this.currentDevice, 0x1004); // ALC_DEFAULT_DEVICE_SPECIFIER
        }
        return name == null ? "Unknown" : name;
    }

    public synchronized boolean hasDefaultDeviceChanged() {
        String currentDefault = getDefaultDeviceName();
        if (Objects.equals(this.defaultDeviceName, currentDefault)) {
            return false;
        }

        this.defaultDeviceName = currentDefault;
        return true;
    }

    private static long openDeviceOrFallback(@Nullable String deviceName) {
        OptionalLong device = OptionalLong.empty();

        if (deviceName != null) {
            device = tryOpenDevice(deviceName);
        }
        if (device.isEmpty()) {
            device = tryOpenDevice(getDefaultDeviceName());
        }
        if (device.isEmpty()) {
            device = tryOpenDevice(null);
        }

        if (device.isEmpty()) {
            throw new IllegalStateException("Failed to open OpenAL device");
        }

        return device.getAsLong();
    }

    private static OptionalLong tryOpenDevice(@Nullable String deviceName) {
        long device = ALC10.alcOpenDevice(deviceName);
        return device != 0L && !checkALCError(device, "Open device")
                ? OptionalLong.of(device)
                : OptionalLong.empty();
    }

    public void cleanup() {
        this.staticChannels.cleanup();
        this.streamingChannels.cleanup();

        if (this.context != 0L) {
            ALC10.alcMakeContextCurrent(0L);
            ALC10.alcDestroyContext(this.context);
            this.context = 0L;
        }

        if (this.currentDevice != 0L) {
            ALC10.alcCloseDevice(this.currentDevice);
            this.currentDevice = 0L;
        }
    }

    public TListener getListener() {
        return this.listener;
    }

    public @Nullable Channel acquireChannel(Pool pool) {
        return (pool == Pool.STREAMING ? this.streamingChannels : this.staticChannels).acquire();
    }

    public void releaseChannel(Channel channel) {
        if (!this.staticChannels.release(channel) && !this.streamingChannels.release(channel)) {
            throw new IllegalStateException("Tried to release unknown channel");
        }
    }

    public String getDebugString() {
        return String.format(
                Locale.ROOT,
                "Sounds: %d/%d + %d/%d",
                this.staticChannels.getUsedCount(),
                this.staticChannels.getMaxCount(),
                this.streamingChannels.getUsedCount(),
                this.streamingChannels.getMaxCount()
        );
    }

    public List<String> getAvailableSoundDevices() {
        List<String> devices = ALUtil.getStringList(NO_DEVICE, 0x1013);
        return devices == null ? Collections.emptyList() : devices;
    }

    public boolean isCurrentDeviceDisconnected() {
        return this.supportsDisconnections
                && ALC11.alcGetInteger(this.currentDevice, 0x0313) == 0;
    }

    private static boolean checkALCError(long device, String operation) {
        int error = ALC10.alcGetError(device);
        if (error == ALC10.ALC_NO_ERROR) {
            return false;
        }

        LOGGER.error(
                "OpenAL error during {}: 0x{}",
                operation,
                Integer.toHexString(error)
        );
        return true;
    }

    private static void checkALError(String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            throw new IllegalStateException(
                    "OpenAL error during " + operation + ": 0x" + Integer.toHexString(error)
            );
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    interface ChannelPool {
        @Nullable Channel acquire();

        boolean release(Channel channel);

        void cleanup();

        int getMaxCount();

        int getUsedCount();
    }

    static class CountingChannelPool implements ChannelPool {
        private final int limit;
        private final Set<Channel> activeChannels =
                Collections.newSetFromMap(new IdentityHashMap<>());

        CountingChannelPool(int limit) {
            this.limit = limit;
        }

        @Override
        public @Nullable Channel acquire() {
            if (this.activeChannels.size() >= this.limit) {
                if (Boolean.getBoolean("fabric.development")) {
                    Library.LOGGER.warn("Maximum sound pool size {} reached", this.limit);
                }
                return null;
            }

            Channel channel = Channel.create();
            if (channel != null) {
                this.activeChannels.add(channel);
            }
            return channel;
        }

        @Override
        public boolean release(Channel channel) {
            if (!this.activeChannels.remove(channel)) {
                return false;
            }

            channel.destroy();
            return true;
        }

        @Override
        public void cleanup() {
            this.activeChannels.forEach(Channel::destroy);
            this.activeChannels.clear();
        }

        @Override
        public int getMaxCount() {
            return this.limit;
        }

        @Override
        public int getUsedCount() {
            return this.activeChannels.size();
        }
    }

    public enum Pool {
        STATIC,
        STREAMING
    }
}