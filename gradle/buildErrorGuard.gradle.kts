import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets

val guardTag = "[build-guard]"

fun isTextFile(name: String) =
  name.endsWith(".java", true) || name.endsWith(".kt", true) ||
  name.endsWith(".kts", true)  || name.endsWith(".gradle", true) ||
  name.endsWith(".groovy", true) || name.endsWith(".yml", true) ||
  name.endsWith(".yaml", true) || name.endsWith(".properties", true) ||
  name.endsWith(".md", true)

tasks.register("preflightFixes") {
  group = "verification"
  description = "Auto-fix common merge/build issues (ellipsis, # removed # removed {스터프3} placeholder (no-op) placeholder (no-op), stray placeholders) before compile"
  doLast {
    val root: Path = project.rootDir.toPath()
    val fixed = mutableListOf<Path>();
    val offenders = mutableListOf<Path>();

    Files.walk(root).filter { Files.isRegularFile(it) && isTextFile(it.fileName.toString()) }.forEach { p ->
      var s = Files.readString(p, StandardCharsets.UTF_8)
      val original = s

      // 1) # removed # removed {스터프3} placeholder (no-op) placeholder (no-op) family → 안전 토큰으로 중화
      s = s.replace(Regex("\\{?\\s*스터프3\\s*\\}?"), "STUFF3_DISABLED")

      // 2) 유니코드 ellipsis(…)와 ascii ''가 코드/스크립트에 남은 경우
      //    - Gradle/YAML: 해당 줄 삭제
      //    - 코드 파일: 줄을 주석 처리
      val lines = s.lines().toMutableList()
      for (i in lines.indices) {
        val line = lines[i]
        if (line.contains("…") || Regex("\\.\\.\\.").containsMatchIn(line)) {
          val lower = p.fileName.toString().lowercase()
          if (lower.endsWith(".gradle") || lower.endsWith(".kts") || lower.endsWith(".groovy") ||
              lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            lines[i] = "" // 제거
          } else if (lower.endsWith(".java") || lower.endsWith(".kt")) {
            lines[i] = "// $guardTag ellipsis removed: " + line.replace("/*","").replace("*/","")
          } else {
            lines[i] = lines[i].replace("…", "").replace("", "")
          }
        }
      }
      s = lines.joinToString(System.lineSeparator())

      if (s != original) {
        Files.writeString(p, s, StandardCharsets.UTF_8)
      }

      // 3) 잔존 placeholder 경고
      if (Regex("\\{\\{?\\w+_PLACEHOLDER\\}?\\}").containsMatchIn(s)) offenders.add(p)
    }

    println("$guardTag offenders=" + offenders.size)
    if (offenders.isNotEmpty()) {
      println("$guardTag placeholders still present:")
      offenders.take(20).forEach { println(" - " + root.relativize(it)) }
    }

    // 4) 최근 빌드 로그 꼬리 요약
    val logs = Files.walk(root)
      .filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().matches(Regex(".*(build|compile|error).*\\.log$")) }
      .toList()
    if (logs.isNotEmpty()) {
      println("$guardTag recent logs:")
      logs.take(4).forEach { p ->
        println("  [log] " + root.relativize(p))
        val last = Files.readAllLines(p, StandardCharsets.UTF_8).takeLast(30)
        last.forEach { l -> println("     " + l) }
      }
    }
  }
}

// 컴파일/실행 전에 항상 수행
gradle.projectsEvaluated {
  tasks.matching { it.name == "compileJava" || it.name == "compileKotlin" || it.name == "bootRun" }
    .configureEach { dependsOn(tasks.named("preflightFixes")) }
}