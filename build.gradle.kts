// Root aggregator build (Kotlin DSL). No Java compilation at root.
plugins {}
allprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/release") }
    }
}
tasks.register("noop") { doLast { println("noop") } }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
