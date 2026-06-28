# Track 8 PR-B 정찰 보고서

> 트랙: Track 8 / PR-B (A급)
> 대상: Order Aggregate Root State Machine 메서드 + Invariant 검증
> 정찰일: 2026-06-29
> 정찰 방식: MCP read-only (Claude.ai)
> 정찰 commit: 347a663 (PR-A 종료 직후 main HEAD)
> 정찰 룰 갱신 반영: 파일 전체 read 의무·메서드 목록 전체 확인 의무 (PR-A 사후 정정 §2.1.1)

---

## 1. 정찰 범위

| # | 파일 | 정찰 깊이 |
|---|---|---|
| 1 | order/entity/Order.java | 전체 read |
| 2 | order/entity/OrderItem.java | 전체 read |
| 3 | order/entity/OrderShippingSnapshot.java | 전체 read |
| 4 | order/enums/OrderStatus.java | 전체 read |
| 5 | order/enums/OrderItemStatus.java | 전체 read |
| 6 | order/service/OrderStatusResolver.java | 전체 read |
| 7 | order/service/OrderService.java | 전체 read |
| 8 | claim/handler/ClaimRefundCompletedHandler.java | 전체 read (WARN-1 해소) |
| 9 | payment/handler/PaymentRefundCompletedHandler.java | 전체 read (WARN-1 해소) |
| 10 | payment/handler/OrderEventHandler.java | 전체 read (WARN-1 해소) |
| 11 | claim/service/ClaimService.java | 전체 read (WARN-1 해소) |
| 12 | claim/entity/Claim.java | 전체 read (WARN-1 해소) |

---

## 2. 정찰 결과 — 현 상태 박제

### 2.1 이미 구현 완료 (Track 2~5 누적 산출물)

**Order.java** (Aggregate Root):
- create(buyerId, orderNo, discountAmount, shippingFee) — 필수값 검증·status=PENDING_PAYMENT·totalPrice 0 초기화
- addItem(OrderItem) — null 가드·양측 연결·totalPrice 누적
- attachSnapshot(OrderShippingSnapshot) — QB-10 A'-1·1:1 양측 연결
- markPaid(LocalDateTime) — 동기화 규칙 [1]·all OrderItem PAID + Order.status=PAID + paid_at (Resolver 미경유)
- applyResolvedStatus(OrderStatus) — Resolver 결과 반영 (전이 검증 없음·ORD-2 정합·D-16)
- markOrdered(LocalDateTime) — ordered_at (D-42 정렬 기준)
- getItems() — Collections.unmodifiableList (Aggregate 무결성)

**OrderItem.java**:
- create(productId, variantId, sellerId, quantity, unitPrice, totalPrice) — 필수값 + quantity ≥ 1 + ORD-5 검증 (total = unit × quantity)
- assignOrder(Order) — package-private (외부 직접 호출 차단)
- changeStatus(OrderItemStatus next) — canTransitionTo 검증 후 전이·IllegalStateException
- markPaid() — ORDERED→PAID 단축 메서드

**OrderShippingSnapshot.java**:
- 불변 스냅샷·create(...)·assignOrder (package-private)·belongsTo(orderId) 보조 메서드

**OrderStatus.java** (enum):
- 8값 PENDING_PAYMENT·PAID·PREPARING·SHIPPING·DELIVERED·CONFIRMED·CANCELLED·PARTIAL_CANCEL
- canTransitionTo 의도적 부재 — Javadoc에 D-16·ORD-2 정합 박제 완료

**OrderItemStatus.java** (enum):
- 12값 (state-machine §3 정합)
- canTransitionTo() 매트릭스 완전 구현 (QB-11 4규칙):
  - 진행 인접: ORDERED→PAID→PREPARING→SHIPPING→DELIVERED→CONFIRMED
  - 요청→종결: CANCEL_REQUESTED→CANCELLED·RETURN_REQUESTED→RETURNED·EXCHANGE_REQUESTED→EXCHANGED
  - 종결 차단: CONFIRMED·CANCELLED·RETURNED·EXCHANGED → 어떤 전이도 false
  - 역방향·건너뛰기 차단 (switch default)

