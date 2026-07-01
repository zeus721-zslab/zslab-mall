# Track 17 Inventory 도메인 행위 구현 — 정찰 보고서

> 정찰일: 2026-07-01  
> 정찰자: Claude Sonnet 4.6 (read-only)  
> 베이스: main HEAD 8f17fb4 (Track 16 PR-2 머지 완료 · 445 tests PASS)  
> 규칙: 모든 단언은 SoT 파일 경로 + 라인 번호 인용 의무. "~로 보임"·"~일 것" 금지.

---

## §0. 진입점 카드 (메모리 룰 #16 시범 적용)

| # | 항목 | 내용 |
|---|---|---|
| 1 | **목적** | Inventory Aggregate에 재고 예약·차감·해제·복구 도메인 행위 신설. E1/E2/E3/E9 4 이벤트 핸들러 및 InventoryService 구현 — 도메인 행위 0건 현재 상태 → 재고 쓰기 4경로 완성 |
| 2 | **핵심 진입점** | `inventory/entity/Inventory.java:32` (행위 신설 대상 Aggregate Root) · `inventory/entity/InventoryHistory.java:62` (정적 팩토리 1건 존재·append-only) · `inventory/enums/InventoryHistoryChangeType.java:7` (A분류 값집합 확정·ORDER/CANCEL/RETURN/ADJUST/INBOUND/OUTBOUND) |
| 3 | **핵심 SoT** | `docs/domain/inventory-policy.md §2~§6` — 예약·차감·복구·available 갱신·HistoryHistory 기록 정책 · `docs/architecture-baseline/decisions.md D-08:244` — 3단계 모델(예약→차감→복구) · `docs/architecture-baseline/domain-events.md §2 E1·E2·E3·E9` — 핸들러 발행처·멱등 정책 |
| 4 | **영향 범위** | `inventory/entity/` (도메인 행위 신설) · `inventory/handler/` (신규 패키지·4 핸들러) · `inventory/service/` (신규 Service) · `checkout/service/CheckoutService.java` (E1 예약 후 이미 재고 조회 있음·핸들러 분리 정합 확인 필요) |
| 5 | **패턴 재사용 SoT** | D-100 Q1 γ 멱등 패턴 5종 — 신규 4 핸들러 패턴 선택 사유 Javadoc 의무 · D-100 Q8 β 통합 테스트 5중 의무 (NO @Transactional + TransactionTemplate + LT-02 + D-91 FK 그래프 시드) · D-91 FK 부모 그래프 시드 — inventory 시드 시 product_variant → product → seller 직접 FK 부모 시드 |
| 6 | **트랩 주의** | LT-01 해당 없음 (Inventory public_id 미부여) · LT-02 try-finally — 통합 테스트 FK_CHECKS 복원 의무 · LT-03 해당 없음 (deleted_at 없음 · soft-delete 미적용) · **WARN-1 (P0)**: E3 PaymentFailed 소비 핸들러 전무 확인 필요 |

---

## §1. Inventory 도메인 실측

### §1.1 패키지 트리

PowerShell `Get-ChildItem -Recurse backend/src/main/java/com/zslab/mall/inventory` 실측:

```
inventory/
  entity/
    Inventory.java
    InventoryHistory.java
  enums/
    InventoryHistoryChangeType.java
  repository/
    InventoryRepository.java
    InventoryHistoryRepository.java
```

**handler/ 패키지 없음** — Get-ChildItem 결과 entity/·enums/·repository/ 3개 디렉토리만 존재.

### §1.2 Inventory.java

파일: `backend/src/main/java/com/zslab/mall/inventory/entity/Inventory.java`

| 속성 | 실측 |
|---|---|
| 상속 | L32: `extends AbstractFullAuditableEntity` |
| PK | L34-38: `@Id @GeneratedValue(IDENTITY) @EqualsAndHashCode.Include Long id` |
| variant_id | L40-41: `@Column(name="variant_id", nullable=false, updatable=false) Long variantId` |
| quantityOnHand | L43-44: `@Column(name="quantity_on_hand") int quantityOnHand` |
| quantityReserved | L46-47: `@Column(name="quantity_reserved") int quantityReserved` |
| quantityAvailable | L49-50: `@Column(name="quantity_available") int quantityAvailable` |
| 정적 팩토리 | **0건** — Track 4 read-only 원칙(Javadoc L18 "Track 4 read-only·D-57") |
| setter | **없음** — `@NoArgsConstructor(AccessLevel.PROTECTED)` |
| 도메인 행위 | **0건** — Javadoc L21 "쓰기 책임은 Track 7로 이연" |
| @Version | **없음** — D-09 구현 단계 확정 이연 정합 |
| public_id | **없음** — Javadoc L20 "public_id 미부여 표준 id 엔티티" |
| deleted_at | **없음** — AbstractFullAuditableEntity 상속(soft-delete abstract 비상속) |

### §1.3 InventoryHistory.java

파일: `backend/src/main/java/com/zslab/mall/inventory/entity/InventoryHistory.java`

| 속성 | 실측 |
|---|---|
| 상속 | L32: `extends AbstractCreatedOnlyEntity` |
| PK | L36-37: `@Id @GeneratedValue(IDENTITY) @EqualsAndHashCode.Include Long id` |
| inventory | L39-41: `@ManyToOne(fetch=LAZY, optional=false) @JoinColumn(name="inventory_id") Inventory inventory` |
| changeType | L43-45: `@Enumerated(EnumType.STRING) InventoryHistoryChangeType changeType` |
| quantityDelta | L47-48: `int quantityDelta` |
| referenceType | L50-51: `@Column(name="reference_type", length=50, updatable=false) String referenceType` |
| referenceId | L53-54: `@Column(name="reference_id", updatable=false) Long referenceId` |
| reason | L56-57: `@Column(name="reason", length=255, updatable=false) String reason` |
| 정적 팩토리 | **1건** — L62-80: `static InventoryHistory create(Inventory, InventoryHistoryChangeType, int, String, Long, String)` |
| @Immutable | **미적용** — Javadoc L26-27: "append-only이지만 @Immutable은 사용하지 않는다(A2 결정·AbstractCreatedOnlyEntity Javadoc 정합)" |

### §1.4 InventoryHistoryChangeType.java

파일: `backend/src/main/java/com/zslab/mall/inventory/enums/InventoryHistoryChangeType.java`

값 집합 (L7-12): `ORDER, CANCEL, RETURN, ADJUST, INBOUND, OUTBOUND`

**RESERVE/RELEASE 부재** — D-08 M-11(L260) "change_type A분류에 RESERVE/RELEASE 부재와 정합" 충족.

### §1.5 Repository 실측

**InventoryRepository.java** (`inventory/repository/InventoryRepository.java`):
- L15: `Optional<Inventory> findByVariantId(Long variantId)` — 단건 조회
- L18: `List<Inventory> findByVariantIdIn(Collection<Long> variantIds)` — 일괄 조회 (N+1 회피)

**InventoryHistoryRepository.java** (`inventory/repository/InventoryHistoryRepository.java`):
- L6: `extends JpaRepository<InventoryHistory, Long>` — 커스텀 메서드 0건

---

## §2. DDL 실측

파일: `backend/src/main/resources/db/migration/V1__init.sql`

### §2.1 inventory 테이블 (L488-505)

```sql
-- L488-505
CREATE TABLE inventory (
  id                  BIGINT      NOT NULL AUTO_INCREMENT,       -- L489
  variant_id          BIGINT      NOT NULL,                      -- L490
  quantity_on_hand    INT         NOT NULL,                      -- L491 (INV-4 ≥0)
  quantity_reserved   INT         NOT NULL,                      -- L492 (INV-3 ≥0)
  quantity_available  INT         NOT NULL,                      -- L493 (INV-1 ≥0·D-09 앱 갱신)
  created_at/created_by/updated_at/updated_by  ...              -- L494-497
  PRIMARY KEY (id),                                              -- L498
  UNIQUE KEY uk_inventory_variant (variant_id),                 -- L499 (INV-6)
  KEY ix_inventory_available (quantity_available),               -- L500
  CONSTRAINT fk_inventory_variant FOREIGN KEY (variant_id) REFERENCES product_variant (id), -- L501
  CONSTRAINT chk_inventory_on_hand CHECK (quantity_on_hand >= 0),   -- L502 (INV-4)
  CONSTRAINT chk_inventory_reserved CHECK (quantity_reserved >= 0), -- L503 (INV-3)
  CONSTRAINT chk_inventory_available CHECK (quantity_available >= 0) -- L504 (INV-1)
)
```

| 제약 | 실측 | 인용 |
|---|---|---|
| INV-1 available ≥ 0 | `chk_inventory_available CHECK (quantity_available >= 0)` | V1 L504 |
| INV-3 reserved ≥ 0 | `chk_inventory_reserved CHECK (quantity_reserved >= 0)` | V1 L503 |
| INV-4 on_hand ≥ 0 | `chk_inventory_on_hand CHECK (quantity_on_hand >= 0)` | V1 L502 |
| INV-6 variant UNIQUE | `uk_inventory_variant (variant_id)` | V1 L499 |
| @Version 컬럼 | **없음** | — |
| public_id 컬럼 | **없음** | — |
| deleted_at 컬럼 | **없음** | — |

### §2.2 inventory_history 테이블 (L510-522)

```sql
-- L510-522
CREATE TABLE inventory_history (
  id              BIGINT       NOT NULL AUTO_INCREMENT,          -- L511
  inventory_id    BIGINT       NOT NULL,                         -- L512
  change_type     ENUM('ORDER','CANCEL','RETURN','ADJUST','INBOUND','OUTBOUND') NOT NULL, -- L513
  quantity_delta  INT          NOT NULL,                         -- L514
  reference_type  VARCHAR(50)  NOT NULL,                         -- L515 (D분류 polymorphic)
  reference_id    BIGINT       NULL,                             -- L516
  reason          VARCHAR(255) NULL,                             -- L517
  created_at DATETIME(6) NOT NULL,                               -- L518
  created_by BIGINT NULL,                                        -- L519
  PRIMARY KEY (id),                                              -- L520
  CONSTRAINT fk_inventory_history_inventory FOREIGN KEY (inventory_id) REFERENCES inventory -- L521
)
```

