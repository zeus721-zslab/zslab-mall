# State Machine (PR-01)

> 소스: decisions.md D-02·D-03·D-04·D-05 [확정 2026-06-24] · D-23 [확정 2026-06-24·§7 Seller.status] · D-24 [확정 2026-06-26·§8 Refund.status] · D-31 [PAY-3 분리]·D-34 [콜백 매트릭스 CANCEL×PENDING]
> 범위: Order·OrderItem·Payment·Claim 4건 (baseline-plan.md §4 결정 1) + Seller.status §7 (D-23) + Refund.status §8 (D-24) — 6건

---

## 1. Payment.status (A분류 — 값 집합 잠금)

> A분류: 값 추가/변경 = Flyway 마이그레이션 필수.

```
PENDING ──→ PAID ──→ CANCELLED
        ↘ FAILED
```

| 상태 | 진입 조건 | 비고 |
|---|---|---|
| PENDING | Payment 행 생성 시 초기값 | — |
| PAID | PG 결제 성공 콜백 | — |
| FAILED | PG 결제 실패 콜백·취소 콜백 수신 (CANCEL × PENDING 케이스)·만료 배치 (expires_at 도달) | 재시도 = 새 Payment 행 생성. 만료(expires_at 도달) 시 자동 배치가 PENDING→FAILED 전이 (D-08 M-14·Track 25 D-109·failure_code=PAYMENT_EXPIRED) |
| CANCELLED | (a) Claim 환불 완료 (Refund.COMPLETED) 자동전이 (PaymentRefundCompletedHandler) · (b) 자동전이 유실 시 운영자 수동 markCancelledByAdmin 보정 (Track 28 D-113) | 불가역·(a)·(b) 전액환불 가드(D-71)·CANCELLED 멱등 NO-OP 공유 |

**Payment.method (A분류)**:

| 값 | 설명 |
|---|---|
| CARD | 신용/체크카드 |
| BANK | 계좌이체 |
| VBANK | 가상계좌 |
| KAKAO | 카카오페이 |

> 결제 수단 확장 = 마이그레이션 필수.

---

## 2. Claim.status / Claim.type (A분류 — 값 집합 잠금)

```
REQUESTED ──→ APPROVED ──→ COMPLETED
          ↘ REJECTED
```

| 상태 | 진입 조건 | 비고 |
|---|---|---|
| REQUESTED | 구매자 취소/반품/교환 요청 | — |
| APPROVED | 관리자/판매자 승인 | — |
| REJECTED | 관리자/판매자 거절 | 재요청 = 새 Claim 행 생성 |
| COMPLETED | 환불/수거/교환발송 완료 | 불가역 |

**Claim.type별 COMPLETED 진입 조건**:

| Claim.type | COMPLETED 조건 |
|---|---|
| CANCEL | Refund.status = COMPLETED |
| RETURN | 수거 확인 + Refund.status = COMPLETED |
| EXCHANGE | 수거 확인 + 교환품 배송 완료 (별도 Delivery 생성) + (차액 발생 시) Refund.status = COMPLETED (D-115) |

**REJECTED 처리 정책**: 기존 Claim은 REJECTED 상태로 보존 (이력 추적). 재요청 시 새 Claim 행 생성.

---

## 3. OrderItem.item_status (B분류 — Code 참조, 값 집합 확정)

> B분류: Code 테이블로 라벨 관리. 값 집합은 코드 레이어 enum으로 고정.
> OrderItem이 실제 상태 보유 (baseline-plan.md §4 결정 1).

```
ORDERED → PAID → PREPARING → SHIPPING → DELIVERED → CONFIRMED
                                                    ↑
                              CANCEL_REQUESTED → CANCELLED (종료)
                              RETURN_REQUESTED → RETURNED ──→ (집계 후 Order.status)
                              EXCHANGE_REQUESTED → EXCHANGED
```

**확정 값 집합 (12개)**:

