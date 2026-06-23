# DDL 작성 트랙 — 결정 기록

> 범위: V1__init.sql 작성 트랙 한정 결정. architecture-baseline 결정(D-01~D-22)·erd-update(E-01~E-06)는 상위 SoT이며 본 문서는 그 하위 DDL 변환 결정만 기록.
> 입력: [RECON.md](./RECON.md) (정찰 §1~§10 사본·단일 입력).

---

## 결정 1 — audit 컬럼 5분류 적용 규칙

정찰 §10-① 발견(audit 컬럼 전 테이블 일괄 부착 시 집계·매핑·append-only 테이블에 의미 없는 컬럼 발생) 해소. db-schema §1.7 audit 4컬럼을 테이블 성격별 5분류로 차등 적용한다.

| 분류 | 컬럼 셋 | 대상 테이블 |
|---|---|---|
| 핵심(full) | created_at·created_by·updated_at·updated_by | User·Seller·Product·ProductVariant·Category·ProductImage·ProductOptionGroup·ProductOptionValue·UserAddress·BuyerProfile·Order·OrderItem·OrderShippingSnapshot·Payment·Delivery·Claim·Refund·Settlement·SellerBankAccount·GradePolicy·CodeGroup·Code·BuyerGrade·Inventory·Attachment·CartItem·WithdrawnUser·SellerUser (28) |
| append-only | created_at·created_by | AuditLog·InventoryHistory·NotificationLog (3) |
| 매핑(N:M) | created_at | UserRole·RolePermission (2) |
| 시드성 | created_at·updated_at | Role·Permission (2) |
| 집계 | updated_at | BuyerPurchaseAggregate·SellerSalesDaily (2) |

- 합계 37 테이블 전수 분류(28+3+2+2+2).
- `created_by`·`updated_by`·`deleted_by`: FK 없음(§1.7·시스템 작업 NULL 허용)·BIGINT NULL.
- `created_at` NOT NULL·`updated_at` NOT NULL(집계·시드성 포함)·기본값 DEFAULT 미지정(앱/JPA Auditing 주입·UTC).
- soft delete 3컬럼(`deleted_at`·`deleted_by`·`delete_reason VARCHAR(255)`)은 audit와 별개로 **SOFT 8 테이블 한정** 부착(§1.8): User·Seller·Product·ProductVariant·Category·Attachment·UserAddress·ProductImage.
> V2(D-23·withdrawn_seller): audit full 분류 → full 28→29 (V2 적용 시점). 본 합계 37은 V1__init 스코프 기준.

## 결정 2 — 파일 경로 docs/ddl/V1__init.sql

- Flyway 표준 위치(`backend/src/main/resources/db/migration/`)는 Spring Boot init 트랙에서 backend 디렉토리 생성 시 **이동**한다.
- 현 트랙은 backend 미생성·DDL 설계 산출물 단계이므로 `docs/ddl/`에 둔다. 파일명은 Flyway 네이밍(`V1__init.sql`) 유지하여 이동만으로 마이그레이션 편입 가능.
- 동반 산출물: `RECON.md`(입력 사본)·`decisions.md`(본 문서).

## 결정 3 — 코멘트 정책 (MariaDB COMMENT)

MariaDB `COMMENT '...'` 절 사용. 도메인 가독성·온보딩 목적.

**테이블 코멘트 — 37건 전건 필수.** 형식: `COMMENT='<한글명>(<Aggregate 코드>·<삭제정책>·public_id <prefix>)'`. public_id 미부여·집계 등은 해당 항목 생략.
예: `COMMENT='회원(USR·SOFT·public_id usr_)'`

> V2(withdrawn_seller·D-23) 적용 시 테이블 코멘트 38건. 본 37건은 V1__init 스코프 기준.

**컬럼 코멘트 — 다음 한정 부착:**
- 도메인 컬럼 전건(업무 의미가 있는 컬럼).
- ENUM 컬럼: 값집합 + invariant 코드.
- FK 컬럼: 참조 대상 + 카디널리티.
- UK 구성 컬럼 일부(business_no·order_no·tracking_no 등 자연키).
- 비식별화 대상(name·phone·email·account_number 등).
- 캐시/계산 컬럼(quantity_available 등).

**컬럼 코멘트 — 생략:**
- id PK·audit 4컬럼·deleted_* 3컬럼(전역 정책·반복).

---

## 정찰 §10 발견 사항 처리 (②③④)

### ② 집계 테이블 FK — 미적용
- 충돌: ERD는 BuyerPurchaseAggregate.buyer_id·SellerSalesDaily.seller_id를 FK로 표기. db-schema §1.9는 집계 테이블 FK 미적용 명시.
- **결정: §1.9 SoT 우선·FK 미적용.** 집계는 Read Model(D-10)이며 이벤트/배치 갱신·삭제 전파 불필요. buyer_id·seller_id는 논리참조(BIGINT)로만 둔다.
- ERD 표기 lag는 본 트랙 범위 외(TODO 등재·§10-⑤와 함께).

