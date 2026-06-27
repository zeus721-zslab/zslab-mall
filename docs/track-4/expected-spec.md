# Track 4 (Order API) — Expected Spec

> **작성 원칙**: Claude.ai가 실 코드를 참조하지 않고, 설계 문서(decisions.md D-39~D-55·db-schema-decisions.md·Track 3 expected-spec)와 합의된 결정만으로 기술한 "기대 사양". recon-report.md에서 1:1 비교 대상.

## 범위
Track 4 = Order REST API 진입 계층 신설. Buyer-facing 4개 엔드포인트(주문 생성·단건 조회·목록 조회·재결제). Spring Security 정식 도입 없음(D-39 임시 헤더). 멱등성 V4 마이그레이션 동반. PG 실연동·Seller/Admin 조회·검색·통계는 범위 외.

## 핵심 Invariant·동작 계약

### 1. 엔드포인트 시그니처 (4개·D-42·D-43·D-47)
```
POST   /api/v1/orders                              (주문 생성 + 첫 결제 시작)
GET    /api/v1/orders/{orderPublicId}              (Buyer 본인 단건)
GET    /api/v1/orders                              (Buyer 본인 목록·페이징)
POST   /api/v1/orders/{orderPublicId}/payments     (재결제 전용)
```
- 단건/재결제 경로 변수는 `{orderPublicId}` (CHAR(30)·`ord_` prefix). 내부 BIGINT id 노출 금지.
- 목록 페이징: `page`·`size` (기본 `size=20`·최대 `size=100`). `sort` 본 트랙 미노출·서버 고정 `ORDER BY ordered_at DESC`.
- **목록 응답 구조: `PagedResponse<OrderSummaryResponse>`** (D-54). Spring Data `Page<T>` 직접 직렬화 금지. 필드: `items`·`page`·`size`·`totalCount`·`hasNext` 5개 한정.

### 2. 인증·인가 (D-39·D-42·D-43)
- 임시 인증: HTTP 요청 헤더 `X-Buyer-Id: <BIGINT>`.
- 본인 일치 검증: 컨트롤러 진입 시 `X-Buyer-Id`와 조회/조작 대상 Order의 `buyer_id` 일치 확인.
- 헤더 검증 응답 분리:
  - **헤더 누락 → 401 `UNAUTHENTICATED`** (인증 정보 없음)
  - **헤더 형식 오류 (BIGINT 파싱 실패) → 400 `MALFORMED_REQUEST`** (문법 오류)
- 불일치 케이스 응답:
  - 존재하지 않는 주문 → 404
  - 타인 주문 → 404 (정보 노출 회피·403 미사용)
- Spring Security·JWT·세션·OAuth2는 본 트랙 범위 외 (후속 트랙).

### 3. 컨트롤러 분류·패키지 구조 (D-40·D-41)
- URL은 액터 중립 (`/api/v1/orders`·`/api/v1/order-items`). `/api/buyer/...`·`/api/seller/...` prefix 금지.
- Controller는 액터별 분리: `BuyerOrderController` (Track 4 단독 신설). `SellerOrderController`·`AdminOrderController`는 후속.
- 패키지 구조:
```
order/controller/
 ├─ BuyerOrderController
 ├─ request/
 │   ├─ CreateOrderRequest
 │   ├─ OrderItemRequest
 │   └─ ShippingAddressRequest
 └─ response/
     ├─ CheckoutResponse
     ├─ OrderResponse
     ├─ OrderItemResponse
     ├─ OrderSummaryResponse
     ├─ ShippingAddressResponse
     └─ PagedResponse           (공용 DTO·common 위치로 이동 검토 가능)
```
- `mapper/` 패키지·MapStruct 미도입.

