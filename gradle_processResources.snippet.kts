// (optional) Add into app/build.gradle.kts
tasks.processResources {
    from("../configs") {
        include("models.manifest.yaml")
        into("configs")
    }
}