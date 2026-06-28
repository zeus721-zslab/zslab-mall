# Track 8 PR-A 정찰 보고서

> 트랙: Track 8 / PR-A (A급)
> 대상: CheckoutService D-66 정합 검증·회귀 테스트 박제·ORD-1 4xx 분류·Outdated 주석 보정·D-87 본문 보정
> 정찰일: 2026-06-28
> 정찰 방식: MCP read-only (Claude.ai)
> 정찰 commit: 6295fb8 (D-87 박제 머지 직후)

---

## 1. 정찰 범위

- `checkout/service/CheckoutService.java` (D-66·D-43·D-52·D-58 정합)
- `order/service/OrderService.java` (OrderPlaced publish·ORD-1 검증·outdated 주석)
- `payment/handler/OrderEventHandler.java` (실은 PaymentCompleted 핸들러·명명 오해)
- `order/event/OrderPlaced.java` (소비자 부재 확인·outdated 주석)
- `order/controller/BuyerOrderController.java` (D-39 정합 확인)

---

## 2. 정찰 결과

### 2.1 CheckoutService D-66 정합 상태 — **이미 부분 구현**

`idempotentCheckout()` 내부 createOrder() 호출 try-catch 명시:

- **4xx** (`CheckoutItemNotFoundException`·`CheckoutItemMismatchException`) → `idempotencyRepository.delete(mark)` 후 재throw → **D-66 정합 ✓**
- **5xx** (`RuntimeException`·`DataAccessException`) → catch 없음·자연 잔류 → **D-66 정합 ✓**

추가 정합성 점검:
- `OrderService.createOrder` 내부 `IllegalArgumentException` (ORD-1 빈 items 등)은 현재 catch 누락·5xx 잔류로 처리됨 → **GlobalExceptionHandler 매핑 확인 의무** (WARN-1)
- `revalidatePayable`의 `OrderNotPayableException` (422)은 retryPayment 경로만·idempotency 미적용 (D-52 정합)
- 트랜잭션 경계: CheckoutService `@Transactional` 없음·`saveAndFlush` 단계별 독립 커밋 (D-58 명시)

### 2.1.1 사후 정정 (PR-A 완료 후 발견)

PR-A 구현 진입 시점에 `CheckoutIntegrationTest.java` 전체 read 결과 회귀 테스트 3건(`checkout_itemNotFound_sameKeyRetryable`·`checkout_itemMismatch_sameKeyRetryable`·`checkout_5xx_sameKeyBlocked`)이 main(6295fb8)에 **이미 존재**. D-66 박제 시점(Track 4 hotfix·2026-06-28)에 회귀 테스트도 동반 구현된 것으로 추정.

정찰 1차에서 head 80줄만 read하여 부재로 오판. PR-A 실제 변경은 Javadoc 2건 + decisions.md 1건 (3파일·6건 PASS 정합 확인).

후속 정찰 룰: 신규 테스트 메서드 추가 전 대상 테스트 파일 메서드 목록 전체 read 의무.

### 2.2 OrderPlaced 핸들러 — **부재 (소비측 후속 트랙)**

- OrderPlaced publish: `OrderService.createOrder` L77 (D-29 정합·save→publish·flush 없음)
- 소비자: **부재** (Glob 0건)
- `payment.handler.OrderEventHandler`는 실은 **PaymentCompleted 핸들러**·OrderPlaced 아님 → WARN-2
- `aggregate-boundary.md §2.7` 명시: NotificationLog가 E1(OrderPlaced) 소비

소비측 후속 트랙 (PR-A 미포함):
- NotificationLog 적재 → AuditLog·NotificationLog 트랙 (가드 2 A급·D-87)
- Inventory 예약 (`quantity_reserved` 증가) → Inventory 트랙 (가드 2 A급·D-87)
- CartItem 소비 → CartItem 트랙 (가드 2 A급·D-87)

### 2.3 Outdated 주석 2건

| 파일 | 위치 | 내용 | 보정 방향 |
|---|---|---|---|
| `order/event/OrderPlaced.java` | L11 | "핸들러는 Track 7 이연" | "핸들러는 후속 트랙(NotificationLog·Inventory·CartItem) 진입 시 구현" |
| `order/service/OrderService.java` | L18~L19 | "Claim 이벤트 핸들러는 Track 5 진입 시점에 추가한다(본 트랙 미작성)" | Track 5 종료·핸들러(ClaimRefundCompletedHandler 등) 이미 존재 → 주석 제거 또는 현재 상태 반영 |

### 2.4 BuyerOrderController D-39 정합 — **정합 확인**

X-Buyer-Id 헤더 해소·Idempotency-Key 형식 검증·thin controller 패턴 모두 정합. 변경 불필요.

---

## 3. PR-A 재정의 (D-87 본문 보정 동반)

### 3.1 D-87 본문 (변경 전)
PR-A = CheckoutService D-66 정합 최종화 + OrderPlaced 핸들러 구현 + Order Aggregate 완결

### 3.2 PR-A (변경 후·정찰 결과 반영·WARN-1 해소 후)

| 항목 | 작업 |
|---|---|
| D-66 정합 회귀 테스트 3건 | `CheckoutIntegrationTest.java`에 추가 — `checkout_itemNotFound_sameKeyRetryable` (404 → 동일 키 재시도 → 201) / `checkout_itemMismatch_sameKeyRetryable` (422 → 동일 키 재시도 → 201) / `checkout_5xx_sameKeyBlocked` (5xx → 동일 키 재시도 → 409 유지) |
| Outdated 주석 보정 2건 | `OrderPlaced.java` L11·`OrderService.java` L18~L19 |
| D-87 본문 보정 박제 | `decisions.md` 인라인 보정 (Q5=A) |

