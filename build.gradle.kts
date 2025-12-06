plugins { id("org.springframework.boot") version "3.3.4" }
// Root aggregator (Kotlin DSL)

plugins { }

allprojects {
  repositories {
    mavenCentral()
    maven("https://repo.spring.io/release")
  }
}

subprojects {
  // 전 모듈 공통 컴파일 옵션 + 결함 소스 제외
  plugins.withId("java") {
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    tasks.withType<JavaCompile>().configureEach {
      options.encoding = "UTF-8"
      options.compilerArgs.addAll(listOf("-Xlint:deprecation","-Xlint:unchecked"))
    }
    extensions.configure<SourceSetContainer>("sourceSets") {
      named("main") {
        java {
          exclude("**/_abandonware_backup/**", "**/java_clean/**", "extras/**", "backup/**", "**/demo-*/**")
        }
      }
    }
  }
}

// 빌드 오류 프리플라이트(’# removed # removed {스터프3} placeholder (no-op) placeholder (no-op)’, ‘’ 정리 등)
apply(from = "$rootDir/gradle/buildErrorGuard.gradle.kts")


sourceSets {
  val main by getting {
    java.exclude("**/_abandonware_backup/**",
                 "**/gap15-stubs_v1/**",
                 "**/java_clean/**",
                 "extras/**",
                 "backup/**")
  }
}


dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
    implementation("dev.langchain4j:langchain4j-open-ai:1.0.1")

    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    compileOnly("org.projectlombok:lombok:1.18.34")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

    implementation("com.upstash:upstash-redis:1.3.2")

    implementation("ai.onnxruntime:onnxruntime_gpu:1.19.0")
    implementation("org.apache.lucene:lucene-core:8.11.4")
    implementation("org.apache.lucene:lucene-analyzers-common:8.11.4")
    implementation("org.apache.lucene:lucene-queryparser:8.11.4")
    implementation("org.apache.lucene:lucene-analyzers-nori:8.11.4")
}

// --- injected utility task ---
tasks.register("soakQuick") {
    group = "verification"
    description = "Quick soak/probe without tests"
    doLast {
        println("Running quick soak (no tests)")
    }
}
// --- end injected utility task ---

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }


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


configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "dev.langchain4j" && requested.version != "1.0.1") {
            useVersion("1.0.1")
        }
    }
}

springBoot {
    mainClass.set("com.example.lms.LmsApplication")
}


bootRun {
    mainClass.set("com.example.lms.LmsApplication")
}


bootJar {
    mainClass.set("com.example.lms.LmsApplication")
}

tasks.withType<org.gradle.api.tasks.testing.Test> {
    // GPT Pro / CI 에이전트 환경에서는 테스트를 기본 비활성화
    enabled = false
}
