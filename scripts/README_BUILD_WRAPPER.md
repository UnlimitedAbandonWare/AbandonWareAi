# Build Wrapper (Matrix-Aware)

이 폴더의 스크립트는 **빌드 오류 패턴 → 자동 수정** 규칙(=Matrix)에 따라 소스/Gradle 설정을 정리합니다.

## 사용법
### macOS/Linux
```bash
cd src_91
./scripts/build.sh
```

### Windows (PowerShell)
```powershell
cd src_91
.\scriptsix_build.ps1
# 이후
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain build -x test
```

## 무엇을 고치나요?
- `gradlew`가 `gradlew-real`을 찾다가 실패하는 문제 ⇒ **wrapper jar 직접 호출** + `gradlew-real` 자동 생성
- Lombok 심볼 미해결 ⇒ 각 `*.gradle.kts`에 `compileOnly/annotationProcessor` 추가(중복 방지)
- 자바 버전 불일치 ⇒ `java { source/targetCompatibility = 17 }` 보강
- 저장소 누락 ⇒ `repositories { mavenCentral() }` 보강
- 인코딩 ⇒ `UTF-8` 고정

> 스크립트는 **여러 번 실행해도 안전**(동일 라인 중복 삽입 없음)합니다.
