# Track 3 (Payment Mock) — Expected Spec

> **작성 원칙**: Claude.ai가 실 코드·테스트를 참조하지 않고, 설계 문서(decisions.md D-27~D-36, invariants.md, domain-events.md)와 합의된 결정만으로 기술한 "기대 사양". recon-report.md에서 1:1 비교 대상.

## 범위
Track 3 = Payment Aggregate Mock 구현. PG 실연동 없음. Flyway V3로 payment 스키마 도입.

## 핵심 Invariant·동작 계약

### 1. PAY-3a — attempt_key UNIQUE 제약
- `payment.payment_attempt_key`는 전역 UNIQUE.
- 동일 attempt_key로 중복 Payment 생성 시 DB 레벨에서 거부.

### 2. PAY-3b — attempt_key 형식
- prefix: `pat_`
- prefix 뒤 ULID(26자) 부여 (`public_id` 정책과 동형).
- 생성 시점: 애플리케이션 레이어 (DB 자동 생성 아님).

### 3. PaymentStatus 전이
- 초기 상태: `PENDING`
- 허용 전이: `PENDING → PAID`, `PENDING → FAILED`, `PAID → CANCELLED`
- **PENDING → CANCELLED 직접 전이 없음** — CANCEL 콜백 × PENDING은 D-34에 따라 `PENDING → FAILED`(실패 코드 `CANCELLED_BEFORE_PAYMENT`)로 처리.
- `PAID` 이후 CANCELLED는 Refund 트랙 연계.
- 전이 규칙은 `canTransitionTo(next)` 메서드(코드 레이어)에서 강제 (D-12·D-34).

### 4. 이벤트 발행 순서 — pullDomainEvents → save → publish (D-29)
- Payment 상태 변경 후: `pullDomainEvents()` → `Repository.save` → `ApplicationEventPublisher.publishEvent`.
- 명시적 flush 없음 — `@EventListener` 동기·동일 트랜잭션에서 1차 캐시 적중으로 정합성 확보.
- 트랜잭션 커밋 전 publish (동기). `@TransactionalEventListener` 미채택 (D-29).

### 5. PaymentCompleted / PaymentFailed 이벤트
- `PaymentCompleted`: PENDING → PAID 전이 시 발행. payload: `paymentId`·`orderId`·`amount`·`pgTransactionId`·`occurredAt`.
- `PaymentFailed`: PENDING → FAILED 전이 시 발행. payload: `paymentId`·`orderId`·`failureCode`·`occurredAt`.
- `occurredAt` 필드는 D-30 "사실 통지·멱등 키" 원칙 반영.
- `PAID → CANCELLED` 전이 이벤트는 Refund 트랙(Track 5)에서 별도 정의.

### 6. OrderEventHandler — 수신 동작 (Track 3 범위)
- `PaymentCompleted` 이벤트 수신 → `OrderService.markPaid` 위임 (Order 상태 갱신).
- `OrderPlaced` 수신 핸들러는 **Track 7 이연** — Track 3에서 결제 시작은 `PaymentService.initiate` 직접 호출 (외부 API 진입점).
- `OrderPlaced` 이벤트 발행 자체는 Track 2에서 완료됨 (`OrderPlaced` javadoc: "핸들러는 Track 7 이연·소비측은 publicId/orderId로 재조회").

### 7. Idempotency — PaymentService.initiate (D-28)
- `attempt_key`는 **서버(Service) 생성** — `PaymentInitiateRequest`에 클라이언트 전달 필드 없음.
- 중복 차단은 `order_id` 기준:
  - PAID 행 존재 → `PaymentAlreadyCompletedException`
  - 미만료 PENDING 행 존재 → `PaymentInProgressException`
  - 만료된 PENDING 행 존재 → 신규 attempt_key로 재시도 허용
- PAY-3a UNIQUE는 동일 attempt_key 중복 INSERT를 DB 레벨에서 추가 보호.

### 8. Mock PaymentGateway
- `PaymentGateway` 인터페이스 + `MockPaymentGateway` 구현체.
- `requestPayment()`는 **결제창 URL 반환** — 즉시 성공/실패 결과 반환 아님.
- 성공/실패는 별도 `PaymentWebhookController` POST 콜백으로 트리거 (실 PG 플로우 시뮬레이션).
- `pg_provider="MOCK_PG"` 고정값. `pg_tid`는 콜백 시 주입.

## Flyway
- V3 마이그레이션: payment 테이블 + (order_id, payment_attempt_key) UNIQUE INDEX + status INDEX.
- V1·V2 호환 유지 (회귀 금지).

## 테스트 커버리지 기대
- Payment 엔티티 단위 테스트 (상태 전이·invariant)
- PaymentRepository @DataJpaTest (UNIQUE 제약 검증)
- PaymentService idempotency·initiate·callback 흐름
- OrderEventHandler 통합 동작
- PaymentWebhookController 콜백 진입점
- 총 테스트 수: 90건 이상 (Track 2 기존 81 + Track 3 추가분)

## 범위 외 (recon에서 OUT-OF-SCOPE로 분류 예상)
- 실 PG 연동·서명 검증
- Refund 처리 (Track 5)
- Settlement 정산 (별도 트랙)
- 결제 수단별 분기 로직 상세 (CARD vs VBANK 등)

> **이력**: v1 (Claude.ai·메모리 기반) → v2 (recon-report 반영·decisions.md 정합 보정·2026-06-27).