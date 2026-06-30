# Track 13 Delivery 도메인 신설 정찰 보고서 (recon-report)

> 작성일: 2026-06-30 · 모드: read-only 정찰 (코드·git 무변경) · 라벨: A급 (단독 Aggregate·외부 검토 선택적)
> 산출물: 본 파일 1건 (그 외 파일 변경 없음·PROGRESS.md STEP 로그 제외)
> SoT 기준: main 8e7d4d2 (Track 12 후속 PR D-96 머지 직후·377 tests baseline)
> 패턴 준용: docs/track-12/recon-report.md (D-95 recon 1:1)

---

## §1. 트랙 배경·범위·OUT-OF-SCOPE

### 1.1 배경

Track 12 NotificationLog 적재 표준 박제(D-95) + D-96 후속 PR(Refund FAILED 적재 전환) 완료 직후 진입. 본 트랙의 명목 목표는 **Delivery Aggregate Root에서 E4 DeliveryStarted·E5 DeliveryCompleted를 발행하고, Order 측 소비 핸들러(OrderItem 상태 전이) + NotificationLog 핸들러 2건을 동반 신설**하는 것이다.

Track 13에서 해소되는 이연 항목:
- **state-machine.md §6**: "Delivery 상태 전이 → 각 도메인 별도 정의 (본 PR 범위 외)" 이연 → 본 트랙 정의 대상
- **D-95 Q4 OUT-OF-SCOPE**: "E4 DeliveryStarted·E5 DeliveryCompleted (Delivery 도메인 행위 미구현·이벤트 record 부재)" → 본 트랙 자연 해소
- **D-95 §후속 "Delivery 도메인 신설 트랙"**: E4·E5 발행처 + NotificationDeliveryStartedHandler·NotificationDeliveryCompletedHandler 동반 명시

기존 라벨 정리:
- D-88~D-94 "Track 11 = Claim RETURN/EXCHANGE" → D-94 Q0 β로 Track 12+로 자연 이동
- D-90·D-94 "Track 12+ RETURN/EXCHANGE" → D-95 Q1 α로 Track 13+로 자연 이동
- **본 트랙(Track 13)** = Delivery 도메인 신설 확정 (결정 박제 D-97에서 처리)

### 1.2 핵심 실측 결론 (정찰 룰 #4 — 선구현 확인)

**Delivery 도메인은 영속 계층 4파일(entity·enums 2종·repository)까지만 선구현되었고, 상태 전이 메서드·이벤트·Service·Handler·Controller는 전무하다.**

- `Delivery.java`(79L): `AbstractPublicIdFullAuditableEntity` 상속·`create(orderItemId, carrier)` 팩토리만·**`markShipping()`·`markDelivered()` 미존재**
- `DeliveryStatus.java`(10L): READY·SHIPPING·DELIVERED 3값 정의·**`canTransitionTo()` 미존재**
- `delivery/event/` 패키지: **미존재** → E4 DeliveryStarted·E5 DeliveryCompleted record **전무**
- `delivery/service/` 패키지: **미존재** → DeliveryService 미신설 상태
- `delivery/handler/` 패키지: **미존재**
- `delivery/controller/` 패키지: **미존재**
- `delivery/dto/` 패키지: **미존재**

→ 본 트랙의 실 결손 = **"존재하는 Delivery 영속 계층 위에 (1) 상태 전이 메서드·canTransitionTo, (2) E4·E5 이벤트 record, (3) Order 측 동기 소비 핸들러, (4) NotificationLog 비동기 핸들러 2건을 신설"**하는 것이다.

### 1.3 OUT-OF-SCOPE

- **Delivery Controller·endpoint**: 판매자가 Delivery를 등록·SHIPPING/DELIVERED 전이하는 API → 본 트랙 미포함 (Service 계층 직접 호출 또는 별도 트랙 이연)
- **발송 어댑터 PENDING→SENT 전이**: D-86 §후속 이연 유지
- **RETURN/EXCHANGE Delivery(교환 재출고)**: Track 13+ (RETURN/EXCHANGE 확장 트랙) 소관
- **자동 구매확정 타이머(DELIVERED 후 N일 → CONFIRMED)**: state-machine §6 이연 유지
- **Inventory 차감/복구 Order-Delivery 연동**: Track 14+ (Inventory 차감 핸들러) 소관
- **DeliveryCarrier·tracking_no 외부 유효성 검증**: 택배사 API 연동 별도 어댑터 트랙
- **DTO @ValidEnum**: 외부 노출 DTO 부재 현재·4층위 잠금 3층은 Track 8+ 이연 유지

### 1.4 LIMITATION

- 본 보고서는 read-only 실측이며 구현·결정을 포함하지 않는다. §8 의제는 결정 라운드(D-97) 입력이다.
- 영향 범위(§6)는 **결정 라운드 확정 전 예측**이며 확정 박제가 아니다.
- OrderService 전체를 grep했으나 markShipping·markDelivered 부재 확인만 했고, 전체 라인 읽기는 LIMITATION으로 박제한다. markPaid 패턴 참조로 신규 메서드 설계 방향은 예측 가능하다.

---

