# Dev Notes
- 본 스켈레톤은 외부 의존성을 제거하여, 컴파일/머지 시 충돌을 최소화했습니다.
- 운영 환경에선 기존 모듈과의 IoC/DI 연결(@Bean, @ComponentScan)을 수행하세요.
- 플랜 파일은 파일 시스템 또는 classpath로 로딩 가능합니다.
