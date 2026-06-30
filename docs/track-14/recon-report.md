# Track 14 RETURN/EXCHANGE 확장 정찰 보고서 (recon-report)

> 작성일: 2026-06-30 · 모드: read-only 정찰 (코드·git 무변경) · 라벨: S급 (다도메인 횡단·Claim·OrderItem·Delivery·Refund·Notification 동시 영향·외부 검토 의무)
> 산출물: 본 파일 1건 (그 외 파일 변경 없음·PROGRESS.md STEP 로그 제외)
> SoT 기준: main 75188e8 (Track 13 Delivery PR 머지 직후·393 tests baseline·working tree clean)
> 패턴 준용: docs/track-13/recon-report.md (D-97 recon 1:1)

---

## §1. 트랙 배경·범위·OUT-OF-SCOPE·LIMITATION

### 1.1 배경

Track 13 Delivery 도메인 신설(D-97) 완료 직후 진입. 본 트랙의 명목 목표는 **Claim 도메인을 CANCEL 한정에서 RETURN/EXCHANGE까지 확장**하는 것이다. RETURN/EXCHANGE 라벨은 D-88부터 D-97까지 4회 연속 후속 트랙으로 carry-over되어 왔으며, 본 트랙이 그 종착지다.

D-97 §후속 SoT 인용 ([decisions.md:4449](../../docs/architecture-baseline/decisions.md#L4449)):

> "**Track 14+ RETURN/EXCHANGE**: D-94·D-95 §후속 라벨 자연 이동·Delivery DELIVERED 의존 전이(RETURN_REQUESTED·EXCHANGE_REQUESTED)·D-92·D-93 횡단 원칙 재사용 2회차·ClaimReasonCode +4값."

라벨 이동 이력 (실측):
- D-88 ([decisions.md:2997](../../docs/architecture-baseline/decisions.md#L2997)): "Track 11 = Claim RETURN/EXCHANGE 확장"
- D-94 Q0 β ([decisions.md:3640](../../docs/architecture-baseline/decisions.md#L3640)): Track 11 = Refund Service 재정의 → RETURN/EXCHANGE는 Track 12+로 이동
- D-95 Q1 α ([decisions.md:4239](../../docs/architecture-baseline/decisions.md#L4239)): Track 13+로 재이동
- D-97 §후속 ([decisions.md:4449](../../docs/architecture-baseline/decisions.md#L4449)): **Track 14+로 자연 이동 (본 트랙)**

### 1.2 핵심 실측 결론 (정찰 룰 #4 — 선구현 확인)

**RETURN/EXCHANGE의 값집합·상태 매트릭스·DTO·Controller·Claim 승인 wrapper는 전부 선구현되었고, 결손은 "CANCEL 게이트 제거 + 흐름 후반부(수거 확인·교환 재출고·RETURN/EXCHANGE 종결·Refund 트리거 시점) 신설"에 집중된다.**

선구현 확정 (무변경 또는 게이트 제거만 — 정찰 룰 #4·#5):
- `OrderItemStatus.canTransitionTo` RETURN/EXCHANGE 전이 **5건 전부 기정의**(D-88): SHIPPING/DELIVERED→RETURN_REQUESTED·DELIVERED→EXCHANGE_REQUESTED·RETURN_REQUESTED→RETURNED·EXCHANGE_REQUESTED→EXCHANGED ([OrderItemStatus.java:49-53](../../backend/src/main/java/com/zslab/mall/order/enums/OrderItemStatus.java#L49-L53))
- `claim.type` DDL ENUM에 RETURN·EXCHANGE 기포함 ([V1__init.sql:668](../../backend/src/main/resources/db/migration/V1__init.sql#L668))
- `ClaimType` enum 3값 전부 정의 ([ClaimType.java:10-17](../../backend/src/main/java/com/zslab/mall/claim/enums/ClaimType.java#L10-L17))
- `ClaimStatus` 전이 매트릭스는 **type 무관**(REQUESTED→APPROVED→COMPLETED·CLM-4)이라 RETURN/EXCHANGE도 그대로 통과 ([ClaimStatus.java:29-36](../../backend/src/main/java/com/zslab/mall/claim/enums/ClaimStatus.java#L29-L36))
- DTO·Controller 3건 전부 **type 중립** — ClaimType을 검증 없이 통과시킴 ([ClaimRequestRequest.java:23](../../backend/src/main/java/com/zslab/mall/claim/controller/request/ClaimRequestRequest.java#L23)). CANCEL 제약은 오직 Service 진입부 단락(§2.1)에만 존재.
- Claim 승인/거부 wrapper(approveBySeller·approveByAdmin 등)는 **actor·type 비의존** primitive에 위임 — RETURN/EXCHANGE 승인 재사용 가능(§9)

실 결손 (신설·게이트 제거 대상):
1. **CANCEL 게이트 5중 제거/확장**: `ClaimService.request`(§2.1) + Claim 이벤트 핸들러 4건 전부(§2.4·§6.1)
2. **수거 확인(picked-up) 상태/이벤트 미설계**: RETURN refund·EXCHANGE 완료 양쪽의 게이트인데 모델 부재(§6)
3. **EXCHANGE 교환품 Delivery 생성 진입점 부재**: `Delivery.create` production 호출처 0건(§4)
4. **RETURN/EXCHANGE Refund 트리거 시점 분기 미설계**: 자동 트리거는 CANCEL 단독(D-94 Q3)(§5)
5. **RETURN/EXCHANGE COMPLETED 진입 경로 부재**: `markCompleted` 호출처가 CANCEL 한정(§5)
6. **ClaimReasonCode +4값 미추가**: Javadoc 예고만, enum 미반영(§3)
7. **ClaimRejected RETURN/EXCHANGE 원복 전이 미정의**: 매트릭스·핸들러 양층 부재(§2.4·WARN-10)
8. **NotificationLog RETURN/EXCHANGE templateCode·메시지 분기 미박제**(§7)

### 1.3 OUT-OF-SCOPE (예측·결정 라운드 확정 의무)

- **부분환불·배송비 환불**: D-94 Q7 박제 1줄 OOS 유지 ([decisions.md:3698](../../docs/architecture-baseline/decisions.md#L3698))
- **CLAIM_REASON Code 테이블 전환**: 시드 등록 부담 발생 시점 별도 트랙 ([ClaimReasonCode.java:10](../../backend/src/main/java/com/zslab/mall/claim/enums/ClaimReasonCode.java#L10))
- **Inventory 회수 복구 + 신규 차감**: Track 14+ Inventory 트랙 소관·EXCHANGE는 D-08 트랜잭션 분리 ([decisions.md:4450](../../docs/architecture-baseline/decisions.md#L4450)·[domain-events.md:155](../../docs/architecture-baseline/domain-events.md#L155))
- **택배사 수거 API 연동**: 외부 어댑터 트랙
- **Observability(이벤트 핸들러 멱등성 표준화·correlationId)**: Track 14+ Observability 분리 유지 ([decisions.md:4448](../../docs/architecture-baseline/decisions.md#L4448))

### 1.4 LIMITATION

- 본 보고서는 read-only 실측이며 구현·결정을 포함하지 않는다. §12 의제는 결정 라운드(D-98) 입력이다.
- 영향 범위·신규 파일 예측(§5·§6 후보)은 **결정 라운드 확정 전 예측**이며 확정 박제가 아니다.
- `OrderService`·`OrderStatusResolver` 전체 라인은 미독(grep으로 RETURNED/EXCHANGED 종결 집계 참조만 확인). Order.status 재계산 로직 상세는 LIMITATION.
- Claim·Refund·Notification 통합 테스트 본문 전체 라인은 미독(@Test 카운트·클래스 Javadoc·PROGRESS 이력으로 시나리오 식별). 회귀 영향(§10)은 게이트 단언 패턴 추론 기반이며 본문 단언 1:1 확인은 구현 진입 시 의무.

---

## §2. 의제 1 — Claim 도메인 RETURN/EXCHANGE 자산 실측

### 2.1 ClaimService 메서드 인벤토리 ([ClaimService.java](../../backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java))

| 메서드 | 라인 | type 게이트 | RETURN/EXCHANGE 진입 |
|---|---|---|---|
| `request(command)` | 73-124 | **CANCEL 한정**(91-93) | **차단** |
| `approve(claimId, at)` primitive | 139-146 | type 무관 | 통과 가능 |
| `reject(claimId, at)` primitive | 161-168 | type 무관 | 통과 가능 |
| `approveBySeller / rejectBySeller` | 179-200 | type 무관·Seller 권한 검증 | 재사용 가능(§9) |
| `approveByAdmin / rejectByAdmin` | 215-233 | type 무관·전체 접근 | 재사용 가능(§9) |
| `getClaim / listClaims` | 240-261 | type 무관 | 통과 |
| `markCompleted(claimId)` | 273-285 | type 무관(호출처가 CANCEL 한정·§5) | 진입점 부재 |

CANCEL 게이트 직접 인용 ([ClaimService.java:90-93](../../backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java#L90-L93)):

```java
// (d) CANCEL 외 차단(Q6·422).
if (command.claimType() != ClaimType.CANCEL) {
    throw new ClaimInvalidStateException("현재 CANCEL 클레임만 지원합니다(Q6). 입력: " + command.claimType());
}
```

→ **RETURN/EXCHANGE 진입 메서드는 별도 신설 불요. `request()`의 단락(91-93) 제거 + (f)단락(101)의 전이 대상 분기(CANCEL_REQUESTED → RETURN_REQUESTED/EXCHANGE_REQUESTED)가 핵심 결손.** D-89 Q5 박제 정합 ([decisions.md:3046](../../docs/architecture-baseline/decisions.md#L3046)): "Track 11 RETURN/EXCHANGE 진입 시 DTO 무영향·Service 차단 단락만 제거."

### 2.2 Controller 인벤토리 (전부 type 중립·무변경 후보)

| Controller | base path | endpoint | type 처리 |
|---|---|---|---|
| [BuyerClaimController](../../backend/src/main/java/com/zslab/mall/claim/controller/BuyerClaimController.java) | `/api/v1/claims` | POST(request)·GET/{id}·GET(list) | DTO ClaimType 통과(46-54) |
| [SellerClaimController](../../backend/src/main/java/com/zslab/mall/claim/controller/SellerClaimController.java) | `/api/v1/claims` | POST/{id}/approve·/reject | type 무관(49-66) |
| [AdminClaimController](../../backend/src/main/java/com/zslab/mall/claim/controller/AdminClaimController.java) | `/api/v1/admin/claims` | POST/{id}/approve·/reject | type 무관(51-69) |

→ **Controller 3건 전부 무변경 가능**(RETURN/EXCHANGE는 동일 endpoint·DTO 재사용). 신규 endpoint 후보는 "수거 확인"(§6) 발생 시 한정.

### 2.3 Claim Entity·enum 실측

- `Claim.java`: type 무관 전이 메서드(approve·reject·markCompleted)·`transitionTo`가 `ClaimStatus.canTransitionTo` 위임 ([Claim.java:154-159](../../backend/src/main/java/com/zslab/mall/claim/entity/Claim.java#L154-L159))·RETURN/EXCHANGE 추가 필드 0
- `ClaimType.java`: CANCEL·RETURN·EXCHANGE 3값. Javadoc 명시 ([ClaimType.java:13-15](../../backend/src/main/java/com/zslab/mall/claim/enums/ClaimType.java#L13-L15)): "RETURN = 수거 확인 + Refund.COMPLETED 필요(본 트랙 미전이)·EXCHANGE = 수거 확인 + 교환품 발송 완료 필요(본 트랙 미전이)"
- `ClaimStatus.java`: REQUESTED·APPROVED·REJECTED·COMPLETED 4값·**전이 매트릭스 type 무관** — RETURN/EXCHANGE도 동일 매트릭스 통과

### 2.4 Claim 이벤트 핸들러 4건 — 전부 CANCEL 게이트 (실측)

| 핸들러 | 소비 이벤트 | CANCEL 게이트 라인 | RETURN/EXCHANGE 동작 |
|---|---|---|---|
| [ClaimRequestedHandler](../../backend/src/main/java/com/zslab/mall/claim/handler/ClaimRequestedHandler.java) | E7 ClaimRequested | 41-45 | OrderItem 전이 미배선 |
| [ClaimRejectedHandler](../../backend/src/main/java/com/zslab/mall/claim/handler/ClaimRejectedHandler.java) | E8 ClaimRejected | 41-44 | 원복 전이 미배선(WARN-10) |
| [ClaimCompletedHandler](../../backend/src/main/java/com/zslab/mall/claim/handler/ClaimCompletedHandler.java) | E9 ClaimCompleted | 41-44 | RETURNED/EXCHANGED 종결 미배선 |
| [ClaimRefundCompletedHandler](../../backend/src/main/java/com/zslab/mall/claim/handler/ClaimRefundCompletedHandler.java) | RefundCompleted | 47-51 | markCompleted 미호출 |

→ **OrderItem RETURN_REQUESTED·EXCHANGE_REQUESTED 진입 메서드는 존재하나(canTransitionTo·changeStatus 기정의) 호출 경로가 4핸들러 어디서도 RETURN/EXCHANGE에 배선되지 않았다.** ClaimRequestedHandler line 41 게이트 직접 인용:

```java
if (event.claimType() != ClaimType.CANCEL) {
    // RETURN·EXCHANGE는 본 트랙 미처리(Track 11)
    log.info("[Claim] ClaimRequested 수신·type={} → 본 트랙 미처리: claimId={}", event.claimType(), event.claimId());
    return;
}
```

ClaimCompletedHandler는 종결 전이를 `CANCEL_REQUESTED → CANCELLED`로 **하드코딩** ([ClaimCompletedHandler.java:50-56](../../backend/src/main/java/com/zslab/mall/claim/handler/ClaimCompletedHandler.java#L50-L56)) — RETURN(RETURN_REQUESTED→RETURNED)·EXCHANGE(EXCHANGE_REQUESTED→EXCHANGED) 종결은 type 분기 또는 신규 핸들러 의무.

---

## §3. 의제 2 — ClaimReasonCode 값집합·DDL ENUM 실측

### 3.1 현재 enum 값집합 (6값·실측)

[ClaimReasonCode.java:12-25](../../backend/src/main/java/com/zslab/mall/claim/enums/ClaimReasonCode.java#L12-L25):
`BUYER_CHANGED_MIND` · `DUPLICATE_ORDER` · `PAYMENT_ISSUE` · `ORDER_MISTAKE` · `STOCK_DELAY` · `OTHER`

### 3.2 "+4값" 메모 검증 — 추측 단정 회피 (정찰 룰 #3)

메모리 "+4값"은 **추측이 아니라 코드·SoT 양쪽에 박제된 예고**임을 실측 확인:
- [ClaimReasonCode.java:9](../../backend/src/main/java/com/zslab/mall/claim/enums/ClaimReasonCode.java#L9): "Track 11 RETURN/EXCHANGE 진입 시 확장 예정: **PRODUCT_DEFECT·DAMAGED_ON_ARRIVAL·WRONG_PRODUCT·DELIVERY_DELAY**."
- [decisions.md:3043](../../docs/architecture-baseline/decisions.md#L3043) (D-89 Q5): "Track 11 확장: PRODUCT_DEFECT·DAMAGED_ON_ARRIVAL·WRONG_PRODUCT·DELIVERY_DELAY."

→ **4값은 현재 enum 미반영(실측 6값). 추가 4값은 RETURN 사유 특화(하자·파손·오배송·지연)로 RETURN/EXCHANGE 흐름의 의미상 결손이다.**

### 3.3 DDL — reason_code는 4층위 잠금의 단일 예외 (VARCHAR 무제약)

[V1__init.sql:669](../../backend/src/main/resources/db/migration/V1__init.sql#L669):

```sql
reason_code  VARCHAR(50) NOT NULL COMMENT '사유 코드·B/CLAIM_REASON Code 정합(③·ENUM 미적용)',
```

→ **claim.reason_code는 DDL ENUM이 아니라 VARCHAR(50) 무제약**(D-89 Q5·Code 테이블 정합 의도·CLAIM_REASON 시드 부재로 ENUM 미적용). 검증은 DTO 층 `ClaimReasonCode` enum 바인딩(Jackson 역직렬화 실패→400)에만 의존. **결과: +4값 추가는 Flyway 마이그레이션 불요** — Java enum + 프론트 constants(4층위 중 2층·4층)만 추가하면 완료. type·status 컬럼(ENUM)과 달리 DB constraint 변경 불필요.

---

## §4. 의제 3 — EXCHANGE 재출고 Delivery 생성 진입점

### 4.1 SoT — EXCHANGE COMPLETED 조건

state-machine §2 ([state-machine.md:57](../../docs/architecture-baseline/state-machine.md#L57)):

> | EXCHANGE | 수거 확인 + 교환품 발송 완료 (**별도 Delivery 생성**) |

state-machine §8 Refund 연동 ([state-machine.md:264](../../docs/architecture-baseline/state-machine.md#L264)):

> | EXCHANGE | 교환 출고 → Refund.COMPLETED → Claim.COMPLETED (환불 금액 발생 시) |

### 4.2 Delivery.create production 호출처 grep — 0건 (실측)

`Delivery.create|new Delivery|.markShipping` grep 결과 ([backend/src/main](../../backend/src/main)):
- `Delivery.java:72` — `new Delivery()`는 `Delivery.create` 팩토리 **자기 내부** 단 1곳
- `DeliveryService.java:44` — `delivery.markShipping(...)`는 **기존 Delivery 행**의 상태 전이(Track 13)
- production 코드에 `Delivery.create(...)` **호출처 0건**

→ **Track 13은 Delivery 상태 전이(markShipping·markDelivered)만 신설했고, Delivery 행을 누가 생성하는지(create 호출 오케스트레이션)는 production 부재.** EXCHANGE 승인 시 교환품 발송용 신규 Delivery 행 자동 생성 진입점은 **미설계 영역**이다. (정찰 룰 #3 — 결정 전 예측·확정 아님: 신규 Delivery 생성을 ClaimApproved/ClaimCompleted 핸들러에서 트리거할지, 별도 판매자 출고 API에서 할지 미결.)

---

## §5. 의제 4 — RETURN/EXCHANGE Refund 자동 트리거 분기 경로

### 5.1 SoT — 자동 트리거는 CANCEL 단독

D-94 Q3 ([decisions.md:3658-3660](../../docs/architecture-baseline/decisions.md#L3658-L3660)):

> "**Q3: Refund 진입점 범위 = α 자동 트리거 단독.** ClaimApproved 자동 트리거를 단독 진입점으로 둔다. Admin 수동 환불 생성 endpoint는 본 트랙에 병존시키지 않는다."

### 5.2 refund/ClaimApprovedHandler type 게이트 실측

[ClaimApprovedHandler.java:50-54](../../backend/src/main/java/com/zslab/mall/refund/handler/ClaimApprovedHandler.java#L50-L54):

```java
if (event.claimType() != ClaimType.CANCEL) {
    // RETURN·EXCHANGE는 수거/교환출고 등 추가 단계가 필요해 자동 환불 대상이 아니다(Track 12+ 소관).
    log.info("[Refund] ClaimApproved 수신·type={} → 자동 환불 미대상: claimId={}", event.claimType(), event.claimId());
    return;
}
```

→ ClaimApproved → Refund 자동 트리거는 **CANCEL 단독**. RETURN/EXCHANGE는 ClaimApproved 시점에 환불 불가.

### 5.3 RETURN — 수거 확인 후 트리거 시점 (신규 진입점 후보)

state-machine §8 ([state-machine.md:263](../../docs/architecture-baseline/state-machine.md#L263)): "RETURN | **수거 확인** → Refund.COMPLETED → Claim.COMPLETED"

→ RETURN 환불은 ClaimApproved가 아니라 **수거 확인(§6) 이후** 트리거되어야 한다. 진입점 후보 (결정 전 예측·확정 아님):
- (a) 신규 이벤트 E11(ClaimPickedUp 등) → 신규 핸들러가 RefundService.initiate 호출
- (b) 수거 확인 API/핸들러가 직접 RefundService.initiate 호출
- 현 `refund/ClaimApprovedHandler`의 CANCEL 게이트는 RETURN/EXCHANGE에 부적합(시점이 다름) — RETURN은 별도 트리거 의무.

### 5.4 EXCHANGE — 환불 금액 발생 시 한정 분기

state-machine §8 ([state-machine.md:264](../../docs/architecture-baseline/state-machine.md#L264)): "EXCHANGE | 교환 출고 → Refund.COMPLETED → Claim.COMPLETED (**환불 금액 발생 시**)"

→ EXCHANGE는 동종 교환(차액 0) 시 Refund 미발생 가능. **Refund 트리거 자체가 조건부**(차액 발생 시만). amount 산정 출처도 CANCEL(OrderItem.totalPrice 전건·D-94 Q7)과 상이 — 차액 계산 로직 미설계. (결정 라운드 의제.)

---

## §6. 의제 5 — 수거 확인(picked-up) 이벤트/상태 신설 여부

### 6.1 state-machine §3 OrderItem 12값 — 수거 확인 단계 부재 (실측)

[state-machine.md:76-91](../../docs/architecture-baseline/state-machine.md#L76-L91) 12값: ORDERED·PAID·PREPARING·SHIPPING·DELIVERED·CONFIRMED·CANCEL_REQUESTED·CANCELLED·RETURN_REQUESTED·RETURNED·EXCHANGE_REQUESTED·EXCHANGED.

전이 매트릭스 실측 ([OrderItemStatus.java:52-53](../../backend/src/main/java/com/zslab/mall/order/enums/OrderItemStatus.java#L52-L53)):

```java
case RETURN_REQUESTED -> next == RETURNED;       // 직접 전이·중간 단계 없음
case EXCHANGE_REQUESTED -> next == EXCHANGED;     // 직접 전이·중간 단계 없음
```

→ **OrderItem 12값에 "수거 확인/picked-up" 중간 상태 부재. RETURN_REQUESTED→RETURNED·EXCHANGE_REQUESTED→EXCHANGED는 직접 전이.** 그러나 state-machine §2(line 56-57)·§8(line 263-264)은 "수거 확인"을 COMPLETED 진입의 명시적 게이트로 규정 — **모델과 SoT 사이 간극**.

### 6.2 수거 확인 모델링 옵션 후보 (정찰 단계 — 후보 박제만·결정 라운드 이동)

| 옵션 | 내용 | 비용/위험 |
|---|---|---|
| (a) OrderItem 신규 상태 | RETURN_PICKED_UP 등 13~14번째 값 | B분류 12값 확장·DDL ENUM 마이그레이션·매트릭스 신설 |
| (b) Claim 보조 상태 | ClaimStatus에 PICKED_UP 삽입 | A분류 4값 확장·state-machine §2 매트릭스 재설계·CANCEL 무관 상태 오염 |
| (c) ReturnDelivery/수거 추적 도메인 | Delivery 역방향 재사용 또는 신규 | 신규 Aggregate·과잉개발 위험(기조 4) |
| (d) 신규 이벤트 E11 ClaimPickedUp | 상태 추가 없이 APPROVED~COMPLETED 사이 이벤트로 게이트 | OrderItem 상태 무변경·수거 확인을 이벤트로만 표현·멱등 키 설계 필요 |

→ **수거 확인은 RETURN refund(§5.3)·EXCHANGE 완료(§4) 양쪽의 공통 게이트로 본 트랙 최대 미설계 영역.** 신규 이벤트 E11(ClaimPickedUp) 후보 박제. domain-events 현 E-번호는 E10까지([domain-events.md:157](../../docs/architecture-baseline/domain-events.md#L157)) — E11 신규 채번 가능.

---

## §7. 의제 6 — NotificationLog 핸들러 추가 대상

### 7.1 현황 실측 — 핸들러 6 클래스 + record 메서드 7건

Notification 핸들러 클래스 (glob 실측·6건):
`NotificationOrderPlacedHandler` · `NotificationPaymentCompletedHandler` · `NotificationClaimApprovedHandler` · `NotificationClaimCompletedHandler` · `NotificationDeliveryStartedHandler` · `NotificationDeliveryCompletedHandler`

NotificationService record 메서드 (7건·[NotificationService.java](../../backend/src/main/java/com/zslab/mall/notification/service/NotificationService.java)): recordOrderPlaced·recordPaymentCompleted·recordClaimApproved·recordClaimCompleted·**recordRefundFailed**·recordDeliveryStarted·recordDeliveryCompleted.

→ **메모 "핸들러 7건"은 정밀하게는 "6 핸들러 클래스 + recordRefundFailed 1건"** — recordRefundFailed는 전용 핸들러 없이 `refund/ClaimApprovedHandler`의 catch 블록에서 직접 호출 ([ClaimApprovedHandler.java:65](../../backend/src/main/java/com/zslab/mall/refund/handler/ClaimApprovedHandler.java#L65)). (정찰 룰 #1 — 실측 보정.)

### 7.2 type 분기 패턴 — recordClaimApproved/Completed는 type 무관

[NotificationService.java:94-126](../../backend/src/main/java/com/zslab/mall/notification/service/NotificationService.java#L94-L126): recordClaimApproved·recordClaimCompleted는 ClaimType 분기 없이 "클레임 승인/완료" 범용 메시지 적재. → **RETURN/EXCHANGE 클레임도 현 메서드가 type 무관으로 동작.** 단 메시지 문구가 범용("클레임 …이(가) 승인되었습니다")이라 RETURN/EXCHANGE 특화 알림(수거 안내·교환품 발송 안내 등)은 분기 또는 신규 메서드 필요.

E7 ClaimRequested에 대한 **NotificationLog 핸들러는 부재** (claim/handler/ClaimRequestedHandler는 OrderItem 동기화 전용·알림 무관). RETURN_REQUESTED·EXCHANGE_REQUESTED 접수 알림은 신규 영역.

### 7.3 WARN-10-α (Enum 승격 ≥10건 임계) 재평가

[NotificationTemplateCodes.java:11-17](../../backend/src/main/java/com/zslab/mall/notification/template/NotificationTemplateCodes.java#L11-L17) 현재 **7 상수**: ORDER_PLACED·PAYMENT_COMPLETED·CLAIM_APPROVED·CLAIM_COMPLETED·REFUND_FAILED·DELIVERY_STARTED·DELIVERY_COMPLETED.

승격 조건 ([NotificationTemplateCodes.java:7](../../backend/src/main/java/com/zslab/mall/notification/template/NotificationTemplateCodes.java#L7)·D-95 WARN-10-α): "Enum 승격은 ≥10건 누적 또는 DTO 검증 수요 발생 시점."

→ **RETURN/EXCHANGE 신규 templateCode 후보 4건(RETURN_REQUESTED·EXCHANGE_REQUESTED·RETURNED·EXCHANGED·픽업 등) 추가 시 7+4=11 ≥ 10 → Enum 승격 임계 도달.** 단 §7.2처럼 type 무관 재사용(CLAIM_APPROVED/COMPLETED 공유) 시 추가분 감소 → 7건 유지 가능. **신규 per-type templateCode vs 범용 재사용 결정이 임계 도달 여부를 가른다**(결정 라운드 의제·WARN-8).

---

## §8. 의제 7 — 기존 통합 테스트 회귀 영향

### 8.1 테스트 인벤토리 (@Test 카운트 실측)

| 테스트 | @Test | 회귀 위험 |
|---|---|---|
| [ClaimIntegrationTest](../../backend/src/test/java/com/zslab/mall/claim/integration/ClaimIntegrationTest.java) | 17 | **고위험** — RETURN/EXCHANGE→422 단언 추정·게이트 제거 시 깨짐 |
| [ClaimEventIntegrationTest](../../backend/src/test/java/com/zslab/mall/claim/integration/ClaimEventIntegrationTest.java) | 4 | 중위험 — CANCEL 흐름 E2E·type 분기 추가 시 영향 |
| [SellerClaimIntegrationTest](../../backend/src/test/java/com/zslab/mall/claim/integration/SellerClaimIntegrationTest.java) | 4 | 저위험 — 승인/거부 type 무관 |
| [AdminClaimIntegrationTest](../../backend/src/test/java/com/zslab/mall/claim/integration/AdminClaimIntegrationTest.java) | 3 | 저위험 — 동일 |
| [ClaimApprovedHandlerTest](../../backend/src/test/java/com/zslab/mall/refund/handler/ClaimApprovedHandlerTest.java) | 6 | **고위험** — EXCHANGE no-refund 단언 보유(PROGRESS Track 11 STEP 6 "T1~T5+EXCHANGE") |
| [RefundAutoTriggerIntegrationTest](../../backend/src/test/java/com/zslab/mall/refund/integration/RefundAutoTriggerIntegrationTest.java) | 3 | 중위험 — CANCEL 자동 트리거 E2E |
| RefundServiceTest · RefundWebhookIntegrationTest · RefundStatusTest | 12·6·4 | 저위험 — Refund 도메인 내부 |
| NotificationServiceTest · NotificationHandlerTest · NotificationLogIntegrationTest | 9·8·5 | 저위험 — type 무관 적재 |

### 8.2 핵심 회귀 트랩 (구현 진입 시 단언 갱신 의무)

- **ClaimIntegrationTest**: `request()` CANCEL 게이트(§2.1) 제거 시, RETURN/EXCHANGE 요청을 422로 단언하는 케이스가 있다면 **단언 반전 의무**(422→201). 본문 미독·LIMITATION — 구현 진입 시 1:1 확인.
- **ClaimApprovedHandlerTest EXCHANGE 케이스**: 현재 "EXCHANGE → 자동 환불 미대상" 단언. RETURN/EXCHANGE refund 시점이 ClaimApproved가 아니라 수거 후(§5.3)로 확정되면 본 단언은 **유지**(ClaimApproved 시점 미트리거는 불변)되나, 새 트리거 핸들러 테스트 신규 의무.
- **ClaimRequested/Rejected/CompletedHandler CANCEL 게이트 테스트**: type 분기 추가 시 RETURN/EXCHANGE 케이스 신규.

---

## §9. 의제 8 — D-92·D-93 횡단 원칙 재사용 2회차

### 9.1 SoT — D-92 원칙·재사용 2회차 명시

D-92 원칙 ([decisions.md:3551](../../docs/architecture-baseline/decisions.md#L3551)·[3963](../../docs/architecture-baseline/decisions.md#L3963)): "권한 검증 = Service 진입부·primitive actor 비의존·액터별 권한은 wrapper 캡슐화."

D-97 §후속 ([decisions.md:4449](../../docs/architecture-baseline/decisions.md#L4449)): "…**D-92·D-93 횡단 원칙 재사용 2회차**…" — 본 트랙이 명시적 2회차 적용 대상.

### 9.2 현재 wrapper 패턴 실측 (재사용 후보)

[ClaimService.java](../../backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java):
- primitive `approve`(139)·`reject`(161): actor 비의존·type 비의존 — RETURN/EXCHANGE 승인/거부에 **그대로 재사용**
- Seller wrapper `approveBySeller`(179)·`rejectBySeller`(195): `authorizeSellerAccess`(296·OrderItem.sellerId 대조) 경유 — type 무관·재사용
- Admin wrapper `approveByAdmin`(215)·`rejectByAdmin`(231): 전체 접근·미존재만 404 — 재사용

→ **RETURN/EXCHANGE 승인/거부는 기존 wrapper 6건 무변경 재사용.** "재사용 2회차"는 신규 wrapper 추가가 아니라 **패턴 정합 확인이 주(主)**. 신규 actor-gated wrapper는 **수거 확인(§6) 진입점이 actor 권한 분리를 요구할 때만** 발생 — 예: `confirmPickupBySeller(claimId, sellerId)`(판매자가 수거 확인) 후보. 이 경우 D-92 원칙대로 primitive(actor 비의존 confirmPickup) + Seller/Admin wrapper 캡슐화 적용.

---

## §10. 회귀 위험 평가 (정찰 룰 #6)

### 10.1 영향 매트릭스

| 도메인 | 영향 유형 | 위험 수준 | 근거 |
|---|---|---|---|
| Claim Service/Handler | 수정(CANCEL 게이트 5중 제거/분기) | **고위험** | request·4 핸들러 전부 게이트·type 분기 신규·기존 CANCEL 흐름 회귀 주의 |
| OrderItem/OrderStatus | 무변경(전이 매트릭스) ~ 수정(수거 상태 신설 시) | **중위험** | RETURN/EXCHANGE 전이 5건 기정의·단 수거 상태(§6 옵션 a) 채택 시 12값 확장 |
| Delivery | 신규(교환품 create 오케스트레이션) | **중위험** | Track 13 상태 전이 무변경·create 호출처 신설 |
| Refund | 신규(RETURN/EXCHANGE 트리거 시점·핸들러) | **중위험** | CANCEL initiate 무변경·신규 트리거 진입점 |
| Notification | 수정(메시지 분기 또는 메서드/templateCode 추가) | 저위험 | type 무관 메서드 기동작·추가만 |
| 기존 통합 테스트 | 단언 갱신 의무 | **고위험** | §8.2 ClaimIntegrationTest·ClaimApprovedHandlerTest |

### 10.2 무변경 확정 (정찰 룰 #4·#5 — 중복 신설 차단)

`OrderItemStatus.canTransitionTo`(RETURN/EXCHANGE 전이 기정의)·`ClaimStatus.canTransitionTo`(type 무관)·`ClaimType`·`claim.type` DDL ENUM·DTO `ClaimRequestRequest`/`ClaimRequestCommand`·Controller 3건·Claim 승인/거부 wrapper 6건·`claim.reason_code` DDL(VARCHAR·마이그레이션 불요)·`PolymorphicTargetType`·Track 13 Delivery 상태 전이.

### 10.3 신규 통합 테스트 의무 (CLAUDE.md §테스트)

"신규 도메인 추가 시 통합 테스트 최소 3개(인증·성공·실패)" — RETURN/EXCHANGE는 신규 도메인이 아니라 Claim 확장이나, S급 다도메인 흐름이라 흐름별 E2E 의무 예상: RETURN 요청→승인→수거 확인→환불→RETURNED 종결 / EXCHANGE 요청→승인→수거→교환 발송→EXCHANGED 종결 / RETURN/EXCHANGE 거절→원복. (예측·결정 라운드 확정.)

---

## §11. 기조 5 자체 감사 (정찰 룰 #7)

> "본 보고서는 모두 실측 기반인가?"

| 항목 | 실측 여부 | 인용 |
|---|---|---|
| ClaimService request CANCEL 게이트 | ✅ 실측 | [ClaimService.java:90-93](../../backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java#L90-L93) |
| Claim 이벤트 핸들러 4건 CANCEL 게이트 | ✅ 실측 | 4 핸들러 전체 Read·각 41~51행 게이트 |
| Controller 3건 type 중립 | ✅ 실측 | Buyer·Seller·Admin Controller 전체 Read |
| OrderItemStatus RETURN/EXCHANGE 전이 5건 기정의 | ✅ 실측 | [OrderItemStatus.java:49-53](../../backend/src/main/java/com/zslab/mall/order/enums/OrderItemStatus.java#L49-L53) |
| ClaimReasonCode 6값 | ✅ 실측 | [ClaimReasonCode.java:12-25](../../backend/src/main/java/com/zslab/mall/claim/enums/ClaimReasonCode.java#L12-L25) |
| +4값 = Javadoc/SoT 예고(enum 미반영) | ✅ 실측 | [ClaimReasonCode.java:9](../../backend/src/main/java/com/zslab/mall/claim/enums/ClaimReasonCode.java#L9)·[decisions.md:3043](../../docs/architecture-baseline/decisions.md#L3043) |
| reason_code VARCHAR 무제약(ENUM 미적용) | ✅ 실측 | [V1__init.sql:669](../../backend/src/main/resources/db/migration/V1__init.sql#L669) |
| Delivery.create production 호출처 0건 | ✅ 실측 | grep `Delivery.create\|new Delivery` → 팩토리 자기 내부 1곳만 |
| D-94 Q3 자동 트리거 CANCEL 단독 | ✅ 실측 | [decisions.md:3658-3660](../../docs/architecture-baseline/decisions.md#L3658-L3660) |
| refund ClaimApprovedHandler CANCEL 게이트 | ✅ 실측 | [ClaimApprovedHandler.java:50-54](../../backend/src/main/java/com/zslab/mall/refund/handler/ClaimApprovedHandler.java#L50-L54) |
| OrderItem 수거 확인 상태 부재 | ✅ 실측 | [OrderItemStatus.java:52-53](../../backend/src/main/java/com/zslab/mall/order/enums/OrderItemStatus.java#L52-L53) 직접 전이 |
| state-machine 수거 확인 게이트 규정 | ✅ 실측 | [state-machine.md:57](../../docs/architecture-baseline/state-machine.md#L57)·[263-264](../../docs/architecture-baseline/state-machine.md#L263-L264) |
| Notification 핸들러 6 클래스·record 7 메서드 | ✅ 실측 | glob 6·[NotificationService.java](../../backend/src/main/java/com/zslab/mall/notification/service/NotificationService.java) 7 메서드 |
| TemplateCode 7 상수 | ✅ 실측 | [NotificationTemplateCodes.java:11-17](../../backend/src/main/java/com/zslab/mall/notification/template/NotificationTemplateCodes.java#L11-L17) |
| 통합 테스트 @Test 카운트 | ✅ 실측 | grep count(claim 28·refund 31·notification 27) |
| D-92 원칙·재사용 2회차 명시 | ✅ 실측 | [decisions.md:3551](../../docs/architecture-baseline/decisions.md#L3551)·[4449](../../docs/architecture-baseline/decisions.md#L4449) |
| Claim 승인 wrapper 6건 type 무관 | ✅ 실측 | [ClaimService.java:179-233](../../backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java#L179-L233) |

**미실측 항목 (§1.4 LIMITATION 박제)**:
- `OrderService`·`OrderStatusResolver` 전체 라인 미독 (RETURNED/EXCHANGED 종결 집계 grep 참조만).
- Claim·Refund·Notification 통합 테스트 **본문 단언** 미독 (@Test 카운트·Javadoc·PROGRESS 이력 기반). §8.2 회귀 단언 1:1 확인은 구현 진입 시 의무.
- ClaimReason CODE 테이블 시드 부재는 D-89 Q5·Javadoc 인용 기반(V1 INSERT 0건 재확인은 미수행).

→ **본 보고서는 모두 실측 기반이다.** 예측·추측 항목은 §4·§5·§6 후보·§10.3에 명시적으로 "(결정 전 예측·확정 아님)" 라벨을 붙여 구분했다.

---

## §12. 결정 라운드 의제 (D-98 입력)·WARN 매트릭스

### 12.1 결정 라운드 의제

#### Q0: 트랙 식별자·범위 확정
- Track 14 = Claim RETURN/EXCHANGE 확장 확정 (D-97 §후속 라벨 carry-over 종결)
- 범위: RETURN 단독 vs RETURN+EXCHANGE 일괄 (EXCHANGE는 교환 재출고 Delivery·차액 환불로 복잡도 상위 — PR 분할 후보)

#### Q1: 수거 확인 모델링 (§6·최대 미설계 영역)
- 옵션 (a) OrderItem 신규 상태 / (b) Claim 보조 상태 / (c) 수거 추적 도메인 / (d) 신규 이벤트 E11 ClaimPickedUp
- 권장 검토: (d) 이벤트 우선(상태 폭증 회피·기조 4) — 결정 의무

#### Q2: RETURN/EXCHANGE Refund 트리거 진입점 (§5)
- RETURN: 수거 확인 후 트리거(ClaimApproved 아님). 신규 핸들러 위치 (refund/handler 또는 claim 흐름)
- EXCHANGE: 환불 금액(차액) 발생 시 조건부 트리거·amount 산정 로직 신설

#### Q3: EXCHANGE 교환품 Delivery 생성 진입점 (§4)
- Delivery.create 호출 오케스트레이션 위치: ClaimApproved/Completed 핸들러 vs 판매자 출고 API

#### Q4: CANCEL 게이트 제거/분기 범위 (§2)
- request() 단락 제거 + 이벤트 핸들러 4건 type 분기 vs RETURN/EXCHANGE 전용 신규 핸들러 신설
- 기존 CANCEL 흐름 회귀 0 보장 의무

#### Q5: RETURN/EXCHANGE COMPLETED 진입 경로 (§2.4·§5)
- markCompleted 호출처 확장(현 ClaimRefundCompletedHandler CANCEL 단독)
- ClaimCompletedHandler 종결 전이 type 분기(CANCELLED/RETURNED/EXCHANGED)

#### Q6: ClaimReasonCode +4값 추가 (§3)
- PRODUCT_DEFECT·DAMAGED_ON_ARRIVAL·WRONG_PRODUCT·DELIVERY_DELAY enum + 프론트 constants (DDL 마이그레이션 불요)

#### Q7: ClaimRejected RETURN/EXCHANGE 원복 전이 (WARN-10)
- RETURN_REQUESTED→DELIVERED·EXCHANGE_REQUESTED→DELIVERED 원복 매트릭스 신설(domain-events §E8) + ClaimRejectedHandler type 분기

#### Q8: NotificationLog 분기 전략 (§7·WARN-8)
- type 무관 재사용(7건 유지) vs per-type 신규 templateCode(≥10건 → Enum 승격 임계)

#### Q9: D-92/D-93 wrapper 재사용 (§9)
- 기존 6 wrapper 재사용 확인 + 수거 확인 actor-gated wrapper(confirmPickupBySeller 등) 신설 여부

#### Q10: PR 분할·외부 검토 라벨
- S급 다도메인 — RETURN PR / EXCHANGE PR 분할 후보·외부 검토 의무

### 12.2 WARN 매트릭스

#### P0 (블로커 — 설계 결정 전 구현 진입 불가)

| WARN | 내용 | 해소 |
|---|---|---|
| WARN-1 | 수거 확인(picked-up) 상태/이벤트 미설계 — RETURN refund·EXCHANGE 완료 공통 게이트 (§6) | Q1 결정 |
| WARN-2 | EXCHANGE 교환품 Delivery 생성 진입점 부재 — Delivery.create production 호출처 0건 (§4) | Q3 결정 |
| WARN-3 | RETURN/EXCHANGE Refund 트리거 시점 분기 미설계 — 자동 트리거 CANCEL 단독(D-94 Q3) (§5) | Q2 결정 |

#### P1 (결정 라운드 의제 의무)

| WARN | 내용 | 해소 |
|---|---|---|
| WARN-4 | CANCEL 게이트 5중(request + 핸들러 4건) 제거/분기 범위 미결 (§2.1·§2.4) | Q4 결정 |
| WARN-5 | RETURN/EXCHANGE COMPLETED 진입 경로 부재 — markCompleted CANCEL 한정 호출처 (§2.4·§5) | Q5 결정 |
| WARN-6 | ClaimReasonCode +4값 미추가 — RETURN 사유 특화 (§3) | Q6 결정 |
| WARN-7 | 기존 통합 테스트 회귀 — ClaimIntegrationTest 422 단언·ClaimApprovedHandlerTest EXCHANGE 단언 (§8.2) | 구현 시 단언 갱신 |

#### P2 (박제 권장)

| WARN | 내용 | 해소 |
|---|---|---|
| WARN-8 | NotificationTemplateCode ≥10건 임계 — 현 7건·per-type 추가 시 11건 Enum 승격(D-95 WARN-10-α) (§7.3) | Q8 결정 |
| WARN-9 | D-92/D-93 wrapper 재사용 2회차 — 기존 6 wrapper 무변경 재사용·수거 actor wrapper만 신규 후보 (§9) | Q9 결정 |
| WARN-10 | ClaimRejected RETURN/EXCHANGE 원복 전이 미정의 — 매트릭스(RETURN_REQUESTED→DELIVERED 부재)·핸들러(CANCEL 게이트) 양층 결손 (§2.4·domain-events §E8) | Q7 결정 |

---

*정찰 완료: 2026-06-30 · 산출물 1건(본 파일) · PROGRESS.md STEP 완료 처리 예정 · 다음 단계: 결정 라운드(D-98) 진입 또는 외부 검토 의뢰*
