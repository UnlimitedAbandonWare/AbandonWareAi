plugins {
    id("java")
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    compileOnly("org.projectlombok:lombok:1.18.34")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    implementation("dev.langchain4j:langchain4j:1.0.1")
    implementation("dev.langchain4j:langchain4j-open-ai:1.0.1")

    implementation("ai.onnxruntime:onnxruntime:1.19.0")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("com.upstash:upstash-redis:1.3.2")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
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
