# Track 14 PR-2 EXCHANGE — 정찰 실측 보고서 (recon-report2)

**작성일**: 2026-06-30  
**기준 커밋**: f271f94 (Track 14 PR-1 RETURN 머지 직후·working tree clean)  
**목적**: D-98 박제 본문 PR-2 EXCHANGE 범위(Q3·Q5·Q13·신규 4·수정 4) 정합 확인. STEP 1~8 구현 진입 전 read-only 실측 박제.  
**기조 5 준수**: 전건 라인 번호 직접 인용·glob/Read 실측 결과만 박제·추정 0건.

---

## §1 정찰 보강 배경·범위

recon-supplement.md는 PR-1 RETURN STEP 1~6 진입 직전 10 의제를 박제했다.  
본 보고서는 PR-2 EXCHANGE STEP 1~8 진입을 위한 신규 15 의제를 실측한다.

- **신규 마이그레이션 번호 확정** (STEP 1)  
- **Delivery.java·DeliveryService.java 수정 포인트 측정** (STEP 2·3·4)  
- **핸들러 부재·기존 핸들러 SoT 패턴 측정** (STEP 5·6)  
- **테스트 인벤토리 측정** (STEP 7)  
- **문서 갱신 포인트 측정** (STEP 8)  

라인 번호는 `Read`·`Grep` 도구 실측 결과 직접 인용(추정 없음).

---

## §2 의제 1 — db/migration 디렉토리 현황 (STEP 1에서 사용)

**실측 대상**: `backend/src/main/resources/db/migration/`

현재 파일 목록 (glob 실측):
```
V1__init.sql
V2__seller_anonymization.sql
V3__payment_track3.sql
V4__order_idempotency_key.sql
V5__refund_constraints.sql
V6__add_claim_pickup_and_previous_status.sql
```

**결론**:
- 마지막 번호: **V6** (PR-1에서 신설된 `V6__add_claim_pickup_and_previous_status.sql` 확인)
- STEP 1 신규 마이그레이션 후보: **V7__add_claim_id_to_delivery.sql**

---

## §3 의제 2 — Delivery.java 현황 (STEP 2·3)

**실측 대상**: `backend/src/main/java/com/zslab/mall/delivery/entity/Delivery.java`

```java
// line 39-58 — 현재 필드 목록
@Column(name = "order_item_id", nullable = false, updatable = false)
private Long orderItemId;                                        // line 40

@Enumerated(EnumType.STRING)
@Column(name = "carrier", nullable = false)
private DeliveryCarrier carrier;                                 // line 44

@Column(name = "tracking_no", length = 100)
private String trackingNo;                                       // line 48

@Enumerated(EnumType.STRING)
@Column(name = "status", nullable = false)
private DeliveryStatus status;                                   // line 51

@Column(name = "shipped_at")
private LocalDateTime shippedAt;                                 // line 54

@Column(name = "delivered_at")
private LocalDateTime deliveredAt;                               // line 58
```

```java
// line 85-92 — markShipping 시그니처
public void markShipping(String trackingNo, LocalDateTime shippedAt) {
    if (!status.canTransitionTo(DeliveryStatus.SHIPPING)) {
        throw new IllegalStateException("불법 배송 상태 전이: " + status + " → " + DeliveryStatus.SHIPPING);
    }
    this.trackingNo = trackingNo;
    this.shippedAt = shippedAt;
    this.status = DeliveryStatus.SHIPPING;
}
```

**결론**:
- `claim_id` 필드 부재 확인 (line 39-58 전건 실측·해당 컬럼 없음). STEP 2에서 V7 마이그레이션 + `@Column(name = "claim_id")` Long 필드 신설 대상.
- `markShipping` (line 85): `trackingNo`·`shippedAt` 파라미터 null 검사 없음. D-98 Q3 보강 의무 — STEP 3에서 null 가드 추가 대상.

---

## §4 의제 3 — DeliveryService.java 현황 (STEP 4)

**실측 대상**: `backend/src/main/java/com/zslab/mall/delivery/service/DeliveryService.java`

