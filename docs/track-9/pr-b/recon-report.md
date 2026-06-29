# Track 9 PR-B 정찰 보고서

> 트랙: Track 9 / PR-B (S급·외부 검토 권장·결정 라운드 메인)
> 대상: ClaimService (request/approve/reject/markCompleted) + BuyerClaimController + DTO + E2E 통합 테스트
> 정찰일: 2026-06-29
> 정찰 방식: MCP read-only (Claude Code)
> 정찰 commit: main HEAD 41eeb61 (PR-A 머지 직후)
> 정찰 룰 적용: 파일 전체 read 의무·메서드 목록 전체 확인 의무 (Track 8 / PR-A/PR-B 사후 정정 패턴 차단)

---

## 1. 정찰 범위

| # | 파일 | 정찰 깊이 |
|---|---|---|
| 1 | claim/entity/Claim.java | 전체 read |
| 2 | claim/enums/ClaimStatus.java | 전체 read |
| 3 | claim/enums/ClaimType.java | 전체 read |
| 4 | claim/service/ClaimService.java | 전체 read |
| 5 | claim/repository/ClaimRepository.java | 전체 read |
| 6 | claim/exception/ClaimNotFoundException.java | 전체 read |
| 7 | claim/exception/ClaimInvalidStateException.java | 전체 read |
| 8 | claim/handler/ClaimRefundCompletedHandler.java | 전체 read |
| 9 | claim/event/ | 디렉토리 list (부재 확인) |
| 10 | order/controller/BuyerOrderController.java | 전체 read |
| 11 | order/controller/request/ | 디렉토리 list + CreateOrderRequest 전체 read |
| 12 | order/controller/response/ | 디렉토리 list + OrderResponse 전체 read |
| 13 | order/service/OrderService.java | 부분 read (패턴 확인) |
| 14 | order/entity/OrderItem.java | 전체 read |
| 15 | order/repository/OrderItemRepository.java | 전체 read |
| 16 | common/web/GlobalExceptionHandler.java | 전체 read |
| 17 | checkout/CheckoutIntegrationTest.java | 전체 read |
| 18 | order/controller/BuyerOrderControllerTest.java | 전체 read |
| 19 | V1__init.sql claim·code 섹션 | grep 확인 |

SoT 정독: decisions.md D-88·D-39·D-40·D-41·D-43·D-50·D-66·D-29·D-75·D-01·state-machine §2·§3·invariants §2.13·§2.13.1·aggregate-boundary §2.5·expected-spec §1.2·pr-a/recon-report §2.4·§2.5·live-traps.md LT-01~03

---

## 2. 현 상태 박제

### 2.1 Claim 도메인 메서드 부재 목록 (Track 9 PR-B 신설 대상)

#### 2.1.1 Claim.java 메서드 전체 목록 (현재)

| 메서드 | 가시성 | 책임 | 상태 |
|---|---|---|---|
| `static create(orderItemId, type, reasonCode, reasonDetail, requestedBy, requestedAt)` | public | 생성·초기 status=REQUESTED | 기 구현 (Track 5) |
| `markCompleted(LocalDateTime processedAt)` | public | APPROVED→COMPLETED·CLM-4 | 기 구현 (Track 5) |
| `transitionTo(ClaimStatus next)` | private | canTransitionTo 가드·전이 | 기 구현 (Track 5) |

**부재 메서드** (PR-B 신설 대상):
- `approve(LocalDateTime processedAt)` — REQUESTED→APPROVED·CLM-4
- `reject(String reason, LocalDateTime processedAt)` — REQUESTED→REJECTED·CLM-2

> `create()` 팩토리 메서드는 이미 존재 (Track 5) → ClaimService.request에서 직접 호출·신설 불요.

#### 2.1.2 ClaimService.java 메서드 전체 목록 (현재)

| 메서드 | 책임 | 상태 |
|---|---|---|
| `markCompleted(Long claimId)` | APPROVED→COMPLETED·멱등 no-op | 기 구현 (Track 5) |

**부재 메서드** (PR-B 신설 대상):
- `request(ClaimRequestCommand command)` — Claim INSERT·OrderItem 사전 검증·ClaimRequested 이벤트 발행
- `approve(Long claimId)` — APPROVED 전이·ClaimApproved 이벤트 발행
- `reject(Long claimId, String reasonCode)` — REJECTED 전이·ClaimRejected 이벤트 발행

### 2.2 ClaimRepository 현 메서드 목록

| 메서드 | 용도 |
|---|---|
| `findByPublicId(String publicId)` | Claim 단건 조회 (public_id 기준) |
| JpaRepository 기본 (`findById` 등) | id 기준 조회 |

