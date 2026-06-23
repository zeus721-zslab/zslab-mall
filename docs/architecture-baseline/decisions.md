# architecture-baseline 결정 누적

> 각 PR에서 확정된 설계 결정을 누적. 미확정 항목은 [미확정] 표시.
> 확정 → [확정] 마킹 + 날짜.

---

## PR-01 결정 항목 (2026-06-24) [확정 2026-06-24]

---

### D-01: Aggregate 경계 분할 (17개 + Read Model 2건 PR-03 이연)

**상태**: [확정 2026-06-24]

**결정안**:
37개 테이블 → 아래 17개 Aggregate로 분할 (db-schema §3 "29개" 표기 오류 — 실제 37개). Read Model 2건(BuyerPurchaseAggregate·SellerSalesDaily)은 PR-03 이연.

| # | Aggregate | Root | 포함 엔티티 (테이블) |
|---|---|---|---|
| 1 | User | User | WithdrawnUser, BuyerProfile, UserAddress |
| 2 | Auth | Role | Permission, UserRole, RolePermission |
| 3 | BuyerGrade | BuyerGrade | GradePolicy |
| 4 | Seller | Seller | SellerBankAccount, SellerUser |
| 5 | Settlement | Settlement | (단독) |
| 6 | Category | Category | (self-ref 계층) |
| 7 | Product | Product | ProductImage, ProductOptionGroup, ProductOptionValue, ProductVariant |
| 8 | Inventory | Inventory | InventoryHistory |
| 9 | CartItem | CartItem | (단독) |
| 10 | Order | Order | OrderItem, OrderShippingSnapshot |
| 11 | Payment | Payment | (단독) |
| 12 | Delivery | Delivery | (단독) |
| 13 | Claim | Claim | Refund |
| 14 | Code | CodeGroup | Code |
| 15 | Attachment | Attachment | (단독, polymorphic) |
| 16 | AuditLog | AuditLog | (단독, append-only) |
| 17 | NotificationLog | NotificationLog | (단독) |

**Read Model 후보 (PR-03 이연)**:

| # | 테이블 | 이연 근거 |
|---|---|---|
| R1 | BuyerPurchaseAggregate | db-schema §2.2 "Read Model" 명시 |
| R2 | SellerSalesDaily | db-schema §1.11·§2.8 "집계 테이블 (배치 갱신)" 명시 |

baseline-plan.md §10 준수 — Read Model 작업은 PR-03 트랙에서 처리.

**Why**: DDD Aggregate 4기준(함께 생성·함께 삭제·트랜잭션 동반·독립 수정 불가) 적용.
- Product에 ProductVariant 포함: Variant는 Product 없이 생성 불가, 상태 변경도 상품 컨텍스트 내.
- Inventory 독립: 주문·취소·반품에 의해 Order 트랜잭션과 별도로 갱신됨.
- Claim에 Refund 포함: Refund는 Claim 승인 후에만 생성되며 Claim과 생명주기 공유.
- Payment·Delivery는 각각 독립: PG 콜백·물류 처리가 별도 컨텍스트.
- 집계·Read Model 후보(BuyerPurchaseAggregate·SellerSalesDaily)는 Aggregate에서 분리 — 도메인 트랜잭션 주체가 아니라 이벤트 핸들러로 갱신되는 파생 데이터이며 baseline-plan.md §10에 의거 PR-03 이연.

**Impact**: Aggregate간 참조는 ID만 허용 (객체 참조 금지). 다른 Aggregate 조작은 도메인 이벤트 또는 Application Service에서 처리.

**Alternative**: Order Aggregate에 Payment, Delivery 포함 → 주문 트랜잭션 단순화. 단, PG 콜백 등 비동기 처리 시 Order 잠금 범위 확대 위험.

