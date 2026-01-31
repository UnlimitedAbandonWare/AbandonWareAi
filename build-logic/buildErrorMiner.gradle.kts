
import org.gradle.api.tasks.Exec

tasks.register<Exec>("mineBuildErrors") {
    group = "diagnostics"
    description = "Scan build logs for error patterns and write reports under analysis/"
    commandLine("python", "tools/build_error_miner.py", "scan", "--in", "build", "--out", "analysis/gradle_build_error_report")
    isIgnoreExitValue = true
}

// [GPTPRO] sourceSets excludes to remove duplicates
sourceSets {
  val main by getting {
    java.exclude(
      "**/_abandonware_backup/**",
      "**/java_clean/**",
      "extras/**",
      "backup/**"
    )
  }
}


// [GPTPRO] deps added
dependencies {
    implementation(project(":lms-core"))
    implementation("ai.onnxruntime:onnxruntime:1.19.0")
    implementation("com.upstash:upstash-redis:1.3.2")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
}


// [GPTPRO] apply build error guard plugin
apply(from = "gradle/buildErrorGuard.gradle.kts")