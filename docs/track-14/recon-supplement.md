# Track 14 PR-1 RETURN — 정찰 보강 (recon-supplement)

**작성일**: 2026-06-30  
**기준 커밋**: 75188e8 (Track 13 Delivery PR 머지 직후·393 tests·working tree clean)  
**목적**: D-98 박제 후 STEP 1~6 구현 진입 전 마지막 read-only 실측 보강. recon-report.md §1~§12 기반 미진 의제 10건을 라인 번호 직접 인용으로 박제.

---

## §1 정찰 보강 배경·범위

recon-report.md는 Claim 도메인 전체 자산·CANCEL 게이트 4건·OrderItemStatus 매트릭스·통합/단위 테스트 인벤토리를 박제했다. 본 보고서는 STEP 1~6 진입 직전 남은 10 의제를 실측으로 보강한다.

의제별 §번호·"STEP {N}에서 사용" 라벨 부착.  
라인 번호는 `Read` 도구 실측 결과 직접 인용(추정 없음).

---

## §2 의제 1 — db/migration 디렉토리 현황 (STEP 1에서 사용)

**실측 대상**: `backend/src/main/resources/db/migration/`

현재 파일 목록 (수정 시각 역순):
```
V1__init.sql
V2__seller_anonymization.sql
V3__payment_track3.sql
V4__order_idempotency_key.sql
V5__refund_constraints.sql
```

**결론**:
- 마지막 번호: **V5**
- STEP 1 신규 마이그레이션: **V6__add_claim_pickup_and_previous_status.sql**

---

## §3 의제 2 — ClaimRequestCommand DTO 시그니처 (STEP 4에서 사용)

**실측 대상**: `backend/src/main/java/com/zslab/mall/claim/controller/request/ClaimRequestCommand.java`

```java
// line 14-21
public record ClaimRequestCommand(
        String orderItemPublicId,
        ClaimType claimType,
        ClaimReasonCode reasonCode,
        String reasonDetail,
        Long buyerId,
        LocalDateTime requestedAt) {
}
```

**현재 필드 6개** (line 15-20):
| 필드 | 타입 |
|------|------|
| orderItemPublicId | String |
| claimType | ClaimType |
| reasonCode | ClaimReasonCode |
| reasonDetail | String |
| buyerId | Long |
| requestedAt | LocalDateTime |

**결론**:  
`previousOrderItemStatus` 필드 미존재. STEP 4에서 `OrderItemStatus previousOrderItemStatus` 추가 시 record 생성자 호출처 전건 수정 필요. Javadoc(line 10-12) 파라미터 설명도 갱신 대상.

---

## §4 의제 3 — OrderItemRepository 시그니처 (STEP 4·5에서 사용)

**실측 대상**: `backend/src/main/java/com/zslab/mall/order/repository/OrderItemRepository.java`

```java
// line 14: 인터페이스 선언
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    // line 16
    List<OrderItem> findByOrderId(Long orderId);

    // line 18
    List<OrderItem> findByOrderIdIn(Collection<Long> orderIds);

    // line 24 (Javadoc 포함 line 20-24)
    Optional<OrderItem> findByPublicId(String publicId);

    // line 30-31 (Javadoc 포함 line 26-31)
    @Query("SELECT oi.order.id FROM OrderItem oi WHERE oi.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);
}
```

**STEP 4·5 관련 메서드**:
- `findByPublicId(String publicId)` → ClaimRequestCommand 입력 해소 (line 24)
- `findOrderIdById(Long id)` → order_id 해소·recalculateStatus 위임 (line 31)
- `findById(Long id)` → JpaRepository 상속·핸들러에서 OrderItem 조회 (line 14)

---

## §5 의제 4 — OrderService.recalculateStatus 시그니처 (STEP 5에서 사용)

**실측 대상**: `backend/src/main/java/com/zslab/mall/order/service/OrderService.java`

