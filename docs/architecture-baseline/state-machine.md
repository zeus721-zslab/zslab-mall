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
| FAILED | PG 결제 실패 콜백·취소 콜백 수신 (CANCEL × PENDING 케이스) | 재시도 = 새 Payment 행 생성. 만료(expires_at 도달)는 상태 전이 트리거가 아님 (새 결제 시도 차단 해제 신호만) |
| CANCELLED | Claim 환불 완료 (Refund.COMPLETED) | 불가역 |

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
| EXCHANGE | 수거 확인 + 교환품 발송 완료 (별도 Delivery 생성) |

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
| CANCELLED | 취소완료 | Claim(CANCEL).COMPLETED |
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
| EXCHANGE | 교환 출고 → Refund.COMPLETED → Claim.COMPLETED (환불 금액 발생 시) |

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
