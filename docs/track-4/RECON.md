# Track 4 (Order API) — RECON

> **목적**: Track 4(Order API) 진입 전 read-only 정찰. 현존 자산·진입점 갭·외부 API 후보·트랙 경계·결정 보류 항목을 사실로 박제한다.
> **기준 커밋**: main `6233074` (Track 3 머지 직후·clean).
> **정찰 범위**: `order/` 전체(Entity·Service·Command·Event·Enum·Repository)·`payment/` 경계·`common/config`·`docs/architecture-baseline/decisions.md`·`docs/design/permission-matrix.md`.
> **방법**: 실 코드 1차 정독 + 설계 결정(D-01~D-36) 대조. 코드 수정 없음.

---

## 1. 현존 자산 인벤토리 (Track 2·3 산출물)

### 1.1 Order Aggregate (Track 2 — 완비)

| 구성요소 | 시그니처·역할 | 비고 |
|---|---|---|
| `Order` (Aggregate Root) | `create(buyerId, orderNo, discountAmount, shippingFee)`·`addItem`·`attachSnapshot`·`markPaid(paidAt)`·`applyResolvedStatus` | public_id `ord_`·status 초기 `PENDING_PAYMENT`·total_price는 addItem 누적 |
| `OrderItem` | `create(productId, variantId, sellerId, quantity, unitPrice, totalPrice)`·`changeStatus`·`markPaid` | public_id `oit_`·초기 `ORDERED`·ORD-5(total = unit×qty) 검증·`seller_id` 보유 |
| `OrderShippingSnapshot` | `create(recipient·zonecode·road·jibun·detail·memo)` | 1:1·jibun/detail/memo nullable |
| `OrderStatus` (enum) | 8값: PENDING_PAYMENT·PAID·PREPARING·SHIPPING·DELIVERED·CONFIRMED·CANCELLED·PARTIAL_CANCEL | 의도적 `canTransitionTo` 부재(Resolver 파생) |
| `OrderItemStatus` (enum) | 12값·`canTransitionTo` 순방향 인접 전이만 | Claim 진입 전이(*_REQUESTED 진입)는 Track 5 이연 |
| `OrderStatusResolver` (Domain Service) | `resolve(List<OrderItemStatus>) → OrderStatus` | 평가 순서 [5]→[6]→[7]→[4]→[3]→[2]→기본 PAID |
| `OrderService` (App Service) | `createOrder(CreateOrderCommand)`·`markPaid(orderId, paidAt)`·`recalculateStatus(orderId)` | `@Transactional`·createOrder 末에 `OrderPlaced` 발행 |
| `CreateOrderCommand` | `(buyerId, items[], shipping, discountAmount, shippingFee)` | Service 계층 Command(웹 DTO 아님) |
| `OrderItemCommand` | `(productId, variantId, sellerId, quantity, unitPrice, totalPrice)` | |
| `ShippingAddressCommand` | `(recipientName, recipientPhone, zonecode, addressRoad, addressJibun, addressDetail, deliveryMemo)` | |
| `OrderRepository` | `findById`·`findByPublicId`·`findByOrderNo`·`existsByOrderNo`·`findByIdWithItems`(fetch join) | |
| `OrderPlaced` (event) | `(publicId, orderId, occurredAt)` | payload 3필드 한정(QB-13)·**핸들러 Track 7 이연** |

### 1.2 Payment 경계 (Track 3)

- `PaymentService.initiate(PaymentInitiateRequest{orderId, method, amount})` — 항상 새 PENDING 행 생성(D-28). 주문 생성과 **분리 트랜잭션**.
- `OrderEventHandler.onPaymentCompleted(PaymentCompleted)` — `@EventListener` 동기·`findByIdWithItems` 선로딩 후 `OrderService.markPaid` 위임(D-29·D-33).
- `PaymentWebhookController` (`/api/payments/callbacks`) — 현존 유일 컨트롤러.

---

