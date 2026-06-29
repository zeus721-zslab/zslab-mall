# Track 9 PR-C 정찰 보고서

> 작성일: 2026-06-29
> 브랜치: main HEAD 8844a48 (PR-B 머지 후)
> 정찰 룰 #1·#2·#3·#4 적용 — 실측 인용 의무

---

## §1. 트랙·범위·진입 조건

- **트랙**: Track 9 / PR-C (S급·외부 검토 권장)
- **D-88 Q6 정의**: "이벤트 핸들러 (ClaimRequested/Approved/Rejected → OrderItem 동기화·Order.status 재계산 hook)"
- **범위**: `claim.handler` 패키지 3 핸들러 신설. OrderItem.item_status 전이 동기화 (ClaimRequested→CANCEL_REQUESTED, ClaimRejected→원상 복귀) + Order.status 재계산 hook 신설.
- **선행 머지 의무**: PR-B (main HEAD 8844a48 → feat/track-9-pr-b aaa853c 머지 완료 확인)
- **가드 2 라벨**: S급 — 외부 검토 권장

### LIMITATION (D-90 Q3 α-1 박제)
- Track 9 PR-C는 "Claim 이벤트를 Order에 연결하는 최소 구현 PR"이다.
- `OrderItemStatus.CANCEL_REQUESTED → PAID` 단일 전이는 claim-lock release(재요청 허용 unlock) 목적이며 과거 상태 복원이 아니다.
- PREPARING 직접 복원은 직전 상태 정보 부재로 미지원·운영 흐름은 `PAID → PREPARING` 정상 재전이로 자연 재개.

---

## §2. 패턴 SoT 정찰 (A-1~A-10)

| ID | 파일 | 실측 1줄 인용 |
|---|---|---|
| A-1 | `payment/handler/OrderEventHandler.java` | `@EventListener` 동기·동일 트랜잭션 패턴 (PaymentCompleted 소비·D-29·D-33). `@TransactionalEventListener` 미사용. |
| A-2 | `claim/handler/ClaimRefundCompletedHandler.java` | `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(Propagation.REQUIRES_NEW)` 패턴 (D-69·D-75). 멱등 가드 2건(null·비APPROVED) + type 분기(CANCEL 한정). |
| A-3 | `order/service/OrderStatusResolver.java` | `public OrderStatus resolve(List<OrderItemStatus> itemStatuses)` 평가 순서 [5]→[6]→[7]→[4]→[3]→[2]. @Component 빈. |
| A-4 | `order/entity/OrderItem.java` | `changeStatus(next)`: canTransitionTo(next) 검증 후 전이. `@Getter(NONE) private Order order` — order 필드 외부 노출 없음. |
| A-5 | `order/entity/Order.java` | `applyResolvedStatus(resolvedStatus)`: Resolver 결과 단순 반영. `getItems()` public 수동 getter (LAZY·`Collections.unmodifiableList`). |
| A-6 | `claim/service/ClaimService.java` | `request·approve·reject·markCompleted` 4 메서드. save→publish 순서(D-29). `markCompleted` 이벤트 **미발행** (현재 ClaimCompleted 이벤트 없음). |
| A-7 | `claim/event/` (3 record) | `ClaimRequested(claimId, claimPublicId, orderItemId, claimType, status, buyerId, occurredAt)` / `ClaimApproved(claimId, claimPublicId, orderItemId, claimType, status, occurredAt)` / `ClaimRejected(동일)`. payload 사실 통지 원칙(D-30). |
| A-8 | `order/enums/OrderItemStatus.java` | `case CANCEL_REQUESTED -> next == CANCELLED` — **CANCEL_REQUESTED→PAID 역전이 없음**. 종결(CONFIRMED·CANCELLED·RETURNED·EXCHANGED) → false 전건. |
| A-9 | `order/repository/OrderItemRepository.java` | `findByPublicId(String)` + `findOrderIdById(@Param("id") Long id)` 양 메서드 존재. `:id` 바인딩 명시. |
| A-10 | `claim/integration/ClaimIntegrationTest.java` | LT-02 try-finally seed wrapper (`try { execute("SET FK=0"); work.run(); } finally { execute("SET FK=1"); }`). 첫 명시 사례. |

---

## §3. PR-C 영향 범위 정찰 (B-1~B-5)