### 4. DTO 변환 패턴 (D-41)
- Request → Command: 인스턴스 메서드 `request.toCommand()`.
- Domain → Response: 정적 팩토리 `Response.from(...)` 또는 `Response.fromOrderWithItems(...)`.
- Response 입력 범위 제한: `Response.from(Order)` 전체 Aggregate 직접 수신 금지. fetch join 로딩된 Order 또는 projection record 입력.
- 페이징 변환: `PagedResponse.from(Page<T>)` 정적 팩토리 (D-54).
- 부수 효과 (Track 3 일관성): `payment/controller/PaymentCallbackRequest` → `payment/controller/request/`로 이관 (별도 작은 PR 또는 Track 4 동반).

### 5. 체크아웃 트랜잭션 경계 (D-43 2·3·4항·D-52)
- `@Transactional`로 Order 생성과 Payment initiate를 **한 TX로 묶지 않음**. 각 Service 자체 `@Transactional` (D-28 정합).
- **오케스트레이션 계층 (D-58)**: 아래 1→5 호출 순서 조립은 `CheckoutService`가 담당. `BuyerOrderController` 직접 조립 금지.
- **호출 순서 (D-52)** — Idempotency-Key 헤더 전달 시:
  1. `order_idempotency_key` INSERT (`status=IN_PROGRESS`·`order_id=NULL`)
  2. `OrderService.createOrder()` (TX1 COMMIT)
  3. `order_idempotency_key.order_id` UPDATE (별도 짧은 TX)
  4. `PaymentService.initiate(orderPublicId, buyerId, method)` (TX2 COMMIT) — D-56 시그니처
  5. 응답 직렬화 → `response_body` 저장 + `status=COMPLETED` UPDATE
- 부분 실패 (Order 성공 + Payment initiate 실패):
  - Order 롤백 **금지** — `PENDING_PAYMENT` 상태 유지.
  - Payment row **미저장** — initiate 자체 실패는 redirect 발급 실패 의미.
- **응답 헤더 (D-53)**:
  - 신규 주문 성공/실패 모두 `Location: /api/v1/orders/{orderPublicId}` 강제.
- `PENDING_PAYMENT` 의미 재정의: "결제 없음 (INITIATE_FAILED) 또는 결제 진행 중 (Payment.PENDING)". 신규 OrderStatus enum 추가 없음.

### 6. 재결제 허용 조건 (D-43 5·6·7항·D-51·D-53)
- 엔드포인트: `POST /api/v1/orders/{orderPublicId}/payments`.
- "활성 Payment" 정의 (status별 응답 분리):
  - `Payment.status = PENDING` 이고 `now < expires_at` (만료 전) → 409 `PAYMENT_IN_PROGRESS`
  - `Payment.status = PAID` → 422 `PAYMENT_ALREADY_COMPLETED`
  - `Payment.status = PENDING` 이고 `now >= expires_at` (만료) → 신규 Payment 생성 허용 (D-32 정합)
- 판정 매트릭스 (Order 단위):
  - 만료 전 PENDING 존재 → 409 `PAYMENT_IN_PROGRESS`
  - PAID 존재 → 422 `PAYMENT_ALREADY_COMPLETED`
  - FAILED Payment만 존재 → 신규 Payment 생성 허용
  - 만료 PENDING만 존재 → 신규 Payment 생성 허용
  - Payment 부재 (INITIATE_FAILED 직후) → 신규 Payment 생성 허용
- **재결제 재검증 2종 (D-51·D-60)** — initiate 진입 시 Order Snapshot 고정·가격 재계산 금지·아래 2종만 차단:
  - `Product.status != SALE` → 422 `ORDER_NOT_PAYABLE` + detail `PRODUCT_NOT_ON_SALE` *(D-59: Track 4에서 Product Java 엔티티·Repository read-only 신설)*
  - `ProductVariant.is_soldout_manual == true` 또는 `Inventory.quantity_available < OrderItem.quantity` → 422 `ORDER_NOT_PAYABLE` + detail `OUT_OF_STOCK` *(D-57·D-59: Inventory·ProductVariant 각 read-only 신설)*
  - **배송 가능성 검증 (`SHIPPING_UNAVAILABLE`) — 본 트랙 보류·Delivery 정책 박제 시점 재진입 (D-60·D-62a)**
