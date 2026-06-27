# Track 5 Recon Report — Refund Flow

> 정찰: Claude Code Sonnet 4.6 read-only
> 정찰일: 2026-06-28
> 대조 대상: docs/track-5/expected-spec.md v2 ↔ feat/track-5-refund-flow 브랜치 실제 코드
> 정찰 범위: backend/src/main/java/com/zslab/mall/{refund,claim,payment}/ + 테스트 + GlobalExceptionHandler
> 분류 규칙: PASS / FAIL-코드 / FAIL-명세 / WARN / OUT-OF-SCOPE

---

## 1. 요약

| 분류 | 건수 |
|---|---|
| PASS | 40 |
| FAIL-코드 | 0 |
| FAIL-명세 | 2 |
| WARN | 2 |
| OUT-OF-SCOPE | 8 |

종합 판정: **조건부 PASS** — FAIL-코드 0건. FAIL-명세 2건은 코드 동작이 정확하고 스펙 표현이 보정 대상이며 blocking이 아니다.

---

## 2. expected-spec §1 범위 대조

### 2.1 In-Scope 10항목 대조

| # | spec 항목 | 산출물 | 분류 | 비고 |
|---|---|---|---|---|
| 1 | Refund 엔티티 | `refund/entity/Refund.java` | PASS | rfn_ public_id·claimId·paymentId·amount·status·pgRefundId·refundedAt·audit 전부 매핑 |
| 2 | Refund Repository | `refund/repository/RefundRepository.java` | PASS | findByPgRefundId·findByClaimId·sumCompletedByPaymentId 전부 구현 |
| 3 | Refund Service | `refund/service/RefundService.java` | PASS | initiate·markCompleted·markFailed·handleCallback 구현 |
| 4 | Refund 상태 enum | `refund/enums/RefundStatus.java` | PASS | canTransitionTo 3값 매트릭스 1:1 |
| 5 | PG 환불 webhook | `refund/controller/RefundWebhookController.java` | PASS | POST /api/webhooks/refunds (STEP 16 보정 완료) |
| 6 | MockPaymentGateway 확장 | `payment/gateway/MockPaymentGateway.java` | PASS | refund(pgTid, amount)→MockRefundResponse(pgRefundId, success, null) |
| 7 | Payment 전이 | `payment/service/PaymentService.markCancelled` | PASS | D-71 전액 환불 시 CANCELLED 전이·멱등 |
| 8 | Claim 최소 골격 | claim 패키지 7파일 | PASS | 엔티티·enum 2종·Repository·Service·Exception 2종·Handler |
| 9 | Domain Event | `refund/event/RefundCompleted.java` | PASS | publisher(markCompleted)·consumer(2핸들러) 구현 |
| 10 | Flyway (V5 없음) | V5 미생성 | PASS | V1 refund·claim 테이블 그대로 사용 |

### 2.2 Out-of-Scope 미구현 확인

| # | spec §1.2 제외 항목 | 분류 |
|---|---|---|
| 1 | Claim 요청 API (구매자 취소/반품/교환 요청 endpoint) | OUT-OF-SCOPE ✅ |
| 2 | Claim 승인/거절 워크플로우 (판매자·운영자 권한 매트릭스) | OUT-OF-SCOPE ✅ |
| 3 | OrderItem.item_status 전이 동기화 | OUT-OF-SCOPE ✅ |
| 4 | Order.status 재계산 트리거 연동 | OUT-OF-SCOPE ✅ |
| 5 | EXCHANGE 비환불 경로 (교환품 재출고 Delivery 생성) | OUT-OF-SCOPE ✅ |
| 6 | Claim REJECTED 경로 처리 | OUT-OF-SCOPE ✅ |
| 7 | 부분환불 (amount 단일 행·RFN-2 재시도 패턴만) | OUT-OF-SCOPE ✅ |
| 8 | 운영자 수동 환불 보정 | OUT-OF-SCOPE ✅ |

grep으로 EXCHANGE·REJECTED·부분환불 로직 코드 진입 없음 확인.

---

## 3. expected-spec §2 엔티티 대조

### 3.1 Refund 필드 대조