**OrderStatusResolver.java**:
- Domain Service (@Component)·D-16 정합
- 평가 순서 [5]→[6]→[7]→[4]→[3]→[2]·기본값 PAID
- ORD-1 가드 (빈 입력·null 차단)

**OrderService.java**:
- createOrder(CreateOrderCommand) — ORD-1 가드·order_no UK 재시도(1회)·OrderPlaced 발행 (D-29 save→publish)
- markPaid(orderId, paidAt) — Order 조회 후 markPaid 위임
- recalculateStatus(orderId) — Resolver 호출 + applyResolvedStatus 적용

### 2.1.1 사후 정정 (WARN-1 해소 후)

PR-B 정찰 1차 후 WARN-1 해소를 위해 Track 5 핸들러 (ClaimRefundCompletedHandler·PaymentRefundCompletedHandler·OrderEventHandler)·ClaimService·Claim.java 전체 read 수행. 발견:

- Track 5 핸들러는 OrderItem 전이 자체를 수행하지 않음 — Claim.markCompleted (APPROVED→COMPLETED)·Payment.markCancelled만 처리
- ClaimService는 markCompleted 1건만 보유·OrderItem 갱신 위임 메서드 부재
- OrderItem이 *_REQUESTED 또는 종결 상태로 전이되는 코드 경로 부재 (Track 5 범위 외)

근거: expected-spec.md §1.2 (Claim 요청·승인/거절 워크플로우 OOS)·D-80 (Track 6 Gate 환불 E2E Claim 생성 seed 시딩 허용).

결론: OrderItemStatus.canTransitionTo의 Claim 진입 전이 부재는 의도된 이연 상태 그대로 유지 (Track 5에서 추가 안 됨). 추가 시점은 후속 Claim 요청 API 트랙 (expected-spec §1.2)이며 Track 8 PR-B 범위 외.

OrderItemStatus.java Javadoc 보정 1건만 필요: "Track 5(Refund Flow)에서 Claim 이벤트 소비 로직과 함께 본 매트릭스에 추가한다" → "Claim 요청·승인/거절 워크플로우 트랙(expected-spec §1.2)에서 Claim 이벤트 소비 로직과 함께 본 매트릭스에 추가한다".

후속 정찰 룰: 인계 메모의 "후속 트랙 자연 진입" 문구는 트랙 식별자(트랙 번호·기능 이름) 명시 의무. 단순히 "Track 5"·"후속 트랙" 표기는 시점 경과 후 드리프트 위험.

### 2.2 Invariant 강제 위치 박제

| # | Invariant | 강제 위치 | 상태 |
|---|---|---|---|
| ORD-1 | Order는 OrderItem ≥1 | OrderService.createOrder 진입부 + OrderStatusResolver.resolve 진입부 (이중 가드) | ✓ 강제 |
| ORD-2 | Order.status = Resolver 결과 | Order.applyResolvedStatus + OrderService.recalculateStatus | ✓ 강제 |
| ORD-3 | OrderItem.seller_id 멀티벤더 혼재 허용 | 도메인 정책·강제 검증 대상 아님 | N/A |
| ORD-4 | order_no UNIQUE | DB UK + OrderService.generateUniqueOrderNo 재시도 가드 | ✓ 강제 |
| ORD-5 | OrderItem.total_price = unit × quantity | OrderItem.create 진입부 IllegalArgumentException | ✓ 강제 |

### 2.3 OrderItemStatus 전이 매트릭스 — Claim 진입 전이 의도된 이연

현 매트릭스:

| from | 허용 to |
|---|---|
| ORDERED | PAID |
| PAID | PREPARING |
| PREPARING | SHIPPING |
| SHIPPING | DELIVERED |
| DELIVERED | CONFIRMED |
| CANCEL_REQUESTED | CANCELLED |
| RETURN_REQUESTED | RETURNED |
| EXCHANGE_REQUESTED | EXCHANGED |
| CONFIRMED·CANCELLED·RETURNED·EXCHANGED | (종결·전이 불가) |

