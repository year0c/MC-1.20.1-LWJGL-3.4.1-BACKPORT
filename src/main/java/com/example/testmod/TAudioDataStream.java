package com.example.testmod;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;

public interface TAudioDataStream extends AutoCloseable {
    AudioFormat getFormat();

    ByteBuffer getBuffer(int size) throws IOException;

    @Override
    void close() throws IOException;
}
