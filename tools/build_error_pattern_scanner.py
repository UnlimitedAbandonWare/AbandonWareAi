# Auto-generated: scans typical build/test logs and records error pattern stats.
import os, re, json, sys, datetime, glob, collections

PATTERNS = [
    ("cannot_find_symbol", r"cannot find symbol", "compile", "의존성/임포트 누락, 또는 JDK 릴리즈 불일치"),
    ("illegal_escape_character", r"error:\s+illegal\s+escape\s+character", "compile", "정규식 이스케이프(역슬래시) 잘못으로 인한 Java 컴파일 오류"),
    ("javac_illegal_start_of_type", r"illegal start of type", "compile", "Javac 파서 오류: 메서드/클래스 바깥에 잘못된 토큰이 있거나 중괄호(}) 정리가 안 된 경우"),
    ("javac_parser_expected_type", r"class, interface, enum, or record expected", "compile", "Javac 파서 오류: 클래스/인터페이스/enum/record 선언 위치가 잘못되었거나, 이전 블록이 제대로 닫히지 않았습니다."),
    ("timeout_missing_import", r"symbol:\s+class\s+TimeoutException", "compile", "TimeoutException 임포트 누락 가능성 (java.util.concurrent.TimeoutException)."),
    ("package_does_not_exist", r"package .* does not exist", "compile", "의존성 누락 혹은 잘못된 groupId/artifactId"),
    ("maven_compiler_failure", r"Failed to execute goal .*maven-compiler-plugin.*", "compile", "maven-compiler-plugin 설정/자바 버전 확인"),
    ("gradle_could_not_resolve", r"Could not resolve all files for configuration", "deps", "리포지토리/버전 캐시 문제 또는 네트워크"),
    ("source_option_unsupported", r"Source option \d+ is no longer supported", "jdk", "maven.compiler.release 또는 Gradle toolchain 조정"),
    ("release_version_unsupported", r"release version \d+ not supported", "jdk", "JDK와 컴파일러 릴리즈 버전 불일치"),
    ("lombok_missing", r"(package org\.projectlombok does not exist|cannot find symbol\s+.*Slf4j)", "lombok", "Lombok 의존성과 annotationProcessor 추가"),
    ("spring_boot_plugin_missing", r"Plugin with id 'org\.springframework\.boot' not found", "gradle", "Gradle 플러그인 리졸빙/버전 정합"),
    ("junit5_platform", r"No tests found for given includes.*JUnit", "test", "JUnit5 플랫폼 설정 useJUnitPlatform() 필요"),
    ("class_file_not_found", r"class file for .* not found", "deps", "트랜지티브 의존성 누락 또는 모듈 경로 문제"),
    ("unsupported_major_minor", r"Unsupported major\.minor version", "jdk", "런타임/컴파일 타겟 버전 엇갈림"),
    ("could_not_find_artifact", r"Could not find artifact .*", "deps", "사설 레포 권한/리포지토리 설정 확인"),
    ("symbol_log_missing", r"symbol:\s+variable\s+log", "lombok", "Logger 정의 또는 Lombok @Slf4j 필요"),
]
PATTERN_REGEX = [(pid, re.compile(rx, re.IGNORECASE), cat, hint) for pid, rx, cat, hint in PATTERNS]

def scan_text(text: str):
    counts = collections.Counter()
    samples = {}
    for line in text.splitlines():
        l = line.strip()
        for pid, rx, cat, hint in PATTERN_REGEX:
            if rx.search(l):
                counts[pid] += 1
                if pid not in samples and len(l) < 500:
                    samples[pid] = l
    return counts, samples

def read_file_safe(p):
    try:
        with open(p, 'r', encoding='utf-8') as f:
            return f.read()
    except Exception:
        try:
            with open(p, 'r', encoding='cp949') as f:
                return f.read()
        except Exception:
            return ""

def main():
    out_dir = os.path.join("build", "error-patterns")
    os.makedirs(out_dir, exist_ok=True)
    # Typical locations
    candidates = []
    patterns = ["**/*.log", "build/*.log", "target/**/*.txt", "build/reports/**/*", "build/test-results/**/*.xml", "surefire-reports/*"]
    for pat in patterns:
        for p in glob.glob(pat, recursive=True):
            if os.path.isfile(p):
                candidates.append(p)
    # Also include CI output if present
    if os.path.exists("ci/build.out"):
        candidates.append("ci/build.out")
    if os.path.exists("ci/test.out"):
        candidates.append("ci/test.out")

    total_counts = collections.Counter()
    samples = {}
    scanned = []

    for p in candidates:
        text = read_file_safe(p)
        if not text:
            continue
        # cheap heuristic to include only files with "error/failed" words
        lower = text.lower()
        if any(k in lower for k in ["error", "failed", "cannot find symbol", "does not exist", "could not", "unsupported"]):
            c, s = scan_text(text)
            if c:
                scanned.append(p)
                total_counts.update(c)
                for k, v in s.items():
                    if k not in samples:
                        samples[k] = v

    report = {
        "generated_at": datetime.datetime.utcnow().isoformat() + "Z",
        "scanned_files": scanned,
        "counts": {k:int(v) for k,v in total_counts.items()},
        "samples": samples,
        "definitions": [{"id": pid, "category": cat, "hint": hint} for (pid, _, cat, hint) in PATTERN_REGEX]
    }

    out_json = os.path.join(out_dir, "build_error_patterns.json")
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    # Append to history
    hist_jsonl = os.path.join(out_dir, "history.jsonl")
    with open(hist_jsonl, "a", encoding="utf-8") as f:
        f.write(json.dumps(report, ensure_ascii=False) + "\n")

    print(f"[scanner] wrote {out_json} and appended {hist_jsonl}.")
    if report["counts"]:
        print("[scanner] summary:", report["counts"])
    else:
        print("[scanner] no patterns found")

if __name__ == "__main__":
    main()