# Track 6 Integration Test — Gate 정찰 보고서

```
정찰일   : 2026-06-28
정찰 범위: docs/architecture-baseline/gate-conditions.md §1·§2·§3 측정 항목 1:1 대조
정찰자   : Claude Code Sonnet 4.6 (read-only · 코드·테스트·SoT 문서 수정 없음)
브랜치   : feat/track-6-integration-test (main HEAD 4c862ee 기준)
SoT      : docs/architecture-baseline/gate-conditions.md
```

---

## §1. 구조 Gate 정찰

### 측정 항목

| # | Gate 조건 | 측정 방법 | 결과 |
|---|---|---|---|
| S-1 | Entity 수정율 ≤10% (≤1.2건) | git log 전 Entity 파일 | ⚠ 조건부 PASS |
| S-2 | FK 재설계 0 | V3·V4·V5 REFERENCES grep | ✅ PASS |
| S-3 | Aggregate 경계 변경 0 | aggregate-boundary.md git log | ✅ PASS |

### S-1 Entity 수정율 상세

**현재 Track 2~5 산출 도메인 Entity (11건)**

| 패키지 | 파일 | 생성 Track | 후속 수정 |
|---|---|---|---|
| order.entity | Order.java | Track B (1664039) | Track 4 (0da0c8f) — markOrdered() 추가 |
| order.entity | OrderItem.java | Track B (1664039) | 없음 |
| order.entity | OrderShippingSnapshot.java | Track B (1664039) | 없음 |
| payment.entity | Payment.java | Track 3 (6123115) | 없음 |
| product.entity | Product.java | Track 4 (0da0c8f) | 없음 |
| product.entity | ProductVariant.java | Track 4 (0da0c8f) | 없음 |
| seller.entity | Seller.java | Track 4 (0da0c8f) | 없음 |
| inventory.entity | Inventory.java | Track 4 (0da0c8f) | 없음 |
| checkout.entity | OrderIdempotencyKey.java | Track 4 (0da0c8f) | 없음 |
| claim.entity | Claim.java | Track 5 (692a58a) | 없음 |
| refund.entity | Refund.java | Track 5 (692a58a) | 없음 |

- **Cross-track 수정 발생**: 1건 (Order.java)
- **수정율**: 1/11 ≈ 9.1% → ≤10% ✅ PASS (Gate 임계 ≤1.2건 = 1건 허용)
- ⚠ **카운트 불일치**: Gate 문서는 12건 기대 · 실제 11건
  - 예상 원인: `withdrawn_seller` DDL 테이블(V2)이 JPA Entity로 구현되지 않음 (WithdrawnSeller.java 부재)
  - Track 7 소관일 가능성 높음 · Gate 통과 판단에는 직접 영향 없음 (분모 차이만)

### S-2 FK 재설계 상세

V3 (`V3__payment_track3.sql`)·V4 (`V4__order_idempotency_key.sql`)·V5 (`V5__refund_constraints.sql`) 전체에
`FOREIGN KEY` / `REFERENCES` 키워드 0건 확인 — FK 구조 변경 없음.

### S-3 Aggregate 경계 변경 상세

`aggregate-boundary.md` 마지막 수정 커밋: `475322a` (docs/pr-05-architecture-baseline-enhance, Track 2 시작 이전).
Track 2~5 어떤 커밋에서도 해당 파일 미수정 — Aggregate 경계 변경 0건.

---

## §2. 기능 Gate 정찰

### 측정 항목

| # | Gate 조건 | 현재 테스트 커버 | 결과 |
|---|---|---|---|
| F-1 | 주문 생성 E2E | CheckoutIntegrationTest (부분 커버) | ❌ GAP |
| F-2 | 결제 성공 E2E | CheckoutIntegrationTest (부분 커버) | ❌ GAP |
| F-3 | 환불 성공 E2E | RefundWebhookIntegrationTest (부분 커버) | ⚠ GAP (부분) |
| F-4 | OrderStatus canTransition 100% | OrderStatusTest — DDL 드리프트만 | ⚠ 설계 결정 GAP |
| F-5 | PaymentStatus canTransition 100% | PaymentStatusTest (4×4 전 조합) | ✅ PASS |
| F-6 | ClaimStatus canTransition 100% | ClaimStatusTest (4×4 전 조합) | ✅ PASS |
| F-7 | OrderItemStatus canTransition 100% | OrderItemStatusTest (12×12 전 조합) | ✅ PASS |
| F-8 | RefundStatus canTransition 100% | RefundStatusTest (3×3 전 조합) | ✅ PASS |

