# Track 5 Expected Spec — Refund Flow (+ Claim Minimal Skeleton)

> 작성: Claude.ai (실 코드 미참조·SoT 문서 기준 중립 작성)
> SoT 인풋: decisions.md D-05·D-24·D-27~D-37 / state-machine.md §1·§2·§8 / invariants.md §2.11 PAY·§2.13 CLM·§2.13.1 RFN / aggregate-boundary.md §2.5 / db-schema-decisions.md §2.5 / V1__init.sql (refund·claim 테이블)
> Track 분류: S급 (Guard 2) — Order·Payment·Refund·Settlement·Claim 군
> 범위 결정: 옵션 B' — Refund 풀 + Claim 최소 골격

---

## 1. 범위

### 1.1 포함 (In-Scope)

| 항목 | 산출물 |
|---|---|
| Refund 엔티티 | Refund.java (rfn_ public_id·BIGINT FK claim_id·payment_id·amount·status·pg_refund_id·refunded_at) |
| Refund Repository | RefundRepository (findByPgRefundId·findByClaimId·sumCompletedByPaymentId) |
| Refund Service | RefundService (initiate·markCompleted·markFailed) |
| Refund 상태 enum | RefundStatus + canTransitionTo (state-machine §8) |
| PG 환불 webhook | RefundWebhookController + MockPaymentGateway.refund() |
| Payment 전이 | Payment.markCancelled (Refund.COMPLETED 누적 = amount 시) |
| Claim 최소 골격 | Claim 엔티티·ClaimStatus·ClaimType enum·canTransitionTo (state-machine §2)·Repository·markCompleted 전이 1개 |
| Domain Event | RefundCompleted (Payment·Claim 핸들러용) |
| Flyway | V5 마이그레이션 없음 (V1 refund 테이블 그대로 사용) |
| 테스트 | RefundService·webhook·state 전이·invariant·Payment 연동·Claim.COMPLETED 전이 |

### 1.2 제외 (Out-of-Scope · 후속 트랙 이연)

- Claim 요청 API (구매자 취소/반품/교환 요청 endpoint)
- Claim 승인/거절 워크플로우 (판매자·운영자 권한 매트릭스)
- OrderItem.item_status 전이 동기화 (CANCEL_REQUESTED → CANCELLED 등 §3)
- Order.status 재계산 트리거 연동 (OrderStatusResolver 호출 hook)
- EXCHANGE 비환불 경로 (교환품 재출고 Delivery 생성)
- Claim REJECTED 경로 처리
- 부분환불 (이번 트랙은 amount 단일 행 기준·RFN-2 재시도 패턴만)
- 운영자 수동 환불 보정 (state-machine §8 "후속 트랙 D안 RefundAdjustment")

---

## 2. 엔티티 사양

### 2.1 Refund

| 필드 | 타입 | 제약 | 비고 |
|---|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | — | V1 박제 |
| public_id | CHAR(30) UNIQUE | NOT NULL | prefix `rfn_` (ADR-001·12 부여 대상) |
| claim_id | BIGINT FK→claim | NOT NULL · ON DELETE RESTRICT | CLM-3 |
| payment_id | BIGINT FK→payment | NOT NULL · ON DELETE RESTRICT | PAY-1 누적 검증 대상 |
| amount | BIGINT | NOT NULL | KRW 정수 (§1.3) |
| status | ENUM(PENDING·COMPLETED·FAILED) | NOT NULL | state-machine §8 |
| pg_refund_id | VARCHAR(100) NULLABLE | — | RFN-1 (COMPLETED 전이 필수)·RFN-3 멱등 키 |
| refunded_at | DATETIME(6) NULLABLE | — | COMPLETED 전이 시점 시스템 시각·PG 원시 시각 아님 (CR-09) |
| audit 4종 | — | NOT NULL (created·updated) | 글로벌 §1.7 |

> Aggregate 소속: Claim Aggregate (aggregate-boundary §2.5). Root 아님 — Claim 경유 생성.
> SOFT 미적용 (§1.8) — 상태 관리로 처리.

