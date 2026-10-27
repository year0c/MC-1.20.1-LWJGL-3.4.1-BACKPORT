package com.mojang.blaze3d.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;

public interface AudioStream extends AutoCloseable {
    AudioFormat getFormat();

    ByteBuffer getBuffer(int size) throws IOException;

    @Override
    void close() throws IOException;
}