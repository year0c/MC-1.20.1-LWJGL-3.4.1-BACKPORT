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

public final class Library {
    private static final Logger LOG = LoggerFactory.getLogger(Library.class);

    private static final int NO_DEVICE = 0;
    private static final int DEFAULT_SOURCE_LIMIT = 30;
    private static final int ALC_FREQUENCY = 0x1007;
    private static final int ALC_ATTRIBUTES_SIZE = 0x1002;
    private static final int ALC_ALL_ATTRIBUTES = 0x1003;
    private static final int ALC_MONO_SOURCES = 0x1010;
    private static final int ALC_HRTF_SOFT = 0x1992;
    private static final int ALC_HRTF_ID_SOFT = 0x1996;
    private static final int AL_SOURCE_DISTANCE_MODEL = 0x0200;
    private static final int ALC_DEFAULT_ALL_DEVICES_SPECIFIER = 0x1012;
    private static final int ALC_ALL_DEVICES_SPECIFIER = 0x1013;
    private static final int ALC_CONNECTED = 0x0313;

    private long deviceHandle;
    private long contextHandle;
    private boolean canCheckDisconnects;
    private @Nullable String lastDefaultDevice;

    private final Listener listener = new Listener();
    private ChannelPool staticPool = new BoundedChannelPool(0);
    private ChannelPool streamingPool = new BoundedChannelPool(0);

    public Library() {
        lastDefaultDevice = getDefaultDeviceName();
    }

    public void init(@Nullable String requestedDevice, boolean enableHrtf) {
        deviceHandle = openDevice(requestedDevice);

        ALCCapabilities alcCaps = ALC.createCapabilities(deviceHandle);
        if (checkAlcError(deviceHandle, "Read device capabilities")) {
            throw new IllegalStateException("Could not read OpenAL device capabilities");
        }
        if (!alcCaps.OpenALC11) {
            throw new IllegalStateException("OpenAL 1.1 is required");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attributes = createContextAttributes(
                    stack,
                    alcCaps.ALC_SOFT_HRTF && enableHrtf
            );
            contextHandle = ALC10.alcCreateContext(deviceHandle, attributes);
        }

        if (contextHandle == 0L || checkAlcError(deviceHandle, "Create context")) {
            throw new IllegalStateException("Could not create an OpenAL context");
        }
        if (!ALC10.alcMakeContextCurrent(contextHandle)) {
            throw new IllegalStateException("Could not activate the OpenAL context");
        }

        ALCapabilities alCaps = AL.createCapabilities(alcCaps);
        checkAlError("Initialize OpenAL");

        if (!alCaps.AL_EXT_source_distance_model) {
            throw new IllegalStateException("Per-source distance models are unavailable");
        }

        AL10.alEnable(AL_SOURCE_DISTANCE_MODEL);
        checkAlError("Enable per-source distance models");

        int sourceLimit = querySourceLimit();
        int streamingLimit = clamp((int) Math.sqrt(sourceLimit), 2, 8);
        int staticLimit = clamp(sourceLimit - streamingLimit, 8, 255);

        staticPool = new BoundedChannelPool(staticLimit);
        streamingPool = new BoundedChannelPool(streamingLimit);
        canCheckDisconnects =
                ALC10.alcIsExtensionPresent(deviceHandle, "ALC_EXT_disconnect");

        LOG.info("OpenAL initialized on device {}", getCurrentDeviceName());
    }

    private IntBuffer createContextAttributes(MemoryStack stack, boolean useHrtf) {
        IntBuffer attributes = stack.callocInt(7);

        if (useHrtf) {
            attributes.put(ALC_HRTF_SOFT).put(1);
            attributes.put(ALC_HRTF_ID_SOFT).put(0);
        }

        attributes.put(ALC_FREQUENCY).put(44100);
        attributes.put(0);
        return attributes.flip();
    }

    private int querySourceLimit() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int attributeCount = ALC10.alcGetInteger(deviceHandle, ALC_ATTRIBUTES_SIZE);
            if (checkAlcError(deviceHandle, "Query device attribute count")
                    || attributeCount <= 0) {
                return DEFAULT_SOURCE_LIMIT;
            }

            IntBuffer attributes = stack.mallocInt(attributeCount);
            ALC10.alcGetIntegerv(deviceHandle, ALC_ALL_ATTRIBUTES, attributes);
            if (checkAlcError(deviceHandle, "Read device attributes")) {
                return DEFAULT_SOURCE_LIMIT;
            }

            for (int index = 0; index + 1 < attributeCount; ) {
                int attribute = attributes.get(index++);
                if (attribute == 0) {
                    break;
                }

                int value = attributes.get(index++);
                if (attribute == ALC_MONO_SOURCES && value > 0) {
                    return value;
                }
            }