ENUM 값집합 L513: `'ORDER','CANCEL','RETURN','ADJUST','INBOUND','OUTBOUND'` — InventoryHistoryChangeType.java 1:1 정합.

### §2.3 V2~V7 inventory ALTER 부재 확인

```powershell
# 실행 명령
grep -n "inventory" V2__seller_anonymization.sql V3__payment_track3.sql V4__order_idempotency_key.sql
                    V5__refund_constraints.sql V6__add_claim_pickup_and_previous_status.sql
                    V7__add_delivery_claim_id.sql
# 결과: 0건
```

V2~V7 전건에서 inventory 관련 ALTER 0건 — V1 DDL이 현행 단일 소스.

---

## §3. 재고 트리거 이벤트 4건 핸들러 영역 실측

### §3.1 E1 OrderPlaced 핸들러 (예약·동기)

**발행처**: `backend/src/main/java/com/zslab/mall/order/service/OrderService.java` L84
```java
eventPublisher.publishEvent(new OrderPlaced(saved.getPublicId(), saved.getId(), LocalDateTime.now()));
```

**OrderPlaced record** (`order/event/OrderPlaced.java` L14-18):
```java
public record OrderPlaced(
        String publicId,
        Long orderId,
        LocalDateTime occurredAt) {}
```
items[] **없음** — D-30 사실 통지·도메인 상태 복제 방지 정합 (Javadoc L9).

**기존 소비자 grep** (`grep "OrderPlaced" **/*.java`):
- `notification/handler/NotificationOrderPlacedHandler.java` — E1 첫 소비자 (Javadoc L16)

**Inventory 예약 핸들러**: **0건** — inventory/handler/ 패키지 미존재.

> 주의: 핸들러는 orderId로 OrderItem 재조회 후 variant_id·quantity 획득 필요(D-30 정합).

### §3.2 E2 PaymentCompleted 핸들러 (차감·동기)

**발행처**: `backend/src/main/java/com/zslab/mall/payment/service/PaymentService.java` L162-164
```java
List<Object> events = payment.pullDomainEvents();
paymentRepository.save(payment);
events.forEach(eventPublisher::publishEvent);
```

**PaymentCompleted record** (`payment/event/PaymentCompleted.java` L16-22):
```java
public record PaymentCompleted(
        Long paymentId, Long orderId, Long amount, String pgTransactionId, LocalDateTime occurredAt) {}
```

**기존 소비자 grep** (`grep "PaymentCompleted" **/*.java`):
- `payment/handler/OrderEventHandler.java` L30: `@EventListener` 동기·markPaid 호출
- `notification/handler/NotificationPaymentCompletedHandler.java` — AFTER_COMMIT 비동기

**Inventory 차감 핸들러**: **0건**

멱등 키 SoT: `domain-events.md E2 L54` — "pgTransactionId 멱등성 키·OrderItem.item_status=PAID 가드로 재차감 skip"

### §3.3 E3 PaymentFailed 핸들러 (예약 해제·동기)

**발행처**: `backend/src/main/java/com/zslab/mall/payment/entity/Payment.java` L165
```java
domainEvents.add(new PaymentFailed(id, orderId, failureCode, occurredAt));
```
→ PaymentService L164 `events.forEach(eventPublisher::publishEvent)` 경유 발행.

**PaymentFailed record** (`payment/event/PaymentFailed.java` L11-16):
```java
public record PaymentFailed(
        Long paymentId, Long orderId, String failureCode, LocalDateTime occurredAt) {}
```
items[] **없음** — Javadoc L7-9: "Inventory 예약 해제 핸들러는 orderId로 OrderItem을 직접 조회 후 처리한다(D-30)."

**기존 소비자 grep** (`grep "PaymentFailed" **/*.java` 결과):
- `payment/event/PaymentFailed.java` (record 정의)
- `payment/entity/Payment.java` (new PaymentFailed(...))
- **소비 핸들러: 0건**

**도메인 이벤트 E3 소비 주체** (`domain-events.md E3 L62-69`): "Inventory(예약 해제)" — 미구현.

> **⚠ WARN-1 (P0)**: E3 PaymentFailed 소비 핸들러가 전혀 없음. domain-events.md §2 E3 소비자 Inventory(예약 해제)가 미구현 상태.

### §3.4 E9 ClaimCompleted 핸들러 (복구·동기·type별 분기)

**발행처**: `backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java` L291-293
```java
eventPublisher.publishEvent(new ClaimCompleted(
        claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
        claim.getType(), claim.getStatus(), LocalDateTime.now()));
```

**ClaimCompleted record** (`claim/event/ClaimCompleted.java` L16-23):
```java
public record ClaimCompleted(
        Long claimId, String claimPublicId, Long orderItemId,
        ClaimType claimType, ClaimStatus status, LocalDateTime occurredAt) {}
```
`variant_id`·`quantity`·`refund_amount` **없음** — `domain-events.md E9 L150` 페이로드 스펙(`variant_id, quantity, refund_amount`)과 불일치.

**기존 소비자**:
- `claim/handler/ClaimCompletedHandler.java` L46-66: type별 분기(CANCEL/RETURN/EXCHANGE) → OrderItem 종결 전이
- `notification/handler/NotificationClaimCompletedHandler.java` — 비동기 알림

**Inventory 복구 핸들러**: **0건**

**type별 분기 실측** (`ClaimCompletedHandler.java` L46-66):
```java
OrderItemStatus requestedStatus = switch (event.claimType()) {
    case CANCEL  -> OrderItemStatus.CANCEL_REQUESTED;
    case RETURN  -> OrderItemStatus.RETURN_REQUESTED;
    case EXCHANGE -> OrderItemStatus.EXCHANGE_REQUESTED;
};
OrderItemStatus terminalStatus = switch (event.claimType()) {
    case CANCEL  -> OrderItemStatus.CANCELLED;
    case RETURN  -> OrderItemStatus.RETURNED;
    case EXCHANGE -> OrderItemStatus.EXCHANGED;
};
```

멱등: L56-61 `if (orderItem.getItemStatus() != requestedStatus) → no-op + log.info`

> **⚠ WARN-2 (P1)**: ClaimCompleted record에 variant_id·quantity·refund_amount 없음. domain-events.md E9 L150 스펙 불일치. Inventory 복구 핸들러는 orderItemId로 OrderItem 재조회 후 variant_id·quantity 획득 필요. 재조회 전략 결정 의제 포함.

### §3.5 inventory/handler/ 패키지 존재 여부

```powershell
# PowerShell Get-ChildItem -Recurse 결과
inventory/
  entity/    ← Inventory.java, InventoryHistory.java
  enums/     ← InventoryHistoryChangeType.java
  repository/ ← InventoryRepository.java, InventoryHistoryRepository.java
# handler/ 없음
```

**inventory/handler/ 패키지 0건** — Track 17 신설 대상.

---

## §4. inventory-policy.md 실측

파일: `docs/domain/inventory-policy.md` **존재 확인**

| 절 | 내용 |
|---|---|
| §1 SoT | Inventory 단일 SoT·3컬럼 정의·판매가능 판정 |
| §2 예약 정책 | E1 OrderPlaced → reserved += qty·동기 트랜잭션·available 검증 |
| §3 차감 정책 | E2 PaymentCompleted → on_hand−=qty · reserved−=qty · HistoryHistory ORDER |
| §4 복구 정책 | E9 ClaimCompleted type별 분기 (결제전취소/CANCEL/RETURN/EXCHANGE) |
| §5 available 갱신 | 애플리케이션 갱신·D-09 정합 |
| §6 InventoryHistory 기록 | on_hand 변동만 기록·RESERVE/RELEASE 미기록(M-11) |
| §7 외부 이연 | 동시성 락·만료 해제·Reservation Tracking 도입 금지(D-17) |

**참조 관계**:
- `domain-events.md §2 E9 L155`: "재고 복구/차감 상세는 inventory-policy.md §4 참조."
- `decisions.md D-17 L542`: "inventory-policy.md §7 외부 이연에 'Reservation Tracking' 항목 추가."

→ inventory-policy.md **존재** — 부재 처치 후보 α/β/γ OOS.

---

## §5. 동시성 정책 실측 (D-09 잔여 결정)

**D-09 원문** (`decisions.md D-09 L281`):
> "동시성: 낙관적 락(version) 또는 비관적 락(SELECT FOR UPDATE) — 권장만, 구현 단계 확정."

**inventory-policy.md §5 L68**:
> "동시성: 동시 주문/차감 시 정합성을 위해 낙관적 락(version) 또는 비관적 락(SELECT FOR UPDATE)을 권장한다 — 구체 전략은 구현 단계 확정(§7)."

**@Version 필드 실측 grep**:
```bash
# grep "@Version" backend/src/main/java/**/*.java → 0건
```
전 Aggregate @Version 필드 0건 — Inventory.java @Version 없음 확인 (V1 DDL에도 version 컬럼 없음).

> **⚠ WARN-3 (P1)**: @Version 필드 전 Aggregate 0건. Inventory 차감/예약 동시성 보호 미구현. Track 17 구현 시 D-09 락 전략 확정 필요. V1 DDL에 version 컬럼 없으므로 낙관적 락 도입 시 Flyway 마이그레이션(V8) 동반 필요.

---

## §6. EXCHANGE 트랜잭션 분리 실측 (D-08)

**D-08 EXCHANGE 행** (`decisions.md D-08 L258`):
> "교환: 회수분 복구 + 교환품 신규 차감 (트랜잭션 분리) — RETURN(회수) + ORDER(재출고)"

**기존 EXCHANGE 흐름 패턴**:
- `claim/handler/ClaimRefundCompletedHandler.java` L48-51: EXCHANGE skip — "EXCHANGE는 Refund 미경유(refundAmount==0)·ExchangeDeliveryCompletedHandler(PR-2)가 종결"
- `claim/handler/ExchangeDeliveryCompletedHandler.java` L59-107: E5 DeliveryCompleted 소비·delivery.claimId 가드·EXCHANGE type 전용·OrderItem EXCHANGED → ClaimService.markCompleted 2단계