| 값 | 설명 | 진입 조건 |
|---|---|---|
| ORDERED | 주문됨 | OrderItem 생성 직후 |
| PAID | 결제완료 | Payment.PAID 처리 완료 |
| PREPARING | 준비중 | 판매자 출고 준비 시작 |
| SHIPPING | 배송중 | Delivery 등록 + SHIPPING |
| DELIVERED | 배송완료 | Delivery 상태 DELIVERED |
| CONFIRMED | 구매확정 | 구매자 확정 또는 자동 확정 |
| CANCEL_REQUESTED | 취소요청 | Claim(CANCEL).REQUESTED |
| CANCELLED | 취소완료 | Claim(CANCEL).COMPLETED, 또는 미결제 자동취소(ORDERED→CANCELLED·D-153 Phase 1) |
| RETURN_REQUESTED | 반품요청 | Claim(RETURN).REQUESTED |
| RETURNED | 반품완료 | Claim(RETURN).COMPLETED |
| EXCHANGE_REQUESTED | 교환요청 | Claim(EXCHANGE).REQUESTED |
| EXCHANGED | 교환완료 | Claim(EXCHANGE).COMPLETED |

> **복귀 전이 매트릭스 (ClaimRejected 핸들러 한정·Track 14 PR-1·D-98 Q7·스냅샷 기반)**:
> - `CANCEL_REQUESTED → CANCELLED | PAID | PREPARING` — `claim.previous_order_item_status` 스냅샷 복원
> - `RETURN_REQUESTED → RETURNED | SHIPPING | DELIVERED` — `claim.previous_order_item_status` 스냅샷 복원
> - `EXCHANGE_REQUESTED → EXCHANGED | DELIVERED` — `claim.previous_order_item_status` 스냅샷 복원
>
> **D-90 Q3 의미 변경 (Track 14·D-98 Q7)**: 기존 §주석(Track 9 PR-C)은 `CANCEL_REQUESTED → PAID`를 claim-lock release(unlock 목적·과거 상태 복원 아님)로 박제했으나, Track 14 PR-1에서 의미 변경. `claim.previous_order_item_status`(Q11) 컬럼에 Claim 요청 시점 OrderItem 상태를 저장·REJECTED 시 해당 스냅샷으로 복원(type 무관). claim-lock release 단어는 더 이상 의미 부재. PREPARING 직접 복원도 스냅샷 기반으로 지원. canTransitionTo 매트릭스 확장 반영.

> **미결제 자동취소 전이 (D-153 Phase 1·FE-12b)**: `ORDERED → CANCELLED` — 유예(30분) 경과한 PENDING_PAYMENT 주문의 전 품목을 자동취소 배치가 CANCELLED로 전이한다. `OrderItemStatus.canTransitionTo` 매트릭스에 `ORDERED → CANCELLED`를 추가(기존 `ORDERED → PAID`와 병존). 무분별 허용 방지는 호출부(`OrderAutoCancelService`)의 status=PENDING_PAYMENT + item=ORDERED 이중 가드로 한정한다.

---

## 4. Order.status (B분류 — Code 참조, 값 집합 확정)

> Order.status = OrderItem 집계 캐시 (baseline-plan.md §4 결정 1).
> OrderItem 변경 후 Order.status를 재계산하여 갱신.

**확정 값 집합 (8개)**:

| 값 | 설명 |
|---|---|
| PENDING_PAYMENT | 결제 전 (주문 생성 직후) |
| PAID | 결제완료 |
| PREPARING | 준비중 |
| SHIPPING | 배송중 |
| DELIVERED | 배송완료 |
| CONFIRMED | 구매확정 |
| CANCELLED | 전체 취소 |
| PARTIAL_CANCEL | 부분취소 |

---

## 5. OrderItem ↔ Order 동기화 규칙 (D-04 방식 B)

> 명시적 전이 조건. OrderItem 상태 변경 후 Order.status 재계산 트리거.