- 권한 검증: `PaymentService.initiate(orderPublicId, buyerId, method)` 시그니처 (D-56). 본인 일치 불일치 시 404 (§2 정합).
- attempt_key: retry 시 **항상 신규 발급**·재사용 금지. 기존 Payment row(FAILED 상태) 재사용 금지·신규 row 추가 (D-52).
- **응답 헤더 (D-53)**: 재결제 성공 시 `Location: /api/v1/payments/{paymentPublicId}` 강제 (Order 리소스 재사용 금지·Payment 생성이므로 Payment 가리킴).
- 미래 상태 (EXPIRED·TIMEOUT 등) 추가 시 "활성 Payment 정의"만 갱신.

### 7. 응답 구조 — CheckoutResponse (D-43 8·9항)
- 신규 주문·재결제 공용 DTO. 의미 중심은 payment (재결제는 Order 변경 아님).
- 정적 팩토리:
  - `forNewOrder(...)` — 신규 주문 + Payment initiate 성공.
  - `forNewOrderInitiateFailed(...)` — 신규 주문 성공 + Payment initiate 실패.
  - `forRetry(...)` — 재결제 성공.
- 응답 필드:
  - `payment.{publicId, status, redirectUrl, expiresAt}` — Payment 직접 속성 (PG 발급·**서버 상태 canonical source**).
  - `next.{retryPaymentUrl}` — 클라이언트 다음 행동 안내 (**UI 편의 필드·canonical source 아님**·CR-06 보완).
- 성공/실패 응답 차이:
  - 성공: `payment.redirectUrl` 존재·`next` 생략.
  - 실패: `payment.status=INITIATE_FAILED`·`payment.publicId` 부재·`next.retryPaymentUrl` 제공.
- `failureCode`는 응답 미노출·운영 로그에만 보존 (§18).
- **Location 헤더는 §5·§6 참조** (D-53).

### 8. 멱등성 — Idempotency-Key 헤더 (D-44·D-52)
- 헤더명: `Idempotency-Key`.
- 값 형식: ULID 또는 UUID v4 — 서버 형식 검증, 생성 주체는 클라이언트.
- 최대 길이: 128자.
- 스코프: `(buyer_id, idempotency_key)` 유일.
- 적용 엔드포인트: `POST /api/v1/orders` (본 트랙 한정). 재결제(`POST .../payments`)는 미적용.
- 헤더 미전달: **허용** — 매 요청 신규 주문 생성 (graceful degradation).
- **재시도 분기 (D-52)**:
  - `status=COMPLETED` → 캐시 응답 반환 (HTTP 200 OK 고정).
  - `status=IN_PROGRESS` + `order_id IS NULL` → 409 `IDEMPOTENCY_KEY_IN_PROGRESS`.
  - `status=IN_PROGRESS` + `order_id IS NOT NULL` → **기존 Order 복구**·`PaymentService.initiate(orderPublicId, buyerId, method)` 재호출만 수행 (Order 재생성 금지·attempt_key 신규 발급) — D-56 시그니처.

### 9. 멱등성 저장 — V4 Flyway (D-44a)
- 테이블: `order_idempotency_key`.
- 컬럼:
```
buyer_id          BIGINT       NOT NULL
idempotency_key   VARCHAR(128) NOT NULL
order_id          BIGINT       NULL
status            ENUM('IN_PROGRESS','COMPLETED') NOT NULL
response_body     LONGTEXT     NULL
created_at        DATETIME(6)  NOT NULL
completed_at      DATETIME(6)  NULL
```
- PK: `(buyer_id, idempotency_key)` 복합.
- 동시성 제어: PK UNIQUE INSERT 충돌 감지 → 409.
- 보존 윈도우 (3단 분리):
  - **0~24h**: 클라이언트 재요청 응답 보장 (멱등성 핵심 SLA).
  - **24~72h**: 운영 디버깅 보존 (추가 48h).
  - **72h 이후**: 배치 일괄 삭제 (response_body 포함 row 전체 삭제·CR-21 기각 박제).
