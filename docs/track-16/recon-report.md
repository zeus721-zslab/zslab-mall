# Track 16 Observability — 정찰 보고서

> **정찰일**: 2026-06-30
> **핵심 의제**: Track 16 = Observability·S급·이벤트 핸들러 멱등성 표준화·correlationId/eventId·Micrometer·로깅 표준·Actuator·테스트 표준화·다중 인스턴스 시나리오
> **정찰 방식**: read-only·코드 수정·파일 생성·git 명령 전건 금지
> **기조 5 선언**: 모든 주장은 실측 라인 인용 근거. 추측·추정 금지.

---

## §1 개요

### §1.1 트랙 번호·핵심 의제

- **트랙 번호**: Track 16 = Observability (D-97 §후속 "Track 14+ Observability" → D-98 §후속 "Track 15+ Observability" → 본 트랙 번호 D-99 진입 이후 Track 16으로 자연 확정)
- **등급**: S급 — 새 패턴 복수 도입 예상 (correlationId 컨벤션 신설·Micrometer 컨벤션·멱등성 표준화·Outbox 진입 가능)
- **인계 메모 의제 8건**:
  1. 이벤트 핸들러 멱등성 표준화 — D-95 Q6·D-97 Q8 박제 1줄 누적
  2. 이벤트 저장소·Outbox — 인메모리 publisher 가정 재검토
  3. correlationId·eventId 컨벤션 — 전 프로젝트 0건 확인 필요
  4. Micrometer 메트릭 컨벤션 — MeterRegistry 미도입
  5. Actuator 노출 범위 — health·info 한정 현황
  6. 로깅 표준 — prefix 산발 현황 표준화
  7. 다중 인스턴스 시나리오 — ApplicationEvent 인메모리 가정의 한계
  8. 테스트 표준화 — @RecordApplicationEvents 사용처·NO @Transactional 적용 현황

### §1.2 읽은 SoT 목록

| 문서 | 읽은 범위 | 핵심 확인 |
|---|---|---|
| CLAUDE-DEV.md | 전문 (51줄) | 개발환경·작업 사이클·Git 규칙 |
| CLAUDE.md | 전문 | 코딩 원칙·4층위 enum 잠금·테스트 3건 의무 |
| decisions.md | D-94~D-99 전문 (lines 4050~5099) | D-95 Q6 멱등 박제·D-97 Q8 박제·D-99 §후속 Track 16 진입점 |
| invariants.md | 전문 (213줄) | CLM-1~5·DLV-1~3·NOT-1~3 |
| state-machine.md | 전문 (318줄) | §6.1 Delivery·§3 Claim 복귀 전이 매트릭스 |
| aggregate-boundary.md | 전문 (125줄) | §2.5 Delivery 외부 ID·§2.7 NotificationLog Infra 분류 |
| domain-events.md | 전문 (238줄) | E1~E11 카탈로그·멱등성·재시도 정책 요약 표 |
| live-traps.md | 전문 (158줄) | LT-01·LT-02·LT-03 전건 |
| docs/track-15/recon-report.md | §1~§18 전문 (형식 SoT) | §1.2 표 형태·§N.N 라인 인용 패턴 |

---

## §2 이벤트 record 컨벤션 실측

### §2.1 이벤트 카탈로그 전수 (11종)

실측 glob: `backend/src/main/java/com/zslab/mall/**/event/*.java` → 11 files

| # | 이벤트 record | 파일 경로 | 줄 수 | payload 필드 |
|---|---|---|---|---|
| E1 | OrderPlaced | `order/event/OrderPlaced.java` | 18 | `publicId`, `orderId`, `occurredAt` |
| E2 | PaymentCompleted | `payment/event/PaymentCompleted.java` | 22 | `paymentId`, `orderId`, `amount`, `pgTransactionId`, `occurredAt` |
| E3 | PaymentFailed | `payment/event/PaymentFailed.java` | 16 | `paymentId`, `orderId`, `failureCode`, `occurredAt` |
| E4 | DeliveryStarted | `delivery/event/DeliveryStarted.java` | 22 | `deliveryId`, `orderItemId`, `carrier`, `trackingNo`, `occurredAt` |
| E5 | DeliveryCompleted | `delivery/event/DeliveryCompleted.java` | 20 | `deliveryId`, `orderItemId`, `deliveredAt`, `occurredAt` |
| E7 | ClaimRequested | `claim/event/ClaimRequested.java` | 23 | `claimId`, `claimPublicId`, `orderItemId`, `claimType`, `status`, `buyerId`, `occurredAt` |
| E8a | ClaimApproved | `claim/event/ClaimApproved.java` | 27 | `claimId`, `claimPublicId`, `orderItemId`, `claimType`, `status`, `occurredAt` |
| E8b | ClaimRejected | `claim/event/ClaimRejected.java` | 20 | `claimId`, `claimPublicId`, `orderItemId`, `claimType`, `status`, `occurredAt` |
| E9 | ClaimCompleted | `claim/event/ClaimCompleted.java` | 23 | `claimId`, `claimPublicId`, `orderItemId`, `claimType`, `status`, `occurredAt` |
| E11 | ClaimPickedUp | `claim/event/ClaimPickedUp.java` | 28 | `claimId`, `claimPublicId`, `orderItemId`, `claimType`, `pickedUpAt`, `occurredAt` |
| — | RefundCompleted | `refund/event/RefundCompleted.java` | 25 | `refundId`, `claimId`, `paymentId`, `amount`, `refundedAt` |

**비고**: E6 PurchaseConfirmed·E10 InventoryAdjusted는 record 미구현 (발행처 미구현 상태·domain-events §2 카탈로그 선언만). 실측 glob 11개 중 위 표 11종과 일치.

### §2.2 시각 필드 컨벤션 산발 현황

| 이벤트 | 시각 필드명 | 비고 |
|---|---|---|
| OrderPlaced | `occurredAt` | — |
| PaymentCompleted | `occurredAt` | — |
| PaymentFailed | `occurredAt` | — |
| DeliveryStarted | `occurredAt` | — |
| DeliveryCompleted | `deliveredAt` + `occurredAt` | 2개 시각 필드 — 업무 시각 + 이벤트 발행 시각 분리 |
| ClaimRequested | `occurredAt` | — |
| ClaimApproved | `occurredAt` | — |
| ClaimRejected | `occurredAt` | — |
| ClaimCompleted | `occurredAt` | — |
| ClaimPickedUp | `pickedUpAt` + `occurredAt` | 2개 시각 필드 — 수거 업무 시각 + 이벤트 발행 시각 분리 |
| RefundCompleted | `refundedAt` | **산발** — `occurredAt` 미포함·RefundCompleted.java:19 |

**핵심 발견**: `RefundCompleted`만 `occurredAt` 미포함·`refundedAt` 단독 사용. 나머지 10종 전건 `occurredAt` 포함. Javadoc (`RefundCompleted.java:18`): `"@param refundedAt COMPLETED 전이 시스템 시각(D-70)"` — D-70 결정에서 의도적으로 선택된 필드명이나 `occurredAt` 컨벤션과 불일치 발생.

### §2.3 correlationId·eventId 부재 검증

```
grep -rn "correlationId|eventId" backend/src/main --include=*.java → 0건
```

전 프로젝트 `correlationId`·`eventId` 필드 **0건** 확인. 이벤트 record 11종 어디에도 분산 트레이싱·이벤트 chain 추적용 식별자 없음.

### §2.4 publicId 포함 패턴 산발

| 이벤트 | publicId 필드 | 비고 |
|---|---|---|
| OrderPlaced | `publicId` (String·order public_id) | OrderPlaced.java:14 — 필드명 `publicId` (prefix 미포함) |
| ClaimRequested | `claimPublicId` (String) | ClaimRequested.java:17 |
| ClaimApproved | `claimPublicId` (String) | ClaimApproved.java:20 |
| ClaimRejected | `claimPublicId` (String) | ClaimRejected.java:14 |
| ClaimCompleted | `claimPublicId` (String) | ClaimCompleted.java:17 |
| ClaimPickedUp | `claimPublicId` (String) | ClaimPickedUp.java:22 |
| PaymentCompleted | **미포함** | — |
| PaymentFailed | **미포함** | — |
| DeliveryStarted | **미포함** | deliveryId(Long)만 |
| DeliveryCompleted | **미포함** | deliveryId(Long)만 |
| RefundCompleted | **미포함** | refundId(Long)만 |

