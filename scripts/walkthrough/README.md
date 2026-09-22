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
| `frontend/walkthrough/*.walkthrough.ts` | 시나리오 18개(관리자 8·셀러 6·구매자 4) |

## 절차

### 1. 기준 상태 준비 (최초 1회, 또는 시드 재생성 후)

```
python scripts/walkthrough/prepare.py
python scripts/walkthrough/dump.py
```

`prepare.py`는 부족한 데이터만 만든다(이미 충족이면 아무것도 만들지 않는다). 출력 표의 판정이 전부 `OK`여야 한다.
`dump.py` 산출물은 `frontend/playwright-report/walkthrough-db/baseline.sql`(gitignored).

### 2. 실행 (매번 복원 → 실행)

> 선행: **자동 배송완료 끄기**(아래 절). 켜 둔 채 돌리면 배송중 전제 시나리오 4개가 깨진다.

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
| 구간 | `Walkthrough.segment(label, role)`로 연 구간의 클릭·입력·이동. 합계는 구간과 무관하게 시나리오 전체다 |
| 관찰값 | `Walkthrough.note(key, value)`. assert하지 않고 "무엇이 보였는지·어느 행을 썼는지"만 기록한다 |

### 역할 전환 · 다건 집계

**역할 전환 시나리오**(`admin-claim-return-inspect`·`admin-claim-exchange-full`)는 한 파일 안에서 `loginAs`를 역할별로 다시 호출한다.
역할 쿠키는 path가 `/`·`/admin`·`/seller`로 갈려 한 브라우저 컨텍스트에 함께 있어도 충돌하지 않는다. 계측은 `segment('구매자', 'buyer')`처럼
역할 구간으로 나누고 `summary.md`의 **역할 · 구간별** 표가 역할별 행으로 보여 준다.

**다건 시나리오**(`seller-order-ship-multi`)는 `segment('1건째')`·`2건째`·`3건째`로 나눠 1건째(대시보드 → 목록 진입 포함)와
2·3건째(목록에서 반복하는 비용만)를 비교할 수 있게 한다. 합계 행은 건별 합이다.

래퍼를 거치지 않은 조작은 세지 않는다 — 시나리오는 반드시 `Walkthrough` 래퍼만 쓴다.

## 자동 배송완료 끄기 (필수)

**워크스루를 돌리기 전에 자동 배송완료를 꺼야 한다.** `DeliveryAutoCompleteScheduler`(1시간 주기·Track 99 D-210)가 배송 조회 결과에 따라
배송중을 배송완료로 바꾸는데, Mock 조회는 발송 후 `MOCK_DELIVERY_DAYS`(기본 2일)면 배달 완료로 보므로 기준 상태의 배송중 배송이
복원 직후 사라질 수 있다. 배송중을 전제하는 시나리오는 4개다 — `admin-delivery-complete`·`admin-delivery-tracking-fix`·
`seller-delivery-tracking-fix`·`buyer-order-tracking`(송장 정정은 배송중에서만 가능하다).

`.env`에 아래를 두고 백엔드 컨테이너를 **재생성**한다(`docker compose … up -d` — `docker restart`는 environment를 다시 읽지 않는다).

```
DELIVERY_AUTO_COMPLETE_ENABLED=false
```

확인: `docker logs zslab_mall_backend | grep DeliveryAutoComplete` 가 비어 있으면 스케줄러가 뜨지 않은 것이다.
다시 켤 때는 값을 `true`로 되돌리고 같은 방식으로 재생성한다.

## 시간 기반 배치 간섭

배송완료 후 7일이 지나면 `OrderAutoConfirmScheduler`(1시간 주기)가 품목을 자동 구매확정하고, 미결제 30분이 지나면
`OrderAutoCancelScheduler`(5분 주기)가 주문을 취소한다. 기준 상태를 오래 재사용하면 복원 직후 배치가 데이터를 바꿀 수 있다.
기본은 차단하지 않으며(운영과 같은 조건), 필요하면 백엔드 컨테이너 환경변수로 끈 뒤 기동한다.

```
zslab.order.auto-confirm.enabled=false
zslab.order.auto-cancel.enabled=false
```

(이 둘은 자동 배송완료와 달리 `docker-compose.mall.yml` backend `environment`에 대응 env가 없어 직접 추가해야 전달된다.
끈 상태로 운영 시나리오를 재면 "자동 확정이 있는 현실"과 달라지므로, 되도록 `prepare.py`로 기준 상태를 새로 만드는 쪽을 쓴다.)

## 시나리오