```java
// line 105-113
public Order recalculateStatus(Long orderId) {
    Order order = findOrder(orderId);
    List<OrderItemStatus> itemStatuses = order.getItems().stream()
            .map(OrderItem::getItemStatus)
            .toList();
    OrderStatus resolved = orderStatusResolver.resolve(itemStatuses);
    order.applyResolvedStatus(resolved);
    return order;
}
```

**시그니처**: `public Order recalculateStatus(Long orderId)` (line 105)  
**인자**: `Long orderId`  
**반환**: `Order`  
**예외**: OrderRepository.findById empty 시 `IllegalArgumentException` (line 117)

**핸들러 호출 패턴** (ClaimRequestedHandlerTest line 62):
```java
verify(orderService).recalculateStatus(ORDER_ID);
```

STEP 5 신설 핸들러(ClaimRequestedHandler·ClaimRejectedHandler·ClaimCompletedHandler 패턴) 동일 호출 패턴 적용.

---

## §6 의제 5 — ClaimStatus.canTransitionTo 매트릭스 (STEP 3에서 사용)

**실측 대상**: `backend/src/main/java/com/zslab/mall/claim/enums/ClaimStatus.java`

```java
// line 29-35
public boolean canTransitionTo(ClaimStatus next) {
    return switch (this) {
        case REQUESTED -> next == APPROVED || next == REJECTED;
        case APPROVED -> next == COMPLETED;
        // 종결 상태는 어떤 전이도 불가(CLM-1·재요청은 새 행 CLM-2)
        case REJECTED, COMPLETED -> false;
    };
}
```

**전이 매트릭스**:
| from | 합법 전이 |
|------|-----------|
| REQUESTED | APPROVED, REJECTED |
| APPROVED | COMPLETED |
| REJECTED | (없음) |
| COMPLETED | (없음) |

**type 무관 정합 검증**: `ClaimStatus.canTransitionTo`에 ClaimType 분기 없음. RETURN 클레임도 동일 4상태 전이 매트릭스 공유.  
STEP 3(ClaimStatus 수정 불필요·D-98 4상태 그대로 사용)에서 확인 완료.

---

## §7 의제 6 — ClaimReasonCode enum 전체 본문 (STEP 2에서 사용)

**실측 대상**: `backend/src/main/java/com/zslab/mall/claim/enums/ClaimReasonCode.java`

현재 6값 (line 12-25):
```java
public enum ClaimReasonCode {
    BUYER_CHANGED_MIND,    // line 14: 단순 변심
    DUPLICATE_ORDER,       // line 16: 중복 주문
    PAYMENT_ISSUE,         // line 18: 결제 문제
    ORDER_MISTAKE,         // line 20: 주문 실수
    STOCK_DELAY,           // line 22: 재고/배송 지연
    OTHER                  // line 24: 기타
}
```

**Javadoc 예고 4값** (line 9):
```
Track 11 RETURN/EXCHANGE 진입 시 확장 예정: PRODUCT_DEFECT·DAMAGED_ON_ARRIVAL·WRONG_PRODUCT·DELIVERY_DELAY
```

**D-89 Q5 정합**: enum 6값 현재 구현 일치 확인.

**STEP 2 영향**: Track 14 PR-1 RETURN 진입 시 Javadoc 예고 4값 추가 필요.  
Javadoc "Track 11" 언급은 현재 Track 14이므로 갱신 필요.  
DDL 무관: claim.reason_code는 varchar(50) 무제약 (V1__init.sql 실측·마이그레이션 불요).

---

## §8 의제 7 — Claim 이벤트 record 4건 (STEP 5에서 사용)

**실측 대상**: claim/event/ 하위 4건

### ClaimRequested (7 fields)
```java
// ClaimRequested.java line 15-22
public record ClaimRequested(
        Long claimId,
        String claimPublicId,
        Long orderItemId,
        ClaimType claimType,
        ClaimStatus status,
        Long buyerId,       // ← ClaimRejected·Approved·Completed와 달리 buyerId 포함
        LocalDateTime occurredAt) {
}
```

