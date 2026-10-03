# B00 등록 실측

측정: 2026-09-28, Project Root `C:\AbandonWare\demo-1\demo-1\src`.
명령: `python -B scripts/autograde_b_rail.py --root .`
레일 exit: 0.

```
B00 RuleBreak=present; imports=present; importsRuleBreak=absent; componentScan=present; zipSha=match; action=NO_CHANGE_VERIFIED
```

| 체크 | live | 의미 |
|---|---|---|
| P01 `WebMvcConfig.addInterceptors` | present, 심볼 줄 59, ZIP SHA match | `ObjectProvider<RuleBreakInterceptor>` + `getIfAvailable()` + `addInterceptor` |
| P02 `AutoConfiguration.imports` | present, 항목 6, ZIP SHA match | RuleBreak라는 이름의 줄은 없다 |
| `RuleBreakInterceptor` | `@Component` + `@ConditionalOnClass` | `com.example.lms.guard.rulebreak` |
| `LmsApplication` | `scanBasePackages`에 `com.example.lms` | 컴포넌트 스캔으로 빈이 잡힌다 |

판정: `action=NO_CHANGE_VERIFIED`. RuleBreak를 imports에 다시 넣거나 `AgentApplication` 광역 스캔을 되살리지 않는다. 전역 HOLD 복원도 이 실측의 후속이 아니다.

`zipSha=match`는 P01과 P02 파일 바이트가 SOURCE_MAP ZIP 해시와 같다는 뜻이다. 그 두 파일의 ZIP 줄 범위는 아직 유효하다. 다른 P 파일의 STALE은 `remap.md`에 있다.

런타임 로그 `[AWX][rulebreak] mvc-interceptor=registered` 또는 `bean_absent`는 이번 세션에서 서버를 띄우지 않아 `NOT_RUN`이다. 빈 등록의 focused 후보는 아래이며 Grok은 Gradle을 돌리지 않았다.

```
.\gradlew.bat test --tests com.example.lms.config.WebMvcRuleBreakRegistrationTest
```

레일 자체 검증: `python -B scripts/test_autograde_b_rail.py` → 6 tests, OK, exit 0.
