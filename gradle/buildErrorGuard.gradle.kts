import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Build error guard & preflight fixer.
 *
 * - preflightFixes: neutralizes common merge artefacts (placeholders, lone '...' lines, stray '\1')
 *   and applies a safe auto-fix for the known trailing '}' issue (스터프2).
 * - errorGuard: runs tools/build_error_guard.py to fail fast with actionable hints when known patterns appear.
 */

fun computeBraceBalanceIgnoringStringsAndComments(text: String): Pair<Int, Int> {
    var i = 0
    var balance = 0
    var lastCloseIdx = -1

    var inLineComment = false
    var inBlockComment = false
    var inSingleQuote = false
    var inDoubleQuote = false
    var escaped = false

    while (i < text.length) {
        val c = text[i]
        val next = if (i + 1 < text.length) text[i + 1] else '\u0000'

        if (inLineComment) {
            if (c == '\n') inLineComment = false
            i++
            continue
        }

        if (inBlockComment) {
            if (c == '*' && next == '/') {
                inBlockComment = false
                i += 2
                continue
            }
            i++
            continue
        }

        if (inSingleQuote) {
            if (!escaped && c == '\\') {
                escaped = true
                i++
                continue
            }
            if (!escaped && c == '\'') {
                inSingleQuote = false
            }
            escaped = false
            i++
            continue
        }

        if (inDoubleQuote) {
            if (!escaped && c == '\\') {
                escaped = true
                i++
                continue
            }
            if (!escaped && c == '"') {
                inDoubleQuote = false
            }
            escaped = false
            i++
            continue
        }

        // Outside strings/comments
        if (c == '/' && next == '/') {
            inLineComment = true
            i += 2
            continue
        }
        if (c == '/' && next == '*') {
            inBlockComment = true
            i += 2
            continue
        }
        if (c == '\'') {
            inSingleQuote = true
            escaped = false
            i++
            continue
        }
        if (c == '"') {
            inDoubleQuote = true
            escaped = false
            i++
            continue
        }

        if (c == '{') balance++
        if (c == '}') {
            balance--
            lastCloseIdx = i
        }

        i++
    }

    return balance to lastCloseIdx
}

fun removeOneStrayTrailingCloseBraceIfDetected(file: File) {
    val original = file.readText(StandardCharsets.UTF_8)
    val (bal, lastClose) = computeBraceBalanceIgnoringStringsAndComments(original)

    // Typical failure: one extra '}' appended at EOF -> balance == -1.
    if (bal == -1 && lastClose >= 0) {
        val fixed = original.removeRange(lastClose, lastClose + 1)
        val (bal2, _) = computeBraceBalanceIgnoringStringsAndComments(fixed)
        if (bal2 == 0) {
            file.writeText(fixed, StandardCharsets.UTF_8)
            println("[preflightFixes] Removed one stray trailing '}' from ${file.path}")
        }
    }
}

tasks.register("preflightFixes") {
    group = "verification"
    description = "Preflight: neutralize placeholders/merge artefacts and apply safe auto-fixes."

    doLast {
        val replacements = listOf(
            // Remove standalone ellipsis placeholder lines (common merge artefact)
            Regex("(?m)^\\s*\\.\\.\\.\\s*$") to "",
            Regex("(?m)^\\s*…\\s*$") to "",
            // Normalize ellipsis chars
            Regex("…") to "...",
            // Neutralize placeholders (do not keep '{스터프3}' in code/docs)
            Regex("\\{\\s*스터프3\\s*\\}") to "STUFF3_REMOVED",
            Regex("\\{\\s*STUFF3\\s*\\}") to "STUFF3_REMOVED",
            // Also neutralize legacy disabled markers used by some tooling
            Regex("\\{\\s*STUFF3_DISABLED\\s*\\}") to "STUFF3_REMOVED",

            // WebClient ClientResponse.Builder#body(null) ambiguity (Flux<DataBuffer> vs String)
            // Fix by casting to String so Javac can resolve the overload.
            Regex("\\.mutate\\(\\)\\.body\\(null\\)") to ".mutate().body((String) null)",
            // Remove lone regex backreference tokens accidentally pasted into source (e.g., '\\1')
            Regex("(?m)^[ \\t]*\\\\1[ \\t]*$") to ""
        )

        val textFiles = fileTree(rootDir) {
            include("**/*.java", "**/*.kt", "**/*.kts", "**/*.gradle", "**/*.groovy", "**/*.yml", "**/*.yaml", "**/*.md")
            exclude("**/build/**", "**/out/**", "**/.gradle/**", "**/node_modules/**", "**/.idea/**", "**/.vscode/**")
        }

        var changed = 0
        textFiles.files.forEach { f ->
            val original = f.readText(StandardCharsets.UTF_8)
            var t = original
            for ((rx, repl) in replacements) {
                t = t.replace(rx, repl)
            }
            if (t != original) {
                f.writeText(t, StandardCharsets.UTF_8)
                changed++
            }
        }

        // 스터프2: trailing extra '}' auto-fix (safe, only when balance == -1)
        val suspects = listOf(
            "src/main/java/com/example/lms/service/rag/HybridRetriever.java",
            "src/main/java/com/example/lms/service/ChatService.java",
            "src/main/java/com/example/lms/service/NaverSearchService.java",
            "src/main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"
        ).map { rootDir.resolve(it) }

        suspects.filter { it.exists() }.forEach { removeOneStrayTrailingCloseBraceIfDetected(it) }

        if (changed > 0) {
            println("[preflightFixes] Updated $changed file(s) (placeholders/merge tokens).")
        }
    }
}

// Ensure preflight runs before compilation tasks.
tasks.matching { it.name == "compileJava" || it.name == "compileTestJava" }.configureEach {
    dependsOn("preflightFixes")
}

tasks.register("errorGuard") {
    group = "verification"
    description = "Detect known build error patterns and fail fast with actionable hints."

    doLast {
        val os = System.getProperty("os.name").lowercase()
        val python = if (os.contains("win")) "python" else "python3"

        val cmd = listOf(python, "${rootDir}/tools/build_error_guard.py", "--root", rootDir.absolutePath)
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (out.isNotBlank()) {
            println(out)
        }
        val code = p.waitFor()
        if (code != 0) {
            throw GradleException("Build error guard detected known failure patterns (exit=$code).")
        }
    }
}

// Run guard after compilation so it can parse the produced logs (if any).
tasks.matching { it.name == "compileJava" }.configureEach {
    finalizedBy("errorGuard")
}
