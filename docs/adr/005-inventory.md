# ADR-005: 재고 모델 — 단일 SoT + 예약→차감→복구 3단계

- **상태**: 확정 (2026-06-24)
- **맥락**: PR-02 Inventory Policy. baseline-plan.md §4 결정 2·§9 보류 결정 #1

---

## 문제

재고를 어디서 보유하고, 언제 점유·차감·복구할 것인가. 결정이 없으면 두 가지 사고가 발생한다.

1. **재고 분산 보유**: ProductVariant와 Inventory가 각자 수량을 들고 있으면 정합성이 붕괴한다.
2. **차감 시점 모호**: 주문 즉시 차감하면 미결제 주문이 재고를 점유하고, 결제 후 차감하면 점유 표현이 필요하다. oversell(초과판매) 위험과 직결된다.

---

## 결정

**Inventory 테이블을 재고 단일 SoT로 두고, 예약 → 차감 → 복구 3단계 모델을 채택한다.**

- **단일 SoT**: Product·ProductVariant에 재고 컬럼을 두지 않는다(ERD 03 확인). 재고 변경은 Inventory Aggregate를 통해서만 발생한다.
- **3컬럼**: `quantity_on_hand`(실물) · `quantity_reserved`(예약) · `quantity_available`(캐시 = on_hand − reserved).
- **예약**: 주문 생성(OrderPlaced) 시 `quantity_reserved += 수량`. 주문 생성과 동일 트랜잭션(동기).
- **차감**: 결제 완료(PaymentCompleted) 시 `quantity_on_hand −= 수량` · `quantity_reserved −= 수량`.
- **복구**: 결제 전 취소 = 예약 환원(reserved−), 결제 후 취소/반품 = `quantity_on_hand += 수량`, 교환 = 회수 복구 + 신규 차감(트랜잭션 분리).
- **quantity_available 갱신**: 컬럼 캐시 유지 + 애플리케이션 갱신(DB 트리거 기각).

상세 정책은 [inventory-policy.md](../domain/inventory-policy.md), 이벤트 정의는 [domain-events.md](../architecture-baseline/domain-events.md) 참조.

---

## 이유

- **단일 SoT**: 재고 정합성의 유일 출처를 강제해 분산 보유로 인한 드리프트를 차단한다.
- **예약/차감 분리**: 결제 전 점유(예약)와 실물 차감(결제 후)을 나눠 oversell를 방지하고, 미결제 점유를 회수 가능하게 한다.
- **애플리케이션 갱신**: baseline-plan.md §9 #1·db-schema §4-1 권장. DB 트리거는 디버깅 난이도·숨은 로직·이식성 저하를 유발한다. 컬럼 캐시는 고빈도 판매가능 필터를 매 조회 계산 없이 처리한다.

---

## 영향

- 재고 차감/복구 핸들러는 **멱등**해야 한다. PG 콜백 중복·이벤트 재전파 대비 `pg_tid`/event_id 키 + OrderItem.item_status 가드를 적용한다.
- 동시 주문/차감의 정합성을 위해 동시성 락(낙관/비관)이 필요하다(구체 전략은 구현 단계).
- InventoryHistory는 **append-only**를 유지한다. quantity_on_hand 변동만 기록하고(예약/해제 미기록), change_type A분류(ORDER/CANCEL/RETURN/ADJUST/INBOUND/OUTBOUND) 값집합을 유지한다.
- quantity_available 재계산 누락을 코드 규율·테스트로 차단해야 한다(단일 진입점 강제).

---

## 대안

| 대안 | 기각 이유 |
|---|---|
| ProductVariant.stock 단일 컬럼 | 예약·이력·동시성 표현 불가. 분산 보유 정합성 위험 |
| 주문 즉시 on_hand 차감 (예약 생략) | 미결제 주문이 재고 점유·결제 실패 시 복구 빈발 |
| quantity_available DB 트리거 자동 갱신 | 누락은 막으나 디버깅·이식성·숨은 로직 비용 |
| quantity_available 컬럼 제거·동적 계산 | 고빈도 판매가능 필터에서 계산 비용·인덱스 불가 |
| InventoryHistory에 예약/해제(RESERVE/RELEASE) 기록 | change_type A분류 enum 확장(=Flyway 마이그레이션·4층위 잠금 재작업) 유발 |