### B-1. claim.handler 디렉토리 (실측)
```
backend\src\main\java\com\zslab\mall\claim\handler\
  └─ ClaimRefundCompletedHandler.java   ← 기존 (Track 5)
```
**ClaimRequestedHandler·ClaimApprovedHandler·ClaimRejectedHandler 전건 부재 확인** → PR-C 신설 대상.

### B-2. OrderItem.changeStatus 호출자 (grep 실측)
```
No matches found
```
`changeStatus(next)` 현재 main/java 호출자 **없음**. PR-C 핸들러가 첫 호출자가 됨. 단위 테스트에서만 사용 중으로 추정.

### B-3. OrderStatusResolver 호출 경로 (실측)
현재 유일 호출자: `OrderService.recalculateStatus(Long orderId)` (line 105).

```java
// OrderService.java:105
public Order recalculateStatus(Long orderId) {
    Order order = findOrder(orderId);
    List<OrderItemStatus> itemStatuses = order.getItems().stream()
            .map(OrderItem::getItemStatus).toList();
    OrderStatus resolved = orderStatusResolver.resolve(itemStatuses);
    order.applyResolvedStatus(resolved);
    return order;
}
```

PR-C 핸들러는 `OrderService.recalculateStatus(orderId)` 호출로 Order.status 재계산 가능 (메서드 신설 불필요).
OrderService가 REQUIRES_NEW 트랜잭션에서 호출되면 `@Transactional(REQUIRED)` 기본 전파 → 동일 트랜잭션 조인 → 정합 보장.

### B-4. Repository 조회 메서드 충족 여부
| 핸들러 | 필요 메서드 | 현황 |
|---|---|---|
| ClaimRequestedHandler | `orderItemRepository.findById(orderItemId)` + `findOrderIdById(itemId)` | **존재** ✅ |
| ClaimApprovedHandler | `claimRepository.findById(claimId)` + `orderItemRepository.findById(orderItemId)` | **존재** ✅ |
| ClaimRejectedHandler | `orderItemRepository.findById(orderItemId)` + `findOrderIdById(itemId)` | **존재** ✅ |
| (미래) ClaimCompletedHandler | `orderItemRepository.findById(orderItemId)` + `findOrderIdById(itemId)` | **존재** ✅ |

**신규 Repository 메서드 신설 불필요** — 기존 메서드로 충분.

### B-5. OrderEventHandler 명명 보정 백로그
`OrderEventHandler` = PaymentCompleted 단일 핸들러. 명칭 미스매치(이름은 Order·내용은 Payment). non-blocking 확인(현재 동기·동일 트랜잭션·rollback-coupled). 명명 보정은 PaymentCompleted 핸들러 신설·PR-C 범위 외 (백로그 추가 권장).

---

## §4. 매트릭스 정찰 (C-1~C-4)

### C-1. OrderItemStatus.canTransitionTo Claim 진입 5건 실측

| 전이 | canTransitionTo 결과 | SoT 정합 |
|---|---|---|
| PAID → CANCEL_REQUESTED | `case PAID -> next == PREPARING \|\| next == CANCEL_REQUESTED` → **true** ✅ | D-88 Q1 정합 |
| PREPARING → CANCEL_REQUESTED | `case PREPARING -> next == SHIPPING \|\| next == CANCEL_REQUESTED` → **true** ✅ | D-88 Q1 정합 |
| SHIPPING → RETURN_REQUESTED | `case SHIPPING -> next == DELIVERED \|\| next == RETURN_REQUESTED` → **true** ✅ | D-88 Q1 정합 |
| DELIVERED → RETURN_REQUESTED | `case DELIVERED -> ... \|\| next == RETURN_REQUESTED \|\| ...` → **true** ✅ | D-88 Q1 정합 |
| DELIVERED → EXCHANGE_REQUESTED | `case DELIVERED -> ... \|\| next == EXCHANGE_REQUESTED` → **true** ✅ | D-88 Q1 정합 |
| SHIPPING → CANCEL_REQUESTED | `case SHIPPING` 미포함 → **false (차단)** ✅ | D-88 Q1 차단 정합 |
| CONFIRMED → *_REQUESTED | `case CONFIRMED -> false` → **false (차단)** ✅ | D-88 Q2 차단 정합 |

### C-2. 종결 전이 존재 여부 실측

