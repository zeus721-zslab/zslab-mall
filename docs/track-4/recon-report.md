# Track 4 사후 정찰 보고서 (recon-report)

| 항목 | 값 |
|------|-----|
| 작성일 | 2026-06-28 |
| 정찰 대상 commit | feat `0da0c8f` / docs `d8aca45` |
| SoT (우선순위 순) | expected-spec.md v4 §1~§18 → D-63~D-65 → D-39~D-62a → audit-policy.md |
| 정찰 브랜치 | chore/track-4-recon |
| 정찰자 | Claude (read-only, 산출물 코드 수정 없음) |

---

## 카운트 요약

| 판정 | 건수 |
|------|------|
| PASS | 62 |
| FAIL-코드 | 1 |
| FAIL-명세 | 0 |
| WARN | 3 |
| OUT-OF-SCOPE | 7 |

**종합 판정: 조건부 통과** — FAIL-코드 1건(D-52 IN_PROGRESS row 미삭제) 수정 후 승인 권고.

---

## §1 — 엔드포인트 시그니처·PagedResponse·기본 20/최대 100

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 1 | POST /api/v1/orders | PASS | BuyerOrderController:35 |
| 2 | GET /api/v1/orders/{orderPublicId} | PASS | BuyerOrderController:52 |
| 3 | GET /api/v1/orders | PASS | BuyerOrderController:62 |
| 4 | POST /api/v1/orders/{orderPublicId}/payments | PASS | BuyerOrderController:74 |
| 5 | PagedResponse 5필드 (items·page·size·totalCount·hasNext) | PASS | PagedResponse.java |
| 6 | size 기본값 20·최대 100 | PASS | BuyerOrderQueryService: DEFAULT=20, MAX=100 |
| 7 | Spring Data Page<T> 직렬화 금지 → PagedResponse.from(Page<T>) | PASS | PagedResponse.java:17 |

---

## §2 — 인증·인가

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 8 | X-Buyer-Id 미전달 → 401 UNAUTHENTICATED | PASS | BuyerOrderController:105 (UnauthenticatedException → GlobalExceptionHandler) |
| 9 | X-Buyer-Id 형식 오류(비숫자) → 400 MALFORMED_REQUEST | PASS | BuyerOrderController:111 (NumberFormatException → 400) |
| 10 | 타인/미존재 주문 → 404 ORDER_NOT_FOUND | PASS | CheckoutService:107, BuyerOrderQueryService:58 |

> D-39.4 "미주입 → 400"과 §2 "누락 → 401" 불일치 — 코드는 expected-spec v4(SoT 최상위) 기준 401 준수. D-39 구문은 §2에 의해 후속 결정으로 override됨.

---

## §3 — 패키지 구조·MapStruct 부재

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 11 | order/controller/request (CreateOrderRequest·OrderItemRequest·ShippingAddressRequest·RetryPaymentRequest) | PASS | 파일 목록 확인 |
| 12 | order/controller/response (CheckoutResponse·OrderResponse·OrderItemResponse·OrderSummaryResponse·ShippingAddressResponse·PagedResponse·SellerGroupResponse·StatusView) | PASS | 파일 목록 확인 |
| 13 | checkout/ 패키지 신설 (command·entity·enums·exception·repository·service) | PASS | 파일 목록 확인 |
| 14 | payment/controller/request/PaymentCallbackRequest 이동 | PASS | payment/controller/request/ 확인 |
| 15 | mapper/ 패키지 미도입 | PASS | 해당 패키지 없음 |

---

## §4 — toCommand·from 변환 패턴

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 16 | CreateOrderRequest.toCommand(buyerId, idempotencyKey) → CheckoutCommand | PASS | CreateOrderRequest.java:20 |
| 17 | OrderResponse.fromOrderWithItems(order, productById, variantById, sellerById) | PASS | OrderResponse.java:25 |
| 18 | PagedResponse.from(Page<T>) — 5필드 | PASS | PagedResponse.java:17 |
| 19 | OrderSummaryResponse.from(order, productById) | PASS | OrderSummaryResponse.java:28 |

