package ai.abandonware.nova.orch.failpattern;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Append failure-pattern events as JSONL.
 *
 * <p>Designed to be safe:
 * <ul>
 *     <li>best-effort (exceptions swallowed)</li>
 *     <li>stores hash-only message diagnostics</li>
 * </ul>
 */
public final class FailurePatternJsonlWriter {

    private final ObjectMapper om;
    private final Path path;
    private final boolean enabled;

    public FailurePatternJsonlWriter(ObjectMapper om, NovaFailurePatternProperties props) {
        this.om = om;
        this.path = Path.of(props.getJsonl().getPath());
        this.enabled = props.getJsonl().isWriteEnabled();
    }

    public void write(FailurePatternEvent evt) {
        if (!enabled || evt == null) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            FailurePatternEvent safeEvt = new FailurePatternEvent(
                    evt.tsEpochMillis(),
                    evt.kind(),
                    evt.source(),
                    evt.key(),
                    evt.cooldownMs(),
                    evt.cooldownPolicy(),
                    evt.logger(),
                    evt.level(),
                    messageDiagnostic(evt.message())
            );

            String line = om.writeValueAsString(safeEvt);

            try (BufferedWriter w = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                w.write(line);
                w.newLine();
            }
        } catch (Exception ignored) {
            FailurePatternTrace.traceSkipped("failurePatternJsonl.write", ignored);
        }
    }

    Path path() {
        return path;
    }

    private static String messageDiagnostic(String s) {
        if (s == null) {
            return null;
        }
        String safePreview = SafeRedactor.safeMessage(s, 128);
        boolean redacted = safePreview != null && !safePreview.equals(s.replace('\n', ' ').replace('\r', ' ').trim());
        String hash = SafeRedactor.hash12(s);
        return "present=true len=" + s.length()
                + " hash12=" + (hash == null ? "" : hash)
                + " redacted=" + redacted;
    }
}
