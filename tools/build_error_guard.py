
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
  "explain": "application.yml/application.yaml 에서 동일한 키가 중복 선언되어 SnakeYAML이 DuplicateKeyException 을 던질 때 발생합니다.",
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
  "java.text_block_open_delimiter": {
    "pattern": r"illegal text block open delimiter sequence, missing line terminator",
    "title": "Java 17 텍스트 블록 구문 오류 (text block open delimiter)",
    "severity": "error",
    "explain": "Java 17 텍스트 블록(여는 따옴표 3개 뒤에 여러 줄 문자열을 쓰는 문법)에서 여는 따옴표 뒤에 바로 문자열이 오면 이 오류가 발생합니다.",
    "fix": [
      "여는 따옴표 세 개 뒤에서 바로 줄바꿈(Enter)을 넣고, 실제 문자열 내용은 다음 줄부터 시작하도록 수정합니다.",
      "텍스트 블록 앞뒤에 불필요한 공백이나 주석이 끼어 있지 않은지도 함께 확인합니다."
    ],
    "tags": ["java","text-block","compile","syntax"]
  },


  "java.illegal_start_of_type": {
    "pattern": r"error:\s+illegal start of type",
    "title": "Java 파서 오류 (illegal start of type)",
    "severity": "error",
    "explain": "메서드/클래스 바깥에 if/for 구문 등이 잘못 배치되었거나, 중괄호(})가 올바르게 닫히지 않을 때 발생하는 Javac 문법 오류입니다.",
    "fix": [
      "오류 직전/직후 라인의 중괄호( {, } ) 개수를 세어 블록이 정확히 닫혔는지 확인합니다.",
      "메서드 선언 밖에 남아 있는 if/for/while/return 등의 구문이 없는지 확인하고, 필요 시 메서드 안으로 옮기거나 제거합니다.",
      "이전 리팩토링에서 잘린 코드 조각이나 중복으로 붙여넣어진 구간(ModelRouterCore 등)이 없는지 확인합니다."
    ],
    "tags": ["java","compile","parser","brace"]
  },

  "java.parser_expected_type": {
    "pattern": r"error:\s+class, interface, enum, or record expected",
    "title": "Java 파서 오류 (class, interface, enum, or record expected)",
    "severity": "error",
    "explain": "Javac가 클래스/인터페이스/enum/record 선언이 와야 할 위치에서 다른 토큰을 발견했을 때 발생합니다. 대개 직전의 블록이 제대로 닫히지 않았거나, 잘린 선언이 남아 있을 때입니다.",
    "fix": [
      "해당 오류 라인의 위쪽에서 클래스/메서드 선언이 중간에 끊겨 있는지 확인합니다.",
      "IDE의 'Reformat code'나 'Auto-indent'로 블록 구조를 눈으로 확인한 뒤, 여는/닫는 중괄호가 쌍을 이루는지 점검합니다.",
      "최근에 머지/리팩토링한 구간에 중복된 메서드 본문이나 잘못된 위치의 어노테이션이 없는지 확인합니다."
    ],
    "tags": ["java","compile","parser","brace"]
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

  ,

  "gradle.lms_core_not_found": {
    "pattern": r"(:lms-core not found|project\s+:lms-core\s+not\s+found|Project with path ':lms-core' could not be found|Could not resolve project :lms-core)",
    "explain": ":lms-core 모듈이 루트 settings.gradle(또는 settings.gradle.kts)에 포함되지 않았거나, 단독 실행 환경에서 모듈 경로가 누락되어 발생합니다.",
    "fix": [
      "멀티모듈로 빌드한다면 루트 settings.gradle(.kts)에 include(':lms-core') 를 추가합니다.",
      "단독 데모 프로젝트라면 -PlmsJar=/path/to/lms-core.jar 로 외부 JAR을 지정하는 방식도 가능합니다.",
      "모듈이 실제로 존재하는지(폴더 경로)와 settings 파일의 include 조건(file('lms-core').exists())도 함께 확인하세요."
    ],
    "tags": ["gradle", "module", "dependency"]
  }

  ,

  "java.webclient_body_ambiguous": {
    "pattern": r"reference to body is ambiguous|both method body\(Flux<DataBuffer>\) in Builder and method body\(String\) in Builder match",
    "explain": "WebClient ClientResponse.mutate().body(null) 호출이 오버로딩(Flux<DataBuffer> vs String) 때문에 모호하여 컴파일 오류가 발생합니다.",
    "fix": [
      "body(null) 를 body((String) null) 로 변경하여 String 오버로드를 명시합니다.",
      "예: .then(Mono.just(clientResponse.mutate().body((String) null).build()));"
    ],
    "tags": ["java", "webclient", "compile", "overload"]
  }

  ,

  "java.chatresult_moved": {
    "pattern": r"ChatService\.ChatResult|symbol:\s+class\s+ChatResult\s*\n\s*location:\s+class\s+ChatService",
    "explain": "ChatResult가 ChatService의 내부 클래스로 존재하던 구조에서 top-level record/class로 분리된 뒤, 예전 참조(ChatService.ChatResult)가 남아 컴파일 오류가 발생합니다.",
    "fix": [
      "ChatService.ChatResult → ChatResult 로 타입 참조를 변경합니다.",
      "필요하면 import com.example.lms.service.ChatResult; 를 추가합니다.",
      "오류 파일 예: ChatApiController, ChatWebSocketHandler"
    ],
    "tags": ["java", "compile", "refactor", "type"]
  }

  ,

  "java.chatworkflow_missing_helper": {
    "pattern": r"composeEvidenceOnlyAnswer\(|cannot find symbol\s*\n\s*\^\s*\n\s*symbol:\s+method\s+composeEvidenceOnlyAnswer",
    "explain": "ChatWorkflow에서 composeEvidenceOnlyAnswer(...) 헬퍼 메서드가 누락되어 빌드가 실패합니다.",
    "fix": [
      "ChatWorkflow 클래스 내부에 composeEvidenceOnlyAnswer(List<EvidenceDoc>, String) 메서드를 추가합니다.",
      "구성은 evidenceAnswerComposer.compose(query, evidenceDocs, isLowRiskDomain(evidenceDocs)) 형태로 연결하는 것이 안전합니다."
    ],
    "tags": ["java", "compile", "missing-method"]
  }

  ,

  "java.metadata_get_removed": {
    "pattern": r"method\s+get\(String\)\s*\n\s*location:\s+variable\s+metadata\s+of\s+type\s+Metadata",
    "explain": "LangChain4j 1.0.x의 Metadata API에서 get(String) 메서드가 없어졌는데 legacy 코드가 metadata.get('url') 형태로 호출하여 컴파일 오류가 발생합니다.",
    "fix": [
      "metadata.get('key') → metadata.getString('key') 로 변경합니다.",
      "예: String url = metadata.getString('url');  (fallback: metadata.getString('source'))"
    ],
    "tags": ["java", "langchain4j", "metadata", "compile"]
  }

  ,

  "java.query_builder_metadata_signature": {
    "pattern": r"method\s+metadata\s+in\s+class\s+Builder\s+cannot\s+be\s+applied\s+to\s+given\s+types",
    "explain": "LangChain4j Query.Builder#metadata(...) 시그니처가 변경되어 metadata(key, value) 형태 호출이 더 이상 지원되지 않아 컴파일 오류가 발생합니다.",
    "fix": [
      "기존 메타데이터를 Map/asMap()로 펼친 뒤, 새 키를 주입한 Metadata 객체를 만들어 .metadata(metaObj) 로 한 번만 전달합니다.",
      "EntityDisambiguationHandler 에서 Query.builder().metadata('originalQuery', ...) 호출을 제거하고, dev.langchain4j.data.document.Metadata.from(Map) 방식으로 교체하세요."
    ],
    "tags": ["java", "langchain4j", "query", "compile"]
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
