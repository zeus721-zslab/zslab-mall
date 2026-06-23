# ADR-003: Order Aggregate 상태 책임 모델

- **상태**: 확정 (2026-06-24)
- **맥락**: PR-01 State Machine. baseline-plan.md §4 결정 1·결정 3

---

## 문제

주문 상태를 어디서 보유할 것인가. 두 가지 선택지가 있다.

1. **Order 단독 관리**: Order.status가 실제 상태. 하위 OrderItem 상태와 별개로 운영.
2. **OrderItem 실제 상태 + Order 집계 캐시**: OrderItem이 각자의 상태를 보유하고, Order.status는 집계 결과.

---

## 결정

**OrderItem이 실제 상태를 보유하고, Order.status는 OrderItem 집계 캐시이다.**

- `OrderItem.item_status`: 각 주문 항목의 실제 현재 상태 (12개 값 확정)
- `Order.status`: OrderItem 전체를 집계한 캐시 값 (8개 값 확정)
- `Order.status`는 OrderItem 상태 변경 이벤트 후 재계산하여 갱신

---

## 이유

쇼핑몰에서는 한 주문 안에 여러 판매자의 상품이 섞이고, 각 상품의 상태가 독립적으로 진행된다.

예시:
- 상품 A: 이미 배송완료 (DELIVERED)
- 상품 B: 아직 준비중 (PREPARING)
- 상품 C: 취소 완료 (CANCELLED)

이 상황에서 Order.status = CONFIRMED 단일값으로는 CS 응대가 불가능하다.
OrderItem 단위 상태가 없으면 "어느 항목이 어떤 상태인가"를 조회하려면 Claim·Delivery를 모두 조인해야 한다.

**Order.status를 캐시로 두는 이유**:
- 주문 목록 화면에서 Order.status 필터/정렬이 필요 (OrderItem 조인 없이 빠른 조회)
- 정규화 70 / 조회 최적화 30 균형 (baseline-plan.md §7) — 집계 캐시는 의도된 비정규화

---

## 동기화 규칙 (방식 B — 명시적 전이 조건)

```
[1] Payment.PAID 이벤트
    → 모든 OrderItem = PAID → Order.status = PAID

[2] 최초 OrderItem = PREPARING
    → Order.status = PREPARING

[3] 최초 OrderItem = SHIPPING
    → Order.status = SHIPPING

[4] 모든 OrderItem ∈ {DELIVERED}
    → Order.status = DELIVERED

[5] 모든 OrderItem = CANCELLED
    → Order.status = CANCELLED

[6] 일부 OrderItem = CANCELLED + 나머지 ∈ {CONFIRMED, RETURNED, EXCHANGED}
    → Order.status = PARTIAL_CANCEL

[7] 모든 OrderItem ∈ {CONFIRMED, RETURNED, EXCHANGED}
    → Order.status = CONFIRMED
```

방식 A(우선순위 기반) 기각 이유: 부분 반품·교환 완료 혼재 케이스에서 Order.status 계산이 직관적이지 않음.

---

## 영향

- `OrderItem.item_status`와 `Order.status`는 항상 동기화 유지 의무
- **Domain Service**에 `OrderStatusResolver` 컴포넌트 필요 (구현 단계) — Order Aggregate 내부 파생 로직(외부 Aggregate 미관여)이므로 Application Service가 아닌 Domain Service에 배치
- `OrderStatusResolver` 책임: 입력 = OrderItem 상태 집합 · 처리 = 방식 B 전이 조건 평가 · 출력 = Order.status 최종값 · 트리거 = OrderItem 상태 변경
- OrderItem 변경 시마다 Order.status 재계산 트리거 필요
- Claim 처리(CANCELLED/RETURNED/EXCHANGED) 완료 시 재계산 포함

---

## 범위 제한

- **포함**: Order Aggregate 경계 + OrderItem.item_status ↔ Order.status 동기화 책임
- **제외**: Read Model·조회 최적화·BuyerPurchaseAggregate (→ PR-03)

---

## 대안

| 대안 | 기각 이유 |
|---|---|
| Order 단독 상태 관리 | 부분 취소/반품 케이스에서 항목별 상태 추적 불가 |
| OrderItem 없이 Claim으로만 추적 | 정상 배송 흐름(ORDERED→DELIVERED)에서도 Claim이 필요해짐 |
| Order.status 없이 OrderItem만 | 주문 목록 조회 시 OrderItem 풀스캔 필요, 성능 저하 |