### ③ Claim.reason_code — VARCHAR(50) + Code 시드 정합
- 충돌: B분류이나 CLAIM_REASON 값집합 미확정(운영자 관리 대상).
- **결정: `reason_code VARCHAR(50) NOT NULL`·ENUM/FK 미적용.** 값은 Code 테이블 CLAIM_REASON 그룹과 시드 단계 정합(COD-3)·운영자 label/display_order/is_active 편집. ENUM 잠금 시 값 추가마다 DDL 변경 필요·운영 유연성 저해.
- 4층위 enum 잠금 규칙(CLAUDE.md)의 예외: 값집합이 운영 데이터(Code 테이블)에 위임된 B분류 컬럼. DB 잠금 대신 Code FK 정합 + Service 검증으로 대체(invariants COD-3).

### ④ zonecode — VARCHAR(10)
- 충돌: db-schema §2.1 "우편번호 5자리" 서술 ↔ ERD VARCHAR(10).
- **결정: `VARCHAR(10)`.** 신우편번호 5자리이나 향후 형식·구우편번호(6자리)·국제 확장 여지·ERD 표기 일치. UserAddress·OrderShippingSnapshot 동일 적용.

---

## DDL 변환 부수 결정 (기계적·정찰 명세 직접 도출)

- **BOOLEAN → `TINYINT(1)`**: MariaDB BOOLEAN 별칭. is_default·is_main·is_active·is_system·is_primary·selected·is_soldout_manual.
- **JSON → `LONGTEXT` + 명시 CHECK**: audit_log.diff_json(D-11·AUD-3). ddl-ready-checklist §3.3 정규형(`LONGTEXT + CHECK(JSON_VALID(...))`) 채택 — 네이티브 `JSON` 타입의 암묵 제약 대신 명명 CHECK(`chk_audit_log_diff_json`)로 AUD-3을 명시·검증 가능화. nullable 대응 `diff_json IS NULL OR JSON_VALID(diff_json)`.
- **금액 → `BIGINT`**(KRW 정수·DECIMAL 금지·§1.3).
- **시간 → `DATETIME(6)`**·DEFAULT 미지정·UTC(§1.2).
- **public_id → `CHAR(30)` NOT NULL UNIQUE**·12 테이블 한정(M-21/M-22 해소·ADR-001).
- **FK 인덱스**: InnoDB는 FK 선언 시 자식 컬럼 보조 인덱스 자동 생성. 별도 `INDEX` 선언 안 함(중복 회피).
- **FK 옵션**: `ON DELETE RESTRICT ON UPDATE CASCADE` 기본. SOFT/ARCHIVE Root 물리 삭제 미발생·deletion-policy 정합. Category self-FK도 동일.
- **cart_item 인덱스**: user_id 단독 조회는 `uk_cart_item_user_variant(user_id,variant_id)` 좌측 prefix가 커버·별도 ix 생략(정찰 §7).
- **soft delete 인덱스**: MariaDB partial index 미지원(ADR-006·index-strategy §4.2) → SOFT 8 테이블 `ix_{table}_deleted_at(deleted_at)` 일반 인덱스.
- **charset**: 테이블·컬럼 모두 utf8mb4/utf8mb4_unicode_ci. 테이블 레벨 선언으로 컬럼 상속·VARCHAR 컬럼 개별 charset 절 미부착(상속).
- **DATE 타입**: seller_sales_daily.sale_date만 `DATE`(일 단위 집계 PK). 그 외 시각은 DATETIME(6).
- **TEXT vs VARCHAR**: 가변 장문(description·reason_detail·content·delivery_memo·failed_reason)은 LONGTEXT/TEXT. 정찰 §2 명세 따름(description=LONGTEXT·그 외 TEXT).

---

## 정찰 입력 보정 (작성·검증 중 발견·DDL 반영)

> 정찰 표 자체의 오기 2건을 발견·DDL에 정정 반영. RECON.md 사본도 동기화 정정.

- **(보정 1) seller.business_no `NOT NULL` → `NULL`**: 정찰 §2.3은 NN으로 표기했으나, db-schema 마스터 §2.3 및 D-22(재등록 = 비식별화 완료 상태) 명세상 Seller 비식별화 시 business_no를 NULL로 비워 UK 슬롯을 해제해야 함(User.email NULL과 동일 패턴). 따라서 `business_no VARCHAR(20) NULL`·UK 유지(다중 NULL 허용). **가정 표면화**: D-22 재등록 흐름을 위한 nullable 채택.
- **(보정 2) §6 FK 표 `code → code_group` 누락·총계 40→41**: 정찰 §6 요약표가 code 테이블의 group_id FK 행을 누락하고 합계를 40으로 기재. §2.6은 group_id를 FK로 명시하므로 DDL은 `fk_code_group`을 정상 포함(FK 총 41). RECON.md §6 표·총계 정정 완료.
- **(검증 메모) audit 5분류·SOFT 8·ENUM 21·UK 23·CHECK 6·public_id 12** 전부 테이블별 파싱 검증 통과(집계 카운트 아닌 블록별 시그니처 대조).
> V2(withdrawn_seller·D-23) 적용 시 FK 41→42(+fk_withdrawn_seller_seller)·audit full 28→29. 본 보정·검증 메모는 V1__init 스코프 기준.