### ClaimApproved (6 fields)
```java
// ClaimApproved.java line 20-27
public record ClaimApproved(
        Long claimId,
        String claimPublicId,
        Long orderItemId,
        ClaimType claimType,
        ClaimStatus status,
        LocalDateTime occurredAt) {
}
```

### ClaimRejected (6 fields)
```java
// ClaimRejected.java line 13-20
public record ClaimRejected(
        Long claimId,
        String claimPublicId,
        Long orderItemId,
        ClaimType claimType,
        ClaimStatus status,
        LocalDateTime occurredAt) {
}
```

### ClaimCompleted (6 fields)
```java
// ClaimCompleted.java line 16-23
public record ClaimCompleted(
        Long claimId,
        String claimPublicId,
        Long orderItemId,
        ClaimType claimType,
        ClaimStatus status,
        LocalDateTime occurredAt) {
}
```

**ClaimPickedUp 신규 record payload 설계 정합**:

제안 payload: `claimId · claimPublicId · orderItemId · claimType · pickedUpAt · occurredAt`

기존 패턴과 비교:
| 필드 | 기존 4건 | ClaimPickedUp 제안 |
|------|----------|---------------------|
| claimId | ✓ | ✓ |
| claimPublicId | ✓ | ✓ |
| orderItemId | ✓ | ✓ |
| claimType | ✓ | ✓ |
| status | ✓ | ✗ → pickedUpAt으로 대체 |
| occurredAt | ✓ | ✓ |
| pickedUpAt | ✗ | ✓ (신규) |

**차이점**: `status` 대신 `pickedUpAt(LocalDateTime)` 추가. ClaimPickedUp은 상태 전이 이벤트가 아니라 "수거 완료" 사실 통지이므로 pickedUpAt이 핵심 payload. `status`는 생략 가능(소비자가 Claim 재조회로 확인·D-30 사실 통지 패턴 정합).

**확인**: `ClaimPickedUp.java` 현재 미존재 (glob 실측). STEP 5에서 신설.

---

## §9 의제 8 — RefundService.initiate 시그니처 (STEP 5에서 사용)

**실측 대상**: `backend/src/main/java/com/zslab/mall/refund/service/RefundService.java`

```java
// line 85-86
public Refund initiate(Long claimId, long amount) {
```

**시그니처**: `public Refund initiate(Long claimId, long amount)` (line 85)  
**인자**: claimId(Long), amount(long)  
**반환**: Refund  
**예외** (line 82-84):
- `ClaimNotFoundException` — 클레임 없는 경우
- `ClaimInvalidStateException` — 클레임이 APPROVED가 아닌 경우(CLM-3)
- `RefundInvariantViolationException` — PAY-1 사전 한도 초과

**기존 ClaimApprovedHandler 호출 패턴** (ClaimApprovedHandlerTest.java line 66):
```java
verify(refundService).initiate(CLAIM_ID, ITEM_TOTAL_PRICE);
```
- amount = `OrderItem.totalPrice` (line 56-57: `ITEM_TOTAL_PRICE = 12_000L`)

**ClaimPickedUpHandler에서 1:1 재사용 정합**:  
RETURN 클레임의 수거 확인 후 환불 트리거 시 동일 패턴 적용:
```java
refundService.initiate(event.claimId(), orderItem.getTotalPrice());
```
CLM-3 게이트(APPROVED 검증)가 initiate 내부에 있으므로 핸들러는 별도 상태 검증 불필요.

---

## §10 의제 9 — 기존 통합 테스트 회귀 영향 (STEP 6에서 사용)

### ClaimIntegrationTest
**파일**: `backend/src/test/java/com/zslab/mall/claim/integration/ClaimIntegrationTest.java`  
**@Test 카운트**: **16개**

