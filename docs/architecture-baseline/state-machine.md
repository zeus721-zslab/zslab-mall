# State Machine (PR-01)

> 소스: decisions.md D-02·D-03·D-04·D-05 [확정 2026-06-24]
> 범위: Order·OrderItem·Payment·Claim 4건 한정 (baseline-plan.md §4 결정 1)

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
| FAILED | PG 결제 실패 콜백 | 재시도 = 새 Payment 행 생성 |
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

- **Delivery·Refund·Settlement 상태 전이** → 각 도메인 별도 정의 (본 PR 범위 외)
- **자동 구매확정 타이머** (배송 후 N일 → CONFIRMED) → 구현 단계
- **재고 복구 시점** (CANCELLED/RETURNED 후) → PR-02 inventory-policy.md