### GAP-E2E-1: 주문 생성 E2E

Gate 조건: `User 로그인 → Cart 추가 → Order 생성 → OrderItem 다건 → 정상 응답`

현재 커버 (`CheckoutIntegrationTest.checkout_happyPath()`):
- ✅ POST /api/v1/orders → 201 · Location · Payment.PENDING
- ❌ User 로그인 단계 없음 (X-Buyer-Id 헤더 모의 인증)
- ❌ Cart 추가 단계 없음 (Cart 도메인 미구현)
- ❌ OrderItem **다건** 미검증 (CREATE_BODY는 단일 variant)

**GAP 규모**: E2E 3단계 미달 · Cart 도메인 자체가 Track 7+ 소관

### GAP-E2E-2: 결제 성공 E2E

Gate 조건: `Order 생성 → Payment.PENDING → MockPaymentGateway 콜백 → Payment.PAID`

현재 커버:
- ✅ Order 생성 → Payment.PENDING: `CheckoutIntegrationTest.checkout_happyPath()` (@SpringBootTest 실DB)
- ❌ MockPaymentGateway 콜백 → Payment.PAID: `PaymentCallbackTest` 존재하나 Mockito 단위 테스트 (실 DB·Flyway 없음)
- ❌ @SpringBootTest 실DB 기반 결제 콜백 → PAID 전이 E2E 없음

**GAP 규모**: 신규 @SpringBootTest 통합 테스트 1건 필요
- `PaymentWebhookIntegrationTest` — POST /api/webhooks/payments → Payment.PAID 전이 검증

### GAP-E2E-3: 환불 성공 E2E (부분)

Gate 조건: `Payment.PAID → Claim 생성 → Refund.PENDING → MockPaymentGateway 환불 → Refund.COMPLETED → Claim.COMPLETED`

현재 커버 (`RefundWebhookIntegrationTest.webhook_success_endToEnd()`):
- ✅ Refund.PENDING → 콜백 → COMPLETED: 실 DB 커밋 경로 검증
- ✅ RefundCompleted 이벤트 → Claim.COMPLETED: 실 DB 커밋 후 AFTER_COMMIT 핸들러 검증
- ✅ RefundCompleted 이벤트 → Payment.CANCELLED: 실 DB 검증
- ❌ `Payment.PAID → Claim 생성` 단계: `seed()`로 수동 시딩 (ClaimService.approve() 미구현·Track 6+ 소관)
- ❌ 전 E2E 연속 흐름 단일 테스트 없음

**GAP 규모**: ClaimService.approve() 구현 + ClaimApprovedHandler → RefundService.initiate 체인이 구현되어야 완전 E2E 달성 가능 (Track 6 핵심 구현 소관)

### GAP-SM-1: OrderStatus canTransitionTo 미존재

Gate 조건: `Payment·Claim·OrderItem·Order·Refund 5 enum canTransition 메서드 100% 분기 테스트`

- `OrderStatus`에는 `canTransitionTo` 메서드가 없음 (의도적 설계)
- 코드 주석: `canTransitionTo 부재 사유: OrderStatusResolver가 OrderItem 상태 집합으로부터 파생·갱신한다 (ORD-2)`
- `OrderStatusTest`는 DDL 정합(8값 존재) 검증만 수행

**결정 필요**: Gate 조건 문구 보정 (`OrderStatus 제외` 명시) vs `canTransitionTo` 메서드 추가

---

## §3. 기술 Gate 정찰

### 측정 항목

| # | Gate 조건 | 현재 상태 | 결과 |
|---|---|---|---|
| T-1 | Flyway clean+migrate 성공 | Testcontainers 기반 migrate 검증됨 | ⚠ 부분 PASS |
| T-2 | Transaction rollback 검증 | 명시적 rollback 테스트 없음 | ❌ GAP |
| T-3 | API endpoint ≥10 | 6개 존재 | ❌ GAP (4개 부족) |
| T-4 | @SpringBootTest 통과율 100% | 156/156 PASS (Track 5 커밋 692a58a) | ✅ PASS |

### T-1: Flyway clean+migrate 상세

Gate 조건: `docker compose down -v → up → Flyway V1·V2 자동 적용·오류 0`
(Gate 문서 기준 V1·V2이나 현재 V5까지 존재 — 실질 측정은 V1~V5 전 적용)