### 2.2 Claim (최소 골격)

| 필드 | 타입 | 비고 |
|---|---|---|
| id·public_id(clm_) | BIGINT·CHAR(30) | V1 박제 |
| order_item_id | BIGINT FK→order_item | — |
| type | ENUM(CANCEL·RETURN·EXCHANGE) | A#13 |
| reason_code | VARCHAR(50) | B/CLAIM_REASON Code 정합 |
| status | ENUM(REQUESTED·APPROVED·REJECTED·COMPLETED) | A#14·state §2 |
| requested_by·requested_at·processed_at | — | — |

> 최소 골격 범위: 엔티티·enum·canTransitionTo·Repository·markCompleted. 요청·승인 API는 후속.
> 테스트용 APPROVED 상태 시드는 Repository 직접 INSERT 또는 fixture builder.

---

## 3. 상태기계

### 3.1 Refund (state-machine §8 1:1)

전이 다이어그램 (평문 들여쓰기):

    PENDING ──→ COMPLETED  (불가역)
            └─→ FAILED      (불가역)

전이 enforcement:

| 전이 | 위치 | 가드 |
|---|---|---|
| (생성) → PENDING | RefundService.initiate | Claim.status = APPROVED 검증 (CLM-3) |
| PENDING → COMPLETED | RefundService.markCompleted | pg_refund_id NOT NULL (RFN-1) + 중복 콜백 no-op (RFN-3) |
| PENDING → FAILED | RefundService.markFailed | failure 응답 멱등 처리·PG 호출 예외 포함 (CR-03·state-machine §8 본문 보강 대상) |
| COMPLETED·FAILED → * | RefundStatus.canTransitionTo | 차단 (RFN-2) |

### 3.2 Claim (state-machine §2·이번 트랙 부분 적용)

전이 다이어그램 (평문 들여쓰기):

    REQUESTED → APPROVED → COMPLETED  (이번 트랙: APPROVED → COMPLETED만)
             ↘ REJECTED                (이번 트랙 외)

- enum + canTransitionTo 전체 박제 (4상태 모두 인지)
- 실제 사용 전이: **APPROVED → COMPLETED** (Refund.COMPLETED 콜백 후 Claim.type=CANCEL일 때) 1건만
- REQUESTED 진입·APPROVED 승인은 테스트 시드/fixture로 직접 INSERT

---

## 4. Invariant 적용

| # | Rule | 강제 위치 |
|---|---|---|
| RFN-1 | COMPLETED 전이 시 pg_refund_id NOT NULL | RefundService.markCompleted 가드 |
| RFN-2 | COMPLETED·FAILED 불가역·재시도 = 새 행 | RefundStatus.canTransitionTo |
| RFN-3 | 동일 pg_refund_id 중복 콜백 멱등(no-op)·단일 PG provider 기준·멀티 PG 도입 시 재평가 (CR-07) | RefundWebhookController + RefundService |
| PAY-1 | Σ(Refund.COMPLETED.amount) ≤ Payment.amount | RefundService.initiate (사전) + markCompleted (사후 재검증) |
| PAY-2 | Payment.PAID → CANCELLED 전이·Track 5 기준 전액 환불 완료 의미·부분환불 도입 시 재정의 가능 (CR-11) | PaymentService.markCancelled (Σ refund = payment.amount 시) |
| CLM-3 | Refund는 Claim.APPROVED 후에만 생성 | RefundService.initiate |
| CLM-4 | Claim 전이 = state-machine §2 | ClaimStatus.canTransitionTo |
| CLM-1 | COMPLETED 후 상태 변경 금지 | ClaimStatus.canTransitionTo |

---

## 5. Service 책임

### 5.1 RefundService

| 메서드 | 입력 | 출력 | 책임 |
|---|---|---|---|
| initiate(claimId, amount) | Long·long | Refund | Claim.APPROVED 검증·PAY-1 사전 누적 검증·Refund(PENDING) INSERT·PG refund() 호출·pg_refund_id 미부여 상태 반환 |
| markCompleted(pgRefundId) | String | Refund | RFN-3 멱등·RFN-1 가드·refunded_at 채움·PAY-1 사후 재검증 (Payment 행 직렬화 필요·구현체 선택·CR-05)·RefundCompleted 이벤트 publish |
| markFailed(refundId, reason) | Long·String | Refund | PENDING→FAILED·failure 멱등 |