**PR-B 신설 대상**:
- `findByOrderItemId(Long orderItemId)` — 중복 REQUESTED 체크용 (CLM-2 정책에 따라 중복 허용 여부 Q1 결정 필요)
- `findAllByRequestedBy(Long buyerId, Pageable pageable)` — 목록 조회 (buyer 기준 페이징)
- 단, `findByOrderItemIdOrderByCreatedAtDesc` 등 구체 시그니처는 구현 시 결정

### 2.3 Claim 이벤트 클래스 부재

`claim/event/` 디렉토리 자체 미존재. 현재 도메인 이벤트 0건.

**PR-B 신설 대상** (발행만·PR-C 핸들러 소비):
- `ClaimRequested` — request 메서드 발행·payload: claimId·orderItemId·claimType·buyerId·occurredAt
- `ClaimApproved` — approve 발행·payload: claimId·orderItemId·claimType·occurredAt
- `ClaimRejected` — reject 발행·payload: claimId·orderItemId·claimType·occurredAt

> D-29 (save→publish) + D-75 (DB 쓰기 핸들러 = REQUIRES_NEW) 정합 — PR-B는 이벤트 발행만·PR-C가 핸들러 신설.

### 2.4 Claim 핸들러 현황

`claim/handler/ClaimRefundCompletedHandler.java` 1건 존재 (Track 5).
- `onRefundCompleted(RefundCompleted event)` — RefundCompleted 소비·CANCEL type→COMPLETED 전이
- PR-B 소관 핸들러 없음 (PR-B는 ClaimRequested/Approved/Rejected 이벤트 **발행**만)

**PR-C 신설 대상** (본 PR 범위 외):
- `ClaimRequestedHandler` — OrderItem.changeStatus(CANCEL_REQUESTED) 전이 (D-88 Q3·Q7)
- `ClaimApprovedHandler` — Refund 생성 트리거
- `ClaimRejectedHandler` — OrderItem 상태 복귀 처리 (정책 미확정·결정 라운드 의제 가능)

### 2.5 BuyerOrderController 패턴 박제 (BuyerClaimController 구현 기준)

| 항목 | 패턴 |
|---|---|
| 패키지 | `claim/controller/` (D-40 β′ 액터별 분리·URL 액터 중립) |
| 인증 헤더 | `X-Buyer-Id` 헤더(BIGINT)·누락 시 401·형식 오류 시 400 (D-39) |
| resolveBuyerId | `Long.parseLong(header.trim())`·`UnauthenticatedException`·`MalformedRequestException` |
| RequestMapping | `/api/v1/claims` (리소스 중심·액터 prefix 금지) |
| DTO 구조 | `controller/request/`·`controller/response/` 하위 분리 (D-41 γ) |
| DTO 패턴 | `Request.toCommand(buyerId)` 인스턴스 메서드·`Response.from(claim)` 정적 팩토리 |
| HTTP 상태 | 신규 생성 201+Location·단건/목록 200·4xx ProblemDetail |
| 전역 예외 | `@RestControllerAdvice`·RFC 7807 ProblemDetail·`code`·`traceId` 속성 |

### 2.6 OrderService 패턴 박제 (ClaimService.request 참조)

| 항목 | 패턴 |
|---|---|
| 트랜잭션 경계 | `@Transactional` 메서드 단위 |
| 이벤트 발행 | `save()` → `eventPublisher.publishEvent(event)` 순서 (D-29·save→publish) |
| 예외 throw | `IllegalArgumentException`(필수값)·`IllegalStateException`(전이 위반) |
| Command 패턴 | `XxxCommand` record·toCommand() Request 메서드에서 생성 |

### 2.7 CheckoutIntegrationTest 패턴 박제 (ClaimIntegrationTest 구현 기준)

| 항목 | 패턴 |
|---|---|
| 기반 | `@SpringBootTest`·`@AutoConfigureMockMvc`·`@Transactional` |
| DB | Testcontainers MariaDB 11.4·`@DynamicPropertySource` |
| 시딩 | `@BeforeEach` seed + `SET FOREIGN_KEY_CHECKS = 0` (LT-02: try-finally 복원 의무) |
| 실행 | `MockMvc`·`mockMvc.perform(post("/api/v1/orders")...)`·jsonPath 검증 |
| FK 처리 | LT-02 패턴: `try { SET FK=0; seed; } finally { SET FK=1; }` 의무 |
| @AfterEach | spy reset 패턴 (`Mockito.reset`) — 필요 시 적용 |

### 2.8 GlobalExceptionHandler 현 매핑 현황

