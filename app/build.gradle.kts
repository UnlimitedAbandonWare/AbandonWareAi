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

repositories {
  mavenCentral()
}

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

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
}
