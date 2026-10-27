package com.mojang.blaze3d.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OpenAlUtil {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(OpenAlUtil.class);

    private OpenAlUtil() {
    }

    public static boolean reportAlFailure(String operation) {
        int errorCode = AL10.alGetError();
        if (errorCode == AL10.AL_NO_ERROR) {
            return false;
        }

        LOGGER.error("{}: {}", operation, describeAlError(errorCode));
        return true;
    }

    public static boolean reportAlcFailure(long deviceHandle, String operation) {
        int errorCode = ALC10.alcGetError(deviceHandle);
        if (errorCode == ALC10.ALC_NO_ERROR) {
            return false;
        }

        LOGGER.error(
                "{} (device {}): {}",
                operation,
                deviceHandle,
                describeAlcError(errorCode)
        );
        return true;
    }

    public static int toOpenAlFormat(AudioFormat audioFormat) {
        Encoding encoding = audioFormat.getEncoding();
        int channelCount = audioFormat.getChannels();
        int sampleBits = audioFormat.getSampleSizeInBits();

        if (encoding.equals(Encoding.PCM_SIGNED)
                || encoding.equals(Encoding.PCM_UNSIGNED)) {
            if (channelCount == 1 && sampleBits == 8) {
                return AL10.AL_FORMAT_MONO8;
            }
            if (channelCount == 1 && sampleBits == 16) {
                return AL10.AL_FORMAT_MONO16;
            }
            if (channelCount == 2 && sampleBits == 8) {
                return AL10.AL_FORMAT_STEREO8;
            }
            if (channelCount == 2 && sampleBits == 16) {
                return AL10.AL_FORMAT_STEREO16;
            }
        }

        throw new IllegalArgumentException("Unsupported audio format: " + audioFormat);
    }

    private static String describeAlError(int errorCode) {
        return switch (errorCode) {
            case AL10.AL_INVALID_NAME -> "Invalid name parameter";
            case AL10.AL_INVALID_ENUM -> "Invalid enumerated parameter";
            case AL10.AL_INVALID_VALUE -> "Invalid parameter value";
            case AL10.AL_INVALID_OPERATION -> "Invalid operation";
            case AL10.AL_OUT_OF_MEMORY -> "Unable to allocate memory";
            default -> "Unrecognized OpenAL error: " + errorCode;
        };
    }

    private static String describeAlcError(int errorCode) {
        return switch (errorCode) {
            case ALC10.ALC_INVALID_DEVICE -> "Invalid device";
            case ALC10.ALC_INVALID_CONTEXT -> "Invalid context";
            case ALC10.ALC_INVALID_ENUM -> "Illegal enum";
            case ALC10.ALC_INVALID_VALUE -> "Invalid value";
            case ALC10.ALC_OUT_OF_MEMORY -> "Unable to allocate memory";
            default -> "Unrecognized ALC error: " + errorCode;
        };
    }
}