| spec 필드 | Java 필드 | 매핑 | 분류 |
|---|---|---|---|
| id BIGINT PK | `@Id @GeneratedValue` | ✅ | PASS |
| public_id CHAR(30) rfn_ | `AbstractPublicIdFullAuditableEntity` + `getPublicIdPrefix()="rfn"` | ✅ | PASS |
| claim_id BIGINT FK | `@Column(name="claim_id", nullable=false, updatable=false)` | ✅ | PASS |
| payment_id BIGINT FK | `@Column(name="payment_id", nullable=false, updatable=false)` | ✅ | PASS |
| amount BIGINT NOT NULL | `@Column(name="amount", nullable=false, updatable=false)` | ✅ | PASS |
| status ENUM(PENDING·COMPLETED·FAILED) | `@Enumerated(EnumType.STRING) RefundStatus` | ✅ | PASS |
| pg_refund_id VARCHAR(100) NULLABLE | `@Column(name="pg_refund_id", length=100)` | ✅ | PASS |
| refunded_at DATETIME(6) NULLABLE | `@Column(name="refunded_at") LocalDateTime` | ✅ | PASS |
| audit 4종 | `AbstractPublicIdFullAuditableEntity` 상속 | ✅ | PASS |

> Aggregate 소속 javadoc: "Claim Aggregate 소속·Root 아님(aggregate-boundary §2.5·CLM-3)" 명시 ✅

### 3.2 Claim 최소 골격 대조

| spec 필드 | Java 필드 | 분류 |
|---|---|---|
| id·public_id(clm_) | `@Id + AbstractPublicIdFullAuditableEntity + "clm"` | PASS |
| order_item_id FK | `@Column(name="order_item_id", nullable=false)` | PASS |
| type ENUM(CANCEL·RETURN·EXCHANGE) | `@Enumerated ClaimType` | PASS |
| reason_code VARCHAR(50) | `@Column(name="reason_code", length=50, nullable=false)` | PASS |
| status ENUM(REQUESTED·APPROVED·REJECTED·COMPLETED) | `@Enumerated ClaimStatus` | PASS |
| requested_by·requested_at·processed_at | 전부 매핑 | PASS |

> spec §2.2에 `reason_detail` 미기재이나 V1 DDL에 존재·Claim.java 매핑 완료. spec 누락 비고(버그 아님).

---

## 4. expected-spec §3 상태기계 대조

### 4.1 RefundStatus.canTransitionTo 매트릭스

| 전이 | spec | 코드 | 분류 |
|---|---|---|---|
| PENDING → COMPLETED | 합법 | `case PENDING -> next == COMPLETED \|\| next == FAILED` | PASS |
| PENDING → FAILED | 합법(D-67·CR-03) | 위 동일 케이스 | PASS |
| COMPLETED → * | 차단(RFN-2) | `case COMPLETED, FAILED -> false` | PASS |
| FAILED → * | 차단(RFN-2) | 위 동일 케이스 | PASS |

### 4.2 ClaimStatus.canTransitionTo 매트릭스

| 전이 | spec | 코드 | 분류 |
|---|---|---|---|
| REQUESTED → APPROVED | 합법 | `case REQUESTED -> next == APPROVED \|\| next == REJECTED` | PASS |
| REQUESTED → REJECTED | 합법 | 위 동일 케이스 | PASS |
| APPROVED → COMPLETED | 합법(본 트랙 실사용) | `case APPROVED -> next == COMPLETED` | PASS |
| REJECTED → * | 차단(CLM-1) | `case REJECTED, COMPLETED -> false` | PASS |
| COMPLETED → * | 차단(CLM-1) | 위 동일 케이스 | PASS |

---

## 5. expected-spec §4 Invariant 강제 대조

| # | 규칙 | spec 강제 위치 | 실제 구현 위치 | 분류 |
|---|---|---|---|---|
| RFN-1 | COMPLETED 전이 시 pg_refund_id NOT NULL | RefundService.markCompleted | `markCompleted(pgRefundId==null→throw)` + `Refund.markCompleted(pgRefundId 방어선)` | PASS |
| RFN-2 | COMPLETED·FAILED 불가역·재시도=새 행 | RefundStatus.canTransitionTo | `COMPLETED, FAILED -> false` | PASS |
| RFN-3 | 동일 pg_refund_id 중복 콜백 멱등 | RefundWebhookController + RefundService | `handleCallback` 종결상태 선검사 no-op | PASS |
| PAY-1 | Σ(Refund.COMPLETED.amount) ≤ Payment.amount | initiate(사전) + markCompleted(사후) | `sumCompletedByPaymentId` 2회 검증·사후=D-68 비관적 락 | PASS |
| PAY-2 | Payment.PAID → CANCELLED (전액 환불 시·D-71) | PaymentService.markCancelled | `totalRefunded == payment.getAmount()` 조건 후 `payment.cancel()` | PASS |
| CLM-3 | Refund는 Claim.APPROVED 후에만 생성 | RefundService.initiate | `claim.getStatus() != APPROVED → ClaimInvalidStateException` | PASS |
| CLM-4 | Claim 전이 = state-machine §2 | ClaimStatus.canTransitionTo | 매트릭스 일치(§4.2 확인) | PASS |
| CLM-1 | COMPLETED 후 상태 변경 금지 | ClaimStatus.canTransitionTo | `REJECTED, COMPLETED -> false` | PASS |

