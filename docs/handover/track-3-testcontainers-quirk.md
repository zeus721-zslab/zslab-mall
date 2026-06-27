# Track 3 STEP 14b — testcontainers 호환성 진단 인수인계

## 상태: RESOLVED (2026-06-27)
- testcontainers 1.21.4 → docker-java 3.4.2 채택으로 14건 전원 PASS (94/95 통과)
- contextLoads 1건은 별개 원인 (아래 별도 항목 참조)

---

## 결론 (RESOLVED 2026-06-27)

**원인**: docker-java 3.4.0 ↔ Docker Desktop 4.73.1 (Engine 29.4.3) `/info` API 호환 버그
- `NpipeSocketClientProviderStrategy`: HTTP 400 + 빈 JSON 응답 (모든 필드 0/null·Labels에 npipe 주소만)
- docker-java 3.4.0이 HTTP 400 응답을 성공으로 처리하지 못하고 예외 처리 실패

**해결**: `extra["testcontainers.version"] = "1.21.4"` (Spring Boot dependency-management override 패턴)
- testcontainers 1.21.4 → docker-java 3.4.2 로 업그레이드됨
- docker-java 3.4.2에서 Docker Desktop 4.73.1 API 400 응답 처리 수정됨

**핵심 학습**: Spring Boot 프로젝트에서 BOM `platform()` 선언만으로는 Spring Boot 관리 의존성 override 불가.
`extra["라이브러리.version"]` 패턴 사용 필수 (Spring Boot dependency-management가 인식하는 property key 방식).

---

## 원래 진단 (2026-06-26)

### 환경
- Windows + PowerShell + Docker Desktop 4.73.1 (API 1.54·Server Version 29.4.3)
- JDK 21 Eclipse Temurin 21.0.11 LTS — 로컬 직접 설치 완료·JAVA_HOME 자동 설정
- 경로: C:\Users\pc\projects\zslab-mall\backend
- 실행: PowerShell에서 ./gradlew test 직접 (Docker 컨테이너 안 아님·Windows 네이티브)

### 진단 결과
Docker Desktop 자체는 정상:
- docker info 정상 응답 (Server Version 29.4.3·Containers 12)
- docker run hello-world 정상
- docker ps 정상

testcontainers의 docker-java만 실패:
- testcontainers 1.20.4 (BOM 명시 적용 확인)
- Windows 환경에서 시도되는 전략: EnvironmentAndSystemPropertyClientProviderStrategy·NpipeSocketClientProviderStrategy
- 두 전략 모두 Docker Desktop API 호출 시 비정상 응답 수신:
  · EnvironmentAndSystemPropertyClientProviderStrategy: HTTP 404 (.testcontainers.properties의 docker.host=npipe:////./pipe/docker_cli로 시도)
  · NpipeSocketClientProviderStrategy: HTTP 400 + 빈 JSON 응답 (모든 필드 빈 값·Labels에 com.docker.desktop.address=npipe://\\\\.\\pipe\\docker_cli만 포함)

### build.gradle.kts 최종 상태
```kotlin
// Spring Boot 3.4.1의 dependency-management가 testcontainers 1.20.4로 pin하므로 override.
// docker-java 3.5.x 도입 시 도커 Desktop 4.73.1(Engine 29.4.3)과 호환 진단.
extra["testcontainers.version"] = "1.21.4"
```

---

## contextLoads 별개 문제 (미해결·별도 트랙)

`@SpringBootTest ZslabMallApplicationTests contextLoads` 1건은 testcontainers와 별개 원인:
- application.yml이 `${DB_HOST}:${DB_PORT}/${DB_NAME}` 환경변수 참조
- application-test.yml 부재
- 테스트 실행 시 환경변수 미주입 → DataSource URL 잘못 생성 → Flyway 연결 실패
- 해결: application-test.yml 신규 작성 또는 `@DynamicPropertySource`로 testcontainers 기반 DB 주입
- 처리 시점: 별도 트랙에서 결정

---

## 시도 이력 (참고용)

### 시도 1~3 (모두 실패·1.21.4 업그레이드로 우회)
1. .testcontainers.properties에 docker.host 설정 (3개 pipe 경로 시도·모두 실패)
2. 환경변수 DOCKER_HOST·TESTCONTAINERS_RYUK_DISABLED 설정
3. testcontainers BOM 1.20.4 명시 (build.gradle.kts·Spring Boot 3.4.1 기본 BOM과 동일·효과 없음)

### 옵션 A: Docker Desktop 다운그레이드 (불필요·1.21.4로 해결)
- 4.73.1 → 4.40.x 설치 계획이었으나 채택 불필요

### 옵션 B: WSL2 안에서 실행 (불필요·1.21.4로 해결)
- WSL2 Ubuntu + JDK 21 계획이었으나 채택 불필요

### 옵션 C: Docker Desktop TCP 데몬 노출 (미시도·1.21.4로 해결)
- Settings → General → "Expose daemon on tcp://localhost:2375 without TLS" 체크
- 환경변수 DOCKER_HOST=tcp://localhost:2375 설정 계획이었으나 채택 불필요

### 옵션 D: 머지 우회·CI 검증 (채택 불필요)
- 비추천으로 기록됐으나 실제 해결로 불필요
