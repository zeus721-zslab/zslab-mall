# 배포 런북 — 방식 A (Actions 빌드 → ghcr.io → 서버 pull)

Track 100(D-211)에서 서버 빌드를 걷어낸 뒤의 배포 절차다. 대상 독자는 운영자(zslab) 1인.

- 워크플로: `.github/workflows/deploy.yml`
- 이미지: `ghcr.io/zeus721-zslab/zslab-mall-backend`, `ghcr.io/zeus721-zslab/zslab-mall-frontend`
- 태그: `latest`(항상 최신 배포본) + `sha-<7자>`(커밋 고정용)
- 서버는 이미지를 빌드하지 않는다. `git pull`은 `docker-compose.mall.yml`·`docker/filebeat/filebeat.yml` 동기화용으로만 남아 있다.

## 1. 평상시 배포

`main`에 `backend/**`·`frontend/**`·`docker/**`·`docker-compose.mall.yml`·`deploy.yml` 변경이 머지되면 자동 실행된다.

1. `build` — 변경된 쪽만 빌드해 두 태그로 push
2. `deploy` — SSH로 서버에서 `git pull` → `compose pull` → `up -d --no-build --wait`
3. `cleanup` — 패키지별 최근 5개 버전만 유지

**이미지 빌드는 테스트를 실행하지 않는다.** 테스트 게이트는 PR 단계의 `backend-ci.yml`·`frontend-ci.yml`이다.

수동 배포는 Actions 화면의 `Run workflow`(workflow_dispatch). 이 경우 변경 판정을 건너뛰고 양쪽을 모두 빌드한다.

## 2. 최초 전환 (완료 · 2026-09-23 실측)

1. PR #256 머지 → `deploy.yml` 첫 실행.
2. `build` job이 두 패키지를 생성했고 **`deploy` job이 그대로 성공했다** — 서버가 인증 없이 pull 할 수 있었다. 사전 예상과 달리 **패키지 가시성을 public으로 바꾸는 작업은 필요 없었다**(패키지가 처음부터 pull 가능한 상태로 만들어졌다).
3. 서버에서 실제로 뜬 이미지를 `deploy` 로그의 digest 줄(`zslab_mall_backend -> ghcr.io/... @ sha256:...`)로 확인했다.

앞으로 `deploy`가 `denied`/`unauthorized`로 실패한다면 패키지가 private으로 만들어진 경우다. 둘 중 하나로 처치한다.

- GitHub → Packages → `zslab-mall-backend`·`zslab-mall-frontend` → Package settings → Change visibility → **Public** → 실패한 run에서 `deploy` job만 **Re-run failed jobs**
- private을 유지하려면 서버에서 `docker login ghcr.io`(PAT·`read:packages`)를 1회 수행한다.

## 3. 롤백

`build`는 변경된 쪽만 빌드하므로(§1), **한쪽만 바뀐 커밋은 다른 패키지에 그 sha 태그가 없다.** 예를 들어 프론트엔드만 바뀐 커밋의 `sha-<7자>`는 frontend 패키지에만 있고 backend 패키지에는 없다. 그래서 두 패키지의 롤백 태그는 따로 고른다.

- 지정 규칙: **패키지마다, 되돌릴 시점 이전에 그 패키지에 올라간 가장 최근 `sha-<7자>` 태그**를 고른다. 두 값이 다른 것이 정상이다.
- 존재 태그 확인(둘 중 하나):
  - 서버: `docker manifest inspect ghcr.io/zeus721-zslab/zslab-mall-backend:sha-<7자>` — 있으면 매니페스트가 출력되고, 없으면 `manifest unknown`으로 실패한다. frontend도 같은 방법(`zslab-mall-frontend`).
  - GitHub → 저장소 → Packages → `zslab-mall-backend`·`zslab-mall-frontend` → 버전 목록에서 패키지별 태그와 게시 시각을 본다(§4 cleanup 때문에 최근 5개만 남는다).

태그를 고른 뒤 서버에서:

1. `DEPLOY_PATH`의 `.env`에 태그를 지정한다(예: 백엔드는 마지막 백엔드 변경 커밋, 프론트엔드는 그 뒤 프론트 전용 커밋).

   ```
   BACKEND_IMAGE_TAG=sha-1a2b3c4
   FRONTEND_IMAGE_TAG=sha-5d6e7f8
   ```

   한쪽만 되돌릴 때는 해당 변수만 지정한다(미지정 변수는 `latest`).

2. 이미지를 받고 재기동한다.

   ```
   docker compose -f docker-compose.mall.yml pull zslab_mall_backend zslab_mall_frontend
   docker compose -f docker-compose.mall.yml up -d --no-build --wait --wait-timeout 180
   ```

3. 복귀할 때는 `.env`의 두 줄을 지우거나 `latest`로 되돌린 뒤 같은 두 명령을 다시 실행한다.

**DB 마이그레이션이 포함된 커밋은 이미지 롤백만으로 되돌아가지 않는다.** Flyway는 `validate`로 기동하므로, 구 이미지가 신 스키마를 만나면 기동에 실패할 수 있다. 이 경우 보상 마이그레이션을 포함한 새 커밋으로 앞으로 굴리는 쪽이 안전하다.

## 4. 확인·정리

- 실행 중인 이미지: `docker compose -f docker-compose.mall.yml ps` / `docker inspect zslab_mall_backend --format '{{.Config.Image}}'`
- 서버 이미지 정리: `deploy` job 끝에서 이 저장소 라벨(`org.opencontainers.image.source`)이 붙은 dangling 이미지만 자동 정리한다. 서버의 다른 프로젝트 이미지는 건드리지 않는다.
- 레지스트리 정리: `cleanup` job이 패키지별 최근 5개만 남긴다. **6번째 이전 버전으로는 롤백할 수 없다.**
- gateway 압축: zslab-mall 443 블록에서 gzip으로 응답한다(D-228). 설정 위치는 gateway nginx(이 저장소 밖)이고, 앱 이미지는 압축하지 않는다. 확인: `curl -sI -H 'Accept-Encoding: gzip' https://zslab-mall.duckdns.org/` → `Content-Encoding: gzip`.

## 5. 자주 나오는 실패

| 증상 | 원인 | 처치 |
|---|---|---|
| 배포는 성공인데 코드가 안 바뀜 | `compose pull`이 대상 없이 no-op | `deploy` 로그의 digest 줄 확인 → `docker-compose.mall.yml`에 `image:` 키가 있는지 확인 |
| `deploy`가 `denied`로 실패 | 패키지가 private | §2 후반부(public 전환 후 job re-run 또는 서버 `docker login`) |
| `cleanup`이 실패 | 패키지 미존재(최초 실행) | 무시해도 된다(`continue-on-error`) |
| 구 이미지로 내렸더니 기동 실패 | Flyway `validate` 불일치 | §3 마지막 문단 |

## 6. 헬스 감시

감시는 두 경로다(D-272). **주 경로는 서버 cron 점검**이고, GitHub 예약 점검은 서버 자체가 멈춘 경우를 잡는 보조 경로다. 알림은 둘 다 GitHub Actions 실패 알림 메일로 받는다(운영 메일은 모의 발송이라 서버 메일을 쓰지 않는다 · §7 `EMAIL_SENDER`).

### 6-1. 서버 cron 점검 (주)

