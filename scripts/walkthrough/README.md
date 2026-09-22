# 워크스루 도구 (Track 98)

운영 업무 시나리오를 끝까지 실행하며 **클릭·입력·화면 이동 수를 재고**(개선 라운드용), 단계마다 **스크린샷을 남긴다**(매뉴얼용).
로컬 전용이며 CI에 포함되지 않는다(`.github/workflows/frontend-ci.yml`은 Playwright를 돌리지 않는다).

## 구성

| 위치 | 역할 |
|---|---|
| `scripts/walkthrough/prepare.py` | 데모 시드에 없는 시나리오 데이터를 API로 보충(멱등) + 시나리오별 데이터 충족 표 출력 |
| `scripts/walkthrough/dump.py` | 로컬 DB를 기준 상태로 덤프 |
| `scripts/walkthrough/restore.py` | 덤프로 로컬 DB 복원 + 백엔드 헬스 확인 |
| `frontend/playwright.walkthrough.config.ts` | 워크스루 전용 Playwright 설정(testDir `walkthrough`·workers 1·retries 0·trace on·뷰포트 1440×900) |
| `frontend/walkthrough/helpers/walkthrough.ts` | 계측 래퍼·스크린샷·시나리오별 `metrics.json` |
| `frontend/walkthrough/helpers/summary.ts` | globalTeardown — `summary.json`·`summary.md` 생성 |
| `frontend/walkthrough/*.walkthrough.ts` | 시나리오 7개(관리자 3·셀러 2·구매자 2) |

## 절차

### 1. 기준 상태 준비 (최초 1회, 또는 시드 재생성 후)

```
python scripts/walkthrough/prepare.py
python scripts/walkthrough/dump.py
```

`prepare.py`는 부족한 데이터만 만든다(이미 충족이면 아무것도 만들지 않는다). 출력 표의 판정이 전부 `OK`여야 한다.
`dump.py` 산출물은 `frontend/playwright-report/walkthrough-db/baseline.sql`(gitignored).

### 2. 실행 (매번 복원 → 실행)

```
python scripts/walkthrough/restore.py --yes
docker exec zslab_mall_frontend sh -c 'export ADMIN_E2E_EMAIL=$NUXT_ADMIN_DEMO_EMAIL ADMIN_E2E_PASSWORD=$NUXT_ADMIN_DEMO_PASSWORD SELLER_E2E_EMAIL=$NUXT_SELLER_DEMO_EMAIL SELLER_E2E_PASSWORD=$NUXT_SELLER_DEMO_PASSWORD BUYER_E2E_EMAIL=$NUXT_BUYER_DEMO_EMAIL BUYER_E2E_PASSWORD=$NUXT_BUYER_DEMO_PASSWORD; cd /app && npm run walkthrough'
```

- **복원을 먼저 한다.** 시나리오는 실제로 상태를 전이시키므로(클레임 승인·정산 지급·셀러 승인·출고·반품 신청) 복원 없이 다시 실행하면 데이터가 없어 실패한다.
- 컨테이너 안에서 실행해야 SSR이 백엔드(`mall-backend:8080`·호스트 미노출)에 닿는다. dev 서버(:3000)가 떠 있어야 한다.
- 역할 자격증명은 `*_E2E_*` 이름으로만 읽으므로 컨테이너의 `NUXT_*_DEMO_*`를 매핑해 넘긴다(LT-37). 미주입 시 전 시나리오가 skip된다.

### 3. 산출물

```
frontend/playwright-report/walkthrough/
  summary.md · summary.json          # 시나리오별 클릭·입력·화면 이동 요약
  {역할}/{시나리오}/NN-단계.png        # 매뉴얼용 스크린샷(호출 순서 번호)
  {역할}/{시나리오}/metrics.json       # 시나리오 단건 계측
```

`playwright-report/`는 `frontend/.gitignore`로 커밋되지 않는다.

## 계측 정의