> 이벤트 publish는 **save→publish** (D-29·메모리 보정 반영). flush 없음.

### 5.2 PaymentService 추가

| 메서드 | 책임 |
|---|---|
| markCancelled(paymentId) | Σ(Refund.COMPLETED.amount by paymentId) = Payment.amount 검증 후 Payment.PAID→CANCELLED 전이 |

### 5.3 ClaimService (최소)

| 메서드 | 책임 |
|---|---|
| markCompleted(claimId) | RefundCompleted 이벤트 핸들러에서 호출·Claim.type=CANCEL일 때 APPROVED→COMPLETED 전이 |

---

## 6. PG 환불 webhook

### 6.1 흐름 (평문 들여쓰기)

    [운영자/Claim 승인 시스템]
            │
            ▼
    RefundService.initiate (PENDING + PG refund() 호출)
            │
            ▼ (PG 비동기 콜백)
    POST /api/webhook/refund (RefundWebhookController)
            │
            ▼
    RefundService.markCompleted | markFailed
            │
            ▼ (COMPLETED 시)
    RefundCompleted 이벤트 publish
            │
            ├──→ ClaimEventHandler.onRefundCompleted → Claim.markCompleted
            └──→ PaymentEventHandler.onRefundCompleted → PaymentService.markCancelled (조건 충족 시)

### 6.2 webhook endpoint

| 항목 | 값 |
|---|---|
| Path | `POST /api/webhook/refund` (Track 3 `/api/webhook/payment` 미러) |
| 요청 | { pg_refund_id, status (SUCCESS·FAIL), failure_reason? } |
| 멱등 | pg_refund_id 키 (RFN-3) |
| 인증 | Track 3 webhook 시그니처 패턴 재사용 (D-27~D-37 중 해당 결정 미러) |

### 6.3 MockPaymentGateway 확장

메서드 시그니처 (평문):

    refund(paymentId, amount) → MockRefundResponse(pg_refund_id, status)

Track 3 initiate() 미러: 즉시 응답 아닌 별도 webhook POST 콜백 방식 (메모리 보정 D-29 정합).

---

## 7. 트랜잭션 경계

| 경계 | 내용 |
|---|---|
| initiate | Refund INSERT + PG refund() 호출 — **Refund INSERT는 PG 호출 전 commit** (Track 3 패턴) |
| markCompleted | Refund UPDATE + 이벤트 publish — Publisher 시점은 save→publish 유지 (D-29·flush 없음) |
| onRefundCompleted (Claim·Payment 핸들러) | Consumer 실행 시점은 @TransactionalEventListener(phase = AFTER_COMMIT) — Refund UPDATE 커밋 후 핸들러 진입 보장·각자 별도 트랜잭션 (Aggregate 경계 §2.5·CR-06) |

> Claim·Payment 갱신은 **이벤트 기반 비동기 분리** — Refund 트랜잭션과 분리되어 부분 실패 허용 (재처리 가능).

---

## 8. Domain Event

| Event | Publisher | Consumer |
|---|---|---|
| RefundCompleted(refundId, claimId, paymentId, amount) | RefundService.markCompleted | ClaimEventHandler·PaymentEventHandler |

> RefundFailed 이벤트는 본 트랙 미발행 (후속 운영자 알림 트랙에서 NotificationLog 트리거 시 검토).

---

## 9. 테스트 기대

### 9.1 단위 (예상 ≥20건)

- RefundStatus.canTransitionTo 매트릭스 (9 케이스)
- ClaimStatus.canTransitionTo 매트릭스 (12 케이스)
- RefundService.initiate — Claim 미승인 차단·PAY-1 사전 가드·정상 INSERT
- RefundService.markCompleted — RFN-1·RFN-3 멱등·refunded_at 채움
- RefundService.markFailed
- PaymentService.markCancelled — 누적 검증·전이