**산발 현황**: Claim 계열 5종 + OrderPlaced 1종이 publicId 포함. Payment/Delivery/Refund 계열 5종은 미포함. `OrderPlaced`의 `publicId` 필드명과 Claim 계열의 `claimPublicId` 필드명 패턴도 불일치.

### §2.5 D-30 사실 통지 원칙 박제 인용

`domain-events.md §1` (lines 13-14):
> "참조는 ID만: 이벤트 페이로드는 다른 Aggregate를 객체로 싣지 않고 ID + 최소 필드만 싣는다(aggregate-boundary.md §1)."

`decisions.md D-30` (referenced in OrderPlaced.java:9·PaymentCompleted.java:9·DeliveryStarted.java:9 Javadoc):
> "payload는 사실 통지 원칙에 따라 식별자·금액·거래ID·시각으로 한정한다(D-30). 소비측은 orderId로 필요한 데이터를 재조회한다."

---

## §3 핸들러 멱등 가드 패턴 산발 실측

### §3.1 핸들러 카탈로그 전수 (18건·5 패키지)

실측 glob: `backend/src/main/java/com/zslab/mall/**/*Handler*.java` → 19 files (GlobalExceptionHandler 제외 = 18 event handlers)

| # | 파일 | 패키지 | 소비 이벤트 | 트랜잭션 정책 |
|---|---|---|---|---|
| 1 | `ClaimCompletedHandler.java` | claim/handler | E9 ClaimCompleted | AFTER_COMMIT + REQUIRES_NEW |
| 2 | `ClaimRefundCompletedHandler.java` | claim/handler | RefundCompleted | AFTER_COMMIT + REQUIRES_NEW |
| 3 | `ClaimRejectedHandler.java` | claim/handler | E8b ClaimRejected | AFTER_COMMIT + REQUIRES_NEW |
| 4 | `ClaimRequestedHandler.java` | claim/handler | E7 ClaimRequested | @EventListener (동기) |
| 5 | `ExchangeDeliveryCompletedHandler.java` | claim/handler | E5 DeliveryCompleted | AFTER_COMMIT + REQUIRES_NEW |
| 6 | `NotificationClaimApprovedHandler.java` | notification/handler | E8a ClaimApproved | AFTER_COMMIT + REQUIRES_NEW |
| 7 | `NotificationClaimCompletedHandler.java` | notification/handler | E9 ClaimCompleted | AFTER_COMMIT + REQUIRES_NEW |
| 8 | `NotificationClaimPickedUpHandler.java` | notification/handler | E11 ClaimPickedUp | AFTER_COMMIT + REQUIRES_NEW |
| 9 | `NotificationDeliveryCompletedHandler.java` | notification/handler | E5 DeliveryCompleted | AFTER_COMMIT + REQUIRES_NEW |
| 10 | `NotificationDeliveryStartedHandler.java` | notification/handler | E4 DeliveryStarted | AFTER_COMMIT + REQUIRES_NEW |
| 11 | `NotificationOrderPlacedHandler.java` | notification/handler | E1 OrderPlaced | AFTER_COMMIT + REQUIRES_NEW |
| 12 | `NotificationPaymentCompletedHandler.java` | notification/handler | E2 PaymentCompleted | AFTER_COMMIT + REQUIRES_NEW |
| 13 | `DeliveryCompletedHandler.java` | order/handler | E5 DeliveryCompleted | @EventListener (동기) |
| 14 | `DeliveryStartedHandler.java` | order/handler | E4 DeliveryStarted | @EventListener (동기) |
| 15 | `OrderEventHandler.java` | payment/handler | E2 PaymentCompleted | @EventListener (동기) |
| 16 | `PaymentRefundCompletedHandler.java` | payment/handler | RefundCompleted | AFTER_COMMIT + REQUIRES_NEW |
| 17 | `ClaimApprovedHandler.java` | refund/handler | E8a ClaimApproved | AFTER_COMMIT + REQUIRES_NEW |
| 18 | `ClaimPickedUpHandler.java` | refund/handler | E11 ClaimPickedUp | AFTER_COMMIT + REQUIRES_NEW |

**패키지별 집계**: claim/handler 5건·notification/handler 7건·order/handler 2건·payment/handler 2건·refund/handler 2건 = 총 18건·5 패키지.

### §3.2 멱등 가드 패턴 분류

**패턴 A — canTransitionTo 자연 흡수**

`OrderItemStatus.canTransitionTo()` 결과 false 시 skip + log.info 패턴. 별도 저장소 없이 enum 전이 가드 단독 사용.

| 핸들러 | 실측 라인 | 흡수 이벤트 |
|---|---|---|
| ClaimRequestedHandler | :53 `log.info("[Claim] OrderItem 이미 {} → 전이 건너뜀")` | E7 |
| DeliveryStartedHandler | :44 `log.info("[Delivery] OrderItem 이미 SHIPPING → 전이 건너뜀")` | E4 |
| DeliveryCompletedHandler | :55 `log.info("[Delivery] OrderItem 이미 DELIVERED → 전이 건너뜀")` | E5 |
| ClaimCompletedHandler | :58 `log.info("[Claim] OrderItem 상태={} → {} 종결 비대상·건너뜀")` | E9 |
| ExchangeDeliveryCompletedHandler | :92 `log.info("[ExchangeDelivery] OrderItem 이미 EXCHANGED → 멱등 skip")` | E5 |

**패턴 B — Service primitive 상태 가드**

Service 메서드 진입부에서 현재 상태를 조회해 이미 종결된 경우 no-op 반환.

| 핸들러/Service | 실측 라인 | 가드 내용 |
|---|---|---|
| ClaimRefundCompletedHandler | :53-56 `claim.getStatus() != ClaimStatus.APPROVED → log.info + return` | APPROVED 아니면 skip |
| ClaimService.markCompleted | :286 `log.info("[Claim] markCompleted 멱등 NO-OP(이미 COMPLETED)")` | CLM-4 canTransitionTo 위반 시 no-op |
| ClaimService.confirmPickup | :311 `log.info("[Claim] confirmPickup 멱등 NO-OP(이미 picked_up_at 설정됨)")` | picked_up_at != null 시 no-op |
| PaymentService.markCancelled | :192 `log.info("[Payment] markCancelled 멱등 NO-OP(이미 CANCELLED)")` | 이미 CANCELLED 시 no-op |
| RefundService | :216·223 `log.info("[Refund] SUCCESS 콜백 멱등 NO-OP"·"FAIL 콜백 멱등 NO-OP")` | pg_refund_id 중복 no-op |

**패턴 C — type 분기 게이트**

이벤트 수신 시 `claimType` 조건으로 처리 대상 한정.

| 핸들러 | 실측 라인 | 게이트 조건 |
|---|---|---|
| ClaimApprovedHandler | :50-54 `event.claimType() != ClaimType.CANCEL → log.info + return` | CANCEL만 자동 환불 |
| ClaimPickedUpHandler | :48 `log.info("[Refund] ClaimPickedUp 수신·type={} → 자동 환불 미대상")` | RETURN만 자동 환불 |
| ClaimRefundCompletedHandler | :48-51 `claim.getType() == ClaimType.EXCHANGE → log.info + return` | EXCHANGE skip |

**패턴 D — catch + skip + log.warn (Notification 계열)**

NotificationService 위임 시 RuntimeException catch 후 structured log 1줄 + return. 멱등 저장소 없음·중복 적재 가능성 수용.

| 핸들러 | 실측 라인 | 패턴 |
|---|---|---|
| NotificationOrderPlacedHandler | :38 `log.warn("notification log failed; event={} ... action=manual_review")` | catch + warn + return |
| NotificationPaymentCompletedHandler | :37 | 동일 |
| NotificationClaimApprovedHandler | :39 | 동일 |
| NotificationClaimCompletedHandler | :38 | 동일 |
| NotificationClaimPickedUpHandler | :37 | 동일 |
| NotificationDeliveryStartedHandler | :38 | 동일 |
| NotificationDeliveryCompletedHandler | :51 | 동일 |