```java
// line 41-49 — markShipping 시그니처
public void markShipping(Long deliveryId, String trackingNo) {
    Delivery delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new IllegalArgumentException("배송을 찾을 수 없습니다: deliveryId=" + deliveryId));
    delivery.markShipping(trackingNo, LocalDateTime.now());
    deliveryRepository.save(delivery);
    eventPublisher.publishEvent(new DeliveryStarted(
            delivery.getId(), delivery.getOrderItemId(), delivery.getCarrier(),
            delivery.getTrackingNo(), LocalDateTime.now()));
}

// line 57-64 — markDelivered 시그니처
public void markDelivered(Long deliveryId) {
    Delivery delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new IllegalArgumentException("배송을 찾을 수 없습니다: deliveryId=" + deliveryId));
    delivery.markDelivered(LocalDateTime.now());
    deliveryRepository.save(delivery);
    eventPublisher.publishEvent(new DeliveryCompleted(
            delivery.getId(), delivery.getOrderItemId(), delivery.getDeliveredAt(), LocalDateTime.now()));
}
```

**결론**:
- `registerExchangeShipment` 메서드 부재 확인 (line 1-65 전건 실측·해당 메서드 없음). D-98 Q3 신설 대상.
- 기존 `markShipping`(line 41)·`markDelivered`(line 57) 시그니처: actor 비의존 Long deliveryId 단일 파라미터(markShipping은 trackingNo 추가). `registerExchangeShipment` 시그니처 설계 SoT로 참조.

---

## §5 의제 4 — Claim.java attachExchangeDelivery 부재 확인 (STEP 2)

**실측 대상**: `backend/src/main/java/com/zslab/mall/claim/entity/Claim.java`

```java
// line 140-148 — confirmPickup 메서드 (검증 패턴 SoT)
public void confirmPickup(LocalDateTime pickedUpAt) {
    if (pickedUpAt == null) {
        throw new IllegalArgumentException("confirmPickup: pickedUpAt는 필수입니다.");
    }
    if (this.status != ClaimStatus.APPROVED) {
        throw new ClaimInvalidStateException("수거 확인은 APPROVED 클레임에서만 가능합니다: " + this.status);
    }
    this.pickedUpAt = pickedUpAt;
}
```

**결론**:
- `attachExchangeDelivery` 메서드 부재 확인 (line 1-193 전건 실측·해당 메서드 없음). D-98 Q13 신설 대상.
- `confirmPickup` (line 140-148) 패턴: null 가드 → APPROVED 상태 가드 → 필드 설정. `attachExchangeDelivery` 구현 시 1:1 참조 SoT.

---

## §6 의제 5 — DeliveryCompletedHandler.java claim_id 분기 부재 (STEP 5)

**실측 대상**: `backend/src/main/java/com/zslab/mall/order/handler/DeliveryCompletedHandler.java`

```java
// line 29-32 — 생성자 주입 (OrderItemRepository·OrderService만 주입)
public DeliveryCompletedHandler(OrderItemRepository orderItemRepository, OrderService orderService) {
    this.orderItemRepository = orderItemRepository;
    this.orderService = orderService;
}

// line 35-57 — onDeliveryCompleted 전체
@EventListener
public void onDeliveryCompleted(DeliveryCompleted event) {
    OrderItem orderItem = orderItemRepository.findById(event.orderItemId()).orElse(null);
    if (orderItem == null) {
        log.warn("[Delivery] DeliveryCompleted 소비·주문 품목 미발견: orderItemId={}", event.orderItemId());
        return;
    }
    if (orderItem.getItemStatus() == OrderItemStatus.DELIVERED) {
        log.info("[Delivery] OrderItem 이미 DELIVERED → 전이 건너뜀: orderItemId={}", event.orderItemId());
        return;
    }
    if (!orderItem.getItemStatus().canTransitionTo(OrderItemStatus.DELIVERED)) {
        log.warn("[Delivery] OrderItem 상태={} → DELIVERED 전이 불가·건너뜀: orderItemId={}",
                orderItem.getItemStatus(), event.orderItemId());
        return;
    }
    orderItem.changeStatus(OrderItemStatus.DELIVERED);
    ...
}
```

**결론**:
- `claim_id != null` early return 분기 부재 확인 (line 35-57 전건 실측). D-98 Q5 보강 의무 — STEP 5에서 DeliveryRepository 주입 + Delivery 재조회 + claim_id != null 분기 추가 대상.
- `DeliveryRepository` 주입 없음 (line 29-32: `OrderItemRepository`·`OrderService`만). STEP 5에서 필드·생성자 주입 추가 필요.

---