---

## §5 — CheckoutService 오케스트레이션·부분 실패·Location 헤더

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 20 | CheckoutService 클래스 @Transactional 없음 (D-58) | PASS | CheckoutService.java:55 (class-level 없음) |
| 21 | D-52 5단계 순서: 멱등성 INSERT → createOrder TX1 → linkOrder UPDATE → initiate TX2 → 캐싱 | PASS | idempotentCheckout():127→137→139→140 |
| 22 | 부분 실패: Order 롤백 금지·Payment row 미저장·INITIATE_FAILED 반환 | PASS | completeWithInitiate():204 (PaymentGatewayException catch → forNewOrderInitiateFailed) |
| 23 | Location: /api/v1/orders/{orderPublicId} 강제 (신규 성공·initiate 실패 양쪽) | PASS | CheckoutService:218 (ORDER_LOCATION_PREFIX + order.getPublicId()) |

---

## §6 — 재결제 허용 조건·D-60 재검증 2종·Location 헤더

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 24 | 활성 Payment(PENDING 기간 내) → 409 PAYMENT_IN_PROGRESS | PASS | PaymentService: unexpired PENDING check |
| 25 | PAID Payment → 422 PAYMENT_ALREADY_COMPLETED (§14·§17 기준) | PASS (WARN) | PaymentService; §6 "→409" vs §14/§17 "→422" 내부 불일치 → 아래 WARN-2 참조 |
| 26 | FAILED Payment → 신규 initiate 허용 | PASS | PaymentService 분기 |
| 27 | D-60 재검증 PRODUCT_NOT_ON_SALE (status ≠ SALE → 422) | PASS | CheckoutService:235 |
| 28 | D-60 재검증 OUT_OF_STOCK (soldoutManual or 가용재고 부족 → 422) | PASS | CheckoutService:243 |
| 29 | 재결제 Location: /api/v1/payments/{paymentPublicId} | PASS | CheckoutService:114 (PAYMENT_LOCATION_PREFIX) |

---

## §7 — CheckoutResponse 팩토리 3종·필드 구조

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 30 | CheckoutResponse.forNewOrder(payment, redirectUrl) | PASS | CheckoutResponse.java:27 |
| 31 | CheckoutResponse.forRetry(payment, redirectUrl) | PASS | CheckoutResponse.java:32 |
| 32 | CheckoutResponse.forNewOrderInitiateFailed(retryPaymentUrl) | PASS | CheckoutResponse.java:37 |
| 33 | INITIATE_FAILED: payment.publicId=null·status.code="INITIATE_FAILED"·next.retryPaymentUrl 제공 | PASS | CheckoutResponse.java:37-40 |
| 34 | 성공: next=null (NON_NULL 직렬화로 미노출) | PASS | success() private 메서드 반환 구조 |
| 35 | failureCode 응답 미노출 (§18) | PASS | CheckoutResponse에 failureCode 필드 없음 |

---

## §8 — Idempotency-Key 128자·재시도 분기 3종

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 36 | 헤더명 Idempotency-Key | PASS | BuyerOrderController: "Idempotency-Key" 헤더 읽기 |
| 37 | 최대 128자 | PASS | IDEMPOTENCY_KEY_PATTERN = `^[0-9A-Za-z-]{1,128}$` |
| 38 | 형식 검증 (WARN 참조) | WARN | 아래 WARN-1 참조 |
| 39 | 미전달 허용 (graceful degradation) | PASS | checkout():93 null check |
| 40 | COMPLETED 키 → 200 캐시 반환 | PASS | handleExistingKey():144 |
| 41 | IN_PROGRESS + orderId=null → 409 | PASS | handleExistingKey():148 |
| 42 | IN_PROGRESS + orderId!=null → 기존 Order 복구·initiate 재호출 | PASS | handleExistingKey():151-154 |

---