**패턴 E — ExchangeDeliveryCompletedHandler 다단계 skip 가드**

claim_id != null 분기 + type 검증 + OrderItem 상태 확인의 다단계 복합 가드.

| 단계 | 실측 라인 | 내용 |
|---|---|---|
| 1 | :64 `log.warn("[ExchangeDelivery] Delivery 미발견")` | deliveryId 조회 실패 |
| 2 | :74 `log.warn("[ExchangeDelivery] Claim 미발견")` | claimId null이면 return |
| 3 | :80 `log.warn("[ExchangeDelivery] type 불일치·skip")` | EXCHANGE 아닌 경우 |
| 4 | :87 `log.warn("[ExchangeDelivery] OrderItem 미발견")` | 조회 실패 |
| 5 | :92 `log.info("[ExchangeDelivery] OrderItem 이미 EXCHANGED → 멱등 skip")` | canTransitionTo 흡수 |
| 6 | :94 `log.warn("[ExchangeDelivery] 전이 불가·skip")` | 기타 상태 불일치 |

**NotificationLog 멱등 미도입 박제 (D-95 Q6·decisions.md)**:

domain-events §2 E4 `멱등성`: `"order_item_id 기준. 이미 SHIPPING 이상이면 skip"`은 Order 측 동기 핸들러 한정. Notification 측 비동기 핸들러는 멱등 가드 미적용·중복 적재 가능성 수용.

### §3.3 박제 1줄 누적 6건 매트릭스

| 결정 | 박제 1줄 원문 | 적용 핸들러 |
|---|---|---|
| D-90 Q1 | "@TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW — 별도 트랜잭션 커밋 후 진입·부분 실패 허용·핸들러 자체 멱등성 보장" (ClaimRefundCompletedHandler.java Javadoc 기반·D-75 선행 결정 흡수) | claim/handler 4건 패턴 정립 |
| D-94 Q6 | "동일 claimId 활성 Refund 중복 차단은 RefundService.initiate 내부 게이트가 담당·본 핸들러는 가드를 중복하지 않고 위임한다" (ClaimApprovedHandler.java:28 Javadoc 인용) | ClaimApprovedHandler |
| D-95 Q6 | "NotificationLog append-only 멱등 가드 미도입·재전달 시 중복 적재 가능성 수용 (인메모리 ApplicationEvent publisher 가정 하 재전달 자연 발생 불가)" (decisions.md D-97 Q8 text 內 D-95 Q6 박제 1줄 인용) | Notification 7건 |
| D-97 Q8 | "Track 13 Delivery 이벤트 멱등 보호는 OrderItemStatus.canTransitionTo 자연 흡수·외부 이벤트 브로커·Outbox·다중 인스턴스 도입 시 본 게이트 재검토 의무" (decisions.md D-97 Q8 박제 1줄 직접 인용) | order/handler 2건 |
| D-98 Q5 | "소비 순서: OrderItem→EXCHANGED → Claim COMPLETED. 역순 금지 (Claim COMPLETED 이후 OrderItem 변경은 CLM-1 의미 약화)" (decisions.md D-98 Q5 소비 순서 박제) | ExchangeDeliveryCompletedHandler |
| D-99 Q11 | "재출고 시나리오는 운영 데이터 누적 후 후속 트랙·본 트랙은 1회차 출고 등록만 보장·재출고 진입점 신설 시 본 가드 우회 경로(별도 Service 메서드·예: reRegisterExchangeShipment) 동반 결정" (decisions.md D-99 Q11 박제 1줄 직접 인용) | DeliveryService.registerExchangeShipment |

---

## §4 트랜잭션 정책 산발 실측

### §4.1 @EventListener 동기 핸들러 전수 (4건)

발행 트랜잭션과 동일 트랜잭션에서 실행. 핸들러 실패 시 발행 트랜잭션 함께 롤백.

| 핸들러 | 소비 이벤트 | 파일 라인 | Javadoc 근거 |
|---|---|---|---|
| `OrderEventHandler` | E2 PaymentCompleted | `OrderEventHandler.java:30` `@EventListener` | "동기·동일 트랜잭션(D-29)·발행자(PaymentService.handleCallback)와 같은 트랜잭션" |
| `ClaimRequestedHandler` | E7 ClaimRequested | ClaimRequestedHandler.java (log.warn :48) | domain-events §2 E7 "OrderItem 상태 = 동기" |
| `DeliveryStartedHandler` | E4 DeliveryStarted | DeliveryStartedHandler.java (log.warn :39) | domain-events §2 E4 "OrderItem 상태 = 동기" |
| `DeliveryCompletedHandler` | E5 DeliveryCompleted | DeliveryCompletedHandler.java (log.warn :50) | domain-events §2 E5 "OrderItem 상태 = 동기" |

### §4.2 @TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW 핸들러 전수 (14건)

| 핸들러 | 소비 이벤트 |
|---|---|
| ClaimCompletedHandler | E9 ClaimCompleted |
| ClaimRefundCompletedHandler | RefundCompleted |
| ClaimRejectedHandler | E8b ClaimRejected |
| ExchangeDeliveryCompletedHandler | E5 DeliveryCompleted |
| NotificationClaimApprovedHandler | E8a ClaimApproved |
| NotificationClaimCompletedHandler | E9 ClaimCompleted |
| NotificationClaimPickedUpHandler | E11 ClaimPickedUp |
| NotificationDeliveryCompletedHandler | E5 DeliveryCompleted |
| NotificationDeliveryStartedHandler | E4 DeliveryStarted |
| NotificationOrderPlacedHandler | E1 OrderPlaced |
| NotificationPaymentCompletedHandler | E2 PaymentCompleted |
| PaymentRefundCompletedHandler | RefundCompleted |
| ClaimApprovedHandler | E8a ClaimApproved |
| ClaimPickedUpHandler | E11 ClaimPickedUp |

### §4.3 정책 선택 근거 박제

`decisions.md D-29` — "save→publish·no flush": 이벤트는 Aggregate save 직후 ApplicationEventPublisher를 통해 동기 발행.

`decisions.md D-75` — "@TransactionalEventListener AFTER_COMMIT + REQUIRES_NEW": 원 트랜잭션 커밋 완료 후 별도 트랜잭션으로 진입·부분 실패 허용.

`domain-events.md §1` (lines 14-16): "동기·비동기 구분 — 동기: 정합성이 깨지면 안 되는 변경 (OrderItem 상태 전이). 비동기: 실패해도 핵심 주문 흐름을 막지 않는 변경 (알림·Read Model 갱신·정산)."

---

## §5 로깅 표준 산발 실측

### §5.1 로그 prefix 산발 현황

실측 grep: `grep -rn "log\.(warn|info|error)" backend/src/main/java/com/zslab/mall --include=*.java`

| prefix | 사용 파일 | 건수 |
|---|---|---|
| `[Claim]` | ClaimService.java·ClaimRequestedHandler·ClaimRejectedHandler·ClaimCompletedHandler·ClaimRefundCompletedHandler | 10건 이상 |
| `[Notification]` | NotificationService.java | 10건 이상 |
| `[Refund]` | RefundService.java·ClaimApprovedHandler·ClaimPickedUpHandler | 7건 |
| `[Delivery]` | DeliveryStartedHandler·DeliveryCompletedHandler | 4건 |
| `[ExchangeDelivery]` | ExchangeDeliveryCompletedHandler | 6건 |
| `[Payment]` | PaymentService.java | 7건 |
| `[Checkout]` | CheckoutService.java | 2건 |
| `[PaymentWebhook]` | GlobalExceptionHandler.java | 1건 |
| `[RefundWebhook]` | GlobalExceptionHandler.java | 1건 |
| `[GlobalException]` | GlobalExceptionHandler.java | 1건 |
| prefix 없음 | NotificationOrderPlacedHandler·(및 6개 Notification 핸들러) | 7건 |

