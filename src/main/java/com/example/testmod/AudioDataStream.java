package com.example.testmod;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;

public interface AudioDataStream extends AutoCloseable {
    AudioFormat getFormat();

    ByteBuffer getBuffer(int size) throws IOException;

    @Override
    void close() throws IOException;
}
