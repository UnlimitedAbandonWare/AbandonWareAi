package com.example.lms.plugin.image.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageManifestWriterDurabilityTest {

    @TempDir
    Path tempDir;

    @Test
    void failedWritePreservesThePreviousCompleteManifest() throws Exception {
        Path manifest = tempDir.resolve("job-1.json");
        String previous = "{\"id\":\"prior\",\"status\":\"SUCCEEDED\"}";
        Files.writeString(manifest, previous);
        ObjectMapper mapper = mock(ObjectMapper.class);
        ObjectWriter objectWriter = mock(ObjectWriter.class);
        when(mapper.writerWithDefaultPrettyPrinter()).thenReturn(objectWriter);
        doAnswer(invocation -> {
            File destination = invocation.getArgument(0);
            Files.writeString(
                    destination.toPath(),
                    "{",
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            throw new IOException("forced partial manifest write");
        }).when(objectWriter).writeValue(any(File.class), any());
        ImageManifestWriter writer = new ImageManifestWriter();
        ReflectionTestUtils.setField(writer, "mapper", mapper);
        ReflectionTestUtils.setField(writer, "manifestDir", tempDir.toString());
        ImageJob job = new ImageJob();
        job.setId("job-1");
        job.setStatus(ImageJob.Status.IN_PROGRESS);

        assertNull(writer.write(job));

        assertEquals(previous, Files.readString(manifest));
        try (var files = Files.list(tempDir)) {
            assertEquals(1L, files.count(), "failed writes must remove their owned temporary file");
        }
    }
}