---

## 6. expected-spec §5 Service 책임 대조

### 6.1 RefundService.initiate

| spec 책임 | 코드 | 분류 |
|---|---|---|
| Claim.APPROVED 검증(CLM-3) | `claimRepository.findById` + status 검사 | PASS |
| PAY-1 사전 누적 검증 | `sumCompletedByPaymentId + amount > payment.getAmount()` | PASS |
| Refund(PENDING) INSERT | `Refund.create + refundRepository.save` | PASS |
| PG refund() 호출 | `paymentGateway.refund(payment.getPgTid(), amount)` | PASS |
| **"pg_refund_id 미부여 상태 반환"** (spec §5.1) | 실제: PG 응답의 pgRefundId를 PENDING 행에 즉시 부여해 반환 | **FAIL-명세** |
| PG 예외 시 FAILED 전이(D-67) | `catch RuntimeException → markFailed()` | PASS |

> [I1] FAIL-명세 상세: spec §5.1 initiate 책임 열 "pg_refund_id 미부여 상태 반환"은 코드 동작과 다르다.
> webhook 페이로드({pg_refund_id,status})가 pg_refund_id로만 PENDING 행을 매칭하므로
> initiate에서 PG 응답 pg_refund_id를 PENDING 행에 즉시 부여하지 않으면 콜백 매칭이 불가능하다.
> → 코드 동작 정확·spec §5.1 표현 "pg_refund_id 부여(PENDING·webhook 매칭 키)·COMPLETED 미확정"으로 보정 필요.
> **권고**: spec §5.1 initiate 행 비고 컬럼 보정(별도 PR).

### 6.2 RefundService.markCompleted

| spec 책임 | 코드 | 분류 |
|---|---|---|
| RFN-3 멱등 | `handleCallback` 선검사 + `markCompleted`에 COMPLETED 체크 | PASS |
| RFN-1 가드 | `pgRefundId==null → RefundInvariantViolationException` | PASS |
| refunded_at 채움(D-70 시스템 시각) | `LocalDateTime.now()` 주입 | PASS |
| PAY-1 사후 재검증(D-68 비관적 락) | `findByIdForUpdate + sumCompletedByPaymentId` | PASS |
| RefundCompleted 이벤트 publish(D-29) | `pullDomainEvents → save → publish` | PASS |

### 6.3 RefundService.markFailed

| spec 책임 | 코드 | 분류 |
|---|---|---|
| PENDING→FAILED | `refund.markFailed()` | PASS |
| failure 멱등 | `if FAILED → log.info → return` | PASS |

### 6.4 PaymentService.markCancelled

| spec 책임 | 코드 | 분류 |
|---|---|---|
| Σ(Refund.COMPLETED.amount) == Payment.amount 검증 | `totalRefunded != payment.getAmount() → no-op` | PASS |
| Payment.PAID→CANCELLED 전이 | `payment.cancel()` | PASS |
| 멱등(이미 CANCELLED) | `if CANCELLED → log.info → return` | PASS |

### 6.5 ClaimService.markCompleted

| spec 책임 | 코드 | 분류 |
|---|---|---|
| Claim.APPROVED→COMPLETED 전이 | `claim.markCompleted(LocalDateTime.now())` | PASS |
| 멱등(이미 COMPLETED) | `if COMPLETED → return` | PASS |

---

## 7. expected-spec §6 PG 환불 webhook 대조

### 7.1 흐름

spec §6.1 흐름 다이어그램과 코드 실행 경로 1:1 일치: PASS

### 7.2 endpoint (경로·요청·멱등·인증)

