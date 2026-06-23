# Index Strategy (PR-04)

> 소스: decisions.md D-14 [확정 2026-06-24]·db-schema-decisions.md §1.6/§1.9·ADR-001/006
> 범위: 명명 규칙·조회 패턴 분류·패턴별 가이드·특수 인덱스 **전략·패턴만**. 테이블별 실제 인덱스 명세·CREATE INDEX·커버링 컬럼 확정은 DDL 트랙/구현 이연(baseline-plan §5).
> 점검 리스트는 [ddl-ready-checklist.md](./ddl-ready-checklist.md) §5 참조.

---

## 1. 명명 규칙 (db-schema §1.6 인용)

| 종류 | 규칙 | 예 |
|---|---|---|
| PK | `pk_{table}` | pk_user |
| FK | `fk_{table}_{ref}` | fk_order_user |
| Unique | `uk_{table}_{column}` | uk_user_email |
| Index | `ix_{table}_{column}` | ix_order_status |
| 복합 | `ix_{table}_{col1}_{col2}` | ix_order_status_created |

> db-schema §1.6 규칙을 그대로 채택(확장 없음). 아래 §3~§5의 인덱스 명은 패턴 예시 — 테이블별 확정 명세는 DDL 트랙.

---

## 2. 조회 패턴 분류

| 구분 | 대표 화면·작업 | 주요 조회 키(예시) |
|---|---|---|
| 운영 화면 | Seller/Product 승인 대기·Claim 처리·Settlement 조회·AuditLog 조회 | Product.status·Seller.status·Claim.status·Settlement.(seller_id, status) |
| 판매자 대시보드 | 자사 주문 항목·일별 매출·재고 부족 | OrderItem.(seller_id, item_status)·SellerSalesDaily PK·Inventory.quantity_available |
| 구매자 화면 | 상품 검색·주문 내역·마이페이지 | Product.(category_id, status)·Order.(buyer_id, status)·CartItem.user_id |
| 정산 | 월간 집계·환불 차감·정산 기간 | SellerSalesDaily.(seller_id, sale_date)·Settlement.(period_start, period_end) |
| CS·감사 | 대상별·행위자별·기간 추적 | AuditLog.(target_type, target_id, created_at)·AuditLog.(actor_user_id, created_at) |

---

## 3. 패턴별 인덱스 가이드

| 종류 | 가이드 |
|---|---|
| PK | 전 테이블 `BIGINT AUTO_INCREMENT`. 집계 2건은 복합 PK(SellerSalesDaily (seller_id, sale_date)·BuyerPurchaseAggregate buyer_id). |
| UK | public_id(부여 대상·§4-1)·도메인 유니크 키: `uk_user_email`·Order.order_no·CartItem (user_id, variant_id)·ProductVariant 옵션 조합 4컬럼 (product_id, option1~3_value_id). |
| FK | db-schema §1.9 **강한 결합 관계 한정**. InnoDB는 FK 선언 시 자식 컬럼 인덱스를 자동 생성하므로 `fk_` 명만 부여. polymorphic·집계·created_by/updated_by·VIEW 참조는 FK·인덱스 미적용(필요 시 §4-3 복합 인덱스로 대체). |
| 복합 | 조회 패턴별 선두 컬럼 선택도 고려(예시: ix_order_buyer_status·ix_order_item_seller_status·ix_product_category_status). 컬럼 순서·범위는 DDL 트랙에서 카디널리티 기준 확정. |
| 커버링 | 고빈도 조회의 SELECT 컬럼 포함. 쓰기 비용·저장 트레이드오프로 **구현 단계 측정 후** 선별 적용. |

> ProductVariant 옵션 조합 UNIQUE는 NULL 허용 컬럼(option2/3) 포함 → MariaDB는 NULL≠NULL이라 DB UNIQUE만으로 중복 완전 차단 불가. 애플리케이션 레이어 추가 검증 병행(ERD 03 설계메모).

---

## 4. 특수 인덱스 정책

### 4.1 public_id 인덱스 (ADR-001)

- 부여 대상에 **UNIQUE 인덱스 필수**(API/URL 외부 조회 키).
- 부여 12건(User·Seller·Product·ProductVariant·Order·OrderItem·Payment·Delivery·Claim·Refund·Attachment·AuditLog — PR-04.5 해소·prefix 목록은 ADR-001/ERD README).
- ULID는 시간 정렬 가능 → 무작위 UUID 대비 B-Tree 단편화 적음(ADR-001).
- 컬럼 타입은 `CHAR(30)` 통일 방향(ddl-ready-checklist §7-1·PR-04.5 정합).

### 4.2 소프트 삭제 인덱스 (ADR-006)

- SOFT 분류 대상 조회는 `deleted_at IS NULL` 가드가 모든 쿼리에 붙음(D-12·deletion-policy.md §4).
- **MariaDB는 partial index(`WHERE deleted_at IS NULL`) 미지원** → 다음 중 택1:
  - deleted_at 단독 인덱스(`ix_{table}_deleted_at`), 또는
  - 고빈도 필터 컬럼과 복합(`ix_{table}_status_deleted_at` 등 — 활성 행 필터를 인덱스 선두에서 처리).
- ADR-006 본문의 "partial index" 표현은 위 대체로 보정(표현 수정 자체는 PR-04.5).

### 4.3 polymorphic 인덱스

- FK가 없는 polymorphic 참조는 (target_type, target_id) **복합 인덱스**로 조회 성능 확보.
- 대상: Attachment·AuditLog·NotificationLog의 (target_type, target_id). AuditLog는 추적 조회 위해 (target_type, target_id, created_at)까지 확장 후보.

---

## 5. Read Model 인덱스 (D-10)

| Read Model | 인덱스 |
|---|---|
| BuyerPurchaseAggregate | buyer_id PK·등급 평가 조회용 last_ordered_at 보조 인덱스 후보 |
| SellerSalesDaily | (seller_id, sale_date) 복합 PK — 일별/기간 범위 조회·월간 VIEW(GROUP BY) 양쪽 커버 |

> VIEW 4건(vw_*)의 인덱스는 기반 테이블 인덱스에 의존 — 별도 인덱스 없음. VIEW 실제 SQL·ALGORITHM은 DDL 트랙(read-model.md §5).

---

## 6. 외부 이연

- **테이블별 실제 인덱스 명세·CREATE INDEX**: DDL 트랙.
- **커버링 인덱스 컬럼 확정·인덱스 성능 측정·튜닝**: 구현/운영 단계(측정 기반).
- **파티셔닝**: 운영 단계(대량 로그 AuditLog·Order 후보).
- **public_id 타입 CHAR(30) 통일·AuditLog 부여(aud_) 정정**: 완료(PR-04.5·ddl-ready-checklist §7).
