# Track 89-F 셀러 정산계좌 암호화 — 운영 반영 체크리스트 (P0~P8)

- 대상: 운영 서버(로키) `zslab-mall` 백엔드 · 관련 결정: decisions.md D-188 · 마이그레이션 V32(주 계좌 UNIQUE·FK RESTRICT)·V33(계좌번호 AES 백필)
- 배포 메커니즘([.github/workflows/deploy.yml](../../.github/workflows/deploy.yml)): main push → SSH → `git pull` → `docker compose -f docker-compose.mall.yml up -d --build --wait`. **Flyway는 새 backend 컨테이너 기동 중에 실행**되며 헬스체크(/actuator/health) 통과 전까지 `--wait`가 대기한다. 구 컨테이너는 새 컨테이너 생성 시 교체되고 실패해도 자동 복귀하지 않는다.
- 이 문서는 **실행 순서와 각 시점의 데이터 상태·실패 시 조치**를 고정한다. 실행은 89-F 머지 후 별도로 진행한다(D-188 결정 13). 계좌 실값은 어떤 로그·채팅·문서에도 기재하지 않는다.

## 요약 표

| 단계 | 행위 | seller_bank_account 상태 | 앱 상태 | 실패 시 |
|---|---|---|---|---|
| P0 | DB 백업 | 평문 N행 | 구 버전 서비스 중 | — |
| P1 | 키 생성 → **별도 백업** → 서버 `.env` 주입 | 평문 | 구 버전(키 미참조) | 키 분실 = 암호화 후 전 계좌 복호 불가 |
| P2 | 사전 점검 SELECT(중복 주 계좌·평문 잔존·길이) | 평문 | 구 버전 | 중복 있으면 정리 후 진행 |
| P3 | main 머지 → deploy.yml → `git pull` | 평문 | 구 버전 | — |
| P4 | `compose up --build` 새 컨테이너 기동 | 평문 | 기동 중 | 키 미주입·형식 오류 → 기동 실패·서비스 다운 |
| P5 | Flyway V32(DDL) | 평문 + FK RESTRICT + generated 컬럼 + UNIQUE | 기동 중 | UNIQUE 실패 → 중복 정리 → repair → 재기동 |
| P6 | Flyway V33(Java·DML) | 트랜잭션 중 평문→`v1:` 암호문 | 기동 중 | 예외 → 전부 롤백(평문 유지) → 원인 수정 → repair → 재기동 |
| P7 | 컨텍스트 완료·헬스 OK | **암호문 N행(`v1:`)** | 신 버전 서비스 | — |
| P8 | 라이브 확인(읽기 전용) | 암호문 | 신 버전 | 불일치 시 롤백 절차 |

## P0. 백업
- `mariadb-dump --single-transaction` 로 DB 전체 덤프(scripts/demo-seed/README.md 선례). 덤프 파일에는 **평문 계좌번호**가 들어 있으므로 접근 통제된 위치에만 보관하고 P8 확인 후 보관 정책에 따라 처리한다.
- 데이터 상태: 평문. 앱: 구 버전 서비스 중.

## P1. 키 생성·백업·주입 (가장 중요)
1. 키 생성(32바이트 CSPRNG → Base64 44자):
   - bash: `openssl rand -base64 32`
   - PowerShell: `$b=New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`
2. **키 백업 확보(선주입만으로 끝내지 말 것)**: 서버 `.env`와 별개로 비밀 관리 저장소 또는 오프라인 매체에 1부 이상 보관하고, 보관 위치를 운영자만 아는 기록에 남긴다. 서버 디스크 장애·`.env` 유실 시 이 백업만이 복호 수단이다.
3. 운영 서버 `.env`에 `BANK_ACCOUNT_ENCRYPTION_KEY=<키>` 추가. docker-compose.mall.yml 화이트리스트에 이미 포함돼 있다(`${BANK_ACCOUNT_ENCRYPTION_KEY}`).
4. 검증: `.env`에서 값 길이 44·Base64 디코드 32바이트인지 확인(값 자체를 출력하지 말고 길이만).
- **P1은 P3보다 먼저여야 한다.** prod 프로파일은 기본값이 없어(application-prod.yml) 키가 비면 `BankAccountEncryptionConfig`가 기동을 중단한다 → `--wait` 타임아웃 → 구 컨테이너는 이미 교체돼 서비스 다운.
- 데이터 상태: 평문(구 버전은 키를 읽지 않음).

