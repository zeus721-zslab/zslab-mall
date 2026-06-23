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

---

## PR-02 결정 항목 (2026-06-24) [확정 2026-06-24]

> 소스: RECON.md PR-02 정찰·baseline-plan.md §4 결정 2·§8·§9 #1
> 발행 주체는 모두 Aggregate Root(aggregate-boundary.md 17개)·트리거는 state-machine.md 전이와 정합.
> 사용자 확정(2026-06-24): D-06~D-09 전체 채택 · M-15=(a) Delivery 트리거 참조만(전이 규칙 미정의·§6 이연 유지) · M-11=(a) on_hand 변동만 기록.

---

### D-06: 도메인 이벤트 목록

**상태**: [확정 2026-06-24]

**결정안**: Aggregate 트랜잭션 경계를 넘는 변경점을 9개 핵심 이벤트 + 1개 선택 이벤트로 정의.

| # | 이벤트 | 발행 주체 | 트리거 | 소비 주체 | 동기/비동기 |
|---|---|---|---|---|---|
| E1 | OrderPlaced | Order | Order/OrderItem 생성(ORDERED) | Inventory(예약)·CartItem(소비)·Notification | 예약=동기 / Cart·알림=비동기 |
| E2 | PaymentCompleted | Payment | PENDING→PAID | Order(OrderItem PAID·status 재계산)·Inventory(차감)·Notification | Order·재고=동기 / 알림=비동기 |
| E3 | PaymentFailed | Payment | PENDING→FAILED·결제 만료 | Inventory(예약 해제) | 동기 |
| E4 | DeliveryStarted | Delivery | Delivery→SHIPPING | Order(OrderItem SHIPPING·status 재계산) | 동기 |
| E5 | DeliveryCompleted | Delivery | Delivery→DELIVERED | Order(OrderItem DELIVERED·status 재계산) | 동기 |
| E6 | PurchaseConfirmed | Order | OrderItem→CONFIRMED | Settlement(정산 대상)·Read Model(PR-03 소비) | 비동기 |
| E7 | ClaimRequested | Claim | →REQUESTED | Order(OrderItem *_REQUESTED) | 동기 |
| E8 | ClaimRejected | Claim | →REJECTED | Order(OrderItem 원상 복귀) | 동기 |
| E9 | ClaimCompleted | Claim | →COMPLETED | Order(OrderItem CANCELLED/RETURNED/EXCHANGED)·Inventory(복구)·Payment(CANCELLED)·Notification | 재고·Order·결제=동기 / 알림=비동기 |
| E10 | InventoryAdjusted (선택) | Inventory | 운영자 INBOUND/OUTBOUND/ADJUST | Notification(품절 해제) | 비동기 |

- **내부 전이(이벤트 아님)**: OrderItem→PREPARING·Claim→APPROVED는 Aggregate 내부 처리.
- **멱등성**: PG 콜백(E2) 중복 수신 대비 `pg_tid`/event_id 멱등성 키. 재고 차감/복구는 OrderItem.item_status 가드로 멱등.
- **재시도**: 비동기(알림·Read Model)는 지수 백오프·N회 후 DLQ. 동기는 트랜잭션 롤백.

**Why**: aggregate-boundary.md §1 — Aggregate 간 변경은 이벤트/Application Service로 처리. 경계를 넘는 9개 전이만 이벤트화하여 결합도 최소화.

**Impact**: 이벤트 발행/구독 인프라 필요(구현 단계). 동기 이벤트는 Application Service 오케스트레이션·비동기는 메시지 전파.

**Alternative**: 모든 상태 전이를 이벤트화 → 이벤트 폭증·내부 전이까지 비동기화로 정합성 복잡도 증가(기각).

---

### D-07: Inventory Source of Truth

**상태**: [확정 2026-06-24]

**결정안**: Inventory 테이블을 재고 단일 SoT로 확정.

- Product·ProductVariant에 재고 컬럼 없음(ERD 03·db-schema §2.4 확인) → Inventory 단독 보유.
- 3컬럼: `quantity_on_hand`(실물) · `quantity_reserved`(예약) · `quantity_available`(캐시 = on_hand − reserved).
- 판매가능 판정: status=SALE ∧ quantity_available>0 ∧ ¬is_soldout_manual.

**Why**: 재고 분산 보유 시 정합성 붕괴. 단일 테이블 SoT + 이력(InventoryHistory)으로 추적성 확보.