```
[1] Payment.PAID 이벤트
    → 모든 OrderItem = PAID
    → Order.status = PAID

[2] 최초 OrderItem = PREPARING
    → Order.status = PREPARING

[3] 최초 OrderItem = SHIPPING
    → Order.status = SHIPPING

[4] 모든 OrderItem ∈ {DELIVERED}
    → Order.status = DELIVERED

[5] 모든 OrderItem = CANCELLED
    → Order.status = CANCELLED

[5-b] 미결제 자동취소 (D-153 Phase 1)
    → 유예 경과 PENDING_PAYMENT 주문의 전 OrderItem = CANCELLED
    → Order.status = CANCELLED (규칙 [5] 재사용·Resolver 파생)
    → OrderCancelled 발행 → Inventory 예약 해제(InventoryOrderCancelledHandler)

[6] 일부 OrderItem = CANCELLED
    + 나머지 ∈ {CONFIRMED, RETURNED, EXCHANGED}
    → Order.status = PARTIAL_CANCEL

[7] 모든 OrderItem ∈ {CONFIRMED, RETURNED, EXCHANGED}
    → Order.status = CONFIRMED
```

### OrderStatusResolver (Domain Service)

Order.status는 OrderItem 집계 캐시이므로, OrderItem 상태가 변경될 때마다 `OrderStatusResolver`가 재계산하여 Order.status를 갱신한다.

| 항목 | 내용 |
|---|---|
| 입력 | 한 Order의 OrderItem 상태 집합(item_status) |
| 처리 | 방식 B 명시적 전이 조건 평가([1]~[7]) |
| 출력 | Order.status 최종값(8값 중 1) |
| 위치 | **Domain Service** — Order Aggregate 내부 파생 로직(외부 Aggregate 미관여)이므로 Application Service가 아닌 Domain Service에 배치 |
| 재계산 트리거 | OrderItem 상태 변경(Payment·Delivery·Claim 이벤트 소비 후) |

**평가 순서**: [5] → [6] → [7] → [4] → [3] → [2]
- **종료 상태 우선**: 전체 취소[5]·부분 취소[6]·전체 확정/반품/교환[7]을 먼저 판정해, 진행 중 상태가 종료 케이스를 가리지 않도록 한다.
- **진행 상태 역순**: 이후 배송완료[4] → 배송중[3] → 준비중[2]을 평가해 "가장 진행된 단계"를 Order.status로 반영한다.
- [1](PAID)은 결제 이벤트 직후 일괄 적용되므로 재계산 평가 순서에서 제외한다.
- Claim 처리 완료(OrderItem → CANCELLED/RETURNED/EXCHANGED) 시 재계산 트리거.

---

## 6. 외부 이연

- **Delivery 상태 전이** → §6.1로 정의 완료 (Track 13·D-97·이연 해소)
- **자동 구매확정 타이머** (배송 후 N일 → CONFIRMED) → 구현 단계
- **재고 복구 시점** (CANCELLED/RETURNED 후) → PR-02 inventory-policy.md

### 6.1 Delivery.status (B분류 — DELIVERY_STATUS·Track 13 D-97)

> 소스: decisions.md D-97 [확정 2026-06-30]·DLV-1~3(invariants.md §2.12)·domain-events.md E4·E5·A#12.
> Track 13에서 본래 §6 이연("Delivery 상태 전이 → 각 도메인 별도 정의")을 영구 해소한다.

```
   READY ──→ SHIPPING ──→ DELIVERED
```

- **READY 의미**: Delivery 엔티티 생성 완료 상태. carrier는 존재하며 tracking_no·shipped_at은 비어 있을 수 있다. 일반 배송·교환 배송 모두 동일 의미이며 실제 출고 개시는 `markShipping()`에서 수행한다(D-98 Q3).
- **단방향 직진**: READY → SHIPPING → DELIVERED. 단계 건너뛰기(READY → DELIVERED) 차단.
- **DELIVERED 종결**: DELIVERED에서 어떤 전이도 불가(불가역).
- **역방향·자기 전이 차단**: SHIPPING → READY·DELIVERED → SHIPPING·동일 상태 재전이 전건 차단.
- **가드 위치**: `DeliveryStatus.canTransitionTo(next)`(OrderItemStatus.canTransitionTo 패턴 1:1)·전이 실행은 `Delivery.markShipping`·`markDelivered` 도메인 메서드(D-97 Q2).
- **OrderItem 연동(§3 정합)**: SHIPPING 진입 시 E4 DeliveryStarted → OrderItem SHIPPING·DELIVERED 진입 시 E5 DeliveryCompleted → OrderItem DELIVERED(domain-events §2 "OrderItem 상태 = 동기").
- **DLV-3**: markDelivered 시 shipped_at ≤ delivered_at 검증(invariants §2.12).

