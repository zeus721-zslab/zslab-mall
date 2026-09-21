# 데모 시드 스크립트

설계: `docs/infra/demo-seed/plan.md`(로컬 전용). 셀러 3·상품 30·구매자 10·주문 3~8월 120건 + 9월 진행분 25건·클레임 18건·정산 3~8월을
API 우선으로 생성하고, 시각만 SQL로 과거로 옮긴 뒤 정산을 생성한다.

## 준비
```
python -m pip install -r scripts/demo-seed/requirements.txt
```

## 환경변수 (기본값 없음 · 전부 필수)
| 변수 | 설명 |
|---|---|
| `API_BASE_URL` | 백엔드 base URL (예 `https://zslab-mall.duckdns.org`) |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | SUPER_ADMIN 로그인 자격 |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | MariaDB 접속(운영은 SSH 터널 경유) |
| `API_TLS_VERIFY` | 선택. `false`면 TLS 검증 생략(로컬 자체 서명 인증서 전용·운영 금지) |
| `DEMO_BUYER_PASSWORD` | 선택. 공개 데모 구매자 `buyer01@demo.zslab-mall.com` 비밀번호(미지정 시 랜덤·state 파일 기록) |

## 실행
```
python scripts/demo-seed/seed.py --dry-run                 # 계획만 출력
python scripts/demo-seed/seed.py --step all                # master → orders → timeshift → settlement → verify
python scripts/demo-seed/seed.py --step orders             # 단계별 실행(state 파일로 재개)
python scripts/demo-seed/seed.py --step verify             # 검증만
```
- `--force`: 재실행 가드 무시. 가드 = 데모 마커(`@demo.zslab-mall.com` 계정 · `데모 ` 상호 · `DEMO-` variantCode) 존재 시 중단.
- `--seed N`: 난수 시드(기본 20260918).
- state: `scripts/demo-seed/state/seed-state.json`(gitignore). 단계 결과·public id·비밀번호가 기록되므로 외부 공유 금지.
  `orders`는 주문 단위로 저장돼 중단 시 이어서 실행된다. `timeshift`는 `time_shifted=true`면 재실행 거부(이중 보정 방지).

## 단계
1. `master` — 카테고리(기존 '데모' 활용 + 5) · 구매자 10·셀러 owner 3 가입 · 관리자 입점(ACTIVE) · 계좌 등록 API `POST /api/v1/admin/sellers/{slr}/bank-accounts`(Track 89-F·계좌번호 AES 암호화 저장·raw INSERT 금지) · PIL 이미지 생성→업로드→상품 30 등록·이미지 연결·승인
2. `orders` — 주문 생성 → 구매자 본인 mock 콜백 SUCCESS(`POST /api/v1/payments/mock-callback`·결제시각은 timeshift가 SQL로 보정) → ADMIN 송장·배송완료 → BUYER 구매확정 / 클레임(취소·반품·교환) 완결 · 9월 진행분(결제완료 8·배송중 8·배송완료 9·진행 클레임 3)
3. `timeshift` — 데모 마커 행만 시각 UPDATE(order·payment·order_item·delivery·claim·refund + 마스터 행) · order_no 날짜부 갱신 · 순서 불변식 검증
4. `settlement` — 3~8월 정산 생성 → 3~7월 확정 → 3~6월 지급 → 지급 paid_at = 지급예정일 +0~3일(SQL)
5. `verify` — settlement_item 합 = gross/fee, occurred_at = confirmed_at, 월별 주문·클레임 집계, 시각 불변식 재검증

## 운영 실행 전
- `mariadb-dump --single-transaction` 백업 + `mall_uploads` 볼륨 스냅샷
- 롤백은 dump 복원 또는 state 파일 id 기준 FK 역순 DELETE(plan.md §5). TRUNCATE·DROP 금지
- 결제 PENDING TTL 30분·JWT 1시간 — 스크립트가 단계마다 재로그인하고 체크아웃 직후 mock 콜백을 호출한다(웹훅 경로 `/api/webhooks/**`는 호출 0·운영 gateway 차단 LT-29)