| HTTP | 예외 클래스 | code |
|---|---|---|
| 400 | MethodArgumentNotValidException | VALIDATION_FAILED |
| 400 | MalformedRequestException·IllegalArgumentException | MALFORMED_REQUEST |
| 401 | UnauthenticatedException | UNAUTHENTICATED |
| 404 | OrderNotFoundException | ORDER_NOT_FOUND |
| 404 | CheckoutItemNotFoundException | PRODUCT_NOT_FOUND |
| 404 | RefundNotFoundException | REFUND_NOT_FOUND |
| 409 | IdempotencyKeyInProgressException | IDEMPOTENCY_KEY_IN_PROGRESS |
| 409 | PaymentInProgressException | PAYMENT_IN_PROGRESS |
| 409 | OptimisticLockingFailureException | OPTIMISTIC_LOCK_FAILURE |
| 422 | PaymentAlreadyCompletedException | PAYMENT_ALREADY_COMPLETED |
| 422 | OrderNotPayableException | ORDER_NOT_PAYABLE |
| 422 | CheckoutItemMismatchException | CHECKOUT_ITEM_MISMATCH |
| 422 | InvalidCallbackException | INVALID_CALLBACK |
| 422 | RefundInvariantViolationException | REFUND_INVARIANT_VIOLATION |
| 500 | Exception (fallback) | INTERNAL_ERROR |

**PR-B 신설 매핑 대상** (ClaimNotFoundException 이미 존재·그러나 GlobalExceptionHandler에 미등록):
- `ClaimNotFoundException` → 404·`CLAIM_NOT_FOUND`
- `IllegalStateException` (canTransitionTo 위반) → 409·`CLAIM_STATE_CONFLICT` (D-50·동시성 충돌 분류)
- 소유권 불일치 (buyer_id 미일치) → 404·`CLAIM_NOT_FOUND` (정보 노출 회피·D-39 패턴 정합)

> `IllegalArgumentException` → 400 은 기존 `MALFORMED_REQUEST`로 이미 처리 (GlobalExceptionHandler 기 등록). `IllegalStateException` 은 현재 500 fallback으로 떨어짐 → 409 명시 등록 필요.

### 2.9 reason_code Code 시드 현황

V1__init.sql에 `code_group`·`code` 테이블 DDL은 있으나 **시드 INSERT 없음**. 마이그레이션 파일 V1~V5 전건 검색 결과 CLAIM_REASON 그룹 시드 레코드 미존재.

claim.reason_code 컬럼 주석: `B/CLAIM_REASON Code 정합(③·ENUM 미적용)`.

**WARN-1 참조** — reason_code 검증 전략(Q5)과 연계·결정 라운드 의제.

---

## 3. PR-B 신설 대상 목록

### 3.1 Claim Aggregate Root 신설 메서드

| 메서드 | 책임 | 연관 불변식 |
|---|---|---|
| `approve(LocalDateTime processedAt)` | REQUESTED→APPROVED 전이·processedAt 채움 | CLM-4·state-machine §2 |
| `reject(String reason, LocalDateTime processedAt)` | REQUESTED→REJECTED·이력 보존 | CLM-2·CLM-4 |

> `create()` 팩토리 기존 활용·request 별도 메서드 신설 불요.
> `reject`의 `reason` 인자 = reasonCode 갱신 여부 or 별도 필드는 Q3 결정 후 확정.

### 3.2 ClaimService 신설 메서드 (4 메서드 완성)

| 메서드 | 책임 | 이벤트 |
|---|---|---|
| `request(ClaimRequestCommand)` | Claim INSERT·OrderItem 사전 검증·ClaimRequested 발행 | ClaimRequested (발행) |
| `approve(Long claimId)` | APPROVED 전이·ClaimApproved 발행 | ClaimApproved (발행) |
| `reject(Long claimId, String reasonCode)` | REJECTED 전이·ClaimRejected 발행 | ClaimRejected (발행) |
| `markCompleted(Long claimId)` | 기존·COMPLETED 전이·멱등 | — |

> D-88 Q2: Seller/Admin endpoint = Track 10 이연. ClaimService.approve/reject는 본 트랙에서 Service layer만 완성·endpoint 없음 (E2E는 Service 직접 호출).

### 3.3 BuyerClaimController endpoint (신규 파일)

D-40 β′ 패턴·URL 액터 중립(`/api/v1/claims`)·X-Buyer-Id stub (D-39):

| HTTP | URL | 책임 | 응답 |
|---|---|---|---|
| POST | /api/v1/claims | Claim 요청·CANCEL type 한정 (D-88 Q1) | 201 + Location |
| GET | /api/v1/claims/{claimPublicId} | 단건 조회·본인 소유 확인 | 200 |
| GET | /api/v1/claims | 목록·X-Buyer-Id 기준·페이징 | 200 PagedResponse |

