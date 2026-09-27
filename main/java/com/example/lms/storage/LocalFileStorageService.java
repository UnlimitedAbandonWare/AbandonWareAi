// src/main/java/com/example/lms/storage/LocalFileStorageService.java
package com.example.lms.storage;

import com.example.lms.storage.FileStorageService;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@Service
@RequiredArgsConstructor
public class LocalFileStorageService implements FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageService.class);
    private static final String UPLOAD_PREFIX = "/uploads/";

    /** application.yml 에서 설정 → 기본값은 project-root/uploads */
    @Value("${lms.upload-dir:uploads}")
    private String rootDir;

    @Value("${lms.upload-public-prefix:/uploads/}")
    private String uploadPublicPrefix;

    @Value("${lms.upload.max-bytes:10485760}")
    private long maxBytes;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".txt", ".md", ".pdf", ".png", ".jpg", ".jpeg", ".webp", ".csv", ".json");
    private final Set<String> issuedStoredPaths = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public String save(MultipartFile file, String subPath) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("빈 파일입니다.");
        }

        if (maxBytes > 0 && file.getSize() > maxBytes) {
            throw new IllegalArgumentException("파일 크기 제한을 초과했습니다.");
        }

        String originalName = StringUtils.cleanPath(String.valueOf(file.getOriginalFilename()));
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("허용되지 않은 파일 형식입니다.");
        }

        Path root = Path.of(rootDir).toAbsolutePath().normalize();
        Path targetDir = root.resolve(safeSubPath(subPath)).normalize();
        if (!targetDir.startsWith(root)) {
            throw new IllegalArgumentException("허용되지 않은 업로드 경로입니다.");
        }
        String filename = UUID.randomUUID() + extension;
        Path targetFile = targetDir.resolve(filename).normalize();
        if (!targetFile.startsWith(root)) {
            throw new IllegalArgumentException("허용되지 않은 업로드 파일 경로입니다.");
        }

        try {
            Path realRoot = prepareRealRoot(root);
            Path realTargetDir = realRoot.resolve(root.relativize(targetDir)).normalize();
            if (!realTargetDir.startsWith(realRoot)
                    || hasUnsafeExistingComponent(realRoot, realTargetDir)) {
                throw unsafeUploadPath();
            }
            Path existingParent = nearestExistingParent(realRoot, realTargetDir);
            if (existingParent == null
                    || !existingParent.toRealPath().startsWith(realRoot)) {
                throw unsafeUploadPath();
            }

            Files.createDirectories(realTargetDir);
            requireSafeDirectory(realRoot, realTargetDir);

            Path realTargetFile = realTargetDir.resolve(filename).normalize();
            if (!realTargetFile.startsWith(realRoot)
                    || Files.exists(realTargetFile, LinkOption.NOFOLLOW_LINKS)) {
                throw unsafeUploadPath();
            }
            try (var input = file.getInputStream()) {
                Files.copy(input, realTargetFile);
            }
            BasicFileAttributes savedAttributes = Files.readAttributes(
                    realTargetFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!savedAttributes.isRegularFile()
                    || savedAttributes.isSymbolicLink()
                    || savedAttributes.isOther()
                    || hasUnsafeExistingComponent(realRoot, realTargetFile)) {
                Files.deleteIfExists(realTargetFile);
                throw unsafeUploadPath();
            }

            String relative = realRoot.relativize(realTargetFile).toString().replace('\\', '/');
            String storedPath = normalizedPublicPrefix() + relative;
            issuedStoredPaths.add(storedPath);
            log.info("파일 저장 완료 rootHash={} pathHash={} pathLength={}",
                    SafeRedactor.hash12(realRoot.toString()),
                    SafeRedactor.hash12(storedPath),
                    storedPath.length());
            return storedPath;
        } catch (IOException e) {
            log.error("파일 저장 실패. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            throw new RuntimeException("파일 저장 실패", e);
        }
    }

    private static Path prepareRealRoot(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw unsafeUploadPath();
        }
        Path noFollowRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realRoot = root.toRealPath();
        if (!realRoot.equals(noFollowRoot)) {
            throw unsafeUploadPath();
        }
        BasicFileAttributes realAttributes = Files.readAttributes(
                realRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!realAttributes.isDirectory()
                || realAttributes.isSymbolicLink()
                || realAttributes.isOther()) {
            throw unsafeUploadPath();
        }
        return realRoot;
    }

    private static void requireSafeDirectory(Path realRoot, Path directory) throws IOException {
        if (!directory.startsWith(realRoot)
                || hasUnsafeExistingComponent(realRoot, directory)) {
            throw unsafeUploadPath();
        }
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || !directory.toRealPath().startsWith(realRoot)) {
            throw unsafeUploadPath();
        }
    }

    private static IllegalArgumentException unsafeUploadPath() {
        return new IllegalArgumentException("허용되지 않은 업로드 경로입니다.");
    }

    @Override
    public boolean delete(String storedPath) {
        Path root = null;
        boolean deleted = false;
        boolean issuedPathClaimed = false;
        boolean restoreIssuedPath = false;
        try {
            root = Path.of(rootDir).toAbsolutePath().normalize();
            String prefix = normalizedPublicPrefix();
            if (storedPath == null || !storedPath.startsWith(prefix)) {
                return false;
            }
            if (!issuedStoredPaths.remove(storedPath)) {
                return false;
            }
            issuedPathClaimed = true;
            restoreIssuedPath = true;

            String relativeText = storedPath.substring(prefix.length()).replace('\\', '/');
            if (relativeText.isBlank()) {
                return false;
            }
            Path relative = Path.of(relativeText);
            if (relative.isAbsolute()) {
                return false;
            }

            Path lexicalTarget = root.resolve(relative).normalize();
            if (lexicalTarget.equals(root) || !lexicalTarget.startsWith(root)) {
                return false;
            }

            BasicFileAttributes rootAttributes = Files.readAttributes(
                    root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!rootAttributes.isDirectory()
                    || rootAttributes.isSymbolicLink()
                    || rootAttributes.isOther()) {
                return false;
            }
            Path noFollowRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realRoot = root.toRealPath();
            if (!realRoot.equals(noFollowRoot)) {
                return false;
            }
            Path realTarget = realRoot.resolve(root.relativize(lexicalTarget)).normalize();
            if (!realTarget.startsWith(realRoot)
                    || hasUnsafeExistingComponent(realRoot, realTarget)) {
                return false;
            }

            Path existingParent = nearestExistingParent(realRoot, realTarget.getParent());
            if (existingParent == null
                    || !existingParent.toRealPath().startsWith(realRoot)) {
                return false;
            }
            if (!Files.exists(realTarget, LinkOption.NOFOLLOW_LINKS)) {
                restoreIssuedPath = false;
                return false;
            }
            BasicFileAttributes targetAttributes = Files.readAttributes(
                    realTarget, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!targetAttributes.isRegularFile()
                    || targetAttributes.isSymbolicLink()
                    || targetAttributes.isOther()) {
                return false;
            }

            deleted = Files.deleteIfExists(realTarget);
            restoreIssuedPath = !deleted && Files.exists(realTarget, LinkOption.NOFOLLOW_LINKS);
            return deleted;
        } catch (IOException | RuntimeException failure) {
            log.warn("파일 삭제 실패. pathHash={} pathLength={} errorHash={} errorLength={}",
                    SafeRedactor.hash12(String.valueOf(storedPath)),
                    storedPath == null ? 0 : storedPath.length(),
                    SafeRedactor.hashValue(messageOf(failure)),
                    messageLength(failure));
            return false;
        } finally {
            if (issuedPathClaimed && restoreIssuedPath) {
                issuedStoredPaths.add(storedPath);
            }
            log.info("파일 삭제 결과 rootHash={} deleted={} pathHash={}",
                    SafeRedactor.hash12(root == null ? null : root.toString()),
                    deleted,
                    SafeRedactor.hash12(String.valueOf(storedPath)));
        }
    }

    private static boolean hasUnsafeExistingComponent(Path root, Path target) throws IOException {
        Path current = root;
        for (Path name : root.relativize(target)) {
            current = current.resolve(name);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                return true;
            }
            Path followed = current.toRealPath();
            if (!followed.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static Path nearestExistingParent(Path root, Path candidateParent) {
        Path current = candidateParent;
        while (current != null && current.startsWith(root)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return current;
            }
            if (current.equals(root)) {
                break;
            }
            current = current.getParent();
        }
        return null;
    }

    private String normalizedPublicPrefix() {
        String prefix = uploadPublicPrefix == null || uploadPublicPrefix.isBlank()
                ? UPLOAD_PREFIX : uploadPublicPrefix.trim();
        if (!prefix.startsWith("/")) prefix = "/" + prefix;
        if (!prefix.endsWith("/")) prefix += "/";
        return prefix;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String safeSubPath(String subPath) {
        if (subPath == null || subPath.isBlank()) {
            return "";
        }
        return subPath.replace('\\', '/')
                .replaceAll("^/+", "")
                .replaceAll("[^A-Za-z0-9._/-]", "_");
    }
}