| 역할 | 파일 | 흐름 | 완료 조건 |
|---|---|---|---|
| 관리자 | `admin-claim-cancel-approve` | 대시보드 클레임 대기 → 취소 탭 → 승인 | 승인 건이 요청 목록에서 사라짐 |
| 관리자 | `admin-claim-cancel-reject` | 대시보드 클레임 요청 → 취소 탭 → 거부(사유 코드·메모) | 거부 건이 요청 목록에서 사라짐 |
| 관리자 | `admin-claim-return-inspect` | **[구매자]** 회수 송장 등록 → **[관리자]** 대시보드 클레임 처리 대기 → 검수 합격·재입고 | 검수 건이 처리 대기 목록에서 사라짐 |
| 관리자 | `admin-claim-exchange-full` | **[관리자]** 교환 승인 → **[구매자]** 회수 송장 → **[관리자]** 검수 → 교환품 발송 → 배송완료 | 교환 건이 처리 대기 목록에서 사라짐 |
| 관리자 | `admin-delivery-complete` | 사이드바 전체 주문 → 배송상태 필터 → 행 메뉴 → 배송완료 처리 | 해당 주문이 배송중 목록에서 빠짐 |
| 관리자 | `admin-delivery-tracking-fix` | 사이드바 배송 관리 → 배송상태 필터 → 상세 → 송장 정정 | 목록 행 송장번호가 새 값 |
| 관리자 | `admin-settlement-confirm-pay` | 대시보드 정산 대기 → 상세 → 정상처리 → 지급완료 | 상태 칩 `지급완료` |
| 관리자 | `admin-seller-approve` | 대시보드 셀러 승인 대기 → 상세 → 활성 전이(사유 입력) | 상태 칩 `활성` |
| 셀러 | `seller-order-ship` | 대시보드 배송 대기 → 주문 목록 → 출고(택배사·송장) | 해당 주문이 배송 대기 목록에서 빠짐 |
| 셀러 | `seller-order-ship-multi` | 대시보드 배송 대기 → 주문 목록 → 출고 3건 연속(건별 구간 계측) | 3건 모두 배송 대기 목록에서 빠짐 |
| 셀러 | `seller-delivery-tracking-fix` | 사이드바 배송 → 배송상태 필터 → 행 메뉴 → 송장 정정 | 목록 행 송장번호가 새 값 |
| 셀러 | `seller-inventory-inbound` | 대시보드 재고 임박 → 재고 목록 → 입고(수량·사유) | 보유 수량이 입고 수량만큼 증가 |
| 셀러 | `seller-product-price-edit` | 사이드바 상품 → 수정 → 판매가 변경 → 저장 | `상품을 저장했습니다.` 토스트 |
| 셀러 | `seller-product-stop-resume` | 상품 목록 → 판매중지 → 재판매 | 상태 칩 `판매중` 복귀 |
| 구매자 | `buyer-claim-return-request` | 주문 목록 → 상세 → 반품 요청 → 사유 입력 → 제출 | `클레임이 접수되었습니다.` |
| 구매자 | `buyer-order-cancel-request` | 주문 목록 → 결제완료 상세 → 취소 요청 → 사유 입력 → 제출 | `클레임이 접수되었습니다.` |
| 구매자 | `buyer-order-confirm` | 주문 목록 → 상세 → 구매확정 → 확인 | `구매확정이 완료되었습니다.` |
| 구매자 | `buyer-order-tracking` | 주문 목록 → 배송중 상세 → 배송 정보 확인 | 주문 상세 도달(배송 정보 노출 여부는 `note`로만 기록) |

### 시나리오 간 데이터 분리

한 번 복원한 뒤 18개를 알파벳 순서로 이어서 실행하므로 시나리오끼리 같은 행을 쓰지 않도록 배정한다. `prepare.py`의 충족 표가 필요 수량을 강제한다.

- 구매자 배송완료 주문 2건 — 반품 신청은 첫 번째, 구매확정은 마지막.
- 취소 요청 클레임 2건 — 승인 시나리오가 먼저 1건을 소비하고 거부 시나리오가 나머지를 쓴다.
- 셀러 배송 대기 품목 4건 — 다건 출고(3) → 단건 출고(1) 순서로 소비한다(`-multi`가 알파벳상 먼저다).
- 셀러 판매중 상품 — 가격 수정은 **마지막 행**, 판매중지→재판매는 **첫 행**.
- 배송중 배송 — 관리자 배송완료 처리는 데모 구매자 주문을 제외한 행, 관리자 송장 정정은 목록 **마지막 행**, 셀러 송장 정정은 셀러 본인 목록의 **첫 행**.
  실제로 고른 주문번호는 `note`로 남으므로 `summary.md`의 **관찰값** 절에서 중복 여부를 확인할 수 있다.
- 데모 구매자 주문 목록을 시나리오용 상태로만 유지하려고, 셀러 출고분·취소 클레임분은 전용 계정(`walkthrough-buyer@demo.zslab-mall.com`)으로 주문한다.

## 주의

- `restore.py`는 덤프의 `DROP TABLE`/`CREATE TABLE`을 실행해 로컬 DB를 덤프 시점으로 되돌린다.
  `SPRING_PROFILES_ACTIVE=local`이 아니면 거부하며 `--yes` 없이는 실행되지 않는다(CLAUDE.md 운영 데이터 보호 규칙).
  운영 DB는 원격 도커 데몬이라 이 스크립트의 `docker exec` 대상이 아니다.
- DB 접속 정보는 `.env`에서 읽어 `MYSQL_PWD` 환경변수로만 넘긴다(argv·로그 노출 없음).
- 스크린샷 마스킹(날짜·주문번호 등 가변 값)은 매뉴얼 단계에서 결정한다(현재 미적용).
