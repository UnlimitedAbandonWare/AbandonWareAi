import groovy.json.JsonOutput
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

plugins {
    java
    id("org.springframework.boot") version "3.3.4"
}

// NOTE:
// - 레포지토리는 settings.gradle.kts 의 dependencyResolutionManagement 에서만 관리합니다.
//   (RepositoriesMode.FAIL_ON_PROJECT_REPOS)
// - 오프라인 빌드를 위해 mirror/local repo 를 settings 에서 주입할 수 있도록 되어 있습니다.

fun String.toAwxBuildHostId(): String =
    replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('.', '-', '_')
        .ifBlank { "host" }

val awxSplitBuildOutputs = providers.gradleProperty("awx.splitBuildOutputs")
    .orElse(providers.environmentVariable("AWX_SPLIT_BUILD_OUTPUTS"))
    .map { it.equals("1") || it.equals("true", ignoreCase = true) || it.equals("yes", ignoreCase = true) || it.equals("on", ignoreCase = true) }
    .getOrElse(false)

val awxBuildHostId = providers.gradleProperty("awx.buildHostId")
    .orElse(providers.environmentVariable("AWX_BUILD_HOST_ID"))
    .orElse("local")
    .get()
    .toAwxBuildHostId()

val awxBuildRootDir = providers.gradleProperty("awx.buildRootDir")
    .orElse(providers.environmentVariable("AWX_BUILD_ROOT_DIR"))
    .map { it.trim() }
    .orNull

fun awxProjectBuildDir(project: org.gradle.api.Project): org.gradle.api.provider.Provider<org.gradle.api.file.Directory> =
    project.layout.dir(
        project.providers.provider {
            if (awxBuildRootDir.isNullOrBlank()) {
                project.layout.projectDirectory.dir("build/$awxBuildHostId").asFile
            } else {
                project.file(awxBuildRootDir)
                    .resolve(awxBuildHostId)
                    .resolve(project.path.trim(':').replace(':', '-').ifBlank { "root" })
            }
        }
    )

if (awxSplitBuildOutputs) {
    layout.buildDirectory.set(awxProjectBuildDir(project))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

// Preserve method parameter names in bytecode for stable reflection/AOP arg binding.
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}


// Dependency locking (optional but recommended for reproducible/offline builds)
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

subprojects {
    if (awxSplitBuildOutputs) {
        layout.buildDirectory.set(awxProjectBuildDir(project))
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf(
                "-parameters",
                "-Xlint:deprecation",
                "-Xlint:unchecked"
            )
        )
    }
}

