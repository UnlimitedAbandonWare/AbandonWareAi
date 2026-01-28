plugins {
    java
    id("org.springframework.boot") version "3.3.4"
}

// NOTE:
// - 레포지토리는 settings.gradle.kts 의 dependencyResolutionManagement 에서만 관리합니다.
//   (RepositoriesMode.FAIL_ON_PROJECT_REPOS)
// - 오프라인 빌드를 위해 mirror/local repo 를 settings 에서 주입할 수 있도록 되어 있습니다.

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
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Caching (required by CacheConfig and decision caches)
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")

    // LangChain4j (Version purity gate: keep a single non-beta line)
    implementation("dev.langchain4j:langchain4j:1.0.1")
    implementation("dev.langchain4j:langchain4j-open-ai:1.0.1")

    runtimeOnly("com.mysql:mysql-connector-j")
    // Verification profile (learning) uses in-memory H2 so verify_learning.sh works
    // without requiring external MariaDB/MySQL.
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
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

// Spring Boot main class
springBoot {
    mainClass.set("com.example.lms.LmsApplication")
}