**EXCHANGE 재고 복구+차감 트랜잭션 분리 구현 후보** (실측만 — 결정 OOS):
- 후보 A: ClaimCompleted E9(회수 복구) + ExchangeDeliveryCompletedHandler 내 재출고 차감 — 2 핸들러 2 트랜잭션
- 후보 B: ClaimCompleted E9에 EXCHANGE 분기 내 RETURN 복구 + 신규 InventoryDeductService.deduct 호출 분리

> **⚠ WARN-4 (P2)**: EXCHANGE 트랜잭션 경계 미확정. D-08 "트랜잭션 분리" 원칙만 있고 Service primitive 분할 vs 핸들러 내 분리 등 구체 패턴 결정은 Claude.ai 결정 라운드 이연.

---

## §7. 멱등 패턴 5종 카탈로그 적용 영역 실측 (D-100 Q1 γ)

**D-100 Q1 박제 1줄** (`decisions.md D-100 Q1 L5190`):
> "이벤트 핸들러 멱등 가드 표준은 패턴 5종(A canTransitionTo 자연 흡수·B Service primitive 가드·C type 분기 게이트·D catch+skip+log.warn·E 다단계 복합) 카탈로그 박제·신규 핸들러는 패턴 선택 사유를 Javadoc 1줄 의무 기록"

**도메인 이벤트 멱등성 원칙** (`domain-events.md §1 L17`):
> "재고 차감/복구 핸들러는 OrderItem.item_status를 가드로 사용해 재처리를 무시한다."

### 4 핸들러 패턴 후보 식별

| 이벤트 | 핸들러 | 후보 패턴 | 멱등 가드 근거 SoT |
|---|---|---|---|
| E1 예약 | InventoryReserveHandler (신설) | **B** Service primitive 가드 | domain-events.md E1 L41: "order_id 기준 1회·재예약 방지" · inventory-policy.md §2: "available < qty → 롤백" |
| E2 차감 | InventoryDeductHandler (신설) | **A** canTransitionTo 자연 흡수 | domain-events.md E2 L54: "OrderItem.item_status=PAID 가드로 재차감 skip" |
| E3 해제 | InventoryReleaseHandler (신설) | **B** Service primitive 가드 | domain-events.md E3 L66: "orderId·reserved 가드" · inventory-policy.md §2: "reserved 가드" |
| E9 복구 | InventoryRestoreHandler (신설) | **C+A** type 분기 + 종결값 가드 | domain-events.md E9 L152: "OrderItem.item_status 종결값 가드로 재복구 skip" · ClaimCompletedHandler 패턴 정합 |

> 패턴 최종 선택 사유는 Claude.ai 결정 라운드에서 확정 후 신규 핸들러 Javadoc 1줄 의무 기록 (D-100 Q1 γ).

---

## §8. 관측성 인프라 활용 영역

### §8.1 TracedEventPublisher 의존 패턴

파일: `backend/src/main/java/com/zslab/mall/common/observability/TracedEventPublisher.java`

5 Service 의존 실측:
- `OrderService.java` (L56 eventPublisher 필드·L84 publishEvent(OrderPlaced))
- `PaymentService.java` (L162-164 events.forEach(eventPublisher::publishEvent))
- `DeliveryService.java` — TracedEventPublisher 의존
- `RefundService.java` — TracedEventPublisher 의존
- `ClaimService.java` (L56 필드)

TracedEventPublisher.publishEvent (L32-36): `recordPublished(event.getClass().getSimpleName())` + `delegate.publishEvent(event)`.

**Inventory 이벤트 발행 시 자동 활용 가능**: Inventory Service가 TracedEventPublisher를 주입하면 `zslab.event.published` 카운터 자동 기록 (β″′ 패턴 정합).

### §8.2 EventMetricsRecorder 패턴

파일: `backend/src/main/java/com/zslab/mall/common/observability/EventMetricsRecorder.java`

- `recordPublished(String eventName)` L23-28: counter "zslab.event.published{event=<name>}"
- `recordFailed(String eventName)` L30-35: counter "zslab.event.failed{event=<name>}"

### §8.3 핸들러 신설 시 적용 의무

D-100 Q6 박제 1줄 (`decisions.md D-100 Q6 L5222`):
> "로그 prefix 표준 = Aggregate 단위 [<Aggregate>] 의무 (Claim·Order·Payment·Refund·Delivery·Notification·Settlement·**Inventory**)"

4 핸들러 catch 블록 신설 시:
- `[Inventory]` prefix 의무
- catch 6 표준키 (`event·target_type·target_id·action·correlationId·handler`) 의무
- `eventMetricsRecorder.recordFailed(eventName)` 호출 의무

---

## §9. 통합 테스트 5중 의무 실측 (D-100 Q8 β)

**D-100 Q8 박제 1줄** (`decisions.md D-100 Q8 L5228`):
> "AFTER_COMMIT 핸들러 검증 테스트는 NO @Transactional + TransactionTemplate + @RecordApplicationEvents + LT-02 try-finally + D-91 FK 부모 그래프 시드 5중 의무"

### §9.1 기존 통합 테스트 적용 사례

| 테스트 파일 | 5중 의무 적용 | 확인 위치 |
|---|---|---|
| `ClaimEventIntegrationTest.java` | 전건 | Javadoc L27-41 |
| `DeliveryEventIntegrationTest.java` | 전건 | Javadoc L24-38 |

**ClaimEventIntegrationTest FK 그래프 시드** (L36-41):
> "order_item·order의 직접 FK 부모(user·seller·product·product_variant)를 시드한다. 더 상위 FK(category·option_value)는 FK_CHECKS=0 시드로 우회."

### §9.2 Inventory 통합 테스트 FK 부모 그래프

V1 DDL inventory FK 체인:
```
category (더미·FK_CHECKS=0 우회)
  └─ product (product.category_id)
      ├─ product_variant (variant.product_id)
      │     └─ inventory (inventory.variant_id → product_variant)  ← 타겟
      └─ option_value (더미·FK_CHECKS=0 우회)
seller (product.seller_id)
```

**직접 FK 부모 시드 필수**: `seller → product → product_variant → inventory`

핸들러가 inventory UPDATE하면 Hibernate 전체 컬럼 UPDATE → variant_id FK 재검증 → product_variant 행 존재 필요 → product/seller 시드 필요.

category·option_value는 inventory 핸들러가 해당 테이블 UPDATE 안 함 → FK_CHECKS=0 우회 가능 (ClaimEventIntegrationTest 패턴 정합).

---

## §10. 라이브 트랩 처치 의무 실측

| 트랩 | Inventory 적용 여부 | 근거 |
|---|---|---|
| **LT-01** CHAR(30) public_id @JdbcTypeCode | **해당 없음** | Inventory.java Javadoc L20 "public_id 미부여" · V1 DDL L488-505 public_id 컬럼 없음 · invariants.md §4 COM-1 12개 목록에 Inventory 미포함 |
| **LT-02** SET FK_CHECKS try-finally | **의무 (통합 테스트 신설 시)** | live-traps.md LT-02 · ClaimEventIntegrationTest L41 "try-finally로 =1 복원과 1:1 짝" |
| **LT-03** @SQLRestriction @Entity 직접 선언 | **해당 없음** | Inventory.java L32 AbstractFullAuditableEntity 상속 (soft-delete abstract 비상속) · V1 DDL deleted_at 없음 |

---

## §11. WARN 후보 사전 식별

| # | 영역 | 우선순위 | 내용 | 근거 SoT |
|---|---|---|---|---|
| WARN-1 | §3.3 E3 | **P0** | E3 PaymentFailed 소비 핸들러 전무 — domain-events.md §2 E3 소비자 "Inventory(예약 해제)" 미구현 | domain-events.md E3 L62-69 · grep PaymentFailed 파일 2건(event+entity) · 핸들러 0건 |
| WARN-2 | §3.4 E9 | **P1** | ClaimCompleted record payload에 variant_id·quantity·refund_amount 없음 — domain-events.md E9 스펙 불일치 · Inventory 복구 핸들러는 OrderItem 재조회 필요 | domain-events.md E9 L150 vs ClaimCompleted.java L16-23 |
| WARN-3 | §5 | **P1** | @Version 전 Aggregate 0건 — Inventory 동시성 보호 미구현 · 낙관적 락 도입 시 V8 마이그레이션 필요 | decisions.md D-09 L281 · grep @Version 0건 |
| WARN-4 | §6 | **P2** | EXCHANGE 트랜잭션 분리 구현 패턴 미확정 — D-08 원칙만 있고 Service primitive 분할 vs 핸들러 내 분리 등 결정 부재 | decisions.md D-08 L258 · ExchangeDeliveryCompletedHandler.java |
| WARN-5 | §3.1 | **P2** | E1 OrderPlaced record items[] 없음 — Inventory 예약 핸들러는 orderId로 OrderItem N건 재조회 필요 · N+1 주의 (findByOrderId + fetch join 필요) | domain-events.md E1 L40-43 · OrderPlaced.java L14-18 |
| WARN-6 | §9.2 | **P2** | Inventory 통합 테스트 FK 부모 그래프 미확정 — category·option_value FK_CHECKS=0 우회 여부 실측 필요 (핸들러가 해당 테이블 UPDATE 안 함으로 우회 가능 추정이나 실측 의무) | live-traps.md LT-02 · D-91 |

**총계**: P0 1건 · P1 2건 · P2 3건 = 6건

---

## §12. 정찰 범위 외 (OOS)