dependencies {
    implementation(project(":app"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Caching (required by CacheConfig and decision caches)
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("commons-codec:commons-codec")
    implementation("com.google.guava:guava:33.2.1-jre")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("org.apache.lucene:lucene-core:9.10.0")
    implementation("org.apache.lucene:lucene-queryparser:9.10.0")
    implementation("org.apache.lucene:lucene-analysis-common:9.10.0")
    implementation("org.apache.lucene:lucene-analysis-nori:9.10.0")
    implementation("org.neo4j.driver:neo4j-java-driver:5.23.0")
    implementation("io.opentelemetry:opentelemetry-api:1.43.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("redis.clients:jedis")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    implementation("commons-io:commons-io:2.16.1")
    implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-jackson:2.11.0")
    implementation("com.squareup.retrofit2:adapter-rxjava2:2.11.0")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.8.3")
    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")

    // LangChain4j (Version purity gate: keep a single non-beta line)
    implementation("dev.langchain4j:langchain4j:1.0.1")
    implementation("dev.langchain4j:langchain4j-open-ai:1.0.1")
    implementation("org.bsc.langgraph4j:langgraph4j-core:1.8.16")
    implementation("org.bsc.langgraph4j:langgraph4j-postgres-saver:1.8.16")

    runtimeOnly("com.mysql:mysql-connector-j")
    // Verification profile (learning) uses in-memory H2 so verify_learning.sh works
    // without requiring external MariaDB/MySQL.
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testCompileOnly("org.projectlombok:lombok:1.18.32")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.32")
}

// --- Version purity gate (required) ---
tasks.register("checkLangchain4jVersionPurity") {
    doLast {
        val bad = configurations.flatMap { it.dependencies }
            .filter { (it.group ?: "") == "dev.langchain4j" }
            .filter { (it.version ?: "") != "1.0.1" }
            .map { "${'$'}{it.group}:${'$'}{it.name}:${'$'}{it.version}" }
        if (bad.isNotEmpty()) {
            throw GradleException("LangChain4j version purity violated: " + bad.joinToString(", "))
        }
    }
}
tasks.named("check") { dependsOn("checkLangchain4jVersionPurity") }

tasks.register("checkSourceSetHygiene") {
    doLast {
        val required = listOf("main/java", "main/resources", "app/src/main/java_clean", "app/src/main/resources")
        val missing = required.filterNot { file(it).isDirectory }
        if (missing.isNotEmpty()) {
            throw GradleException("Active sourceSet roots missing: " + missing.joinToString(", "))
        }
        val retainedSources = linkedMapOf<String, List<String>>()
        val owners = listOf(Triple("root", project, "main"), Triple("app", project("app"), "src/main"))
        for ((ownerName, owner, prefix) in owners) {
            val main = owner.extensions.getByType<org.gradle.api.tasks.SourceSetContainer>().getByName("main")
            fun verifyOwner(kind: String, roots: Set<java.io.File>, expected: java.io.File) {
                val absentJavaPluginDefault = if (ownerName == "root") owner.file("src/main/$kind") else null
                val configured = roots.filterNot { it == absentJavaPluginDefault && !it.exists() }
                    .map { it.canonicalFile }.toSet()
                if (configured != setOf(expected.canonicalFile)) {
                    throw GradleException("Active sourceSet owner mismatch: owner=$ownerName type=$kind")
                }
            }
            verifyOwner("java", main.java.srcDirs, owner.file("$prefix/${if (ownerName == "app") "java_clean" else "java"}"))
            verifyOwner("resources", main.resources.srcDirs, owner.file("$prefix/resources"))
            // Use the compiler's retained input after existing SourceSet/JavaCompile exclusions.
            retainedSources[ownerName] = owner.tasks.named<JavaCompile>("compileJava").get().source.files
                .map { relativePath(it).replace('\\', '/') }.sorted()
        }
        val manifest = layout.buildDirectory.file("reports/source-set-hygiene-retained.json").get().asFile
        manifest.parentFile.mkdirs()
        manifest.writeText(groovy.json.JsonOutput.toJson(retainedSources), Charsets.UTF_8)
        project.exec {
            commandLine("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                layout.projectDirectory.file("tools/context_purity_score.ps1").asFile.absolutePath,
                "-Workspace", layout.projectDirectory.asFile.absolutePath,
                "-ActiveSourceManifest", manifest.absolutePath)
        }.assertNormalExitValue()
        val inactiveAppJava = file("app/src/main/java")
        if (inactiveAppJava.isDirectory) {
            logger.lifecycle("[AWX][sourceset] inactive-present: app/src/main/java")
        }
    }
}
tasks.named("check") { dependsOn("checkSourceSetHygiene") }

val contextPurityDecisionsOutput = layout.projectDirectory.file("__reports__/context-purity-decisions.tsv")
tasks.register<Exec>("contextPurityReport") {
    description = "Generates bounded context-purity KEEP/DELETE/QUARANTINE/EVIDENCE_NEEDED decisions."
    group = "verification"
    doNotTrackState("Support roots can contain access-restricted evidence; the bounded report is regenerated each run.")
    dependsOn("checkSourceSetHygiene")
    inputs.file(layout.projectDirectory.file("tools/context_purity_score.ps1"))
    outputs.file(contextPurityDecisionsOutput)
    commandLine(
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        layout.projectDirectory.file("tools/context_purity_score.ps1").asFile.absolutePath,
        "-Workspace",
        layout.projectDirectory.asFile.absolutePath,
        "-OutputPath",
        contextPurityDecisionsOutput.asFile.absolutePath,
    )
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

val structuralAuditGitDir = layout.buildDirectory.dir("generated/structural-audit/git")
val structuralAuditGitHead = structuralAuditGitDir.map { it.file("head.txt") }
val structuralAuditGitBranch = structuralAuditGitDir.map { it.file("branch.txt") }
val structuralAuditGitPaths = structuralAuditGitDir.map { it.file("paths.z") }
val structuralAuditGitStatus = structuralAuditGitDir.map { it.file("status.z") }
val structuralAuditGitStage = structuralAuditGitDir.map { it.file("stage.z") }
val structuralAuditGitTags = structuralAuditGitDir.map { it.file("tags.z") }
val structuralAuditGitSkipWorktree =
    structuralAuditGitDir.map { it.file("git-skip-worktree-fallback.json") }

fun captureGitBytes(vararg arguments: String): ByteArray =
    providers.exec {
        workingDir(layout.projectDirectory)
        commandLine("git", *arguments)
    }.standardOutput.asBytes.get()

fun decodeGitNulTokens(raw: ByteArray): List<String> {
    if (raw.isEmpty()) {
        return emptyList()
    }
    if (raw.last() != 0.toByte()) {
        throw GradleException("structural-audit-git-fallback-capture-failed")
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val decoded = try {
        decoder.decode(ByteBuffer.wrap(raw)).toString()
    } catch (_: CharacterCodingException) {
        throw GradleException("structural-audit-git-fallback-capture-failed")
    }
    val tokens = decoded.split('\u0000')
    if (tokens.lastOrNull() != "" || tokens.dropLast(1).any { it.isEmpty() }) {
        throw GradleException("structural-audit-git-fallback-capture-failed")
    }
    return tokens.dropLast(1)
}

fun normalizeGitCapturePath(raw: String): String {
    val normalized = raw.replace('\\', '/')
    val parts = normalized.split('/')
    if (
        normalized.isBlank() ||
        normalized != raw ||
        normalized.startsWith('/') ||
        Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
        "://" in normalized ||
        normalized.any { it.code < 32 } ||
        parts.any { it.isEmpty() || it == "." || it == ".." }
    ) {
        throw GradleException("structural-audit-git-fallback-capture-failed")
    }
    return normalized
}

fun parseStageObjectIds(raw: ByteArray): Map<String, String> {
    val byPath = linkedMapOf<String, String>()
    for (token in decodeGitNulTokens(raw)) {
        val tab = token.indexOf('\t')
        if (tab <= 0 || tab == token.lastIndex) {
            throw GradleException("structural-audit-git-fallback-capture-failed")
        }
        val fields = token.substring(0, tab).split(' ')
        val path = normalizeGitCapturePath(token.substring(tab + 1))
        if (fields.size != 3 || !fields[1].matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))) {
            throw GradleException("structural-audit-git-fallback-capture-failed")
        }
        if (fields[2] == "0" && byPath.put(path, fields[1].lowercase(Locale.ROOT)) != null) {
            throw GradleException("structural-audit-git-fallback-capture-failed")
        }
    }
    return byPath
}

fun parseSkipWorktreePaths(raw: ByteArray): Set<String> {
    val paths = linkedSetOf<String>()
    for (token in decodeGitNulTokens(raw)) {
        if (token.length < 3 || token[1] != ' ') {
            throw GradleException("structural-audit-git-fallback-capture-failed")
        }
        val path = normalizeGitCapturePath(token.substring(2))
        if (token[0] == 'S' && !paths.add(path)) {
            throw GradleException("structural-audit-git-fallback-capture-failed")
        }
    }
    return paths
}

fun sha256Hex(payload: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

val captureStructuralAuditGitState = tasks.register("captureStructuralAuditGitState") {
    description = "Captures raw Git identity, path, and porcelain-v2 bytes before audit evidence generation."
    group = "verification"
    outputs.files(
        structuralAuditGitHead,
        structuralAuditGitBranch,
        structuralAuditGitPaths,
        structuralAuditGitStatus,
        structuralAuditGitStage,
        structuralAuditGitTags,
        structuralAuditGitSkipWorktree,
    )
    outputs.upToDateWhen { false }
    doLast {
        structuralAuditGitDir.get().asFile.mkdirs()
        val headBytes = captureGitBytes("rev-parse", "HEAD")
        val branchBytes = captureGitBytes("branch", "--show-current")
        val pathBytes =
            captureGitBytes("ls-files", "--cached", "--others", "--exclude-standard", "-z")
        val statusBytes =
            captureGitBytes("status", "--porcelain=v2", "-z", "--untracked-files=all")
        val stageBytes = captureGitBytes("ls-files", "--stage", "-z")
        val tagBytes = captureGitBytes("ls-files", "-v", "-z")
        structuralAuditGitHead.get().asFile.writeBytes(headBytes)
        structuralAuditGitBranch.get().asFile.writeBytes(branchBytes)
        structuralAuditGitPaths.get().asFile.writeBytes(pathBytes)
        structuralAuditGitStatus.get().asFile.writeBytes(statusBytes)
        structuralAuditGitStage.get().asFile.writeBytes(stageBytes)
        structuralAuditGitTags.get().asFile.writeBytes(tagBytes)

        val stageByPath = parseStageObjectIds(stageBytes)
        val fallbackRows = parseSkipWorktreePaths(tagBytes)
            .sortedWith(compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it })
            .mapNotNull { path ->
                val candidate = layout.projectDirectory.file(path).asFile
                if (candidate.isFile) {
                    null
                } else {
                    if (candidate.exists()) {
                        throw GradleException("structural-audit-git-fallback-capture-failed")
                    }
                    val objectId = stageByPath[path]
                        ?: throw GradleException("structural-audit-git-fallback-capture-failed")
                    val blob = captureGitBytes("cat-file", "blob", objectId)
                    linkedMapOf<String, Any>(
                        "path" to path,
                        "sizeBytes" to blob.size,
                        "contentSha256" to sha256Hex(blob),
                    )
                }
            }
        val fallbackPayload = linkedMapOf<String, Any>(
            "schemaVersion" to "awx.structural-audit-git-skip-worktree.v1",
            "rows" to fallbackRows,
        )
        structuralAuditGitSkipWorktree.get().asFile.writeBytes(
            (JsonOutput.toJson(fallbackPayload) + "\n").toByteArray(Charsets.UTF_8)
        )
    }
}

tasks.register<Exec>("harmonyPressureReport") {
    description = "Generates Dynamic RAG harmony pressure metrics."
    group = "verification"
    dependsOn(captureStructuralAuditGitState)
    inputs.dir(layout.projectDirectory.dir("main/java")).optional()
    inputs.dir(layout.projectDirectory.dir("app/src/main/java_clean")).optional()
    inputs.file(layout.projectDirectory.file("scripts/harmony_pressure_report.py"))
    inputs.file(layout.projectDirectory.file("scripts/harmony_catch_contract.py"))
    outputs.file(layout.projectDirectory.file("verification/dynamic-rag-harmony-pressure-metrics.json"))
    outputs.upToDateWhen { false }
    commandLine(
        "python",
        "scripts/harmony_pressure_report.py",
        "--root",
        ".",
        "--output",
        "verification/dynamic-rag-harmony-pressure-metrics.json",
    )
}

tasks.register<Exec>("testTreeContaminationReport") {
    description = "Generates active source/test tree contamination metrics."
    group = "verification"
    dependsOn(captureStructuralAuditGitState)
    inputs.dir(layout.projectDirectory.dir("main/java")).optional()
    inputs.dir(layout.projectDirectory.dir("app/src/main/java_clean")).optional()
    inputs.dir(layout.projectDirectory.dir("src/test/java")).optional()
    inputs.file(layout.projectDirectory.file("scripts/test_tree_contamination_report.py"))
    outputs.file(layout.projectDirectory.file("verification/test-tree-contamination-metrics.json"))
    outputs.upToDateWhen { false }
    commandLine(
        "python",
        "scripts/test_tree_contamination_report.py",
        "--root",
        ".",
        "--output",
        "verification/test-tree-contamination-metrics.json",
    )
}

val structuralAuditMetrics =
    layout.projectDirectory.file("verification/dynamic-rag-quant-audit-metrics.json")
val structuralAuditBaseline =
    layout.projectDirectory.file("verification/structural-design-baseline.json")
val structuralAuditLedger =
    layout.projectDirectory.file("verification/structural-design-debt-ledger.jsonl")
val structuralRepairWaveRegistry =
    layout.projectDirectory.file("verification/structural-repair-waves/registry.json")
val structuralRepairWaveOneJournal =
    layout.projectDirectory.file("verification/structural-repair-closure-journal.jsonl")
val structuralRepairWaveOneProofRoot =
    layout.projectDirectory.dir(
        ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave"
    )
val structuralRepairWaveOneFixedInputs = files(
    layout.projectDirectory.file(
        ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/intake/intake-summary.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/intake/eligible-groups.jsonl"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/intake/target-preimages.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/repair-progress.jsonl"
    ),
)
val structuralRepairWaveOneProofInputs = fileTree(structuralRepairWaveOneProofRoot) {
    include("proofs/*/red-summary.json")
    include("proofs/*/green-summary.json")
    include("proofs/*/event-baseline.json")
    include("proofs/*/gate-evidence.json")
    include("proofs/*/red-hold-summary.json")
    include("proofs/*/restored-control-summary.json")
}
val structuralRepairWaveTwoJournal =
    layout.projectDirectory.file("verification/structural-repair-waves/wave-0002/journal.jsonl")
val structuralRepairWaveTwoProofRoot =
    layout.projectDirectory.dir(".superpowers/sdd/structural-repair-waves/wave-0002")
val structuralRepairWaveTwoFixedInputs = files(
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0002/intake/intake-summary.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0002/intake/eligible-groups.jsonl"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0002/intake/target-preimages.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0002/repair-progress.jsonl"
    ),
)
val structuralRepairWaveTwoProofInputs = fileTree(structuralRepairWaveTwoProofRoot) {
    include("proofs/*/red-summary.json")
    include("proofs/*/green-summary.json")
    include("proofs/*/event-baseline.json")
    include("proofs/*/gate-evidence.json")
    include("proofs/*/red-hold-summary.json")
    include("proofs/*/restored-control-summary.json")
}
val structuralRepairWaveThreeJournal =
    layout.projectDirectory.file("verification/structural-repair-waves/wave-0003/journal.jsonl")
val structuralRepairWaveThreeProofRoot =
    layout.projectDirectory.dir(".superpowers/sdd/structural-repair-waves/wave-0003")
val structuralRepairWaveThreeFixedInputs = files(
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0003/intake/intake-summary.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0003/intake/eligible-groups.jsonl"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0003/intake/target-preimages.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0003/intake/admission-decision.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0003/intake/duplicate-evidence.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0003/repair-progress.jsonl"
    ),
)
val structuralRepairWaveThreeProofInputs = fileTree(structuralRepairWaveThreeProofRoot) {
    include("proofs/*/red-summary.json")
    include("proofs/*/green-summary.json")
    include("proofs/*/event-baseline.json")
    include("proofs/*/gate-evidence.json")
    include("proofs/*/red-hold-summary.json")
    include("proofs/*/restored-control-summary.json")
    include("proofs/*/repair-target-admission.json")
    include("proofs/*/detector-red-summary.json")
    include("proofs/*/compile-baseline.json")
    include("proofs/*/owner-contract.json")
}
val structuralRepairWaveFourJournal =
    layout.projectDirectory.file("verification/structural-repair-waves/wave-0004/journal.jsonl")
val structuralRepairWaveFourFixedInputs = files(
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0004/intake/intake-summary.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0004/intake/eligible-groups.jsonl"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0004/intake/target-preimages.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0004/intake/admission-decision.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0004/intake/duplicate-evidence.json"
    ),
    layout.projectDirectory.file(
        ".superpowers/sdd/structural-repair-waves/wave-0004/repair-progress.jsonl"
    ),
)
val appDupFqcnEvidence =
    project(":app").layout.buildDirectory.file("reports/dup-fqcn-evidence.json")