- 스크립트: `docker/ops/health-alert.sh` — 서버 cron이 주기적으로 실행한다.
- 점검 4가지: 공개 메인 페이지 200 · mall 컨테이너(`zslab_mall_backend`·`zslab_mall_frontend`) health가 `healthy` · backend 내부 헬스(컨테이너 안 `localhost:8080/actuator/health`)가 `UP` · 직전 실행 이후 backend 로그의 ERROR 줄 수가 0(첫 실행은 최근 15분). 스케줄러 실패도 ERROR 로그로 남으므로 여기에 잡힌다.
- 이상 시: GitHub `repository_dispatch`(event_type `health-alert`)를 보낸다 → `.github/workflows/health-alert.yml`이 요약을 출력하고 실패한다 → 실패 알림 메일. 요약에는 항목명·상태 코드·ERROR 건수만 싣고 로그 원문은 싣지 않는다. 원문은 서버에서 `docker logs --since <시각> zslab_mall_backend`로 본다.
- 이상이 이어지면 실행마다 알린다(중복 억제 없음).
- 설정: 배포 디렉터리는 cron 줄의 환경변수 `HEALTH_ALERT_DEPLOY_DIR`로 넘기고, 나머지 3개는 그 디렉터리의 `.env`에 둔다 — `HEALTH_ALERT_PUBLIC_URL`(공개 URL) · `HEALTH_ALERT_REPOSITORY`(owner/repo) · `GH_DISPATCH_TOKEN`. 스크립트는 `.env`를 셸로 실행하지 않고 이 3개 줄만 문자 그대로 읽는다.
- 상태 파일: 배포 디렉터리의 `.health-alert.state`(직전 실행 시각 · 권한 600). 지우면 다음 실행이 최근 15분을 본다.
- 종료 코드: 0 정상 · 1 이상(알림 전송) · 2 설정 오류 또는 전송 실패.
- cron 등록 예시(`DEPLOY`는 배포 디렉터리 · 15분 간격 — 첫 실행이 보는 최근 15분과 같은 폭):

  ```
  */15 * * * * HEALTH_ALERT_DEPLOY_DIR=$DEPLOY bash $DEPLOY/docker/ops/health-alert.sh >> $DEPLOY/.health-alert.log 2>&1
  ```

  crontab에는 `$DEPLOY`를 실제 경로로 바꿔 적는다(cron은 로그인 셸 변수를 읽지 않는다).
- 수동 확인: `HEALTH_ALERT_DEPLOY_DIR=$DEPLOY DRY_RUN=1 bash $DEPLOY/docker/ops/health-alert.sh` — 이상이 있으면 보낼 페이로드를 출력만 한다(전송·상태 파일 갱신 없음). 정상이면 아무것도 출력하지 않고 0으로 끝난다.

**토큰(`GH_DISPATCH_TOKEN`)**

- GitHub → Settings → Developer settings → Fine-grained tokens. Repository access는 **이 저장소만**, 권한은 `Contents: Read and write` 하나(repository_dispatch 호출에 필요한 최소 권한 · Metadata 읽기는 자동 포함).
- 실패 알림 메일은 실행을 일으킨 계정(토큰 소유자)에게 간다. 그 계정의 GitHub → Settings → Notifications → Actions에서 실패 알림(Email)이 켜져 있어야 한다.
- **한계: 토큰이 만료되거나 폐기되면 이상이 생겨도 알림이 가지 않는다.** 스크립트는 전송 실패를 종료 코드 2와 cron 로그(`알림 전송 실패: HTTP 401` 등)로만 남긴다. 토큰 만료일을 기록해 두고 만료 전에 새 토큰으로 서버 `.env` 값을 바꾼다(GitHub도 만료 전 메일로 알린다).

### 6-2. GitHub 예약 점검 (보조 · best-effort)

- 워크플로: `.github/workflows/health-watch.yml` — 15분마다(예약) 또는 Actions 화면의 `Run workflow`로 실행한다.
- 동작: 운영 메인 페이지(`/`)와 `/api/v1/categories`를 GET으로 확인한다. 2xx가 아니면(연결 실패·타임아웃 포함) 30초 뒤 1회 재시도하고, 그래도 아니면 job이 실패한다. 공개 GET만 쓰므로 시크릿은 없다.
- 역할: 서버 자체가 멈추면 6-1 cron도 멈추므로, 외부에서 본 도달성만 이 경로가 맡는다.
- 알림 경로: job 실패 시 GitHub Actions 실패 알림 메일로 받는다. 예약 실행의 알림은 워크플로를 마지막으로 수정한 계정에게 가므로, 그 계정의 GitHub → Settings → Notifications → Actions에서 실패 알림(Email)이 켜져 있어야 한다.
- 예약 지연: GitHub 예약 실행은 best-effort라 부하에 따라 수 분 늦게 시작되거나 건너뛰어질 수 있다(대부분의 슬롯이 건너뛰어진 기간도 있었다). 감지 시각은 정확한 15분 간격이 아니고, 실행 자체가 없으면 실패 메일도 없다.
- 60일 제약: 공개 저장소는 60일간 저장소 활동(커밋 등)이 없으면 예약 워크플로가 자동 비활성된다. 비활성되면 Actions → `Health Watch` → `Enable workflow`로 다시 켠다(비활성 예정 시 GitHub가 메일로 미리 알린다).