ORD-1 4xx 분류는 WARN-1 해소로 무작업 결정.

### 3.3 PR-A 미포함 (후속 트랙 자연 진입)

- OrderPlaced 핸들러 구현 → NotificationLog·Inventory·CartItem 트랙 각자 자체 핸들러 구현
- Order Aggregate Root State Machine 메서드 → **PR-B** 정합 유지
- OrderService 확장·E2E → **PR-C** 정합 유지

---

## 4. 결정 라운드 Q항목 (4건·전건 추천 채택)

| # | 항목 | 옵션 | 추천 |
|---|---|---|---|
| Q1 | OrderPlaced 핸들러 범위 | A NotificationLog 단일 / B +Inventory / C 풀패키지 / **D PR-A 미포함** | **D** |
| Q2 | D-66 정합 추가 처치 | α 회귀 테스트만 / **β α+ORD-1 4xx** / γ β+TX propagation 명시 | **β** |
| Q3 | PR-A 분할 후 핸들러 PR | i 즉시 PR-A2 / **ii 후속 트랙 자연** / **iii D-87 본문 보정** | **ii + iii** |
| Q4 | Outdated 주석 보정 위치 | **a PR-A 포함** / b 별도 chore / c 자연 도달 | **a** |

기조 4 재점검 결과: 4건 모두 유효·충돌 없음.

---

## 5. WARN (PR-A 진입 전 추가 정찰·구현 시 보정)

### WARN-1. GlobalExceptionHandler IllegalArgumentException 매핑 [✓ 해소 2026-06-28]

**해소 결과**:
- `IllegalArgumentException` → `handleMalformed` 매핑 (L69~L73)·**400 BAD_REQUEST** 자연
- 컨트롤러 `@Valid` first-line defense:
  - `CreateOrderRequest.items` `@NotEmpty @Valid` → 빈 list·null 차단
  - `CreateOrderRequest.shippingAddress` `@NotNull @Valid` → null 차단
  - `OrderItemRequest.productId·variantId` `@NotBlank`·`quantity` `@Min(1)`
  - `ShippingAddressRequest` 4 필드 `@NotBlank`
- `OrderService.createOrder` 내부 `IllegalArgumentException` 발생 조건 (빈 items·shipping null)은 컨트롤러에서 모두 차단됨
- `idempotentCheckout()` 진입 자체가 막혀 idempotency row 생성 전 400 반환·D-66 정합 자연 충족

**Q2 추천 재평가**: β (ORD-1 4xx 분류 추가 처치) → **α** (코드 변경 불필요·회귀 테스트만)

**근거**:
- @Valid가 first-line defense·CheckoutService 도달 경로 사실상 없음
- 신규 예외 클래스 신설은 과잉 (기조 4 정합)
- depth-in-defense 차원은 D-66 회귀 테스트로 검증

### WARN-2. payment.handler.OrderEventHandler 명명 오해 [NON-BLOCKING]

- 실은 `PaymentCompleted` 핸들러·OrderPlaced 핸들러처럼 보이는 이름
- 후속 OrderPlaced 핸들러 신설 시 명명 충돌 가능 (D-74 이벤트 핸들러 빈 네이밍 정책 정합 검토 후보)
- PR-A 범위 외·후속 트랙 핸들러 신설 시점에 보정 후보
- 보정 방향 후보: `PaymentEventHandler` 또는 `PaymentCompletedHandler` (Track 5 PaymentRefundCompletedHandler·ClaimRefundCompletedHandler 패턴 정합)

---

## 6. 영향 범위

### 6.1 수정 예상 (PR-A·WARN-1 해소 후 확정)

| 파일 | 변경 |
|---|---|
| `order/event/OrderPlaced.java` | Javadoc 주석 보정 1건 |
| `order/service/OrderService.java` | Javadoc 주석 보정 1건 |
| `backend/src/test/.../checkout/CheckoutIntegrationTest.java` | D-66 회귀 테스트 3건 추가 |
| `docs/architecture-baseline/decisions.md` | D-87 본문 인라인 보정 |

`CheckoutService.java`·`GlobalExceptionHandler.java`·신규 예외 클래스: WARN-1 해소로 변경 없음.

### 6.2 영향 0건

- 다른 SoT 문서 (aggregate-boundary·live-traps·invariants·state-machine)
- 다른 Aggregate (Payment·Claim·Refund·Settlement 등)

---

## 7. 외부 검토

- A급 트랙·외부 검토 선택적
- D-66 정합 회귀 테스트는 박제 사실 검증·외부 검토 실익 낮음
- PR-A 외부 검토 **생략 권장**

---

## 8. 진입 조건 [전건 해소]

- [x] WARN-1 해소 (✓ @Valid first-line defense·D-66 정합 자연 충족)
- [x] Q1~Q4 결정 라운드 확정 (Q1=D·Q2=α·Q3=ii+iii·Q4=a)
- [x] Q5 D-87 본문 보정 방향 확정 (A 인라인 보정)
- [x] CheckoutServiceTest 기존 파일 여부 (`CheckoutIntegrationTest.java`에 추가·`@MockitoSpyBean OrderService`로 5xx 시뮬레이션)

---

## 9. 관련 결정

D-29 (event publish·save→publish·flush 없음)·D-43 (재결제 분리)·D-50 (Validation 매트릭스·400 vs 422)·D-52 (멱등성 + Order 생성 순서)·D-58 (CheckoutService 오케스트레이션)·D-66 (멱등성 4xx 삭제·5xx 잔류)·D-74 (이벤트 핸들러 빈 네이밍)·D-75 (이벤트 핸들러 트랜잭션 정책)·D-87 (Track 8 진입 결정)