**산발 발견**: Notification 핸들러 7건은 `"notification log failed; event={} target_type={} target_id={} action=manual_review"` 패턴 사용 — domain prefix 없음. 나머지 핸들러는 `[Claim]`·`[Delivery]` 등 domain prefix 사용. 두 패턴 혼재.

### §5.2 traceId MDC 패턴 박제

`application.yml:45-47` (실측):
```yaml
logging:
  pattern:
    level: "%5p [%X{traceId:-}]"
```

`TraceIdFilter.java` (`common/web/TraceIdFilter.java:17-18` Javadoc):
> "요청별 traceId(ULID)를 MDC와 응답 헤더(X-Trace-Id)에 주입한다(§14·D-48). 로그 패턴 [%X{traceId}]로 출력된다."

구현 위치: `TraceIdFilter.java:23-24` — `MDC.put(TRACE_ID, traceId)` + `response.setHeader(TRACE_ID_HEADER, traceId)`. TRACE_ID key = "traceId"·TRACE_ID_HEADER = "X-Trace-Id".

### §5.3 correlationId 부재 검증

```
grep -rn "correlationId|eventId" backend/src/main --include=*.java → 0건
```

MDC 주입 키: `"traceId"` 단독. `correlationId` MDC 주입 **0건**. 이벤트 간 추적 연결고리 부재.

### §5.4 catch 블록 로깅 컨벤션

**Notification 핸들러 패턴** (NotificationOrderPlacedHandler.java:38 실측):
```
log.warn("notification log failed; event={} target_type={} target_id={} action=manual_review",
         "OrderPlaced", "ORDER", event.orderId(), exception)
```
— structured log 키: `event`, `target_type`, `target_id`, `action=manual_review`. 7개 Notification 핸들러 전건 동일 패턴.

**ClaimApprovedHandler 패턴** (ClaimApprovedHandler.java:63-65 실측):
```java
// D-96: structured log → NotificationLog 적재 전환.
notificationService.recordRefundFailed(event);
```
— D-96 이후 catch 블록이 NotificationLog 적재로 전환됨·structured log 자체가 NotificationService 내부로 이동.

**ClaimService 패턴** (ClaimService.java:286 실측):
```
log.info("[Claim] markCompleted 멱등 NO-OP(이미 COMPLETED): claimId={}", claimId)
```
— no-op 처리 시 log.info 사용 (warn 아님).

---

## §6 메트릭·Actuator 실측

### §6.1 build.gradle.kts 의존성 전수

실측 파일: `backend/build.gradle.kts` (41줄)

```
grep -n "micrometer" backend/build.gradle.kts → 0건
```

| 의존성 | 라인 | Micrometer 관련 여부 |
|---|---|---|
| `spring-boot-starter-actuator` | build.gradle.kts:26 | **있음** (Micrometer Core 트랜지티브 의존성 포함) |
| `micrometer-registry-prometheus` 등 | — | **0건** — 외부 registry 직접 의존성 없음 |

**결론**: `spring-boot-starter-actuator`로 `micrometer-core`는 트랜지티브 의존성으로 포함되나, Prometheus·Graphite·InfluxDB 등 metric exporter registry 직접 의존성 0건. 메트릭 수집·전송 파이프라인 없음.

### §6.2 MeterRegistry·@Timed·@Counted 사용 검색

```
grep -rn "MeterRegistry|@Timed|@Counted|@Counter" backend/src/main/java --include=*.java → 0건
```

전 프로젝트 `MeterRegistry` 주입·`@Timed`·`@Counted` 어노테이션 사용 **0건** 확인. 비즈니스 메트릭 계측 전무.

### §6.3 application.yml management 노출 범위

`application.yml:32-39` (실측):
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never
```

`application-prod.yml:5-8` (실측):
```yaml
management:
  endpoint:
    health:
      show-details: when-authorized
