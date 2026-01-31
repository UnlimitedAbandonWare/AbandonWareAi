# GPT_PRO_AGENT_INSTRUCTIONS_src_93

작업명: **src_92.zip 입력 → 소스 수정 → src_93.zip 출력**

목표: 비회원(게스트)이 브라우저 재시작 후에도 이전 세션 **목록/내용을 볼 수 있게** 한다.

## 수정 지점

### `src/main/java/com/example/lms/web/OwnerKeyBootstrapFilter.java`
- `ownerKey`를 **지속 쿠키(180일)** 로 발급 (`setMaxAge(60*60*24*180)`), **매 요청에서 TTL 갱신**.
- `HttpOnly; Path=/; SameSite=Lax;` (https면 `Secure`) 적용.
- `addCookie(...)` 외에 동일 속성의 **`Set-Cookie` 헤더도 추가**(인프라별 유실 대비).

### `src/main/java/com/example/lms/web/ClientOwnerKeyResolver.java`
- 해상 순서: **X-Owner-Key → ownerKey 쿠키 → gid 쿠키 → SHA256(ip|ua) → UUID**.
- 프록시 지원: `X-Forwarded-For` **첫 IP 우선**, 없으면 `remoteAddr`.
- DB에는 **해시 키만 저장**(원시 IP/UA 저장 금지).

## 빌드/검증
- **데이터베이스 마이그레이션 불필요** (`chat_session.owner_key/owner_type` 이미 존재).
- 프록시 뒤면 `X-Forwarded-For` 전달 여부 확인.
- **수용 테스트**: 최초 방문 쿠키 발급, 대화 생성 후 브라우저 재시작 시 이전 세션 유지, HTTPS에서 Secure 확인.

## 산출물
- 수정된 전체 소스를 **src_93.zip**으로 압축.
