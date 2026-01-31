package ai.abandonware.nova.orch.failpattern;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

/**
 * Append failure-pattern events as JSONL.
 *
 * <p>Designed to be safe:
 * <ul>
 *     <li>best-effort (exceptions swallowed)</li>
 *     <li>redacts common API-key shapes in messages</li>
 *     <li>clips message length</li>
 * </ul>
 */
public final class FailurePatternJsonlWriter {

    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9]{10,}\\b");
    private static final Pattern GROQ_KEY = Pattern.compile("\\bgsk_[A-Za-z0-9]{10,}\\b");

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
                    clip(redact(evt.message()), 512)
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
            // fail-soft: never break logging path
        }
    }

    Path path() {
        return path;
    }

    private static String redact(String s) {
        if (s == null) {
            return null;
        }
        String t = OPENAI_KEY.matcher(s).replaceAll("sk-REDACTED");
        t = GROQ_KEY.matcher(t).replaceAll("gsk_REDACTED");
        return t;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