- Flyway: V4. V1·V2·V3 회귀 금지.
- 향후 재평가 트리거: Track 7 (Inventory) 진입 시 Redis 분산 락 도입과 동시에 멱등성 저장 매체 SETNX 마이그레이션 검토.

### 10. 멱등성 응답 캐싱 (D-44b)
- 저장 시점: 응답 직렬화 후·`status=COMPLETED` UPDATE와 동일 트랜잭션 (D-52 5단계).
- 캐싱 대상: **2xx 성공 응답만**.
- 캐싱 제외 (성공 응답만 캐싱·실패 응답 미캐싱):
  - **4xx**: 미캐싱 + IN_PROGRESS row 삭제 → 동일 키 재시도 허용 (D-66)
  - **5xx**: 미캐싱 + IN_PROGRESS row 잔류 → 운영자 개입 전까지 409 유지 (D-66)
  - 단 `order_id IS NOT NULL` 상태에서는 §8 분기로 기존 Order 복구 (D-52).
- 재요청 처리: `response_body` 그대로 반환·HTTP **200 OK 고정** (최초 응답이 201이어도 재요청은 200).
- 현재 상태와의 불일치: 의도된 동작 — 실제 현재 상태는 `GET /api/v1/orders/{id}` 별도 조회.

### 11. OrderItem 응답 그룹화 — seller 단위 (D-45·D-55)
- 적용 범위: `POST /api/v1/orders`·`GET /api/v1/orders/{id}`·`CheckoutResponse` 본문 내 Order 표현 시.
- 상세 응답 구조 (seller 그룹화):
```json
{
  "orderId": "ord_...",
  "status": { "code": "PAID", "label": "결제완료" },
  "sellers": [
    {
      "sellerId": "slr_...",
      "companyName": "...",
      "items": [
        {
          "orderItemId": "oit_...",
          "productId": "prd_...",
          "variantId": "var_...",
          "quantity": 1,
          "unitPrice": 10000,
          "totalPrice": 10000
        }
      ],
      "subtotal": 10000
    }
  ],
  "totalPrice": 10000
}
```
- 단일 판매자 주문도 동일 구조 (`sellers` 배열 길이 1·분기 금지).
- `subtotal` 필드 포함 (정산 단위 일치·null 금지·§15).
- 식별자 전체 `public_id` 사용·내부 BIGINT PK 노출 금지.
- **목록 응답은 그룹화 비적용 (D-55)** — `OrderSummaryResponse` 구조:
  - `orderId` (public_id·`ord_` prefix)
  - `previewTitle` (서버 생성 문자열·D-55·null 금지)
    - OrderItem 1건 → `"{productName}"`
    - OrderItem 2건 이상 → `"{firstProductName} 외 {count - 1}건"`
    - Preview 대상: `OrderItem.created_at ASC` 첫 행 (정렬 의존성 차단)
  - `sellerCount` (정수·멀티벤더 정보)
  - `totalPrice` (KRW 정수)
  - `status` (`{code, label}` 객체·§12 정합)
  - `orderedAt` (ISO-8601 UTC)

### 12. status 외부 노출 방식 (D-46·D-49)
- UI 노출 status (5종): `OrderStatus`·`PaymentStatus`·`DeliveryStatus`·`ClaimStatus`·`RefundStatus` → `{code, label}` 객체.
- 내부 기술 상태: string 유지 (`order_idempotency_key.status` 등).
- 객체 구조:
  - `code`: enum 값 (SCREAMING_SNAKE_CASE).
  - `label`: `Code.label` 조회 결과 (운영자 편집 반영).
  - fallback: 라벨 조회 실패 시 `label = code` (null 금지).

