package com.example.lms.dto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;



/**
 * Represents an uploaded file attachment.  This record encapsulates the
 * raw bytes of the file together with basic metadata such as the file name,
 * MIME type, SHA-256 hash and size.  The canonical constructor performs
 * validation and automatically computes the SHA-256 hash when not provided.
 *
 * <p>This class is distinct from {@link AttachmentDto} which is used to
 * expose upload metadata to clients.  It is used internally by the chat
 * pipeline to carry file contents through the service layer.</p>
 *
 * @param fileName the original file name, must not be blank
 * @param mimeType the MIME type of the file, defaults to {@code application/octet-stream} when null/blank
 * @param content  the raw bytes of the file, must not be empty
 * @param sha256   hex-encoded SHA-256 hash of the file contents, computed when null/blank
 * @param size     size of the file in bytes, defaults to {@code content.length} when <= 0
 */
public record Attachment(String fileName,
                         String mimeType,
                         byte[] content,
                         String sha256,
                         long size) {
    public Attachment {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName required");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content required");
        }
        if (size <= 0) {
            size = content.length;
        }
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "application/octet-stream";
        }
        if (sha256 == null || sha256.isBlank()) {
            sha256 = sha256(content);
        }
    }

    private static String sha256(byte[] in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(in);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}