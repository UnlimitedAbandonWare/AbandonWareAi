# SRC91 런북 — 부트 & 스모크

## 부트
```bash
./gradlew clean bootRun
```

## 헬스/키 없음 경로
```bash
curl -s localhost:8080/actuator/health | jq
curl -i http://localhost:8080/bootstrap
```

## RAG 스모크
```bash
curl -s 'http://localhost:8080/api/search?q=hello'
```

## 이미지 플러그인(옵션)
```properties
openai.image.enabled=true
```
```bash
curl -i http://localhost:8080/api/oauth/kakao/authorize
```

## 정적 검증/듀얼포트
```bash
curl -I http://localhost:8080/.well-known/pki-validation/test.txt
```