### 13. URL 버저닝 (D-47)
- `/api/v1/` 적용: `/api/v1/orders`·`/api/v1/payments` 등 모든 공개 REST API.
- Webhook 별도 경로 (versioning 미적용): `/api/webhooks/payments`.
- 기존 `PaymentWebhookController` 마이그레이션: `/api/payments/webhook` → `/api/webhooks/payments`.
- breaking change 발생 시 `/api/v2` 병행 운영 → deprecation 기간 후 v1 제거 (본 트랙 범위 외).

### 14. 전역 예외 핸들러 — RFC 7807 ProblemDetail (D-48)
- 구현: `@RestControllerAdvice` 기반.
- 응답 본문 구조:
```json
{
  "type": "https://zslab-mall.duckdns.org/errors/order-not-found",
  "title": "Order Not Found",
  "status": 404,
  "detail": "주문을 찾을 수 없습니다.",
  "instance": "/api/v1/orders/ord_xxx",
  "code": "ORDER_NOT_FOUND",
  "traceId": "..."
}
```
- 예외 → HTTP 상태 매트릭스 (11종):

  | 카테고리 | HTTP | code 예시 |
  |---|:---:|---|
  | Bean Validation 실패 (`@Valid`) | 400 | `VALIDATION_FAILED` |
  | 형식·파싱 오류 (JSON·타입·X-Buyer-Id 형식 오류) | 400 | `MALFORMED_REQUEST` |
  | 인증 실패 (X-Buyer-Id 헤더 누락) | 401 | `UNAUTHENTICATED` |
  | 리소스 없음 | 404 | `ORDER_NOT_FOUND` |
  | 멱등성 진행 중 충돌 | 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` |
  | 낙관적 락 충돌 | 409 | `OPTIMISTIC_LOCK_FAILURE` |
  | 활성 Payment 존재 (재결제 차단) | 409 | `PAYMENT_IN_PROGRESS` |
  | 도메인 규칙 위반 | 422 | `PAYMENT_ALREADY_COMPLETED` |
  | 재결제 불가 (D-51) | 422 | `ORDER_NOT_PAYABLE` |
  | Rate limit 초과 (본 트랙 미구현·Track 6 이후 별도 결정) | 429 | `RATE_LIMIT_EXCEEDED` |
  | 외부 응답 이상 (본 트랙 미구현) | 502 | `BAD_GATEWAY` |
  | 서비스 이용 불가 (본 트랙 미구현) | 503 | `SERVICE_UNAVAILABLE` |
  | 미분류 서버 오류 | 500 | `INTERNAL_ERROR` |

- 403 `FORBIDDEN`은 본 트랙 범위 외 (Spring Security 정식 도입 후속 트랙).
- MDC: 요청 진입 시 `traceId` (ULID) MDC 주입·응답 헤더 `X-Trace-Id` 반환·로그 패턴 `[%X{traceId}]` 포함.

### 15. JSON 직렬화 정책 (D-49)
- 필드명: camelCase.
- null 처리: `@JsonInclude(NON_NULL)` 응답에서 null 필드 제외.
- null 제외 예외 (필수 유지 필드):
  - `ProblemDetail.detail` — 에러 가독성 필수.
  - `subtotal`·`totalPrice` 등 금액 필드 — 계산 또는 0 fallback.
  - `label` — fallback으로 code 값 채움 (§12 정합).
  - `previewTitle` — D-55 null 금지.
- 날짜·시간: ISO-8601 UTC (`2026-06-27T05:30:00.000Z`).
- 금액: 정수 그대로 (KRW 원 단위·db-schema-decisions.md 1.3 정합).
- enum: SCREAMING_SNAKE_CASE·UI 노출 status는 §12 객체 래핑.

### 16. Validation 계층 매트릭스 (D-50)

  | 검증 위치 | 책임 | HTTP | 도구 |
  |---|---|:---:|---|
  | Controller `@Valid` | 형식·필수·범위·정규식 | 400 | Jakarta Bean Validation |
  | Service / Domain | 비즈니스 규칙·상태 전이·중복 | 422 | 도메인 예외 throw |
  | Repository (UNIQUE 위반) | 의미 기준 분류 | 409 또는 422 | 예외 변환 |

- 409 vs 422 분류 — **의미 기준** (계층 변경 둔감):
  - 동시성 충돌 → 409 (멱등성 키 진행 중·낙관락 충돌·활성 Payment 존재).
  - 업무 제약 위반 → 422 (중복 주문 금지·결제 완료된 주문 재결제·재결제 불가 D-51).
- 중복 검증 허용: 컨트롤러 `@NotNull` + 도메인 객체 생성자 null 체크는 의도적 중복 허용 (도메인 객체 단독 사용 시 무결성 보장).
- 400 vs 422 판단:
  - "요청 형식 잘못" → 400 (수정 후 재요청 의미 있음).
  - "형식은 맞으나 비즈니스 규칙 위반" → 422 (요청 자체 재고 필요).

### 17. HTTP 상태 종합 색인
> §5·6·8·14·16에 분산된 상세 매트릭스의 정찰용 색인. 행 단위 1:1 비교 대상. 풀 사양은 각 § 본문 참조.

| HTTP | code | Location | 발생 § |
|---|---|---|---|
| 201 | (성공·신규 주문) | `/api/v1/orders/{orderPublicId}` | §5·§7 |
| 201 | (성공·신규 주문 + initiate 실패) | `/api/v1/orders/{orderPublicId}` | §5·§7 |
| 201 | (성공·재결제) | `/api/v1/payments/{paymentPublicId}` | §6·§7 |
| 200 | (멱등성 캐시 재반환) | (없음) | §10 |
| 400 | `VALIDATION_FAILED` | — | §16 |
| 400 | `MALFORMED_REQUEST` (X-Buyer-Id 형식 오류 포함) | — | §2·§16 |
| 401 | `UNAUTHENTICATED` (X-Buyer-Id 헤더 누락) | — | §2·§14 |
| 404 | `ORDER_NOT_FOUND` | — | §2·§6 |
| 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` | — | §8 |
| 409 | `OPTIMISTIC_LOCK_FAILURE` | — | §16 |
| 409 | `PAYMENT_IN_PROGRESS` (활성 Payment 존재) | — | §6 |
| 422 | `PAYMENT_ALREADY_COMPLETED` | — | §6·§16 |
| 422 | `ORDER_NOT_PAYABLE` (재결제 불가·D-51) | — | §6·§16 |
| 422 | (도메인 규칙 위반·기타) | — | §16 |
| 429 | `RATE_LIMIT_EXCEEDED` (본 트랙 미구현) | — | §14 |
| 500 | `INTERNAL_ERROR` | — | §14 |
| 502 | `BAD_GATEWAY` (본 트랙 미구현) | — | §14 |
| 503 | `SERVICE_UNAVAILABLE` (본 트랙 미구현) | — | §14 |

