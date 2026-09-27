package com.example.lms.api;

import com.example.lms.service.PkiValidationStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;



@RestController
@RequestMapping("/.well-known/pki-validation")
@RequiredArgsConstructor
public class PkiValidationController {

    private static final System.Logger LOG = System.getLogger(PkiValidationController.class.getName());

    private final PkiValidationStorageService storageService;

    @GetMapping("/{fileName}")
    public ResponseEntity<byte[]> download(@PathVariable String fileName) {
        try {
            return storageService.read(fileName)
                    .map(content -> ResponseEntity.ok()
                            .contentType(MediaType.TEXT_PLAIN)
                            .body(content))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            traceSuppressed("pkiRead.badRequest", e);
            return ResponseEntity.badRequest().build();
        } catch (RuntimeException e) {
            traceSuppressed("pkiRead.runtime", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        try {
            String url = storageService.save(file);
            return ResponseEntity.ok(new FileUploadResponse("업로드 성공", url, true));
        } catch (IllegalArgumentException e) {
            traceSuppressed("pkiUpload.badRequest", e);
            return ResponseEntity.badRequest().body(new FileUploadResponse(publicPkiUploadError(e), null, false));
        } catch (RuntimeException e) {
            traceSuppressed("pkiUpload.runtime", e);
            return ResponseEntity.internalServerError().body(new FileUploadResponse(publicPkiUploadError(e), null, false));
        }
    }

    static String publicPkiUploadError(Exception e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        return "PKI upload failed: errorCode=pki_upload_failed"
                + " errorHash=" + com.example.lms.trace.SafeRedactor.hashValue(message)
                + " errorLength=" + message.length();
    }

    private static void traceSuppressed(String stage, RuntimeException failure) {
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "PKI upload fallback stage={0} errorType={1}",
                    stage,
                    failure == null ? "unknown" : failure.getClass().getSimpleName());
        }
    }
}