| 항목 | spec §6.2 | 코드 | 분류 |
|---|---|---|---|
| Path | `POST /api/webhook/refund` (v2 원문) | `POST /api/webhooks/refunds` (STEP 16 보정) | PASS ([I4] B 채택·REST 복수형 컨벤션·Track 3 정합) |
| 요청 | `{pg_refund_id, status, failure_reason?}` | `RefundCallbackRequest(pgRefundId, status, failureReason)` @Valid | PASS |
| 멱등 | pg_refund_id 키(RFN-3) | handleCallback 종결상태 no-op | PASS |
| 인증 | Track 3 webhook 시그니처 패턴 재사용 | Track 5 미구현(Out-of-Scope·mock 환경) | OUT-OF-SCOPE |

> spec §6.2 경로 원문 `/api/webhook/refund`는 STEP 16(본 PR)에서 `/api/webhooks/refunds`로 보정. spec 표현 보정은 별도 PR 대상.

### 7.3 MockPaymentGateway 확장

| spec §6.3 | 코드 | 분류 |
|---|---|---|
| `refund(paymentId, amount) → MockRefundResponse(pg_refund_id, status)` | `refund(pgTid, amount) → MockRefundResponse(pgRefundId, true, null)` | PASS |

> 파라미터가 spec `paymentId`→코드 `pgTid`. PG TID로 환불하는 것이 실제 PG 인터페이스에 맞으므로 코드 정확. spec 표현 참고용 의사코드 수준. WARN으로 분류하지 않음.

---

## 8. expected-spec §7 트랜잭션 경계 대조

| 경계 | spec §7 표현 | 코드 | 분류 |
|---|---|---|---|
| initiate | **"Refund INSERT는 PG 호출 전 commit"** | 단일 `@Transactional` 안에서 INSERT 후 PG 호출(D-67 채택) | **FAIL-명세** |
| markCompleted | `save→publish(D-29·flush 없음)` | `markCompleted → pullDomainEvents → save → publish` | PASS |
| 핸들러 | `@TransactionalEventListener(AFTER_COMMIT)·각자 별도 트랜잭션(CR-06)` | `@TransactionalEventListener(phase=AFTER_COMMIT)` + `@Transactional(propagation=REQUIRES_NEW)` | PASS |

> [I3] FAIL-명세 상세: spec §7 "Refund INSERT는 PG 호출 전 commit"은 Track 3 패턴 설명이었으나
> D-67(PG 호출 예외 → FAILED 전이)을 채택하면서 단일 TX 안에서 처리하는 방향으로 확정되었다.
> D-67은 expected-spec v2 §11.2 CR-03 채택에 박제된 결정이다.
> → spec §7 initiate 행을 "단일 @Transactional·PG 예외 시 PENDING→FAILED 전이(D-67)"으로 보정 필요(별도 PR).

---

## 9. expected-spec §8 Domain Event 대조

| 항목 | spec §8 | 코드 | 분류 |
|---|---|---|---|
| Event 명 | RefundCompleted | `record RefundCompleted` | PASS |
| Publisher | RefundService.markCompleted | `markCompleted → pullDomainEvents → save → eventPublisher.publishEvent` | PASS |
| Consumer | ClaimEventHandler·PaymentEventHandler | `claim/handler/RefundCompletedHandler`·`payment/handler/RefundCompletedHandler` | PASS |
| Payload 필드 | `(refundId, claimId, paymentId, amount)` 4개 | `(refundId, claimId, paymentId, amount, refundedAt)` 5개 | WARN |

> WARN 상세: spec §8 payload 4필드이나 코드는 `refundedAt` 추가(D-70 정합으로 COMPLETED 시각 전달).
> 컨슈머 핸들러가 refundedAt을 사용하지 않으므로 기능상 무해. spec §8 payload 표에 refundedAt 추가 권고(별도 PR).

---

## 10. expected-spec §9 테스트 기대 대조

### 10.1 단위 ≥20건

| spec §9.1 기대 | 실제 테스트 | 분류 |
|---|---|---|
| RefundStatus.canTransitionTo 매트릭스(예상 9케이스) | RefundStatusTest 4건 | WARN |
| ClaimStatus.canTransitionTo 매트릭스(예상 12케이스) | ClaimStatusTest 5건 | WARN |
| RefundService.initiate(Claim 미승인·PAY-1·정상) | RefundServiceTest: initiate 3케이스 포함 11건 | PASS |
| RefundService.markCompleted(RFN-1·RFN-3·refunded_at) | RefundServiceTest 포함 | PASS |
| RefundService.markFailed | RefundServiceTest 포함 | PASS |
| PaymentService.markCancelled(누적 검증·전이) | PaymentServiceMarkCancelledTest 3건 | PASS |
| **합계 단위 테스트** | 23건(4+5+11+3) ≥ 20 ✅ | PASS |

