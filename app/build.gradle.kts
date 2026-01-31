plugins {
  `java-library`
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
  // Lombok (compile-time only). Safe even if unused.
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
}

// Prevent plan resource duplicates on the runtime classpath
// (BOOT-INF/classes vs BOOT-INF/lib) by ensuring only the root module
// contributes plans/**.
tasks.processResources {
  exclude("plans/**")
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

val generateDupFqcnExcludes by tasks.registering {
  val mainRoot = rootProject.layout.projectDirectory.dir("main/java")
  val cleanRoot = project.layout.projectDirectory.dir("src/main/java_clean")

  inputs.dir(mainRoot)
  inputs.dir(cleanRoot)
  outputs.file(dupFqcnExcludesFile)

  doLast {
    val out = dupFqcnExcludesFile.get().asFile
    out.parentFile.mkdirs()

    val modeLabel = dupFqcnExcludeModeRaw?.trim().orEmpty().ifBlank { "(default)" }

    if (!mainRoot.asFile.exists() || !cleanRoot.asFile.exists()) {
      logger.lifecycle("[dup-fqcn] skip (roots missing) mainRoot=${mainRoot.asFile} cleanRoot=${cleanRoot.asFile}")
      out.writeText(
          "# AUTO-GENERATED by :app:generateDupFqcnExcludes ; mode=$modeLabel ; filter=$dupFqcnExcludeFilter ; onDup=$dupFqcnExcludeOnDup\n",
          Charsets.UTF_8
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
            .filter { it.isFile && it.extension == "java" }
            .associateBy { fqcn(it) }

    val cleanByFqcn: Map<String, java.io.File> =
        cleanRoot.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
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
}