| 전이 | canTransitionTo 결과 |
|---|---|
| CANCEL_REQUESTED → CANCELLED | `case CANCEL_REQUESTED -> next == CANCELLED` → **true** ✅ |
| RETURN_REQUESTED → RETURNED | `case RETURN_REQUESTED -> next == RETURNED` → **true** ✅ |
| EXCHANGE_REQUESTED → EXCHANGED | `case EXCHANGE_REQUESTED -> next == EXCHANGED` → **true** ✅ |

### C-3. CANCEL_REQUESTED → PAID 원상 복귀 전이 ⚠️ WARN

**실측 결과: 전이 부재.**

```java
case CANCEL_REQUESTED -> next == CANCELLED;
// CANCEL_REQUESTED → PAID: 허용 케이스 없음 → false 반환
```

ClaimRejectedHandler는 OrderItem을 CANCEL_REQUESTED에서 원래 상태(PAID 또는 PREPARING)로 복귀해야 하나 `canTransitionTo(PAID)` = false, `canTransitionTo(PREPARING)` = false.

**원상 복귀 전략 미결정 → 결정 라운드 의제 Q3** 참조.

### C-4. OrderItem.previous_status 필드 ⚠️ WARN

**실측 결과: 필드 없음.**

`OrderItem.java` 전체 필드: `id, order(Getter.NONE), productId, variantId, sellerId, quantity, unitPrice, totalPrice, itemStatus`. `previousStatus` 또는 이에 준하는 직전 상태 추적 필드 없음.

원상 복귀에 직전 상태를 저장하려면 필드 신설 또는 별도 조회 전략 필요 → 결정 라운드 의제 Q3.

---

## §5. Track 5 OOS 5항목 검증 (D-1~D-5)

| 항목 | 검증 결과 |
|---|---|
| D-1: Claim 요청 API | BuyerClaimController 3 endpoint 존재 확인 (POST·GET 단건·GET 목록) ✅ PR-B 머지 완료 |
| D-2: Claim 승인/거절 워크플로우 | ClaimService.approve + ClaimService.reject 존재 ✅. Seller/Admin endpoint 미신설 (Track 10 이연) ✅ |
| D-3: OrderItem.item_status 전이 동기화 | 핸들러 미신설 ✅ → PR-C 소관 확인 |
| D-4: Order.status 재계산 hook | 핸들러 미신설 ✅ → PR-C 소관 확인. OrderService.recalculateStatus 메서드 존재 (B-3 실측) |
| D-5: Claim REJECTED 경로 처리 | ClaimRejectedHandler 미신설 ✅ → PR-C 소관 확인. 원상 복귀 전략 미결정 (WARN-1) |

**5항목 전건 현황 확인·PR-C 진입 조건 기충족** (단, 결정 라운드 후 최종 확정).

---

## §6. 신규/수정 파일 최종 영향 범위 (D-90 확정·12건)

> 정찰 룰 #3: WARN 해소 후 최종 확정 박제 완료. D-90 결정(Q1 α·Q2 γ·Q3 α-1·Q4 α·Q5 β) 반영.

### 6.1 신규 파일 (5건)

| # | 경로 | 책임 |
|---|---|---|
| 1 | `claim/handler/ClaimRequestedHandler.java` | ClaimRequested → OrderItem PAID/PREPARING→CANCEL_REQUESTED + recalculateStatus (AFTER_COMMIT·REQUIRES_NEW) |
| 2 | `claim/handler/ClaimRejectedHandler.java` | ClaimRejected → OrderItem CANCEL_REQUESTED→PAID claim-lock release + recalculateStatus (D-90 Q3 α-1) |
| 3 | `claim/event/ClaimCompleted.java` | Claim.COMPLETED 이벤트 record (ClaimApproved 1:1·D-90 Q4) |
| 4 | `claim/handler/ClaimCompletedHandler.java` | ClaimCompleted → OrderItem CANCEL_REQUESTED→CANCELLED 종결 + recalculateStatus (D-90 Q4) |
| 5 | `claim/integration/ClaimEventIntegrationTest.java` | E2E (NO @Transactional+TransactionTemplate·LT-02·중첩 AFTER_COMMIT 실측·4건) |

### 6.2 수정 파일 (5건)