> WARN 상세: spec은 RefundStatus 9케이스·ClaimStatus 12케이스를 "예상"으로 기재했으나 실제는 4·5건으로
> 핵심 전이(합법 2건·불법 2건)만 커버한다. 주요 케이스는 모두 포함되어 있어 **blocking 아님**.
> spec §9.1 "예상 ≥20건" 달성 기준은 합계(23건) 기준으로 PASS.

### 10.2 통합 ≥6건

| spec §9.2 기대 | 실제 | 분류 |
|---|---|---|
| webhook e2e(initiate→callback→COMPLETED→Claim·Payment 갱신) | RefundWebhookIntegrationTest: `webhook_success_endToEnd` | PASS |
| 중복 webhook 멱등(RFN-3) | `webhook_duplicateCallback_idempotent` | PASS |
| 재시도 = 새 Refund 행(RFN-2) | `retry_afterFailed_createsNewRow` | PASS |
| PAY-1 초과 시도 차단 | `initiate_payOneExceeded_blocked` | PASS |
| Claim.FAIL e2e | `webhook_fail_endToEnd` | PASS |
| Claim.type=RETURN/EXCHANGE Claim.COMPLETED 미전이 | `returnClaim_doesNotCompleteClaim` | PASS |
| **합계** | 6건 ≥ 6 ✅ | PASS |

### 10.3 회귀 (Track 3·4 PASS 유지)

| 항목 | 실제 | 분류 |
|---|---|---|
| 전체 테스트 수 | 156건 (기존 127 + 신규 29) | PASS |
| 실패 건수 | 0 | PASS |
| 빌드 결과 | BUILD SUCCESSFUL | PASS |

---

## 11. 표면화 6항목 분류

| ID | 항목 | 분류 | 권고 조치 |
|---|---|---|---|
| I1 | pg_refund_id initiate 시점 즉시 부여 | **FAIL-명세** | spec §5.1 initiate 비고 "pg_refund_id 부여(PENDING·webhook 매칭 키)·COMPLETED 미확정"으로 보정(별도 PR) |
| I2 | payment_id 해소 그래프 + findOrderIdById projection | PASS | spec §5.1에 "claim→order_item→order→payment 해소" 추가 권고(별도 PR·보강 가치) |
| I3 | initiate 단일 @Transactional(D-67) | **FAIL-명세** | spec §7 initiate 행 "단일 TX·PG 예외→FAILED(D-67)" 표현으로 보정(별도 PR) |
| I4 | webhook 경로 보정 완료 | PASS | `/api/webhooks/refunds`·STEP 16·17(본 PR)에서 완료·spec §6.2 경로 표현 보정은 별도 PR |
| I5 | 핸들러 빈 이름 충돌 회피(@Component 명시) | PASS | spec/SoT 변경 불요·구현 detail |
| 🪤 | AFTER_COMMIT + REQUIRES_NEW propagation 필수 | PASS | D-69에 propagation=REQUIRES_NEW 명시 누락·별도 PR SoT 보강 대상 |

---

## 12. 추가 발견 항목

### 12.1 RefundCompleted payload refundedAt 추가

spec §8 payload `(refundId, claimId, paymentId, amount)` 4개 대비 코드 `(refundId, claimId, paymentId, amount, refundedAt)` 5개.
D-70(refunded_at = 시스템 시각)과 정합하여 핸들러 downstream에 시각 정보를 전달하는 설계다. 기능 무해.
→ spec §8 payload 표에 `refundedAt` 행 추가 권고(별도 PR).

### 12.2 MockPaymentGateway.refund 파라미터 차이

spec §6.3 `refund(paymentId, amount)` vs 코드 `refund(pgTid, amount)`.
실제 PG 환불은 PG TID 기준 요청이 표준이므로 코드가 올바른 시그니처다.
spec §6.3 의사코드 표현 보정 권고(별도 PR·minor).

### 12.3 spec §6.2 인증 Out-of-Scope 명시 누락