## §7 의제 6 — NotificationDeliveryCompletedHandler.java 현황 (STEP 6)

**실측 대상**: `backend/src/main/java/com/zslab/mall/notification/handler/NotificationDeliveryCompletedHandler.java`

```java
// line 32-41 — handle 전체
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void handle(DeliveryCompleted event) {
    try {
        notificationService.recordDeliveryCompleted(event);
    } catch (RuntimeException exception) {
        log.warn("notification log failed; event={} target_type={} target_id={} action=manual_review",
                "DeliveryCompleted", "DELIVERY", event.deliveryId(), exception);
    }
}
```

**결론**:
- `claim_id` 분기 없음 (line 34-41 전건 실측). D-98 Q5 보강 의무 — STEP 6에서 Delivery 재조회(DeliveryRepository 주입) + `claim_id != null` early return 분기 추가 대상.
- Delivery 재조회 패턴 없음: 현재 `event`를 그대로 `notificationService.recordDeliveryCompleted`에 전달(line 36). 교환 배송 완료 시 일반 배송 완료 알림 발송 차단이 미구현 상태.

---

## §8 의제 7 — claim/handler/ 하위 glob (STEP 5)

**실측 대상**: `backend/src/main/java/com/zslab/mall/claim/handler/`

glob 결과 (4건):
```
ClaimCompletedHandler.java
ClaimRefundCompletedHandler.java
ClaimRejectedHandler.java
ClaimRequestedHandler.java
```

**결론**:
- `ExchangeDeliveryCompletedHandler.java` 부재 확인. D-98 Q5 신설 대상 — STEP 5에서 `claim/handler/ExchangeDeliveryCompletedHandler.java` 신규 작성.

---

## §9 의제 8 — ClaimRefundCompletedHandler EXCHANGE skip 패턴 SoT (STEP 5)

**실측 대상**: `backend/src/main/java/com/zslab/mall/claim/handler/ClaimRefundCompletedHandler.java`

```java
// line 40-59 — onRefundCompleted type 분기 패턴
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void onRefundCompleted(RefundCompleted event) {
    Claim claim = claimRepository.findById(event.claimId()).orElse(null);
    if (claim == null) {
        log.warn("[Claim] RefundCompleted 소비·클레임 미발견: claimId={}", event.claimId());
        return;
    }
    if (claim.getType() == ClaimType.EXCHANGE) {
        // EXCHANGE는 Refund 미경유(refundAmount==0)·ExchangeDeliveryCompletedHandler(PR-2)가 종결
        log.info("[Claim] RefundCompleted 수신·type=EXCHANGE → 본 핸들러 미전이: claimId={}", event.claimId());
        return;
    }
    if (claim.getStatus() != ClaimStatus.APPROVED) {
        log.info("[Claim] 클레임 상태={} → 종결 전이 건너뜀: claimId={}", claim.getStatus(), event.claimId());
        return;
    }
    claimService.markCompleted(event.claimId());
}
```

**결론**:
- line 48-52: `EXCHANGE` type → log.info + return (skip 패턴) 확인.
- PR-2 신규 `ExchangeDeliveryCompletedHandler`의 type 분기 패턴 참조 SoT: `claim.getType() == ClaimType.EXCHANGE` 조건 가드 형태(단, 신규 핸들러는 EXCHANGE만 처리하므로 역방향 적용 — EXCHANGE가 아니면 skip).

---

## §10 의제 9 — ClaimPickedUpHandler 패턴 SoT (STEP 5)

**실측 대상**: `backend/src/main/java/com/zslab/mall/refund/handler/ClaimPickedUpHandler.java`

```java
// line 43-44 — 트랜잭션 정책
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void handle(ClaimPickedUp event) {
    if (event.claimType() != ClaimType.RETURN) {
        // line 47-49 — type 분기 (CANCEL·EXCHANGE 미대상)
        log.info("[Refund] ClaimPickedUp 수신·type={} → 자동 환불 미대상: claimId={}", event.claimType(), event.claimId());
        return;
    }
    ...
    try {
        refundService.initiate(event.claimId(), orderItem.getTotalPrice());
    } catch (RuntimeException exception) {
        // line 59-61 — 실패 보상: NotificationLog 적재 (핸들러 밖 전파 없음)
        notificationService.recordRefundFailed(event);
    }
}
```