**확정 결과**:
- [확정-A] SellerUser → Seller Aggregate 포함 (SellerUser는 Seller 컨텍스트에서 생성·삭제)
- [확정-B] CartItem → 독립 Aggregate (#9) (User 트랜잭션과 무관하게 조작)
- [확정-C] Delivery → 독립 Aggregate (#12) (물류 컨텍스트에서 독립 갱신)

---

### D-02: Order.status 값 집합

**상태**: [확정 2026-06-24]

**확정 값 집합 (8개)**:

| 값 | 설명 |
|---|---|
| PENDING_PAYMENT | 결제 전 (주문 생성 직후) |
| PAID | 결제완료 |
| PREPARING | 준비중 (최초 OrderItem PREPARING) |
| SHIPPING | 배송중 (최초 OrderItem SHIPPING) |
| DELIVERED | 배송완료 (모든 OrderItem DELIVERED) |
| CONFIRMED | 구매확정 (모든 OrderItem CONFIRMED) |
| CANCELLED | 취소 (모든 OrderItem CANCELLED) |
| PARTIAL_CANCEL | 부분취소 (일부 CANCELLED·나머지 CONFIRMED) |

**Why**: B분류(Code 참조) — 운영자가 Code 테이블에서 라벨만 편집 가능. 값 집합은 코드 레이어 enum으로 고정. db-schema §2.6 예시 코드 기반 확장.

**Impact**: PARTIAL_CANCEL 포함으로 운영 화면에서 "부분 취소 주문" 필터 가능.

**Alternative**: PENDING_PAYMENT 제외 → 주문 생성과 결제를 단일 트랜잭션으로 묶을 경우. 단, 현재 PG 연동 구조상 결제 전 주문 생성이 일반적.

---

### D-03: OrderItem.item_status 값 집합

**상태**: [확정 2026-06-24]

**확정 값 집합 (12개)**:

| 값 | 설명 |
|---|---|
| ORDERED | 주문됨 (OrderItem 생성 직후) |
| PAID | 결제완료 |
| PREPARING | 준비중 |
| SHIPPING | 배송중 |
| DELIVERED | 배송완료 |
| CONFIRMED | 구매확정 |
| CANCEL_REQUESTED | 취소요청 |
| CANCELLED | 취소완료 |
| RETURN_REQUESTED | 반품요청 |
| RETURNED | 반품완료 |
| EXCHANGE_REQUESTED | 교환요청 |
| EXCHANGED | 교환완료 |

**Why**: OrderItem이 실제 상태 보유(baseline-plan §4 결정 1). Claim.type(CANCEL·RETURN·EXCHANGE)별로 OrderItem 상태가 분기되므로 각 클레임 타입의 최종 상태가 필요.

**Impact**: EXCHANGED 포함으로 교환 완료 후 OrderItem 최종 상태 명확. 교환 완료 후 추가 교환 요청도 EXCHANGED → EXCHANGE_REQUESTED 전이로 처리 가능.

**Alternative**: 클레임 관련 상태를 OrderItem에서 제거, Claim.status로만 추적. 단, OrderItem.item_status가 "실제 상태"라는 baseline-plan 결정 1과 충돌.

---

### D-04: OrderItem.item_status ↔ Order.status 동기화 규칙

**상태**: [확정 2026-06-24]

**확정 결정**: 방식 B (명시적 전이 조건)

```
Payment.PAID 이벤트 → 모든 OrderItem = PAID → Order.status = PAID
OrderItem = PREPARING (최초) → Order.status = PREPARING
OrderItem = SHIPPING (최초) → Order.status = SHIPPING
모든 OrderItem ∈ {DELIVERED} → Order.status = DELIVERED
모든 OrderItem = CANCELLED → Order.status = CANCELLED
일부 OrderItem = CANCELLED + 나머지 ∈ {CONFIRMED, RETURNED, EXCHANGED} → Order.status = PARTIAL_CANCEL
모든 OrderItem ∈ {CONFIRMED, RETURNED, EXCHANGED} → Order.status = CONFIRMED
```

**Why**: 쇼핑몰 CS에서 케이스별 Order.status 정합이 중요. 명시적 조건 없을 경우 부분 반품·교환 완료 혼재 케이스에서 Order.status 계산 버그 발생 위험.

**Impact**: Service.canTransition() 또는 OrderStatusCalculator 컴포넌트 필요. 이벤트 핸들러에서 OrderItem 변경 후 Order.status 재계산 트리거.

**Alternative**: 방식 A (우선순위 기반 — 가장 낮은 OrderItem 상태가 Order.status). 규칙 단순하나 클레임 상태와의 우선순위 정의가 불명확.

---

### D-05: Payment·Claim State Machine (A분류 확정값)

**상태**: [확정 2026-06-24]

**Payment.status 전이**:

```
PENDING → PAID (PG 성공)
PENDING → FAILED (PG 실패, 재시도 = 새 Payment 행)
PAID → CANCELLED (Refund.COMPLETED 완료 후)
```

**Claim.status 전이**:

```
REQUESTED → APPROVED → COMPLETED
           → REJECTED  (재요청 = 새 Claim 행 생성)
```

**Why**: A분류(잠금) — 값 집합 변경 = 마이그레이션 필수. db-schema §1.13 확정값.

**확정**: REJECTED 후 재요청 = 새 Claim 행 생성 (기존 Claim은 REJECTED 상태로 보존·이력 추적 가능).

---

## 부록: 확인 필요 불일치

| # | 항목 | 현황 | 처리 |
|---|---|---|---|
| M-08 | buyer-grade.md에 grade_changed_reason 존재, db-schema §2.1에서 제거. ERD에는 없음. | ERD 기준 제거 간주 (buyer-grade.md가 구버전 보존) | 처리 불필요 |
| M-09 | db-schema §3 "총 29개" → 실제 37개 | §3 카운트 오류 | PR-04 ddl-ready-checklist.md 작성 시 일괄 처리 |
