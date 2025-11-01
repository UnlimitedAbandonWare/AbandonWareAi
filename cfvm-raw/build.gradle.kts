plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // Logging API
    api("org.slf4j:slf4j-api:2.0.12")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.12")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

// --- BEGIN auto-patch: exclude backup sources (src111_msaerge15) ---
sourceSets {
    val main by getting {
        java.exclude("/_abandonware_backup/")
        resources.exclude("/_abandonware_backup/")
    }
    val test by getting {
        java.exclude("/_abandonware_backup/")
    }
}
// --- END auto-patch ---