## §9 — V4 Flyway·복합 PK·보존 윈도우

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 43 | V4__order_idempotency_key.sql 신설 | PASS | V4__.sql 확인 |
| 44 | 컬럼 (buyer_id BIGINT·idempotency_key VARCHAR(128)·order_id BIGINT NULL·status ENUM·response_body LONGTEXT NULL·created_at DATETIME(6)·completed_at DATETIME(6) NULL) | PASS | V4__.sql:26-35 전체 컬럼 일치 |
| 45 | PK (buyer_id, idempotency_key) 복합 | PASS | V4__.sql:34 |
| 46 | FK 미부여 (논리 참조·Track 7 이관 고려) | PASS | V4__.sql 주석 명시 |
| 47 | 72h 보존 윈도우 정책 문서화·삭제 배치 후속 트랙 위임 | OUT-OF-SCOPE | V4__.sql 주석: "삭제 배치·created_at 인덱스는 본 트랙 범위 밖" |

---

## §10 — 멱등성 응답 캐싱

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 48 | 저장 시점: 2xx 성공 응답 직렬화 → COMPLETED UPDATE와 동일 flush | PASS | completeWithInitiate():215-216 |
| 49 | 4xx·5xx 응답 미저장 | PASS | 예외 전파 시 completeWithInitiate 미도달 |
| 50 | INITIATE_FAILED(201) 캐싱 | PASS | catch PaymentGatewayException → 이후 markOrNull.complete() 진입 |
| 51 | 재요청: response_body 그대로·HTTP 200 OK | PASS | handleExistingKey():145 (CheckoutOutcome.cached → ResponseEntity.ok) |

> **FAIL-코드-1 연관**: createOrder() 4xx 예외 발생 시 IN_PROGRESS row 미삭제로 동일 키 재시도 불가 → 아래 FAIL 항목 참조.

---

## §11 — seller 그룹화·subtotal·BIGINT 미노출·목록 비그룹화

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 52 | GET /api/v1/orders/{id}: OrderItem → seller 단위 그룹화·배열 | PASS | OrderResponse.fromOrderWithItems():25 (LinkedHashMap grouping) |
| 53 | 단일 판매자도 배열 (sellers[]) | PASS | 조건부 분기 없음 |
| 54 | sellers[].subtotal 포함 | PASS | SellerGroupResponse 필드 확인 |
| 55 | 식별자 public_id 노출 (BIGINT DB id 미노출) | PASS | OrderResponse: orderId=order.publicId, sellerId=seller.publicId 등 |
| 56 | GET /api/v1/orders 목록: seller 비그룹화 (OrderSummaryResponse) | PASS | OrderSummaryResponse: sellers 필드 없음 |
| 57 | previewTitle: orderedAt ASC 첫 상품명 + "외 N건" | PASS | OrderSummaryResponse.from() 정렬·포맷 로직 |
| 58 | shippingAddress GET /api/v1/orders/{id} 포함 (설계 승인) | PASS | OrderResponse:22 ShippingAddressResponse 포함 |

---

## §12 — status {code, label}·fallback

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 59 | StatusView record {code, label} | PASS | StatusView.java |
| 60 | StatusView.of(Enum<?>) → code=enum.name(), label=code (Track 4 fallback) | PASS (WARN) | StatusView.java; 아래 WARN-3 참조 |
| 61 | StatusView.ofCode(String) → INITIATE_FAILED 등 합성 코드 | PASS | StatusView.java |

---

## §13 — /api/v1/ 경로·webhook 경로 분리

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 62 | BuyerOrderController: @RequestMapping("/api/v1/orders") | PASS | BuyerOrderController:36 |
| 63 | PaymentWebhookController: @RequestMapping("/api/webhooks/payments") | PASS | PaymentWebhookController:21 |
| 64 | webhook sub-path 없음 (@PostMapping 단독) | PASS | PaymentWebhookController @PostMapping 확인 |

---