| 지표 | 정의 |
|---|---|
| 클릭 | `Walkthrough.click`·`check` 호출 수. 버튼·메뉴 항목·링크·행 클릭 1회 = 1 |
| 입력 | `Walkthrough.fill`·`selectOption`·`select` 호출 수 = **채운 필드 수**. Vuetify select는 내부적으로 2번 클릭하지만 필드 1개로 센다 |
| 화면 이동 | URL **pathname**이 바뀐 횟수(`framenavigated`·메인 프레임). 진입 화면(첫 로드)은 세지 않고, query만 바뀌는 필터·탭 조작도 이동으로 보지 않는다 |

래퍼를 거치지 않은 조작은 세지 않는다 — 시나리오는 반드시 `Walkthrough` 래퍼만 쓴다.

## 시간 기반 배치 간섭

배송완료 후 7일이 지나면 `OrderAutoConfirmScheduler`(1시간 주기)가 품목을 자동 구매확정하고, 미결제 30분이 지나면
`OrderAutoCancelScheduler`(5분 주기)가 주문을 취소한다. 기준 상태를 오래 재사용하면 복원 직후 배치가 데이터를 바꿀 수 있다.
기본은 차단하지 않으며(운영과 같은 조건), 필요하면 백엔드 컨테이너 환경변수로 끈 뒤 기동한다.

```
zslab.order.auto-confirm.enabled=false
zslab.order.auto-cancel.enabled=false
```

(대응하는 env는 `docker-compose.mall.yml` backend `environment`에 추가해야 전달된다. 끈 상태로 운영 시나리오를 재면
"자동 확정이 있는 현실"과 달라지므로, 되도록 `prepare.py`로 기준 상태를 새로 만드는 쪽을 쓴다.)

## 시나리오

| 역할 | 파일 | 흐름 | 완료 조건 |
|---|---|---|---|
| 관리자 | `admin-claim-cancel-approve` | 대시보드 클레임 대기 → 취소 탭 → 승인 | 승인 건이 요청 목록에서 사라짐 |
| 관리자 | `admin-settlement-confirm-pay` | 대시보드 정산 대기 → 상세 → 정상처리 → 지급완료 | 상태 칩 `지급완료` |
| 관리자 | `admin-seller-approve` | 대시보드 셀러 승인 대기 → 상세 → 활성 전이(사유 입력) | 상태 칩 `활성` |
| 셀러 | `seller-order-ship` | 대시보드 배송 대기 → 주문 목록 → 출고(택배사·송장) | 해당 주문이 배송 대기 목록에서 빠짐 |
| 셀러 | `seller-product-stop-resume` | 상품 목록 → 판매중지 → 재판매 | 상태 칩 `판매중` 복귀 |
| 구매자 | `buyer-claim-return-request` | 주문 목록 → 상세 → 반품 요청 → 사유 입력 → 제출 | `클레임이 접수되었습니다.` |
| 구매자 | `buyer-order-confirm` | 주문 목록 → 상세 → 구매확정 → 확인 | `구매확정이 완료되었습니다.` |

구매자 두 시나리오는 **서로 다른 배송완료 주문**을 쓴다(반품은 첫 번째, 구매확정은 마지막). `prepare.py`가 배송완료 주문 2건을 보장한다.

## 주의

- `restore.py`는 덤프의 `DROP TABLE`/`CREATE TABLE`을 실행해 로컬 DB를 덤프 시점으로 되돌린다.
  `SPRING_PROFILES_ACTIVE=local`이 아니면 거부하며 `--yes` 없이는 실행되지 않는다(CLAUDE.md 운영 데이터 보호 규칙).
  운영 DB는 원격 도커 데몬이라 이 스크립트의 `docker exec` 대상이 아니다.
- DB 접속 정보는 `.env`에서 읽어 `MYSQL_PWD` 환경변수로만 넘긴다(argv·로그 노출 없음).
- 스크린샷 마스킹(날짜·주문번호 등 가변 값)은 매뉴얼 단계에서 결정한다(현재 미적용).
