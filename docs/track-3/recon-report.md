# Track 3 — Recon Report

> **기준**: `docs/track-3/expected-spec.md` 8개 항목 × 실 코드 1:1 비교.
> **정찰 범위**: `backend/src/main/java/com/zslab/mall/payment/`·`V3__payment_track3.sql`·`order/event/OrderPlaced.java`·테스트 11클래스·`decisions.md` D-27~D-36.
> **판정 원칙**: PASS(기대=실제) / FAIL(기대≠실제) / WARN(의도≈실제·문서 갱신 필요) / OUT-OF-SCOPE(Track 3 범위 외).

---

## 비교표

| # | 항목 | 기대 | 실제 | 판정 |
|---|---|---|---|---|
| 1 | PAY-3a — attempt_key UNIQUE | DB 레벨 전역 UNIQUE | V3 `uk_payment_attempt_key` UNIQUE KEY·RepositoryTest 검증 | **PASS** |
| 2 | PAY-3b — attempt_key 형식 | `pat_` + ULID 26자 = 30자·애플리케이션 생성 | `PublicIdGenerator.generate("pat")` = `pat_<ULID 26>` = CHAR(30)·Service 생성 | **PASS** |
| 3 | PaymentStatus 전이 | PENDING→PAID·FAILED·CANCELLED / PAID·FAILED·CANCELLED 종결 | `canTransitionTo`: PENDING→PAID·FAILED만 허용·PENDING→CANCELLED 없음 / D-34 CANCEL×PENDING = FAILED 처리 | **WARN** |
| 4 | 이벤트 발행 순서 — save → flush → publish | 명시적 flush 필수 | `pullDomainEvents()` → `save()` → `publishEvent()` · flush 명시 없음·D-29 결정에도 flush 미언급 | **WARN** |
| 5 | PaymentCompleted / PaymentFailed 이벤트 | Completed: payment_id·order_id·amount·**paid_at** / Failed: payment_id·order_id·**failure_reason** | Completed: paymentId·orderId·amount·**pgTransactionId**·**occurredAt** / Failed: paymentId·orderId·**failureCode**·occurredAt | **WARN** |
| 6 | OrderEventHandler — OrderPlaced 수신 | OrderPlaced 수신 → PENDING Payment 1건 생성 | `OrderEventHandler`는 `PaymentCompleted` 수신 → `markPaid` 위임·`OrderPlaced` 핸들러 미구현 (`OrderPlaced` javadoc: "핸들러는 Track 7 이연") | **FAIL** |
| 7 | Idempotency — initiate | 동일 attempt_key 재호출 → 기존 Payment 반환 | `PaymentInitiateRequest`에 attempt_key 없음·서버 생성 방식·동일 attempt_key 재호출 시나리오 불가·order_id 기준 중복 차단만 구현 | **WARN** |
| 8 | Mock PaymentGateway | 인터페이스 + Mock·**즉시 성공/실패 결과 반환** | `PaymentGateway` + `MockPaymentGateway` 구현·`requestPayment()`는 결제창 URL 반환·성공/실패는 별도 webhook POST 트리거 방식 | **WARN** |

---

## 판정 기준

- **PASS**: 기대 = 실제
- **FAIL**: 기대 ≠ 실제 (미구현·회귀)
- **WARN**: 기대 ≈ 실제 (의도 일치·동작 가능하나 expected-spec 기술이 실 코드·결정과 다름·문서 갱신 필요)
- **OUT-OF-SCOPE**: Track 3 범위 외

---

## FAIL 상세

### #6 — OrderEventHandler 미구현 (FAIL)

**사유**: 실 `OrderEventHandler`(`payment.handler`)는 `PaymentCompleted` 이벤트를 수신해 `OrderService.markPaid`를 위임한다. `OrderPlaced` 이벤트를 수신해 PENDING Payment를 생성하는 핸들러는 Track 3에 존재하지 않는다.

**증거**: `OrderPlaced` 클래스 javadoc — "소비측(Inventory 예약·CartItem 소비·NotificationLog)은 publicId/orderId로 재조회한다. 핸들러는 Track 7 이연." `OrderEventHandlerTest`는 `onPaymentCompleted` 케이스만 검증.

**영향**: Track 3에서 OrderPlaced 수신 → 자동 Payment 생성 흐름은 없음. 결제 시작은 `PaymentService.initiate`를 직접 호출해야 한다.

**권장 조치**: expected-spec #6을 "Track 7 이연" 또는 "OUT-OF-SCOPE"로 보정. Track 7 진입 시 `OrderPlaced` → Payment 생성 핸들러 구현.

---

## WARN 상세

### #3 — PaymentStatus 전이 그래프 불일치 (WARN)

**사유**: expected-spec이 "PENDING → CANCELLED" 전이를 허용 전이로 기술했으나, `canTransitionTo`는 `PENDING → PAID | FAILED`만 허용. CANCEL 콜백 × PENDING 상태는 D-34 결정에 따라 `PENDING → FAILED`(실패 코드 `CANCELLED_BEFORE_PAYMENT`)로 처리한다.