## §14 — ProblemDetail·X-Trace-Id·MDC traceId

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 65 | @RestControllerAdvice GlobalExceptionHandler | PASS | GlobalExceptionHandler.java |
| 66 | ProblemDetail RFC 7807: type·title·status·detail·instance + code·traceId 커스텀 | PASS | build() 메서드 |
| 67 | 400 VALIDATION_FAILED (MethodArgumentNotValidException) | PASS | GlobalExceptionHandler |
| 68 | 400 MALFORMED_REQUEST (HttpMessageNotReadable·TypeMismatch·MalformedRequest·IllegalArgument) | PASS | GlobalExceptionHandler |
| 69 | 401 UNAUTHENTICATED | PASS | GlobalExceptionHandler |
| 70 | 404 ORDER_NOT_FOUND / PRODUCT_NOT_FOUND | PASS | GlobalExceptionHandler |
| 71 | 409 IDEMPOTENCY_KEY_IN_PROGRESS / PAYMENT_IN_PROGRESS / OPTIMISTIC_LOCK_FAILURE | PASS | GlobalExceptionHandler |
| 72 | 422 PAYMENT_ALREADY_COMPLETED / ORDER_NOT_PAYABLE / CHECKOUT_ITEM_MISMATCH / INVALID_CALLBACK | PASS | GlobalExceptionHandler |
| 73 | 500 INTERNAL_ERROR (Exception fallback) | PASS | GlobalExceptionHandler |
| 74 | 429·502·503 — 미구현 | OUT-OF-SCOPE | expected-spec §14 "본 트랙 범위 외" 명시 |
| 75 | 403 FORBIDDEN — Spring Security 미도입 | OUT-OF-SCOPE | expected-spec §14 "본 트랙 범위 외" 명시 |
| 76 | X-Trace-Id 응답 헤더 주입 | PASS | TraceIdFilter:32 response.setHeader("X-Trace-Id") |
| 77 | MDC "traceId" 주입·finally remove | PASS | TraceIdFilter:28 MDC.put, finally MDC.remove |
| 78 | ULID 기반 traceId (UlidCreator.getMonotonicUlid) | PASS | TraceIdFilter:27 |
| 79 | logging.pattern.level: "%5p [%X{traceId:-}]" | PASS | application.yml |

---

## §15 — JSON 직렬화 정책

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 80 | camelCase (Jackson 기본) | PASS | Jackson default |
| 81 | NON_NULL (null 필드 미노출) | PASS | application.yml: default-property-inclusion: non_null |
| 82 | 금액 정수 (long) | PASS | totalPrice·unitPrice·subtotal 등 모두 long |
| 83 | ISO-8601 UTC @JsonFormat ("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") | PASS | CheckoutResponse.PaymentView.expiresAt·OrderSummaryResponse.orderedAt |

---

## §16 — Validation 3계층·409 vs 422 의미 분기

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 84 | Controller: @Valid + Bean Validation | PASS | BuyerOrderController: @Valid @RequestBody |
| 85 | Service: 도메인 422 (OrderNotPayableException·CheckoutItemMismatchException 등) | PASS | CheckoutService:236·244 |
| 86 | Repository: DataIntegrityViolationException → 409 (IdempotencyKeyInProgressException) | PASS | idempotentCheckout():129 |
| 87 | 409 = 기술적 충돌(멱등성·낙관락)·422 = 도메인 규칙 위반 | PASS | GlobalExceptionHandler 분류 일치 |

---

## §17 — HTTP 상태 색인·SHIPPING_UNAVAILABLE 행 부재

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 88 | 201 신규 주문 성공·Location /api/v1/orders/{id} | PASS | BuyerOrderController:toResponseEntity |
| 89 | 201 신규 주문+initiate 실패·Location /api/v1/orders/{id} | PASS | CheckoutService:218 (INITIATE_FAILED도 ORDER_LOCATION_PREFIX) |
| 90 | 201 재결제 성공·Location /api/v1/payments/{payId} | PASS | CheckoutService:114 |
| 91 | 200 멱등성 캐시·Location 없음 | PASS | BuyerOrderController:toResponseEntity (cached → ok()) |
| 92 | SHIPPING_UNAVAILABLE — 재검증 목록 부재 | OUT-OF-SCOPE | §17 행 없음·D-60 2종 한정 명시 |