- **동시성 락 메커니즘 확정**: 낙관적 @Version vs 비관적 SELECT FOR UPDATE 선택 → Claude.ai 결정 라운드
- **EXCHANGE 트랜잭션 경계 확정**: 회수 복구 + 재출고 차감 2 트랜잭션 경계 → 결정 라운드
- **Inventory 도메인 행위 메서드 시그니처 확정**: reserve/release/deduct/restore 파라미터 · 예외 타입 → 결정 라운드
- **InventoryService vs Aggregate 행위 책임 분리**: available 재계산 위치(Domain vs Service) → 결정 라운드
- **Track 18+ 후속**: Outbox 패턴 · 자동 구매확정 타이머 · Reservation Tracking → 후속 트랙
- **E10 InventoryAdjusted 발행 여부**: 선택 이벤트 — 운영자 입고/출고/조정 → 별도 결정

---

## §13. 결정 라운드 의제 후보 (Q0~Q8)

외부 검토 1·2차 권장 (A급 트랙).

| # | 의제 | 배경 SoT |
|---|---|---|
| **Q0** | 트랙 식별자 = Track 17 Inventory 도메인 행위 구현 | D-100 Q0 선례 |
| **Q1** | Inventory 도메인 행위 신설 범위 (reserve/release/deduct/restore 메서드 시그니처·파라미터·예외 타입) | inventory-policy.md §2~§4 · D-08 L244 |
| **Q2** | 동시성 락 정책 확정 — 낙관적 @Version(V8 마이그레이션 동반) vs 비관적 SELECT FOR UPDATE(메서드 단위 락) | decisions.md D-09 L281 · WARN-3 |
| **Q3** | EXCHANGE 트랜잭션 경계 확정 — 후보 A(E9 회수 + ExchangeHandler 재출고 분리) vs 후보 B(InventoryRestoreHandler 내 분리) | decisions.md D-08 L258 · WARN-4 |
| **Q4** | 4 핸들러 멱등 패턴 선택 사유 Javadoc 확정 (D-100 Q1 γ 의무 이행) | decisions.md D-100 Q1 L5190 · §7 패턴 후보 |
| **Q5** | inventory/handler/ 패키지 위치 확정 — inventory/handler/ 단독 vs 이벤트 발행처 패키지 분산 | aggregate-boundary.md §2.3 |
| **Q6** | InventoryHistory @Immutable 미도입 유지 여부 재확인 — Javadoc "A2 결정" 근거 실측·신규 행위 신설 시 변경 필요 여부 | InventoryHistory.java L26-27 |
| **Q7** | ClaimCompleted record payload 처치 — domain-events.md E9 스펙(variant_id·quantity) 추가 vs Inventory 핸들러 재조회 전략 | WARN-2 · domain-events.md E9 L150 · ClaimCompleted.java |
| **Q8** | PR 분할 방식 확정 — 단일 PR(E1+E2+E3+E9 4핸들러+Service+도메인행위+통합테스트) vs 이벤트별 분리 | D-100 Q12 β′ 선례 |

**총계**: Q0~Q8 = 9건 (결정은 Claude.ai 결정 라운드에서만)

---

## §14. 인용 인덱스 (SoT 파일 목록)

| 파일 | 실측 범위 |
|---|---|
| `docs/architecture-baseline/decisions.md` | D-07 L226·D-08 L244·D-09 L273·D-17 L538·D-100 L5128 |
| `docs/architecture-baseline/invariants.md` | §2.8 INV-1~6·§4 COM-1 |
| `docs/architecture-baseline/domain-events.md` | §1 L9-17·§2 E1 L33-43·E2 L45-55·E3 L57-69·E9 L143-157 |
| `docs/architecture-baseline/aggregate-boundary.md` | §2.3 L50-55 |
| `docs/architecture-baseline/state-machine.md` | §3 OrderItem L63-96 |
| `docs/domain/inventory-policy.md` | §1-§7 전건 |
| `docs/troubleshooting/live-traps.md` | LT-01 L23·LT-02 L56·LT-03 L93 |
| `backend/.../inventory/entity/Inventory.java` | L18-51 전건 |
| `backend/.../inventory/entity/InventoryHistory.java` | L21-80 전건 |
| `backend/.../inventory/enums/InventoryHistoryChangeType.java` | L6-12 |
| `backend/.../inventory/repository/InventoryRepository.java` | L12-19 |
| `backend/.../inventory/repository/InventoryHistoryRepository.java` | L6 |
| `backend/.../order/event/OrderPlaced.java` | L14-18 |
| `backend/.../payment/event/PaymentCompleted.java` | L16-22 |
| `backend/.../payment/event/PaymentFailed.java` | L11-16 |
| `backend/.../claim/event/ClaimCompleted.java` | L16-23 |
| `backend/.../claim/handler/ClaimCompletedHandler.java` | L46-66 |
| `backend/.../claim/handler/ClaimRefundCompletedHandler.java` | L43-59 |
| `backend/.../claim/handler/ExchangeDeliveryCompletedHandler.java` | L59-107 |
| `backend/.../order/service/OrderService.java` | L84 |
| `backend/.../payment/service/PaymentService.java` | L162-164 |
| `backend/.../claim/service/ClaimService.java` | L291-293 |
| `backend/.../common/observability/TracedEventPublisher.java` | L32-36 |
| `backend/.../common/observability/EventMetricsRecorder.java` | L23-35 |
| `backend/.../payment/handler/OrderEventHandler.java` | L30-36 |
| `backend/src/main/resources/db/migration/V1__init.sql` | L488-522 |
| `backend/.../claim/integration/ClaimEventIntegrationTest.java` | L27-41 |
| `backend/.../delivery/integration/DeliveryEventIntegrationTest.java` | L24-38 |

---

## §15. 사전 구현 정찰 (2026-07-01)

> 정찰자: Claude Sonnet 4.6 (read-only)  
> 목적: Track 17 PR-A 구현 프롬프트 정밀도 확보  
> 베이스: main HEAD 5fdd01b

---

### §15.1 @Lock / SELECT FOR UPDATE 사용 사례

**실행 명령:**
```
Get-ChildItem -Recurse backend/src/main/java -Filter *Repository.java | Select-String -Pattern "@Lock|LockModeType|FOR UPDATE"
```

**매칭 결과 — 1파일 3라인:**

| 파일 | 라인 | 내용 |
|---|---|---|
| `payment/repository/PaymentRepository.java` | L5 | `import jakarta.persistence.LockModeType;` |
| `payment/repository/PaymentRepository.java` | L21 | Javadoc: `Payment 행을 비관적 쓰기 락(SELECT ... FOR UPDATE)으로 조회한다(Track 5·D-68)` |
| `payment/repository/PaymentRepository.java` | L24 | `@Lock(LockModeType.PESSIMISTIC_WRITE)` |

**전체 패턴 인용 (`PaymentRepository.java:20-26`):**
```java
/**
 * Payment 행을 비관적 쓰기 락(SELECT ... FOR UPDATE)으로 조회한다(Track 5·D-68). PAY-1 사후 재검증 시 동시 환불
 * 확정을 직렬화해 과환불을 차단한다. 모든 변수는 :id 바인딩이다.
 */
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Payment p WHERE p.id = :id")
Optional<Payment> findByIdForUpdate(@Param("id") Long id);
```

**판단:** 프로젝트 SoT는 `PaymentRepository.findByIdForUpdate` 1건. `@Lock(PESSIMISTIC_WRITE)` + `@Query(JPQL)` + 메서드명 `findBy<Key>ForUpdate` 패턴이 컨벤션이며, PR-A의 `findByVariantIdForUpdate` 명명은 이 패턴에 직접 근거한다.

---

### §15.2 DomainException 계열

**실행 명령:**
```
Get-ChildItem -Recurse backend/src/main/java -Filter *Exception.java | Select-Object FullName | Sort-Object FullName
```

**전건 목록 (16개):**

| 파일 | 패키지 | 상속 | 생성자 시그니처 |
|---|---|---|---|
| `checkout/exception/CheckoutItemMismatchException.java` | checkout | `RuntimeException` | `(String message)` |
| `checkout/exception/CheckoutItemNotFoundException.java` | checkout | `RuntimeException` | `(String message)` |
| `checkout/exception/IdempotencyKeyInProgressException.java` | checkout | `RuntimeException` | `(String message)` |
| `claim/exception/ClaimInvalidStateException.java` | claim | `RuntimeException` | `(String message)` |
| `claim/exception/ClaimNotFoundException.java` | claim | `RuntimeException` | `(String message)` |
| `common/exception/MalformedRequestException.java` | common | `RuntimeException` | `(String message)` |
| `common/exception/UnauthenticatedException.java` | common | `RuntimeException` | `(String message)` |
| `order/exception/OrderNotFoundException.java` | order | `RuntimeException` | `(String message)` |
| `order/exception/OrderNotPayableException.java` | order | `RuntimeException` | `(OrderNotPayableReason reason, String message)` |
| `payment/exception/InvalidCallbackException.java` | payment | `RuntimeException` | `(String message)` |
| `payment/exception/PaymentAlreadyCompletedException.java` | payment | `RuntimeException` | `(String message)` |
| `payment/exception/PaymentInProgressException.java` | payment | `RuntimeException` | `(String message)` |
| `payment/gateway/PaymentGatewayException.java` | payment.gateway | `RuntimeException` | `(String attemptKey, String failureCode, String message)` |
| `refund/exception/RefundIdempotentNoOpException.java` | refund | `RuntimeException` | `(String message)` |
| `refund/exception/RefundInvariantViolationException.java` | refund | `RuntimeException` | `(String message)` |
| `refund/exception/RefundNotFoundException.java` | refund | `RuntimeException` | `(String message)` |

**Inventory 관련 Exception 존재 여부:** 0건. `inventory/` 패키지에 `exception` 서브패키지 없음.

**공통 base class:** 없음 — 전건 `RuntimeException` 직접 상속.

**ClaimInvalidStateException 실측 (`claim/exception/ClaimInvalidStateException.java:16-20`):**
```java
public class ClaimInvalidStateException extends RuntimeException {
    public ClaimInvalidStateException(String message) {
        super(message);
    }
}
```
Javadoc: `CLM-3 책임·422 매핑(D-50·D-89 Q3)`. `RefundInvariantViolationException`과 동일한 `RuntimeException + String message` 단순 패턴으로 D-89 Q3에서 재활용 결정 확정됨.

