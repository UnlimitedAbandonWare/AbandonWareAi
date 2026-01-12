
package tools.scorecard;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: tools.scorecard.Scorecard
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: tools.scorecard.Scorecard
role: config
*/
public class Scorecard {
    static class Result {
        int base = 0;
        int components = 0;
        int duplicatePenalty = 0;
        int todoPenalty = 0;
        int total() { return base + components + duplicatePenalty + todoPenalty; }
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".");
        Result r = compute(root);
        System.out.println("== Scorecard ==");
        System.out.println("Base: " + r.base);
        System.out.println("Components: " + r.components);
        System.out.println("DuplicatePenalty: " + r.duplicatePenalty);
        System.out.println("TodoPenalty: " + r.todoPenalty);
        System.out.println("TOTAL: " + r.total());
    }

    static Result compute(Path root) throws IOException {
        Result r = new Result();
        // Feature code existence
        long javaCount = Files.walk(root.resolve("src"))
            .filter(p -> p.toString().endsWith(".java")).count();
        if (javaCount > 0) r.base += 30;

        // Components: Controller/Service/config/properties/tests/internal refs
        boolean hasController = hasAny(root, "**/*Controller.java");
        boolean hasService = hasAny(root, "**/*Service.java");
        boolean hasConfig = hasAny(root, "**/*Config.java");
        boolean hasProps = hasAny(root, "**/application*.yml");
        boolean hasTests = hasAny(root, "**/*Test.java");
        boolean hasInternal = Files.exists(root.resolve("DUPLICATE_CLASS_REPORT.md")) || Files.exists(root.resolve("prompts/prompts.manifest.yaml"));
        int comp = 0;
        comp += hasController ? 2 : 0;
        comp += hasService ? 2 : 0;
        comp += hasConfig ? 2 : 0;
        comp += hasProps ? 2 : 0;
        comp += hasTests ? 1 : 0;
        comp += hasInternal ? 1 : 0;
        r.components += Math.min(comp, 10);

        // Duplicate class penalty: -5 per duplicate entry in report
        int dup = parseDuplicateCount(root.resolve("DUPLICATE_CLASS_REPORT.md"));
        r.duplicatePenalty -= 5 * dup;

        // TODO penalty: -3 per file up to -9 per file (we approximate: count per file then cap)
        int todoPenalty = 0;
        Pattern todoPattern = Pattern.compile("\\bTODO\\b");
        List<Path> files = new ArrayList<>();
        Files.walk(root).forEach(p -> {
            String s = p.toString();
            if (s.endsWith(".java") || s.endsWith(".md") || s.endsWith(".yml") || s.endsWith(".yaml")) files.add(p);
        });
        for (Path p : files) {
            String content = Files.isRegularFile(p) ? new String(Files.readAllBytes(p)) : "";
            int c = 0;
            var m = todoPattern.matcher(content);
            while (m.find()) c++;
            if (c > 0) todoPenalty -= Math.min(9, 3 * c);
        }
        r.todoPenalty += todoPenalty;

        return r;
    }

    static boolean hasAny(Path root, String glob) throws IOException {
        final PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + glob);
        try (var stream = Files.walk(root)) {
            return stream.anyMatch(p -> matcher.matches(root.relativize(p)));
        }
    }

    static int parseDuplicateCount(Path report) {
        if (!Files.exists(report)) return 0;
        try {
            for (String line : Files.readAllLines(report)) {
                if (line.startsWith("Post-move duplicates remaining:")) {
                    String n = line.replace("Post-move duplicates remaining:", "").trim();
                    return Integer.parseInt(n);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }
}