부재 전이: 진행 단계 → *_REQUESTED (예: PAID→CANCEL_REQUESTED·DELIVERED→RETURN_REQUESTED·CONFIRMED→EXCHANGE_REQUESTED). §2.1.1 사후 정정으로 후속 Claim 요청 API 트랙 진입 시 추가가 정확함을 확인.

---

## 3. 인계 메모 vs 실측 차이 (PR-A 사후 정정 패턴 재현)

인계 메모 D-87 본문 PR-B 범위:

> "Order Aggregate Root State Machine 메서드 (canTransitionTo·apply) + Invariant 검증·Q4 α 패턴 첫 사례·도메인 행위 단독 응집"

실측 박제 결과:

| 가정 항목 | 실측 상태 |
|---|---|
| OrderItemStatus.canTransitionTo 추가 | ✓ 이미 완전 구현 (Track 2 시점 추정) |
| OrderItem.changeStatus + 전이 검증 | ✓ 이미 구현 |
| OrderStatus.canTransitionTo 부재 의도 박제 | ✓ Javadoc D-16 정합 명시 |
| Order.markPaid·applyResolvedStatus 메서드 | ✓ 이미 구현 |
| OrderStatusResolver Domain Service | ✓ 이미 구현 (Track 2) |
| ORD-1~ORD-5 강제 | ✓ ORD-1·2·4·5 강제·ORD-3 N/A |
| Q4 α 패턴 첫 사례 | 이미 적용 완료 (canTransitionTo on enum + 도메인 메서드 on Aggregate Root) |

핵심 발견: PR-B의 명목 산출물 대부분이 이미 존재. PR-A와 동일하게 사후 정정 패턴.

---

## 4. 미구현 / Gap 처치 방향 (WARN-1 해소 후 확정)

### 4.1 Claim 진입 전이 매트릭스 — 추가 안 함 (Track 5 OOS·후속 트랙 이연 유지)

§2.1.1 사후 정정 결과·Claim 진입 전이 매트릭스는 Claim 요청 API 트랙에서 추가. Track 8 PR-B에서는 OrderItemStatus.java Javadoc 문구 보정 1건만 수행 (Track 5 → 후속 Claim 요청 API 트랙).

### 4.2 OrderItem 진행 단계 단축 메서드 — 미추가 (기조 4)

현 changeStatus(next) + markPaid() 충분. 단축 메서드(markPreparing·markShipping 등) 추가는 호출자(후속 Delivery·Claim 트랙) 진입 시점에 의미·필요성 평가 후 결정.

### 4.3 Order 명시 invariant 가드 — 미추가 (기조 3·OrderItem 매트릭스로 자연 차단)

Order.markPaid 중복 호출·종결 상태에서 추가 전이 등은 모두 OrderItemStatus.canTransitionTo가 IllegalStateException으로 자연 차단. 이중 가드는 과잉.

### 4.4 OrderService Claim 관련 전이 위임 메서드 — 미추가 (후속 트랙 이연)

changeOrderItemStatus 같은 위임 메서드는 Claim 요청 API 트랙에서 호출자와 함께 신설. 현 단계 데드 코드 회피.

---

## 5. 결정 라운드 (확정)

| # | 항목 | 결정 | 기조 근거 |
|---|---|---|---|
| Q1 | PR-B 범위 재정의 | α (사후 정정·박제 위주) | 기조 2·4·호출자 없는 매트릭스 확장 = 데드 코드 |
| Q2 | Claim 진입 전이 매트릭스 위치 | N/A (Track 8 PR-B 범위 외·후속 Claim 요청 API 트랙) | 기조 4 |
| Q3 | OrderItem 진행 단계 단축 메서드 | ii (미추가) | 기조 4·현 changeStatus 충분 |
| Q4 | Order 명시 invariant 가드 | b (미추가·OrderItem 매트릭스로 자연 차단) | 기조 3·이중 가드 과잉 |
| Q5 | 회귀 테스트 base | N/A (코드 변경 1줄(Javadoc)·기존 테스트 충분) | 기조 4 |
| Q6 | D-87 본문 보정 | a (PR-A 패턴 인라인 보정) | PR-A 정합 |

