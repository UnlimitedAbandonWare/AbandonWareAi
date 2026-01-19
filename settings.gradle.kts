import java.io.File

/**
 * Offline-friendly repository setup.
 *
 * 기본 동작:
 * - 평소엔 (온라인) Gradle Plugin Portal / Maven Central / Spring repo 를 사용합니다.
 * - `--offline` 또는 `OFFLINE_BUILD=1` 인 경우, 공용 인터넷 레포지토리는 *추가하지 않습니다*
 *   (방화벽/폐쇄망에서 네트워크 hang 방지).
 *
 * 오프라인에서 의존성/플러그인 해소를 위해서는 아래 중 하나를 준비하세요.
 * - 사내 미러(Nexus/Artifactory 등):
 *     - MAVEN_MIRROR_URL
 *     - GRADLE_PLUGIN_MIRROR_URL (없으면 보통 MAVEN_MIRROR_URL 만으로도 해결되도록 미러 구성)
 * - 로컬(동봉) Maven repo 디렉토리:
 *     - OFFLINE_MAVEN_REPO=/path/to/m2repo  또는 file:///...
 *     - 또는 프로젝트 루트의 ./offline-repo 디렉토리
 */

val explicitOffline: Boolean = System.getenv("OFFLINE_BUILD") == "1"
val offlineMode: Boolean = gradle.startParameter.isOffline || explicitOffline

fun envOrProp(envKey: String, propKey: String): String? {
    val env = System.getenv(envKey)
    if (!env.isNullOrBlank()) return env
    val prop = System.getProperty(propKey)
    if (!prop.isNullOrBlank()) return prop
    return null
}

val mavenMirrorUrl: String? = envOrProp("MAVEN_MIRROR_URL", "mavenMirrorUrl")
val gradlePluginMirrorUrl: String? = envOrProp("GRADLE_PLUGIN_MIRROR_URL", "gradlePluginMirrorUrl")
val offlineMavenRepo: String? = envOrProp("OFFLINE_MAVEN_REPO", "offlineMavenRepo")
val projectLocalOfflineRepo: File? = File(rootDir, "offline-repo").takeIf { it.isDirectory }

fun org.gradle.api.artifacts.dsl.RepositoryHandler.addLocalReposFirst() {
    // 1) 로컬 캐시/레포지토리를 최우선
    mavenLocal()

    // 2) 외부에서 전달된 로컬 Maven repo 디렉토리
    if (!offlineMavenRepo.isNullOrBlank()) {
        maven { url = uri(offlineMavenRepo) }
    }

    // 3) 프로젝트에 동봉한 offline-repo
    if (projectLocalOfflineRepo != null) {
        maven { url = uri(projectLocalOfflineRepo) }
    }
}

fun org.gradle.api.artifacts.dsl.RepositoryHandler.addMirrorRepos() {
    // 사내 미러(프록시) 레포지토리
    if (!gradlePluginMirrorUrl.isNullOrBlank()) {
        maven { url = uri(gradlePluginMirrorUrl) }
    }
    if (!mavenMirrorUrl.isNullOrBlank()) {
        maven { url = uri(mavenMirrorUrl) }
    }
}

pluginManagement {
    repositories {
        addLocalReposFirst()
        addMirrorRepos()

        // 온라인일 때만 공용 레포지토리 사용
        if (!offlineMode) {
            gradlePluginPortal()
            mavenCentral()
            maven { url = uri("https://repo.spring.io/release") }
        }
    }
}

dependencyResolutionManagement {
    // 레포지토리는 settings 에서 단일 관리 (재현성/오프라인 대응)
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        addLocalReposFirst()

        if (!mavenMirrorUrl.isNullOrBlank()) {
            maven { url = uri(mavenMirrorUrl) }
        }

        if (!offlineMode) {
            mavenCentral()
            maven { url = uri("https://repo.spring.io/release") }
        }
    }
}

rootProject.name = "lms-core"

include(":app")
if (file("demo-1").exists()) include(":demo-1")
if (file("demo-2").exists()) include(":demo-2")
if (file("demo-3").exists()) include(":demo-3")
if (file("lms-core").exists()) include(":lms-core")

// backup modules
if (file("lms-core-backup").exists()) include(":lms-core-backup")
if (file("demo-1-backup").exists()) include(":demo-1-backup")
if (file("demo-2-backup").exists()) include(":demo-2-backup")
if (file("demo-3-backup").exists()) include(":demo-3-backup")