> endpoint 수·분류는 Q4 결정 의제.

### 3.4 DTO (D-41 γ 정합)

**request/**:

| 클래스 | 책임 | 필드 초안 |
|---|---|---|
| `ClaimRequestRequest` | POST 요청 DTO·@Valid·toCommand(buyerId) | orderItemPublicId·type·reasonCode·reasonDetail |
| `ClaimRequestCommand` | Service 입력 Command record | orderItemId(Long)·type·reasonCode·reasonDetail·buyerId·requestedAt |

**response/**:

| 클래스 | 책임 | 필드 초안 |
|---|---|---|
| `ClaimResponse` | 단건/목록 상세 응답 | publicId·type·status·reasonCode·reasonDetail·requestedBy·requestedAt·processedAt |
| `ClaimSummaryResponse` | 목록 경량 응답 (선택) | publicId·type·status·requestedAt |

> 필드 목록은 Q7 결정 의제.

### 3.5 도메인 이벤트 (신규 파일·PR-C 핸들러 소비 대상)

| 이벤트 | payload 초안 | 발행 시점 |
|---|---|---|
| `ClaimRequested` | claimId·orderItemId·claimType·buyerId·occurredAt | ClaimService.request |
| `ClaimApproved` | claimId·orderItemId·claimType·occurredAt | ClaimService.approve |
| `ClaimRejected` | claimId·orderItemId·claimType·occurredAt | ClaimService.reject |

> D-29 정합: save → publishEvent 순서. D-75: PR-C 핸들러가 DB 쓰기 → REQUIRES_NEW. PR-B는 발행만.

### 3.6 GlobalExceptionHandler 신설 매핑

| 예외 | HTTP | code | D-50 분류 |
|---|---|---|---|
| `ClaimNotFoundException` | 404 | CLAIM_NOT_FOUND | 리소스 미존재 |
| `IllegalStateException` (클레임 상태 전이 위반) | 409 | CLAIM_STATE_CONFLICT | 동시성·업무 제약 (Q3 결정 후 422 vs 409 확정) |
| 소유권 불일치 | 404 | CLAIM_NOT_FOUND | 정보 노출 회피·D-39 패턴 정합 |

> `IllegalStateException` → 409 vs 422 는 D-50 "동시성 충돌" vs "업무 제약 위반" 분류 기준으로 Q3 결정 필요.

### 3.7 E2E 통합 테스트 시나리오 (예상 ≥8건)

| # | 시나리오 | 예상 결과 |
|---|---|---|
| T1 | Claim 요청 성공 (PAID OrderItem·buyerId 일치) | 201·REQUESTED |
| T2 | Claim 요청 실패 (CONFIRMED OrderItem·canTransitionTo 차단) | 409 CLAIM_STATE_CONFLICT |
| T3 | Claim 요청 실패 (다른 buyer 소유 OrderItem) | 404 CLAIM_NOT_FOUND |
| T4 | Claim 요청 실패 (X-Buyer-Id 누락) | 401 UNAUTHENTICATED |
| T5 | Claim 단건 조회 (본인 소유) | 200·claimPublicId 정합 |
| T6 | Claim 목록 조회 | 200·PagedResponse |
| T7 | ClaimService.approve 직접 호출 → APPROVED (Service layer 검증) | APPROVED 전이 성공 |
| T8 | ClaimService.reject 직접 호출 → REJECTED (CLM-2 이력 추적) | REJECTED 전이 성공 |
| T9 | REJECTED 후 재요청 (CLM-2·새 행) | 201·새 REQUESTED Claim |

---

## 4. 결정 라운드 결과 (Q1~Q10 확정·외부 검토 1·2차 흡수)

> S급·외부 검토 1·2차 + 자체 실측 12건 흡수 완료·D-89 박제 (decisions.md). 사용자 4 기조 정합 추천안 전건 채택.

### Q1. OrderItem 사전 검증 + active Claim 차단 = α 사전 검증 + existsActiveByOrderItemId [확정]
- race condition 대응·CLM-5 신설 정합 (활성 Claim 최대 1개·활성 = REQUESTED 또는 APPROVED)
- D-88 Q3 OrderItem.item_status 동기화 양방 정책 보조
- 외부 검토 1차 #1 흡수 (existsActiveClaim 보강 권장 → CLM-5 신설 명문화)

### Q2. idempotency 키 = α CLM-2 자연 적용 [확정]
- CLM-2 "REJECTED 재요청 = 새 Claim 행" 자체가 멱등 모델
- X-Idempotency-Key 헤더 신설 불필요 (D-05·D-88 Q4 정합·기조 4)

### Q3. IllegalStateException HTTP·예외명 = 422 + ClaimInvalidStateException 재활용 [확정]
- HTTP: 422 (D-50 SoT 정합·외부 검토 1차 #2 반박·2차 §1 422 유지 동의)
- 예외: 기존 ClaimInvalidStateException 재활용 (Track 5 박제·CLM-3 책임·RuntimeException + String message)
- 외부 검토 2차 "Conflict→Invalid 권장" 우연 정합·신설 폐기 (기조 4)
- Javadoc 보강: CLM-3 책임 + canTransitionTo 위반 케이스 추가 명시
- 실측 12: ClaimInvalidStateException Track 5 기 박제·RefundService.initiate 사용처 정합 확인

### Q4. endpoint 개수 = α 3개 [확정]
- POST `/api/v1/claims` (Claim 요청)
- GET `/api/v1/claims/{claimPublicId}` (단건 조회)
- GET `/api/v1/claims` (본인 목록·Pageable)
- Track 11 RETURN/EXCHANGE 확장 시 type 파라미터 추가 자연 확장·URL ClaimType 중립

### Q5. reason_code 검증 = α @ValidEnum + ClaimReasonCode ENUM 6값 [확정]
- 정찰 실측 9: CLAIM_REASON Code 시드 부재 확정 (V1__init.sql INSERT 0건)·WARN-1 해소
- ENUM 6값 (Track 9 CANCEL 한정): BUYER_CHANGED_MIND·DUPLICATE_ORDER·PAYMENT_ISSUE·ORDER_MISTAKE·STOCK_DELAY·OTHER
- Track 11 확장 예정: PRODUCT_DEFECT·DAMAGED_ON_ARRIVAL·WRONG_PRODUCT·DELIVERY_DELAY
- 외부 검토 1차 #3 부분 수용 (ENUM 신설·Code 시드 Track 11 이연)
- 외부 검토 2차 §2 ENUM 값 보정 흡수 (ORDER_MISTAKE·STOCK_DELAY 교체)

### Q6. ClaimType 처리 = α DTO 자유 type + Service CANCEL 차단 [확정]
- DTO record 필드 ClaimType 자유 입력·Service 진입부 CANCEL 외 ClaimInvalidStateException throw (422)
- Track 11 RETURN/EXCHANGE 진입 시 DTO 무영향·Service 차단 단락만 제거

### Q7. ClaimResponse 필드 = reasonDetail 노출·requestedBy 미노출 [확정]
- reasonDetail: 사용자 자유 입력·본인 조회 시 노출 정합 (UX)
- requestedBy: 내부 Long buyerId·외부 노출 시 사용자 식별 누출 차단 (보안·기조 4)
- 외부 검토 1차 #4 흡수 (reasonDetail 노출 권장)

### Q8. 권한 검증 위치 = α Service 진입부 (OrderItem 2단계 조회) [확정]
- D-40 β′ 정합 (관심사 집중·Controller HTTP 변환만)
- 2단계 조회: OrderItemRepository.findOrderIdById(orderItemId) → OrderRepository.findById(orderId) → order.getBuyerId() 비교
- 실측 4: @Query JOIN 우회·OrderItem.order 필드 @Getter(AccessLevel.NONE)·WARN-2 해소

### Q9. LT-02 패턴 (ClaimIntegrationTest) = α SoT 정합 try-finally 명시 [확정·신규 의제]
- ClaimIntegrationTest는 SET FOREIGN_KEY_CHECKS 사용 시 try-finally 명시 (LT-02 SoT 정합)
- 실측 11: CheckoutIntegrationTest는 @Transactional rollback 의존·LT-02 미적용·별도 트랙 보정 후보 (백로그)
- 라이브 트랩 차단 (기조 1 운영 용이성)

### Q10. ClaimSummaryResponse 신설 = α 신설 [확정·신규 의제]
- 목록 경량: publicId·type·status·reasonCode·requestedAt 필드 한정 (페이로드 절감)
- 단건 상세: ClaimResponse (Q7 정합·reasonDetail 노출·processedAt 포함)
- BuyerOrderController OrderSummaryResponse·OrderResponse 패턴 정합

### 신규 결정 3건 (D-89 박제)

#### CLM-5 신설 (invariants §2.13)
동일 OrderItem 활성 Claim 최대 1개·활성 = REQUESTED 또는 APPROVED·Service 사전 가드 (ClaimRepository.existsActiveByOrderItemId).

#### DomainEvent 추상 클래스 폐기
정찰 실측 1: OrderPlaced·PaymentCompleted·RefundCompleted 전건 record 패턴·DomainEvent 추상 클래스 부재. ClaimRequested/Approved/Rejected 동일 record 패턴 채택 (occurredAt = LocalDateTime). correlationId/eventId Track 12+ Observability 일괄 도입.

#### D-50 매트릭스 별도 트랙 이연
D-50 본문 정정·RFC 7231·Stripe·PayPal 사례 재평가는 별도 트랙·본 PR 범위 외.

### 외부 검토 흡수 요약

| 차수 | 건수 | 수용 | 반박 | 부분 수용 |
|---|---|---|---|---|
| 1차 | 9 | 5 | 1 (Q3 422 유지) | 3 (Q5 ENUM·5b correlationId·Q7 reasonDetail) |
| 2차 | 4 + 횡단 1 | 5 | 0 | 0 |
| 실측 | 12 | 12 | 0 | 0 |

상세는 D-89 박제 본문 참조.

---

## 5. WARN

### WARN-1. CLAIM_REASON Code 시드 미존재 [해소 필요·결정 라운드 Q5 연계]

**내용**: claim.reason_code 컬럼 DDL 주석 "B/CLAIM_REASON Code 정합(③·ENUM 미적용)"이지만, V1~V5 마이그레이션 전건에 CLAIM_REASON CodeGroup + Code 시드 INSERT 없음.

**영향**: 
- Q5 β (Service 검증) 선택 시 모든 Claim 요청 실패
- Q5 α 선택 시 형식 검증만으로 충분·시드 불요

**해소 방안**:
- α 채택 시: PR-B에서 @Pattern("^[A-Z0-9_]{1,50}$") 등 형식 검증만 적용·시드 신설 불요 → 정찰 내 해소 가능
- β 채택 시: Flyway V6 마이그레이션으로 CLAIM_REASON 시드 신설 필요 → PR-B 범위 확장

**상태**: Q5 결정 대기

### WARN-2. OrderItem buyer_id 조회 경로 미존재 [해소 필요·Service 권한 검증 필요]

**내용**: ClaimService.request에서 "X-Buyer-Id = OrderItem 소유자" 검증이 필요하나, 현재 OrderItem에 `buyer_id` 컬럼이 없음. `order_item.order_id → order.buyer_id` 조인 경로로만 buyerId 접근 가능.

**실측**: 
- `OrderItem` 엔티티: `order` 필드 `AccessLevel.NONE` (`@Getter(AccessLevel.NONE)`)·외부에서 order.getBuyerId() 직접 접근 불가
- `OrderItemRepository.findOrderIdById(Long id)` 존재 → `Order.buyer_id` 접근은 `OrderRepository.findById(orderId)` 경유

**해소 방안**:
- ClaimService.request: `orderItemRepository.findOrderIdById(orderItemId)` → `orderRepository.findById(orderId)` → `order.getBuyerId()` 비교·2단계 조회
- 또는 `@Query` 신설로 `order_item JOIN order WHERE order_item.id = :id` 단일 쿼리 (PR-B 구현 시 결정)

**상태**: 구현 시 2단계 조회 패턴 활용 권장·심각한 블로커 아님

### WARN-3. LT-02 try-finally 패턴 (ClaimIntegrationTest 신설 대상) [해소·Q9 결정으로 자연 해소]

**내용**: 정찰 실측 11에서 CheckoutIntegrationTest가 LT-02 미적용 (@Transactional rollback 의존) 발견. ClaimIntegrationTest 신설 시 LT-02 SoT 정합 채택 여부 의제화.

**해소**: Q9 α 채택·ClaimIntegrationTest는 SET FOREIGN_KEY_CHECKS 사용 시 try-finally 명시. CheckoutIntegrationTest 자체 보정은 별도 트랙 후보 (백로그).

**상태**: Q9 결정으로 해소·구현 시 적용 의무.

---

### WARN 일괄 해소 표 (Q1~Q10 결정·외부 검토 흡수 후)

| WARN | 상태 | 해소 결정 |
|---|---|---|
| WARN-1 CLAIM_REASON Code 시드 미존재 | 해소 | Q5 α (ClaimReasonCode ENUM 6값 신설·시드 불요) |
| WARN-2 OrderItem buyer_id 조회 경로 | 해소 | Q8 α (Service 2단계 조회·OrderItemRepository.findOrderIdById 실측) |
| WARN-3 LT-02 try-finally 패턴 | 해소 | Q9 α (ClaimIntegrationTest LT-02 SoT 정합 명시) |

---

## 6. 영향 범위 (확정·21건)

> WARN 전건 해소·Q1~Q10 결정 확정·D-89 박제 기준 확정.

### 6.1 신규 파일 (12건)

| # | 경로 | 책임 |
|---|---|---|
| 1 | claim/enums/ClaimReasonCode.java | ENUM 6값 (Q5) |
| 2 | claim/controller/BuyerClaimController.java | 3 endpoint (Q4) |
| 3 | claim/controller/request/ClaimRequestRequest.java | DTO record·@Valid·toCommand(buyerId, requestedAt) |
| 4 | claim/controller/request/ClaimRequestCommand.java | Service Command record |
| 5 | claim/controller/response/ClaimResponse.java | 단건 상세·reasonDetail 노출·requestedBy 미노출 (Q7) |
| 6 | claim/controller/response/ClaimSummaryResponse.java | 목록 경량 (Q10) |
| 7 | claim/event/ClaimRequested.java | record·payload claimId·claimPublicId·orderItemId·claimType·status·buyerId·occurredAt |
| 8 | claim/event/ClaimApproved.java | record·payload claimId·claimPublicId·orderItemId·claimType·status·occurredAt |
| 9 | claim/event/ClaimRejected.java | record·payload claimId·claimPublicId·orderItemId·claimType·status·occurredAt |
| 10 | test/.../claim/service/ClaimServiceTest.java | Service 단위 (T16 이벤트 발행 검증 포함) |
| 11 | test/.../claim/controller/BuyerClaimControllerTest.java | @WebMvcTest (T14 malformed publicId·X-Buyer-Id 헤더 검증) |
| 12 | test/.../claim/integration/ClaimIntegrationTest.java | @SpringBootTest + Testcontainers MariaDB 11.4·LT-02 try-finally (Q9)·E2E 13건 |

### 6.2 수정 파일 (6건)

| # | 경로 | 변경 |
|---|---|---|
| 1 | claim/entity/Claim.java | approve(processedAt)·reject(reason, processedAt) 메서드 신설·transitionTo 내부 ClaimInvalidStateException 변환 (Track 5 기 throw) |
| 2 | claim/service/ClaimService.java | request·approve·reject 3 메서드 신설·OrderItem 2단계 조회·existsActiveByOrderItemId 차단·이벤트 발행 (save→publish·D-29) |
| 3 | claim/repository/ClaimRepository.java | existsActiveByOrderItemId(orderItemId)·findAllByRequestedBy(buyerId, Pageable) 신설 |
| 6 | order/repository/OrderItemRepository.java | findByPublicId(String publicId) 읽기 메서드 1건 신설·D-64·D-65 Service 진입점 publicId 해소 패턴 정합·스키마/엔티티 무변경 |
| 4 | claim/exception/ClaimInvalidStateException.java | Javadoc 보강 (CLM-3 책임·canTransitionTo 위반 케이스 명시·신설 폐기 사유 박제) |
| 5 | common/web/GlobalExceptionHandler.java | ClaimNotFoundException 404·ClaimInvalidStateException 422 매핑·CODE 상수 2건·@ExceptionHandler 2개 추가 |

### 6.3 문서 (2건)

| # | 경로 | 변경 |
|---|---|---|
| 1 | docs/architecture-baseline/invariants.md | §2.13 CLM-5 신설 1행 |
| 2 | docs/track-9/pr-b/recon-report.md | §4·§5·§6·§7·§8 갱신 (외부 검토 흡수·WARN 해소·진입 조건 [x]) |

### 6.4 인프라 (1건)

| # | 경로 | 변경 |
|---|---|---|
| 1 | .gitignore | docs/track-*/handover.md 패턴 추가 (PR-B 동반 커밋) |