현재 검증:
- ✅ Testcontainers 기반 `@SpringBootTest`에서 Flyway V1~V5 자동 migrate 수행됨
- ✅ `ZslabMallApplicationTests.contextLoads()` — Flyway + validate 통과 검증
- ❌ `docker compose down -v → up` 시나리오를 코드로 자동 검증하는 CI/테스트 없음 (수동 검증만)

**Gap 성격**: Testcontainers가 사실상 동등한 검증을 제공함. docker compose 명시 자동화 여부는 사용자 판단 필요.

### T-2: Transaction rollback 검증 상세

Gate 조건: `OrderItem 다건 INSERT 중 1건 실패 시 Order 포함 전체 rollback·DB 정합성 검증`

현재 상태:
- `CheckoutIntegrationTest`는 `@Transactional`로 전체 트랜잭션이 테스트 종료 시 자동 롤백됨 (테스트 클린업 목적)
- 비즈니스 로직 중 1건 실패 → 전체 Order+OrderItem rollback 시나리오 명시적 테스트 없음
- `OrderServiceTest`는 Mockito 단위 테스트 (실 DB rollback 검증 불가)

**GAP 규모**: 신규 @SpringBootTest 또는 @DataJpaTest 기반 rollback 검증 1건 필요

### T-3: API Endpoint 카운트 상세

Gate 조건: `Track 4 Order API·Track 5 Refund Flow API 합산 ≥10 endpoint`

현재 endpoint (6개):

| Controller | Method | Path |
|---|---|---|
| BuyerOrderController | POST | /api/v1/orders |
| BuyerOrderController | GET | /api/v1/orders/{orderPublicId} |
| BuyerOrderController | GET | /api/v1/orders |
| BuyerOrderController | POST | /api/v1/orders/{orderPublicId}/payments |
| PaymentWebhookController | POST | /api/webhooks/payments |
| RefundWebhookController | POST | /api/webhooks/refunds |

**GAP**: 4개 부족. 달성 가능 후보:
- GET /api/v1/payments/{paymentId} — 결제 단건 조회
- GET /api/v1/claims (또는 POST) — 청구 관련 API
- GET /api/v1/orders/{orderId}/refunds — 환불 목록
- GET /api/v1/refunds/{refundId} — 환불 단건 조회

단, 이 endpoint들은 설계 확정(decisions.md)이 선행되어야 Track 6 구현 가능.

### T-4: @SpringBootTest 통과율 상세

최종 테스트 결과 (Track 5 커밋 692a58a 기준):
```
156 tests, 0 failures, 0 errors, 0 skipped
```

테스트 분포:
- 단위 테스트 (Mockito): OrderStatusTest·OrderServiceTest·PaymentStatusTest 등
- enum 매트릭스: PaymentStatus(4×4)·OrderItemStatus(12×12)·ClaimStatus(4×4)·RefundStatus(3×3)
- @DataJpaTest (실 MariaDB): OrderRepositoryTest·OrderItemRepositoryTest·OrderShippingSnapshotRepositoryTest·PaymentRepositoryTest
- @SpringBootTest (실 MariaDB·Flyway): ZslabMallApplicationTests·CheckoutIntegrationTest·RefundWebhookIntegrationTest
- @WebMvcTest: BuyerOrderControllerTest·PaymentWebhookControllerTest

---

## §4. 갭 요약 및 보강 작업 추정

### GAP 목록

| ID | 구분 | Gate 항목 | 내용 | 난이도 |
|---|---|---|---|---|
| GAP-E2E-1 | §2 기능 | 주문 생성 E2E | Cart 미구현·다건 OrderItem·로그인 | 높음 (Cart 도메인 부재) |
| GAP-E2E-2 | §2 기능 | 결제 콜백 E2E | @SpringBootTest 결제 콜백→PAID 미존재 | 낮음 (테스트 1건 추가) |
| GAP-E2E-3 | §2 기능 | 환불 E2E 완전 | ClaimService.approve 미구현·연속 흐름 없음 | 중간 (구현 + 테스트) |
| GAP-SM-1 | §2 기능 | OrderStatus canTransition | 설계 의도적 부재 (ORD-2) · Gate 조건 문구 불일치 | 낮음 (Gate 조건 보정 또는 메서드 추가) |
| GAP-TECH-1 | §3 기술 | rollback 검증 | 명시적 rollback 시나리오 테스트 없음 | 낮음 (테스트 1건 추가) |
| GAP-TECH-2 | §3 기술 | API endpoint ≥10 | 현재 6개·4개 부족 | 중간 (신규 endpoint + 테스트) |

