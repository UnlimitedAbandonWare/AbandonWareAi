package com.example.lms.cfvm.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class FileRawStore implements CfvmRawStore {
  private final File file;
  private final ObjectMapper om = new ObjectMapper();

  public FileRawStore(@Value("${cfvm.store.path:./data/cfvm-events.ndjson}") String path) {
    this.file = new File(path);
    File dir = this.file.getParentFile();
    if (dir != null) { dir.mkdirs(); }
  }
  @Override
  public synchronized void append(Map<String, Object> event) {
    try (FileOutputStream fos = new FileOutputStream(file, true)) {
      fos.write((om.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // non-blocking
    }
  }
  @Override
  public Stream<Map<String, Object>> scan(Duration window) {
    return Stream.empty();
  }
}