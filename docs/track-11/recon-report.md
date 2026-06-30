# Track 11 Refund Service 정찰 보고서 (recon-report)

> 작성일: 2026-06-30 · 모드: read-only 정찰 (코드·git 무변경) · 라벨: S급 (외부 검토 권장·CLM-3 정합 핵심)
> 산출물: 본 파일 1건 (그 외 파일 변경 없음·PROGRESS.md STEP 로그 제외)
> SoT 기준: main d8f8cb0 (Track 10-B PR #74 머지 직후·D-93 박제 완료)

---

## §1. 트랙 배경·범위·LIMITATION

### 1.1 배경
Track 10-B(Admin Claim endpoint·D-93) 머지 직후 진입. 본 트랙의 명목 목표는 **`ClaimApproved → Refund 자동 변환`** 구성이다. 결정 라인은 D-87 Q3 → D-90 Q2 → D-92 Q8 → D-93 Q9로 **4회 연속 이연**된 carry-over다(§2.3 인용).

### 1.2 핵심 실측 결론 (정찰 룰 #6 — 선구현 확인)
**Refund 도메인은 빈 상태가 아니다. Track 5에서 거의 전부 선구현되었다.** 정찰 결과 본 트랙의 실제 잔여 범위는 "Refund Service 신규 구축"이 아니라 **"이미 존재하는 `RefundService.initiate`를 `ClaimApproved` 이벤트에 자동 연결하는 단일 핸들러 신설"**로 축소된다.

- `RefundService.initiate(claimId, amount)`는 CLM-3(Claim.APPROVED 검증)·PAY-1 사전·PENDING INSERT·PG `refund()` 호출·D-67 FAILED 전이까지 **기구현**이다([RefundService.java:82-122](../../backend/src/main/java/com/zslab/mall/refund/service/RefundService.java#L82-L122)).
- 역방향 루프(`RefundCompleted → Claim.COMPLETED·Payment.CANCELLED`)도 **기구현·통합 테스트 통과**다(§4·§5·§7).
- **유일 결손 = forward 트리거**: `ClaimApproved`를 소비해 `initiate`를 호출하는 핸들러가 **부재**다(grep 실측·§4.2).

### 1.3 LIMITATION
- 본 보고서는 read-only 실측이며 구현·결정을 포함하지 않는다. §8 의제는 결정 라운드 입력이다.
- 박제 영향 범위(§10)는 **결정 라운드 확정 전 예측**이며 확정 박제가 아니다.
- 본 트랙 식별자("Track 11")는 기존 결정과 **충돌**한다(§8 Q0·§9 WARN-1). 본 보고서는 잠정적으로 "Track 11 Refund Service"로 표기하나 최종 식별자는 사용자 결정 사항이다.

---

## §2. 정독 SoT 인용 목록

### 2.1 불변조건 (invariants.md)
- **CLM-3**: "Refund는 Claim 승인 후에만 생성"·생명주기 공유(D-01)·Enforcement = Domain([invariants.md:136](../architecture-baseline/invariants.md#L136)).
- **§2.13.1 RFN-1~3**([invariants.md:140-148](../architecture-baseline/invariants.md#L140-L148)):
  - RFN-1: COMPLETED 전이는 pg_refund_id 필수(Service)
  - RFN-2: FAILED·COMPLETED 불가역·재시도는 새 Refund 행(Domain canTransition)
  - RFN-3: 동일 pg_refund_id 중복 콜백 멱등 no-op(Service)
  - 명문: "Refund는 Claim Aggregate 소속(CLM-3). 별도 Aggregate 아님."
- **PAY-1**: "Refund 총액 ≤ Payment.amount"·교차 Aggregate·강제 위치 = Claim/Refund Domain([invariants.md:115·120](../architecture-baseline/invariants.md#L115)).
- COM-1: public_id 12종에 `rfn_` 포함([invariants.md:195](../architecture-baseline/invariants.md#L195)).

### 2.2 상태머신 (state-machine.md)
- **§8 Refund.status**: 3값 `PENDING·COMPLETED·FAILED`·PENDING→COMPLETED/FAILED 둘 다 불가역([state-machine.md:214-252](../architecture-baseline/state-machine.md#L214-L252)). PENDING 진입 = "Claim 승인 후 Domain Service가 생성". 전이 권한 = **자동(PG 콜백 핸들러)·운영자 수동 보정 권한 없음**(후속 D안 RefundAdjustment).
- §2 Claim.status: CANCEL type COMPLETED 조건 = `Refund.status = COMPLETED`([state-machine.md:55](../architecture-baseline/state-machine.md#L55)).
- §1 Payment.status: CANCELLED = "Claim 환불 완료(Refund.COMPLETED)"([state-machine.md:22](../architecture-baseline/state-machine.md#L22)).

### 2.3 결정 (decisions.md) — Refund 자동 트리거 carry-over 4연속 이연
- **D-87 Q3**: Admin API 별도 트랙·Refund 자동 트리거 원천 이연([decisions.md:2845](../architecture-baseline/decisions.md#L2845)).
- **D-90 Q2 γ**: ClaimApprovedHandler 미신설. "Refund 자동 트리거는 D-87 Q3 이연 정합"([decisions.md:3205-3208](../architecture-baseline/decisions.md#L3205-L3208)). 대안 기각: "Q2 α RefundService.initiate 자동 트리거 → 과잉개발·기각"([decisions.md:3358](../architecture-baseline/decisions.md#L3358)).
- **D-92 Q8 β**: "Refund 자동 트리거 본 PR 미포함. 운영 절차 박제: REQUESTED → APPROVED(Seller) → REFUND_PENDING → Refund 생성(Admin/Job). **Refund Service 트랙 진입 시점에 ClaimApproved → RefundCreated 자동 변환을 구성한다**"([decisions.md:3455-3456](../architecture-baseline/decisions.md#L3455-L3456)).
- **D-93 Q9 β**: "D-90 Q2·D-92 Q8 β carry-over. **ClaimApproved 소비자 부재 확정**·Refund Service 트랙 진입 시점 자동 변환"([decisions.md:3540-3541](../architecture-baseline/decisions.md#L3540-L3541)). WARN-4 해소: "ClaimApproved 소비자 부재 → Q9 β carry-over"([decisions.md:3560](../architecture-baseline/decisions.md#L3560)).
- **D-91**: Hibernate 전체 컬럼 UPDATE FK 재검증 트랩·통합 테스트 seed FK 부모 그래프 신설 의무([decisions.md:3387-3398](../architecture-baseline/decisions.md#L3387)).

### 2.4 Aggregate 경계 (aggregate-boundary.md §2.5)
- `Claim`(Root) = 포함 엔티티 `Refund`·외부 ID 참조 `OrderItem.id, Payment.id`([aggregate-boundary.md:74](../architecture-baseline/aggregate-boundary.md#L74)).
- "Refund는 Claim 승인 후에만 생성, Claim과 생명주기 공유 → Claim 포함"([aggregate-boundary.md:80](../architecture-baseline/aggregate-boundary.md#L80)).

### 2.5 라이브 트랩 (live-traps.md)
- **LT-01**: CHAR(N) public_id `@JdbcTypeCode(SqlTypes.CHAR)` 미적용 트랩·abstract 적용 Entity 목록에 **Refund 포함**([live-traps.md:52](../troubleshooting/live-traps.md#L52)) → AbstractPublicIdFullAuditableEntity 상속으로 자동 처치.
- **LT-02**: `SET FOREIGN_KEY_CHECKS=0` HikariCP 잔류·try-finally 복원 의무([live-traps.md:56-90](../troubleshooting/live-traps.md#L56-L90)).
- **LT-03**: @SQLRestriction 비전파(HHH-17453)·Refund는 soft-delete 대상 아님(ARCHIVE)·해당 표 미포함 → 무관.

### 2.6 Track 5 expected-spec (Refund 원 설계)
- §1.2 Out-of-Scope: "Claim 승인/거절 워크플로우"·"부분환불(amount 단일 행 기준)"·"운영자 수동 환불 보정" 명시 이연([track-5/expected-spec.md:28-37](../track-5/expected-spec.md#L28-L37)).
- §6.1 흐름: `[운영자/Claim 승인 시스템] → RefundService.initiate`([track-5/expected-spec.md:153-156](../track-5/expected-spec.md#L153-L156)) — **initiate 호출자가 추상·이연 상태로 박제됨**.
- §5 메서드: `initiate(claimId, amount)` — amount는 **호출자 공급 파라미터**([track-5/expected-spec.md:129](../track-5/expected-spec.md#L129)).

---

## §3. Refund 도메인 현황 (STEP 2 실측)

`backend/src/main/java/com/zslab/mall/refund/` 11파일 전건 full read. **`handler/` 패키지 부재**(controller·entity·enums·event·exception·repository·service만 존재).

| 파일 | 라인 | 핵심 |
|---|---|---|
| entity/Refund.java | 165 | Aggregate member·`create`/`assignPgRefundId`/`markCompleted`/`markFailed`/`pullDomainEvents`. RFN-1 방어선(L132). rfn_ public_id·claimId·paymentId ID 참조 |
| enums/RefundStatus.java | 31 | 3값 PENDING·COMPLETED·FAILED·`canTransitionTo`(RFN-2) |
| enums/RefundCallbackStatus.java | 14 | SUCCESS·FAIL (webhook 페이로드 enum·DB 컬럼 아님) |
| service/RefundService.java | 231 | **initiate·markCompleted·markFailed·handleCallback·findByClaimId** (§3.1) |
| repository/RefundRepository.java | 38 | findByPublicId·findByPgRefundId·findByClaimId·sumCompletedByPaymentId(PAY-1) |
| event/RefundCompleted.java | 25 | record(refundId·claimId·paymentId·amount·refundedAt)·D-69 |
| controller/RefundWebhookController.java | 37 | `POST /api/webhooks/refunds`·handleCallback 위임 |
| controller/request/RefundCallbackRequest.java | 21 | pgRefundId·status·failureReason DTO(@NotBlank·@NotNull) |
| exception/RefundIdempotentNoOpException.java | 15 | RFN-3 멱등 시그널 |
| exception/RefundInvariantViolationException.java | 17 | PAY-1·RFN-1 위반 |
| exception/RefundNotFoundException.java | 11 | pg_refund_id·refundId 미매칭 |

### 3.1 RefundService.initiate — CLM-3 기구현 핵심 ([RefundService.java:82-122](../../backend/src/main/java/com/zslab/mall/refund/service/RefundService.java#L82-L122))
순서: ① CLM-3 `claim.getStatus() != APPROVED` 차단(L93-96) → ② payment 해소 `claim→orderItem→order→PAID Payment`(L99·`resolvePaidPayment`) → ③ PAY-1 사전 `Σ(COMPLETED)+amount ≤ Payment.amount`(L102-106) → ④ Refund PENDING INSERT(L109-110) → ⑤ `paymentGateway.refund(pgTid, amount)` → `assignPgRefundId`(L114-115) → ⑥ PG 예외 시 `markFailed()`(D-67·L116-120).

**클래스 Javadoc 실측**(L31-33): "REST 엔드포인트로는 노출하지 않으며(initiate는 **후속 승인 시스템·테스트 호출**), 콜백 수신만 RefundWebhookController가 위임한다." → **initiate 자동 호출자가 없음을 코드 주석이 직접 명시**.

---

## §4. Claim ↔ Refund 연결 지점 (STEP 3 실측)

### 4.1 ClaimApproved 발행처 (존재)
`ClaimService.approve(claimId, processedAt)`가 save 직후 발행([ClaimService.java:143-145](../../backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java#L143-L145)). 외부 진입점: `approveBySeller`(L179)·`approveByAdmin`(L215) wrapper가 `approve` primitive 경유. payload = `claimId·claimPublicId·orderItemId·claimType·status·occurredAt` — **`amount` 필드 없음**([ClaimApproved.java:19-26](../../backend/src/main/java/com/zslab/mall/claim/event/ClaimApproved.java#L19-L26)).

### 4.2 ClaimApproved 소비자 = 부재 확정 (grep 실측)
`ClaimApproved` 전 참조 grep 결과 main 코드 소비자 **0건**:
- 발행: ClaimService.java:143 · 정의: ClaimApproved.java
- 소비: **없음** (claim/handler·payment/handler·refund/handler 어디에도 핸들러 없음)
- 테스트 참조는 전부 발행 검증·테스트 로컬 리스너뿐: ClaimServiceTest(ArgumentCaptor 발행 검증)·SellerClaimIntegrationTest/AdminClaimIntegrationTest(발행 횟수 count)·ClaimIntegrationTest(테스트 로컬 `onApproved` 수집 리스너)

ClaimApproved Javadoc 자체가 박제([ClaimApproved.java:15-17](../../backend/src/main/java/com/zslab/mall/claim/event/ClaimApproved.java#L15-L17)): "**소비자는 아직 부재다**... 후속 트랙(Refund Service 진입 시점)에서 `ClaimApproved → RefundCreated` 자동 변환을 구성할 예정이다."

### 4.3 claim/handler/ 전건 (4파일·ClaimApprovedHandler 부재)
| 핸들러 | 소비 이벤트 | 동작 | 트랜잭션 |
|---|---|---|---|
| ClaimRequestedHandler | ClaimRequested | OrderItem → CANCEL_REQUESTED·재계산 | AFTER_COMMIT·REQUIRES_NEW |
| ClaimRejectedHandler | ClaimRejected | OrderItem CANCEL_REQUESTED → PAID(claim-lock release·D-90 Q3) | AFTER_COMMIT·REQUIRES_NEW |
| ClaimCompletedHandler | ClaimCompleted | OrderItem → CANCELLED·재계산 | AFTER_COMMIT·REQUIRES_NEW |
| ClaimRefundCompletedHandler | **RefundCompleted** | CANCEL·APPROVED Claim → markCompleted | AFTER_COMMIT·REQUIRES_NEW |

→ **ClaimApprovedHandler 없음**. 전 핸들러 공통 패턴: `if (claimType != CANCEL) return;` type 게이트 + 상태 멱등 가드.

### 4.4 역방향 루프 (기구현·완결)
`Refund.markCompleted` → `RefundCompleted` 발행([RefundService.java:159-162](../../backend/src/main/java/com/zslab/mall/refund/service/RefundService.java#L159-L162)) → 2 소비자:
1. `ClaimRefundCompletedHandler`(CANCEL·APPROVED) → `claimService.markCompleted` → Claim COMPLETED → `ClaimCompleted` → `ClaimCompletedHandler` → OrderItem CANCELLED([ClaimRefundCompletedHandler.java:39-58](../../backend/src/main/java/com/zslab/mall/claim/handler/ClaimRefundCompletedHandler.java#L39-L58)).
2. `PaymentRefundCompletedHandler` → `paymentService.markCancelled` → Payment CANCELLED(§5.2).

**결론**: PENDING Refund 생성 이후의 모든 하류 흐름은 완결 상태. 결손은 오직 "PENDING Refund를 만들어 줄 forward 트리거"다.

---

## §5. Payment ↔ Refund 연결 지점 (STEP 4 실측)

### 5.1 PaymentGateway.refund (존재)
`MockRefundResponse refund(String paymentPgTid, Long amount)`([PaymentGateway.java:33](../../backend/src/main/java/com/zslab/mall/payment/gateway/PaymentGateway.java#L33))·구현 `MockPaymentGateway.refund`는 `mock_rfn_`+ULID 합성·항상 접수 성공([MockPaymentGateway.java:42-48](../../backend/src/main/java/com/zslab/mall/payment/gateway/MockPaymentGateway.java#L42-L48))·실패 시 `PaymentGatewayException`(D-67 트리거). `MockRefundResponse(pgRefundId·success·failureReason)` record 존재.

### 5.2 RefundCompleted → Payment 취소 (존재)
`PaymentRefundCompletedHandler`([PaymentRefundCompletedHandler.java:33-38](../../backend/src/main/java/com/zslab/mall/payment/handler/PaymentRefundCompletedHandler.java#L33-L38)) → `paymentService.markCancelled(paymentId)`. markCancelled는 **D-71 전액 일치 시에만 CANCELLED·부분환불/멱등 no-op**([PaymentService.java:188-204] 실측). `Payment.cancel()`(PAID→CANCELLED) 존재.
PAY-1 사후 비관적 락: `paymentRepository.findByIdForUpdate`(@Lock PESSIMISTIC_WRITE·D-68)·PAID 해소 `findFirstByOrderIdAndStatusOrderByIdDesc` 모두 존재.

### 5.3 Payment 환불 전용 이벤트 = 부재 (실측)
`payment/event/`에는 `PaymentCompleted·PaymentFailed`만 존재. **`PaymentRefunded`·`PaymentRefundFailed` 이벤트 없음**(정찰 가설 falsified). 환불 완료 통지는 `refund/event/RefundCompleted` 단일 채널로 양 도메인이 소비한다. 별도 refund webhook endpoint도 payment 패키지에 없음(refund/controller/RefundWebhookController 단일).

---

## §6. DDL·Flyway 현황 (STEP 5 실측)

migration 5건: V1__init·V2__seller_anonymization·V3__payment_track3·V4__order_idempotency_key·**V5__refund_constraints**.

### 6.1 refund 테이블 ([V1__init.sql:688-705](../../backend/src/main/resources/db/migration/V1__init.sql#L688-L705))
```
id BIGINT AI · public_id CHAR(30) · claim_id BIGINT NOT NULL · payment_id BIGINT NOT NULL
amount BIGINT NOT NULL · status ENUM('PENDING','COMPLETED','FAILED') NOT NULL · refunded_at DATETIME(6) NULL
pg_refund_id VARCHAR(100) NULL · created/updated 감사컬럼
UNIQUE uk_refund_public_id · FK fk_refund_claim→claim ON DELETE RESTRICT · FK fk_refund_payment→payment ON DELETE RESTRICT
```

### 6.2 V5 제약 ([V5__refund_constraints.sql](../../backend/src/main/resources/db/migration/V5__refund_constraints.sql))
- CR-13: `idx_refund_payment_status(payment_id, status)` — PAY-1 누적 조회 최적화
- CR-14: `chk_refund_completed_at` CHECK — PENDING→refunded_at NULL / COMPLETED→NOT NULL / FAILED→무제약(D-70)
- CR-15: `uk_refund_pg_refund_id` UNIQUE(pg_refund_id) — webhook 멱등성(RFN-3·D-76·NULL 다건 허용)
- ROLLBACK 보상 마이그레이션 주석 박제(CLAUDE.md Flyway rollback 룰 정합)

### 6.3 4층위 enum 잠금 (refund.status) — 완비
(1) DB ENUM 3값 ✓ · (2) Java `RefundStatus` @Enumerated(STRING) ✓ · (3) DTO — refund.status 외부 미노출·webhook은 `RefundCallbackStatus` @NotNull 검증 ✓ · (4) 프론트 — 해당 없음(Refund 프론트 미존재). **LT-01**: public_id CHAR(30)는 AbstractPublicIdFullAuditableEntity 상속으로 자동 처치(Refund 직접 @JdbcTypeCode 미선언·정합).

---

## §7. 테스트 현황 (STEP 6 실측)

| 테스트 | 케이스 | 비고 |
|---|---|---|
| RefundServiceTest | 11 (Mockito) | initiate 4(CLM-3·PAY-1 사전·D-67·정상)·markCompleted 5(RFN-1·RFN-3·PAY-1 사후·D-70·발행)·markFailed 2. **initiate 직접 호출·amount=상수**(자동 트리거 부재 방증) |
| RefundWebhookIntegrationTest | 6 E2E (실 MariaDB) | initiate→webhook→COMPLETED/FAILED·RFN-3 중복·RFN-2 재시도·PAY-1 초과·RETURN 미전이. **전체 역방향 루프 통과 검증** |
| RefundStatusTest | 4 | 3×3 전이 매트릭스·종결 불가역·3값 DDL 정합 |
| ClaimEventIntegrationTest | 4 | Requested/Rejected/Completed 핸들러 e2e. **ClaimApproved 소비 테스트 없음**(확정) |

### 7.1 LT-02 처치 편차 (실측·§9 WARN-5)
- **RefundWebhookIntegrationTest(Track 5·LT-02 이전)**: seed()/cleanup() `SET FOREIGN_KEY_CHECKS=0`·**try-finally 복원 없음**([RefundWebhookIntegrationTest.java:172-201](../../backend/src/test/java/com/zslab/mall/refund/controller/RefundWebhookIntegrationTest.java#L172-L201)). order_item이 PAID로 시드되어 ClaimCompletedHandler 가드가 UPDATE를 건너뛰므로 D-91은 회피(decisions.md:3394 명시).
- **ClaimEventIntegrationTest(Track 9 PR-C·LT-02 이후)**: try-finally `=1` 복원 + FK 부모 그래프 시드(D-91)·정합 패턴([ClaimEventIntegrationTest.java:163-229](../../backend/src/test/java/com/zslab/mall/claim/integration/ClaimEventIntegrationTest.java#L163-L229)). → **신규 통합 테스트는 후자 패턴을 따라야 함**.

---

## §8. 의제 (Q0~Q10·결정 라운드 입력)

> 각 의제는 §1~§7 실측 근거를 인용한다. 추천안은 사용자 4 기조(운영 용이성·객관 판단·과잉문서 회피·과잉개발 회피) 정합 후보이며 확정 아님.

### Q0 (META·P0): 트랙 식별자 충돌 — 사용자 결정 필수
D-88:2997·D-89:3176·D-90:3373·D-93:3610은 **"Track 11 = Claim RETURN/EXCHANGE 확장"**으로 일관 박제. 반면 "**Refund Service 트랙**"은 D-92:3494·D-93:3608에 **별도·미번호 트랙**으로 박제. 본 작업 프롬프트는 이를 "Track 11 Refund Service"로 호칭 → **번호 충돌**.
- 옵션 α: 본 트랙 = "Refund Service 트랙"(미번호·RETURN/EXCHANGE가 Track 11 유지) — 기존 결정 박제와 정합
- 옵션 β: 본 트랙을 Track 11로 재정의·RETURN/EXCHANGE를 Track 12+로 재배치 — 4개 결정 후속 목록 정정 동반
- **영향**: 신규 D-XX 결정 번호·PR 명·후속 목록 정합. **조용히 선택 불가**(CLAUDE.md 가정 표면화 의무).

### Q1 (P0): ClaimApproved 핸들러 위치
실측 패턴: 소비 핸들러는 **반응 도메인 패키지**에 위치(RefundCompleted 소비자가 claim/handler·payment/handler에 분산·§4.3·§5.2).
- 옵션 α (**추천**): `refund/handler/ClaimApprovedHandler`(신규 패키지) — "반응 도메인 = Refund" 정합·ClaimRefundCompletedHandler 대칭
- 옵션 β: `claim/handler/ClaimApprovedHandler` — Claim 이벤트 핸들러 군집성·단 Refund 의존을 claim 패키지로 역유입
- 옵션 γ: 핸들러 없이 RefundService가 @TransactionalEventListener 직접 보유 — Service에 이벤트 소비 책임 혼입(기존 패턴 위배)

### Q2 (P1): RefundCreated 이벤트 신설 여부
실측: `RefundCreated` 이벤트 **부재**·RefundService.initiate는 동기 PG 호출까지 자기완결·현재 RefundCreated 소비자 없음.
- 옵션 α (**추천**): 신설 안 함 — 핸들러가 initiate() 직접 호출·기조 4. D-naming "RefundCreated 자동 변환"은 변환 행위 서술이지 이벤트 신설 명령 아님
- 옵션 β: RefundCreated 신설 — NotificationLog/Observability 미래 소비자용·해당 트랙 진입 시점 이연이 자연

### Q3 (P1): Refund 진입점 범위
- 옵션 α (**추천**): ClaimApproved 자동 트리거 **단독** — D-90 Q2 핵심 이연분 해소·state-machine §8 "운영자 수동 보정 권한 없음" 정합
- 옵션 β: Admin 수동 환불 생성 endpoint 병존 — D-92 Q8 "Refund 생성(Admin/Job)" 언급 흡수·단 범위 확장·"RefundAdjustment 후속 트랙" 명시와 충돌

### Q4 (확인·기결정): PG refund 호출 시점
initiate 내부 동기 호출(PENDING INSERT→PG refund→pg_refund_id 부여)·최종 확정은 webhook 비동기는 **D-72·D-73·[I1]·[I3]로 기결정·기구현**(§3.1). 자동 트리거 핸들러는 initiate 호출로 상속. → **신규 결정 불요·기결정 재확인만**.

### Q5 (확인·기결정): RefundStatus 상태머신
3값(PENDING·COMPLETED·FAILED)·CANCELLED 없음·DDL ENUM·enum·state-machine §8 3중 정합·RefundStatusTest 3×3 커버(§6.3·§7). → **누락값 없음·CANCELLED 불요**. (부분환불 신상태는 OOS·별도 RETURN/EXCHANGE 트랙 소관.)

### Q6 (P1): 멱등성 — 동일 ClaimApproved 재전달 중복 차단
실측 공백: 자동 트리거가 무가드면 ClaimApproved 재전달 시 중복 PENDING Refund 생성. (재승인은 CLM-4 `APPROVED→APPROVED` 불법이라 차단되나, **이벤트 재전달**이 잔존 위험.) RFN-2 "재시도=새 행"은 FAILED 재시도 의미이며 재전달 중복과 구분 필요.
- 옵션 α (**추천**): 핸들러가 `refundRepository.findByClaimId` 조회 후 PENDING·COMPLETED 존재 시 no-op skip — 기존 핸들러 상태 멱등 가드 패턴 정합·D-66 idempotency 기조
- 옵션 β: DB UNIQUE(claim_id) 제약 — RFN-2 재시도(FAILED 후 새 행)와 충돌·기각 후보

### Q7 (P0·신규 발견): 환불 amount 산정 출처
실측 공백: `initiate(claimId, amount)`는 amount 필수·`ClaimApproved` payload에 amount **없음**(orderItemId만 보유·§4.1). 자동 트리거는 amount를 도출해야 함.
- 옵션 α (**추천**): `OrderItem.totalPrice`(orderItemId 경유 조회·ORD-5 `total=unit×qty`·[OrderItem.java:59-60] 실측) — CANCEL 전건 환불 자연·Track 5 §1.2 "amount 단일 행 기준" 정합
- 옵션 β: ClaimApproved payload에 amount 필드 추가 — 이벤트 record·발행처 ClaimService 수정 동반
- 옵션 γ: Payment.amount 전액 — 멀티 OrderItem 주문에서 과환불 위험(PAY-1 의존)·부정합

### Q8 (P1): PG 환불 실패 시 보상 흐름
실측: initiate의 PG 호출 실패 → Refund FAILED(D-67)·**현재 FAILED에 반응하는 소비자 없음**·`RefundFailed` 이벤트 미발행(expected-spec §8:207). 자동 트리거 맥락에서 FAILED = "승인됐으나 환불 실패" → Claim은 APPROVED 잔류.
- 옵션 α (**추천**): Claim 상태 환원 없음·재시도 허용(운영자/Job 재 initiate·RFN-2)·CLM-3 정합(FAILED가 승인을 취소하지 않음). 운영 가시성(알림)은 NotificationLog 트랙 이연
- 옵션 β: RefundFailed 이벤트 신설 + Claim 보상 핸들러 — 범위 확장·Q2 β 동반·과잉개발 위험

### Q9 (P2·백로그 흡수): RefundWebhookIntegrationTest LT-02 보정 동반
실측: Track 5 테스트 try-finally 미적용(§7.1). 본 트랙이 refund 도메인 통합 테스트를 신규 추가하므로 동반 보정 저비용.
- 옵션 α (**추천**): 본 트랙 동반 보정(try-finally `=1` 복원 추가) — 라이브 트랩 차단·기조 1
- 옵션 β: 별도 트랙 이연 — D-89 백로그 명시와 정합하나 트랩 잔존 지속

### Q10 (P1): PR 분할 전략
실측: 잔여 범위 협소(핸들러 1 + amount 해소 + 멱등 가드 + 테스트 + D-XX 1건). D-87/D-92/D-93 1-PR-단독 패턴 정합.
- 옵션 α (**추천**): 단일 PR(핸들러·단위/통합 테스트·D-XX 박제)·S급 외부 검토 권장(CLM-3 정합 핵심)
- 옵션 β: 분할 — 범위 대비 과분할·기조 3 위배

---

## §9. WARN

| # | 우선 | 내용 | 근거 |
|---|---|---|---|
| WARN-1 | P0 | **트랙 식별자 충돌**: "Track 11"=RETURN/EXCHANGE(D-88~D-93) vs 프롬프트 "Track 11 Refund Service". 결정 번호·PR명·후속 목록 정정 리스크 | §8 Q0·decisions.md:2997·3494·3608·3610 |
| WARN-2 | P0 | **amount 출처 미정**: ClaimApproved에 amount 부재·initiate 필수. 미해결 시 자동 트리거 배선 불가 | §8 Q7·ClaimApproved.java:19·OrderItem.java:59 |
| WARN-3 | P1 | **멱등성 공백**: ClaimApproved 재전달 시 중복 PENDING Refund. RFN-2(재시도=새 행)와 재전달 중복 혼동 주의 | §8 Q6·기존 핸들러 가드 패턴 |
| WARN-4 | P1 | **FAILED 보상 부재**: 자동 트리거 Refund FAILED 시 Claim APPROVED 잔류·반응 소비자/알림 없음 | §8 Q8·expected-spec §8:207 |
| WARN-5 | P2 | **LT-02 잔존**: RefundWebhookIntegrationTest try-finally 미적용·후속 테스트 FK 오염 잠재 | §7.1·RefundWebhookIntegrationTest.java:172-201 |
| WARN-6 | P1 | **신규 통합 테스트 D-91/LT-02 의무**: e2e가 webhook 완료까지 구동 시 ClaimCompletedHandler가 order_item UPDATE → FK 부모 그래프 시드(D-91) + try-finally(LT-02) 필수. ClaimEventIntegrationTest 패턴 준용·RefundWebhookIntegrationTest 패턴 금지 | §7.1·decisions.md:3387 |
| WARN-7 | P2 | **CLM-4 재승인 차단 사실**: APPROVED→APPROVED 불법이라 재승인發 중복은 자연 차단·주 위험은 이벤트 재전달(WARN-3와 결합) | ClaimStatus.canTransitionTo·§8 Q6 |

### D-91 영향 범위 판정
- forward 트리거 자체(ClaimApproved→initiate)는 order_item을 UPDATE하지 않음(refund INSERT + refund/payment UPDATE만) → 그 경로 단독은 D-91 직접 영향 낮음.
- 단 e2e 테스트가 후속 webhook 완료까지 구동하면 ClaimCompletedHandler order_item UPDATE 발생 → **D-91 적용**. WARN-6 처치 의무.

---

## §10. 영향 범위 예측 (결정 라운드 전·미확정)

> 확정 박제는 결정 라운드(특히 Q0·Q1·Q7) 후. 아래는 추천안(α 다수) 가정 시 예측.

### 신규 (예측)
- `backend/.../refund/handler/ClaimApprovedHandler.java` (refund/handler 신규 패키지·Q1 α)
- `backend/.../refund/handler/ClaimApprovedHandlerTest.java` (단위·Mockito)
- `backend/.../refund/integration/...IntegrationTest.java` (e2e·D-91+LT-02 패턴·WARN-6)
- decisions.md D-XX 1건 (carry-over 4연속 종결 박제)

### 수정 (예측)
- `claim/event/ClaimApproved.java` Javadoc ("소비자 부재" → 소비자 명시·Q10 γ 패턴)
- (Q7 β 채택 시) ClaimApproved payload + ClaimService 발행부 — α 채택 시 무수정
- (Q9 α 채택 시) RefundWebhookIntegrationTest try-finally 보정

### 무변경 (예측·재사용)
- RefundService(initiate 기구현·재사용)·Refund·RefundStatus·RefundRepository·RefundCompleted·RefundWebhookController
- DDL/Flyway (amount=OrderItem.totalPrice·스키마 무변경 시)·PaymentGateway·Payment 도메인
- 역방향 루프 핸들러 전건(ClaimRefundCompletedHandler·PaymentRefundCompletedHandler·ClaimCompletedHandler)

---

> **정찰 요약**: Refund 도메인은 Track 5 선구현으로 사실상 완성(CLM-3·PAY-1·RFN-1~3·역방향 루프·DDL·테스트). 본 트랙 실 결손은 **ClaimApproved→initiate forward 트리거 핸들러 1건**으로 협소. 결정 라운드 핵심 의제는 Q0(트랙 식별자)·Q1(핸들러 위치)·Q7(amount 출처)·Q6(멱등성)·Q8(FAILED 보상). 의제 11건·WARN 7건.