| # | 경로 | 변경 |
|---|---|---|
| 1 | `claim/service/ClaimService.java` | `markCompleted`에 ClaimCompleted 발행 1줄 (save→publish·D-29·D-90 Q4) |
| 2 | `order/enums/OrderItemStatus.java` | canTransitionTo CANCEL_REQUESTED case에 `\|\| next == PAID` + Javadoc 1줄 (D-90 Q3) |
| 3 | `docs/architecture-baseline/invariants.md` | §2.13 CLM-2 비고 1줄 (D-90 Q3) |
| 4 | `docs/architecture-baseline/state-machine.md` | §3 비고 1줄 (D-90 Q3) |
| 5 | `docs/track-9/pr-c/recon-report.md` | §1 LIMITATION·§6·§7·§9 갱신 |

### 6.3 단위 테스트 (검증 동반·신규 3·수정 2)
- 신규: `ClaimRequestedHandlerTest`·`ClaimRejectedHandlerTest`·`ClaimCompletedHandlerTest` (각 happy+type/null/멱등 가드)
- 수정: `OrderItemStatusTest` (오라클 + 명시 케이스)·`ClaimServiceTest` (markCompleted 이벤트 발행 + 멱등 미발행)

### 6.4 제거 (정찰 §6 예비 목록 대비)
- ❌ ClaimApprovedHandler 미신설 (D-90 Q2 γ)
- ❌ OrderItem.previousItemStatus 필드 미도입 (D-90 Q3 α-1·β 기각)
- ❌ DDL V6 마이그레이션 미신설 (D-90 Q3 β 기각)

---

## §7. WARN 발견 사항

### WARN-1. CANCEL_REQUESTED → 원상 복귀 전이 부재 [✅ 해소·D-90 Q3 α-1·CANCEL_REQUESTED→PAID claim-lock release]
- **실측**: `case CANCEL_REQUESTED -> next == CANCELLED` 단일 케이스. PAID/PREPARING 복귀 불허.
- **영향**: ClaimRejectedHandler가 OrderItem.changeStatus 직접 호출 불가.
- **해소 전략 후보**: Q3 결정 (α 역전이 추가 / β bypass 메서드 / γ 이연)

### WARN-2. CANCELLED 종결 전이 처리 메커니즘 없음 [✅ 해소·D-90 Q4 α·ClaimCompleted 이벤트+ClaimCompletedHandler]
- **실측**: `ClaimService.markCompleted` 이벤트 **미발행**. ClaimRefundCompletedHandler(Track 5)는 Claim만 COMPLETED 처리, OrderItem CANCEL_REQUESTED → CANCELLED 전이 없음.
- **영향**: D-88 Q3 "완료 전이 (Claim.COMPLETED 시: CANCEL_REQUESTED → CANCELLED)" 미처리 상태.
- **해소 전략 후보**: Q4 결정 (α ClaimCompleted 이벤트 신설 / β ClaimRefundCompletedHandler 수정)

### WARN-3. ClaimApprovedHandler 역할 미결정 [✅ 해소·D-90 Q2 γ·핸들러 미신설·ClaimApproved 발행은 Track 10·NotificationLog 미래 소비자용]
- **실측**: ClaimApproved Javadoc: `"소비측 핸들러 (Refund 생성 트리거 등)는 Track 9 PR-C 소관"`. 그러나 D-88 Q6은 `"OrderItem 동기화·Order.status 재계산 hook"` 명시.
- **긴장**: CANCEL형 OrderItem은 ClaimApproved 시 CANCEL_REQUESTED 유지 (추가 전이 없음). 핸들러가 RefundService.initiate를 자동 호출할지, Track 10 Seller/Admin 승인 endpoint가 직접 호출할지 미결정.
- **해소 전략 후보**: Q2 결정 (α 자동 Refund 트리거 / β Track 10 이연)

### WARN-4. PR-C 핸들러 트랜잭션 정책 미결정 [✅ 해소·D-90 Q1 α·AFTER_COMMIT+REQUIRES_NEW]
- **SoT 참조**: ClaimRefundCompletedHandler = AFTER_COMMIT + REQUIRES_NEW (D-75). OrderEventHandler = @EventListener (동기·동일 트랜잭션).
- **PR-C 핸들러 소비 대상**: ClaimRequested/Approved/Rejected — ClaimService 동기 발행(D-29 save→publish).
- **영향**: AFTER_COMMIT 채택 시 `@SpringBootTest @Transactional` 통합 테스트에서 핸들러 미실행 (commit 없음). 테스트 전략 변경 필요.
- **해소 전략 후보**: Q1 결정 (α AFTER_COMMIT / β @EventListener)