project(":app").tasks.matching { it.name == "generateDupFqcnExcludes" }.configureEach {
    dependsOn(captureStructuralAuditGitState)
    outputs.upToDateWhen { false }
}

tasks.register<Exec>("dynamicRagQuantAudit") {
    description = "Generates the linked structural baseline, debt ledger, and quantitative metrics."
    group = "verification"
    dependsOn(
        captureStructuralAuditGitState,
        "harmonyPressureReport",
        "testTreeContaminationReport",
        ":app:generateDupFqcnExcludes",
    )
    inputs.dir(layout.projectDirectory.dir("main/java"))
    inputs.dir(layout.projectDirectory.dir("app/src/main/java_clean"))
    inputs.file(layout.projectDirectory.file("scripts/dynamic_rag_quant_audit.py"))
    inputs.files(
        structuralAuditGitHead,
        structuralAuditGitBranch,
        structuralAuditGitPaths,
        structuralAuditGitStatus,
        structuralAuditGitSkipWorktree,
    )
    inputs.file(layout.projectDirectory.file("verification/dynamic-rag-harmony-pressure-metrics.json"))
    inputs.file(layout.projectDirectory.file("verification/test-tree-contamination-metrics.json"))
    inputs.file(appDupFqcnEvidence)
    inputs.file(structuralRepairWaveRegistry)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.file(structuralRepairWaveOneJournal)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveOneFixedInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveOneProofInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.file(structuralRepairWaveTwoJournal)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveTwoFixedInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveTwoProofInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.file(structuralRepairWaveThreeJournal)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveThreeFixedInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveThreeProofInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.file(structuralRepairWaveFourJournal)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveFourFixedInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    outputs.file(structuralAuditMetrics)
    outputs.file(structuralAuditBaseline)
    outputs.file(structuralAuditLedger)
    outputs.upToDateWhen { false }
    commandLine(
        "python",
        "scripts/dynamic_rag_quant_audit.py",
        "--root",
        ".",
        "--active-java-root",
        "main/java",
        "--active-java-root",
        "app/src/main/java_clean",
        "--git-head-input",
        structuralAuditGitHead.get().asFile.absolutePath,
        "--git-branch-input",
        structuralAuditGitBranch.get().asFile.absolutePath,
        "--git-paths-input",
        structuralAuditGitPaths.get().asFile.absolutePath,
        "--git-status-input",
        structuralAuditGitStatus.get().asFile.absolutePath,
        "--git-skip-worktree-input",
        structuralAuditGitSkipWorktree.get().asFile.absolutePath,
        "--harmony-input",
        "verification/dynamic-rag-harmony-pressure-metrics.json",
        "--test-tree-input",
        "verification/test-tree-contamination-metrics.json",
        "--dup-fqcn-input",
        appDupFqcnEvidence.get().asFile.absolutePath,
        "--closure-wave-registry",
        "verification/structural-repair-waves/registry.json",
        "--metrics-output",
        "verification/dynamic-rag-quant-audit-metrics.json",
        "--baseline-output",
        "verification/structural-design-baseline.json",
        "--ledger-output",
        "verification/structural-design-debt-ledger.jsonl",
        "--candidate-cap",
        "1100",
    )
}