기조 4 재점검: 6건 모두 정합·충돌 없음.

---

## 6. WARN

### WARN-1. Track 5 핸들러 OrderItem 전이 처리 방식 [✓ 해소 2026-06-29]

해소 결과 (§2.1.1 사후 정정 반영):
- Track 5 핸들러 (ClaimRefundCompletedHandler·PaymentRefundCompletedHandler·OrderEventHandler)·ClaimService·Claim.java 전체 read
- OrderItem 전이 수행 핸들러 부재 — Claim·Payment 종결 전이만 처리
- OrderItemStatus.canTransitionTo Claim 진입 전이는 Track 5 OOS·후속 Claim 요청 API 트랙 소관 (expected-spec §1.2·D-80)
- Javadoc "Track 5에서 추가" 문구는 오기 → 후속 Claim 요청 API 트랙으로 보정

### WARN-2. state-machine.md §3 ↔ OrderItemStatus.java 정합 [✓ 해소 2026-06-29]

state-machine.md §3 "진입 조건" 표가 *_REQUESTED 진입 조건을 Claim Aggregate 트리거로 명시. 현 OrderItemStatus.java 매트릭스는 *_REQUESTED → 종결만 정의·진입 자체는 미정의. 후속 Claim 요청 API 트랙에서 매트릭스 확장 시 자연 정합. 본 트랙 무조치.

---

## 7. 영향 범위 (확정)

| 파일 | 변경 |
|---|---|
| order/enums/OrderItemStatus.java | Javadoc 1줄 보정 (Track 5 → 후속 Claim 요청 API 트랙·expected-spec §1.2) |
| docs/architecture-baseline/decisions.md | D-87 본문 PR-B 행 인라인 보정 (PR-A 패턴) |

영향 0건:
- Order.java·OrderShippingSnapshot.java·OrderStatus.java·OrderStatusResolver.java·OrderService.java
- 모든 테스트 파일
- 다른 Aggregate (Payment·Claim·Settlement)
- 다른 SoT 문서 (state-machine.md·invariants.md·live-traps.md·aggregate-boundary.md)
- DB·DDL

---

## 8. 외부 검토

- A급 트랙·외부 검토 선택적
- Javadoc 1줄 보정·D-87 인라인 보정만이라 외부 검토 실익 낮음
- PR-B 외부 검토 생략 권장

---

## 9. 진입 조건 [전건 해소]

- [x] WARN-1 해소 (✓ Track 5 핸들러 정찰·OrderItem 전이 수행 핸들러 부재 확인)
- [x] WARN-2 해소 (✓ state-machine.md §3 정합·본 트랙 무조치 판정)
- [x] Q1~Q6 결정 라운드 확정 (Q1=α·Q2=N/A·Q3=ii·Q4=b·Q5=N/A·Q6=a)
- [x] D-87 본문 보정 방향 확정 (Q6=a·PR-A 패턴 인라인 보정)

---

## 10. 관련 결정

D-01 (외부 Aggregate 참조 = Long ID)·D-04 (Order.status 동기화·방식 B)·D-16 (OrderStatusResolver Domain Service·canTransitionTo 부재 의도)·D-29 (event publish save→publish)·D-78 (Gate 4 enum·OrderStatus 제외)·D-80 (Gate 환불 E2E Claim seed 허용)·D-87 (Track 8 진입·Order Aggregate 3 PR 분할·PR-B 범위·정찰 보정 단락 PR-A/PR-B 2건 포함)·expected-spec §1.2 (Claim 요청·승인/거절 워크플로우 OOS)

---

## 11. 다음 단계

PR-B 구현 PR 진입:
- OrderItemStatus.java Javadoc 1줄 보정
- decisions.md D-87 본문 PR-B 정찰 보정 단락 추가 (PR-A 패턴 정합)
- 본 recon-report.md SoT 박제

PR 머지 후 Track 8 PR-C 진입 (S급·OrderService 확장 + BuyerOrderController 잔여 endpoint + E2E·외부 검토 권장·결정 라운드 의무).
