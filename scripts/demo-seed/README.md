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
| `API_TLS_VERIFY` | 선택. `false`면 TLS 검증 생략(로컬 자체 서명 인증서 전용 · `--target prod`면 거부) |
| `DEMO_BUYER_PASSWORD` | 선택. 공개 데모 구매자 `buyer01@demo.zslab-mall.com` 비밀번호(미지정 시 랜덤·state 파일 기록) |

## 실행
`--target local|prod`는 필수다(기본값 없음 · D-242). `--dry-run`도 env 전부·관리자 로그인·DB 연결이 필요하다(교차 검증을 읽기로 수행).
```
python scripts/demo-seed/seed.py --target local --dry-run                # 대상 가드·교차 검증 후 계획만 출력(쓰기 0)
python scripts/demo-seed/seed.py --target local --step all               # master → orders → timeshift → settlement → reviews → verify
python scripts/demo-seed/seed.py --target local --step orders            # 단계별 실행(state 파일로 재개)
python scripts/demo-seed/seed.py --target local --step verify            # 검증만
```
- `--force`: 재실행 가드 무시(local 전용). 가드 = 데모 마커(`@demo.zslab-mall.com` 계정 · `데모 ` 상호 · `DEMO-` variantCode) 존재 시 중단.
- `--seed N`: 난수 시드(기본 20260918).
- state: `scripts/demo-seed/state/seed-state.{local,prod}.json`(gitignore) — 대상별 분리. 단계 결과·public id·비밀번호가 기록되므로 외부 공유 금지.
  `orders`는 주문 단위로 저장돼 중단 시 이어서 실행된다. `timeshift`는 `time_shifted=true`면 재실행 거부(이중 보정 방지).

## 대상 가드 (D-242 · 모든 쓰기보다 먼저)
- local: `API_BASE_URL` 호스트와 `DB_HOST`가 전부 loopback·사설 대역으로 해석될 때만 진행. 이 PC는 hosts로 운영 도메인을 127.0.0.1에 두므로
  hosts 줄이 빠지면 거부된다.
- prod: 순서대로 거부 — API 호스트 해석 결과 중 하나라도 loopback·사설 대역(IPv6 ULA 포함) → `API_BASE_URL`이 https가 아님 →
  `API_TLS_VERIFY=false` → `--force` → 허용 단계 밖. 통과하면 API 호스트 도메인을 터미널에서 직접 입력해 확인한다(파이프 입력·생략 옵션 없음).
- prod 허용 단계: `verify`만(허용 목록 방식 · 운영은 추가분만 쓴다). master·orders·timeshift·settlement·reviews·all은 거부되고,
  새 단계는 목록(`PROD_ALLOWED_STEPS`)에 넣을 때만 열린다.
- 교차 검증: 관리자 로그인 → `GET /api/v1/admin/me`의 본인(userPublicId·email) 행이 지정 DB에 없으면 API와 DB가 다른 환경으로 보고 중단.
- state: 파일에 대상(`name·apiHost·dbHost·dbName`)을 기록하고 현재 대상과 다르면 거부. 대상 기록이 없는 state는 local만 현재 대상으로 받아들이고
  prod는 거부. `--dry-run`은 state를 쓰지 않는다.
- 기존 `state/seed-state.json`(대상 미기록)이 있으면 자동 이전 없이 중단한다 — local 실행분이면 `seed-state.local.json`으로 이름을 바꾼다.
- HTTP 클라이언트는 환경·시스템 프록시를 쓰지 않는다(DNS 판정과 실제 접속 대상 일치).

