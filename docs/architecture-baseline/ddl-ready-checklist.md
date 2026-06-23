# DDL Ready Checklist (PR-04)

> 소스: decisions.md D-13 [확정 2026-06-24]·baseline-plan.md §8/§9·db-schema-decisions.md §1/§3/§4
> 범위: DDL 작성 직전 점검 단일 레퍼런스. 실제 DDL·Flyway·테이블별 인덱스 명세는 다음 트랙(§5 배제).
> 인덱스 전략은 [index-strategy.md](./index-strategy.md) 참조.

---

## 1. 목적

- DDL 작성 직전 점검 단일 레퍼런스.
- PR-00~03 확정 결정이 DDL에 **누락 없이 반영**되는지 확인.
- 다음 트랙 흐름: 본 체크리스트 통과 → ERD 갱신 → DDL 생성(Flyway).
- 체크박스는 "DDL에 반영됨"을 뜻한다. DDL은 다음 트랙 산출물이므로 본 PR 시점에는 미체크 상태(점검 항목 인벤토리).

---

## 2. 글로벌 정책 점검 (db-schema §1 인용)

| # | 항목 | 기준 | 출처 |
|---|---|---|---|
| - [ ] | 내부 PK | `BIGINT AUTO_INCREMENT` (집계 2건은 복합 PK) | §1.1 |
| - [ ] | public_id | ULID + prefix·UNIQUE 인덱스·애플리케이션 생성. 타입은 ⚠ §7-1 해소 후 확정 | §1.1·ADR-001 |
| - [ ] | 시간 | UTC·`DATETIME(6)`·row-level timezone 컬럼 없음 | §1.2 |
| - [ ] | 금액 | `BIGINT`(KRW 정수)·`DECIMAL` 금지 | §1.3 |
| - [ ] | 문자열 길이 | email 254·name 50·phone 20·title 200·url 2048·code 50 (무지성 VARCHAR(255) 금지) | §1.4 |
| - [ ] | Charset/Collation | `utf8mb4` / `utf8mb4_unicode_ci` (테이블·컬럼 일괄) | §1.5 |
| - [ ] | 인덱스 명명 | `pk_/fk_/uk_/ix_/복합 ix_` | §1.6 |
| - [ ] | Audit 컬럼 | `created_at·created_by·updated_at·updated_by` (created_by/updated_by FK 없음·시스템 작업 NULL 허용) | §1.7 |
| - [ ] | 소프트 삭제 컬럼 | `deleted_at·deleted_by·delete_reason` — **SOFT 분류 대상에 한정 부착** | §1.7·§1.8·D-12 |
| - [ ] | FK 적용 범위 | 강한 결합만(Order↔OrderItem·Product↔Variant·User↔Address·Seller↔Product 등). polymorphic·집계·created_by/updated_by·VIEW 참조는 미적용 | §1.9 |

---

## 3. 도메인별 점검

### 3.1 State Machine 4건 (D-02·D-03·D-05·db-schema §1.13)

| # | 컬럼 | 분류 | 값집합 점검 |
|---|---|---|---|
| - [ ] | Order.status | B(Code 참조) | 8값(PENDING_PAYMENT·PAID·PREPARING·SHIPPING·DELIVERED·CONFIRMED·CANCELLED·PARTIAL_CANCEL)·ORDER_STATUS Code 시드 일치 |
| - [ ] | OrderItem.item_status | B(Code 참조) | 12값(ORDERED~EXCHANGED)·ORDER_ITEM_STATUS Code 시드 일치 |
| - [ ] | Payment.status / method | A(잠금) | status 4값·method 4값 ENUM |
| - [ ] | Claim.status / type | A(잠금) | status 4값·type 3값 ENUM·reason_code는 B(CLAIM_REASON) |

> B분류 ENUM 값과 Code 테이블 데이터는 시드 단계 일치(db-schema §1.13). 운영자는 label·display_order·is_active만 편집.

### 3.2 Inventory 3컬럼 (D-07·D-08·D-09·ADR-005)