---

## §18 — initiate 실패 운영 로그 5필드

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 93 | log.warn 5필드: orderPublicId·attemptKey·buyerId·failureCode·occurredAt | PASS | CheckoutService:206-208 |
| 94 | failureCode 응답 미노출 (로그 전용) | PASS | CheckoutResponse에 failureCode 필드 없음 |

---

## D-63·D-64·D-65 추가 검증

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 95 | D-63: 재검증 CheckoutService.revalidatePayable — 재결제 경로에만 배치 | PASS | retryPayment():110 call; checkout() 경로 미호출 |
| 96 | D-63: PaymentService.initiate 자체 재검증 없음 | PASS | PaymentService: 본인검증·amount 재계산만 수행 |
| 97 | D-64: OrderItemRequest 3필드 (productId·variantId·quantity) — 가격 미입력 | PASS | OrderItemRequest.java:9-12 |
| 98 | D-64: unit_price = base_price + additional_price | PASS | CheckoutService:184 |
| 99 | D-64: total_price = unit × quantity | PASS | CheckoutService:185 |
| 100 | D-64: sellerId = product.seller_id | PASS | CheckoutService:188 |
| 101 | D-64: variant.productId == product.id 소속 검증 → 422 CHECKOUT_ITEM_MISMATCH | PASS | CheckoutService:179-181 |
| 102 | D-65: productId·variantId = public_id (prd_·var_ 접두어) | PASS | OrderItemRequest 필드·CheckoutService findByPublicIdIn |
| 103 | D-65: findByPublicIdIn으로 BIGINT DB id 해소 | PASS | CheckoutService:164·167 |

---

## FAIL 항목 상세

### FAIL-코드-1 — D-52/D-44b: createOrder 예외 시 IN_PROGRESS row 미삭제

- **위치**: `CheckoutService.idempotentCheckout()` (CheckoutService.java:137)
- **SoT 근거**: decisions.md D-52 "4xx·5xx: IN_PROGRESS row 삭제 후 동일 키 재시도 허용" / D-44b 동일
- **현상**:
  1. `saveAndFlush(startInProgress)` → IN_PROGRESS row DB 커밋 (독립 TX)
  2. `createOrder(command)` 예외 발생 (예: CheckoutItemNotFoundException 404, CheckoutItemMismatchException 422)
  3. 예외 전파 → IN_PROGRESS row 잔류 (삭제 코드 없음)
  4. 동일 키 재시도 → `handleExistingKey()` → IN_PROGRESS + orderId=null → `409 IDEMPOTENCY_KEY_IN_PROGRESS`
- **영향**: 상품 정보 오입력 등 클라이언트 교정 가능 4xx 오류 후, 동일 Idempotency-Key로 재시도 불가. 다른 키 사용 필수.
- **수정 방향** (정찰 보고만, 코드 수정 금지):
  ```java
  // idempotentCheckout() createOrder 호출 주변에 try-catch 추가
  Order order;
  try {
      order = createOrder(command);
  } catch (RuntimeException createFail) {
      idempotencyRepository.delete(mark);   // 4xx 교정 가능 → 재시도 허용
      throw createFail;
  }
  ```
  단, 5xx(예상치 못한 RuntimeException) 처리 정책 결정 필요 (D-52: "5xx → 운영자 개입 전까지 409 유지" → 4xx만 삭제 권장).

---

## WARN 항목 상세

### WARN-1 — §8: Idempotency-Key 형식 검증 패턴 과허용