---

## 7. Seller.status (B분류 — Code 참조, SELLER_STATUS)

> 소스: decisions.md D-23 [확정 2026-06-24]·SLR-4(invariants.md §2.4)·§1.13 B#1.
> B분류: Code 테이블로 라벨 관리. 값 집합은 코드 레이어 enum으로 고정(추가 = Flyway 마이그레이션 + Code 시드).
> 본 절은 §6 외부 이연 대상이 아니다(§6은 Delivery·Settlement 한정·Refund는 §8 D-24). Seller.status는 D-23으로 본 절에 정의된다.

```
   PENDING ──→ ACTIVE ⇄ SUSPENDED
      │          │          │
      └──────────┴──────────┴──→ TERMINATED (불가역)
```

**확정 값 집합 (4개)** (V1__init.sql seller.status·§1.13 B#1):

| 값 | 진입 조건 | 비고 |
|---|---|---|
| PENDING | Seller 행 생성 시 초기값 | 입점 심사 대기 |
| ACTIVE | 운영자 입점 승인 | 정상 영업 |
| SUSPENDED | 운영자 정지 (정책 위반·정산 미납 등) | ACTIVE에서만 진입·복귀 가능 |
| TERMINATED | 탈퇴 요청·운영자 강제 종료·승인 거부/심사 철회 | 불가역·WithdrawnSeller 행 생성·비식별화 흐름 시작 (D-23·SLR-6) |

**전이 규칙**:

| 전이 | 트리거 |
|---|---|
| PENDING → ACTIVE | 운영자 입점 승인 |
| PENDING → TERMINATED | 승인 거부·심사 중 철회 |
| ACTIVE → SUSPENDED | 운영자 정지 (정책 위반·정산 미납 등) |
| SUSPENDED → ACTIVE | 운영자 정지 해제 |
| ACTIVE → TERMINATED | 탈퇴 요청·운영자 강제 종료 |
| SUSPENDED → TERMINATED | 강제 종료 |

**불가역**: TERMINATED. 진입 후 상태 변경 없음. WithdrawnSeller 행 생성 → 법정 보관 → 배치 비식별화 (D-23 흐름).

**전이 권한**: 운영자(ADMIN_OPERATOR 이상)가 모든 전이를 수행. 판매자 자기 종료(SELLER_OWNER) 요청은 운영자 승인을 경유 — 판매자 직접 종료 권한 부여 여부는 구현 트랙 이연(본 트랙 결정 범위 외).

**TERMINATED → 비식별화 연동**: TERMINATED 진입이 D-23 비식별화 흐름의 시작점이다. state-machine 전이(상태)와 비식별화(불가역 개인정보 파기)는 별개 축이며, 후자는 deletion-policy §3·D-23 배치가 담당한다.

---

## 8. Refund.status (A분류 #15)

> 소스: decisions.md D-24 [확정 2026-06-26]·invariants.md §2 RFN·V1__init.sql refund.status·§1.13 A#15.
> A분류: ENUM 값 집합 코드 레이어 enum 고정. 추가/변경 = Flyway 마이그레이션 + db-schema 갱신.
> 본 절은 §6 외부 이연 대상이 아니다(§6은 Delivery·Settlement 한정). Refund.status는 D-24로 본 절에 정의된다.

```
PENDING ──→ COMPLETED (불가역)
        └─→ FAILED    (불가역)
```

**확정 값 집합 (3개)** (V1__init.sql refund.status·§1.13 A#15):

| 값 | 진입 조건 | 비고 |
|---|---|---|
| PENDING | Refund 행 생성 시 초기값 (Claim 승인 후 Domain Service가 생성) | PG 환불 요청 대기 |
| COMPLETED | PG 환불 콜백/응답 성공 | 불가역·refunded_at·pg_refund_id 채움 |
| FAILED | PG 환불 콜백/응답 실패 | 불가역·재시도는 새 Refund 행 생성 |

**전이 규칙**:

| 전이 | 트리거 | 멱등성 |
|---|---|---|
| PENDING → COMPLETED | PG 환불 콜백/응답 성공 | pg_refund_id 키로 중복 호출 시 no-op (Service 가드) |
| PENDING → FAILED | PG 환불 콜백/응답 실패 | failure 응답 멱등 처리·재시도는 새 행 |

**불가역**: COMPLETED·FAILED 모두 불가역. 상태 변경 없음. 재시도는 동일 Claim 하위 신규 PENDING row 생성 (Claim §2 재시도 패턴 일관).

**Claim 연동 (§2 미러링)**:

| Claim.type | 연동 순서 |
|---|---|
| CANCEL | Refund.COMPLETED → Claim.COMPLETED |
| RETURN | 수거 확인 → Refund.COMPLETED → Claim.COMPLETED |
| EXCHANGE | (차액 발생 시) 교환 출고 시 Refund 생성 → 교환 배송 완료 + Refund.COMPLETED 수렴 → Claim.COMPLETED (D-115) |

**Payment 연동 (D-05 정합)**: Refund.COMPLETED 후 Payment.status는 환불 누적 금액에 따라 CANCELLED 전이 가능 (PAY-1 invariant·Domain 검증).

**전이 권한**: 자동 (PG 콜백 핸들러). 운영자 수동 보정 권한 없음 — 후속 트랙(D안 RefundAdjustment) 검토 사항.

---

## 9. Settlement.status (A분류 #5)

> 소스: Entity 정찰 §17 발견 1·invariants.md STL-2·V1__init.sql settlement.status·§1.13 A#5·Track A 사전 결정 A6 [확정 2026-06-26].
> A분류: ENUM 값 집합 코드 레이어 enum 고정. 추가/변경 = Flyway 마이그레이션 + db-schema 갱신.
> 본 절은 §6 외부 이연 대상이 아니다(§6은 Delivery 한정). Settlement.status는 Track A 사전 결정 A6으로 본 절에 정의된다.

전이 다이어그램 (평문 들여쓰기):

    PENDING ──→ CONFIRMED ──→ PAID (불가역)

**확정 값 집합 (3개)** (V1__init.sql settlement.status·§1.13 A#5):

| 값 | 진입 조건 | 비고 |
|---|---|---|
| PENDING | Settlement 행 생성 시 초기값 (정산 주기 도래 시 Domain Service가 생성) | 정산 금액 미확정 |
| CONFIRMED | 운영자 정산 금액 확정 (gross·fee·refund·net 검증 후) | net = gross - fee - refund (STL-1·Domain) |
| PAID | 실 입금 처리 완료 | 불가역·paid_at 채움 |

**전이 규칙**:

| 전이 | 트리거 | 권한 |
|---|---|---|
| PENDING → CONFIRMED | 운영자 정산 금액 확정 | ADMIN_OPERATOR 이상 |
| CONFIRMED → PAID | 실 입금 처리 완료 콜백 또는 운영자 확정 | ADMIN_OPERATOR 이상 |

**역전 금지**:

| 차단 전이 | 사유 |
|---|---|
| CONFIRMED → PENDING | 정산 확정 후 미확정 복귀 금지·조정은 별도 트랜잭션 |
| PAID → CONFIRMED | 입금 완료 후 미입금 복귀 금지·환수는 별도 프로세스 |
| PAID → PENDING | 직접 전이 차단 |

**불가역**: PAID. 진입 후 상태 변경 없음. 정산 조정·환수 필요 시 별도 보상 트랜잭션 또는 후속 트랙(RefundAdjustment 패턴) 검토.

**전이 권한**: 운영자(ADMIN_OPERATOR 이상)만 수행. 판매자 자기 전이 권한 없음. SellerBankAccount 검증(SLR-2·SLR-3)은 PENDING → CONFIRMED 전이 전제 조건.

**STL-2 정합**: invariants.md STL-2 "Settlement.status 전이 Domain(enum canTransition)"를 SettlementStatus enum 내부 canTransitionTo() 메서드로 구현 (state-machine §1·§2·§3·§7·§8 동일 패턴).

**Payment·Refund 연동**: Settlement는 별도 정산 주기에 일괄 처리·Payment/Refund 트랜잭션과 분리. Settlement.refund_amount는 정산 주기 내 Refund.COMPLETED 합산 결과·실시간 연동 아님.

## 10. Product.status (A분류 #6)

> 소스: product.enums.ProductStatus(A#6·7값)·invariants.md PRD-6·V1__init.sql product.status·Track 50 승인 워크플로 [확정 2026-07-05].
> A분류: ENUM 값 집합 코드 레이어 enum 고정. 추가/변경 = Flyway 마이그레이션 + db-schema 갱신.

전이 다이어그램 (평문 들여쓰기):

    PENDING ──→ SALE (운영자 승인)
    PENDING ──→ REJECTED (운영자 거부·종료)

**값 집합 (7개)** (V1__init.sql product.status): DRAFT·PENDING·APPROVED·REJECTED·SALE·HIDDEN·STOPPED. Track 50 승인 워크플로가 소비하는 전이 대상은 PENDING·SALE·REJECTED 3값이며, 나머지(DRAFT·APPROVED·HIDDEN·STOPPED)는 값 집합에만 존재하고 전이 소비처가 아직 없다.

| 값 | 진입 조건 | 비고 |
|---|---|---|
| PENDING | Product 행 생성 시 초기값 (판매자 등록·Track 39/50) | 승인 심사 대기·카탈로그 미노출 |
| SALE | 운영자 승인 | 공개 판매·카탈로그 노출 대상 (Seller ACTIVE 전제·D-129) |
| REJECTED | 운영자 거부 | 종료 상태·재심사 없음·카탈로그 미노출 |

**전이 규칙**:

| 전이 | 트리거 | 권한 |
|---|---|---|
| PENDING → SALE | 운영자 상품 승인 | ADMIN |
| PENDING → REJECTED | 운영자 상품 거부 | ADMIN |

**역전·기타 차단**: PENDING 외 상태에서의 전이는 전부 차단(canTransitionTo=false). REJECTED는 종료 상태(재심사 없음). SALE·HIDDEN·STOPPED 등에서의 전이는 소비처가 없어 도입하지 않는다.

**전이 권한**: 운영자(ADMIN)만 수행(SecurityConfig {@code /api/v1/admin/**}). 판매자 자기 전이 권한 없음.

**PRD-6 정합**: invariants.md PRD-6 전이 규칙을 ProductStatus.canTransitionTo() 메서드로 구현 (Settlement §9 canTransitionTo 동일 패턴). 등록 초기=PENDING·SALE 도달=승인 경유(등록 직행 SALE 아님·Track 50).

**확장 지점**: 판매자 상품 수정/재심사 기능 도입 시 REJECTED→PENDING 전이 검토.

---

## 11. FE-12c 갱신 — 미결제 주문 종료 (Order.status·Payment.status)

> 소스: D-154·PaymentService(handleFailure/handleCancel/terminateUnpaid)·Payment(fail/expire)·OrderService.markPaid·OrderAutoCancelService.cancelOne·V18/V19 [확정 2026-07-09].
> [갱신] FE-12b의 "PENDING_PAYMENT → CANCELLED(미결제 자동취소)" 블록 SUPERSEDED. 미결제 종료는 신규 PAYMENT_EXPIRED로 수렴(CANCELLED는 결제 후 사용자 취소=Claim 경로 전용).

### Order.status — 신규 값 PAYMENT_EXPIRED

전이 (평문 들여쓰기):

    PENDING_PAYMENT ──→ PAYMENT_EXPIRED (미결제 종료·비노출)

| 값 | 진입 조건 | 비고 |
|---|---|---|
| PAYMENT_EXPIRED | 결제 미완 종료(결제창 이탈·PG 실패·30분 만료·INITIATE_FAILED) | 구매자 목록 비노출·재고 해제 완료·삭제 대상(FE-12c-2) |

**전이 규칙**:

| 전이 | 트리거 | 방식 |
|---|---|---|
| PENDING_PAYMENT → PAYMENT_EXPIRED | OrderAutoCancelService.cancelOne(auto-cancel 배치·PG 실패·결제창 취소·만료) | Order.expirePayment() 직접 세팅(Resolver 미경유·OrderItem 무변경)·멱등(status≠PENDING_PAYMENT skip)·OrderTerminated 발행 |

**직접 세팅 예외(ORD-2)**: PAYMENT_EXPIRED는 PENDING_PAYMENT와 동일하게 Resolver 파생 대상이 아니라 Order가 직접 세팅한다(OrderItem 집계 미경유). OrderItem은 ORDERED 유지(무변경).

**늦은 웹훅 차단 불변식**: 결제 성공(markPaid) 승인은 Order.status==PENDING_PAYMENT일 때만. PAYMENT_EXPIRED 종료 후 지연 SUCCESS 콜백은 거부(IllegalStateException·롤백)해 PAID 부활·재고 음수화 차단.

### Payment.status — 신규 값 EXPIRED

전이 (평문 들여쓰기):

    PENDING ──→ PAID
    PENDING ──→ FAILED   (PG 실제 결제 실패)
    PENDING ──→ EXPIRED  (결제창 이탈·30분 만료)
    PAID    ──→ CANCELLED (환불 흐름·Track 5)

| 값 | 진입 조건 | 비고 |
|---|---|---|
| EXPIRED | 결제창 이탈(handleCancel×PENDING)·30분 만료(expireOne) | 종결·이벤트 미발행·Payment.expire() |

**FAILED vs EXPIRED 구분**: FAILED=PG가 실패로 통지한 실제 결제 실패(handleFailure·failure_code 유지). EXPIRED=결제가 성립 못 하고 유효기간 종료(이탈·만료). 실패를 EXPIRED로, 만료를 FAILED로 뭉개지 않는다(원칙 4).

**이벤트 미발행**: EXPIRED·FAILED 모두 재고 해제·주문 종료를 유발하지 않는다. Payment.fail()의 PaymentFailed 발행 제거(소비처 0). 재고 해제·주문 종료는 Order 종료 경로(OrderTerminated)가 단일 담당(원칙 3·4).

**종결**: FAILED·EXPIRED·CANCELLED 전이 불가(canTransitionTo=false).

### 이벤트 — OrderCancelled → OrderTerminated

OrderCancelled를 OrderTerminated로 개명. CANCELLED(Claim 경로)·PAYMENT_EXPIRED(미결제 종료) 양쪽이 발행. 재고 해제는 InventoryOrderTerminatedHandler 단일 구독(AFTER_COMMIT·REQUIRES_NEW·멱등 reserved==0 skip·order_item.variant_id 기반·Order.status 비의존). InventoryPaymentFailedHandler·PaymentFailed 이벤트 제거.

---

## 12. FE-12c-2 갱신 — 결제 시작 상태 가드·미결제 종료 주문 삭제

> 소스: D-155·PaymentService.initiate·ExpiredOrderCleanupService·ExpiredOrderCleanupScheduler [확정 2026-07-10].

### Payment 시작(initiate) 전제 조건
- initiate는 Order.status==PENDING_PAYMENT일 때만 새 Payment(PENDING) 생성 허용. 그 외 상태 → OrderNotPendingPaymentException(422).
- 통과: PENDING_PAYMENT(INITIATE_FAILED·만료 PENDING 재시도 포함). 차단: PAYMENT_EXPIRED·PAID 등.
- 목적: PAYMENT_EXPIRED(삭제 대상) 주문에 결제 자식이 새로 생기는 동시성 창 차단(불변식: PAYMENT_EXPIRED는 새 결제 생성 불가).

### PAYMENT_EXPIRED 주문 종결 처리(삭제)
- PAYMENT_EXPIRED는 종결 상태. 이후 상태 전이 없음(불변식: 읽기 외 상태 변경·자식 생성 제외).
- 삭제 배치: updated_at ≤ now-7일 AND 전 order_item reserved==0 AND PENDING payment 부재 → hard delete(payment→snapshot→order_item→order 순차).
- reserved>0(재고 미해제): OrderTerminated 재발행으로 해제 재시도 → 이번 회차 삭제 이연(다음 배치 재확인). 재고 해제는 OrderTerminated 단일 경로 유지(원칙 3).
- 삭제 후 주문 행 소멸(구매자 목록엔 이미 FE-12c에서 비노출).

---