**총 21건** (신규 12·수정 6·문서 2·인프라 1).

### 6.5 영향 0건 (확정)

OrderItemStatus.java·OrderItem.java·Order.java·state-machine.md·aggregate-boundary.md·live-traps.md·DDL (enum 값 변경 없음·신규 Entity 0건). PR-C 핸들러·OrderStatusResolver 재계산 hook 본 PR 무영향. Order Aggregate는 OrderItemRepository.findByPublicId 읽기 메서드 1건 신설(§6.2 수정 6) 외 엔티티·스키마·DDL 무변경 (스키마/엔티티 무변경·트랩 해소).

### 6.6 LT 처치 의무 (확정)

| LT | 처치 의무 | 적용 위치 |
|---|---|---|
| LT-01 CHAR public_id @JdbcTypeCode | 없음 (신규 Entity 0건) | — |
| LT-02 FK_CHECKS try-finally | 있음 (Q9 α) | ClaimIntegrationTest |
| LT-03 @SQLRestriction @Entity 직접 | 없음 (신규 Entity 0건) | — |

---

## 7. 외부 검토

S급·외부 검토 권장 (D-88 가드 2 라벨 "PR-B = S급·외부 검토 권장").

외부 검토 대상 항목:
- Q1 사전/사후 검증 아키텍처 트레이드오프
- Q3 IllegalStateException HTTP 분류 (409 vs 422)·감사 추적 패턴
- Q5 reason_code 검증 전략·COD-3 정합 수준
- Q7 ClaimResponse 필드 목록·security 관점 (requestedBy 외부 노출)
- 이벤트 payload 설계 (claimId·orderItemId·claimType 충분성)
- E2E 시나리오 충실도·누락 케이스 검토
**상태**: 외부 검토 1차 9건·2차 4건 + 횡단 1건 흡수 완료 (D-89 박제·§4 외부 검토 흡수 요약 표 참조).