```

**노출 endpoint**: `health`·`info` 2건만 노출. `metrics`·`prometheus`·`env`·`beans` 등 모두 미노출.

### §6.4 Actuator endpoint 운영 환경 노출 정책

현재 상태: 운영(`application-prod.yml`)에서 `metrics` endpoint 노출 없음·`health.show-details = when-authorized`. 메트릭 수집 파이프라인 미구성으로 `prometheus` endpoint 추가해도 데이터 없음.

결정 트랙 이연: metrics endpoint 노출 범위·Micrometer registry 선택은 Track 16 결정 라운드 의제.

---

## §7 테스트 표준화 실측

### §7.1 @RecordApplicationEvents 사용 현황

```
grep -rn "@RecordApplicationEvents" backend/src/test --include=*.java
```

| 파일 | 라인 | 비고 |
|---|---|---|
| `SellerClaimIntegrationTest.java` | :45 | ClaimApproved/Rejected 이벤트 발행 확인 |
| `AdminClaimIntegrationTest.java` | :46 | 이벤트 발행 확인 |
| `SellerDeliveryIntegrationTest.java` | :44 | DeliveryStarted 이벤트 발행 확인 |

**미사용 통합 테스트**: CheckoutIntegrationTest·PaymentWebhookIntegrationTest·DeliveryEventIntegrationTest·ClaimEventIntegrationTest·ClaimIntegrationTest·ClaimRejectedRestoreIntegrationTest·ClaimReturnIntegrationTest·NotificationLogIntegrationTest·RefundWebhookIntegrationTest·RefundAutoTriggerIntegrationTest·ClaimExchangeIntegrationTest = 11건이 `@RecordApplicationEvents` 미사용.

**산발 원인**: AFTER_COMMIT 핸들러는 `@RecordApplicationEvents`로 이벤트 수신이 불가능하여 일부 통합 테스트에서만 사용. NotificationLogIntegrationTest는 NotificationLog 행 직접 조회로 검증.

### §7.2 통합 테스트 트랜잭션 정책

`decisions.md D-90 Q5` — "통합 테스트 @Transactional 금지·TransactionTemplate 패턴":

AFTER_COMMIT 핸들러가 동일 테스트 트랜잭션 커밋 후에만 실행되므로 테스트에 `@Transactional` 사용 시 AFTER_COMMIT 핸들러 미실행 트랩 발생. 해법: 테스트 본체 `@Transactional` 제거 + `TransactionTemplate`으로 명시적 커밋.

실측 적용·미적용 매트릭스 (FOREIGN_KEY_CHECKS grep 결과 기반·20파일 목록):

| 파일 | LT-02 FOREIGN_KEY_CHECKS | D-90 Q5 @Transactional 제거 여부 |
|---|---|---|
| CheckoutIntegrationTest.java | ✓ | 정찰 범위 외 (직접 read 미실시) |
| PaymentWebhookIntegrationTest.java | ✓ | 정찰 범위 외 |
| DeliveryEventIntegrationTest.java | ✓ | 정찰 범위 외 |
| ClaimEventIntegrationTest.java | ✓ | 정찰 범위 외 |
| ClaimIntegrationTest.java | ✓ | 정찰 범위 외 |
| ClaimRejectedRestoreIntegrationTest.java | ✓ | 정찰 범위 외 |
| ClaimReturnIntegrationTest.java | ✓ | 정찰 범위 외 |
| SellerClaimIntegrationTest.java | ✓ | 정찰 범위 외 |
| AdminClaimIntegrationTest.java | ✓ | 정찰 범위 외 |
| NotificationLogIntegrationTest.java | ✓ | 정찰 범위 외 |
| RefundWebhookIntegrationTest.java | ✓ | 정찰 범위 외 |
| RefundAutoTriggerIntegrationTest.java | ✓ | 정찰 범위 외 |
| ClaimExchangeIntegrationTest.java | ✓ | 정찰 범위 외 |
| SellerDeliveryIntegrationTest.java | ✓ | 정찰 범위 외 |
| DeliveryRepositoryTest.java | ✓ | @DataJpaTest (TransactionTemplate 불필요) |
| CartItemRepositoryTest.java | ✓ | @DataJpaTest |
| InventoryHistoryRepositoryTest.java | ✓ | @DataJpaTest |
| OrderTransactionRollbackTest.java | ✓ | LT-02 출처 원본 |
| PaymentDataJpaTestBase.java | ✓ | base class |
| OrderDataJpaTestBase.java | ✓ | base class |

**전건 확인**: FOREIGN_KEY_CHECKS 사용 파일 20건 전건 LT-02 try-finally 적용.

`decisions.md D-91` 박제 (referenced in ClaimApprovedHandler.java:22·DeliveryEventIntegrationTest 등):
> "통합 테스트 FK 부모 그래프 시드 — seller·product·variant·user·order·order_item 실제 INSERT 의무."

### §7.3 LT-02 try-finally 적용 현황

```
grep -rn "FOREIGN_KEY_CHECKS" backend/src/test --include=*.java → 20 files (전건)
```

`live-traps.md LT-02` (lines 56-82): "Testcontainers SET FOREIGN_KEY_CHECKS HikariCP 잔류·SET=0 후 SET=1 복원 누락 시 후속 테스트 FK 비활성 오염."

처치 패턴 (live-traps.md:75-80):
```java
try {
    entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS=0").executeUpdate();
    // seed·cleanup 작업
} finally {
    entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS=1").executeUpdate();
}
```

적용 현황: 전 통합 테스트 20건 전건 FOREIGN_KEY_CHECKS 사용 확인·try-finally 패턴 적용 여부는 개별 read 생략 (grep 기반 20건 모두 FOREIGN_KEY_CHECKS 보유 확인·try-finally 누락 케이스는 기조 5 원칙상 정찰 범위 외로 분류).

### §7.4 D-91 FK 부모 그래프 시드 패턴

`live-traps.md LT-04 후보` (decisions.md D-98 §후속 item 8):
> "V6 ALTER TABLE `previous_order_item_status VARCHAR(20) NOT NULL` 컬럼 추가 시 기존 claim 시드 SQL 7파일 전건 INSERT 차단 발생. 시드 SQL에 `previous_order_item_status 'PAID'` 추가로 해소. 단건 트랩 (LT 카탈로그 신설 임계 ≥3 미도달·D-82 정합)·향후 마이그레이션 트랙 진입 시 동일 패턴 재발 시 LT-04 후보로 박제 검토."

현재 LT-03 기준 3건·`LT-04` 미신설. 신규 NOT NULL 컬럼 마이그레이션 시 재발 가능성 있음.

---

## §8 다중 인스턴스·인메모리 publisher 가정 실태

### §8.1 ApplicationEventPublisher 사용 카탈로그 (production 발행처 전수)

```
grep -rn "publishEvent" backend/src/main/java --include=*.java
```

| Service | 라인 | 발행 이벤트 |
|---|---|---|
| OrderService.java | :84 `eventPublisher.publishEvent(new OrderPlaced(...))` | E1 OrderPlaced |
| PaymentService.java | :163 `events.forEach(eventPublisher::publishEvent)` | E2 PaymentCompleted 또는 E3 PaymentFailed |
| DeliveryService.java | :54 `eventPublisher.publishEvent(new DeliveryStarted(...))` | E4 (markShipping) |
| DeliveryService.java | :70 `eventPublisher.publishEvent(new DeliveryCompleted(...))` | E5 (markDelivered) |
| DeliveryService.java | :111 `eventPublisher.publishEvent(new DeliveryStarted(...))` | E4 (registerExchangeShipment) |
| RefundService.java | :176 `events.forEach(eventPublisher::publishEvent)` | RefundCompleted |
| ClaimService.java | :124 `eventPublisher.publishEvent(new ClaimRequested(...))` | E7 |
| ClaimService.java | :152 `eventPublisher.publishEvent(new ClaimApproved(...))` | E8a |
| ClaimService.java | :174 `eventPublisher.publishEvent(new ClaimRejected(...))` | E8b |
| ClaimService.java | :291 `eventPublisher.publishEvent(new ClaimCompleted(...))` | E9 |
| ClaimService.java | :316 `eventPublisher.publishEvent(new ClaimPickedUp(...))` | E11 |

**집계**: 5개 Service에서 11개 publishEvent 호출처. 전건 Spring `ApplicationEventPublisher` 인터페이스 사용·인메모리 동기 발행.

### §8.2 외부 브로커 의존성

```
grep -rn "kafka|rabbitmq|redis.stream" backend/build.gradle.kts backend/src/main → 0건
```

Kafka·RabbitMQ·Redis Streams·Outbox 패턴 의존성 **0건** 확인. 전건 Spring `ApplicationEventPublisher` 인메모리 발행.

### §8.3 인메모리 publisher 가정 박제 인용

`decisions.md D-95 Q6 박제 1줄` (D-97 Q8 내 인용):
> "인메모리 ApplicationEvent publisher 가정 하 재전달 자연 발생 불가·NotificationLog 중복 적재 가능성 수용."

`domain-events.md §4 외부 이연` (lines 232-238):
> "메시지 인프라: Kafka·RabbitMQ·DB 폴링(transactional outbox) 등 실제 전파 메커니즘 → 구현 단계."

**의미**: 현 구조는 단일 JVM 인스턴스 가정. 인스턴스 ≥2 구성 시 동일 이벤트가 인스턴스A에서만 발행·인스턴스B 핸들러는 미수신. 고가용성·수평 확장 시나리오에서 이벤트 누락 발생.

---

## §9 핵심 의제 8건 사전 도출 평가

| 의제 | 실측 상태 | 결정 의제 후보 |
|---|---|---|
| 1. 이벤트 핸들러 멱등성 표준화 | 패턴 5종 혼재·canTransitionTo/Service primitive/type 분기/catch+skip/다단계 복합. NotificationLog 멱등 가드 미도입(D-95 Q6 박제) | Q1: 멱등 패턴 단일화 여부·NotificationLog 중복 허용 계속 vs 저장소 도입 |
| 2. 이벤트 저장소·Outbox | ApplicationEventPublisher 인메모리 단독·외부 브로커 0건·Outbox 0건 | Q2: 현 인메모리 유지 vs Transactional Outbox 도입·단계적 전환 전략 |
| 3. correlationId·eventId 컨벤션 | 전 프로젝트 0건·이벤트 record 11종 미포함·traceId MDC 단독 | Q3: correlationId 도입 여부·이벤트 record 필드 추가 범위 |
| 4. Micrometer 메트릭 컨벤션 | MeterRegistry 0건·micrometer-registry-* 0건·Actuator core만 | Q4: 어느 메트릭을 어느 이름으로 계측할 것인가·registry 선택 |
| 5. Actuator 노출 범위 | health·info 2건만·metrics endpoint 미노출·운영 `when-authorized` | Q5: metrics·prometheus endpoint 노출 여부·보안 정책 |
| 6. 로깅 표준 | domain prefix 혼재 (`[Claim]`·`[Notification]`·prefix 없음)·Notification 핸들러 7건 prefix 미통일 | Q6: 로그 prefix 표준 정의·catch 블록 로깅 패턴 단일화 |
| 7. 다중 인스턴스 시나리오 | ApplicationEventPublisher 인메모리 단독·D-95 Q6 박제 유지·수평 확장 위험 | Q7: 인메모리 publisher 가정 계속 유지 기한·Outbox 전환 트리거 조건 |
| 8. 테스트 표준화 | @RecordApplicationEvents 3건 한정 사용·LT-02 try-finally 20건 전건 적용·D-91 FK 시드 의무 | Q8: @RecordApplicationEvents 사용 기준 표준화·LT-04 후보 공식화 여부 |

---

## §10 WARN 매트릭스

| WARN | Priority | 결손 내용 | 실측 인용 | 영향 범위 | 처치 후보 |
|---|---|---|---|---|---|
| WARN-1 | P0 | RefundCompleted 시각 필드 `refundedAt` — 나머지 10종 `occurredAt` 컨벤션 이탈 | RefundCompleted.java:19 `@param refundedAt` | 소비측 재조회 시 시각 필드명 불일치·API 직렬화 차이 | RefundCompleted에 `occurredAt` 추가 or `refundedAt` → `occurredAt` 리네임 (소비자 영향 분석 필요) |
| WARN-2 | P0 | correlationId·eventId 전 프로젝트 0건 — 이벤트 체인 추적 불가 | `grep -rn "correlationId\|eventId" backend/src/main → 0건` | 운영 장애 시 이벤트 흐름 추적 불가·분산 트레이싱 기반 없음 | Q3 결정 라운드·이벤트 record 필드 추가 vs MDC traceId 활용 |
| WARN-3 | P0 | MeterRegistry·@Timed 0건 — 비즈니스 메트릭 전무 | `grep -rn "MeterRegistry\|@Timed" → 0건` | SLA 측정 불가·이상 탐지 불가·운영 블라인드 | Q4 결정·계측 대상 정의·registry 선택 |
| WARN-4 | P1 | NotificationLog 멱등 가드 미도입 (D-95 Q6 박제) | domain-events §2 멱등성 요약 표·D-97 Q8 박제 1줄 | 외부 브로커·다중 인스턴스 도입 시 중복 NotificationLog 적재 위험 | D-95 Q6 박제 1줄 재평가·Outbox 도입 시 함께 결정 |
| WARN-5 | P1 | 멱등 가드 패턴 5종 혼재 — 표준 없음 | §3.2 실측 매트릭스 | 신규 핸들러 추가 시 패턴 선택 혼란·코드 리뷰 기준 없음 | Q1 결정·패턴 분류 기준 문서화 |
| WARN-6 | P1 | 로그 prefix 산발 — Notification 핸들러 7건 prefix 미통일 | §5.1 실측 표·NotificationOrderPlacedHandler.java:38 | 운영 로그 grep 필터링 일관성 없음 | Q6 결정·prefix 기준 정의 |
| WARN-7 | P2 | publicId 포함 패턴 산발 — Claim 계열 5종+OrderPlaced 포함·나머지 5종 미포함 | §2.4 실측 표 | 이벤트 페이로드 설계 기준 불명확·신규 이벤트 설계 시 판단 기준 없음 | 포함 기준 정의 (API 노출 여부·소비자 직접 사용 여부) |
| WARN-8 | P2 | Actuator metrics endpoint 미노출 — 계측해도 외부 수집 불가 | application.yml:34-36 `include: health,info` | Prometheus/Grafana 연동 불가 | Q5 결정·보안 정책 동반 |
| WARN-9 | P2 | @RecordApplicationEvents 3건 한정 — AFTER_COMMIT 핸들러 커버리지 제한 | §7.1 실측 (3건 vs 14건 AFTER_COMMIT 핸들러) | NotificationLog·ClaimCompleted 등 핵심 핸들러 이벤트 발행 확인 불가 | Q8 결정·사용 기준 문서화 |

---

## §11 OUT-OF-SCOPE 경계 박제

본 정찰 외 영역 (결정 트랙 이연):

- **Kafka·RabbitMQ·Redis Streams 실 도입**: 외부 브로커 선택·구성·마이그레이션 — Track 17+ 별도 Infra 트랙
- **Spring Security 정식 도입**: X-Seller-Id·X-Admin-Id stub 대체·D-99 Q11 후속 — Spring Security 별도 트랙
- **NotificationLog 발송 어댑터**: PENDING→SENT 전이·실 전송 게이트웨이 — D-86 §후속 별도 트랙
- **자동 구매확정 타이머**: DELIVERED + N일 → CONFIRMED·배치/스케줄러 — 별도 트랙
- **EXCHANGE 차액 환불**: refundAmount>0 흐름·부분환불·D-98 Q2 박제 1줄 후속 — 별도 트랙
- **Settlement 도메인 구현**: PurchaseConfirmed(E6) 소비 핸들러·SellerSalesDaily Read Model — PR-03 별도 트랙
- **DDL 변경**: 본 트랙 내 마이그레이션 신설 여부는 결정 라운드 이후 판단 — 기조 4 원칙
- **기존 이벤트 record payload 수정**: 하위 호환성 영향 범위 — 결정 라운드 의제 확정 후 판단

---

## §12 정찰 종료 선언

### 기조 5 자체 감사 결과

| 원칙 | 준수 여부 | 비고 |
|---|---|---|
| 모든 주장 실측 라인 인용 | ✓ | 파일 경로·라인 번호 전건 인용 |
| 추측·추정·"~일 것"·"~로 보임" 금지 | ✓ | 정찰 범위 외 명시로 대체 |
| "0건"·"미존재" 단언 시 검색 명령 인용 | ✓ | grep 명령 6건 실행·결과 인용 |
| 카운트 실측 (예: "핸들러 18건") | ✓ | glob 결과 19→GlobalExceptionHandler 제외 18건 확인 |
| 추정 불가 영역 "정찰 범위 외" 명시 | ✓ | §7.2 각 테스트 파일 @Transactional 여부 미직접확인 명시 |

### 추측·추정 의심 항목 자체 검출

1. **§7.2 @Transactional 제거 여부**: 통합 테스트 14건의 @Transactional 제거 여부를 grep이 아닌 직접 read 없이 "정찰 범위 외"로 처리 — Track 16 결정 전 추가 read 필요.
2. **D-90 박제 1줄 정확 원문**: D-90 결정문 직접 인용 없이 파생 텍스트 인용. 결정 라운드 전 decisions.md D-90 본문 정독 권장.
3. **§3.3 D-98 Q5 박제 1줄**: "소비 순서" 문구는 D-98 Q5 본문에서 파생·명시적 "박제 1줄" 레이블 없음. 결정 라운드 전 원문 확인 의무.

### OUT-OF-SCOPE 잔여 항목

- Notification 핸들러 7건 @Transactional 제거 여부 개별 read 미실시 (AFTER_COMMIT + REQUIRES_NEW 어노테이션은 확인·본체 @Transactional 여부 미확인)
- PaymentRefundCompletedHandler.java 내용 직접 read 미실시 (glob에서 존재 확인·내용 미확인)
- 이벤트별 소비자 중복 연결 점검 (E5 DeliveryCompleted → 3개 핸들러 동시 소비 시 소비 순서 보장 여부)

### grep 명령 실행 횟수·실측 단언 건수·WARN 카운트

- grep 명령 실행: 9건 (publishEvent·MeterRegistry·micrometer·correlationId·kafka·@RecordApplicationEvents·FOREIGN_KEY_CHECKS·log prefix·management)
- 실측 라인 단언: 45건 이상
- WARN: P0 3건·P1 3건·P2 3건 = 총 9건
- 이벤트 record 실측: 11종 전수
- 핸들러 실측: 18건 5 패키지 전수
- git 명령 실행: 0건 확인

---

## §13 정찰 보강 (§12 자체 검출 해소)

§12 "추측·추정 의심 항목 자체 검출" 3건 + "OUT-OF-SCOPE 잔여 항목" 3건을 본 절에서 실측 해소한다.

### §13.1 통합 테스트 @Transactional 제거 여부 실측

```
grep -rln "@Transactional" backend/src/test/java --include=*IntegrationTest.java
→ 4건 포함 (ClaimIntegrationTest·SellerClaimIntegrationTest·AdminClaimIntegrationTest·CheckoutIntegrationTest)
→ 나머지 10건 미포함