- **위치**: `BuyerOrderController.IDEMPOTENCY_KEY_PATTERN = "^[0-9A-Za-z-]{1,128}$"`
- **SoT 문언**: §8 "ULID 또는 UUID v4 — 서버 형식 검증"
- **현상**: 현재 패턴은 `idem-key-replay-0001`, `abc123` 등 ULID·UUID 이외 문자열도 허용. 통합 테스트도 해당 형식 키 사용(CheckoutIntegrationTest:106).
- **리스크**: 기능 저하 없음. 허용 범위가 넓어 클라이언트 실수 검출력 약화. 보안·정합성 위험 없음.
- **권고**: 엄밀 ULID(`^[0-9A-HJKMNP-TV-Z]{26}$`) + UUID v4 패턴 병렬 허용으로 강화 가능. Track 5+ 재평가 권장.

### WARN-2 — §6 vs §14/§17 PAID Payment HTTP 상태 내부 불일치

- **SoT 문언**: §6 "활성 Payment(PENDING or PAID) 존재 → 409" vs §14 `PAYMENT_ALREADY_COMPLETED → 422`
- **코드 동작**: PAID → `PaymentAlreadyCompletedException → 422 PAYMENT_ALREADY_COMPLETED` (§14/§17 기준)
- **평가**: §14/§17이 §6보다 세부 사양이며 SoT 최상위 문서 내 일관. 코드는 §14/§17 준수로 정확. §6 문언 자체가 단순화된 설명. 코드 수정 불필요.
- **권고**: expected-spec §6 문언 "활성 Payment(PENDING or PAID)" → "PENDING(기간 내) → 409 / PAID → 422" 로 개정 권장.

### WARN-3 — §12: Code 도메인 미구현·label 항상 code와 동일

- **위치**: `StatusView.of(Enum<?>)` — label = enum.name() (Code 조회 없음)
- **SoT 문언**: §12 "label: Code.label 조회, 운영자 편집 반영 / 실패 시 fallback label=code"
- **코드 동작**: Track 4 시점 Code 도메인 미구현 → 항상 fallback(label=code). StatusView 주석에 명시.
- **평가**: §12 fallback 규정 자체를 정확히 적용. 기능 문제 없음. Code 도메인은 Track 5+ 과제.
- **권고**: Track 5에서 Code 테이블 + StatusView.of() Code 조회 로직 추가 시 본 WARN 해소.

---

## OUT-OF-SCOPE 목록

| 항목 | 근거 |
|------|------|
| 72h 삭제 배치·created_at 인덱스 | §9 "본 트랙 범위 밖·운영 트래픽 발생 시점 도입" |
| 429 RATE_LIMIT_EXCEEDED | §14 "본 트랙 범위 외" |
| 502 BAD_GATEWAY | §14 "본 트랙 범위 외" |
| 503 SERVICE_UNAVAILABLE | §14 "본 트랙 범위 외" |
| 403 FORBIDDEN (Spring Security) | §14 "본 트랙 범위 외" |
| SHIPPING_UNAVAILABLE 재검증 | §17 행 없음·D-60 2종 한정 |
| Code 도메인·label 다국어화 | Track 5+ |

---

## 테스트 커버리지 확인

| 테스트 파일 | 유형 | 건수 | 주요 검증 |
|------------|------|------|----------|
| CheckoutServiceTest | @ExtendWith(Mockito) | 8 | 가격 산정·멱등성 분기·INITIATE_FAILED·재검증 2종 |
| CheckoutIntegrationTest | @SpringBootTest + Testcontainers MariaDB | 8 | 실 DB·Flyway V1~V4·멱등성 INSERT·X-Trace-Id·재고 재검증 |
| BuyerOrderControllerTest | @WebMvcTest | 12 | HTTP 상태 매트릭스·ProblemDetail·인증 헤더·Location |
| PaymentWebhookControllerTest | @WebMvcTest | 3 | webhook 200/422/400 |

> FAIL-코드-1(D-52 row 미삭제) 커버 테스트 부재 — 수정 시 `checkout_itemNotFound_sameKeyRetryable` 통합 테스트 추가 권장.
