
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gradle 빌드 로그에서 반복되는 오류 패턴을 수집/분석하고
수정 가이드를 제시하는 가드 스크립트.

사용:
  python tools/build_error_guard.py --scan build.log
  (또는)  ./gradlew compileJava 2>&1 | tee build.log && python tools/build_error_guard.py --scan build.log
"""
import re, json, sys, argparse, os, datetime

def normalize_ignore_list(raw_list):
    out = []
    for item in (raw_list or []):
        if not item:
            continue
        out.extend([p.strip() for p in re.split(r"[,\n;]", item) if p.strip()])
    return out

CATALOG = {
"yaml.duplicate_key": {
  "pattern": r"DuplicateKeyException.*duplicate key (\w+)",
  "title": "YAML Duplicate Key (application.yml)",
  "severity": "error",
  "fix": [
    "Merge duplicate top-level blocks (e.g., multiple 'retrieval:' sections)",
    "Enable Gradle yamlTopLevelGuard (preflight) to fail fast"
  ],
  "tags": ["yaml","config","preflight"]
},
  "java.illegal_escape_character": {
    "pattern": r"error:\s+illegal\s+escape\s+character",
    "title": "Java 정규식 이스케이프 오류 (illegal escape character)",
    "severity": "error",
    "explain": "Java 문자열 안에서 정규식 기호를 쓸 때 역슬래시(\\)를 한 번만 쓰면 컴파일 오류가 발생합니다. 예: \"Inc\\.\", \"Co\\.\", \"Ltd\\.\" 처럼 보이지만, 실제 Java 코드에서는 \"Inc\\\\.\", \"Co\\\\.\", \"Ltd\\\\.\"처럼 두 번 써야 합니다.",
    "fix": [
      "컴파일 로그의 'illegal escape character' 위치를 확인합니다.",
      "해당 라인의 문자열 리터럴에서 정규식용 마침표/슬래시 등 앞의 역슬래시를 두 번으로 늘립니다. 예: 'Inc\\\\.'",
      "혹은 Pattern.compile(...) + Matcher를 사용해 가독성을 높입니다.",
      "KeywordHeuristics처럼 회사명 후미를 치환하는 코드에서는 Inc\\., Co\\., Ltd\\. 등의 패턴을 모두 두 번 이스케이프 했는지 확인합니다."
    ],
    "tags": ["java","regex","compile","escape"]
  },

  "missing_findbugs_annotations": {
    "pattern": r"package\s+edu\.umd\.cs\.findbugs\.annotations\s+does\s+not\s+exist",
    "explain": "SpotBugs(구 FindBugs) 애노테이션 클래스패스 누락",
    "fix": [
      "build.gradle*에 compileOnly 'com.github.spotbugs:spotbugs-annotations:4.8.6' 추가",
      "mavenCentral() 저장소 확인",
    ],
    "tags": ["deps", "annotations", "spotbugs"]
  },
  "missing_lombok": {
    "pattern": r"cannot\s+find\s+symbol\s+class\s+(Getter|Setter|Builder|NoArgsConstructor|AllArgsConstructor|RequiredArgsConstructor)|annotation\s+type\s+Builder\s+not\s+found",
    "explain": "Lombok 누락 또는 annotationProcessor 미설정",
    "fix": [
      "compileOnly/annotationProcessor 'org.projectlombok:lombok:1.18.34' 추가",
      "configurations.compileOnly.extendsFrom annotationProcessor 설정",
      "프로젝트 루트에 lombok.config 추가"
    ],
    "tags": ["deps", "annotations", "lombok"]
  },
  "missing_timeout_import": {
    "pattern": r"symbol:\s+class\s+TimeoutException",
    "explain": "TimeoutException 클래스를 찾을 수 없는 컴파일 오류 (임포트 누락 가능성).",
    "fix": [
      "해당 소스 파일 상단에 'import java.util.concurrent.TimeoutException;' 추가",
      "Reactor/WebClient 타임아웃을 이 예외로 처리하는지 확인"
    ],
    "tags": ["imports", "timeout", "naver-search"]
  },
  "kapt_needed": {
    "pattern": r"error:\s+annotation\s+processing\s+is\s+not\s+enabled",
    "explain": "애노테이션 프로세싱 비활성",
    "fix": [
      "Gradle: annotationProcessor 설정 확인",
      "IDE: 'Enable annotation processing' 옵션 체크"
    ],
    "tags": ["config", "ide"]
  },
  "lms_search_method_mismatch": {
    "pattern": r"method\s+searchSnippets\(String,int\)|method\s+getKeyword\(\)",
    "explain": "LMS 데모의 BraveSearchService / WebSearchQuery 시그니처가 변경된 뒤에도 IntegrationController / SerpApiProvider 에서 예전 메서드 이름(searchSnippets, getKeyword)으로 호출하고 있을 때 발생하는 컴파일 오류입니다.",
    "fix": [
      "project/src/main/java/com/example/lms/api/IntegrationController.java 에서 brave.searchSnippets(\"test\", 1) 호출을 brave.search(\"test\") 로 변경합니다.",
      "project/src/main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java 에서 query.getKeyword() 호출을 query.getQuery() 로 변경합니다.",
      "이후 ./gradlew :project:compileJava 또는 해당 모듈만 다시 빌드해 컴파일 오류가 해결되었는지 확인합니다."
    ],
    "tags": ["compile", "java", "lms", "method-signature"]
  }
}
def scan_log(path):
    with open(path, 'r', encoding='utf-8', errors='ignore') as f:
        log = f.read()
    hits = []
    for code, meta in CATALOG.items():
        if re.search(meta["pattern"], log, flags=re.IGNORECASE|re.MULTILINE):
            hits.append({"code": code, **meta})
    return hits


def main():

    ap = argparse.ArgumentParser()
    ap.add_argument("--scan", required=True, help="Gradle build log path")
    ap.add_argument("--out", default=".build_error_report.json")
    args = ap.parse_args()
    # Load ignore patterns from env or --ignore
    env_ign = os.environ.get("GUARD_IGNORE_PATTERNS", "")
    cli_ign = os.environ.get("_GUARD_CLI_IGNORE", "")
    # argparse cannot be easily changed post hoc; emulate simple parsing from sys.argv for --ignore
    try:
        if "--ignore" in sys.argv:
            idx = sys.argv.index("--ignore")
            cli_ign = sys.argv[idx+1]
    except Exception:
        pass
    ignore_patterns = normalize_ignore_list([env_ign, cli_ign])
    hits = scan_log(args.scan)
    if ignore_patterns:
        # If any hit.explain or pattern matches ignore regex, drop it
        filtered = []
        for h in hits:
            joined = " ".join([h.get("code",""), h.get("explain","")] + h.get("fix",[]))
            if any(re.search(pat, joined, flags=re.IGNORECASE) for pat in ignore_patterns):
                continue
            filtered.append(h)
        hits = filtered
    report = {
        "ts": datetime.datetime.utcnow().isoformat()+"Z",
        "log": os.path.abspath(args.scan),
        "matched": hits
    }
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    print("== Build Error Guard ==")
    if hits:
        print(f"패턴 일치 {len(hits)}건")
        for h in hits:
            print(f"- [{h['code']}] {h['explain']}")
            for step in h["fix"]:
                print(f"    · {step}")
        print(f"\n자세한 보고서: {args.out}")
        sys.exit(2)  # 명시적 실패로 CI에서 눈에 띄게
    else:
        print("문제 패턴 없음")
        sys.exit(0)

if __name__ == "__main__":
    main()
