plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

repositories { mavenCentral() }

dependencies {
    api("org.slf4j:slf4j-api:2.0.13")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")

    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

tasks.test { useJUnitPlatform() }


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