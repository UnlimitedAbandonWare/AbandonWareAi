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

// lms-core is intentionally not included as a subproject in this projection.
// If you need lms-core functionality, supply it as an external JAR via -PlmsJar.