| 메서드 | 라인 | CANCEL 게이트 의존 |
|--------|------|-------------------|
| request_paidCancel_returns201AndInserts | 97 | CANCEL type 입력만 사용 |
| request_confirmedItem_returns422 | 120 | CANCEL type 입력만 사용 |
| request_otherBuyerItem_returns404 | 140 | CANCEL type 입력만 사용 |
| request_missingBuyerId_returns401 | 160 | CANCEL type 입력만 사용 |
| **request_nonCancelType_returns422 (T13)** | **171** | **핵심 CANCEL 게이트 단언·변경 필수** |
| request_existingActiveClaim_returns422 | 197 | CANCEL type 입력만 사용 |
| request_afterRejected_returns201NewRow | 221 | CANCEL type 입력만 사용 |
| request_duplicateActive_secondReturns422 | 247 | CANCEL type 입력만 사용 |
| getOne_ownClaim_returns200_hidesRequestedBy | 275 | CANCEL type 시딩 |
| getOne_otherBuyerClaim_returns404 | 298 | CANCEL type 시딩 |
| list_ownClaims_returnsPaged | 319 | CANCEL type 시딩 |
| list_buyerIsolation_onlyOwnData | 346 | CANCEL type 시딩 |
| list_buyerIsolation_size100_noLeak | 371 | CANCEL type 시딩 |
| list_sortParamIgnored_filteredByBuyer | 396 | CANCEL type 시딩 |
| approve_serviceDirect_transitionsAndPublishes | 421 | CANCEL type 시딩 |
| reject_serviceDirect_transitionsAndPublishes | 444 | CANCEL type 시딩 |

**T13 변경 의무** (line 171-193):
```java
// line 183-191: RETURN·EXCHANGE 모두 422 CLAIM_STATE_INVALID 단언
.content(requestBody(orderItemPid, "RETURN", "BUYER_CHANGED_MIND")))
    .andExpect(status().isUnprocessableEntity())
    .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));
// ...
.content(requestBody(orderItemPid, "EXCHANGE", "BUYER_CHANGED_MIND")))
    .andExpect(status().isUnprocessableEntity())
    .andExpect(jsonPath("$.code").value("CLAIM_STATE_INVALID"));
```
→ D-98 Q4 게이트 제거 후: **RETURN 분기는 201 Created** 로 변경. EXCHANGE는 PR-2 소관이므로 T13 EXCHANGE 분기 단언은 당장 유지 가능하나 분리 필요.

### ClaimEventIntegrationTest
**파일**: `backend/src/test/java/com/zslab/mall/claim/integration/ClaimEventIntegrationTest.java`  
**@Test 카운트**: **4개**

| 메서드 | 라인 |
|--------|------|
| claimRequested_transitionsItemToCancelRequested | 95 |
| claimRejected_releasesItemToPaid | 112 |
| claimCompleted_terminatesItemAndRecalculatesOrder | 128 |
| claimCompleted_idempotent_secondCallNoOp | 145 |

**CANCEL 게이트 의존**: `seedClaim`에서 `'CANCEL'` 하드코딩 (line 210). 4개 모두 CANCEL type 고정.  
**회귀 영향**: 현존 4개 테스트 자체는 CANCEL 흐름 검증이므로 변경 불필요. RETURN e2e 테스트 **신규 추가** 필요 (STEP 6 의무).

### SellerClaimIntegrationTest
**파일**: `backend/src/test/java/com/zslab/mall/claim/integration/SellerClaimIntegrationTest.java`  
**@Test 카운트**: **4개** (I1~I4)

**CANCEL 게이트 의존**: 없음. approve/reject endpoint 자체가 type 무관.  
**회귀 영향**: 0 (현존 4개 변경 불필요).

### AdminClaimIntegrationTest
**파일**: `backend/src/test/java/com/zslab/mall/claim/integration/AdminClaimIntegrationTest.java`  
**@Test 카운트**: **3개** (I1~I3)

**CANCEL 게이트 의존**: 없음.  
**회귀 영향**: 0 (현존 3개 변경 불필요).

### ClaimApprovedHandlerTest
**파일**: `backend/src/test/java/com/zslab/mall/refund/handler/ClaimApprovedHandlerTest.java`  
**@Test 카운트**: **6개**

