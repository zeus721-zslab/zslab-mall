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
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | MariaDB 접속(운영은 서버 래퍼가 `zslab_mariadb:3306`으로 지정 · 아래 운영 실행 절차) |
| `API_TLS_VERIFY` | 선택. `false`면 TLS 검증 생략(로컬 자체 서명 인증서 전용 · `--target prod`면 거부) |
| `DEMO_BUYER_EMAIL` | 공개 데모 구매자(프론트 데모 버튼 계정) = `.env`의 `NUXT_BUYER_DEMO_EMAIL`. delivered·inquiries·qna(`all` 포함) 필수 · verify [106]은 미설정 시 미판정 |
| `DEMO_BUYER_PASSWORD` | 106 단계: `DEMO_BUYER_EMAIL` 계정 비밀번호(필수) · master: 시드 구매자 `buyer01@demo.zslab-mall.com` 비밀번호(선택 · 미지정 시 랜덤·state 파일 기록) |
| `DEMO_SELLER_EMAIL` / `DEMO_SELLER_PASSWORD` | 데모 셀러(seller02 · 데모 패션랩) 로그인 자격 — `.env`의 `NUXT_SELLER_DEMO_*`와 같은 계정. qna(`all` 포함)만 필수 · 누락 시 exit 2 |

## 실행
`--target local|prod`는 필수다(기본값 없음 · D-242). `--dry-run`도 env 전부·관리자 로그인·DB 연결이 필요하다(교차 검증을 읽기로 수행).
```
python scripts/demo-seed/seed.py --target local --dry-run                # 대상 가드·교차 검증 후 계획만 출력(쓰기 0)
python scripts/demo-seed/seed.py --target local --step all               # master → orders → timeshift → settlement → reviews → delivered → inquiries → qna → verify
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
- prod 허용 단계: `verify`·`delivered`·`inquiries`·`qna`(허용 목록 방식 · 운영은 추가분만 쓴다). master·orders·timeshift·settlement·reviews·all은
  거부되고, 새 단계는 목록(`PROD_ALLOWED_STEPS`)에 넣을 때만 열린다.
- 교차 검증: 관리자 로그인 → `GET /api/v1/admin/me`의 본인(userPublicId·email) 행이 지정 DB에 없으면 API와 DB가 다른 환경으로 보고 중단.
- state: 파일에 대상(`name·apiHost·dbHost·dbName`)을 기록하고 현재 대상과 다르면 거부. 대상 기록이 없는 state는 local만 현재 대상으로 받아들이고
  prod는 거부. `--dry-run`은 state를 쓰지 않는다.
- 기존 `state/seed-state.json`(대상 미기록)이 있으면 자동 이전 없이 중단한다 — local 실행분이면 `seed-state.local.json`으로 이름을 바꾼다.
- HTTP 클라이언트는 환경·시스템 프록시를 쓰지 않는다(DNS 판정과 실제 접속 대상 일치).

## 운영(prod) 실행 절차 (운영 확인 2026-10-01)
운영 서버에서 서버 전용 래퍼 `~/demo-seed/run.sh`(저장소 미포함)로 실행한다.
- 인자: `verify` | `delivered` | `inquiries` | `qna`(`verify --dry-run` 허용) — 항상 `--target prod`로 실행한다.
- 동작: `docker run --rm --network infra_net python:3.12-slim`에 `~/demo-seed`를 마운트하고 requirements 설치 후 seed.py를 실행한다.
  DB는 도커 네트워크 안 이름 `zslab_mariadb:3306`, API는 운영 도메인.
- 자격증명은 서버 `.env`에서 읽어 넘긴다: `DB_USER`←`DB_USERNAME` · `ADMIN_EMAIL`/`ADMIN_PASSWORD`←`ADMIN_BOOTSTRAP_*` ·
  `DEMO_BUYER_*`←`NUXT_BUYER_DEMO_*` · `DEMO_SELLER_*`←`NUXT_SELLER_DEMO_*`.

순서(부족분만 채우므로 반복 실행 가능):
```
~/demo-seed/run.sh verify --dry-run   # 가드·교차 검증만
~/demo-seed/run.sh delivered
~/demo-seed/run.sh inquiries
~/demo-seed/run.sh qna
~/demo-seed/run.sh verify
```
- 단계 사이에 약 1분 둔다. gateway가 로그인 API 3경로(구매자·셀러·관리자)에 IP당 10r/m · burst 5를 걸고 단계마다 로그인하므로 제한을 함께 쓴다.
  429로 실패하면 잠시 기다렸다가 그 단계를 다시 실행한다(seed.py 자체 재시도는 최대 3회 시도 · 대기 1초·2초).
- 실행 중 도메인 확인 프롬프트에 운영 도메인을 직접 입력한다(prod 가드).
- 금지: 허용 목록 밖 단계(master·orders·timeshift·settlement·reviews·all — settlement는 정산 생성 API가 전 셀러 대상이라 비데모 셀러 정산까지 PENDING 생성) · `--force`.
- 함정: 운영 서버의 `127.0.0.1:3306`은 호스트에 직접 설치된 별도 MariaDB라 mall DB가 아니다(`zslab_mariadb`는 호스트 포트 미공개) — PC에서 SSH 터널로 그 포트에 붙는 방식은 쓰지 않는다.

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
6. `delivered` — 공개 데모 구매자에게 자동확정 창(배송완료 후 7일) 안의 배송완료 품목(진행 중 클레임 없음)이 없으면 데모 패션랩 상품으로 새 주문 1건 →
   mock 결제 → 관리자 발송 준비(송장 `DEMO`+8자리) → 배송완료. 시각은 서버 now 그대로(timeshift 없음)
7. `inquiries` — 공개 데모 구매자 1:1 문의에서 ①답변+확인 ②답변+미확인 ③미답변 ④본인 주문 첨부 중 부족한 조건만 문의 작성 → 관리자 답변 → 구매자 답변 확인
8. `qna` — 데모 셀러 상품에서 ①답변된 질문 ②미답변 질문 ③숨김 질문 ④여러 문장 설명 상품 중 부족한 조건만 —
   설명은 셀러 기본정보 수정 API(`PUT /api/v1/seller/products/{prd}` · 상태·옵션·재고·이미지 무변경) · 질문 공개 데모 구매자 · 답변 셀러 · 숨김 관리자
9. `verify` — 106 데이터 충족 여부(D2·R1·R2) 출력(판정만 · exit code 무관) · settlement_item 합 = gross/fee, occurred_at = confirmed_at,
   월별 주문·클레임 집계, 시각 불변식 재검증

### 106 데이터 단계 (D-243)
- 반복 실행 가능: 가드용 SELECT로 현재 상태를 읽어 **부족한 조건만** 만든다. 모두 충족돼 있으면 건너뛴다. 외부인이 데모 중 구매확정·답변 확인을
  해서 조건이 깨지면 다시 실행해 채운다(시연 직전 실행 권장).
- 쓰기는 전부 API 경유(직접 쓰기 SQL 없음) · 계정은 env(state 미사용 · 비밀번호를 state에 기록하지 않는다).
- inquiries의 주문 첨부는 공개 데모 구매자의 최근 주문을 쓴다(주문이 없으면 delivered 먼저).
- 빈 DB에서 `--step all`: master가 셀러 비밀번호를 랜덤으로 만들어 env 셀러 로그인(qna)이 실패한다 → reviews까지 단계별로 실행한 뒤
  state의 seller02 비밀번호를 `DEMO_SELLER_PASSWORD`로 지정해 delivered·inquiries·qna를 실행한다.

## 운영 실행 전
- `mariadb-dump --single-transaction` 백업 + `mall_uploads` 볼륨 스냅샷
- 롤백은 dump 복원 또는 state 파일 id 기준 FK 역순 DELETE(plan.md §5). TRUNCATE·DROP 금지
- 결제 PENDING TTL 30분·JWT 1시간 — 스크립트가 단계마다 재로그인하고 체크아웃 직후 mock 콜백을 호출한다(웹훅 경로 `/api/webhooks/**`는 호출 0·운영 gateway 차단 LT-29)