| # | 항목 | 점검 |
|---|---|---|
| - [ ] | quantity_on_hand·quantity_reserved·quantity_available | 3컬럼 모두 존재(`INT`) |
| - [ ] | quantity_available 갱신 | **애플리케이션 갱신**(= on_hand − reserved). DB 트리거 **미생성** 확인(§7 보류결정 #1) |
| - [ ] | InventoryHistory | append-only·change_type A분류 6값·on_hand 변동만 기록(예약/해제 미기록) |

### 3.3 AuditLog diff_json (D-11)

| # | 항목 | 점검 |
|---|---|---|
| - [ ] | diff_json 타입 | **JSON**(= MariaDB LONGTEXT + `CHECK(JSON_VALID(...))`) |
| - [ ] | 기록 범위 | changed_fields 한정·민감정보(비밀번호·결제 토큰·계좌번호·주민번호) 마스킹/제외 |
| - [ ] | FK·append-only | actor_user_id·target_id 논리 참조(FK 없음)·수정/삭제 없음 |

### 3.4 public_id prefix 매핑 (ADR-001)

| # | 항목 | 점검 |
|---|---|---|
| - [ ] | prefix 11건 | usr_·slr_·prd_·var_·ord_·oit_·pay_·dlv_·clm_·rfn_·att_ |
| - [ ] | AuditLog prefix | `aud_` 부여 확정 — ⚠ §7-2 해소(ERD/§1.1 정합) 후 반영 |

---

## 4. 보류 결정 3건 최종 확정 인용 (baseline-plan §9)

> 3건 모두 PR-01~03에서 **확정 완료**. 본 체크리스트는 재확인·인용만 — 재논의 금지.

| # | 항목 | 확정값 | 출처 결정 |
|---|---|---|---|
| - [ ] | #1 Inventory.quantity_available 갱신 방식 | **애플리케이션 갱신** (DB 트리거 기각) | D-09·ADR-005 |
| - [ ] | #2 AuditLog.diff_json 컬럼 타입 | **JSON** | D-11 |
| - [ ] | #3 OrderItem.item_status ↔ Order.status 동기화 | **방식 B**(명시적 전이) | D-04·ADR-003 |

---

## 5. 인덱스 점검 (index-strategy.md 인용)

| # | 항목 | 점검 |
|---|---|---|
| - [ ] | PK·UK·FK·복합 명명 | db-schema §1.6 규칙 일치 |
| - [ ] | public_id UNIQUE | 부여 대상에 UNIQUE 인덱스 |
| - [ ] | 소프트 삭제 인덱스 | deleted_at 가드 인덱스(MariaDB partial index 미지원 → 일반/복합 — ⚠ §7-3) |
| - [ ] | polymorphic 복합 | (target_type, target_id) — Attachment·AuditLog·NotificationLog |
| - [ ] | 조회 패턴 복합 | 운영·판매자·구매자·정산·CS 5패턴(index-strategy §2) |

> 테이블별 실제 인덱스 명세는 DDL 트랙 이연(본 PR은 전략·패턴만).

---

## 6. Read Model 점검 (D-10)

| # | 항목 | 점검 |
|---|---|---|
| - [ ] | BuyerPurchaseAggregate | buyer_id PK·lifetime_purchase_amount·last_ordered_at·updated_at·이벤트 핸들러 갱신(E6) |
| - [ ] | SellerSalesDaily | (seller_id, sale_date) 복합 PK·order_count·gross/refund/net_amount·배치 갱신 |
| - [ ] | VIEW 4건 | vw_seller_sales_monthly·vw_order_admin·vw_seller_dashboard·vw_buyer_grade_history — **DDL 트랙/구현 이연**(본 PR·DDL 직접 산출물 아님) |

---

## 7. DDL 진입 전 해소 필요 (⚠) — 3건

> 정찰 중 발견한 DDL 직전 불일치. **본 PR(PR-04) 산출물 5+1파일 범위 밖**(ADR-001·db-schema §1.1·ERD·ADR-006 본문 수정 필요) → 등재만. 실제 정정은 **PR-04.5 (architecture-baseline-fix)** 에서 처리.

### ⚠ 7-1. public_id 컬럼 타입 불일치 (M-21)

- **현상**: db-schema §1.1 `CHAR(26)` ↔ ADR-001 §영향 `VARCHAR(30)`.
- **분석**: ULID 26자 + prefix 4자(usr_·ord_ 등 11종 모두 4자) = **30자 고정**. CHAR(26)은 prefix 수용 불가.
- **결정 방향(확정)**: **`CHAR(30)` 통일** — 고정 길이로 B-Tree 효율 미세 우위.
- **처리**: PR-04.5에서 db-schema §1.1·ADR-001 §영향 정정.

### ⚠ 7-2. AuditLog public_id 부여 여부 불일치 (M-22)

- **현상**: db-schema §1.1 미부여 + ADR-001 prefix 목록 부재 ↔ ERD 05·db-schema §2.7 `char26 public_id` 보유(ERD 05 메모 "Attachment·AuditLog만").
- **결정 방향(확정)**: **AuditLog public_id 부여·prefix `aud_`** — ERD가 최신 방향·독립 Aggregate(D-01 #16)·CS 티켓 참조 가치. 부여 대상 11 → 12.
- **처리**: PR-04.5에서 db-schema §1.1 부여 목록·ADR-001 prefix 표 정정.

### ⚠ 7-3. ADR-006 partial index 표현 보정

- **현상**: ADR-006 영향 절 "소프트 삭제 컬럼 인덱스" 관련 partial index 가능성 언급. **MariaDB는 partial index(`WHERE deleted_at IS NULL`) 미지원**.
- **결정 방향(확정)**: 일반 인덱스 또는 (status, deleted_at) 복합으로 대체(index-strategy.md §4 명시).
- **처리**: ADR-006 본문 "partial index" 표현 자체 보정은 PR-04.5에서 처리.

---

## 8. 다음 트랙 진입 조건

1. 본 체크리스트 §2~§6 항목이 DDL에 반영될 것(DDL 트랙에서 체크).
2. ⚠ §7 해소 필요 3건 → **PR-04.5 (architecture-baseline-fix)** 에서 정정 완료.
3. ERD 갱신(architecture-baseline 결정 반영).
4. DDL 생성(Flyway 마이그레이션) 진입.

> 본 PR(PR-04) 머지 시점에 architecture-baseline 트랙 "주요 산출물" 완료. PR-04.5는 정정 처리 트랙.