### 참고 정보 (GAP 아님)

| ID | 내용 |
|---|---|
| INFO-1 | Entity 카운트 11 vs Gate 기대 12 — WithdrawnSeller JPA entity 미구현 (V2 DDL 테이블만 존재) |
| INFO-2 | Flyway docker compose 자동 검증 없음 — Testcontainers가 동등 검증 제공 중 |

### 보강 작업 추정

| 분류 | 작업 | 예상 PR 단위 |
|---|---|---|
| **즉시 가능 (테스트 추가)** | GAP-E2E-2 결제 콜백 통합 테스트 1건 | PR-A |
| **즉시 가능 (테스트 추가)** | GAP-TECH-1 rollback 시나리오 테스트 1건 | PR-A (병합 가능) |
| **설계 결정 선행** | GAP-SM-1 Gate 조건 보정 또는 OrderStatus canTransitionTo 추가 여부 | 사용자 결정 후 PR-B |
| **구현 선행 필요** | GAP-E2E-3 ClaimService.approve() + ClaimApprovedHandler + 테스트 | PR-C |
| **설계 확정 선행** | GAP-TECH-2 API endpoint 4개 추가 (GET /payments/{id} 등) | PR-D |
| **Track 7 소관 가능** | GAP-E2E-1 Cart·로그인 E2E (Cart 도메인 부재) | 후속 트랙 |

**신규 테스트 추정: 최소 3~6건** (GAP-E2E-2 1건·GAP-TECH-1 1건·GAP-E2E-3 2~3건·GAP-TECH-2 endpoint별 1건)

---

## §5. PR 분할 권고

사용자 결정 라운드 입력용 의견 (최종 결정은 사용자):

### 권고 A: 즉시 통과 가능 갭만 먼저 (PR-A·PR-B)

```
PR-A: 테스트 추가 (즉시 가능)
  - GAP-E2E-2: PaymentWebhookIntegrationTest 신규 (결제 콜백→PAID @SpringBootTest)
  - GAP-TECH-1: Transaction rollback 시나리오 테스트 신규

PR-B: Gate 조건 보정 (설계 결정 필요)
  - GAP-SM-1: gate-conditions.md §2 OrderStatus 항목 보정
              또는 OrderStatus.canTransitionTo 추가 + 테스트
```

이 두 PR 후에도 **GAP-E2E-1·GAP-E2E-3·GAP-TECH-2는 미해소** → Gate 미통과 유지.

### 권고 B: Track 6 핵심 구현 병행 (PR-A·PR-B·PR-C·PR-D)

```
PR-C: ClaimService.approve() + ClaimApprovedHandler + RefundService.initiate 체인 구현
      → GAP-E2E-3 해소

PR-D: 신규 API endpoint 4개 설계 확정 후 구현
      → GAP-TECH-2 해소
      (설계 결정 decisions.md 선행 필요)
```

**GAP-E2E-1 (Cart E2E) 은 권고 A·B 모두에서 Track 7 이연 권고** — Cart 도메인 자체가 Track 7 소관.
Gate 조건 §2 "Cart 추가" 단계도 Gate 문서 수정으로 현실화하는 방향 검토 가능.

---

## §6. 종합 판정

```
Gate §1 구조 : PASS (S-1 조건부·S-2·S-3 PASS)
Gate §2 기능 : GAP-3건 + 설계 결정 1건
Gate §3 기술 : GAP-2건 + 부분 PASS 1건 + PASS 1건

종합 판정: GAP-6건 확인 — Gate 미통과 (Track 7 진입 차단)
```

**해소 우선순위 (사용자 결정 후 실행):**
1. GAP-SM-1 (Gate 조건 문구 보정 — 설계 결정 필요·비용 최소)
2. GAP-E2E-2 + GAP-TECH-1 (테스트 추가 — 즉시 가능)
3. GAP-E2E-3 (ClaimService.approve 구현 — Track 6 핵심)
4. GAP-TECH-2 (API endpoint 설계 확정 + 구현)
5. GAP-E2E-1 (Cart E2E — 후속 트랙 이연 권고)
