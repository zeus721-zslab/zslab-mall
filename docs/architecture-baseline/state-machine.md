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

**구현 참고사항**:
- 규칙 평가 순서: [5] → [6] → [7] → [4] → [3] → [2]
- Application Service (OrderStatusCalculator)에서 집계 후 Order.status 갱신
- Claim 처리 완료(OrderItem → CANCELLED/RETURNED/EXCHANGED) 시 재계산 트리거

---

## 6. 외부 이연

- **Delivery·Refund·Settlement 상태 전이** → 각 도메인 별도 정의 (본 PR 범위 외)
- **자동 구매확정 타이머** (배송 후 N일 → CONFIRMED) → 구현 단계
- **재고 복구 시점** (CANCELLED/RETURNED 후) → PR-02 inventory-policy.md