### 9.2 통합 (예상 ≥6건)

- webhook end-to-end (initiate → callback → COMPLETED → 이벤트 → Claim·Payment 갱신)
- 중복 webhook 멱등 (RFN-3)
- 재시도 = 새 Refund 행 (RFN-2)
- PAY-1 초과 시도 차단
- Claim.type=RETURN/EXCHANGE는 본 트랙 Claim.COMPLETED 전이 미발생 확인

### 9.3 회귀 보장

- Track 3 Payment 테스트 전건 PASS
- Track 4 Order API 테스트 전건 PASS (전체 127건 + 신규 ≥26건)

---

## 10. SoT 의존성 추적표

| 본 문서 § | SoT 출처 |
|---|---|
| §1 범위 | aggregate-boundary §2.5·CLM-3 |
| §3.1 Refund 전이 | state-machine §8 (D-24) |
| §3.2 Claim 전이 | state-machine §2 |
| §4 invariant | invariants §2.11·§2.13·§2.13.1 |
| §6 webhook | 메모리 보정 D-29 (save→publish)·Track 3 패턴 |
| §7 트랜잭션 | aggregate-boundary §1 (Aggregate간 ID 참조·이벤트 분리) |
| §2.1 refund DDL | V1__init.sql §34 |
| §2.2 claim DDL | V1__init.sql §33 |

---

## 11. 외부 검토 흡수 결과 (v2)

### 11.1 Q1~Q5 확정

| ID | 항목 | 결정 | 근거 |
|---|---|---|---|
| Q1 | RefundFailed 이벤트 발행 | (b) 후속 이연 | 컨슈머 없음·NotificationLog 트랙 도래 시 |
| Q2 | webhook 시그니처 검증 | (a) Track 3 동일 | 일관성 우선·공통화는 구현 후 promote |
| Q3 | initiate 트랜잭션 범위 | (b) PG 호출 분리 | Track 3 패턴 정합·부분 실패 격리 |
| Q4 | EXCHANGE 환불 | (b) 후속 | 교환 출고 Delivery 미구현·결합도 |
| Q5 | amount 검증 시점 | (b) 2회 (initiate + markCompleted) | PAY-1 교차 invariant·동시성 안전 |

### 11.2 CR 흡수 매트릭스

| ID | 처리 | 박제 위치 |
|---|---|---|
| CR-01 | 보류 | — (실패 분류 enum 도입 시 재평가) |
| CR-02 | 보류 | — (3개 이상 webhook 도달 시 promote) |
| CR-03 | 채택 | §3.1 PENDING→FAILED 가드 보강·state-machine §8 본문 후속 PR |
| CR-04 | 이연 | Track 7 ClaimCompletionPolicy 도입 시 |
| CR-05 | 채택·축소 | §5.1 markCompleted 책임 보강·구현체 선택 위임 |
| CR-06 | 채택·시점 분리 서술 | §7 Publisher(save→publish·D-29)·Consumer(AFTER_COMMIT) |
| CR-07 | 철회·문서 주석만 | §4 RFN-3 비고 보강·DDL/Repository 무관 |
| CR-08 | 철회 | — (RFN-2 정합·PAY-1로 과환불 차단) |
| CR-09 | 채택·정의 변경 | §2.1 refunded_at = COMPLETED 전이 시스템 시각 |
| CR-10 | 철회 | D-29 save→publish 유지 |
| CR-11 | 채택·문구 완화 | §4 PAY-2 비고·Track 5 한정 의미·부분환불 시 재정의 가능 |

### 11.3 후속 SoT 보강 대상 (별도 PR)

- state-machine.md §8 FAILED 진입 조건 본문 보강 (CR-03 본 spec과 정합)
- decisions.md D-67~D-71 박제 (Q1~Q5 확정 + CR-03·05·06·09·11 채택 사유)
- invariants.md §2.13.1 RFN-3 비고 보강 (CR-07 단일 PG 기준 명시)