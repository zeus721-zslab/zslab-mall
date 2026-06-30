# Track 12 NotificationLog 정찰 보고서 (recon-report)

> 작성일: 2026-06-30 · 모드: read-only 정찰 (코드·git 무변경) · 라벨: A급 (횡단 인프라·이벤트 핸들러·외부 검토 선택)
> 산출물: 본 파일 1건 (그 외 파일 변경 없음·PROGRESS.md STEP 로그 제외)
> SoT 기준: main 8b5bc57 (Track 11 Refund 자동 트리거 PR #75·박제 PR #76 머지 직후·D-94 박제 완료)
> 패턴 준용: docs/track-11/recon-report.md (D-94 recon 1:1)

---

## §1. 트랙 배경·범위·OUT-OF-SCOPE

### 1.1 배경
Track 11(Refund Service·ClaimApproved→Refund 자동 트리거·D-94) 머지 직후 진입. 본 트랙의 명목 목표는 **NotificationLog 도메인 진입 — 도메인 이벤트를 소비해 알림 발송 이력(NotificationLog)을 적재하는 핸들러 구성**이다. NotificationLog는 D-86 Q3·D-90 §후속·D-94 Q8/§후속에 걸쳐 **반복 이연**된 carry-over다(§4 인용).

### 1.2 핵심 실측 결론 (정찰 룰 #6 — 선구현 확인)
**NotificationLog 도메인은 영속 계층까지만 선구현되었고, 적재·소비 로직은 전무다.** Track 7 Batch-3c(D-86)에서 Entity·Enum 2종·Repository·@DataJpaTest까지 박제되었으나(§2), **Service·Handler·Controller·도메인 이벤트는 0건**이다(§2.1·§3).

- `NotificationLog`는 production 코드 어디에서도 **인스턴스화·영속·주입되지 않는다**. main 코드 전 참조 grep 결과 소비처 0건(§3.3)·유일 인스턴스화는 테스트뿐(§2.4).
- `NotificationLog.create(...)`는 `status = PENDING` 고정 적재 정적 팩토리만 보유([NotificationLog.java:76-99](../../backend/src/main/java/com/zslab/mall/notification/entity/NotificationLog.java#L76-L99)). **PENDING→SENT/FAILED 전이 메서드는 부재**(create 외 도메인 행위 0).
- domain-events.md §2는 6개 이벤트(E1·E2·E4·E5·E9·E10)가 NotificationLog를 **소비 주체**로 박제하나(§4.1), **그 중 어느 이벤트에도 알림 핸들러가 없다**(§3.4). 더하여 **E4·E5·E10 3건은 이벤트 record 자체가 미존재**다(§3.5 — 신규 발견).

→ 본 트랙의 실 결손 = **"이미 존재하는 NotificationLog 영속 계층 위에, 발행처가 존재하는 이벤트(E1·E2·E9)를 소비해 NotificationLog를 PENDING 적재하는 핸들러(또는 Service+Handler) 신설"**이다.

### 1.3 OUT-OF-SCOPE (프롬프트 명시 + 정찰 보강)
- **PENDING→SENT 전이**: 실제 발송 성공 시 상태 전이 — 발송 어댑터 전제·본 트랙 미포함(D-86 §후속·NotificationLog.status 전이 핸들러 이연).
- **실 전송 어댑터**: EMAIL·SMS·PUSH·IN_APP 채널 실 송신 게이트웨이 — 외부 인프라 의존·이연.
- **DTO @ValidEnum**: NotificationChannel·NotificationLogStatus·PolymorphicTargetType의 DTO 형식 검증(4층위 잠금 3층) — D-86 §후속 Track 8+ 이연·NotificationLog는 외부 노출 DTO 부재(§6.3).
- **Micrometer 카운터·dashboard·alert**: D-94 Q8 보류분·Observability 표준 박제 부재(D-90 백로그)·기조 4.
- **(정찰 보강) E4·E5·E10 발행 도메인 신설**: DeliveryStarted·DeliveryCompleted·InventoryAdjusted 이벤트 자체가 부재(§3.5)·발행처 Delivery/Inventory 도메인 행위 신설은 별도 트랙(§8 Q4).
- **(정찰 보강) Observability 멱등 인프라**: correlationId/eventId·이벤트 저장소·Outbox(D-90 §후속 Track 12+ Observability·D-94 Q6 박제 1줄).

### 1.4 LIMITATION
- 본 보고서는 read-only 실측이며 구현·결정을 포함하지 않는다. §8 의제는 결정 라운드 입력이다.
- 영향 범위(§6)는 **결정 라운드 확정 전 예측**이며 확정 박제가 아니다.
- 본 트랙 식별자("Track 12 NotificationLog")는 기존 결정과 **충돌 소지**가 있다(§8 Q1·§9 WARN-6). D-90 §후속은 "Track 12+ Observability", D-94 §후속은 "Track 12+ RETURN/EXCHANGE"·"NotificationLog 트랙(미번호)"을 병기한다. 최종 식별자는 사용자 결정 사항이다.

---

## §2. NotificationLog 도메인 자산 실측 (STEP A)

`backend/src/main/java/com/zslab/mall/notification/` 전체 트리 = **production 4파일**(entity 1·enums 2·repository 1) + test 1파일. **handler·service·controller·event·dto·exception 패키지 전부 부재.**

| 파일 | 라인 | 핵심 |
|---|---|---|
| entity/NotificationLog.java | 100 | `AbstractCreatedOnlyEntity` 직접 상속(D-86 Q3·created_at·created_by 2컬럼)·정적 팩토리 `create()`만·필드 11(id·recipientUserId·channel·templateCode·targetType·targetId·title·content·status·sentAt·failedReason)·**public_id 없음** |
| enums/NotificationChannel.java | 14 | 4값 EMAIL·SMS·PUSH·IN_APP(A#16·NOT-2) |
| enums/NotificationLogStatus.java | 14 | 3값 PENDING·SENT·FAILED(A#17) |
| repository/NotificationLogRepository.java | 14 | `JpaRepository`·derived 2종(`findByTargetTypeAndTargetId`·`findByRecipientUserIdAndStatus`) |
| (test) repository/NotificationLogRepositoryTest.java | 103 | `Batch1DataJpaTestBase`·CRUD·polymorphic 조회·channel/status ENUM 위반 → `PersistenceException` 검증 5건 |

### 2.1 결손 식별 (정찰 룰 — 선구현 확인)
- **Service**: 부재. 적재 오케스트레이션·recipient/template 산정 로직 없음.
- **Handler**: 부재. notification/handler 패키지 자체 없음. 어떤 도메인 이벤트도 NotificationLog로 라우팅되지 않음.
- **Controller**: 부재. 알림 조회/발송 endpoint 없음(외부 API 미노출).
- **State transition 메서드**: 부재. `create()`가 PENDING 고정 적재만·SENT/FAILED 전이 메서드 없음([NotificationLog.java:76-99](../../backend/src/main/java/com/zslab/mall/notification/entity/NotificationLog.java#L76-L99)).
- **도메인 이벤트(NotificationXxx)**: 부재. NotificationLog는 이벤트 발행 주체가 아님(Aggregate 아님·§4.3).

### 2.2 NotificationLog.create 정적 팩토리 ([NotificationLog.java:76-99](../../backend/src/main/java/com/zslab/mall/notification/entity/NotificationLog.java#L76-L99))
시그니처: `create(recipientUserId, channel, templateCode, targetType, targetId, title, content)`. 필수값(channel·templateCode·targetType·targetId) null/blank 가드 후 `IllegalArgumentException`(L84-88). `status`는 인자 없이 **`PENDING` 고정**(L97)·`sentAt`·`failedReason`는 미설정(NULL). → 적재 호출자는 recipient·channel·templateCode·target·title·content **7개 인자를 모두 공급**해야 하나, 도메인 이벤트 payload엔 이 정보 대부분이 없다(§4.2·§9 WARN-3).

### 2.3 클래스 Javadoc 박제 실측 ([NotificationLog.java:21-28](../../backend/src/main/java/com/zslab/mall/notification/entity/NotificationLog.java#L21-L28))
"D-18·D-01 — Aggregate 아님·도메인 트랜잭션 주체 아님. 이벤트(E1·E2·E4·E5·E9·E10) 발송 이력 기록. ... **PENDING→SENT 전이 핸들러·DTO @ValidEnum은 Track 8+ 이연(D-86 §OUT-OF-SCOPE)**." → 본 트랙 OUT-OF-SCOPE 경계를 Entity Javadoc이 직접 박제.

### 2.4 테스트 인스턴스화 = 유일 생성처 ([NotificationLogRepositoryTest.java:26-30](../../backend/src/test/java/com/zslab/mall/notification/repository/NotificationLogRepositoryTest.java#L26-L30))
`createLog(...)` 헬퍼가 `NotificationLog.create(recipientUserId, channel, "TPL_ORDER_CREATED", targetType, targetId, "주문 완료", "주문이 완료되었습니다.")` 호출. **template_code·title·content를 테스트가 상수로 공급** = production 적재 시 산정 출처 미정의 방증(§9 WARN-3).

---

## §3. 이벤트·핸들러 인벤토리 전수 (STEP B)

`backend/src/main/java/com/zslab/mall/**/{event,handler}/*.java` 전건 glob·read.

### 3.1 도메인 이벤트 인벤토리 (8건·전건 record)
| 이벤트 | 발행처(Javadoc 실측) | payload 필드 | domain-events # |
|---|---|---|---|
| order/event/OrderPlaced | (미배선·"핸들러 후속 트랙 진입 시 구현"·[OrderPlaced.java:9](../../backend/src/main/java/com/zslab/mall/order/event/OrderPlaced.java#L9)) | publicId·orderId·occurredAt | E1 |
| payment/event/PaymentCompleted | PaymentService.handleCallback(D-29) | paymentId·orderId·amount·pgTransactionId·occurredAt | E2 |
| payment/event/PaymentFailed | Payment PENDING→FAILED | paymentId·orderId·failureCode·occurredAt | E3 |
| claim/event/ClaimRequested | ClaimService.request | claimId·claimPublicId·orderItemId·claimType·status·**buyerId**·occurredAt | E7 |
| claim/event/ClaimRejected | ClaimService.reject | claimId·claimPublicId·orderItemId·claimType·status·occurredAt | E8 |
| claim/event/ClaimApproved | ClaimService.approve(+Seller/Admin wrapper) | claimId·claimPublicId·orderItemId·claimType·status·occurredAt | (내부 전이·E 미지정) |
| claim/event/ClaimCompleted | ClaimService.markCompleted(D-90 Q4) | claimId·claimPublicId·orderItemId·claimType·status·occurredAt | E9 |
| refund/event/RefundCompleted | RefundService.markCompleted(D-69) | refundId·claimId·paymentId·amount·refundedAt | (역방향 루프) |

### 3.2 핸들러 인벤토리 (7건)
| 핸들러 | 소비 이벤트 | 트랜잭션 정책 | 반응 도메인 |
|---|---|---|---|
| payment/handler/OrderEventHandler | PaymentCompleted | **@EventListener 동기·동일 트랜잭션**(D-29·D-33) | Order(markPaid) |
| payment/handler/PaymentRefundCompletedHandler | RefundCompleted | @TransactionalEventListener AFTER_COMMIT·REQUIRES_NEW(D-75) | Payment(markCancelled) |
| claim/handler/ClaimRequestedHandler | ClaimRequested | AFTER_COMMIT·REQUIRES_NEW | Order(OrderItem → CANCEL_REQUESTED) |
| claim/handler/ClaimRejectedHandler | ClaimRejected | AFTER_COMMIT·REQUIRES_NEW | Order(CANCEL_REQUESTED → PAID·claim-lock release) |
| claim/handler/ClaimCompletedHandler | ClaimCompleted | AFTER_COMMIT·REQUIRES_NEW | Order(OrderItem → CANCELLED) |
| claim/handler/ClaimRefundCompletedHandler | RefundCompleted | AFTER_COMMIT·REQUIRES_NEW(D-69·D-75) | Claim(markCompleted) |
| refund/handler/ClaimApprovedHandler | ClaimApproved | AFTER_COMMIT·REQUIRES_NEW(D-94 Q1·D-75) | Refund(initiate) |

### 3.3 이벤트 ↔ 핸들러 매핑 표 (NotificationLog 관점)
| 이벤트 | 현재 소비 핸들러 | NotificationLog 핸들러 |
|---|---|---|
| OrderPlaced(E1) | **0건**(미배선) | **부재** |
| PaymentCompleted(E2) | OrderEventHandler | **부재** |
| PaymentFailed(E3) | **0건**(Inventory 핸들러 미구현) | (E3는 NotificationLog 소비 대상 아님) |
| ClaimRequested(E7) | ClaimRequestedHandler | (E7는 NotificationLog 소비 대상 아님) |
| ClaimRejected(E8) | ClaimRejectedHandler | (E8는 NotificationLog 소비 대상 아님) |
| ClaimApproved | refund/ClaimApprovedHandler | **부재**(ClaimApproved Javadoc: "NotificationLog 진입 시 Seller 승인 알림 source 추가 소비 가능"·[ClaimApproved.java:17](../../backend/src/main/java/com/zslab/mall/claim/event/ClaimApproved.java#L17)) |
| ClaimCompleted(E9) | ClaimCompletedHandler | **부재** |
| RefundCompleted | Claim·Payment 핸들러 2건 | **부재** |

### 3.4 NotificationLog 소비자 = 부재 확정 (grep 실측)
`NotificationLog`·`notificationLog` main 코드 전 참조 grep → 7파일이나, **로직 소비처 0건**:
- notification 패키지 자체 4파일(entity·repository·2 enum)
- `PolymorphicTargetType`(NOTIFICATION_LOG enum 값 보유·§6.3)
- `AbstractCreatedOnlyEntity`(Javadoc 적용 대상 언급)
- `ClaimApproved.java`·`OrderPlaced.java`(**Javadoc 텍스트 언급뿐**·코드 참조 아님)
→ NotificationLogRepository를 **주입하는 Service·Handler 0건**·`NotificationLog.create` **호출처 0건(테스트 제외)**.

### 3.5 domain-events.md 박제 6 이벤트 발행처 존재/미존재 실측 (신규 발견)
domain-events.md §2가 NotificationLog 소비를 박제한 6 이벤트 중 **3건은 이벤트 record 자체가 부재**다:
| 이벤트 | record 존재 | 발행처 | 판정 |
|---|---|---|---|
| E1 OrderPlaced | ✓ | 미배선(발행만) | PASS(이벤트 존재) |
| E2 PaymentCompleted | ✓ | PaymentService | PASS |
| **E4 DeliveryStarted** | **✗** | Delivery 도메인 행위 미구현 | **FAIL(이벤트 부재)** |
| **E5 DeliveryCompleted** | **✗** | Delivery 도메인 행위 미구현 | **FAIL(이벤트 부재)** |
| E9 ClaimCompleted | ✓ | ClaimService | PASS |
| **E10 InventoryAdjusted** | **✗** | Inventory 도메인 행위 미구현·domain-events "선택" 박제 | **FAIL(이벤트 부재·선택)** |

→ **NotificationLog가 즉시 배선 가능한 이벤트는 E1·E2·E9 3건뿐**. E4·E5·E10은 발행 도메인 신설 선행 필요(§8 Q4·§9 WARN-1·OUT-OF-SCOPE).

---

## §4. 박제 정합 (STEP C)

### 4.1 domain-events.md §2 — NotificationLog 소비 박제 전수
- **E1 OrderPlaced**: 소비 주체 "Inventory(예약), CartItem(소비/삭제), **NotificationLog(주문 접수 알림)**"·"알림 = 비동기"([domain-events.md:39-43](../architecture-baseline/domain-events.md#L39-L43)).
- **E2 PaymentCompleted**: "Order, Inventory(차감), **NotificationLog(결제 완료 알림)**"·"알림 = 비동기"·재시도·DLQ([domain-events.md:51-55](../architecture-baseline/domain-events.md#L51-L55)).
- **E4 DeliveryStarted**: "Order, **NotificationLog(발송 알림)**"·"알림 = 비동기"([domain-events.md:77-81](../architecture-baseline/domain-events.md#L77-L81)).
- **E5 DeliveryCompleted**: "Order, **NotificationLog(배송 완료 알림)**"·"알림 = 비동기"([domain-events.md:91-95](../architecture-baseline/domain-events.md#L91-L95)).
- **E9 ClaimCompleted**: "Order, Inventory(복구), Payment(CANCELLED), **NotificationLog**"·"알림 = 비동기"([domain-events.md:145-149](../architecture-baseline/domain-events.md#L145-L149)).
- **E10 InventoryAdjusted(선택)**: "**NotificationLog(품절 해제 등 — 선택)**"·"알림 연동 불필요 시 미발행 무방"([domain-events.md:159-165](../architecture-baseline/domain-events.md#L159-L165)).
- **공통**: 6 이벤트 전건 알림 소비를 **비동기·재시도·DLQ**로 박제(§1 멱등성·재시도 원칙·[domain-events.md:16-18](../architecture-baseline/domain-events.md#L16-L18)). → 신규 적재 핸들러는 **AFTER_COMMIT·REQUIRES_NEW 비동기 패턴 정합**(§5·§8 Q3).

### 4.2 D-86 Q3 — NotificationLog Entity 박제 + 후속 이연
- **Q3 결정**([decisions.md:2784](../architecture-baseline/decisions.md#L2784)): "notification_log abstract 매칭 = **AbstractCreatedOnlyEntity 직접 상속**·DDL public_id 없음·신규 abstract 불필요."
- **§후속**([decisions.md:2818-2819](../architecture-baseline/decisions.md#L2818-L2819)): "Track 8+ Application Service 진입 시 — **NotificationLog.status PENDING→SENT 전이 핸들러(E1·E2·E4·E5·E9·E10 이벤트 소비)**·DTO @ValidEnum PolymorphicTargetType·AuditLogAction·NotificationChannel·NotificationLogStatus." → 본 트랙이 이 후속의 진입점. 단 "PENDING→SENT 전이"·"DTO @ValidEnum"은 프롬프트 OUT-OF-SCOPE(§1.3).
- **Q4 PolymorphicTargetType**([decisions.md:2790](../architecture-baseline/decisions.md#L2790)): NOTIFICATION_LOG 포함 20값 공유 Enum·"DDL VARCHAR(50)·D분류 앱 검증·Track 8+ 진입 시 사용 값 부분집합 결정."

### 4.3 D-18 — NotificationLog Infra/Event Processing 재분류
- ([decisions.md:556-575](../architecture-baseline/decisions.md#L556-L575)) "NotificationLog를 Aggregate에서 **Infra/Event Processing**으로 재분류. 17 Aggregate → 16 + 1." Why: "**이벤트 소비 부산물로 불변식·전이가 없어** Aggregate 기준 부합 안 함." Alternative 기각: "Aggregate 유지 → 이벤트 소비 기록을 Aggregate로 취급(기각)."

### 4.4 aggregate-boundary §2.7 — Aggregate 아님 박제
- ([aggregate-boundary.md:96-106](../architecture-baseline/aggregate-boundary.md#L96-L106)) "NotificationLog는 도메인 트랜잭션 주체가 아니다 — **자체 비즈니스 불변식·상태 전이 규칙이 없다.** 다른 Aggregate가 발행한 이벤트(E1·E2·E4·E5·E9·E10)의 **소비 기록**(발송 이력)이다. ... ARCHIVE 유지."

### 4.5 D-90 §후속 — Track 12+ Observability·멱등성 백로그
- **§후속 자연 진입 트랙**([decisions.md:3374](../architecture-baseline/decisions.md#L3374)): "**Track 12+: Observability(이벤트 핸들러 멱등성·이벤트 저장소·correlationId/eventId 일괄 도입)**."
- **백로그 추가**([decisions.md:3351](../architecture-baseline/decisions.md#L3351)): "**이벤트 핸들러 멱등성 표준화(이벤트 저장소·재전달 인프라 도입 시점·Track 12+ Observability)**." → NotificationLog 적재 멱등성은 이 백로그와 정합·§8 Q6.

### 4.6 D-94 — NotificationLog 트랙 흡수 박제 (Track 11 직전)
- **Q2 α**([decisions.md:3656](../architecture-baseline/decisions.md#L3656)): "RefundCreated 미신설·향후 **NotificationLog/Observability 소비자 발생 시점에 추가 검토.**"
- **Q8 α**([decisions.md:3711](../architecture-baseline/decisions.md#L3711)): "Micrometer 보류·**NotificationLog 신설은 검토자 양차 기각·structured log + 향후 Observability 트랙 자연 흡수가 적정.**" → 본 트랙 진입 시점 D-94가 미리 "NotificationLog 별도 트랙" 위임.
- **§후속 트랙**([decisions.md:3790](../architecture-baseline/decisions.md#L3790)): "**NotificationLog 트랙: Refund FAILED 운영 알림 source·structured log → 알림 채널 자연 흡수.**" → 본 트랙이 D-94 Q8 structured log를 NotificationLog 채널로 승격하는 후속(§8 Q8).
- **§후속 트랙**([decisions.md:3788](../architecture-baseline/decisions.md#L3788)): "Track 12+ RETURN/EXCHANGE 라벨 자연 이동" — 트랙 번호 충돌 소지(§8 Q1·§9 WARN-6).

### 4.7 invariants §3.1 — NotificationLog 항목 존재 (NOT-1~3)
([invariants.md:177-182](../architecture-baseline/invariants.md#L177-L182)) "§3 Infra/Event Processing":
- **NOT-1**: append-only 발송 이력·Enforcement Domain·"발송 기록 위변조 차단."
- **NOT-2**: channel/status 값집합 잠금(A분류)·DB ENUM + enum.
- **NOT-3**: (target_type, target_id) 논리 참조·FK 없음·polymorphic(D분류)·Domain 화이트리스트 검증.
→ NOT-3 "화이트리스트 검증"은 적재 시 targetType 유효성 검증 책임 박제(§8 Q5 참고).

### 4.8 state-machine.md — NotificationLog 항목 **없음** (실측·정합)
state-machine.md 전문 grep 결과 NotificationLog/notification 매칭 **0건**. D-18·aggregate-boundary §2.7 "상태 전이 규칙 없음"과 정합(§4.3·§4.4). NotificationLog.status(PENDING·SENT·FAILED)는 **상태머신 박제 대상이 아니다** — 전이 규칙·canTransitionTo 없음·append 시 PENDING 고정(§2.2). → 본 트랙은 state-machine.md **무수정** 예상(§6).

---

## §5. 핸들러 패턴 정독 (STEP D·기존 산출물 1:1 인용)

### 5.1 payment/handler/OrderEventHandler — 동기·동일 트랜잭션 패턴 ([OrderEventHandler.java:30-37](../../backend/src/main/java/com/zslab/mall/payment/handler/OrderEventHandler.java#L30-L37))
- `@EventListener`(AFTER_COMMIT 아님)·발행자와 **같은 트랜잭션**·실패 시 결제 전이까지 롤백(Javadoc L12-14).
- **핵심 차이**: 이 패턴은 "정합성이 깨지면 안 되는 동기 변경"(재고·OrderItem)용. **알림은 비동기**(domain-events §1·§4.1)라 NotificationLog 핸들러는 이 패턴을 따르면 안 된다(§8 Q3·§9 WARN — E2에 동기 핸들러 1건 + 비동기 알림 핸들러 1건 혼재 주의).

### 5.2 claim/handler/ClaimRefundCompletedHandler — AFTER_COMMIT·REQUIRES_NEW 표준 ([ClaimRefundCompletedHandler.java:39-58](../../backend/src/main/java/com/zslab/mall/claim/handler/ClaimRefundCompletedHandler.java#L39-L58))
- `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)`(D-69·D-75).
- 멱등 가드 패턴: `findById().orElse(null)` null 가드 → type 게이트(`if (type != CANCEL) return;`) → 상태 멱등 가드(`if (status != APPROVED) return;`) → 본 행위. 각 스킵에 `log.info`/`log.warn`.
- **NotificationLog 적재 핸들러 후보 패턴**: 비동기 알림 정합·주문/결제/클레임 흐름 비차단·실패 격리(§8 Q3 α 근거).

### 5.3 refund/handler/ClaimApprovedHandler — Track 11 최신 패턴 (D-94 Q1·Q6·Q8) ([ClaimApprovedHandler.java:44-64](../../backend/src/main/java/com/zslab/mall/refund/handler/ClaimApprovedHandler.java#L44-L64))
- 위치(D-94 Q1 α): **반응 도메인 패키지**(`refund/handler`)에 배치·RefundCompleted 소비자 분산 패턴과 1:1 대칭·역의존 회피(Javadoc L21-22).
- type 게이트(`if (claimType != CANCEL) return;` + log.info)·target null 방어(`orderItemRepository.findById(...).orElse(null)` + log.warn)·핵심 행위(`refundService.initiate`)·**실패 격리 catch**(`catch (RuntimeException) { log.warn("...action=manual_retry_required", ..., exception); }`·D-94 Q8).
- 멱등 위임(D-94 Q6): 핸들러는 가드 중복 안 하고 Service 내부 게이트에 위임(Javadoc L30-31).
- **본 트랙 핸들러 후보 도출**: 위치 = **반응 도메인 = Notification → `notification/handler/` 신규 패키지**(Q1 α 대칭·§8 Q2 α). 트랜잭션 = AFTER_COMMIT·REQUIRES_NEW(§8 Q3 α). 멱등 게이트 = append-only 특성상 재검토(§8 Q6). 실패 격리 = structured log catch(§8 Q7).

---

## §6. 영향 범위 예상 (결정 라운드 전·미확정)

> 확정 박제는 결정 라운드(특히 Q1·Q2·Q4·Q5) 후. 아래는 추천안(α 다수) 가정 시 예측.

### 6.1 신규 (예측)
- `notification/handler/*Handler.java` — 이벤트별 N건 또는 통합 1건(§8 Q2·Q4 결정 종속). E1·E2·E9 한정 시 최대 3 소비 진입점.
- `notification/service/NotificationService.java`(예측·적재 오케스트레이션·recipient/template 산정·NotificationLogRepository 주입) — Q5 결정 종속(핸들러 직접 적재 vs Service 경유).
- `notification/integration/...IntegrationTest.java`(e2e·NO @Transactional·TransactionTemplate·D-91/LT-02 패턴·§7).
- `notification/handler/*HandlerTest.java`(단위·Mockito).
- decisions.md D-XX 1건(NotificationLog 트랙 진입·carry-over 종결 박제·사용자 직접 처리).

### 6.2 수정 (예측)
- 소비 대상 이벤트 Javadoc(소비자 명시·`ClaimApproved`·`ClaimCompleted`·`PaymentCompleted`·`OrderPlaced` — "NotificationLog 진입 시점" → "소비" 박제·D-94 패턴).
- (Q5 결정 종속) NotificationLog.create 인자 산정 보조가 Service 신설로 흡수되면 Entity 무수정.

### 6.3 무변경 확정 예측 (재사용)
- **DDL/Flyway 영향 0**: notification_log 테이블·channel/status ENUM·ix_notification_log_target 인덱스 **V1__init 기박제**([V1__init.sql:759-775](../../backend/src/main/resources/db/migration/V1__init.sql#L759-L775))·신규 컬럼·제약 0(적재만·스키마 무변경).
- NotificationLog Entity·NotificationChannel·NotificationLogStatus·NotificationLogRepository(적재 재사용).
- 도메인 이벤트 record payload(식별자만·소비측 재조회·D-30) — 단 §9 WARN-3(recipient/title/content 산정) 미해소 시 payload 확장 압력 발생 가능(§8 Q5 β).
- state-machine.md(§4.8·전이 규칙 없음·무수정)·aggregate-boundary.md(§2.7 기박제).
- PolymorphicTargetType(NOTIFICATION_LOG 외 20값 기존)·AbstractCreatedOnlyEntity.

### 6.4 4층위 enum 잠금 현황 (channel·status) — 2/4층 완비
(1) DB ENUM ✓(V1 channel 4값·status 3값) · (2) Java backed enum ✓(@Enumerated STRING·NotificationChannel·NotificationLogStatus) · (3) DTO @ValidEnum — **부재**(NotificationLog 외부 DTO 미노출·D-86 Track 8+ 이연·OUT-OF-SCOPE) · (4) 프론트 constants — **부재**(Refund와 동일·프론트 미존재·OUT-OF-SCOPE). → CLAUDE.md "신규 type/status 4층위" 의무는 **신규 컬럼** 대상·notification_log.channel/status는 Track 7 기존 컬럼이라 DB+Java 2층 완비로 점진 정합(§8 Q8·§9 WARN-7).

---

## §7. 회귀 위험 평가

| 영역 | 위험 | 근거·완화 |
|---|---|---|
| 기존 주문/결제/클레임 흐름 | **저** | 적재 핸들러를 AFTER_COMMIT·REQUIRES_NEW 비동기로 두면 원 트랜잭션 비차단(§5.2·domain-events "알림=비동기") |
| E2 PaymentCompleted 동기/비동기 혼재 | **중** | OrderEventHandler가 **동기**(@EventListener) 소비 중([OrderEventHandler.java:30](../../backend/src/main/java/com/zslab/mall/payment/handler/OrderEventHandler.java#L30)). 같은 이벤트에 비동기 알림 핸들러 추가 시 두 리스너 공존·순서·실패 격리 검증 필요(§9 WARN) |
| D-91(통합 테스트 FK 부모 그래프) | **저~중** | notification_log는 **FK 없음**(논리참조·NOT-3·[V1__init.sql:761-765](../../backend/src/main/resources/db/migration/V1__init.sql#L761-L765))·적재 자체는 D-91 직접 영향 낮음. 단 e2e가 주문/결제/클레임 부모 그래프를 시드하면 ClaimEventIntegrationTest 패턴(FK 부모 그래프 시드) 준용 의무(§9 WARN) |
| LT-02(FK_CHECKS 잔류) | **저** | notification_log FK 없어 SET FOREIGN_KEY_CHECKS=0 불요·단 e2e가 타 테이블 시드에 사용 시 try-finally 복원 의무([live-traps.md:56-90](../troubleshooting/live-traps.md#L56-L90)) |
| LT-01(public_id @JdbcTypeCode) | **없음** | NotificationLog public_id 없음·AbstractCreatedOnlyEntity 상속·무관([live-traps.md:52](../troubleshooting/live-traps.md#L52) 적용 목록 미포함) |
| LT-03(@SQLRestriction 비전파) | **없음** | NotificationLog soft-delete 아님(ARCHIVE)·LT-03 표 미포함([live-traps.md:133-142](../troubleshooting/live-traps.md#L133-L142)) |
| 멱등성·재전달 중복 적재 | **중** | append-only ARCHIVE라 중복 INSERT가 이력 오염·D-90 백로그 멱등성 표준화 미도입(§8 Q6·§9 WARN-4) |

---

## §8. 결정 의제 (Q1~Q8·결정 라운드 입력)

> 각 의제는 §1~§7 실측 근거를 인용한다. 추천안은 사용자 4 기조(운영 용이성·객관 판단·과잉문서 회피·과잉개발 회피) 정합 후보이며 확정 아님·결정 권한은 Claude.ai·사용자에 위임.

### Q1 (META·P0): 트랙 식별자 — "Track 12 NotificationLog" 충돌 소지
D-90 §후속은 "Track 12+ Observability", D-94 §후속은 "Track 12+ RETURN/EXCHANGE"·"NotificationLog 트랙(미번호)"을 병기(§4.5·§4.6). "Track 12 = NotificationLog"는 세 라벨과 긴장.
- 옵션 α (**추천**): Track 12 = NotificationLog로 확정·RETURN/EXCHANGE·Observability를 Track 13+로 자연 이동(D-94 Q0 β 선례 — 트랙 번호 = 진입 순서 라벨·도메인 영구 바인딩 아님)
- 옵션 β: 미번호 "NotificationLog 트랙"(D-94 §후속 표기 유지)·RETURN/EXCHANGE가 Track 12 유지 — 추적 식별자 부재(D-94 Q0 α 기각 사유 재현)
- **영향**: 신규 D-XX·PR명·branch명 정합. 조용히 선택 불가(CLAUDE.md 가정 표면화 의무).

### Q2 (P1): NotificationLog 적재 핸들러 위치
실측 패턴: 소비 핸들러는 **반응 도메인 패키지**에 위치(D-94 Q1 α·ClaimApprovedHandler가 refund/handler·§5.3).
- 옵션 α (**추천**): `notification/handler/` 신규 패키지(반응 도메인 = Notification 정합·ClaimApprovedHandler 대칭·횡단 인프라 응집)
- 옵션 β: 각 발행 도메인 패키지(order/payment/claim handler에 알림 핸들러 분산) — 도메인별 군집·단 notification 의존 역유입·횡단 책임 분산
- 옵션 γ: NotificationService가 @TransactionalEventListener 직접 보유 — Service에 이벤트 소비 책임 혼입(D-94 Q1 γ 기각 패턴)

### Q3 (P1): 적재 트랜잭션 정책
domain-events.md 박제: 알림 소비 = **비동기·재시도·DLQ**(§4.1).
- 옵션 α (**추천**): @TransactionalEventListener AFTER_COMMIT·REQUIRES_NEW(§5.2·§5.3 표준·원 흐름 비차단·실패 격리·기조 1)
- 옵션 β: @EventListener 동기(OrderEventHandler 패턴·§5.1) — domain-events "알림=비동기" 위배·알림 실패가 주문/결제 롤백 유발·기각 후보

### Q4 (P0): 배선 대상 이벤트 범위 — 발행처 부재 이벤트 처리
실측: NotificationLog 박제 6 이벤트 중 **E4·E5·E10 record 부재**(§3.5).
- 옵션 α (**추천**): **발행처 존재 3건(E1 OrderPlaced·E2 PaymentCompleted·E9 ClaimCompleted)만 배선**·E4·E5·E10은 발행 도메인(Delivery·Inventory) 신설 트랙으로 명시 이연(OUT-OF-SCOPE·기조 4)
- 옵션 α′: E1·E2·E9 + ClaimApproved(Seller 승인 알림·D-94 Q2/ClaimApproved Javadoc 박제·[ClaimApproved.java:17](../../backend/src/main/java/com/zslab/mall/claim/event/ClaimApproved.java#L17)) 추가 — 박제된 알림 수요 흡수·단 domain-events 미박제 이벤트
- 옵션 β: 6 이벤트 전건 + Delivery·Inventory 이벤트 발행처 동반 신설 — 범위 폭발·다도메인 동시 수정·기조 4 위배·기각 후보

### Q5 (P0·신규 발견): NotificationLog 적재 인자 산정 출처
실측 공백: `create(...)`는 recipientUserId·channel·templateCode·title·content 7인자 필요(§2.2)·이벤트 payload는 **식별자·시각만**(§3.1·D-30). recipient_user_id·title·content·template_code 산정 로직 부재.
- 옵션 α (**추천**): `notification/service/NotificationService` 신설·이벤트 식별자로 **재조회**(orderId→buyer·orderItemId→상품명 등) + **templateCode 정적 매핑**(이벤트 type → TPL_*)·payload 무수정(D-30 정합·소비측 재조회 원칙)
- 옵션 β: 이벤트 payload에 recipient/title 필드 추가 — record·발행처 수정 동반·D-30 "사실 통지" 위배(D-94 Q7 β 기각 패턴)
- 옵션 γ: 최소 적재(recipient_user_id NULL 허용·title/content NULL·templateCode + target만) — DDL상 recipient/title/content NULL 허용([V1__init.sql:761·766-767](../../backend/src/main/resources/db/migration/V1__init.sql#L761))·MVP·후속 보강. **검토 가치 있음**(기조 4)

### Q6 (P1): 멱등성 — 동일 이벤트 재전달 중복 적재
실측 공백: 무가드 적재 시 이벤트 재전달이 중복 NotificationLog INSERT(append-only ARCHIVE 오염·§7). 현 인프라 = 인메모리 ApplicationEvent(D-94 Q6 박제·재전달 자연 발생 불가).
- 옵션 α (**추천**): 멱등 가드 미도입·append-only 특성 수용·D-90 백로그 "멱등성 표준화(이벤트 저장소·재전달 인프라 도입 시점)"로 이연(§4.5·D-94 Q6 박제 1줄 정합·기조 4). 박제 1줄: "외부 브로커·Outbox·@Async 도입 시 재검토"
- 옵션 β: (target_type, target_id, template_code) 존재 가드 — ClaimRefundCompletedHandler 상태 멱등 패턴 차용·단 정당 재발송(재알림) 수요와 충돌 소지

### Q7 (P1): 적재 실패 격리·보상
실측: 적재 핸들러가 NotificationLog INSERT 실패 시 원 흐름(주문/결제/클레임)과의 결합.
- 옵션 α (**추천**): AFTER_COMMIT·REQUIRES_NEW + **catch structured log**(ClaimApprovedHandler 패턴·§5.3·D-94 Q8)·알림 실패가 원 도메인 비차단·CLAUDE.md "heartbeat/알림 실패 묵살 시 주석 명시" 룰 정합
- 옵션 β: 실패 시 status=FAILED 적재 후 재시도 Job — 발송 어댑터 전제·OUT-OF-SCOPE(PENDING→SENT/FAILED 전이 이연)·범위 확장·기각 후보

### Q8 (P1): PR 분할·4층위 잠금 완성 범위
실측: 잔여 범위 = 핸들러(Service) + 적재 + 테스트 + D-XX. 4층위 잠금 DTO·프론트 층은 OUT-OF-SCOPE(§6.4).
- 옵션 α (**추천**): 단일 PR(핸들러·Service·단위/통합 테스트·이벤트 Javadoc·D-XX)·DTO @ValidEnum·프론트 constants는 D-86 §후속 유지 이연·A급 외부 검토 선택(D-87/D-92/D-94 1-PR 패턴)
- 옵션 β: 4층위 잠금(DTO @ValidEnum) 동반 완성 — NotificationLog 외부 API/DTO 부재라 검증 대상 없음·과잉개발·기각 후보
- 옵션 γ: 적재(본 PR) + 발송 어댑터·PENDING→SENT(후속 PR) 분할 명시 — OUT-OF-SCOPE 경계 박제(D-86 §후속 정합)

---

## §9. WARN 카탈로그

| # | 우선 | 내용 | 근거 |
|---|---|---|---|
| WARN-1 | P0 | **발행처 미존재 이벤트 3건**: domain-events §2가 NotificationLog 소비로 박제한 E4 DeliveryStarted·E5 DeliveryCompleted·E10 InventoryAdjusted의 **이벤트 record 자체 부재**. 배선 불가·발행 도메인(Delivery·Inventory) 신설 선행 필요 | §3.5·§4.1·glob 실측 |
| WARN-2 | P1 | **OrderPlaced(E1) 소비자 전무**: 현재 어떤 핸들러도 OrderPlaced 미소비(Javadoc "후속 트랙 진입 시 구현"). NotificationLog가 E1 첫 소비자가 될 경우 발행 검증 테스트 부재 | §3.3·[OrderPlaced.java:9](../../backend/src/main/java/com/zslab/mall/order/event/OrderPlaced.java#L9) |
| WARN-3 | P1 | **적재 인자 산정 출처 미박제**: create()는 recipient/title/content/templateCode 7인자 필요·이벤트 payload는 식별자·시각만(D-30). 재조회 또는 정적 매핑 로직 미정의 | §2.2·§2.4·§8 Q5 |
| WARN-4 | P1 | **멱등성 키 미박제**: append-only ARCHIVE 특성상 이벤트 재전달이 중복 NotificationLog 적재·D-90 백로그 멱등성 표준화 미도입 | §4.5·§7·§8 Q6 |
| WARN-5 | P2 | **발송 어댑터 부재**: EMAIL/SMS/PUSH/IN_APP 실 송신 미구현·status PENDING 영구 잔류·SENT/FAILED 전이 메서드 부재(OUT-OF-SCOPE 경계) | §2.1·§2.2·§1.3 |
| WARN-6 | P0 | **트랙 식별자 충돌**: "Track 12 NotificationLog" vs D-90 §후속 "Track 12+ Observability" vs D-94 §후속 "Track 12+ RETURN/EXCHANGE"·"NotificationLog 트랙(미번호)" | §4.5·§4.6·§8 Q1 |
| WARN-7 | P2 | **4층위 enum 잠금 2/4층**: channel·status DB+Java 완비·DTO @ValidEnum·프론트 부재(외부 DTO 미노출·D-86 Track 8+ 이연·CLAUDE.md 신규 컬럼 의무 대상 아님·기존 Track 7 산출물) | §6.4·[decisions.md:2819](../architecture-baseline/decisions.md#L2819) |
| WARN-8 | P1 | **D-94 흡수 시점 도래**: D-94 Q8/§후속이 "Refund FAILED structured log → NotificationLog 채널 자연 흡수"를 본 트랙에 위임([ClaimApprovedHandler.java:61](../../backend/src/main/java/com/zslab/mall/refund/handler/ClaimApprovedHandler.java#L61) structured log 잔존). 본 트랙이 흡수 결정 시 Refund 도메인 동반 수정 범위 검토 필요 | §4.6·decisions.md:3790·§8 Q4 |

### E2 동기/비동기 혼재 주의 (§7 재강조)
PaymentCompleted는 OrderEventHandler가 **@EventListener 동기** 소비 중([OrderEventHandler.java:30](../../backend/src/main/java/com/zslab/mall/payment/handler/OrderEventHandler.java#L30)). 비동기 알림 핸들러를 같은 이벤트에 추가 시 두 리스너 공존·실패 격리·실행 순서 검증 의무. NotificationLog 핸들러는 AFTER_COMMIT(§8 Q3 α)이라 동기 핸들러 커밋 후 실행 = 자연 분리되나 통합 테스트로 실측 권장.

---

## §10. 진입 조건 체크리스트

- [x] SoT 정독: CLAUDE.md·CLAUDE-DEV.md·decisions D-18/D-86/D-90/D-94·domain-events §1·§2·aggregate-boundary §2.7·invariants §3.1·state-machine(NotificationLog 부재 확인)·live-traps LT-01~03
- [x] NotificationLog 도메인 자산 실측(entity·2 enum·repository·test 5파일·Service/Handler/Controller/Event 부재 확정)
- [x] 이벤트·핸들러 인벤토리 전수(이벤트 8·핸들러 7·매핑 표)
- [x] NotificationLog 소비자 부재 확정(grep·인스턴스화 0건·테스트 제외)
- [x] domain-events 박제 6 이벤트 발행처 실측(E1·E2·E9 존재·E4·E5·E10 부재 — 신규 발견)
- [x] 핵심 핸들러 패턴 3건 정독(OrderEventHandler 동기·ClaimRefundCompletedHandler AFTER_COMMIT·ClaimApprovedHandler D-94)
- [x] DDL 영향 0 확인(notification_log V1 기박제·신규 컬럼·제약 0)
- [x] 결정 의제 Q1~Q8 도출(추천안 표시·결정 위임)
- [x] WARN 8건 카탈로그
- [ ] **결정 라운드 진입**(Q1 트랙 식별자·Q4 이벤트 범위·Q5 적재 인자 산정 = P0 선결)
- [ ] D-XX 박제(사용자 직접 처리)·구현 프롬프트 발행

---

> **정찰 요약**: NotificationLog 도메인은 Track 7(D-86)에서 영속 계층(Entity·Enum 2·Repository·Test)까지만 선구현·**적재/소비 로직 전무**(Service·Handler·Controller·Event 0건·인스턴스화 0건). 실 결손 = 발행처 존재 이벤트(E1·E2·E9)를 소비해 NotificationLog를 PENDING 적재하는 핸들러(Service) 신설. **핵심 발견**: domain-events 박제 6 소비 이벤트 중 E4·E5·E10 3건은 이벤트 record 자체 부재(배선 불가). 결정 라운드 P0 의제 = Q1(트랙 식별자)·Q4(이벤트 범위)·Q5(적재 인자 산정). DDL 영향 0·state-machine 무수정. 의제 8건·WARN 8건.