**근거**: D-34 — "CANCEL × PENDING(결제 미완료 취소) 실패 코드(D-34). PENDING → FAILED 경로."

**권장 조치**: expected-spec #3을 D-34 기반으로 보정.
- 허용 전이: `PENDING → PAID`, `PENDING → FAILED`, `PAID → CANCELLED`
- CANCEL × PENDING = FAILED 처리 명시·PENDING → CANCELLED 직접 전이 삭제

### #4 — 이벤트 발행 순서 기술 불일치 (WARN)

**사유**: expected-spec이 "flush 필수"를 명시했으나, D-29 결정문·실 코드 모두 명시적 flush를 언급하지 않는다. 실제 순서: `pullDomainEvents()` → `save()` → `publishEvent()`. `@EventListener` 동기·동일 트랜잭션에서 1차 캐시 적중으로 기능적 문제는 없다.

**권장 조치**: expected-spec #4에서 "flush 필수" 표현을 삭제하거나 "D-29 기준 save() 직후 publishEvent()" 기술로 대체.

### #5 — 이벤트 payload 필드명 불일치 (WARN)

**사유**:
- `PaymentCompleted`: expected-spec은 `paid_at`이지만 실제 record 필드는 `occurredAt`. 추가 필드 `pgTransactionId` 있음.
- `PaymentFailed`: expected-spec은 `failure_reason`이지만 실제는 `failureCode`. 추가 필드 `occurredAt` 있음.
- 의미는 동일하지만 명칭이 다르며, 추가 필드는 D-30 "사실 통지 원칙·멱등 키" 설계에 따른 것.

**권장 조치**: expected-spec #5 payload 목록을 실 record 필드명으로 동기화 (`paid_at` → `occurredAt`, `failure_reason` → `failureCode`, `pgTransactionId`·`occurredAt` 추가 기재).

### #7 — initiate idempotency 기술 불일치 (WARN)

**사유**: expected-spec은 "동일 attempt_key 재호출 시 기존 Payment 반환"을 기술했으나, `PaymentInitiateRequest`에 attempt_key 필드가 없다 — attempt_key는 서버(Service)가 생성한다. 따라서 클라이언트가 동일 attempt_key로 재호출하는 시나리오 자체가 성립하지 않는다.

**실 구현**: 중복 차단은 `order_id` 기준(PAID 행 존재 → `PaymentAlreadyCompletedException`, 미만료 PENDING 존재 → `PaymentInProgressException`)으로 구현됨. D-28 결정 기준.

**권장 조치**: expected-spec #7 수정 — "동일 attempt_key 재호출" 항목 삭제. "동일 order_id 중복 시도 차단" 기술로 대체.

### #8 — Mock 결과 반환 방식 불일치 (WARN)

**사유**: expected-spec은 "호출 시 즉시 성공/실패 결과 반환"을 기술했으나, `MockPaymentGateway.requestPayment()`는 결제창 URL만 반환한다. 성공/실패는 별도 `PaymentWebhookController` POST 콜백으로 트리거한다.

**기능 검증**: `pg_provider="MOCK_PG"` 고정값으로 채워지며, 콜백을 직접 호출해 성공/실패를 시뮬레이션할 수 있어 Track 3 범위 내 테스트는 가능.

**권장 조치**: expected-spec #8에서 "즉시 성공/실패 결과 반환" 표현 제거. "결제창 URL 반환 + 별도 webhook POST로 성공/실패 트리거" 기술로 대체.

---

## expected-spec 보정 필요성

| # | 항목 | 보정 사유 |
|---|---|---|
| 3 | PaymentStatus 전이 | expected-spec이 D-34와 불일치 — PENDING→CANCELLED 전이 삭제·CANCEL×PENDING=FAILED 명시 |
| 4 | 이벤트 발행 순서 | "flush 필수" 표현 삭제 — D-29 결정·실 코드 모두 명시적 flush 없음 |
| 5 | 이벤트 payload | 필드명 실 record 기준으로 갱신 (paid_at→occurredAt, failure_reason→failureCode) |
| 6 | OrderEventHandler | "Track 7 이연" 명시 또는 OUT-OF-SCOPE 처리 |
| 7 | initiate idempotency | attempt_key 클라이언트 전달 패턴 삭제·order_id 기준 차단으로 재기술 |
| 8 | Mock 결과 반환 | 즉시 반환 표현 제거·webhook 방식으로 재기술 |

> **원칙**: 코드 수정 결정은 별개 트랙. 이 리포트는 사실만 기록한다.

---

## 결론

| 판정 | 건수 | 항목 |
|---|---|---|
| PASS | 2 | #1(PAY-3a UNIQUE)·#2(PAY-3b 형식) |
| FAIL | 1 | #6(OrderEventHandler OrderPlaced 미구현) |
| WARN | 5 | #3(전이 그래프)·#4(flush)·#5(이벤트 필드명)·#7(initiate idempotency)·#8(Mock 방식) |
| OUT-OF-SCOPE | 0 | — |

**총 95 테스트 통과** (expected-spec 기대치 ≥ 90 충족).