val harmonyScoreOutput = layout.projectDirectory.file("verification/harmony-build-report.txt")
tasks.register<JavaExec>("harmonyScoreReport") {
    description = "Runs the Java harmony scanner for the active Desktop source root."
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.lms.tools.HarmonyBuildScanner")
    outputs.file(harmonyScoreOutput)
    args("--root", ".", "--output", harmonyScoreOutput.asFile.absolutePath)
}

val sourceScoreOutput = layout.projectDirectory.file("verification/source-score-report.txt")
tasks.register<JavaExec>("sourceScoreReport") {
    description = "Runs the Java source scoring report for the active Desktop source root."
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.lms.tools.ScoringRunner")
    outputs.file(sourceScoreOutput)
    args("--root", ".", "--output", sourceScoreOutput.asFile.absolutePath)
}

tasks.register<Exec>("sourceHealthScorecard") {
    description = "Generates the strict evidence-adjusted source health scorecard."
    group = "verification"
    dependsOn("dynamicRagQuantAudit")
    inputs.file(layout.projectDirectory.file("verification/dynamic-rag-quant-audit-metrics.json"))
    inputs.file(layout.projectDirectory.file("verification/structural-design-baseline.json"))
    inputs.file(layout.projectDirectory.file("verification/structural-design-debt-ledger.jsonl"))
    inputs.file(layout.projectDirectory.file("scripts/dynamic_rag_quant_audit.py"))
    inputs.file(structuralRepairWaveRegistry)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.file(structuralRepairWaveOneJournal)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveOneFixedInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveOneProofInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.file(structuralRepairWaveTwoJournal)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveTwoFixedInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveTwoProofInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.file(structuralRepairWaveThreeJournal)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveThreeFixedInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveThreeProofInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.file(structuralRepairWaveFourJournal)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.files(structuralRepairWaveFourFixedInputs)
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("verification/dynamic-rag-harmony-pressure-metrics.json"))
    inputs.file(layout.projectDirectory.file("verification/test-tree-contamination-metrics.json"))
    if (layout.projectDirectory.file("verification/websoak-kpi-smoke/websoak-kpi-provider-disabled.json").asFile.isFile) {
        inputs.file(layout.projectDirectory.file("verification/websoak-kpi-smoke/websoak-kpi-provider-disabled.json")).optional()
    }
    if (layout.projectDirectory.file("data/db-gap-report/gap_matrix.json").asFile.isFile) {
        inputs.file(layout.projectDirectory.file("data/db-gap-report/gap_matrix.json")).optional()
    }
    if (layout.projectDirectory.dir("var/codex-smoke").asFile.isDirectory) {
        inputs.files(
            fileTree(layout.projectDirectory.dir("var/codex-smoke")) {
                include("**/*.json")
                include("**/*.ndjson")
                include("**/*.txt")
                include("**/*.log")
                exclude("chrome-cdp-*/**")
                exclude("**/Cache/**")
                exclude("**/Code Cache/**")
                exclude("**/GPUCache/**")
            }
        ).optional()
    }
    inputs.file(layout.projectDirectory.file("scripts/source_health_scorecard.py"))
    outputs.file(layout.projectDirectory.file("verification/source-health-scorecard.json"))
    outputs.file(layout.projectDirectory.file("verification/source-health-failure-pattern-events.ndjson"))
    outputs.file(layout.projectDirectory.file("verification/source-health-patchdrop-manifest-contract.json"))
    commandLine(
        "python",
        "scripts/source_health_scorecard.py",
        "--root",
        ".",
        "--output",
        "verification/source-health-scorecard.json",
    )
}

