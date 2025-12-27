
# Build Error Guard

사용 예:
```
gradlew compileJava ^> build.log 2^>^&1
python tools/build_error_guard.py --scan build.log
```

CI에 통합:
```
- run: ./gradlew -i compileJava 2>&1 | tee build.log
- run: python tools/build_error_guard.py --scan build.log --out .artifact/build_error_report.json
```
패턴 카탈로그는 `build_error_guard.py`의 CATALOG에서 수정하세요.


### 무시(IGNORE) 패턴 —  무해화
빌드 로그 내 특정 항목을 **오류로 집계하지 않도록** 할 수 있습니다.

- 환경변수: `GUARD_IGNORE_PATTERNS=", harmless-warning"`
- 또는 CLI: `--ignore ""`

예)
```
./gradlew compileJava 2>&1 | tee build.log
GUARD_IGNORE_PATTERNS="" python tools/build_error_guard.py --scan build.log
```
