# Inventory Policy (PR-02)

> 소스: decisions.md D-07·D-08·D-09 [확정 2026-06-24]
> 위치: docs/domain/ (baseline-plan.md §4 결정 2 — 재고는 DB 정책이 아니라 도메인 규칙)
> 범위: SoT·예약/차감/복구·available 갱신·InventoryHistory 기록 정책. 동시성 락 구체·다중 창고는 구현/별도 트랙 이연.

---

## 1. Source of Truth

- **Inventory 테이블이 재고 단일 SoT.** Product·ProductVariant에는 재고 컬럼이 없다(ERD 03·db-schema §2.4 확인).
- Inventory : ProductVariant = 1:1.
- 3컬럼 의미:

| 컬럼 | 의미 |
|---|---|
| quantity_on_hand | 실물 보유 수량 |
| quantity_reserved | 주문 점유(예약) 수량 |
| quantity_available | 판매가능 캐시 = quantity_on_hand − quantity_reserved |

- **판매가능 판정**(ERD 03 설계메모): `status = SALE` ∧ `quantity_available > 0` ∧ `¬is_soldout_manual`.
- ProductVariant는 재고를 **읽기만** 한다(quantity_available 참조). 재고 변경은 Inventory Aggregate를 통해서만 발생한다.

---

## 2. 예약 정책

- **시점**: 주문 생성(OrderPlaced·E1) 시 `quantity_reserved += 수량`. `quantity_on_hand`은 불변.
- **트랜잭션**: 예약은 주문 생성과 **동일 트랜잭션(동기)**으로 묶는다. eventual consistency는 oversell(초과판매) 위험이 있어 배제(D-08·M-13).
- **선점 검증**: 예약 직전 `quantity_available >= 요청 수량`을 확인한다. 부족 시 주문 트랜잭션 롤백.
- **자동 해제**: 결제 만료(미결제) 시 예약 자동 해제 — 정책만 명시. 만료 판정 타이머/배치는 구현 단계 이연(§7).

---

## 3. 차감 정책

- **시점**: 결제 완료(PaymentCompleted·E2) 수신 시.
  - `quantity_on_hand −= 수량`
  - `quantity_reserved −= 수량` (예약을 실물 차감으로 전환)
- **멱등성**: PG 콜백은 중복 수신 가능. `pg_tid` 멱등성 키 + OrderItem.item_status=PAID 가드로 재차감을 방지한다(domain-events.md E2).
- **이력**: InventoryHistory에 `change_type = ORDER`, `quantity_delta = −수량` 기록(§6).

---

## 4. 복구 정책

복구는 **결제 전/후**로 분기한다.

| 케이스 | 트리거 | 재고 동작 | InventoryHistory |
|---|---|---|---|
| 결제 전 취소 | 주문 취소(결제 전) | `quantity_reserved −= 수량` (예약 환원) | 미기록 (on_hand 불변) |
| 결제 후 취소 | ClaimCompleted CANCEL(E9) | `quantity_on_hand += 수량` | change_type = CANCEL |
| 반품 완료 | ClaimCompleted RETURN(E9) | `quantity_on_hand += 수량` (재판매 가능 검수 통과 시) | change_type = RETURN |
| 교환 완료 | ClaimCompleted EXCHANGE(E9) | 회수분 복구 + 교환품 신규 차감 | RETURN(회수) + ORDER(재출고) |

- **반품 검수**: RETURNED 후 실제 재고 복구 여부(파손·재판매 불가 등)는 **운영 영역**. 본 정책은 "검수 통과 시 복구"만 규정한다.
- **교환 트랜잭션 분리**: 회수분 복구와 교환품 신규 차감은 **별도 트랜잭션**으로 처리한다. 부분 실패 시 보상 가능하도록 분리한다(D-08).
- **change_type 매핑**(M-12): change_type에 EXCHANGE 값이 없으므로(A분류 잠금) 교환은 RETURN(회수) + ORDER(재출고) 2건으로 분리 기록한다. enum 확장(=마이그레이션)을 피한다.
- **멱등성**: OrderItem.item_status 종결값(CANCELLED/RETURNED/EXCHANGED) 가드로 재복구를 무시한다.

---

## 5. quantity_available 갱신

- **방식**: 컬럼 캐시 유지 + **애플리케이션 갱신**(D-09). DB 트리거는 디버깅 난이도·숨은 로직·이식성 문제로 기각.
- **갱신 시점**: `quantity_on_hand` 또는 `quantity_reserved`가 변경되는 모든 지점에서 `quantity_available = quantity_on_hand − quantity_reserved`를 재계산한다.
- **갱신 누락 방지**: 재고 변경 진입점을 Inventory Aggregate 단일 경로로 강제한다. 캐시 재계산 누락은 코드 규율·테스트로 차단한다.
- **동시성**: 동시 주문/차감 시 정합성을 위해 낙관적 락(version) 또는 비관적 락(SELECT FOR UPDATE)을 권장한다 — 구체 전략은 구현 단계 확정(§7).

---

## 6. InventoryHistory 기록

- **기록 범위**(M-11): InventoryHistory는 **quantity_on_hand 변동만** 기록한다. 예약/해제(quantity_reserved만 변동)는 컬럼 갱신만 하고 이력에 남기지 않는다.
  - 근거: change_type A분류 값집합(ORDER/CANCEL/RETURN/ADJUST/INBOUND/OUTBOUND)에 RESERVE/RELEASE가 없다. reserved 변동을 이력화하면 enum 확장(=Flyway 마이그레이션·4층위 잠금 재작업)이 필요하므로 배제한다.
- **change_type 매핑**:

| change_type | 발생 시점 | quantity_delta |
|---|---|---|
| ORDER | 결제 차감·교환 재출고 | 음수 |
| CANCEL | 결제 후 취소 복구 | 양수 |
| RETURN | 반품 복구·교환 회수 | 양수 |
| INBOUND | 운영자 입고 | 양수 |
| OUTBOUND | 운영자 출고 | 음수 |
| ADJUST | 운영자 실사 조정 | 음수/양수 |

- **추적**: 모든 변동은 `reference_type`/`reference_id`(D분류 polymorphic — Order/Claim/Manual)로 출처를 추적한다. append-only를 유지한다(수정·삭제 금지).

---

## 7. 외부 이연

- **다중 창고(Warehouse)**: `warehouse_id` 제거·단일 창고 전제(ERD 03). 다중 창고 도입은 별도 트랙.
- **동시성 락 전략 구체**: 낙관/비관 락 선택·재시도·데드락 회피 → 구현 단계.
- **결제 만료 자동 해제**: 미결제 주문 만료 판정 타이머/배치 → 구현 단계.
- **재판매 가능 검수 흐름**: 반품/교환 회수품의 재고 복구 가부 판정(운영 워크플로) → 운영/구현 영역.