**결론**:
- `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW` (line 43-44): PR-2 신규 핸들러 트랜잭션 정책 참조 SoT. `ExchangeDeliveryCompletedHandler`도 동일 정책 적용.
- type 분기 (line 46-49): RETURN 한정 처리·비대상 type → log.info + return. 신규 핸들러 EXCHANGE 한정 처리 패턴 1:1 대칭.
- catch RuntimeException (line 56-61): 실패 시 핸들러 밖 전파 없이 알림 적재. D-95/D-96 catch 패턴 SoT.

---

## §11 의제 10 — DeliveryRepository 시그니처 (STEP 5)

**실측 대상**: `backend/src/main/java/com/zslab/mall/delivery/repository/DeliveryRepository.java`

```java
// line 10-13 — 전체
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByPublicId(String publicId);
}
```

**결론**:
- 현재 메서드: `findById`(JpaRepository 상속)·`findByPublicId(String publicId)` (line 12-13).
- `claim_id` 조회 메서드 없음. STEP 5에서 `ExchangeDeliveryCompletedHandler`·`DeliveryCompletedHandler`가 Delivery 재조회 시 `findById(event.deliveryId())`(JpaRepository 상속) 그대로 활용 가능 — 신규 메서드 추가 불필요.

---

## §12 의제 11 — V1__init.sql delivery 테이블 (STEP 1)

**실측 대상**: `backend/src/main/resources/db/migration/V1__init.sql` — `CREATE TABLE delivery` 블록

```sql
-- line 642-659
CREATE TABLE delivery (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  public_id      CHAR(30)     NOT NULL COMMENT 'ULID+prefix dlv_',
  order_item_id  BIGINT       NOT NULL COMMENT 'FK→order_item(N:1·DLV-2)',
  carrier        ENUM('CJ','HANJIN','POST','LOGEN') NOT NULL COMMENT '택배사·A#11',
  tracking_no    VARCHAR(100) NULL     COMMENT '운송장번호·UK(DLV-1)·자연키',
  status         ENUM('READY','SHIPPING','DELIVERED') NOT NULL COMMENT '배송 상태·A#12',
  shipped_at     DATETIME(6)  NULL     COMMENT '발송 시각',
  delivered_at   DATETIME(6)  NULL     COMMENT '배송 완료 시각(DLV-3)',
  created_at     DATETIME(6)  NOT NULL,
  created_by     BIGINT       NULL,
  updated_at     DATETIME(6)  NOT NULL,
  updated_by     BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_delivery_public_id (public_id),
  UNIQUE KEY uk_delivery_tracking_no (tracking_no),
  CONSTRAINT fk_delivery_order_item FOREIGN KEY (order_item_id) REFERENCES order_item (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='배송(DLV Aggregate Root·ARCHIVE·public_id dlv_)';
```

**결론**:
- `claim_id` 컬럼 부재 확인 (line 642-659 전건 실측).
- 현재 FK: `fk_delivery_order_item` (order_item.id). INDEX: `uk_delivery_public_id`·`uk_delivery_tracking_no`.
- V7 마이그레이션에서 `claim_id BIGINT NULL COMMENT 'FK→claim(교환 배송 한정·NULL=일반 배송)'` 컬럼 추가 + FK·INDEX 추가 예정.

---

## §13 의제 12 — DeliveryCompleted event record 페이로드 (STEP 5)

**실측 대상**: `backend/src/main/java/com/zslab/mall/delivery/event/DeliveryCompleted.java`

```java
// line 15-20 — record 페이로드
public record DeliveryCompleted(
        Long deliveryId,
        Long orderItemId,
        LocalDateTime deliveredAt,
        LocalDateTime occurredAt) {
}
```

**결론**:
- 페이로드 4필드: `deliveryId`·`orderItemId`·`deliveredAt`·`occurredAt`. `claim_id` 없음 확인 (line 15-20 전건 실측).
- D-98 Q5 3차 검토 흡수 확인: 이벤트 페이로드 무수정 확정. `DeliveryCompletedHandler`·`NotificationDeliveryCompletedHandler`·`ExchangeDeliveryCompletedHandler` 모두 `event.deliveryId()`로 `DeliveryRepository.findById`를 호출해 `claim_id`를 조회·분기하는 방식으로 처리.

---

## §14 의제 13 — Delivery 관련 테스트 인벤토리 (STEP 7)

