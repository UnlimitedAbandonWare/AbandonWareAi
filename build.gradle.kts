
// Root aggregator build (no Java/Boot plugins; only repo + shared config)
plugins {}

allprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/release") }
    }
}

tasks.register("noop") {}