**판단:** Inventory invariant 위반 예외는 신규 `InventoryInvariantViolationException extends RuntimeException(String message)` 신설이 패턴 정합 (`RefundInvariantViolationException` 1:1 대응). 기존 Inventory 도메인 exception 0건이므로 `inventory/exception/` 패키지 신설 필요.

---

### §15.3 Inventory 도메인 4파일 전건 재검증

#### Inventory.java (`inventory/entity/Inventory.java:1-51`)

**상속:** `AbstractFullAuditableEntity`

**Lombok 어노테이션:**
- `@Getter` (전체 필드)
- `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
- `@ToString(onlyExplicitlyIncluded = true)`
- `@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)`

**JPA 어노테이션:** `@Entity`, `@Table(name = "inventory")`

**필드 순서 · 타입 · 어노테이션 (L34-50):**

| # | 필드명 | 타입 | 어노테이션 |
|---|---|---|---|
| 1 | `id` | `Long` | `@Id @GeneratedValue(IDENTITY) @EqualsAndHashCode.Include @ToString.Include` |
| 2 | `variantId` | `Long` | `@Column(name="variant_id", nullable=false, updatable=false)` |
| 3 | `quantityOnHand` | `int` | `@Column(name="quantity_on_hand", nullable=false)` |
| 4 | `quantityReserved` | `int` | `@Column(name="quantity_reserved", nullable=false)` |
| 5 | `quantityAvailable` | `int` | `@Column(name="quantity_available", nullable=false)` |

**setter 부재 확인:** 정적 팩토리 없음·도메인 행위 메서드 없음·setter 없음. 쓰기 대상 필드: `quantityOnHand`, `quantityReserved`, `quantityAvailable` 3필드 (도메인 행위로 mutate 대상).

#### InventoryHistory.java (`inventory/entity/InventoryHistory.java:1-81`)

**상속:** `AbstractCreatedOnlyEntity`

**정적 팩토리 시그니처 (L62-68) — 정확 인용:**
```java
public static InventoryHistory create(
        Inventory inventory,
        InventoryHistoryChangeType changeType,
        int quantityDelta,
        String referenceType,
        Long referenceId,
        String reason)
```
파라미터 6개·순서 확정: `inventory → changeType → quantityDelta → referenceType → referenceId → reason`

필수값 검증 (L69-71): `inventory`, `changeType`, `referenceType` 3개. `referenceId`·`reason` nullable.

#### InventoryHistoryChangeType.java (L6-12)

```java
public enum InventoryHistoryChangeType {
    ORDER, CANCEL, RETURN, ADJUST, INBOUND, OUTBOUND
}
```
PR-A 4 이벤트 → `ORDER`(E1 예약), `ORDER`(E2 차감, 동일 값 재사용), `CANCEL`(E3 해제), `RETURN`(E9 복구) — 정책 문서 재확인 필요 (§OOS 참조).

#### InventoryRepository.java (L12-19)

현재 메서드 2건:
- `findByVariantId(Long variantId)` → `Optional<Inventory>`
- `findByVariantIdIn(Collection<Long> variantIds)` → `List<Inventory>`

`findByVariantIdForUpdate` **미존재** — PR-A에서 신규 추가 필요.

**판단:** InventoryService 도메인 행위 4건 시그니처는 Inventory.java 5필드(variantId·quantityOnHand·quantityReserved·quantityAvailable)·InventoryHistory.create 6파라미터·ChangeType 6값집합으로 완전 확정. Repository에 `@Lock(PESSIMISTIC_WRITE)` `findByVariantIdForUpdate` 1건 추가 필요.

---

### §15.4 인접 Aggregate 단위 테스트 패턴

**실행 명령:**
```
Get-ChildItem -Recurse backend/src/test/java -Filter *.java | Where-Object { $_.Name -match "^(Claim|Order|Payment)Test\.java$" }
```

**매칭 2건:**
- `order/entity/OrderTest.java`
- `payment/entity/PaymentTest.java`

#### OrderTest.java 패턴

**import 패턴 (L1-12):**
```java
import static org.assertj.core.api.Assertions.assertThat;
// JUnit 5
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
```
- Mockito: **미사용** (순수 도메인 객체 테스트)
- given: private helper `newOrder()`, `newItem()` 사용
- when: 도메인 메서드 직접 호출
- then: `assertThat(...).isEqualTo(...)`, `assertThat(...).allSatisfy(...)`
- `@Nested` + `@DisplayName`: 관련 케이스 그룹핑

#### PaymentTest.java 패턴

**import 추가 (L4):**
```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```
- `assertThatThrownBy` 패턴 (L50-56):
```java
assertThatThrownBy(() -> Payment.create(null, PaymentMethod.CARD, AMOUNT, ATTEMPT_KEY, NOW))
        .isInstanceOf(IllegalArgumentException.class);