**실측 대상**: `backend/src/test/java/com/zslab/mall/delivery/` 하위 + handler 테스트 2건

### DeliveryRepositoryTest.java
```
@Test save_findById_success_withPublicId          (line 61)
@Test insert_trackingNoNull_multipleAllowed        (line 82)
@Test insert_duplicateTrackingNoNotNull_...        (line 97)
@Test insert_invalidOrderItemId_...               (line 117)
@Test insert_invalidCarrier_...                   (line 129)
```
소계: **5 @Test**

### DeliveryEventIntegrationTest.java
```
@Test markShipping_publishesE4_...                (line 90)
@Test markDelivered_publishesE5_...               (line 105)
@Test markDelivered_fromReady_throwsAndNoStateChange (line 119)
```
소계: **3 @Test**

### DeliveryServiceTest.java
```
@Test markShipping_ready_transitionsAndPublishes   (line 46)
@Test markShipping_delivered_throwsAndDoesNotPublish (line 65)
@Test markDelivered_shipping_transitionsAndPublishes (line 78)
@Test markDelivered_dlv3Violation_...             (line 96)
@Test markDelivered_ready_throwsAndDoesNotPublish  (line 111)
```
소계: **5 @Test**

### DeliveryCompletedHandlerTest.java
```
@Test onDeliveryCompleted_shipping_transitionsToDelivered  (line 48)
@Test onDeliveryCompleted_itemNotFound_noOp               (line 63)
@Test onDeliveryCompleted_alreadyDelivered_idempotentNoOp (line 73)
@Test onDeliveryCompleted_preparing_canTransitionGuardSkip (line 86)
```
소계: **4 @Test**

### NotificationDeliveryCompletedHandlerTest.java
glob 결과: **파일 없음** (No files found).

**결론**:
- delivery 관련 기존 테스트 합계: **17 @Test** (RepositoryTest 5 + IntegrationTest 3 + ServiceTest 5 + CompletedHandlerTest 4).
- `claim_id` 분기 영향 대상:
  - `DeliveryCompletedHandler` (수정) → `DeliveryCompletedHandlerTest`에 claim_id != null early return 케이스 추가
  - `NotificationDeliveryCompletedHandler` (수정) → `NotificationDeliveryCompletedHandlerTest` 신규 작성 + claim_id 분기 케이스 포함
  - `ExchangeDeliveryCompletedHandler` (신설) → 전용 단위 테스트 신규 작성
  - `DeliveryService.registerExchangeShipment` (신설) → `DeliveryServiceTest`에 케이스 추가 또는 신규
  - `DeliveryEventIntegrationTest` → 교환 배송 E2E 케이스 추가(STEP 7)

---

## §15 의제 14 — aggregate-boundary.md §2.5 Delivery 외부 ID 참조 현황 (STEP 8)

**실측 대상**: `docs/architecture-baseline/aggregate-boundary.md` §2.5

```markdown
<!-- line 69-74 — §2.5 주문·결제·배송·클레임 테이블 -->
| Aggregate | Root     | 포함 엔티티 | 외부 ID 참조           |
|-----------|----------|-------------|------------------------|
| Order     | Order    | OrderItem, OrderShippingSnapshot | User.id, ProductVariant.id, Seller.id |
| Payment   | Payment  | — (단독)    | Order.id               |
| Delivery  | Delivery | — (단독)    | OrderItem.id           |
| Claim     | Claim    | Refund      | OrderItem.id, Payment.id |
```

**결론**:
- `Delivery` 외부 ID 참조 현재값: `OrderItem.id` 단독 (line 73 실측).
- D-98 Q13 의무: `Claim.id` 추가 → `OrderItem.id, Claim.id (교환 배송 한정·NULL=일반 배송)`으로 갱신. STEP 8에서 수정 대상.

---

## §16 의제 15 — state-machine.md §6.1 Delivery READY 의미 정의 부재 (STEP 8)

**실측 대상**: `docs/architecture-baseline/state-machine.md` §6.1

