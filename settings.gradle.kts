rootProject.name = "src111_merge15"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://repo.spring.io/release")
  }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
    maven("https://repo.spring.io/release")
  }
}

include(":app")
if (file("demo-1").exists()) include(":demo-1")
if (file("cfvm-raw").exists()) include(":cfvm-raw")
if (file("lms-core").exists()) include(":lms-core")

// lms-core is included as an optional subproject in this projection.
// You can still supply lms-core as an external JAR via -PlmsJar if you prefer that mode.
