import java.security.MessageDigest
import java.time.Instant

plugins {
  `java-library`
}

fun dupFqcnJsonString(value: String): String = buildString {
  append('"')
  value.forEach { ch ->
    when (ch) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\b' -> append("\\b")
      '\u000C' -> append("\\f")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> {
        if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
          append("\\u").append(ch.code.toString(16).padStart(4, '0'))
        } else {
          append(ch)
        }
      }
    }
  }
  append('"')
}

fun dupFqcnSha256(value: String): String {
  val hex = "0123456789abcdef"
  val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
  return buildString(64) {
    digest.forEach { byte ->
      val unsigned = byte.toInt() and 0xff
      append(hex[unsigned ushr 4])
      append(hex[unsigned and 0x0f])
    }
  }
}

group = "com.example.lms"
version = "0.0.1-SNAPSHOT"

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

// Repositories are managed centrally via settings.gradle(.kts)
// (dependencyResolutionManagement + RepositoriesMode.FAIL_ON_PROJECT_REPOS).

dependencies {
  implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))

  implementation("org.springframework.boot:spring-boot-starter")
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-validation")

  implementation("org.apache.lucene:lucene-core:9.10.0")
  implementation("org.apache.lucene:lucene-queryparser:9.10.0")
  implementation("org.apache.lucene:lucene-analysis-common:9.10.0")
  implementation("org.apache.lucene:lucene-analysis-nori:9.10.0")

  // Lombok (compile-time only). Safe even if unused.
  compileOnly("com.github.spotbugs:spotbugs-annotations:4.8.3")
  compileOnly("org.projectlombok:lombok:1.18.32")
  annotationProcessor("org.projectlombok:lombok:1.18.32")

  testImplementation(platform("org.junit:junit-bom:5.10.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
  useJUnitPlatform()
}

sourceSets {
  val main by getting {
    java.setSrcDirs(listOf("src/main/java_clean"))
    resources.setSrcDirs(listOf("src/main/resources"))
  }
  val test by getting {
    java.setSrcDirs(emptyList<String>())
    resources.setSrcDirs(emptyList<String>())
  }
}

// Prevent plan resource duplicates on the runtime classpath
// (BOOT-INF/classes vs BOOT-INF/lib) by ensuring only the root module
// contributes plans/**.
tasks.processResources {
  exclude("plans/**", "application*.yml", "application*.yaml", "application*.properties")
}

/**
 * AUTO: Generate jar-exclude patterns for duplicate FQCNs between:
 *   - rootProject/main/java   (canonical)
 *   - this project's java_clean (legacy stubs / adapters)
 *
 * Why: when packages move, a hard-coded exclude list becomes stale and Spring DI / ABI can "silently" break
 *      because a non-stereotype stub may win classpath resolution.
 *
 * Usage:
 *   - default (stereotype): exclude duplicates only when the root/main version is a Spring stereotype
 *       ./gradlew :app:jar
 *   - exclude ALL duplicate FQCNs (prevents DI + ABI mismatches):
 *       ./gradlew :app:jar -PdupFqcnExcludeMode=all
 *   - warn (build continues) if any duplicate FQCNs remain NOT excluded (keep):
 *       ./gradlew :app:jar -PdupFqcnExcludeMode=warn
 *   - fail-fast if any duplicate FQCNs remain NOT excluded (keep):
 *       ./gradlew :app:jar -PdupFqcnExcludeMode=fail
 *
 * Advanced:
 *   - combine tokens (filter + action): -PdupFqcnExcludeMode=stereotype:fail  (tokens: stereotype|all + warn|fail)
 */
data class DupFqcnExcludeConfig(val filter: String, val onDup: String)

data class DupFqcnEvidenceRow(
    val fqcn: String,
    val rootPath: String,
    val appPath: String,
    val packagingState: String,
    val evidenceFingerprint: String,
)

fun parseDupFqcnExcludeMode(raw: String?): DupFqcnExcludeConfig {
  val t = raw?.trim()?.lowercase().orEmpty()
  if (t.isBlank()) return DupFqcnExcludeConfig("stereotype", "none")

  // Backward compatible single-value modes
  if (t == "stereotype" || t == "all") return DupFqcnExcludeConfig(t, "none")
  if (t == "warn" || t == "warning") return DupFqcnExcludeConfig("all", "warn")
  if (t == "fail" || t == "fail-fast" || t == "failfast") return DupFqcnExcludeConfig("all", "fail")

  val tokens: List<String> =
      t.split(':', '+', ',', ';', '|', ' ')
          .map { it.trim() }
          .filter { it.isNotBlank() }

  var filter: String? = null
  var onDup: String? = null
  for (tok in tokens) {
    when (tok) {
      "stereotype", "all" -> filter = tok
      "warn", "warning" -> onDup = "warn"
      "fail", "fail-fast", "failfast" -> onDup = "fail"
    }
  }
  return DupFqcnExcludeConfig(filter ?: "stereotype", onDup ?: "none")
}

val dupFqcnExcludeModeRaw = (findProperty("dupFqcnExcludeMode") as? String)
val dupFqcnExcludeConfig = parseDupFqcnExcludeMode(dupFqcnExcludeModeRaw)

val dupFqcnExcludeFilter: String = dupFqcnExcludeConfig.filter
val dupFqcnExcludeOnDup: String = dupFqcnExcludeConfig.onDup

// MERGE_HOOK:PROJ_AGENT::APP_JAR_DUPLICATE_EXCLUDES_LIST_V1
// Hard-coded duplicate-FQCN excludes that MUST NOT ship inside :app jar.
//
// NOTE: This list is intentionally kept as a "safety net" even with auto-generation,
// and is also used by the warn/fail-fast report to decide what is truly kept.
val appJarDuplicateFqcnExcludes: List<String> = listOf(
    "com/example/lms/guard/AnswerSanitizer*",
    "com/example/lms/service/onnx/OnnxCrossEncoderReranker*",
    "com/example/lms/service/rag/AnalyzeWebSearchRetriever*",
    "com/example/lms/service/rag/auth/DomainWhitelist*",
    "com/example/lms/service/rag/fusion/RerankCanonicalizer*",
    "com/example/lms/service/rag/fusion/WeightedRRF*",
    "com/example/lms/service/rag/fusion/WeightedPowerMeanFuser*",
    "com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain*",
    "com/example/lms/service/rag/handler/KnowledgeGraphHandler*",
    "com/example/lms/service/rag/overdrive/AngerOverdriveNarrower*",
    "com/example/lms/service/rag/overdrive/OverdriveGuard*",
    "com/example/lms/service/rag/rerank/DppDiversityReranker*",
    "service/rag/DppDiversityReranker*",
    "com/example/lms/strategy/RetrievalOrderService*",
    "com/example/lms/trace/TraceContext*",
    // default-package duplicates
    "service/rag/planner/SelfAskPlanner*",
    "trace/TimeBudget*",
)

val dupFqcnExcludesFile = layout.buildDirectory.file("generated/dup-fqcn-excludes.txt")
val dupFqcnEvidenceFile = layout.buildDirectory.file("reports/dup-fqcn-evidence.json")

val generateDupFqcnExcludes by tasks.registering {
  val mainRoot = rootProject.layout.projectDirectory.dir("main/java")
  val cleanRoot = project.layout.projectDirectory.dir("src/main/java_clean")

  inputs.dir(mainRoot)
  inputs.dir(cleanRoot)
  outputs.file(dupFqcnExcludesFile)
  outputs.file(dupFqcnEvidenceFile)

  doLast {
    val out = dupFqcnExcludesFile.get().asFile
    out.parentFile.mkdirs()
    val evidenceOut = dupFqcnEvidenceFile.get().asFile
    evidenceOut.parentFile.mkdirs()

    val modeLabel = dupFqcnExcludeModeRaw?.trim().orEmpty().ifBlank { "(default)" }

    fun writeEvidence(
        status: String,
        sourceCollisionCount: Int,
        generatedExcludeCount: Int,
        hardExcludeCount: Int,
        packagedActiveCount: Int,
        collisions: List<DupFqcnEvidenceRow>,
    ) {
      val schemaVersion = "awx.dup-fqcn-evidence.v1"
      val semanticMaterial = buildString {
        append("schemaVersion=").append(schemaVersion).append('\n')
        append("status=").append(status).append('\n')
        append("mode=").append(modeLabel).append('\n')
        append("filter=").append(dupFqcnExcludeFilter).append('\n')
        append("action=").append(dupFqcnExcludeOnDup).append('\n')
        append("duplicateFqcnSourceCollisionCount=").append(sourceCollisionCount).append('\n')
        append("duplicateFqcnGeneratedExcludeCount=").append(generatedExcludeCount).append('\n')
        append("duplicateFqcnHardExcludeCount=").append(hardExcludeCount).append('\n')
        append("duplicateFqcnPackagedActiveCount=").append(packagedActiveCount).append('\n')
        append("duplicateFqcnActiveCount=").append(packagedActiveCount).append('\n')
        collisions.forEach { row ->
          append("collision=")
              .append(row.fqcn).append('\u0000')
              .append(row.rootPath).append('\u0000')
              .append(row.appPath).append('\u0000')
              .append(row.packagingState).append('\u0000')
              .append(row.evidenceFingerprint).append('\n')
        }
      }
      val semanticHash = dupFqcnSha256(semanticMaterial)
      val generatedAt = Instant.now().toString()

      evidenceOut.writeText(
          buildString {
            append("{\n")
            append("  \"schemaVersion\": ").append(dupFqcnJsonString(schemaVersion)).append(",\n")
            append("  \"status\": ").append(dupFqcnJsonString(status)).append(",\n")
            append("  \"generatedAt\": ").append(dupFqcnJsonString(generatedAt)).append(",\n")
            append("  \"mode\": ").append(dupFqcnJsonString(modeLabel)).append(",\n")
            append("  \"filter\": ").append(dupFqcnJsonString(dupFqcnExcludeFilter)).append(",\n")
            append("  \"action\": ").append(dupFqcnJsonString(dupFqcnExcludeOnDup)).append(",\n")
            append("  \"duplicateFqcnSourceCollisionCount\": ").append(sourceCollisionCount).append(",\n")
            append("  \"duplicateFqcnGeneratedExcludeCount\": ").append(generatedExcludeCount).append(",\n")
            append("  \"duplicateFqcnHardExcludeCount\": ").append(hardExcludeCount).append(",\n")
            append("  \"duplicateFqcnPackagedActiveCount\": ").append(packagedActiveCount).append(",\n")
            append("  \"duplicateFqcnActiveCount\": ").append(packagedActiveCount).append(",\n")
            append("  \"collisions\": [")
            if (collisions.isNotEmpty()) append('\n')
            collisions.forEachIndexed { index, row ->
              append("    {\n")
              append("      \"fqcn\": ").append(dupFqcnJsonString(row.fqcn)).append(",\n")
              append("      \"rootPath\": ").append(dupFqcnJsonString(row.rootPath)).append(",\n")
              append("      \"appPath\": ").append(dupFqcnJsonString(row.appPath)).append(",\n")
              append("      \"packagingState\": ").append(dupFqcnJsonString(row.packagingState)).append(",\n")
              append("      \"evidenceFingerprint\": ")
                  .append(dupFqcnJsonString(row.evidenceFingerprint)).append('\n')
              append("    }")
              if (index < collisions.lastIndex) append(',')
              append('\n')
            }
            append("  ],\n")
            append("  \"semanticHash\": ").append(dupFqcnJsonString(semanticHash)).append('\n')
            append("}\n")
          },
          Charsets.UTF_8
      )
    }

    if (!mainRoot.asFile.exists() || !cleanRoot.asFile.exists()) {
      logger.lifecycle("[dup-fqcn] skip (roots missing) mainRoot=${mainRoot.asFile} cleanRoot=${cleanRoot.asFile}")
      out.writeText(
          "# AUTO-GENERATED by :app:generateDupFqcnExcludes ; mode=$modeLabel ; filter=$dupFqcnExcludeFilter ; onDup=$dupFqcnExcludeOnDup\n",
          Charsets.UTF_8
      )
      writeEvidence(
          status = "roots-missing",
          sourceCollisionCount = 0,
          generatedExcludeCount = 0,
          hardExcludeCount = 0,
          packagedActiveCount = 0,
          collisions = emptyList(),
      )
      return@doLast
    }

    val pkgRe = Regex("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;")

    fun fqcn(file: java.io.File): String {
      val txt = file.readText(Charsets.UTF_8)
      val pkg = pkgRe.find(txt)?.groupValues?.get(1)?.trim().orEmpty()
      val cls = file.nameWithoutExtension
      return if (pkg.isBlank()) cls else "$pkg.$cls"
    }

    fun declaresFileType(file: java.io.File): Boolean {
      val typeName = Regex.escape(file.nameWithoutExtension)
      val typeRe = Regex("(?m)(?:\\b(?:class|interface|enum|record)|@interface)\\s+$typeName\\b")
      return typeRe.containsMatchIn(file.readText(Charsets.UTF_8))
    }

    val stereotypeRe = Regex(
        "@\\s*(?:org\\.springframework\\.(?:stereotype|context\\.annotation|web\\.bind\\.annotation)\\.)?" +
            "(Component|Service|Repository|Controller|RestController|Configuration)\\b"
    )
    val bootRe = Regex(
        "@\\s*(?:org\\.springframework\\.boot\\.autoconfigure\\.)?" +
            "(SpringBootApplication|AutoConfiguration)\\b"
    )

    fun isSpringStereotype(file: java.io.File): Boolean {
      val txt = file.readText(Charsets.UTF_8)
      return stereotypeRe.containsMatchIn(txt) || bootRe.containsMatchIn(txt)
    }

    val mainByFqcn: Map<String, java.io.File> =
        mainRoot.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "java" && declaresFileType(it) }
            .associateBy { fqcn(it) }

    val cleanByFqcn: Map<String, java.io.File> =
        cleanRoot.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "java" && declaresFileType(it) }
            .associateBy { fqcn(it) }

    val dupFqcns: List<String> =
        cleanByFqcn.keys
            .asSequence()
            .filter { mainByFqcn.containsKey(it) }
            .distinct()
            .sorted()
            .toList()

    val excludedFqcns: List<String> =
        dupFqcns.asSequence()
            .filter { f ->
              dupFqcnExcludeFilter == "all" || isSpringStereotype(mainByFqcn.getValue(f))
            }
            .toList()

    val excludedSet: Set<String> = excludedFqcns.toSet()

    fun isCoveredByHardcodedExcludes(fqcn: String): Boolean {
      // CopySpec.exclude(...) uses Ant-style path matching, but our hard-coded patterns are simple
      // "prefix*" forms. For fail-fast we only need a conservative approximation.
      val path = fqcn.replace('.', '/')
      return appJarDuplicateFqcnExcludes.any { p ->
        val prefix = p.trim().removeSuffix("*")
        prefix.isNotEmpty() && path.startsWith(prefix)
      }
    }

    val hardExcludedFqcns: Set<String> =
        dupFqcns.asSequence()
            .filterNot { excludedSet.contains(it) }
            .filter { isCoveredByHardcodedExcludes(it) }
            .toSet()

    val keptFqcns: List<String> =
        dupFqcns.asSequence()
            .filterNot { excludedSet.contains(it) || hardExcludedFqcns.contains(it) }
            .toList()

    val collisionRows: List<DupFqcnEvidenceRow> =
        dupFqcns.map { fqcn ->
          val rootPath =
              rootProject.projectDir.toPath()
                  .relativize(mainByFqcn.getValue(fqcn).toPath())
                  .toString()
                  .replace('\\', '/')
          val appPath =
              rootProject.projectDir.toPath()
                  .relativize(cleanByFqcn.getValue(fqcn).toPath())
                  .toString()
                  .replace('\\', '/')
          val packagingState = when {
            excludedSet.contains(fqcn) -> "GENERATED_EXCLUDE"
            hardExcludedFqcns.contains(fqcn) -> "HARD_EXCLUDE"
            else -> "PACKAGED_ACTIVE"
          }
          val fingerprintMaterial =
              listOf(fqcn, rootPath, appPath, packagingState).joinToString("\u0000")
          DupFqcnEvidenceRow(
              fqcn = fqcn,
              rootPath = rootPath,
              appPath = appPath,
              packagingState = packagingState,
              evidenceFingerprint = dupFqcnSha256(fingerprintMaterial),
          )
        }

    val patterns: List<String> =
        excludedFqcns.asSequence()
            .map { f -> f.replace('.', '/') + "*" }
            .distinct()
            .sorted()
            .toList()

    out.writeText(
        buildString {
          append("# AUTO-GENERATED by :app:generateDupFqcnExcludes ; mode=")
              .append(modeLabel)
              .append(" ; filter=").append(dupFqcnExcludeFilter)
              .append(" ; onDup=").append(dupFqcnExcludeOnDup)
              .append('\n')
          patterns.forEach { append(it).append('\n') }
        },
        Charsets.UTF_8
    )

    writeEvidence(
        status = "current",
        sourceCollisionCount = dupFqcns.size,
        generatedExcludeCount = excludedFqcns.size,
        hardExcludeCount = hardExcludedFqcns.size,
        packagedActiveCount = keptFqcns.size,
        collisions = collisionRows,
    )

    logger.lifecycle(
        "[dup-fqcn] found ${dupFqcns.size} duplicate(s); generated ${patterns.size} exclude(s) at: ${out} " +
            "(hardExcluded=${hardExcludedFqcns.size}, kept=${keptFqcns.size})"
    )

    // Warn/fail-fast should focus on what will STILL be packaged ("kept") after excludes,
    // not on the mere existence of duplicates in source trees.
    if (keptFqcns.isNotEmpty() && dupFqcnExcludeOnDup != "none") {
      val total = dupFqcns.size
      val autoExcluded = excludedFqcns.size
      val hardExcluded = hardExcludedFqcns.size
      val kept = keptFqcns.size

      val reportMax = 200
      val report = buildString {
        append("[dup-fqcn] duplicate FQCN(s) will remain in :app jar (NOT excluded) (kept=")
            .append(kept)
            .append(")\n")
        append("  main : ").append(mainRoot.asFile).append('\n')
        append("  clean: ").append(cleanRoot.asFile).append('\n')
        append("  filter=").append(dupFqcnExcludeFilter)
            .append(", onDup=").append(dupFqcnExcludeOnDup)
            .append(", total=").append(total)
            .append(", excluded(auto)=").append(autoExcluded)
            .append(", excluded(hard)=").append(hardExcluded)
            .append(", kept=").append(kept)
            .append('\n')

        append("  hint: run -PdupFqcnExcludeMode=all or add patterns to appJarDuplicateFqcnExcludes to prevent classpath shadowing.\n")

        keptFqcns.take(reportMax).forEach { f ->
          append("  [keep] ").append(f).append('\n')
          val mf = mainByFqcn[f]
          val cf = cleanByFqcn[f]
          if (mf != null) append("    main : ").append(mf).append('\n')
          if (cf != null) append("    clean: ").append(cf).append('\n')
        }

        if (keptFqcns.size > reportMax) {
          append("  ... (").append(keptFqcns.size - reportMax).append(" more)\n")
        }
      }

      when (dupFqcnExcludeOnDup) {
        "warn" -> logger.warn(report)
        "fail" -> throw org.gradle.api.GradleException(report)
      }
    }
  }
}

