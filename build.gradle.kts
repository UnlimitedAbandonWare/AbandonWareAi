plugins {
    id("org.springframework.boot") version "3.3.4"
}

// NOTE:
// - 레포지토리는 settings.gradle.kts 의 dependencyResolutionManagement 에서만 관리합니다.
//   (RepositoriesMode.FAIL_ON_PROJECT_REPOS)
// - 오프라인 빌드를 위해 mirror/local repo 를 settings 에서 주입할 수 있도록 되어 있습니다.

java {
    sourceCompatibility = JavaVersion.VERSION_17
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
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")

    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

sourceSets {
    main {
        java {
            srcDirs(
                "app/src/main/java",
                "app/src/main/resources",
                "main/java",
                "main/resources"
            )
        }
    }

    test {
        java {
            srcDirs("src/test/java")
        }
    }
}

// Spring Boot main class
springBoot {
    mainClass.set("com.example.lms.LmsApplication")
}
