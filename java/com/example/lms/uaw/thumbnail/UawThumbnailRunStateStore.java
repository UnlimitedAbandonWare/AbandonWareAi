package com.example.lms.uaw.thumbnail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * UAW Thumbnail 상태 저장소.
 *
 * <p>파일 경로는 uaw.thumbnail.state-path 로 설정 가능.
 * 원자적 저장을 위해 tmp 파일로 쓰고 move 합니다.</p>
 */
@Component
public class UawThumbnailRunStateStore {

    private static final Logger log = LoggerFactory.getLogger(UawThumbnailRunStateStore.class);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public UawThumbnailRunState load(Path path) {
        try {
            if (path == null) return UawThumbnailRunState.empty();
            if (!Files.exists(path)) return UawThumbnailRunState.empty();
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) return UawThumbnailRunState.empty();
            return mapper.readValue(bytes, UawThumbnailRunState.class);
        } catch (Exception e) {
            log.warn("[UAW_THUMB] state load failed: {} -> reset. err={}", path, e.toString());
            return UawThumbnailRunState.empty();
        }
    }

    public void save(Path path, UawThumbnailRunState state) {
        try {
            if (path == null) return;

            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
            Files.write(tmp, bytes);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("[UAW_THUMB] state save failed: {} err={}", path, e.toString());
        }
    }
}