tasks.withType<Jar>().configureEach {
    exclude("plans/**")
    exclude("application*.yml", "application*.yaml", "application*.properties")

    // MERGE_HOOK:PROJ_AGENT::APP_JAR_DUPLICATE_EXCLUDES_AUTOGEN_V1
    // Also apply auto-generated excludes (keeps us safe when packages move).
    dependsOn(generateDupFqcnExcludes)
    doFirst {
        val extra = dupFqcnExcludesFile.get().asFile
            .takeIf { it.exists() }
            ?.readLines(Charsets.UTF_8)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("#") }
            .orEmpty()

        if (extra.isNotEmpty()) {
            extra.forEach { exclude(it) }
            logger.lifecycle("[dup-fqcn] applied ${extra.size} generated excludes to ${archiveFileName.get()}")
        }
    }

    // MERGE_HOOK:PROJ_AGENT::APP_JAR_DUPLICATE_EXCLUDES_V1
    // Remove duplicated FQCNs that also exist in :lms-core main module.
    // If left as-is, runtime classpath order can cause patched code to be silently shadowed.
    exclude(*appJarDuplicateFqcnExcludes.toTypedArray())
}


tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  source = source.matching {
    exclude(*appJarDuplicateFqcnExcludes.toTypedArray())
  }
}