            return DEFAULT_SOURCE_LIMIT;
        }
    }

    public static @Nullable String getDefaultDeviceName() {
        if (!ALC10.alcIsExtensionPresent(NO_DEVICE, "ALC_ENUMERATE_ALL_EXT")) {
            return null;
        }
        return ALC10.alcGetString(NO_DEVICE, ALC_DEFAULT_ALL_DEVICES_SPECIFIER);
    }

    public String getCurrentDeviceName() {
        String name = ALC10.alcGetString(deviceHandle, ALC10.ALC_DEVICE_SPECIFIER);
        return name == null ? "Unknown" : name;
    }

    public synchronized boolean hasDefaultDeviceChanged() {
        String currentDefault = getDefaultDeviceName();
        if (Objects.equals(lastDefaultDevice, currentDefault)) {
            return false;
        }

        lastDefaultDevice = currentDefault;
        return true;
    }

    private static long openDevice(@Nullable String requestedName) {
        OptionalLong result = requestedName == null
                ? OptionalLong.empty()
                : tryOpenDevice(requestedName);

        if (result.isEmpty()) {
            result = tryOpenDevice(getDefaultDeviceName());
        }
        if (result.isEmpty()) {
            result = tryOpenDevice(null);
        }

        return result.orElseThrow(
                () -> new IllegalStateException("Could not open an OpenAL device")
        );
    }

    private static OptionalLong tryOpenDevice(@Nullable String name) {
        long opened = ALC10.alcOpenDevice(name);
        if (opened == 0L) {
            return OptionalLong.empty();
        }

        if (checkAlcError(opened, "Open device")) {
            ALC10.alcCloseDevice(opened);
            return OptionalLong.empty();
        }

        return OptionalLong.of(opened);
    }

    public void cleanup() {
        staticPool.cleanup();
        streamingPool.cleanup();

        if (contextHandle != 0L) {
            ALC10.alcMakeContextCurrent(0L);
            ALC10.alcDestroyContext(contextHandle);
            contextHandle = 0L;
        }

        if (deviceHandle != 0L) {
            ALC10.alcCloseDevice(deviceHandle);
            deviceHandle = 0L;
        }

        canCheckDisconnects = false;
    }

    public Listener getListener() {
        return listener;
    }

    public @Nullable Channel acquireChannel(Pool pool) {
        return (pool == Pool.STREAMING ? streamingPool : staticPool).acquire();
    }

    public void releaseChannel(Channel channel) {
        if (!staticPool.release(channel) && !streamingPool.release(channel)) {
            throw new IllegalStateException("Tried to release an unowned channel");
        }
    }

    public String getDebugString() {
        return String.format(
                Locale.ROOT,
                "Sounds: %d/%d + %d/%d",
                staticPool.usedCount(),
                staticPool.capacity(),
                streamingPool.usedCount(),
                streamingPool.capacity()
        );
    }

    public List<String> getAvailableSoundDevices() {
        List<String> names = ALUtil.getStringList(NO_DEVICE, ALC_ALL_DEVICES_SPECIFIER);
        return names == null ? Collections.emptyList() : names;
    }

    public boolean isCurrentDeviceDisconnected() {
        return canCheckDisconnects
                && ALC11.alcGetInteger(deviceHandle, ALC_CONNECTED) == 0;
    }

    private static boolean checkAlcError(long device, String operation) {
        int error = ALC10.alcGetError(device);
        if (error == ALC10.ALC_NO_ERROR) {
            return false;
        }

        LOG.error("ALC error during {}: 0x{}", operation, Integer.toHexString(error));
        return true;
    }

    private static void checkAlError(String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            throw new IllegalStateException(
                    "OpenAL error during " + operation + ": 0x" + Integer.toHexString(error)
            );
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private interface ChannelPool {
        @Nullable Channel acquire();

        boolean release(Channel channel);

        void cleanup();

        int capacity();

        int usedCount();
    }

    private static final class BoundedChannelPool implements ChannelPool {
        private final int maximum;
        private final Set<Channel> leasedChannels =
                Collections.newSetFromMap(new IdentityHashMap<>());

        private BoundedChannelPool(int maximum) {
            this.maximum = maximum;
        }

        @Override
        public @Nullable Channel acquire() {
            if (leasedChannels.size() >= maximum) {
                if (Boolean.getBoolean("fabric.development")) {
                    LOG.warn("Audio channel limit reached ({})", maximum);
                }
                return null;
            }

            Channel channel = Channel.create();
            if (channel != null) {
                leasedChannels.add(channel);
            }
            return channel;
        }

        @Override
        public boolean release(Channel channel) {
            if (!leasedChannels.remove(channel)) {
                return false;
            }

            channel.destroy();
            return true;
        }

        @Override
        public void cleanup() {
            leasedChannels.forEach(Channel::destroy);
            leasedChannels.clear();
        }

        @Override
        public int capacity() {
            return maximum;
        }

        @Override
        public int usedCount() {
            return leasedChannels.size();
        }
    }

    public enum Pool {
        STATIC,
        STREAMING
    }
}