assertThatThrownBy(() -> payment.complete(NOW, PG_PROVIDER, PG_TID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("불법 결제 상태 전이");
```
- given: `private Payment pendingPayment()` helper + `ReflectionTestUtils.setField`로 비공개 필드 설정
- static 상수: `private static final Long ORDER_ID = 100L;` 패턴

**판단:** PR-A 단위 테스트 SoT — JUnit 5 + AssertJ (`assertThat`, `assertThatThrownBy`) 조합·Mockito 없음·private helper 메서드로 given 구성·`assertThatThrownBy().isInstanceOf().hasMessageContaining()` 3단 체이닝이 표준.

---

### §15.5 인접 Aggregate 도메인 메서드 throw 패턴

#### Claim.java — `attachExchangeDelivery` (L202-215)

```java
public void attachExchangeDelivery(Long deliveryId, Long deliveryOrderItemId) {
    if (deliveryId == null || deliveryOrderItemId == null) {
        throw new IllegalArgumentException("attachExchangeDelivery: deliveryId·deliveryOrderItemId는 필수입니다.");
    }
    if (this.type != ClaimType.EXCHANGE) {
        throw new ClaimInvalidStateException(
                "교환 배송 연결은 EXCHANGE 클레임에서만 가능합니다: type=" + this.type);
    }
    if (!this.orderItemId.equals(deliveryOrderItemId)) {
        throw new ClaimInvalidStateException(
                "Delivery-OrderItem 불일치: claim.orderItemId=" + this.orderItemId
                        + ", delivery.orderItemId=" + deliveryOrderItemId);
    }
}
```

**private helper `transitionTo` (L222-227):**
```java
private void transitionTo(ClaimStatus next) {
    if (!status.canTransitionTo(next)) {
        throw new ClaimInvalidStateException("불법 클레임 상태 전이: " + status + " → " + next);
    }
    this.status = next;
}
```

#### OrderItem.java — `changeStatus` (L117-125)

```java
public void changeStatus(OrderItemStatus next) {
    if (next == null) {
        throw new IllegalArgumentException("전이 목표 상태는 null일 수 없습니다.");
    }
    if (!itemStatus.canTransitionTo(next)) {
        throw new IllegalStateException("불법 품목 상태 전이: " + itemStatus + " → " + next);
    }
    itemStatus = next;
}
```

**throw 패턴 정리:**

| 위반 유형 | 예외 | 메시지 형식 |
|---|---|---|
| 필수값 null | `IllegalArgumentException` | `"<메서드명>: <파라미터명>는 필수입니다."` |
| 불법 상태 전이 | `IllegalStateException` | `"불법 <도메인> 상태 전이: " + current + " → " + next` |
| 비즈니스 규칙 위반 | 도메인 전용 `RuntimeException` | `"<한국어 설명>: <필드>=<값>"` |

**판단:** Inventory 도메인 행위 4건 throw 패턴 — 필수값(null·음수) → `IllegalArgumentException`, 불변조건(예약>가용·차감>예약) → `InventoryInvariantViolationException(String message)` 신설(RefundInvariantViolationException 1:1 대응), 메시지는 `"불법 재고 <동작>: variantId=" + ... + ", 요청=" + ... + ", 가용=" + ...` 한국어 형식.

---

### §15.6 정찰 범위 외 (OOS)

- **InventoryHistoryChangeType 매핑 확정:** E2(PaymentCompleted → 차감) 시 `ORDER` vs `INBOUND`/`OUTBOUND` 선택 — `inventory-policy.md §3` 재확인 필요 (본 정찰 범위 외)
- **GlobalExceptionHandler InventoryInvariantViolationException HTTP 매핑:** 신규 예외의 HTTP 상태코드(422 vs 409) — `GlobalExceptionHandler.java` 수정 범위 확인 필요 (PR-A 구현 프롬프트에서 명시)
- **AbstractFullAuditableEntity vs AbstractCreatedOnlyEntity 구분:** Inventory는 Full·InventoryHistory는 CreatedOnly — 실측 확인 완료, 상세 필드 목록은 정찰 범위 외 (기존 §3.2 참조)
- **findByVariantIdForUpdate 락 범위:** 단일 variant 1행 비관적 락 — 배치(findByVariantIdIn) 락 필요 여부는 PR-A 요건 문서 재확인 필요

---

### §15.7 PR-A 구현 판단 요약 (5절 종합)

| 절 | 판단 |
|---|---|
| §15.1 @Lock | `PaymentRepository.findByIdForUpdate` 1건이 SoT — `@Lock(PESSIMISTIC_WRITE)` + `@Query(JPQL)` + `findByVariantIdForUpdate` 명명 확정 |
| §15.2 DomainException | 공통 base class 없음·전건 `RuntimeException(String message)` 단순 패턴 — `InventoryInvariantViolationException` 신설(RefundInvariantViolationException 1:1 대응)·inventory/exception 패키지 신설 |
| §15.3 Inventory 4파일 | 5필드(variantId·onHand·reserved·available)·도메인 행위 0건·HistoryHistory.create 6파라미터 순서 확정·`findByVariantIdForUpdate` 미존재(신규 추가) |
| §15.4 단위 테스트 | JUnit 5 + AssertJ·Mockito 없음·private helper given·`assertThatThrownBy().isInstanceOf().hasMessageContaining()` 3단 체이닝 표준 |
| §15.5 throw 패턴 | 필수값 → `IllegalArgumentException`, 불변조건 → `InventoryInvariantViolationException`, 메시지 한국어 `"불법 재고 <동작>: variantId=... , 요청=... , 가용=..."` 형식 |

**PR-A 구현 프롬프트 진입 가능 여부: YES** — 판단 5건 전건 확정.

---

## §16. PR-B 사전 구현 정찰 (2026-07-01)

> 정찰자: Claude Sonnet 4.6 (read-only)  
> 목적: Track 17 PR-B (4 핸들러 + 통합 테스트 + exchange() 신설) 구현 프롬프트 정밀도 확보  
> 베이스: main HEAD c7867cd8662049c6035eb865d185900a15ed7539

---

### §16.0 정찰 시점·베이스

**HEAD 실측 명령:**
```bash
git rev-parse HEAD
# c7867cd8662049c6035eb865d185900a15ed7539
```

**PR-A 머지 커밋 상속 확인:**

`git log --oneline -5` 실측:
```
c7867cd Merge pull request #86 from zeus721-zslab/feat/track-17-inventory-domain
15c02da docs(track-17): 정찰 보고서 + 사전 구현 정찰 §15 (D-101 §2·§4·§5·§9)
79058c0 test(inventory): Track 17 PR-A 도메인 행위 + Service 단위 테스트 20건 (D-101 §9)
018d683 feat(inventory): Track 17 PR-A 도메인 행위 4건 + Service + 비관락 (D-101 §2·§4·§9)
5fdd01b Merge pull request #85 from zeus721-zslab/chore/d101-companion-updates
```

**PR-A 상속 항목 (본 정찰 정의 SoT):**

| 파일 | PR-A 변경 내용 |
|---|---|
| `inventory/entity/Inventory.java` | 도메인 행위 4건 (`reserve`·`release`·`commitReservation`·`restoreStock`) + `recalculateAvailable` 신설 |
| `inventory/repository/InventoryRepository.java` | `findByVariantIdForUpdate` 추가 |
| `inventory/service/InventoryService.java` | **신규** — 4 행위 진입점 + History 생성 책임 |
| `inventory/exception/InventoryInvariantViolationException.java` | **신규** |

**465 baseline 문서 인용 (실측 미실행):**  
commit 79058c0 메시지 "Track 17 PR-A 도메인 행위 + Service 단위 테스트 20건" → §15 베이스 445 + 20 = **465 tests** (D-101 §9 정합).

---

### §16.1 이벤트 record 4건 실측

#### OrderPlaced (`order/event/OrderPlaced.java`)

**필드 카탈로그 (L14-18):**

| 순서 | 필드명 | 타입 |
|---|---|---|
| 1 | `publicId` | `String` |
| 2 | `orderId` | `Long` |
| 3 | `occurredAt` | `LocalDateTime` |

**Javadoc 소비자 목록 (L9-12):**  
"Track 12 `notification/handler/NotificationOrderPlacedHandler`가 본 이벤트를 소비한다(D-95 Q4·E1 박제·orderId 재조회 기반 적재·E1 첫 소비자)."

**D-30 사실 통지 정합:** payload 식별자·시각 3 필드 한정. items[]·도메인 상태 복제 없음 (L8 "소비측은 publicId/orderId로 재조회").

**variant_id/quantity 포함 여부:** 없음 — InventoryOrderPlacedHandler는 orderId로 OrderItem 재조회 필요.

---

#### PaymentCompleted (`payment/event/PaymentCompleted.java`)

**필드 카탈로그 (L16-22):**

| 순서 | 필드명 | 타입 |
|---|---|---|
| 1 | `paymentId` | `Long` |
| 2 | `orderId` | `Long` |
| 3 | `amount` | `Long` |
| 4 | `pgTransactionId` | `String` |
| 5 | `occurredAt` | `LocalDateTime` |

**Javadoc 소비자 목록 (L13-15):**  
"Track 12 `notification/handler/NotificationPaymentCompletedHandler`가 본 이벤트를 소비한다(D-95 Q4·E2 박제·동기 `OrderEventHandler`(markPaid) 소비와 공존·AFTER_COMMIT으로 자연 분리)."

**멱등 키:** `pgTransactionId` (L11: "동일 PG 거래의 중복 통지는 소비측에서 무시한다").

**D-30 정합:** items[] 없음 — 소비측은 orderId 재조회.

---

#### PaymentFailed (`payment/event/PaymentFailed.java`)

**필드 카탈로그 (L11-16):**

| 순서 | 필드명 | 타입 |
|---|---|---|
| 1 | `paymentId` | `Long` |
| 2 | `orderId` | `Long` |
| 3 | `failureCode` | `String` |
| 4 | `occurredAt` | `LocalDateTime` |

**Javadoc 소비자 목록:**  
소비자 명시 0건 (D-30 정합 주석만 존재). L8-9: "Inventory 예약 해제 핸들러는 `orderId`로 OrderItem을 직접 조회 후 처리한다(도메인 상태 복제 방지)."

**D-30 사실 통지 정합:** items[] 없음 (L8 명시).

> **기존 WARN-1 (P0) 재확인**: E3 PaymentFailed 소비 핸들러 0건 — inventory/handler/ 패키지 미존재·PR-B 신설 대상.

---

#### ClaimCompleted (`claim/event/ClaimCompleted.java`)

**필드 카탈로그 (L16-23):**

| 순서 | 필드명 | 타입 |
|---|---|---|
| 1 | `claimId` | `Long` |
| 2 | `claimPublicId` | `String` |
| 3 | `orderItemId` | `Long` |
| 4 | `claimType` | `ClaimType` |
| 5 | `status` | `ClaimStatus` |
| 6 | `occurredAt` | `LocalDateTime` |

**Javadoc 소비자 목록 (L10-15):**  
"발행 시점은 `ClaimService.markCompleted`의 save 직후(D-29). 소비측 핸들러 `ClaimCompletedHandler`(OrderItem 종결 전이·Track 9 PR-C 소관). Track 12 `notification/handler/NotificationClaimCompletedHandler` 추가 소비."

**variant_id·quantity·refund_amount 포함 여부:** **없음** — D-101 §8 β (record 무변경·orderItemId 재조회) 정합 확인.

**D-101 §8 β 재조회 체인 검증:**  
- `event.claimId()` → `ClaimRepository.findById()` → `claim.getOrderItemId()` → `OrderItemRepository.findById()` → `orderItem.getVariantId()`·`orderItem.getQuantity()`  
- 전 단계 SoT 실측 결과 §16.3으로 확인 (Claim.orderItemId L41-42 존재·JpaRepository.findById 표준·OrderItem.variantId L47-48·OrderItem.quantity L53-54 존재).

---

### §16.2 OrderItem entity·OrderItemRepository 실측

**파일:** `backend/src/main/java/com/zslab/mall/order/entity/OrderItem.java`

**핵심 필드 카탈로그:**

| 필드명 | 라인 | 타입 | 어노테이션 |
|---|---|---|---|
| `variantId` | L47-48 | `Long` | `@Column(name = "variant_id", nullable = false)` |
| `quantity` | L53-54 | `int` | `@Column(name = "quantity", nullable = false)` |
| `unitPrice` | L56-58 | `Long` | `@Column(name = "unit_price", nullable = false)` |
| `totalPrice` | L59-60 | `Long` | `@Column(name = "total_price", nullable = false)` |
| `itemStatus` | L62-64 | `OrderItemStatus` | `@Enumerated(EnumType.STRING) @Column(name = "item_status")` |

**도메인 메서드:**  
- `create(...)` L76-103: 정적 팩토리  
- `changeStatus(OrderItemStatus next)` L117-125: 상태 전이·`canTransitionTo` 검증  
- `markPaid()` L130-132: `changeStatus(PAID)` 래퍼  
- `assignOrder(Order)` L108-110: package-private (Order 연결용)

**OrderItemRepository (`order/repository/OrderItemRepository.java`):**

| 메서드 | 라인 | 반환 타입 | 비고 |
|---|---|---|---|
| `findByOrderId(Long orderId)` | L16 | `List<OrderItem>` | 주문별 품목 전건 |
| `findByOrderIdIn(Collection<Long> orderIds)` | L18 | `List<OrderItem>` | 배치 조회 |
| `findByPublicId(String publicId)` | L24 | `Optional<OrderItem>` | public_id 해소 |
| `findOrderIdById(Long id)` | L30-31 | `Optional<Long>` | 경량 projection (order_id만) |

**N+1 회피용 fetch join 메서드 존재 여부:**  
```bash
# grep "@EntityGraph\|fetch join\|EntityGraph" backend/src/main/java/com/zslab/mall/order/repository/OrderItemRepository.java
# 결과: 0건
```
`@EntityGraph` 또는 `JOIN FETCH` 쿼리 **0건** — E1 핸들러 재조회 시 `findByOrderId` 재사용 가능 (OrderItem 자체가 타겟·@ManyToOne Order는 `@Getter(AccessLevel.NONE)` 미접근·N+1 해당 없음).

**E1 재조회 전략 판단:** `findByOrderId(orderId)` 1회 쿼리로 OrderItem 리스트 획득 → 각 item의 `variantId`·`quantity` 추출 → `InventoryService.reserve(variantId, qty)` 순차 호출. 신규 메서드 불필요.

---

### §16.3 Claim entity·ClaimRepository 실측

**파일:** `backend/src/main/java/com/zslab/mall/claim/entity/Claim.java`

**핵심 필드 카탈로그:**

| 필드명 | 라인 | 타입 | 어노테이션 |
|---|---|---|---|
| `orderItemId` | L41-42 | `Long` | `@Column(name = "order_item_id", nullable = false)` |
| `type` | L44-46 | `ClaimType` | `@Enumerated(EnumType.STRING) @Column(name = "type", nullable = false, updatable = false)` |
| `status` | L54-56 | `ClaimStatus` | `@Enumerated(EnumType.STRING) @Column(name = "status", nullable = false)` |
| `previousOrderItemStatus` | L70-72 | `OrderItemStatus` | `@Enumerated(EnumType.STRING) @Column(name = "previous_order_item_status", nullable = false, updatable = false)` |
| `pickedUpAt` | L67-68 | `LocalDateTime` | `@Column(name = "picked_up_at")` |
| `requestedBy` | L58-59 | `Long` | `@Column(name = "requested_by")` |

**ClaimType enum 값 3건 (`claim/enums/ClaimType.java` L10-16):**  
`CANCEL`, `RETURN`, `EXCHANGE` — 3값 전건 확인.

**ClaimRepository (`claim/repository/ClaimRepository.java`) 메서드 카탈로그:**

| 메서드 | 라인 | 비고 |
|---|---|---|
| `findById(Long id)` | JpaRepository 상속 표준 | 추가 선언 불필요 |
| `findByPublicId(String publicId)` | L16 | public_id 해소 |
| `existsActiveByOrderItemId(Long orderItemId)` | L23-28 | REQUESTED·APPROVED 활성 클레임 존재 여부 |
| `findAllByRequestedBy(Long requestedBy, Pageable pageable)` | L31 | Buyer 목록 |

**InventoryClaimCompletedHandler 재조회 체인 정합:**  
`event.claimId()` → `claimRepository.findById(claimId)` (JpaRepository 표준·추가 선언 불필요) → `claim.getOrderItemId()` → `orderItemRepository.findById(orderItemId)` → `orderItem.getVariantId()`·`orderItem.getQuantity()`.

---

### §16.4 사전 재고 조회 진입점 실측

**대상: D-101 §10 α (OrderService.placeOrder 사전 조회)**

**OrderService.createOrder (`order/service/OrderService.java` L57-87):**  
의존 필드: `orderRepository`, `orderStatusResolver`, `eventPublisher` (L38-48). `InventoryRepository` 주입 **없음**. 재고 조회 로직 **없음**.

**CheckoutService (`checkout/service/CheckoutService.java`):**  
의존 필드 목록 (L61-67):
```java
private final OrderService orderService;
private final PaymentService paymentService;
private final OrderRepository orderRepository;
private final ProductRepository productRepository;
private final ProductVariantRepository productVariantRepository;
private final InventoryRepository inventoryRepository;   // L66 — 주입 존재
private final OrderIdempotencyKeyRepository idempotencyRepository;
```

**`InventoryRepository` 사용 위치:**  
- `CheckoutService.revalidatePayable(Order order)` L229-255: `inventoryRepository.findByVariantIdIn(variantIds)` L237 — **재결제 경로만** (L103 `retryPayment` → L110 `revalidatePayable` 호출).

**신규 주문 경로 (`checkout` L165 `createOrder`):**  
`orderService.createOrder(createCommand)` L201 호출 전 InventoryRepository 사용 **없음** — D-101 §10 α 사전 재고 조회 **미구현**.

**D-101 §10 α 신설 위치 실측 결정:**  
- 신설 위치: `CheckoutService.createOrder()` L165 내부 · `orderService.createOrder()` L201 호출 **직전**  
- 사용 메서드: `InventoryRepository.findByVariantIdIn(variantIds)` (read-only·D-101 §10 "findByVariantIdIn 사용" 예외 박제 정합)  
- 재결제 경로 `revalidatePayable`의 INV 로직 L247-253과 동일 패턴 재사용 가능

> **⚠ WARN-3 (P1)**: CheckoutService.createOrder 신규 주문 경로의 α 사전 재고 조회 미구현 — D-101 §10 α PR-B 신설 대상.

---

### §16.5 통합 테스트 SoT 실측

#### ClaimEventIntegrationTest (`claim/integration/ClaimEventIntegrationTest.java`)

**클래스 Javadoc 의무 항목 실측 (L27-42):**

| D-100 Q8 β 5중 의무 | 실측 | 라인 |
|---|---|---|
| NO @Transactional (클래스 레벨) | **충족** — 클래스 어노테이션 없음 | L43 `@SpringBootTest` 단독 |
| TransactionTemplate | **충족** — `tx = new TransactionTemplate(txManager)` | L85 |
| @RecordApplicationEvents | **미사용** — import·어노테이션 전건 부재 | L1-247 전범위 |
| LT-02 try-finally | **충족** — `try { FK_CHECKS=0 ... } finally { FK_CHECKS=1 }` | L167-173 |
| D-91 FK 부모 그래프 시드 | **충족** — user·seller·product·product_variant 4단계 | L178-191 |

**LT-02 try-finally 패턴 (L164-173):**
```java
tx.executeWithoutResult(s -> {
    try {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        seedingWork.run();
    } finally {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
});
```

**D-91 FK 부모 그래프 시드 패턴 (L178-191):**  
INSERT 순서: `user` → `seller` → `product(category_id=DUMMY_FK_ID)` → `product_variant(option1_value_id=DUMMY_FK_ID)`.  
category·option_value: `DUMMY_FK_ID = 9101L`로 FK_CHECKS=0 우회 (L53-54 Javadoc "핸들러가 해당 테이블을 UPDATE하지 않아 FK_CHECKS=0 시드로 우회").

**검증 패턴:** JdbcTemplate 직접 조회 (L231-241) — @RecordApplicationEvents 미사용.

---

#### DeliveryEventIntegrationTest (`delivery/integration/DeliveryEventIntegrationTest.java`)

**5중 의무 실측:**

| D-100 Q8 β 5중 의무 | 실측 | 라인 |
|---|---|---|
| NO @Transactional | **충족** | L39 `@SpringBootTest` 단독 |
| TransactionTemplate | **충족** — `tx.executeWithoutResult(s -> deliveryService.markShipping(...))` | L94-95 |
| @RecordApplicationEvents | **미사용** | L1-229 전범위 |
| LT-02 try-finally | **충족** — `try { FK_CHECKS=0 } finally { FK_CHECKS=1 }` | L138-174 |
| D-91 FK 부모 그래프 시드 | **충족** — user·seller·product·product_variant·order·order_item·delivery | L140-173 |

---

#### inventory 통합 테스트 존재 여부

```bash
# glob: backend/src/test/java/com/zslab/mall/inventory/integration/**
# 결과: No files found
```

`inventory/integration/` 디렉토리 **0건** — PR-B 신설 대상.

> **⚠ WARN-4 (P2)**: D-100 Q8 박제 5중 의무 중 `@RecordApplicationEvents`가 기존 통합 테스트 SoT 2건(`ClaimEventIntegrationTest`·`DeliveryEventIntegrationTest`) 모두 **미사용**. 실측 SoT 패턴은 4중(NO @Transactional + TransactionTemplate + LT-02 + D-91 시드). InventoryEventIntegrationTest 신설 기준 결정 필요 — D-100 Q8 박제 의무 그대로 vs 실측 4중 SoT 정합.

---

### §16.6 exchange() 옵션 α/β/γ 판단 근거 실측

#### `Inventory.commitReservation` 사전 조건 실측

파일: `inventory/entity/Inventory.java` L98-111

```java
public void commitReservation(int qty) {
    requirePositiveQty(qty, "commitReservation");
    if (quantityReserved - qty < 0) {          // INV-3: 예약 부족 가드
        throw new InventoryInvariantViolationException(
                "불법 재고 차감·예약 부족: variantId=" + variantId + ", 요청=" + qty + ", 예약=" + quantityReserved);
    }
    if (quantityOnHand - qty < 0) {            // INV-4: 실물 부족 가드
        throw new InventoryInvariantViolationException(...);
    }
    quantityOnHand -= qty;
    quantityReserved -= qty;
    recalculateAvailable();
}
```

**사전 조건**: `quantityReserved >= qty` (INV-3). 교환품에 사전 `reserve()` 없이 직접 호출 시 `quantityReserved == 0` → qty > 0 이면 **항상 throw**.

---

#### EXCHANGE 흐름에서 교환품 신규 발송과 예약 개념 실측

`ExchangeDeliveryCompletedHandler.java` L59-107 실측:  
- E5 DeliveryCompleted 소비 → `delivery.getClaimId()` 체크 L67 → EXCHANGE type 가드 L78 → OrderItem EXCHANGED 전이 L84-103 → `claimService.markCompleted(claim.getId())` L106  
- 교환품 신규 발송 완료 시 **별도 reserve() 발생 없음** — E1 OrderPlaced는 원래 주문 생성 시 1회 발행이며, 교환품 출고는 Delivery 생성·배송 완료 흐름(E4·E5) 경유.

**판단**: 교환품에 대한 inventory.reserve()가 선행되지 않으므로, E9 핸들러에서 교환품 차감 시 `commitReservation(newQty)` 단독 호출은 INV-3 위반.

---

#### D-101 §5 예시 코드 원문 (`decisions.md L5608-5617`)

```java
@Transactional
public void exchange(Long returnVariantId, int returnQty,
                     Long newVariantId, int newQty, Long claimId) {
    Inventory ret = repo.findByVariantIdForUpdate(returnVariantId)...;
    ret.restoreStock(returnQty);
    historyRepo.save(InventoryHistory.create(ret, RETURN, returnQty, "claim", claimId, ...));

    Inventory neu = repo.findByVariantIdForUpdate(newVariantId)...;
    neu.commitReservation(newQty);  // 또는 reserve+commit 2단계
    historyRepo.save(InventoryHistory.create(neu, ORDER, -newQty, "claim", claimId, ...));
}
```

**"또는 reserve+commit 2단계" = D-101 §5 미해소 코멘트 — 결정 라운드 이연 상태.**

---

#### 3 옵션 비교표

| 옵션 | 구현 | INV-3 안전 | 의미 정합 | 신규 메서드 |
|---|---|---|---|---|
| **α** reserve → commitReservation 2단계 | `reserve(newQty)` 후 `commitReservation(newQty)` 동일 TX | **안전** (reserve가 quantityReserved 먼저 증가) | 교환 = 예약+즉시확정·비즈니스 의미 어색 | 불필요 |
| **β** deductDirectly 신설 | `on_hand -= qty`·INV-4만 체크·신규 `deductDirectly(qty)` 메서드 | **안전** (reserved 무관) | 교환 직접 차감·의미 정합 | **신설 필요** (PR-A 미포함) |
| **γ** commitReservation 단독 | `commitReservation(newQty)` 직접 | **INV-3 위반** (교환품 reserve 없음) | 의미 오류·사용 불가 | 불필요 |

**실측 근거 요약:**  
- γ: `commitReservation` L100-103 INV-3 가드 = 교환품 reserve 없는 컨텍스트에서 반드시 throw → **불가**  
- α: PR-A 기존 `reserve()`·`commitReservation()` 재사용·신규 코드 없음. 단, `reserve`는 "예약 점유" 개념이나 교환 출고 컨텍스트에서 즉시 확정되므로 트레이드오프 존재  
- β: 도메인 의미 정합·신규 메서드 `deductDirectly(int qty)` Inventory.java 추가 필요 (PR-B 범위 확장)

> **⚠ WARN-1 (P0 이전·결정 의제)**: exchange() α/β/γ 옵션 미확정. D-101 §5 코멘트 "또는 reserve+commit 2단계" 잔존. γ 불가 확인·α vs β 결정 필요.

---

### §16.7 4 핸들러 신설 위치·의존 실측

#### inventory/handler/ 디렉토리 부재 확인

```bash
# glob: backend/src/main/java/com/zslab/mall/inventory/handler/**
# 결과: No files found
```

`inventory/handler/` **0건** — PR-B 신설 패키지.

---

#### D-101 §3 핸들러 목록 SoT (`decisions.md L5570-5575`)

| 핸들러 | 이벤트 | 동작 |
|---|---|---|
| `InventoryOrderPlacedHandler` | E1 OrderPlaced | OrderItem별 `reserve` |
| `InventoryPaymentCompletedHandler` | E2 PaymentCompleted | OrderItem별 `commitReservation` |
| `InventoryPaymentFailedHandler` | E3 PaymentFailed | orderId 재조회 후 OrderItem별 `release` |
| `InventoryClaimCompletedHandler` | E9 ClaimCompleted | claimId 재조회 후 type별 분기 |

---

#### InventoryClaimCompletedHandler 의존 실측

재조회 체인: `event.claimId()` → `ClaimRepository.findById()` → `claim.getOrderItemId()` → `OrderItemRepository.findById()` → `variantId`·`quantity` → `InventoryService.restoreStock / exchange()`

**필요 의존 목록:**  
- `InventoryService` (도메인 행위 진입점)  
- `ClaimRepository` (claimId → Claim 재조회·JpaRepository.findById)  
- `OrderItemRepository` (orderItemId → OrderItem 재조회·JpaRepository.findById)

---

#### 기존 패턴 재사용 SoT 라인 인용

**ClaimCompletedHandler.java L38-39:**
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void onClaimCompleted(ClaimCompleted event) { ... }
```

**ExchangeDeliveryCompletedHandler.java L59-60:**
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void handle(DeliveryCompleted event) { ... }
```

4 핸들러 전건 `@TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)` 패턴 적용 (D-101 §3 정합·기존 SoT 1:1 재사용).

**E9 type 분기 SoT (`ClaimCompletedHandler.java` L46-55):**
```java
OrderItemStatus requestedStatus = switch (event.claimType()) {
    case CANCEL  -> OrderItemStatus.CANCEL_REQUESTED;
    case RETURN  -> OrderItemStatus.RETURN_REQUESTED;
    case EXCHANGE -> OrderItemStatus.EXCHANGE_REQUESTED;
};
```
`InventoryClaimCompletedHandler` switch 패턴 1:1 재사용 — InventoryService 호출 대상만 교체 (`CANCEL/RETURN → restoreStock`·`EXCHANGE → exchange()`).

---

### §16.8 WARN 카탈로그

| # | 영역 | 우선순위 | 내용 | 근거 SoT |
|---|---|---|---|---|
| **WARN-1** | §16.6 | **P0 (결정)** | exchange() 옵션 α/β/γ 미확정 — D-101 §5 "또는 reserve+commit 2단계" 코멘트 잔존·γ 불가(INV-3)·α vs β 결정 필요 | Inventory.java L100-103·decisions.md D-101 §5 L5615 |
| **WARN-2** | §16.4 | **P1** | CheckoutService.createOrder 신규 주문 경로 α 사전 재고 조회 미구현 — D-101 §10 α PR-B 신설 대상·`inventoryRepository.findByVariantIdIn` 사용 위치 재결제 경로(L237)만·신규 주문 경로(L165) 미적용 | CheckoutService.java L165 L229-255·decisions.md D-101 §10 L5693 |
| **WARN-3** | §16.5 | **P1** | @RecordApplicationEvents D-100 Q8 박제 5중 포함이나 ClaimEventIntegrationTest·DeliveryEventIntegrationTest 실측 4중(미사용) — InventoryEventIntegrationTest 신설 기준 결정 필요 | ClaimEventIntegrationTest.java L1-247 전범위·DeliveryEventIntegrationTest.java L1-229·decisions.md D-100 Q8 L5228 |
| **WARN-4** | §16.7 | **P2** | InventoryRepository.java Javadoc L14 "Track 4 read-only·D-57" 잔존 — PR-A `findByVariantIdForUpdate` 추가 후 미갱신·D-101 §4 α 비관락 일원화 설명 누락 | InventoryRepository.java L14 |
| **WARN-5** | §16.1 | **P2** | PaymentFailed record Javadoc L8-9 "Inventory 예약 해제 핸들러는 orderId로 OrderItem을 직접 조회 후 처리" — Inventory 핸들러 소비자 미목록화 (단순 설계 코멘트이나 PR-B 구현 완료 후 Javadoc 업데이트 필요) | PaymentFailed.java L8-9 |

**총계**: P0 1건 · P1 2건 · P2 2건 = 5건

> P0 기준: WARN-1 (exchange() 옵션)은 결정 미확정으로 PR-B 구현 전 결정 라운드 진입 필수.

---

### §16.9 판단 필요 3건 정리

#### 판단 1: D-101 §5 exchange() 옵션 α/β/γ

**배경 SoT:**  
- D-101 §5 코드 예시 `decisions.md L5615`: "또는 reserve+commit 2단계" — 미결  
- `Inventory.commitReservation` INV-3 가드: L100-103  
- 교환품 사전 reserve 없음: ExchangeDeliveryCompletedHandler L59-107 실측

**실측 근거 강도: 강함**  
- γ: 교환품 reserve 없는 컨텍스트 → INV-3 위반·**불가** (코드 실측)  
- α: 기존 메서드만 재사용·신규 없음. EXCHANGE 컨텍스트에서 reserve 후 즉시 commit = 예약 점유 없이 동기 처리이므로 의미 불일치나 기능은 정상  
- β: `deductDirectly(qty)` 신설 필요 (PR-A 미포함·Inventory.java 변경 동반)

**결정 라운드 진입 준비도: 높음** — α/β 2안 정확 대비·근거 실측 완료.

---

#### 판단 2: E1 재조회 전략 — 기존 메서드 재사용 vs 신설

**배경 SoT:**  
- `OrderItemRepository.findByOrderId(Long orderId)` L16 존재 확인  
- N+1 회피용 fetch join 메서드 0건 (§16.2 실측)  
- OrderItem은 단순 컬럼 Aggregate (ManyToOne Order는 `@Getter(AccessLevel.NONE)` — 핸들러 내 미접근)

**실측 근거 강도: 강함**  
- `findByOrderId(orderId)` 1회 IN-쿼리로 OrderItem 전건 로드 → 핸들러 for loop 내 `variantId`·`quantity` 추출 → N+1 없음  
- fetch join 신설 불필요: OrderItem 로드 자체가 목적이며 연관 Lazy 탐색 없음  
- **결론: `findByOrderId` 재사용 확정·신설 불필요**

**결정 라운드 진입 준비도: 완료** — 신설 불필요 확정. 별도 결정 불요.

---

#### 판단 3: E9 type 분기 방식 — ClaimCompletedHandler switch 패턴 1:1 재사용 정합 근거

**배경 SoT:**  
- `ClaimCompletedHandler.java` L46-55: `switch(event.claimType())` 패턴 존재 (§16.7 인용)  
- D-101 §3 `InventoryClaimCompletedHandler`: "claimId 재조회 후 type별 분기 (§5)"  
- ClaimType 3값: CANCEL·RETURN·EXCHANGE (§16.3 확인)

**실측 근거 강도: 강함**  
- CANCEL/RETURN: `InventoryService.restoreStock(variantId, qty, CANCEL/RETURN, ...)` — 기존 메서드 직접  
- EXCHANGE: `InventoryService.exchange(returnVariantId, returnQty, newVariantId, newQty, claimId)` — §16.6 WARN-1 exchange() 미확정에 의존  
- `switch` 1:1 재사용 자체는 정합 — EXCHANGE case가 exchange() 구현 완료 후 자연 충족  
- **결론: switch 패턴 재사용 확정·EXCHANGE case 구현은 판단 1 결정 후**

**결정 라운드 진입 준비도: 높음** — exchange() 옵션(판단 1) 확정 시 즉시 구현 가능.

---

### §16.10 OOS 명시

정찰 범위 외 항목:

- **E10 InventoryAdjusted 발행** — D-101 §13 α 완전 OOS (Track 18+)  
- **Outbox·이벤트 저장소** — D-100 Q2 γ 트리거 미충족·OOS  
- **Spring Security 정식 도입** — Track 18+ OOS  
- **ADJUST·INBOUND·OUTBOUND 진입점** — D-101 §13 OOS (enum 유지·메서드 미신설)  
- **교환품 다른 variant 조달** — Claim 엔티티 newVariantId 필드 없음·현 모델에서 동일 variant 교체 가정. 다른 variant 교환 지원은 데이터 모델 변경 동반·OOS  
- **@RecordApplicationEvents 도입 판단** — WARN-3 에스컬레이션·결정 라운드 또는 사용자 직접 확인 필요 (OOS for 본 정찰)  
- **InventoryRepository Javadoc 갱신** — WARN-4·PR-B 병행 처리 가능하나 정찰 단계 변경 금지·OOS