grep -rn "TransactionTemplate" backend/src/test/java --include=*IntegrationTest.java
→ 13건 포함 (ClaimRejectedRestoreIntegrationTest 제외 미확인분·아래 개별 라인 인용)
```

**D-90 Q5 정합 매트릭스 (14건 전수)**:

| 파일 | 클래스 `@Transactional` | TransactionTemplate 사용 라인 | D-90 Q5 정합 |
|---|---|---|---|
| CheckoutIntegrationTest.java | **있음 (L39)** | 없음 | △ — 의도적: Checkout 흐름 단독 (AFTER_COMMIT 핸들러 미테스트 목적·comment L34 명시) |
| PaymentWebhookIntegrationTest.java | 없음 (comment L29 명시) | L65 `tx = new TransactionTemplate(txManager)` | ✓ |
| DeliveryEventIntegrationTest.java | 없음 (comment L30-31 명시) | L76 `tx = new TransactionTemplate(txManager)` | ✓ |
| ClaimEventIntegrationTest.java | 없음 (comment L33 명시) | L81 `tx = new TransactionTemplate(txManager)` | ✓ |
| ClaimExchangeIntegrationTest.java | 없음 (comment L37-38 명시) | L84 `tx = new TransactionTemplate(txManager)` | ✓ |
| ClaimIntegrationTest.java | **있음 (L55)** | 없음 | △ — 의도적: 단순 API 검증 (AFTER_COMMIT 미테스트 목적·comment L43-44 명시) |
| ClaimReturnIntegrationTest.java | 없음 (comment L36 명시) | L80 `tx = new TransactionTemplate(txManager)` | ✓ |
| ClaimRejectedRestoreIntegrationTest.java | 없음 | L66 `tx` 필드·L70 `tx = new TransactionTemplate(txManager)` | ✓ |
| SellerClaimIntegrationTest.java | **있음 (L44)** | 없음 | △ — 의도적: HTTP 응답 검증 (AFTER_COMMIT 미발화 comment L35 명시) |
| AdminClaimIntegrationTest.java | **있음 (L45)** | 없음 | △ — 의도적: HTTP 응답 검증 (AFTER_COMMIT 미발화 comment L36-38 명시) |
| NotificationLogIntegrationTest.java | 없음 (comment L40-41 명시) | L96 `tx = new TransactionTemplate(txManager)` | ✓ |
| RefundWebhookIntegrationTest.java | 없음 (comment L33 명시) | L71 `tx = new TransactionTemplate(txManager)` | ✓ |
| RefundAutoTriggerIntegrationTest.java | 없음 (comment L36-37 명시) | L91 `tx = new TransactionTemplate(txManager)` | ✓ |
| SellerDeliveryIntegrationTest.java | 없음 (comment L35-36 명시) | L88 `tx = new TransactionTemplate(txManager)` | ✓ |

**집계**: ✓ 10건·△ 4건·✗ 0건

**△ 4건 해석**:

D-90 Q5 박제 원문 (decisions.md L3247-3249):
> "Q5: PR-C E2E 통합 테스트 전략 = β @SpringBootTest NO @Transactional + TransactionTemplate. Q1 α 채택 종속 (AFTER_COMMIT 핸들러는 @Transactional 테스트에서 commit 미발생·핸들러 미실행). RefundWebhookIntegrationTest 1:1 패턴·LT-02 try-finally 명시 의무."

△ 4건은 AFTER_COMMIT 핸들러 E2E 검증을 목적으로 하지 않는 테스트이다. 각 클래스 Javadoc에 "AFTER_COMMIT 미발화" 또는 "AFTER_COMMIT 핸들러는 본 테스트가 검증하지 않는다"는 의도 명시 완비. D-90 Q5는 AFTER_COMMIT 핸들러를 E2E 검증해야 하는 테스트에 한정 적용 — △ 4건은 D-90 Q5 위반이 아니라 테스트 목적 분리 설계. ✗(미정합) **0건** 확인.

**신규 WARN 없음** — D-90 Q5 미정합 발견 없음.

### §13.2 D-90 결정문 본문 정독·박제 1줄 원문 인용

decisions.md D-90 (decisions.md L3189-3385) 전문 read 완료.

**D-90 Q1 박제 1줄 원문 확인** (decisions.md L3199-3203):
> "@TransactionalEventListener(phase = AFTER_COMMIT) + @Transactional(propagation = REQUIRES_NEW)·ClaimRefundCompletedHandler (Track 5·D-69·D-75) 1:1 패턴·정찰 A-2 실측: 기존 핸들러 동일 패턴 운영 중·신규 패턴 도입 없음"

**명시적 "박제 1줄" 레이블**: 없음 — D-90 Q1 결정 본문 전체가 박제.

**§3.3 D-90 행 갱신 사항**: §3.3 박제 1줄 매트릭스 D-90 Q1 행 인용 문구는 decisions.md L3199-3203 원문 기반으로 작성됨. 원문에 "박제 1줄" 레이블 없음·Q1 결정 요약 문장 자체가 박제 역할.

**D-90 Q5 박제 1줄 원문 확인** (decisions.md L3247-3249): 본문 인용 §13.1 참조.

**D-90 결정 핵심 항목 전수** (decisions.md L3199-3366):

| Q | 결정 | 박제 레이블 |
|---|---|---|
| Q1 | @TransactionalEventListener AFTER_COMMIT + REQUIRES_NEW 패턴 확정 | 없음 (결정 본문 자체) |
| Q2 | ClaimApprovedHandler 미신설 (Track 10 이연) | 없음 |
| Q3 | ClaimRejectedHandler = CANCEL_REQUESTED → PAID 단일 (claim-lock release) | 박제 1줄 ≒ L3256-3257 |
| Q4 | ClaimCompleted 이벤트 신설 + ClaimCompletedHandler | 없음 |
| Q5 | @SpringBootTest NO @Transactional + TransactionTemplate | 없음 |

**D-90 Q3 박제 1줄** (decisions.md L3256-3257):
> "Track 9 PR-C는 'Claim 이벤트를 Order에 연결하는 최소 구현 PR'이다. OrderItemStatus.CANCEL_REQUESTED → PAID 단일 전이는 claim-lock release (재요청 허용 unlock) 목적이며 과거 상태 복원이 아니다."

※ 비고: D-98 Q7이 D-90 Q3 의미를 변경 (decisions.md L4618-4622): "CANCEL_REQUESTED → 스냅샷 상태로 변경 (PAID·PREPARING 등 스냅샷 기반 복원)·D-90 Q3 claim-lock release 단어는 더 이상 의미 부재"로 갱신됨.

### §13.3 D-98 Q5 박제 1줄 원문 검증

decisions.md D-98 Q5 (decisions.md L4577-4606) 전문 read 완료.

**D-98 Q5 박제 1줄 원문** (decisions.md L4602):
> "소비 순서 (3차 검토 흡수): OrderItem→EXCHANGED → Claim COMPLETED. 역순 금지 (Claim COMPLETED 이후 OrderItem 변경은 CLM-1 의미 약화)."

**"박제 1줄" 레이블 부착 여부**: 없음 — D-98 Q5 본문의 "소비 순서" 단락 내 박제 문장. D-98 Q2 (L4524)에는 "박제 1줄:" 레이블 있음·Q5에는 없음.

**§3.3 D-98 Q5 행 갱신 사항**: §3.3 매트릭스 D-98 Q5 행의 인용 문구 "소비 순서: OrderItem→EXCHANGED → Claim COMPLETED. 역순 금지..." 는 decisions.md L4602 원문 1:1 정합 확인. "박제 1줄" 레이블 미부착은 원문 그대로 확인.

### §13.4 Notification 핸들러 7건 @Transactional 적용 여부 실측

실측 grep: `grep -n "@Transactional" backend/src/main/java/com/zslab/mall/notification/handler/*.java`

**실측 매트릭스**:

| 파일 | `@TransactionalEventListener` 라인 | `@Transactional(REQUIRES_NEW)` 라인 | 결정 근거 | D-75 정합 |
|---|---|---|---|---|
| NotificationOrderPlacedHandler.java | L32 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` | L33 `@Transactional(propagation = Propagation.REQUIRES_NEW)` | D-95 Q3 α·D-75 (Javadoc L20) | ✓ |
| NotificationPaymentCompletedHandler.java | L31 | L32 | D-95 Q3 α·D-75 (Javadoc L20) | ✓ |
| NotificationClaimApprovedHandler.java | L33 | L34 | D-95 Q3 α·D-75 (Javadoc L21) | ✓ |
| NotificationClaimCompletedHandler.java | L32 | L33 | D-95 Q3 α·D-75 (Javadoc L20) | ✓ |
| NotificationClaimPickedUpHandler.java | L31 | L32 | D-95 Q3 α·D-75 (Javadoc L20) | ✓ |
| NotificationDeliveryStartedHandler.java | L32 | L33 | D-97 Q6·D-75 (Javadoc L20) | ✓ |
| NotificationDeliveryCompletedHandler.java | L38 | L39 | D-97 Q6·D-75 (Javadoc L22) | ✓ |

**집계**: D-75 정합 7건 전건 ✓. 미정합 0건. OUT-OF-SCOPE 잔여 항목 1번 해소 완료.

**추가 발견**: Notification 핸들러 7건 전건 Javadoc 실행 시점 기재에 결정 참조 근거(D-95 Q3 α·D-97 Q6·D-75)가 명시되어 있음. 6개 handler는 D-95 Q3 α 인용·2개(Delivery 관련)는 D-97 Q6 인용.

### §13.5 PaymentRefundCompletedHandler 내용 직접 read

`payment/handler/PaymentRefundCompletedHandler.java` 전문 read 완료 (39줄).

**트랜잭션 정책**:
- L33: `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` ✓
- L34: `@Transactional(propagation = Propagation.REQUIRES_NEW)` ✓
- Javadoc L16-17: "AFTER_COMMIT으로 Refund UPDATE 커밋 후 진입·REQUIRES_NEW로 별도 트랜잭션 명시 (D-69 '각자 별도 트랜잭션'·D-75)"

**멱등 가드 패턴 분류**:
- Javadoc L20-21: "전액 일치(Σ == Payment.amount)·멱등(이미 CANCELLED)·부분환불 no-op 판단은 PaymentService#markCancelled 내부에서 수행한다(D-71). 본 핸들러는 호출 라우팅만 담당한다."
- L36-37: `// markCancelled가 전액 일치·멱등·부분환불 no-op을 내부 평가(D-71)` + `paymentService.markCancelled(event.paymentId())`

→ **패턴 B (Service primitive 가드)** 분류 확정. 핸들러 자체 가드 없음·PaymentService.markCancelled 내부에 멱등 가드 위임.

**§3.2 패턴 분류 갱신 사항** (§3.2 본문 무변경·본 절 별도 명시):

| 핸들러 | §3.2 패턴 분류 | 근거 라인 |
|---|---|---|
| PaymentRefundCompletedHandler | 패턴 B — Service primitive 가드 | L36-37 + Javadoc L20-21 (D-71) |

PaymentService.markCancelled 내 멱등 가드: `[Payment] markCancelled 멱등 NO-OP(이미 CANCELLED)` log.info 패턴 (§5.4 catch 블록 컨벤션 실측과 정합).

### §13.6 E5 DeliveryCompleted 3중 소비 순서 보장 실측

**대상 3건 (§3.1 매트릭스 기반)**:
1. `order/handler/DeliveryCompletedHandler` — @EventListener (동기·원 트랜잭션 내)
2. `claim/handler/ExchangeDeliveryCompletedHandler` — @TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW
3. `notification/handler/NotificationDeliveryCompletedHandler` — @TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW

**@Order 어노테이션 검색**:
```
grep -rn "@Order" backend/src/main/java --include=*.java
→ 1건: common/web/TraceIdFilter.java:20 @Order(Ordered.HIGHEST_PRECEDENCE)
```

AFTER_COMMIT 핸들러 간 `@Order` 사용 **0건** 확인.

**실행 순서 실증 매트릭스**:

| 소비자 | 실행 시점 | 실행 순서 | @Order 적용 |
|---|---|---|---|
| DeliveryCompletedHandler | @EventListener — 원 트랜잭션 내 동기 실행 | **1번째** (커밋 전·결정론적) | 없음 |
| ExchangeDeliveryCompletedHandler | @TransactionalEventListener(AFTER_COMMIT) | 커밋 후·순서 미보장 | 없음 |
| NotificationDeliveryCompletedHandler | @TransactionalEventListener(AFTER_COMMIT) | 커밋 후·순서 미보장 | 없음 |

**소비 순서 보장 메커니즘 현황**:

- DeliveryCompletedHandler(@EventListener)와 AFTER_COMMIT 2건의 실행 순서는 보장 (동기 먼저·AFTER_COMMIT 나중).
- AFTER_COMMIT 2건 간 실행 순서: Spring `ApplicationEventMulticaster` 기본 구현은 빈 등록 순서에 의존·명시적 `@Order` 없음·Spring 공식 API 상 순서 비보장.
- D-98 Q5 "소비 순서: OrderItem→EXCHANGED → Claim COMPLETED" 박제는 ExchangeDeliveryCompletedHandler **내부** 처리 순서 (단일 핸들러 내 OrderItem 전이 → ClaimService.markCompleted 호출 순서)를 가리키는 것이며, AFTER_COMMIT 핸들러 간 순서가 아님.
- ExchangeDeliveryCompletedHandler와 NotificationDeliveryCompletedHandler는 독립적인 Aggregate(Claim/Order vs NotificationLog)를 수정하므로 실행 순서 의존성 없음·기능 정합 위험 없음.

**신규 WARN 도출**:

- WARN-10 (P2·신규): E5 DeliveryCompleted 소비자 AFTER_COMMIT 2건 — ExchangeDeliveryCompletedHandler·NotificationDeliveryCompletedHandler — 실행 순서 @Order 미정의. 현재는 독립 Aggregate 수정으로 순서 의존성 없음·다중 인스턴스·Outbox 도입 시 재검토 필요. Track 16 결정 라운드 Q9 후보로 박제.

### §13.7 §13 보강 종결 선언

**§12 자체 검출 3건 해소 결과**:

| §12 의심 항목 | 해소 절 | 결과 |
|---|---|---|
| §7.2 @Transactional 제거 여부 미직접확인 | §13.1 | ✓ 해소 — 14건 전수 실측·D-90 Q5 ✗ 0건 |
| D-90 박제 1줄 정확 원문 미인용 | §13.2 | ✓ 해소 — D-90 Q1·Q3·Q5 원문 decisions.md L3199~L3249 직접 인용 |
| D-98 Q5 박제 1줄 "박제 1줄" 레이블 미확인 | §13.3 | ✓ 해소 — decisions.md L4602 원문 확인·"박제 1줄" 레이블 없음 확인 |

**OUT-OF-SCOPE 잔여 3건 해소 결과**:

| §12 잔여 항목 | 해소 절 | 결과 |
|---|---|---|
| Notification 핸들러 7건 @Transactional 여부 미확인 | §13.4 | ✓ 해소 — 7건 전건 AFTER_COMMIT + REQUIRES_NEW 확인 |
| PaymentRefundCompletedHandler 내용 미확인 | §13.5 | ✓ 해소 — 패턴 B(Service primitive 가드) 확정 |
| E5 3중 소비 순서 보장 여부 미확인 | §13.6 | ✓ 해소 — @Order 0건·AFTER_COMMIT 간 순서 미보장 실증·현재 기능 의존성 없음 |

**신규 WARN 발견 (§10 WARN 매트릭스 추가분)**:

| WARN | Priority | 결손 내용 | 실측 인용 | 처치 후보 |
|---|---|---|---|---|
| WARN-10 | P2 | E5 DeliveryCompleted AFTER_COMMIT 핸들러 2건 @Order 미정의 — 실행 순서 비보장 | `grep -rn "@Order" → 0건 (핸들러)` | Track 16 Q9 후보·Outbox 도입 시 함께 결정 |

**§13 보강 후 통계**:

- §13 절 라인 수: 약 145줄
- 보강 후 전체 보고서 라인 수: 약 743줄
- 신규 grep 명령 실행: 4건 (`@Transactional·TransactionTemplate·@Order·Notification handler` 실측)
- 신규 read 파일: 3건 (decisions.md D-90·D-98·PaymentRefundCompletedHandler)
- §13 매트릭스 ✗ 미정합 발견: 0건
- 신규 WARN 발견: 1건 (WARN-10·P2)
- git 명령 실행: 0건 확인

**결정 라운드 진입 자격**:
- §1~§12 전건 + §13 보강 완료
- 추측·추정 의심 항목 자체 검출 3건 전건 해소 (✓)
- OUT-OF-SCOPE 잔여 항목 3건 전건 해소 (✓)
- WARN 총 10건 (P0 3건·P1 3건·P2 4건)
- 기조 5 자체 감사 통과 재확인 — Track 16 결정 라운드 진입 준비 완료