tasks.register<Exec>("agentCodeEvidenceGate") {
    description = "Reduces independently collected evidence to a review-only PASS/HOLD/REJECT verdict."
    group = "verification"
    inputs.file(layout.projectDirectory.file("scripts/agent_code_evidence_gate.py"))
    val gateInput = providers.gradleProperty("agentCodeGateInput")
    val gateContract = providers.gradleProperty("agentCodeGateContract")
    val gateContractHash = providers.gradleProperty("agentCodeGateContractSha256")
    if (gateInput.isPresent) inputs.file(gateInput.get()).optional()
    if (gateContract.isPresent) inputs.file(gateContract.get()).optional()
    outputs.upToDateWhen { false }
    commandLine("python", "scripts/agent_code_evidence_gate.py")
    if (gateInput.isPresent) args("--input", gateInput.get())
    if (gateContract.isPresent) args("--contract", gateContract.get())
    if (gateContractHash.isPresent) args("--contract-sha256", gateContractHash.get())
}

tasks.register<Exec>("sourceHealthValidationLoop") {
    description = "Emits the source-health autonomous validation loop ledger."
    group = "verification"
    dependsOn("sourceHealthScorecard")
    inputs.file(layout.projectDirectory.file("scripts/source_health_validation_loop.py"))
    inputs.file(layout.projectDirectory.file("verification/source-health-scorecard.json")).optional()
    inputs.file(layout.projectDirectory.file("verification/source-health-failure-pattern-events.ndjson")).optional()
    inputs.file(layout.projectDirectory.file("verification/source-health-patchdrop-manifest-contract.json")).optional()
    outputs.file(layout.projectDirectory.file("verification/source-health-validation-loop.json"))
    outputs.file(layout.projectDirectory.file("verification/source-health-validation-cycles.ndjson"))
    commandLine(
        "python",
        "scripts/source_health_validation_loop.py",
        "--root",
        ".",
        "--output",
        "verification/source-health-validation-loop.json",
        "--cycles-output",
        "verification/source-health-validation-cycles.ndjson",
        "--max-duration-hours",
        "9",
        "--max-cycles",
        "9",
        "--skip-gradle",
    )
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "dev.langchain4j" && requested.version != "1.0.1") {
            useVersion("1.0.1")
        }
    }
}