---

## 8. 진입 조건 (체크리스트)

- [x] WARN-1 해소 (Q5 α 결정·ClaimReasonCode ENUM 6값 신설·Code 시드 불요)
- [x] WARN-2 해소 (Q8 α 결정·Service 2단계 조회·OrderItemRepository.findOrderIdById 실측)
- [x] WARN-3 해소 (Q9 α 결정·ClaimIntegrationTest LT-02 try-finally 명시)
- [x] Q1~Q10 결정 라운드 의제 확정 (D-89 박제 완료)
- [x] 신설 대상 목록 박제 완료 (§6 영향 범위 21건 확정)
- [x] 외부 검토 의견 흡수 완료 (1차 9건·2차 4건 + 횡단 1건·실측 12건)
- [x] CLM-5 신설 본문 박제 (invariants.md §2.13)
- [x] DomainEvent 폐기 결정 박제 (D-89)
- [x] ClaimInvalidStateException 재활용 결정 박제 (D-89·신설 폐기·기조 4)

**진입 가능 상태**: PR-B 브랜치 `feat/track-9-pr-b` 생성·구현 진입 가능 (가드 5 사전 통지 21건 일괄·승인 후).

---

## 9. 관련 결정·SoT

| 항목 | § |
|---|---|
| D-88 | Q1~Q7·PR-B 범위·가드 2 라벨 |
| D-39 | X-Buyer-Id stub·누락 401·형식 400 |
| D-40 | BuyerClaimController 패키지·URL 액터 중립 |
| D-41 | DTO request·response 하위 분리·toCommand/from 패턴 |
| D-50 | Validation 계층 매트릭스·400/422/409 분류 |
| D-29 | save→publish 이벤트 발행 순서 |
| D-75 | 이벤트 핸들러 AFTER_COMMIT·DB 쓰기 REQUIRES_NEW |
| D-01 | Claim→Order 갱신은 이벤트 경유·OrderItem.id 외부 참조 |
| D-05 | CLM-2·REJECTED 재요청 = 새 행 |
| state-machine §2 | Claim.status 전이 매트릭스 |
| state-machine §3 | OrderItem.item_status 12값·REQUESTED 진입 조건 |
| invariants §2.13 | CLM-1~CLM-4 |
| aggregate-boundary §2.5 | Claim Aggregate Root·OrderItem ID 외부 참조 |
| expected-spec §1.2 | Claim 요청 API OOS → Track 9 진입 |
| pr-a/recon-report §2.4·§2.5 | Claim.java 메서드 부재·ClaimService 1건만 현황 |
| live-traps LT-01~03 | LT-02 FK_CHECKS try-finally·LT-01 @JdbcTypeCode·LT-03 @SQLRestriction·신규 Entity 없음→처치 의무 없음 |

---

## 10. 다음 단계

1. Q1~Q8 결정 라운드 진행 (회사 정책 + 아키텍처 결정·외부 검토 흡수 옵션)
2. WARN-1·WARN-2 해소 방안 확정
3. 결정 확정 후 구현 PR 진입 (브랜치 feat/track-9-pr-b):
   - Claim.java approve/reject 메서드 신설
   - ClaimService request/approve/reject 신설
   - BuyerClaimController + DTO 신규
   - 도메인 이벤트 ClaimRequested/Approved/Rejected 신규
   - GlobalExceptionHandler ClaimNotFoundException·IllegalStateException 매핑 추가
   - E2E 통합 테스트 (ClaimIntegrationTest) 신규
4. 구현 완료 후 Track 9 PR-C 진입 (S급·이벤트 핸들러·OrderItem 동기화·Order.status 재계산 hook·외부 검토 권장)
