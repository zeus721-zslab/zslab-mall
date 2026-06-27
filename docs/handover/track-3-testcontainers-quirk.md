# Track 3 STEP 14b — testcontainers 호환성 진단 인수인계

## 상태 (2026-06-26)
- Track 3 Payment Mock PR 생성 완료 (커밋 6123115·브랜치 feat/track-3-payment)
- 자가 검증 81/81 통과 (단위·웹·enum)
- STEP 14b 사용자 환경 검증 실패 — testcontainers 14건 + contextLoads 1건

## 환경 (검증 환경)
- Windows + PowerShell + Docker Desktop 4.73.1 (API 1.54·Server Version 29.4.3)
- JDK 21 Eclipse Temurin 21.0.11 LTS — 로컬 직접 설치 완료·JAVA_HOME 자동 설정
- 경로: C:\Users\pc\projects\zslab-mall\backend
- 실행: PowerShell에서 ./gradlew test 직접 (Docker 컨테이너 안 아님·Windows 네이티브)

## 진단 결과
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

## 시도된 해결책 (모두 실패)
1. .testcontainers.properties에 docker.host 설정 (3개 pipe 경로 시도·모두 실패)
2. 환경변수 DOCKER_HOST·TESTCONTAINERS_RYUK_DISABLED 설정
3. testcontainers BOM 1.20.4 명시 (build.gradle.kts·Spring Boot 3.4.1 기본 BOM과 동일)

## 진짜 원인 추정
Docker Desktop 4.73.1이 매우 최신 버전 (2026-05-06 빌드)·testcontainers와의 호환 검증 안 됨.
표준 npipe 경로(docker_engine·dockerDesktopLinuxEngine·docker_cli) 모두 testcontainers docker-java 호출에 정상 응답 안 함.

## contextLoads 별개 문제
@SpringBootTest ZslabMallApplicationTests contextLoads 1건은 별개 원인:
- application.yml이 ${DB_HOST}:${DB_PORT}/${DB_NAME} 환경변수 참조
- application-test.yml 부재
- 테스트 실행 시 환경변수 미주입 → DataSource URL 잘못 생성 → Flyway 연결 실패
- 해결: application-test.yml 신규 작성 또는 @DynamicPropertySource로 testcontainers 기반 DB 주입

## build.gradle.kts 현재 상태
```
testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
testImplementation("org.testcontainers:junit-jupiter")
testImplementation("org.testcontainers:mariadb")
```

→ BOM 1.20.4 적용 상태이나 효과 없음. 후속 채팅에서 BOM 명시 유지 여부 결정 (1.21.x 시도 또는 롤백).

## 다음 채팅 진입 시 결정 옵션

### 옵션 A: Docker Desktop 다운그레이드 (4.40.x 안정)
- 4.73.1 → 4.40.x 설치
- 비용: 30분·기존 설정 보존
- 성공률: 매우 높음 (검증된 안정 버전·testcontainers 광범위 사용 환경)

### 옵션 B: WSL2 안에서 실행
- WSL2 Ubuntu + JDK 21 설치
- WSL2 안에서 ./gradlew test 실행
- 비용: 1시간 초기 설치
- 성공률: 매우 높음 (Linux 환경·Docker 소켓 직접 접근)

### 옵션 C: Docker Desktop TCP 데몬 노출
- Settings → General → "Expose daemon on tcp://localhost:2375 without TLS" 체크
- 환경변수 DOCKER_HOST=tcp://localhost:2375 설정
- 비용: 5분
- 성공률: 중 (TLS 없음·로컬 개발에서는 허용)
- **다음 채팅 첫 시도 추천**

### 옵션 D: 머지 우회·CI 검증
- GitHub Actions에서 testcontainers 실행
- 사용자 로컬 환경 검증 영구 불가
- 비추천 — 향후 트랙도 동일 환경 필요

## 새 채팅 첫 액션
옵션 C 시도:
1. Docker Desktop Settings → General → "Expose daemon on tcp://localhost:2375 without TLS" 체크 → Apply & Restart
2. PowerShell에서:
```
$env:DOCKER_HOST = "tcp://localhost:2375"
cd C:\Users\pc\projects\zslab-mall\backend
./gradlew --stop
./gradlew test --tests "*PaymentRepositoryTest"
```
3. 결과 보고 → PASS 시 옵션 C 정식 채택·FAIL 시 옵션 A 진입

## 박제 후 새 채팅 진입 시 본 문서 정독·결정 옵션 그대로 적용