sourceSets {
    main {
        java {
            srcDirs("main/java")
        }
        resources {
            srcDirs("main/resources")
        }
    }

    test {
        java {
            srcDirs("src/test/java")
        }
        resources {
            srcDirs("src/test/resources")
        }
    }
}

val chatUiTest by sourceSets.creating {
    java.srcDir("src/chatUiTest/java")
    resources.srcDir("src/chatUiTest/resources")
    compileClasspath += sourceSets["main"].output + configurations["testCompileClasspath"]
    runtimeClasspath += output + compileClasspath + configurations["testRuntimeClasspath"]
}

val gatewaySecurityTestSources = listOf(
    "com/example/lms/llm/LocalLlmGatewayHeadersTest.java",
    "com/example/lms/uaw/autolearn/OpenCodeFreeQuotaGuardTest.java",
    "ai/abandonware/nova/orch/aop/LlmRouterGatewaySecurityTest.java",
    "com/example/lms/config/AppSecurityConfigContractTest.java",
)
val crossSubsystemContractTestSources = listOf(
    "ai/abandonware/nova/orch/aop/AspectOrderingContractTest.java",
    "com/example/lms/orchestration/StrategyConflictResolverTest.java",
    "com/example/lms/orchestration/ExecutionPlanApplierTest.java",
    "com/example/lms/service/rag/burst/ExtremeZTriggerTest.java",
)
val gatewaySecurityTestClasses = gatewaySecurityTestSources.map { it.removeSuffix(".java").replace('/', '.') }
val crossSubsystemContractTestClasses = crossSubsystemContractTestSources.map { it.removeSuffix(".java").replace('/', '.') }
val isolatedCustomTestClasses = gatewaySecurityTestClasses + crossSubsystemContractTestClasses

