plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/release") }
}

dependencies {
    api("org.slf4j:slf4j-api:2.0.13")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("org.yaml:snakeyaml:2.2")

    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

tasks.test { useJUnitPlatform() }


// --- patch3: build-error scan task ---
tasks.register<JavaExec>("scanBuildLog") {
    group = "verification"
    description = "Scan a Gradle build log and write NDJSON + summary JSON (cfvm-raw patch3)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.lms.cfvm.tools.ScanBuildLogMain")
    // example usage:
    // ./gradlew :cfvm-raw:scanBuildLog --args="--log BUILD_LOG.txt --out build-logs/build-errors.ndjson --summary build-logs/build-error-summary.json --session dev"
}