| 메서드 | 라인 | CANCEL 게이트 의존 |
|--------|------|-------------------|
| cancel_triggersInitiateWithOrderItemTotalPrice | 61 | CANCEL type = 정상 트리거 |
| **returnType_skipsInitiate (T2)** | **71** | **RETURN skip 단언·재검토 필요** |
| **exchangeType_skipsInitiate (T2')** | **79** | **EXCHANGE skip 단언·재검토** |
| cancel_serviceIdempotentNoOp_stillInvokesOnce | 88 | CANCEL type |
| cancel_initiateThrows_exceptionNotPropagated | 100 | CANCEL type |
| cancel_orderItemMissing_skipsInitiate | 113 | CANCEL type |

**T2·T2' 재검토**:  
RETURN approved 시 ClaimApprovedHandler는 initiate를 호출하지 않아야 맞음 (RETURN은 수거 후 ClaimPickedUpHandler가 환불 트리거). 따라서 T2의 단언 자체는 계속 유효.  
그러나 Javadoc(line 31 "CANCEL 한정")은 갱신 필요.

### RefundAutoTriggerIntegrationTest
**파일**: `backend/src/test/java/com/zslab/mall/refund/integration/RefundAutoTriggerIntegrationTest.java`  
**@Test 카운트**: **3개** (I1~I3)

**CANCEL 게이트 의존**: I1~I3 모두 CANCEL type 고정 (line 108).  
**회귀 영향**: 0 (현존 3개 변경 불필요). RETURN 수거 후 환불 트리거는 신규 통합 테스트로 분리.

---

## §11 의제 10 — 기존 단위 테스트 회귀 영향 (STEP 6에서 사용)

### ClaimRequestedHandlerTest
**파일**: `backend/src/test/java/com/zslab/mall/claim/handler/ClaimRequestedHandlerTest.java`  
**@Test 카운트**: **5개**

| 메서드 | 라인 | CANCEL 게이트 의존 |
|--------|------|-------------------|
| onClaimRequested_paid_transitionsAndRecalculates | 51 | CANCEL type=정상 |
| **onClaimRequested_nonCancel_noOp** | **67** | **RETURN no-op 단언·변경 필수** |
| onClaimRequested_itemNotFound_noOp | 76 | CANCEL type |
| onClaimRequested_alreadyCancelRequested_idempotentNoOp | 85 | CANCEL type |
| onClaimRequested_notTransitionable_noOp | 99 | CANCEL type |

**변경 필수** (line 67-72):
```java
@Test
void onClaimRequested_nonCancel_noOp() {
    handler.onClaimRequested(event(ClaimType.RETURN));
    verify(orderItemRepository, never()).findById(anyLong());  // 이 단언이 변경됨
    verify(orderService, never()).recalculateStatus(anyLong());
}
```
→ D-98 Q4 게이트 제거 후: RETURN type도 findById 호출 + `RETURN_REQUESTED` 전이 → 단언 반전 필요.

### ClaimRejectedHandlerTest
**파일**: `backend/src/test/java/com/zslab/mall/claim/handler/ClaimRejectedHandlerTest.java`  
**@Test 카운트**: **4개**

| 메서드 | 라인 | CANCEL 게이트 의존 |
|--------|------|-------------------|
| onClaimRejected_cancelRequested_releasesToPaid | 50 | CANCEL type=정상 |
| **onClaimRejected_nonCancel_noOp** | **65** | **RETURN no-op 단언·변경 필수** |
| onClaimRejected_itemNotFound_noOp | 75 | CANCEL type |
| onClaimRejected_notCancelRequested_noOp | 84 | CANCEL type |

**변경 필수** (line 65-71): RETURN rejected → `RETURN_REQUESTED → 스냅샷 상태` 복원 필요 → 단언 반전 필요.

### ClaimCompletedHandlerTest
**파일**: `backend/src/test/java/com/zslab/mall/claim/handler/ClaimCompletedHandlerTest.java`  
**@Test 카운트**: **4개**

| 메서드 | 라인 | CANCEL 게이트 의존 |
|--------|------|-------------------|
| onClaimCompleted_cancelRequested_terminatesToCancelled | 50 | CANCEL type=정상 |
| **onClaimCompleted_nonCancel_noOp** | **65** | **RETURN no-op 단언·변경 필수** |
| onClaimCompleted_itemNotFound_noOp | 75 | CANCEL type |
| onClaimCompleted_alreadyCancelled_idempotentNoOp | 84 | CANCEL type |

**변경 필수** (line 65-71): RETURN completed → `RETURNED` 전이 → 단언 반전 필요.

### ClaimRefundCompletedHandlerTest
**파일**: `backend/src/test/java/com/zslab/mall/claim/handler/ClaimRefundCompletedHandlerTest.java`  
**상태**: **파일 미존재** (glob 실측 확인)

**본체** (`ClaimRefundCompletedHandler.java`) 현황:
```java
// line 47-50
if (claim.getType() != ClaimType.CANCEL) {
    // RETURN·EXCHANGE는 본 트랙 미전이(수거/교환출고 단계 필요·expected-spec §3.2)
    return;
}
```
CANCEL 게이트 line 47에 존재. Track 14 PR-1에서 RETURN도 markCompleted 대상이 되므로 이 게이트 수정 필요.

**STEP 6 의무**: ClaimRefundCompletedHandlerTest 신설 (CLAUDE.md: "기존 테스트 없는 도메인 수정 시 — 변경 영역만이라도 테스트 추가 권장").

### OrderItemStatusTest
**파일**: `backend/src/test/java/com/zslab/mall/order/enums/OrderItemStatusTest.java`  
**@Test 카운트**: **5개**

| 메서드 | 라인 |
|--------|------|
| canTransitionTo_coversFullMatrix | 36 |
| terminalStatuses_haveNoOutgoingTransition | 50 |
| reverseAndSkipTransitions_blocked | 68 |
| hasTwelveValues | 77 |
| cancelRequested_allowsPaidLockRelease | 82 |

**oracle Map 현재 상태** (line 18-33):
```java
map.put(OrderItemStatus.CANCEL_REQUESTED,   EnumSet.of(CANCELLED, PAID));         // line 26
map.put(OrderItemStatus.RETURN_REQUESTED,   EnumSet.of(RETURNED));                // line 28
map.put(OrderItemStatus.EXCHANGE_REQUESTED, EnumSet.of(EXCHANGED));               // line 30
```

**D-98 Q7 변경 의무** (canTransitionTo 매트릭스 확장 후 oracle 동반 갱신):
| 상태 | 현재 합법 타깃 | D-98 Q7 신규 합법 타깃 |
|------|---------------|------------------------|
| CANCEL_REQUESTED | CANCELLED, PAID | CANCELLED, PAID, **PREPARING** |
| RETURN_REQUESTED | RETURNED | RETURNED, **SHIPPING, DELIVERED** |
| EXCHANGE_REQUESTED | EXCHANGED | EXCHANGED, **DELIVERED** |

→ `canTransitionTo_coversFullMatrix` (line 36) oracle map 갱신 필수.  
→ `cancelRequested_allowsPaidLockRelease` (line 82-88) 이름·단언 갱신 필요 (D-98 "의미 변경·claim-lock release 단어 더 이상 의미 부재").

### ClaimServiceTest
**파일**: `backend/src/test/java/com/zslab/mall/claim/service/ClaimServiceTest.java`  
**@Test 카운트**: **28개**

**CANCEL 게이트 직접 의존 단언**:
```java
// line 167-180
@Test
@DisplayName("request: CANCEL 외 유형(RETURN) → ClaimInvalidStateException(Q6)·CLM-5 검사 전 차단")
void request_nonCancelType_throws() {
    // ...
    assertThatThrownBy(() -> claimService.request(command(ClaimType.RETURN)))
            .isInstanceOf(ClaimInvalidStateException.class);  // ← 이 단언이 변경됨
```
→ D-98 Q4 게이트 제거 후: RETURN type 정상 처리 → `isInstanceOf(ClaimInvalidStateException.class)` 단언 삭제 또는 반전 필수.

---

## §12 기조 5 자체 감사

| 기조 | 항목 | 자체 평가 |
|------|------|-----------|
| 1 운영 개발 용이성 | STEP별 최소 변경 매트릭스 제공 | ✓ |
| 2 객관적 판단 | ClaimApprovedHandlerTest T2·T2' 재검토 시 "단언 유효"로 결론 도달 | ✓ |
| 3 과잉문서 회피 | 10 의제 1:1·필요 이상 섹션 없음 | ✓ |
| 4 과잉개발 회피 | STEP 1~6 이외 추가 제안 없음 | ✓ |
| **5 추측·추정 절대 금지·실측 우선** | | |
| — 의제 1 migration 번호 | glob 실측 → V5 확인·V6 결론 | ✓ |
| — 의제 2 ClaimRequestCommand 필드 | Read 실측 → 6필드·previousOrderItemStatus 미존재 | ✓ |
| — 의제 3 OrderItemRepository 시그니처 | Read 실측·라인 번호 인용 | ✓ |
| — 의제 4 recalculateStatus 시그니처 | Read 실측 line 105 인용 | ✓ |
| — 의제 5 ClaimStatus.canTransitionTo | Read 실측 line 29-35 인용 | ✓ |
| — 의제 6 ClaimReasonCode 6값 | Read 실측 line 12-25 인용 | ✓ |
| — 의제 7 이벤트 record 4건 | Read 실측·각 라인 번호 인용 | ✓ |
| — 의제 7 ClaimPickedUp 미존재 | glob 실측 → 0건 확인 | ✓ |
| — 의제 8 RefundService.initiate | Read 실측 line 85 인용 | ✓ |
| — 의제 9 통합 테스트 카운트 | Read 실측·@Test 메서드 수동 열거 | ✓ |
| — 의제 10 ClaimRefundCompletedHandlerTest 미존재 | glob 실측 → 0건 확인 | ✓ |

**결론**: 본 보고서 §2~§11 전건은 Read/glob 실측 결과 직접 인용. 추측·추정 항목 0건.

---

## §13 STEP 1~6 진입 시 사용 매트릭스

| STEP | 작업 | 참조 §섹션 |
|------|------|------------|
| STEP 1 | V6__add_claim_pickup_and_previous_status.sql 신규 마이그레이션 | §2 (마이그레이션 번호 V6 확정) |
| STEP 2 | ClaimReasonCode +4값·ClaimPickedUp record 신설 | §7 (Javadoc 예고 4값)·§8 (ClaimPickedUp 미존재·payload 정합) |
| STEP 3 | ClaimStatus·OrderItemStatus canTransitionTo 확장 | §6 (ClaimStatus 매트릭스·type 무관)·§11 OrderItemStatusTest oracle 갱신 위치 |
| STEP 4 | ClaimRequestCommand previousOrderItemStatus 추가·ClaimService request RETURN 게이트 제거 | §3 (현재 6필드)·§4 (findByPublicId·findOrderIdById 호출 패턴) |
| STEP 5 | 핸들러 3건 확장·ClaimPickedUpHandler 신설·RefundService.initiate 재사용 | §4 (findById·recalculateStatus)·§5 (recalculateStatus 시그니처)·§8 (ClaimPickedUp 미존재)·§9 (initiate 시그니처·호출 패턴) |
| STEP 6 | 테스트 갱신 (핸들러 4건 nonCancel 단언·OrderItemStatusTest oracle·ClaimServiceTest request_nonCancelType·ClaimRefundCompletedHandlerTest 신설) | §10 (통합 변경 위치)·§11 (단위 변경 위치·ClaimRefundCompletedHandlerTest 미존재 확인) |

---

*본 보고서는 기조 5(추측·추정 절대 금지·실측 우선) 준수. 전건 라인 번호 직접 인용.*