val gatewaySecurityTest by sourceSets.creating {
    java {
        srcDir("src/test/java")
        include(*gatewaySecurityTestSources.toTypedArray())
    }
    resources.srcDir("src/test/resources")
    compileClasspath += sourceSets["main"].output + configurations["testCompileClasspath"]
    runtimeClasspath += output + compileClasspath + configurations["testRuntimeClasspath"]
}

val crossSubsystemContractTest by sourceSets.creating {
    java {
        srcDir("src/test/java")
        include(*crossSubsystemContractTestSources.toTypedArray())
    }
    resources.srcDir("src/test/resources")
    compileClasspath += sourceSets["main"].output + configurations["testCompileClasspath"]
    runtimeClasspath += output + compileClasspath + configurations["testRuntimeClasspath"]
}

configurations.named(chatUiTest.implementationConfigurationName) {
    extendsFrom(configurations["testImplementation"])
}
configurations.named(chatUiTest.runtimeOnlyConfigurationName) {
    extendsFrom(configurations["testRuntimeOnly"])
}
configurations.named(gatewaySecurityTest.implementationConfigurationName) {
    extendsFrom(configurations["testImplementation"])
}
configurations.named(gatewaySecurityTest.runtimeOnlyConfigurationName) {
    extendsFrom(configurations["testRuntimeOnly"])
}
configurations.named(crossSubsystemContractTest.implementationConfigurationName) {
    extendsFrom(configurations["testImplementation"])
}
configurations.named(crossSubsystemContractTest.runtimeOnlyConfigurationName) {
    extendsFrom(configurations["testRuntimeOnly"])
}