## 2. 진입점 갭 — Track 4가 신설해야 하는 것

| 갭 | 현재 상태 | 영향 |
|---|---|---|
| **Order REST 컨트롤러** | 없음. `OrderController` 부재 | Track 4 핵심 산출물 |
| **`createOrder()` 호출자** | 프로덕션 코드 0건(테스트만) | 주문 생성 외부 진입점 전무 |
| **`initiate()` 호출자** | 프로덕션 코드 0건(테스트만) | 결제 시작 외부 진입점 전무 → §4·결정 보류 |
| **웹 DTO 계층** | 공용 `dto/web` 패키지 없음. Payment만 `controller/PaymentCallbackRequest` + `toCommand()` 패턴 보유 | 동일 패턴 차용 후보 |
| **Spring Security / 인증** | 없음. `AuditorAwareImpl.getCurrentAuditor()` 항상 `empty`(Q1=B) | `buyer_id` 주입 출처 미정 → 결정 보류 |
| **전역 예외 핸들러** | `@RestControllerAdvice` 없음. `PaymentWebhookController`가 로컬 `@ExceptionHandler` 사용·"컨트롤러 늘면 일원화" 명시 | Track 4 = 둘째 컨트롤러 → 일원화 타이밍 |

> 참고: `OrderService`·`OrderItem.create`는 도메인 규칙(ORD-1·ORD-5)을 던지나 `IllegalArgumentException`/`IllegalStateException` 평문이다. 전역 핸들러 도입 시 HTTP 매핑(400/404/409/422) 정책이 필요하다.

---

## 3. API 진입점 후보 식별

권한 매트릭스상 **주문 기능은 구매자 전용**(아래 §5). 후보:

### 3.1 Buyer 측 (1차 범위 유력)
- `POST /api/orders` — 주문 생성(`createOrder`). 응답: 주문 식별자(publicId·orderNo)·status·금액.
- `GET /api/orders/{publicId}` — 주문 단건 조회(본인 주문).
- `GET /api/orders` — 본인 주문 목록(buyer_id 스코프).
- (보류) `POST /api/orders/{publicId}/payments` 또는 `/checkout` — 결제 시작(`initiate`) 진입점.

### 3.2 Seller 측
- 자사 OrderItem 조회는 RLS(`WHERE seller_id = :current_seller_id`) 필요(permission-matrix §구현). Spring Security 미도입 상태라 본 트랙 범위 여부 결정 필요.

### 3.3 내부 호출 (이미 존재)
- `OrderEventHandler` → `markPaid` (이벤트 경로·외부 노출 아님).

---

## 4. Payment 트랙 경계

- **D-28**: 주문 생성과 결제 생성은 분리 트랜잭션. `initiate`가 결제 행 생성. Order 1 : Payment N.
- **OrderPlaced(E1) 핸들러는 Track 7 이연** — 즉 "주문 생성 → 자동 결제 생성" 자동 연결은 현재 없음. 결제 시작은 `initiate` **명시적 호출**이 필요하나, 그 호출자(REST 또는 체크아웃 서비스)가 미존재.
- 따라서 Track 4 주문 생성 API와 결제 시작 API의 **결합 방식**(단일 체크아웃 엔드포인트 vs 주문 생성/결제 시작 2단계 분리)이 결정 보류 항목.

---

## 5. 권한 매트릭스 — Order 관련 행

| 기능 | 구매자 | SELLER_* | 운영자 | 전체관리자 |
|---|:--:|:--:|:--:|:--:|
| 주문 | **O** | - | - | - |

- 주문 생성·실행은 구매자 전용. 판매자/운영자는 주문 기능 자체 비보유.
- 단, SELLER_* 역할의 **주문 조회**는 자사(seller_id) 스코프 RLS로 별도(permission-matrix §핵심원칙·§구현). 조회 API는 본 트랙 범위 결정 필요.

---

## 6. 관련 설계 결정 (대조 결과)