### 18. 운영 로그 5필드 — Payment initiate 실패 (D-43 3항)
- 보존 필드: `orderPublicId`·`attemptKey`·`buyerId`·`failureCode`·`occurredAt`.
- 출력 위치: Payment initiate 실패 catch 블록 (Application Service 또는 Controller 경계).
- 사용처: 부분 실패 디버깅·재결제 호출 추적·운영 CS 대응.
- `failureCode`는 응답 미노출 (§7 정합).

## Flyway
- V4 마이그레이션: `order_idempotency_key` 테이블 신설 (§9 스키마).
- V1·V2·V3 회귀 금지.

## 테스트 커버리지 기대
> **원칙**: 테스트 수는 품질 지표가 아니다. 필수 시나리오 충족 여부를 우선한다. 아래 절대 숫자는 Track 3 패턴 외삽 참고값일 뿐 목표 아님.

필수 시나리오 4축:
- `BuyerOrderController` 슬라이스 — 4개 엔드포인트 × HTTP 상태별 분기 (201·200·400·401·404·409·422).
- 멱등성 — 동일 키 재요청 200 OK 캐시 반환·동시 요청 409·미전달 graceful·재시도 시 Order 재생성 금지(D-52)·`order_id IS NOT NULL` 분기 복구.
- 전역 예외 핸들러 — 카테고리 × ProblemDetail 응답 구조·`X-Trace-Id` 헤더·MDC traceId.
- 재결제 — 활성 Payment 409·FAILED 후 재시도·D-51 3종 차단(422 ORDER_NOT_PAYABLE)·attempt_key 신규 발급.

