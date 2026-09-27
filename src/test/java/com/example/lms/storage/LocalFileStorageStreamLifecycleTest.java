package com.example.lms.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileStorageStreamLifecycleTest {
    @TempDir Path root;

    @Test
    void closesMultipartInputAfterSuccessfulCopy() {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream input = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        storage().save(file(input), "chat");
        assertTrue(closed.get(), "storage owns and must close the input stream it opens");
    }

    @Test
    void closesMultipartInputWhenCopyFails() {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream input = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("synthetic read failure"); }
            @Override public void close() { closed.set(true); }
        };
        assertThrows(RuntimeException.class, () -> storage().save(file(input), "chat"));
        assertTrue(closed.get(), "failed copy must still release its input stream");
    }

    private LocalFileStorageService storage() {
        var service = new LocalFileStorageService();
        ReflectionTestUtils.setField(service, "rootDir", root.toString());
        ReflectionTestUtils.setField(service, "maxBytes", 1048576L);
        return service;
    }

    private static MockMultipartFile file(InputStream input) {
        return new MockMultipartFile("file", "proof.txt", "text/plain", new byte[] {1, 2, 3}) {
            @Override public InputStream getInputStream() { return input; }
        };
    }
}
