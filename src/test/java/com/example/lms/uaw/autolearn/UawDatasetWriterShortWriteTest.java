package com.example.lms.uaw.autolearn;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import static org.junit.jupiter.api.Assertions.*;

class UawDatasetWriterShortWriteTest {
    @Test void shortWritesConsumeTheWholeUtf8Record() throws Exception {
        var method = assertDoesNotThrow(() -> UawDatasetWriter.class.getDeclaredMethod(
                "writeFully", WritableByteChannel.class, ByteBuffer.class));
        method.setAccessible(true);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        WritableByteChannel channel = new WritableByteChannel() {
            public boolean isOpen() { return true; }
            public void close() {}
            public int write(ByteBuffer buffer) {
                int count = Math.min(2, buffer.remaining());
                for (int i = 0; i < count; i++) sink.write(buffer.get());
                return count;
            }
        };
        byte[] record = "{\"answer\":\"한글\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        method.invoke(null, channel, ByteBuffer.wrap(record));
        assertArrayEquals(record, sink.toByteArray());
    }

    @Test void stalledChannelFailsWithoutUnboundedSpin() throws Exception {
        var method = assertDoesNotThrow(() -> UawDatasetWriter.class.getDeclaredMethod(
                "writeFully", WritableByteChannel.class, ByteBuffer.class));
        method.setAccessible(true);
        WritableByteChannel channel = new WritableByteChannel() {
            public boolean isOpen() { return true; }
            public void close() {}
            public int write(ByteBuffer buffer) { return 0; }
        };
        var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(null, channel, ByteBuffer.wrap(new byte[]{1})));
        assertInstanceOf(IOException.class, error.getCause());
    }
}