보조 시나리오:
- DTO 변환 (`request.toCommand()`·`Response.fromOrderWithItems()`·`PagedResponse.from(Page)`).
- Validation 계층 — Controller 400·Service/Domain 422·UNIQUE 409/422 의미 분기.
- 통합 — 체크아웃 (신규 주문 + initiate 성공/실패)·Location 헤더.

외삽 참고: Track 3(~95건) 패턴 적용 시 Track 4 추가분 ~45건 예상. recon 단계에서 절대 숫자가 아닌 시나리오 충족 여부로 판정.

## 범위 외 (recon에서 OUT-OF-SCOPE로 분류 예상)
- Spring Security 정식 도입·JWT 발급·OAuth2/Login·UserDetailsService·Refresh token·세션 관리·403 `FORBIDDEN`.
- Seller 자사 OrderItem 조회·Admin 전체 조회.
- 검색·필터 (`status`·기간·키워드)·통계·집계 (`vw_order_admin`·`vw_seller_dashboard`).
- 정렬 파라미터 (`sort`) 노출.
- 결제 만료 자동 처리·PENDING 영구 정체 운영 강제 전이.
- 결제 수단 변경 UX·부분 결제·결제 분리.
- `/api/v2` 분기 시점·기준.
- Redis 분산 락 도입 (Track 7 재평가).
- Rate limit 정책·구현 (Track 6 이후 별도 결정).
- 502/503 외부 시스템 장애 시나리오 (실 PG 연동 시점).
- 배송 가능성 검증 (`SHIPPING_UNAVAILABLE` §6) — D-60·D-62a, Delivery 정책 박제 시점 재진입.

> **이력**:
> - v1 (2026-06-27·Claude.ai·D-39~D-50 기반)
> - v2 (2026-06-27·외부 검토 3차 흡수·D-51~D-55 + CR-06 보완 + CR-11 표기 반영)
> - v3 (2026-06-27): D-56·D-57·D-58 반영
>   - §5: CheckoutService 오케스트레이션 계층 명시 (D-58)·`PaymentService.initiate(orderPublicId, buyerId, method)` 시그니처 갱신 (D-56)
>   - §6: `PaymentService.initiate(orderPublicId, buyerId, method)` 시그니처 갱신 (D-56)·`Inventory` 재고 검증 행에 D-57 Inventory Java 엔티티 read-only 신설 명시
>   - §8: `PaymentService.initiate` 재시도 호출에 D-56 시그니처 반영
> - v4 (2026-06-27): D-59·D-60·D-61·D-62a 반영
>   - §6: 재검증 3종 → 2종 축소 (`SHIPPING_UNAVAILABLE` 본 트랙 보류·D-62a)·Product·Variant read-only 신설 명시 (D-59)
>   - "범위 외" 절: `SHIPPING_UNAVAILABLE` 항목 추가 (D-60·D-62a)
>   - D-61 (Payment.amount 산식) · D-59 (Product·ProductVariant·Seller read-only) · D-60 (재검증 범위 2종) 박제 반영
> - v5 (2026-06-28): WARN-2 보정·D-66 정합 반영
>   - §6: 활성 Payment 정의 PENDING(만료 전) → 409·PAID → 422 응답 분리
>   - §10: 캐싱 제외 4xx 삭제·5xx 잔류 분리 (D-66)