## P2. 사전 점검 SELECT (운영 DB·읽기 전용)
```sql
-- (a) 셀러당 주 계좌 2건 이상 = V32 ③ UNIQUE 추가 실패 원인 → 0행이어야 한다
SELECT seller_id, SUM(is_primary) AS primary_count FROM seller_bank_account GROUP BY seller_id HAVING SUM(is_primary) > 1;
-- (b) 이미 v1: 접두사인 행(재적용 아님이면 0) · 전체 행 수
SELECT COUNT(*) AS encrypted_rows FROM seller_bank_account WHERE account_number LIKE 'v1:%';
SELECT COUNT(*) AS total_rows FROM seller_bank_account;
-- (c) 길이 분포(자릿수만·실값 출력 금지) — 30자 초과 행이 있으면 V33 암호문이 VARCHAR(255)를 넘지 않는지 계산(약 3n+45자 이내)
SELECT CHAR_LENGTH(account_number) AS len, COUNT(*) FROM seller_bank_account GROUP BY len;
```
- (a)가 1행 이상이면 운영자가 셀러와 확인해 하나만 `is_primary=1`로 남기고(`UPDATE seller_bank_account SET is_primary = 0 WHERE id = ?`) 다시 (a)를 0행으로 만든 뒤 진행한다.
- 데이터 상태: 평문.

## P3. 머지·배포 트리거
- 89-F PR 머지(외부 검토 A 완료 후) → deploy.yml 자동 실행 → 서버에서 `git pull origin main`.
- 데이터 상태: 평문. 앱: 구 버전.

## P4. 새 컨테이너 기동
- `docker compose up -d --build --wait --wait-timeout 180`. 이미지 빌드 후 새 backend 컨테이너가 뜨며 Spring 컨텍스트 초기화 → `BankAccountEncryptionConfig`가 키를 검증(공백·Base64 아님·32바이트 아님 → 명확한 메시지로 기동 중단) → Flyway.
- 실패 시: `docker logs zslab_mall_backend`에서 `BANK_ACCOUNT_ENCRYPTION_KEY` 메시지 확인 → `.env` 수정 → `docker compose up -d` 재기동. 이 시점 데이터는 여전히 평문이라 데이터 손상은 없다.

## P5. Flyway V32 (DDL)
- 순서: ① FK `fk_seller_bank_account_seller` ON UPDATE CASCADE → RESTRICT 재생성 ② `primary_seller_id` STORED generated 컬럼 ③ `uk_seller_bank_account_primary` UNIQUE. 전 문장 `IF [NOT] EXISTS`.
- 데이터 상태: 평문 + 제약. 앱: 기동 중.
- 실패(③ 중복): `flyway_schema_history`에 version 32·success 0 행. 조치 → (1) P2 (a)대로 중복 정리 (2) 실패 행 제거 — **1순위 `flyway repair`**(Flyway CLI 또는 Gradle Flyway 플러그인 `flywayRepair` — 현재 프로젝트·컨테이너에는 둘 다 없으므로 운영자가 CLI를 별도 설치해 같은 DB 접속 정보로 실행): 실패 행만 정확히 제거한다. **2순위(CLI가 없을 때만)** 수동 SQL `DELETE FROM flyway_schema_history WHERE version = '32' AND success = 0` — 반드시 `success = 0` 조건을 붙여 실패 행 1건만 지운다. **경고: 조건을 빠뜨리거나 성공 행을 지우면 Flyway가 스키마 이력을 잃어 다음 기동에서 validate 실패·재적용 시도로 스키마가 깨질 수 있다. 실행 전 `SELECT * FROM flyway_schema_history WHERE version = '32'`로 대상 1행을 확인한다.** (3) `docker compose up -d` 재기동 → V32가 처음부터 재실행되며 ①②는 IF EXISTS로 통과·③만 실제 적용. V32V33SellerBankAccountMigrationTest가 이 복구 경로(repair → 재실행)를 검증한다.
- ON UPDATE CASCADE → RESTRICT 전환의 동작 차이: seller.id는 AUTO_INCREMENT·갱신 경로 없음 → 없음. ON DELETE는 원래 RESTRICT·무변경(셀러 삭제 동작 동일).