spec §6.2 인증 항목 "Track 3 webhook 시그니처 패턴 재사용" 기재이나 Track 5 코드에 webhook 인증 미구현.
expected-spec §1.2 Out-of-Scope에 명시되지 않은 묵시적 제외. 단 mock 환경·본 트랙 범위 합리적.
→ spec §1.2 Out-of-Scope에 "webhook 인증(Track 5 Mock PG 환경·후속 트랙)" 명시 권고(별도 PR·minor).

---

## 13. 외부 검토 의뢰 인풋 후보

| # | 항목 | 사유 |
|---|---|---|
| 1 | `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 조합 | Spring 이벤트 전파 모델의 비자명한 부분·라이브 트랩으로 발견·D-69 SoT 보강 필요 |
| 2 | PAY-1 사후 검증의 비관적 락(D-68) — `findByIdForUpdate` + `sumCompletedByPaymentId` 순서 | 교차 Aggregate 동시성 안전 보장 방식·락 범위가 적절한지 검증 가치 |
| 3 | initiate 단일 TX에서 PG 호출 후 예외 시 FAILED 전이(D-67) — PENDING 행이 영속된 상태에서 FAILED 전이 | PG 장애 시 PENDING 잔존 vs FAILED 처리 트레이드오프·재시도 가능 여부 |

---

## 14. recon 종합 판정

- **정찰 통과 가부**: 조건부 PASS
- **외부 검토 의뢰 진입 가부**: 가능 (FAIL-코드 0건·blocking FAIL 없음)
- FAIL-코드 0건 / FAIL-명세 2건 (I1·I3 — 코드 정확·스펙 표현 보정 대상·후속 PR)
- WARN 2건 (RefundCompleted payload + 상태기계 테스트 케이스 수 — 비차단)
- OUT-OF-SCOPE 8건 (spec §1.2 전부 미구현 확인)
- 신규 테스트 29건·회귀 0건·156 PASS

---

## 15. v2.1 흡수 후 상태 (2026-06-28 박제)

> 본 정찰 보고서는 expected-spec.md **v2** 시점 스냅샷이며 본문은 정찰 SoT로 보존한다.
> v2.1 외부 검토 흡수(D-72~D-77) 이후 상태 변화는 본 섹션에 누적 기록한다.

### 15.1 FAIL-명세 항목 해소 현황

| ID | v2 시점 상태 | v2.1 흡수 후 상태 | 해소 경위 |
|---|---|---|---|
| [I1] pg_refund_id 발급 시점 | FAIL-명세 (spec: 콜백 시 부여 / 코드: initiate 시 발급) | RESOLVED | D-72 채택·spec §5.1 보정으로 initiate 시점 발급 명문화 |
| [I3] TX 경계 표현 | FAIL-명세 (spec §7 "PG 호출 전 commit" 표현 모호) | RESOLVED | D-73 채택·spec §7 단일 @Transactional·PG 실패=비즈니스 실패 정상 커밋 명문화 |

### 15.2 WARN 항목 처리

| ID | v2 시점 상태 | v2.1 흡수 후 상태 |
|---|---|---|
| RefundCompleted payload refundedAt 추가 | WARN·비차단 | 유지 (별도 결정 없음) |
| 상태기계 테스트 케이스 수 | WARN·비차단 | 유지 (별도 결정 없음) |

### 15.3 추가 흡수 결정 (외부 검토 2차)

| ID | 내용 | 박제 위치 |
|---|---|---|
| D-74 | 이벤트 핸들러 빈 네이밍 (Spring 기본·명시 지정은 충돌 시 한정) | decisions.md |
| D-75 | 이벤트 핸들러 propagation 정책 (D-69 PARTIALLY SUPERSEDED·역할별 분기) | decisions.md |
| D-76 | Refund 멱등성 키 역할 분리 (attempt_key=내부·pg_refund_id=외부·둘 다 UNIQUE) | decisions.md·V5 |
| D-77 | Claim 1:1 Refund 유지·미래 1:N 확장 가능성 기록 | decisions.md |

### 15.4 OUT-OF-SCOPE 8건

v2 시점 그대로 OUT-OF-SCOPE 유지·후속 트랙 소관 (변경 없음).

### 15.5 종합 판정 변화

- v2 시점: **조건부 PASS** (FAIL-명세 2건)
- v2.1 흡수 후: **PASS** (FAIL-명세 2건 RESOLVED·코드 무수정·spec/D 보정으로 해소)