| ID | 요지 | Track 4 함의 |
|---|---|---|
| D-01 | Order Aggregate에 Payment·Delivery 미포함(분리) | 주문 API와 결제 API 분리 정당 |
| D-02 | Order.status 8값·PENDING_PAYMENT = 결제 전 | 주문 생성 직후 응답 status = PENDING_PAYMENT |
| D-04 | OrderItem↔Order status 동기화(방식 B) | 조회 응답 status 의미 정합 |
| D-16 | OrderStatusResolver Domain Service·책임 명문화 | 조회 시 파생 status 노출 |
| D-28 | initiate가 결제 행 생성·분리 트랜잭션·Order 1:N Payment | §4 결합 방식 결정 근거 |
| E1 | OrderPlaced 발행·소비측 재조회·핸들러 Track 7 이연 | 자동 결제 연결 없음 |

> Track 4(Order **API**) 진입점·인증·DTO·예외 매핑에 대한 전용 결정(D-xx)은 decisions.md에 **아직 없음** — 본 트랙 decisions.md에서 신규 확정 대상.

---

## 7. 결정 보류 항목 (정찰 후 확정)

1. **API 진입점 분류**: 단일 OrderController vs buyer/seller 분리.
2. **인증·인가 진입 시점**: `buyer_id` 출처. Spring Security 본 트랙 도입 vs 임시 헤더/파라미터 주입 후 후속 트랙 이연.
3. **DTO 분리 전략**: `CreateOrderRequest`·`OrderResponse`·`OrderItemResponse`·`ShippingAddressDto` 등 + `toCommand()` 변환 패턴(Payment 차용).
4. **멀티벤더 OrderItem 응답 그룹화**: seller_id 단위 그룹 vs 평면 항목 배열.
5. **OrderStatus 외부 노출 vs 내부용 분리**: enum 직접 노출 vs 표시용 매핑.
6. **결제 시작 결합 방식**: 단일 체크아웃 엔드포인트 vs 주문 생성/결제 시작 2단계(§4).
7. **전역 예외 핸들러 도입**: `@RestControllerAdvice` 일원화 + 도메인 예외 → HTTP 상태 매핑 정책.
8. **조회 API 범위**: buyer 본인 조회만 vs seller 자사 스코프 조회 포함(RLS).
9. **API 버저닝 정책**: `/api/v1/orders` 도입 vs `PaymentWebhookController`(`/api/payments/callbacks`) 일관성 유지(미버전). 향후 breaking change 부담·일괄 마이그레이션 시점 고려.
10. **JSON 직렬화 정책**: snake_case vs camelCase·`null` 필드 응답 포함 여부·날짜 포맷(`OffsetDateTime` vs `Instant`·timezone 직렬화 규칙). Nuxt 프론트 연동 인터페이스 박제 필요.
11. **Validation 계층**: Bean Validation(`@Valid`·`@NotNull`·`@Min` 등) 도입 위치(Web DTO vs Command 양쪽). 도메인 규칙(ORD-1·ORD-5)과 중복 검증 정책·실패 시 HTTP 상태(400 vs 422) 매핑.
12. **주문 생성 멱등성**: 중복 클릭·네트워크 재시도 방지. Payment `attempt_key`(D-28) 패턴을 Order에도 적용할지·미적용 시 중복 주문 위험 수용 여부. 적용 시 클라이언트 전달 vs 서버 생성 패턴 결정 필요.

---

## 8. 결론

- Order Aggregate(Track 2)·Payment Mock(Track 3)은 도메인·서비스 계층 완비. **REST/웹/인증/예외 진입 계층이 전무**하다.
- Track 4의 본질 = Order Aggregate 위에 **첫 buyer-facing HTTP 진입 계층 신설** + 그에 수반되는 인증·DTO·전역 예외 정책 확정.
- 다음 단계: 위 §7 보류 항목을 `docs/track-4/decisions.md`로 확정 후 expected-spec → 구현 → recon 사이클 진입.