## 7. 운영 .env에 없는 키의 실효값

운영 `.env`에도 아래 키를 두고, 값은 `docker-compose.mall.yml`의 기본값(`${KEY:-기본값}`)과 같게 둔다(D-270). 값을 바꿀 때만 서버 `.env`의 값을 바꾼다.

| 키 | compose 기본값 | 의미 |
|---|---|---|
| `BACKEND_IMAGE_TAG` | `latest` | 백엔드 이미지 태그 — 최신 배포본(롤백 때만 `sha-<7자>` 지정 · §3) |
| `FRONTEND_IMAGE_TAG` | `latest` | 프론트엔드 이미지 태그 — 위와 같음 |
| `BACKEND_DOCKERFILE` | `Dockerfile` | 로컬 빌드용 Dockerfile 선택. 운영은 pull만 하므로(`--no-build`) 실효 없음 |
| `FRONTEND_DOCKERFILE` | `Dockerfile` | 로컬 빌드용 Dockerfile 선택. 운영은 pull만 하므로(`--no-build`) 실효 없음 |
| `PAYMENT_GATEWAY` | `mock` | 결제 구현체 — 모의 결제(외부 호출 없음) |
| `SMS_SENDER` | `mock` | SMS 구현체 — 모의 발송 |
| `EMAIL_SENDER` | `mock` | 메일 구현체 — 모의 발송(구매자 비밀번호 재설정도 닫힘·`smtp`로 바꾸면 `SMTP_*` 필요 · D-269) |
| `APP_DOMAIN` | `zslab-mall.duckdns.org` | 메일 링크(비밀번호 재설정)의 프런트 도메인 — 백엔드 `zslab.frontend.base-url = https://APP_DOMAIN` (D-269) |
| `DELIVERY_TRACKER` | `mock` | 배송 조회 구현체 — 택배사 API 호출 없음 |
| `MOCK_DELIVERY_DAYS` | `2` | 모의 배송 조회가 발송 후 며칠이면 배달 완료로 볼지 |
| `DELIVERY_AUTO_COMPLETE_ENABLED` | `true` | 자동 배송완료 스케줄러 켬 |
| `RECONCILIATION_CHECK_ENABLED` | `true` | 하루 1회 주문·결제 불일치 점검 스케줄러 켬 |
| `LLM_PROVIDER` | `mock` | 리뷰 요약 언어 모델 — 규칙·템플릿 요약(외부 호출 없음) |
| `ES_HOST` | `zslab_elasticsearch` | filebeat 로그 전송 대상 호스트 |
| `ES_PORT` | `9200` | filebeat 로그 전송 대상 포트 |
| `NUXT_API_INTERNAL_BASE` | `http://mall-backend:8080` | SSR이 백엔드를 직접 부르는 내부 주소(gateway_net 별칭) |
| `NUXT_PUBLIC_API_BASE` | (빈 값) | 브라우저 API 베이스 — 비면 동일 Origin 상대경로 `/api` |

## 8. 환경 변수 동기화 (D-270)

- 모든 환경의 `.env`와 `.env.example`은 키 집합이 같아야 한다(값은 환경별).
- 키를 추가·폐기할 때 `.env.example`과 각 환경 `.env`를 함께 고친다.
- 확인: `sh docker/env/check-env-keys.sh .env .env.example`(PC는 `docker/env/check-env-keys.ps1`) — 일치 0 · 불일치 1 · 파일 없음 2, 키 이름만 출력한다.
- 배포 때 키가 일치하지 않으면 Actions에 경고가 나고, 배포는 계속된다(`deploy.yml`).
- 서버 source 범위: `sparse-checkout` cone 모드 · `backend`·`docker`·`frontend` + 루트 파일(`.env.example` 포함).