**Impact**: 모든 재고 변경은 Inventory Aggregate를 통해서만. ProductVariant는 재고를 읽기만(quantity_available 참조).

**Alternative**: ProductVariant.stock 단일 컬럼 → 예약/이력/동시성 표현 불가(기각).

---

### D-08: 재고 예약·차감·복구 시점

**상태**: [확정 2026-06-24]

**결정안**: 예약 → 차감 → 복구 3단계 모델.

| 단계 | 트리거 이벤트 | 재고 동작 | InventoryHistory |
|---|---|---|---|
| 예약 | OrderPlaced(E1) | quantity_reserved += qty | 미기록(on_hand 불변) |
| 해제 | PaymentFailed/만료(E3) | quantity_reserved −= qty | 미기록(on_hand 불변) |
| 차감 | PaymentCompleted(E2) | quantity_on_hand −= qty · quantity_reserved −= qty | change_type=ORDER |
| 복구(결제 전 취소) | ClaimCompleted/주문취소 | quantity_reserved −= qty | 미기록 |
| 복구(결제 후 취소) | ClaimCompleted CANCEL(E9) | quantity_on_hand += qty | change_type=CANCEL |
| 복구(반품) | ClaimCompleted RETURN(E9) | quantity_on_hand += qty (검수 통과 시) | change_type=RETURN |
| 교환 | ClaimCompleted EXCHANGE(E9) | 회수분 복구 + 교환품 신규 차감 (트랜잭션 분리) | RETURN(회수) + ORDER(재출고) |

- **M-11 처리**: InventoryHistory는 on_hand 변동만 기록. 예약/해제(reserved만 변동)는 컬럼 갱신만·History 미기록. change_type A분류(ORDER/CANCEL/RETURN/ADJUST/INBOUND/OUTBOUND)에 RESERVE/RELEASE 부재와 정합.
- **M-12 처리**: 교환은 change_type EXCHANGE 부재 → 회수=RETURN·재출고=ORDER 2건 분리 기록(A분류 enum 확장 회피).
- **M-13 처리**: 예약은 주문 생성과 동일 트랜잭션(동기) — oversell 방지.
- **M-14 처리**: 결제 만료 자동 예약 해제는 정책만 명시·타이머/배치는 구현 단계 이연.

**Why**: 결제 전 점유(예약)와 실물 차감(결제 후)을 분리해 oversell 방지·미결제 점유 회수 가능. 복구를 결제 전/후로 분기해 reserved/on_hand 정확히 환원.

**Impact**: 재고 차감/복구 핸들러는 멱등(OrderItem.item_status 가드). 교환은 복구·차감 2트랜잭션 분리로 부분 실패 시 보상 가능.

**Alternative**: 주문 즉시 on_hand 차감(예약 단계 생략) → 미결제 주문이 재고 점유·결제 실패 시 복구 빈발(기각).

---

### D-09: quantity_available 갱신 방식

**상태**: [확정 2026-06-24]

**결정안**: 컬럼 캐시 유지 + 애플리케이션 갱신.

- ERD 03 기정의: quantity_available는 컬럼 캐시(= on_hand − reserved). 동적 계산(VIEW) 대신 컬럼 유지.
- 갱신 트리거: 애플리케이션(Inventory 도메인 로직에서 on_hand/reserved 변경 시 available 재계산). DB 트리거 기각.
- 동시성: 낙관적 락(version) 또는 비관적 락(SELECT FOR UPDATE) — 권장만, 구현 단계 확정.

**Why**: baseline-plan §9 #1·db-schema §4-1 모두 애플리케이션 갱신 권장. DB 트리거는 디버깅 난이도·숨은 로직·이식성 문제. 컬럼 캐시는 판매가능 필터 고빈도 조회를 매번 계산 없이 처리.

**Impact**: 재고 변경 코드는 available 재계산 누락 금지(코드 규율·테스트 의무). 단일 진입점(Inventory Aggregate) 강제로 누락 위험 완화.

**Alternative 1**: DB 트리거 자동 갱신 → 누락 불가하나 디버깅·이식성 저하(기각).
**Alternative 2**: quantity_available 컬럼 제거·매 조회 시 (on_hand − reserved) 동적 계산 → 고빈도 판매가능 필터에서 계산 비용·인덱스 불가(기각).