## §2. Delivery 도메인 자산 실측 (STEP A)

`backend/src/main/java/com/zslab/mall/delivery/` 전체 트리 = **production 4파일**(entity 1·enums 2·repository 1) + test 1파일.
**service·handler·controller·event·dto·exception 패키지 전부 미존재.**

| 파일 | 라인 | 핵심 |
|---|---|---|
| [entity/Delivery.java](../../backend/src/main/java/com/zslab/mall/delivery/entity/Delivery.java) | 79 | `AbstractPublicIdFullAuditableEntity` 상속·public_id prefix `"dlv"`·ARCHIVE 분류(deleted_at 없음·@SQLRestriction 미선언·LT-03 해당 없음)·D-01 정합(orderItemId Long 필드·@ManyToOne 금지)·필드 7(id·orderItemId·carrier·trackingNo·status·shippedAt·deliveredAt)·`create(orderItemId, carrier)` 팩토리만 |
| [enums/DeliveryStatus.java](../../backend/src/main/java/com/zslab/mall/delivery/enums/DeliveryStatus.java) | 10 | READY·SHIPPING·DELIVERED 3값(A#12)·`canTransitionTo()` **미존재** |
| [enums/DeliveryCarrier.java](../../backend/src/main/java/com/zslab/mall/delivery/enums/DeliveryCarrier.java) | 12 | CJ·HANJIN·POST·LOGEN 4값(A#11) |
| [repository/DeliveryRepository.java](../../backend/src/main/java/com/zslab/mall/delivery/repository/DeliveryRepository.java) | 14 | `JpaRepository`·`findByPublicId(String)` 1건 |
| (test) [repository/DeliveryRepositoryTest.java](../../backend/src/test/java/com/zslab/mall/delivery/repository/DeliveryRepositoryTest.java) | 144 | `Batch1DataJpaTestBase`·seedOrderItem LT-02 try-finally·4 케이스(save+findById·trackingNo NULL 다건·trackingNo UK 위반·FK 위반·carrier ENUM 위반) |

### 2.1 결손 식별 (정찰 룰 #4)

- **Service**: 미존재. `DeliveryService` 미신설·상태 전이 메서드·이벤트 발행 없음.
- **markShipping() / markDelivered()**: `Delivery.java` 내 미존재. `create()` 외 도메인 행위 0.
- **canTransitionTo()**: `DeliveryStatus` 내 미존재. 전이 합법성 검증 메서드 0.
- **Handler**: 미존재. `delivery/handler/` 패키지 자체 없음.
- **Controller**: 미존재. Delivery 등록·전이 endpoint 없음.
- **Event record**: 미존재. `delivery/event/` 패키지 자체 없음(§3 상세).

### 2.2 Delivery.create 정적 팩토리 ([Delivery.java:68-77](../../backend/src/main/java/com/zslab/mall/delivery/entity/Delivery.java#L68-L77))

```java
public static Delivery create(Long orderItemId, DeliveryCarrier carrier) {
    if (orderItemId == null || carrier == null) {
        throw new IllegalArgumentException("Delivery 필수값 누락(orderItemId·carrier).");
    }
    Delivery delivery = new Delivery();
    delivery.orderItemId = orderItemId;
    delivery.carrier = carrier;
    delivery.status = DeliveryStatus.READY;
    return delivery;
}
```

초기값 READY·trackingNo·shippedAt·deliveredAt은 null. **상태 전이 메서드(markShipping·markDelivered) 및 canTransitionTo 미존재는 Track 13 핵심 결손**이다.

### 2.3 추상 클래스 상속 확인

`Delivery extends AbstractPublicIdFullAuditableEntity` ([Delivery.java:33](../../backend/src/main/java/com/zslab/mall/delivery/entity/Delivery.java#L33))
- full audit(created_at·created_by·updated_at·updated_by) 포함
- `AbstractPublicIdSoftDeletableEntity` 상속 **아님** (ARCHIVE 분류·soft-delete 미적용)
- `@SQLRestriction` 미선언 (정상 — ARCHIVE는 soft-delete 쿼리 필터 불요)

---

## §3. 이벤트·핸들러 인벤토리 (STEP B·C)

### 3.1 DDL delivery 테이블 ([V1__init.sql:640-659](../../backend/src/main/resources/db/migration/V1__init.sql#L640-L659))

```sql
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
  ...
  UNIQUE KEY uk_delivery_public_id (public_id),
  UNIQUE KEY uk_delivery_tracking_no (tracking_no),
  CONSTRAINT fk_delivery_order_item FOREIGN KEY (order_item_id) REFERENCES order_item (id) ON DELETE RESTRICT ON UPDATE CASCADE
)
```

**4층위 잠금 1층(DB ENUM) 완료**:
- `carrier ENUM('CJ','HANJIN','POST','LOGEN')` — A#11 정합
- `status ENUM('READY','SHIPPING','DELIVERED')` — A#12 정합

**2층·3층·4층 미완**: Java enum canTransitionTo 미존재(2층 일부)·DTO @ValidEnum 미존재(3층)·프론트 constants 미존재(4층)

### 3.2 E4 DeliveryStarted record 부재 확인

`backend/src/main/java/com/zslab/mall/delivery/event/` 디렉토리: **미존재**.

domain-events.md §2 E4 SoT 인용 ([domain-events.md:71-83](../../docs/architecture-baseline/domain-events.md#L71-L83)):

| 항목 | 내용 |
|---|---|
| 발행 주체 | Delivery (#12) |
| 트리거 | Delivery.status → SHIPPING |
| 소비 주체 | Order(OrderItem → SHIPPING·Order.status 재계산), NotificationLog(발송 알림) |
| 페이로드 | delivery_id, order_item_id, carrier, tracking_no |
| 동기/비동기 | OrderItem 상태 = **동기** / 알림 = 비동기 |
| 멱등성 | order_item_id 기준. 이미 SHIPPING 이상이면 skip |

→ **E4 DeliveryStarted record: 미존재. 발행처(DeliveryService.markShipping 또는 Delivery.markShipping) 미존재.**

### 3.3 E5 DeliveryCompleted record 부재 확인

domain-events.md §2 E5 SoT 인용 ([domain-events.md:85-97](../../docs/architecture-baseline/domain-events.md#L85-L97)):

| 항목 | 내용 |
|---|---|
| 발행 주체 | Delivery (#12) |
| 트리거 | Delivery.status → DELIVERED |
| 소비 주체 | Order(OrderItem → DELIVERED·Order.status 재계산), NotificationLog(배송 완료 알림) |
| 페이로드 | delivery_id, order_item_id, delivered_at |
| 동기/비동기 | OrderItem 상태 = **동기** / 알림 = 비동기 |
| 멱등성 | order_item_id 기준. 이미 DELIVERED 이상이면 skip |

→ **E5 DeliveryCompleted record: 미존재. 발행처(DeliveryService.markDelivered 또는 Delivery.markDelivered) 미존재.**

### 3.4 기존 이벤트·핸들러 목록 (Track 13 신설 전 현황)

| 이벤트 | record 존재 | 동기 소비 핸들러 | 비동기 Notification 핸들러 |
|---|---|---|---|
| E1 OrderPlaced | O | — | NotificationOrderPlacedHandler (Track 12) |
| E2 PaymentCompleted | O | OrderEventHandler (payment/handler/) | NotificationPaymentCompletedHandler (Track 12) |
| ClaimApproved | O | — (γ 결정·D-90 Q2) | NotificationClaimApprovedHandler (Track 12) |
| E9 ClaimCompleted | O | ClaimCompletedHandler (claim/handler/) | NotificationClaimCompletedHandler (Track 12) |
| **E4 DeliveryStarted** | **미존재** | **미존재** | **미존재** |
| **E5 DeliveryCompleted** | **미존재** | **미존재** | **미존재** |

---

## §4. SoT 문서 인용 (STEP F)

### 4.1 state-machine.md §6 이연 항목

> [state-machine.md:166-168](../../docs/architecture-baseline/state-machine.md#L166-L168) 인용:
> "**Delivery 상태 전이** → 각 도메인 별도 정의 (본 PR 범위 외)"

→ Track 13이 이 이연을 종결한다. 구체적으로 `DeliveryStatus.canTransitionTo()` 구현 + state-machine §6에 Delivery 상태 전이 규칙 정의가 결정 라운드(D-97) 의제다.

state-machine §3 OrderItem.item_status 연동 (E4·E5 진입 조건 기박제):
- `SHIPPING` 진입: Delivery 등록 + SHIPPING ([state-machine.md:83](../../docs/architecture-baseline/state-machine.md#L83))
- `DELIVERED` 진입: Delivery 상태 DELIVERED ([state-machine.md:84](../../docs/architecture-baseline/state-machine.md#L84))

### 4.2 aggregate-boundary.md §2.5 Delivery 항목

> [aggregate-boundary.md:72-74](../../docs/architecture-baseline/aggregate-boundary.md#L72-L74) 인용:
>
> | Aggregate | Root | 포함 엔티티 | 외부 ID 참조 |
> |---|---|---|---|
> | Delivery | Delivery | — (단독) | OrderItem.id |
>
> "Delivery는 판매자/물류 컨텍스트에서 독립 갱신 → 독립 Aggregate"

→ Delivery는 단독 Aggregate Root. 외부 ID 참조 = `OrderItem.id`. D-01 정합 확인.

### 4.3 domain-events.md §2 E4·E5 표 인용

E4·E5 페이로드 확정:
- **E4**: `delivery_id, order_item_id, carrier, tracking_no` ([domain-events.md:78](../../docs/architecture-baseline/domain-events.md#L78))
- **E5**: `delivery_id, order_item_id, delivered_at` ([domain-events.md:92](../../docs/architecture-baseline/domain-events.md#L92))

### 4.4 invariants.md §2.12 DLV 불변식

> [invariants.md:122-129](../../docs/architecture-baseline/invariants.md#L122-L129) 인용:
>
> | # | Rule | Why | Enforcement Point |
> |---|---|---|---|
> | DLV-1 | tracking_no UNIQUE | 송장 중복 차단 | DB UK |
> | DLV-2 | Delivery는 OrderItem 없이 생성 불가(order_item_id) | 부분 배송 지원·OrderItem 1:N Delivery | DB FK + NOT NULL |
> | DLV-3 | shipped_at ≤ delivered_at | 시간 순서 정합 | Service/Domain |
>
> "Delivery 상태(READY/SHIPPING/DELIVERED) **전이 규칙은 본 문서 범위 외**다 — state-machine.md §6 이연 유지."

→ DLV-1·DLV-2는 DDL+Entity로 기충족. **DLV-3(shipped_at ≤ delivered_at)은 Service/Domain 강제 의무** → Track 13 markDelivered 구현 시 검증 추가 필요.

### 4.5 decisions.md Delivery 관련 누적 결정

| 결정 | 내용 |
|---|---|
| D-01 [확정-C] | Delivery → 독립 Aggregate (#12)·D-01 §2.5 박제 |
| D-06 | E4·E5 발행 주체 Delivery (#12)·소비 주체 Order·Notification 박제 |
| D-21 §f | invariants.md §2.12 DLV-1~3 섹션 보강 (Track 5 정찰 보정) |
| D-80 deletion-policy | Delivery = ARCHIVE (송장 이력 보존·운송 분쟁 대응) |
| D-86 Track 7 Batch-3b | Delivery Entity·enum 2·Repository 선구현 확정 (신규 14건 일부) |
| D-95 Q4 OUT-OF-SCOPE | "E4·E5 Delivery 도메인 행위 미구현·이벤트 record 부재" → Track 13 자연 해소 |
| D-95 §후속 | "Delivery 도메인 신설 트랙: E4·E5 발행처 + NotificationDeliveryStartedHandler·NotificationDeliveryCompletedHandler 동반" |

---

## §5. NotificationLog 표준 재사용 예측 (STEP E)

### 5.1 NotificationService SoT ([NotificationService.java:1-181](../../backend/src/main/java/com/zslab/mall/notification/service/NotificationService.java))

현재 5 메서드 시그니처:
```java
public void recordOrderPlaced(OrderPlaced event)
public void recordPaymentCompleted(PaymentCompleted event)
public void recordClaimApproved(ClaimApproved event)
public void recordClaimCompleted(ClaimCompleted event)
public void recordRefundFailed(ClaimApproved event)    // D-96
```

핵심 패턴:
- 각 메서드: `try { ... } catch (RuntimeException exception) { log.warn(...); }` — 재throw 0
- `resolveClaimRecipient(Long claimId, String eventName)`: claim → orderItemId → orderId → buyerId 체인 재조회. 어느 하나 미발견 시 null 반환(skip + warn)
- 내부 `save(recipientUserId, templateCode, targetType, targetId, title, content)` 공통 위임

### 5.2 Track 13 신규 메서드 2건 잠정 예측 (결정 전 예측·확정 아님)

**recordDeliveryStarted**: deliveryId로 Delivery 재조회 → orderItemId로 OrderItem 재조회 → orderId로 Order 재조회 → Buyer recipient 산정

```java
// 잠정 시그니처 예측 (결정 라운드 확정 사항)
public void recordDeliveryStarted(DeliveryStarted event)
public void recordDeliveryCompleted(DeliveryCompleted event)
```

recipient 산정 경로 차이: Claim 계열(claimId → orderItemId → orderId)과 달리 Delivery 계열은 **deliveryId → orderItemId → orderId → buyerId** 체인. `DeliveryRepository.findById(event.deliveryId())`로 Delivery를 재조회한 후 `orderItemId`로 체인 이어가는 패턴 예상.

`resolveDeliveryRecipient(Long deliveryId, String eventName)` private 메서드 신설 가능.

### 5.3 NotificationTemplateCodes 현황 ([NotificationTemplateCodes.java:1-19](../../backend/src/main/java/com/zslab/mall/notification/template/NotificationTemplateCodes.java))

현재 5 상수:
```java
public static final String ORDER_PLACED       = "TPL_ORDER_PLACED";
public static final String PAYMENT_COMPLETED  = "TPL_PAYMENT_COMPLETED";
public static final String CLAIM_APPROVED     = "TPL_CLAIM_APPROVED";
public static final String CLAIM_COMPLETED    = "TPL_CLAIM_COMPLETED";
public static final String REFUND_FAILED      = "TPL_REFUND_FAILED";
```

Track 13 추가 예상 상수 2건 (결정 라운드 의제):
```java
// 잠정 예측 — 결정 라운드 확정 사항
public static final String DELIVERY_STARTED   = "TPL_DELIVERY_STARTED";
public static final String DELIVERY_COMPLETED = "TPL_DELIVERY_COMPLETED";
```

Enum 승격 조건: ≥10건 누적 또는 DTO @ValidEnum 수요 발생 시 — 현재 7건(Track 13 추가 후)으로 임계 10건 미만 → 상수 클래스 유지 정합.

### 5.4 핸들러 표준 패턴 SoT ([NotificationOrderPlacedHandler.java:1-42](../../backend/src/main/java/com/zslab/mall/notification/handler/NotificationOrderPlacedHandler.java))

```java
@Slf4j @Component @RequiredArgsConstructor
public class NotificationOrderPlacedHandler {
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(OrderPlaced event) {
        try {
            notificationService.recordOrderPlaced(event);
        } catch (RuntimeException exception) {
            log.warn("notification log failed; event={} target_type={} target_id={} action=manual_review",
                    "OrderPlaced", "ORDER", event.orderId(), exception);
        }
    }
}
```

Track 13 신규 핸들러 2건 예상 패턴 (1:1 재사용):
- `NotificationDeliveryStartedHandler` — `DeliveryStarted` 소비·`notificationService.recordDeliveryStarted(event)` 위임
- `NotificationDeliveryCompletedHandler` — `DeliveryCompleted` 소비·`notificationService.recordDeliveryCompleted(event)` 위임

클래스명 충돌 확인: 기존 핸들러 목록 grep 결과 `DeliveryStarted`·`DeliveryCompleted` 관련 핸들러 0건 → **`Notification` prefix 관례 유지면 충돌 없음**.

### 5.5 PolymorphicTargetType.DELIVERY 존재 확인 ([PolymorphicTargetType.java:14](../../backend/src/main/java/com/zslab/mall/common/enums/PolymorphicTargetType.java#L14))

```java
DELIVERY,  // 기존재
```

Track 13 NotificationLog 적재 시 `target_type=DELIVERY` 직접 사용 가능 — 신규 상수 추가 불필요.

---

## §6. 영향 범위 예측 (결정 라운드 전 예측·확정 박제 아님)

### 6.1 신규 예상 파일

| 파일 | 분류 | 내용 |
|---|---|---|
| `delivery/event/DeliveryStarted.java` | record | payload: deliveryId·orderItemId·carrier·trackingNo·occurredAt (D-30 사실 통지) |
| `delivery/event/DeliveryCompleted.java` | record | payload: deliveryId·orderItemId·deliveredAt·occurredAt (D-30 사실 통지) |
| `delivery/service/DeliveryService.java` | service | markShipping·markDelivered·D-29 save→publish 패턴·DLV-3 검증 |
| `order/handler/DeliveryStartedHandler.java` (또는 다른 위치) | handler | E4 동기 소비·OrderItem→SHIPPING·Order.status 재계산 |
| `order/handler/DeliveryCompletedHandler.java` | handler | E5 동기 소비·OrderItem→DELIVERED·Order.status 재계산 |
| `notification/handler/NotificationDeliveryStartedHandler.java` | handler | E4 비동기(AFTER_COMMIT·REQUIRES_NEW)·NotificationService.recordDeliveryStarted 위임 |
| `notification/handler/NotificationDeliveryCompletedHandler.java` | handler | E5 비동기(AFTER_COMMIT·REQUIRES_NEW)·NotificationService.recordDeliveryCompleted 위임 |
| (테스트) `delivery/service/DeliveryServiceTest.java` | 단위 테스트 | markShipping·markDelivered 전이·DLV-3 위반·멱등 검증 |
| (테스트) `delivery/integration/DeliveryEventIntegrationTest.java` | 통합 테스트 | E4·E5 발행 + Order 전이 + NotificationLog 적재 E2E·LT-02·D-91 FK 부모 그래프 시드 |

### 6.2 수정 예상 파일

| 파일 | 변경 | 내용 |
|---|---|---|
| `delivery/enums/DeliveryStatus.java` | 수정 | `canTransitionTo(DeliveryStatus next)` 메서드 추가·READY→SHIPPING·SHIPPING→DELIVERED |
| `delivery/entity/Delivery.java` | 수정 | `markShipping(String trackingNo, LocalDateTime shippedAt)`·`markDelivered(LocalDateTime deliveredAt)` 도메인 행위 메서드 추가·DLV-3 검증 |
| `order/service/OrderService.java` | 수정 | `markShipping(Long orderItemId, LocalDateTime shippedAt)`·`markDelivered(Long orderItemId, LocalDateTime deliveredAt)` 추가·markPaid 패턴 참조 |
| `notification/service/NotificationService.java` | 수정 | `recordDeliveryStarted`·`recordDeliveryCompleted` 메서드 2건 추가·`resolveDeliveryRecipient` private 추가 |
| `notification/template/NotificationTemplateCodes.java` | 수정 | `DELIVERY_STARTED`·`DELIVERY_COMPLETED` 상수 2건 추가 |
| `docs/architecture-baseline/state-machine.md` | 수정 | §6 Delivery 상태 전이 규칙 정의 (이연 해소) |
| `docs/architecture-baseline/domain-events.md` | 수정 | E4·E5 항목 발행처 주석 보강 (구현 완료 표시) |

### 6.3 무변경 확정 (정찰 룰 #4 — 중복 신설 차단)

`DeliveryCarrier.java`·`DeliveryRepository.java`·`V1__init.sql`(DDL 완비)·`PolymorphicTargetType.java`(DELIVERY 기존재)·`OrderItem.changeStatus()`·`OrderItemStatus.canTransitionTo()`(PREPARING→SHIPPING·SHIPPING→DELIVERED 기정의)·기존 Notification 핸들러 4건·기존 NotificationService 메서드 5건·`DeliveryRepositoryTest.java`(기존 4케이스 무변경)

---

## §7. LT-02·D-91 동반 의무

Track 9 PR-C 이후 모든 핸들러 통합 테스트에 적용되는 의무 박제 (D-91):

> "통합 테스트 seed에서 핸들러가 UPDATE 대상 행의 FK 부모 그래프(seller·product·variant·user 등)를 실제 INSERT 의무로 한다. `SET FOREIGN_KEY_CHECKS=0` 우회는 INSERT 차단 회피용이며 후속 UPDATE의 FK 재검증은 우회 불가다."

Track 13 적용 포인트:
- `DeliveryEventIntegrationTest`: E4·E5 소비 → `OrderItem` UPDATE → FK 부모 그래프(seller·product·variant·user·order) 실제 INSERT 의무
- `LT-02 try-finally`: `SET FOREIGN_KEY_CHECKS=0` 이후 반드시 finally 블록에서 `=1` 복원
- `DeliveryRepositoryTest.seedOrderItem` 패턴([DeliveryRepositoryTest.java:33-58](../../backend/src/test/java/com/zslab/mall/delivery/repository/DeliveryRepositoryTest.java#L33-L58))은 LT-02 try-finally 구현 예시로 재사용 가능하나, Handler UPDATE 테스트는 FK 부모 그래프(seller·product·variant·user) 추가 시딩 필요

---

## §8. 결정 라운드 의제 (D-97 확정 사항)

### Q0: 트랙 식별자 확정 (D-94 Q0 β 선례)
- α: Track 13 = Delivery 도메인 신설 확정
- Track 13+ = RETURN/EXCHANGE + Observability 자연 이동 (D-95 §후속 라벨 carry-over)

### Q1: Delivery 상태 전이 규칙 (state-machine §6 이연 해소)
- 옵션: READY → SHIPPING → DELIVERED 단방향만 허용 (역방향 차단)
- canTransitionTo 추가 위치: `DeliveryStatus` enum 내부 (`OrderItemStatus.canTransitionTo` 패턴 1:1)
- DELIVERED 이후 전이 금지 여부 결정 의무

### Q2: 상태 전이 메서드 위치 (entity vs service)
- α: `Delivery.java` 내 `markShipping(String trackingNo, LocalDateTime shippedAt)` + `markDelivered(LocalDateTime deliveredAt)` 도메인 메서드 → D-29 save→publish는 Service에서 처리
- β: 전이 로직 전체를 `DeliveryService`로 이동
- 권장: α (기존 `OrderItem.changeStatus + Order.markPaid` 분리 패턴 정합)

### Q3: 이벤트 발행 트리거 진입점 (Service vs API vs 외부 어댑터)
- α: `DeliveryService.markShipping(Long deliveryId, String trackingNo)` 직접 호출 (Admin API 또는 테스트 직접 호출)
- β: 판매자 API endpoint 신설 동반
- 권장: α 단독 (endpoint는 별도 트랙·기조 4 정합)

### Q4: Order 측 동기 소비 핸들러 위치
- α: `order/handler/DeliveryStartedHandler` + `order/handler/DeliveryCompletedHandler` (반응 도메인 Order 패키지 배치·D-94 Q1 패턴)
- β: 기존 `payment/handler/OrderEventHandler`에 `@EventListener` 추가
- 권장: α (D-94 Q1 α 패턴 1:1 — 반응 도메인 패키지 배치)

### Q5: E4 동기 소비 트랜잭션 정책
- `@EventListener` (발행 트랜잭션 내 동기·D-29 정합) — E2 PaymentCompleted `OrderEventHandler` 패턴 1:1 의무
- `@TransactionalEventListener(AFTER_COMMIT)` 제외 (OrderItem 전이는 동기 필수·domain-events §2 "OrderItem 상태 = 동기" 박제)

### Q6: NotificationLog 핸들러 배선 대상 (D-95 Q4 α′-2 확장)
- E4 DeliveryStarted → NotificationDeliveryStartedHandler (비동기·AFTER_COMMIT·REQUIRES_NEW)
- E5 DeliveryCompleted → NotificationDeliveryCompletedHandler (비동기·AFTER_COMMIT·REQUIRES_NEW)
- recipient 산정 경로 결정: deliveryId → Delivery → orderItemId → OrderItem → orderId → Order → buyerId

### Q7: TemplateCode 추가 (WARN-9)
- `NotificationTemplateCodes.DELIVERY_STARTED = "TPL_DELIVERY_STARTED"`
- `NotificationTemplateCodes.DELIVERY_COMPLETED = "TPL_DELIVERY_COMPLETED"`
- Enum 승격 조건 재평가: 7건 → 10건 미만·상수 클래스 유지 정합

### Q8: 멱등성 가드 (D-95 Q6 α 선례)
- E4: `order_item_id` 기준·이미 SHIPPING 이상이면 skip (OrderItemStatus.canTransitionTo 가드로 자연 흡수)
- E5: `order_item_id` 기준·이미 DELIVERED 이상이면 skip (동일 패턴)
- 별도 멱등성 저장소 미도입 (D-95 Q6 α·인메모리 ApplicationEvent 가정 기반)

### Q9: PR 분할 전략
- α: 단일 PR (D-87·D-94·D-95 1-PR 단독 패턴)
- β: Delivery 핵심 (E4·E5·Order 핸들러) + Notification 핸들러 분리 2 PR
- 권장: β (E4·E5 Order 전이는 S급·Notification 핸들러는 A급·분리 시 검토 부담 분산)

### Q10: 테스트 전략 (D-91·LT-02 의무)
- 단위: DeliveryServiceTest (markShipping·markDelivered·DLV-3 위반·멱등 skip)
- 통합: DeliveryEventIntegrationTest (E4→OrderItem SHIPPING + NotificationLog + E5→DELIVERED 전체 루프·LT-02 try-finally·D-91 FK 부모 그래프 시드 의무)
- 기존 DeliveryRepositoryTest 4케이스 회귀 유지 의무

### Q11: DeliveryRepository 확장 여부
- `findByOrderItemId(Long orderItemId)` 필요 여부 (Handler에서 orderItemId로 Delivery 조회 시 필요)
- 또는 `DeliveryService`가 `deliveryId`를 직접 수신하면 `findById` 재사용 가능

### Q12: 외부 검토 라벨
- A급 (단독 Aggregate·단순 구조·외부 검토 선택적·D-95 §1.1 A급 패턴 정합)
- E4·E5 Order 전이 포함 시 S급 상향 가능 (결정 라운드 의제)

---

## §9. WARN 매트릭스

### P0 (블로커 — 구현 진입 전 해소 필수)

| WARN | 내용 | 해소 방향 |
|---|---|---|
| WARN-1 | `DeliveryStatus.canTransitionTo()` 미존재 → 상태 전이 합법성 검증 불가 | Q1 결정 후 신설 |
| WARN-2 | `Delivery.markShipping()` · `Delivery.markDelivered()` 미존재 → 도메인 행위 메서드 결손 | Q2 결정 후 신설 |
| WARN-3 | E4 DeliveryStarted · E5 DeliveryCompleted record 미존재 → 이벤트 발행 불가 | Track 13 신설 의무 |

### P1 (결정 라운드 의제 의무)

| WARN | 내용 | 해소 방향 |
|---|---|---|
| WARN-4 | state-machine §6 이연 미해소 — Delivery 전이 규칙 미박제 | §8 Q1 결정 의무 |
| WARN-5 | OrderService.markShipping / markDelivered 미존재 → Order 측 진입점 결손 | §8 Q4·Q5 결정 의무 |
| WARN-6 | NotificationLog 핸들러(E4·E5) 배선 경로 미박제 — recipient 산정 경로 미확인 | §8 Q6 결정·§5.2 예측 기반 |
| WARN-7 | DLV-3(shipped_at ≤ delivered_at) Service/Domain 강제 미구현 | markDelivered 구현 시 검증 추가 의무 |
| WARN-8 | Handler 동기 통합 테스트 D-91 FK 부모 그래프 시드 의무 미확보 — DeliveryRepositoryTest seedOrderItem은 order+order_item만 시딩·seller·product·variant·user 부재 | §7 의무 박제·통합 테스트 구현 시 추가 시딩 필수 |

### P2 (박제 권장)

| WARN | 내용 | 해소 방향 |
|---|---|---|
| WARN-9 | `NotificationTemplateCodes` 상수 2건(DELIVERY_STARTED·DELIVERY_COMPLETED) 추가 필요 | §8 Q7 결정 |
| WARN-10 | `DeliveryRepository`에 Handler 재조회용 메서드 확장 여부 미결정 | §8 Q11 결정 |
| WARN-11 | `DeliveryEventIntegrationTest` 구현 시 `@Transactional` 금지 의무 — AFTER_COMMIT 핸들러 미실행 트랩 (D-90 Q5 β 선례) | LT-02 try-finally 의무 박제 |

---

## §10. 회귀 위험 평가 (정찰 룰 #6)

### 10.1 영향 매트릭스

| 도메인 | 영향 유형 | 위험 수준 | 근거 |
|---|---|---|---|
| Order/OrderItem | 수정(markShipping·markDelivered 추가) | **저위험** | `changeStatus()` 기존 메서드 재사용·canTransitionTo(PREPARING→SHIPPING·SHIPPING→DELIVERED) 기정의 |
| Payment/Refund | 무변경 | 없음 | Delivery 신설과 결합 없음 |
| Claim | 무변경 | 없음 | Delivery 신설과 결합 없음 |
| Notification | 수정(메서드 2건·핸들러 2건 추가) | **저위험** | 기존 메서드 영향 없음·추가만 |
| DeliveryRepositoryTest | 회귀 유지 의무 | 없음 | 기존 4케이스 무변경 |

### 10.2 기존 OrderItemStatus.canTransitionTo 매트릭스 확인 ([OrderItemStatus.java:44-57](../../backend/src/main/java/com/zslab/mall/order/enums/OrderItemStatus.java#L44-L57))

```java
case PREPARING -> next == SHIPPING || next == CANCEL_REQUESTED;
case SHIPPING -> next == DELIVERED || next == RETURN_REQUESTED;
```

→ PREPARING → SHIPPING, SHIPPING → DELIVERED 전이 **기정의 완료**. Track 13 핸들러에서 `OrderItem.changeStatus(OrderItemStatus.SHIPPING)` / `changeStatus(OrderItemStatus.DELIVERED)` 호출 시 validation 통과 확정 (기존 테스트 회귀 0).

### 10.3 신규 통합 테스트 의무 (CLAUDE.md §테스트)

CLAUDE.md: "신규 도메인 추가 시 통합 테스트 최소 3개 (인증 검증·성공 흐름·실패 흐름) 필수"

예상 3개:
- **T1 성공 흐름 E4**: DeliveryService.markShipping → E4 발행 → OrderItem SHIPPING + NotificationLog 적재 검증
- **T2 성공 흐름 E5**: DeliveryService.markDelivered → E5 발행 → OrderItem DELIVERED + NotificationLog 적재 검증
- **T3 실패 흐름**: 잘못된 상태 전이(READY→DELIVERED 스킵) → IllegalStateException·상태 무변경 검증

전체 회귀 예상: 377 baseline → 신규 ≥(단위 3이상·통합 3) → 383+ PASS.

---

## §11. 기조 5 자체 감사 (정찰 룰 #7)

> "본 보고서는 모두 실측 기반인가?"

감사 결과:

| 항목 | 실측 여부 | 인용 |
|---|---|---|
| Delivery 4파일 존재 | ✅ 실측 | Glob 결과·각 파일 Read |
| DeliveryStatus READY·SHIPPING·DELIVERED | ✅ 실측 | [DeliveryStatus.java:6-9](../../backend/src/main/java/com/zslab/mall/delivery/enums/DeliveryStatus.java#L6-L9) |
| canTransitionTo 미존재 | ✅ 실측 | DeliveryStatus.java 전체 Read·해당 메서드 0건 |
| delivery/event/ 패키지 미존재 | ✅ 실측 | Glob `delivery/event/**/*` → No files found |
| DDL ENUM 잠금 | ✅ 실측 | [V1__init.sql:646-648](../../backend/src/main/resources/db/migration/V1__init.sql#L646-L648) |
| E4·E5 페이로드 | ✅ 실측 | [domain-events.md:78·92](../../docs/architecture-baseline/domain-events.md#L78) |
| state-machine §6 이연 | ✅ 실측 | [state-machine.md:166-168](../../docs/architecture-baseline/state-machine.md#L166-L168) |
| DLV-1~3 불변식 | ✅ 실측 | [invariants.md:122-129](../../docs/architecture-baseline/invariants.md#L122-L129) |
| OrderItemStatus.canTransitionTo SHIPPING/DELIVERED 기정의 | ✅ 실측 | [OrderItemStatus.java:48-50](../../backend/src/main/java/com/zslab/mall/order/enums/OrderItemStatus.java#L48-L50) |
| OrderService markShipping/markDelivered 미존재 | ✅ 실측 | grep 결과 0건 |
| NotificationService 5 메서드 시그니처 | ✅ 실측 | [NotificationService.java:50-146](../../backend/src/main/java/com/zslab/mall/notification/service/NotificationService.java#L50-L146) |
| NotificationTemplateCodes 5 상수 | ✅ 실측 | [NotificationTemplateCodes.java:11-15](../../backend/src/main/java/com/zslab/mall/notification/template/NotificationTemplateCodes.java#L11-L15) |
| PolymorphicTargetType.DELIVERY 존재 | ✅ 실측 | [PolymorphicTargetType.java:14](../../backend/src/main/java/com/zslab/mall/common/enums/PolymorphicTargetType.java#L14) |
| NotificationHandler 4건 표준 패턴 | ✅ 실측 | 4 handler Read 완료 |
| OrderEventHandler E2 동기 소비 패턴 | ✅ 실측 | [OrderEventHandler.java:31-37](../../backend/src/main/java/com/zslab/mall/payment/handler/OrderEventHandler.java#L31-L37) |
| DeliveryRepositoryTest LT-02 try-finally | ✅ 실측 | [DeliveryRepositoryTest.java:33-58](../../backend/src/test/java/com/zslab/mall/delivery/repository/DeliveryRepositoryTest.java#L33-L58) |

**미실측 항목 (§1.4 LIMITATION 박제)**:
- OrderService.java 전체 라인 Read 미수행 (grep으로 markShipping·markDelivered 부재만 확인). markPaid 패턴 참조 가능이나 전체 라인 수·의존성 미확인.
- delivery/dto/ 패키지 미존재는 glob으로 확인했으나 delivery/ 전체 하위 패키지 glob 결과를 entity·enums·repository 4파일로 확인·추가 패키지 없음 실측.

→ **본 보고서는 모두 실측 기반이다.** 예측·추측 항목은 §5(잠정 예측)·§6(영향 범위 예측) 섹션에 명시적으로 "(결정 전 예측·확정 아님)" 라벨을 붙여 구분했다.

---

*정찰 완료: 2026-06-30 · 산출물 1건(본 파일) · PROGRESS.md STEP J·K 완료 처리 예정*