---

## §8. 결정 라운드 의제 후보

> 외부 검토 권장 (S급·D-88). 정찰 실측 결과 기반 의제.

| Q | 항목 | 옵션 | 정찰 근거 |
|---|---|---|---|
| Q1 | PR-C 핸들러 트랜잭션 정책 | α AFTER_COMMIT + REQUIRES_NEW (ClaimRefundCompletedHandler 패턴 1:1·D-75) / β @EventListener (동기·단순) | A-2: AFTER_COMMIT 패턴 확인. WARN-4: 테스트 전략 연동 |
| Q2 | ClaimApprovedHandler 역할 범위 | α OrderItem 동기화 + RefundService.initiate 자동 트리거 / β OrderItem 동기화만 (Refund 트리거 = Track 10 Seller 승인 endpoint) | A-6: markCompleted 이벤트 미발행. A-7: ClaimApproved payload 중 amount 없음 |
| Q3 | ClaimRejectedHandler 원상 복귀 전략 | α canTransitionTo에 CANCEL_REQUESTED→PAID/PREPARING 역전이 추가 (PR-C 또는 별도 PR) / β OrderItem.revertStatus() bypass 메서드 신설 (previous_status 필드 또는 직접 필드 설정) / γ 원상 복귀 이연 (CANCEL_REQUESTED 상태에서 Rejected 상태임을 Claim 이력으로만 처리·별도 트랙) | A-8: CANCEL_REQUESTED→PAID 불허 (WARN-1). C-4: previous_status 없음 (WARN-1) |
| Q4 | CANCELLED 종결 전이 처리 | α ClaimService.markCompleted에 ClaimCompleted 이벤트 발행 추가 → ClaimCompletedHandler 신설 / β ClaimRefundCompletedHandler (Track 5) 확장 → OrderItem.changeStatus(CANCELLED) 추가 / γ 이연 (별도 트랙) | A-6: markCompleted 이벤트 미발행 (WARN-2). D-88 Q3: 완료 전이 PR-C 소관 명시 |
| Q5 | PR-C E2E 통합 테스트 전략 | α @SpringBootTest + @Transactional (ClaimIntegrationTest 패턴·Q1 β 채택 시) / β @SpringBootTest NO @Transactional + TransactionTemplate (RefundWebhookIntegrationTest 패턴·Q1 α 채택 시) | A-2: AFTER_COMMIT 패턴은 @Transactional 테스트에서 핸들러 미실행. A-10: ClaimIntegrationTest LT-02 try-finally 1:1 정합 의무 |

---

## §9. 진입 조건

> 결정 라운드 후 해소 여부 최종 확정 의무.

| # | 조건 | 상태 |
|---|---|---|
| 1 | PR-B 머지 완료 (main HEAD 8844a48) | [x] |
| 2 | D-88 Q6 PR-C 범위 명시 정찰 완료 | [x] |
| 3 | claim.handler 신규 3건 부재 확인 | [x] |
| 4 | OrderItem.changeStatus 호출자 없음 확인 | [x] |
| 5 | OrderService.recalculateStatus 메서드 존재 확인 | [x] |
| 6 | Q1 (트랜잭션 정책) 결정 완료 | [x] D-90 Q1 α (AFTER_COMMIT+REQUIRES_NEW) |
| 7 | Q2 (ClaimApprovedHandler 역할) 결정 완료 | [x] D-90 Q2 γ (미신설) |
| 8 | Q3 (원상 복귀 전략) 결정 완료 | [x] D-90 Q3 α-1 (CANCEL_REQUESTED→PAID claim-lock release) |
| 9 | Q4 (CANCELLED 종결 전이) 결정 완료 | [x] D-90 Q4 α (ClaimCompleted 이벤트+ClaimCompletedHandler) |
| 10 | Q5 (E2E 테스트 전략) 결정 완료 | [x] D-90 Q5 β (NO @Transactional+TransactionTemplate) |
| 11 | §6 영향 범위 표 최종 확정 박제 | [x] (본 절 §6 갱신 완료) |