## 운영(prod) 실행 절차
1. 이 PC hosts의 `127.0.0.1 zslab-mall.duckdns.org` 줄을 주석 처리하고 DNS 캐시를 비운다(`ipconfig /flushdns`) — 그대로 두면 prod 가드가 거부한다.
2. SSH 터널로 운영 DB를 연다(예 `ssh -N -L 3307:127.0.0.1:3306 <운영 서버>` → `DB_HOST=127.0.0.1` · `DB_PORT=3307`).
   실행 전 그 포트가 운영 DB인지 확인한다(예 `mariadb -h 127.0.0.1 -P 3307 -e "SELECT @@hostname"`이 운영 서버 이름) — 로컬 DB 컨테이너도 127.0.0.1에 있다.
3. `API_BASE_URL=https://zslab-mall.duckdns.org`(https 필수) · `API_TLS_VERIFY` 미설정 · 운영 관리자·DB 자격을 env로 지정한다.
4. 허용 단계만 명시해 실행한다. 실행 직전 도메인 입력 프롬프트에 `zslab-mall.duckdns.org`를 직접 입력한다.
```
python scripts/demo-seed/seed.py --target prod --step verify --dry-run   # 가드·교차 검증만
python scripts/demo-seed/seed.py --target prod --step verify
```
- 금지: 허용 목록 밖 단계(master·orders·timeshift·settlement·reviews·all — settlement는 정산 생성 API가 전 셀러 대상이라 비데모 셀러 정산까지 PENDING 생성) · `--force`.
5. 끝나면 hosts 줄을 복구하고 터널을 닫는다.

## 테스트
```
python -m unittest discover -s scripts/demo-seed -p "test_*.py"
```
표준 라이브러리 unittest만 쓴다(설치 불요 · 실제 DNS·HTTP·DB 접속 없음).

## 단계
1. `master` — 카테고리(기존 '데모' 활용 + 5) · 구매자 10·셀러 owner 3 가입 · 관리자 입점(ACTIVE) · 계좌 등록 API `POST /api/v1/admin/sellers/{slr}/bank-accounts`(Track 89-F·계좌번호 AES 암호화 저장·raw INSERT 금지) · PIL 이미지 생성→업로드→상품 30 등록·이미지 연결·승인
2. `orders` — 주문 생성 → 구매자 본인 mock 콜백 SUCCESS(`POST /api/v1/payments/mock-callback`·결제시각은 timeshift가 SQL로 보정) → ADMIN 송장·배송완료 → BUYER 구매확정 / 클레임(취소·반품·교환) 완결 · 9월 진행분(결제완료 8·배송중 8·배송완료 9·진행 클레임 3)
3. `timeshift` — 데모 마커 행만 시각 UPDATE(order·payment·order_item·delivery·claim·refund + 마스터 행) · order_no 날짜부 갱신 · 순서 불변식 검증
4. `settlement` — 3~8월 정산 생성 → 3~7월 확정 → 3~6월 지급 → 지급 paid_at = 지급예정일 +0~3일(SQL)
5. `reviews` — 카테고리별 리뷰 키워드 세트 INSERT(키워드 쓰기 API 없음 · code가 있으면 건너뜀) → 클레임 없는 구매확정 품목 약 70%에 구매자 API로 리뷰 작성(별점 분산 · 일부 PIL 사진 1~3장을 1장씩 업로드) · 도움됐어요 → 작성 시각을 구매확정 뒤로 SQL 보정. 가드 = 데모 구매자 리뷰 존재
6. `verify` — settlement_item 합 = gross/fee, occurred_at = confirmed_at, 월별 주문·클레임 집계, 시각 불변식 재검증

## 운영 실행 전
- `mariadb-dump --single-transaction` 백업 + `mall_uploads` 볼륨 스냅샷
- 롤백은 dump 복원 또는 state 파일 id 기준 FK 역순 DELETE(plan.md §5). TRUNCATE·DROP 금지
- 결제 PENDING TTL 30분·JWT 1시간 — 스크립트가 단계마다 재로그인하고 체크아웃 직후 mock 콜백을 호출한다(웹훅 경로 `/api/webhooks/**`는 호출 0·운영 gateway 차단 LT-29)
