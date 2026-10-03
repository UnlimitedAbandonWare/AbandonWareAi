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

// :app is intentionally an EMPTY module. The canonical runtime sources live in
// the root project (main/java + main/resources). The legacy java_clean /
// resources trees were quarantined to app/quarantine/ on 2026-10-01 to remove
// duplicate-FQCN classpath shadowing; do not re-add srcDirs here.
sourceSets {
  val main by getting {
    java.setSrcDirs(emptyList<String>())
    resources.setSrcDirs(emptyList<String>())
  }
  val test by getting {
    java.setSrcDirs(emptyList<String>())
    resources.setSrcDirs(emptyList<String>())
  }
}