## P6. Flyway V33 (Java·DML)
- `account_number NOT LIKE 'v1:%'` 행을 앱 키로 AES-256-GCM 암호화해 UPDATE. DML만이라 Flyway 트랜잭션 안에서 원자적. 로그에는 건수·id만.
- 데이터 상태: 커밋 전 평문 → 커밋 후 전 행 `v1:` 암호문. 앱: 기동 중(요청 수신 전).
- 실패(키 오류·DB 오류): 전부 롤백 → **평문 그대로**·history version 33 success 0. 조치 → 원인 수정 → 실패 행 제거(1순위 `flyway repair` / 2순위 CLI 없을 때만 수동 `DELETE FROM flyway_schema_history WHERE version = '33' AND success = 0`·P5와 같은 경고: 실패 행 1건만·성공 행 삭제 금지) → 재기동(멱등·이미 암호화된 행은 건너뜀).

## P7. 서비스 개시
- 헬스 OK. 이후 모든 계좌 읽기는 Converter가 복호화한다(strict — 평문 행이 남아 있으면 그 셀러 상세·정산 상세 조회가 500).
- 데이터 상태: 암호문. 새로 등록되는 계좌도 Converter가 암호화한다.

## P8. 라이브 확인 (읽기 전용)
```sql
SELECT COUNT(*) AS plain_rows FROM seller_bank_account WHERE account_number NOT LIKE 'v1:%';   -- 0
SELECT LEFT(account_number, 3) AS prefix, CHAR_LENGTH(account_number) AS len, COUNT(*) FROM seller_bank_account GROUP BY prefix, len;
SELECT version, success FROM flyway_schema_history WHERE version IN ('32','33');
SELECT seller_id, SUM(is_primary) FROM seller_bank_account GROUP BY seller_id HAVING SUM(is_primary) > 1;  -- 0행
```
- 관리자 화면: 셀러 상세 계좌 카드 끝 4자리 정상 · 정산 상세(PAID) 스냅샷 계좌 끝 4자리 정상 · 지급 가능 정산의 지급 버튼 활성.
- `docker logs zslab_mall_backend | grep -i "V33\|AttributeConverter"` — `[V33] 계좌번호 암호화 백필 완료: N건` 1줄·Converter 예외 0.

## 롤백

### 코드만 되돌릴 때(데이터는 암호문 유지)
- 구 버전에는 Converter가 없어 암호문을 문자열로 취급한다 → **지급(계좌 id만 사용)·목록(등록 여부)·경고 배너 정상**, **셀러 상세·정산 상세의 끝 4자리만 Base64 꼬리 4자로 오표시**(장애 아님). V32 제약은 구 코드와 충돌하지 않는다(구 코드에 계좌 쓰기 경로 없음).

### 데이터까지 되돌릴 때
- 키가 있어야만 가능하다(그것이 SLR-2의 목적). 절차: 신 버전 컨테이너가 살아 있는 상태에서 보상 Java 마이그레이션(V34 등·`v1:` 행 복호 → 평문 UPDATE)을 배포하거나, 동일 키로 `AesGcmTextEncryptor.decrypt`를 호출하는 1회 툴을 실행한 뒤 구 버전으로 롤백한다. 키가 없으면 P0 덤프 복원 외 방법이 없다(그 사이 등록·변경된 계좌는 유실).
- V32 보상 SQL(V32 파일 하단 ROLLBACK 주석): UNIQUE → generated 컬럼 → FK 순으로 제거·복원.

### 키 로테이션(이월·설계 메모)
- 저장 형식이 `v1:` 접두사를 가지므로 `v2` 키를 추가하고 복호는 접두사로 분기·저장은 최신 키로 하는 방식으로 무중단 전환이 가능하다. 도구는 이월(D-188 §8).
