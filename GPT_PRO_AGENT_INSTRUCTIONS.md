  # GPT Pro 에이전트용 수정 지시서 (Spring Boot + Redis 스택 확정판)

  ## 작업명
  게스트 세션별 방/히스토리 생성 → 로그인 시 기록 승계 → Admin 가시성 노출 (Java/Spring Boot, Redis 기반)

  ## 현재 스택
  - Spring Boot 3.1.x (Gradle Kotlin DSL)
  - spring-boot-starter-web, spring-data-redis 사용
  - `com.abandonware.ai.agent` 패키지, `ContextBridge` + `ConsentInterceptor` 존재
  - 보안 모듈(Spring Security) 없음 → Admin 엔드포인트는 일단 공개 (상위 레이어에서 보호 필요)

  ## 목표
  1) **비로그인(게스트)** 도 브라우저 **세션 쿠키(gid)** 만으로 방(Room) 생성 및 메시지 히스토리 누적
  2) **로그인 시**(사용자 식별자 제공) **게스트 데이터 승계**(guest:<session> → user:<userId>)
  3) **Admin** 은 게스트/유저/승계 모든 방을 **조회 가능(type 필터 제공)**

  ## 구현 포인트(이 리포지토리에 적용됨)
  - **IdentityInterceptor** 추가 → `gid` 쿠키 발급/유지, `ContextBridge` sessionId 세팅(없을 때만)
  - **Redis 기반 RoomService**:
    - 키 스키마
      - `room:{roomId}` → JSON(Room)
      - `room:{roomId}:messages` → LIST(JSON(Message))
      - `identity:{identity}:rooms` → SET(roomId)
      - 인덱스: `rooms:all`, `rooms:guest`, `rooms:user`, `rooms:migrated`
    - 마이그레이션: `guest:<session>` → `user:<userId>` 전환 + 메시지 authorIdentity 업데이트
  - **API**
    - `POST /api/rooms` : 방 생성 (`X-User-Id` 헤더 있으면 user, 없으면 guest)
    - `GET  /api/rooms` : 내 방 목록
    - `POST /api/messages` : 메시지 작성 `{roomId, content}`
    - `GET  /api/rooms/{roomId}/messages?limit=200` : 최근 메시지
    - `POST /api/identity/claim` : `{userId}` 또는 헤더 `X-User-Id` 로 승계
    - `GET  /api/admin/rooms?type=all|guest|user|migrated` : Admin 조회
  - **WebConfig** 에 인터셉터 등록 순서
    - `IdentityInterceptor` → `ConsentInterceptor`
    - 헤더 `X-Session-Id` 가 있을 경우 ConsentInterceptor 가 우선(IdentityInterceptor 는 bridge.current()==null 일 때만 세팅)

  ## 수용 기준 (테스트 시나리오)
  - [게스트] `gid` 없음 → 최초 요청 시 쿠키 발급, `POST /api/rooms` 성공, `POST /api/messages` 정상, `GET /api/rooms/{id}/messages` 로 조회
  - [승계] 동일 브라우저에서 `POST /api/identity/claim` (헤더 `X-User-Id: u1`) → 해당 세션의 모든 방/메시지가 `user:u1` 로 전환, 재요청 시 `GET /api/rooms` 에 동일 목록이 보임
  - [멱등] 동일 승계 요청 반복 시 데이터 중복/손실 없음
  - [교차기기] 다른 브라우저(다른 gid)는 승계 전까지 해당 데이터 미노출
  - [Admin] `GET /api/admin/rooms?type=migrated` 등 필터가 정상 동작
  - [회귀] 기존 `/flows/{flow}:run` 등 기존 엔드포인트 정상 동작

  ## 운영/보안 주의
  - 현재 Admin 엔드포인트는 인증 없음 → 게이트웨이/프록시에서 보호하거나 Spring Security 도입 검토
  - Redis TTL 을 도입하고 싶다면 `identity:guest:<gid>:rooms` 에 TTL 설정 고려(본 구현은 TTL 미설정)
  - 쿠키 보안: 운영 환경에서 `Secure`, `SameSite=Lax` 유지. 서명/암호화가 필요하면 서블릿 컨테이너/게이트웨이 레벨에서 처리.

  ## 코드 변경 파일
  - `app/src/main/java/com/abandonware/ai/agent/identity/IdentityInterceptor.java`
  - `app/src/main/java/com/abandonware/ai/agent/identity/IdentityUtils.java`
  - `app/src/main/java/com/abandonware/ai/agent/room/Room.java`
  - `app/src/main/java/com/abandonware/ai/agent/room/Message.java`
  - `app/src/main/java/com/abandonware/ai/agent/room/RoomService.java`
  - `app/src/main/java/com/abandonware/ai/agent/web/RoomController.java`
  - `app/src/main/java/com/abandonware/ai/agent/web/MessageController.java`
  - `app/src/main/java/com/abandonware/ai/agent/web/IdentityController.java`
  - `app/src/main/java/com/abandonware/ai/agent/web/AdminController.java`
  - `app/src/main/java/com/abandonware/ai/agent/config/WebConfig.java` (인터셉터 등록)

  ## 로컬 실행 체크
  1) Redis 실행 (기본 localhost:6379)
  2) 앱 기동 후:
  ```bash
  curl -i -X POST http://localhost:8080/api/rooms -H "Content-Type: application/json" -d '{"title":"demo"}'
  # 응답 헤더 Set-Cookie: gid=... 확인, body 의 roomId 확보
  curl -i -X POST http://localhost:8080/api/messages -H "Content-Type: application/json" \
-d '{"roomId":"<roomId>","content":"hello"}'
  curl -s http://localhost:8080/api/rooms/<roomId>/messages | jq
  curl -i -X POST http://localhost:8080/api/identity/claim -H "X-User-Id: u1"
  curl -s http://localhost:8080/api/rooms | jq
  curl -s "http://localhost:8080/api/admin/rooms?type=migrated" | jq
  ```