```markdown
<!-- line 183-191 — §6.1 Delivery.status -->
   READY ──→ SHIPPING ──→ DELIVERED

- **단방향 직진**: READY → SHIPPING → DELIVERED. 단계 건너뛰기(READY → DELIVERED) 차단.
- **DELIVERED 종결**: DELIVERED에서 어떤 전이도 불가(불가역).
- **역방향·자기 전이 차단**: SHIPPING → READY·DELIVERED → SHIPPING·동일 상태 재전이 전건 차단.
- **가드 위치**: `DeliveryStatus.canTransitionTo(next)` ...
- **OrderItem 연동(§3 정합)**: SHIPPING 진입 시 E4 DeliveryStarted → OrderItem SHIPPING ...
- **DLV-3**: markDelivered 시 shipped_at ≤ delivered_at 검증(invariants §2.12).
```

**결론**:
- `READY` 의미 1줄 정의 박제 없음 (line 183-191 전건 실측·"READY = 배송 등록 직후 초기 상태"·"교환품 출고 대기 상태" 등 박제 항목 없음). D-98 Q3 신규 박제 의무 — STEP 8에서 `READY` 행 1줄 추가 대상.

---

## §17 기조 5 자체 감사 매트릭스

| 의제 | 실측 방법 | 인용 라인 | 추정 여부 |
|------|-----------|-----------|-----------|
| §2 migration 목록 | Glob `db/migration/*.sql` | — (파일명 목록) | 실측 |
| §3 Delivery.java 필드 | Read line 39-58 | line 39-58 | 실측 |
| §3 markShipping 시그니처 | Read line 85-92 | line 85-92 | 실측 |
| §4 DeliveryService 메서드 | Read line 41-64 | line 41-64 | 실측 |
| §5 Claim.confirmPickup | Read line 140-148 | line 140-148 | 실측 |
| §6 DeliveryCompletedHandler 주입·handle | Read line 29-57 | line 29-57 | 실측 |
| §7 NotificationDeliveryCompletedHandler | Read line 32-41 | line 32-41 | 실측 |
| §8 claim/handler/ glob | Glob `claim/handler/*.java` | — (파일명 4건) | 실측 |
| §9 ClaimRefundCompletedHandler 분기 | Read line 40-59 | line 40-59 | 실측 |
| §10 ClaimPickedUpHandler 트랜잭션·분기 | Read line 43-61 | line 43-61 | 실측 |
| §11 DeliveryRepository 메서드 | Read line 10-13 | line 10-13 | 실측 |
| §12 V1__init.sql delivery DDL | Grep `CREATE TABLE delivery` -A30 | line 642-659 | 실측 |
| §13 DeliveryCompleted record | Read line 15-20 | line 15-20 | 실측 |
| §14 테스트 인벤토리 | Read 4파일 전건 + Glob | line 별도 명시 | 실측 |
| §15 aggregate-boundary §2.5 | Read line 69-74 | line 69-74 | 실측 |
| §16 state-machine §6.1 | Read line 183-191 | line 183-191 | 실측 |

추정 항목: **0건**

---

## §18 PR-2 STEP 1~8 진입 시 사용 매트릭스

| STEP | 내용 | 참조 의제 |
|------|------|-----------|
| STEP 1 | V7 마이그레이션 신설 (delivery.claim_id 추가) | §2 (번호 확정)·§12 (현재 DDL) |
| STEP 2 | Delivery.java claim_id 필드 신설·attachExchangeDelivery 없음 확인 | §3 (필드 목록)·§5 (Claim 메서드 SoT) |
| STEP 3 | Delivery.markShipping null 가드 추가 (D-98 Q3 보강) | §3 (markShipping 시그니처) |
| STEP 4 | DeliveryService.registerExchangeShipment 신설 | §4 (기존 메서드 SoT)·§11 (Repository) |
| STEP 5 | DeliveryCompletedHandler claim_id 분기 추가·ExchangeDeliveryCompletedHandler 신설 | §6 (Handler 현황)·§7 (Notification Handler)·§8 (핸들러 부재)·§9 (RefundCompleted SoT)·§10 (ClaimPickedUp SoT)·§11 (Repository)·§13 (event payload) |
| STEP 6 | NotificationDeliveryCompletedHandler claim_id 분기 추가 | §7 (현황)·§13 (event payload) |
| STEP 7 | 테스트 추가·갱신 (Handler 수정·신설별 케이스 3+) | §14 (인벤토리 전건) |
| STEP 8 | aggregate-boundary §2.5 Claim.id 추가·state-machine §6.1 READY 의미 1줄 박제 | §15 (외부 ID 현황)·§16 (READY 정의 부재) |