tasks.register<Test>("chatUiTest") {
    description = "Runs isolated Chat UI tests without compiling stale broad src/test/java drift."
    group = "verification"
    testClassesDirs = chatUiTest.output.classesDirs
    classpath = chatUiTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}

tasks.register<Test>("gatewaySecurityTest") {
    description = "Runs isolated gateway and HTTPS boundary tests without compiling stale broad src/test/java drift."
    group = "verification"
    testClassesDirs = gatewaySecurityTest.output.classesDirs
    classpath = gatewaySecurityTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}

tasks.register<Test>("crossSubsystemContractTest") {
    description = "Runs isolated cross-subsystem orchestration contract tests without compiling stale broad src/test/java drift."
    group = "verification"
    testClassesDirs = crossSubsystemContractTest.output.classesDirs
    classpath = crossSubsystemContractTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}

tasks.named<Test>("test") {
    filter {
        isolatedCustomTestClasses.forEach { className ->
            excludeTestsMatching(className)
        }
    }
}

tasks.withType<Test>().configureEach {
    reports.junitXml.required.set(true)
    // Bound cumulative static/thread-local retention in the multi-thousand-test root suite.
    forkEvery = 256L
}

tasks.named("check") {
    dependsOn(
        tasks.named<Test>("chatUiTest"),
        tasks.named<Test>("gatewaySecurityTest"),
        tasks.named<Test>("crossSubsystemContractTest"),
    )
}

tasks.processResources {
    exclude(
        "application-secrets.yml",
        "keystore.p12",
        "*.p12",
        "*.jks",
        "**/*.p12",
        "**/*.jks",
        "app/resources/application-local.yml",
        "application.disabled.yml",
        "application-example.yml",
        "application-features-example.yml",
        "application-merge16.yml",
        "application-patch.yml",
        "application-recency30.yml",
    )
}

// Spring Boot main class
springBoot {
    mainClass.set("com.example.lms.LmsApplication")
}

// Isolated Java 17 STDIO MCP launcher. The web runtime remains on the existing
// main source set; this source set only supplies the Codex-facing transport.
val glmAgentMcp by sourceSets.creating {
    java.srcDir("src/glmAgentMcp/java")
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += output + compileClasspath
}

val glmAgentMcpTest by sourceSets.creating {
    java.srcDir("src/glmAgentMcpTest/java")
    resources.srcDir("src/glmAgentMcpTest/resources")
    compileClasspath += glmAgentMcp.output + sourceSets["main"].output + configurations["testCompileClasspath"]
    runtimeClasspath += output + compileClasspath + glmAgentMcp.runtimeClasspath + configurations["testRuntimeClasspath"]
}

configurations.named(glmAgentMcp.implementationConfigurationName) {
    extendsFrom(configurations["implementation"])
}
configurations.named(glmAgentMcp.runtimeOnlyConfigurationName) {
    extendsFrom(configurations["runtimeOnly"])
}
configurations.named(glmAgentMcpTest.implementationConfigurationName) {
    extendsFrom(configurations["testImplementation"])
}
configurations.named(glmAgentMcpTest.runtimeOnlyConfigurationName) {
    extendsFrom(configurations["testRuntimeOnly"])
}

dependencies {
    add(glmAgentMcp.implementationConfigurationName, "io.modelcontextprotocol.sdk:mcp-core:2.0.0")
    add(glmAgentMcp.implementationConfigurationName, "io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.0")
}

val glmAgentMcpJar by tasks.registering(Jar::class) {
    description = "Builds the read-only Codex GLM agent STDIO MCP executable."
    group = "build"
    archiveFileName.set("glm-agent-mcp.jar")
    destinationDirectory.set(layout.buildDirectory.dir("glm-agent-mcp"))
    dependsOn(glmAgentMcp.classesTaskName, sourceSets["main"].classesTaskName, ":app:jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "ai.abandonware.subagent.mcp.GlmAgentMcpServer"
    }
    from(glmAgentMcp.output)
    from(sourceSets["main"].output)
    from({
        configurations[glmAgentMcp.runtimeClasspathConfigurationName]
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.register<Test>("glmAgentMcpTest") {
    description = "Runs the real-process STDIO MCP protocol acceptance tests."
    group = "verification"
    dependsOn(glmAgentMcpJar)
    testClassesDirs = glmAgentMcpTest.output.classesDirs
    classpath = glmAgentMcpTest.runtimeClasspath
    useJUnitPlatform()
    systemProperty("glm.agent.mcp.jar", glmAgentMcpJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") {
    dependsOn(tasks.named<Test>("glmAgentMcpTest"))
}
