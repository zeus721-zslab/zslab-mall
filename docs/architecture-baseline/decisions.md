# architecture-baseline 결정 누적

> 범위: 백엔드 + 인프라 전용. 프론트엔드 결정은 decisions-fe.md(FE-XX) 참조.

> 각 PR에서 확정된 설계 결정을 누적. 미확정 항목은 [미확정] 표시.
> 확정 → [확정] 마킹 + 날짜.

---

## PR-01 결정 항목 (2026-06-24) [확정 2026-06-24]

---

### D-01: Aggregate 경계 분할 (16개 Aggregate + Infra/Event 1 + Read Model 2건 PR-03 이연)

**상태**: [확정 2026-06-24]

**결정안**:
37개 테이블 → 아래 16개 Aggregate + Infra/Event Processing 1건으로 분할 (db-schema §3 "29개" 표기 오류 — 실제 37개). Read Model 2건(BuyerPurchaseAggregate·SellerSalesDaily)은 PR-03 이연. (원안 17 Aggregate → PR-05 D-18로 NotificationLog 재분류, 아래 [갱신] 참조.)

| # | Aggregate | Root | 포함 엔티티 (테이블) |
|---|---|---|---|
| 1 | User | User | WithdrawnUser, BuyerProfile, UserAddress |
| 2 | Auth | Role | Permission, UserRole, RolePermission |
| 3 | BuyerGrade | BuyerGrade | GradePolicy |
| 4 | Seller | Seller | SellerBankAccount, SellerUser |
| 5 | Settlement | Settlement | (단독) |
| 6 | Category | Category | (self-ref 계층) |
| 7 | Product | Product | ProductImage, ProductOptionGroup, ProductOptionValue, ProductVariant |
| 8 | Inventory | Inventory | InventoryHistory |
| 9 | CartItem | CartItem | (단독) |
| 10 | Order | Order | OrderItem, OrderShippingSnapshot |
| 11 | Payment | Payment | (단독) |
| 12 | Delivery | Delivery | (단독) |
| 13 | Claim | Claim | Refund |
| 14 | Code | CodeGroup | Code |
| 15 | Attachment | Attachment | (단독, polymorphic) |
| 16 | AuditLog | AuditLog | (단독, append-only) |

> **Infra/Event Processing 1건** (Aggregate 아님): NotificationLog (단독·이벤트 소비 기록). 원안 #17 → PR-05 D-18 재분류. aggregate-boundary.md §2.7.

**Read Model 후보 (PR-03 이연)**:

| # | 테이블 | 이연 근거 |
|---|---|---|
| R1 | BuyerPurchaseAggregate | db-schema §2.2 "Read Model" 명시 |
| R2 | SellerSalesDaily | db-schema §1.11·§2.8 "집계 테이블 (배치 갱신)" 명시 |

baseline-plan.md §10 준수 — Read Model 작업은 PR-03 트랙에서 처리.

**Why**: DDD Aggregate 4기준(함께 생성·함께 삭제·트랜잭션 동반·독립 수정 불가) 적용.
- Product에 ProductVariant 포함: Variant는 Product 없이 생성 불가, 상태 변경도 상품 컨텍스트 내.
- Inventory 독립: 주문·취소·반품에 의해 Order 트랜잭션과 별도로 갱신됨.
- Claim에 Refund 포함: Refund는 Claim 승인 후에만 생성되며 Claim과 생명주기 공유.
- Payment·Delivery는 각각 독립: PG 콜백·물류 처리가 별도 컨텍스트.
- 집계·Read Model 후보(BuyerPurchaseAggregate·SellerSalesDaily)는 Aggregate에서 분리 — 도메인 트랜잭션 주체가 아니라 이벤트 핸들러로 갱신되는 파생 데이터이며 baseline-plan.md §10에 의거 PR-03 이연.

**Impact**: Aggregate간 참조는 ID만 허용 (객체 참조 금지). 다른 Aggregate 조작은 도메인 이벤트 또는 Application Service에서 처리.

**Alternative**: Order Aggregate에 Payment, Delivery 포함 → 주문 트랜잭션 단순화. 단, PG 콜백 등 비동기 처리 시 Order 잠금 범위 확대 위험.

**확정 결과**:
- [확정-A] SellerUser → Seller Aggregate 포함 (SellerUser는 Seller 컨텍스트에서 생성·삭제)
- [확정-B] CartItem → 독립 Aggregate (#9) (User 트랜잭션과 무관하게 조작)
- [확정-C] Delivery → 독립 Aggregate (#12) (물류 컨텍스트에서 독립 갱신)

**[갱신 2026-06-24 PR-05]** (D-18):
- NotificationLog를 Aggregate(원안 #17)에서 **Infra/Event Processing**으로 재분류 → **16 Aggregate + Infra/Event Processing 1건**.
- 사유: (1) 도메인 트랜잭션 주체 아님(불변식·전이 없음) (2) 타 Aggregate 발행 이벤트(E1·E2·E4·E5·E9·E10)의 소비 기록 (3) append-only이나 감사 의무 아님(발송 로그). AuditLog는 감사 의무로 Aggregate 유지(#16).
- 위치: aggregate-boundary.md §2.7 Infra/Event Processing. 삭제 정책은 ARCHIVE 유지(deletion-policy.md §2.2·Aggregate 여부와 독립).
- [확정 2026-06-24] 마킹 유지 — 본 갱신은 절차(decisions.md 누적·D-19 잠금 표현)를 따른 분류 정정이다.

---

### D-02: Order.status 값 집합

**상태**: [확정 2026-06-24]

**확정 값 집합 (8개)**:

| 값 | 설명 |
|---|---|
| PENDING_PAYMENT | 결제 전 (주문 생성 직후) |
| PAID | 결제완료 |
| PREPARING | 준비중 (최초 OrderItem PREPARING) |
| SHIPPING | 배송중 (최초 OrderItem SHIPPING) |
| DELIVERED | 배송완료 (모든 OrderItem DELIVERED) |
| CONFIRMED | 구매확정 (모든 OrderItem CONFIRMED) |
| CANCELLED | 취소 (모든 OrderItem CANCELLED) |
| PARTIAL_CANCEL | 부분취소 (일부 CANCELLED·나머지 CONFIRMED) |

**Why**: B분류(Code 참조) — 운영자가 Code 테이블에서 라벨만 편집 가능. 값 집합은 코드 레이어 enum으로 고정. db-schema §2.6 예시 코드 기반 확장.

**Impact**: PARTIAL_CANCEL 포함으로 운영 화면에서 "부분 취소 주문" 필터 가능.

**Alternative**: PENDING_PAYMENT 제외 → 주문 생성과 결제를 단일 트랜잭션으로 묶을 경우. 단, 현재 PG 연동 구조상 결제 전 주문 생성이 일반적.

---

### D-03: OrderItem.item_status 값 집합

**상태**: [확정 2026-06-24]

**확정 값 집합 (12개)**:

| 값 | 설명 |
|---|---|
| ORDERED | 주문됨 (OrderItem 생성 직후) |
| PAID | 결제완료 |
| PREPARING | 준비중 |
| SHIPPING | 배송중 |
| DELIVERED | 배송완료 |
| CONFIRMED | 구매확정 |
| CANCEL_REQUESTED | 취소요청 |
| CANCELLED | 취소완료 |
| RETURN_REQUESTED | 반품요청 |
| RETURNED | 반품완료 |
| EXCHANGE_REQUESTED | 교환요청 |
| EXCHANGED | 교환완료 |

**Why**: OrderItem이 실제 상태 보유(baseline-plan §4 결정 1). Claim.type(CANCEL·RETURN·EXCHANGE)별로 OrderItem 상태가 분기되므로 각 클레임 타입의 최종 상태가 필요.

**Impact**: EXCHANGED 포함으로 교환 완료 후 OrderItem 최종 상태 명확. 교환 완료 후 추가 교환 요청도 EXCHANGED → EXCHANGE_REQUESTED 전이로 처리 가능.

**Alternative**: 클레임 관련 상태를 OrderItem에서 제거, Claim.status로만 추적. 단, OrderItem.item_status가 "실제 상태"라는 baseline-plan 결정 1과 충돌.

---

### D-04: OrderItem.item_status ↔ Order.status 동기화 규칙

**상태**: [확정 2026-06-24]

**확정 결정**: 방식 B (명시적 전이 조건)

```
Payment.PAID 이벤트 → 모든 OrderItem = PAID → Order.status = PAID
OrderItem = PREPARING (최초) → Order.status = PREPARING
OrderItem = SHIPPING (최초) → Order.status = SHIPPING
모든 OrderItem ∈ {DELIVERED} → Order.status = DELIVERED
모든 OrderItem = CANCELLED → Order.status = CANCELLED
일부 OrderItem = CANCELLED + 나머지 ∈ {CONFIRMED, RETURNED, EXCHANGED} → Order.status = PARTIAL_CANCEL
모든 OrderItem ∈ {CONFIRMED, RETURNED, EXCHANGED} → Order.status = CONFIRMED
```

**Why**: 쇼핑몰 CS에서 케이스별 Order.status 정합이 중요. 명시적 조건 없을 경우 부분 반품·교환 완료 혼재 케이스에서 Order.status 계산 버그 발생 위험.

**Impact**: Service.canTransition() 또는 `OrderStatusResolver`(Domain Service) 컴포넌트 필요. 이벤트 핸들러에서 OrderItem 변경 후 Order.status 재계산 트리거.

> [갱신 2026-06-24 PR-05] `OrderStatusCalculator` → `OrderStatusResolver` 명칭 통일·**Domain Service** 배치 정정(D-16). Order Aggregate 내부 파생 로직(외부 Aggregate 미관여).

**Alternative**: 방식 A (우선순위 기반 — 가장 낮은 OrderItem 상태가 Order.status). 규칙 단순하나 클레임 상태와의 우선순위 정의가 불명확.

---

### D-05: Payment·Claim State Machine (A분류 확정값)

**상태**: [확정 2026-06-24]

**Payment.status 전이**:

```
PENDING → PAID (PG 성공)
PENDING → FAILED (PG 실패, 재시도 = 새 Payment 행)
PAID → CANCELLED (Refund.COMPLETED 완료 후)
```

**Claim.status 전이**:

```
REQUESTED → APPROVED → COMPLETED
           → REJECTED  (재요청 = 새 Claim 행 생성)
```

**Why**: A분류(잠금) — 값 집합 변경 = 마이그레이션 필수. db-schema §1.13 확정값.

**확정**: REJECTED 후 재요청 = 새 Claim 행 생성 (기존 Claim은 REJECTED 상태로 보존·이력 추적 가능).

---

## 부록: 확인 필요 불일치

| # | 항목 | 현황 | 처리 |
|---|---|---|---|
| M-08 | buyer-grade.md에 grade_changed_reason 존재, db-schema §2.1에서 제거. ERD에는 없음. | ERD 기준 제거 간주 (buyer-grade.md가 구버전 보존) | 처리 불필요 |
| M-09 | db-schema §3 "총 29개" → 실제 37개 | §3 카운트 오류 | PR-04 ddl-ready-checklist.md 작성 시 일괄 처리 |

---

## PR-02 결정 항목 (2026-06-24) [확정 2026-06-24]

> 소스: RECON.md PR-02 정찰·baseline-plan.md §4 결정 2·§8·§9 #1
> 발행 주체는 모두 Aggregate Root(aggregate-boundary.md 17개)·트리거는 state-machine.md 전이와 정합.
> 사용자 확정(2026-06-24): D-06~D-09 전체 채택 · M-15=(a) Delivery 트리거 참조만(전이 규칙 미정의·§6 이연 유지) · M-11=(a) on_hand 변동만 기록.

---

### D-06: 도메인 이벤트 목록

**상태**: [확정 2026-06-24]

**결정안**: Aggregate 트랜잭션 경계를 넘는 변경점을 9개 핵심 이벤트 + 1개 선택 이벤트로 정의.

| # | 이벤트 | 발행 주체 | 트리거 | 소비 주체 | 동기/비동기 |
|---|---|---|---|---|---|
| E1 | OrderPlaced | Order | Order/OrderItem 생성(ORDERED) | Inventory(예약)·CartItem(소비)·Notification | 예약=동기 / Cart·알림=비동기 |
| E2 | PaymentCompleted | Payment | PENDING→PAID | Order(OrderItem PAID·status 재계산)·Inventory(차감)·Notification | Order·재고=동기 / 알림=비동기 |
| E3 | PaymentFailed | Payment | PENDING→FAILED·결제 만료 | Inventory(예약 해제) | 동기 |
| E4 | DeliveryStarted | Delivery | Delivery→SHIPPING | Order(OrderItem SHIPPING·status 재계산) | 동기 |
| E5 | DeliveryCompleted | Delivery | Delivery→DELIVERED | Order(OrderItem DELIVERED·status 재계산) | 동기 |
| E6 | PurchaseConfirmed | Order | OrderItem→CONFIRMED | Settlement(정산 대상)·Read Model(PR-03 소비) | 비동기 |
| E7 | ClaimRequested | Claim | →REQUESTED | Order(OrderItem *_REQUESTED) | 동기 |
| E8 | ClaimRejected | Claim | →REJECTED | Order(OrderItem 원상 복귀) | 동기 |
| E9 | ClaimCompleted | Claim | →COMPLETED | Order(OrderItem CANCELLED/RETURNED/EXCHANGED)·Inventory(복구)·Payment(CANCELLED)·Notification | 재고·Order·결제=동기 / 알림=비동기 |
| E10 | InventoryAdjusted (선택) | Inventory | 운영자 INBOUND/OUTBOUND/ADJUST | Notification(품절 해제) | 비동기 |

- **내부 전이(이벤트 아님)**: OrderItem→PREPARING·Claim→APPROVED는 Aggregate 내부 처리.
- **멱등성**: PG 콜백(E2) 중복 수신 대비 `pg_tid`/event_id 멱등성 키. 재고 차감/복구는 OrderItem.item_status 가드로 멱등.
- **재시도**: 비동기(알림·Read Model)는 지수 백오프·N회 후 DLQ. 동기는 트랜잭션 롤백.

**Why**: aggregate-boundary.md §1 — Aggregate 간 변경은 이벤트/Application Service로 처리. 경계를 넘는 9개 전이만 이벤트화하여 결합도 최소화.

**Impact**: 이벤트 발행/구독 인프라 필요(구현 단계). 동기 이벤트는 Application Service 오케스트레이션·비동기는 메시지 전파.

**Alternative**: 모든 상태 전이를 이벤트화 → 이벤트 폭증·내부 전이까지 비동기화로 정합성 복잡도 증가(기각).

---

### D-07: Inventory Source of Truth

**상태**: [확정 2026-06-24]

**결정안**: Inventory 테이블을 재고 단일 SoT로 확정.

- Product·ProductVariant에 재고 컬럼 없음(ERD 03·db-schema §2.4 확인) → Inventory 단독 보유.
- 3컬럼: `quantity_on_hand`(실물) · `quantity_reserved`(예약) · `quantity_available`(캐시 = on_hand − reserved).
- 판매가능 판정: status=SALE ∧ quantity_available>0 ∧ ¬is_soldout_manual.

**Why**: 재고 분산 보유 시 정합성 붕괴. 단일 테이블 SoT + 이력(InventoryHistory)으로 추적성 확보.

**Impact**: 모든 재고 변경은 Inventory Aggregate를 통해서만. ProductVariant는 재고를 읽기만(quantity_available 참조).

**Alternative**: ProductVariant.stock 단일 컬럼 → 예약/이력/동시성 표현 불가(기각).

---

### D-08: 재고 예약·차감·복구 시점

**상태**: [확정 2026-06-24]

**결정안**: 예약 → 차감 → 복구 3단계 모델.

| 단계 | 트리거 이벤트 | 재고 동작 | InventoryHistory |
|---|---|---|---|
| 예약 | OrderPlaced(E1) | quantity_reserved += qty | 미기록(on_hand 불변) |
| 해제 | PaymentFailed/만료(E3) | quantity_reserved −= qty | 미기록(on_hand 불변) |
| 차감 | PaymentCompleted(E2) | quantity_on_hand −= qty · quantity_reserved −= qty | change_type=ORDER |
| 복구(결제 전 취소) | ClaimCompleted/주문취소 | quantity_reserved −= qty | 미기록 |
| 복구(결제 후 취소) | ClaimCompleted CANCEL(E9) | quantity_on_hand += qty | change_type=CANCEL |
| 복구(반품) | ClaimCompleted RETURN(E9) | quantity_on_hand += qty (검수 통과 시) | change_type=RETURN |
| 교환 | ClaimCompleted EXCHANGE(E9) | 회수분 복구 + 교환품 신규 차감 (업무 단계 분리·DB 트랜잭션 단일·[갱신 Track17 D-101 §5]) | RETURN(회수) + ORDER(재출고) 2건 분리 기록(단일 DB TX 내) |

- **M-11 처리**: InventoryHistory는 on_hand 변동만 기록. 예약/해제(reserved만 변동)는 컬럼 갱신만·History 미기록. change_type A분류(ORDER/CANCEL/RETURN/ADJUST/INBOUND/OUTBOUND)에 RESERVE/RELEASE 부재와 정합.
- **M-12 처리**: 교환은 change_type EXCHANGE 부재 → 회수=RETURN·재출고=ORDER 2건 분리 기록(A분류 enum 확장 회피). **[갱신 Track17 D-101 §5]**: 2 History 행 생성은 단일 DB TX 내 처리.
- **M-13 처리**: 예약은 주문 생성과 동일 트랜잭션(동기) — oversell 방지.
- **M-14 처리**: 결제 만료 자동 예약 해제는 정책만 명시·타이머/배치는 구현 단계 이연.

**Why**: 결제 전 점유(예약)와 실물 차감(결제 후)을 분리해 oversell 방지·미결제 점유 회수 가능. 복구를 결제 전/후로 분기해 reserved/on_hand 정확히 환원.

**Impact**: 재고 차감/복구 핸들러는 멱등(OrderItem.item_status 가드). **[갱신 Track17 D-101 §5]**: 교환은 복구·차감 단일 DB 트랜잭션 내 처리·부분 실패 시 트랜잭션 자연 롤백. 2 TX 분리·Saga 도입은 D-100 Q2 Outbox 트리거 충족 시 재평가.

**Alternative**: 주문 즉시 on_hand 차감(예약 단계 생략) → 미결제 주문이 재고 점유·결제 실패 시 복구 빈발(기각).

---

### D-09: quantity_available 갱신 방식

**상태**: [확정 2026-06-24]

**결정안**: 컬럼 캐시 유지 + 애플리케이션 갱신.

- ERD 03 기정의: quantity_available는 컬럼 캐시(= on_hand − reserved). 동적 계산(VIEW) 대신 컬럼 유지.
- 갱신 트리거: 애플리케이션(Inventory 도메인 로직에서 on_hand/reserved 변경 시 available 재계산). DB 트리거 기각.
- 동시성: 낙관적 락(version) 또는 비관적 락(SELECT FOR UPDATE) — 권장만, 구현 단계 확정.

**Why**: baseline-plan §9 #1·db-schema §4-1 모두 애플리케이션 갱신 권장. DB 트리거는 디버깅 난이도·숨은 로직·이식성 문제. 컬럼 캐시는 판매가능 필터 고빈도 조회를 매번 계산 없이 처리.

**Impact**: 재고 변경 코드는 available 재계산 누락 금지(코드 규율·테스트 의무). 단일 진입점(Inventory Aggregate) 강제로 누락 위험 완화.

**Alternative 1**: DB 트리거 자동 갱신 → 누락 불가하나 디버깅·이식성 저하(기각).
**Alternative 2**: quantity_available 컬럼 제거·매 조회 시 (on_hand − reserved) 동적 계산 → 고빈도 판매가능 필터에서 계산 비용·인덱스 불가(기각).

---

## PR-03 결정 항목 (2026-06-24) [확정 2026-06-24]

> 소스: RECON.md PR-03 정찰·baseline-plan.md §8/§9 #2/§10·aggregate-boundary.md §3·domain-events.md(E6)·db-schema-decisions.md §1.7/§1.8/§1.11/§2.1/§2.2/§2.7/§2.8/§1.13
> Read Model 갱신 트리거는 domain-events.md 이벤트와 정합·삭제 분류는 db-schema §1.8 기준 확장.
> 사용자 확정(2026-06-24): D-10·D-11 전체 채택 · M-16/M-17/M-18/M-19 제안 채택 · D-12 수정 확정(Aggregate Root 단위 분류 원칙·경계 케이스 8건 보강·M-20 (a) ARCHIVE 흡수).

---

### D-10: Read Model 카탈로그

**상태**: [확정 2026-06-24]

**결정안**: Read Model = Aggregate(이벤트 핸들러) 1건 + 집계 테이블(배치) 1건 + 조회 VIEW 4건으로 분류.

| Read Model | 종류 | 갱신 방식 | 집계 키·단위 | 재계산 가능 |
|---|---|---|---|---|
| BuyerPurchaseAggregate | 집계 테이블 | 이벤트 핸들러(E6 PurchaseConfirmed) | buyer_id·구매확정 누적(lifetime) | 가능(Order CONFIRMED SUM 재집계) |
| SellerSalesDaily | 집계 테이블 | 배치(일 1회 마감) | (seller_id, sale_date)·일별 | 가능(특정 일자 재배치·upsert) |
| vw_seller_sales_monthly | VIEW | 즉시 집계(Daily 30개 GROUP BY) | (seller_id, 월) | — (파생 뷰) |
| vw_order_admin | VIEW | MERGE(실시간) | 주문 관리 화면 | — |
| vw_seller_dashboard | VIEW | MERGE(실시간) | 판매자 대시보드 | — |
| vw_buyer_grade_history | VIEW | AuditLog 기반 | 등급 변경 이력 | — |

- **Write↔Read 동기화 패턴 채택**: 실시간 누적=이벤트 핸들러(BuyerPurchaseAggregate), 일 마감=배치(SellerSalesDaily), 단순 조회·월 집계=VIEW.
- **M-16 처리**: SellerSalesDaily는 배치(일 마감). 환불·취소 당일 확정값 마감 정확성·db-schema 명시. BuyerPurchaseAggregate만 이벤트(등급 산정 실시간성).
- **멱등성**: 이벤트 핸들러는 event_id + 집계 키 가드, 배치는 idempotent upsert(재실행 안전).
- **VIEW 실제 SQL·ALGORITHM·인덱스**: PR-04/구현 이연(§5 배제).

**Why**: db-schema §1.11 조회 최적화 3패턴(MERGE VIEW / 집계 테이블 / Aggregate)을 Read Model별로 매핑. Write Model(Aggregate 트랜잭션 일관성)과 Read Model(조회·집계 파생)을 분리해 결합도·잠금 범위 축소.

**Impact**: 이벤트 핸들러·배치 잡 자리 필요(구현 단계). Read Model은 원천(Write Model)으로부터 재생성 가능 — 손실 허용·복구 경로 확보.

**Alternative**: 모든 집계를 실시간 이벤트로 → SellerSalesDaily 환불 마감 정확성 저하·중간 상태 노출(기각). CQRS Read DB 분리 → 트래픽 증가 시점 재검토(외부 이연).

---

### D-11: Audit Policy

**상태**: [확정 2026-06-24]

**결정안**: AuditLog append-only·추적 액션 7종·diff_json = JSON·changed_fields 한정 기록.

- **추적 원칙**: AuditLog append-only(수정·삭제 금지)·FK 없음·운영자/시스템 행위 모두 기록·분쟁/감사 대응 최우선.
- **추적 액션(db-schema §1.13 A분류 #18)**: CREATE / UPDATE / DELETE / APPROVE / REJECT / LOGIN / LOGOUT.
- **diff_json 컬럼 타입**: **JSON 확정**(baseline-plan §9 #2). MariaDB JSON = LONGTEXT alias + CHECK(JSON_VALID)·함수 질의 가능.
- **기록 범위(M-17)**: `{ changed_fields: [...], before: {...}, after: {...} }` — 변경 필드 한정. 민감정보(비밀번호·결제 토큰·계좌번호·주민번호) 제외/마스킹.
- **적용 우선 대상**: Settlement(상태·금액)·Seller.status·Product.status(승인/거부)·SellerUser·UserRole·RolePermission(권한)·SellerBankAccount(정산 계좌)·BuyerProfile.grade.
- **보존 기간**: 전자상거래법 등 법정 보관 준수(예: 5년). 구체 기간·파티셔닝은 운영/PR-04 이연.

**Why**: 입점형 마켓플레이스는 정산·권한·상태 변경 분쟁이 빈번. append-only 감사 로그로 "누가·언제·무엇을" 추적해 CS·법적 대응. diff_json JSON 타입은 변경 필드 질의(JSON_EXTRACT)·유효성 보장.

**Impact**: 우선 대상 테이블의 변경 지점에 AuditLog 적재 훅 필요(구현 단계). 민감정보 마스킹 규율 의무. 인덱싱·외부 적재(ES)는 PR-04/운영 이연.

**Alternative**: diff_json LONGTEXT → 질의·검증 불가(기각). before/after 전체 스냅샷 → 저장 비대화·민감정보 노출 위험(기각, changed_fields 한정 채택).

---

### D-12: 삭제 정책 (SOFT / HARD / ARCHIVE 분류)

**상태**: [확정 2026-06-24]

**결정안**: db-schema §1.8 기준 3분류. Aggregate Root 단위 적용·종속 엔티티 자동 상속·경계 케이스만 별도 명시. "상태 관리·삭제 불가"는 ARCHIVE(영구 보존)로 흡수(M-20 (a)).

**분류 원칙(Aggregate Root 단위)**: 본 분류표는 16 Aggregate Root + 1 Infra/Event Processing 기준(D-18). 종속 엔티티(Aggregate 내부 포함 엔티티)는 Root와 동일 정책 자동 적용. 경계 케이스만 별도 명시.

**주 분류표**:

| 분류 | 정의 | 대상 테이블 |
|---|---|---|
| SOFT | deleted_at 마킹·복구 가능(§1.7 컬럼) | User, Seller, Product, ProductVariant, Category, Attachment, UserAddress, ProductImage |
| HARD | 물리 삭제 | CartItem(M-18), UserRole·RolePermission(권한 회수+AuditLog·M-19), (도입 시) 세션·임시파일·검증토큰 |
| ARCHIVE | 영구 보존(삭제 불가·법정 보관·상태 관리) | Order, OrderItem, Payment, Settlement, Claim, Refund, AuditLog, Inventory, InventoryHistory, SellerSalesDaily, BuyerPurchaseAggregate |

**경계 케이스 보강(8건)** — Root와 정책이 갈리거나 주 분류표 누락분:

| 테이블 | 분류 | 근거 |
|---|---|---|
| SellerBankAccount | ARCHIVE | Seller(SOFT) 종속이나 정산 계좌 이력 = 금전 직결·법정 보관 의무 |
| WithdrawnUser | ARCHIVE | User(SOFT) 종속이나 법정 보관 후 비식별화 별도 흐름(db-schema §2.1) |
| Delivery | ARCHIVE | 독립 Aggregate·송장 이력 보존·운송 분쟁 대응 |
| NotificationLog | ARCHIVE | 독립 Aggregate·발송 이력 보존(보존 기간 후 폐기는 운영 이연) |
| Code·CodeGroup | SOFT (is_system=FALSE만) | is_system=TRUE는 삭제 불가(시스템 의존) |
| Permission·Role | HARD | 시스템 마스터 데이터·운영 단계 변경 거의 없음 |
| BuyerGrade·GradePolicy | SOFT | 등급 정책 변경 이력은 GradePolicy.is_active 컬럼으로 관리 |

> 종속 엔티티 자동 상속 예: Product(SOFT) → ProductImage·ProductOptionGroup·ProductOptionValue 자동 SOFT / Order(ARCHIVE) → OrderShippingSnapshot 자동 ARCHIVE / Seller(SOFT) → SellerUser 자동 SOFT(Auth의 권한 매핑과 달리 소속 Aggregate Root를 따름).

- **M-20 처리(확정 (a))**: "상태 관리·삭제 불가"(Order·Payment·Settlement 등)를 ARCHIVE에 흡수해 스펙 3분류 유지. ARCHIVE 정의 = "영구 보존(삭제 불가·법정 보관·상태 관리 통합)". 콜드 스토리지 물리 이전은 운영 이연.
- **소프트 삭제 + 비식별화 흐름(db-schema §2.1)**: User 탈퇴(withdrawn_at) → 로그인 차단 → 법정 보관(WithdrawnUser.legal_retention_until) → 배치 → 비식별화(anonymized_at·email NULL·phone HASH·name NULL·식별자 유지).
- **개념 분리**: 소프트 삭제(복구 가능) ≠ 비식별화(불가역 개인정보 파기).
- **운영 가이드**: 삭제 권한 = 전체관리자 한정·삭제 시 AuditLog 자동 기록·복구는 SOFT 한정.

**Why**: 전체 HARD는 분쟁·감사 대응 불가, 전체 SOFT는 개인정보보호법 위반·테이블 비대화. 데이터 성격별 3분류로 법적 의무(보관/파기) 동시 충족. Aggregate Root 단위 분류로 종속 엔티티 일괄 적용·후속 트랙 모호함 차단. 소프트 삭제와 비식별화를 분리해 "탈퇴+법정 보관 중" 상태 표현.

**Impact**: 모든 조회 쿼리에 `deleted_at IS NULL` 가드 필요(SOFT 대상). 소프트 삭제 컬럼 인덱스 영향. 비식별화 배치 잡 필요(구현 이연).

**Alternative**: (M-20 (b)) RETAIN 4분류 신설 → 분류 명확하나 스펙 3분류 이탈·ARCHIVE와 실질 차이 낮음(기각). 전체 HARD/전체 SOFT → 위 Why 사유로 기각.

---

## PR-04 결정 항목 (2026-06-24) [확정 2026-06-24]

> 소스: RECON.md PR-04 정찰·baseline-plan.md §5/§8/§9·db-schema-decisions.md §1.1/§1.6/§1.7/§1.9/§3/§4·ERD 01~05·ADR-001/003/005/006·decisions.md D-01~D-12
> 본 PR은 architecture-baseline 마지막 PR(주요 산출물 완료). DDL "준비"까지 — 실제 DDL·Flyway·테이블별 인덱스 명세는 다음 트랙(§5 배제).
> 사용자 확정(2026-06-24): D-13·D-14·D-15 전체 채택 · M-21=CHAR(30)·M-22=AuditLog public_id 부여+prefix `aud_`·ADR-006 partial index 표현 보정 → 모두 ddl-ready-checklist §7 ⚠ 등재만(실수정은 PR-04.5 architecture-baseline-fix 이연) · M-23=README 카운트 본 PR 포함(6파일).

---

### D-13: DDL Ready 체크리스트 구조

**상태**: [확정 2026-06-24]

**결정안**: ddl-ready-checklist.md를 6개 점검 카테고리로 구성. PR-00~03 확정 사항이 DDL에 누락 없이 반영되는지 점검하는 단일 레퍼런스.

| # | 카테고리 | 점검 내용 |
|---|---|---|
| 1 | 글로벌 정책(db-schema §1) | PK·public_id·시간(UTC DATETIME(6))·금액(BIGINT)·문자열 길이·charset/collation·인덱스 명명·Audit 컬럼·소프트 삭제 컬럼·FK 적용 범위 |
| 2 | 도메인별 | 17 Aggregate별 핵심 컬럼·State Machine 4건 enum/Code 매핑·Inventory 3컬럼·AuditLog diff_json·public_id prefix 매핑 |
| 3 | 보류 결정 3건(baseline-plan §9) | #1 애플리케이션 갱신(D-09)·#2 JSON(D-11)·#3 방식 B(D-04) 확정값 인용·재확인 |
| 4 | 인덱스 | index-strategy.md 인용(PK·UK·FK·복합·특수) |
| 5 | Read Model | BuyerPurchaseAggregate·SellerSalesDaily 테이블(집계 컬럼·복합 PK)·VIEW 4건은 DDL 트랙 이연 |
| 6 | 다음 트랙 진입 조건 | 체크리스트 완료 → ERD 갱신 → DDL 생성(Flyway) |

**해소 필요 항목(⚠) 등재**: 정찰 중 발견한 DDL 직전 불일치 3건을 ddl-ready-checklist §7에 "DDL 진입 전 해소 필요"로 명시. **실제 수정은 본 PR(6파일) 범위 밖** → 결정 방향만 확정·실수정은 **PR-04.5 (architecture-baseline-fix)** 이연.

- **M-21 (확정 방향: `CHAR(30)`)**: public_id 컬럼 타입 불일치 — db-schema §1.1 `CHAR(26)` vs ADR-001 §영향 `VARCHAR(30)`. ULID 26자 + prefix 4자(11종 모두 4자) = 30자 고정 → CHAR(30) 통일(고정 길이 B-Tree 효율 우위). db-schema §1.1·ADR-001 정정은 PR-04.5.
- **M-22 (확정 방향: 부여 + prefix `aud_`)**: AuditLog public_id 부여 불일치 — §1.1 미부여·ADR-001 prefix 부재 vs ERD 05·§2.7 `char26 public_id` 보유. ERD가 최신 방향·독립 Aggregate(D-01 #16)·CS 티켓 참조 가치 → AuditLog 부여 확정(부여 11→12). §1.1 부여 목록·ADR-001 prefix 표 정정은 PR-04.5.
- **ADR-006 partial index 표현 보정**: MariaDB partial index 미지원 → 일반 인덱스/(status, deleted_at) 복합 대체(index-strategy §4.2 명시). ADR-006 본문 표현 자체 수정은 PR-04.5.

**Why**: DDL 직전 골든 리뷰는 "결정이 빠짐없이 DDL에 반영되는가"의 단일 점검점. 카테고리 분류로 누락 영역(글로벌 정책·도메인·보류 결정·인덱스)을 구조적으로 차단. 발견한 불일치를 묵살하지 않고 등재해 라이브 트랩 방지.

**Impact**: DDL 트랙은 본 체크리스트를 통과 기준으로 사용. ⚠ 항목 미해소 시 DDL 진입 보류. 체크리스트는 점검 리스트이지 DDL 산출물 아님(§5 배제 준수).

**Alternative**: 체크리스트 없이 바로 DDL 작성 → 결정 누락·불일치를 라이브에서 발견(트랩). 결정마다 개별 문서 산재 → 단일 점검점 부재로 교차 검증 비용 증가(기각).

---

### D-14: 인덱스 설계 전략

**상태**: [확정 2026-06-24]

**결정안**: index-strategy.md를 명명 규칙·조회 패턴 분류·패턴별 가이드·특수 인덱스·Read Model 인덱스로 구성. 전략·패턴만 정의, 테이블별 실제 인덱스 명세는 DDL 트랙 이연(§5 배제).

- **명명 규칙(db-schema §1.6 인용·확장 없음)**: `pk_/fk_/uk_/ix_/복합 ix_`.
- **조회 패턴 분류**: 운영 화면·판매자 대시보드·구매자 화면·정산·CS/감사 5분류.
- **패턴별 가이드**:
  - PK: 전 테이블 BIGINT AUTO_INCREMENT(집계 2건 복합 PK).
  - UK: public_id(부여 대상)·도메인 유니크(email·order_no·CartItem(user_id,variant_id)·Variant 옵션 조합 4컬럼).
  - FK: db-schema §1.9 강한 결합 한정(InnoDB FK는 자식 컬럼 인덱스 자동 생성). polymorphic/집계/created_by 미적용.
  - 복합: 조회 패턴별(ix_order_buyer_status·ix_order_item_seller_status 등).
  - 커버링: 구현 단계 측정 후 확정.
- **특수 인덱스**:
  - public_id(ADR-001): 부여 대상 UNIQUE.
  - 소프트 삭제(ADR-006): deleted_at 가드 인덱스. **MariaDB partial index 미지원** → 일반 인덱스 또는 (status, deleted_at) 복합으로 대체(ADR-006 "partial index" 표현 보정).
  - polymorphic: (target_type, target_id) 복합(Attachment·AuditLog·NotificationLog).
- **Read Model 인덱스**: BuyerPurchaseAggregate(buyer_id PK·last_ordered_at)·SellerSalesDaily((seller_id, sale_date) 복합 PK).

**Why**: db-schema §1.6 명명·§1.9 FK 범위를 단일 인덱스 전략으로 통합해 DDL 트랙이 일관 적용. 소프트 삭제·polymorphic·public_id는 본 도메인 고빈도 조회 직결이라 특수 정책으로 선분류. MariaDB partial index 미지원을 사전 명시해 ADR-006 표현과의 DDL 충돌 방지.

**Impact**: DDL 트랙은 본 전략을 인덱스 명세의 기준으로 사용. 실제 인덱스 컬럼·커버링 범위·파티셔닝은 측정 기반 구현/운영 이연. 본 PR은 테이블별 명세를 생성하지 않음(§5 배제).

**Alternative**: 인덱스 전략 생략·DDL에서 즉흥 결정 → 명명 불일치·FK 범위 드리프트·소프트 삭제 인덱스 누락 위험(기각). 전 테이블 일괄 커버링 인덱스 → 쓰기 비용·저장 과다(기각, 측정 기반 이연).

---

### D-15: db-schema §3 카운트 수정 (M-09 처리)

**상태**: [확정 2026-06-24]

**결정안**: db-schema-decisions.md §3 "최종 테이블 목록" 헤딩의 "총 **29개** (집계 1개 제외 시 28개)"를 "총 **37개**"로 수정하고 카테고리 합계 라인 추가. 카테고리별 테이블 구성·수는 이미 정확(합계 37) — 헤딩 숫자만 정정.

| 카테고리 | 수 |
|---|---|
| 회원·권한 | 9 |
| 등급 | 3 |
| 판매자 | 3 |
| 상품·재고 | 8 |
| 주문·결제·배송 | 8 |
| 코드·공통 | 5 |
| 집계 | 1 |
| **합계** | **37** (9+3+3+8+8+5+1) |

**README 동일 오류(M-23·확정: 본 PR 포함)**: ERD `README.md` 머리말 "총 테이블: 29개"도 동일 오류(다이어그램별 합계 12+3+8+8+6=37과 불일치). 동일 오류·동일 수정 방식이라 동시 수정이 일관성 → **본 PR 포함 확정**(5파일 → 6파일). "총 테이블: 37개"로 정정.

**Why**: PR-01부터 누적된 M-09 확인 항목. 37개 테이블이 17 Aggregate + Read Model 2건의 근거(D-01)이며, "29개" 오기는 후속 트랙(DDL·Entity)에서 테이블 수 혼선 유발. 카테고리 합계는 정확하므로 헤딩만 수정(최소 변경).

**Impact**: db-schema가 ERD/DDL 단일 레퍼런스이므로 정정 시 후속 트랙 테이블 수 기준 확정. 카테고리 내용 변경 없음(숫자 정정만).

**Alternative**: 카운트 미수정 유지 → "29 vs 37" 혼선 지속·DDL 트랙에서 테이블 누락 의심(기각). 카테고리 재구성 → 불필요(구성은 이미 정확·최소 변경 위반, 기각).

---

## PR-04.5 정정 처리 결과 (2026-06-24)

> PR-04 ddl-ready-checklist §7 ⚠ 등재 3건의 기계적 정정. 신규 결정 없음.

### M-21 — public_id 컬럼 타입 CHAR(30) 통일 [완료]

**결정 방향(PR-04 확정)**: `CHAR(30)` 통일. ULID 26자 + prefix 4자 = 30자 고정.

**처리 파일**: db-schema-decisions.md §1.1/§1.4·ADR-001 §영향·ERD 01~05 mermaid 14곳.

### M-22 — AuditLog public_id 부여 + prefix `aud_` [완료]

**결정 방향(PR-04 확정)**: ERD가 최신 방향. 부여 대상 11 → 12.

**처리 파일**: db-schema-decisions.md §1.1 부여 표·ADR-001 prefix 목록·ERD 05 mermaid(`char30 public_id "prefix: aud_"`)·ERD README prefix 목록.

### ADR-006 §영향 표현 구체화 [완료]

**결정 방향(PR-04 확정)**: "(전략은 PR-04)" 모호 참조 → partial index 미지원 + 대체 방법 명시.

**처리 파일**: ADR-006 §영향 "(전략은 PR-04)" → "MariaDB partial index 미지원 → 일반 인덱스 또는 복합(index-strategy.md §4.2)" 구체화.

> **본 PR 완료 시점**: architecture-baseline 트랙 완전 종료. 다음 트랙: 호흡 정리 채팅 → ERD 갱신.

---

## PR-05 결정 항목 (리뷰 반영 6건) [확정 2026-06-24]

> 소스: RECON.md PR-05 정찰·baseline-plan.md §5/§6·aggregate-boundary.md·state-machine.md·ADR-003·decisions.md(D-01·D-04)·deletion-policy.md·inventory-policy.md·db-schema-decisions.md §1.8
> 본 PR은 architecture-baseline 트랙 **마지막 보강 PR**. 결정 변경 1건(D-18 NotificationLog 재분류)·나머지(D-16·D-17·D-19·D-20·D-21)는 표현·구조 보강.
> **확정 처리(2026-06-24)**: D-16~D-21 전체 채택. RECON §7 불확실 a~e 추천 채택 + c(deletion-policy §2.2 표현 정정·산출물 7파일) + f(invariants.md Delivery 섹션 보강·정찰 누락 보정). 브랜치 베이스 = fix/pr-04.5 스택.

---

### D-16: OrderStatusResolver 통일·책임 정의

**상태**: [확정 2026-06-24]

**결정안**: `OrderStatusCalculator` → `OrderStatusResolver` 명칭 통일(3곳: ADR-003·state-machine §5·decisions D-04). Resolver 책임을 명시한다.

- **입력**: 한 Order의 OrderItem 상태 집합(item_status)
- **처리**: 방식 B 명시적 전이 조건 평가(평가 순서 [5]→[6]→[7]→[4]→[3]→[2])
- **출력**: Order.status 최종값(8값 중 1)
- **위치**: **Domain Service** (Order Aggregate 내부 파생 로직 — Application Service에서 Domain Service로 표현 정정·§불확실 b)
- **재계산 트리거**: OrderItem 상태 변경(Payment/Delivery/Claim 이벤트 소비 후)

**Why**: "Calculator/Resolver" 명칭 혼재는 후속 트랙(Entity·구현)에서 컴포넌트 식별 혼선. 단일 명칭 + 책임(입력·처리·출력·위치·트리거) 명문화로 드리프트 차단. Order.status는 OrderItem→Order 단일 Aggregate 내부 집계이므로 Domain Service가 DDD상 정합(외부 Aggregate 미관여).

**Impact**: state-machine §5·ADR-003 §영향·decisions D-04 Impact 3곳 동시 갱신. 구현 트랙은 `OrderStatusResolver`(Domain Service)로 컴포넌트 생성. 동기화 규칙(방식 B) 자체는 무변경.

**Alternative**: 명칭 Calculator 유지 → 리뷰 지적(명칭/책임 모호) 미해소(기각). Application Service 배치 유지 → 단일 Aggregate 내부 로직을 Application 레이어로 올려 책임 경계 흐림(기각).

---

### D-17: Inventory 확장 경로 메모 (Reservation Tracking)

**상태**: [확정 2026-06-24]

**결정안**: `inventory-policy.md` §7 외부 이연에 "Reservation Tracking (현재 단계 도입 금지)" 하위 항목 추가.

- 향후 예약 추적 요구(예: 예약 이력 조회·예약 만료 통계) 발생 시 Reservation Tracking 도입 가능.
- **현재 단계 도입 금지** — PR-02 D-08 유지(예약/해제는 reserved 컬럼만 갱신·InventoryHistory 미기록).
- 도입 시점: 별도 ADR 발행 + decisions.md 누적. change_type A분류에 RESERVE/RELEASE 부재 → 도입 시 enum 확장(마이그레이션) 동반.

**Why**: 리뷰에서 "예약 이력 추적 부재" 지적 가능성에 대해, 현 결정(D-08)을 보호하면서 확장 경로를 명시해 후속 재논의 비용 절감. 도입 금지를 명문화해 범위 확대를 차단.

**Impact**: inventory-policy.md §7 1개 하위 항목 추가. §1~§6 본문·D-08 무변경. 현재 단계 코드/스키마 영향 0.

**Alternative**: 메모 없이 침묵 → 후속 트랙에서 동일 논의 반복(기각). 지금 Reservation Tracking 도입 → D-08 확정 위반·범위 확대(기각).

---

### D-18: NotificationLog 재분류 (D-01 갱신) — **결정 변경 1건**

**상태**: [확정 2026-06-24]

**결정안**: NotificationLog를 Aggregate에서 **Infra/Event Processing**으로 재분류. 17 Aggregate → **16 Aggregate + 1 Infra/Event Processing**.

- `aggregate-boundary.md` §2.6에서 NotificationLog 제거 → 신규 **§2.7 Infra/Event Processing** 섹션으로 이동.
- `decisions.md` D-01 [확정 2026-06-24] 마킹 **유지** + 본문 하단 [갱신 2026-06-24 PR-05] 섹션 추가(17→16+1·사유).
- §2 헤딩 "17개" → "16개 + Infra/Event Processing 1건".

**재분류 사유**:
1. 도메인 트랜잭션 주체 아님(자체 비즈니스 불변식·상태 전이 규칙 없음).
2. 다른 Aggregate가 발행한 이벤트(E1·E2·E4·E5·E9·E10)의 **소비 기록**.
3. AuditLog와 성격(append-only) 유사하나 **감사 의무 아님**(발송 로그) → AuditLog는 Aggregate 유지(#16), NotificationLog는 분리.

**Why**: Aggregate는 트랜잭션 일관성 경계·도메인 불변식의 주체다. NotificationLog는 이벤트 소비 부산물로 불변식·전이가 없어 Aggregate 기준(DDD 4기준)에 부합하지 않는다. Infra/Event Processing으로 분리해 Aggregate 목록의 의미를 정확히 유지한다.

**Impact**: aggregate-boundary §2 헤딩·§2.6/§2.7 변경. D-01 갱신. domain-events.md는 "소비 주체"로만 참조(Aggregate 번호 미인용) → 정합·무변경. deletion-policy §2.2 NotificationLog ARCHIVE 분류는 유지(삭제 정책은 Aggregate 여부와 독립) — "독립 Aggregate" 문구 처리는 §불확실 c.

**Alternative**: NotificationLog Aggregate 유지(#17) → 리뷰 지적(이벤트 소비 기록을 Aggregate로 취급) 미해소(기각). AuditLog까지 함께 Infra 분리 → AuditLog는 감사 의무·분쟁 대응 주체로 Aggregate 성격 유지가 타당(기각·NotificationLog만 분리).

---

### D-19: Aggregate 잠금 표현 추가

**상태**: [확정 2026-06-24]

**결정안**: `aggregate-boundary.md` §1 원칙에 변경 절차를 명시한다.

> "Aggregate 분할은 확정 결정. 변경 필요 시 ADR 신규 발행 + decisions.md 누적으로만 갱신. 'DDL 전 변경 가능' 등 잠금 해제 표현 금지."

**Why**: Aggregate 경계가 "임시·가변"으로 읽히면 후속 트랙(ERD·DDL·Entity)에서 무절차 변경 유혹이 생긴다. 변경 경로를 ADR + decisions 누적으로 고정해 경계 안정성을 보장한다(본 PR의 D-18 재분류도 이 절차를 그대로 따름).

**Impact**: §1 원칙에 1개 불릿 추가. 향후 Aggregate 변경은 본 절차 강제.

**Alternative**: 잠금 표현 생략 → 경계 드리프트 위험(기각). "변경 불가"로 완전 동결 → D-18 같은 정당한 재분류도 막음(기각·절차적 변경은 허용).

---

### D-20: 삭제 정책 SoT 인용

**상태**: [확정 2026-06-24]

**결정안**: `db-schema-decisions.md` §1.8 말미에 deletion-policy.md 단일 레퍼런스 인용 1줄 추가.

> "→ 상세 분류(SOFT/HARD/ARCHIVE)·경계 케이스·비식별화 흐름은 `docs/architecture-baseline/deletion-policy.md` 단일 레퍼런스 참조."

**Why**: §1.8은 SOFT 적용/미적용 2열 표만 보유. 상세 분류(D-12·deletion-policy)와의 SoT 관계가 불명해 후속 트랙이 §1.8만 보고 ARCHIVE/경계 케이스를 누락할 위험. 인용 1줄로 SoT를 deletion-policy로 명시.

**Impact**: §1.8 표·인용 본문 **무변경**, 말미 1줄만 추가. 본문 이동·축소 금지.

**Alternative**: deletion-policy 내용을 §1.8로 이동 → db-schema 비대화·SoT 이중화(기각). 인용 없이 유지 → §1.8↔deletion-policy 단절 지속(기각).

---

### D-21: Invariant 문서 신규 (invariants.md)

**상태**: [확정 2026-06-24]

**결정안**: `docs/architecture-baseline/invariants.md` 신규 작성. 16 Aggregate + Infra/Event 1 + 공통 + 외부 이연.

- **구조**: ①원칙 ②Aggregate별 invariant(2.1~2.16) + Infra/Event ③공통 invariant ④외부 이연(DDL/Entity/Service + Read Model 참고).
- **각 invariant**: Rule·Why·Enforcement Point(DB CHECK/UK/FK · Service · Domain · Batch)·Impact·Alternative.
- **원칙**: 가장 낮은 레이어(DB) 우선 강제·불가능 시 Service/Domain(D-09·D-12 정합).
- **범위 구분**: 불변조건 카탈로그 — State Machine 전이 정의 아님(전이는 state-machine.md 4건 한정). 실제 DB CHECK/UK 제약·Entity 검증·Service 가드 구현은 **외부 이연**.
- **인벤토리**: 도메인별 ≈62건(INV 6·ORD 5·PAY 3·DLV 3·CLM 4·PRD 5·USR 4·AUTH 4·GRD 3·SLR 5·STL 4·CAT 2·CRT 2·COD 3·ATT 2·AUD 4·NOT 3) + 공통 4.
- **불확실 항목 반영**(RECON §7): a) PAY-1 교차 Aggregate → Payment 절 기재 + Claim/Refund Domain 강제 명시 / d) Read Model은 외부 이연 절 참고 표기 / **f) Delivery(DLV 3건) 섹션 보강 추가**(정찰 §6 누락 보정).

**Why**: invariant(불변조건)는 상태 상위 개념으로, 위반 시 시스템 정합성이 붕괴한다. DDL 트랙의 CHECK/UK·Entity 트랙의 검증·구현 트랙의 Service 가드가 참조할 **단일 레퍼런스**를 선제 확보해 라이브 트랩(제약 누락)을 차단한다.

**Impact**: 신규 문서(다른 산출물 영향 없음). DDL·Entity·구현 트랙이 Enforcement Point 기준으로 직접 활용. ORD-2는 D-16(Resolver)·PAY-2/CLM-1은 state-machine과 정합.

**Alternative**: invariant를 각 정책 문서에 분산 → 단일 점검점 부재·교차 검증 비용 증가(기각). DDL 트랙에서 즉흥 CHECK 작성 → 도메인 불변식 누락·근거 부재(기각).

---

### D-22: 비식별화 후 재가입·재등록 정책 (CR-3 A-1 채택)

**상태**: [확정 2026-06-24]

**결정안**: 탈퇴·종료 후 비식별화가 완료된 상태에 한해 재가입(User)·재등록(Seller)을 허용한다.

- USR-1 invariant에 2줄 추가:
  · "재가입 정책: 비식별화 완료 이후 허용 (db-schema §2.1)"
  · "email은 비식별화(NULL 처리) 완료 후에만 재사용 허용"
- SLR-1 invariant에 2줄 추가:
  · "사업자 재등록 정책: 비식별화 완료 이후 허용"
  · "business_no는 비식별화(NULL 처리) 완료 후에만 재등록 허용"
- db-schema §2.1에 재가입 조건 1줄 + Seller business_no NULL 처리 대상 1줄 추가.

**Why**: 외부 리뷰 CR-3에서 "탈퇴 직후 재가입 차단·법정 보관 기간 중 재가입 허용 여부" 미정의 지적. A-1(비식별화 완료 후 허용) 채택으로 법정 보관 기간 중에는 차단·완료 후 재가입 허용을 명확화. MariaDB UNIQUE는 NULL을 비교 제외하므로 비식별화로 email/business_no가 NULL인 row가 다중 존재해도 신규 가입의 UNIQUE 제약과 충돌 없음 → DDL 변경 없이 정책 명시만으로 일관 해석 강제.

**Impact**: invariants.md USR-1·SLR-1 각 2줄·db-schema-decisions.md §2.1 2줄·TODO.md 2행 추가. DDL 영향 없음(UNIQUE 제약 그대로). Entity·구현 트랙은 본 invariant 기준으로 재가입 가드 구현. Seller 비식별화 흐름 자체 정의는 별도 후속 트랙(TODO 등재).

**Alternative**: 비식별화와 무관하게 재가입 차단(법정 보관 기간 영구 차단) → 정상 운영 시나리오 제약 과도(기각). 탈퇴 즉시 재가입 허용 → 부정 사용·법정 의무 회피 우려(기각). Seller 비식별화 흐름까지 본 트랙에서 정의 → 4지점 보강 범위 초과·D-22 단일 결정 원칙 위반(기각·후속 트랙 분리).

**후속**:
- "state-machine 보강 (Refund.status)" — Entity 트랙 진입 전 처리
- "Order.status 복구 정책 (CR-2)" — 구현 트랙 진입 전 처리
- "Seller 비식별화 흐름 정의" — Entity 트랙 진입 전 처리

---

## Seller 비식별화 트랙 (feat/seller-anonymization, 2026-06-24) [확정 2026-06-24]

> 소스: RECON.md "Entity 차단 2건 통합 정찰 (2026-06-24)" B 트랙·D-22 후속·db-schema-decisions.md §2.1/§2.3·deletion-policy.md §3·V1__init.sql(seller·withdrawn_user)
> Entity 차단 2건 정찰 후 B 트랙(Seller 비식별화) 결정 항목 9건 사용자 확정·전건 추천 채택. A 트랙(Refund.status 전이)은 후속 별도 진행.

---

### D-23: Seller 비식별화 흐름 정의 (B 트랙 9건 일괄 확정)

**상태**: [확정 2026-06-24]

**결정안**: User 비식별화 패턴(db-schema §2.1·deletion-policy §3)을 Seller에 대칭 적용한다. 9개 결정 항목을 일괄 확정한다.

| # | 항목 | 채택 | DDL V2 영향 |
|---|---|---|---|
| B-d1 | 법정 보관 흐름 구조 | WithdrawnSeller 신설·WithdrawnUser 패턴 준용 | CREATE TABLE |
| B-d2 | WithdrawnSeller 컬럼 셋 | original_seller_id·terminate_reason·legal_retention_until·anonymized_at + audit 4 | CREATE TABLE |
| B-d3 | Seller 비식별화 대상 컬럼 | company_name·ceo_name·contact_email·contact_phone 전건 NULL | NOT NULL→NULL ALTER (company_name·ceo_name) |
| B-d4 | account_number 비식별화 처리 | 암호화 키 폐기 (NOT NULL 유지·복호화 불가) | 없음 |
| B-d5 | account_holder NULL 허용 | NOT NULL 유지 (B-d4 정합) | 없음 |
| B-d6 | SellerUser 처리 | 행 유지 (감사 추적성·SOFT 상속) | 없음 |
| B-d7 | Settlement 처리 | 비식별화 대상 아님·seller_id 유지 확정 | 없음 |
| B-d8 | SLR-4 Seller.status 전이 | 본 트랙 포함 (state-machine §7 신규) | 없음 |
| B-d9 | DDL V2 처리 | 본 트랙에 V2 동반 작성 | V2__seller_anonymization.sql |

**흐름** (User 비식별화 패턴 대칭):

```
Seller TERMINATED 진입
  → WithdrawnSeller 행 생성 (terminate_reason·legal_retention_until)
  → 법정 보관 기간 유지
  → 배치 → anonymized_at 마킹·비식별화
           (company_name·ceo_name·contact_email·contact_phone·business_no NULL
            + SellerBankAccount.account_number 암호화 키 폐기)
  → 재등록 허용 (D-22 정합·business_no UK 슬롯 해제)
```

- **company_name·ceo_name**: V1 NOT NULL → V2에서 NULL 허용(비식별화 대상). 활성 판매자 등록 시 필수값은 Service 검증으로 강제(User.email/name/phone 패턴 동일).
- **contact_email·contact_phone**: V1에서 이미 NULL → DDL 변경 없음·배치 비식별화 대상.
- **account_number**: NULL 처리 대신 **암호화 키 폐기**로 비식별화. 컬럼·NOT NULL 유지·복호화 불가 → Settlement.bank_account_id 스냅샷(STL-3) 정합 보존.
- **Settlement**: 직접 비식별화 대상 아님. seller_id는 논리참조로 유지(식별자 보존·정합성).
- **SellerUser**: 행 유지(감사 추적성·Seller(SOFT) 상속).

> **SoT 정의** (외부 검토 2차): Seller가 Source of Truth·WithdrawnSeller는 종료 메타데이터(Snapshot Metadata). Seller·WithdrawnSeller 동시 수정 금지 (SLR-7 강제).

**Why**: Seller 비식별화는 User와 동일 법정 요구(전자상거래법·개인정보보호법)를 받는다. 패턴 통일로 운영·감사 일관성을 확보하고, 활성 Seller(SOFT) ↔ 종료 Seller(ARCHIVE)의 책임을 분리한다. account_number 키 폐기 정책은 "정산 스냅샷 정합성 보존"과 "개인·금융정보 비식별화 의무"를 동시 만족한다. MariaDB UNIQUE는 NULL을 비교 제외하므로 business_no NULL row 다중 존재가 신규 등록 UK와 충돌하지 않는다(D-22 정합).

**Impact**:
- invariants.md SLR 섹션 보강(SLR-1 비식별화 흐름 참조·SLR-4 state-machine 참조·신규 SLR-6 WithdrawnSeller 생성 강제).
- state-machine.md §7 신규 Seller.status 전이(B분류 SELLER_STATUS·4상태·TERMINATED 불가역). §6 외부 이연은 무변경(교차 참조 보존).
- db-schema-decisions.md §2.1 포인터 갱신·§2.3 Seller 비식별화 흐름·WithdrawnSeller 블록·§3 37→38.
- DDL V2 신규(withdrawn_seller CREATE·seller company_name/ceo_name ALTER nullable).
- Entity 트랙 Seller·SellerBankAccount 진입 차단 해소.

**Alternative**:
- Seller 본 테이블에 비식별화 메타 직접 부착(B-d1 대안) → 활성 row 비대화·SOFT/ARCHIVE 경계 흐림(기각).
- account_number NULL 처리(B-d4 대안) → Settlement 스냅샷 정합성 단절(기각).
- DDL V2 분리 트랙(B-d9 대안) → 결정·DDL 머지 사이 불일치 구간(기각).

**후속**:
- "state-machine 보강 (Refund.status)" (A 트랙) — Entity 트랙 진입 전 처리
- "Order.status 복구 정책 (CR-2)" — 구현 트랙 진입 전 처리

> **propagation 보류**: WithdrawnSeller 신설로 37→38 테이블이 되나, 본 트랙 산출물(6파일) 밖의 37-테이블·WithdrawnUser 패턴 참조처(deletion-policy.md §2.2/§2.3·db-schema §1.1/§1.8·docs/ddl/decisions.md audit 5분류·docs/ddl/RECON.md)는 미반영. "37→38 테이블 카운트·분류 propagation"은 별도 정합 트랙으로 보류(M-09 29→37·"17 Aggregate" lag 처리 방식과 동일).

---

## Refund 상태 전이 트랙 (feat/refund-state-machine, 2026-06-26) [확정 2026-06-26]

> 소스: decisions.md D-05(Claim·Refund 생명주기)·state-machine.md §2/§8·invariants.md §2 RFN·V1__init.sql refund.status·§1.13 A#15
> Entity 차단 마지막 항목(A 트랙·Refund.status 전이) 결정 3건 사용자 확정. B 트랙(Seller 비식별화·D-23)·propagation-38 머지 완료 후 진입.

---

### D-24: Refund.status 상태 전이 정의 (A 트랙 3건 일괄 확정)

**상태**: [확정 2026-06-26]

**결정안**:
1. **FAILED 재시도 정책**: 새 Refund 행 생성. FAILED는 불가역 종료 상태. 재시도는 동일 Claim 하위에 신규 PENDING row 생성. (state-machine §2 Claim 재시도 패턴 일관)
2. **COMPLETED 트리거·멱등성**: PG 콜백/응답 트리거 전용. `pg_refund_id`를 멱등성 키로 사용. 동일 `pg_refund_id` 재수신 시 no-op (Service 가드). 운영자 수동 보정 경로 없음 — 후속 트랙(D안) 검토.
3. **Claim.COMPLETED ↔ Refund.COMPLETED 연동 순서**: 케이스별 분기. state-machine.md §2 Claim 정의 미러링:
   - CANCEL: Refund.COMPLETED → Claim.COMPLETED
   - RETURN: 수거 확인 → Refund.COMPLETED → Claim.COMPLETED
   - EXCHANGE: 교환 출고 → Refund.COMPLETED → Claim.COMPLETED (환불 금액 발생 시)

**전이 규칙** (state-machine §8 신규):

```
PENDING ──→ COMPLETED (불가역·PG 환불 성공·refunded_at·pg_refund_id 채움)
        └─→ FAILED    (불가역·PG 환불 실패·재시도는 새 행)
```

**불가역**: COMPLETED·FAILED 모두 불가역. 상태 변경 없음.

**Why**: Refund.status 전이는 V1 DDL 시점부터 이연 상태(state-machine.md §6). Entity 트랙·Claim 엔티티 canTransition 구현 차단 항목. Claim §2 패턴(재시도=새 행)과 일관 적용해 감사 추적성·멱등성·정합성 동시 확보. PG 콜백 전용은 현 단계(PG 미연동)에서 과조기 최적화 회피·실 운영 데이터 누적 후 수동 보정 정책(D안) 재검토.

**Impact**:
- state-machine.md §8 Refund.status 전이 신규.
- state-machine.md §6 외부 이연 라인: Refund 제거 (§7 Seller 처리와 동일 패턴).
- invariants.md §2 신규 RFN 섹션 (RFN-1·RFN-2·RFN-3).
- DDL 영향 없음 (Refund.status ENUM·pg_refund_id 컬럼 V1 확정).
- Entity 트랙 Refund·Claim 진입 차단 해소.

**Alternative**:
- A-d1 B(FAILED→PENDING 복귀): 동일 row 상태 변경·시도 추적 불가·pg_refund_id 멱등성 모호 (기각).
- A-d2 C(자동+수동 하이브리드): PG 미연동 단계 과조기 최적화·정합 신뢰도 손실·후속 트랙으로 이연 (기각).
- A-d3 A·B(단일 순서 강제): state-machine §2 케이스별 정의와 충돌·운영 업무 흐름 불일치 (기각).

**후속**:
- Refund 수동 보정 정책 (D안 RefundAdjustment 신규 테이블 검토) — PG 운영 데이터 누적 후 진입 (TODO 등재)
- Entity 트랙 진입 가능 (B 트랙·A 트랙 머지 완료 시)

---

## 외부 검토 2차 통합 트랙 (docs/external-review-integration, 2026-06-26) [확정 2026-06-26]

> 소스: 외부 AI 2차 검토 결과·decisions.md D-23/D-24·invariants.md SLR/COM·audit-policy.md·gate-conditions.md(신규)
> 검토자 권고 4건(SoT·트랙 구조·Gate 조건·DDL 잠금)+ 즉시 확정 2건(public_id 기준·audit L1~L4)을 단일 PR 통합. 신규 설계 결정은 D-25 1건(나머지는 기존 결정 보강).

---

### D-25: Gate 후 DDL 잠금 정책 (외부 검토 2차 반영)

**상태**: [확정 2026-06-26]

**결정안**:
1. Track A~F (Base·Order Aggregate·Payment Mock·Order API·Refund Flow·Integration Test) Gate 통과 후, DDL은 V3 이상 신규 Flyway 마이그레이션으로만 수정 가능.
2. State Machine·Invariant는 Gate 후에도 수정 허용 (구현 중 발견된 도메인 규칙 변경 반영).
3. V1·V2 본문 직접 수정 금지 (이미 머지된 마이그레이션 변경 불허).
4. **예외**: 데이터 손실 없는 변경(코멘트·인덱스 추가·NULL 허용 확장 등)·오타 수정·개발 단계 긴급 수정은 V3 이상 신규 마이그레이션이되 사유를 후속 결정(D-26 이상)에 누적 명시 후 허용. 운영 데이터 정합성·롤백 가능성·재현성 3 조건 동시 충족 시 적용.

**Gate 통과 조건**: gate-conditions.md §1·§2·§3 (구조·기능·기술 3 카테고리 전건 통과).

**Why**: 옵션 C 진입 후 "구현 → 문서 역전" 위험을 외부 검토자가 지적. State/Invariant는 도메인 규칙 정제가 정상 진화 경로이나, DDL은 운영 데이터 정합성 직결. Gate 전후 변경 권한을 분리해 검증 루프와 재설계 루프를 명확히 구분. 단, Gate 직후 초기 조정 필요성을 인정해 데이터 손실 없는 예외를 §4로 허용.

**Impact**:
- Track A~F 진행 중 DDL 수정 필요 시 V3·V4 신규 마이그레이션 작성 (V1·V2 ALTER 또는 본문 수정 금지).
- 예외 적용 시 사유를 D-26 이상에 누적·운영 데이터 정합성 검증 동반.
- State Machine·Invariant는 정상 갱신 흐름 유지.
- ddl/decisions·gate-conditions.md 교차 참조.

**Alternative**:
- DDL 전체 잠금 (예외 없음) → Gate 직후 초기 조정 차단·검토자 권고 미수용 (기각).
- 잠금 없음 → 구현 단계 DDL 무절제 변경 위험·외부 검토 지적 미해소 (기각).
- State/Invariant도 함께 잠금 → 정상 도메인 규칙 진화 차단 (기각).

**후속**:
- Gate 통과 시점에 본 결정 발동 (Track F 진입 전 검증).
- DDL 수정 발생 시 D-26 이상으로 사유 누적·예외 조건 충족 검증.

---

### D-26: AbstractPublicId* publicId 매핑 CHAR(30) 명시 (Track B 발견 #2 흡수) [ARCHIVED]

**상태**: [확정 2026-06-26]

**결정안**:
1. `AbstractPublicIdFullAuditableEntity`·`AbstractPublicIdSoftDeletableEntity`의 `publicId` 필드에 `@JdbcTypeCode(SqlTypes.CHAR)`를 부착해 DDL `public_id CHAR(30)`과 JPA 매핑을 정합한다.
2. DDL(V1)·데이터는 변경하지 않는다. 매핑 계층 보강만 수행한다(D-25 §3 V1 본문 잠금과 무충돌).
3. 흡수 위치는 Track B(Order Aggregate) 첫 PR로 한다(옵션 A 채택).

**배경**: Track A(Base) 작성 시점엔 `AbstractPublicId*`를 상속하는 구체 엔티티가 없어 `ddl-auto=validate`가 한 번도 실행되지 않았다. Track B가 첫 `public_id` 엔티티(Order·OrderItem)를 작성하면서 표면화 — Hibernate가 `String` 필드를 기본 `VARCHAR`로 매핑해 DDL `CHAR(30)`과 불일치, validate가 거부했다(`Schema-validation: wrong column type ... found [char (Types#CHAR)], but expecting [varchar(30)]`). 운영 `application.yml`도 `validate`라 Order·OrderItem 배포 시 부팅 실패하는 라이브 트랩이었다(CI 미탐지).

**원인 분류 (gate-conditions §4.6)**:
- 분류: **설계 문제** (정찰·결정 단계 누락) — Track A 작성 시 실 엔티티 부재로 validate 미수행. ※ 본 건은 DDL 무변경 매핑 보강이나, 11 엔티티 공통 base 회귀이므로 §4.6 4점 추적 템플릿을 적용한다.
- 데이터 손실 없음: DDL·데이터 무변경, JPA 매핑 어노테이션 1줄 추가뿐.
- 롤백 가능성: 단순 어노테이션 제거로 이전 상태 복원 가능.
- 재현성: testcontainers-mariadb·`@DataJpaTest`로 검증 가능(Track B 산출물 `OrderDataJpaTestBase` 포함). 수정 전=validate 거부, 수정 후=BUILD SUCCESSFUL.

**Impact**:
- `AbstractPublicIdFullAuditableEntity` 상속 6종(Order·OrderItem·Payment·Delivery·Claim·Refund) + `AbstractPublicIdSoftDeletableEntity` 상속 5종(User·Seller·Product·ProductVariant·Attachment) = 11 엔티티 전체 매핑 정합 회복.
- Track B Order Aggregate `ddl-auto=validate` 통과·운영 부팅 트랩 해소.
- DDL·State Machine·Invariant·데이터 영향 없음.

**Alternative**:
- DDL을 `VARCHAR(30)`으로 변경(V3 신규 마이그레이션) → public_id는 고정 길이(prefix 3 + ULID 26 + `_`)라 CHAR가 의미상 정확·DDL 변경은 과한 대응 (기각).
- `columnDefinition = "CHAR(30)"` 명시 → DB 종속·이식성 저하·Hibernate 타입 추론 우회로 부작용 위험 (기각).

**후속**:
- Track 7(User·Seller 등) 진입 시 `AbstractPublicIdSoftDeletableEntity` 첫 구체 엔티티 작성 시점에 동일 매핑 자동 적용 확인.
- 신규 base `MappedSuperclass` 추가 시 구체 엔티티 부재 상태에서도 매핑 검증을 강제하는 방안(샘플 엔티티·@DataJpaTest) 검토 — 동종 트랩 재발 방지.

> **[ARCHIVED 2026-06-28]** 본 트랩 상세는 live-traps.md LT-01로 이관·본 결정문은 박제 이력 보존 목적 유지.

---

### D-27. 결제 콜백 컨트롤러·서비스·Command 분리 (Track 3) [ACTIVE]

**상태**: [확정 2026-06-26]

**배경**: PG 콜백 처리에 HTTP 관심사(요청·헤더)와 도메인 처리가 한 곳에 섞이면 테스트·재사용·교체가 어렵다.

**결정**:
1. `PaymentWebhookController`(HTTP 책임) → `PaymentCallbackCommand` 변환 → `PaymentService.handleCallback`(도메인 책임) → `PaymentGateway`(인터페이스·`MockPaymentGateway` 구현) 4계층 분리.
2. Service 시그니처에 `HttpServletRequest`·`HttpHeaders` 등 HTTP 타입 금지.
3. Mock Gateway 패키지: `com.zslab.mall.payment.gateway`.

**근거**: HTTP 경계와 도메인 경계 분리로 Service는 `@WebMvcTest` 없이 단위 검증 가능, 실 PG 도입 시 Gateway 구현만 교체.

**영향 범위**: `payment.controller`·`payment.command`·`payment.service`·`payment.gateway` 신규. 콜백 수신은 `POST /api/payments/callbacks`.

---

### D-28. 결제 행 생성·재시도 정책 (Track 3) [ACTIVE]

**상태**: [확정 2026-06-26]

**배경**: 결제 재시도 시 기존 행 갱신은 이력 소실·상태 충돌을 부른다. Order:Payment 카디널리티를 명문화한다.

**결정**:
1. `PaymentService.initiate(PaymentInitiateRequest)`가 결제 행을 생성한다. 주문 생성과 결제 생성은 분리 트랜잭션.
2. 카디널리티 Order 1 : Payment N. 재시도 시 기존 행 재사용 금지·항상 새 결제 행 생성.
3. `Order.currentPayment()` 의미: latest(PAID) 존재 시 PAID 반환 / 없고 유효 PENDING 존재 시 PENDING / 그 외 empty.

**근거**: 행 불변(생성 후 상태만 전이)으로 이력 보존(ARCHIVE)·멱등 단순화.

**영향 범위**: `PaymentService.initiate`·`Payment.create`. PAID 유일성은 PAY-3a(D-31)가 강제.

---

### D-29. 이벤트 발행 시점·방식 (Track 3) [ACTIVE]

**상태**: [확정 2026-06-26]

**배경**: 결제 완료→주문 반영을 동기/비동기 중 무엇으로 연결할지·발행 순서를 확정한다.

**결정**:
1. Spring `ApplicationEventPublisher` 동기 발행. `PaymentService`와 `OrderEventHandler`는 동일 트랜잭션·한쪽 실패 시 전체 롤백.
2. 발행 순서: ① `payment.complete(...)` 도메인 메서드(상태 전이·이벤트 내부 누적) ② `payment.pullDomainEvents()` ③ `paymentRepository.save(payment)` ④ `events.forEach(publishEvent)`.
3. `@TransactionalEventListener(AFTER_COMMIT)` 미사용. Outbox 미도입(현 단계 과설계·향후 IntegrationEvent 전환 예정 주석만).

**근거**: 동기·동일 트랜잭션이 결제↔주문 정합을 단순·강하게 보장. Outbox는 외부 메시징 도입 시점에 검토.

**영향 범위**: `PaymentService.handleCallback`·`OrderEventHandler`. 이벤트 누적은 `Payment` 엔티티 `@Transient` 목록.

---

### D-30. 이벤트 페이로드 사실 통지 원칙 (Track 3) [ACTIVE]

**상태**: [확정 2026-06-26]

**배경**: 이벤트에 도메인 상태(items[] 등)를 복제하면 소비측이 stale 데이터를 신뢰하게 된다.

**결정**:
1. `PaymentCompleted`: `paymentId·orderId·amount·pgTransactionId·occurredAt`(camelCase·`occurredAt` 포함).
2. `PaymentFailed`: `paymentId·orderId·failureCode·occurredAt`. items[] 제거.
3. 소비측은 `orderId`로 OrderItem을 재조회한다(도메인 상태 복제 방지).

**근거**: 사실 통지(식별자·시각)만 전달, 소비 시점 최신 상태 재조회로 정합 보장. SoT(domain-events.md)와 동기화.

**영향 범위**: `payment.event` record 2종. Inventory 예약 해제 핸들러(향후)는 `orderId` 기반 조회.

---

### D-31. PAY-3 멱등성 분리·3계층 강제 (Track 3) [ACTIVE]

**상태**: [확정 2026-06-26]

**배경**: 기존 PAY-3(단일)이 "주문당 PAID 유일"과 "PG 거래 멱등" 두 관심사를 뭉쳐 강제 수단이 모호했다.

**결정**:
1. **PAY-3a**(Order × PAID 유일성): Service 사전 가드(`existsByOrderIdAndStatus(orderId, PAID)`)로 강제. MariaDB partial unique index 미지원으로 Service 단독 강제. `initiate` 진입·`handleCallback` PAID 전이 직전 이중 체크.
2. **PAY-3b**((pg_provider, pg_tid) 콜백 멱등): DB `UNIQUE uk_payment_provider_pg_tid` + `CHECK chk_payment_pg_tid_provider (pg_tid IS NULL OR pg_provider IS NOT NULL)` + Entity `canTransitionTo` 방어.
3. 멱등 키 우선순위: 1차 `payment_attempt_key`(확실 매핑·D-35), 2차 `(pg_provider, pg_tid)` UNIQUE(중복 INSERT/UPDATE 차단).
4. Entity 가드: `PaymentStatus.canTransitionTo` — PAID→PAID·종결→임의 모두 false.

**근거**: 관심사 분리로 각 불변식의 강제 계층(Service/DB/Entity)을 명시. partial index 부재를 Service 가드로 보강.

**영향 범위**: V3 마이그레이션 제약 3건·`PaymentService` 가드·`PaymentStatus`·invariants.md §2.11.

---

### D-32. FAILED 결제 정책·expires_at 도입 (Track 3) [ACTIVE]

**상태**: [확정 2026-06-26]

**배경**: 미완료 PENDING이 영구 잔존하면 재시도가 영원히 차단된다. 만료 개념이 필요하다.

**결정**:
1. `expires_at DATETIME(6) NULL` 추가. `initiate` 시 `now + 30분`.
2. 재시도 시 PENDING 존재 + `now < expires_at` → `PaymentInProgressException` 차단. `now >= expires_at`(만료) → 새 시도 허용(만료 PENDING은 상태 변경 없이 무시).
3. 만료 PENDING에 SUCCESS 콜백 도착: PAY-3a 위반(이미 PAID) → REJECT·운영 알림 / PAY-3a 통과 → PAID 전이(PG가 받아들였으면 결제 성립).
4. 만료는 (Track 25 이전) 차단 해제 신호였으며, D-109에서 자동 배치 만료 전이(PENDING→FAILED·failure_code=PAYMENT_EXPIRED)로 재정의(state-machine.md §1). `ExpirePaymentScheduler`(@Scheduled 5분)가 만료 PENDING을 `ExpirePaymentService.expireOne`으로 FAILED 전이시킨다.
5. FAILED 행은 영구 보관(ARCHIVE). 결제 화면=PAID만 / 운영 화면=전체.

**근거**: 만료를 "차단 해제 신호"로만 한정해 상태 머신을 단순하게 유지. 결제 성립은 PG 수락 사실을 우선.

**영향 범위**: V3 `expires_at`·`failure_code`·`PaymentService.initiate`·`Payment.isExpired`. (Track 25 D-109 추가) V8 `idx_payment_expire`·`PaymentRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc`·`ExpirePaymentService.expireOne`·`ExpirePaymentScheduler`·`SchedulingConfig`.

---

### D-33. Lazy 로딩 안전망 (Track 3) [ACTIVE]

**상태**: [확정 2026-06-26]

**배경**: `markPaid`가 OrderItem을 순회하는데, 이벤트 핸들러 트랜잭션에서 items가 미로딩이면 `LazyInitializationException` 위험.

**결정**:
1. `OrderRepository.findByIdWithItems`(LEFT JOIN FETCH o.items) 추가.
2. `OrderEventHandler.onPaymentCompleted`는 markPaid 호출 전 fetch join으로 items 선로딩.
3. `OrderService.markPaid`는 기존 `findById` 유지(다른 호출자 영향 0). 동일 트랜잭션 1차 캐시 적중으로 안전.

**근거**: 소비 경로에서만 fetch join을 선행해 핸들러를 안전하게, 기존 Service 시그니처는 불변.

**영향 범위**: `OrderRepository`(메서드 1 추가)·`payment.handler.OrderEventHandler`.

---

### D-34. 콜백 타입 매트릭스·응답 코드 정책 (Track 3) [ACTIVE]

**상태**: [확정 2026-06-26]

**배경**: 콜백 타입 × 현재 상태 조합별 처리·HTTP 응답을 표준화해야 멱등·거부가 일관된다.

**결정**:
1. `CallbackType` enum: SUCCESS·FAILURE·CANCEL.
2. 매트릭스(12 조합):
   - SUCCESS×PENDING→PAID+PaymentCompleted(200) / SUCCESS×PAID→NO-OP(200) / SUCCESS×FAILED·CANCELLED→REJECT(422)
   - FAILURE×PENDING→FAILED+PaymentFailed(200) / FAILURE×PAID·FAILED·CANCELLED→NO-OP(200)
   - CANCEL×PAID→CANCELLED(200·Track 5 환불 진입) / CANCEL×PENDING→FAILED(200·미완료 취소=실패) / CANCEL×FAILED·CANCELLED→NO-OP(200)
3. Service 시그니처: `handleCallback(PaymentCallbackCommand)` (provider·callbackType·paymentAttemptKey·pgTid·occurredAt·metadata).
4. REJECT·행 미발견·PAY-3a anomaly는 `InvalidCallbackException`으로 통일 → Controller가 HTTP 422. 형식 오류는 400(Bean Validation).

**근거**: 매트릭스 명문화로 멱등(NO-OP)·거부(422)·정상(200)을 결정적으로 분기. `failure_code`는 `PaymentFailed` 발행 시 기록.

**영향 범위**: `PaymentService.handleCallback`·`PaymentWebhookController`·state-machine.md §1.

---

### D-35. payment_attempt_key 도입 (Track 3) [ACTIVE]

**상태**: [확정 2026-06-26]

**배경**: 콜백 회신 시 결제 행을 확실히 매핑할 1차 키가 필요하다. `public_id`(pay_)는 외부 노출 식별자라 별도 시도 키가 적절.

**결정**:
1. 컬럼 `payment_attempt_key CHAR(30) NOT NULL UNIQUE`. 형식 `pat_` + ULID 26자.
2. 발급 시점: `PaymentService.initiate`(Service 계층·정적 `PublicIdGenerator.generate("pat")`로 발급해 `Payment.create`에 주입).
3. 용도: PG에 metadata 전달·콜백 회신 매핑 1차 키.
4. `PublicIdGenerator` prefix 13종으로 확장(기존 12 + `pat`). `pat`는 Payment 행의 `pay`와 별개 키임을 주석 명시.

**근거**: 외부 노출 식별자와 콜백 매핑 키의 책임 분리. CHAR(30)·`@JdbcTypeCode(SqlTypes.CHAR)`로 D-26 정합.

**영향 범위**: V3 `payment_attempt_key`·`uk_payment_attempt_key`·`Payment`·`PublicIdGenerator`(javadoc).

---

### D-36. Payment Domain Service 미도입 (Track 3) [ACTIVE]

**상태**: [확정 2026-06-26]

**배경**: Track B는 Order.status 파생을 `OrderStatusResolver`(Domain Service)로 분리했다. Payment도 같은 패턴이 필요한지 판단한다.

**결정**:
1. Payment는 단일 행 상태 전이만 — 집계 로직 없음. 별도 Domain Service 클래스 불필요.
2. `PaymentService`(Application) 단일·`PaymentStatus.canTransitionTo`가 도메인 전이 규칙 담당.
3. Track B `OrderStatusResolver` 같은 Domain Service 없음(의도적 차이).

**근거**: Order.status는 OrderItem 집합의 파생이라 Resolver가 필요했으나, Payment.status는 자기 행의 직접 전이라 enum 매트릭스로 충분(과설계 회피).

---

### D-37. testcontainers 1.21.4 채택 (Track 3) [ACTIVE]

**상태**: [확정 2026-06-27]

**배경**: Docker Desktop 4.73.1 (Engine 29.4.3) 환경에서 testcontainers 1.20.4 기본 포함 docker-java 3.4.0이 Windows Named Pipe(`npipe`) 경유 `/info` API 호출 시 HTTP 400 + 빈 JSON 응답을 반환해 14개 `@DataJpaTest` 전원 실패.

**결정**:
1. `extra["testcontainers.version"] = "1.21.4"` — Spring Boot dependency-management override 패턴 적용.
2. testcontainers 1.21.4 → docker-java 3.4.2 → Docker Desktop 4.73.1 `/info` 400 응답 처리 수정 확인.
3. testcontainers 14건 전원 PASS (94/95·contextLoads 1건은 별도 원인).

**근거**: docker-java 3.4.2가 Windows Named Pipe HTTP 400 응답을 정상 처리하도록 수정됨. 다운그레이드(옵션 A) 또는 WSL2(옵션 B) 없이 라이브러리 업그레이드만으로 해결.

**영향 범위**: `backend/build.gradle.kts` `extra["testcontainers.version"]`·`docs/handover/track-3-testcontainers-quirk.md`.

---

### D-38. Spring Boot 의존성 override 패턴 — extra["xxx.version"] (전역) [ACTIVE]

**상태**: [확정 2026-06-27]

**배경**: `testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))` BOM 선언만으로 Spring Boot 3.4.1 dependency-management가 pin한 testcontainers 버전을 override하지 못했다. 동일 버전(1.20.4)이 두 경로로 충돌 없이 그대로 적용됨.

**결정**:
- Spring Boot dependency-management plugin이 관리하는 의존성을 override할 때는 `extra["라이브러리.version"] = "X.Y.Z"` 패턴 사용 (BOM `platform()` 단독 선언은 override 불가).
- Spring Boot가 인식하는 property key는 spring-boot-dependencies BOM `<properties>` 블록 참조.

**근거**: `io.spring.dependency-management` plugin은 `extra["xxx.version"]`로 주입된 project property를 BOM property로 취급해 버전을 교체한다. `platform()` BOM은 같은 라이브러리 선언이 이미 존재하면 병합 규칙에 따라 기존 우선이 될 수 있음.

**영향 범위**: 전역 — `build.gradle.kts` 패턴·신규 라이브러리 override 시 동일 패턴 적용.

---

### D-39. 인증 진입 시점 — 임시 X-Buyer-Id 주입 (Track 4) [ACTIVE]

**상태**: [확정 2026-06-27]

**배경**: Track 4 = Order Aggregate(Track 2)·Payment Mock(Track 3) 위 첫 buyer-facing HTTP 진입 계층 신설. `OrderController`·`createOrder()` 프로덕션 호출자·Spring Security·전역 예외 핸들러 모두 부재. `AuditorAwareImpl.getCurrentAuditor()`는 Q1=B 결정으로 항상 `empty` 반환. `buyer_id` 출처 정책이 트랙 규모를 가르는 핵심 결정.

**결정**:
1. Track 4 범위 내 Spring Security 도입하지 않는다. `buyer_id`는 요청 헤더 `X-Buyer-Id` (정수 buyer_id) 임시 주입 패턴으로 컨트롤러 진입 시점에 주입.
2. `AuditorAwareImpl` 보강 — ThreadLocal 또는 RequestContextHolder 경유로 헤더 값 읽어 `created_by`·`updated_by`에 반영.
3. 임시 주입 메커니즘은 `HandlerMethodArgumentResolver` 또는 `@RequestHeader` 단순 패턴 중 구현 단계에서 결정.
4. 미주입 요청(`X-Buyer-Id` 없음) → 400 Bad Request (전역 예외 핸들러와 함께 확정).

**옵션 비교**:
| 옵션 | 채택 | 사유 |
|---|---|---|
| α. Spring Security 본 트랙 도입 | ✗ | 책임 확장·트랙 규모 분할 필요 |
| **β. 임시 주입 + Security 후속 트랙** | **✓** | 진입 계층 책임 집중·AuditorAwareImpl 자연 연동 |
| γ. 하드코딩 buyer_id 1 + TODO | ✗ | 배포 불가·운영 누락 위험 |

**근거**: Track 4 본질은 "HTTP 진입 계층 신설"이지 "인증 시스템 구축"이 아님. Spring Security 정식 도입은 Seller·Admin 인증과 묶어 후속 트랙(Track 4.5 또는 별도)에서 일괄 처리해야 책임 경계 명확.

**범위 외 (Track 4.5 또는 후속)**: Spring Security·JWT 발급·OAuth2/Login·UserDetailsService·Seller/Admin 인증·Refresh token·세션 관리.

**관련 결정**: Q1=B(AuditorAwareImpl empty 반환·Track 1 합의).

**영향 범위**: `AuditorAwareImpl`·`OrderController`(신규)·전역 예외 핸들러(신규).

---

## 외부 검토 의견 (CR)

### CR-01. testcontainers 업그레이드 우선 시도 (Track 3) [채택]

**출처**: 외부 검토 (ChatGPT) — 2026-06-27

**의견 요약**:
- Docker Desktop 다운그레이드(옵션 A) 전에 testcontainers 최신 버전 업그레이드를 먼저 시도할 것.
- docker-java 3.4.x는 Windows Named Pipe 핸들링에 알려진 quirk 존재·3.4.2+에서 수정 패치 포함.
- Spring Boot 환경에서는 `extra["testcontainers.version"]` property override 패턴 사용.

**채택 여부**: 채택 — 1.21.4 적용 후 14건 전원 PASS 확인. D-37·D-38로 결정 박제.

**의의**: 환경 변경(다운그레이드·WSL2) 없이 의존성 업그레이드만으로 해결 가능했음을 외부 검토가 먼저 제시. 향후 docker-java 호환 이슈 발생 시 버전 업그레이드 우선 검토.

**영향 범위**: `payment.service` 단일 Application Service. `PaymentStatus`가 전이 규칙 보유.

---

### D-40. Track 4 컨트롤러 분류 — URL 액터 중립·Controller 액터 분리 (β′) (Track 4) [ACTIVE]

**상태**: [확정 2026-06-27]

**배경**: Track 4가 신설할 Order REST 컨트롤러의 분류·URL 구조 결정. 권한 매트릭스상 주문 생성·실행은 구매자 전용·자사 OrderItem 조회는 Seller RLS. D-39 임시 X-Buyer-Id 주입과 향후 Seller 인증(미정·JWT 가능성)이 이질적·동일 컨트롤러에서 두 인증 경로 분기 시 응집도 저하.

**결정**:
1. **URL은 액터 중립** — 리소스 중심 URL 유지(`/api/orders`·`/api/order-items`). `/api/buyer/...`·`/api/seller/...` prefix 금지.
2. **Controller는 액터별 분리** — `BuyerOrderController` (Track 4)·`SellerOrderController` (#7 결정에 따라 본 또는 후속 트랙)·`AdminOrderController` (후속).
3. **Security·DTO·Application Service도 액터 분리** — `@PreAuthorize` 컨트롤러 클래스 단위·DTO 액터별 분리(D-41)·향후 BuyerOrderQueryService·SellerOrderQueryService 자연 분리.
4. **패키지 구조**:
   ```
   order/controller/
    ├─ BuyerOrderController
    └─ (후속) SellerOrderController·AdminOrderController
   ```
5. URL prefix 도입(γ)은 Seller API 공개 로드맵·외부 SDK 공개 시점에 재평가. 현 단계 선반영 금지.

**옵션 비교**:
| 옵션 | 채택 | 사유 |
|---|---|---|
| α. 단일 컨트롤러 | ✗ | 인증 분기·책임 응집 저하 |
| β. URL 도메인 통일·Controller 분리 (원안) | ✗ | "URL 도메인 통일" 표현 모호 |
| **β′. URL 액터 중립·Controller·Security·DTO 액터 분리** | **✓** | 액터는 애플리케이션 경계지 도메인 경계 아님 (Order Aggregate는 액터 중립)·확장 자리 선점 회피 |
| γ. URL prefix 분리 (`/api/buyer/...`·`/api/seller/...`) | ✗ | Seller 1년 내 계획 부재·prefix 선점 무의미 |

**근거**: Order Aggregate는 buyer/seller 구분 없는 단일 도메인. 변하는 건 인증·조회 범위(RLS)·응답 DTO·권한 — 모두 애플리케이션 계층 경계. URL이 아닌 Controller + Application Service 조합에서 분리하는 것이 DDD 정합·운영 원칙 "확장 가능성보다 운영 가능성 우선" 정합. Spring Security 정식 도입 시 컨트롤러 클래스 단위 매처(`.requestMatchers(BuyerOrderController.class)`)도 prefix 매처와 동등 단순. Springdoc `GroupedOpenApi`로 컨트롤러 단위 OpenAPI 그룹화 가능 → γ의 OpenAPI 우위 약함.

**Alternative**:
- α 단일 컨트롤러: 메서드별 인증 분기 산재·응집도 저하 (기각).
- β 원안 "URL 도메인 통일": 같은 URL에 buyer/seller 의미 분기 발생 가능·표현 모호 (β′로 명확화).
- γ prefix 분리: Seller API 공개 시점에 마이그레이션(URL 변경 + nginx + Nuxt base URL 상수) 1 커밋 분량 — 현 단계 선반영 무가치 (기각).

**영향 범위**: `order/controller/BuyerOrderController` (신규)·`@PreAuthorize` 적용 위치(Security 정식 도입 시점)·후속 Seller·Admin 컨트롤러 패키지 구조 사전 박제.

**관련 결정**: D-39 (임시 X-Buyer-Id)·D-41 (DTO)·D-42 (조회 범위).

---

### D-41. Track 4 DTO 분리 전략 — request/response 하위 분리·DTO 자기 변환 (γ 절충) (Track 4) [ACTIVE]

**상태**: [확정 2026-06-27]

**배경**: Track 4 Order API 진입 시 Web DTO 6~7개 신설 예상(CreateOrderRequest·OrderItemRequest·ShippingAddressRequest·OrderResponse·OrderItemResponse·OrderSummaryResponse·ShippingAddressResponse). Track 3 Payment 패턴은 `controller/` 안에 컨트롤러·Request DTO 공존·DTO 인스턴스 메서드 `toCommand()` 보유. Track 4 규모에서 패키지 구조·매핑 책임 위치를 결정.

**결정**:
1. **하위 패키지 분리** — `controller/request/`·`controller/response/`. `mapper/` 패키지는 도입하지 않는다.
2. **DTO 자기 변환 패턴** — `request.toCommand()` 인스턴스 메서드·`Response.from(...)` 정적 팩토리 메서드.
3. **Response 입력 범위 제한** — `Response.from(Order order)` 전체 Aggregate 직접 수신 금지. fetch join으로 로딩된 Order를 받되 Response가 의존하는 필드 범위를 시그니처에 명시 (`OrderResponse.fromOrderWithItems(Order order)` 또는 projection record 도입). Order에 향후 추가될 필드(예: payments·delivery)와의 결합 회피.
4. **Validation 계층 분리 원칙** (D-41 박제·상세는 #11 별도 D-XX):
   - **DTO 계층** — Bean Validation (`@NotNull`·`@Min`·`@Size` 등) — HTTP 400.
   - **Command 계층** — 도메인 진입 직전 불변식 검증.
   - **Domain 계층** — 비즈니스 규칙 (ORD-1·ORD-5 등) — HTTP 422.
   상세 매핑 정책·실패 시 HTTP 상태 분기는 **#11 결정 (별도 D-XX)** 박제 대상.
5. **Track 3 일관성 처리** — `payment/controller/PaymentCallbackRequest`를 `payment/controller/request/`로 이관(별도 작은 PR·1 파일 이동·Track 4 진입 전 또는 동반).

**패키지 구조**:
```
order/controller/
 ├─ BuyerOrderController
 ├─ request/
 │   ├─ CreateOrderRequest          (toCommand())
 │   ├─ OrderItemRequest
 │   └─ ShippingAddressRequest
 └─ response/
     ├─ OrderResponse               (fromOrderWithItems(Order))
     ├─ OrderItemResponse
     ├─ OrderSummaryResponse
     └─ ShippingAddressResponse
```

**옵션 비교**:
| 옵션 | 채택 | 사유 |
|---|---|---|
| α. Track 3 패턴 그대로 (`controller/` 공존) | ✗ | DTO 7개 단계에서 가독성 저하 |
| β. mapper/ 별도 패키지 + Mapper 클래스 | ✗ | 매핑 단순도 대비 잉여 추상화·디버깅 흐름 길어짐 |
| **γ. 하위 분리 (request·response) + DTO 자기 메서드** | **✓** | Track 3 패턴 자연 확장·파일 수 통제 |
| δ. MapStruct 도입 | ✗ | 단독 개발자·DTO 7개 규모에 과조기 |

**근거**: Order DTO ↔ Command/Domain 매핑은 필드 1:1 + 중첩 변환 수준. 별도 Mapper 클래스는 매핑 로직 복잡도가 높을 때 가치 발생·현 단계 잉여. `request.toCommand()`·`Response.from()`은 Track 3에서 검증된 패턴·DTO가 자기 변환 책임 보유. Response 입력 범위 제한으로 Aggregate 내부 구조 변경에 대한 Response 결합도 차단.

**Alternative**:
- α: PaymentCallbackRequest 위치 유지 가능하나 Order 6~7 DTO 단계에서 `controller/` 단일 패키지는 파일 폭증 (기각).
- β: Mapper 별도 클래스는 매핑 규칙 다수·재사용 빈번 시 정당. Order 단순 매핑엔 과한 추상화 (기각).
- δ: MapStruct는 매핑 대상 폭증·팀 규모 시 유리. 단독 개발자·DTO 7개엔 어노테이션 처리기 학습·Gradle 설정 비용 대비 효익 미미 (기각).

**영향 범위**: `order/controller/request/`·`order/controller/response/` 신규·`payment/controller/PaymentCallbackRequest` 이관(작은 별도 PR)·Validation 위치 원칙은 #11 결정에서 상세화.

**관련 결정**: D-40 (컨트롤러 분류)·D-42 (조회 범위)·#11 Validation 계층(후속 박제).

---

### D-42. Track 4 조회 API 범위 — Buyer 생성·단건·목록 한정 (β) (Track 4) [ACTIVE]

**상태**: [확정 2026-06-27]

**배경**: Track 4가 신설할 조회 엔드포인트 범위. Seller 자사 RLS 조회·Admin 전체 조회·검색·통계·필터 포함 여부에 따라 트랙 규모 폭증 가능. Seller 조회 포함 시 Seller 인증 출처·RLS 강제 메커니즘·Read Model 결정 3건 동반 → 트랙 규모 가르는 핵심 결정.

**결정**:
1. **Track 4 진입 엔드포인트 (Buyer 한정·3개)**:
   ```
   POST   /api/orders                       (주문 생성)
   GET    /api/orders/{orderPublicId}       (Buyer 본인 단건)
   GET    /api/orders                       (Buyer 본인 목록·페이징)
   ```
2. **목록 페이징 정책**:
   - 노출 파라미터: `page`·`size` (기본 `size=20`·최대 `size=100`).
   - **정렬(`sort`)은 본 트랙 미노출** — 서버 고정 `ORDER BY ordered_at DESC`. 향후 정렬 확장 요구 발생 시점에 별도 결정으로 노출.
   - 응답: `Page<OrderSummaryResponse>` (Spring Data Page 구조).
3. **단건 조회 키는 `public_id` 고정** — 내부 BIGINT id 금지. 모든 외부 노출 경로(URL·이벤트 payload)는 public_id (`ord_` prefix·CHAR(30)) 일관.
4. **본인 조회 강제** — 컨트롤러 진입 시 D-39 `X-Buyer-Id` 헤더와 조회 대상 Order의 `buyer_id` 일치 검증. 불일치 시 404 (정보 노출 회피·403 미사용).
5. **이연 항목 (후속 트랙)**:
   - Seller 자사 OrderItem 조회 (Seller 인증·RLS·Read Model 결정 동반).
   - Admin 전체 조회 (권한 매처·RLS 면제).
   - 검색·필터 (`status`·기간·키워드·인덱스 신설 필요).
   - 통계·집계 (`vw_order_admin`·`vw_seller_dashboard` Read Model — PR-03 이연 항목).

**옵션 비교**:
| 옵션 | 채택 | 사유 |
|---|---|---|
| α. 최소 (생성 + 단건) | ✗ | Buyer UX 미완결 (목록 부재) |
| **β. Buyer 한정 기본 조회** | **✓** | Buyer 쇼핑몰 UX 완결·Read Model 조기 압박 회피 |
| γ. Buyer + Seller RLS | ✗ | Seller 인증·RLS·Read Model 결정 3건 동반·트랙 규모 폭증 |
| δ. 풀스코프 (admin·검색·통계 포함) | ✗ | D-25 DDL 잠금·PR-03 Read Model 이연과 충돌 |

**근거**: Track 4 본질은 RECON §8 결론 "Order Aggregate 위 첫 buyer-facing HTTP 진입 계층 신설". β가 Buyer 주문 플로우(상품→주문→결제→조회) 끊김 없는 완결·목록 조회도 단순 쿼리(`WHERE buyer_id=? ORDER BY ordered_at DESC LIMIT ...`) 수준이라 Read Model 불필요·Order Aggregate 직접 조회로 충분. 정렬 미노출은 향후 `status`·`price`·`created_at` 확장 압박 회피 (operate first 원칙). public_id 키 고정은 OrderPlaced 이벤트(publicId 발행)·db-schema §1.1 ULID prefix 정책 정합.

**Alternative**:
- α: 단건만 제공 시 Buyer 본인 주문 이력 조회 불가·실 쇼핑몰 UX 미달 (기각).
- γ: Seller 인증 출처(`X-Seller-Id` 임시 vs JWT)·RLS 강제 메커니즘(`@Filter`·AOP·Repository base)·Seller Read Model 후보(`vw_seller_dashboard`) 결정 동반 → Track 4 S-tier 1트랙이 사실상 2트랙 합치는 효과·검증 부담 폭증 (기각·후속 트랙으로 이연).
- δ: admin·검색·필터 인덱스(`ix_order_buyer_status_ordered`·`ix_order_item_seller_status`) 신설은 DDL V3 마이그레이션·D-25 Gate 후 DDL 잠금과 직접 충돌 (기각·운영 단계 요구 발생 시점 결정).

**영향 범위**: `BuyerOrderController` (POST·GET 단건·GET 목록)·`OrderRepository`(목록 쿼리·페이징 메서드 추가)·`OrderResponse`·`OrderSummaryResponse`·404 응답 매핑 (#6 전역 예외와 함께).

**관련 결정**: D-39 (X-Buyer-Id 인증)·D-40 (컨트롤러 분류)·D-41 (DTO 분리)·E1 OrderPlaced (publicId 키 정합)·PR-03 Read Model 이연.

---

### CR-02. Track 4 외부 검토 1차·2차 통합 (β′·DTO·MDC·OpenAPI·#9 미버전·sort 보류·publicId 키) [채택]

**출처**: 외부 검토 (ChatGPT) — 2026-06-27

**의견 요약**:
- β′ 본질 (URL 액터 중립·Controller·Security·DTO 액터 분리) 권고.
- DTO 패키지 `controller/request·response` 분리·`toCommand()`·`from()` 패턴.
- MDC 태깅 (`actor`·`aggregate`·`operation`) Track 4 동반 권고.
- Springdoc `GroupedOpenApi`로 컨트롤러 단위 그룹 가능 → γ OpenAPI 우위 약함.
- #9 API 버저닝은 v1 미도입 권고 (`PaymentWebhookController` 일관).
- `Response.from(Order)` 입력 축소 (Aggregate 결합도 회피).
- Validation 계층 분리 원칙 (DTO=Bean Validation·Command=불변식·Domain=비즈니스 규칙).
- `GET /api/orders` 정렬 공개 보류·서버 고정 `ORDER BY ordered_at DESC`.
- 단건 조회 `{orderPublicId}` 명시·내부 id 금지.

**채택 여부**:
- D-40 β′·D-41 γ + Response 입력 축소·D-42 β + sort 보류·publicId 키 — **전건 흡수**.
- MDC 태깅·#9 미버전·Validation 상세는 #6·#9·#11 별도 결정에서 박제 (Track 4 진행 중 순차).

**영향 범위**: D-40·D-41·D-42 본문·후속 #6·#9·#11 결정 박제 예정.

---

### D-43. Track 4 결제 시작 결합 방식 — 단일 체크아웃 + 재결제 분리 (γ′) (Track 4) [ACTIVE]

**상태**: [확정 2026-06-27]

**배경**: Track 4 `POST /api/orders` 진입 시 Payment initiate를 어떻게 결합할지 결정. OrderPlaced 이벤트 핸들러는 Track 7 이연 상태 → Track 4에서는 `PaymentService.initiate` 직접 호출 필요. 결합 방식이 신규 주문 UX·재결제 시나리오·Order 1:N Payment 모델 활용도를 동시에 결정.

**결정**:

1. **엔드포인트 2개 분리**:
   - `POST /api/orders` — 신규 주문 + 첫 결제 시작 (1콜 완결)
   - `POST /api/orders/{publicId}/payments` — 재결제 전용 (신규 Payment row 추가)

2. **트랜잭션 경계** — `@Transactional`로 Order 생성과 Payment initiate를 한 TX로 묶지 않는다. 각 Service가 자체 `@Transactional` 보유 (D-28 정합). 호출 순서: Controller → OrderService.createOrder() (TX1 COMMIT) → PaymentService.initiate() (TX2 COMMIT).

3. **부분 실패 정책** — Order 생성 성공 + Payment initiate 실패 시:
   - Order 롤백 **금지** — `PENDING_PAYMENT` 상태 유지
   - Payment row **미저장** — `initiate` 자체 실패는 redirect 발급 실패·결제 시도가 시작되지 않은 상태이므로 Payment 의미상 row 부재가 자연
   - 운영 로그 5필드 보존: `orderPublicId`·`attemptKey`·`buyerId`·`failureCode`·`occurredAt`

4. **PENDING_PAYMENT 의미 재정의** — "결제 없음 (INITIATE_FAILED) 또는 결제 진행 중 (Payment.PENDING)"으로 정의. 신규 OrderStatus 추가 없음. 기존 enum 의미 확장으로 처리.

5. **재결제 허용 조건** (Order 단위 판정):
   - 활성 Payment 정의: Payment.status가 `PENDING` 또는 `PAID`인 row
   - 활성 Payment 존재 → 409 Conflict
   - FAILED Payment만 존재 → 신규 Payment 생성 허용
   - Payment 부재 (INITIATE_FAILED 직후) → 신규 Payment 생성 허용
   - 미래 EXPIRED·TIMEOUT 등 상태 추가 시 "활성 Payment" 정의만 갱신

6. **권한 검증** — 재결제 엔드포인트 서비스 시그니처 `initiatePayment(orderPublicId, buyerId)` 강제. 컨트롤러 진입 시 D-39 `X-Buyer-Id` 헤더와 조회 대상 Order의 `buyer_id` 일치 검증.
   - 존재 안 함 → 404
   - 타인 주문 → 404 (정보 노출 회피)
   - 활성 Payment 존재 → 409

7. **attempt_key 정책** — retry 요청은 항상 신규 `attempt_key` 발급. attempt_key 재사용 금지. 생성 위치는 PaymentService 내부 (컨트롤러 생성 금지). D-28·D-35 정합.

8. **응답 DTO 단일 공용** — `CheckoutResponse` 정적 팩토리 `forNewOrder(...)`·`forRetry(...)` 보유. 신규/재결제 동일 구조. 의미 중심은 payment (재결제는 Order 변경 아님).

9. **응답 구조 (하이브리드)**:
   - `payment.{publicId, status, redirectUrl, expiresAt}` — Payment 직접 속성 (PG 발급)
   - `next.{retryPaymentUrl}` — 클라이언트 다음 행동 안내 (현 단계 단일 키·확장 자리 확보 목적)
   - 성공 응답: payment.redirectUrl 존재 + next.retryPaymentUrl 생략
   - 실패 응답: payment.status=INITIATE_FAILED (publicId 부재로 row 미저장 표현) + next.retryPaymentUrl 제공
   - `failureCode`는 응답 미노출·운영 로그에만 보존

10. **HTTP 상태 매핑**:
    - 신규 주문 성공 (Payment 성공) → 201 Created
    - 신규 주문 성공 + Payment 실패 → 201 Created (Order는 정상 생성)
    - 재결제 성공 → 201 Created
    - 활성 Payment 존재 (PAID·PENDING) → 409 Conflict
    - 타인 주문·존재 안 함 → 404 Not Found

11. **컨트롤러 책임 경계** (BuyerOrderController 적용):
    - 금지: Repository 직접 접근·트랜잭션 직접 제어·Payment 생성 규칙 포함
    - 허용: Service 조합·응답 조립·HTTP 변환

12. **CheckoutApplicationService 승격 조건** — 현 단계 미도입. 다음 조건 중 하나 충족 시 승격 재평가:
    - 호출 지점 2개 이상
    - 주문+결제 외 정책 추가 (쿠폰·포인트·배송비·프로모션)
    - 재결제와 신규 주문 로직 30% 이상 공유
    - 외부 PG 2종 이상 도입

13. **범위 외 (후속 결정)**:
    - PENDING 영구 정체 케이스 (webhook 미도달)는 운영 강제 전이로 해소. 자동 타임아웃 정책은 후속 결정 영역
    - 결제 만료 자동 처리·결제 수단 변경 UX·결제 분리(부분 결제)는 본 결정 범위 외

**옵션 비교**:

| 옵션 | 채택 | 사유 |
|---|---|---|
| α. 단일 체크아웃 (재결제도 동일 엔드포인트) | ✗ | 재결제 시 Order 재생성 강제·Order 1:N Payment 모델 위배·주문 중복 발생 |
| β. 2단계 완전 분리 (신규도 2콜) | ✗ | 신규 주문 99% 경로 2콜 강제·Buyer UX 저하·Nuxt 상태 관리 복잡도 증가 |
| **γ′. 단일 체크아웃 + 재결제 분리 (하이브리드)** | **✓** | 신규 1콜 완결·재결제 자연 경로·Order 1:N Payment 자연 활용·D-28 트랜잭션 경계 정합 |
| δ. POST /api/checkouts 별도 리소스 신설 | ✗ | Track 4 단계에 별도 리소스 계층 추가 과함·CheckoutApplicationService 승격 조건 도달 시 재평가 |

**근거**: γ′은 신규 주문(99% 경로)에서 단일 엔드포인트 호출로 UX 손실 없이 완결되며, 재결제는 Order 1:N Payment 모델을 그대로 반영해 신규 Payment row 추가로 처리. D-28 별도 트랜잭션 원칙은 엔드포인트 결합 여부와 무관하게 트랜잭션 경계 분리만 보장하면 충족. 부분 실패 시 Order 유지·Payment 미저장 정책은 "Payment = 실제 결제 시도(redirect 발급 후)" 의미 보존·PaymentAttempt 분리 압박 회피. 응답 구조 하이브리드(payment 직접 속성 + next 행동 안내)는 PG 발급 속성과 클라이언트 다음 행동을 의미 단위로 분리. CheckoutApplicationService 미도입 + 승격 조건 박제는 "operate first → verify through repetition → promote to documentation" 원칙 정확 준수.

**Alternative**:
- α: 재결제 시 Order 재생성 강제는 멀티벤더 OrderItem·재고 점유·Order 누적 오염·운영자 설명 부담 (기각).
- β: 아키텍처 정석이나 Track 4 본질("첫 buyer-facing 진입") 단계에서 신규 주문 2콜 강제는 프론트 상태 관리·실패 복구 부담 과중 (기각).
- δ: Checkout 별도 리소스는 orchestration 로직이 단순 위임 단계인 현재 잉여 추상화·CheckoutApplicationService 승격 조건 도달 시 자연 재평가 (기각·승격 조건만 박제).

**영향 범위**: `BuyerOrderController` (신규·POST /api/orders + POST /api/orders/{publicId}/payments)·`OrderService.createOrder` (신규)·`PaymentService.initiate(orderPublicId, buyerId)` 시그니처 (재결제 호출 경로 신설)·`CheckoutResponse` 공용 DTO 신규·운영 로그 5필드 출력 (`orderPublicId`·`attemptKey`·`buyerId`·`failureCode`·`occurredAt`).

**관련 결정**: D-28 (Payment 별도 TX·1:N)·D-34 (PENDING→FAILED webhook)·D-35 (attempt_key 서버 생성·pat_ prefix)·D-39 (X-Buyer-Id)·D-40 (컨트롤러 분류)·D-41 (DTO·CheckoutResponse)·D-42 (조회 범위·404 정책).

---

## D-44 주문 생성 멱등성 — 클라이언트 전달 `Idempotency-Key` 헤더 [ACTIVE]

### 결정
`POST /api/v1/orders` 멱등성은 클라이언트 전달 `Idempotency-Key` HTTP 헤더 방식.

### 사양
- 헤더명: `Idempotency-Key`
- 값 형식: ULID 또는 UUID v4 (서버 형식 검증·생성 주체는 클라이언트)
- 최대 길이: 128자 (상한 명시)
- 스코프: `(buyer_id, idempotency_key)` 유일
- 유효 기간: 24시간 (조회 보장 윈도우)
- 헤더 미전달: 허용 — 매 요청 신규 주문 생성 (graceful degradation)
- 동일 키 재요청: 최초 응답 그대로 반환
- 동일 키 진행 중 동시 요청: 409 + `IDEMPOTENCY_KEY_IN_PROGRESS`

### 근거
1. D-43 단일 `POST /api/v1/orders` 시그니처 유지
2. Payment `attempt_key`(D-35)는 PG 콜백 정합용 — Order 멱등성은 사용자 의도 단위로 컨텍스트 분리
3. REST 표준 패턴 (Stripe·PayPal)
4. 미전달 허용으로 점진 적용 가능

### 후속 결정
- D-44a: 저장 매체
- D-44b: 응답 캐싱 형태

---

## D-44a 멱등성 키 저장 매체 — 별도 테이블 `order_idempotency_key` [ACTIVE]

### 결정
별도 테이블 `order_idempotency_key`. Redis 도입은 Track 7 (Inventory) 시점에 재평가.

### 사양
- 테이블명: `order_idempotency_key`
- 컬럼:
  - `buyer_id` BIGINT NOT NULL
  - `idempotency_key` VARCHAR(128) NOT NULL
  - `order_id` BIGINT NULL (진행 중엔 NULL)
  - `status` ENUM('IN_PROGRESS', 'COMPLETED') NOT NULL
  - `response_body` LONGTEXT NULL
  - `created_at` DATETIME(6) NOT NULL
  - `completed_at` DATETIME(6) NULL
- PK: `(buyer_id, idempotency_key)` 복합
- 동시성 제어: PK UNIQUE INSERT 충돌 감지 → 409
- 보존 정책: 72시간 (조회 보장은 24시간·이후 48시간은 운영 디버깅 용도)
- 정리: 72시간 경과 row 배치 삭제 (Track 6 이후 정리 배치와 묶음 검토)
- Flyway: V4

### 근거
1. INSERT IN_PROGRESS 충돌 시 즉시 409·COMPLETED UPDATE 명확
2. 현재 Redis 미사용 상태 유지 — 단일 용도로 인프라 의존 증설 부적절
3. 24시간 직후 운영 디버깅 요청 시 데이터 손실 위험 회피 (외부 검토 CR-11 흡수)
4. Flyway V4로 자연 편입

### 후속 재평가 트리거
- **Track 7 (Inventory) 진입 시**: `quantity_reserved` 동시성 제어로 Redis 분산 락(Redisson) 도입 검토. 도입 확정 시 멱등성 저장 매체도 Redis SETNX로 마이그레이션 재평가
- 마이그레이션 방향(테이블 → Redis) 비용 낮음·역방향 대비 안전

### 외부 검토 흡수 / 기각
- 흡수: 보존 72h (CR-11)
- 기각: `response_hash` 컬럼 추가 (CR-11) — `response_body`는 디버깅·재응답 캐시이며 원본 권위(source of truth)가 아님. 무결성 검증 필요 시 Order·Payment 재조립이 권위 경로이므로 `response_hash`는 중복 책임

---

## D-44b 멱등성 응답 캐싱 형태 — 전체 응답 직렬화 저장 [ACTIVE]

### 결정
최초 응답 전체 JSON을 `response_body LONGTEXT`에 저장.

### 사양
- 저장 시점: 응답 직렬화 후·`status=COMPLETED` UPDATE와 동일 트랜잭션
- 캐싱 대상: 2xx 성공 응답만
- 캐싱 제외: 4xx·5xx 모두 미캐싱 — IN_PROGRESS row 삭제 후 동일 키 재시도 허용
- 재요청 처리: `response_body` 그대로 반환·HTTP 200 OK 고정
- 현재 상태와의 불일치: 의도된 동작 — 실제 현재 상태는 `GET /api/v1/orders/{id}` 별도 조회

### 근거
1. 멱등성 정의 충족 ("동일 요청 = 동일 응답")
2. 24h TTL·체크아웃 응답 수 KB 수준
3. 사용자 입력 보정 후 재시도 흐름 보존

### 외부 검토 흡수 / 기각
- 기각: 4xx deterministic 응답 캐싱 (CR-12) — D-44 멱등성의 목적은 **중복 생성 방지**이지 **요청 결과 재현**이 아님. 실패 응답 캐싱은 사용자 수정 흐름과 충돌하므로 Track 4에서는 미적용
- 흡수: 실패 시 IN_PROGRESS row 삭제 명확화 (FAILED 상태 추가 대비 단순)

### 후속 보정
- "4xx·5xx 모두 미캐싱 — IN_PROGRESS row 삭제" 정책은 **D-66으로 보정** (4xx 삭제·5xx 잔류). 본 결정의 "IN_PROGRESS row 삭제" 표현은 D-66 정합 기준으로 재해석.

---

## D-45 멀티벤더 OrderItem 응답 그룹화 — seller 단위 그룹화 [ACTIVE]

### 결정
주문 응답에서 OrderItem은 seller 단위로 그룹화하여 반환. 식별자는 모두 `public_id` 노출.

### 사양
```json
{
  "orderId": "ord_...",
  "status": { "code": "PAID", "label": "결제완료" },
  "sellers": [
    {
      "sellerId": "slr_...",
      "companyName": "...",
      "items": [
        {
          "orderItemId": "oit_...",
          "productId": "prd_...",
          "variantId": "var_...",
          "quantity": 1,
          "unitPrice": 10000,
          "totalPrice": 10000
        }
      ],
      "subtotal": 10000
    }
  ],
  "totalPrice": 10000
}
```
- 적용 범위: `POST /api/v1/orders`·`GET /api/v1/orders/{id}`·CheckoutResponse
- 단일 판매자 주문도 동일 구조 유지 (`sellers` 배열 길이 1·분기 금지)
- seller별 `subtotal` 필드 포함 (정산 단위 일치)
- 식별자 전체 `public_id` 사용·내부 BIGINT PK 노출 금지

### 근거
1. 멀티벤더가 핵심 도메인 특성 — 평탄화 시 프론트 groupBy 반복
2. 판매자별 카드 UI 자연 매핑
3. 정산 단위(seller_id) 일치 — 부분 클레임·부분 배송 시 응답 구조 일관성
4. db-schema-decisions.md 1.1 public_id 정책 정합

### 외부 검토 흡수
- publicId 명시·내부 PK 노출 금지

---

## D-46 status 외부 노출 방식 — `{code, label}` 객체 (UI 노출 status 한정) [ACTIVE]

### 결정
**사용자에게 표시되는 status**는 `{code, label}` 객체로 반환. **내부 기술 상태**는 string 유지.

### 사양

**`{code, label}` 객체 적용 (UI 노출 status)**

| status | 적용 |
|---|:---:|
| OrderStatus | O |
| PaymentStatus | O |
| DeliveryStatus | O |
| ClaimStatus | O |
| RefundStatus | O |

**string 유지 (내부 기술 상태)**
- `order_idempotency_key.status` (IN_PROGRESS / COMPLETED)
- 향후 도입될 재시도 큐·배치 작업 상태 등 내부 enum

**객체 구조**
- `code`: enum 값 (SCREAMING_SNAKE_CASE)
- `label`: `Code.label` 조회 결과 (운영자 편집 반영·db-schema-decisions.md 2.6)
- fallback: 라벨 조회 실패 시 `label = code`
- `label`은 null 금지 (fallback으로 항상 채움·D-49 정합)

### 근거
1. 운영자 라벨 편집 실시간 반영 (UI 노출 status에 한정)
2. 클라이언트 분기는 안정적 `code`로 처리
3. 다국어 확장 자연 (`labelEn` 등)
4. 기준 축을 "Code 테이블 관리 대상"이 아닌 **"UI 노출 의미"**로 설정 — 향후 내부 enum이 Code 테이블로 이동해도 API shape 영향 없음

### 외부 검토 흡수
- CR-13 부분 흡수 — 단, 기준 축을 저장 위치(Code table) → 외부 노출 의미(UI 노출)로 변경

---

## D-47 API 버저닝 정책 — `/api/v1` 도입 (webhook 제외) [ACTIVE]

### 결정
공개 REST API는 `/api/v1/` prefix. Webhook은 versioning 대상 제외·`/api/webhooks/` 별도 경로.

### 사양

**`/api/v1/` 적용**
- `/api/v1/orders`
- `/api/v1/products`
- `/api/v1/payments` (결제 시작 등 사용자 호출)
- 기타 모든 공개 REST API

**Webhook 별도 경로 (versioning 미적용)**
- `/api/webhooks/payments` (PG 콜백 수신)
- 기존 PaymentWebhookController 마이그레이션: `/api/payments/webhook` → `/api/webhooks/payments`

**버전 전환 정책**
- breaking change 발생 시 `/api/v2` 병행 운영 → deprecation 기간 후 v1 제거
- v2 분기 시점·기준은 별도 박제 (트랙 범위 외)

### 근거
1. breaking change는 마켓플레이스 진화상 필연 — 도입 시점이 늦을수록 마이그레이션 비용 증가
2. Webhook은 PG 공급자별 자체 버전 체계 따름 — public API versioning 대상 아님
3. 한국 SaaS·이커머스 표준 (Coupang·Naver Smart Store)

### 외부 검토 흡수
- webhook을 `/api/v1` 적용 범위에서 제외하고 `/api/webhooks/` 별도 경로로 분리

### CR-02 재평가
- CR-02 권고는 v1 미도입이었으나, 본 결정에서 도입으로 전환
- 사유: breaking change 마이그레이션 비용 선제 회피·webhook 분리로 CR-02 우려(PG 호환성) 해소

---

## D-48 전역 예외 핸들러·HTTP 상태 매핑 [ACTIVE]

### 결정
`@RestControllerAdvice` 기반 전역 예외 핸들러. 응답 본문은 RFC 7807 ProblemDetail. MDC traceId 태깅.

### 응답 구조 (ProblemDetail)
```json
{
  "type": "https://zslab-mall.duckdns.org/errors/order-not-found",
  "title": "Order Not Found",
  "status": 404,
  "detail": "주문을 찾을 수 없습니다.",
  "instance": "/api/v1/orders/ord_xxx",
  "code": "ORDER_NOT_FOUND",
  "traceId": "..."
}
```

### 예외 → HTTP 상태 매트릭스

| 카테고리 | HTTP | code 예시 |
|---|:---:|---|
| Bean Validation 실패 (`@Valid`) | 400 | `VALIDATION_FAILED` |
| 형식·파싱 오류 (JSON·타입) | 400 | `MALFORMED_REQUEST` |
| 인증 실패 | 401 | `UNAUTHENTICATED` |
| 권한 부족 | 403 | `FORBIDDEN` |
| 리소스 없음 | 404 | `ORDER_NOT_FOUND` 등 |
| 멱등성 진행 중 충돌 | 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` |
| 낙관적 락 충돌 | 409 | `OPTIMISTIC_LOCK_FAILURE` |
| 도메인 규칙 위반 | 422 | `PAYMENT_ALREADY_COMPLETED` 등 |
| Rate limit 초과 | 429 | `RATE_LIMIT_EXCEEDED` |
| 외부 응답 이상 (잘못된 응답) | 502 | `BAD_GATEWAY` |
| 서비스 이용 불가 (외부 의존 다운) | 503 | `SERVICE_UNAVAILABLE` |
| 미분류 서버 오류 | 500 | `INTERNAL_ERROR` |

### MDC
- 요청 진입 시 `traceId` (ULID) MDC 주입·응답 헤더 `X-Trace-Id` 반환
- 로그 패턴 `[%X{traceId}]` 포함
- CR-02 흡수

### 근거
1. Spring Boot 3.x 표준 패턴·ProblemDetail 내장 지원
2. 4xx/5xx 분리로 클라이언트 재시도 가능성 판단 명확
3. 도메인 위반(422)과 형식 오류(400) 분리는 D-41 원칙 상세화
4. 502/503 분리로 PG·외부 시스템 장애 원인 진단 용이

### 외부 검토 흡수
- CR-14 흡수 — 409 낙관락·429 rate limit·502/503 분리

---

## D-49 JSON 직렬화 정책 [ACTIVE]

### 결정
- **필드명**: camelCase (Spring Boot 기본)
- **null 처리**: 응답에서 null 필드 제외 (`@JsonInclude(NON_NULL)`)
- **null 제외 예외 (필수 유지 필드)**:
  - `ProblemDetail.detail` — 에러 가독성 필수
  - `subtotal`, `totalPrice` 등 금액 필드 — 계산 또는 0 fallback
  - `label` — fallback으로 code 값 채움 (D-46 정합)
- **날짜·시간**: ISO-8601 UTC (`2026-06-27T05:30:00.000Z`)
- **금액**: 정수 그대로 (KRW·원 단위·db-schema-decisions.md 1.3 정합)
- **enum**: 코드값 그대로 (SCREAMING_SNAKE_CASE)·D-46 적용 후 객체 래핑

### 근거
1. Java/Spring 진영 표준 — snake_case는 Python/Ruby 컨벤션
2. null 제외로 페이로드 축소·필드 부재 = null 시맨틱 명확
3. UTC 저장(db-schema-decisions.md 1.2)과 응답 일치·표시 변환은 클라이언트 책임
4. 필수 필드(에러 detail·금액·label)는 클라이언트 분기 단순화 위해 항상 유지

### 외부 검토 흡수
- detail·subtotal·label null 금지 예외 명시

---

## D-50 Validation 계층 매트릭스 [ACTIVE]

### 결정
형식 검증과 도메인 규칙 검증을 계층 분리. D-41 원칙 상세화. HTTP 상태는 **의미 기준**으로 분류.

### 매트릭스

| 검증 위치 | 책임 | HTTP | 도구 |
|---|---|:---:|---|
| Controller `@Valid` | 형식·필수·범위·정규식 | 400 | Jakarta Bean Validation |
| Service / Domain | 비즈니스 규칙·상태 전이·중복 | 422 | 도메인 예외 throw |
| Repository (UNIQUE 위반) | 의미 기준 분류 | 409 또는 422 | 예외 변환 |

### 409 vs 422 분류 (의미 기준)

| 상황 | HTTP | 예 |
|---|:---:|---|
| 동시성 충돌 | 409 | 멱등성 키 진행 중·낙관락 충돌 |
| 업무 제약 위반 | 422 | 중복 주문 금지·결제 완료된 주문 재결제 |

> Repository 계층에서 발생하는 UNIQUE 위반이라도 의미가 "동시성"이면 409·"업무 제약"이면 422.

### 중복 검증 허용 케이스
- 컨트롤러 `@NotNull`과 도메인 객체 생성자 null 체크는 의도적 중복 허용 (도메인 객체 단독 사용 시 무결성 보장)

### 400 vs 422 판단 기준
- "요청 형식이 잘못됨" → 400 (수정 후 재요청 의미 있음)
- "요청 형식은 맞으나 비즈니스 규칙 위반" → 422 (요청 자체 재고 필요)

### 근거
1. D-41 원칙(컨트롤러=형식·도메인=규칙) 상세화
2. 400/422/409 분리로 클라이언트 에러 대응 정확
3. 도메인 객체 단독 사용 시 무결성 보장 위해 중복 검증 허용
4. 의미 기준 분류는 계층 변경에 둔감

### 외부 검토 흡수
- 409/422 분류 기준을 Repository 계층 → 의미(동시성 vs 업무 제약)로 이동

---

## D-51 재결제 재검증 규칙 — Order Snapshot 고정 + 판매 가능성 차단 [ACTIVE]

### 결정
재결제(`POST /api/v1/orders/{publicId}/payments`) 진입 시 가격·수량 재계산 금지. Order 시점 Snapshot 그대로 유지. 단, 판매 종료·재고 부족·배송 불가 3종만 차단.

### 사양
- **고정 항목** (Order 생성 시점 값 그대로 사용):
  - `OrderItem.unit_price`·`OrderItem.total_price`·`OrderItem.quantity`
  - `OrderShippingSnapshot` 전체 (배송지·운송 정책)
- **재검증 항목** (PaymentService.initiate 진입 시 호출):
  - `Product.status != SALE` → 차단
  - `ProductVariant.is_soldout_manual == true` → 차단
  - `Inventory.quantity_available < OrderItem.quantity` → 차단
  - 배송 불가 (배송지 기준 향후 도입 시점 정의) → 차단
- **차단 응답**: HTTP 422 + `ORDER_NOT_PAYABLE` code. ProblemDetail.detail에 차단 사유 (`PRODUCT_NOT_ON_SALE`·`OUT_OF_STOCK`·`SHIPPING_UNAVAILABLE`) 명시.
- **검증 위치**: `PaymentService.initiatePayment(orderPublicId, buyerId)` 진입 직후 (Order 본인 검증 다음·attempt_key 발급 전).
- **신규 주문 경로 (`POST /api/v1/orders`)**: 본 검증 미적용 (Order 생성 시점에 OrderService가 이미 동일 검증 수행 가정).

### 근거
1. 가격 재계산은 상거래 표준 위배 — 사용자가 본 가격과 결제 가격 일치 필수
2. Order Snapshot 보존이 OrderItem 의미 정합 (정산·환불 기준 일관)
3. 3종 차단은 PG 결제 진행 자체가 무의미한 경우만 한정 — 가격 변동·할인 변경 등은 차단 사유 아님
4. 422 분류는 D-50 "업무 제약 위반" 정합

### 외부 검토 흡수
- CR-15 흡수 (3차 외부 검토) — 재결제 시 가격·재고·판매상태 재검증 부재 지적

### 관련 결정
- D-43 (재결제 분리)·D-50 (Validation 매트릭스)·D-42 (404 정책)

---

## D-52 멱등성 + Order 생성 순서 — TX 분리 부분 실패 복구 [ACTIVE]

### 결정
`POST /api/v1/orders` 진입 시 멱등성 row INSERT → Order 생성 → order_id 저장 → Payment initiate → 응답 캐싱 순서 강제. 재시도 시 Order 재생성 금지.

### 사양
- **호출 순서** (Idempotency-Key 헤더 전달 시):
  1. `order_idempotency_key` INSERT (`status=IN_PROGRESS`·`order_id=NULL`) — PK UNIQUE 충돌 시 409
  2. `OrderService.createOrder()` (TX1 COMMIT)
  3. `order_idempotency_key.order_id` UPDATE (별도 짧은 TX)
  4. `PaymentService.initiate()` (TX2 COMMIT) — 실패 허용 (D-43 정합)
  5. 응답 직렬화 → `response_body` 저장 + `status=COMPLETED` UPDATE (동일 TX)
- **재시도 분기** (동일 `Idempotency-Key` 재요청):
  - `status=COMPLETED` → 캐시 응답 반환 (D-44b 정합·HTTP 200)
  - `status=IN_PROGRESS` + `order_id IS NULL` → 409 `IDEMPOTENCY_KEY_IN_PROGRESS`
  - `status=IN_PROGRESS` + `order_id IS NOT NULL` → **기존 Order 복구**·`PaymentService.initiate` 재호출만 수행
- **Order 재생성 금지** — `order_id IS NOT NULL` 상태에서 신규 Order INSERT 발생 시 invariant 위반·시스템 오류로 분류.
- **attempt_key 재시도 정책** — initiate 재호출 시 **항상 신규 attempt_key 발급** (D-43·D-35 정합). 기존 Payment row(FAILED 상태) 재사용 금지·신규 row 추가.

### 근거
1. TX 분리(D-43) + 멱등성(D-44) 조합의 실 시나리오: Order 생성 후 응답 직전 서버 장애 발생 → 재시도 시 중복 Order 위험
2. `order_id` 컬럼은 D-44a에 이미 포함 — 신규 컬럼·테이블 추가 없음 (절차 박제만)
3. 재시도 시 Order 재생성은 buyer_id·OrderItem 점유·정산 단위 모두 오염
4. attempt_key 신규 발급은 PG 콜백 정합 (D-35 prefix 정책 일관)

### 외부 검토 흡수
- CR-16 흡수 (3차 외부 검토) — TX 분리 + 멱등성 경쟁 조건 지적
- CR-20 흡수 (3차 외부 검토) — 5xx 타임아웃 재시도 시 중복 주문 위험 → 본 결정으로 자연 해소
- CR-09 보강 흡수 — Order 재생성 금지·attempt_key 신규 명시

### 관련 결정
- D-43 (TX 분리)·D-44/D-44a/D-44b (멱등성)·D-35 (attempt_key)·D-66 (부분 실패 row 처리)

---

## D-53 Location 헤더 강제 — REST 리소스 타입별 분리 [ACTIVE]

### 결정
201 Created 응답에 `Location` 헤더 강제. 단, 리소스 타입에 따라 Location 대상 분리.

### 사양

| 케이스 | HTTP | Location |
|---|---|---|
| 신규 주문 + Payment initiate 성공 | 201 | `/api/v1/orders/{orderPublicId}` |
| 신규 주문 + Payment initiate 실패 | 201 | `/api/v1/orders/{orderPublicId}` |
| 재결제 성공 | 201 | `/api/v1/payments/{paymentPublicId}` |
| 멱등성 캐시 재반환 | 200 | (Location 없음·재요청 응답) |

- 재결제는 Payment 생성이므로 Payment 리소스를 가리킴 (Order 리소스 재사용 금지).
- Location URL은 풀 경로 (도메인 제외·`/api/v1/...` prefix 포함).
- `paymentPublicId`는 `pay_` prefix·CHAR(30).

### 근거
1. REST 표준 — POST 후 생성된 리소스의 canonical URI 제공
2. 리소스 타입 분리 — 재결제는 Payment 생성·Order 변경 아님 (D-43 정합)
3. 클라이언트 URL 빌딩 책임 분산·향후 OpenAPI 자동 문서화 정합
4. initiate 실패도 Order는 정상 생성 — Location(order) 부여 자연

### 외부 검토 흡수
- CR-17 흡수 (3차 외부 검토) — 201 + 실패 응답 모호성 해소
- CR-17 보정 (외부 검토 4차) — 재결제 Location 대상은 Payment 리소스

### 관련 결정
- D-43 (재결제 분리)·D-44b (멱등성 캐시 200)

---

## D-54 PagedResponse<T> 명시 DTO — Spring Data Page 직접 노출 금지 [ACTIVE]

### 결정
페이징 응답은 자체 정의 `PagedResponse<T>` DTO 사용. `org.springframework.data.domain.Page` 직접 직렬화 금지.

### 사양
- **DTO 구조** (record):
  - `List<T> items`
  - `int page`
  - `int size`
  - `long totalCount`
  - `boolean hasNext`
- **정적 팩토리**: `PagedResponse.from(Page<T>)` — Repository 반환 Page → DTO 변환.
- **응답 필드 한정**: 위 5개. `pageable`·`sort`·`first`·`last`·`numberOfElements`·`empty` 등 Spring Data Page 내부 필드 노출 금지.
- **적용 범위**: 본 트랙 `GET /api/v1/orders` 목록 응답. 향후 모든 페이징 응답에 일관 적용.

### 근거
1. Spring Boot 3.2+ `PageImpl` 직접 직렬화 deprecation 경고 — Spring 측에서도 명시 DTO 권장
2. Spring Data 버전 업그레이드 시 응답 계약 안정성 (외부 영향 차단)
3. `pageable.offset`·`sort` 등 노출 필드 축소로 응답 크기 감소
4. 단독 개발자·단일 프론트 환경에 충분한 최소 필드 (확장 시 필드 추가 자연)

### 외부 검토 흡수
- CR-18 흡수 (3차 외부 검토) — Page<T> 직접 노출 계약 안정성 위험 지적

### 관련 결정
- D-42 (목록 조회 페이징)

---

## D-55 OrderSummary previewTitle 규칙 — 서버 생성 문자열 [ACTIVE]

### 결정
`OrderSummaryResponse.previewTitle` 필드 도입. 서버에서 문자열 생성·클라이언트 변환 책임 없음.

### 사양
- **필드 위치**: `OrderSummaryResponse.previewTitle` (`String` 타입·null 금지).
- **Preview 대상 OrderItem 선정 규칙**: Order의 OrderItem 중 `created_at ASC` 첫 행. INSERT 순서 = Order 생성 시점 cart 항목 순서 (Order 단위 멱등).
- **문자열 생성 규칙**:
  - OrderItem 1건 → `"{productName}"`
  - OrderItem 2건 이상 → `"{firstProductName} 외 {count - 1}건"`
- **예시**:
  - 단건: `"맥북 프로 14인치"`
  - 다건: `"맥북 프로 14인치 외 2건"`
- **OrderSummaryResponse 구조** (D-45 그룹화 비적용·목록 한정):
  - `orderId` (public_id·`ord_` prefix)
  - `previewTitle` (본 결정)
  - `sellerCount` (멀티벤더 정보·정수)
  - `totalPrice` (KRW 정수)
  - `status` (`{code, label}` 객체·D-46 정합)
  - `orderedAt` (ISO-8601 UTC)
- **i18n 대응**: 향후 다국어 도입 시 서버 단일 책임 — 클라이언트는 문자열 그대로 표시. "외 N건" 부분 locale별 메시지 키 적용은 후속 결정.

### 근거
1. "첫 OrderItem = 대표" 정의를 `created_at ASC`로 박제 — 정렬 의존성 차단
2. 서버 문자열 생성으로 클라이언트 변환 0·UI 책임 분리
3. `sellerCount` 추가로 멀티벤더 정보 전달 (D-45 정합·목록은 그룹화 비적용)
4. previewTitle null 금지 — D-49 필수 유지 필드 정합

### 외부 검토 흡수
- CR-19 흡수 (3차 외부 검토) — OrderSummary 비그룹화 명확화·sellerCount·previewTitle 도입
- CR-19 보정 (외부 검토 4차) — `representativeProductName` → `previewTitle`·정렬 의존성 제거·문자열 생성 규칙 박제

### 관련 결정
- D-42 (목록 조회)·D-45 (그룹화·목록 비적용)·D-49 (null 금지)

---

### CR-21. 멱등성 response_body 24h NULL 처리 (Track 4) [기각]

**출처**: 외부 검토 (3차) — 2026-06-27

**의견 요약**:
- D-44a 보존 윈도우 72h 일괄에서 `response_body` 저장량 증가 우려.
- 24h 시점 `response_body = NULL` 처리·`status`·metadata만 72h 유지 권장.

**기각 여부**: 기각

**기각 사유**:
1. **저장량 우려 미실증** — 체크아웃 응답 수 KB × 24h 추가 보관량 미미. 트래픽 임계 실측 없이 도입은 본 프로젝트 원칙(operate first → verify through repetition → promote to documentation) 위배.
2. **배치 운영 부담** — 24h NULL 처리 배치 + 72h 삭제 배치 = 2회 운영. 단독 개발자 환경에 모니터링·실패 복구 부담 증가.
3. **디버깅 의도 무력화** — 24~72h 구간 row의 `response_body` 부재 시 운영 CS 재현 자체 불가. 외부 검토 CR-11(72h 디버깅 보존) 의도와 자기 충돌.
4. **현 단계 트래픽 가정** — Track 4는 첫 HTTP 진입 계층 신설 단계. 저장량 임계 도달 시점은 운영 트래픽 발생 이후로 자연 이연 가능.

**향후 재평가 트리거**:
- `order_idempotency_key` 테이블 row 수 임계 도달 시점
- 또는 Track 7 Redis 도입 시 멱등성 매체 마이그레이션 검토와 동시 (D-44a 기존 트리거 정합)

**관련 결정**: D-44a (72h 일괄 유지).

---

## D-56. PaymentService.initiate 시그니처 확장 [ACTIVE]

**결정일**: 2026-06-27
**관련**: Track 4 / D-43·D-51·D-52

**변경 전**: `initiate(PaymentInitiateRequest request)` — request = (orderId, method, amount)
**변경 후**: `initiate(String orderPublicId, Long buyerId, PaymentMethod method)`

**사유**:
- D-51 진입 직후 3종 재검증 필요 (Order Snapshot 고정·판매 가능성)
- D-52 buyerId 소유권 확인 (404 vs 403 분기)
- attempt_key 서버 발급 원칙 유지
- amount는 Order에서 재계산 (클라이언트 신뢰 차단)

**정찰 근거**: PaymentService.java 현 시그니처 = `initiate(PaymentInitiateRequest)`·프로덕션 호출부 0건 (회귀 위험 없음).

---

## D-57. Inventory 엔티티 Track 4 범위 신설 [ACTIVE]

**결정일**: 2026-06-27
**관련**: Track 4 / D-51 / Track 7 deferred 일부 해제

**결정**: Track 4에서 Inventory Java 엔티티·Repository 최소 신설. 범위는 read-only 조회 한정 (재고 검증용).

**범위**:
- `Inventory.java` 엔티티 (variant_id·quantity_on_hand·quantity_reserved·quantity_available)
- `InventoryRepository` (findByVariantId 등 조회만)
- 재고 차감/증가·InventoryHistory는 Track 7 deferred 유지

**사유**:
- DDL은 V1__init.sql L488에 이미 존재 (테이블 신설 불필요)
- D-51 재고 검증 (`quantity_available < quantity` 차단) 온전 적용
- Track 7 Inventory 도메인 구현 시 read-write 확장

**정찰 근거**: Java inventory 패키지·엔티티 부재 (Glob 0건)·DDL inventory 테이블 존재.

---

## D-58. CheckoutService 오케스트레이션 계층 신설 [ACTIVE]

**결정일**: 2026-06-27
**관련**: Track 4 / D-52

**결정**: createOrder ↔ initiate TX 분리 호출 순서 조립을 `CheckoutService`에서 수행.

**책임**:
- OrderService.createOrder (TX1) → PaymentService.initiate (TX2) 순서 조립
- D-52 부분 실패 복구 (Order 생성 후 initiate 실패 시 재시도 처리)
- 향후 쿠폰·포인트 차감 등 조립 지점 확장

**제외 대안**:
- OrderController 직접 조립: 얇은 컨트롤러 원칙 위반
- OrderFacade: Facade는 다중 도메인 단순 위임용·오케스트레이션 책임 부적합

**정찰 근거**: OrderService.createOrder는 PaymentService 미호출·OrderPlaced 이벤트만 발행 (현재 비결합 상태).

---

## D-59. Product·ProductVariant·Seller 읽기전용 엔티티 Track 4 선반영 [ACTIVE]

**결정일**: 2026-06-27
**관련**: Track 4 / D-45·D-51·D-55·D-57

**결정**: Track 4에서 Product·ProductVariant·Seller Java 엔티티·Repository 최소 신설. 범위는 read-only 조회 한정. 쓰기 책임은 Track 7.

**범위**:
- `product/entity/Product.java` (BIGINT id·public_id `prd_`·seller_id·category_id·name·description·status·base_price·thumbnail_url) — `AbstractPublicIdSoftDeletableEntity` 상속
- `product/entity/ProductVariant.java` (variant_code·seller_sku·barcode·additional_price·status·is_soldout_manual·display_order·option1~3_value_id·public_id `var_`) — `AbstractPublicIdSoftDeletableEntity` 상속
- `seller/entity/Seller.java` (BIGINT id·public_id `slr_`·company_name·status·business_no·ceo_name·contact_email·contact_phone) — `AbstractPublicIdSoftDeletableEntity` 상속
- 각 도메인 Repository (`findById`·`findByIdIn`·`findByPublicId` 등 조회만)

**제외 (Track 7 deferred 유지)**:
- 쓰기 메서드·도메인 행위 (상태 전이·승인·재고 차감 등)
- 부수 엔티티 (`ProductOptionGroup`·`ProductOptionValue`·`ProductImage`·`Category`) — 본 트랙 응답 필드 미요구
- `ProductVariant.option1~3_value_id` FK 연결 — read-only 컬럼만 노출·조인 미수행

**사유**:
- D-45/§11 응답 (`slr_/prd_/var_ public_id`·`companyName`)·D-55 (`productName`)·D-51 (`Product.status`·`is_soldout_manual`)의 공통 데이터 조달 전제
- D-57(Inventory) 선례와 일관 — 코드베이스 표준(엔티티 + Repository derived query) 유지
- Projection 쿼리 (B안)는 코드베이스 첫 복합 JPQL·CLAUDE.md 바인딩 주석 규칙 신규 적용 부담 회피
- 응답 필드 descope (C안)는 §11/§55 BIGINT 노출 금지 정합 위반

**정찰 근거**: V1 DDL에 product·product_variant·seller 테이블 존재·DDL 필드는 audit-policy.md L1~L3 박제 (회귀 위험 낮음).

**Track 7 연결**: Track 7 Product/Seller 도메인 구현 시 도메인 행위 추가·필드/베이스 재정의 금지.

---

## D-60. D-51 재검증 Track 4 구현 범위 [ACTIVE]

**결정일**: 2026-06-27
**관련**: Track 4 / D-51

**결정**: D-51 재결제 재검증 3종 중 본 트랙 구현 범위는 **2종 한정** (`Product.status` + 재고). `SHIPPING_UNAVAILABLE`은 본 트랙 보류.

**구현 범위 (Track 4 본 트랙)**:
- `Product.status != SALE` → 422 `ORDER_NOT_PAYABLE` + detail `PRODUCT_NOT_ON_SALE`
- `ProductVariant.is_soldout_manual == true` OR `Inventory.quantity_available < OrderItem.quantity` → 422 `ORDER_NOT_PAYABLE` + detail `OUT_OF_STOCK`

**보류 (Track 4 OUT-OF-SCOPE)**:
- `SHIPPING_UNAVAILABLE` — 배송 가능성 판정 규칙 미정의 (배송 정책·도서산간·일시 중단·재고 위치 등 동반 결정 필요)
- 배송 규칙 정의 시점에 재진입

**사유**:
- D-59 데이터 조달 (Product·Variant·Inventory) 4종 read-only로 즉시 가능
- 배송 가능성 판정은 도메인 정책 동반 결정 — 본 트랙 신규 박제 부담
- 운영 현실: 입점 초기 단계에서 SHIPPING_UNAVAILABLE 발생 시나리오 희소

**향후 재평가 트리거**:
- 배송 정책 박제 (Delivery 트랙 또는 별도 결정) — 도서산간·일시 중단·재고 위치 등
- expected-spec.md §6 `SHIPPING_UNAVAILABLE` 행 재진입 시 본 결정 [SUPERSEDED] 처리

**관련 결정**: D-51 (재검증 3종 정의)·D-62a (expected-spec §6·§17 OUT-OF-SCOPE 이관).

---

## D-61. Payment.amount 서버 재계산 산식 [ACTIVE]

**결정일**: 2026-06-27
**관련**: Track 4 / D-56

**결정**: `Payment.amount = Order.total_price - Order.discount_amount + Order.shipping_fee` (실 결제액 표준).

**Track 4 시점 운용**:
- `Order.discount_amount`·`Order.shipping_fee` **서버 0 고정**
- `CreateOrderRequest`에 `discountAmount`·`shippingFee` 필드 **미노출**
- 산식 실 효과는 현재 `total_price`와 동일

**미래 대응**:
- 할인·배송비 산정 로직은 Track 7 이후 (Coupon·Promotion·Delivery 정책 동반 박제 시점)
- 산식 자체는 본 결정으로 박제 — 미래 도입 시 D-61 변경 없음·`createOrder` 산정 로직만 확장

**사유**:
- 클라이언트 amount 신뢰 차단 (D-56 amount 서버 재계산 원칙)
- 산식 미래 대비 — Track 7 도입 시 retro 회피
- 서버 0 고정으로 본 트랙 현시점 부담 0

**정찰 근거**: V1 DDL `order` 테이블 `total_price`·`discount_amount`·`shipping_fee` 컬럼 존재 (db-schema-decisions.md §2.5).

---

## D-62a. expected-spec §6·§17 SHIPPING_UNAVAILABLE OUT-OF-SCOPE 이관 [ACTIVE]

**결정일**: 2026-06-27
**관련**: Track 4 / D-51·D-60

**결정**: expected-spec.md v4에서 `SHIPPING_UNAVAILABLE` 행을 §6·§17 본문에서 "범위 외" 절로 이관. 배송 규칙 정의 시점에 재진입.

**보정 범위**:
- §6 재결제 재검증 3종 → 2종으로 축소 (`PRODUCT_NOT_ON_SALE`·`OUT_OF_STOCK`)
- §17 HTTP 상태 색인에서 `SHIPPING_UNAVAILABLE` 행 제거
- "범위 외" 절에 "배송 가능성 검증 (`SHIPPING_UNAVAILABLE`) — D-60·D-62a, Delivery 정책 박제 시점 재진입" 항목 추가

**사유**:
- D-60(A) 채택으로 본 트랙 미트리거
- recon 시 §6·§17 행과 실 코드 1:1 PASS 판정 정확성 확보 — 미구현 행이 본문에 잔존 시 FAIL/WARN 오판정 위험
- 보정 비용 최소 (행 이동 + 범위 외 절 1줄 추가)

**버전**: expected-spec.md v3 → v4 (D-62a 보정 단독·다른 변경 없음).

**관련 결정**: D-60 (`SHIPPING_UNAVAILABLE` 보류)·D-51 (재검증 3종 원문).

---

## D-63. D-51 재검증 적용 경로 — CheckoutService 재결제 한정 [ACTIVE]

**결정일**: 2026-06-27
**관련**: Track 4 / D-51·D-56·D-58·D-60

**결정**: D-51·D-60 재검증 2종을 **`CheckoutService` 재결제 경로에만** 배치한다. `PaymentService.initiate`는 순수 결제 생성 역할만 수행.

**적용 경로**:
- **신규 주문** (`POST /api/v1/orders`): 재검증 **미적용** (D-51 명시 정합). cart → Order 생성 시점 검증은 OrderItem 생성 로직 내 ORD-5(total = unit × quantity) 등 기존 명세만 적용.
- **재결제** (`POST /api/v1/orders/{orderPublicId}/payments`): `CheckoutService` 재결제 진입점에서 D-60 2종 검증 후 `PaymentService.initiate` 호출.
- `PaymentService.initiate`는 자체 검증 없음 (순수 결제 row 생성·attempt_key 발급·PG redirect URL 발급).

**사유**:
- D-51 원문(decisions L1686) "신규 주문 경로 본 검증 미적용·재결제만" 충실. D-56 단일 시그니쳐 공유 시 신규 경로에서도 강제 검증되는 충돌 해소.
- D-58 CheckoutService "재결제 진입점" 책임 명시와 정합. D-58 본문 변경 없음·본 결정이 위치 보완.
- D-51 "initiate 진입 직후" 문구는 D-58 (CheckoutService 신설) 박제 이전 표현으로 해석. CheckoutService 계층이 생겼으므로 검증 위치도 해당 계층으로 이동.
- 신규 주문 시나리오는 "주문·결제 시점 간격이 짧아 상품 상태·재고가 변하지 않음" 전제가 자연 성립하므로 D-51 2종 검증 실익 희소. 재결제는 "결제 실패 후 대기 간격 동안 상품 상태·재고가 변경될 수 있음" 가정이 성립.

**제외 대안**:
- (B) `initiate` 무조건 재검증: 더 엄격하나 D-51 원문 위반·신규 주문도 Product 데이터 강제 의존 — 명세와 불일치.

**관련 결정**: D-51 (재검증 원문)·D-58 (CheckoutService 재결제 진입점)·D-60 (검증 범위 2종)·D-56 (initiate 시그니쳐).

---

## D-64. 신규 주문 unit_price·sellerId 서버 산정 [ACTIVE]

**결정일**: 2026-06-27
**관련**: Track 4 / D-56·D-59·D-61·D-63

**결정**: 신규 주문 생성 시 OrderItem의 `unit_price`·`total_price`·`seller_id`는 서버가 Product/Variant 데이터에서 도출한다. 클라이언트 제공 금지.

**`OrderItemRequest` 필드 한정**:
- `productId` (public_id·`prd_` prefix·D-65)
- `variantId` (public_id·`var_` prefix·D-65)
- `quantity` (정수·1 이상)

**서버 도출 산식** (CheckoutService 신규 주문 경로):
- `unitPrice = Product.base_price + ProductVariant.additional_price`
- `totalPrice = unitPrice × quantity` (OrderItem.create ORD-5 검증 재사용)
- `sellerId = Product.seller_id`

**정합성 검증** (도출 전):
- `ProductVariant.product_id == OrderItemRequest.productId` (variant 소속 확인) → 불일치 시 422 `MALFORMED_REQUEST` 또는 도메인 예외
- Product/Variant 존재 확인 → 부재 시 404 (조회 단계)

**사유**:
- D-56 amount 서버 재계산 원칙·D-61 산식 박제와 일관 (클라이언트 가격 신뢰 차단)
- `sellerId` 클라이언트 신뢰 시 멀티벤더 정산 조작 고리 개방 — 보안 필수
- D-59 read-only 엔티티 활용 — 신규·재결제 양 경로에서 일관 사용

**트레이드오프**:
- 신규 주문이 Product/Variant 데이터 존재에 의존 — Track 4 테스트 시 Product·ProductVariant·Seller 행 시딩 필요 (`@DataJpaTest`/통합 테스트 fixture)
- 테스트 fixture 부담은 Track 7 Product 도메인 구현 시 fixture 표준화로 해소 가능

**제외 대안**:
- (B) `OrderItemRequest`에 `unitPrice` 포함: 단순하나 가격 위변조 가능·D-56 신뢰 차단 정신 약화.

**관련 결정**: D-56 (amount 서버 재계산)·D-59 (read-only 엔티티)·D-61 (산식)·D-63 (경로별 적용).

---

## D-65. 요청 product/variant 식별자 형식 — public_id [ACTIVE]

**결정일**: 2026-06-27
**관련**: Track 4 / D-64·§11·§15

**결정**: API 요청 본문의 product/variant 식별자는 **public_id** (`prd_`/`var_` prefix)로 입력한다. CheckoutService 진입점에서 `findByPublicId`로 BIGINT id 해소.

**적용 범위**:
- `OrderItemRequest.productId` → `CHAR(30)` (`prd_` prefix)
- `OrderItemRequest.variantId` → `CHAR(30)` (`var_` prefix)
- 응답 동일 (§11 정합 — 이미 public_id)

**제외**:
- `shippingAddress` 등 식별자 아닌 입력값 필드는 본 결정 적용 없음
- 헤더 `X-Buyer-Id`는 입시 인증 (D-39) 시점 BIGINT 유지 (부파적 결정 아님)

**사유**:
- §11 "식별자 전체 public_id 사용·내부 BIGINT PK 노출 금지" 정합 (응답·요청 양방향)
- 세션·로그 노출 시 BIGINT 자동증가 수 노출 회피
- D-64 CheckoutService 데이터 로드 경로와 자연 결합 (`findByPublicId` → BIGINT id 해소)

**관련 결정**: D-64 (서버 산정)·§11 (public_id 정책)·§15 (JSON 직렬화).

---

## D-66. 멱등성 IN_PROGRESS row 부분 실패 처리 — 4xx 삭제·5xx 잔류 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 4 hotfix / D-44b·D-52 보정

### 결정
`CheckoutService.idempotentCheckout()` 처리 중 예외 발생 시 IN_PROGRESS row 처리 정책을 HTTP 상태별로 분기.

| 예외 카테고리 | IN_PROGRESS row | 효과 |
|---|---|---|
| 4xx (클라이언트 교정 가능) | 삭제 | 동일 키 재시도 허용 |
| 5xx (예상치 못한 서버 오류) | 잔류 | 운영자 개입 전까지 409 유지 |

### 사양
- **4xx 대상**: `CheckoutItemNotFoundException`(404)·`CheckoutItemMismatchException`(422)·`OrderNotPayableException`(422) 등 도메인·검증 예외
- **5xx 대상**: 그 외 `RuntimeException`·`DataAccessException` 등 예상치 못한 서버 오류
- **삭제 시점**: `createOrder` 호출 주변 try-catch에서 예외 catch 직후·예외 재throw 직전 (동일 메서드 내)
- **삭제 트랜잭션**: IN_PROGRESS INSERT와 동일 독립 TX 패턴 (`REQUIRES_NEW` 또는 별도 트랜잭션 경계)
- **위치**: `CheckoutService.idempotentCheckout()` (createOrder 호출부)

### 근거
1. **D-52 복구 분기 보존**: D-52 "IN_PROGRESS + order_id!=NULL → 기존 Order 복구·initiate 재호출" 분기가 5xx에서도 정상 동작. 옵션 B(5xx 삭제) 채택 시 분기 자체를 회피해 중복 Order 발생 위험.
2. **5xx 자동 차단이 default 안전**: 5xx는 예상치 못한 오류 — 자동 재시도 허용보다 자동 차단이 운영 안전.
3. **4xx 재시도 허용 자연**: 4xx는 클라이언트 교정 가능 (상품 ID 오타·재고 부족 등) — 동일 키 재시도 허용이 사용자 흐름 정합.
4. **부분 실패 시나리오 분석** (5xx 발생 시점별):
   - createOrder 5xx → Order 미생성·IN_PROGRESS만 잔류 → 옵션 A(잔류)로 409 차단 안전
   - order_id UPDATE 직전 죽음 → Order 생성·order_id=NULL → 옵션 A로 409 차단 (옵션 B 시 중복 Order)
   - initiate 5xx → Order 생성·order_id!=NULL → 옵션 A로 D-52 복구 분기 정상 작동 (옵션 B 시 중복 Order)

### 외부 검토 흡수
- recon-report.md Track 4 FAIL-코드-1 권장 (옵션 A) 흡수

### 회귀 테스트
- `checkout_itemNotFound_sameKeyRetryable` (404 → 동일 키 재시도 → 201)
- `checkout_itemMismatch_sameKeyRetryable` (422 → 동일 키 재시도 → 201)
- `checkout_5xx_sameKeyBlocked` (5xx → 동일 키 재시도 → 409 유지)

### 관련 결정
- D-44b (멱등성 응답 캐싱·"IN_PROGRESS row 삭제" 표현 → D-66 정합 재해석)
- D-52 (호출 순서·재시도 분기·복구 분기 보존)
- D-50 (409 vs 422 의미 분류)

---

## D-67. Track 5 Refund FAILED 진입 조건 — PG 호출 예외 포함 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 5 / state-machine §8·CR-03

### 결정
Refund.status PENDING → FAILED 전이 트리거에 **PG 호출 자체 예외 (네트워크·timeout·gateway exception)** 를 포함한다. 콜백 응답 실패만이 아니다.

### 사양
- `RefundService.initiate` 내부 PG 호출 (`MockPaymentGateway.refund()`) 예외 발생 시 PENDING 행을 FAILED로 전이
- 별도 `RefundFailureCode` enum·`failure_reason` 컬럼 도입하지 않음 (CR-01 보류 정합)
- state-machine §8 본문 "PG 환불 콜백/응답 실패" 표현은 본 결정과 정합하도록 후속 PR에서 "PG 환불 콜백/응답 실패·호출 예외 포함"으로 보강

### 사유
- 현 state-machine §8 정의는 콜백 실패만 포함 — PG 호출 예외 발생 시 상태 정의 공백
- 영구 PENDING 잔존 위험 차단 (외부 검토 운영 리스크 지적)
- 별도 enum/컬럼은 Track 5 범위 초과·discover → repeat → promote 정합 (CR-01 보류 근거 일관)

### 관련 결정
- D-24 (Refund.status 전이 확정)·CR-03 (외부 검토 흡수)

---

## D-68. Track 5 RefundService.markCompleted PAY-1 사후 재검증 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 5 / PAY-1·CR-05

### 결정
`RefundService.markCompleted()` 진입 시 PAY-1 (Σ Refund.COMPLETED.amount ≤ Payment.amount) 을 사후 재검증한다. `initiate()` 1회 검증만으로는 동시 환불 race condition 방어 불가.

### 사양
- `initiate()` 사전 검증: PAY-1 1차 (예약 단계)
- `markCompleted()` 사후 재검증: PAY-1 2차 (확정 단계)
- 동시 환불 시 Payment 행 직렬화 필요 — 구현체 (DB 비관적 락 `SELECT ... FOR UPDATE` vs Optimistic Lock) 선택은 구현 트랙 위임
- SoT에는 "Payment 행 직렬화 보장"만 명시·구현체 박제 없음

### 사유
- PAY-1은 교차 Aggregate invariant (Refund/Claim → Payment.amount 참조) — 단일 트랜잭션 보호 불가
- 동시 환불 시나리오: Refund A·B 동시 initiate 통과 → 동시 markCompleted → 초과 환불 위험
- 락 방식 SoT 박제는 과함 (외부 검토 지적) — 구현체 자율

### 관련 결정
- PAY-1 (invariants §2.11)·CR-05 (외부 검토 흡수)·Q5 (expected-spec §11.1)

---

## D-69. Track 5 RefundCompleted 이벤트 Publisher·Consumer 시점 분리 [ACTIVE·PARTIALLY SUPERSEDED by D-75]

**결정일**: 2026-06-28
**관련**: Track 5 / D-29·CR-06

### 결정
RefundCompleted 이벤트 Publisher 시점과 Consumer 실행 시점을 분리 서술한다.

| 구분 | 시점 | 근거 |
|---|---|---|
| Publisher | save → publish (flush 없음) | D-29 유지 |
| Consumer 실행 | `@TransactionalEventListener(phase = AFTER_COMMIT)` | Refund UPDATE 커밋 후 핸들러 진입 보장 |

### 사양
- `RefundService.markCompleted()` 내부에서 `repository.save(refund); eventPublisher.publishEvent(...)` 순서 (D-29 정합)
- `ClaimEventHandler`·`PaymentEventHandler` 모두 `@TransactionalEventListener(phase = AFTER_COMMIT)` 어노테이션 적용
- 핸들러 자체 멱등성 보장 (이벤트 재진입 시 no-op)
- Outbox·event_consumer_failure 등 별도 저장소 도입 없음 (CR-10 철회 정합)

### 사유
- D-29 원문은 publish 시점 정의 — `AFTER_COMMIT`은 listener 실행 시점만 지연 (publish 자체는 commit 전)
- 두 시점 혼합 서술 시 구현자가 `TransactionSynchronization.afterCommit()` 직접 publish 구조로 오해 가능
- 부분 실패 (Claim 성공·Payment 실패 등) 는 핸들러 멱등성으로 자연 재처리·Outbox 패턴은 Track 5 범위 초과

### 관련 결정
- D-29 (save→publish·flush 없음)·CR-06 (외부 검토 흡수·시점 분리 명시)

---

## D-70. Track 5 Refund.refunded_at 정의 — COMPLETED 전이 시스템 시각 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 5 / CR-09

### 결정
`Refund.refunded_at` 컬럼 의미는 **Refund.status가 COMPLETED로 전이된 시스템 시각**이다. PG 원시 시각 (PG 승인 시각·콜백 수신 시각) 아님.

### 사양
- `RefundService.markCompleted()` 내부 `LocalDateTime.now()` 또는 동등 시스템 시각으로 채움
- PG 콜백 페이로드 내 `pg_approved_at` 등 외부 시각은 별도 컬럼 없이 무시 (필요 시 후속 트랙)
- 정산·CS 조회 기준은 본 컬럼
- `Refund.status = FAILED` 상태에서는 `refunded_at = NULL` 유지한다 (설정 금지). 근거: refunded_at = COMPLETED 전이 시각 정의·V5 `chk_refund_completed_at` 정합.

### 사유
- 콜백 수신 시각은 네트워크 지연 영향 — 정산·CS 기준 흔들림 위험
- 시스템 상태 전이 시각이 운영 의미 안정 (외부 검토 지적)
- 컬럼 타입·구현 동일 — 의미만 안정화

### 관련 결정
- CR-09 (외부 검토 흡수)

---

## D-71. Track 5 Payment.CANCELLED 의미 — 전액 환불 완료 (트랙 범위 한정) [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 5 / state-machine §1·CR-11

### 결정
Track 5 범위 내 `Payment.CANCELLED` 의미는 **전액 환불 완료**이다. 부분환불 도입 시 의미 재정의 가능 — 영구 고정 아님.

### 사양
- `PaymentService.markCancelled()` 진입 조건: Σ(Refund.COMPLETED.amount by paymentId) == Payment.amount
- 부분환불 (Σ < Payment.amount) 시 Payment 상태 유지 (`PAID`)
- 부분환불 트랙 도입 시 후보: `PAID → PARTIALLY_REFUNDED → CANCELLED` 또는 CANCELLED 자체 의미 재정의 — 본 결정 시점 미확정

### 사유
- 상태 의미 영구 고정은 미래 확장 비용 증가 (외부 검토 지적)
- Track 5 범위 명시로 부분환불 도입 시 재정의 자유 확보
- 현 트랙 구현은 전액 환불 단일 시나리오 — 의미 모호성 없음

### 관련 결정
- D-05 (Payment state machine)·CR-11 (외부 검토 흡수)

---

## D-72. Track 5 Refund pg_refund_id 발급 시점 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 5 / expected-spec §5.1·외부 검토 [I1]

### 결정
`pg_refund_id`는 `RefundService.initiate()` 내 PG 호출 성공 응답 시점에 수신·PENDING 행에 저장한다.
webhook은 상태 확정(SUCCESS/FAIL) 및 부가 메타데이터 전달 용도이며 pg_refund_id를 최초 부여하지 않는다.

### 사유
- webhook 페이로드가 `{pg_refund_id, status, failure_reason}`만 운반 — PENDING refund를 콜백에 연결할 키가 pg_refund_id뿐
- 실 PG(토스·아임포트·KG이니시스 등) 표준 패턴 일치·향후 PG 교체 비용 최소화
- spec §5.1 "pg_refund_id 미부여 상태 반환" 문구는 "COMPLETED 미확정" 의미로 해석 가능하나 코드가 정확 → spec v2.1 보정

### 관련 결정
- 외부 검토 [I1]·expected-spec §5.1 (v2.1 보정)

---

## D-73. Track 5 PG 호출 실패 처리 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 5 / D-67·외부 검토 [I3]

### 결정
PG 호출 실패는 시스템 예외가 아니라 비즈니스 실패로 취급한다.
`RefundService.initiate()` 내 PG 호출 예외 발생 시 Refund 상태를 FAILED로 전이한 후 트랜잭션을 정상 커밋한다.
FAILED 저장 전 RuntimeException 재던지기 금지(테스트로 보장).

### 사유
- Outbox 미도입 단계 운영 친화성 — DB 기록 유실 방지
- spec §7 "Refund INSERT는 PG 호출 전 commit" 표현은 단일 @Transactional 실제와 불일치 → spec v2.1 보정

### 관련 결정
- D-67 (PENDING→FAILED 트리거)·외부 검토 [I3]·expected-spec §7 (v2.1 보정)

---

## D-74. Track 5 이벤트 핸들러 빈 네이밍 정책 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 5 / 외부 검토 [I5]

### 결정
이벤트 핸들러는 Spring 기본 빈 네이밍(클래스명 camelCase)을 사용한다.
명시적 `@Component("...")` 지정은 다중 구현체·`@Qualifier`·조건부 빈 등 충돌 발생 시에만 허용한다.

동명 클래스가 복수 패키지에 존재해 기본 빈 이름이 충돌할 경우, 명시 이름 대신 **클래스 리네이밍**을 우선 적용한다.
`claim.handler.RefundCompletedHandler` + `payment.handler.RefundCompletedHandler` →
`ClaimRefundCompletedHandler` + `PaymentRefundCompletedHandler` (Track 5 실 적용)

### 사유
- 패키지 분리만으로 Spring 기본 빈 이름 충돌 해소 불가 (동명 클래스 → 동일 camelCase 이름)
- 선제적 명시 이름 부여는 리팩토링 비용 증가·D-74 정책 위반
- 클래스 리네이밍은 코드 의도 명확화 부수 효과

### 관련 결정
- 외부 검토 [I5]

---

## D-75. Track 5 이벤트 핸들러 트랜잭션 정책 [ACTIVE] (D-69 SUPERSEDES 부분)

**결정일**: 2026-06-28
**관련**: Track 5 / D-69·외부 검토 [🪤]

### 결정
도메인 이벤트 핸들러는 AFTER_COMMIT 실행을 기본으로 한다.
독립 트랜잭션(`REQUIRES_NEW`)은 핸들러가 아래 중 하나를 수행할 때만 적용한다:
- DB 상태 변경
- 외부 시스템 호출
- 재시도 가능한 부수효과 저장

로깅·조회·캐시 갱신은 트랜잭션을 생성하지 않는다.
Outbox 도입 시 REQUIRES_NEW 의존도 재평가.

D-69 "각자 별도 트랜잭션" 표현은 "DB 쓰기 수행 핸들러에 한해 별도 트랜잭션"으로 정제된다.

### 사유
- 전역 REQUIRES_NEW는 예방적 과설계·불필요한 트랜잭션 오버헤드
- 역할별 최적화 필요 — 로깅 전용 핸들러에 REQUIRES_NEW는 낭비
- Track 5 두 핸들러는 모두 DB 쓰기 수행 → REQUIRES_NEW 유지가 D-75 정책과 정합

### 관련 결정
- D-69 (Publisher·Consumer 시점 분리 — AFTER_COMMIT 부분 유효·REQUIRES_NEW 범위 정제)

---

## D-76. Track 5 Refund 멱등성 키 역할 분리 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 5 / 외부 검토 CR-15·V5__refund_constraints.sql

### 결정
`pg_refund_id` = 외부 PG 식별·webhook 중복 방어 (UNIQUE, initiate 실패 시 NULL 허용).
내부 멱등 제어는 `pg_refund_id`에 위임하지 않는다.
(내부 attempt_key 도입이 필요할 경우 후속 트랙에서 별도 결정)

V5 제약: `uk_refund_pg_refund_id UNIQUE (pg_refund_id)` — MariaDB UNIQUE는 NULL 다건 허용으로 initiate 실패 행 공존 가능.

### 사유
- PG 교체·Mock 차이·재시도 정책과 외부 식별자 결합 방지
- 외부 검토 CR-15 지적: pg_refund_id UNIQUE 부재 시 webhook 중복 방어 불완전

### 관련 결정
- CR-15 (외부 검토)·V5__refund_constraints.sql

---

## D-77. Track 5 Claim 1:1 Refund 제약 유지 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 5 / 외부 검토 CR-12

### 결정
Track 5에서는 Claim 1:1 Refund 제약(단일 환불 행)을 유지한다.
미래 부분환불 정책 도입 시 Claim 1:N RefundAttempt 진화 가능성을 기록한다(현 시점 구현 변경 없음).

### 사유
- 현 규모 단순성 우선·부분환불 요구사항 미확정
- CR-12 지적을 설계 부채로 기록, 도입 시 재평가

### 관련 결정
- 외부 검토 CR-12

---

## D-78. Track 6 Gate 조건 보정 (트랙 결정 정합) [ACTIVE]

**상태**: [확정 2026-06-28]
**관련**: Track 6 / D-04·D-16·D-23·D-42·gate-conditions.md §1·§2·§3

### 배경
Track 6 정찰 (docs/track-6/recon-report.md) 결과 Gate 조건 6항목 GAP 표면화. GAP 6건 중 3건은 Gate 조건 박제 시점 (D-25·외부 검토 2차) 이후 트랙 결정 (D-16 OrderStatusResolver·D-42 Buyer 한정 endpoint·D-23 WithdrawnSeller Entity 이연)에 의해 발생한 Gate 조건 자체의 드리프트로 분류. Gate 조건을 트랙 결정과 정합하도록 보정한다.

### 결정
gate-conditions.md §1·§2·§3 4 항목 일괄 정정.

1. §1 Entity 분모 12 → 11로 정정·임계 ≤1.2 → ≤1.1 (1건 허용 유지). WithdrawnSeller Entity는 D-23 정합 Track 7+ 소관·분모 제외.
2. §2 상태 전이 검증 5 enum → 4 enum (OrderStatus 제외). OrderStatus는 D-04·D-16 ORD-2 OrderStatusResolver Domain Service 파생·canTransitionTo 부재가 의도된 설계.
3. §2 주문 생성 E2E에서 User 로그인·Cart 추가 단계 제거. Spring Security·Cart 도메인은 Track 7+ 진입 시 측정 재진입.
4. §3 API endpoint 임계 ≥10 → ≥6 축소. D-42 Buyer 한정 정책 정합·현재 6 endpoint 실측 기반. Track 7+ Aggregate 진입에 따른 endpoint 추가 시 임계 재정의.

### 사유
- "좋은 설계 < 좋은 현재 설계" 원칙 정합 — 박제 시점 가정값보다 실측·결정 정합 임계가 운영 안전
- "operate first → verify through repetition → promote to documentation" 원칙 정합 — 실측 기반 임계가 미래 가정 임계보다 운영 안정
- Gate 조건과 트랙 결정 (D-04·D-16·D-23·D-42) 자기 충돌 해소·운영자 정독 시 측정 결과 판단 모호성 0
- canTransitionTo 메서드 추가 (대안 B)는 OrderStatusResolver Domain Service 중복·D-16 위반
- Gate ≥10 유지 (대안 B)는 "임계 ≥10인데 현재 6 → 통과" 판단 모호·표현 부담만 잔존

### 영향 범위
gate-conditions.md §1·§2·§3 본문 4 항목 정정·각 항목 직후 비고 1줄 추가. 코드·테스트·다른 SoT 문서 영향 0. Track 6 PR-A (보강 테스트 2건) 진입 가능 상태 확립.

### 대안 검토
- 대안 1: OrderStatus.canTransitionTo 추가·5 enum 유지 → D-16 위반·OrderStatusResolver 중복 (기각)
- 대안 2: Gate ≥10 유지·Track 7+ 이연 명시만 추가 → 판단 모호성 잔존·운영 부담 증가 (기각)
- 대안 3: §3 측정 기준 endpoint 수 → 커버율 변경 → 측정 기준 전면 신설·D-25 박제 시점 이후 가장 큰 변경 (기각)

### 관련 결정
D-04 (Order.status 동기화·방식 B)·D-16 (OrderStatusResolver Domain Service)·D-23 (WithdrawnSeller·Seller 비식별화)·D-42 (Track 4 조회 API 범위·Buyer 한정)·D-25 (Gate 후 DDL 잠금)

### 후속
- PR-B 본 결정 박제 + gate-conditions.md 정정 완료 후 PR-A 진입 (GAP-E2E-2·GAP-TECH-1 보강 테스트 2건)
- Track 7+ 진입 시점에 Gate 조건 재측정 (Entity 분모·endpoint 임계·Cart·인증 E2E 단계 재진입)

---

### D-79. 라이브 트랩 — Testcontainers SET FOREIGN_KEY_CHECKS HikariCP 잔류 [ARCHIVED]

상태: [확정 2026-06-28]
관련: Track 6 / PR-A OrderTransactionRollbackTest

배경: OrderTransactionRollbackTest 작성 중 invalid FK item 시딩을 위해 SET FOREIGN_KEY_CHECKS=0 사용 시 HikariCP 커넥션 풀에 세션 변수 잔류·후속 테스트에서 FK 비활성 오염 발생.

결정: SET FOREIGN_KEY_CHECKS=0 사용 시 cleanup·seed 말미에 SET FOREIGN_KEY_CHECKS=1 복원 강제. 미복원 = 라이브 트랩 (CI 미탐지·후속 테스트에서만 표면화).

영향 범위: 전 Testcontainers 기반 통합 테스트. SET FOREIGN_KEY_CHECKS=0 사용 시 1:1 복원 짝 의무.

후속: ≥3건 라이브 트랩 누적 시 docs/troubleshooting/live-traps.md 신설 (promote 임계 도달).

관련 결정: CLAUDE.md "라이브 트랩 방지" 룰.

> **[ARCHIVED 2026-06-28]** 본 트랩 상세는 live-traps.md LT-02로 이관·본 결정문은 박제 이력 보존 목적 유지.

---

### D-80. Track 6 Gate 환불 E2E Claim 생성 단계 seed 시딩 허용 [ACTIVE]

상태: [확정 2026-06-28]
관련: Track 6 / Track 5 expected-spec §1.2·D-78·gate-conditions.md §2

배경: Track 6 정찰 (docs/track-6/recon-report.md) GAP-E2E-3 — 환불 완전 E2E (Payment.PAID → Claim 생성 → Refund.PENDING → MockPaymentGateway 환불 → Refund.COMPLETED → Claim.COMPLETED) 중 "Claim 생성" 단계가 RefundWebhookIntegrationTest에서 seed 수동 시딩으로 처리됨. ClaimService.approve()는 Track 5 expected-spec §1.2 명시 Out-of-Scope (후속 트랙 이연·Claim 요청 API·Claim 승인/거절 워크플로우).

결정: Gate 조건 §2 환불 성공 E2E의 "Claim 생성" 단계는 통합 테스트 seed 시딩 허용. ClaimService.approve() 구현 없이 Refund 콜백 흐름 본질 (Refund.PENDING → COMPLETED·Claim.COMPLETED 전이·Payment.CANCELLED 전이·RefundCompleted 이벤트 핸들러 AFTER_COMMIT) 검증으로 Gate 통과 판정.

사유:
- Track 5 의도적 OOS 결정 정합 — Track 6에서 ClaimService.approve() 구현은 Scope Drift (§4.1 위반)
- RefundWebhookIntegrationTest seed 시딩이 이미 Gate 본질 (환불 콜백 흐름) 달성
- D-78 패턴 정합 — Gate 조건이 트랙 결정 (Track 5 OOS) 정합하도록 보정
- 대안: ClaimService.approve() 구현 = Track 5 OOS 침범·운영 부담 증가 (기각)
- 대안: Gate §2 환불 항목 자체 제거 = 환불 도메인 핵심·Gate 측정 의미 약화 (기각)

영향 범위: gate-conditions.md §2 환불 성공 E2E 비고 1줄 추가. 코드·테스트·다른 SoT 문서 영향 0. Track 6 Gate 통과 측정 가능 상태 확립.

대안 검토:
- 대안 1: ClaimService.approve() + ClaimApprovedHandler 구현 → Track 5 §1.2 OOS 침범·§4.1 Scope Drift 위반 (기각)
- 대안 2: Gate §2 환불 E2E 항목 제거 → 환불 도메인 핵심·측정 의미 약화 (기각)
- 대안 3: 부분 PASS·잔존 GAP 인정 → 운영자 정독 모호성 발생 (기각)

관련 결정: D-78 (Gate 조건 보정·트랙 결정 정합)·Track 5 expected-spec §1.2 (Claim API OOS)·D-04·D-16 (Order.status 도메인 서비스 패턴)·§4.1 (Scope Drift 금지)

후속: PR-C 박제 완료 후 Gate 통과 측정 라운드 진입 (gate-conditions.md §1·§2·§3 전건 통과 검증·gate-passed.md 박제).

---

## D-81. Track 7 분할 — Batch 구성·PR 단위·산출물 범위·가드 2 라벨 [ACTIVE]

상태: [확정 2026-06-28]
관련: Track 7 / Gate 통과 후속 / aggregate-boundary.md §2 / gate-conditions.md §4

### 배경
Track 6 Gate 통과 박제 완료 (gate-passed.md·11/11 PASS) 후속·가드 3 발동 (Track 7 분할 박제 권고 의무). Track 7 = 나머지 Entity 일괄 확장 트랙. 실측 잔여 28건 (V1 37 + V2 +1 + V4 +1 = 39 테이블 - 이미 구현 11건). 인계 메모 추정값 26~27건 대비 +1~2건 실측 정합 분배 필요. 운영 용이성·객관 판단·과잉문서 회피·과잉개발 회피 4 기조 기준 분할 박제.

### 결정

#### 1. Batch 구성 (실측 정합)

| Batch | 카운트 | 대상 Entity |
|---|---|---|
| Batch-1 시드성·System 데이터 | 7 | role · permission · buyer_grade · code_group · code · category · grade_policy |
| Batch-2 매핑·집계·Read Model | 6 | user_role · role_permission · seller_user · buyer_purchase_aggregate · seller_sales_daily · inventory_history |
| Batch-3 도메인 | 15 | user · withdrawn_user · buyer_profile · user_address · seller_bank_account · settlement · withdrawn_seller · product_image · product_option_group · product_option_value · cart_item · delivery · attachment · audit_log · notification_log |

#### 2. PR 단위 (총 5 PR)

| PR | Batch | 대상 | 근거 |
|---|---|---|---|
| PR-1 | Batch-1 | 시드 7 일괄 | 시드성·Aggregate 내부 로직 거의 없음·일괄 정찰 1회 |
| PR-2 | Batch-2 | 매핑집계 6 일괄 | 매핑·Read Model·단순 구조·일괄 처리 효율 |
| PR-3a | Batch-3 | User Aggregate 4 (user·withdrawn_user·buyer_profile·user_address) | aggregate-boundary §2.1 자연 경계 |
| PR-3b | Batch-3 | Seller·Settlement·CartItem·Delivery 5 (seller_bank_account·settlement·withdrawn_seller·cart_item·delivery) | aggregate-boundary §2.2·§2.4·§2.5 |
| PR-3c | Batch-3 | Product잔여·공통보조 6 (product_image·product_option_group·product_option_value·attachment·audit_log·notification_log) | aggregate-boundary §2.3·§2.6·§2.7 |

§4.2 PR 크기 경고선 (Entity ≤3) 일부 초과·근거 명시: 응집 도메인 (User Aggregate·Product 종속 등)·시드성·매핑 일괄 처리는 경고선 예외 정합.

#### 3. 산출물 범위 (Track 7 트랙 한정)

- **포함**: Entity (JPA 매핑)·Repository (JpaRepository 인터페이스)·Repository 단위 테스트 (`@DataJpaTest`·기본 CRUD·UK·FK 검증)
- **이연 (Track 8+)**: Application Service·Controller·DTO·State Machine canTransitionTo 메서드·Invariant 검증 로직·E2E 통합 테스트·도메인 이벤트 핸들러

#### 4. 가드 2 S/A/B 라벨

| Batch | 라벨 | 근거 |
|---|---|---|
| Batch-1 시드 | B급 | 시드성·일괄 정찰 1회·외부 검토 생략 |
| Batch-2 매핑집계 | B급 | 매핑·Read Model·외부 검토 생략 |
| Batch-3 도메인 | A급 | Entity·Repository 한정·외부 검토 선택적 |

S급 후보 (User·Settlement·CartItem)는 Track 8+ Application Service 트랙 진입 시 재적용. Track 7 산출물 범위 (Entity·Repository) 한정에서는 S급 풀패키지 (외부 검토 + 결정 라운드 + Application Service) 불필요.

### 사유

**운영 용이성**:
- 5 PR 분할로 리뷰 단위 적정 (도메인 15건 일괄 PR은 리뷰 불가)
- Aggregate 자연 경계 정합으로 PR 범위 명확
- Batch당 1회 정찰로 정찰 비용 최소화

**객관 판단**:
- 실측 28건 기준 분배 (인계 메모 26~27 추정 대비 +1~2 정합)
- aggregate-boundary §2 16 Aggregate 자연 경계 활용
- §4.2 PR 크기 경고선 일부 초과 시 응집 도메인·시드성·매핑 일괄 처리 예외 명시

**과잉문서 회피**:
- 시드·매핑·집계는 일괄 PR (Aggregate별 7 PR 분할 대비 결정 박제 1건 처리)
- Track 7 결정 라운드 D-81 단건 박제·Batch별 별도 결정 파일 미신설 (다건 누적 시 신설 정당)

**과잉개발 회피**:
- 산출물 범위 Entity·Repository 한정으로 Application Service·E2E·State Machine 동반 구현 차단
- S급 풀패키지 미적용으로 외부 검토·결정 라운드 비용 최소화
- State Machine canTransitionTo·Invariant 검증은 후속 트랙 진입 시 도메인 행위와 함께 구현

### 영향 범위
docs/architecture-baseline/decisions.md D-81 1건 추가. 코드·테스트·다른 SoT 문서 영향 0. Track 7 Batch-1 진입 가능 상태 확립.

### 대안 검토
- 대안 1: Batch 구성 4·4·18 인계 메모 정합 → 박제 시점 추정값·실측 부정합 (기각)
- 대안 2: Batch 구성 위상정렬 11·9·8 → Batch-1 비대화·B급 일괄 정찰 부담 증가 (기각)
- 대안 3: 도메인 1 PR 일괄 (15건) → §4.2 경고선 심각 초과·리뷰 불가·rollback 어려움 (기각)
- 대안 4: 도메인 7 PR Aggregate별 → PR 7개 과잉문서·과잉개발 (기각)
- 대안 5: Track 7 산출물 범위 Application Service 포함 → 과잉개발·S급 풀패키지 부담·트랙 정의 위배 (기각)
- 대안 6: User·Settlement·CartItem S급 적용 → Track 7 산출물 범위 (Entity·Repository) 정합 불필요 (기각)

### 관련 결정
D-01 (Aggregate 16 + Infra/Event 1)·D-18 (NotificationLog Infra/Event 분류)·D-23 (WithdrawnSeller Snapshot Metadata)·D-25 (Gate 후 DDL 잠금)·D-78 (Gate 조건 보정·트랙 결정 정합)·gate-conditions.md §4.1 (Scope Drift 금지)·§4.2 (PR 크기 경고선)·§4.3 (DONE 조건)·§4.4 (금지 목록)·§4.5 (Entity 변경 정책)

### 후속

1. 본 결정 박제 PR 진행 (docs/track-7-split branch → main 머지)
2. Track 7 Batch-1 (시드 7) 진입 (신규 채팅 권장·정찰 → 결정 → 구현·B급 일괄 정찰 1회)
3. Batch-1 PR 머지 후 Batch-2·Batch-3a·3b·3c 순차 진행 (Batch별 신규 채팅 또는 동일 채팅 라운드 여유 시 연속 진행)

---

## D-82. 라이브 트랩 카탈로그 신설 — live-traps.md 박제·라벨링 도입 임계 도달 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 7 Batch-1 후속·D-26·D-79·CLAUDE.md "라이브 트랩 방지" 룰·가드 4

### 배경
Track 7 Batch-1 (Category Entity 구현) 중 @SQLRestriction @MappedSuperclass → @Entity 비전파 트랩 (HHH-17453) 발견·라이브 트랩 누적 3건 도달 (D-26·D-79·신규). 메모리 명시 임계 (≥3건 누적 시 docs/troubleshooting/live-traps.md 신설 정당) 정확히 도달. 동시에 결정 라이프사이클 라벨링 [SUPERSEDED] 1건 (D-69) + [ARCHIVED] 후보 2건 (D-26·D-79) = 합계 3건 → 가드 4 라벨링 도입 임계 도달.

### 결정

#### 1. live-traps.md 신설
경로: docs/troubleshooting/live-traps.md
내용: 트랩 카탈로그 SoT·LT-01·LT-02·LT-03 박제·후속 트랙 진입 전 정독 의무 문서.

#### 2. D-26·D-79 [ARCHIVED] 처리·이관
- D-26 → LT-01 이관 (CHAR public_id @JdbcTypeCode)
- D-79 → LT-02 이관 (SET FOREIGN_KEY_CHECKS HikariCP 잔류)
- 원본 D-XX 결정문 본문은 박제 이력 보존 목적 유지·말미에 이관 1줄 추가·헤더 [ARCHIVED] 라벨 부착

#### 3. LT-03 신규 박제 — @SQLRestriction HHH-17453
@MappedSuperclass에 선언한 @SQLRestriction이 Hibernate 6.6에서 @Entity 서브클래스로 전파되지 않음. @Entity 서브클래스에 직접 선언 의무.

**후속 영향 (Batch-3 필수 처치)**:
- 직접 상속 2종: UserAddress·ProductImage
- PublicId 경유 5종: User·Seller·Product·ProductVariant·Attachment
- Batch-3a·3b·3c 진입 시 본 트랩 정독 의무·@SQLRestriction 직접 선언 확인

#### 4. 결정 라이프사이클 라벨링 도입 (가드 4 옵션 C)
- [ACTIVE]·[ARCHIVED]·[SUPERSEDED]·[DEFERRED] 라벨 자연 적용 시작
- decisions-index.md 신설·도메인별 분할은 차회 임계 도달 시 (옵션 C 미니멀 적용)
- 신규 결정 박제 시 라벨 포함 (D-78 이후 패턴 유지)

### 사유
- 메모리 명시 임계 정확 도달 (≥3건)·미신설 시 룰 위반
- Batch-3 진입 시 LT-03 미인지 = 7 Entity 회귀 위험 명확
- 운영 용이성: 트랩 단일 카탈로그 정독으로 후속 트랙 진입 비용 절감
- 객관 판단: 임계 도달 = 신설 시점·박제 지연 불필요
- 과잉문서 회피: 신설 결정 단건만 박제·옵션 C 미니멀 라벨링 적용·index 분할 보류

### 영향 범위
- 신규 파일 1건: docs/troubleshooting/live-traps.md
- 수정 파일 1건: docs/architecture-baseline/decisions.md (D-26·D-79 [ARCHIVED] 처리·D-82 추가)
- 코드·테스트 영향 0건
- 후속 트랙 진입 시 live-traps.md 정독 의무 신설

### 대안 검토
- 대안 1: live-traps.md 미신설·D-82만 박제 → 메모리 명시 임계 룰 위반·후속 트랙 정독 부담 분산·기각
- 대안 2: 라벨링 도입 옵션 A·B (decisions-index.md 동반 신설) → 옵션 C 우선 원칙 위반·과잉문서·기각
- 대안 3: PR 머지 후 신규 채팅에서 박제 → 박제 지연 위험·메모리 룰 즉시 정합 위반·기각

### 관련 결정
D-26 [ARCHIVED] (CHAR public_id 트랩)·D-79 [ARCHIVED] (SET FOREIGN_KEY_CHECKS 트랩)·CLAUDE.md "라이브 트랩 방지" 룰·가드 4 (옵션 C 라벨링 도입)

### 후속
1. live-traps.md 신설 PR 진행 (docs/track-7-batch-1-live-traps → main)
2. Batch-2 진입 시 live-traps.md 정독 의무 적용 (신규 채팅 권장)
3. Batch-3 진입 시 LT-03 처치 7 Entity 의무 적용
4. 신규 라이브 트랩 발견 시 본 카탈로그 LT-XX 직접 추가·decisions.md 중복 박제 금지

---

## D-83. Track 7 Batch-2 진입 결정 — 복합 PK 전략·Test Base·SellerUser.roleId updatable [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 7 Batch-2 / D-81 §1·§3 / docs/track-7/batch-2/recon-report.md §8

### 배경
Batch-2 정찰 (recon-report.md) §8 결정 요청 3건 (Q1·Q2·Q3) 도출. B급 일괄 정찰 1회·외부 검토 생략 트랙. 사용자 4 기조 (운영 용이성·객관 판단·과잉문서 회피·과잉개발 회피) 정합 추천안 채택.

### 결정

#### Q1: SellerSalesDaily 복합 PK = @IdClass(SellerSalesDailyId.class)
Entity 필드 직접 접근(`e.sellerId·e.saleDate`)으로 코드·JPQL depth 감소. Batch-1 단순성 패턴 정합. `@EmbeddedId` 대비 `e.id.sellerId` depth 불필요·기각.

#### Q2: Batch-2 Repository @DataJpaTest Base = Batch1DataJpaTestBase 재사용
기능 차이 0·신설 비용 0 이득. "Batch1" 네이밍 아티팩트 허용. Track 7 PR 최종 단계(Batch-3c 머지 후) 클래스명 일괄 리네이밍 옵션 보류 (예: `TrackSevenDataJpaTestBase`·선택적).

#### Q3: SellerUser.roleId updatable 어노테이션 명시 생략 (JPA default true)
역할 변경 허용 여부 업무 정책 미확정 — Track 8+ Application Service 진입 시 정책 결정 후 명시. nullable=false 유지. userId는 updatable=false 유지 (행 의미 자체가 user 종속이므로 다름).

### 사유
- 기조 1 (운영 용이성): @IdClass 평탄 접근·Test Base 재사용 즉시 사용
- 기조 2 (객관 판단): Q3 업무 정책 가정 도입 회피·Track 8+ 정책 결정 이연
- 기조 3 (과잉문서 회피): 추가 클래스·문서 최소화
- 기조 4 (과잉개발 회피): 미결정 사항 박제 회피·DDL 영향 0·후속 변경 시 어노테이션 1줄 수정만

### 영향 범위
SellerSalesDaily·SellerSalesDailyId 클래스·SellerUser 어노테이션·Batch1DataJpaTestBase 재사용 명시. DDL·다른 SoT 영향 0. Batch-2 구현 PR-2 진입 가능 상태 확립.

### 후속
1. Track 8+ Application Service 트랙 진입 시 SellerUser.roleId updatable 정책 결정 (역할 변경 허용 시 updatable=true 명시·삭제/재생성 정책 시 updatable=false 명시)
2. Track 7 PR 최종 단계 (Batch-3c 머지 후) Batch1DataJpaTestBase 클래스명 일괄 리네이밍 옵션 (선택적)

### 관련 결정
D-81 (Track 7 분할)·recon-report.md §5.2.1·§5.3·§8

---

## D-84. Track 7 Batch-3a 진입 결정 — BuyerProfile 공유 PK @MapsId·재적용 결정 4건 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 7 Batch-3a / D-81 §1 PR-3a / docs/track-7/batch-3a/recon-report.md §8

### 배경
Batch-3a 정찰 (recon-report.md) §8 결정 요청 5건 (Q1~Q5) 도출. A급 정찰 read-only 트랙·외부 검토 생략. 사용자 4 기조 정합 권고안 전건 채택. Q1만 신규 결정 (공유 PK @MapsId 전략)·Q2~Q5는 기존 결정 재적용.

### 결정

#### Q1: BuyerProfile 공유 PK 매핑 = @MapsId + @OneToOne LAZY (신규 결정)
JPA 1:1 공유 PK 표준 패턴. `@MapsId`가 `user.id`를 `userId` 필드에 자동 채움·실수 차단. User 객체 직접 접근 가능 (lazy 로딩). SellerSalesDaily(@IdClass 복합 PK·D-83 Q1)와 이유 명확 분리 — 공유 PK 1:1은 @MapsId가 표준·복합 PK 다차원은 @IdClass가 자연.

#### Q2~Q5 재적용 결정 (신규 결정 아님·D-XX 부여 안 함)

| # | 항목 | 재적용 근거 |
|---|---|---|
| Q2 | WithdrawnUser.originalUser @ManyToOne LAZY | D-01 내부 Aggregate·SellerUser.seller·InventoryHistory.inventory 패턴 정합 |
| Q3 | UserAddress.user @ManyToOne LAZY | D-01 내부 Aggregate·동일 패턴 |
| Q4 | Test Base = Batch1DataJpaTestBase 재사용 | D-83 Q2 정합·신설 비용 0 |
| Q5 | User.email·name·phone nullable 유지 | D-22 비식별화 정합·Service 가드 Track 8+ 이연 |

### 사유
- 기조 1 (운영 용이성): @MapsId User 직접 접근·실수 차단
- 기조 2 (객관 판단): JPA 1:1 공유 PK 표준 패턴·SellerSalesDaily와 이유 분리 명확
- 기조 3 (과잉문서 회피): Q2~Q5 재적용은 D-XX 부여 안 함·본문 표로 간결 박제
- 기조 4 (과잉개발 회피): 외부 검토 생략 (A급 선택적·결정 항목 명확)

### 영향 범위
BuyerProfile 매핑·UserAddress·WithdrawnUser 매핑·UserRepositoryTest·BuyerProfileRepositoryTest·UserAddressRepositoryTest. DDL·다른 SoT 영향 0. Batch-3a 구현 완료·전체 회귀 PASS.

### 후속
- Track 8+ Application Service 진입 시 D-22 재가입 가드·비식별화 배치 로직 (User.email·name·phone Service 검증)
- BuyerProfile.gradeLockedUntil·gradeUpdatedAt 정책 로직 Track 8+
- Batch-3b 진입 시 LT-03 처치 의무 (Seller·SellerBankAccount·withdrawn_seller 등 검토)

### 관련 결정
D-01 (Aggregate 외부 ID 참조)·D-22 (비식별화 후 재가입 정책)·D-81 (Track 7 분할)·D-82 (라이브 트랩 카탈로그)·D-83 (Batch-2 진입 결정·복합 PK @IdClass)

---

## D-85. Track 7 Batch-3b 진입 결정 — Q1~Q5 일괄 채택 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 7 Batch-3b / D-81 §1 PR-3b / docs/track-7/batch-3b/recon-report.md §8

### 배경
Batch-3b 정찰 (recon-report.md) §8 결정 요청 5건 (Q1~Q5) 도출. A급 정찰 read-only 트랙·외부 검토 생략. 정찰 WARN-1 (settlement.public_id 비존재 확인)·WARN-2 (aggregate-boundary §2.2 WithdrawnSeller 미명시)·WARN-3 (Seller.java LT-03 미처치) 반영.

### 결정

| # | 항목 | 결정 | 사유 |
|---|---|---|---|
| Q1 | settlement.bank_account_id 매핑 | **Long bankAccountId** | D-01·Settlement 독립 Aggregate·aggregate-boundary §2.2 외부 ID·STL-3 스냅샷 의미. @ManyToOne 금지 |
| Q2 | withdrawn_seller.original_seller_id 매핑 | **@ManyToOne LAZY Seller** | D-23 SLR-7 SoT·WithdrawnUser 패턴 준용(D-84 Q2)·Seller Aggregate 내부 엔티티. Long 필드 대안 기각 |
| Q3 | seller_bank_account.account_number 매핑 | **평문 String·AES Track 8+ 이연** | Track 7 범위 한정(Entity·Repository)·Application Service 없는 단계·D-23 B-d4 정합. 즉시 AES 적용 대안 기각 |
| Q4 | cart_item UK(user_id, variant_id) 매핑 | **DDL 신뢰·@Table uniqueConstraints 생략** | DB UK가 제약 주체·user_id·variantId 모두 Long 필드·Batch-2 UserRole/SellerUser 패턴 정합 |
| Q5 | Test Base | **Batch1DataJpaTestBase 재사용** | D-83 Q2·D-84 Q4 정합·신설 비용 0 |

### 추가 결정 (WARN 처치)
- **WARN-1 처치**: settlement → AbstractFullAuditableEntity (public_id 없음·DDL 실측). getPublicIdPrefix() 불필요
- **WARN-2 처치**: aggregate-boundary §2.2 WithdrawnSeller → Seller Aggregate 포함 엔티티 추가 (동반 갱신)
- **WARN-3 처치**: 기존 Seller.java @SQLRestriction("deleted_at IS NULL") 직접 선언 추가 (LT-03·HHH-17453)

### 사유
- 기조 1 (운영 용이성): Q1 Long 필드로 Settlement 독립성 보장·Q2 @ManyToOne으로 D-23 SLR-7 정합
- 기조 2 (객관 판단): Q3 평문 이연은 Track 7 범위 초과 회피·Q4 DDL 신뢰는 기존 패턴 정합
- 기조 3 (과잉문서 회피): Q5 신규 Test Base 신설 회피
- 기조 4 (과잉개발 회피): AES @Converter·State Machine·Service 로직 일체 Track 8+ 이연

### 영향 범위
신규 14건 (SellerBankAccount·WithdrawnSeller·Settlement·CartItem·Delivery Entity + Enum 4·Repository 5)·기존 Seller.java @SQLRestriction 1줄·aggregate-boundary §2.2 갱신 1줄. DDL 영향 0.

### 후속
1. Track 8+ Application Service 진입 시 seller_bank_account.account_number AES @Converter 적용 (SLR-2·D-23 B-d4)
2. Batch-3c 진입 시 ProductImage·Product·ProductVariant·Attachment LT-03 처치 의무
3. Track 8+ Settlement State Machine (PENDING→CONFIRMED→PAID·STL-2)·SellerBankAccount 인증 흐름 구현

### 관련 결정
D-01·D-23 (Seller 비식별화·SLR-7)·D-81 (Track 7 분할·PR-3b)·D-82 (live-traps.md·LT-03)·D-83 (Batch-2 진입)·D-84 (Batch-3a 진입·@MapsId)·recon-report.md §8

---

## D-86. Track 7 Batch-3c 진입 결정 — Q1~Q6 일괄 채택 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 7 Batch-3c / D-81 §1 PR-3c / docs/track-7/batch-3c/recon-report.md §8

### 배경
Batch-3c 정찰 (recon-report.md) §8 결정 요청 6건 (Q1~Q6) 도출. A급 정찰 read-only 트랙·외부 검토 생략. 사용자 4 기조 (운영 용이성·객관 판단·과잉문서 회피·과잉개발 회피) 정합 추천안 전건 채택.

### 결정

| # | 항목 | 결정 | 사유 |
|---|---|---|---|
| Q1 | product_image·product_option_group·product_option_value 외부 참조 매핑 | **@ManyToOne(fetch=LAZY) Product / ProductOptionGroup** | aggregate-boundary §2.3 내부 Aggregate·D-85 Q2 패턴 정합·필드 직접 접근 |
| Q2 | audit_log abstract 매칭 | **AbstractCreatedOnlyEntity 상속 + publicId 자체 정의 + @PrePersist 자체 정의** | AbstractPublicIdFullAuditableEntity Javadoc 기명시·단일 사용 추상화 회피 (CLAUDE.md) |
| Q3 | notification_log abstract 매칭 | **AbstractCreatedOnlyEntity 직접 상속** | DDL public_id 없음·Javadoc 적용 대상 명시·신규 abstract 불필요 |
| Q4 | polymorphic target_type Enum | **공유 Enum `com.zslab.mall.common.enums.PolymorphicTargetType`** | 3 Entity 동일 참조·D-01 Aggregate 목록 기반 단일 소스 |
| Q5 | audit_log.diff_json 매핑 | **String (LONGTEXT)·JSON 함수 질의는 native query 위임** | D-11 정합 (MariaDB JSON=LONGTEXT alias·CHECK(JSON_VALID) DDL 강제)·Entity 레이어 구조 분석 미수행·@JdbcTypeCode 호환성 검증 부담 회피 |
| Q6 | Test Base | **Batch1DataJpaTestBase 재사용** | D-83 Q2·D-84 Q4·D-85 Q5 정합·신설 비용 0 |

### Q4 PolymorphicTargetType 값 후보 (Aggregate 전수 포함)
ORDER·ORDER_ITEM·PAYMENT·DELIVERY·CLAIM·REFUND·USER·SELLER·PRODUCT·PRODUCT_VARIANT·CART_ITEM·SETTLEMENT·SETTLEMENT_BANK_ACCOUNT·CATEGORY·INVENTORY·ATTACHMENT·AUDIT_LOG·NOTIFICATION_LOG·CODE·BUYER_GRADE

자기 참조는 사용 컨텍스트에서 자연 제외 (attachment.target_type ≠ ATTACHMENT 등). Java Enum 값 추가는 DB 마이그레이션 부담 0 (DDL VARCHAR(50)·D분류 앱 검증). Track 8+ Application Service 진입 시 사용 값 부분집합 결정.

### 추가 결정 (LT-03 처치 4건)
구현 PR 동반:
- **ProductImage** (신규·AbstractSoftDeletableEntity) — @SQLRestriction 직접 선언
- **Attachment** (신규·AbstractPublicIdSoftDeletableEntity) — @SQLRestriction 직접 선언
- **Product** (기존·Track 4 신설) — @SQLRestriction 직접 선언 + Javadoc 오기 보정 ("베이스 @SQLRestriction으로 자동 제외" → "@Entity에 직접 선언·LT-03 처치·HHH-17453")
- **ProductVariant** (기존·Track 4 신설) — 동일

### live-traps.md 갱신 (구현 PR 동반)
- Seller "Batch-3b 진입 시" → "✓ 완료 (Track 7 Batch-3b)" (WARN-2 보정·정찰 발견)
- ProductImage·Product·ProductVariant·Attachment → "✓ 완료 (Track 7 Batch-3c)"

### 사유
- **운영 용이성**: Q1 @ManyToOne으로 Product 필드 직접 접근·Q4 공유 Enum 단일 소스·Q6 Test Base 즉시 사용
- **객관 판단**: Q1·Q2·Q3·Q6 모두 기존 결정 (D-83·D-84·D-85·CLAUDE.md) 정합·Q5 D-11 원문 충실
- **과잉문서 회피**: D-86 단건 박제·Q2 신규 abstract 회피·Q4 공유 Enum
- **과잉개발 회피**: Q5 @JdbcTypeCode 호환성 검증 부담 회피·DTO @ValidEnum·diff_json 마스킹 로직 Track 8+ 이연

### 영향 범위
신규 Entity 6 (ProductImage·ProductOptionGroup·ProductOptionValue·Attachment·AuditLog·NotificationLog)·신규 Enum 4 (AuditLogAction·NotificationChannel·NotificationLogStatus·PolymorphicTargetType)·신규 Repository 6·신규 Test 6·기존 Entity 2건 @SQLRestriction 직접 선언 (Product·ProductVariant) + Javadoc 보정·live-traps.md LT-03 표 갱신 5건. DDL 영향 0. aggregate-boundary.md 무수정 (§2.3 이미 명시).

### 후속
1. Track 8+ Application Service 진입 시:
   - AuditLog 적재 훅 (D-11 우선 대상 8 테이블 변경 지점)
   - diff_json 민감정보 마스킹 로직
   - NotificationLog.status PENDING→SENT 전이 핸들러 (E1·E2·E4·E5·E9·E10 이벤트 소비)
   - DTO @ValidEnum PolymorphicTargetType·AuditLogAction·NotificationChannel·NotificationLogStatus
   - Product·ProductVariant·Attachment·CartItem 등 Aggregate Root 도메인 행위 추가
2. Track 7 종료 후속 작업:
   - Batch1DataJpaTestBase 클래스명 일괄 리네이밍 옵션 (D-83 Q2 후속·선택적)

### 관련 결정
D-01 (Aggregate 16+1·외부 참조 ID only)·D-11 (Audit Policy·diff_json JSON·changed_fields 한정)·D-18 (NotificationLog Infra/Event)·D-81 (Track 7 분할·PR-3c)·D-82 (live-traps.md·LT-03)·D-83 (Batch-2 진입·복합 PK)·D-84 (Batch-3a 진입·@MapsId)·D-85 (Batch-3b 진입·내부 @ManyToOne LAZY)·recon-report.md §8

---

## D-87. Track 8 진입 결정 — 우선 Aggregate·분할 단위·Admin 분리·State Machine 위치·Order Aggregate 3 PR 분할 [ACTIVE]

**결정일**: 2026-06-28
**관련**: Track 8 / D-01·D-16·D-29·D-39·D-43·D-58·D-66·D-81 / Track 3·4 산출물

### 배경
Track 7 종료 (Batch-3c 머지·2f590e8) 후 Track 8 진입. Application Service·Controller·DTO·E2E·State Machine·Invariant 책임 범위 광역·사전 분할 전략 결정 필요. 사용자 4 기조 (운영 용이성·객관 판단·과잉문서 회피·과잉개발 회피) 정합 추천안 채택.

### 결정

#### Q1: 우선 Aggregate = A (Order/Payment 핵심)
D-39 X-Buyer-Id stub 운영 중·인증 인프라 (옵션 D) 선구축은 기조 4 위배. Track 3·4 산출물 (OrderService·PaymentService·CheckoutService) 잔여 확장이 자연. 옵션 B (Product/Seller)·C (횡단 인프라)는 도메인 행위 의존·후속.

#### Q2: 분할 단위 = β (Service + Controller + E2E)
Track 4 패턴 (PaymentService + BuyerOrderController + 통합 테스트) 재적용. Aggregate 단위 PR 분할 자연. 옵션 α (Service만)는 검증 공백·옵션 γ (풀스택 일괄)는 PR 비대화.

#### Q3: Admin API = 별도 트랙 (Track 9+)
메모리 명시 "Admin API 자체 트랙 권장·~70% Service 재사용" 정합. Track 8 = Buyer/Seller 한정.

#### Q4: State Machine·Invariant 위치 = α (Aggregate Root) 기본 + 복잡도 도달 시 β (Policy 객체) 추출
D-16 OrderStatusResolver 선례 (Aggregate 경계 넘는 파생 로직)·단순 전이는 Aggregate Root 메서드 표준. Invariant 검증은 Service에서 호출.

### 가드 2 라벨 (Track 8 Aggregate별 사전 박제)

| Aggregate | 라벨 | 근거 |
|---|---|---|
| Order·Payment | S | 풀패키지·외부 검토·결정 라운드 의무 |
| Claim·Refund | S | Track 5 패턴 정합·환불 정합성 핵심 |
| Settlement | S | 정산 invariant·AES @Converter 신규 (D-85 Q3 이연분) |
| CartItem·Delivery | A | 단독 Aggregate·외부 검토 선택적 |
| Inventory | A | D-57 read-only 확장·재고 차감 invariant |
| AuditLog·NotificationLog | A | 적재 훅·이벤트 핸들러·횡단 인프라 |

### Order Aggregate 3 PR 분할

| PR | 범위 | 라벨 | 근거 |
|---|---|---|---|
| PR-A | CheckoutService D-66 정합 회귀 테스트 박제 + Outdated 주석 보정 | A | D-66 박제 후 회귀 테스트 부재 차단·OrderPlaced 핸들러는 후속 트랙 자연 진입 |
| PR-B | Order Aggregate Root State Machine 메서드 (canTransitionTo·apply) + Invariant 검증 | A | Q4 α 패턴 첫 사례·도메인 행위 단독 응집 |
| PR-C | OrderService 확장 + BuyerOrderController 잔여 endpoint + E2E | ~~S~~ [SUPERSEDED] | 정찰 결과 산출물 기 완료·실 코드 변경 0건·폐기 |

**진입 순서**: PR-A → PR-B → PR-C. PR-A·B 안착 후 PR-C 진입 시 회귀 표면 축소·결정 라운드 PR-C 집중 가능.

**정찰 보정 (2026-06-28·PR-A 진입)**: PR-A 범위는 회귀 테스트 박제 + Outdated 주석 보정으로 정정. OrderPlaced 핸들러 구현은 후속 트랙(NotificationLog·Inventory·CartItem·가드 2 A급) 자연 진입. 정찰 결과 CheckoutService.idempotentCheckout 내부 try-catch가 이미 4xx 삭제·5xx 잔류 정합·@Valid first-line defense로 ORD-1 등 IllegalArgumentException 도달 경로 차단. 회귀 테스트 3건(itemNotFound 404·itemMismatch 422·5xx 잔류)으로 정합 박제.

**정찰 보정 (2026-06-29·PR-B 진입)**: PR-B 범위는 OrderItemStatus.java Javadoc 1줄 보정(Track 5 → Claim 요청 API 트랙·expected-spec §1.2)으로 정정. Order Aggregate Root State Machine 메서드(canTransitionTo·apply)·OrderStatusResolver Domain Service·ORD-1~ORD-5 강제는 정찰 결과 Track 2~5 누적 산출물로 이미 완료 박제. OrderItemStatus.canTransitionTo Claim 진입 전이 매트릭스(진행 단계 → *_REQUESTED)는 Track 5 핸들러 정찰로 미수행 확인(Claim·Payment 종결 전이만 처리)·후속 Claim 요청 API 트랙 자연 진입.

**정찰 보정 (2026-06-29·PR-C 진입·Track 8 종료)**: PR-C는 정찰 결과 명목 산출물 3종(OrderService 확장·BuyerOrderController 잔여 endpoint·E2E 통합 테스트) 모두 Track 4 + PR-A + PR-B 누적 산출물로 이미 완료 박제. 4 endpoint(POST `/api/v1/orders`·GET `/{orderPublicId}`·GET `/api/v1/orders`·POST `/{orderPublicId}/payments`) 운영 중·D-42 3개 + D-43 재결제 1개 정합·잔여 endpoint 부재. CheckoutIntegrationTest 11건·BuyerOrderControllerTest 11건·OrderServiceTest 6건으로 4 endpoint × HTTP 상태 매트릭스·D-66 회귀·재결제 재검증·멱등성 분기 전건 커버. OrderService 잔여 도메인 행위(markCancelled·changeOrderItemStatus 등)는 호출자 부재 상태 추가 = 데드 코드(기조 4 위배) — 후속 Delivery·Claim 요청 API·Inventory 트랙 진입 시 호출자와 함께 신설. 실 코드 변경 0건 PR 회피(기조 3) → PR-C 폐기·Track 8 종료 선언. Order Aggregate 3 PR 분할은 PR-A·PR-B 2 PR 완료로 종결.

### 사유

**운영 용이성 (기조 1)**:
- 3 PR 분할로 리뷰 단위 적정·롤백 영역 한정
- Aggregate 단위 PR (β)로 Track 4 패턴 재현

**객관 판단 (기조 2)**:
- D-39 stub 운영 사실 기반 (Q1 A 선택)
- D-16 선례 기반 (Q4 α 기본·β 추출)

**과잉문서 회피 (기조 3)**:
- D-87 단건 박제·Track 8 별도 분할 결정 파일 미신설 (D-81 패턴·임계 미도달)
- 가드 2 라벨 본문 표 박제·Aggregate별 별도 결정 미발행

**과잉개발 회피 (기조 4)**:
- 인증·Admin·횡단 인프라 모두 이연
- 풀패키지 일괄 PR 회피 (5개 영역 동시 수정 위험 차단)
- State Machine Policy 객체는 복잡도 도달 시 자연 추출

### 영향 범위
docs/architecture-baseline/decisions.md D-87 1건 추가. 코드·테스트·다른 SoT 영향 0. Track 8 PR-A 정찰 진입 가능 상태 확립.

### 대안 검토
- 대안 1: Q1 옵션 D (인증 우선) → X-Buyer-Id stub 운영 중·과잉개발 (기각)
- 대안 2: Order Aggregate 풀패키지 단일 PR → §4.2 경고선 초과·회귀 다중 표면·결정 라운드 중첩 (기각)
- 대안 3: Q3 본 트랙 Admin 포함 → ~70% Service 재사용 가능 시점 이전·Buyer/Seller 안착 전 (기각)
- 대안 4: Q4 γ (Service 레이어) → Anemic Domain Model·도메인 누수 (기각)
- 대안 5: PR-A 추가 분할 (D-66 정합·OrderPlaced 핸들러 2 PR) → 정찰 비용 과잉·단일 PR 응집 충분 (기각)

### 후속
1. 본 결정 박제 PR (`docs/track-8-entry` → main)
2. PR-A 정찰 진입 (`docs/track-8/pr-a/recon-report.md`·신규 채팅 권장)
3. PR-B·PR-C 순차 진행 (각 PR별 정찰 → 결정 라운드 → 구현 → 1:1 대조)

### 관련 결정
D-01·D-16·D-29·D-39·D-43·D-58·D-66·D-81·D-83·D-84·D-85·D-86·gate-conditions.md §4.2 (PR 크기 경고선)

---

## D-88. Track 9 진입 결정 — Claim 요청 API 트랙·CANCEL 한정·Buyer Scope·3 PR 분할 [ACTIVE]

**결정일**: 2026-06-29
**관련**: Track 9 / D-01·D-02·D-03·D-04·D-05·D-16·D-23·D-24·D-29·D-39·D-75·D-87 / Track 5 expected-spec §1.2 / state-machine §2·§3 / invariants §2.13·§2.13.1

### 배경
Track 8 종료 (PR-A·PR-B 완료·PR-C 폐기·Order Aggregate 2 PR 종결). Track 5 expected-spec §1.2 Out-of-Scope 7항목 중 5항목 (Claim 요청 API·승인/거절 워크플로우·OrderItem.item_status 전이 동기화·Order.status 재계산 hook·REJECTED 경로) 미해소 잔존·단일 트랙 자연 응집. Track 8 PR-A·PR-B 박제 "후속 자연 진입" 트랙 식별자 (정찰 룰 #4) 명시 의무. 사용자 4 기조 정합 추천안 전건 채택.

### 결정

#### Q1: Claim type 범위 = β CANCEL 우선
RETURN·EXCHANGE는 Delivery 수거 추적·교환품 재출고 Delivery 의존 — 별도 트랙 분리 (Track 11). Track 5 박제 `Refund.COMPLETED → Claim.COMPLETED` 1전이가 CANCEL 자연 정합.

#### Q2: 권한 매트릭스 = α Buyer endpoint만 + ClaimService 전체
Buyer Claim 요청·조회 endpoint 본 트랙. ClaimService.request/approve/reject/markCompleted 4 메서드 Service layer 완성. Seller/Admin 승인·거절 endpoint는 Track 10 (Admin API) 이연·D-87 Q3 정합. E2E 테스트는 ClaimService 직접 호출로 승인/거절 흐름 검증 (Track 5 ClaimService.markCompleted 패턴).

#### Q3: OrderItem.item_status 동기화 = α 양방 본 트랙
REQUESTED 진입 (Claim 요청 시: PAID/PREPARING → CANCEL_REQUESTED 등)·완료 전이 (Claim.COMPLETED 시: CANCEL_REQUESTED → CANCELLED 등) 양방. state-machine §3 OrderItem.item_status 12값 완성 의무·Track 5 ClaimRefundCompletedHandler가 OrderItem 동기화 미박제 (PR-A·PR-B 정찰 박제).

#### Q4: REJECTED 경로 = α 본 트랙 포함
ClaimService.reject + Buyer 거절 조회 endpoint. 재요청은 동일 endpoint 재호출·CLM-2 (재요청 = 새 행) 자연 적용. 별도 재요청 UI/endpoint 신설 없음.

#### Q5: OrderItemStatus.canTransitionTo Claim 진입 매트릭스 = PR-A 정찰 후 매트릭스 초안 박제·결정 라운드는 회사 정책 항목만
state-machine §3 표 기반 일반 매트릭스 (PAID/PREPARING → CANCEL_REQUESTED·SHIPPING/DELIVERED → RETURN_REQUESTED·DELIVERED → EXCHANGE_REQUESTED) 는 정찰 자체 박제. 회사 정책 항목 (CONFIRMED 이후 클레임 허용 여부·SHIPPING 단계 CANCEL 허용 여부·기간 제한 등) 만 PR-A 결정 라운드.

#### Q6: PR 분할 = 3 PR (D-87 Order 패턴 재적용)

| PR | 범위 | 라벨 |
|---|---|---|
| PR-A | OrderItemStatus.canTransitionTo Claim 진입/완료 매트릭스 + ClaimStatus 전이 보강 (REQUESTED → APPROVED·REJECTED) | A |
| PR-B | ClaimService (request/approve/reject/markCompleted) + BuyerClaimController + DTO + E2E | S |
| PR-C | 이벤트 핸들러 (ClaimRequested/Approved/Rejected → OrderItem 동기화·Order.status 재계산 hook) | S |

진입 순서: PR-A → PR-B → PR-C. PR-B 결정 라운드 메인·외부 검토 의무. PR-A·PR-C 가벼움.

> **정찰 보정 (2026-06-29·PR-A 구현)**: PR-A 실 범위는 OrderItemStatus.canTransitionTo Claim 진입 매트릭스 5건 신설(PAID/PREPARING→CANCEL_REQUESTED·SHIPPING/DELIVERED→RETURN_REQUESTED·DELIVERED→EXCHANGE_REQUESTED) + OrderItemStatusTest 오라클 `Map<Set>` 리팩토링으로 정정. D-88 Q6 PR-A scope "ClaimStatus 전이 보강 (REQUESTED→APPROVED·REJECTED)"은 정찰 결과 Track 5 시점에 ClaimStatus.canTransitionTo 4×4 완전 구현 완료 박제 — PR-A 추가 작업 불필요. 회사 정책 결정 라운드: Q1(SHIPPING→CANCEL_REQUESTED 차단·출고 후 CANCEL은 RETURN 경로)·Q2(CONFIRMED 종결·*_REQUESTED 전건 차단)·Q3(SHIPPING→EXCHANGE_REQUESTED 차단·교환은 수령 후 절차)·Q4(canTransitionTo(next) 단일 인자 유지·ClaimType 무관·책임 분리). Track 8 PR-A/PR-B 사후 정정 패턴 재현·docs/track-9/pr-a/recon-report.md WARN-2 해소.

#### Q7: Order.status 재계산 트리거 = 혼합 패턴
Claim → OrderItem 동기화: 도메인 이벤트 + `@TransactionalEventListener(AFTER_COMMIT)` 핸들러 (D-29·D-75 정합). Claim Aggregate 외부 Order Aggregate 갱신은 이벤트 경유 (D-01).
핸들러 내부 OrderItem → Order.status 재계산: OrderStatusResolver Domain Service 동기 호출 (D-16). OrderItem은 Order Aggregate 내부 (aggregate-boundary §2.5)·동일 트랜잭션 갱신.

### 가드 2 라벨 (Track 9 PR별)
- PR-A = A급 (Enum 메서드·State Machine 보강 한정·외부 검토 선택적)
- PR-B = S급 (Application Service·Controller·E2E 풀패키지·외부 검토 권장·결정 라운드 의무)
- PR-C = S급 (이벤트 핸들러·Order.status 재계산 hook·환불 정합성 핵심·외부 검토 권장)

### 사유

**운영 용이성 (기조 1)**:
- 3 PR 분할로 리뷰 단위 적정·롤백 영역 한정 (D-87 패턴 재적용)
- Buyer endpoint 한정으로 D-39 X-Buyer-Id stub 인프라 그대로 활용

**객관 판단 (기조 2)**:
- Track 5 expected-spec §1.2 OOS 명시 5항목 단일 트랙 자연 응집
- Track 8 PR-A·PR-B 박제 후속 자연 진입 식별자 명시
- D-87 Q3 (Admin 별도) 정합·D-16 (OrderStatusResolver) 자연 호출

**과잉문서 회피 (기조 3)**:
- D-88 단건 박제·Track 9 별도 분할 결정 파일 미신설 (D-81·D-87 패턴)
- 가드 2 라벨 본문 표 박제·PR별 별도 결정 미발행
- 재요청 UI/endpoint 신설 회피 (CLM-2 자연 적용)

**과잉개발 회피 (기조 4)**:
- RETURN·EXCHANGE 이연 (Track 11)·Delivery 도메인 동반 차단
- Seller/Admin endpoint 이연 (Track 10)·권한 매트릭스 구현 차단
- 부분환불·EXCHANGE 비환불 경로 등 expected-spec §1.2 잔여 2항목 이연

### 영향 범위
docs/architecture-baseline/decisions.md D-88 1건 추가. 코드·테스트·다른 SoT 영향 0. Track 9 PR-A 정찰 진입 가능 상태 확립.

### 대안 검토
- 대안 1: Q1 α (CANCEL/RETURN/EXCHANGE 3종 일괄) → Delivery 수거·재출고 도메인 동반·과잉개발 (기각)
- 대안 2: Q2 β (Seller 승인 endpoint 본 트랙 포함) → D-87 Q3 Admin 별도 정합 위배·인증 매트릭스 동반 (기각)
- 대안 3: Q3 β (REQUESTED 진입만·완료 전이 후속) → OrderItem 상태 정합 불완전·E2E 검증 불가 (기각)
- 대안 4: Q6 2 PR 압축 (PR-A·PR-B 통합) → PR-B 비대화·결정 라운드 중첩 (기각)
- 대안 5: Q7 α (전건 동기 호출) → Claim → Order Aggregate 직접 갱신·D-01 위배 (기각)
- 대안 6: Q7 β (전건 이벤트) → OrderItem → Order.status 재계산은 동일 트랜잭션 자연 (D-16 동기)·이벤트 분리 과잉 (기각)

### 후속 자연 진입 트랙 (정찰 룰 #4·식별자 명시)
- **Track 10**: Claim Admin API (Seller/Admin 승인·거절 endpoint·권한 매트릭스·Spring Security 진입 후보)
- **Track 11**: Claim RETURN/EXCHANGE 확장 (Delivery 수거·교환 재출고 도메인 동반)
- **Track 12+**: Inventory 차감 핸들러 (OrderPlaced·CANCELLED·RETURNED)·Settlement·NotificationLog 핸들러 등 (Track 8 PR-A 박제 후속)

### 관련 결정
D-01 (Aggregate 외부 ID 참조·이벤트 경유)·D-02·D-03·D-04 (OrderItem·Claim·Order.status 동기화)·D-05 (Claim REJECTED 재요청 = 새 행)·D-16 (OrderStatusResolver Domain Service)·D-23·D-24 (Claim·Refund 상태 전이)·D-29 (save→publish·no flush)·D-39 (X-Buyer-Id stub)·D-75 (이벤트 핸들러 AFTER_COMMIT·REQUIRES_NEW 정책)·D-87 (Track 8 진입·Order Aggregate 3 PR 패턴·가드 2 라벨 표)·Track 5 expected-spec §1.2 (Claim API OOS)·state-machine §2·§3 (Claim·OrderItem 상태)·invariants §2.13 CLM-1~4·§2.13.1 RFN-1~3

### 후속
1. 본 결정 박제 PR (`docs/track-9-entry` → main·신규 채팅 권장)
2. PR-A 정찰 진입 (`docs/track-9/pr-a/recon-report.md`·신규 채팅)
3. PR-B·PR-C 순차 진행 (각 PR별 정찰 → 결정 라운드 → 구현 → 1:1 대조·PR-B 외부 검토 권장)

---

## D-89. Track 9 PR-B 진입 결정 — Claim 요청 API 구현·외부 검토 1·2차 흡수·CLM-5 신설·DomainEvent 폐기·ClaimInvalidStateException 재활용 [ACTIVE]

**결정일**: 2026-06-29
**관련**: Track 9 PR-B / D-01·D-05·D-29·D-30·D-39·D-40·D-41·D-50·D-66·D-69·D-75·D-87·D-88 / state-machine §2·§3 / invariants §2.13 / aggregate-boundary §2.5 / live-traps LT-02 / docs/track-9/pr-b/recon-report.md

### 배경
Track 9 PR-A 완료 (OrderItemStatus.canTransitionTo Claim 진입 매트릭스 5건 신설·머지 41eeb61) 후 PR-B 진입. PR-B = Claim 요청 API 풀패키지 (S급·외부 검토 권장). 정찰 (docs/track-9/pr-b/recon-report.md) 결과 Q1~Q10 결정 의제 도출·외부 검토 1차 9건·2차 4건·자체 실측 12건 흡수 완료. 사용자 4 기조 정합 추천안 전건 채택.

### 결정

#### Q1: OrderItem 사전 검증 + active Claim 차단 = α 사전 검증 + ClaimRepository.existsActiveByOrderItemId
race condition 대응·CLM-5 (활성 Claim 1개) 신설 정합·D-88 Q3 OrderItem.item_status 동기화 양방 정책 보조.

#### Q2: idempotency 키 = α CLM-2 자연 적용
CLM-2 "REJECTED 재요청 = 새 Claim 행" 자체가 멱등 모델·X-Idempotency-Key 헤더 신설 불필요. D-05·D-88 Q4 정합·과설계 회피 (기조 4).

#### Q3: IllegalStateException HTTP·예외명 = 422 + ClaimInvalidStateException 재활용 (Track 5 박제)
- HTTP: 422 (D-50 SoT 정합·Validation 매트릭스 422 분류·409 변경 시 다른 도메인 영향 부담)
- 예외: 기존 ClaimInvalidStateException 재활용 (Track 5 박제·CLM-3 책임·RuntimeException + String message·RefundService.initiate 사용처 정합). 외부 검토 2차 "Conflict→Invalid 권장" 우연 정합. 신설 폐기 (기조 4)
- Javadoc 보강: CLM-3 책임 + canTransitionTo 위반 케이스 추가 명시

#### Q4: endpoint 개수 = α 3개 (POST·GET 단건·GET 목록)
- POST `/api/v1/claims` (Claim 요청)
- GET `/api/v1/claims/{claimPublicId}` (단건 조회)
- GET `/api/v1/claims` (본인 목록·Pageable)

Track 11 RETURN/EXCHANGE 확장 시 type 파라미터 추가로 자연 확장·URL ClaimType 중립.

#### Q5: reason_code 검증 = α @ValidEnum + ClaimReasonCode ENUM 6값 (Track 9 CANCEL 한정)
정찰 실측 9: CLAIM_REASON Code 시드 부재 확정 (V1__init.sql INSERT 0건). ENUM 신설 정당. 값:

- BUYER_CHANGED_MIND·DUPLICATE_ORDER·PAYMENT_ISSUE·ORDER_MISTAKE·STOCK_DELAY·OTHER

Track 11 확장: PRODUCT_DEFECT·DAMAGED_ON_ARRIVAL·WRONG_PRODUCT·DELIVERY_DELAY. Code 테이블 전환은 시드 등록 부담 발생 시점 별도 트랙.

#### Q6: ClaimType 처리 = α DTO 자유 type + Service CANCEL 차단
DTO record 필드 ClaimType 자유 입력·Service 진입부 CANCEL 외 ClaimInvalidStateException throw (422). Track 11 RETURN/EXCHANGE 진입 시 DTO 무영향·Service 차단 단락만 제거.

#### Q7: ClaimResponse 필드 = reasonDetail 노출·requestedBy 미노출
- reasonDetail: 사용자 자유 입력·본인 조회 시 노출 정합 (UX)
- requestedBy: 내부 Long buyerId·외부 노출 시 사용자 식별 누출 (보안·기조 4)

#### Q8: 권한 검증 위치 = α Service 진입부 (OrderItem 2단계 조회)
- D-40 β′ 정합 (관심사 집중)
- 2단계 조회: OrderItemRepository.findOrderIdById(orderItemId) → OrderRepository.findById(orderId) → order.getBuyerId() 비교
- OrderItem.order 필드 @Getter(AccessLevel.NONE) 실측 (recon WARN-2 해소)

#### Q9: LT-02 패턴 (ClaimIntegrationTest) = α SoT 정합 try-finally 명시
ClaimIntegrationTest는 SET FOREIGN_KEY_CHECKS 사용 시 try-finally 명시. CheckoutIntegrationTest는 @Transactional rollback 의존 (정찰 실측 11)·LT-02 미적용 — 별도 트랙 보정 후보 (백로그·라이브 트랩 차단).

#### Q10: ClaimSummaryResponse 신설 = α 신설 (목록 경량)·ClaimResponse 단건 상세
BuyerOrderController OrderSummaryResponse·OrderResponse 패턴 정합. 목록은 publicId·type·status·reasonCode·requestedAt 필드 한정 (페이로드 절감).

### 신규 결정

#### CLM-5 신설 (invariants §2.13 추가)
**Rule**: 동일 OrderItem에 활성 Claim 최대 1개  
**활성 정의**: status ∈ {REQUESTED, APPROVED}  
**Why**: 중복 클레임 차단·운영 일관성·외부 검토 1차 #6·2차 횡단 명시  
**Enforcement Point**: Service (ClaimRepository.existsActiveByOrderItemId 사전 가드)  
**Impact**: 동일 OrderItem 활성 Claim 중복 차단·REJECTED·COMPLETED 후 재요청 자연 허용 (CLM-2 정합)  
**Alternative**: DB partial UK (MariaDB 미지원·기각)

#### DomainEvent 추상 클래스 폐기
정찰 실측 1: OrderPlaced·PaymentCompleted·RefundCompleted 전건 record 패턴·DomainEvent 추상 클래스 부재. ClaimRequested/Approved/Rejected 동일 record 패턴 채택 (occurredAt = LocalDateTime·D-30 QB-13 사실 통지 원칙·D-69 시점 분리 정합). correlationId/eventId/MDC/OpenTelemetry는 Track 12+ Observability 일괄 도입.

#### D-50 매트릭스 별도 트랙 이연
D-50 본문 정정·RFC 7231·Stripe·PayPal 사례 재평가는 Q3 422 결정 안착 후 별도 트랙·본 PR 범위 외.

### 외부 검토 흡수 결과

#### 1차 외부 검토 (9건)
| # | 의견 | 처리 |
|---|---|---|
| 1 | Q1 race condition·existsActiveClaim 보강 | 수용 |
| 2 | Q3 422 → 409 | 반박 (D-50 SoT 정합) |
| 3 | Q5 reason_code @Pattern 약함·ENUM 권장 | 부분 수용 (ENUM 신설·Code 시드 Track 11) |
| 4 | Q7 reasonDetail 노출 권장 | 수용 |
| 5a | 이벤트 payload status·publicId 추가 | 수용 |
| 5b | 이벤트 payload correlationId/eventId | 부분 수용 (Track 12+ Observability 이연) |
| 6 | CLM-5 신설 (활성 Claim 1개) | 수용·CLM-5 신설 명문화 |
| 7 | E2E +8건 | 부분 수용 (필수 E2E 3건·단위 분담 4건·1건 검토) |
| 8 | ClaimPolicy 추출 | 수용 (Track 11 이연 박제) |

#### 2차 외부 검토 (4건)
| § | 의견 | 처리 |
|---|---|---|
| §1 | 422 유지 동의·"Conflict"→"Invalid" 권장 | 수용·실측 후 ClaimInvalidStateException 재활용 확정 |
| §2 | ENUM 값 보정 (PRODUCT_DEFECT·DELIVERY_DELAY = RETURN 영역) | 수용·ORDER_MISTAKE·STOCK_DELAY 교체 |
| §3 | DomainEvent 진입점 권장 | 실측 후속·record 패턴 SoT·추상 클래스 폐기 |
| §4 | E2E 분담 동의·T17 4세부 명문화 | 수용·T17-1~T17-4 박제 |
| 횡단 | CLM-5 활성 정의 모호 | 수용·"활성 = REQUESTED 또는 APPROVED" 명시 |

#### 자체 실측 12건 (recon-report.md §3.3)
실1 DomainEvent record 패턴·실2 LocalDateTime occurredAt·실3 payload 사실 통지 원칙·실4 OrderItemRepository.findOrderIdById·실5 AbstractPublicIdFullAuditableEntity publicId 자동 관리·실6 IllegalArgumentException 400 자동 매핑·실7 IllegalStateException 500 fallback 위험·실8 RefundInvariantViolationException 단순 패턴·실9 CLAIM_REASON 시드 부재·실10 BuyerOrderController 1:1 패턴·실11 CheckoutIntegrationTest LT-02 미적용·실12 ClaimInvalidStateException Track 5 기 박제 (신설 폐기·재활용).

### 사유

**운영 용이성 (기조 1)**:
- 3 endpoint 정합·D-39 X-Buyer-Id stub 인프라 그대로 활용
- ClaimInvalidStateException 재활용으로 GlobalExceptionHandler 매핑 단일화
- LT-02 try-finally 명시로 라이브 트랩 차단

**객관 판단 (기조 2)**:
- 외부 검토 2회·실측 12건 흡수 후 결정·D-50 SoT 정합 유지 (Q3 422)
- DomainEvent 폐기는 SoT 정찰 결과·억측 회피
- CLM-5 정의 "REQUESTED 또는 APPROVED" 명시로 모호성 0

**과잉문서 회피 (기조 3)**:
- D-89 단건 박제·PR-B 별도 결정 파일 미신설
- D-50 본문 정정은 별도 트랙 이연
- ClaimInvalidStateException 신설 폐기 (재활용 우선)

**과잉개발 회피 (기조 4)**:
- correlationId/eventId Track 12+ 이연·DomainEvent 추상 클래스 폐기
- ClaimReasonCode 6값 한정 (Track 11 4값 확장 자연)
- ClaimPolicy 인터페이스 추출 Track 11 이연

### 영향 범위

#### 신규 파일 (12건)
- backend/src/main/java/com/zslab/mall/claim/enums/ClaimReasonCode.java
- backend/src/main/java/com/zslab/mall/claim/controller/BuyerClaimController.java
- backend/src/main/java/com/zslab/mall/claim/controller/request/ClaimRequestRequest.java
- backend/src/main/java/com/zslab/mall/claim/controller/request/ClaimRequestCommand.java
- backend/src/main/java/com/zslab/mall/claim/controller/response/ClaimResponse.java
- backend/src/main/java/com/zslab/mall/claim/controller/response/ClaimSummaryResponse.java
- backend/src/main/java/com/zslab/mall/claim/event/ClaimRequested.java (record)
- backend/src/main/java/com/zslab/mall/claim/event/ClaimApproved.java (record)
- backend/src/main/java/com/zslab/mall/claim/event/ClaimRejected.java (record)
- backend/src/test/java/com/zslab/mall/claim/service/ClaimServiceTest.java
- backend/src/test/java/com/zslab/mall/claim/controller/BuyerClaimControllerTest.java
- backend/src/test/java/com/zslab/mall/claim/integration/ClaimIntegrationTest.java

#### 수정 파일 (6건)
- backend/src/main/java/com/zslab/mall/claim/entity/Claim.java (approve·reject 메서드 신설·transitionTo 내부 ClaimInvalidStateException 변환)
- backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java (request·approve·reject 3 메서드 신설·OrderItem 2단계 조회·existsActiveByOrderItemId 차단·이벤트 발행 D-29)
- backend/src/main/java/com/zslab/mall/claim/repository/ClaimRepository.java (existsActiveByOrderItemId·findAllByRequestedBy 신설)
- backend/src/main/java/com/zslab/mall/order/repository/OrderItemRepository.java (findByPublicId(String publicId) 읽기 메서드 1건 신설·D-64·D-65 Service 진입점 publicId 해소 패턴 정합·스키마/엔티티 무변경)
- backend/src/main/java/com/zslab/mall/claim/exception/ClaimInvalidStateException.java (Javadoc 보강·CLM-3 책임·canTransitionTo 위반 케이스 명시·신설 폐기 사유)
- backend/src/main/java/com/zslab/mall/common/web/GlobalExceptionHandler.java (ClaimNotFoundException 404·ClaimInvalidStateException 422 매핑·CODE 상수 2건)

#### 문서 (2건)
- docs/architecture-baseline/invariants.md §2.13 CLM-5 신설 1행
- docs/track-9/pr-b/recon-report.md §4·§6·§8 갱신 (외부 검토 1·2차 흡수·WARN 해소·진입 조건 [x])

#### 인프라 (1건)
- .gitignore (docs/track-*/handover.md 패턴 추가·미커밋 잔존분 PR-B 동반 커밋)

**총 21건** (신규 12·수정 6 + 인프라 1·문서 2). 코드 영향: Claim 도메인 + Order Aggregate 읽기 메서드 1건(findByPublicId·스키마/엔티티 무변경)·Payment/Refund Aggregate 영향 0건 (PR-B는 OrderItem.item_status 동기화 미포함·PR-C 소관).

### 대안 검토
- 대안 1: Q3 409 Conflict 채택 → D-50 SoT 정합 위배·다른 도메인 영향 부담 (기각)
- 대안 2: InvalidClaimStateException 신설 (Track 5 ClaimInvalidStateException과 공존) → 유사 명칭 2개·과잉개발·기조 4 위배 (기각)
- 대안 3: ClaimReasonCode Code 테이블 전환 본 PR → V1__init.sql INSERT 0건·시드 등록 부담·기조 4 (기각)
- 대안 4: DomainEvent 추상 클래스 신설 → 실측 record 패턴 SoT·억측 도입 (기각)
- 대안 5: CheckoutIntegrationTest LT-02 보정 동반 → PR 범위 광역화·별도 트랙 후보 (기각)
- 대안 6: ClaimPolicy 인터페이스 본 PR 추출 → CANCEL 단일 정책·과잉추상 (기각·Track 11 이연)
- 대안 7: D-89 박제 단독 PR (옵션 a) → 결정 분리 PR 비용·D-87 D-88 PR-A 인라인 패턴 정합 위배 (기각·옵션 b 채택)

### 관련 결정
D-01 (Aggregate 외부 ID 참조)·D-05 (Claim REJECTED 재요청 = 새 행)·D-29 (save→publish·no flush)·D-30 (OrderPlaced 사실 통지·QB-13 record 패턴)·D-39 (X-Buyer-Id stub)·D-40 (컨트롤러 분류·URL 액터 중립)·D-41 (DTO 분리 전략)·D-50 (Validation 매트릭스·422 분류)·D-66 (idempotency 4xx 삭제·5xx 잔류)·D-69 (RefundCompleted Publisher/Consumer 시점 분리)·D-75 (이벤트 핸들러 AFTER_COMMIT·REQUIRES_NEW)·D-87 (Track 8 진입·Order Aggregate 3 PR 패턴)·D-88 (Track 9 진입·Claim 요청 API·CANCEL 한정·Buyer Scope·3 PR 분할)·LT-02 (FK_CHECKS try-finally)·Track 5 expected-spec §1.2·state-machine §2·§3·invariants §2.13 CLM-1~4·§2.13.1 RFN-1~3·aggregate-boundary §2.5·D-64 (신규 주문 unit_price·sellerId 서버 산정)·D-65 (요청 식별자 public_id·CheckoutService 진입점 findByPublicId 해소)·docs/track-9/pr-b/recon-report.md

### 후속 자연 진입 트랙 (정찰 룰 #4 식별자 명시)
- **Track 9 PR-C**: 이벤트 핸들러 (ClaimRequested/Approved/Rejected → OrderItem 동기화·Order.status 재계산 hook via OrderStatusResolver) — S급·D-88 Q3·Q7 정합
- **Track 10**: Claim Admin API (Seller/Admin 승인·거절 endpoint·권한 매트릭스)
- **Track 11**: Claim RETURN/EXCHANGE 확장 (Delivery 수거·재출고 도메인 동반·ClaimReasonCode +4값·ClaimPolicy 인터페이스 추출)
- **Track 12+**: Observability (이벤트 payload correlationId/eventId·MDC·OpenTelemetry 일괄 도입)
- **별도 트랙**: D-50 매트릭스 본문 정정 (RFC 7231·Stripe·PayPal 재평가)·CheckoutIntegrationTest LT-02 보정

### 후속
1. 본 결정 박제 = PR-B 동반 (옵션 b·D-88 Q5 정찰 보정 단락·PR-A 인라인 패턴 정합)
2. PR-B 브랜치 `feat/track-9-pr-b` 생성 (main HEAD 41eeb61 기준)
3. 가드 5 사전 통지 20건 일괄·승인 후 진입
4. TDD 우선 (단위 → 통합 → E2E)·전체 회귀 BUILD SUCCESSFUL
5. GitHub Web UI PR 생성·Base: main·Compare: feat/track-9-pr-b·외부 검토 권장 (S급)

---

## D-90. Track 9 PR-C 진입 결정 — Claim 이벤트 핸들러 풀패키지·외부 검토 흡수·Q3 claim-lock release 재해석 [ACTIVE]

**결정일**: 2026-06-29
**관련**: Track 9 PR-C / D-01·D-05·D-29·D-30·D-69·D-75·D-87·D-88·D-89 / state-machine §2·§3·§5 / invariants §2.13 CLM-1~5 / aggregate-boundary §2.5 / live-traps LT-02 / docs/track-9/pr-c/recon-report.md

### 배경
Track 9 PR-B 완료 (Claim 요청 API 풀패키지·머지 8844a48) 후 PR-C 진입. PR-C = Claim 이벤트 핸들러 풀패키지 (S급·외부 검토 권장·D-88 Q6 분할 마지막 PR). 정찰 (docs/track-9/pr-c/recon-report.md) 결과 Q1~Q5 결정 의제 도출·WARN 4건 박제·외부 검토 1차·2차 흡수 후 기조 4 정합 추천안 채택. 외부 검토 2차 Q3 γ 권장 후 CLM-2 실질 차단 지적 흡수·α-1 (claim-lock release 의미) 최종 재판단.

### 결정

#### Q1: PR-C 핸들러 트랜잭션 정책 = α AFTER_COMMIT + REQUIRES_NEW
- @TransactionalEventListener(phase = AFTER_COMMIT) + @Transactional(propagation = REQUIRES_NEW)
- ClaimRefundCompletedHandler (Track 5·D-69·D-75) 1:1 패턴
- 정찰 A-2 실측: 기존 핸들러 동일 패턴 운영 중·신규 패턴 도입 없음
- 외부 Aggregate 갱신 (Claim → Order)은 이벤트 경유 (D-01 정합)·D-88 Q7 혼합 패턴 외부 갱신부 = AFTER_COMMIT 명시

#### Q2: ClaimApprovedHandler 역할 = γ 핸들러 미신설
- ClaimApproved 이벤트 발행은 유지 (Track 10 Admin endpoint·NotificationLog 미래 소비자용)
- CANCEL형 OrderItem 전이 매트릭스 실측 = PAID/PREPARING → CANCEL_REQUESTED → CANCELLED. APPROVED 시점 추가 전이 없음
- Refund 자동 트리거는 D-87 Q3 (Admin 별도 트랙·Track 10) 이연 정합
- 빈 핸들러 신설은 기조 4 (과잉개발) 위배·외부 검토 2차 일치

#### Q3: ClaimRejectedHandler 원상 복귀 전략 = α-1 CANCEL_REQUESTED → PAID 단일 (claim-lock release 의미)

**의미 재해석 (핵심)**:
본 전이는 **과거 상태 복원이 아니라 claim-lock release** (Claim 재진입 가능 상태 복구) 목적의 최소 전이다. PR-A 박제 Claim 진입 5건과 PR-C 추가 1건은 책임 분리:

| 박제 시점 | 전이 매트릭스 | 목적 |
|---|---|---|
| PR-A (5건) | PAID/PREPARING → CANCEL_REQUESTED·SHIPPING/DELIVERED → RETURN_REQUESTED·DELIVERED → EXCHANGE_REQUESTED | Claim 진입 lock |
| PR-C (1건·본 결정) | CANCEL_REQUESTED → PAID | Claim REJECTED unlock·재요청 허용 |

**PREPARING 복원 미포함 사유**:
- OrderItem.previous_status 필드 부재 (정찰 C-4 실측)
- previous_status 없이 PREPARING 복원은 추정 상태머신·운영 리스크
- PAID 환원 후 판매자는 PAID → PREPARING 정상 재전이 가능 (정찰 A-8 매트릭스 실측·`case PAID -> next == PREPARING || next == CANCEL_REQUESTED`)·운영 흐름 자연 재개

**CLM-2 실질 차단 방지 (Q3 γ 기각 사유)**:
정찰 실측 — ClaimService.request (e) CLM-5 활성 차단 통과 후 (f) `canTransitionTo(CANCEL_REQUESTED)` 검증. CANCEL_REQUESTED 잔류 시 (f) 422 차단·**CLM-2 "REJECTED 재요청 = 새 Claim 행" 실질 봉쇄**. invariant와 런타임 동작 불일치 발생 = 기조 2 (객관성) 위배. γ 이연은 단순 운영 불편이 아닌 invariant 정합 깨짐으로 재분류·기각.

**β (previous_status 필드 신설) 기각**:
DDL V6 마이그레이션·필드·테스트·유지비. PR-C 범위 초과·기조 4 위배.

#### Q4: CANCELLED 종결 전이 처리 = α ClaimCompleted 이벤트 신설 + ClaimCompletedHandler

**흐름**:
ClaimService.markCompleted → ClaimCompleted 이벤트 발행 (D-29 save→publish) → ClaimCompletedHandler (AFTER_COMMIT + REQUIRES_NEW) → OrderItem.changeStatus(CANCELLED) + OrderService.recalculateStatus 호출

**β (ClaimRefundCompletedHandler 확장) 기각**:
- Track 5 환불 정합성 핸들러에 Order Aggregate 의존 추가·관심사 혼합
- 추후 추적·테스트 어려움·CLM-1 정합 위험

**γ (이연) 기각**:
- D-88 Q3 "완료 전이 PR-C 소관" 명시 위배
- OrderItem CANCEL_REQUESTED 영구 잔류·CLM-1 정합 깨짐

ClaimCompleted record payload = ClaimApproved 패턴 1:1: claimId·claimPublicId·orderItemId·claimType·status·occurredAt (D-30 사실 통지 원칙).

#### Q5: PR-C E2E 통합 테스트 전략 = β @SpringBootTest NO @Transactional + TransactionTemplate

Q1 α 채택 종속 (AFTER_COMMIT 핸들러는 @Transactional 테스트에서 commit 미발생·핸들러 미실행). RefundWebhookIntegrationTest 1:1 패턴·LT-02 try-finally 명시 의무.

### 추가 결정

#### LIMITATION 박제 (Q3 α-1 채택 결과)
docs/track-9/pr-c/recon-report.md §1 또는 §9에 1줄 박제:
- Track 9 PR-C는 "Claim 이벤트를 Order에 연결하는 최소 구현 PR"이다.
- OrderItemStatus.CANCEL_REQUESTED → PAID 단일 전이는 claim-lock release (재요청 허용 unlock) 목적이며 과거 상태 복원이 아니다.
- PREPARING 직접 복원은 직전 상태 정보 부재로 미지원·운영 흐름은 PAID → PREPARING 정상 재전이로 자연 재개.

#### invariants.md §2.13 CLM-2 비고 갱신
CLM-2 행 Why 또는 Impact 열에 비고 추가:
"REJECTED 재요청 가능 상태 복구는 OrderItemStatus.CANCEL_REQUESTED → PAID claim-lock release 전이로 보장 (D-90 Q3·Track 9 PR-C)"

#### state-machine.md §3 비고 갱신
OrderItem 12값 표 또는 본문 말미에 1줄 추가:
"예외 전이: CANCEL_REQUESTED → PAID는 ClaimRejected 핸들러 한정·claim-lock release 의미 (D-90 Q3)"

#### OrderItemStatus.java Javadoc 보강 (구현 PR 동반)
canTransitionTo 매트릭스 본문에 비고 추가:
"CANCEL_REQUESTED → PAID: ClaimRejected 한정·claim-lock release (재요청 허용 unlock 목적·D-90 Q3·Track 9 PR-C)"

#### §6 영향 범위 (recon-report.md 갱신 의무)
- 신규: ClaimRequestedHandler·ClaimRejectedHandler·ClaimCompleted (record)·ClaimCompletedHandler·통합 테스트 (ClaimEventIntegrationTest)
- 수정: ClaimService.markCompleted (ClaimCompleted 이벤트 발행 1줄 추가)·OrderItemStatus (CANCEL_REQUESTED → PAID 1줄 추가)·Javadoc·invariants.md·state-machine.md·recon-report.md §1·§6·§7·§9
- 제거 (정찰 §6 예비 목록 대비): ClaimApprovedHandler 미신설·OrderItem.previousItemStatus 필드 미도입

#### §7 WARN 우선순위 (외부 검토자 정합)
- P0: WARN-2 (CANCELLED 종결 전이) → Q4 α 해소·WARN-4 (트랜잭션 정책) → Q1 α 해소
- P1: WARN-1 (CANCEL_REQUESTED → 원상 복귀) → Q3 α-1 해소
- P2: WARN-3 (ClaimApproved Javadoc 긴장) → Q2 γ 해소·Javadoc은 ClaimApproved 발행 자체가 Track 10·NotificationLog 미래 소비자 의도임을 본문 명시 (구현 PR 동반 보강)

### 외부 검토 흡수 결과

#### 1차 외부 검토 (정합성·확장성 우선)
| Q | 1차 의견 | 처리 |
|---|---|---|
| Q1 | α 동의 | 수용 |
| Q2 | β·γ 검토 요청 | γ 채택 (외부 검토 2차 강화) |
| Q3 | α 권장·α-1/α-2 분기 검토 요청 | α-1 채택 (2차 협력 후) |
| Q4 | α 동의 | 수용 |
| Q5 | β 동의 (Q1 α 종속) | 수용 |

#### 2차 외부 검토 (기조 4 엄격 적용)
| Q | 2차 의견 | 처리 |
|---|---|---|
| Q1 | α 유지 (높음) | 수용 |
| Q2 | γ 강하게 동의 (매우 높음) | 수용 |
| Q3 | γ 권장 (중~높음·이연) | **부분 수용 → α-1 재판단** (CLM-2 실질 차단 지적 후 외부 검토자 자체 수정) |
| Q4 | α 유지 (높음) | 수용 |
| Q5 | β 유지 (높음) | 수용 |
| 추가 의제 Q6 (멱등성) | 백로그 이연 (과잉) | 수용·백로그 추가 |

#### Q3 재판단 협력 흐름 (박제 의무)
1. 1차: α 권장 (정합성·확장성 관점)
2. 2차: γ 권장 (기조 4 적용·"상태 복원 엔진 구축 PR 아님")
3. CLM-2 실질 차단 지적: OrderItemStatus.canTransitionTo(CANCEL_REQUESTED) 사전 가드로 새 Claim 생성 차단·invariant 런타임 불일치
4. 최종: α-1 채택·claim-lock release 의미 재해석·"과거 상태 복원이 아닌 재진입 가능 상태 복구"

### 사유 (기조 4 정합)

**기조 1 (운영 용이성)**:
- Q1 α: 신규 패턴 도입 없음·ClaimRefundCompletedHandler 1:1 재사용
- Q2 γ: 빈 핸들러 회피·후속 트랙 진입 시 자연 신설
- Q3 α-1: 상태 저장 필드 없음·매트릭스 1줄 추가
- Q4 α: 이벤트·핸들러 분리·추적 명확
- Q5 β: 단일 표준 통합 테스트 패턴 정합

**기조 2 (객관 판단)**:
- 외부 검토 2회·정찰 실측 12건 + 추가 spot check 4건 흡수 후 결정
- Q3 γ 기각 사유: CLM-2 실질 차단 = invariant 정합 깨짐·운영 불편 차원 아님
- Q3 α-2 기각 사유: previous_status 부재 = 추정 상태머신 거부

**기조 3 (과잉문서 회피)**:
- D-90 단건 박제·PR-C 별도 결정 파일 미신설 (D-87·D-88·D-89 패턴)
- LIMITATION 박제는 recon-report.md §1·§9 1줄 + invariants·state-machine 비고 1줄씩
- OrderItemStatus Javadoc 1줄 보강 (DDL·신규 클래스 신설 회피)

**기조 4 (과잉개발 회피)**:
- Q3 β (previous_status 필드·DDL V6) 명시 기각
- Q2 γ ClaimApprovedHandler 미신설
- Q6 멱등성 의제 백로그 이연
- Track 10 Admin API·Track 11 RETURN/EXCHANGE 자연 이연 유지

### 영향 범위

#### 신규 파일 (5건)
- backend/src/main/java/com/zslab/mall/claim/handler/ClaimRequestedHandler.java (AFTER_COMMIT·REQUIRES_NEW·OrderItem → CANCEL_REQUESTED + recalculateStatus)
- backend/src/main/java/com/zslab/mall/claim/handler/ClaimRejectedHandler.java (AFTER_COMMIT·REQUIRES_NEW·OrderItem → PAID claim-lock release + recalculateStatus)
- backend/src/main/java/com/zslab/mall/claim/event/ClaimCompleted.java (record·D-30 사실 통지)
- backend/src/main/java/com/zslab/mall/claim/handler/ClaimCompletedHandler.java (AFTER_COMMIT·REQUIRES_NEW·OrderItem → CANCELLED + recalculateStatus)
- backend/src/test/java/com/zslab/mall/claim/integration/ClaimEventIntegrationTest.java (NO @Transactional·TransactionTemplate·LT-02 try-finally)

#### 수정 파일 (5건)
- backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java (markCompleted에 ClaimCompleted 이벤트 발행 추가·D-29 save→publish)
- backend/src/main/java/com/zslab/mall/order/enums/OrderItemStatus.java (CANCEL_REQUESTED → PAID 매트릭스 1줄 + Javadoc 1줄 보강)
- docs/architecture-baseline/invariants.md (§2.13 CLM-2 비고 1줄)
- docs/architecture-baseline/state-machine.md (§3 비고 1줄)
- docs/track-9/pr-c/recon-report.md (§1·§6·§7·§9 갱신)

#### 백로그 추가 (2건)
- ClaimApproved Javadoc 보강 (Track 10·NotificationLog 미래 소비자 의도 명시·구현 PR 동반 검토)
- 이벤트 핸들러 멱등성 표준화 (이벤트 저장소·재전달 인프라 도입 시점·Track 12+ Observability)

**총 12건** (신규 5·수정 5·문서 2 추가). 코드 영향: Claim 도메인 + Order Aggregate 읽기 호출 (recalculateStatus). Payment·Refund Aggregate 영향 0.

### 대안 검토

- **Q1 β @EventListener 동기·동일 트랜잭션**: ClaimRefundCompletedHandler 패턴 부정합·신규 패턴 도입·D-01 위배·기각
- **Q2 α RefundService.initiate 자동 트리거**: D-87 Q3 Admin 별도 위배·과잉개발·기각
- **Q2 β OrderItem 동기화만 빈 핸들러**: 빈 책임·기조 4 위배·기각
- **Q3 α-2 CANCEL_REQUESTED → PAID·PREPARING 양방**: previous_status 부재·추정 상태머신·기각
- **Q3 β previous_status 필드 신설**: DDL V6·기조 4 위배·기각
- **Q3 γ 이연**: CLM-2 실질 차단·invariant 런타임 불일치·기조 2 위배·기각
- **Q4 β ClaimRefundCompletedHandler 확장**: 관심사 혼합·Track 5 핸들러 추적 어려움·기각
- **Q4 γ 이연**: D-88 Q3 명시 위배·CLM-1 정합 깨짐·기각
- **Q5 α @Transactional**: AFTER_COMMIT 핸들러 미실행·Q1 α 종속 모순·기각
- **Q6 멱등성 본 PR 도입**: 이벤트 저장소·재전달 인프라 부재·과잉·기각 (백로그)

### 관련 결정
D-01 (Aggregate 외부 ID 참조·이벤트 경유)·D-05 (Claim REJECTED 재요청 = 새 행)·D-16 (OrderStatusResolver Domain Service)·D-29 (save→publish·no flush)·D-30 (사실 통지·record 패턴)·D-69 (RefundCompleted Publisher/Consumer 시점 분리)·D-75 (이벤트 핸들러 AFTER_COMMIT·REQUIRES_NEW)·D-87 (Track 8 진입·Order Aggregate 3 PR 패턴·Q3 Admin 별도)·D-88 (Track 9 진입·CANCEL 한정·3 PR 분할·Q3 OrderItem 동기화 양방·Q6 PR 분할·Q7 혼합 패턴)·D-89 (Track 9 PR-B 구현·CLM-5 신설·ClaimInvalidStateException 재활용)·LT-02 (FK_CHECKS try-finally)·state-machine §2·§3·§5·invariants §2.13 CLM-1~5·aggregate-boundary §2.5·docs/track-9/pr-c/recon-report.md

### 후속 자연 진입 트랙 (정찰 룰 #4·식별자 명시)
- **Track 10**: Claim Admin API (Seller/Admin 승인·거절 endpoint·권한 매트릭스·ClaimApproved Javadoc 보강 동반)
- **Track 11**: Claim RETURN/EXCHANGE 확장 (Delivery 수거·재출고 도메인 동반·OrderItemStatus 추가 매트릭스 신설·ClaimReasonCode +4값)
- **Track 12+**: Observability (이벤트 핸들러 멱등성·이벤트 저장소·correlationId/eventId 일괄 도입)
- **별도 트랙**: D-50 매트릭스 본문 정정·CheckoutIntegrationTest/RefundWebhookIntegrationTest LT-02 보정

### 후속
1. 본 결정 박제 = PR-C 동반 (D-87·D-88·D-89 PR 인라인 패턴 정합)
2. PR-C 브랜치 `feat/track-9-pr-c` 생성 (main HEAD 8844a48 기준)
3. 가드 5 사전 통지 12건 일괄·승인 후 진입
4. TDD 우선 (단위 → 통합 → E2E)·전체 회귀 BUILD SUCCESSFUL
5. GitHub Web UI PR 생성·Base: main·Compare: feat/track-9-pr-c·외부 검토 권장 (S급)
6. PR-C 머지로 Track 9 종결·D-88 Q6 3 PR 분할 완전 해소

---

## D-91. Hibernate 전체 컬럼 UPDATE FK 재검증 트랩 — 통합 테스트 seed FK 부모 그래프 신설 의무 [ACTIVE]

**결정일**: 2026-06-29
**관련**: Track 9 PR-C 구현 시 발견 / LT-02 / live-traps.md

### 배경

Track 9 PR-C ClaimEventIntegrationTest 구현 중 핸들러의 order_item·order UPDATE 시 Hibernate 전체 컬럼 UPDATE가 FK(seller·product·variant·user)를 재검증하여 `SET FOREIGN_KEY_CHECKS=0` seed 환경에서 상위 그래프 부재 시 SQL 1452·AFTER_COMMIT 트랜잭션 롤백이 발생하였다. CI 단위 테스트 미탐지·핸들러가 order_item을 실제 UPDATE하는 첫 통합 케이스라 표면화되었다. RefundWebhookIntegrationTest·ClaimIntegrationTest는 order_item UPDATE 부재로 회피하였던 케이스다.

### 결정

통합 테스트 seed에서 핸들러가 UPDATE 대상 행의 FK 부모 그래프(seller·product·variant·user 등)를 실제 INSERT 의무로 한다. `SET FOREIGN_KEY_CHECKS=0` 우회는 INSERT 차단 회피용이며 후속 UPDATE의 FK 재검증은 우회 불가다.

### 사유 (기조 정합)

- 기조 1 (운영 용이성): 후속 핸들러 트랙(Track 10 Admin·Track 11 RETURN/EXCHANGE) 재발 차단
- 기조 2 (객관): 라이브 표면화·CI 미탐지·실측 검증 완료
- 기조 3 (과잉 문서 회피): 단건 인라인 박제·LT 카탈로그 신설은 ≥3건 누적 시
- 기조 4 (과잉개발 회피): 시스템 변경 없음·문서 1건

### 영향 범위

docs/architecture-baseline/decisions.md D-91 1건 추가. 코드·다른 SoT 영향 0.

### 관련 결정

LT-02 (FK_CHECKS try-finally)·D-82 (LT 카탈로그 promote 원칙)·D-90 (Track 9 PR-C)

### 후속

1. ≥3건 누적 시 live-traps.md LT-04 이관·D-91 `[ARCHIVED]` 라벨
2. 후속 핸들러 트랙 진입 시 정독 의무

---

## D-92. Track 10 Seller Claim endpoint·SellerActorResolver seam·횡단 권한 검증 원칙 [ACTIVE]

**결정일**: 2026-06-30
**관련**: Track 10 / D-39·D-40·D-87 Q1·Q3·D-88 Q2·D-89 Q3·Q7·Q8·D-90 Q2·D-91 / invariants §2.13 CLM-1~5 / state-machine §2 / aggregate-boundary §2.5 / live-traps LT-02 / docs/track-10/recon-report.md

### 배경

Track 10 진입(D-88 Q2 Seller endpoint 후속·D-89 Claim Service 완성 후). Buyer가 요청(REQUESTED)한 Claim을 Seller가 승인/거절하는 endpoint를 신설한다. `ClaimService.approve/reject`는 PR-B에서 Service layer로 완성되었으나 endpoint·Seller 권한 검증은 미신설 상태였다. 1·2차 외부 검토 수렴 후 결정 9건 + 횡단 원칙 1건 + 테스트 책임 경계 1건을 확정한다.

### 결정 (9건)

#### Q1: 권한 인프라 = α′ X-Seller-Id 헤더 stub + SellerActorResolver seam
X-Seller-Id 헤더 stub에 `SellerActorResolver` seam 인터페이스를 더한다. Spring Security 도입 시점까지 임시이며 후속 액터 resolver로 교체 가능하다. 패키지는 `com.zslab.mall.common.auth`(claim 도메인 종속 회피). D-39 X-Buyer-Id stub 패턴 1:1.

#### Q2: PR 분할 = α Seller 단독 1 PR
Seller 단독 1 PR로 분할하고 Admin/Buyer는 분리한다. 액터별 권한 차이 검증 단위를 분리하고 후속 트랙 영향을 격리한다.

#### Q3: Seller 권한 검증 = α Service 진입부 2단계 조회 + sub a‴ wrapper
- α: Service 진입부에서 Claim → OrderItem.sellerId 비교 2단계 조회. 권한 위반 시 404 CLAIM_NOT_FOUND를 재사용한다(정보 노출 회피). 실패 우선순위는 권한 → 상태 → 전이.
- sub a‴: Service wrapper 메서드(approveBySeller·rejectBySeller)를 신설하고 기존 approve/reject primitive는 보존·역할만 재정의한다. actor 비의존 도메인 primitive를 보존하면서 외부 액터 권한을 wrapper에 캡슐화한다.

#### Q4: endpoint URL = β 액터 중립
`/api/v1/claims/{claimPublicId}/approve`·`/reject`. BuyerClaimController와 base path를 공존하며 후속 Admin URL도 재사용한다. D-40 "URL 액터 중립·/api/seller prefix 금지" 정합.

#### Q5: DTO = α ClaimResponse 재사용
ClaimResponse를 재사용한다. OrderItem.publicId 조달은 Controller 재조회 패턴(전이 primitive가 void이므로 전이 후 재조회로 응답 조립).

#### Q6: endpoint 개수 = α 2개 (approve·reject)
승인·거절 2 endpoint만 둔다. Seller publicId 공급 경로는 NotificationLog/운영 콘솔 백로그이며 본 PR 미포함.

#### Q7: ClaimApproved Javadoc·D-50 매트릭스 = γ 부분 동반
ClaimApproved Javadoc 보강은 본 PR 동반, D-50 Validation 매트릭스 정정은 별도 트랙 이연.

#### Q8: Refund 자동 트리거 = β 본 PR 미포함
Refund 자동 트리거는 본 PR에 포함하지 않는다. 운영 절차를 박제한다: REQUESTED → APPROVED(Seller) → REFUND_PENDING → Refund 생성(Admin/Job). Refund Service 트랙 진입 시점에 ClaimApproved → RefundCreated 자동 변환을 구성한다.

#### Q9: 식별자 노출 = α publicId 외부 한정
publicId만 외부 노출 식별자로 둔다. 내부 BIGINT id는 Service 경계 이내로 한정한다.

### 횡단 원칙 (Track 10-B·Track 11 재사용 의무)

> 권한 검증은 Service 진입부 책임으로 둔다. 도메인 상태 전이 메서드는 actor 식별자가 상태 자체에 포함되지 않는 한 actor 비의존 시그니처를 우선한다. 액터별 권한 차이는 wrapper 진입점에서 캡슐화한다.

### WARN-5 테스트 책임 경계

> Track 10 테스트는 endpoint 권한 검증까지만 보장한다. 이벤트 소비 보장은 범위 외다(Refund 자동 트리거 후속 트랙 소관). ClaimIntegrationTest T7·T8의 MockMvc 승격은 권장이며 의무는 아니다. 순수 상태 전이 검증은 approve/reject primitive 직접 호출을 허용한다.

### 외부 검토 흡수 흐름

- 1차 외부 검토: Service 진입부 조회 방식 a → b 변경 권고 수렴
- 2차 외부 검토: b 철회 → a‴ wrapper 패턴 최종. 도메인 primitive 시그니처 보존·테스트 가독성·후속 액터 재사용성

### 영향 범위

- 신규 5: SellerActorResolver·HeaderSellerActorResolver·SellerClaimController·SellerClaimControllerTest·SellerClaimIntegrationTest
- 수정 2: ClaimService(approveBySeller·rejectBySeller·authorizeSellerAccess 신설·approve/reject primitive 시그니처·본문 보존·Javadoc 재정의)·ClaimApproved(Javadoc 보강)
- 무변경: application.yml·build.gradle.kts·Entity·DDL·Flyway·SecurityConfig(미존재)
- 회귀: 전체 331 tests · 0 failures · 0 errors · 신규 12 PASS(Controller 8·Integration 4) · 기존 회귀 0

### 대안 검토 (기각)

- Q1 α(Controller 직접 헤더 파싱·seam 부재): 후속 교체 비용 큼
- Q3 b(도메인 primitive에 sellerId 파라미터 추가): primitive 시그니처 오염·actor 의존. 1차 채택 후 2차 철회
- Q4 α(URL 액터 prefix /seller/...): 후속 Admin URL 분기 비용·D-40 prefix 금지 위배
- Q8 α(본 PR Refund 자동 트리거 동반): Refund Service 진입 전 미성숙

### 관련 결정

D-39(X-Buyer-Id stub·resolveBuyerId 패턴 원천)·D-40(URL 액터 중립·Controller 액터별 분리)·D-87 Q3(Admin 별도 트랙)·D-88 Q2(Seller endpoint Track 10 이연)·D-89 Q3·Q7·Q8(ClaimInvalidStateException 422·requestedBy 미노출·소유권 Service 조회·404 정보 노출 회피)·D-90 Q2(Refund 자동 트리거 Track 10 이연·ClaimApproved 발행 유지)·D-91(통합 테스트 FK 부모 그래프 시드 의무)·LT-02(FK_CHECKS try-finally)·invariants §2.13 CLM-1~5·state-machine §2·aggregate-boundary §2.5·docs/track-10/recon-report.md

### 후속 트랙

- Refund Service 트랙: ClaimApproved → RefundCreated 자동 변환
- NotificationLog 트랙: Seller 승인/거절 알림 source
- D-50 별도 트랙: Validation 매트릭스 정정(Q7 γ 부분 동반 잔여)

---

## D-93. Track 10-B Admin Claim endpoint·AdminActorResolver seam·D-40 admin prefix 본문 실측 [ACTIVE]

**결정일**: 2026-06-30
**관련**: Track 10-B / D-39·D-40·D-87 Q3·D-88 Q2·D-89 Q3·Q7·Q8·D-90 Q2·D-91·D-92 / invariants §2.13 CLM-1~5 / state-machine §2 / aggregate-boundary §2.5 / live-traps LT-02 / docs/track-10/recon-report.md §10~§19

### 배경

Track 10 Seller PR(D-92) 머지 직후 Track 10-B 진입. Admin이 Buyer 요청 Claim을 승인/거절하는 endpoint를 신설한다. D-92 횡단 원칙("권한 검증 = Service 진입부·primitive actor 비의존·액터별 권한은 wrapper 캡슐화")의 **첫 재사용 트랙**이다. 1·2차 외부 검토 수렴 후 결정 11건 + D-40 admin prefix 본문 실측 인용 1건을 확정한다.

### 결정 (11건)

#### Q1: Admin 권한 인프라 = α X-Admin-Id 헤더 stub + AdminActorResolver 신설
D-39 X-Buyer-Id stub·D-92 X-Seller-Id stub 패턴 1:1. 패키지 `com.zslab.mall.common.auth`. Spring Security 도입 시점까지 임시.

#### Q2: AdminActorResolver seam 분리 = α 별도 인터페이스
SellerActorResolver Javadoc "후속 Admin/Buyer 액터 resolver는 본 인터페이스를 공유하지 않고 별도 인터페이스로 분리한다" 실측 정합. generic ActorResolver<T> 추출은 공통 행위가 `Long resolve(HttpServletRequest)` 1건뿐·추상화 이득 부재로 기각. SecurityContext 도입 시 resolver 구현체 제거 또는 adapter 전환 가능.

#### Q3: Admin 권한 모델 = α 전체 접근·stub 단계 한정
RBAC enforcement 미연결(RoleCode 데이터 모델만 존재) 상태. SUPER_ADMIN/ADMIN_OPERATOR 구분 미적용. X-Admin-Role 헤더 도입 반대(헤더 기반 RBAC 제거 비용만 증가). **stub 단계 한정 명시·운영 Admin 개념 승격은 Spring Security 트랙 범위.**

#### Q4: PR 분할 = α Admin 단독 1 PR
D-92 Q2 α(Seller 단독 1 PR) 패턴 직접 재적용. Admin + 목록은 ClaimRepository Admin scope 쿼리 신설 동반·기조 4 위배.

#### Q5: Admin 권한 검증 위치·HTTP = α Service 진입부·Claim 미존재 → 404 단일 처리
Admin은 cross-tenant 개념 없음(전체 접근). 권한 검증 단락 부재·Claim 미존재만 404. GlobalExceptionHandler 무변경·403 미신설. D-89 Q8 정합·D-92 Q3 정보 노출 회피 논리는 Admin scope에서 자연 적용 불요.

#### Q6: endpoint URL 패턴 = γ′ `/api/v1/admin/claims/{claimPublicId}/approve|reject`
**D-40 본문 명시 인용**: 결정 1 "`/api/buyer/...`·`/api/seller/...` prefix 금지"는 buyer·seller 2건 한정. 옵션 비교 표 γ "URL prefix 분리"도 buyer·seller만 박제. **D-40 본문 `/admin` 명시 부재 실측 확정**. 결정 5 "URL prefix 도입(γ)은 Seller API 공개 로드맵 시점에 재평가" — γ 옵션은 buyer·seller만 가리킴. γ′(/admin) 채택은 D-40 본문 직접 위배 아님.

채택 사유(기조 정합):
- 기조 1: SellerClaimController 무변경(Track 10 산출물 8 Controller 테스트·4 Integration 테스트 영향 0건)
- 기조 4: headers 매핑 α는 T2 회귀 위험(헤더 누락 시 404 vs 401)·Swagger 영향 실측 비용 회피
- β′(단일 컨트롤러 + delegate) 기각: D-92 wrapper 패턴과 중복 추상화·관심사 3층 분산

#### Q7: DTO 재사용 = α ClaimResponse 재사용
D-92 Q5 α 패턴 1:1. Controller 재조회 패턴(전이 primitive void·전이 후 재조회 응답 조립).

#### Q8: endpoint 개수 = α 2개 (approve·reject)
D-92 Q6 α 패턴 1:1. Admin 목록은 ClaimRepository 신규 쿼리 동반·범위 확장.

#### Q9: Refund 자동 트리거 = β 본 PR 미포함
D-90 Q2·D-92 Q8 β carry-over. ClaimApproved 소비자 부재 확정·Refund Service 트랙 진입 시점 자동 변환.

#### Q10: ClaimApproved Javadoc·D-50 매트릭스 = γ 부분 동반
ClaimApproved Javadoc 1줄 보강("Track 10-B Admin 진입점 `approveByAdmin` 경유에서도 동일 primitive 발행"). D-50 매트릭스 정정은 별도 트랙 이연(D-92 Q7 γ 패턴 재적용).

#### Q11: SecurityContext 전환 seam 유지 전략 = 이연·백로그 1줄
approveBySeller·approveByAdmin wrapper의 SecurityContext 도입 후 유지 여부(α 유지·β ActorContext 통합·γ Security 계층 이동)는 Spring Security 트랙 진입 시점 결정. 본 PR 결정 박제 가치 부재(기조 4).

### 횡단 원칙 (D-92 재사용 1회차)

D-92 횡단 원칙("권한 검증 = Service 진입부·primitive actor 비의존·액터별 권한은 wrapper 캡슐화") 본 PR 재사용 확인. Admin scope는 권한 검증 단락이 "전체 접근·미존재만 404"로 축소·wrapper 구조는 동일. 후속 Track 11 RETURN/EXCHANGE 재사용 의무 carry-over.

### WARN 해소 결과

| WARN | 해소 |
|---|---|
| WARN-1 라우팅 충돌 | Q6 γ′ 채택·`/api/v1/admin/...` prefix·base path 충돌 회피 |
| WARN-2 AdminActorResolver 미존재 | Q1·Q2 α 신설 |
| WARN-3 403 매핑 부재 | Q5 α 404 단일 처리·GlobalExceptionHandler 무변경 |
| WARN-4 ClaimApproved 소비자 부재 | Q9 β carry-over·Refund Service 트랙 이연 |
| WARN-5 ClaimRepository Admin scope 부재 | Q8 α(2 endpoint) 채택·신설 불요 |

### 외부 검토 흡수 흐름

- 1차: Q1·Q2 α 동의·Q6 β′(단일 컨트롤러 + delegate) 권장·Q3 stub 단계 한정 권장·Q5 표현 수정·Q11 신규 의제 제시
- 2차(기조 4 재평가): β′ 자체 철회(D-92 wrapper와 중복)·γ′ 조건부 1순위(D-40 원문 재확인 조건)·α(headers 매핑) 실측 후 판단으로 하향·Q11 이연·E1 영향 범위 5건 철회·E2 Q10 γ 충돌 해소
- D-40 본문 실측: Claude.ai MCP read·명시 prefix 2건(`/buyer`·`/seller`) 한정·`/admin` 명시 부재 확정 → γ′ 채택 근거 확보

### 영향 범위

#### 신규 파일 (5건)
- backend/src/main/java/com/zslab/mall/common/auth/AdminActorResolver.java
- backend/src/main/java/com/zslab/mall/common/auth/HeaderAdminActorResolver.java
- backend/src/main/java/com/zslab/mall/claim/controller/AdminClaimController.java
- backend/src/test/java/com/zslab/mall/claim/controller/AdminClaimControllerTest.java (6건·T1 승인 200·T2 401·T3 400·T4 미존재 404·T5 상태 422·T6 거부 200)
- backend/src/test/java/com/zslab/mall/claim/integration/AdminClaimIntegrationTest.java (3건·I1 승인 + ClaimApproved 1회·I2 거부 + ClaimRejected 1회·I3 미존재 404·β 패턴·LT-02 try-finally·D-91 FK 부모 그래프 시드)

#### 수정 파일 (2건)
- backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java (approveByAdmin·rejectByAdmin wrapper 신설·primitive approve·reject 시그니처·본문 불변·D-92 횡단 원칙 재사용)
- backend/src/main/java/com/zslab/mall/claim/event/ClaimApproved.java (Javadoc 1줄 보강·Q10 γ)

#### 문서 (1건)
- docs/track-10/recon-report.md §19 결정 라운드 확정 결과 append

#### 무변경 확정
application.yml·build.gradle.kts·Entity·DDL·Flyway·SecurityConfig(미존재)·SellerClaimController(γ′ 채택으로 영향 0)·SellerClaimControllerTest·SellerClaimIntegrationTest·GlobalExceptionHandler(404 단일 처리)·ClaimRepository(2 endpoint 한정·신규 쿼리 0건).

#### 회귀 예상
전체 회귀 BUILD SUCCESSFUL·신규 9 PASS(Controller 6·Integration 3)·기존 회귀 0(D-92 331 → 340 예상).

### 대안 검토 (기각)

- Q1·Q2 β SellerActorResolver seam 재사용: SellerActorResolver Javadoc 명시 분리 지침 위배·기조 2 위배
- Q1·Q2 γ generic ActorResolver<T> 추출: 공통 행위 1건·추상화 이득 부재·기조 4 위배
- Q3 β SUPER_ADMIN/ADMIN_OPERATOR 분리: enforcement 미연결 상태 stub 구현 불가·기조 4 위배
- Q3 X-Admin-Role 헤더 도입: 헤더 기반 RBAC 제거 비용·기조 4 위배
- Q5 403 매핑 신설: Spring Security 동반·기조 4 위배
- Q6 α headers 매핑: T2 회귀 위험·Swagger 영향 실측 비용·SellerClaimController 수정 동반
- Q6 β′ 단일 컨트롤러 + delegate: D-92 wrapper 패턴과 중복 추상화·관심사 3층 분산·외부 검토 2차 자체 철회
- Q8 β/γ 목록 endpoint 포함: ClaimRepository Admin scope 쿼리 신설·D-88 명시 범위 한정 초과

### 관련 결정

D-39(X-Buyer-Id stub·resolveBuyerId 패턴 원천)·D-40(URL 액터 중립·본문 명시 prefix 2건 한정·`/admin` 명시 부재 실측)·D-87 Q3(Admin 별도 트랙·~70% Service 재사용)·D-88 Q2(Seller/Admin endpoint Track 10 이연)·D-89 Q3·Q7·Q8(ClaimInvalidStateException 422·requestedBy 미노출·소유권 Service 조회·404 정보 노출 회피)·D-90 Q2(Refund 자동 트리거 Track 10 이연·ClaimApproved 발행 유지)·D-91(통합 테스트 FK 부모 그래프 시드 의무)·D-92(횡단 원칙·wrapper 캡슐화·Track 10-B 재사용 의무·SellerActorResolver Javadoc 명시 분리)·LT-02(FK_CHECKS try-finally)·invariants §2.13 CLM-1~5·state-machine §2·aggregate-boundary §2.5·docs/track-10/recon-report.md §10~§19

### 후속 트랙

- **Refund Service 트랙**: ClaimApproved → RefundCreated 자동 변환·CLM-3
- **NotificationLog 트랙**: Seller/Admin 승인/거절 알림 source
- **Track 11**: Claim RETURN/EXCHANGE 확장·D-92 횡단 원칙 재사용 2회차
- **Spring Security 트랙**: SecurityFilterChain·UserDetailsService·auth RBAC enforcement·X-*-Id stub 3종 일괄 대체·Q11 wrapper 유지 전략 결정
- **별도 트랙**: D-50 Validation 매트릭스 정정

### 후속

1. 본 결정 박제 = PR-B 동반 (D-87·D-88·D-89·D-90·D-92 PR 인라인 패턴 정합)
2. PR-B 브랜치 `feat/track-10-pr-b` 생성 (main HEAD 1b264f3 기준)
3. TDD 우선(단위 → 통합 → E2E)·전체 회귀 BUILD SUCCESSFUL
4. GitHub Web UI PR 생성·Base: main·Compare: feat/track-10-pr-b

---

---

## D-94. Track 11 Refund Service · ClaimApproved → Refund 자동 트리거 · Track 식별자 재정의 [ACTIVE]

**결정일**: 2026-06-30
**관련**: Track 11 / D-29·D-66·D-67·D-69·D-72·D-73·D-75·D-87 Q3·D-90 Q2·D-92 Q8·D-93 Q9 / invariants §2.11 PAY-1·§2.13 CLM-3·§2.13.1 RFN-1~3 / state-machine §2·§8 / aggregate-boundary §2.5 / live-traps LT-02 / docs/track-11/recon-report.md §1~§10

### 배경

D-87 Q3 → D-90 Q2 → D-92 Q8 → D-93 Q9에 걸쳐 **4회 연속 carry-over**된 "ClaimApproved → Refund 자동 변환" 의제를 본 트랙에서 종결한다. 정찰 결과(recon-report §1.2) Refund 도메인은 Track 5(D-67~D-77)에서 사실상 완성되어 있다. `RefundService.initiate`가 CLM-3·PAY-1 사전·PG 동기 호출·D-67 FAILED 전이까지 기구현이며 역방향 루프(`RefundCompleted → Claim COMPLETED·Payment CANCELLED`)도 통합 테스트 통과 완료다. 본 트랙의 실 결손은 **`ClaimApproved` 이벤트를 소비해 `initiate`를 호출하는 forward 트리거 핸들러 1건**으로 협소하다.

1·2차 외부 검토 수렴 후 결정 11건 + 신규 WARN 1건 + 트랙 식별자 재정의 1건을 확정한다.

### 결정 (11건)

#### Q0: 트랙 식별자 재정의 = β Track 11 = Refund Service

D-88·D-89·D-90·D-93 후속 목록에 "Track 11 = Claim RETURN/EXCHANGE 확장"으로 일관 박제되어 있었으나, 본 트랙 진입 시점에 사용자 결정으로 **Track 11 = Refund Service**로 재정의한다. 기존 RETURN/EXCHANGE 라벨은 **Track 12+로 자연 이동**한다.

사유:
- 트랙 번호는 진입 순서 라벨이며 도메인 영구 바인딩이 아니다.
- 후속 결정 D-XX·PR명·branch명·코드 인용 식별자 일관성 확보.
- 미번호 트랙 유지 시 추후 추적 식별자 부재로 탐색 비용 증가.

후속 목록 정정 의무:
- D-88·D-89·D-90·D-93의 "Track 11 = RETURN/EXCHANGE" 표기는 본 결정 박제 시점부터 "Track 12+"로 자연 이동. 기존 결정 본문 정정은 수행하지 않는다(과잉문서 회피·D-94가 재정의 박제로 후속 정정 효과).

#### Q1: ClaimApproved 핸들러 위치 = α `refund/handler/ClaimApprovedHandler`

신규 패키지 `com.zslab.mall.refund.handler`에 `ClaimApprovedHandler`를 신설한다. RefundCompleted 소비자가 `claim/handler`·`payment/handler`에 반응 도메인별 분산되어 있는 패턴(recon §4.3·§5.2)과 1:1 대칭이다. 반응 도메인(Refund) 패키지 배치로 Aggregate 경계 정합 유지·`claim` 패키지에 `refund` 의존 역유입 회피.

#### Q2: RefundCreated 이벤트 미신설 = α

`RefundCreated` 이벤트를 신설하지 않는다. 핸들러가 `RefundService.initiate(claimId, amount)`를 직접 호출하며, `initiate`는 동기 PG 호출까지 자기완결이다(recon §3.1). D-93 후속 목록의 "ClaimApproved → RefundCreated 자동 변환" 표현은 변환 행위 서술이며 이벤트 신설 명령이 아니다. 향후 NotificationLog/Observability 소비자가 발생할 시점에 추가 검토.

#### Q3: Refund 진입점 범위 = α 자동 트리거 단독

ClaimApproved 자동 트리거를 단독 진입점으로 둔다. Admin 수동 환불 생성 endpoint는 본 트랙에 병존시키지 않는다.

사유:
- state-machine §8 "운영자 수동 보정 권한 없음·후속 트랙 D안 RefundAdjustment" 정합.
- D-92 Q8 "Refund 생성(Admin/Job)" 언급은 운영 절차 박제이며 endpoint 신설 명령이 아니다.
- 수동 endpoint는 사실상 RefundAdjustment 트랙 선행 구현·범위 확장.

#### Q4: PG refund 호출 시점 = 기결정 유지

`RefundService.initiate` 내부 동기 PG 호출·webhook 비동기 확정 구조(D-72·D-73·기구현)를 그대로 상속한다. 자동 트리거 핸들러는 `initiate` 호출로 본 구조에 자연 진입한다. 신규 결정 사항 없음.

#### Q5: RefundStatus 상태머신 = 기결정 유지

3값(PENDING·COMPLETED·FAILED)·CANCELLED 없음·DDL ENUM·enum·state-machine §8 3중 정합·RefundStatusTest 3×3 커버 완료(recon §6.3·§7). 누락값·신규 상태 도입 사유 없음. 부분환불 신상태는 Track 5 §1.2 OOS·별도 트랙 소관.

#### Q6: 멱등성 = α′ Service 내부 게이트·lock 미도입

`RefundService.initiate` 진입부에 멱등 게이트를 신설한다. 동일 트랜잭션 내 `refundRepository.existsActiveByClaimId(claimId)` SELECT 후 PENDING/COMPLETED 존재 시 no-op 또는 `RefundIdempotentSkipException` 시그널. **claim row pessimistic lock·DB UNIQUE(claim_id)는 도입하지 않는다.**

사유:
- 현재 인프라는 Spring ApplicationEvent **인메모리 publisher**(D-29·D-75). 외부 MQ·이벤트 저장소·Outbox·replay 부재. 동일 ClaimApproved의 동시 재전달은 현 인프라에서 자연 발생 불가.
- 현실적 위험 원천 = 운영 재실행·테스트 중복·동일 승인 경로 중복 진입·미래 인프라 교체. existsActiveByClaimId 게이트로 대부분 차단.
- pessimistic lock = Claim Aggregate 락 경합 비용 + 기조 4 위배 위험.
- DB UNIQUE(claim_id) = RFN-2 "재시도 = 새 행" 의도와 구조 충돌·기각.

**박제 1줄**: "현재 멱등 보호는 단일 프로세스 ApplicationEvent 가정 기반. 외부 이벤트 브로커·Outbox·다중 인스턴스 수평 확장·@Async EventListener 도입 시 본 게이트 재검토 의무." (Track 12+ Observability 트랙·이벤트 핸들러 멱등성 표준화와 통합 시점에 자연 재평가·D-90 백로그 정합.)

신규 메서드: `RefundRepository.existsActiveByClaimId(Long claimId)` (status IN (PENDING, COMPLETED)).

#### Q7: 환불 amount 산정 출처 = α `OrderItem.totalPrice`

자동 트리거 핸들러는 `ClaimApproved.orderItemId`로 `OrderItem`을 조회하고 `OrderItem.totalPrice`를 `initiate(claimId, amount)` 인자로 전달한다.

실측 조건 충족(recon §8 Q7·검토자 3 조건):
1. CANCEL Claim = OrderItem 단위 1:1(Claim.order_item_id FK 단일·Track 5 spec §2.2·invariants CLM-3).
2. 부분환불 본 트랙 한정 OOS(Track 5 spec §1.2 "amount 단일 행 기준"·PAY-2 비고 "부분환불 도입 시 재정의 가능").
3. 배송비 구조 분리(OrderItem.totalPrice = unit_price × quantity·ORD-5·배송비 필드 OrderItem 외부).

**박제 1줄**: "Track 11 자동 환불 amount는 OrderItem.totalPrice 기준이며 배송비 환불 정책은 본 범위 외. 부분환불·배송비 환불 도입 시 본 규칙 재정의." (WARN-10 흡수)

ClaimApproved payload 수정 없음·발행처 ClaimService 수정 없음·DDL 무변경.

#### Q8: FAILED 보상·운영 가시성 = α structured log만·Micrometer 보류

자동 트리거 Refund FAILED 시 Claim 상태 환원 없음·재시도 허용(운영자/Job 재 initiate·RFN-2). CLM-3 정합(FAILED는 승인 취소가 아님).

운영 가시성 = `ClaimApprovedHandler.handle` catch 블록에 structured log 1줄만 추가:

    log.warn("Refund auto-trigger failed; claim={} refund_state=FAILED action=manual_retry_required",
             claimPublicId, exception);

**Micrometer 카운터·dashboard·alert 파이프는 본 트랙 미도입.** 현 프로젝트에 Observability 표준 박제 부재(D-90 백로그). 카운터 1개만 신설 시 후속 컨벤션 재정의 부담·기조 4 위배. NotificationLog 신설은 검토자 양차 기각·structured log + 향후 Observability 트랙 자연 흡수가 적정.

#### Q9: RefundWebhookIntegrationTest LT-02 보정 동반 = α

Track 5에 잔존하는 LT-02 미적용분(try-finally `=1` 복원 부재·recon §7.1)을 본 트랙에서 동반 보정한다. 신규 통합 테스트 추가에 따른 보정 비용 거의 0·라이브 트랩 차단·기조 1 정합.

#### Q10: PR 분할 전략 = α 단일 PR

본 트랙은 단일 PR(`feat/track-11-refund-auto-trigger`)로 구성한다. D-87·D-92·D-93 1-PR-단독 패턴 정합. 잔여 범위(핸들러 1·existsActiveByClaimId·structured log·테스트·D-94·Q9 LT-02 보정·Javadoc) 협소·분할 시 문서·PR·검증 비용 역증.

### 횡단 원칙 (D-92·D-93 carry-over)

D-92·D-93 횡단 원칙("권한 검증은 Service 진입부 책임·primitive actor 비의존·액터별 권한은 wrapper 캡슐화")은 본 트랙(이벤트 핸들러 트랙)에 직접 적용 대상 아니다. 단 `RefundService.initiate`가 actor 비의존 시그니처(`initiate(Long claimId, long amount)`)를 유지하는 점은 본 원칙 정합. 본 트랙은 횡단 원칙 재사용 회차에 산입하지 않는다(Track 12+ 액터 트랙 진입 시 재사용 카운트 재개).

### WARN 해소 결과 (recon §9)

| WARN | 해소 |
|---|---|
| WARN-1 트랙 식별자 충돌 | Q0 β 채택·Track 11 = Refund Service 재정의·후속 목록 자연 이동 |
| WARN-2 amount 출처 미정 | Q7 α 채택·OrderItem.totalPrice·실측 조건 3건 충족 |
| WARN-3 멱등성 공백 | Q6 α′ 채택·Service 내부 existsActiveByClaimId 게이트 |
| WARN-4 FAILED 보상 부재 | Q8 α 채택·structured log·재시도 허용·Claim 환원 없음 |
| WARN-5 LT-02 잔존 | Q9 α 채택·본 트랙 동반 보정 |
| WARN-6 신규 통합 테스트 D-91/LT-02 의무 | ClaimEventIntegrationTest 패턴 준용·FK 부모 그래프 시드·try-finally 복원 |
| WARN-7 CLM-4 재승인 차단 | Q6 가드와 자연 통합·이벤트 재전달 위험만 잔존·Q6 박제 1줄 흡수 |
| WARN-8 이벤트 순서 역전 내성 | Q6 게이트로 자연 흡수·COMPLETED 상태 시 no-op |
| WARN-9 재시도 운영 진입점 부재 | 본 결정 §후속 1줄 박제(운영자 수동 재 initiate·Job 도입은 후속 트랙) |
| WARN-10 배송비 환불 정책 명문 박제 부재 | Q7 박제 1줄 흡수(invariants 신규 항목 미신설·정책 결정·미래 변경 가능) |

### 외부 검토 흡수 흐름

- **1차 외부 검토**: 11 의제 + 추천안 송부. 수용: Q0 β·Q1 α·Q2 α·Q3 α·Q4·Q5 유지·Q9 α·Q10 α·횡단 1~3·신규 WARN-8·WARN-9 제시. 부분 수용 요청: Q6(pessimistic lock 또는 Service 내부 멱등 보호)·Q7(조건 3건 확인)·Q8(structured log + 카운터).
- **Claude.ai 축소 흡수**: Q6 α′(lock 미도입·existsActiveByClaimId 게이트만)·Q7 α(실측 조건 3건 확정·WARN-10 도출)·Q8 α(structured log만·Micrometer 보류).
- **2차 외부 검토**: 축소안 전건 정합 확인. Q6 lock 철회 유지 동의(인메모리 publisher 가정 하 동시성 위험 모델 제한)·Q7 α 확정(payload 계약 유지)·Q8 structured log 메시지 구조화 강화(`claim={} refund_state=FAILED action=manual_retry_required`)·WARN-10 α(D-94 본문 1줄 박제·invariants 신규 항목 금지).
- **2차 검토 자체 평가**: "축소안이 1차보다 오히려 4기조 정합성이 좋아졌다·Q6에서 일반론적 락을 현재 인프라 현실로 되돌린 점 방향 정합."

### 영향 범위

#### 신규 파일 (3건)
- backend/src/main/java/com/zslab/mall/refund/handler/ClaimApprovedHandler.java (신규 패키지·@TransactionalEventListener AFTER_COMMIT·REQUIRES_NEW·CANCEL 게이트·structured log catch)
- backend/src/test/java/com/zslab/mall/refund/handler/ClaimApprovedHandlerTest.java (단위·Mockito·정상 트리거·CANCEL 외 type 스킵·existsActive 스킵·PG 실패 시 log.warn)
- backend/src/test/java/com/zslab/mall/refund/integration/RefundAutoTriggerIntegrationTest.java (e2e·NO @Transactional·TransactionTemplate·LT-02 try-finally·D-91 FK 부모 그래프 시드·ClaimApproved 발행→Refund PENDING→webhook→Claim COMPLETED·Payment CANCELLED 전체 루프)

#### 수정 파일 (4건)
- backend/src/main/java/com/zslab/mall/refund/service/RefundService.java (initiate 진입부 existsActiveByClaimId 게이트 추가·RefundIdempotentSkipException 또는 no-op·Javadoc 1줄)
- backend/src/main/java/com/zslab/mall/refund/repository/RefundRepository.java (existsActiveByClaimId 신규 메서드·status IN (PENDING, COMPLETED))
- backend/src/main/java/com/zslab/mall/claim/event/ClaimApproved.java (Javadoc 보강·"소비자 부재" → "Track 11 refund/handler/ClaimApprovedHandler 소비")
- backend/src/test/java/com/zslab/mall/refund/controller/RefundWebhookIntegrationTest.java (Q9 LT-02 보정·seed/cleanup `SET FOREIGN_KEY_CHECKS=0` 사용부에 try-finally `=1` 복원)

#### 무변경 확정
application.yml·build.gradle.kts·DDL/Flyway(amount=OrderItem.totalPrice·스키마 무변경)·ClaimApproved record payload(amount 필드 미추가)·ClaimService(발행부 무변경)·RefundService.markCompleted/markFailed·Refund Entity·RefundStatus·역방향 루프 핸들러 전건(ClaimRefundCompletedHandler·PaymentRefundCompletedHandler·ClaimCompletedHandler)·PaymentGateway·invariants.md(RFN-4 신규 항목 미신설)·state-machine.md.

#### 회귀 예상
전체 회귀 BUILD SUCCESSFUL·신규 PASS(단위 ≥4·통합 ≥1)·기존 회귀 0(D-93 345 baseline 유지·신규 합산 ≥350).

### 대안 검토 (기각)

- Q0 α(미번호 트랙 유지): 후속 추적 식별자 부재·탐색 비용 증가·기각.
- Q1 β(`claim/handler/ClaimApprovedHandler`): Refund 의존 claim 패키지 역유입·Aggregate 경계 흐림·기각.
- Q1 γ(RefundService @TransactionalEventListener 직접 보유): Service에 이벤트 소비 책임 혼입·기존 패턴 위배·기각.
- Q2 β(RefundCreated 신설): 현재 소비자 부재·NotificationLog/Observability 연쇄 요구·기조 4 위배·기각.
- Q3 β(Admin 수동 endpoint 병존): state-machine §8 "운영자 수동 보정 권한 없음" 위배·RefundAdjustment 트랙 선행 구현·기각.
- Q6 lock 도입(claim row pessimistic lock·DB UNIQUE(claim_id)): 인메모리 publisher 가정 하 동시성 위험 제한·RFN-2 충돌·기조 4 위배·기각.
- Q7 β(ClaimApproved payload amount 추가): 이벤트 record·발행처 ClaimService 수정 동반·D-30 "사실 통지" 원칙 위배·기각.
- Q7 γ(Payment.amount 전액): 멀티 OrderItem 주문 과환불·PAY-1 위반·기각.
- Q8 Micrometer 카운터 본 PR 도입: Observability 표준 박제 부재·후속 컨벤션 재정의 부담·기조 4 위배·보류(Track 12+ Observability 트랙 동반).
- Q8 RefundFailed 이벤트 신설 + Claim 보상 핸들러: 범위 확장·Q2 β 동반·과잉개발·기각.
- Q10 분할: 범위 협소 대비 과분할·기조 3 위배·기각.
- WARN-10 γ(invariants 신규 RFN-4): 구조적 불변 아닌 정책 결정·미래 변경 가능성 큼·기각.

### 관련 결정

D-29(save→publish·no flush)·D-66(idempotency 기조)·D-67(Refund FAILED 전이·PG 호출 예외)·D-69(RefundCompleted Publisher/Consumer 시점 분리)·D-72(initiate 단일 트랜잭션)·D-73(PG 호출 실패 = 비즈니스 실패)·D-75(이벤트 핸들러 AFTER_COMMIT·REQUIRES_NEW)·D-87 Q3(Refund 자동 트리거 원천 이연)·D-90 Q2(ClaimApprovedHandler 미신설·Refund 자동 트리거 D-87 Q3 정합)·D-91(통합 테스트 FK 부모 그래프 시드 의무)·D-92 Q8(Refund 자동 트리거 Refund Service 트랙 이연)·D-93 Q9(ClaimApproved 소비자 부재 확정·Refund Service 트랙 자동 변환)·LT-02(FK_CHECKS try-finally)·invariants §2.11 PAY-1·§2.13 CLM-3·§2.13.1 RFN-1~3·state-machine §2·§8·aggregate-boundary §2.5·docs/track-11/recon-report.md.

### 후속 트랙

- **Track 12+ Observability**: 이벤트 핸들러 멱등성 표준화·이벤트 저장소·Outbox·correlationId/eventId·Micrometer 컨벤션·Q6 박제 1줄("외부 이벤트 인프라 도입 시 본 게이트 재검토") 자연 재평가.
- **Track 12+ RETURN/EXCHANGE**: D-88·D-89·D-90·D-93 후속 목록의 "RETURN/EXCHANGE" 라벨 자연 이동 진입·D-92 횡단 원칙 재사용 2회차·Delivery 도메인 동반·ClaimReasonCode +4값.
- **RefundAdjustment 트랙**: state-machine §8 "운영자 수동 보정·D안" 진입 시점.
- **NotificationLog 트랙**: Refund FAILED 운영 알림 source·structured log → 알림 채널 자연 흡수.
- **부분환불·배송비 환불 정책 트랙**: Q7 박제 1줄 재정의 진입점·invariants RFN-4 또는 PAY-2 본문 재정의.
- **Spring Security 트랙**: X-*-Id stub 3종 일괄 대체·Q11(D-93) wrapper 유지 전략 결정.

### 후속

1. 본 결정 박제 = PR 동반 (D-87·D-88·D-89·D-90·D-92·D-93 PR 인라인 패턴 정합).
2. PR 브랜치 `feat/track-11-refund-auto-trigger` 생성 (main HEAD d8f8cb0 기준).
3. 가드 5 사전 통지: 신규 3·수정 4·문서 1(본 D-94 박제는 사용자 직접 처리) — Claude Code 구현 프롬프트 시점 일괄 통지.
4. TDD 우선(단위 → 통합)·전체 회귀 BUILD SUCCESSFUL·신규 ≥5 PASS·기존 회귀 0.
5. GitHub Web UI PR 생성·Base: main·Compare: feat/track-11-refund-auto-trigger·외부 검토 권장(S급·CLM-3 정합 핵심·본 결정으로 4연속 carry-over 종결).
6. PR 머지로 Track 11 종결·ClaimApproved 소비자 부재 carry-over 영구 해소.

---

## D-95. Track 12 NotificationLog · 이벤트 → NotificationLog 적재 표준 박제 · ClaimApproved 4번째 배선 · α′-2 fallback skip 정책 [ACTIVE]

**결정일**: 2026-06-30
**관련**: Track 12 / D-01·D-18·D-29·D-30·D-69·D-74·D-75·D-86 Q3·D-87·D-90·D-92·D-94 / invariants §3.1 NOT-1~3 / state-machine.md (NotificationLog 항목 부재·D-18 정합) / aggregate-boundary §2.7 / domain-events §1·§2 (E1·E2·E9 박제) / live-traps LT-02 / docs/track-12/recon-report.md §1~§10

### 배경

D-86 §후속 (Track 7 Batch-3c·"Track 8+ Application Service 진입 시 NotificationLog.status PENDING→SENT 전이 핸들러·E1·E2·E4·E5·E9·E10 이벤트 소비") → D-90 §후속 (Track 12+ Observability·이벤트 핸들러 멱등성) → D-94 Q8 (NotificationLog 신설 보류·structured log + 향후 Observability 트랙 자연 흡수) → D-94 §후속 (NotificationLog 트랙·Refund FAILED 운영 알림 source)에 걸쳐 **3회 연속 carry-over**된 "NotificationLog 적재 표준" 의제를 본 트랙에서 종결한다.

정찰 결과 (recon-report §1.2) NotificationLog 도메인은 Track 7 Batch-3c (D-86)에서 영속 계층(Entity·NotificationChannel·NotificationLogStatus·Repository·Test)까지만 선구현·**Service·Handler·Controller·도메인 이벤트는 0건**·`NotificationLog.create()` 호출처는 테스트뿐(production 인스턴스화 0건). 본 트랙의 실 결손은 **발행처 존재 이벤트(E1·E2·E9 + ClaimApproved)를 소비해 NotificationLog를 PENDING 적재하는 핸들러·NotificationService 신설**로 협소하다.

domain-events §2 박제 6 이벤트(E1·E2·E4·E5·E9·E10) 중 **E4 DeliveryStarted·E5 DeliveryCompleted·E10 InventoryAdjusted는 이벤트 record 자체 부재**(recon §3.5)·발행 도메인(Delivery·Inventory) 신설 선행 필요·본 트랙 OUT-OF-SCOPE. ClaimApproved는 domain-events §2 NotificationLog 소비 박제 외이나 Javadoc("NotificationLog 진입 시 Seller 승인 알림 source 추가 소비 가능") 박제 흡수.

1·2차 외부 검토 수렴 후 결정 11건 + 신규 WARN 처치 2건 + 트랙 식별자 확정 1건을 박제한다.

### 결정 (11건)

#### Q1: 트랙 식별자 = α Track 12 = NotificationLog 확정

기존 라벨 충돌 해소:
- D-90 §후속 "Track 12+ Observability" → **Track 13+로 자연 이동**
- D-94 §후속 "Track 12+ RETURN/EXCHANGE" → **Track 13+로 자연 이동**
- D-94 §후속 "NotificationLog 트랙(미번호)" → 본 결정으로 Track 12 확정

사유:
- D-94 Q0 β 선례 직접 재적용 — 트랙 번호는 진입 순서 라벨·도메인 영구 바인딩 아님
- "미번호 트랙" 유지 시 D-94 Q0 α 기각 사유(추적 식별자 부재·탐색 비용 증가) 재현
- 후속 결정 D-XX·PR명·branch명·코드 인용 식별자 일관성 확보

후속 목록 정정 의무: D-90·D-94의 "Track 12+" 표기는 본 결정 박제 시점부터 "Track 13+"로 자연 이동. 기존 결정 본문 정정은 수행하지 않는다(과잉문서 회피·D-94 Q0 β 패턴 1:1).

#### Q2: 핸들러 위치 = α `notification/handler/` 신규 패키지

신규 패키지 `com.zslab.mall.notification.handler`에 4 핸들러를 신설한다. D-94 Q1 α 패턴 1:1 — 반응 도메인(Notification) 패키지 배치·발행 도메인 패키지 분산 회피·notification 의존 역유입 차단.

#### Q3: 트랜잭션 정책 = α `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`

D-69·D-75·D-94 Q1 표준 패턴 1:1 적용. ClaimRefundCompletedHandler·ClaimApprovedHandler(refund) 패턴 정합. domain-events §1·§2 박제 "알림 = 비동기" 정합·원 흐름 비차단·실패 격리.

E2 PaymentCompleted 동기 소비(payment/OrderEventHandler·@EventListener) + 비동기 알림 핸들러 공존: AFTER_COMMIT은 동기 핸들러 커밋 후 실행 = 자연 분리·통합 테스트로 실측 의무.

#### Q4: 배선 대상 이벤트 = α′-2 (4건·산정 실패 시 skip + structured log)

본 트랙 배선 대상:
- **E1 OrderPlaced** (recipient = Buyer·templateCode = TPL_ORDER_PLACED)
- **E2 PaymentCompleted** (recipient = Buyer·templateCode = TPL_PAYMENT_COMPLETED)
- **E9 ClaimCompleted** (recipient = Buyer·templateCode = TPL_CLAIM_COMPLETED)
- **ClaimApproved** (recipient = Buyer·templateCode = TPL_CLAIM_APPROVED·ClaimApproved.java Javadoc 박제 흡수)

**범위 한정 규칙 (외부 검토 2차 "범위는 좁게" 흡수)**:
- ClaimApproved는 본 트랙 4번째 배선 대상에 포함·단 산정 실패(recipient·title·content·templateCode 중 어느 하나라도 산정 불가) 시 **skip + structured log**·NULL 적재 회피·NotificationLog 미적재 (A1-α 정합)

OUT-OF-SCOPE (재확인):
- E4 DeliveryStarted·E5 DeliveryCompleted (Delivery 도메인 행위 미구현·이벤트 record 부재)
- E10 InventoryAdjusted (Inventory 도메인 행위 미구현·domain-events "선택" 박제)
- E3 PaymentFailed (domain-events §2 NotificationLog 소비 박제 외)
- E7 ClaimRequested·E8 ClaimRejected (domain-events §2 NotificationLog 소비 박제 외·운영 알림 수요 발생 시 별도 트랙)
- RefundCompleted (역방향 루프 핸들러 전용·domain-events §2 NotificationLog 소비 박제 외)

후속 트랙: Delivery 도메인·Inventory 도메인 신설 트랙 진입 시 E4·E5·E10 발행처 + NotificationLog 핸들러 동반 신설.

#### Q5: 적재 인자 산정 출처 = α NotificationService 재조회 + templateCode 정적 매핑

신규 `notification/service/NotificationService.java`·재조회 기반 적재 오케스트레이션:

- recipient 산정: 이벤트 식별자 → 재조회 (orderId·orderItemId·claimId → Buyer ID)
- title/content 산정: 재조회 데이터 + templateCode 정적 매핑으로 조립
- templateCode: `notification/template/NotificationTemplateCodes.java` 상수 클래스 (WARN-10-α·단일 위치 응집)
- target_type/target_id: 이벤트 식별자 직접 사용·PolymorphicTargetType (ORDER·PAYMENT·CLAIM 등) 매핑

D-30 "사실 통지" 원칙 정합 — 이벤트 payload 무수정·소비측 재조회·발행처 무영향. D-94 Q7 β 기각 패턴 정합 (payload 확장 회피).

#### Q6: 멱등성 = α 가드 미도입·박제 1줄

본 트랙 멱등 가드 미도입. append-only ARCHIVE 특성 수용·현 인프라 인메모리 ApplicationEvent publisher(D-29·D-75·D-94 Q6 박제) 가정 하 재전달 자연 발생 불가.

**박제 1줄**: "Track 12 NotificationLog 적재 멱등 보호는 단일 프로세스 ApplicationEvent 가정 기반·외부 이벤트 브로커·Outbox·다중 인스턴스 수평 확장·@Async EventListener 도입 시 본 게이트 재검토 의무." (Track 13+ Observability·이벤트 핸들러 멱등성 표준화·D-90 백로그·D-94 Q6 박제 1줄 정합)

(target_type, target_id, template_code) 존재 가드는 정당 재발송(재알림) 수요와 구조 충돌 가능·기각.

#### Q7: 적재 실패 격리 = α AFTER_COMMIT·REQUIRES_NEW + catch structured log

각 핸들러 `handle()` 메서드 catch 블록에 structured log 1줄:

    log.warn("notification log failed; event={} target_type={} target_id={} action=manual_review",
             eventClass, targetType, targetId, exception);

D-94 Q8 ClaimApprovedHandler 패턴 1:1 재사용·알림 실패가 원 도메인(주문/결제/클레임) 비차단·CLAUDE.md "알림 실패 묵살 시 주석 명시" 룰 정합.

Micrometer 카운터·dashboard·alert 본 트랙 미도입(D-94 Q8 보류 정합·Observability 표준 박제 부재·Track 13+ Observability 통합 시점에 자연 재평가).

#### Q8: PR 분할 = α 단일 PR

본 트랙은 단일 PR(`feat/track-12-notification-log`)로 구성한다. D-87·D-92·D-93·D-94 1-PR 단독 패턴 정합. 범위 협소(핸들러 4·NotificationService 1·NotificationTemplateCodes 1·단위 4·통합 1·이벤트 Javadoc 4·D-95)·분할 시 문서·PR·검증 비용 역증.

DTO @ValidEnum·프론트 constants는 D-86 §후속 유지·이연 (외부 노출 DTO 부재·검증 대상 없음).

발송 어댑터·PENDING→SENT 전이는 별도 후속 PR (D-86 §OUT-OF-SCOPE 충실).

#### A1: ClaimApproved fallback 정책 = A1-α 본 트랙 박제·skip + structured log

ClaimApproved 소비 시 산정 실패 케이스 명시 정책:

- recipient 산정 불가 (Buyer ID 재조회 실패·Claim 또는 OrderItem 미존재): skip + warn
- title/content 조립 불가 (재조회 데이터 부족): skip + warn
- templateCode 매핑 부재 (Q5 정적 매핑 외 케이스): skip + warn

NULL 적재 회피 (적재 의미 보존)·NotificationLog 미적재·structured log 통한 운영 추적·재시도 정책은 OUT-OF-SCOPE (PENDING→SENT 전이·발송 어댑터 부재).

Q4 α′-2 범위 한정 규칙과 일관·외부 검토 2차 흡수.

#### A2: NotificationService 재조회 실패 정책 = A2-α skip + warn

`NotificationService.record()` 진입부 재조회 실패 시 동작:

- 재조회 결과 Optional.empty() → skip + warn log·예외 throw 금지
- 재조회 중 예외 발생 → catch 후 warn log·NotificationLog 미적재·핸들러 상위로 재throw 금지

Q5 α 재조회 기반 설계·Q7 α structured log 격리 패턴과 일관·원 흐름 비차단.

#### D-94 Q8 흡수 시점 = B 별도 후속 PR 이연

D-94 §후속 "Refund FAILED structured log → NotificationLog 채널 자연 흡수"는 본 트랙 미포함·별도 후속 PR로 이연한다.

사유:
- 본 트랙 = "이벤트 → NotificationLog 적재" 표준 박제 집중
- Refund FAILED structured log는 이벤트 미발행 흐름·범위 혼입 시 표준 모호
- D-94 Q8 박제 1줄(`refund/handler/ClaimApprovedHandler.handle` catch structured log) 그대로 유지·후속 PR에서 NotificationLog 적재로 전환
- 외부 검토 2차 "D-94 의도도 '자연 흡수'에 가깝다" 정합

후속 PR 명목: `feat/track-12-followup-refund-failed-notification` (또는 통합 시점 별도 식별자).

### 신규 WARN 처치

#### WARN-9: 재조회 기반 적재의 stale state 취약성 = WARN-9-α 박제 1줄

AFTER_COMMIT 시점 NotificationService 재조회는 commit 시점 스냅샷 일관성을 전제한다. 외부 row(Buyer·OrderItem·Claim 등)가 별도 트랜잭션에서 변경된 경우 stale state 적재 가능.

**박제 1줄**: "Track 12 NotificationLog 적재는 AFTER_COMMIT 시점 commit 스냅샷 일관성 가정 기반·실측 위반 사례(stale recipient·삭제 row 참조 등) 누적 시 재평가·payload 보조 필드 추가 또는 이벤트 저장소 도입은 후속 트랙(Track 13+ Observability) 통합 시점에 결정."

payload 확장(WARN-9-β)은 D-30 "사실 통지" 위배·Q5 β 회귀·기각.

#### WARN-10: 템플릿 코드 관리 분산 = WARN-10-α `NotificationTemplateCodes` 상수 클래스

신규 `notification/template/NotificationTemplateCodes.java` 상수 클래스 신설·이벤트별 templateCode 매핑 단일 위치 응집:

    public final class NotificationTemplateCodes {
        public static final String ORDER_PLACED = "TPL_ORDER_PLACED";
        public static final String PAYMENT_COMPLETED = "TPL_PAYMENT_COMPLETED";
        public static final String CLAIM_APPROVED = "TPL_CLAIM_APPROVED";
        public static final String CLAIM_COMPLETED = "TPL_CLAIM_COMPLETED";
        private NotificationTemplateCodes() {}
    }

Enum 신설(WARN-10-β)은 DTO @ValidEnum 동반·Track 8+ 의무·범위 확장·기각. 후속 트랙(RETURN/EXCHANGE·Delivery·Inventory) 진입 시 누적 매핑 본 클래스에 추가·Enum 승격은 ≥10건 누적 또는 DTO 검증 수요 발생 시점 결정.

### 횡단 원칙 (D-92·D-93 carry-over)

D-92·D-93 횡단 원칙("권한 검증 = Service 진입부·primitive actor 비의존·액터별 권한은 wrapper 캡슐화")은 본 트랙(이벤트 핸들러 트랙)에 직접 적용 대상 아니다. NotificationService는 actor 비의존·단순 적재 오케스트레이션. 본 트랙은 횡단 원칙 재사용 회차에 산입하지 않는다(Track 13+ 액터 트랙 진입 시 재사용 카운트 재개).

### 클래스 네이밍 (D-74 정합)

기존 핸들러 클래스와 동명 충돌 회피·D-74 "동명 클래스 복수 패키지 → 클래스 리네이밍 우선" 정책 적용:

| 신규 핸들러 (notification/handler/) | 충돌 기존 클래스 | 네이밍 방식 |
|---|---|---|
| NotificationOrderPlacedHandler | 없음 | 일관성 prefix |
| NotificationPaymentCompletedHandler | 없음 | 일관성 prefix |
| NotificationClaimApprovedHandler | refund/handler/ClaimApprovedHandler | **충돌 회피 의무** |
| NotificationClaimCompletedHandler | claim/handler/ClaimCompletedHandler | **충돌 회피 의무** |

`Notification` prefix 일관 적용·D-74 SellerRefundCompletedHandler·ClaimRefundCompletedHandler 선례 패턴 정합.

### WARN 해소 결과 (recon-report §9)

| WARN | 해소 |
|---|---|
| WARN-1 P0 발행처 미존재 이벤트 3건 (E4·E5·E10) | Q4 α′-2 채택·OUT-OF-SCOPE 명시·Delivery·Inventory 신설 트랙 이연 |
| WARN-2 P1 OrderPlaced(E1) 소비자 전무 | Q4 α′-2 E1 첫 소비자 = NotificationOrderPlacedHandler·통합 테스트로 발행 검증 |
| WARN-3 P1 적재 인자 산정 출처 미박제 | Q5 α 채택·NotificationService 재조회·A1-α·A2-α 정책 명시 |
| WARN-4 P1 멱등성 키 미박제 | Q6 α 채택·박제 1줄·D-90 백로그 이연 |
| WARN-5 P2 발송 어댑터 부재 | OUT-OF-SCOPE 경계 박제·Q8 발송 어댑터 후속 PR 이연 |
| WARN-6 P0 트랙 식별자 충돌 | Q1 α 채택·Track 12 = NotificationLog 확정·D-90·D-94 라벨 Track 13+ 자연 이동 |
| WARN-7 P2 4층위 enum 잠금 2/4층 | OUT-OF-SCOPE·D-86 §후속 Track 8+ 이연 유지 |
| WARN-8 P1 D-94 흡수 시점 도래 | D-94 Q8 흡수 시점 B 채택·별도 후속 PR 이연 |
| WARN-9 (신규·외부 검토 2차) 재조회 stale state | WARN-9-α 박제 1줄·Track 13+ 통합 시점 재평가 |
| WARN-10 (신규·외부 검토 2차) 템플릿 코드 분산 | WARN-10-α NotificationTemplateCodes 상수 클래스 |

### 외부 검토 흡수 흐름

- **1차 외부 검토**: 8 의제(Q1~Q8) + 추천안 송부. 수용: Q1·Q2·Q3·Q5·Q6·Q7·Q8 α 전건·D-94 Q8 흡수 시점 B·A급 라벨. 부분 수용: Q4(α → α′ 권장·"범위는 좁게"·"필요하면 한정"). 신규 의제 제기: A1 ClaimApproved fallback·A2 재조회 실패 정책. 신규 WARN 제기: WARN-9 stale state·WARN-10 템플릿 분산.

- **Claude.ai 축소 흡수**: Q4 α′-2 (4건 + skip + structured log 한정)·A1-α (NULL 적재 회피)·A2-α (skip + warn)·WARN-9-α (박제 1줄)·WARN-10-α (상수 클래스).

- **2차 외부 검토**: 축소안 전건 정합 확인. Q4 α′-2·A1-α·A2-α·WARN-9-α·WARN-10-α 전건 수용.

- **2차 검토 자체 평가**: "부분 수용·범위를 더 좁히고 fallback 정책을 명시한 방향이 1차보다 4기조 정합성 개선" (D-94 2차 검토자 자체 평가 "축소안이 1차보다 4기조 정합성 좋아졌다" 패턴 재현).

### 영향 범위

#### 신규 파일 (10건)
- backend/src/main/java/com/zslab/mall/notification/service/NotificationService.java (재조회 기반 적재 오케스트레이션·NotificationLogRepository 주입·title/content 조립·skip + warn 정책)
- backend/src/main/java/com/zslab/mall/notification/template/NotificationTemplateCodes.java (상수 클래스·4 templateCode)
- backend/src/main/java/com/zslab/mall/notification/handler/NotificationOrderPlacedHandler.java (E1 소비·AFTER_COMMIT·REQUIRES_NEW)
- backend/src/main/java/com/zslab/mall/notification/handler/NotificationPaymentCompletedHandler.java (E2 소비·AFTER_COMMIT·REQUIRES_NEW)
- backend/src/main/java/com/zslab/mall/notification/handler/NotificationClaimApprovedHandler.java (ClaimApproved 소비·AFTER_COMMIT·REQUIRES_NEW·D-74 충돌 회피)
- backend/src/main/java/com/zslab/mall/notification/handler/NotificationClaimCompletedHandler.java (E9 소비·AFTER_COMMIT·REQUIRES_NEW·D-74 충돌 회피)
- backend/src/test/java/com/zslab/mall/notification/service/NotificationServiceTest.java (단위·Mockito·재조회 성공/실패 skip/정상 적재)
- backend/src/test/java/com/zslab/mall/notification/handler/NotificationHandlerTest.java (단위·4 핸들러 통합·Mockito·이벤트 소비·structured log catch)
- backend/src/test/java/com/zslab/mall/notification/integration/NotificationLogIntegrationTest.java (e2e·NO @Transactional·TransactionTemplate·LT-02 try-finally·D-91 FK 부모 그래프 시드·E1·E2·E9·ClaimApproved 발행 → NotificationLog PENDING 적재 검증·E2 동기/비동기 혼재 실측)
- docs/track-12/recon-report.md (정찰 보고서·기 작성)

#### 수정 파일 (4건·이벤트 Javadoc 보강)
- backend/src/main/java/com/zslab/mall/order/event/OrderPlaced.java (Javadoc "Track 12 NotificationOrderPlacedHandler 소비")
- backend/src/main/java/com/zslab/mall/payment/event/PaymentCompleted.java (Javadoc "Track 12 NotificationPaymentCompletedHandler 소비·OrderEventHandler와 동기/비동기 혼재")
- backend/src/main/java/com/zslab/mall/claim/event/ClaimApproved.java (Javadoc "Track 12 NotificationClaimApprovedHandler 소비·산정 실패 시 skip + structured log")
- backend/src/main/java/com/zslab/mall/claim/event/ClaimCompleted.java (Javadoc "Track 12 NotificationClaimCompletedHandler 소비")

#### 무변경 확정
application.yml·build.gradle.kts·DDL/Flyway(notification_log V1 기박제·스키마 무변경)·NotificationLog Entity·NotificationChannel·NotificationLogStatus·NotificationLogRepository·PolymorphicTargetType·AbstractCreatedOnlyEntity·이벤트 record payload 전건(D-30 정합·payload 무수정)·발행처 Service 전건(ClaimService·PaymentService·OrderService·RefundService)·기존 핸들러 7건 전건·역방향 루프(ClaimRefundCompletedHandler·PaymentRefundCompletedHandler)·refund/handler/ClaimApprovedHandler(D-94 Q8 structured log 그대로 유지·후속 PR 이연)·invariants.md (NOT-4 신규 항목 미신설)·state-machine.md·aggregate-boundary.md(§2.7 기박제).

#### 회귀 예상
전체 회귀 BUILD SUCCESSFUL·신규 PASS(단위 ≥8·통합 ≥4)·기존 회귀 0(D-94 355 baseline 유지·신규 합산 ≥367).

### 대안 검토 (기각)

- Q1 β(미번호 NotificationLog 트랙): D-94 Q0 α 기각 사유 재현·추적 비용 증가·기각.
- Q2 β(발행 도메인 패키지 분산): 횡단 책임 분산·notification 의존 역유입·D-94 Q1 β 기각 패턴·기각.
- Q2 γ(NotificationService @TransactionalEventListener 직접 보유): Service에 이벤트 소비 책임 혼입·D-94 Q1 γ 기각 패턴·기각.
- Q3 β(@EventListener 동기): domain-events §1·§2 "알림 = 비동기" 위배·알림 실패가 원 도메인 롤백 유발·기각.
- Q4 α(3건 한정·ClaimApproved 미포함): ClaimApproved.java Javadoc 박제 흡수 기회 손실·외부 검토 1차 α′ 권장·2차 α′-2 정합 흡수·기각.
- Q4 α′-1(ClaimApproved 즉시 포함·fallback 정책 미박제): "범위는 좁게" 외부 검토 2차 의도 위배·기각.
- Q4 α′-3(ClaimApproved 후속 트랙 이연): Javadoc 박제 흡수 지연·외부 검토 2차 α′-2 채택 정합·기각.
- Q4 β(6 이벤트 전건 + Delivery·Inventory 발행처 신설): 범위 폭발·다도메인 동시 수정·기조 4 위배·기각.
- Q5 β(이벤트 payload recipient/title 추가): record·발행처 수정 동반·D-30 "사실 통지" 위배·D-94 Q7 β 기각 패턴 정합·기각.
- Q5 γ(최소 적재 NULL 허용): 운영 품질 저하·외부 검토 1차 "MVP성은 있으나 운영 품질 저하" 평가·A1-α (NULL 적재 회피) 일관성 위배·기각.
- Q6 β(target+template 멱등 가드): 정당 재발송(재알림) 수요와 구조 충돌·인메모리 publisher 가정 하 재전달 자연 발생 불가·D-94 Q6 박제 1줄 정합·기각.
- Q7 β(status=FAILED 적재 후 재시도 Job): 발송 어댑터 전제·OUT-OF-SCOPE·범위 확장·기각.
- Q8 β(4층위 잠금 DTO @ValidEnum 동반): 외부 노출 DTO 부재·검증 대상 없음·과잉개발·기각.
- Q8 γ(적재 + 발송 어댑터·PENDING→SENT 분할 명시): D-86 §OUT-OF-SCOPE 충실 자연 충족·별도 PR 분할 명시 박제는 과잉문서·기각.
- A1-β(fallback 미박제·NULL 적재): 적재 의미 보존 불가·운영 리스크 증가·외부 검토 2차 A1-α 채택·기각.
- A1-γ(ClaimApproved 본 트랙 제외): Q4 α′-2 정합 위배·Javadoc 박제 흡수 손실·기각.
- A2-β(재조회 실패 시 fail throw): 원 흐름 비차단 원칙 위배·Q7 α structured log 격리 패턴과 충돌·기각.
- A2-γ(NULL 적재): A1-α NULL 적재 회피 정합 위배·기각.
- D-94 Q8 흡수 시점 A(본 트랙 동반): 범위 혼입·표준 모호·D-94 "자연 흡수" 의도 위배·외부 검토 2차 B 채택·기각.
- WARN-9-β(이벤트 payload 보조 필드 추가): D-30 위배·Q5 β 회귀·외부 검토 2차 WARN-9-α 채택·기각.
- WARN-9-γ(본 트랙 미박제): D-90 백로그 통합 이연 시 본 트랙 가정 추적 손실·기각.
- WARN-10-β(Enum NotificationTemplateCode 신설): DTO @ValidEnum 동반·Track 8+ 의무·범위 확장·외부 검토 2차 WARN-10-α 채택·기각.
- WARN-10-γ(본 트랙 미박제): 후속 트랙 누적 패턴 분산·운영 비용 증가·기각.

### 관련 결정

D-01 (Aggregate 외부 ID 참조)·D-18 (NotificationLog Infra/Event Processing 재분류)·D-29 (save→publish·no flush)·D-30 (이벤트 사실 통지·payload 무수정)·D-69 (RefundCompleted Publisher/Consumer 시점 분리)·D-74 (이벤트 핸들러 빈 네이밍·동명 클래스 리네이밍 우선)·D-75 (이벤트 핸들러 AFTER_COMMIT·REQUIRES_NEW)·D-86 Q3 (NotificationLog Entity AbstractCreatedOnlyEntity 직접 상속·§후속 Track 8+ 이연)·D-87 (Track 8 진입·1-PR 단독 패턴)·D-90 (이벤트 핸들러 멱등성 표준화 백로그·Track 13+ Observability 통합)·D-92·D-93 (액터 트랙 횡단 원칙·본 트랙 산입 외)·D-94 (Track 11 Refund 자동 트리거·Q0 β 트랙 식별자 재정의 선례·Q6 인메모리 publisher 멱등 박제·Q7 payload 무수정·Q8 structured log + Observability 자연 흡수·§후속 NotificationLog 트랙 위임)·LT-02 (FK_CHECKS try-finally)·domain-events §1·§2 (E1·E2·E9 NotificationLog 소비 박제·알림 = 비동기·재시도·DLQ)·invariants §3.1 NOT-1~3·aggregate-boundary §2.7 (Aggregate 아님·자체 비즈니스 불변식 없음)·docs/track-12/recon-report.md.

### 후속 트랙

- **Track 12 후속 PR (Refund FAILED 흡수)**: D-94 Q8 흡수 시점 B 채택 후속·refund/handler/ClaimApprovedHandler structured log → NotificationLog 적재 전환·범위 협소(catch 블록 1건 + 통합 테스트).
- **Track 13+ Observability**: 이벤트 핸들러 멱등성 표준화·이벤트 저장소·Outbox·correlationId/eventId·Micrometer 컨벤션·WARN-9 stale state 재평가·Q6 박제 1줄 재검토 자연 진입점.
- **Track 13+ RETURN/EXCHANGE**: D-94 §후속 라벨 자연 이동·Delivery 도메인 동반·NotificationLog 핸들러 신설 동반·NotificationTemplateCodes 추가.
- **Delivery 도메인 신설 트랙**: E4 DeliveryStarted·E5 DeliveryCompleted 발행처 + NotificationDeliveryStartedHandler·NotificationDeliveryCompletedHandler 동반.
- **Inventory 도메인 신설 트랙**: E10 InventoryAdjusted 발행처 + NotificationInventoryAdjustedHandler 동반 (선택).
- **발송 어댑터 트랙**: NotificationLog PENDING→SENT 전이 메서드·실 전송 게이트웨이(EMAIL·SMS·PUSH·IN_APP)·재시도 Job·D-86 §후속 충실.
- **Spring Security 트랙**: X-*-Id stub 3종 일괄 대체·D-93 Q11 wrapper 유지 전략 결정.

### 후속

1. 본 결정 박제 = 사용자 직접 처리 (D-94 패턴 1:1).
2. PR 브랜치 `feat/track-12-notification-log` 생성 (main HEAD 8b5bc57 기준).
3. 가드 5 사전 통지: 신규 9 (Service 1·Template 1·Handler 4·Test 3) + 수정 4 (이벤트 Javadoc) — Claude Code 구현 프롬프트 시점 일괄 통지.
4. TDD 우선(단위 → 통합)·전체 회귀 BUILD SUCCESSFUL·신규 ≥12 PASS·기존 회귀 0.
5. GitHub Web UI PR 생성·Base: main·Compare: feat/track-12-notification-log·외부 검토 1·2차 완료·A급·추가 검토 선택.
6. PR 머지로 Track 12 종결·NotificationLog 적재 표준 박제·D-86 §후속 carry-over 영구 해소.
7. 후속 PR(D-94 Q8 흡수) 진입 시점 결정.

---

## D-96. Track 12 후속 PR · D-94 Q8 흡수 · ClaimApprovedHandler structured log → NotificationLog 적재 전환 [ACTIVE]

**결정일**: 2026-06-30
**관련**: 트랙 미부여 후속 PR / D-30·D-67·D-74·D-75·D-94 Q8·D-95 Q5·A1·A2·WARN-10 / invariants §3.1 NOT-1~3 / aggregate-boundary §2.7 / live-traps LT-02

### 배경

D-94 Q8 "FAILED 보상·운영 가시성 = α structured log만"에서 박제된 `refund/handler/ClaimApprovedHandler.handle` catch 블록의 structured log 1줄을, D-95 §"D-94 Q8 흡수 시점 = B 별도 후속 PR" 박제에 따라 NotificationLog 적재로 전환한다.

본 처치는 **트랙 미부여 후속 PR**로 진행한다. 범위 협소(catch 1건·테스트 1건 확장·상수 1건 추가)·D-94 Q0 β 선례 정합(트랙 번호는 진입 순서 라벨)·recon-report.md·외부 검토 절차 비용 회피·기조 3·4 정합. PR branch `feat/refund-failed-notification-followup` 단독.

정찰 결과 (MCP 실측 5건 인용·채팅 인라인):
- `ClaimApprovedHandler.handle` catch 현재 = `log.warn("Refund auto-trigger failed; claim={} refund_state=FAILED action=manual_retry_required", ...)` 1줄
- `NotificationService` 4 메서드 시그니처·내부 try-catch skip+warn·`resolveClaimRecipient(claimId, eventName)` 재사용 가능·throw 0
- `RefundAutoTriggerIntegrationTest` I3 = PaymentGatewayException → `RefundService.initiate` 내부 흡수(D-67)·catch 미발화·LT-02 try-finally 이미 적용
- `RefundService.initiate` PG 예외 흡수 범위 = **PG 호출 시점만**·그 외(Claim 정합·CLM-3·PAY-1·Payment 미발견·existsActive 모순) 핸들러 catch까지 전파
- `ClaimApprovedHandlerTest` T4 = `PaymentGatewayException` Mock + `assertThatCode(...).doesNotThrowAnyException()` catch 발화 검증 기보유·확장으로 충분

### 결정 (5건)

#### Q1: 적재 대상 범위 = α catch 블록만

ClaimApprovedHandler.handle catch 블록 1건만 NotificationLog 적재 대상. PG 실패 FAILED 전이는 `RefundService.initiate` 내부 흡수(D-67)·catch 미발화·범위 포함 외.

사유:
- D-94 Q8 본문 1:1 정합 ("운영 가시성 = catch 블록에 structured log 1줄만 추가")
- PG 실패 포함 시 RefundService.markFailed 또는 RefundFailed 이벤트 신설 동반·범위 폭발·기조 4 위배

catch 발화 경로 다양성 (실측):
- existsActive 모순·Claim 미발견·CLM-3 위반·Payment 미발견 → Refund INSERT 전·행 0건
- PAY-1 사전 위반 → Refund INSERT 전·행 0건
- PG 호출 외 RuntimeException → Refund 행 상태 불확정

→ NotificationLog 적재는 Refund 행 존재 여부와 무관하게 ClaimApproved 식별자로 수행 (Q3 정합).

#### Q2: recipient = α′ Buyer + Javadoc 재정의 박제

`resolveClaimRecipient(claimId, eventName)` 재사용·기존 NotificationService 4 메서드 패턴 1:1·D-95 A1-α NULL 적재 회피 정합.

**박제 1줄**: "Refund FAILED 알림 recipient는 Buyer로 시작·운영자 알림 채널 도입 시 재정의 가능·발송 어댑터 트랙(D-86 §후속) 진입 시점 결정." (NotificationService.recordRefundFailed Javadoc 흡수)

Operator recipient(β) 기각: admin user 모델 부재·D-93 stub·NULL 적재 시 A1-α 위배.

#### Q3: NotificationService.recordRefundFailed 신설 + REFUND_FAILED 상수

신규 메서드 `NotificationService.recordRefundFailed(ClaimApproved event)`:
- `resolveClaimRecipient(event.claimId(), "RefundFailed")` 재사용
- templateCode = `NotificationTemplateCodes.REFUND_FAILED` (신규 5번째 상수·`TPL_REFUND_FAILED`)
- targetType = `PolymorphicTargetType.CLAIM`·targetId = `event.claimId()`
- title·content = 클레임 식별자(`event.claimPublicId()`) 기반 운영자 시각 메시지

**박제 1줄**: "본 메서드는 Refund 행 존재 여부와 무관하게 ClaimApproved 식별자 기반으로 적재한다. catch 발화 시점에 Refund INSERT 전(Claim/PAY-1/Payment 검증 단계)일 수 있다." (NotificationService.recordRefundFailed Javadoc 흡수)

#### Q4: catch 구조 = α catch 내부 NotificationService 단일 호출·log.warn 제거

```java
try {
    refundService.initiate(event.claimId(), orderItem.getTotalPrice());
} catch (RuntimeException exception) {
    notificationService.recordRefundFailed(event);
}
```

사유:
- NotificationService 내부 try-catch + skip+warn 자체 보유·throw 0 (D-95 A2-α 정합)
- 호출 측 추가 catch 불필요·외부 catch 추가는 과잉방어
- 운영 가시성 = NotificationService 단일 책임 응집·로그 분산 회피

기각:
- β 이중 로깅 (catch log.warn 유지 + NotificationService 호출): 3중 로깅 가능(핸들러 warn + Service 내부 warn + skip log)·기조 4 위배
- γ catch에 log.warn 보존 + NotificationService 호출 추가: 동일 사유·과잉방어

#### Q5: 테스트 전략 = T4 확장 (신규 케이스 미추가)

`ClaimApprovedHandlerTest` 기존 T4 ("CANCEL·PG/도메인 예외: initiate 예외를 catch → 핸들러 밖 전파 차단") 확장:
- `@Mock NotificationService` 필드 추가
- `verify(notificationService).recordRefundFailed(any(ClaimApproved.class))` 검증 추가

기각:
- 신규 케이스 추가: T4가 이미 catch 발화 검증·중복·기조 4 위배
- 통합 테스트 신규 케이스 (Claim 미발견·PAY-1 한도 초과 시드): RefundService 모킹 또는 시드 정합성 부담·단위 테스트로 충분·기조 4 위배

### 영향 범위

#### 수정 파일 (4건)
- backend/src/main/java/com/zslab/mall/refund/handler/ClaimApprovedHandler.java (catch 블록 `notificationService.recordRefundFailed(event)` 단일 호출·기존 `log.warn` 제거·NotificationService 필드 주입·Javadoc "D-96 D-94 Q8 흡수 후속 PR로 structured log → NotificationLog 적재 전환" 1줄 추가)
- backend/src/main/java/com/zslab/mall/notification/service/NotificationService.java (`recordRefundFailed(ClaimApproved)` 신설·resolveClaimRecipient 재사용·targetType=CLAIM·targetId=claimId·Javadoc Q2·Q3 박제 2줄)
- backend/src/main/java/com/zslab/mall/notification/template/NotificationTemplateCodes.java (`REFUND_FAILED = "TPL_REFUND_FAILED"` 상수 5번째 추가)
- backend/src/test/java/com/zslab/mall/refund/handler/ClaimApprovedHandlerTest.java (T4 확장·`@Mock NotificationService` 필드 추가·`verify(notificationService).recordRefundFailed(any(ClaimApproved.class))` 검증)

#### 무변경 확정
ClaimApproved record payload·기존 NotificationService 4 메서드(recordOrderPlaced·recordPaymentCompleted·recordClaimApproved·recordClaimCompleted)·NotificationLog Entity·DDL/Flyway·RefundService·RefundAutoTriggerIntegrationTest (I3 PG 실패는 RefundService 내부 흡수·catch 미발화·NotificationLog 적재 없음이 의도된 동작·LT-02 try-finally 이미 적용 완료)·invariants·state-machine·aggregate-boundary.

#### 회귀 예상
전체 회귀 BUILD SUCCESSFUL·T4 확장 PASS·기존 회귀 0(D-95 baseline 377 → 377 유지·테스트 수 무증가).

### 대안 검토 (기각)

- Q1 β PG 실패 FAILED 전이 포함: RefundService.markFailed 수정 또는 RefundFailed 이벤트 신설 동반·범위 폭발·D-94 Q8 의도 위배·기각.
- Q2 β Operator recipient: admin user 모델 부재·D-93 stub·NULL 적재 시 A1-α 위배·기각.
- Q3 β payload 확장 (ClaimApproved에 refund 실패 사유 필드 추가): D-30 "사실 통지" 위배·발행처 ClaimService 수정 동반·D-94 Q7 β·D-95 Q5 β 기각 패턴 정합·기각.
- Q4 β 이중 로깅(catch log.warn 유지 + NotificationService 호출): 3중 로깅 가능·기조 4 위배·기각.
- Q4 γ catch에 log.warn 보존 + NotificationService 호출 추가: NotificationService 내부 try-catch 자체 보유·throw 0·외부 catch 불필요·과잉방어·기각.
- Q5 β 신규 단위 케이스 추가: T4가 이미 catch 발화 검증·중복·기조 4 위배·기각.
- Q5 γ 통합 테스트 신규 케이스 추가 (Claim 미발견 또는 PAY-1 한도 초과 시드): RefundService 모킹 또는 시드 정합성 부담·단위 테스트로 충분·기조 4 위배·기각.
- 트랙 부여 (Track 13): 범위 협소·recon-report.md·외부 검토 절차 비용 과대·D-94 Q0 β 선례 정합·기각.
- D-95 본문 추가로 흡수 (D-96 미신설): D-95 응집 흐림·결정 5건·박제 단위 적정·기각.

### 외부 검토 흡수 흐름

본 결정은 외부 검토 생략 (가드 1·A급·범위 협소·D-95 흡수 완료·신규 의제 5건 모두 내부 결정으로 종결). 정찰 후 5 의제 도출·기조 4 자체 재점검으로 Q2 α → α′·Q4 α 확정·실측 5건 인용으로 박제 명확화 완료.

### 관련 결정

D-30 (이벤트 사실 통지·payload 무수정)·D-67 (PG 호출 예외 FAILED 전이·RefundService 내부 흡수)·D-74 (이벤트 핸들러 빈 네이밍)·D-75 (이벤트 핸들러 AFTER_COMMIT·REQUIRES_NEW)·D-94 Q8 (structured log·향후 Observability/NotificationLog 자연 흡수·본 D-96으로 종결)·D-95 Q5 α (NotificationService 재조회 적재 표준)·D-95 A1-α (NULL 적재 회피·skip + warn)·D-95 A2-α (재조회 실패 skip + warn·throw 금지)·D-95 WARN-10-α (NotificationTemplateCodes 상수 클래스).

### 후속 트랙

- **발송 어댑터 트랙** (D-86 §후속): NotificationLog PENDING→SENT 전이·실 전송 게이트웨이(EMAIL·SMS·PUSH·IN_APP)·재시도 Job·Q2 운영자 알림 채널 recipient 재정의 진입점.
- **Track 13+ Observability**: 이벤트 핸들러 멱등성 표준화·Refund FAILED 알림 적재의 Outbox 진입 시 본 결정 재검토.

### 후속

1. 본 결정 박제 = 사용자 직접 처리 (D-94·D-95 패턴 정합).
2. PR 브랜치 `feat/refund-failed-notification-followup` 생성 (main HEAD d33f454 기준).
3. 가드 5 사전 통지: 수정 4·문서 1(본 D-96 박제는 사용자 직접 처리) — Claude Code 구현 프롬프트 시점 일괄 통지.
4. TDD 우선(T4 확장 → 전체 회귀)·BUILD SUCCESSFUL·기존 회귀 0.
5. GitHub Web UI PR 생성·Base: main·Compare: feat/refund-failed-notification-followup·외부 검토 생략 (A급·D-95 흡수 완료).
6. PR 머지로 D-94 Q8 흡수 carry-over 영구 해소·D-95 §후속 7 종결.

---

## D-97. Track 13 Delivery 도메인 신설 · state-machine §6 이연 해소 · E4·E5 발행처 신설 · NotificationLog 표준 1회차 재사용 [ACTIVE]

**결정일**: 2026-06-30
**관련**: Track 13 / D-01·D-06·D-21·D-29·D-30·D-74·D-75·D-80·D-86 Batch-3b·D-91·D-94 Q0·D-95 Q1·Q4·Q5·Q6·WARN-10 / invariants §2.12 DLV-1~3 / state-machine §3·§6 / aggregate-boundary §2.5 / domain-events §2 E4·E5 / live-traps LT-02 / docs/track-13/recon-report.md §1~§11

### 배경

Track 12 NotificationLog 적재 표준 박제(D-95) + D-96 후속 PR(Refund FAILED 적재 전환) 머지 직후 진입. 본 트랙은 D-95 §후속 "Delivery 도메인 신설 트랙·E4·E5 발행처 + NotificationDeliveryStartedHandler·NotificationDeliveryCompletedHandler 동반" 자연 진입점이다.

정찰 결과 (docs/track-13/recon-report.md §2·§3 실측 인용):
- Delivery 도메인 영속 계층 4파일(entity·enums 2종·repository) 선구현·service/handler/controller/event 패키지 전무
- `DeliveryStatus.canTransitionTo()` 미존재·`Delivery.markShipping()`·`markDelivered()` 미존재·E4·E5 record 미존재
- DDL ENUM 1층 잠금 완료·4층위 룰 2~4층 미완(외부 노출 DTO 부재로 3·4층 OUT-OF-SCOPE)
- `OrderItemStatus.canTransitionTo` PREPARING→SHIPPING·SHIPPING→DELIVERED 기정의 완료 (회귀 위험 저)
- NotificationLog 표준 5 메서드·핸들러 4건 SoT 안정·PolymorphicTargetType.DELIVERY 기존재

1·2차 외부 검토 생략 (A급·새 패턴 0·전건 1:1 재사용·D-95 표준 2차 검토 통과 후 1회차 재사용). 정찰 룰 7건·기조 5 자체 감사 통과 후 결정 12건 + WARN 처치 3건 박제.

### 결정 (12건)

#### Q0: 트랙 식별자 = α Track 13 = Delivery 확정

기존 라벨 정정 의무: D-95 §후속 "Track 13+ Observability"·"Track 13+ RETURN/EXCHANGE"는 본 결정 박제 시점부터 **Track 14+로 자연 이동**. 기존 결정 본문 정정 수행 안 함 (과잉문서 회피·D-94 Q0 β·D-95 Q1 α 패턴 3회차).

후속 트랙 라벨 누적 정정:
- D-90 §후속·D-94 §후속의 "Track 12+ Observability" → 이미 D-95 Q1에서 Track 13+로 이동·본 결정으로 **Track 14+로 재이동**
- D-94 §후속의 "Track 12+ RETURN/EXCHANGE" → 이미 D-95 Q1에서 Track 13+로 이동·본 결정으로 **Track 14+로 재이동**

#### Q1: Delivery 상태 전이 규칙 (state-machine §6 이연 해소)

`DeliveryStatus.canTransitionTo(DeliveryStatus next)` 신설:
- READY → SHIPPING 허용
- SHIPPING → DELIVERED 허용
- DELIVERED → * 차단 (종결 상태)
- 역방향 전건 차단·자기 전이 차단

`OrderItemStatus.canTransitionTo` 패턴 1:1·switch expression·단순 직진 단방향. state-machine §6 "Delivery 상태 전이 → 각 도메인 별도 정의 (본 PR 범위 외)" 이연 영구 해소·본 결정 박제로 §6 본문 갱신 의무.

#### Q2: 상태 전이 메서드 위치 = α Entity 도메인 메서드

`Delivery.java` 내 도메인 메서드 신설:
- `markShipping(String trackingNo, LocalDateTime shippedAt)` — canTransitionTo 가드·trackingNo·shippedAt·status 설정
- `markDelivered(LocalDateTime deliveredAt)` — canTransitionTo 가드·DLV-3 검증(shippedAt ≤ deliveredAt)·deliveredAt·status 설정

D-29 save→publish 원칙·이벤트 발행은 DeliveryService 책임. OrderItem.changeStatus + Order.markPaid 분리 패턴 1:1 정합.

기각: β Service 이동 (도메인 행위 응집 위배·기조 4).

#### Q3: 트리거 진입점 = α Service 직접 호출

`DeliveryService.markShipping(Long deliveryId, String trackingNo)`·`markDelivered(Long deliveryId)` Service 메서드 신설·Controller·endpoint 본 트랙 OUT-OF-SCOPE.

판매자 API endpoint·Admin endpoint는 별도 트랙 이연 (Seller Service 트랙 또는 Admin Service 트랙 진입 시점). 본 트랙 단위·통합 테스트는 Service 직접 호출로 충분.

기각: β Controller 동반 (범위 폭발·기조 4·외부 노출 DTO @ValidEnum 3·4층 동반 의무 발생).

#### Q4: Order 측 동기 소비 핸들러 위치 = α `order/handler/` 패키지

신규 핸들러 2건:
- `order/handler/DeliveryStartedHandler` — E4 소비·OrderItem → SHIPPING 전이 + Order.status 재계산 (구체 호출 경로는 기존 핸들러 3건 `ClaimRequestedHandler`·`ClaimCompletedHandler`·`payment/handler/OrderEventHandler` 실측 후 1:1 재사용)
- `order/handler/DeliveryCompletedHandler` — E5 소비·OrderItem → DELIVERED 전이 + Order.status 재계산 (동일 패턴)

D-94 Q1 α 패턴 1:1 (반응 도메인 패키지 배치)·delivery 패키지 분산·order 의존 역유입 차단.

기각: β payment/handler/OrderEventHandler 확장 (Payment 핸들러에 Delivery 책임 혼입·SRP 위배·D-94 Q1 β 기각 패턴 정합).

#### Q5: Order 측 트랜잭션 정책 = `@EventListener` 동기 + 동일 트랜잭션

`order/handler/Delivery*Handler` 양자에 `@EventListener` 적용·**`@TransactionalEventListener` 금지**. payment/handler/OrderEventHandler(E2 PaymentCompleted 소비) 패턴 1:1.

근거: domain-events.md §2 E4·E5 "OrderItem 상태 = 동기" 박제 의무·OrderItem 전이 실패 시 발행 트랜잭션 롤백 필수·AFTER_COMMIT 비동기화 시 Delivery 상태와 OrderItem 상태 불일치 윈도우 발생.

#### Q6: Notification 핸들러 = `notification/handler/` 2건·AFTER_COMMIT·REQUIRES_NEW

신규 핸들러 2건:
- `notification/handler/NotificationDeliveryStartedHandler` — E4 비동기 소비·`notificationService.recordDeliveryStarted(event)` 위임
- `notification/handler/NotificationDeliveryCompletedHandler` — E5 비동기 소비·`notificationService.recordDeliveryCompleted(event)` 위임

D-95 표준 1:1 재사용 1회차·D-74 `Notification` prefix 일관 적용 (DeliveryStartedHandler·DeliveryCompletedHandler는 order/handler/에 점유·충돌 회피 의무).

`NotificationService` 신규 메서드 2건:
- `recordDeliveryStarted(DeliveryStarted event)`
- `recordDeliveryCompleted(DeliveryCompleted event)`

private 헬퍼 신설: `resolveDeliveryRecipient(Long deliveryId, String eventName)` — deliveryId → Delivery 재조회 → orderItemId → OrderItem → orderId → Order → buyerId 체인·미발견 시 null 반환·skip + warn (D-95 A1-α·A2-α 정합).

#### Q7: TemplateCode = `DELIVERY_STARTED`·`DELIVERY_COMPLETED` 상수 2건 추가

`NotificationTemplateCodes`에 2 상수 추가 (5→7건):
- `DELIVERY_STARTED = "TPL_DELIVERY_STARTED"`
- `DELIVERY_COMPLETED = "TPL_DELIVERY_COMPLETED"`

Enum 승격 임계 ≥10건 유지 (D-95 WARN-10-α 정합·여유 3건). DTO @ValidEnum 수요 발생 시 재평가.

#### Q8: 멱등성 가드 = α `OrderItemStatus.canTransitionTo` 자연 흡수

별도 멱등 저장소 미도입. Order 측 핸들러에서 OrderItem.changeStatus 호출 시 `OrderItemStatus.canTransitionTo` 가드가 자연 흡수:
- E4 재전달·OrderItem이 이미 SHIPPING 이상 → canTransitionTo false → no-op skip
- E5 재전달·OrderItem이 이미 DELIVERED → canTransitionTo false → no-op skip

NotificationLog 측은 D-95 Q6 α 정합·append-only 멱등 가드 미도입·재전달 시 중복 적재 가능성 수용 (인메모리 ApplicationEvent publisher 가정 하 재전달 자연 발생 불가).

**박제 1줄**: "Track 13 Delivery 이벤트 멱등 보호는 OrderItemStatus.canTransitionTo 자연 흡수·외부 이벤트 브로커·Outbox·다중 인스턴스 도입 시 본 게이트 재검토 의무." (D-95 Q6 박제 1줄과 일관·Track 14+ Observability 자연 재평가)

#### Q9: PR 분할 = α 단일 PR

본 트랙은 단일 PR(`feat/track-13-delivery`)로 구성. D-87·D-94·D-95 1-PR 단독 패턴 4회차 정합. 범위 협소(신규 7·수정 5·문서 2·D-97 박제 1)·분할 시 문서·PR·검증 비용 역증·Order 핸들러와 Notification 핸들러는 동일 이벤트(E4·E5) 소비 결합·분리 시 통합 테스트 1회 커버 불가.

정찰 보고서 §8 Q9 β(분리) 권장은 검토 부담 분산 사유였으나 외부 검토 생략 결정(Q12)으로 사유 약화·기조 4 정합 α 채택.

기각: β Delivery 핵심 + Notification 분리 2 PR (E4·E5 발행 → Order·Notification 동시 소비 통합 검증 분리 비용·기조 4 위배).

#### Q10: 테스트 전략 = 단위 + 통합·D-91·LT-02 의무

**단위 테스트** (≥3건):
- `DeliveryServiceTest.markShipping` 정상·canTransitionTo 위반(DELIVERED 상태에서 markShipping)·이벤트 발행 검증
- `DeliveryServiceTest.markDelivered` 정상·DLV-3 위반(shippedAt > deliveredAt)·canTransitionTo 위반(READY 상태에서 markDelivered 스킵)
- `order/handler/DeliveryStartedHandlerTest`·`DeliveryCompletedHandlerTest` (OrderItem 전이 + 멱등 skip 검증)

**통합 테스트** (≥1건):
- `delivery/integration/DeliveryEventIntegrationTest`
  - T1: markShipping → E4 발행 → OrderItem SHIPPING + NotificationLog 적재 검증
  - T2: markDelivered → E5 발행 → OrderItem DELIVERED + NotificationLog 적재 검증
  - T3: 잘못된 상태 전이(READY→DELIVERED 스킵) → IllegalStateException·상태 무변경

**의무 박제**:
- `@Transactional` **금지** (D-90 Q5 β·AFTER_COMMIT 핸들러 미실행 트랩 회피)·TransactionTemplate 패턴
- LT-02 try-finally `SET FOREIGN_KEY_CHECKS=1` 복원 의무
- D-91 FK 부모 그래프 시드: seller·product·variant·user·order·order_item 실제 INSERT 의무 (DeliveryRepositoryTest seedOrderItem 패턴 + 부모 그래프 확장)

#### Q11: DeliveryRepository 확장 = β 미추가

`findByOrderItemId` 미추가. DeliveryService가 deliveryId를 직접 수신·`findById(deliveryId)` 재사용 충분. 운영자/판매자 endpoint 도입 시점에 수요 발생 시 추가 (별도 트랙).

기각: α `findByOrderItemId` 추가 (현재 호출처 부재·기조 4 위배·과잉개발).

#### Q12: 외부 검토 라벨 = A급·외부 검토 생략

근거:
- 회귀 위험 저 (OrderItemStatus.canTransitionTo 기정의 흡수·기존 테스트 회귀 0)
- 새 패턴 0건·전건 1:1 재사용 (D-29 save→publish·D-74 Notification prefix·D-75 AFTER_COMMIT·REQUIRES_NEW·D-94 Q1 핸들러 배치·D-95 Q5/Q6 NotificationLog 표준)
- 단일 Aggregate·결합도 낮음·state-machine §6 신규 박제는 단순 직진
- D-95 §1.1 A급 외부 검토 선택 패턴 정합·D-95 자체 2차 검토 통과한 표준의 1회차 재사용

가드 1 라벨: **외부 검토 생략 권장**.

### WARN 처치 (recon-report §9 매트릭스)

#### P0 처치 (Q1·Q2 결정으로 자연 해소)
- WARN-1 canTransitionTo 미존재 → Q1 결정으로 신설
- WARN-2 markShipping/markDelivered 미존재 → Q2 결정으로 신설
- WARN-3 E4·E5 record 미존재 → Q1·Q2 발행처 신설 동반

#### P1 처치
- WARN-4 state-machine §6 이연 → Q1 결정 + state-machine.md §6 본문 갱신 의무
- WARN-5 OrderService markShipping/markDelivered 미존재 → Q4 결정으로 핸들러 신설·구체 호출 패턴(OrderService 경유 또는 OrderItemRepository 직접 주입·OrderItem 전이 메서드 직접 호출)은 기존 핸들러 3건(`ClaimRequestedHandler`·`ClaimCompletedHandler`·`payment/handler/OrderEventHandler`) 구현 단계 실측 후 1:1 재사용·본 결정 박제 외 (기조 5 정합·추측 단정 회피)
- WARN-6 Notification 핸들러 recipient 산정 → Q6 결정으로 resolveDeliveryRecipient 체인 박제
- **WARN-7 DLV-3 강제 의무** → `Delivery.markDelivered` 내부에 `if (shippedAt != null && deliveredAt.isBefore(shippedAt)) throw new IllegalStateException("DLV-3 위반·shipped_at ≤ delivered_at 정합 깨짐")` 박제
- **WARN-8 D-91 FK 부모 그래프 시드** → Q10 통합 테스트 의무 박제·DeliveryRepositoryTest seedOrderItem은 order+order_item만 시딩·핸들러 UPDATE는 seller·product·variant·user 추가 INSERT 필수

#### P2 처치
- WARN-9 TemplateCode 2건 → Q7 결정
- WARN-10 Repository 확장 → Q11 β 미추가
- WARN-11 `@Transactional` 금지 → Q10 의무 박제 (D-90 Q5 β·TransactionTemplate 패턴)

### 횡단 원칙 (D-92·D-93 carry-over)

D-92·D-93 횡단 원칙("권한 검증 = Service 진입부·primitive actor 비의존·액터별 권한은 wrapper 캡슐화")은 본 트랙(이벤트 핸들러 + Service 트랙)에 직접 적용 대상 아니다. DeliveryService는 actor 비의존 시그니처(`markShipping(Long deliveryId, String trackingNo)`·`markDelivered(Long deliveryId)`)·Controller endpoint OUT-OF-SCOPE. 본 트랙은 횡단 원칙 재사용 회차에 산입하지 않는다 (Track 14+ 액터 트랙·Spring Security 트랙 진입 시 재사용 카운트 재개).

### 클래스 네이밍 (D-74 정합)

| 신규 핸들러 | 패키지 | 충돌 기존 클래스 | 네이밍 방식 |
|---|---|---|---|
| DeliveryStartedHandler | order/handler/ | 없음 | 도메인 prefix 미부착 (Order 측 동기 소비) |
| DeliveryCompletedHandler | order/handler/ | 없음 | 동일 |
| NotificationDeliveryStartedHandler | notification/handler/ | order/handler/DeliveryStartedHandler | **충돌 회피 의무·`Notification` prefix** |
| NotificationDeliveryCompletedHandler | notification/handler/ | order/handler/DeliveryCompletedHandler | **동일** |

D-74·D-95 NotificationClaim*Handler·NotificationOrderPlaced·NotificationPaymentCompleted 선례 패턴 1:1.

### 영향 범위

#### 신규 파일 (7건)
- backend/src/main/java/com/zslab/mall/delivery/event/DeliveryStarted.java (record·payload: deliveryId·orderItemId·carrier·trackingNo·occurredAt·D-30 사실 통지·Javadoc "Track 13 order/handler/DeliveryStartedHandler 동기 소비·notification/handler/NotificationDeliveryStartedHandler 비동기 소비")
- backend/src/main/java/com/zslab/mall/delivery/event/DeliveryCompleted.java (record·payload: deliveryId·orderItemId·deliveredAt·occurredAt·D-30 사실 통지·Javadoc 동일 구조)
- backend/src/main/java/com/zslab/mall/delivery/service/DeliveryService.java (markShipping·markDelivered·D-29 save→publish·DLV-3 검증은 Entity 위임)
- backend/src/main/java/com/zslab/mall/order/handler/DeliveryStartedHandler.java (E4 동기 소비·@EventListener·OrderItem 전이·Order.status 재계산)
- backend/src/main/java/com/zslab/mall/order/handler/DeliveryCompletedHandler.java (E5 동기 소비·동일 패턴)
- backend/src/main/java/com/zslab/mall/notification/handler/NotificationDeliveryStartedHandler.java (E4 비동기·AFTER_COMMIT·REQUIRES_NEW·NotificationService 위임)
- backend/src/main/java/com/zslab/mall/notification/handler/NotificationDeliveryCompletedHandler.java (E5 비동기·동일 패턴)

#### 수정 파일 (production 5건)
- backend/src/main/java/com/zslab/mall/delivery/enums/DeliveryStatus.java (canTransitionTo 신설·READY→SHIPPING·SHIPPING→DELIVERED·DELIVERED 종결)
- backend/src/main/java/com/zslab/mall/delivery/entity/Delivery.java (markShipping·markDelivered 도메인 메서드 신설·DLV-3 검증·Javadoc Q2 박제)
- backend/src/main/java/com/zslab/mall/notification/service/NotificationService.java (recordDeliveryStarted·recordDeliveryCompleted 메서드 2건 추가·resolveDeliveryRecipient private 추가·Javadoc Q6 박제)
- backend/src/main/java/com/zslab/mall/notification/template/NotificationTemplateCodes.java (DELIVERY_STARTED·DELIVERY_COMPLETED 상수 2건 추가·7건)
- backend/src/main/resources/db/migration/ DDL 무변경 확정 (V1__init.sql delivery 테이블 기완비)

#### 신규 테스트 (≥4건)
- backend/src/test/java/com/zslab/mall/delivery/service/DeliveryServiceTest.java (단위·markShipping 정상·canTransitionTo 위반·markDelivered 정상·DLV-3 위반·이벤트 발행 검증)
- backend/src/test/java/com/zslab/mall/order/handler/DeliveryStartedHandlerTest.java (단위·OrderItem 전이·canTransitionTo 멱등 skip)
- backend/src/test/java/com/zslab/mall/order/handler/DeliveryCompletedHandlerTest.java (단위·동일 패턴)
- backend/src/test/java/com/zslab/mall/delivery/integration/DeliveryEventIntegrationTest.java (e2e·NO @Transactional·TransactionTemplate·LT-02 try-finally·D-91 FK 부모 그래프 시드·T1·T2·T3)

#### 문서 수정 (2건)
- docs/architecture-baseline/state-machine.md §6 (Delivery 상태 전이 규칙 본문 신설·READY→SHIPPING→DELIVERED 단방향·이연 해소)
- docs/architecture-baseline/domain-events.md §2 E4·E5 표 (발행처 주석 보강·"구현 완료·Delivery.markShipping·markDelivered + DeliveryService publishEvent")

#### 무변경 확정
application.yml·build.gradle.kts·DDL/Flyway(V1__init.sql delivery 테이블·ENUM 1층 잠금 기완비·스키마 무변경)·DeliveryCarrier·DeliveryRepository·DeliveryRepositoryTest 4 케이스(회귀 유지)·OrderItemStatus.canTransitionTo(PREPARING→SHIPPING·SHIPPING→DELIVERED 기정의)·OrderItem.changeStatus·Order.status 재계산 메서드·PolymorphicTargetType(DELIVERY 기존재)·NotificationLog Entity·NotificationChannel·NotificationLogStatus·NotificationLogRepository·기존 NotificationService 5 메서드·기존 Notification 핸들러 4건·기존 이벤트 8건 payload·invariants.md(DLV-1~3 기박제·신규 항목 미신설).

#### 회귀 예상
전체 회귀 BUILD SUCCESSFUL·신규 PASS(단위 ≥6·통합 ≥3)·기존 회귀 0(D-96 baseline 377 → 신규 합산 ≥386).

### 대안 검토 (기각)

- Q1 양방향 전이(SHIPPING→READY 허용): 운송 분쟁 시 보정 시나리오는 별도 보정 트랙·기조 4 위배·기각.
- Q2 β Service 이동: 도메인 행위 응집 위배·OrderItem 패턴 위배·기각.
- Q3 β Controller 동반: 외부 노출 DTO @ValidEnum 3·4층 동반 의무·범위 폭발·기조 4 위배·기각.
- Q4 β payment/handler/OrderEventHandler 확장: Payment 핸들러에 Delivery 책임 혼입·SRP 위배·D-94 Q1 β 기각 패턴·기각.
- Q5 AFTER_COMMIT 비동기: domain-events §2 "OrderItem 상태 = 동기" 박제 위배·Delivery-OrderItem 상태 불일치 윈도우·기각.
- Q6 NotificationService 메서드 미분리(통합 record 메서드): D-95 표준 1:1 위배·재조회 체인 차이 무시·기각.
- Q7 Enum 승격 즉시 도입: DTO @ValidEnum 동반·외부 노출 DTO 부재·D-95 WARN-10-α 위배·기각.
- Q8 멱등 저장소 도입(target_type+target_id 가드): 정당 재알림 수요 충돌·D-95 Q6 박제 1줄 위배·기각.
- Q9 β PR 분리: 통합 테스트 1회 커버 불가·검증 비용 역증·외부 검토 생략(Q12)으로 분리 사유 약화·기각.
- Q10 단위만(통합 생략): D-91 핸들러 동작 실측 부재·CLAUDE.md 신규 도메인 통합 3건 의무 위배·기각.
- Q11 α findByOrderItemId 추가: 현재 호출처 부재·기조 4 위배·기각.
- Q12 외부 검토 의무화(S급 상향): 새 패턴 0·전건 1:1 재사용·D-95 §1.1 A급 패턴 정합·기각.

### 관련 결정

D-01 (Aggregate 외부 ID 참조·Delivery #12)·D-06 (E4·E5 발행 주체 Delivery·소비 주체 Order·Notification 박제)·D-21 §f (DLV-1~3 invariants 보강)·D-29 (save→publish·no flush)·D-30 (이벤트 사실 통지·payload 무수정)·D-74 (이벤트 핸들러 빈 네이밍·Notification prefix 충돌 회피)·D-75 (이벤트 핸들러 AFTER_COMMIT·REQUIRES_NEW)·D-80 (Delivery ARCHIVE 분류·송장 이력 보존)·D-86 Batch-3b (Delivery Entity·enum 2·Repository 선구현 확정)·D-90 Q5 β (통합 테스트 @Transactional 금지·TransactionTemplate)·D-91 (통합 테스트 FK 부모 그래프 시드 의무)·D-94 Q0 β·Q1 α (트랙 식별자 재정의 선례·핸들러 반응 도메인 패키지 배치)·D-95 Q1 α (트랙 식별자 재정의 2회차)·D-95 Q4 α′-2 (NotificationLog 적재 범위 한정·산정 실패 skip+warn)·D-95 Q5 α (NotificationService 재조회 적재 표준)·D-95 Q6 α (멱등 가드 미도입·인메모리 publisher 가정)·D-95 WARN-10-α (NotificationTemplateCodes 상수 클래스)·D-96 (NotificationService 운영 알림 채널 패턴)·LT-02 (FK_CHECKS try-finally)·invariants §2.12 DLV-1~3·state-machine §3·§6·aggregate-boundary §2.5·domain-events §2 E4·E5·docs/track-13/recon-report.md §1~§11.

### 후속 트랙

- **Track 14+ Observability**: 이벤트 핸들러 멱등성 표준화·이벤트 저장소·Outbox·correlationId/eventId·Micrometer 컨벤션·D-95 Q6 박제 1줄·본 결정 Q8 박제 1줄 통합 재평가 진입점.
- **Track 14+ RETURN/EXCHANGE**: D-94·D-95 §후속 라벨 자연 이동·Delivery DELIVERED 의존 전이(RETURN_REQUESTED·EXCHANGE_REQUESTED)·D-92·D-93 횡단 원칙 재사용 2회차·ClaimReasonCode +4값.
- **Track 14+ Inventory 차감/복구 Order-Delivery 연동**: E1·E2·E9 차감/복구 핸들러·Inventory 도메인 행위 신설.
- **Seller Service 트랙 (또는 Admin Service 트랙)**: Delivery Controller·endpoint 신설·판매자 운송장 등록 API·운영자 강제 전이·DTO @ValidEnum 3·4층 동반.
- **자동 구매확정 타이머 트랙**: DELIVERED 후 N일 → OrderItem CONFIRMED 전이·배치 또는 스케줄러.
- **택배사 API 연동 어댑터 트랙**: DeliveryCarrier·tracking_no 외부 유효성 검증·실시간 배송 추적.
- **NotificationLog 발송 어댑터 트랙** (D-86 §후속): PENDING→SENT 전이·실 전송 게이트웨이·D-96 Q2 운영자 채널 recipient 재정의 진입점.

### 후속

1. 본 결정 박제 = 사용자 직접 처리 (D-94·D-95·D-96 패턴 정합).
2. PR 브랜치 `feat/track-13-delivery` 생성 (main HEAD 8e7d4d2 기준).
3. 가드 5 사전 통지: 신규 7 (event 2·service 1·handler 4) + 수정 5 (production 4·DDL 무변경 확정 1) + 신규 테스트 ≥4 + 문서 2 (state-machine §6·domain-events §2) + 박제 1 (본 D-97·사용자 직접 처리) — Claude Code 구현 프롬프트 시점 일괄 통지.
4. TDD 우선(단위 → 통합)·BUILD SUCCESSFUL·신규 ≥9 PASS·기존 회귀 0.
5. GitHub Web UI PR 생성·Base: main·Compare: feat/track-13-delivery·외부 검토 생략 (A급·D-95 §1.1 패턴 정합).
6. PR 머지로 Track 13 종결·state-machine §6 이연 영구 해소·D-95 §후속 Delivery 도메인 신설 트랙 carry-over 영구 해소·D-95 Q4 OUT-OF-SCOPE(E4·E5) 자연 해소.

---

## D-98. Track 14 Claim RETURN/EXCHANGE 확장 · state-machine §2/§8 RETURN/EXCHANGE 흐름 종결 · ClaimPickedUp 이벤트 신설 · PR-1 RETURN / PR-2 EXCHANGE 분할 · D-90 Q3 의미 변경 [ACTIVE]

**결정일**: 2026-06-30
**관련**: Track 14 / D-01·D-05·D-06·D-29·D-30·D-67·D-74·D-75·D-86·D-88·D-89·D-90 (Q3 의미 변경)·D-91·D-92·D-93·D-94·D-95·D-96·D-97 / invariants §2.11 PAY-1·§2.12 DLV-1~3·§2.13 CLM-1~5·§2.13.1 RFN-1~3 / state-machine §2·§3·§6.1·§8 / aggregate-boundary §2.5 / domain-events §2 E5·E7·E8·E9·E11(신설) / live-traps LT-02 / docs/track-14/recon-report.md §1~§12

### 배경

D-88·D-89·D-90·D-93·D-94·D-95·D-97 §후속에 걸쳐 **4회 연속 carry-over**된 "Claim RETURN/EXCHANGE 확장" 의제를 본 트랙에서 종결한다.

**정찰 결과 (docs/track-14/recon-report.md §1.2 실측 인용)**:
- RETURN/EXCHANGE의 값집합·상태 매트릭스·DTO·Controller·승인 wrapper는 전부 선구현
- 결손은 "CANCEL 게이트 5중 제거 + 흐름 후반부 신설"에 집중

**P0 결손 3건**:
- WARN-1 수거 확인 미설계: state-machine §2/§8은 수거 확인을 COMPLETED 게이트로 규정하나 OrderItem 12값에 중간 상태 부재·`OrderItemStatus.canTransitionTo` 라인 52-53 `RETURN_REQUESTED → RETURNED`·`EXCHANGE_REQUESTED → EXCHANGED` 직접 전이만 존재
- WARN-2 EXCHANGE 교환품 Delivery 생성 진입점 부재: `Delivery.create` production 호출처 0건
- WARN-3 RETURN/EXCHANGE Refund 트리거 시점 분기 미설계: `refund/handler/ClaimApprovedHandler` 라인 50-54 CANCEL 단독 게이트

1·2·3차 외부 검토 수렴 후 결정 13건 + WARN 처치 7건 + 트랙 식별자 1건 + PR 분할 1건 + D-90 Q3 의미 변경 1건을 박제한다.

### 결정 (13건)

#### Q0: 트랙 식별자 = α Track 14 = Claim RETURN/EXCHANGE 확장·문서 일괄·PR 분리

본 트랙은 D-97 §후속 carry-over 종착지. D-94 Q0 β·D-95 Q1 α·D-97 Q0 β 선례 4회차·트랙 번호는 진입 순서 라벨.

**문서·PR 분할**:
- **D-98 박제 = RETURN+EXCHANGE 일괄** (본 결정 본문 단일·과잉문서 회피·기조 3)
- **PR = RETURN PR-1 + EXCHANGE PR-2 분할** (1차 검토자 강권·Q10)

RETURN은 state-machine §2/§8이 닫혀 있고 본 트랙에서 완결 가능·EXCHANGE는 교환 Delivery 생성·배송 완료 게이트가 새로 닫혀야 하는 모델·복잡도 상위·1-PR 5회차 비대화 회피.

#### Q1: 수거 확인 모델링 = (d) 신규 이벤트 E11 ClaimPickedUp + claim.picked_up_at 컬럼

**옵션 (a) OrderItem 신규 상태·(b) ClaimStatus 보조 상태·(c) ReturnDelivery 신규 Aggregate 전건 기각**.

**(d) 채택**:
- `claim` 테이블 컬럼 추가: `picked_up_at DATETIME(6) NULL COMMENT '수거 확인 시각'`
- Claim Aggregate 행위 메서드: `Claim.confirmPickup(LocalDateTime pickedUpAt)` 신설 — `status == APPROVED` && `picked_up_at IS NULL` 가드 (멱등·이미 picked_up_at != null 시 no-op)
- 신규 이벤트 **E11 ClaimPickedUp** (domain-events §2 E11 신설): payload = `claimId·claimPublicId·orderItemId·claimType·pickedUpAt·occurredAt` (D-30 사실 통지·기존 Claim 이벤트 record 패턴 1:1)

**발행 주체**: Seller 우선·Admin override·외부 택배 어댑터는 후속 트랙. 본 트랙 Service primitive `confirmPickup` + wrapper 2건 (Q9 정합).

**멱등성**: `claim.picked_up_at != null` 시 Service no-op + log.info (`ClaimService.markCompleted` 멱등 가드 라인 270-274 패턴 1:1).

#### Q2: RETURN/EXCHANGE Refund 트리거 분기 = RETURN 자동 / EXCHANGE refundAmount==0 → Refund row 미생성

**RETURN**:
- 신규 핸들러 `refund/handler/ClaimPickedUpHandler` (E11 비동기 소비·`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`·기존 `refund/handler/ClaimApprovedHandler` 패턴 1:1)
- type=RETURN 한정 게이트·`RefundService.initiate(claimId, OrderItem.totalPrice)` 자동 호출 (`refund/handler/ClaimApprovedHandler` CANCEL 처리 패턴 1:1·D-94 Q7 amount=OrderItem.totalPrice 상속)
- D-94 Q7 박제 1줄 (배송비 환불 OOS) 그대로 상속·D-96 Q3 catch NotificationLog 적재 패턴 1:1

**EXCHANGE**:
- 본 트랙 차액 환불 OOS·동종 교환만 지원 (refundAmount==0)
- **Refund row 생성 자체 미수행** (1·2차 검토 흡수): RFN-1 (COMPLETED 전이 pg_refund_id 필수) 충돌 회피·state-machine §8 "환불 금액 발생 시" 단서를 "환불 금액 0 → Refund 미생성"으로 해석·CLM-3 정합 (생성 조건이지 의무 아님)
- EXCHANGE ClaimCompleted 직접 (Refund 경유 없음)·후속 트랙(차액 환불 트랙)에서 refundAmount>0 흐름 신설

**박제 1줄**: "EXCHANGE 차액 환불은 본 트랙 OOS·refundAmount==0 → Refund Aggregate 미생성·refundAmount>0 흐름은 후속 트랙(차액 환불·부분환불 트랙)에서 신설." (state-machine §8 단서 해석 명시·invariants RFN-4 신규 항목 미신설)

#### Q3: EXCHANGE 교환품 Delivery 생성 = Seller 출고 등록 시점 지연·DDL 무변경·carrier NOT NULL 유지

**옵션 α (ClaimApproved 자동 생성) 1·2·3차 검토 흡수로 철회**. 권장 β 채택:

```
ClaimApproved(EXCHANGE)
  ↓
ClaimPickedUp(E11)        ← Q1 수거 확인
  ↓
Seller registerExchangeShipment (별도 트랙·Seller Service)
  ↓
Delivery.create(orderItemId, carrier)
  ↓
Claim.attachExchangeDelivery(deliveryId, orderItemId)  ← Q13·WARN-5
  ↓
DeliveryService.markShipping(deliveryId, trackingNo)
  ↓
DeliveryCompleted(E5)
  ↓
OrderItem→EXCHANGED + Claim→COMPLETED (Q5)
```

**DDL 무변경** (V1__init.sql 라인 660-664 실측 인용):
- `delivery.carrier ENUM('CJ','HANJIN','POST','LOGEN') NOT NULL` 유지 (NULL 허용 불요)
- `delivery.tracking_no VARCHAR(100) NULL` 기존 유지

**READY 의미 정의 박제** (3차 검토 흡수·state-machine.md §6.1 본문 1줄 추가 의무):

> "READY: Delivery 행 생성 완료 상태. tracking_no·shipped_at은 비어 있을 수 있다. 출고 개시는 markShipping()에서 수행한다."

**markShipping 진입부 검증** (Entity invariant·DLV-3 정합):
- `trackingNo != null` 의무
- `shippedAt != null` 의무
- 위반 시 `IllegalStateException` throw

**Seller 출고 등록 endpoint 신설은 본 트랙 OOS** — Seller Service 트랙 이연·PR-2 EXCHANGE는 `DeliveryService.registerExchangeShipment(Long claimId, DeliveryCarrier carrier, String trackingNo)` Service 메서드만 신설·Controller는 별도.

#### Q4: CANCEL 게이트 제거 = α 게이트 제거 + 핸들러 type 분기·Service 분기 금지

**ClaimService.request() (실측 라인 97-99)**: `if (command.claimType() != ClaimType.CANCEL) throw new ClaimInvalidStateException(...)` 단락 제거·type 무관 진입 허용.

**(f) OrderItem 전이 검증 (실측 라인 109-112)**: `if (!orderItem.getItemStatus().canTransitionTo(OrderItemStatus.CANCEL_REQUESTED))` 하드코딩 → type별 전이 대상 분기 의무 (CANCEL_REQUESTED·RETURN_REQUESTED·EXCHANGE_REQUESTED).

**핸들러 4건 type 분기 (실측 라인)**:
- `claim/handler/ClaimRequestedHandler` 라인 41-45 CANCEL 게이트 제거·type별 OrderItem 전이 대상 분기 (CANCEL_REQUESTED·RETURN_REQUESTED·EXCHANGE_REQUESTED)·기존 멱등 가드 (CANCEL_REQUESTED 일치 확인 라인 52)는 type별 일치 분기로 확장
- `claim/handler/ClaimRejectedHandler` 라인 41-44 CANCEL 게이트 제거·Q7 스냅샷 기반 원복 (type 무관·`claim.previous_order_item_status` 사용)·기존 PAID 환원 (라인 65 `orderItem.changeStatus(OrderItemStatus.PAID)`) 철회 (D-90 Q3 의미 변경 동반)
- `claim/handler/ClaimCompletedHandler` 라인 41-44 CANCEL 게이트 제거·종결 전이 type 분기 (라인 56 하드코딩 `orderItem.changeStatus(OrderItemStatus.CANCELLED)`을 type별 CANCELLED·RETURNED·EXCHANGED 분기로 확장)·기존 멱등 가드 (CANCEL_REQUESTED 일치 확인 라인 50) type별 분기 확장
- `claim/handler/ClaimRefundCompletedHandler` 라인 47-52 CANCEL 게이트 제거·type 분기 (CANCEL·RETURN → markCompleted 호출·EXCHANGE → skip·Refund 미경유)

**Service 분기 금지** (1차 검토 흡수): primitive Service 메서드 (approve·reject·markCompleted) type 무관·분기는 핸들러 책임. D-92·D-93 횡단 원칙 1:1 정합 (재사용 2회차).

#### Q5: RETURN/EXCHANGE COMPLETED 진입 = RETURN 기존 핸들러 확장·EXCHANGE 신규 핸들러 분리

**RETURN 흐름** (PR-1):
```
ClaimPickedUp(E11)
  → refund/handler/ClaimPickedUpHandler (Q2 신규)
  → RefundService.initiate → RefundCompleted
  → claim/handler/ClaimRefundCompletedHandler (Q4 type 분기 RETURN 진입)
  → ClaimService.markCompleted → ClaimCompleted(E9)
  → claim/handler/ClaimCompletedHandler (Q4 type 분기 RETURNED 전이)
```

**EXCHANGE 흐름** (PR-2·3차 검토 흡수):
```
ClaimPickedUp(E11) → Seller 출고 등록 (별도 트랙)
  → DeliveryService.markShipping → DeliveryStarted(E4) → markDelivered → DeliveryCompleted(E5)
  → claim/handler/ExchangeDeliveryCompletedHandler (신규·@TransactionalEventListener AFTER_COMMIT·REQUIRES_NEW)
       - delivery_id로 Delivery 재조회
       - claim_id != null 분기
       - OrderItem (EXCHANGE_REQUESTED → EXCHANGED)
       - Claim.markCompleted → ClaimCompleted(E9)
  → order/handler/DeliveryCompletedHandler는 claim_id != null 시 early return (3차 검토 흡수)
  → notification/handler/NotificationDeliveryCompletedHandler는 claim_id 분기 (교환 배송 완료 vs 일반 배송 완료 메시지)
```

**소비 순서** (3차 검토 흡수): OrderItem→EXCHANGED → Claim COMPLETED. 역순 금지 (Claim COMPLETED 이후 OrderItem 변경은 CLM-1 의미 약화).

**핸들러 트랜잭션 정책**: `claim/handler/ExchangeDeliveryCompletedHandler`는 `@TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)` (기존 `ClaimRefundCompletedHandler` 라인 39-40 패턴 1:1·D-75·D-90 Q1).

**E5 페이로드 무수정** (3차 검토 흡수): Delivery 재조회로 claim_id 분기·E12 신설 불요·E5 재사용.

#### Q6: ClaimReasonCode +4값 추가 = α

`PRODUCT_DEFECT`·`DAMAGED_ON_ARRIVAL`·`WRONG_PRODUCT`·`DELIVERY_DELAY` enum 추가 (Javadoc·D-89 Q5 예고 흡수). `claim.reason_code`는 V1__init.sql 라인 697 `VARCHAR(50) NOT NULL` 무제약 → DDL 마이그레이션 불요. 프론트 constants 동기화 동반. PR-1 RETURN 동반.

#### Q7: ClaimRejected 원복 = 스냅샷 기반·type 무관 (CANCEL 포함·D-90 Q3 의미 변경)

**고정 DELIVERED/PAID 복원 철회** (1·2차 검토 흡수). 모든 Claim 요청 시점 OrderItem 상태를 스냅샷으로 저장·REJECTED 시 스냅샷 복원.

**적용 범위**: CANCEL·RETURN·EXCHANGE 전건 (domain-events §E8 "type 예외 없음"·2차 검토 흡수).

**D-90 Q3 의미 변경 (실측 발견)**:
- `OrderItemStatus.canTransitionTo` 라인 51 `CANCEL_REQUESTED → CANCELLED || PAID` 기정의·D-90 Q3 claim-lock release PAID 고정 환원·"과거 상태 복원 아닌 unlock"으로 박제
- 본 결정으로 의미 변경: **CANCEL_REQUESTED → 스냅샷 상태**로 변경 (PAID·PREPARING 등 스냅샷 기반 복원)
- canTransitionTo 매트릭스 확장: `CANCEL_REQUESTED → CANCELLED || PAID || PREPARING` 신설 의무·`RETURN_REQUESTED → RETURNED || SHIPPING || DELIVERED` 신설·`EXCHANGE_REQUESTED → EXCHANGED || DELIVERED` 신설
- D-90 Q3 §주석 갱신 의무 (state-machine.md §3 §주석·invariants.md §2.13 CLM-2 비고): "Track 14·D-98 Q7 스냅샷 기반 원복으로 의미 변경·claim-lock release 단어는 더 이상 의미 부재"

#### Q8: NotificationLog = α 재사용 + PICKUP_CONFIRMED 1건 추가

기존 7 templateCode 재사용·메시지 문구 type 분기:
- `NotificationService.recordClaimApproved` 라인 91-95 메시지 "클레임 ... 승인되었습니다" → type별 분기 ("취소 요청이 승인되었습니다"·"반품 요청이 승인되었습니다"·"교환 요청이 승인되었습니다")
- `NotificationService.recordClaimCompleted` 라인 108-112 메시지 동일 패턴

**E11 ClaimPickedUp 알림 추가**:
- 신규 templateCode `PICKUP_CONFIRMED = "TPL_PICKUP_CONFIRMED"` 1건 추가 → 8건 (NotificationTemplateCodes Enum 승격 임계 ≥10 미도달·D-95 WARN-10-α 정합·여유 2건)
- 신규 핸들러 `notification/handler/NotificationClaimPickedUpHandler` (E11 비동기 소비·기존 6 핸들러 패턴 1:1)
- 신규 메서드 `NotificationService.recordClaimPickedUp(ClaimPickedUp event)`·`resolveClaimRecipient` 재사용

#### Q9: D-92/D-93 wrapper 재사용 2회차 = 재사용 + confirmPickup wrapper 신설

**기존 6 wrapper 무변경 재사용** (실측 라인): `ClaimService.approve` (라인 137-145)·`reject` (라인 158-166)·`approveBySeller` (라인 179-185)·`rejectBySeller` (라인 195-201)·`approveByAdmin` (라인 215-217)·`rejectByAdmin` (라인 229-231) — type·actor 무관·RETURN/EXCHANGE 그대로 통과.

**신규 confirmPickup 3건** (D-92 패턴 1:1·재사용 2회차):
- primitive: `ClaimService.confirmPickup(Long claimId, LocalDateTime pickedUpAt)` — actor·type 비의존·picked_up_at 설정 + ClaimPickedUp 발행
- Seller wrapper: `ClaimService.confirmPickupBySeller(Long claimId, Long sellerId, LocalDateTime pickedUpAt)` — `authorizeSellerAccess` (라인 297-304) 경유
- Admin wrapper: `ClaimService.confirmPickupByAdmin(Long claimId, LocalDateTime pickedUpAt)` — Admin 전체 접근

D-97 §후속 "D-92·D-93 횡단 원칙 재사용 2회차" 명시 이행.

#### Q10: PR 분할 = β RETURN PR-1 + EXCHANGE PR-2

**PR-1 RETURN** (`feat/track-14-claim-return`):
- Q1·Q2 RETURN·Q4·Q5 RETURN·Q6·Q7·Q8 PICKUP_CONFIRMED·Q9·Q11·Q12·D-90 Q3 의미 변경
- 신규 컬럼 마이그레이션: `claim.picked_up_at`·`claim.previous_order_item_status` 동반 (PR-1에서 일괄)
- 외부 검토 의무 (S급·1·2·3차 완료)

**PR-2 EXCHANGE** (`feat/track-14-claim-exchange`):
- Q3 (Seller 출고 등록 진입점)·Q5 EXCHANGE (신규 ExchangeDeliveryCompletedHandler·order 핸들러 early return·Notification 분기)·Q13 (delivery.claim_id FK·attachExchangeDelivery)
- Seller 출고 등록 Controller·endpoint는 본 트랙 OOS·Seller Service 트랙 동반
- 외부 검토 의무 (S급)

1-PR 단독 패턴 5회차 비대화 회피·기조 1·4 정합.

#### Q11: claim.previous_order_item_status 컬럼 신설 = VARCHAR·nullable 금지·PR-1 동반

**컬럼** (Flyway 마이그레이션 `V{N}__add_claim_pickup_and_previous_status.sql` 동반):
```sql
ALTER TABLE claim
  ADD COLUMN picked_up_at DATETIME(6) NULL COMMENT '수거 확인 시각·Q1',
  ADD COLUMN previous_order_item_status VARCHAR(20) NOT NULL COMMENT '요청 시점 OrderItem 상태 스냅샷·REJECTED 원복용·Q11';
```

**타입 근거** (2차 검토 흡수·실측 V1__init.sql 라인 697): claim.reason_code VARCHAR(50) 무제약 운영 일관성·ENUM은 OrderItem enum 변경 시 DDL 동기 비용.

**nullable 금지**: 스냅샷 없는 Claim 생성 금지·`ClaimService.request()` 시점 (실측 라인 115-122 `Claim.create` 호출 직전) OrderItem 상태 스냅샷 캡처 후 status 전이·Claim save.

**Claim.create 시그니처 확장**: 현 시그니처 (실측 라인 78-84) `create(Long orderItemId, ClaimType type, String reasonCode, String reasonDetail, Long requestedBy, LocalDateTime requestedAt)` → 신규 인자 `OrderItemStatus previousOrderItemStatus` 추가 (마지막 위치).

**마이그레이션 백필**: V1__init.sql 라인 692-712 claim 테이블 시드 INSERT 0건 실측 확인 → 운영 데이터 0건 가정 정당. ALTER TABLE 직접 적용·NOT NULL 충족·기존 행 0건이므로 백필 SQL 불요.

#### Q12: CLM-5 활성성 정의 = 유지 + picked_up_at 무관 비고 1줄

`invariants.md §2.13 CLM-5` 본문 무변경·비고 추가:

> "활성 정의(REQUESTED·APPROVED)는 picked_up_at 설정 여부와 무관하다. picked_up_at은 milestone 데이터로 활성성 판단에 영향 없음."

CLM-2 (REJECTED 재요청은 새 Claim 행)·RETURN→REJECTED 후 EXCHANGE 신규 요청은 활성 종결 후 가능·정합 유지.

#### Q13: WARN-5 EXCHANGE Delivery 식별 + 일관성 검증 = delivery.claim_id NULLABLE FK + Claim.attachExchangeDelivery 진입점

**DDL 변경** (PR-2 EXCHANGE 동반·`V{N+1}__add_delivery_claim_id.sql`):
```sql
ALTER TABLE delivery
  ADD COLUMN claim_id BIGINT NULL COMMENT 'EXCHANGE 교환품 Delivery 참조·일반 주문은 NULL·Q13',
  ADD CONSTRAINT fk_delivery_claim FOREIGN KEY (claim_id) REFERENCES claim (id) ON DELETE RESTRICT ON UPDATE CASCADE;
```

**INDEX 명시 선언 제거** (외부 검토 2차·R5 흡수): V1 헤더 정책(line 13 "FK 자식 컬럼 인덱스는 InnoDB 자동 생성에 의존") 정합·`fk_delivery_claim` InnoDB 자동 INDEX 의존. delivery 테이블 기존 `fk_delivery_order_item`과 동일 패턴.

**UNIQUE 금지** (2·3차 검토 흡수): 향후 재출고 가능성 차단 회피.

**Aggregate 행위 진입점** (3차 검토 흡수·외부 검토 1차 Q3 신규 의제 P1 흡수·Claim 귀속):
- `Claim.attachExchangeDelivery(Long deliveryId, Long deliveryOrderItemId)` primitive 메서드
- 내부 검증 (양자 의무·위반 시 `ClaimInvalidStateException` throw·로그+skip 금지):
  - `this.orderItemId == deliveryOrderItemId` — Delivery-OrderItem 일관성
  - `this.type == ClaimType.EXCHANGE` — Aggregate 불변식·API 실수로 RETURN/CANCEL 연결 차단 (외부 검토 1차 신규 의제 P1)
- 검증 시점: create 직전 (커밋 직전 검증 금지)

**RETURN/CANCEL/일반 주문**: `claim_id == null`·검증 우회 (`if (claimId == null) skip`).

**aggregate-boundary.md §2.5 Delivery 외부 ID 참조에 Claim.id 추가 의무**.

### WARN 처치 (recon-report §12 매트릭스)

#### P0 처치
- WARN-1 수거 확인 미설계 → Q1 결정 (E11 + claim.picked_up_at)
- WARN-2 EXCHANGE Delivery 생성 진입점 부재 → Q3 결정 (Seller 출고 등록 시점 지연·DDL 무변경)
- WARN-3 RETURN/EXCHANGE Refund 트리거 미설계 → Q2 결정 (RETURN 자동·EXCHANGE refundAmount==0 Refund 미생성)
- **WARN-4 (1차 신규)** Refund/Claim COMPLETED 중복 가능성 → CLM-1 상태 가드 단독·`ClaimService.markCompleted` 라인 270-274 멱등 가드 (이미 COMPLETED 시 no-op + log.info) 단독 유지·completed_at 컬럼 추가 불요 (2차 검토 흡수)

#### P1 처치
- WARN-4 (recon §12 원번호) CANCEL 게이트 5중 → Q4 결정 (게이트 5건 라인 인용)
- WARN-5 (recon §12 원번호) RETURN/EXCHANGE COMPLETED 진입 경로 → Q5 결정
- WARN-6 (recon §12 원번호) ClaimReasonCode +4값 → Q6 결정
- WARN-7 (recon §12 원번호) 기존 통합 테스트 회귀 → Q4·Q5·Q7 구현 시 단언 갱신 의무
- **WARN-5 (1차 신규)** EXCHANGE Delivery 식별 → Q13 결정 (delivery.claim_id FK)

#### P2 처치
- WARN-8 NotificationTemplateCode 임계 → Q8 결정 (8건·임계 ≥10 미도달·여유 2건)
- WARN-9 D-92/D-93 wrapper 재사용 2회차 → Q9 결정
- WARN-10 ClaimRejected 원복 매트릭스 → Q7·Q11 결정 (스냅샷 기반·고정 DELIVERED/PAID 철회·D-90 Q3 의미 변경)

### 회귀 테스트 박제 (3차 검토 권고)

**EXCHANGE DeliveryCompleted 중복 재전달 멱등 검증** (PR-2 EXCHANGE 통합 테스트 1건 의무):
- 동일 E5 이벤트 2회 발행 → OrderItem EXCHANGED 상태 유지·Claim COMPLETED 유지·NotificationLog 중복 없음
- 멱등 보장 메커니즘: `OrderItemStatus.canTransitionTo` 라인 56 종결 상태 `false` 자연 차단·`Claim.markCompleted`의 `transitionTo` 라인 162-165 `canTransitionTo` 위반 `ClaimInvalidStateException` 자연 차단·`ClaimService.markCompleted` 멱등 가드 라인 270-274 no-op
- NotificationLog 멱등은 D-95 Q6 박제 1줄 가정 하 ApplicationEvent 인메모리 publisher 재전달 자연 차단

### 횡단 원칙 (D-92·D-93 carry-over 2회차)

D-97 §후속 명시 이행. Q9 confirmPickup primitive + Seller/Admin wrapper 신설·Q4 Service 분기 금지·핸들러 분기 응집·1·2회차 패턴 1:1 재사용.

### 외부 검토 흡수 흐름

- **1차 외부 검토**: 11 의제 + 추천안 송부. 수용: Q0·Q1·Q4·Q6·Q8·Q9·Q10 7건. 부분 수용·반박: Q2·Q3·Q5·Q7. 신규 의제: Q11·Q12·WARN-4·WARN-5.
- **Claude.ai 2차 응답**: Q2 (b) Refund row 미생성·Q3 (β) DDL 무변경·Q5 (E5 재사용)·Q7 스냅샷·Q11 VARCHAR·Q12 정의 유지·WARN-4 CLM-1 단독·WARN-5 delivery.claim_id FK.
- **2차 외부 검토**: 본문 결정 전건 합의·Q3 누락 (READY 정의)·Q5 누락 (소비 순서)·WARN-5 누락 (일관성 검증) 3건 신규 의제.
- **Claude.ai 3차 응답**: Q3 (a) READY 정의·Q5 (b) 핸들러 분리·WARN-5 (d) Claim 진입점.
- **3차 외부 검토**: 전건 합의·회귀 테스트 1건 권고·"남은 설계 위험 거의 없음" 평가.
- **D-90 Q3 의미 변경**: Claude.ai 정찰 보강 (Sonnet 4.6 패턴·MCP read) 시점 실측 발견·`OrderItemStatus.canTransitionTo` 라인 51 `CANCEL_REQUESTED → CANCELLED || PAID` 기정의 확인·D-90 Q3 claim-lock release PAID 고정 환원 의미를 Q7 스냅샷 기반 원복으로 변경 박제·외부 검토 미경유 (실측 정합 정정).

### 영향 범위

#### PR-1 RETURN — 신규 파일 (8건)
- backend/src/main/resources/db/migration/V{N}__add_claim_pickup_and_previous_status.sql (claim.picked_up_at·claim.previous_order_item_status 컬럼 추가)
- backend/src/main/java/com/zslab/mall/claim/event/ClaimPickedUp.java (E11·record·payload: claimId·claimPublicId·orderItemId·claimType·pickedUpAt·occurredAt)
- backend/src/main/java/com/zslab/mall/refund/handler/ClaimPickedUpHandler.java (E11 비동기 소비·AFTER_COMMIT·REQUIRES_NEW·type=RETURN 게이트·RefundService.initiate 호출·D-96 Q3 catch NotificationLog 적재 패턴)
- backend/src/main/java/com/zslab/mall/notification/handler/NotificationClaimPickedUpHandler.java (E11 비동기·AFTER_COMMIT·REQUIRES_NEW·D-95 패턴 1:1)
- backend/src/test/java/com/zslab/mall/claim/service/ClaimServiceConfirmPickupTest.java (단위·confirmPickup 정상·멱등·권한)
- backend/src/test/java/com/zslab/mall/refund/handler/ClaimPickedUpHandlerTest.java (단위·type=RETURN 자동 트리거·CANCEL/EXCHANGE skip)
- backend/src/test/java/com/zslab/mall/claim/integration/ClaimReturnIntegrationTest.java (e2e·NO @Transactional·LT-02·D-91·요청→승인→픽업→환불→RETURNED 전체 루프)
- backend/src/test/java/com/zslab/mall/claim/integration/ClaimRejectedRestoreIntegrationTest.java (스냅샷 기반 원복·CANCEL/RETURN/EXCHANGE 3 케이스)

#### PR-1 RETURN — 수정 파일 (production 10건)
- backend/src/main/java/com/zslab/mall/claim/entity/Claim.java (picked_up_at·previous_order_item_status 필드 추가·confirmPickup 메서드 신설·create 시그니처 확장·attachExchangeDelivery는 PR-2)
- backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java (request CANCEL 게이트 라인 97-99 제거·(f)단락 라인 109-112 type 분기·confirmPickup primitive 신설·confirmPickupBySeller·confirmPickupByAdmin wrapper·스냅샷 저장)
- backend/src/main/java/com/zslab/mall/claim/enums/ClaimReasonCode.java (PRODUCT_DEFECT·DAMAGED_ON_ARRIVAL·WRONG_PRODUCT·DELIVERY_DELAY 4값 추가·6→10건)
- backend/src/main/java/com/zslab/mall/claim/handler/ClaimRequestedHandler.java (라인 41-45 CANCEL 게이트 제거·type별 OrderItem 전이 대상 분기·라인 52 멱등 가드 type별 확장)
- backend/src/main/java/com/zslab/mall/claim/handler/ClaimRejectedHandler.java (라인 41-44 CANCEL 게이트 제거·라인 65 PAID 환원 철회·Q7 스냅샷 기반 원복·type 무관)
- backend/src/main/java/com/zslab/mall/claim/handler/ClaimCompletedHandler.java (라인 41-44 CANCEL 게이트 제거·라인 50 멱등 가드 type별 확장·라인 56 하드코딩 CANCELLED → type별 CANCELLED·RETURNED·EXCHANGED 분기)
- backend/src/main/java/com/zslab/mall/claim/handler/ClaimRefundCompletedHandler.java (라인 47-52 CANCEL 게이트 제거·type 분기 CANCEL/RETURN markCompleted 호출·EXCHANGE skip)
- backend/src/main/java/com/zslab/mall/notification/service/NotificationService.java (recordClaimPickedUp 신설·recordClaimApproved 라인 91-95 메시지 type 분기·recordClaimCompleted 라인 108-112 메시지 type 분기·resolveClaimRecipient 재사용)
- backend/src/main/java/com/zslab/mall/notification/template/NotificationTemplateCodes.java (PICKUP_CONFIRMED = "TPL_PICKUP_CONFIRMED" 상수 8번째)
- backend/src/main/java/com/zslab/mall/order/enums/OrderItemStatus.java (라인 50 `CANCEL_REQUESTED → CANCELLED || PAID || PREPARING` 확장·라인 52 `RETURN_REQUESTED → RETURNED || SHIPPING || DELIVERED` 신설·라인 53 `EXCHANGE_REQUESTED → EXCHANGED || DELIVERED` 신설·D-90 Q3 의미 변경 동반 Javadoc 갱신)

#### PR-1 RETURN — 문서 (3건)
- docs/architecture-baseline/state-machine.md §3 (OrderItem RETURN_REQUESTED·EXCHANGE_REQUESTED·CANCEL_REQUESTED 원복 전이 본문 추가·D-90 Q3 의미 변경 §주석 갱신)
- docs/architecture-baseline/domain-events.md §2 (E11 ClaimPickedUp 신설·E8 원복 본문 갱신·E9 type별 종결 본문 갱신)
- docs/architecture-baseline/invariants.md §2.13 (CLM-5 비고 1줄 추가·CLM-2 §주석 갱신·D-90 Q3 의미 변경 동반)

#### PR-2 EXCHANGE — 신규 파일 (4건)
- backend/src/main/resources/db/migration/V{N+1}__add_delivery_claim_id.sql (delivery.claim_id FK·INDEX)
- backend/src/main/java/com/zslab/mall/delivery/service/DeliveryService.java 신규 메서드 추가 (registerExchangeShipment·Claim.attachExchangeDelivery 호출·markShipping 진입부 NOT NULL 검증 보강)
- backend/src/main/java/com/zslab/mall/claim/handler/ExchangeDeliveryCompletedHandler.java (E5 비동기 소비·@TransactionalEventListener AFTER_COMMIT·REQUIRES_NEW·claim_id != null 분기·OrderItem→EXCHANGED·Claim→COMPLETED)
- backend/src/test/java/com/zslab/mall/claim/integration/ClaimExchangeIntegrationTest.java (e2e·요청→승인→픽업→Seller 출고 등록→배송 완료→EXCHANGED·중복 E5 멱등 회귀 1건 포함·LT-02·D-91)

#### PR-2 EXCHANGE — 수정 파일 (production 4건)
- backend/src/main/java/com/zslab/mall/claim/entity/Claim.java (attachExchangeDelivery primitive 메서드 추가·orderItemId 일관성 검증·throw)
- backend/src/main/java/com/zslab/mall/delivery/entity/Delivery.java (claim_id 필드 추가·setter는 attach 메서드 경유 한정·markShipping 진입부 trackingNo·shippedAt NOT NULL 검증)
- backend/src/main/java/com/zslab/mall/order/handler/DeliveryCompletedHandler.java (claim_id != null 시 early return·Delivery 재조회)
- backend/src/main/java/com/zslab/mall/notification/handler/NotificationDeliveryCompletedHandler.java (claim_id 분기·교환 배송 완료 메시지·Delivery 재조회 중복 허용)

#### PR-2 EXCHANGE — 문서 (2건)
- docs/architecture-baseline/state-machine.md §6.1 (READY 의미 정의 1줄 박제·markShipping 진입부 검증 명시)
- docs/architecture-baseline/aggregate-boundary.md §2.5 (Delivery 외부 ID 참조에 Claim.id 추가)

#### 무변경 확정
application.yml·build.gradle.kts·Refund 도메인 전건 (RefundService·Refund Entity·RefundStatus·RefundRepository·RefundWebhookIntegrationTest)·Payment 도메인 전건·Inventory 도메인 전건·기존 Notification 핸들러 6건·기존 NotificationService 메서드 시그니처 (recordOrderPlaced·recordPaymentCompleted·recordRefundFailed·recordDeliveryStarted·recordDeliveryCompleted)·DeliveryCarrier·DeliveryRepository·DeliveryRepositoryTest·기존 이벤트 10건 payload (E11 신규만)·ClaimType DDL ENUM·ClaimStatus 전이 매트릭스·DTO ClaimRequestRequest·Controller 3건·Claim 승인/거부 wrapper 6건.

#### 회귀 예상
PR-1 RETURN: 393 baseline → 신규 합산 ≥405 (단위 ≥6·통합 ≥3·기존 회귀 단언 갱신 7~10건 의무·canTransitionTo 매트릭스 확장 영향 통합 회귀)
PR-2 EXCHANGE: PR-1 머지 후 baseline 기준 → 신규 합산 ≥3·중복 E5 멱등 회귀 1건 포함

### 대안 검토 (기각)

- Q0 PR 단일: 1-PR 5회차 비대화·EXCHANGE Q3 DDL/Aggregate 변경과 RETURN 흐름 분리 가능·기각.
- Q1 (a) OrderItem 신규 상태: 12값 매트릭스 폭증·DDL ENUM 마이그레이션·canTransitionTo 전건 재설계·기조 4 위배·기각.
- Q1 (b) ClaimStatus 보조 상태: A분류 4값 확장·CANCEL 무관 오염·기각.
- Q1 (c) ReturnDelivery 신규 Aggregate: 과잉개발·기각.
- Q2 EXCHANGE refundAmount=0 Refund row 생성: RFN-1 (pg_refund_id 필수) 충돌·PG 0원 환불 의미 부재·기각 (1·2차 검토 흡수).
- Q3 α ClaimApproved 자동 Delivery 생성: carrier·trackingNo NULL 상태 행 의미 모호·기각 (1차 검토 흡수).
- Q3 carrier NULL 허용 DDL 변경: 일반 주문 Delivery와 규칙 분기·DLV-2 의미 약화·기각 (2·3차 검토 흡수).
- Q4 β RETURN/EXCHANGE 전용 신규 핸들러 신설: 핸들러 4→7건 폭증·기각.
- Q5 E5 페이로드 확장 (claim_id 추가): D-30 사실 통지 위배·D-94 Q7 β·D-95 Q5 β 기각 패턴 정합·기각 (3차 검토 흡수).
- Q5 E12 ExchangeDelivered 신규 이벤트: E5 재사용 가능·이벤트 폭증·기각 (1차 검토 흡수).
- Q5 (a) order/handler 내부 통합 분기: Order 핸들러 책임 과밀·기각 (3차 검토 흡수).
- Q7 고정 DELIVERED/PAID 복원: domain-events §E8 "스냅샷 기준" 위배·D-90 Q3 PAID 고정도 제거 대상·기각 (1차 검토 흡수).
- Q11 ENUM 컬럼: OrderItem enum 변경 시 DDL 동기 비용·기각 (2차 검토 흡수).
- Q11 nullable 허용: 스냅샷 없는 Claim 생성 허용·원복 불완전·기각.
- WARN-4 completed_at 컬럼 추가 가드: CLM-1 canTransitionTo 자연 차단·기각 (2차 검토 흡수).
- WARN-5 delivery.purpose ENUM: 분류가 아니라 연관 관계·claim_id FK로 충분·기각 (2차 검토 흡수).
- WARN-5 (c) DB Trigger 검증: 운영 데이터 보호 룰 비권장·기각.
- WARN-5 (e) 통합 테스트만: 런타임 무방어·기각.

### 관련 결정

D-01·D-05·D-06·D-29·D-30·D-67·D-74·D-75·D-86 Batch-3b·D-88·D-89·D-90 (Q3 의미 변경·**본 결정으로 갱신**)·D-91·D-92·D-93·D-94 (Q3·Q7 상속)·D-95·D-96 (Q3 catch NotificationLog 패턴 1:1 재사용)·D-97 (§후속 carry-over 종결·Q5 EXCHANGE Delivery 흐름)·LT-02·invariants §2.11·§2.12·§2.13·§2.13.1·state-machine §2·§3·§6.1·§8·aggregate-boundary §2.5·domain-events §2 E5·E7·E8·E9·E11(신설)·docs/track-14/recon-report.md.

### 후속 트랙

- **EXCHANGE 차액 환불·부분환불 트랙**: Q2 박제 1줄 ("refundAmount>0 흐름은 후속 트랙") 진입점·invariants RFN-4 또는 PAY-2 본문 재정의.
- **Seller Service 트랙**: Q3 Seller registerExchangeShipment Controller·endpoint·DTO @ValidEnum 3·4층.
- **Track 15+ Observability**: D-90·D-94 Q6·D-95 Q6·D-97 Q8 박제 1줄 통합 재평가·이벤트 핸들러 멱등성 표준화.
- **외부 택배 어댑터 트랙**: Q1 발행 주체 확장·자동 픽업 확인.
- **자동 구매확정 타이머 트랙**: DELIVERED + N일 → CONFIRMED.
- **Inventory 차감/복구 Order-Claim 연동 트랙**: E9 ClaimCompleted Inventory 복구·EXCHANGE D-08 트랜잭션 분리.
- **Spring Security 트랙**: X-*-Id stub 3종 일괄 대체·D-93 Q11 wrapper 유지 전략.

### 후속

1. 본 결정 박제 = 사용자 직접 처리 (D-94·D-95·D-96·D-97 패턴 5회차).
2. **PR-1 RETURN** 브랜치 `feat/track-14-claim-return` 생성 (main HEAD 75188e8 기준).
3. 가드 5 사전 통지: PR-1 신규 8 + 수정 production 10 + 문서 3 + Flyway 1·PR-2 신규 4 + 수정 production 4 + 문서 2 + Flyway 1 — Claude Code 구현 프롬프트 시점 일괄 통지.
4. TDD 우선 (단위 → 통합)·BUILD SUCCESSFUL·PR-1 신규 ≥9 PASS·기존 회귀 0 (단언 갱신 후).
5. GitHub Web UI PR 생성·Base: main·Compare: feat/track-14-claim-return·외부 검토 1·2·3차 완료·S급.
6. PR-1 머지 후 PR-2 EXCHANGE 진입·동일 절차.
7. PR-1·PR-2 양 머지로 Track 14 종결·D-88·D-89·D-90·D-93·D-94·D-95·D-97 §후속 RETURN/EXCHANGE carry-over 4회 연속 영구 해소·state-machine §2/§8 RETURN/EXCHANGE 흐름 종결·D-90 Q3 의미 변경 동반 박제 완료.
8. **라이브 트랩 박제 (D-98 PR-1 구현 시점 실측 발견)**: V6 ALTER TABLE `previous_order_item_status VARCHAR(20) NOT NULL` 컬럼 추가 시 기존 claim 시드 SQL 7파일 전건 INSERT 차단 발생. 시드 SQL에 `previous_order_item_status 'PAID'` 추가로 해소. 단건 트랩 (LT 카탈로그 신설 임계 ≥3 미도달·D-82 정합)·향후 마이그레이션 트랙 진입 시 동일 패턴 재발 시 LT-04 후보로 박제 검토.

---

## D-99. Track 15 Seller Service · EXCHANGE 출고 등록 Seller endpoint 신설 · D-97·D-98 §후속 carry-over 종결 [ACTIVE]

**결정일**: 2026-06-30
**관련**: Track 15 / D-40 (URL 액터 중립·Controller 액터 분리)·D-64·D-65 (publicId 해소)·D-74 (네이밍)·D-92 (횡단 원칙·권한 검증 Service 진입점·primitive actor 비의존)·D-93 (SellerActorResolver seam·X-Seller-Id 임시 헤더·재사용 3회차)·D-97 §후속 (Delivery Controller 이연)·D-98 Q3·Q13 (registerExchangeShipment primitive·Claim.attachExchangeDelivery 검증·delivery.claim_id NULLABLE FK·UNIQUE 금지·재출고 허용) / invariants §2.12 DLV-1~3 / state-machine §6.1 (READY 의미) / docs/track-15/recon-report.md §1~§18

### 배경

D-97·D-98 §후속에 명시된 "Seller Service 트랙: registerExchangeShipment Controller·endpoint·DTO @ValidEnum 3·4층 동반" carry-over를 본 트랙에서 종결한다.

**정찰 결과** (docs/track-15/recon-report.md §1~§18 실측 인용):

- Seller 도메인 Controller·Service 0건 (Track 4 read-only·D-59 박제·§2.1)
- 기존 Seller endpoint 2건 (SellerClaimController approve·reject·§2.2)
- `DeliveryService.registerExchangeShipment(claimId, carrier, trackingNo)` Service 완전 구현 (§3.1·DeliveryService.java L89-107)·Controller 0건
- DeliveryService 주입 전건: `DeliveryRepository`·`ClaimRepository`·`ApplicationEventPublisher` 3건 (§14.2·L31-33·OrderItemRepository 부재)
- D-40 본문 재실측: `/api/buyer/...`·`/api/seller/...` 액터 prefix 자체 금지·버전 prefix `/api/v1/`는 별개 (§12.1)
- `ClaimService.authorizeSellerAccess` private 가시성·`OrderItem.sellerId == sellerId` 경유 (§14.6·ClaimService.java L353-360)
- `Claim.attachExchangeDelivery`는 type·orderItemId 검증 전용 void 메서드·`Claim.exchangeDeliveryId` 필드 부재 (§14.4·Claim.java L202-215)
- V1 `delivery.tracking_no VARCHAR(100) NULL` (§14.3·V1__init.sql L647)

1·2·3차 외부 검토 수렴 후 결정 12건 박제.

### 결정 (12건)

#### Q0: 트랙 식별자 = α Track 15 = Seller Service

본 트랙은 D-97·D-98 §후속 carry-over 종착지. D-94 Q0 β·D-95 Q1 α·D-97 Q0 β·D-98 Q0 α 선례 5회차.

**범위**:
- 포함: Seller Delivery API (EXCHANGE 출고 등록 단독)
- 제외: Admin override·Spring Security 정식 도입·markShipping/markDelivered Seller endpoint·일반 주문 Delivery 생성 진입점·재출고 흐름

#### Q1: endpoint URL = α `POST /api/v1/claims/{claimPublicId}/register-exchange-shipment`

**채택**: claim 하위 리소스·동사형 sub-path (기존 SellerClaimController `/approve`·`/reject` 패턴 일관성)·`exchange-shipment` 명시로 일반 주문 배송 등록과 의미 충돌 회피 (외부 검토 2차 흡수).

**기각**:
- `/api/v1/seller/deliveries/...` (β·D-40 액터 prefix 금지 위반)
- `/api/v1/deliveries/{claimPublicId}/...` (γ·base path와 path variable 의미 불일치)
- `/api/v1/claims/{claimPublicId}/exchange-shipment` (명사형·기존 동사형 패턴과 비일관)
- `/api/v1/claims/{claimPublicId}/register-shipment` (일반 주문 배송 등록과 의미 충돌·외부 검토 2차 권고 흡수)

**근거** (정찰 §12.1 D-40 본문 인용·SellerClaimController.java L19-27 Javadoc 인용):
> "URL은 액터 중립이다(D-92 Q4 β): base path /api/v1/claims를 BuyerClaimController와 공존하며 HTTP method·하위 경로(/approve·/reject)로 분리한다."

`Claim.attachExchangeDelivery` (D-98 Q13) Aggregate 진입점이 Claim이므로 base path 의미 정합.

#### Q2: markShipping/markDelivered Seller endpoint 동반 = α 단독·일반 주문 Delivery 트랙 이연

**채택**: 본 트랙은 registerExchangeShipment Controller 단독. markShipping/markDelivered Seller endpoint는 본 트랙 OOS.

**근거** (외부 검토 2차 근거 재정합 흡수): 일반 주문 Delivery 생성 진입점 자체가 현재 부재 (Order Aggregate 측 Delivery 생성 핸들러 미구현)·markShipping/markDelivered Controller만 추가해도 호출 가능한 일반 주문 Delivery 데이터 부재. 일반 주문 Delivery 생성 트랙 시점에 동반 도입이 정합.

**박제 1줄**: "markShipping/markDelivered Seller endpoint는 일반 주문 Delivery 생성 진입점 신설 트랙 시점에 동반 도입·본 트랙은 EXCHANGE 출고 등록 단독."

#### Q3: DTO 설계 = α `RegisterExchangeShipmentRequest(DeliveryCarrier carrier, @NotBlank @Size(max=100) String trackingNo)`

**채택**: record·DeliveryCarrier enum 직접 바인딩·`@Size(max=100)`은 V1 `delivery.tracking_no VARCHAR(100)` 정합 (정찰 §14.3 실측 인용).

```java
public record RegisterExchangeShipmentRequest(
    DeliveryCarrier carrier,
    @NotBlank @Size(max=100) String trackingNo
) {}
```

**근거**: ClaimRequestRequest 패턴 실측 (enum 직접 바인딩·Jackson 역직렬화 실패 자동 400)·Service 변환 책임 회피.

#### Q4: @ValidEnum 미신설 = β Jackson 역직렬화 400 패턴 재사용

**채택**: 커스텀 Constraint 어노테이션 신설 없음. Jackson enum 역직렬화 실패 시 자동 400 (Spring Boot 기본 동작).

**근거**:
- 전 프로젝트 @ValidEnum 실사용 0건
- ConstraintValidator·메시지 리소스·테스트 동반 = 단일 트랙 최초 도입 과잉개발 (기조 4)
- D-97 Q3 §기각 사유의 "@ValidEnum 3·4층 동반 의무 발생"은 경고였고 의무 박제 부재
- CLAUDE.md "@Pattern 또는 커스텀 @ValidEnum 검증" = 옵션 표현 ("또는")

**박제 1줄**: "@ValidEnum 신설은 enum alias·case-insensitive·legacy 입력 요구 발생 시점에 별도 트랙으로 재평가."

#### Q5: Controller publicId 해소 = α 기존 SellerClaimController 패턴 1:1 재사용

**채택**: Controller에서 `claimRepository.findByPublicId(claimPublicId)` → `claim.getId()` 해소 후 ClaimService wrapper에 `Long claimId` 전달.

**근거** (정찰 §14.5·SellerClaimController.java L24 Javadoc 인용):
> "HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만 수행하며 권한/상태 판단은 ClaimService 책임이다."

D-92 "권한 검증 = Service 진입점" 원칙 위배 없음 — publicId 해소는 식별자 변환 단일 책임·권한 검증은 Service 내부 `authorizeSellerAccess`에서 수행.

**기각**: β `Service.registerExchangeShipmentByPublicId(String publicId, ...)` 오버로드 (Service 시그니처 분기·동일 행위 2 메서드 책임 모호).

#### Q6: SellerActorResolver 재사용 = α 1:1 재사용 (3회차)

기존 `HeaderSellerActorResolver` (D-93 seam·X-Seller-Id 헤더 stub) 1:1 재사용. 별도 resolver 신설 없음.

#### Q7: PR 분할 = α 단일 PR

`feat/track-15-seller-delivery` 단독 PR. 범위 = Controller 1 + DTO 2 (Request·Response) + ClaimService wrapper + DeliveryService 가드 + 테스트 ~8건. D-87~D-98 1-PR 단독 패턴 9회차.

#### Q8: Admin Service = β 별도 트랙 이연

`AdminDeliveryController` 본 트랙 OOS. Admin Service 별도 트랙 진입 시점에 D-93 AdminActorResolver 패턴으로 신설·D-92 Q3-sub a‴ 자연 재사용.

#### Q9: Seller 권한 게이트 위치 = γ ClaimService wrapper 신설·DeliveryService primitive 무변경

**채택**: `ClaimService.registerExchangeShipmentBySeller` wrapper 신설·내부 `authorizeSellerAccess` 호출 후 `DeliveryService.registerExchangeShipment` primitive 위임.

**옵션 비교 (정찰 §16 후 재정의)**:

| 옵션 | 평가 | 사유 |
|---|---|---|
| α | 차선 | DeliveryService에 `OrderItemRepository` 신규 주입·자체 authorize 8라인 복제·권한 규칙 분산 |
| β | 기각 | `authorizeSellerAccess` private → package-private 공개·Service 내부 계약 누수·테스트 경계 흐려짐 |
| **γ** | **채택** | D-92·D-98 정합·D-98 흐름 업무 주체 Claim·권한 규칙 단일 SoT·wrapper 패턴 3회차 |
| δ | 기각 | `ExchangeShipmentApplicationService` orchestration 과잉개발·현 범위 잉여 추상화 |

**근거 (외부 검토 3차 인용)**:
> "γ가 단순히 DRY 때문이 아니라, 이미 프로젝트가 채택한 '권한 wrapper → actor 없는 primitive' 패턴과 가장 일관적입니다."

**구조 (정찰 §16.3 실측 해소)**:

```
SellerDeliveryController
  → sellerActorResolver.resolve(request) → sellerId
  → claimRepository.findByPublicId(claimPublicId) → claim.getId()
  → claimService.registerExchangeShipmentBySeller(claimId, sellerId, carrier, trackingNo)
       → claimRepository.findById(claimId) → claim
       → authorizeSellerAccess(claim, sellerId)  ← D-92 Q3 실패 우선순위 최선두
       → deliveryService.registerExchangeShipment(claimId, carrier, trackingNo) ← primitive 무변경
       → return Delivery
  → RegisterExchangeShipmentResponse.from(delivery)
```

**Service 시그니처 (D-92 횡단 원칙 재사용 3회차)**:
- primitive: `DeliveryService.registerExchangeShipment(Long claimId, DeliveryCarrier carrier, String trackingNo): Delivery` — 기존재·actor 비의존·무변경
- Seller wrapper: `ClaimService.registerExchangeShipmentBySeller(Long claimId, Long sellerId, DeliveryCarrier carrier, String trackingNo): Delivery` — 신설·권한 검증 후 primitive 위임

**ClaimService 신규 주입**: `DeliveryService` (생성자 추가).

**순환 의존 부재 확인** (정찰 §16.3): `DeliveryService` → `ClaimRepository` 의존 (§14.2 L32)·`ClaimService` → `DeliveryService` 신규 주입은 Repository 대 Service 분리·순환 없음.

**트랜잭션 경계**: `ClaimService` 클래스 단위 `@Transactional` 기보유 (§14.6)·wrapper 동일 트랜잭션·primitive 호출은 `Propagation.REQUIRED` 자연 전파.

**이벤트 발행 위치 불변**: `DeliveryStarted` 발행은 `DeliveryService.registerExchangeShipment` L103 유지·wrapper 통과해도 위치 무변경.

**권한 검증 패턴** (`authorizeSellerAccess` 1:1 재사용·정찰 §14.6 L353-360 인용):
- `claim.orderItemId` → `orderItemRepository.findById` → `orderItem.sellerId == sellerId`
- 불일치 시 `ClaimNotFoundException` throw (404·cross-tenant 정보 노출 회피·D-92 Q3 실패 우선순위 최선두)

#### Q10: ClaimInvalidStateException → 422 매핑

**채택**: `Claim.attachExchangeDelivery` 내부 검증 throw (type 불일치·orderItemId 불일치) → `ClaimInvalidStateException` → HTTP 422 매핑.

**일관성** (외부 검토 2차 확인 요청 흡수): 프로젝트 전체 `ClaimInvalidStateException` → 422 통일 (ClaimService 패턴 일관).

**오류 응답 정책**: 현재 상태 (`Claim.exchangeDeliveryId`·Delivery 존재 여부) 응답 본문에 포함 금지·일반 에러 메시지로 통일 (cross-tenant 정보 노출 회피).

**근거 정정** (정찰 §14.4 실측): D-99 초안 "Claim 엔티티 필드 자연 흡수" 가정 무효 — `Claim.exchangeDeliveryId` 필드 부재·`attachExchangeDelivery`는 void 검증 전용. 이중 호출 차단은 Q11 별도 처리.

#### Q11: 이중 호출 멱등 차단 = α DeliveryService 진입부 가드·`DeliveryRepository.findByClaimId` 신규

**채택**: `DeliveryService.registerExchangeShipment` primitive 진입부에 `deliveryRepository.findByClaimId(claimId).ifPresent → throw` 가드 신설.

**옵션 비교**:

| 옵션 | DDL | 평가 |
|---|---|---|
| **α** | 무변경 | **채택**·런타임 가드·DeliveryRepository.findByClaimId 신규 메서드 1건 |
| β | V8 마이그레이션 동반 (`delivery.claim_id UNIQUE`) | 기각·D-98 Q13 "UNIQUE 금지·재출고 가능성 차단 회피" 박제와 직접 충돌 |
| γ | 무변경 | 기각·통합 테스트 회귀만·런타임 가드 무방어·운영 데이터 중복 위험 |

**가드 위치 결정 근거** (외부 검토 3차 권고 "Claim 상태 기반" 부분 반박):
- 외부 검토 권고: "이미 교환 배송 연결됨"을 Claim 측 가드로 차단
- **실측 정정 (정찰 §14.4)**: `Claim.exchangeDeliveryId` 필드 부재·Claim 상태(APPROVED+pickedUpAt)만으로는 첫 등록·재호출 구분 불가
- Claim 측 가드 실측상 불가 → Delivery 측 (`claim_id` 조회) 가드만 가능
- D-98 Q13 박제 `delivery.claim_id NULL·UNIQUE 금지·재출고 허용` 정합

**구현 위치**: Service primitive 진입부 (actor 비의존 유지·D-92 정합·`ClaimService.markCompleted` L279 멱등 가드 패턴 1:1).

**예외 매핑**: 이중 호출 차단 시 `ClaimInvalidStateException` throw → Q10 일관 매핑 (HTTP 422).

**박제 1줄**: "재출고 시나리오는 운영 데이터 누적 후 후속 트랙·본 트랙은 1회차 출고 등록만 보장·재출고 진입점 신설 시 본 가드 우회 경로 (별도 Service 메서드·예: `reRegisterExchangeShipment`) 동반 결정."

### 응답 계약 박제

`RegisterExchangeShipmentResponse` (Controller 단일 책임):

```java
public record RegisterExchangeShipmentResponse(
    String deliveryPublicId,
    DeliveryStatus status,
    DeliveryCarrier carrier,
    String trackingNo
) {
    public static RegisterExchangeShipmentResponse from(Delivery delivery) { ... }
}
```

- Claim 상태 미포함 (Controller 단일 책임)
- Delivery 상태는 박제 시점 SHIPPING (D-98 Q3 단일 트랜잭션 박제·state-machine §6.1 READY 의미 정합)

### 예외 매핑 박제

| 예외 | HTTP | 사유 |
|---|---|---|
| `ClaimNotFoundException` (미존재 + cross-tenant) | 404 | 정보 노출 회피 (D-92 Q3) |
| `ClaimInvalidStateException` (type 불일치·orderItemId 불일치·이중 호출·CLM-4 위반) | 422 | 도메인 invariant 위반·프로젝트 일관 |
| Bean Validation 위반 (@NotBlank·@Size) | 400 | DTO 1층 차단 |
| Jackson enum 역직렬화 실패 | 400 | Q4 β 자연 흡수 |

### 영향 범위

#### 신규 파일 (5건)
- `backend/src/main/java/com/zslab/mall/delivery/controller/SellerDeliveryController.java` (`@RestController @RequestMapping("/api/v1/claims")`·Javadoc D-40 β′·D-99 Q5·Q9 인용·SellerClaimController L19-27 패턴 1:1)
- `backend/src/main/java/com/zslab/mall/delivery/controller/request/RegisterExchangeShipmentRequest.java` (Q3 record·@NotBlank·@Size(max=100))
- `backend/src/main/java/com/zslab/mall/delivery/controller/response/RegisterExchangeShipmentResponse.java` (응답 계약 박제)
- `backend/src/test/java/com/zslab/mall/delivery/controller/SellerDeliveryControllerTest.java` (@WebMvcTest·단위 ≥5건)
- `backend/src/test/java/com/zslab/mall/delivery/integration/SellerDeliveryIntegrationTest.java` (e2e·NO @Transactional·LT-02 try-finally·D-91 FK 부모 그래프 시드·≥3건)

#### 수정 파일 (production 3건)
- `backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java` (Q9 `registerExchangeShipmentBySeller` wrapper 신설·`DeliveryService` 신규 주입·`authorizeSellerAccess` 1:1 재사용)
- `backend/src/main/java/com/zslab/mall/delivery/service/DeliveryService.java` (Q11 `registerExchangeShipment` primitive 진입부 가드 추가·`deliveryRepository.findByClaimId` 호출·이중 호출 시 `ClaimInvalidStateException` throw)
- `backend/src/main/java/com/zslab/mall/delivery/repository/DeliveryRepository.java` (Q11 `findByClaimId(Long claimId): Optional<Delivery>` 메서드 신규 추가)

#### 무변경 확정
DeliveryService.registerExchangeShipment primitive 시그니처·Claim 도메인 전건·Delivery Entity·기존 핸들러 전건·DDL·Flyway (V8 등 마이그레이션 불요)·기존 테스트 전건·invariants.md·state-machine.md·aggregate-boundary.md·domain-events.md.

#### 회귀 예상
424 baseline → ≥432 (신규 단위 ≥5 + 통합 ≥3 = ≥8)·기존 회귀 0.

### 회귀 테스트 의무 박제

**Q11 이중 호출 멱등 회귀** (PR 통합 테스트 1건 의무):
- 동일 claimId·정상 첫 등록 → 200
- 동일 claimId 재호출 → 422 `ClaimInvalidStateException`·Delivery 추가 생성 없음·DeliveryStarted 추가 발행 없음
- D-91 FK 부모 그래프 시드·LT-02 try-finally

### 대안 검토 (기각)

- Q1 명사형 `exchange-shipment` (외부 검토 1차): 기존 동사형 패턴과 비일관·기각 (외부 검토 2차 철회).
- Q1 `register-shipment`: 일반 주문 배송 등록과 의미 충돌·기각 (외부 검토 2차 흡수).
- Q2 β markShipping/markDelivered 동반: 일반 주문 Delivery 생성 진입점 부재·호출 데이터 부재·기각.
- Q4 α @ValidEnum 신설: 단일 트랙 최초 도입 과잉개발·기조 4 위배·기각.
- Q5 β Service publicId 오버로드: SellerClaimController 패턴 비일관·동일 행위 2 메서드·기각 (외부 검토 2차 철회).
- Q7 β PR 분할: Q2 α 채택으로 분할 대상 자체 부재·기각.
- Q9 α DeliveryService 자체 authorize: OrderItemRepository 신규 주입·권한 규칙 8라인 복제·규칙 분산·차선이나 권장 미달·기각.
- Q9 β authorizeSellerAccess private → package-private: Service 내부 계약 누수·테스트 경계 흐려짐·기각.
- Q9 δ ExchangeShipmentApplicationService orchestration: 현 범위 과잉개발·계층 증가·기각.
- Q10 별도 409 분기 (이중 호출): Q11 가드가 422로 단일 매핑·HTTP 의미 중복·기각.
- Q11 β delivery.claim_id UNIQUE 제약: D-98 Q13 "UNIQUE 금지·재출고 가능성 차단 회피" 박제 직접 충돌·기각.
- Q11 γ 테스트만: 런타임 보호 부재·외부 요청 → Delivery 생성 → 이벤트 발행 흐름 차단 불가·기각.
- Q11 Claim 측 가드 (외부 검토 3차 권고): `Claim.exchangeDeliveryId` 필드 부재 실측 (정찰 §14.4)·Claim 상태만으로 첫 등록·재호출 구분 불가·기각.

### 관련 결정

D-40 (URL 액터 중립)·D-64·D-65 (publicId 해소)·D-74 (네이밍)·D-92 (횡단 원칙·재사용 3회차)·D-93 (SellerActorResolver seam·재사용 3회차)·D-97 §후속 (Delivery Controller carry-over 종결)·D-98 Q3·Q13 (registerExchangeShipment primitive·attachExchangeDelivery 검증·delivery.claim_id NULLABLE FK·UNIQUE 금지·재출고 허용)·invariants §2.12 DLV-1~3·state-machine §6.1·SellerClaimController.java L19-27·L54·L64 패턴 SoT·ClaimService.java L50-53·L353-360 authorizeSellerAccess SoT·DeliveryService.java L31-33·L89-107 SoT·V1__init.sql L642-654 SoT·Claim.java L202-215 attachExchangeDelivery SoT·docs/track-15/recon-report.md §1~§18.

### 후속 트랙

- **markShipping/markDelivered Seller endpoint**: 일반 주문 Delivery 생성 진입점 신설 트랙 동반.
- **재출고 흐름 정의**: 운영 데이터 누적 후 후속 트랙·EXCHANGE 1회차 실패 → 새 Claim 또는 동일 Claim 재시도 시나리오 확정·Q11 가드 우회 경로 (`reRegisterExchangeShipment` 등) 동반 결정.
- **Admin Service**: `AdminDeliveryController`·D-93 AdminActorResolver 패턴 재사용·`registerExchangeShipmentByAdmin` wrapper.
- **Spring Security 정식 도입**: X-Seller-Id stub 3종 일괄 대체·D-93 Q11 wrapper 유지 전략.
- **외부 택배 어댑터**: D-98 Q1 ClaimPickedUp 발행 주체 확장·자동 픽업 확인.
- **@ValidEnum 신설 트랙**: enum alias·case-insensitive·legacy 입력 요구 발생 시점.
- **EXCHANGE 차액 환불**: D-98 Q2 박제 1줄 진입점·refundAmount>0 흐름.
- **Observability**: D-90·D-94 Q6·D-95 Q6·D-97 Q8·D-98 Q5·D-99 Q11 박제 1줄 통합 재평가·이벤트 핸들러 멱등성 표준화.

### 후속

1. 본 결정 박제 = 사용자 직접 처리 (D-94·D-95·D-96·D-97·D-98 패턴 6회차).
2. PR 브랜치 `feat/track-15-seller-delivery` 생성 (main HEAD 4e5d0b3 기준).
3. 가드 5 사전 통지: 신규 5 (controller 1·DTO 2·테스트 2) + 수정 production 3 (ClaimService wrapper·DeliveryService 가드·DeliveryRepository 메서드 추가) — Claude Code 구현 프롬프트 시점 일괄 통지.
4. TDD 우선 (단위 → 통합)·BUILD SUCCESSFUL·신규 ≥8 PASS·기존 회귀 0.
5. GitHub Web UI PR 생성·Base: main·Compare: feat/track-15-seller-delivery·외부 검토 1·2·3차 완료·A급.
6. PR 머지로 Track 15 종결·D-97·D-98 §후속 Seller Service 트랙 carry-over 영구 해소·D-92·D-93 횡단 원칙 재사용 3회차 박제·Q11 재출고 후속 트랙 carry-over 신설.

---

## D-100. Track 16 Observability 표준 박제 · 관측성 컨텍스트 전파 컨벤션 · Micrometer 명명·Actuator 노출·로깅 표준·테스트 표준 · 박제 1줄 누적 6건 통합 재평가 [ACTIVE]

**결정일**: 2026-06-30
**관련**: Track 16 / D-29 (save→publish·no flush)·D-30 (이벤트 사실 통지·payload 무수정)·D-48 (TraceIdFilter MDC 박제)·D-69 (RefundCompleted Publisher/Consumer 시점 분리)·D-70 (refundedAt 시각 의미)·D-74 (이벤트 핸들러 빈 네이밍)·D-75 (이벤트 핸들러 AFTER_COMMIT·REQUIRES_NEW)·D-90 (Q1·Q3·Q5 AFTER_COMMIT 패턴·복귀 전이·통합 테스트 정책)·D-91 (FK 부모 그래프 시드)·D-94 Q6 (RefundService.initiate 활성 Refund 게이트)·D-95 Q6 (NotificationLog 멱등 미도입·인메모리 publisher 가정)·D-97 Q8 (canTransitionTo 자연 흡수)·D-98 Q5 (소비 순서)·D-99 Q11 (registerExchangeShipment 이중 호출 가드) / invariants 전건 / state-machine 전건 / aggregate-boundary 전건 / domain-events §1·§2 E1~E11 / live-traps LT-01·LT-02·LT-03 / docs/track-16/recon-report.md §1~§13.7

### 배경

D-99 §후속에 명시된 "Observability" carry-over를 본 트랙에서 종결한다.

정찰 결과 (docs/track-16/recon-report.md §1~§13.7 실측 인용):

- **이벤트 record 11종 실측** (recon §2.1): OrderPlaced·PaymentCompleted·PaymentFailed·DeliveryStarted·DeliveryCompleted·ClaimRequested·ClaimApproved·ClaimRejected·ClaimCompleted·ClaimPickedUp·RefundCompleted
- **핸들러 18건 실측** (recon §3.1): claim 5·notification 7·order 2·payment 2·refund 2 = 5 패키지
- **멱등 가드 패턴 5종 혼재** (recon §3.2): A canTransitionTo 자연 흡수·B Service primitive 가드·C type 분기 게이트·D catch+skip+log.warn·E 다단계 복합
- **트랜잭션 정책 이원화** (recon §4.1·§4.2): 동기 @EventListener 4건·AFTER_COMMIT + REQUIRES_NEW 14건
- **correlationId·eventId 전 프로젝트 0건** (recon §2.3·§5.3): `grep -rn "correlationId|eventId" → 0건`
- **MeterRegistry·@Timed·@Counted 0건** (recon §6.2): 비즈니스 메트릭 전무
- **Actuator 노출** (recon §6.3): health·info 한정·application-prod.yml show-details when-authorized
- **로그 prefix 11종 산발** (recon §5.1): `[Claim]`·`[Notification]`·`[Refund]`·`[Delivery]`·`[ExchangeDelivery]`·`[Payment]`·`[Checkout]`·prefix 없음 등
- **ApplicationEventPublisher 인메모리 단독** (recon §8.1·§8.2): publishEvent 11 호출처·외부 브로커 0건
- **박제 1줄 누적 6건** (recon §3.3): D-90·D-94 Q6·D-95 Q6·D-97 Q8·D-98 Q5·D-99 Q11
- **WARN 10건** (recon §10): P0 3건 (RefundCompleted 시각 필드·correlationId 0건·MeterRegistry 0건)·P1 3건·P2 4건 (WARN-10 §13.7 추가)

1·2·3차 외부 검토 수렴 후 결정 18건 + WARN 처치 8건 + 진입점 카드 6 항목 박제한다.

---

### §원칙

본 결정은 "정책(왜)"와 "검증방법(어떻게)"를 분리해 3계층(원칙·규칙·종료조건)으로 재정렬한다 (WARN-18 흡수). 박제는 책임만 기록하며 구현 클래스명·테스트 클래스명·메트릭 이벤트명 고정은 회피한다 (WARN-15·17 흡수).

**1. 관측성 트랙은 도메인 트랙과 다르다**:
- 본 트랙은 16 Aggregate·11 이벤트·18 핸들러 횡단 표준 박제이며 도메인 행위 변경 0건
- 잘못된 결정은 이후 모든 Aggregate·이벤트·테스트·운영 규칙에 횡단 전파 위험·신중 의무

**2. 인메모리 publisher 가정 유지**:
- D-95 Q6 박제 1줄 유지 (인메모리 ApplicationEvent publisher·재전달 자연 발생 불가)
- Outbox·외부 브로커 도입은 본 트랙 OOS·트리거 조건 박제 (Q2 γ)

**3. D-30 사실 통지 원칙 유지**:
- 이벤트 record payload 무수정·correlationId·eventId 필드 추가 회피
- 관측성 컨텍스트는 MDC 전파 (이벤트 record 무영향)

**4. 박제는 정책 책임만 기록**:
- 구현 클래스명(TraceIdFilter·NotificationLogIntegrationTest 등) 고정 회피
- 메트릭 이벤트명 고정 회피
- 박제는 책임·계약만 기록·구현은 PR 단계 실측 후 결정

**5. 박제 1줄 누적 6건 전건 유지**:
- D-90·D-94 Q6·D-95 Q6·D-97 Q8·D-98 Q5·D-99 Q11 전건 통합 재평가 후 유지 확인
- 본 트랙 신규 박제 1줄 14건 추가·총 20건 (3계층 재정렬로 유지 가능)

---

### §규칙 (Q0~Q17 결정 18건)

#### Q0: 트랙 식별자 = α Track 16 = Observability 확정

D-94 Q0 β·D-95 Q1 α·D-97 Q0 β·D-98 Q0 α·D-99 Q0 α 선례 6회차. D-99 §후속 "Observability" 명시.

#### Q1: 이벤트 핸들러 멱등성 표준화 = γ 5 패턴 카탈로그 박제 + Javadoc 의무

**박제 1줄**: "이벤트 핸들러 멱등 가드 표준은 패턴 5종(A 상태전이 자연 흡수 (canTransitionTo·**불변식 제외**·[보강 Track17 D-101 §6])·B Service primitive 가드·C type 분기 게이트·D catch+skip+log.warn·E 다단계 복합) 카탈로그 박제·신규 핸들러는 패턴 선택 사유를 Javadoc 1줄 의무 기록·NotificationLog 멱등 저장소는 Outbox 도입 시점 동반."

**근거**: 패턴별 이벤트 성격(동기 정합성 vs 비동기 알림) 차이 반영·단일화는 SRP 위배·D-95 Q6 박제 1줄 유지.

#### Q2: 이벤트 저장소·Outbox = γ 인메모리 publisher 유지 + 트리거 조건 박제

**박제 1줄**: "Outbox 도입 트리거 = (1) 다중 인스턴스 배포 결정 또는 (2) 외부 브로커(Kafka·RabbitMQ·Redis Streams) 도입 또는 (3) 이벤트 누락 라이브 트랩 발생 또는 (4) 이벤트 재처리 요구 SLA 발생 — 넷 중 1건 도달 시 별도 트랙 진입 의무."

**근거**: 단일 인스턴스 운영·publishEvent 11건 인메모리 단독·Outbox는 outbox 테이블·publisher·poller·retry·cleanup·replay·테스트 운영 부담 동반·기조 4 위배.

#### Q3: correlationId·eventId 컨벤션 = β′ correlationId·eventName MDC + event record 무수정

**박제 1줄**: "관측성 컨텍스트 전파 표준 = MDC 키 `traceId`·`correlationId` 2종 + `eventName`은 핸들러 catch 블록 `event.getClass().getSimpleName()` 직접 인용 (MDC 미주입)·event record 무수정 (D-30 사실 통지 정합)·eventId 도입은 Outbox 트리거 시점 동반."

**박제 1줄 갱신 (eventName MDC 제거·실측 기반 책임 재정의·2026-06-30)**: 초기 박제 본문 "MDC 키 3종"은 AFTER_COMMIT 핸들러 진입 시점 eventName 보장 가정으로 작성됐으나, TracedEventPublisher의 finally MDC.remove(eventName)가 publishEvent 동기 구간 종료 시점에 실행되고 @TransactionalEventListener(AFTER_COMMIT) 핸들러 발화는 트랜잭션 커밋 시점이라 시간 차이로 핸들러 진입 시 MDC eventName 부재 실측 확정 (CorrelationIdIntegrationTest 캡처값 null). nested publishEvent 패턴 (핸들러 A 내부 publishEvent(B)) 발생 시 옵션 2 적용 시 MDC eventName 오염 위험 동반 → 옵션 4 채택: TracedEventPublisher 책임 = delegate 단순 위임만·eventName MDC 미주입·핸들러 catch 6 표준키 event 직접 인용으로 운영 grep 충족·Outbox 도입 시 본 클래스가 Outbox writer 자연 진입점 (이벤트 메시지에서 eventName 추출·MDC 의존 약화). 씨앗 3건 (박제 불일치·Outbox 재작업·silent null) 전건 해소.

**근거**: D-30 정합·11 record 수정 회귀 회피·MDC 기반 운영 추적 효용 즉시 확보.

#### Q4: Micrometer 메트릭 컨벤션 = β′ published+failed 카운터 2종 (handler 태그 미부착)

**박제 1줄**: "메트릭 명명 = `zslab.event.published{event=<EventName>}`·`zslab.event.failed{event=<EventName>}` 카운터 2종 1차 박제·handler 태그 미부착(rename 시 시계열 보존·저카디널리티)·handler 식별은 로그 책임·`zslab.event.duration{handler=<HandlerClass>}` Timer + `zslab.event.consumed` 카운터는 운영 부하 측정 수요 발생 시점 후속 트랙 동반."

**근거**: 핸들러 rename 시 메트릭 시계열 단절 회피·태그 폭발 위험 회피 (WARN-13 흡수)·핵심 도메인 우선 계측·후속 트랙 확장 정합.

#### Q5: Actuator endpoint 노출 = α′ health·info·prometheus + gateway IP 화이트리스트

**박제 1줄**: "Actuator 운영 노출 = `health`·`info`·`prometheus` 한정·`metrics` raw JSON 미노출·Prometheus scrape 단독 운영·`/actuator/prometheus` 보안은 gateway_nginx 인프라 단 IP 화이트리스트 의무·애플리케이션 단 보안 처리는 Spring Security 정식 도입 트랙 시점 결합."

**근거**: Spring Security 부재·공유 인프라 gateway_nginx 기반 IP 화이트리스트 가능·운영 모니터링 즉시 확보.

#### Q6: 로깅 표준 = β + 5 표준키 + Metric-Log 책임 분리

**박제 1줄**: "로그 prefix 표준 = Aggregate 단위 `[<Aggregate>]` 의무 (Claim·Order·Payment·Refund·Delivery·Notification·Settlement·Inventory)·핸들러 catch 블록 structured log 키 `event·target_type·target_id·action·correlationId·handler` 6 키 박제·correlationId MDC 자동 상속·로그 책임(상세·handler 포함) vs 메트릭 책임(저카디널리티·event 단일 태그) 분리·중복 관측 회피."

**근거**: prefix 통일 운영 grep 일관성·Q3 β′ correlationId MDC 통합 정합·Notification 7건 catch 블록 `action=manual_review` 패턴 1:1 유지·WARN-13 책임 분리 흡수.

#### Q8: 테스트 표준화 = β + Javadoc 의도 명시 의무 (마커 어노테이션 미도입)

**박제 1줄**: "통합 테스트 표준 = AFTER_COMMIT 핸들러 검증 테스트는 NO @Transactional + TransactionTemplate + @RecordApplicationEvents + LT-02 try-finally + D-91 FK 부모 그래프 시드 5중 의무·비검증 테스트는 클래스 Javadoc에 의도 명시 의무·마커 어노테이션(@TestCategory 등) 미도입 (기조 4 정합·Track 14·15 Javadoc 패턴 정착 실측)."

**근거**: △ 4건 (Checkout·Claim·SellerClaim·AdminClaim) Javadoc 의도 명시 완비 실측 (recon §13.1)·강제 α는 회귀 위험·기조 4 위배·JUnit Tag 인프라 도입 과잉개발.

#### Q9: AFTER_COMMIT 핸들러 실행 순서 = γ 비보장 박제 + Q2 트리거 정합

**박제 1줄**: "AFTER_COMMIT 핸들러 간 실행 순서는 비보장 (Spring `ApplicationEventMulticaster` 빈 등록 순서 의존)·현재 핸들러 간 의존성 부재 (E5 DeliveryCompleted 3중 소비자 독립 Aggregate 수정 실측)·다중 인스턴스·Outbox 도입 시 명시적 순서 보장 메커니즘 동반 결정 의무 (Q2 트리거 조건 정합)."

**근거**: `@Order` 어노테이션 핸들러 0건 실측 (recon §13.6)·현 시점 기능 의존성 없음·인메모리 publisher 가정 종료 시점 자연 진입.

#### Q10: RefundCompleted 시각 필드 정합 = γ 의도적 박제 유지

**박제 1줄**: "RefundCompleted record `refundedAt` 단독 사용은 D-70 의도적 선택 (COMPLETED 전이 시스템 시각·업무 의미 강조)·`occurredAt` 컨벤션 이탈은 의도된 비일관·이벤트 record 시각 필드는 업무 시각(`refundedAt`·`pickedUpAt`·`deliveredAt` 등)·이벤트 발행 시각(`occurredAt`) 2 트랙 자유 선택 박제."

**근거**: D-70 결정 의미 보존·소비자 영향 회피·11 record 시각 필드 통일 욕심은 의미 손실.

#### Q11: publicId 이벤트 record 포함 패턴 = β 현 패턴 박제

**박제 1줄**: "이벤트 record publicId 포함 기준 = 소비자 NotificationLog 메시지·운영자 로그·외부 API 응답 등 표시용 사용 시 포함 의무·내부 도메인 처리만 수행 시 미포함·필드명은 `<aggregate>PublicId` 통일 (OrderPlaced.publicId는 단일 publicId 사용으로 prefix 생략 의도적 패턴 박제·정합 비고로 유지)."

**근거**: Claim 5종 + OrderPlaced 1종 표시용 사용 실측·Payment/Delivery/Refund 5종 내부 처리 한정 실측·11 record 전건 수정 회귀 회피.

#### Q12: PR 분할 = β′ PR-1 + PR-2

- **PR-1** (`feat/track-16-observability-standards`): D-100 박제·로깅 prefix Notification 7건 적용·correlationId/eventName MDC 주입·Q14 실측 + (조건부) MdcContextCopier helper 신설·테스트 표준 박제
- **PR-2** (`feat/track-16-observability-metrics`): Micrometer published+failed 카운터·Prometheus registry 의존성·Actuator prometheus endpoint 노출·gateway IP 화이트리스트

**근거**: 1-PR 단독 패턴 9회차 누적·correlationId/로깅은 코드 산발 작음·Micrometer는 18 핸들러 영향·분리 자연·rollback 비용 저감.

**박제 1줄 갱신 (β″′ 채택·2026-06-30)**: PR-1 영향 production 7→13 (TraceIdFilter 1·Notification 7·5 Service 발행처 의존 교체)·신규 2 (TracedEventPublisher + 단위 테스트)·5 Service = OrderService·PaymentService·DeliveryService·RefundService·ClaimService·발행처 ↔ ApplicationEventPublisher → TracedEventPublisher 생성자 주입 교체·publishEvent 시그니처 동일·트랜잭션 경계 무관 (D-29 save→publish 정합)·eventName MDC 주입 단일 SoT 확보·Outbox 도입 시 재작업 비용 0·Q14 ✓ 자연 상속 직접 활용.

#### Q13: correlationId 정의 = α traceId 동일 값 (요청 기원 한정·요청 외 기원 분리 재평가)

**박제 1줄**: "correlationId는 현재 단계에서 traceId와 동일 값 사용. 요청 기원(Request-originated) 이벤트 체인에서는 TraceIdFilter 진입 시 traceId·correlationId 동시 주입. 요청 외 기원(배치·Outbox·Replay·Scheduler·Admin trigger·CLI) 도입 시 분리 재평가."

**근거**: 1 HTTP 요청 = 1 이벤트 체인 현 가정 정합·별도 ULID 분리는 추적 키 2개 운영 부담·인메모리 publisher 가정 하 효용 미약·향후 요청 외 기원 진입 시 자연 분리.

#### Q14: MDC 자연 상속 실측 = α 단위 테스트 실측 + nested publishEvent 동반 + 스레드 전환 시 재결정

**박제 1줄**: "AFTER_COMMIT 핸들러 MDC 자연 상속은 본 트랙 진입 시점 단위 테스트로 실측 의무·nested publishEvent (핸들러 내 추가 이벤트 발행) MDC 상속 동반 실측 의무·자연 상속 확인 시 helper 미신설·미상속 발견 시 `MdcContextCopier` helper 신설·스레드 전환(@Async·Scheduler·Outbox·Executor) 발생 시 MDC 복원 전략 재결정."

**근거**: 기조 5 실측 우선·AFTER_COMMIT 동일 스레드 실행 명세 (트랜잭션 분리 ≠ 스레드 분리)·helper 무조건 신설은 과잉개발·실측 후 결정.

#### Q15: MDC.clear 적용 책임 = α 정책·구현 분리 (구현 클래스명 고정 회피)

**박제 1줄**: "요청 컨텍스트 종료 시 MDC 정리(MDC.clear 또는 동등 보장) 의무·PR-1 구현 단계에서 적용 여부 실측 후 누락 시 보완·구현 클래스명·매커니즘은 박제하지 않으며 책임만 기록 (WARN-15 흡수)."

**근거**: WARN-12 본질은 요청 종료 시 MDC 오염 방지·구현 상태 확인은 PR-1 단계·정책과 구현 결합 회피.

#### Q16: correlationId 누락 검증 = α 대표 흐름 누락 없음 검증 (수치 집착 제거)

**박제 1줄**: "PR-1 종료 검증은 대표 이벤트 흐름에 대해 correlationId 누락 없음 검증 의무 (HTTP → publish → AFTER_COMMIT → 로그)·운영 지속 측정은 외부 로그 수집 인프라 도입 시점 자연 진입."

**근거**: "존재율 100%" 운영 KPI식 표현 회피·대표 흐름 검증으로 충분·LogbackAppender 추가는 과잉개발.

#### Q17: failed metric 검증 = α′ 메트릭 계약만 고정 (이벤트명·테스트 구현 비박제)

**박제 1줄**: "PR-2 종료 검증은 이벤트 처리 실패 유발 후 `zslab.event.failed` 카운터 증가 단언 의무·검증 이벤트 종류·핸들러 구현·테스트 클래스명은 변경 가능·메트릭 계약만 고정 (WARN-17 흡수)."

**근거**: 테스트 구조 변경 시 박제 깨짐 회피·메트릭 계약(증가 단언) 단독 박제·구현 자유.

---

### §종료조건

#### PR-1 종료조건 (Done 정의·WARN-14 흡수)

1. **로그 prefix 100% 적용** — 핸들러 catch 블록 `[<Aggregate>]` prefix grep 검증 의무·Notification 7건 prefix 추가 완료
2. **traceId·correlationId 누락 없음** — 대표 이벤트 흐름 (HTTP → publish → AFTER_COMMIT → 로그) 통합 테스트 1건으로 traceId·correlationId MDC 포함 단언 (Q3 β′ 갱신 정합·eventName MDC는 책임 미보유·핸들러 로그 직접 인용으로 별도 충족)
3. **TraceIdFilter MDC 정리 적용** — 요청 컨텍스트 종료 시 MDC 정리 실측·미적용 시 보완
4. **Q14 MDC 자연 상속 실측 완료** — 단위 테스트 + nested publishEvent 동반·결과에 따라 helper 신설 또는 미신설 결정 박제
5. **TracedEventPublisher 발행처 5 Service 교체 완료** — publishEvent 11 호출처 wrapper 의존 확인 (β″′ 채택)

#### PR-2 종료조건 (Done 정의)

6. **failed 메트릭 증가 단언** — 통합 테스트 1건으로 `zslab.event.failed` 카운터 증가 단언·이벤트명·테스트 클래스명은 변경 가능
7. **Actuator scrape 확인** — `/actuator/prometheus` endpoint scrape 응답 본문에 `zslab.event.*` 메트릭 노출 확인

**종료조건 추가 금지** (WARN-16 흡수): 위 7건 외 추가 종료조건 신설 회피·과측정 위험·운영 효용 한계.

---

### §진입점 (메모리 룰 #16 시범 적용)

본 절은 신규 트랙 진입 시 첫 손길 들어갈 코드 위치·SoT 메서드·횡단 원칙·트랩 주의 압축 카드다.

1. **목적**: 관측성 표준 박제 (correlationId 전파·로깅 prefix·Micrometer 명명·Actuator 노출·테스트 5중 의무) — 도메인 행위 변경 0건
2. **핵심 진입점**:
   - `common/web/TraceIdFilter.java` — D-48 SoT·correlationId MDC 동시 주입 진입점·MDC.clear 적용 위치
   - `common/observability/TracedEventPublisher.java` — eventName MDC 주입 단일 SoT (β″′ 채택)·5 Service 발행처 의존 wrapper
   - `notification/handler/Notification*Handler.java` 7건 — 로그 prefix 적용 대상·structured log 6 표준키 적용 대상
   - `build.gradle.kts` — `micrometer-registry-prometheus` 의존성 추가 위치
   - `application.yml`·`application-prod.yml` — Actuator endpoint 노출 설정
3. **핵심 SoT 메서드**:
   - `TracedEventPublisher.publishEvent` — 11 발행처 단일 진입점·delegate 단순 위임·Outbox writer 자연 흡수 진입점·Micrometer `zslab.event.published` 카운터 적용 대상 (eventName MDC 책임 미보유·옵션 4 채택)
   - 핸들러 catch 블록 18건 — `zslab.event.failed` 카운터 + 로그 prefix 동반 적용 대상
4. **영향 범위**:
   - production: `common/web/`·`common/observability/`·`notification/handler/`·5 Service 발행처·`build.gradle.kts`·`application*.yml`
   - test: 통합 테스트 ≥2건 (PR-1 correlationId·PR-2 failed metric) + 단위 ≥2건 (Q14 MDC·TracedEventPublisher)
   - Aggregate 단위 변경: 0건 (도메인 행위 무변경·횡단 표준만)
5. **패턴 재사용 SoT**:
   - D-48 (TraceIdFilter MDC 박제) — correlationId 동시 주입 1회차 확장
   - D-95 Q6·D-97 Q8 박제 1줄 — Q1 γ·Q2 γ 통합 재평가 흡수
   - D-30 사실 통지 원칙 — Q3 β′ event record 무수정 정합
   - D-29 save→publish — TracedEventPublisher 트랜잭션 경계 무관 정합 (β″′)
6. **트랩 주의**:
   - LT-02 try-finally — Q8 β 5중 의무 정합
   - WARN-15 정책·구현 고정 결합 — 박제는 책임만·구현 클래스명 고정 회피
   - WARN-17 종료조건 구현 의존성 — 테스트 클래스명 비박제
   - WARN-18 박제 증식 — 3계층 재정렬 의무
   - 5 Service 발행처 의존 교체 회귀 — TracedEventPublisher 시그니처 동일·통합 테스트 20건 회귀 검증 의무 (β″′)

---

### WARN 처치 매트릭스

| WARN | Priority | 처치 | 흡수 위치 |
|---|---|---|---|
| 1 (RefundCompleted 시각 필드 산발) | P0 | Q10 γ 의도적 박제 | Q10 박제 1줄 |
| 2 (correlationId·eventId 0건) | P0 | Q3 β′ MDC 주입 + Q13 α 정의 | Q3·Q13 박제 1줄 |
| 3 (MeterRegistry 0건) | P0 | Q4 β′ published+failed 카운터 | Q4 박제 1줄 |
| 4 (NotificationLog 멱등 미도입) | P1 | Q1 γ + D-95 Q6 박제 1줄 유지 | Q1 박제 1줄 |
| 5 (멱등 5종 혼재) | P1 | Q1 γ 카탈로그 박제 + Javadoc 의무 | Q1 박제 1줄 |
| 6 (로그 prefix 산발) | P1 | Q6 β prefix 표준 + 5 표준키 | Q6 박제 1줄 |
| 7 (publicId 포함 패턴 산발) | P2 | Q11 β 현 패턴 박제 | Q11 박제 1줄 |
| 8 (Actuator metrics 미노출) | P2 | Q5 α′ prometheus + IP 화이트리스트 | Q5 박제 1줄 |
| 9 (@RecordApplicationEvents 3건) | P2 | Q8 β 의도 명시 의무 | Q8 박제 1줄 |
| 10 (AFTER_COMMIT 핸들러 @Order 미정의) | P2 | Q9 γ 비보장 + Q2 트리거 정합 | Q9 박제 1줄 |
| 11 (관측성 컨텍스트 전파 부재) | P1 | Q3 β′·Q14 α 흡수 | Q3·Q14 박제 1줄 |
| 12 (TraceIdFilter MDC 정리 부재) | P1 | Q15 α 정책 박제 (구현 분리) | Q15 박제 1줄 |
| 13 (Metric-Log 책임 중복) | P2 | Q6 β 책임 분리 박제 | Q6 박제 1줄 |
| 14 (관측성 도입 완료 기준 부재) | P2 | §종료조건 7건 박제 | §종료조건 |
| 15 (정책·구현 고정 결합 위험) | P2 | Q15 α + WARN-18 3계층 재정렬 흡수 | Q15·§원칙 |
| 16 (완료조건 과측정 위험) | P3 | §종료조건 추가 금지 박제 | §종료조건 |
| 17 (종료조건 구현 의존성) | P3 | Q17 α′ 메트릭 계약만 고정 | Q17 박제 1줄 |
| 18 (박제 증식·총 20건) | P3 | 3계층 재정렬 (원칙·규칙·종료조건) | §원칙·§규칙·§종료조건 |
| 19 (TracedEventPublisher finally MDC.remove vs AFTER_COMMIT 시점 미스매치 실측 발견·2026-06-30) | P1 | Q3 β′ 박제 본문 갱신 + 옵션 4 채택 + TracedEventPublisher 책임 재정의 | Q3 박제 1줄 갱신 |

---

### 외부 검토 흡수 흐름

- **1차 외부 검토**: 12 의제 (Q0~Q12) 송부. 흡수: Q0·Q1 (Javadoc 보강)·Q2 (트리거 조건 보강)·Q3 β′·Q4 β′·Q5 α′·Q6 (correlationId 표준키 추가)·Q8 (마커 후속)·Q9·Q10·Q11. WARN-11 신규 (관측성 컨텍스트 전파 부재·P1).
- **2차 외부 검토**: Q3·Q4 β′·Q8·Q12 분할·WARN-11 처치 4건 의제 송부. 흡수: Q13 α 신규 (correlationId 정의)·Q14 α 신규 (MDC 자연 상속 실측)·Q4 β′ handler 태그 제거·Q12 β′ PR-1 grep 100% 적용률·Q8 마커 반박 수용. WARN-12·13·14 신규.
- **3차 외부 검토**: Q15·Q16·Q17 신규 + 2차 흡수 정합 의제 송부. 흡수: Q15 α 정책·구현 분리 (구현 클래스명 고정 회피)·Q16 α 수치 집착 제거·Q17 α′ 메트릭 계약만 고정. WARN-15·16·17·18 신규.
- **Claude.ai 자체 정정**:
  - Q1 신규 핸들러 Javadoc 사유 의무 1차 검토 흡수
  - Q15 정책·구현 분리 3차 검토 핵심 의견 흡수 (γ → α 변경)
  - WARN-18 3계층 재정렬 흡수
  - **Q12 β″′ 채택 자체 정정 (2026-06-30·PR-1 Step 3 진입 전)**: α′ (catch 직접 인용) vs β″′ (TracedEventPublisher SoT) 정면 비교 후 확장성·결합도·운영 grep 효용·Q3 β′ 강해석 정합·Q14 ✓ 자연 상속 직접 활용 5건 기조 5 정합 우월 판정. PR-1 영향 production 7→13 갱신·외부 검토 4차 불요 (영향 범위·구현 디테일 갱신·결정 의제 추가 아님).

---

### 영향 범위

#### PR-1 — 신규 파일 (4~5건)

- (조건부 Q14 실측 결과) `backend/src/main/java/com/zslab/mall/common/observability/MdcContextCopier.java` — MDC 미상속 발견 시 신설·자연 상속 확인 시 미신설 (실측 결과: 자연 상속 ✓·미신설 확정)
- `backend/src/test/java/com/zslab/mall/observability/MdcPropagationTest.java` — Q14 단위 테스트 (AFTER_COMMIT + nested publishEvent MDC 상속 실측)
- `backend/src/test/java/com/zslab/mall/observability/CorrelationIdIntegrationTest.java` — Q16 PR-1 종료 검증 (대표 흐름 correlationId 누락 없음)
- `backend/src/main/java/com/zslab/mall/common/observability/TracedEventPublisher.java` — β″′ 채택 필수·eventName MDC 주입 단일 SoT
- `backend/src/test/java/com/zslab/mall/common/observability/TracedEventPublisherTest.java` — β″′ 단위 테스트 필수

#### PR-1 — 수정 파일 (production 13건)

- `backend/src/main/java/com/zslab/mall/common/web/TraceIdFilter.java` — correlationId·eventName MDC 동시 주입·요청 종료 시 MDC 정리 (Q15 α 실측 후 미적용 시 보완)
- `backend/src/main/java/com/zslab/mall/notification/handler/NotificationOrderPlacedHandler.java` — `[Notification]` prefix + 6 표준키 적용
- `backend/src/main/java/com/zslab/mall/notification/handler/NotificationPaymentCompletedHandler.java` — 동일
- `backend/src/main/java/com/zslab/mall/notification/handler/NotificationClaimApprovedHandler.java` — 동일
- `backend/src/main/java/com/zslab/mall/notification/handler/NotificationClaimCompletedHandler.java` — 동일
- `backend/src/main/java/com/zslab/mall/notification/handler/NotificationClaimPickedUpHandler.java` — 동일
- `backend/src/main/java/com/zslab/mall/notification/handler/NotificationDeliveryStartedHandler.java` — 동일
- `backend/src/main/java/com/zslab/mall/notification/handler/NotificationDeliveryCompletedHandler.java` — 동일
- `backend/src/main/java/com/zslab/mall/order/service/OrderService.java` — ApplicationEventPublisher → TracedEventPublisher 생성자 의존 교체 (β″′)
- `backend/src/main/java/com/zslab/mall/payment/service/PaymentService.java` — 동일 (β″′)
- `backend/src/main/java/com/zslab/mall/delivery/service/DeliveryService.java` — 동일 (β″′)
- `backend/src/main/java/com/zslab/mall/refund/service/RefundService.java` — 동일 (β″′)
- `backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java` — 동일 (β″′)

#### PR-2 — 수정 파일 (production 2~3건)

- `backend/build.gradle.kts` — `io.micrometer:micrometer-registry-prometheus` 의존성 추가
- `backend/src/main/resources/application.yml` — Actuator `include: health,info,prometheus` 갱신
- `backend/src/main/resources/application-prod.yml` — 동일 + show-details 정책 유지

#### PR-2 — 신규 파일 (1~2건)

- `backend/src/main/java/com/zslab/mall/common/observability/EventMetricsRecorder.java` — `recordPublished(eventName)`·`recordFailed(eventName)` 메서드·`zslab.event.published`·`zslab.event.failed` 카운터 발화 단일 SoT (β 채택·2026-06-30·SRP 정합·TracedEventPublisher 책임 최소화 옵션 4 정합·테스트 격리·failed 카운터 통일 메커니즘·Outbox 도입 시 SoT 유지). TracedEventPublisher에서 `recordPublished` 호출·핸들러 catch 블록에서 `recordFailed` 호출.
- `backend/src/test/java/com/zslab/mall/observability/EventFailedMetricIntegrationTest.java` — Q17 α′ PR-2 종료 검증 (failed 카운터 증가 단언·이벤트명 자유)

#### 무변경 확정

이벤트 record 11종 (D-30 정합·Q3 β′ 정합)·publishEvent 11 호출처 시그니처 (β″′ TracedEventPublisher.publishEvent = ApplicationEventPublisher.publishEvent 시그니처 동일·호출 라인 무변경)·도메인 Aggregate·invariants·state-machine·aggregate-boundary·domain-events·live-traps·DDL/Flyway (스키마 무변경)·기존 NotificationService·기존 핸들러 18건 본문 중 11건 (동기 4 + claim/order/payment/refund 7건·로직 무변경·Notification 7건만 prefix·6 표준키 적용)·기존 통합 테스트 20건 본문 (β″′ 시그니처 동일로 회귀 0 기대)·Aggregate boundary·SoT 박제 1줄 6건 (D-90·D-94 Q6·D-95 Q6·D-97 Q8·D-98 Q5·D-99 Q11).

#### 회귀 예상

PR-1: 433 baseline → 신규 합산 ≥438 (Q14 단위 2 + TraceIdFilter 단위 3 + TracedEventPublisher 단위 ≥2 + CorrelationId 통합 ≥1·기존 회귀 0)
PR-2: PR-1 머지 후 baseline 기준 → 신규 합산 ≥439 (통합 ≥1·기존 회귀 0)

---

### 대안 검토 (기각)

#### Q1
- α 단일 표준 강제 (모든 핸들러 멱등 키 저장소): 18 핸들러 영향·NotificationLog 멱등 저장소 동반·D-95 Q6 박제 1줄 충돌·기조 4 위배·기각
- β 패턴 B 단일화 (Service 진입부 멱등 가드 의무): A·D 패턴 SRP 위배·캐스케이드 실패 위험·기각

#### Q2
- α Transactional Outbox 즉시 도입: outbox 테이블·publisher·poller·retry·cleanup·replay·테스트 운영 부담·운영 가치 미미·기조 4 위배·기각
- β Spring Modulith Outbox: 외부 라이브러리·DB 부하 동반·동일 사유·기각

#### Q3
- α event record 전건 eventId·correlationId 필드 추가: D-30 사실 통지 위배·11 record 수정 회귀·기각
- γ 현 상태 유지 (correlationId 미도입): WARN-2 P0 해소 실패·운영 추적 효용 0·기각

#### Q4
- α 도메인 이벤트 전건 published/consumed/failed/duration 4종 계측: 18 핸들러 전건 영향·태그 폭발·기조 4 위배·기각
- γ 명명 컨벤션 박제만 (계측 0): 운영 효용 0·WARN-3 P0 해소 실패·기각
- β (handler 태그 부착): 핸들러 rename 시 시계열 단절·외부 검토 2차 흡수·기각

#### Q5
- α (metrics raw 노출): Prometheus scrape 외 metrics raw JSON 효용 0·보안 노출면 확대·기각
- γ 현 상태 유지 (prometheus 미노출): Q4 β′ 계측 효용 0·기각

#### Q6
- α prefix 표준만 (5 표준키 미박제): catch 블록 grep 일관성 결손·운영 추적 효용 부족·기각
- γ 현 상태 유지: WARN-6 P1 해소 실패·기각

#### Q8
- α 강제 (NO @Transactional 전건 의무): △ 4건 회귀·테스트 의도 분리 위배·기조 4 위배·기각
- γ 미박제: WARN-9 P2 해소 실패·기각
- 마커 어노테이션 (`@TestCategory(AFTER_COMMIT)`): JUnit Tag 인프라·CI 분리·테스트 분류 룰 동반·과잉개발·외부 검토 2차 흡수·기각

#### Q9
- α `@Order` 전건 핸들러 박제: 핸들러 추가 시 순위 재할당 비용·인메모리 가정 종료 시점 자연 진입·기각
- β E5 한정 `@Order` 박제: 일부만 박제 시 일관성 부족·기각

#### Q10
- α `occurredAt` 추가 (양자 보유): 중복 필드·의미 모호·기각
- β `refundedAt → occurredAt` 리네임: D-70 갱신·소비자 영향·기각

#### Q11
- α 전건 publicId 의무화 + 필드명 통일: 11 record 수정 회귀·발행처 11건 수정·기조 4 위배·기각
- γ 현 상태 유지: WARN-7 P2 해소 실패·기각

#### Q12
- α 단일 PR: correlationId/로깅과 Micrometer 영향 범위 결합·rollback 비용 증가·기각
- γ PR-1/PR-2/PR-3 분할: 과잉분할·기조 4 위배·기각
- **α′ (catch 직접 인용·MDC 미주입)**: Q3 β′ 약해석 분기 위험·운영 grep 정상 흐름 추적 불가·Q14 ✓ 자연 상속 활용 못 함·18 핸들러 분산 결합 (Outbox 도입 시 재작업 비용 대)·기조 1·2·5 위배·기각 (β″′ 자체 정정 2026-06-30)
- **β / β″ (wrapper 신설 후 미사용 또는 부분 적용)**: dead code 또는 책임 모호·기조 4 위배·기각

#### Q13
- β 별도 ULID 생성: 추적 키 2개·운영 grep 부담·인메모리 publisher 가정 하 효용 미약·기각
- γ correlationId 미도입: Q3 γ 회귀·기각

#### Q14
- β MDC copy helper 무조건 신설: 자연 상속 가정 하 과잉개발 위험·기각
- γ 실측 생략·미상속 발견 시 백로그: 기조 5 실측 우선 위배·기각

#### Q15
- β WARN-12 처치 별도 트랙 이연: PR-1 종료조건 결손·요청 종료 시 MDC 오염 위험 잔존·기각
- γ PR-1 진입 전 본 채팅 즉시 MCP read: 정책 결정과 구현 상태 결합·외부 검토 3차 흡수·기각

#### Q16
- β 운영 메트릭 추가 (`zslab.log.correlationId.missing` 카운터): LogbackAppender·추가 의존성·과잉개발·기각
- γ 통합 테스트 + 운영 메트릭 양자: 부담 가중·기각

#### Q17
- β 단위 테스트 1건 (MeterRegistry mock): 통합 흐름 미검증·기각
- γ PR-2 종료조건 제거 (prometheus scrape로 충분): 검증 공백·운영 도입 시 첫 실패 시점에 발견·기각

---

### 관련 결정

D-29 (save→publish·no flush·Q3 β′ event record 무수정 정합·β″′ TracedEventPublisher 트랜잭션 경계 무관 정합)·D-30 (이벤트 사실 통지·payload 무수정·Q3 β′ 정합)·D-48 (TraceIdFilter MDC 박제·Q3 β′·Q13 α 자연 확장)·D-69 (RefundCompleted Publisher/Consumer 시점 분리·Q10 γ 정합)·D-70 (refundedAt 시각 의미·Q10 γ 의도 보존)·D-74 (이벤트 핸들러 빈 네이밍)·D-75 (이벤트 핸들러 AFTER_COMMIT·REQUIRES_NEW·Q1 γ 패턴 카탈로그 SoT)·D-90 Q1 (AFTER_COMMIT 패턴·박제 1줄 유지·Q1 γ 흡수)·D-90 Q5 (통합 테스트 NO @Transactional + TransactionTemplate·Q8 β 정합)·D-91 (FK 부모 그래프 시드·Q8 β 5중 의무)·D-94 Q6 (RefundService.initiate 활성 Refund 게이트·박제 1줄 유지·Q1 γ 패턴 B 흡수)·D-95 Q6 (NotificationLog 멱등 미도입·인메모리 publisher 가정·박제 1줄 유지·Q1 γ·Q2 γ 통합 흡수)·D-97 Q8 (canTransitionTo 자연 흡수·박제 1줄 유지·Q1 γ 패턴 A 흡수)·D-98 Q5 (소비 순서·박제 1줄 유지·Q1 γ 패턴 E 흡수)·D-99 Q11 (registerExchangeShipment 이중 호출 가드·박제 1줄 유지·Q1 γ 패턴 B 흡수)·LT-02 (FK_CHECKS try-finally·Q8 β 5중 의무)·invariants 전건·state-machine 전건·aggregate-boundary 전건·domain-events §1·§2 E1~E11·docs/track-16/recon-report.md §1~§13.7·D-100 Q3 β′ 박제 본문 자체 갱신 (eventName MDC 제거·옵션 4 채택·2026-06-30).

---

### 후속 트랙

- **Track 17+ Outbox·이벤트 저장소**: Q2 트리거 4건 (다중 인스턴스·외부 브로커·이벤트 누락 트랩·재처리 SLA) 중 1건 도달 시 별도 트랙·eventId 동반·@Order 명시적 순서 보장·Q3 β′ correlationId 별도 ULID 분리 자연 진입·TracedEventPublisher 내부 Outbox writer 자연 흡수 진입점 (β″′)
- **Spring Security 정식 도입**: X-Seller-Id·X-Admin-Id stub 3종 일괄 대체·D-99 Q11 후속·Q5 α′ Actuator 보안 처리 결합
- **Inventory 차감/복구 Order-Claim 연동**: E1·E2·E9 핸들러·멱등 패턴 5종 카탈로그 (Q1 γ) 적용 1회차·Inventory 도메인 행위 신설
- **자동 구매확정 타이머**: DELIVERED + N일 → OrderItem CONFIRMED 전이·배치/스케줄러·Q14 스레드 전환 시 MDC 복원 전략 재결정 진입점
- **NotificationLog 발송 어댑터**: PENDING→SENT 전이·실 전송 게이트웨이·D-86 §후속·Q4 β′ failed 카운터 적용 확장
- **EXCHANGE 차액 환불·부분환불**: D-98 Q2 박제 1줄 진입점·refundAmount>0 흐름
- **외부 택배 어댑터**: D-98 Q1 ClaimPickedUp 발행 주체 확장·자동 픽업 확인
- **D-50 매트릭스 정정**: 본문 정정만·별도 트랙
- **재출고 흐름 정의**: D-99 Q11 §후속·운영 데이터 누적 후 후속 트랙
- **로그 수집 인프라 도입**: Loki·Grafana·ELK 등·Q16 운영 지속 측정 자연 진입
- **ArchUnit 정적 검사 도입**: Q8 Javadoc 누락 시 테스트 실패 검사·외부 검토 3차 후속 검토 박제

---

### 후속

1. 본 결정 박제 = 사용자 직접 처리 (D-94·D-95·D-96·D-97·D-98·D-99 패턴 7회차).
2. **PR-1** 브랜치 `feat/track-16-observability-standards` 생성 (main HEAD 422a92e 기준·기 생성 완료).
3. 가드 5 사전 통지: PR-1 신규 4~5 (TracedEventPublisher + 단위 + MdcPropagation 단위 + CorrelationId 통합 + 조건부 MdcContextCopier) + 수정 production 13 (TraceIdFilter 1·Notification 7·5 Service 발행처)·PR-2 수정 production 3 (build.gradle.kts·application.yml·application-prod.yml) + 신규 (EventMetricsRecorder 1) + 신규 테스트 1 — Claude Code 구현 프롬프트 시점 일괄 통지.
4. TDD 우선 (단위 → 통합)·BUILD SUCCESSFUL·PR-1 신규 ≥5 PASS·기존 회귀 0.
5. GitHub Web UI PR 생성·Base: main·Compare: feat/track-16-observability-standards·외부 검토 1·2·3차 완료·Q12 β″′ 자체 정정 동반·S급.
6. PR-1 머지 후 PR-2 진입·동일 절차.
7. PR-1·PR-2 양 머지로 Track 16 종결·D-99 §후속 Observability carry-over 영구 해소·박제 1줄 누적 6건 통합 재평가 완료·신규 박제 1줄 14건 + Q12 β″′ 갱신 1줄 추가·관측성 표준 박제 완료.

---

## PR-Track17 결정 항목 (2026-07-01) [확정 2026-07-01]

> 소스: docs/track-17/recon-report.md · 외부 검토 1·2차 (2026-07-01) · D-08·D-09·D-17·D-30·D-100 Q1·Q2 박제 정합
> 트랙 등급: A급 (도메인 행위 신설·외부 검토 1·2차 수렴 완료)
> 베이스: main HEAD 8f17fb4 (Track 16 PR-2 머지·445 tests PASS·D-100 박제 완료)
> 사용자 확정(2026-07-01): Q0~Q12 + WARN-7 전건 외부 검토 2차 권장안 1:1 채택.

---

### D-101: Track 17 Inventory 도메인 행위 구현 결정

**상태**: [확정 2026-07-01]

#### §1 트랙 식별자 (Q0)

Track 17 = Inventory 도메인 행위 구현 (A급). E1 OrderPlaced(예약) · E2 PaymentCompleted(차감) · E3 PaymentFailed(해제) · E9 ClaimCompleted(복구) 4 이벤트 핸들러 + Inventory 도메인 행위 4건 + InventoryService 신설.

#### §2 Inventory 도메인 행위 4건 (Q1)

| 메서드 | 동작 | invariant 가드 |
|---|---|---|
| `reserve(int qty)` | reserved += qty · recalculateAvailable() | INV-1 (available ≥ 0)·INV-3 (reserved ≥ 0) |
| `release(int qty)` | reserved -= qty · recalculateAvailable() | INV-3 |
| `commitReservation(int qty)` | on_hand -= qty · reserved -= qty · recalculateAvailable() | INV-1·INV-3·INV-4 |
| `restoreStock(int qty)` | on_hand += qty · recalculateAvailable() | INV-2 (available = on_hand - reserved)·INV-4 |

- `private void recalculateAvailable()` Aggregate 내부 캡슐화. Service에서 available 직접 계산 금지.
- invariant 위반 시 도메인 예외 throw (DomainException 계열).
- Setter 미신설.
- 명명 근거: E2는 "재고 감소"가 아니라 **"예약 확정"** (D-08 차감 = reserved -= qty + on_hand -= qty). `commitReservation`이 도메인 의미 정합. `deduct`는 직접 출고와 의미 충돌·Q12 ADJUST(향후)와 명명 충돌.

#### §3 핸들러 패키지 (Q2)

`inventory/handler/` 단독 패키지 신설. 소비측 Aggregate 귀속 (notification/handler·claim/handler·order/handler 기존 패턴 1:1 정합).

| 핸들러 | 이벤트 | 동작 |
|---|---|---|
| `InventoryOrderPlacedHandler` | E1 | OrderItem별 reserve |
| `InventoryPaymentCompletedHandler` | E2 | OrderItem별 commitReservation |
| `InventoryPaymentFailedHandler` | E3 | orderId 재조회 후 OrderItem별 release |
| `InventoryClaimCompletedHandler` | E9 | claimId 재조회 후 type별 분기 (§5) |

#### §4 동시성 락 정책 (Q3·D-09 잔여 결정 확정)

**채택**: α 비관락 일원화.

- `InventoryRepository.findByVariantIdForUpdate(Long variantId)` 단일 메서드.
- 모든 Inventory 도메인 행위(reserve·release·commitReservation·restoreStock) 진입 시 비관락 획득.
- `@Version` 미도입. V8 Flyway 마이그레이션 미신설.
- **InventoryService 외부에서 일반 `findByVariantId` 사용 금지** (read-only 조회는 별도 Service 경로·본 트랙 OOS).

**근거**:
- D-100 Q2 γ Outbox 트리거 4건 (다중 인스턴스·외부 브로커·이벤트 누락·재처리 SLA) 전건 미충족 = 현재 단일 인스턴스 동기 발행 환경.
- 낙관락 도입 시 OptimisticLockException 발생·재시도 정책 부재 → 사용자 실패 직접 노출.
- 행위별 락 혼용 시 동일 row(variant 1:1) 경합 시나리오에서 reserve 비관락 완료 후 deduct 낙관락 flush 시 version 충돌. 결국 같은 row 경합·운영 복잡도만 증가.
- 본 단계 정책: **oversell 방지 > 처리량**.

**재평가 트리거**: D-100 Q2 Outbox 트리거 충족 시점에 @Version 도입 재평가 (본 트랙 OOS).

#### §4 갱신 (2026-07-01·E3 핸들러 read-only 예외 추가)

**정정**: §4 원안 "InventoryService 외부 findByVariantId 사용 금지·단일 예외 = CheckoutService 사전 조회 read-only" → "read-only 2 예외".

**추가 예외**: InventoryPaymentFailedHandler.1차 가드 (§6 갱신 E3 정정 정합).
- 목적: 잔여 reserved == 0 skip 판정 (재전달 방어)
- 성격: read-only 조회 (쓰기 진입점 아님)
- 방식: InventoryRepository.findByVariantId 일반 조회
- 정합: CheckoutService 사전 조회와 동일 read-only 성격·§4 예외 취지 평행

**원칙 재확인**: 쓰기 진입점 (reserve·release·commitReservation·restoreStock·exchange) 은 InventoryService·비관락 (findByVariantIdForUpdate) 일원화 유지. read-only 조회는 예외 확장 허용·건별 정당화 의무 (본 결정 정합).

**재평가 트리거**: read-only 예외 ≥3건 누적 시 §4 원칙 재검토 (별도 트랙).

#### §5 EXCHANGE 트랜잭션 — D-08 [갱신 Track17] (Q4)

**D-08 박제 부분 갱신**: "트랜잭션 분리" → "업무 단계 분리·DB 트랜잭션 단일".

| D-08 박제 부분 | 변경 전 | 변경 후 |
|---|---|---|
| 교환 행 동작 | 회수분 복구 + 교환품 신규 차감 (**2트랜잭션 분리**) | 회수분 복구 + 교환품 신규 차감 (**업무 단계 분리·DB 트랜잭션 단일**) |
| Impact 절 | 교환은 복구·차감 **2트랜잭션 분리**로 부분 실패 시 보상 가능 | 교환은 복구·차감 **단일 DB 트랜잭션** 내 처리·부분 실패 시 트랜잭션 롤백 |
| M-12 | 회수=RETURN·재출고=ORDER 2건 분리 기록 | **유지** — 단일 DB TX 내 InventoryHistory 2행 (RETURN/ORDER) 생성. enum 확장 회피 목적은 유지 |

**구현 형식**:
```java
// InventoryService
@Transactional
public void exchange(Long returnVariantId, int returnQty, 
                     Long newVariantId, int newQty, Long claimId) {
    Inventory ret = repo.findByVariantIdForUpdate(returnVariantId)...;
    ret.restoreStock(returnQty);
    historyRepo.save(InventoryHistory.create(ret, RETURN, returnQty, "claim", claimId, ...));
    
    Inventory neu = repo.findByVariantIdForUpdate(newVariantId)...;
    neu.commitReservation(newQty);  // 또는 reserve+commit 2단계
    historyRepo.save(InventoryHistory.create(neu, ORDER, -newQty, "claim", claimId, ...));
}
```

**근거**:
- 본 프로젝트 D-100 Q2 Outbox 미충족·Saga/보상 메커니즘 부재.
- D-08 원안의 "2트랜잭션 분리"는 분산 트랜잭션 가정인데 인프라 부재 → 현실 충돌.
- 단일 DB TX는 원자성 보장·부분 실패 시 자연 롤백.
- 락 범위 확대(2 row 비관락 보유)는 EXCHANGE 빈도 낮아 트레이드오프 수용.

**재평가 트리거**: Saga·Outbox 도입 시 D-08 원안(2 TX 분리) 회귀 재평가.

#### §5 갱신 (2026-07-01·PR-B 결정 라운드 확정)

**exchange() 옵션 = α 채택** (reserve → commitReservation 2단계).

**채택 근거** (기조 5 재확인·docs/track-17/recon-report.md §16.6 실측 인용):
- 기조 1 운영 용이성: 기존 4 도메인 행위(reserve·release·commitReservation·restoreStock) 재사용·운영 지식 유지
- 기조 3 과잉문서 회피: exchange() Javadoc 1줄로 시맨틱 어색 처치
- 기조 4 과잉개발 회피 (강): 신규 도메인 행위 0건·5번째 메서드 명명 논쟁·단위 테스트 신설 회피
- 기조 5 실측 우선: §16.6 commitReservation INV-3 가드 실측·γ(commitReservation 단독) 불가 실증

**구현 시그니처** (D-101 §5 예시 코드 그대로):

    // InventoryService
    @Transactional
    public void exchange(Long returnVariantId, int returnQty,
                         Long newVariantId, int newQty, Long claimId) {
        Inventory ret = repo.findByVariantIdForUpdate(returnVariantId)...;
        ret.restoreStock(returnQty);
        historyRepo.save(InventoryHistory.create(ret, RETURN, returnQty, "claim", claimId, ...));

        Inventory neu = repo.findByVariantIdForUpdate(newVariantId)...;
        neu.reserve(newQty);            // α 1단계: 예약
        neu.commitReservation(newQty);  // α 2단계: 확정
        historyRepo.save(InventoryHistory.create(neu, ORDER, -newQty, "claim", claimId, ...));
    }

**exchange() Javadoc 의무 1줄**: "EXCHANGE 교환품 신규 발송은 예약 후 즉시 확정 패턴 (α·D-101 §5 갱신)·commitReservation INV-3 가드 자연 흡수·기조 4 정합·§7 InventoryHistoryChangeType.ADJUST 명명 충돌 회피."

**restoreStock type 가드 확장 확정**: 기존 InventoryService.restoreStock의 CANCEL/RETURN 가드는 무변경 유지. exchange() 내부 회수는 restoreStock 재사용 없이 직접 `Inventory.restoreStock(qty)` + `InventoryHistoryChangeType.RETURN` History 기록으로 처치 (Service 가드 우회는 InventoryService 내부 단독 메서드 exchange()에서만 허용·외부 Service 미노출).

**기각**:
- β deductDirectly 신규 메서드: 신규 도메인 행위·명명 논쟁·기조 4 위배·기각
- γ commitReservation 단독: §16.6 INV-3 가드 실측 위반·기각

**관련 결정**: D-101 §5 (예시 코드 SoT)·§7 (InventoryHistory Immutable)·§16.6 recon-report.md 실측·기조 4·5.

#### §6 4 핸들러 멱등 패턴 — γ 이중 방어 + D-100 Q1 패턴 A 보강 (Q5)

**채택**: γ 핸들러 1차 가드 + 도메인 2차 가드 이중 방어.

| 이벤트 | 핸들러 1차 가드 (패턴 B) | 도메인 2차 가드 (invariant throw) |
|---|---|---|
| E1 | OrderItem.item_status != ORDERED면 skip | `reserve` invariant 위반 시 throw |
| E2 | 1차 가드 없음 (PAY-3b UNIQUE + D-100 Q2 인메모리 publisher 이중 방어) | commitReservation 내 INV-3·INV-4 위반 시 throw |
| E3 | orderId 재조회 후 잔여 reserved == 0이면 skip | `release` invariant 위반 시 throw |
| E9 | InventoryHistoryRepository.existsByReferenceTypeAndReferenceId("claim", claimId) true면 skip (순서 독립·D-100 Q9 γ 정합) | type별 도메인 행위 invariant 위반 시 throw |

**계층 책임 분리**:
- **Handler**: 멱등 (재실행 안전성)
- **Domain**: 불변식 (도메인 일관성)

핸들러 Javadoc 의무 (D-100 Q1 γ 정합): 핸들러 1차 가드 패턴 B + 도메인 2차 가드 사유 1줄 명시.

**D-100 Q1 카탈로그 보강 의무**:

| 패턴 | 변경 전 | 변경 후 |
|---|---|---|
| A | canTransitionTo 자연 흡수 | **상태전이 자연 흡수 (불변식 제외)** |

근거: 도메인 invariant(reserved < qty throw)를 패턴 A로 흡수하면 카탈로그 경계 흐려짐. 불변식은 멱등 패턴 카탈로그에 포함하지 않음. 핸들러 멱등(B)과 도메인 불변식(throw + catch 책임)을 분리.

**적용**: D-100 본문 Q1 절 패턴 A 정의 1줄 갱신 (별도 박제 PR 또는 Track 17 PR-A에 동반).

#### §6 갱신 (2026-07-01·라이브 트랩 발견·PR-B 구현 진입 전 정정)

**발견**: PR-B 구현 진입 전 Claude Code 실측 정찰에서 §6 원안 E2·E9 1차 가드가 실제 이벤트 흐름과 충돌 확인.

**E2 원안 문제 (P0)**: PaymentService.publishEvent → OrderEventHandler @EventListener (동일 TX·동기) → markPaid → 커밋 전 OrderItem PAID 전이. InventoryPaymentCompletedHandler는 AFTER_COMMIT 실행이므로 실행 시점에 모든 item 이미 PAID. "PAID면 skip" 원안 → 항상 skip → commitReservation 영구 미실행 (데드코드).

**E2 정정 (A′ 채택)**: 1차 가드 제거. 재전달 방어선 이중화:
- Payment 레벨: PAY-3b UNIQUE (pg_provider·pg_tid)로 PG 콜백 재수신 차단
- 이벤트 레벨: D-100 Q2 γ at-most-once 인메모리 publisher
- 도메인 레벨: commitReservation INV-3 backstop
→ 인메모리 publisher 환경에서 이벤트 재전달 자연 발생 불가·PAY-3b 이중 방어 충분.

**E3 원안 유지 확인 (2026-07-01·§4 갱신 동반)**: E3 1차 가드 "잔여 reserved == 0 skip"은 원안 유지·InventoryRepository.findByVariantId read-only 조회 사용·§4 갱신 (read-only 2 예외) 정합.

**E9 원안 문제 (P1)**: InventoryClaimCompletedHandler와 기존 ClaimCompletedHandler 양자 AFTER_COMMIT 소비·`@Order` 0건 실측. ClaimCompletedHandler가 먼저 실행 시 OrderItem 종결 상태 진입 → InventoryClaimCompletedHandler skip → 재고 복구 누락. 순서 비결정적 라이브 레이스.

**E9 정정 (A 채택)**: InventoryHistoryRepository.existsByReferenceTypeAndReferenceId("claim", claimId) 신규 메서드로 History 기반 멱등 가드. 형제 핸들러 순서 독립·완전 멱등.

**기각**:
- E9 옵션 B (@Order 강제): D-100 Q9 γ "AFTER_COMMIT 핸들러 간 실행 순서 비보장·핸들러 간 의존성 부재" 박제 위배·형제 Aggregate 핸들러 순서 강결합·기각.

**Outbox 도입 시 재평가**: D-100 Q2 트리거 충족 시점에 E2 1차 가드 재도입 필요성 재평가 (인메모리 publisher 가정 종료 시).

**D-100 Q1 카탈로그 보강 후속**: 패턴 B 세분 (B-1 상태 기반·B-2 History 기반)은 D-100 본문 갱신 시 흡수 (별도 트랙·본 트랙 OOS·기조 3).

**본 트랩 라이브 트랩 카탈로그 이관 판단**: 단건 트랩·설계 결정 미스매치 성격·CI 미탐지 표면화 성격 아님·D-82 임계 미도달·decisions.md 단건 박제 유지·live-traps.md 이관 불요.

#### §7 InventoryHistory `@Immutable` (Q6)

`@Immutable` 미적용 유지 (현 상태). InventoryHistory.java Javadoc 명시 "A2 결정·AbstractCreatedOnlyEntity Javadoc 정합" 1:1 유지. 도메인 메서드 부재·정적 팩토리 단일·UPDATE 진입점 부재로 코드 규율 보장.

#### §8 ClaimCompleted record 데이터 조달 (Q7)

**채택**: β record 무변경·orderItemId 재조회.

- `ClaimCompleted` record 6 필드 유지 (claimId·claimPublicId·orderItemId·claimType·status·occurredAt).
- `InventoryClaimCompletedHandler`가 claimId로 Claim 재조회 → orderItemId로 OrderItem 재조회 → variant_id·quantity 도출.
- 발행처 (ClaimService.markCompleted) 무변경.

**근거**:
- D-30 사실 통지 원칙 정합 (이벤트 페이로드에 도메인 상태 복제 금지).
- E3 PaymentFailed 동일 패턴 (orderId 재조회) 1:1 정합.
- 기존 ClaimCompleted 소비자 2건 (ClaimCompletedHandler·NotificationClaimCompletedHandler) 모두 claimId 재조회 패턴·1:1 정합.
- D-100 Q3 옵션 4 "11 record 수정 회귀 회피" 정합.

**재평가 트리거**: EXCHANGE 신규 variant가 ExchangeItem 등 별도 Aggregate 단일 조회로 확보 불가능한 구조 변경 발생 시 재평가.

#### §9 PR 분할 (Q8)

**채택**: γ 2 PR.

| PR | 범위 |
|---|---|
| `feat/track-17-inventory-domain` (PR-A) | Inventory Aggregate 도메인 행위 4건 + `recalculateAvailable` + InventoryRepository.findByVariantIdForUpdate + InventoryService + 단위 테스트 (도메인 행위·invariant 검증) |
| `feat/track-17-inventory-handlers` (PR-B) | 4 핸들러 (E1·E2·E3·E9) + 통합 테스트 (D-100 Q8 β 5중 의무) |

**근거**:
- 결정 라운드 종결 후 락 정책(Q3) 고정 가정 → 외부 검토 추가 비용 절감.
- 단일 PR은 머지 충돌 비용 큼.
- 3 PR은 외부 검토 1차 3회 비용·과잉.

#### §10 Inventory 부족 시 Order 영향 (Q9)

**채택**: α+β 혼합 이중 방어.

- **1차 (UX 정합)**: OrderService.placeOrder 진입 시 사전 재고 조회 → 부족 시 즉시 4xx 응답 (트랜잭션 진입 전).
- **2차 (Aggregate 정합)**: Inventory.reserve 도메인 invariant 가드 → INV-3 위반 시 throw → OrderPlaced 이벤트 핸들러 트랜잭션 롤백 → Order 자체 롤백.

**근거**:
- OrderService 단독 사전 조회는 TOCTOU 취약 (조회 ↔ 예약 사이 동시성).
- Inventory invariant 단독은 UX 저하 (커밋 시점 실패).
- 이중 방어로 정합·UX 모두 확보.

**OrderService 사전 조회 범위**: read-only `findByVariantIdIn` 사용 (Q3 §4 "InventoryService 외부 일반 findByVariantId 사용 금지" 예외 — read-only 사전 검증 한정·쓰기 진입점 아님).

#### §11 InventoryHistory 생성 책임 (Q10)

InventoryHistory 생성 책임 = **InventoryService** (Aggregate 비포함).

- Aggregate 내부 행위(reserve·release·commitReservation·restoreStock)는 컬럼 갱신만 수행.
- InventoryHistory append는 InventoryService 트랜잭션 책임 (도메인 행위 호출 후 직접 생성·저장).
- 기존 `InventoryHistory.create()` 정적 팩토리 1:1 재사용.

**근거**: Aggregate 강결합 회피·응집도 < 결합 증가 비용. Service 단일 트랜잭션 내 도메인 행위 + History append 원자성 보장.

#### §12 이벤트 순서 보장 가정 (Q11)

본 트랙은 D-100 Q2 박제 상속.
- at-most-once + ordered (Spring ApplicationEvent 동기·D-29 save→publish).
- 별도 D-101 항목 박제 없음 (기조 3 과잉문서 회피).
- 트랙 진입점 카드(§15)에 "D-100 Q2 상속" 1줄 명시.

#### §13 운영자 ADJUST 진입점 (Q12)

**채택**: α 완전 OOS.

- 본 트랙에서 `adjustStock`·`recordInbound`·`recordOutbound` 도메인 메서드 미신설.
- 운영자 API 진입점 미신설.
- InventoryHistoryChangeType enum 6값 (ADJUST·INBOUND·OUTBOUND 포함) 유지·미사용 상태로 보존.
- E10 InventoryAdjusted 이벤트 발행 미신설.

**근거**:
- 진입점 없는 도메인 행위는 사양 부채.
- enum 존재 ≠ 도메인 행위 신설 의무.
- 기조 4 과잉개발 회피.

**이연**: Track 18+ 별도 트랙.

#### §14 WARN-7 (이벤트 역전) 처치

**처치**: WARN 제거.

**근거**:
- 본 환경 단일 인스턴스·Spring ApplicationEvent 동기·단일 스레드.
- Payment 상태 머신 (D-05): 한 Payment 행은 PENDING → PAID 또는 PENDING → FAILED 한 종결 상태만 보유. 동일 Payment에서 PAID·FAILED 동시 발행은 도메인 invariant 위반 (불가).
- 재시도 = 새 Payment 행 (D-05) → 동일 OrderItem에 대해 직전 Payment FAILED 종결·신규 Payment PAID 순차 발행·역전 시나리오 없음.

**재평가 트리거**: D-100 Q2 Outbox 트리거 충족 시점 (외부 브로커 도입 등)에 WARN 부활 재평가.

**메모 1줄**: live-traps.md 또는 D-100 Q2 본문에 "Outbox 도입 시 이벤트 역전 WARN 재평가" 박제 (선택·본 트랙 OOS).

#### §15 진입점 카드 (메모리 룰 #16 시범 적용 2회차)

| # | 항목 | 내용 |
|---|---|---|
| 1 | **목적** | Inventory Aggregate 도메인 행위 4건 신설 + 재고 트리거 4 이벤트 핸들러 신설로 예약/차감/해제/복구 4경로 완성. D-08 [갱신 Track17]로 EXCHANGE 단일 DB TX 회귀 |
| 2 | **핵심 진입점** | `inventory/entity/Inventory.java` (도메인 행위 4건) · `inventory/service/InventoryService.java` (신설·History 생성 책임) · `inventory/handler/` 패키지 (4 핸들러 신설) |
| 3 | **핵심 SoT** | D-07 (Inventory SoT 3컬럼) · D-08 [갱신 Track17] (예약→차감→복구·EXCHANGE 단일 TX) · D-09 (available 캐시·비관락 일원화·§4) · D-100 Q1 γ (멱등 패턴 B + 도메인 invariant 분리·패턴 A 정의 보강) · D-100 Q2 (at-most-once + ordered 상속) |
| 4 | **영향 범위** | inventory/ 전체 (entity·service·handler·repository) · checkout/service/CheckoutService (사전 재고 조회 진입점 §10) · docs/architecture-baseline/decisions.md (D-08·D-100 Q1 본문 갱신 동반) |
| 5 | **패턴 재사용 SoT** | D-100 Q1 γ 5 패턴 (B 적용 4건·A 정의 보강) · D-100 Q4 β′ 메트릭 (TracedEventPublisher 자동 활용) · D-100 Q6 β 로깅 6 표준키 · D-100 Q8 β 통합 테스트 5중 의무 · D-91 FK 부모 그래프 시드 (product → product_variant → inventory) · D-92 횡단 권한 검증 (운영자 ADJUST §13 OOS) |
| 6 | **트랩 주의** | LT-01 해당 없음 (public_id 미부여) · LT-02 try-finally 의무 (통합 테스트 신설 시) · LT-03 해당 없음 (deleted_at 없음) · **신규 트랩 후보**: 비관락 일원화 환경에서 read-only 일반 조회 진입점 격리 (`findByVariantId` 사용 금지 예외 = OrderService 사전 조회·§10 단일 예외) |

#### §16 외부 검토 이력

- **1차 (2026-07-01)**: Q0~Q8 9건 송부. 채택 Q0·Q2·Q6·Q7. 수정 권고 Q1(명명)·Q3(행위별 분리)·Q4(단일 TX)·Q5(γ)·Q8(3 PR). 신규 의제 4건 (Q9·Q10·Q11·Q12). 신규 WARN-7 (P0).
- **2차 (2026-07-01)**: 1차 권고 중 Q3·Q8 방향 전환 (Q3 α 비관락 일원화·Q8 γ 2 PR). Q4 D-08 박제 수정 권장. Q5 γ + 카탈로그 보강. Q9 α+β·Q11 인용 흡수·Q12 완전 OOS·WARN-7 제거 확정.
- **수렴 결과**: 2차 권장안 1:1 채택.

#### §17 후속 트랙 박제 의무

| 항목 | 처치 | 이연 |
|---|---|---|
| D-08 본문 갱신 | [갱신 Track17] 절 추가 (§5) | 본 D-101 박제 동반 |
| D-100 Q1 패턴 A 정의 보강 | "상태전이 자연 흡수 (불변식 제외)" 1줄 갱신 (§6) | 본 D-101 박제 동반 |
| Outbox 도입 시 재평가 항목 | Q3 @Version·Q4 2 TX 회귀·WARN-7 부활 | Track 18+ Observability/Outbox 트랙 |
| ADJUST 진입점 | adjustStock 도메인 메서드·E10 발행·운영자 API | Track 18+ |
| 자동 예약 만료 (D-08 M-14) | Track 25 D-109 스케줄러 자동 만료 배치 박제(완료) | 해소 |
| Reservation Tracking (D-17) | 도입 금지 유지·확장 경로 메모 | 외부 이연 |

---

**Why**: Inventory Aggregate Track 4 read-only 스캐폴딩 → 재고 쓰기 4경로 (예약·차감·해제·복구) 완성. D-08 박제와 본 프로젝트 인프라(D-100 Q2 미충족) 충돌 해소. 멱등 책임을 핸들러(B)·도메인(invariant)으로 명확히 분리해 D-100 Q1 카탈로그 경계 정합.

**Impact**: 
- E1·E2·E3·E9 4 이벤트 핸들러 신설로 재고 정합성 자동화.
- 비관락 일원화로 oversell 방지 보장 (단일 인스턴스 환경 정합).
- D-08·D-100 Q1 본문 갱신 동반 (PR-A 또는 별도 박제 PR).
- 운영자 ADJUST·Outbox·자동 만료 등 Track 18+ 명확한 이연.

**Alternative**:
- @Version 낙관락 도입 → 재시도 정책 부재·사용자 실패 노출 (기각).
- EXCHANGE 2 TX 분리 유지 → Saga/Outbox 부재로 보상 불가 (기각).
- 멱등 패턴 A에 invariant 흡수 → 카탈로그 경계 흐려짐 (기각).
- ADJUST 도메인 메서드 사전 신설 → 진입점 없는 사양 부채 (기각).

---

### D-102: Track 18 Admin Delivery — registerExchangeShipmentByAdmin wrapper·AdminDeliveryController 진입점

**상태**: [확정 2026-07-01]

#### §1 트랙 식별자

Track 18 = Admin Service 확산 트랙 (A급). Track 15·Track 17에서 확립된 Admin 인프라(AdminActorResolver·HeaderAdminActorResolver·AdminClaimController)를 Delivery 도메인으로 확산. registerExchangeShipmentByAdmin wrapper 신설 + AdminDeliveryController endpoint 확립으로 D-99 §후속 L5110 carry-over 해소.

#### §2 우선 진입 도메인 (Q1)

**채택**: α Delivery.

| 후보 | 근거 | 판정 |
|---|---|---|
| α Delivery (registerExchangeShipmentByAdmin) | D-99 §후속 L5110 명시 carry-over·primitive `registerExchangeShipment` (DeliveryService.java) 기 존재·wrapper 최소 범위 | **채택** |
| β ADJUST (adjustStock·E10) | D-101 §13 명시·도메인 행위 신설 동반·범위 초과·회귀 위험 중 | 기각 (Track 19+ 이연) |
| γ Refund·Payment | Admin 진입점 부재 실측 근거 부족 | 기각 (실측 후 별도 트랙) |

**근거**:
- primitive `registerExchangeShipment` 기 존재 → wrapper만 추가로 최소 범위.
- Admin 인프라 SoT 기 확립 (Track 15·Track 17) → 재사용 4회차로 횡단 원칙 확정.
- 회귀 위험 하·기조 4 (과잉개발 회피) 정합.

#### §3 URL 패턴·Response (Q2·재정찰 반영)

**채택**: β `/api/v1/admin/claims/{claimPublicId}/register-exchange-shipment` — SellerDeliveryController URL 1:1 대칭 (액터축 admin 치환).

| 항목 | 값 |
|---|---|
| Controller | `AdminDeliveryController` (신설) |
| URL prefix | `/api/v1/admin/claims/*` |
| 신규 endpoint | `POST /api/v1/admin/claims/{claimPublicId}/register-exchange-shipment` |
| Path Variable | `claimPublicId` (String) |
| Request Body | `RegisterExchangeShipmentRequest` (기존 DTO 재사용·`{carrier, trackingNo}`) |
| Response | `200 OK` + `RegisterExchangeShipmentResponse` (기존 DTO 재사용) |

**근거**:
- SellerDeliveryController 실측 URL `/api/v1/claims/{claimPublicId}/register-exchange-shipment` 1:1 대칭 (액터축만 admin 치환)·기조 1 (운영 용이성) 정합.
- 기존 `RegisterExchangeShipmentRequest`·`RegisterExchangeShipmentResponse` DTO 완전 재사용 → 신규 DTO 신설 회피·기조 4 정합.
- publicId 사용으로 내부 ID 노출 회피·Seller와 일관.
- Response 200 OK는 Seller controller 실측 정합·wrapper는 위임만 수행·리소스 신규 생성 시맨틱은 primitive 내부.

**기각**:
- **α (원 D-102 §3 안·`POST /api/v1/admin/deliveries/exchange` + body claimId Long)**: SellerDeliveryController URL 리소스 축 실측 결과와 불일치·Request DTO 신설 필요·내부 ID 노출·기조 4 위반. 기각.
- **γ (`POST /api/v1/admin/claims/{claimPublicId}/exchange-shipment`)**: Seller URL과 부분 불일치·재정찰 SoT 대칭 원칙 위반. 기각.

#### §4 AdminActorResolver 재사용 방식 (Q3)

**채택**: α 기 구현 `AdminActorResolver` interface + `HeaderAdminActorResolver` 그대로 주입.

- `backend/src/main/java/com/zslab/mall/common/auth/AdminActorResolver.java` 재사용.
- `HeaderAdminActorResolver` (X-Admin-Id 헤더 stub·누락 401·형식오류 400) 재사용.
- AdminClaimController approve/reject 패턴 1:1 재사용·`adminActorResolver.resolve(request)` 헤더 존재·형식 검증만·식별자 미사용 (D-93 Q3 stub 정합).

**근거**:
- D-93 Q2 "별도 인터페이스 분리" 원칙은 Buyer·Seller·Admin 간 분리 → Admin 내부 재사용은 단일 인터페이스.
- β 전용 Resolver 신설은 채택 사유 없음 → 기각.

#### §5 wrapper 시그니처 (Q4·재정찰 반영)

**채택**: β DeliveryService.registerExchangeShipmentByAdmin — primitive 직접 위임.

**구현 형식** (평문 들여쓰기):

    // DeliveryService
    @Transactional
    public Delivery registerExchangeShipmentByAdmin(Long claimId,
                                                    DeliveryCarrier carrier,
                                                    String trackingNo) {
        return registerExchangeShipment(claimId, carrier, trackingNo);
    }

- primitive `registerExchangeShipment` 인자 시그니처 1:1 재사용.
- actor 파라미터 비수신 (D-92 primitive actor 비의존 원칙).
- Admin 권한 검증은 Controller 레벨 (AdminActorResolver.resolve)·Service primitive 위임.
- Controller에서 claimPublicId → claimId 조회 후 wrapper 호출 (AdminClaimController approve/reject 패턴 1:1 정합).

**근거**:
- Admin 권한 검증 = Controller 레벨 완결 (D-93 Q3·Q5: Admin 전체 접근·헤더 검증만)·`authorizeSellerAccess` 패턴 불필요.
- Seller wrapper는 `ClaimService.registerExchangeShipmentBySeller`에 위치 (재정찰 실측): `authorizeSellerAccess(claim, sellerId)` — ClaimService private 메서드 의존. Admin은 이 단계 없음 → primitive 직접 위임이 최단 경로.
- Delivery 도메인 캡슐화: primitive가 DeliveryService 소속 → wrapper도 동일 클래스가 도메인 경계 정합.

**기각**:
- **α ClaimService.registerExchangeShipmentByAdmin (Seller wrapper 위치 대칭)**: Admin은 `authorizeSellerAccess` 의존 없음·ClaimService 경유 사유 없음·기각.

#### §6 통합 테스트 3건 (Q5·재정찰 반영)

| # | 시나리오 | 기대 결과 |
|---|---|---|
| 1 | X-Admin-Id 헤더 부재 | 401 Unauthorized |
| 2 | 유효 헤더 + 유효 claimPublicId + 승인된 Claim | 200 OK + `RegisterExchangeShipmentResponse` 반환·DB Delivery 행 생성 확인 |
| 3 | Claim 상태 미승인 등 primitive 예외 전파 | 4xx |

- CLAUDE.md 신규 도메인 통합 테스트 3건 의무 정합 (인증·성공·실패).
- LT-02 try-finally 의무 (SET FOREIGN_KEY_CHECKS 사용 시).
- D-91 FK 부모 그래프 시드 (claim → order → order_item·payment).
- SellerDeliveryController 통합 테스트 (존재 시) 시드·시나리오 패턴 1:1 재사용 권장.

#### §7 PR 분할 (Q6)

**채택**: 1-PR 단독 (내부 3-split: docs·feat·test).

| 커밋 | 범위 |
|---|---|
| 커밋 1 (docs) | decisions.md D-102 §1~§9 박제 (§8 §진입점 카드 4회차 포함) |
| 커밋 2 (feat) | DeliveryService.registerExchangeShipmentByAdmin + AdminDeliveryController (기존 DTO 재사용) |
| 커밋 3 (test) | 통합 테스트 3건 (인증·성공·실패) |

브랜치: `feat/track-18-admin-delivery`

**근거**:
- 범위 좁음 (wrapper 1건 + Controller 1건 + 테스트 3건·DTO 신설 무)·A급.
- D-92·D-93 재사용 4회차·1-PR 단독 패턴 10회차 반복·기조 4 정합.
- 2-PR·3-PR 분할은 오버헤드·기각.

#### §8 진입점 카드 (메모리 룰 #16 적용 4회차)

| # | 항목 | 내용 |
|---|---|---|
| 1 | **목적** | 교환 배송 등록 primitive에 대한 Admin actor wrapper 신설·AdminDeliveryController endpoint 확립·D-99 §후속 L5110 carry-over 해소 |
| 2 | **핵심 진입점** | `delivery/service/DeliveryService.java` (primitive `registerExchangeShipment` + wrapper `registerExchangeShipmentByAdmin` 신설) · `delivery/controller/AdminDeliveryController.java` (신설) · `common/auth/AdminActorResolver.java` (재사용) |
| 3 | **핵심 SoT** | `registerExchangeShipmentByAdmin` wrapper — primitive 인자 시그니처 1:1 재사용·actor 파라미터 비수신 (D-92 primitive actor 비의존 원칙) · SellerDeliveryController URL `/api/v1/claims/{claimPublicId}/register-exchange-shipment` 1:1 대칭 (액터축 admin 치환) |
| 4 | **영향 범위** | Delivery Aggregate 단독 · 패키지 delivery/service·delivery/controller·common/auth(재사용) · 신규 파일 2건 (AdminDeliveryController·통합테스트) · 수정 파일 1건 (DeliveryService +1메서드) · DTO 신설 무 (기존 `RegisterExchangeShipmentRequest`·`RegisterExchangeShipmentResponse` 재사용) |
| 5 | **패턴 재사용 SoT** | D-92 (횡단 권한 원칙·Service 진입점 primitive·wrapper 캡슐화) 재사용 4회차 · D-93 (AdminActorResolver seam·X-Admin-Id stub) 재사용 4회차 · SellerDeliveryController URL 리소스 축 대칭 (액터축 admin 치환) · AdminClaimController approve/reject Controller 패턴 1:1 재사용 |
| 6 | **트랩 주의** | LT-01 비해당 (신규 Entity 무) · LT-03 비해당 (신규 Entity 무) · LT-02 try-finally 의무 (통합 테스트 SET FOREIGN_KEY_CHECKS 사용 시) · **원칙 감시**: primitive에 actor 파라미터 추가 금지·wrapper 캡슐화 유지 · **D-40 액터 중립 재확인**: `/api/v1/admin/claims/*` Admin 전용·`/api/v1/claims/*` (Seller)와 분리 |

#### §9 후속 트랙 박제 의무

| 항목 | 처치 | 이연 |
|---|---|---|
| AdminDeliveryController 확장 (markShipping/markDelivered 등 일반 주문 Delivery 진입점) | D-99 §후속 L5108 carry-over 유지 | Track 19+ |
| Admin Refund·Payment 진입점 | SoT 실측 후 별도 트랙 | Track 19+ |
| ADJUST 진입점·adjustStock·E10 | D-101 §13 carry-over 유지 | Track 19+ |

---

---

### D-103: Track 19 NotificationLog 발송 어댑터 — NotificationSender seam·즉시 dispatch 통합

**상태**: [확정 2026-07-02]

#### §1 트랙 식별자

Track 19 = NotificationLog 발송 어댑터 트랙 (A급). D-86 §후속 "Track 8+ Application Service 진입 시 NotificationLog.status PENDING→SENT 전이 핸들러 (E1·E2·E4·E5·E9·E10 이벤트 소비)" carry-over 종결. NotificationSender interface seam 신설 + MockNotificationSender 스텁 + save/dispatch 분리 + markSent/markFailed 도메인 메서드 신설로 D-95 §OUT-OF-SCOPE 및 D-86 §후속 일부 종결.

#### §2 결정 3건

| # | 안건 | 채택 | 근거 |
|---|---|---|---|
| Q1 | Sender 시그니처 | α `send(NotificationLog)` 단일 인터페이스 + MockNotificationSender | PaymentGateway 액션 분리 패턴 정합·Track 19 범위상 EMAIL 단독·채널별 impl 분리는 Track 20+ 실 어댑터 진입 시 자연 확장 |
| Q2 | PENDING 처리 흐름 | α + save/dispatch 분리 (이벤트 핸들러 내 즉시 발송) | D-95 A2-α 재throw 금지 원칙 정합·PENDING 상태 재처리 진입점 보존·D-100 Q2 인메모리 publisher 가정 정합·Outbox 트리거 4건 미충족 |
| Q3 | failed 카운터 | β `zslab.notification.failed{event, channel}` 별도 SoT | D-100 Q4 β′ 이벤트 축(published·failed) 정의·발송 축은 어댑터 계층·SRP 정합·EventMetricsRecorder 책임 유지·Track 20+ 확장 안전 지대 |

**기각**:
- **Q1 β (채널별 분리 `sendEmail/sendSms/...`)**: 실사용 EMAIL 단독·과잉설계·기조 4 위반. 기각.
- **Q2 β (배치 폴링)·γ (Outbox 준비)**: 인메모리 publisher 가정·트리거 미충족·범위 초과. 기각.
- **Q3 α (`zslab.event.failed` 재사용)**: 이벤트 소비 성공 후 어댑터 실패는 계층 상이·의미 혼용. 기각.

#### §3 NotificationSender 계약

**파일**: `backend/src/main/java/com/zslab/mall/notification/adapter/NotificationSender.java`

- 시그니처: `void send(NotificationLog notificationLog)`
- 계약: RuntimeException 전파 (커스텀 예외 계층 미도입·§9 §후속 이연)
- 파라미터명 `notificationLog` 채택 (impl `@Slf4j log` 필드 shadowing 회피·PaymentGateway 인터페이스/impl 동일명 관례 정합)
- 구현체: MockNotificationSender (`@Component`·`@Slf4j`·외부 호출 없이 log 모사·성공 반환)

**패턴 재사용**: PaymentGateway interface + MockPaymentGateway 패턴 1:1 정합.

#### §4 dispatch 흐름 (save/dispatch 분리)

**파일**: `backend/src/main/java/com/zslab/mall/notification/service/NotificationService.java`

**save(recipientUserId, templateCode, targetType, targetId, title, content, eventName)** — 시그니처 확장 (eventName 파라미터 추가):

    NotificationLog notificationLog = NotificationLog.create(...);
    notificationLogRepository.save(notificationLog);  // PENDING 적재
    log.info("[Notification] 적재 완료: ...");
    dispatch(notificationLog, eventName);              // 즉시 발송

**dispatch(notificationLog, eventName)** — 발송·상태 전이·계측:

    try:
        sender.send(notificationLog)
        notificationLog.markSent(LocalDateTime.now())
        notificationLogRepository.save(notificationLog)  // SENT 커밋
    catch (RuntimeException):
        notificationLog.markFailed(exception.getMessage())
        notificationLogRepository.save(notificationLog)  // FAILED 커밋
        metricsRecorder.recordFailed(eventName, channel.name())
        log.warn("[Notification] 발송 실패: event=... action=manual_review")
        // 재throw 금지 (D-95 A2-α)

**8종 recordXxx 호출부** eventName 문자열 전달: `OrderPlaced`·`PaymentCompleted`·`ClaimApproved`·`ClaimCompleted`·`RefundFailed`·`ClaimPickedUp`·`DeliveryStarted`·`DeliveryCompleted`.

**근거**:
- save 통합 배제: sender 예외 시 INSERT 롤백 위험·PENDING 상태 자체 소멸·재처리 진입점 상실.
- dispatch 분리: save는 항상 성공 (PENDING 커밋)·dispatch 실패는 상태 전이로 흡수·PENDING 상태 재처리 가능성 보존.

#### §5 markSent/markFailed 도메인 메서드

**파일**: `backend/src/main/java/com/zslab/mall/notification/entity/NotificationLog.java`

- `public void markSent(LocalDateTime sentAt)`: status = SENT·sentAt 갱신
- `public void markFailed(String reason)`: status = FAILED·failedReason 갱신
- 가드: PENDING이 아닌 상태 호출 시 `IllegalStateException` (중복 전이 방지)

**근거**:
- `AbstractCreatedOnlyEntity` Javadoc L22 "append-only이지만 `@Immutable`은 사용하지 않는다(A2 결정·NotificationLog status 전이 허용)" 명시 근거 재확인·상태 전이 정당성 확보.
- append-only = 행 추가 전용 (DELETE 금지)·status 전이 (UPDATE)는 허용 범위 내.
- D-01 Aggregate Root 도메인 메서드 원칙 정합 (Entity 내부 상태 캡슐화).

#### §6 NotificationDispatchMetricsRecorder SoT 분리

**파일**: `backend/src/main/java/com/zslab/mall/common/observability/NotificationDispatchMetricsRecorder.java`

- 메서드: `public void recordFailed(String eventName, String channel)`
- 카운터: `zslab.notification.failed{event, channel}`
- 태그 정책: event (8종)·channel (EMAIL·SMS·PUSH·IN_APP 4종) = 저카디널리티·template_code 태그 지양 (중카디널리티)

**근거 (SRP 분리)**:
- EventMetricsRecorder = 이벤트 축 (`zslab.event.published`·`zslab.event.failed`) SoT·D-100 Q4 β′ 채택 근거 = SRP 정합·별도 클래스 SoT
- 발송 실패 = 이벤트 소비 성공 후 어댑터 계층 실패 → 이벤트 축 밖 = **동일 근거로 EventMetricsRecorder와 분리 정당**
- Outbox 도입 시 EventMetricsRecorder SoT 유지·발송 축은 어댑터 확장 시 격리
- 위치 `common/observability/` = EventMetricsRecorder 선례 정합

#### §7 통합 테스트 T1~T3 이중 축 검증

**파일**: `backend/src/test/java/com/zslab/mall/notification/integration/NotificationDispatchIntegrationTest.java`

| # | 시나리오 | 기대 결과 |
|---|---|---|
| T1 | OrderPlaced 발행 + Sender 기본 no-op (성공) | NotificationLog status = SENT·sentAt 기록·failedReason NULL |
| T2 | OrderPlaced 발행 + Sender doThrow (실패 주입) | NotificationLog status = FAILED·failedReason 저장·`zslab.notification.failed{event=OrderPlaced, channel=EMAIL}` +1·원 주문 커밋 유지 |
| T3 | 발송 실패 + 이중 축 검증 | `zslab.event.failed` **무증가** (핸들러 catch 미진입) + `zslab.notification.failed` +1 (발송 축만 계측) + 상위 트랜잭션 커밋 유지 |

**T3 이중 축 검증 SoT**: `notification.failed +1` vs `event.failed +0` = **재throw 없음 이중 증명**·향후 dispatch 개조 시 회귀 감지 SoT (판단 3 β 채택 근거 실증).

**패턴**:
- `@MockitoBean NotificationSender` (기본 no-op·doThrow 주입)
- `@Transactional` 미부착·`TransactionTemplate` + `JdbcTemplate` (D-90 Q5 β)
- MeterRegistry delta assertion (컨텍스트 캐싱·Track 16 트랩 회피)
- LT-02 try-finally (`SET FOREIGN_KEY_CHECKS=0`↔`=1` 짝)

#### §8 스펙 진화 처리 (기존 테스트 정합화)

**갱신 대상 (2파일)**:
- `NotificationLogIntegrationTest.java`: status assertion 4건 `PENDING → SENT` (L120·L134·L149·L164) + 클래스 Javadoc·T1 DisplayName 정합화
- `NotificationServiceTest.java`: `@Mock NotificationSender`·`@Mock NotificationDispatchMetricsRecorder` 주입 + `verify(times(2))` (save 호출 = PENDING + SENT 2회 정확) + 성공 경로 `verifyNoInteractions(metricsRecorder)` + L76 assertion `PENDING → SENT`

**근거**:
- 회귀 처리 아님·**스펙 진화**: 기존 PENDING assertion = D-95 §OUT-OF-SCOPE (발송 어댑터 부재 시점) 스펙·Track 19 진입으로 소멸 예정 상태
- `verify(times(2))` 명시 = `atLeastOnce` 대비 회귀 감지 정확도 상승·중복 dispatch 등 오류 감지

#### §9 진입점 카드 (메모리 룰 #16 적용 5회차)

| # | 항목 | 내용 |
|---|---|---|
| 1 | **목적** | NotificationLog 발송 어댑터 seam 신설·즉시 dispatch 통합·D-86 §후속 PENDING→SENT 전이 핸들러 carry-over 종결 |
| 2 | **핵심 진입점** | `notification/adapter/NotificationSender.java` (interface 신설) · `notification/adapter/MockNotificationSender.java` (스텁 신설) · `notification/service/NotificationService.java` (save/dispatch 분리) · `notification/entity/NotificationLog.java` (markSent/markFailed 신설) · `common/observability/NotificationDispatchMetricsRecorder.java` (신설) |
| 3 | **핵심 SoT** | `NotificationSender.send(NotificationLog)` — 단일 액션·RuntimeException 계약 · `NotificationService.dispatch(notificationLog, eventName)` — 재throw 금지·상태 전이·계측 · `NotificationLog.markSent(LocalDateTime)`·`markFailed(String)` — PENDING 가드 IllegalStateException · `NotificationDispatchMetricsRecorder.recordFailed(event, channel)` — 발송 축 SoT |
| 4 | **영향 범위** | Notification Infra/Event Processing 단독 · 패키지 notification/adapter·notification/service·notification/entity·common/observability · 신규 파일 6건 (adapter 2 + MetricsRecorder 1 + 테스트 3) · 수정 파일 4건 (Entity + Service + 테스트 2) · DB 스키마 변경 무 (sent_at·failed_reason 기존재) |
| 5 | **패턴 재사용 SoT** | PaymentGateway interface + MockPaymentGateway seam 패턴 1:1 (Sender seam 정합) · EventMetricsRecorder SRP 원칙 (D-100 Q4 β′ 별도 클래스 SoT) 재사용 (MetricsRecorder 분리 정합) · D-95 A2-α 재throw 금지 원칙 재사용 (dispatch 예외 흡수 정합) · AbstractCreatedOnlyEntity A2 "status 전이 허용" 재사용 (markSent/markFailed 정당성) |
| 6 | **트랩 주의** | LT-01 비해당 (신규 Entity 무·기존 NotificationLog `@JdbcTypeCode` 미적용 유지) · LT-02 통합 테스트 try-finally 의무 · LT-03 비해당 · **LT-04 감시**: dispatch catch가 재throw 안 하므로 핸들러 catch = 실질 미발화 (Track 20+ 리팩터링 시 dispatch/handler catch 중복 여부 재확인) · LT-05 비해당 (형제 핸들러 순서 독립·@Order 0건) · **Track 16 트랩**: `@SpringBootTest` 기본 metric export 비활성화·MeterRegistry delta assertion 의무 |

#### §10 후속 트랙 박제 의무

| 항목 | 처치 | 이연 |
|---|---|---|
| 실 이메일·SMS·푸시 어댑터 (채널별 impl 분리·SMTP·SNS·FCM 등) | 본 seam 재사용·MockNotificationSender 교체 | Track 20+ |
| 커스텀 예외 계층 (NotificationDispatchException·채널별 예외) | 실 어댑터 도입 시점 결정 | Track 20+ |
| NotificationLog 재시도 흐름 (findByStatus(PENDING) 신설·배치 폴링) | 재시도 요구사항 SoT 확인 후 | Track 20+ |
| Outbox 도입 시 NotificationLog 소비자 연동·멱등 재검토 | D-100 Q2 트리거 4조건 도달 시 (D-95 Q6 박제 재검토 트리거) | 트리거 도달 시 |
| E10 (InventoryAdjusted) 알림 핸들러 신설 | ADJUST 진입점 구현 후 (D-101 §13 carry-over) | Track 20+ |
| NotificationService 클래스 Javadoc "발행처 존재 4 이벤트" 문구 → 7 이벤트 정합화 | 본 트랙 범위 밖·별도 정정 | 후속 소규모 정정 |
| NotificationSender·MockNotificationSender 파라미터명 통일 (`notificationLog`) | 확정 완료 | 처치 완료 |

#### §11 실측 검증

- 501 tests PASS (기존 493 + 신규 8·회귀 0건)·2026-07-02 로컬 실측
- 통합 T1·T2·T3 컨테이너 실측 통과·이중 축 검증 실증
- feat 커밋 944049b·test 커밋 738075a·머지 `ca18e32` (main)
- 브랜치: `feat/track-19-notification-dispatch` (머지 후 정리 대상)

---

### D-104: Track 20 AdminDeliveryController markDelivered wrapper — 배송 완료 Admin endpoint·404 예외 신설·base path 옵션 A

**상태**: [확정 2026-07-02]

#### §1 트랙 식별자

Track 20 = AdminDeliveryController 확산 트랙 (A급). D-102 §9 carry-over "AdminDeliveryController 확장 (markShipping/markDelivered 등 일반 주문 Delivery 진입점)" 부분 종결. 배송 완료 primitive `markDelivered`에 대한 Admin actor wrapper `markDeliveredByAdmin` 신설 + `POST /api/v1/admin/deliveries/{deliveryPublicId}/mark-delivered` endpoint 확립 + 404 스펙 필수 전제인 `DeliveryNotFoundException` 신설로 D-92 wrapper 캡슐화 패턴을 mark 계열로 확장한다. 정찰 산출물: docs/track-20/recon-report.md (gitignore·FAIL 2·WARN 3·판정 집계 PASS 10).

##### §1-A 트랙 범위 확정 근거 (α/β/γ 검토)

Track 20 진입 시 D-102 §9 carry-over(markShipping·markDelivered·일반 주문 Delivery 생성 진입점) 해소 범위 3안 검토:

| # | 범위 안 | 판정 | 근거 |
|---|---|---|---|
| α | markDelivered Admin wrapper 단독 (교환 배송 대상) | **채택** | 최소 범위·A급 유지·회귀 위험 최저·1-PR 단독·primitive `markDelivered` + `ExchangeDeliveryCompletedHandler` 체인 실측 SoT 위에 wrapper만 얹음·D-102 §5 패턴 1:1 재사용·기조 1·4 정합 |
| β | 일반 주문 Delivery 생성 진입점 + markShipping·markDelivered wrapper 동반 신설 | 기각 | 트리거 시점 결정(OrderPaid 자동 vs 별도 이벤트 vs Admin 수동 create)이 상태 머신·이벤트 배선 파급·범위 급증·A→S급 승격 가능성·정찰 재수행 의무·기조 4 위반 위험·부분 배송(DLV-2) 정책 실체화 강제·carry-over 종결 목적이 도리어 새 carry-over 생성 위험 |
| γ | 일반 주문 Delivery 생성 진입점 단독 (Admin create endpoint 또는 이벤트 핸들러) | 기각 | markShipping·markDelivered carry-over 지연·Track 20 원 목적(D-102 §9) 이탈·트리거 결정은 β와 동일 파급 |

**α 채택 실증** (recon-report §4-1·§2):
- `grep "Delivery\.create\(|new Delivery\("` → 2건뿐(교환 배송 한정·일반 주문 진입점 0건)
- 프로덕션 영속 Delivery = 교환 배송 전용 → α는 실측 SoT 위에서 wrapper만 얹는 최단 경로
- markShipping은 READY 상태 행 요구·현재 READY로 영속되는 Delivery 부재 → markShipping wrapper는 진입점 신설 선행 의존(Track 21+ 이연 정합)

**carry-over 이월**: markShipping Admin wrapper·일반 주문 Delivery 생성 진입점(트리거·부분 배송 정책 동반)은 §8 후속 트랙 박제 의무 표에 이연 명시.

#### §2 결정 3건 (재정찰 recon-report.md FAIL 2·WARN 1 해소)

| # | 안건 | 채택 | 근거 |
|---|---|---|---|
| Q1 | base path 처리 (신규 `/api/v1/admin/deliveries` vs 기존 클래스 `/api/v1/admin/claims`) | **옵션 A** — 클래스 `@RequestMapping` 제거·메서드 절대경로 2건 | 단일 컨트롤러가 두 리소스 축 노출·"AdminDeliveryController 확장" 확정 스펙 정합·register-exchange URL 결과 불변(D-102 §3 SellerDelivery 대칭 보존) |
| Q2 | `DeliveryNotFoundException` 신규 범위 포함 여부 | **α 포함** | 404 실패 테스트 통과 필수 전제·primitive `markDelivered`는 미존재 시 `IllegalArgumentException`→400 매핑이라 404 불가·`ClaimNotFoundException` 1:1 미러 |
| Q3 | 성공 테스트 시드 flavor (교환 배송 claim_id SET vs 일반 배송 claim_id NULL) | **α 교환 배송** | 프로덕션 충실도(교환이 유일한 실 Delivery 영속 경로)·일반 주문 Delivery 생성 진입점 0건 실측(recon §4-1)·`ExchangeDeliveryCompletedHandler` AFTER_COMMIT 체인 실 구동 |

**기각**:
- **Q1 옵션 B (신규 별도 컨트롤러 분리·기존 무변경)**: 확정 스펙 "AdminDeliveryController 확장"과 상충·단일 Delivery Admin 컨트롤러 응집 저해. 기각.
- **Q3 β (일반 배송 시드·claim_id NULL)**: 최소 시드이나 프로덕션 미도달 경로(일반 주문 Delivery 생성 진입점 0건)·`ExchangeDeliveryCompletedHandler` 체인 미구동. 기각.

#### §3 base path 옵션 A 상세 (Q1)

**채택**: 클래스 레벨 `@RequestMapping("/api/v1/admin/claims")` 제거 → 메서드별 절대경로 2건 부여.

| endpoint | 절대경로 | URL 변화 |
|---|---|---|
| register-exchange (기존) | `POST /api/v1/admin/claims/{claimPublicId}/register-exchange-shipment` | **불변** (클래스 prefix + `/{claimPublicId}/...` = 절대경로 동일) |
| mark-delivered (신규) | `POST /api/v1/admin/deliveries/{deliveryPublicId}/mark-delivered` | 신규 |

**근거**:
- Spring MVC는 메서드 경로가 클래스 prefix에 상대적 → 단일 컨트롤러가 두 base path를 노출하려면 클래스 prefix 제거 + 메서드 절대경로가 유일한 방법.
- register-exchange 결과 URL 불변 → D-102 §3 SellerDelivery 1:1 대칭 보존·기존 통합 테스트(T1~T3) URL 문자열 무변경·회귀 0 실증.

#### §4 markDeliveredByAdmin wrapper (D-102 §5 패턴 2회차)

**구현 형식** (평문 들여쓰기):

    // DeliveryService (registerExchangeShipmentByAdmin 다음·L128 직후)
    @Transactional
    public void markDeliveredByAdmin(Long deliveryId) {
        markDelivered(deliveryId);
    }

- primitive `markDelivered(Long)` 1:1 위임·actor 파라미터 비수신(D-92 원칙).
- 반환형 void: primitive가 void(엔티티 미반환)이므로 wrapper도 void·응답 조립은 Controller 재조회 책임(`AdminClaimController.approveByAdmin` void + `toResponse` 선례 정합). `registerExchangeShipmentByAdmin`이 Delivery 반환하는 것은 primitive가 생성 엔티티를 반환하기 때문이며 mark 계열과 비대칭 정상.
- Controller: `deliveryRepository.findByPublicId(deliveryPublicId)` → id 해소(404 게이트) → wrapper 호출 → **재조회** `findByPublicId(...).map(from)` 응답. OSIV off(`application.yml` open-in-view: false)로 첫 조회 엔티티는 wrapper 트랜잭션 종료 후 stale(SHIPPING)·재조회 필수(`AdminClaimController.toResponse` 패턴 1:1).

#### §5 DeliveryNotFoundException + 404 매핑 (Q2 α)

- **신규 파일**: `delivery/exception/DeliveryNotFoundException.java` (RuntimeException·`ClaimNotFoundException` 1:1 미러)
- **GlobalExceptionHandler**(`common/web/`): `@ExceptionHandler(DeliveryNotFoundException.class)` → 404 `CODE_DELIVERY_NOT_FOUND`(`handleClaimNotFound` 선례 미러)
- **응답 계약**: 미존재 deliveryPublicId → `404 DELIVERY_NOT_FOUND`. 전이 후 재조회 실패는 `IllegalStateException`→500(무결성 위반·`AdminClaimController.toResponse` 선례 정합·client 404와 구분).

#### §6 통합 테스트 T4~T6 (Q3 α·기존 파일 확장)

| # | 시나리오 | 기대 결과 |
|---|---|---|
| T4 | mark-delivered X-Admin-Id 헤더 부재 | 401 UNAUTHENTICATED·DeliveryCompleted 0 |
| T5 | 유효 헤더 + SHIPPING 교환 배송(claim_id SET·shipped_at NOW(6)) | 200·status=DELIVERED·deliveryPublicId 일치·**AFTER_COMMIT 체인** claim.status=COMPLETED·order_item.item_status=EXCHANGED·DeliveryCompleted 1회 |
| T6 | 미존재 deliveryPublicId | 404 DELIVERY_NOT_FOUND·DeliveryCompleted 0 |

- `AdminDeliveryControllerIntegrationTest` 확장(기존 T1~T3 register-exchange 유지·신규 T4~T6 추가).
- 클래스 `@Transactional` 미부착 유지(AFTER_COMMIT 핸들러 실 구동 목적).
- LT-02 try-finally 재사용·`seedApprovedClaim(EXCHANGE)` + 신규 `seedShippingExchangeDelivery`(`ClaimExchangeIntegrationTest.seedShippingDeliveryLinkedToClaim` 패턴 1:1).
- T5 종결 전이는 `ClaimExchangeIntegrationTest.exchangeFullLoop`(기존 green)과 동일 → HTTP 진입으로 재실증.

#### §7 진입점 카드 (메모리 룰 #16 적용 6회차)

| # | 항목 | 내용 |
|---|---|---|
| 1 | **목적** | 배송 완료 primitive의 Admin actor wrapper 신설·mark-delivered endpoint 확립·404 예외 신설·D-102 §9 carry-over(markDelivered) 부분 종결 |
| 2 | **핵심 진입점** | `delivery/service/DeliveryService.java` (wrapper `markDeliveredByAdmin` 신설) · `delivery/controller/AdminDeliveryController.java` (base path 옵션 A 재배치·DeliveryRepository 주입·mark-delivered 메서드) · `delivery/exception/DeliveryNotFoundException.java` (신설) · `common/web/GlobalExceptionHandler.java` (404 매핑) |
| 3 | **핵심 SoT** | `markDeliveredByAdmin(Long)` — primitive `markDelivered` 1:1 위임·void·actor 비수신(D-92) · `DeliveryNotFoundException` — `ClaimNotFoundException` 미러·404 · 옵션 A 메서드 절대경로 2건(클래스 prefix 제거·register-exchange URL 불변) · Controller 재조회(OSIV off·stale 회피) |
| 4 | **영향 범위** | Delivery Aggregate 단독 · 패키지 delivery/service·delivery/controller·delivery/exception·common/web · 신규 파일 1건(DeliveryNotFoundException) + 통합테스트 확장 · 수정 파일 3건(DeliveryService +1메서드·AdminDeliveryController base path 재배치+메서드·GlobalExceptionHandler +1핸들러) · DTO 신설 무(`RegisterExchangeShipmentResponse` 재사용) |
| 5 | **패턴 재사용 SoT** | D-92 (primitive actor 비의존·wrapper 캡슐화) 재사용 6회차 · D-93 (AdminActorResolver seam·X-Admin-Id stub) 재사용 6회차 · D-102 §5 wrapper 패턴 2회차 · `AdminClaimController` approve/reject void + 재조회(`toResponse`) 패턴 1:1 · `ClaimExchangeIntegrationTest.seedShippingDeliveryLinkedToClaim` 시드 1:1 · 1-PR 단독(내부 3-split) 12회차 |
| 6 | **트랩 주의** | LT-01 비해당(신규 Entity 무) · LT-02 통합 테스트 try-finally 의무 · **base path 트랩**: 클래스 prefix 제거 시 register-exchange 메서드도 절대경로 이관 필수(누락 시 URL 붕괴)·기존 T1~T3 URL 문자열 회귀 감지 SoT · **RegisterExchangeShipmentResponse 명칭 부정합**(mark-delivered 응답 재사용·시맨틱 부정합이나 필드 완전 재사용·백로그) · **재조회 필수**(OSIV off·wrapper 후 첫 조회 엔티티 stale) |

#### §8 후속 트랙 박제 의무 (carry-over)

| 항목 | 처치 | 이연 |
|---|---|---|
| markShipping Admin wrapper (READY→SHIPPING 일반 배송 발송 진입점) | D-102 §9·D-104 mark 계열 wrapper 패턴 재사용 | Track 21+ |
| 일반 주문 Delivery 생성 진입점 (claim_id NULL·현재 프로덕션 0건·recon §4-1) | 주문 배송 흐름 트랙 SoT 확인 후 | Track 21+ |
| RegisterExchangeShipmentResponse → 도메인 중립 DeliveryResponse 리네이밍 | mark 계열 endpoint 누적 시 시맨틱 정합화(백로그·recon §4-6) | 후속 소규모 정정 |
| ADJUST 진입점·adjustStock·E10 | D-101 §13 carry-over 유지 | Track 21+ |

#### §9 실측 검증

- 504 tests PASS (기존 501 + 신규 3·110 suites·skipped 0·failures 0·errors 0·회귀 0건)·2026-07-02 로컬 실측(커밋 전 검증)
- 기존 register-exchange 통합 테스트(T1~T3) URL 결과 불변·회귀 0 확인(옵션 A 검증)
- 신규 mark-delivered T4(401)·T5(200 교환 배송 DELIVERED + AFTER_COMMIT 체인 EXCHANGED·COMPLETED)·T6(404) 컨테이너 실측 통과
- 브랜치: `feat/track-20-admin-mark-delivered` (docs·feat·test 3-split·push는 명시 지시 대기)

---

### D-105: Track 21 재고 조정 adjustStock — Admin 재고 조정 endpoint·ProductVariantNotFoundException 신설·E10 미발행(γ)

**상태**: [확정 2026-07-02]

#### §1 트랙 식별자

Track 21 = Inventory 쓰기 진입점 확산 트랙 (A급). D-101 §13 carry-over "ADJUST 진입점·adjustStock·E10"(D-102 §9·D-103·D-104 §8 이월 유지)를 부분 종결한다. Inventory Aggregate에 운영자 수동 조정 도메인 행위 `adjustStock`을 신설하고, InventoryService primitive·`AdminInventoryController` endpoint(`POST /api/v1/admin/inventories/{variantPublicId}/adjust`)·`ProductVariantNotFoundException`(404) 신설로 D-92 wrapper 캡슐화·D-93 AdminActorResolver seam을 재고 도메인으로 확장한다. 정찰 산출물: docs/track-21/recon-report.md (gitignore·판정 집계 PASS 6·WARN 3).

##### §1-A 트랙 범위 확정 근거 (α/β/γ 검토)

D-101 §13 carry-over(adjustStock·E10) 해소 범위 3안 검토:

| # | 범위 안 | 판정 | 근거 |
|---|---|---|---|
| α | `adjustStock` 도메인·Service primitive + `AdminInventoryController` + `ProductVariantNotFoundException` (E10 미발행) | **채택** | 최소 범위·A급 유지·회귀 위험 최저·1-PR 단독·Inventory 도메인 행위 4건(reserve·release·commit·restore) SoT 위에 5번째 행위만 얹음·D-92·D-93·옵션 A 패턴 1:1 재사용·기조 1·4 정합 |
| β | α + E10 `InventoryAdjusted` record 발행 + `NotificationInventoryAdjustedHandler` 신설 | 기각 | E10 알림 recipient 산정 정책 미확립(AdminUser 모델 grep 0건·구매자 1:1 매핑 불가·recon §2)·domain-events.md L171 "알림 연동 불필요하면 미발행 무방한 선택 이벤트" 명시 정합·핸들러 즉시 skip+log 형태는 실효 없는 배선·기조 4 과잉개발 회피 |
| γ | β + `markInbound`/`markOutbound` Admin wrapper 동반 (INBOUND/OUTBOUND 입출고 진입점) | 기각 | INBOUND/OUTBOUND는 ADJUST와 별개 도메인 행위·입출고 트리거·참조 모델(발주·검수) 결정 파급·범위 급증·A→S급 승격 위험·기조 4 위반·Track 22+ 이연(§8) |

**α 채택 실증** (recon §4·§5·§7):
- `InventoryHistoryChangeType.ADJUST`는 V1 DDL enum·enum 상수 공히 기존 존재·미사용(D-101 §13 보존) → 도메인 행위만 얹으면 배선 완결
- `grep publishEvent backend/.../inventory/` → 0건: inventory 패키지는 현재 이벤트 무발행·E10 신설은 신규 배선(TracedEventPublisher 주입·record·handler) 파급 → α는 배선 증분 0
- E10 recipient FAIL(운영자 userId 부재)·WARN(구매자 다중) → 발행해도 소비처(NotificationLog) 실효 없음 → γ(발행 없음)가 domain-events.md L171 정합

**carry-over 이월**: E10 `InventoryAdjusted` 발행·`NotificationInventoryAdjustedHandler`·`markInbound`/`markOutbound` Admin wrapper는 §8 후속 트랙 박제 의무 표에 Track 22+ 이연 명시.

#### §2 결정 5건 (판단 1~5)

| # | 안건 | 채택 | 근거 |
|---|---|---|---|
| Q1 | API 진입 식별자 (variantId Long vs variantPublicId String) | **α variantPublicId** | Admin 선례(claimPublicId·deliveryPublicId) 전건 publicId·`ProductVariantRepository.findByPublicId` 기존 재사용·내부 PK 노출 회피(D-40 §2) |
| Q2 | base path 처리 | **옵션 A** — 클래스 `@RequestMapping` 없음·메서드 절대경로 | `AdminDeliveryController`(D-104 §3) 옵션 A 선례 1:1·`/api/v1/admin/inventories/...` 신규 리소스 축 |
| Q3 | E10 InventoryAdjusted 발행 여부 | **γ 미발행** (정찰 1차 α → 재점검 γ 변경) | recipient 산정 정책 미확립·domain-events.md L171 선택 이벤트 명시·기조 4 과잉개발 회피(§1-A β 기각과 동치) |
| Q4 | E10 알림 recipient 산정 | **γ 자연 소거** | Q3 γ(미발행) 확정으로 recipient 산정 대상 자체가 소거·핸들러·record 전건 미신설 |
| Q5 | 조회 실패 예외 | **α `ProductVariantNotFoundException` 신설** | Q1 α(variantPublicId) 정합·variant 미존재 404·`ClaimNotFoundException` 1:1 미러·GlobalExceptionHandler 404 매핑 |

**기각**:
- **Q1 γ (variantId 직접 수신)**: exception 신설 없이 scope 최소이나 내부 PK 노출·Admin 선례 전건 위배. 기각.
- **Q3 α (E10 발행 채택·정찰 1차안)**: §1-A β 기각 근거와 동일(recipient 실효 없음·선택 이벤트·과잉개발). 재점검 결과 γ로 변경. 기각.

#### §3 판단 1 variantPublicId 진입 경로 상세 (Q1 α)

**경로**:

    variantPublicId → ProductVariantRepository.findByPublicId(publicId) → 미존재 시 ProductVariantNotFoundException(404)
                    → variant.getId() → InventoryService.adjustStock(variantId, ...) → findByVariantIdForUpdate(비관락)

- Admin 진입점 식별자 일관성: `AdminClaimController`(claimPublicId)·`AdminDeliveryController`(deliveryPublicId·claimPublicId) 전건 publicId → variantPublicId 정합.
- 비관락 조회는 기존 `InventoryRepository.findByVariantIdForUpdate(Long)` 재사용(신규 쿼리 없음). ProductVariant 조회는 read-only(`findByPublicId`·락 없음)로 id 해소만 담당.

#### §4 adjustStock 도메인 메서드 + Service primitive (Q3 γ)

**Inventory.adjustStock(int quantityDelta)** (평문 들여쓰기·`restoreStock` 이후·`recalculateAvailable` 직전 배치):

    public void adjustStock(int quantityDelta) {
        if (quantityDelta == 0) { throw new IllegalArgumentException(...); }   // delta=0 무의미 조정 거부
        int projectedOnHand = quantityOnHand + quantityDelta;
        if (projectedOnHand < 0) throw InventoryInvariantViolationException(...);        // INV-4 실물 부족
        int projectedAvailable = projectedOnHand - quantityReserved;
        if (projectedAvailable < 0) throw InventoryInvariantViolationException(...);      // INV-1 가용 부족
        quantityOnHand = projectedOnHand; recalculateAvailable();
    }

- 계산·검증 후 mutate: 위반 시 상태 보존(`reserve` 패턴 정합). 증가(delta>0)는 `restoreStock`과 동일하게 INV-1·INV-4 자연 정합.
- delta=0 → `IllegalArgumentException` → 400(handleMalformed). INV-1·INV-4 위반 → `InventoryInvariantViolationException` → 422.

**InventoryService.adjustStock(Long variantId, int quantityDelta, String reason): Inventory** (기존 5 메서드 다음):
- `findByVariantIdForUpdate`(미존재 시 `InventoryInvariantViolationException` 422·기존 5 메서드 패턴 1:1) → `inventory.adjustStock(delta)` → `InventoryHistory.create(ADJUST, quantityDelta, "admin", null, reason)` save.
- **History**: on_hand 변동이므로 기록(M-11·D-101 §11). `quantity_delta`는 부호 그대로(ORDER의 -qty 변환과 달리 조정은 부호 보존). `referenceType="admin"`(운영자 조정 표기·특정 주문·클레임 참조 부재)·`referenceId=null`(inventory_history.reference_id NULL 허용).
- **반환 Inventory**: 응답 조립(after 수치)을 위해 조정된 Inventory 반환. `registerExchangeShipmentByAdmin`(엔티티 반환) 패턴 정합. OSIV off에서도 스칼라 필드(on_hand·reserved·available)만 읽으므로 Controller 재조회 불요(`AdminClaimController.toResponse` void+재조회 패턴과 비대칭 정상·recon §7).

#### §5 ProductVariantNotFoundException + 404 매핑 (Q5 α)

- **신규 파일**: `product/exception/ProductVariantNotFoundException.java` (RuntimeException·`ClaimNotFoundException` 1:1 미러·product/exception/ 디렉토리 신설).
- **GlobalExceptionHandler**(`common/web/`): `@ExceptionHandler(ProductVariantNotFoundException.class)` → 404 `CODE_PRODUCT_VARIANT_NOT_FOUND`(`handleDeliveryNotFound` 선례 미러·상수 신설).
- **응답 계약**: 미존재 variantPublicId → `404 PRODUCT_VARIANT_NOT_FOUND`. Inventory 미존재(variant는 존재)는 기존 `InventoryInvariantViolationException` 422 재사용(별도 InventoryNotFoundException 미신설·기존 5 메서드 일관성).

#### §6 통합 테스트 T1~T4 (신규 파일)

| # | 시나리오 | 기대 결과 |
|---|---|---|
| T1 | X-Admin-Id 헤더 부재 | 401 UNAUTHENTICATED·inventory 도메인 이벤트 0 |
| T2 | 유효 헤더 + 유효 차감(delta=-3·on_hand 10→7·reserved 2·available 8→5) | 200·InventoryAdjustResponse 정합·DB on_hand 7·available 5·InventoryHistory ADJUST 1행(delta -3·reason·reference_type 'admin')·이벤트 0 |
| T3 | 미존재 variantPublicId | 404 PRODUCT_VARIANT_NOT_FOUND·이벤트 0 |
| T4 | 음수 차감(delta=-11)·on_hand 부족 | 422 INVENTORY_INVARIANT_VIOLATION·on_hand·available 롤백 불변·InventoryHistory 0행·이벤트 0 |

- **신규 파일** `inventory/controller/AdminInventoryControllerIntegrationTest`(`@SpringBootTest`+`@AutoConfigureMockMvc`+`@RecordApplicationEvents`·Testcontainers static MariaDB·`AdminDeliveryControllerIntegrationTest` 패턴 1:1).
- 클래스 `@Transactional` 미부착(adjustStock 커밋을 JdbcTemplate 직접 조회 검증).
- LT-02 try-finally 재사용·FK 시드 그래프 확장: user→seller→product→product_variant→**inventory**(D-91).
- **판단 3 γ 회귀 잠금**: `@RecordApplicationEvents`로 inventory 패키지 도메인 이벤트 0건 단언(E10 미발행·향후 오배선 감지 SoT).

#### §7 진입점 카드 (메모리 룰 #16 적용 7회차)

| # | 항목 | 내용 |
|---|---|---|
| 1 | **목적** | Inventory 운영자 수동 조정 도메인 행위·Admin 재고 조정 endpoint 신설·variant 404 예외 신설·D-101 §13 carry-over(adjustStock) 부분 종결 |
| 2 | **핵심 진입점** | `inventory/entity/Inventory.java`(`adjustStock` 도메인 신설) · `inventory/service/InventoryService.java`(`adjustStock` primitive 신설·Inventory 반환) · `inventory/controller/AdminInventoryController.java`(신설·옵션 A) · `inventory/controller/request/AdminInventoryAdjustRequest.java`·`inventory/controller/response/InventoryAdjustResponse.java`(신설) · `product/exception/ProductVariantNotFoundException.java`(신설) · `common/web/GlobalExceptionHandler.java`(404 매핑) |
| 3 | **핵심 SoT** | `Inventory.adjustStock(int)` — delta=0 거부·INV-4·INV-1 가드·계산후 mutate(상태 보존) · `InventoryService.adjustStock(Long,int,String):Inventory` — findByVariantIdForUpdate·History ADJUST(부호 보존·reference_type "admin"·reference_id null) · `ProductVariantNotFoundException` — 404·`ClaimNotFoundException` 미러 · 옵션 A 메서드 절대경로 · Inventory 반환→Controller 재조회 불요 |
| 4 | **영향 범위** | Inventory Aggregate 단독 · 패키지 inventory/entity·service·controller(+request·response)·product/exception·common/web · 신규 파일 5건(Controller·Request·Response·Exception·통합테스트) + 수정 3건(Inventory +1도메인·InventoryService +1메서드·GlobalExceptionHandler +1핸들러+1상수+1import) · Flyway 무(ADJUST enum·inventory_history 기존)·E10 이벤트·핸들러 무(γ) |
| 5 | **패턴 재사용 SoT** | D-92(primitive actor 비의존)·D-93(AdminActorResolver seam·X-Admin-Id stub) 재사용 7회차 · 옵션 A(메서드 절대경로) 2회차 · `ClaimNotFoundException`→404 미러 · `registerExchangeShipmentByAdmin` 엔티티 반환(재조회 회피) 패턴 · InventoryService findByVariantIdForUpdate 5→6 메서드 · LT-02 try-finally · 1-PR 단독(내부 3-split) 13회차 |
| 6 | **트랩 주의** | LT-01 비해당(신규 Entity 무) · LT-02 통합 테스트 try-finally 의무 · **referenceType 비-null 트랩**: inventory_history.reference_type NOT NULL → "admin" 리터럴 공급 필수(누락 시 IllegalArgumentException) · **delta=0 400 vs INV 위반 422 구분**: Bean Validation은 delta=0 미차단(표준 @NotZero 부재·커스텀 금지)·도메인에서 400 처리 · **γ 미발행 회귀**: @RecordApplicationEvents inventory 이벤트 0 단언으로 향후 E10 오배선 감지 |

#### §8 후속 트랙 박제 의무 (carry-over)

| 항목 | 처치 | 이연 |
|---|---|---|
| E10 `InventoryAdjusted` record 발행 + `NotificationInventoryAdjustedHandler` | recipient 산정 정책(AdminUser 모델 or 구매자 브로드캐스트) 확립 후·domain-events.md L171 선택 이벤트 | Track 22+ |
| `markInbound`/`markOutbound` Admin wrapper (INBOUND/OUTBOUND 입출고 진입점) | 입출고 트리거·참조 모델(발주·검수) 설계 트랙 SoT 확인 후 | Track 22+ |
| `InventoryService` TracedEventPublisher 주입 | E10 발행 채택(위 항목) 시 동반(현재 발행 무·미주입) | Track 22+ |

#### §9 실측 검증

- 508 tests PASS (기존 504 + 신규 4·failures 0·errors 0·skipped 0·회귀 0건)·2026-07-02 로컬 실측(커밋 전 검증·`./gradlew test`)
- 빌드툴 실측: 본 프로젝트는 Gradle(`backend/gradlew.bat`)·전 트랙 전건 `./gradlew test` 사용(Maven·pom.xml 부재)
- 신규 T1(401)·T2(200 유효 차감·on_hand 7·available 5·InventoryHistory ADJUST 1행)·T3(404)·T4(422 INV-4 롤백) 컨테이너 실측 통과
- production compileJava·test compileTestJava BUILD SUCCESSFUL
- 브랜치: `feat/track-21-adjust-stock` (docs·feat·test 3-split·push는 명시 지시 대기)

---

### D-106: Track 22 AdminRefundController initiateByAdmin wrapper — 운영자 수동 환불 개시 endpoint·옵션 A·범위 α(생성 단독·보정 이연)

**상태**: [확정 2026-07-02]

#### §1 트랙 식별자·목적

Track 22 = Refund Admin 진입점 확산 트랙 (A급). 환불 개시 primitive `RefundService.initiate`(L85)에 운영자 actor wrapper `initiateByAdmin`를 신설하고, `AdminRefundController`(옵션 A) + `POST /api/v1/admin/claims/{claimPublicId}/initiate-refund` endpoint를 확립해 D-92 wrapper 캡슐화·D-93 AdminActorResolver seam을 Refund 도메인으로 확장한다. 자동 트리거(`ClaimApprovedHandler`·`ClaimPickedUpHandler`)가 PG 장애·도메인 위반으로 실패해 Claim이 환불 없이 APPROVED로 잔존할 때(D-94 Q8·recon §1-1), 운영자가 동일 Claim에 대해 환불을 수동 재개시하는 fallback 경로를 확립한다. 정찰 산출물: docs/track-22/recon-report.md.

근거 계승: D-92(primitive actor 비의존·wrapper 캡슐화)·D-93(AdminActorResolver·X-Admin-Id stub)·D-94 Q8(운영자/Job 재 initiate 허용)·D-104·D-105(옵션 A·A급 최소 범위·1-PR 3-split).

##### §1-A 트랙 범위 확정 근거 (α/β/γ 검토)

D-94 Q3이 Track 11에서 이연한 "Admin 수동 환불 생성 endpoint"(L3660) 해소 범위 3안 검토:

| # | 범위 안 | 판정 | 근거 |
|---|---|---|---|
| α | `AdminRefundController` + `initiateByAdmin` wrapper 단독 (생성 endpoint) | **채택** | 최소 범위·A급 유지·회귀 위험 최저·1-PR 단독·`RefundService.initiate` SoT(CLM-3·PAY-1 사전·멱등 게이트·D-67 FAILED 전이 기구현) 위에 wrapper만 얹음·D-92·D-93·옵션 A 1:1 재사용·기조 1·4 정합·D-94 Q8 재 initiate 명문 허용 |
| β | α + Payment wrapper(`markCancelledByAdmin` 등) 동시 신설 | 기각 | Admin이 Payment.status를 수동 보정하는 시나리오 실측 근거 부재(recon §1·§7 Payment wrapper 대상 실측 없음)·Refund.COMPLETED 후 Payment CANCELLED는 `PaymentRefundCompletedHandler` 자동 전이로 이미 배선(recon §6-2)·수동 Payment 개입은 별도 도메인 결정 파급·기조 4 위반 |
| γ | β + `PaymentNotFoundException` 동반 신설 | 기각 | β 종속(Payment wrapper 없으면 신규 예외 소비처 부재)·404 경로 실체 없는 dead exception·기조 4 위반 |

**α 채택 실증** (실측·recon §1-1·§1-5):
- `RefundService.initiate(Long, long)`(L85)는 자동 트리거 2개 핸들러가 이미 호출하는 완성 primitive → wrapper만 얹으면 Admin 진입 완결(배선 증분 0)
- 자동 트리거 실패 시 Claim은 APPROVED로 잔존(CLM-3·상태 환원 없음·recon §1-1) → 동일 Claim 재-initiate가 유일한 운영 복구 경로(D-94 Q8)
- 멱등 게이트(initiate L95-101)가 중복 개시를 자연 차단(활성 PENDING/COMPLETED 존재 시 기존 행 no-op 반환) → Admin 재시도 안전

**D-94 Q3 조정 (생성/보정 분리·해석 1)**:
D-94 Q3(L3658-3665)은 "Admin 수동 환불 생성 endpoint는 본 트랙에 병존시키지 않는다"며 생성(initiate)과 보정(markFailed·RefundAdjustment)을 한 덩어리로 Track 11 밖으로 이연했다. "본 트랙에"(L3660)는 Track 11 **범위** 한정이며 영구 금지가 아니다. Track 22가 그 이연 대상 후속 트랙이며, Q3이 묶어둔 둘을 분리해 **생성(initiate)만** 채택한다:
- **생성 채택**: D-94 Q8(L3704) "재시도 허용(운영자/Job 재 initiate·RFN-2)"·D-92 Q8(L3456) "Refund 생성(Admin/Job)" 운영 절차 명문 → 신규 PENDING 행 생성은 허용 행위.
- **보정 이연**: state-machine §8 L274 "운영자 수동 보정 권한 없음"은 기존 Refund 행의 status 강제 전이(markFailed·markCompleted)에만 유효 → `markFailedByAdmin`은 §8 carry-over로 이연(판단 3).
- Q3의 이연 근거(L274·RefundAdjustment 종속)는 **보정에 유효·생성에는 과잉 적용**이었음을 본 조정으로 명문화한다.

**carry-over 이월**: `markFailedByAdmin`·Payment wrapper·`PaymentNotFoundException`은 §8 후속 트랙 박제 의무 표에 Track 23+ 이연 명시.

#### §2 결정 5건 (판단 1~5)

| # | 안건 | 채택 | 근거 |
|---|---|---|---|
| 판단 1 | 트랙 범위 (α/β/γ) | **α 생성 단독** | §1-A·D-94 Q8·기조 4·A급 최소 범위 |
| 판단 2 | Refund primitive wrapper 대상 (initiateByAdmin / markFailedByAdmin / 둘 다) | **A `initiateByAdmin` 단독** | ① D-94 Q3(L3660 "본 트랙에" 범위 한정·생성/보정 분리) ② D-94 Q8(L3704 재 initiate 허용) ③ D-92 Q8(L3456 Refund 생성 Admin/Job 운영 절차) ④ state-machine §8 L274(보정 금지 → markFailedByAdmin 이연) |
| 판단 3 | `markFailedByAdmin` 동반 여부 | **이연 (Track 23+)** | state-machine §8 L274 "운영자 수동 보정 권한 없음" 정면 충돌·RefundAdjustment(D안) 트랙 종속·`markFailed`는 기존 행 status 강제 전이(보정)로 생성과 도메인 성격 상이 |
| 판단 4 | Payment wrapper(`markCancelledByAdmin`) 동반 여부 | **이연 (Track 23+)** | Admin Payment 수동 보정 시나리오 실측 근거 부재·Refund.COMPLETED→Payment CANCELLED 자동 배선 기존재(`PaymentRefundCompletedHandler`)·β 기각과 동치 |
| 판단 5 | `PaymentNotFoundException` 동반 여부 | **이연 (Track 23+)** | 판단 4 종속·소비처 없는 dead exception·γ 기각과 동치 |

**판단 2 근거 상세 (D-94 내부 정합·해석 1)**: D-94 Q3은 생성(initiate)과 보정(markFailed)을 한 덩어리로 이연하며 근거로 state-machine L274·RefundAdjustment를 들었으나, 그 근거는 **보정에만 유효**하다. 생성(신규 PENDING 행)은 동일 결정 D-94 Q8이 "운영자/Job 재 initiate 허용"으로 명문 허용하고 D-92 Q8이 "Refund 생성(Admin/Job)"을 운영 절차로 박제한 행위다. Track 22는 Q3의 "본 트랙에"(Track 11 범위 한정) 이연을 후속 트랙에서 종결하되, Q3이 과잉 결합한 생성/보정을 분리해 생성만 채택하고 보정은 L274 준수로 판단 3 이연한다. D-104(markDelivered·D-102 §9)·D-105(adjustStock·D-101 §13)가 각자 트랙에서 carry-over를 종결한 선례와 1:1.

**기각**:
- **판단 2 "둘 다"(initiateByAdmin + markFailedByAdmin)**: markFailedByAdmin은 state-machine L274 정면 충돌·범위 확대·A급 이탈. 기각.
- **판단 1 β·γ**: §1-A 표 근거. 기각.

#### §3 base path 옵션 A 상세 (실측 6)

**실측 6 결과**: `AdminClaimController`(claim/controller/AdminClaimController.java L30-31)는 클래스 레벨 `@RequestMapping("/api/v1/admin/claims")` **보유** → 옵션 A 아님(별도 패턴). 옵션 A 선례는 `AdminInventoryController`·`AdminDeliveryController`(recon §2·클래스 `@RequestMapping` 없음·메서드 절대경로).

**채택**: 옵션 A(`AdminInventoryController`·`AdminDeliveryController` 1:1) — 클래스 `@RestController` 단독·메서드 절대경로.

    @RestController
    public class AdminRefundController {
        @PostMapping("/api/v1/admin/claims/{claimPublicId}/initiate-refund")
        ...
    }

| endpoint | 절대경로 |
|---|---|
| initiate-refund (신규) | `POST /api/v1/admin/claims/{claimPublicId}/initiate-refund` |

**라우팅 충돌 검토**: `AdminClaimController`가 `.../{claimPublicId}/approve`·`/reject`(L51·L62)를 소유하나 신설 `.../initiate-refund`는 full path 상이 → Spring handler mapping 충돌 없음(서로 다른 구체 경로·서로 다른 컨트롤러 정상). claimPublicId 리소스 축을 공유하되 Refund 개시라는 별도 행위 축이므로 별도 컨트롤러 응집 정합(Refund는 Claim 종속 Aggregate·aggregate-boundary §2.5).

#### §4 initiateByAdmin wrapper (D-92 wrapper 패턴·D-104 §4 3회차)

**구현 형식** (평문 들여쓰기·`initiate`(L85) 하단 인접):

    // RefundService (initiate 다음)
    public Refund initiateByAdmin(Long claimId, long amount) {
        return initiate(claimId, amount);
    }

- primitive `initiate(Long, long)` 1:1 위임·actor 파라미터 비수신(D-92 원칙·`initiate`가 이미 actor 비의존 시그니처).
- **`@Transactional` 미부여**: 실측 결과 `RefundService` 클래스 레벨 `@Transactional`(L43) 보유·`initiate`(L85) 메서드 어노테이션 없음 → wrapper도 클래스 레벨 프록시가 커버(별도 부여 불요·중복 회피).
- 반환형 `Refund`: primitive가 엔티티 반환 → wrapper도 반환·Controller가 DTO 조립(`registerExchangeShipmentByAdmin`·`InventoryService.adjustStock` 엔티티 반환 패턴 정합·`AdminClaimController.toResponse` void+재조회와 비대칭 정상).
- **Controller 재조회 불요**: 반환 `Refund`의 스칼라 필드(publicId·status·amount·pgRefundId)만 응답에 읽으므로 OSIV off에서도 재조회 불필요(D-105 §4 Inventory 반환 패턴 정합).

**Javadoc 필수 기재** (D-94 Q8 원문 인용·admin 진입·자동 트리거 실패 fallback):
- "운영자/Job 재 initiate 허용"(D-94 Q8) 원문 인용
- 자동 트리거(`ClaimApprovedHandler`·`ClaimPickedUpHandler`) PG 장애·도메인 위반 실패 시 fallback 시나리오
- 멱등 게이트로 중복 개시 no-op 반환(재시도 안전)

#### §5 DTO 확정 (실측 1·실측 5)

**AdminRefundInitiateRequest** (refund/controller/request/):

    public record AdminRefundInitiateRequest(long amount) {
    }

- `long amount` 단일 필드·**Bean Validation 없음**. 근거: `RefundService.initiate` L89-91 `if (amount < 1) throw new IllegalArgumentException`·GlobalExceptionHandler L79-83 `IllegalArgumentException` → 400 MALFORMED_REQUEST 기존 매핑 → amount ≤ 0은 도메인 검증이 400 처리(`AdminInventoryAdjustRequest.quantityDelta` 무검증 1:1·recon §3-1).

**AdminRefundInitiateResponse** (refund/controller/response/·실측 1 기반 5필드):

    public record AdminRefundInitiateResponse(
            String refundPublicId,
            String claimPublicId,
            RefundStatus status,
            Long amount,
            String pgRefundId) {
        public static AdminRefundInitiateResponse from(String claimPublicId, Refund refund) {
            return new AdminRefundInitiateResponse(
                    refund.getPublicId(), claimPublicId, refund.getStatus(),
                    refund.getAmount(), refund.getPgRefundId());
        }
    }

- 필드 매핑 실측 근거(Refund.java): `refundPublicId=getPublicId()`(상속 AbstractPublicIdFullAuditableEntity L39·String)·`status=getStatus()`(L61·RefundStatus raw enum·ClaimResponse L19 정합)·`amount=getAmount()`(L57·Long boxed)·`pgRefundId=getPgRefundId()`(L64·nullable·length 100·PG 예외 시 null)·`claimPublicId`는 from() 인자(Controller path variable).
- `from(String claimPublicId, Refund refund)` 팩토리: `InventoryAdjustResponse.from(String, Inventory)` 시그니처 패턴 1:1(recon §3-2).

#### §6 통합 테스트 T1~T4 (신규 파일)

| # | 시나리오 | 기대 결과 |
|---|---|---|
| T1 | X-Admin-Id 헤더 부재 | 401 UNAUTHENTICATED·이벤트 0·시드 불요 |
| T2 | 유효 헤더 + APPROVED Claim + PAID Payment + 유효 amount | 200·status=PENDING·refundPublicId(rfn_)·pgRefundId NOT NULL·DB Refund 1행 커밋·이벤트 0 |
| T3 | 미존재 claimPublicId | 404 CLAIM_NOT_FOUND·이벤트 0 |
| T4 | APPROVED 아닌 Claim (REQUESTED) | 422 CLAIM_STATE_INVALID·Refund 0행 롤백·이벤트 0 |

- **신규 파일** `refund/controller/AdminRefundControllerIntegrationTest`(`@SpringBootTest`+`@AutoConfigureMockMvc`+`@RecordApplicationEvents`·Testcontainers static MariaDB(mariadb:11.4)·`AdminInventoryControllerIntegrationTest` 패턴 1:1·recon §4 SoT).
- 클래스 `@Transactional` 미부착(initiate 커밋을 JdbcTemplate 직접 조회 검증·MockMvc HTTP 경유 의무·primitive 직접 호출 금지).
- LT-02 try-finally SET FOREIGN_KEY_CHECKS 0/1 재사용·FK 부모 그래프: user→seller→product→product_variant→order→order_item→claim(APPROVED)→payment(PAID).
- **이벤트 0 단언**: initiate 성공 경로는 PENDING 반환·`RefundCompleted`는 markCompleted에서만 누적(Refund.java L137)·initiate 경로 이벤트 발행 없음(recon §6-3) → `@RecordApplicationEvents` 0건 단언.
- T2 성공 전제: initiate가 claim→order_item→order→PAID payment 해소(RefundService L113 `resolvePaidPayment`) → PAID Payment 시드 필수. T4는 status 게이트(L107)가 payment 해소(L113) 이전이라 payment 불요·claim(REQUESTED)만으로 422.

#### §7 트랩 주의 (LT-02·LT-05·D-67·RefundIdempotentNoOpException)

- **LT-02**: 통합 테스트 seed/cleanup try-finally SET FOREIGN_KEY_CHECKS 0/1 의무(recon §4-1).
- **LT-05 비해당**: initiate 경로는 `RefundCompleted` 미발행(markCompleted 전용·Refund.java L137) → AFTER_COMMIT 형제 핸들러(`ClaimRefundCompletedHandler`·`PaymentRefundCompletedHandler`) 순서 종속 없음(recon §6-3). initiateByAdmin은 형제 핸들러 신설 아님.
- **D-67 (PG 예외 → FAILED 전이)**: initiate 내부 `gateway.refund()` 예외 시 PENDING 행 FAILED 전이(RefundService L130-134)·예외 미전파. Admin 진입도 동일 경로 → PG 장애 시 200 + status=FAILED 반환 가능(T2는 Mock PG 정상 → PENDING·pgRefundId 부여). D-67 정상 동작(failure_reason 컬럼 없음).
- **RefundIdempotentNoOpException 발생 경로 없음**: `initiate`는 멱등 시 기존 행을 조용히 반환(L95-101)·`RefundIdempotentNoOpException` throw 없음(recon §5-3). GlobalExceptionHandler 미등록 상태이나 initiate 경로에서 발생하지 않으므로 500 fallback 트랩 무(recon §7).
- **claimId null 경로 비도달**: initiate L87-88 `claimId == null` → IllegalArgumentException이나 Controller가 `claim.getId()`(non-null·실측 3) 전달 → Admin 경로 비도달.

#### §8 후속 트랙 박제 의무 (carry-over·"carry-over 무" 아님)

| 항목 | 처치 | 이연 |
|---|---|---|
| `markFailedByAdmin` (기존 Refund 행 status 수동 보정) | state-machine §8 L274 "운영자 수동 보정 권한 없음" 준수·RefundAdjustment(D안) 트랙 종속·D-24 후속 "수동 보정 정책(D안)" | Track 23+ |
| Payment wrapper (`markCancelledByAdmin` 등 Admin Payment 보정) | Admin Payment 수동 개입 시나리오 실측·정책 확립 후·현재 Refund.COMPLETED→Payment CANCELLED 자동 배선 기존재 | Track 23+ |
| `PaymentNotFoundException` + 404 매핑 | Payment wrapper(위 항목) 채택 시 동반·현재 소비처 부재 | Track 23+ |
| E10 `InventoryAdjusted` 발행·`NotificationInventoryAdjustedHandler` (D-105 §8 이월) | Track 22가 Refund 트랙으로 Inventory 미해소 → 재이연 | Track 23+ |
| `markInbound`/`markOutbound` Admin wrapper (D-105 §8 이월) | 입출고 트리거·참조 모델 설계 후·Track 22 미해소 재이연 | Track 23+ |

본 트랙은 `markFailedByAdmin`·Payment 계열을 명시 이연하며, D-105 §8 Inventory 계열(E10·markInbound/markOutbound)은 Track 22가 Refund 트랙이므로 미해소·Track 23+ 재이연한다.

#### §9 실측 검증

- 512 tests PASS (baseline 508 + 신규 4·112 suites·failures 0·errors 0·skipped 0·회귀 0건)·2026-07-02 로컬 실측(`./gradlew.bat cleanTest test`·2m 56s)
- 빌드툴: Gradle(`backend/gradlew.bat`)·Maven·pom.xml 부재(D-105 §9 정합)
- 신규 T1(401 UNAUTHENTICATED)·T2(200 PENDING+pgRefundId·Refund 1행 커밋)·T3(404 CLAIM_NOT_FOUND)·T4(422 CLAIM_STATE_INVALID·Refund 0행 롤백) 컨테이너 실측·refund 도메인 이벤트 0 단언
- production compileJava·test compileTestJava BUILD SUCCESSFUL
- 브랜치: `feat/track-22-refund-admin-initiate` (docs·feat·test 3-split·push는 명시 지시 대기)

#### §진입점 카드 (메모리 룰 #16 적용 8회차)

| # | 항목 | 내용 |
|---|---|---|
| 1 | **목적** | 환불 개시 primitive의 운영자 actor wrapper 신설·initiate-refund endpoint 확립·자동 트리거 실패 fallback 경로·D-94 Q3 이연(생성 endpoint) 종결(보정 분리 이연) |
| 2 | **핵심 진입점** | `refund/service/RefundService.java`(wrapper `initiateByAdmin` 신설·L85 하단) · `refund/controller/AdminRefundController.java`(신설·옵션 A) · `refund/controller/request/AdminRefundInitiateRequest.java`·`refund/controller/response/AdminRefundInitiateResponse.java`(신설) |
| 3 | **핵심 SoT** | `initiateByAdmin(Long, long):Refund` — primitive `initiate` 1:1 위임·actor 비수신(D-92)·`@Transactional` 미부여(클래스 L43 커버) · 옵션 A 메서드 절대경로 · Response 5필드(실측 1·status raw enum·amount Long·pgRefundId nullable) · Request `long amount` 무검증(실측 5·400 도메인 처리) |
| 4 | **영향 범위** | Refund Aggregate 단독 · 패키지 refund/service·refund/controller(+request·response) · 신규 파일 4건(Controller·Request·Response·통합테스트) + 수정 1건(RefundService +1 wrapper) · **GlobalExceptionHandler 무변경**(ClaimNotFound 404·ClaimInvalidState 422·IllegalArgument 400 전건 기존 매핑 재사용·실측 4·5) · 신규 예외 무 · DTO 신설 2 · Flyway 무·이벤트 무 |
| 5 | **패턴 재사용 SoT** | D-92(primitive actor 비의존)·D-93(AdminActorResolver seam·X-Admin-Id stub) 재사용 8회차 · 옵션 A(메서드 절대경로) 3회차 · D-104 §4 wrapper 위임 패턴 3회차 · `ClaimNotFoundException`→404·`ClaimInvalidStateException`→422 기존 매핑 재사용 · `InventoryAdjustResponse.from(String, Entity)` DTO 팩토리 패턴 · LT-02 try-finally · 1-PR 단독(내부 3-split) 14회차 |
| 6 | **트랩 주의** | LT-01 비해당(신규 Entity 무) · LT-02 통합 테스트 try-finally 의무 · LT-05 비해당(initiate 경로 RefundCompleted 미발행·형제 핸들러 순서 무관) · **base path 트랩**: AdminClaimController는 클래스 `@RequestMapping` 보유(옵션 A 아님)·AdminRefundController는 옵션 A 선례(Inventory·Delivery) 준수·claimPublicId 축 공유하되 full path 상이로 라우팅 충돌 무 · **RefundIdempotentNoOpException 500 트랩 무**: initiate는 멱등 시 조용히 기존 행 반환·본 예외 throw 없음(경로 비도달) · **D-67**: PG 예외 시 status=FAILED 반환 정상 |

---

### D-107: 일반 주문 Delivery 생성 진입점 + PAID→PREPARING 전이

**상태**: [확정 2026-07-02]

#### §1 결정
일반 주문 배송 시작 진입점 신설. 판매자가 PAID OrderItem을 출고 준비(PREPARING)→배송 시작(SHIPPING)까지 단일 façade로 처리한다. carry-over 3건 통합 해소: D-102 §9 L6002(AdminDeliveryController 확장 중 일반 주문 배송분)·D-104 §8 L6257(markShipping wrapper)·L6258(일반 주문 Delivery 생성 진입점). 구현 SoT: `OrderShippingService.prepareShipment`(order/service·L54)·`DeliveryService.createForOrder`(delivery/service·L128~131)·`SellerShippingController`(order/controller·`POST /api/v1/order-items/{orderItemPublicId}/prepare-shipment` L48).

##### §1-A 트랙 범위 α/β/γ 검토 (결정 근거 영구화)
- **트리거: β 판매자 수동 채택.** α 자동(PaymentCompleted 핸들러 즉시 생성) 기각 = state-machine §3 L82 "PREPARING=판매자 출고 준비 시작" SoT 충돌·멀티벤더 판매자별 출고 타이밍 무시. γ Admin 기각 = 운영 주체 부적합·출고는 판매자 책임 영역. (외부 검토 2회·재검토 3건 수렴.)
- **진입점: façade 1 endpoint 채택.** 3-endpoint 분리 기각 = READY 독립 관측 요구 SoT 부재(state-machine §6.1 L186 "READY=생성 완료·출고 미개시"는 상태 의미 정의일 뿐 외부 독립 조회 요구 아님)·`registerExchangeShipment`(DeliveryService L93~115) 선례도 READY 관측 지점 없이 즉시 SHIPPING 커밋. 내부 4단계 분리·외부 API 1개.
- **부분배송: 1:1 운영 제한 채택**(OrderItem당 Delivery 1건). 1:N 구조 유지(order_item_id UNIQUE 미추가). invariants DLV-2(L126) 원문 무변경 = 1:1 ⊂ 1:N·invariant 위반 아님·원문 수정 시 미래 확장 의도 훼손·SoT 재개정 비용. 분할 배송 후속 트랙 이연.
- **PREPARING 전이 성격: 미구현 누락**(의도적 이연 아님·recon §H-3). 규칙(D-02 값·D-03 값·D-04 동기화 규칙 §5 [2]·D-06 내부전이 분류) 존재·전이 진입점 결정 D-XX 0건·상위 배송 흐름 이연에 암묵 귀속분을 본 트랙에서 해소.

#### §2 결정 라운드 Q (논점 1~4·외부 검토 수렴)
- 논점 1 트리거 β·논점 2 façade·논점 3 1:1·논점 4 예외 422. 외부 검토 1차 회신 → 재검토 3건(진입점 façade vs 분리 상충·DLV-2 정정 위치·예외 계층 개수) → 재검토 회신 전건 실측 수렴.
- 예외 1개(`DeliveryInvalidStateException`) 신설·OrderItem 예외 미신설(endpoint 직접 changeStatus 없음·Service 경유)·`OrderNotFoundException` 재사용(소유자 불일치 통일·Javadoc L3~7 "주문을 찾을 수 없거나 본인 주문이 아닐 때·존재 여부 노출 회피").
- **배선 리스크**: 같은 트랜잭션 내 PAID→PREPARING→[E4 동기소비]→SHIPPING 2회 전이 성립 여부 = 사전 실측·구현 1단계 배선 검증으로 확정(§4).

#### §3 endpoint (구현 실측)
- `POST /api/v1/order-items/{orderItemPublicId}/prepare-shipment`(SellerShippingController L48). base path 신규·`/api/v1/claims` 재사용 금지(EXCHANGE 의미 오염 회피). OrderItem public_id(oit_) 전역 유일·`orderItemRepository.findByPublicId`(controller L54) 해소·orderPublicId 불요.
- 배치: `order/controller`(액터 prefix 부재 관례·리소스 기반 경로·BuyerClaim/SellerClaim/SellerDelivery 모두 `/api/v1/claims` 공유·Admin만 `/admin/`). PREPARING 주체=Order Aggregate·delivery/controller는 EXCHANGE+Admin 전용·혼재 회피. X-Seller-Id → `SellerActorResolver.resolve`(controller L53).

#### §4 façade 내부 4단계·트랜잭션 (구현 실측·핵심)
- `OrderShippingService.prepareShipment(sellerId, orderItemId, carrier, trackingNo)`(L54) 클래스 `@Transactional`(L30) 단일 트랜잭션(가드 A). 순서: `authorize`(L55·sellerId 대조·불일치 `OrderNotFoundException` 404·존재 은닉·L71~78) → `changeToPreparing`(L58·`orderItemRepository.findById` + `OrderItem.changeStatus(PREPARING)`·actor 비의존·D-92·L90~96) → `deliveryService.createForOrder(orderItemId, carrier)`(L60·Delivery.create+save·claim 비의존·READY·claim_id NULL·DeliveryService L128~131) → `deliveryService.markShipping`(L61·기존 primitive 재사용·E4 발화).
- **가드 A**: façade `@Transactional` 필수(4단계 원자성·하위 DeliveryService REQUIRED join). **가드 B**: `changeToPreparing`이 `markShipping` 前 필수 — 역전 시 OrderItem PAID인 채 E4 발화 → `DeliveryStartedHandler` 가드 warn+return(조용한 skip) → Delivery SHIPPING·OrderItem PAID 불일치·façade 성공 오인. T2 역행 회귀 테스트 락인.
- **2회 전이 성립 근거**: `DeliveryStartedHandler` `@EventListener`(평문·동기·같은 트랜잭션·AFTER_COMMIT 아님)·`TracedEventPublisher` 동기 위임·handler `findById`가 같은 영속성 컨텍스트 1차 캐시로 방금 PREPARING된 관리 인스턴스 반환 → `canTransitionTo(SHIPPING)`=true. 재설계 불요.
- **recalc**: `changeToPreparing` recalc 생략(`DeliveryStartedHandler` L57이 최종 Order.status=SHIPPING 확정·중복 회피·기조 4).
- **프로덕션 첫 가동**: 기존 유일 Delivery 생성 경로 EXCHANGE는 OrderItem이 EXCHANGE_REQUESTED라 `DeliveryStartedHandler` SHIPPING 전이 skip → DeliveryStartedHandler SHIPPING 성공 경로는 Track 23이 첫 프로덕션 가동(이전엔 테스트 시드만).

#### §5 예외 (구현 실측)
- `DeliveryInvalidStateException` 신규(delivery/exception·RuntimeException·L16·422·`ClaimInvalidStateException` 미러). `GlobalExceptionHandler` `CODE_DELIVERY_INVALID_STATE` 상수 + `handleDeliveryInvalidState`(L205~211·422·InventoryInvariantViolation 핸들러 다음).
- **흡수 지점 = `changeToPreparing` 국한**(OrderShippingService L90~96·비-PAID OrderItem 중복 출고 등 `IllegalStateException`→`DeliveryInvalidStateException`). `markShipping`은 방금 생성 READY만 대상 → READY→SHIPPING 항상 합법·전이 위반 미도달·trackingNo `@NotBlank` 보장 → 선제 매핑/테스트 회피(기조 4·미도달 예외 선제 테스트 금지). handler 내부 IllegalState 오분류 회피.

#### §6 부분배송 정책 박제 (invariants 무변경 흡수)
- DLV-2(invariants L126) "부분 배송 지원·OrderItem 1:N Delivery" 구조 허용 유지. **Track 23 운영 정책 = OrderItem당 Delivery 생성 1건 제한**(DeliveryService.createForOrder L128~131·요청당 1건 생성). order_item_id UNIQUE 미추가. 분할 배송(1 OrderItem:N Delivery·수량 부분 추적) 후속 트랙 이연 = `DeliveryStarted`/`DeliveryCompleted`가 OrderItem 전체 전이 구조라 부분배송 시 OrderItem 전이 규칙 재설계 강제(D-104 §1-A L6174 "부분 배송(DLV-2) 정책 실체화 강제"). invariants 원문 수정 불요 근거(1:1 ⊂ 1:N) 명문.

#### §7 진입점 카드
| # | 항목 | 내용 |
|---|---|---|
| 1 | **목적** | 일반 주문 배송 시작(판매자 출고 준비→발송)·PAID OrderItem→SHIPPING 완결 |
| 2 | **핵심 진입점** | `SellerShippingController` `POST /api/v1/order-items/{orderItemPublicId}/prepare-shipment`(L48) · `OrderShippingService.prepareShipment`(order/service·L54) |
| 3 | **핵심 SoT 메서드** | `OrderShippingService.prepareShipment`(façade·4단계·L54) · `DeliveryService.createForOrder`(일반 주문 생성·L128~131) · `changeToPreparing`(내부·L90~96) |
| 4 | **영향 범위** | Order Aggregate(OrderItem PREPARING/SHIPPING 전이) · Delivery Aggregate(생성·SHIPPING) · order→delivery 단방향 · production 신규 5·수정 2·test 신규 2 |
| 5 | **패턴 재사용** | D-92 권한=Service 진입부·actor 비의존 9회차 · D-93 `SellerActorResolver` seam · façade 4단계 orchestration · DeliveryEventIntegrationTest/SellerDeliveryIntegrationTest 시드·이벤트 패턴 |
| 6 | **트랩 주의** | 가드 A(@Transactional 원자성·하위 REQUIRED join) · 가드 B(changeToPreparing→markShipping 순서·역전 시 조용한 skip 불일치·T2 락인) · markShipping 위반 미도달(READY 신규만·422 흡수 changeToPreparing 국한) · LT-02 통합 테스트 try-finally |

#### §8 후속 트랙 박제 의무 (carry-over)
- **해소**: markShipping wrapper(D-104 §8 L6257)·일반 주문 Delivery 생성 진입점(L6258)·PREPARING 전이(암묵 귀속·recon §H-3)·D-102 §9 L6002 AdminDeliveryController 확장 중 일반 주문 배송분 → Track 23 통합 해소.
- **이월**:
  - 부분배송 분할(1 OrderItem:N Delivery·OrderItem 전이 규칙 재설계 동반)·후속 트랙.
  - markShipping Admin wrapper(운영자 대행 시나리오 실측 후·현재 판매자 façade로 일반 주문 배송 충족).
  - `DeliveryInvalidStateException` 외 Delivery 예외 계층 확장(공통 부모 EntityStateException 승격은 필요성 미입증·이연).

#### §9 검증 (구현 실측)
- **519 PASS**(baseline 512 + 신규 7·114 suites·failures 0·errors 0·skipped 0·회귀 0). 1단계 배선 검증 3(T1 2회 전이 성립·T2 가드 B 역행 회귀·T3 권한 404·`OrderShippingServiceIntegrationTest`)·2단계 controller 4(T1 200·T2 401·T3 404·T4 422·`SellerShippingControllerIntegrationTest`).
- **파일**: production 수정 2(`GlobalExceptionHandler`·`DeliveryService`)·신규 5(`DeliveryInvalidStateException`·`OrderShippingService`·`SellerShippingController`·`PrepareShipmentRequest`·`PrepareShipmentResponse`)·test 신규 2.

---

## D-108. Track 25 재이연 판정 — NotificationLog 재시도 흐름 [DEFERRED]

**결정일**: 2026-07-02
**관련**: Track 25·D-103 §10·Track 24 준용

### §1 결정
Track 25(NotificationLog 재시도 흐름) 재이연·Track 26+ 실 어댑터 도입 트랙과 동시 진행.

### §1-A 재이연 옵션 검토
- α 재이연 (채택) — Mock PENDING 0건·SoT 미확정·기조 5 정합
- β 결정 SoT만 박제 (기각) — 실 어댑터 특성 미실측 상태 정책 결정 = 재수정 리스크
- γ Repository findByStatus 최소 신설 (기각) — 호출자 부재 죽은 코드
- δ 전건 구현 (기각) — 대상 0건·SoT 미확정·기조 4·5 이중 위반

### §2 정찰 실측 결과 (handover.md §3·§4 인용)
- Mock 환경 PENDING 잔류 0건 (MockNotificationSender.send() L18~23 항상 성공 → 즉시 SENT)
- Repository findByStatus(NotificationLogStatus) 미존재 (D-103 §10 신설 대상)
- 재시도 정책 관련 grep 0건 (retry·backoff·@Scheduled·재시도)
- 실 어댑터(SMTP·SMS·FCM) 부재 (Track 20+ 이연·D-103 §10)
- 재시도 요구 SoT 미확정 (D-103 §10 원문 "재시도 요구사항 SoT 확인 후" 조건절)

### §3 재진입 조건
1. 실 어댑터(SMTP·SMS·FCM 중 최소 1종) 도입 완료
2. 재시도 요구 SoT 확정 (트리거·대상 상태·횟수·backoff·최종 실패 처리)
3. PENDING/FAILED 잔류 실제 발생 경로 확인

### §4 재진입 시 필수 정찰 항목 (SoT 확정 의무)
1. D-08 정합·NotificationLog 만료·재시도 요구 SoT 원문
2. NotificationLog 엔티티 현행 스키마 (retry_count 컬럼 추가 여부 판단 근거)
3. NotificationService·NotificationSender·NotificationLogRepository 실측 (실 어댑터 도입 후 상태)
4. state-machine·invariants NotificationLog 관련 절 재실측 (§3.1 NOT-1~3 갱신 여부)
5. @Scheduled 기존 사용 여부·설정 실측 (Payment 만료 트랙 D-08 M-14와 동반 도입 시)

### §5 재진입 판단 필요 사항 (Track 26+ 결정 라운드 진입 시)
- 판단 1: 재시도 트리거 (α 스케줄러 자동·β Admin 수동·γ 혼합)
- 판단 2: 재시도 대상 상태 (α PENDING only·β FAILED reset·γ 신규 행)
- 판단 3: 횟수·backoff·최종 실패 처리·retry_count 컬럼 추가
- 판단 4: Repository·Service 확장 범위·커스텀 예외 계층

### §6 관련 결정
- D-103 §10 (Track 19·NotificationSender seam·재시도 이연 원문)
- D-92·D-93 (Admin wrapper 캡슐화 원칙·재진입 시 재사용)
- D-107 (Track 23 façade·1-PR 3-split 패턴 재사용)

### §7 carry-over
재이연 판정·구현 무·§4·§5 항목이 Track 26+ carry-over.

---

## D-109. Track 25 자동 예약 만료 — PENDING 결제 만료 배치 (PENDING→FAILED·예약 자동 해제) [ACTIVE]

**결정일**: 2026-07-02
**관련**: D-08 M-14·D-32·D-34·D-101 §3·§17·D-100 Q3(TracedEventPublisher wrapper)·D-29(발행 순서)
**브랜치**: feat/track-25-payment-auto-expire

### §1 결정 요약
만료된 PENDING 결제를 스케줄러 배치가 주기적으로 FAILED 전이시키고, 기존 `PaymentFailed`(E3) 파이프라인을 재활용해 재고 예약을 자동 해제한다. D-08 M-14 "타이머/배치 이연"을 해소한다.
- **트리거**: `@Scheduled`(fixedDelay 5분) 단독 — Admin 수동 재실행 endpoint는 이연(§8-b).
- **전이**: 기존 `Payment.fail("PAYMENT_EXPIRED", now)` 재사용 — 신규 `markExpired()` 도메인 메서드·`PaymentExpired` 이벤트 미신설.
- **하류 자동 상속**: `PaymentFailed` 재사용으로 `InventoryPaymentFailedHandler`(AFTER_COMMIT·REQUIRES_NEW·Track 17 D-101 §3) 재고 해제가 신규 배선 없이 상속됨 — 실 신규 작업은 "PENDING→FAILED를 발화하는 트리거" 단 하나.

### §1-A 옵션 검토 (채택/기각)
| Q | 쟁점 | 채택 | 기각 |
|---|---|---|---|
| Q1 | 트리거 | **α 스케줄러**(@Scheduled 5분·단일 인스턴스 정합) | β Admin 수동(이연·§8-b)·γ 혼합(과잉·기조 4) |
| Q2 | 만료 후보 조회 | **`findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc` + V8 `idx_payment_expire`** | 무인덱스 full scan(recon §C FAIL 리스크) |
| Q3 | 전이 방법 | **`fail()` 재사용**(하류 PaymentFailed 파이프라인 자동 상속) | `markExpired()` 신규 도메인 메서드(중복·이벤트 신설 유발) |
| Q4 | failure_code | **`PAYMENT_EXPIRED`**(기존 `failure_code VARCHAR(50)` 무제약 선례 계승) | ENUM/CHECK 4층위 잠금(선례 무제약·과잉·§8) |
| Q5 | 동시성·멱등 | **건별 `findByIdForUpdate` 비관락 + status/isExpired 재검증** | ShedLock 도입(단일 인스턴스·자연 직렬화로 불요·기조 4) |
| Q6 | 인프라 | **`SchedulingConfig` 분리(@EnableScheduling)·batch 100·5분** | Application 클래스 직접 부착(AuditingConfig 분리 선례 위배) |

### §2 외부 검토 + 재검토 수렴
recon-report.md(460줄·외부 검토 2회 수렴)를 근거로 아래 재검토 수렴.
- **R1 (건별 TX 경계)**: 스케줄러 TX 없음·`expireOne` 건별 @Transactional → 한 건 커밋이 AFTER_COMMIT 재고 해제 발화 조건 충족·부분 실패 격리(1건 실패가 나머지 롤백 안 함).
- **R2 (자연 멱등)**: `findByIdForUpdate` 잠금 후 `status != PENDING`(콜백 선점)·`!isExpired`(만료 조건) 2중 재검증 → 중복 발화·경합 콜백 안전. ShedLock 없이 다중 인스턴스 직렬화(비관락).
- **R3 (Admin 이연)**: 재실행 endpoint는 실 운영 필요성 실증 후 도입 — 현 스코프 스케줄러 단독.
- **R4 (SoT 동반 흡수)**: 만료→FAILED 재정의는 D-08 원안(L253 E3 트리거)·domain-events E3(L62)와 모순 없음 → SoT 7건 잠금 승격만 동반.

### §3 구현 산출 (실측 라인)
**신규 6**:
- `common/config/SchedulingConfig.java` — `@Configuration @EnableScheduling`(L12-14).
- `payment/service/ExpirePaymentService.java` — `expireOne(Long)` @Transactional(L44).
- `payment/scheduler/ExpirePaymentScheduler.java` — `expireBatch()` @Scheduled(fixedDelay 5분)(L49).
- `resources/db/migration/V8__payment_expiry_index.sql` — `CREATE INDEX idx_payment_expire ON payment (status, expires_at)`(L19).
- `test/payment/scheduler/ExpirePaymentSchedulerTest.java` — 단위 4건.
- `test/payment/integration/PaymentExpiryIntegrationTest.java` — E2E 4건.

**수정 3**:
- `payment/service/PaymentService.java` — `public static final String PAYMENT_EXPIRED`(L63·`ExpirePaymentService` 참조 위해 public).
- `payment/repository/PaymentRepository.java` — `findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(status, now, Pageable)`(L47·파생쿼리 + Pageable).
- `payment/entity/Payment.java` — `fail()` Javadoc 보강(정책 만료 포함·L152-158).

### §4 핵심 메서드
- **`ExpirePaymentService.expireOne`(L44)**: `findByIdForUpdate`(비관락) → `status != PENDING` skip → `!isExpired(now)` skip → `fail(PAYMENT_EXPIRED, now)`(L62) → pull → save → 동기 publish(D-29 순서 재사용). 2중 재검증으로 조회~잠금 사이 콜백 선점 멱등 처리.
- **`ExpirePaymentScheduler.expireBatch`(L49)**: TX 없음 → `findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(PENDING, now, PageRequest 100)`(L54) → id별 `try{ expireOne(id) } catch(Exception){ log·count·continue }` → `catch(Exception)`으로 **Error 미흡수**(치명 오류 전파)·schedulerRunId·성공/실패 건수 로그.

### §5 이벤트/파이프라인 (재사용·자동 상속)
- `PaymentFailed`(E3) payload 재사용(신규 이벤트 없음): `(paymentId, orderId, PAYMENT_EXPIRED, occurredAt)`.
- 소비: `InventoryPaymentFailedHandler`(Track 17·D-101 §3·AFTER_COMMIT+REQUIRES_NEW) — orderId로 OrderItem 재조회·품목별 `InventoryService.release`·reserved==0 skip 멱등·실패 격리(recordFailed 흡수). **신규 배선 0건**.
- 발화 조건: `expireOne` 트랜잭션 커밋 후 AFTER_COMMIT 핸들러 발화 → 재고 예약 해제(따라서 만료 전이는 반드시 커밋돼야 재고 해제).

### §6 테스트 (구현 후 실측)
- **527 PASS**(baseline 519 + 신규 8·116 suites·failures 0·errors 0·skipped 0·회귀 0·cleanTest test 3m23s).
- 단위(`ExpirePaymentSchedulerTest` 4): 정상·부분실패격리(RuntimeException 흡수 후 진행)·Error 미흡수(전파·후속 중단)·빈 배치.
- E2E(`PaymentExpiryIntegrationTest` 4): 만료 PENDING→FAILED(PAYMENT_EXPIRED)+PaymentFailed 발행+AFTER_COMMIT 재고 해제 / PAID skip(재검증 멱등) / 미만료 skip / EXPLAIN.
- **EXPLAIN**: `type=range · possible_keys=key=idx_payment_expire · key_len=10 · Extra="Using where; Using index"` — 3행에서도 옵티마이저가 인덱스 선택·covering·full scan 없음.

### §7 SoT 7건 갱신 (본 박제 동반)
- [정책변경 5]: state-machine.md §1 L21 · decisions.md D-32 §4+영향범위 · decisions.md D-101 §17 · domain-events.md E3 L67(재시도) · db-schema-decisions.md Payment 절 expires_at.
- [문서정정 2]: domain-events.md E3 L65(발행=동기/소비=AFTER_COMMIT 정합) · domain-events.md L235(외부 이연 해소·예약 해제 완료·주문 자동 취소 이연).
- [검토] D-05 L160-162 = **편집 불요**(§8-c).

### §8 carry-over
- **(a) 운영 규모 인덱스 재검증**: EXPLAIN은 테스트 3행 기준 range scan. 운영 데이터 분포·통계(cardinality) 종속 → 스테이징 `EXPLAIN ANALYZE`·slow query 재검증 대상.
- **(b) Admin 재실행 endpoint**: 수동 만료·재처리 endpoint는 실 운영 필요성 실증 후 도입(현 스코프 제외).
- **(c) D-05 편집 판정 = 편집 불요**: D-05는 A분류(값 집합·전이 edge 잠금)이며 Track 25는 신규 상태·edge 미추가(기존 PENDING→FAILED edge에 트리거만 추가). 트리거 상세 SoT는 state-machine.md §1(위임·PAY-2 동일 원칙)이 보유하며 본 박제에서 §1 갱신. D-05 괄호주("PG 실패")는 예시 주석이며 exhaustive 트리거 목록이 아님(D-34 CANCEL×PENDING 트리거도 D-05 미기재 선례) → 만료 트리거도 D-05 직접 편집 불요.

### §9 @ConditionalOnProperty 킬스위치
`ExpirePaymentScheduler`에 `@ConditionalOnProperty(name="zslab.payment.expiry.enabled", havingValue="true", matchIfMissing=true)` 부착.
- **사유**: 프로젝트 test profile 인프라 0건(@Profile·@ActiveProfiles·@ConditionalOnProperty 실측 0건) → @Scheduled가 모든 @SpringBootTest 컨텍스트에서 자동 발화하는 라이브 트랩.
- **효과**: 기본 활성(운영 무영향·application.yml 미변경)·통합테스트만 `zslab.payment.expiry.enabled=false`로 발화 차단(결정론 확보)·운영 긴급 킬스위치 겸용.

### §진입점 카드
- **목적**: 만료 PENDING 결제 자동 FAILED 전이 + 재고 예약 자동 해제(D-08 M-14).
- **진입점**: `ExpirePaymentScheduler.expireBatch`(scheduler·L49) → `ExpirePaymentService.expireOne`(service·L44).
- **SoT 메서드**: `Payment.fail`(전이·이벤트 누적)·`Payment.isExpired`(만료 판정)·`PaymentRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc`(만료 조회 SoT).
- **영향 범위**: payment(전이·조회·PAYMENT_EXPIRED 상수)·inventory(PaymentFailed 소비·해제 자동 상속).
- **패턴 재사용**: `TracedEventPublisher` 발행 wrapper(D-100 Q3 β′·Track 16)·D-29 pull→save→publish 순서·AuditingConfig @Configuration 분리 선례.
- **트랩**: LT-02(FK_CHECKS try-finally)·@Scheduled 테스트 자동 발화(§9 킬스위치로 차단)·AFTER_COMMIT 발화는 커밋 필수(테스트는 비-TX 직접 호출).

---

## D-110. Track 26 통합 테스트 LT-02 위반 보정 — CheckoutIntegrationTest·PaymentWebhookIntegrationTest FK 세션 변수 복원 · 복원 지점 구조 의존 실증 [ACTIVE]

**결정일**: 2026-07-02
**관련**: Track 26 / D-79 [ARCHIVED]·D-89 Q9·D-90 Q5·D-91·D-100 Q8 / live-traps LT-02 / docs/handover.md (Track 26 정찰)

### §1 결정 요약
LT-02(SET FOREIGN_KEY_CHECKS=0 사용 시 HikariCP 커넥션 세션 변수 잔류) 위반 2파일 3지점을 보정한다. 복원(FK=1) 짝을 추가하되, 복원 지점은 테스트의 트랜잭션 구조에 따라 seed 블록 내부가 아니라 @AfterEach 최종 지점으로 배치한다. 나머지 22개 통합 테스트는 이미 준수·무변경. 배치 규약 SoT는 D-100 Q8이 이미 보유하므로 신규 문서(test-standards.md) 미신설. 회귀 방지는 Track 20~25 패턴 자연 준수 유지(ArchUnit 미도입).

- **수정 대상 3지점**: CheckoutIntegrationTest(seed FK=0 L90 복원 부재)·PaymentWebhookIntegrationTest(seed FK=0 L103·cleanup FK=0 L124 복원 부재).
- **복원 지점**: 두 파일 모두 FK=0을 테스트 본체 실행 구간 전체에 유지해야 하는 구조 → 복원을 @AfterEach 최종 finally로 배치(seed 블록 내부 복원은 성립 불가·실증).
- **527 tests PASS 유지·회귀 0**.

### §1-A 옵션 검토 (채택/기각·결정 근거 영구화)

**트랙 범위 (α/β/γ)**:

| # | 범위 안 | 판정 | 근거 |
|---|---|---|---|
| α | LT-02 실재 위반 2파일 3지점 전건 보정 | **채택** | Checkout·PaymentWebhook 모두 실재 위반·죽은 코드 아님·D-89 Q9 이연 사유가 "고치기 어려움"이 아니라 단순 후순위 백로그(@Transactional 롤백 의존)로 실측 확인 → 실재 결함 처치는 작업 범위와 무관하게 과잉개발 아님(기조 4 정의: 미사용·죽은 코드 회피이지 범위 크기 아님) |
| β | PaymentWebhook 1건만 보정·Checkout은 D-89 Q9 이연 유지 | 기각 | Checkout LT-02도 실재 위반·복원 부재 시 HikariCP 세션 변수 잔류는 @Transactional 롤백과 무관(세션 변수는 트랜잭션 대상 아님)·D-89 Q9는 후순위 지목이지 영구 이연 아님 |
| γ | 코드 무수정·test-standards.md 신설 단독 | 기각 | 실위험 2건 잔류·D-100 Q8이 이미 5중 의무 SoT로 기능 중이라 문서 신설은 미사용 문서(기조 3·4 위배) |

**SoT 위치 (test-standards.md 신설 여부)**:
- **decisions.md D-100 Q8 단독 채택**: 통합 테스트 표준(NO @Transactional·TransactionTemplate·@RecordApplicationEvents·LT-02 try-finally·D-91 FK 부모 그래프 5중 의무)은 D-100 Q8(decisions.md L5226)이 이미 단일 지점 박제. test-standards.md 신설은 기능 중 SoT 중복·실질 이득이 검색 편의에 그침 → 기조 3·4 위배로 기각. 5곳 분산 SoT(D-89·D-90 Q5·D-91·D-100 Q8·LT-02)는 각자 다른 축의 부속 SoT로 정합 기능 중.

**회귀 방지 (ArchUnit)**:
- **자연 준수 채택**: Track 20~25 통합 테스트 5건 전건 표준 준수 실측(handover §5-1)·신규 테스트 자연 수렴. ArchUnit은 D-100 Q8이 이미 이연(Javadoc 매트릭스 확정 종속)·별도 의존성 부담 → 미도입.

### §2 결정 라운드 Q (재진입·재검토 수렴)
- **재검토 1 (기조 4 정의 정합)**: 초기 추천은 CheckoutIntegrationTest를 "범위 확대"로 보아 이연 유지(β 근접)였으나, 사용자 기조 4 정의("과잉개발 = 미사용·죽은 코드 회피이지 작업 범위 크기 아님") 명시 후 재검토 → 실재 위반 2건 전건 보정(α)으로 정정. 기조 4 정의는 CLAUDE-DEV.md 5대 기조 절에 영구 박제(채팅별 해석 표류 차단).
- **재검토 2 (D-89 Q9 이연 사유 실측)**: Checkout 미보정 사유가 "고치기 어려움"인지 확인 위해 D-89 Q9 원문(decisions.md L3057) MCP read → "@Transactional rollback 의존·별도 트랙 보정 후보(백로그)" 확인. 기술적 장애 아닌 후순위 → 본 트랙 흡수 정당.
- **재검토 3 (복원 지점 실측 정정·핵심)**: 사전 실측(STEP 1)에서 "seed 블록 내 try-finally"(RefundWebhook 패턴)를 승인했으나 구현 시 10개 실패. 실측 원인 = ① Checkout은 @Transactional 공유 커넥션·seed finally FK=1 복원 시 테스트 본체(buyer_id=1 주문 INSERT)가 FK=1로 실행되어 fk_order_user 위반 ② PaymentWebhook은 웹훅 핸들러가 order_item 전컬럼 UPDATE(seller_id 포함) 발행·orphaned FK 참조로 FK=0이 본체 실행까지 유지 필요(RefundWebhook은 claim/payment만 변경해 회피). 복원 지점을 @AfterEach 최종 finally로 이동해 커넥션 풀 반환 전 세션 변수 정리 → LT-02 충족·527 PASS.

### §3 구현 산출 (실측)
**수정 2파일 (production·DDL·문서·22개 정상 테스트 무변경)**:
- `checkout/CheckoutIntegrationTest.java` — seed() @BeforeEach 원본 유지(FK=0·INSERT·flush)·resetSpy() @AfterEach에 `try { SET FK=1 } finally { Mockito.reset }` 추가. @Transactional 공유 커넥션·단일 트랜잭션 구조로 FK=0이 seed~본체 전체 유지·@AfterEach 복원이 롤백 전 세션 변수 정리.
- `payment/PaymentWebhookIntegrationTest.java` — seed()·cleanup() 원본 유지(FK=0·try-finally 없음)·tearDown() @AfterEach에 `try { cleanup() } finally { SET FK=1 }` 추가. 웹훅 order_item orphaned FK 참조로 FK=0이 본체 전체 유지·최종 복원으로 HikariCP 반환 전 정리.

### §4 검증 (구현 후 실측)
- 527 PASS(baseline 527 유지·신규 테스트 무·failures 0·errors 0·회귀 0)·BUILD SUCCESSFUL 3m 12s·`./gradlew.bat cleanTest test`·2026-07-02.
- 빌드툴: Gradle(`backend/gradlew.bat`)·pom.xml 부재.
- 브랜치: `feat/track-26-integration-test-lt02`(docs·feat·test 3-split·push 명시 지시 대기).

### §5 핵심 실증 (복원 지점 구조 의존)
LT-02 복원 짝의 **배치 지점은 테스트의 트랜잭션 구조에 종속**한다. 단일 표준 패턴("seed 블록 try-finally"·RefundWebhook)이 전 통합 테스트에 일괄 적용 불가:
- **@Transactional 공유 커넥션 테스트**(Checkout): FK=0이 seed~본체 단일 트랜잭션 전체 유지 필요 → 복원은 @AfterEach.
- **핸들러가 FK 부모 컬럼 UPDATE 발행하는 테스트**(PaymentWebhook·orphaned FK 참조): FK=0이 본체 실행까지 유지 필요 → 복원은 @AfterEach 최종.
- **seed/cleanup만 FK 우회하고 본체가 FK 참조 안 하는 테스트**(RefundWebhook): seed 블록 내 try-finally로 충분.

### §진입점 카드 (메모리 룰 #16)
- **목적**: 통합 테스트 LT-02 위반 2파일 보정·복원 지점 구조 의존 실증.
- **핵심 진입점**: `checkout/CheckoutIntegrationTest.java` resetSpy() @AfterEach · `payment/PaymentWebhookIntegrationTest.java` tearDown() @AfterEach.
- **핵심 SoT**: LT-02 복원 짝 = FK=0 유지 구간 종료 지점(@AfterEach)에 배치 · D-100 Q8 5중 의무 SoT.
- **영향 범위**: 테스트 2파일 단독 · production·DDL·문서 무변경 · 22개 정상 테스트 무변경.
- **패턴 재사용**: LT-02(live-traps L56) · D-89 Q9 이연 해소 · RefundWebhook try-finally 패턴(단 복원 지점은 구조별 상이).
- **트랩 주의**: seed 블록 내 복원은 @Transactional 공유 커넥션·핸들러 FK 부모 UPDATE 테스트에서 성립 불가(본체 FK 위반)·복원 지점은 테스트 트랜잭션 구조 실측 후 결정.

### §8 carry-over
- carry-over 무. Track 26은 LT-02 위반 2건 전건 보정·D-89 Q9 이연 해소로 종결. 잔여 이월 항목 없음.
- 후속 자연 진입 트랙: 백로그 §10 유지(markFailedByAdmin·Payment wrapper·markShipping Admin wrapper·부분배송 분할·Spring Security 등)·본 트랙과 독립.

---

## D-111. 외부 연동 seam 현황 박제 — 결제·알림 seam 확립·택배/주소/이미지 실 어댑터 전용 경로 실측 · Mock→실 어댑터 전환 준비도 SoT [ACTIVE]

**결정일**: 2026-07-02
**관련**: 트랙 무관 문서 정리 / D-27(PaymentGateway 추상화)·Track 19 D-86(NotificationSender) / 기조 4(Mock 경계·죽은 코드 판정)·기조 5(정찰 실측)

### §1 결정 요약
MVP를 Mock 기준으로 진행하되 실 운영 진입 시 Mock→실 어댑터 교체 가능 여부를 판정할 SoT를 박제한다. 5개 외부 연동 중 결제·알림 2건은 인터페이스 seam 확립(실 어댑터 교체 준비 완료)·택배·주소·이미지 3건은 seam 미확립이되 Claude Code 정찰로 **3건 모두 "실 어댑터 도입돼야 비로소 호출되는 경로"**(MVP에서 외부 호출 미exercise) 실측 확정 → seam 신설은 실 연동 트랙 이연 정합.

| 외부 연동 | seam 상태 | 실 어댑터 교체 준비 | 실측 근거 |
|---|---|---|---|
| 결제(PG) | 인터페이스 분리 확립 | 준비 완료 | `payment/gateway/PaymentGateway.java`(D-27·"실 PG 교체 지점" Javadoc)·`MockPaymentGateway` |
| 알림(이메일·SMS·PUSH) | 인터페이스 분리 확립 | 준비 완료 | `notification/adapter/NotificationSender.java`(Track 19·D-86·PaymentGateway 1:1 정합·채널 SMTP·SMS·PUSH 단일 seam)·`MockNotificationSender` |
| 택배(delivery) | seam 없음 | 신규 설계 필요 | 외부 택배사 API 호출 0건·`trackingNo`는 `DeliveryService.markShipping(deliveryId, trackingNo)`(L49) 수동 파라미터 주입·HTTP 클라이언트 import 0 |
| 주소 API | seam 없음 | 신규 설계 필요 | `UserAddress`·`OrderShippingSnapshot` free-text 4컬럼(zonecode·address_road·address_jibun·address_detail) 저장·백엔드 주소검색 API 0건·프론트 우편번호 위젯 구조 |
| 이미지 저장(attachment) | seam 없음 | 신규 설계 필요 | `attachment/` = Entity·Repository 뼈대만·**상위 소비처(Service·Controller) 0건**·업로드/S3 호출 0·실 이미지는 `ProductImage.image_url VARCHAR(2048)` URL 문자열 저장 |

**판정 기준**: Mock 유지는 MVP 정책·seam 확립 여부가 실 운영 전환 준비도의 판정 기준. seam 확립 = Mock 구현만 교체·계약 유지. seam 미확립 = 인터페이스 분리 선행 후 어댑터 도입.

### §1-A 옵션 검토 (채택/기각·결정 근거 영구화)

**박제 범위 (α/β/γ)**:

| # | 범위 안 | 판정 | 근거 |
|---|---|---|---|
| α | seam 현황만 박제(확립 2·미확립 3 실측·SoT)·신설은 백로그 이연 | **채택** | Claude Code 정찰로 택배·주소·이미지 3건 모두 MVP에서 외부 호출 미exercise·"실 어댑터 도입돼야 호출되는 경로" 실측 확정 → 기조 4 이연 대상 정합. 실측 사실 박제는 코드 무변경·SoT 공백(채팅 확인만) 해소. |
| β | seam 현황 + 택배·주소·이미지 seam 지금 확정 | 기각 | **정찰 실측**: 택배(trackingNo 수동 주입·API 0)·주소(free-text 저장·API 0·프론트 위젯)·이미지(attachment 소비처 0·ProductImage URL 문자열) 3건 모두 실 어댑터 미도입 상태 외부 호출 경로 부재. seam을 지금 확정하면 소비처 없는 인터페이스 선박제(기조 4 위배). MVP 정책의 "6종 Mock seam 확정"은 실제 호출되는 경로 한정 해석(기조 4 "MVP에서 실제 사용되는가")이며, 미호출 경로는 seam 확정 대상 아님. |
| γ | 문서 무박제(채팅 확인 유지) | 기각 | seam 실측이 채팅·정찰에만 존재·decisions.md 미박제 → handover/recon 소실 시 재현 불가. 전환 준비도 판정 SoT 공백은 기조 2(객관 판단) 저해. |

### §2 결정 라운드 Q (재검토 수렴)
- **재검토 1 (Mock 정책 ↔ 기조 4 상충 해석)**: Mock 유지가 기조 4와 상충하는지 검토. 결론 = 비상충. 결제·알림 Mock seam은 이미 호출되는 실사용 경로(테스트·MVP 실행)·미사용 아님. 실 어댑터 신설만 "도입돼야 호출되는 경로"로 이연 대상.
- **재검토 2 (MVP 정책 6종 vs 실측 갭)**: MVP 정책은 PG·택배·이메일·SMS·주소·이미지 6종을 Mock seam 확정 대상으로 명시하나, 실측은 결제·알림 2종만 seam 보유·택배/주소/이미지는 Mock조차 부재. 초기 β 기각을 "미사용 인터페이스 선박제"로 단언한 것은 미호출 전제를 실측 없이 단정 = 기조 5 위반 소지로 판정 → 재정찰 진입.
- **재검토 3 (Claude Code 정찰로 갭 해소·핵심)**: read-only 3-STEP 정찰 결과 — ① 택배: HTTP 클라이언트 import 0·trackingNo 판매자/운영자 수동 입력 파라미터 주입 ② 이미지: attachment 상위 계층 0(소비처 없는 뼈대·기조 4 죽은 코드)·MultipartFile/S3 호출 0·실 이미지는 `ProductImage.image_url` URL 문자열 저장 ③ 주소: `UserAddress`·`OrderShippingSnapshot` free-text 저장·주소검색 API(juso·kakao) 0·프론트 클라이언트 위젯 구조. 3건 모두 MVP에서 외부 호출 미exercise → "실 어댑터 도입돼야 호출되는 경로" 확정·α채택 실측 근거 확보(MVP 정책 6종은 실호출 경로 한정 해석으로 정합).

### §진입점 카드
- **목적**: MVP Mock 기준 진행 중 실 운영 진입 시 Mock→실 어댑터 전환 준비도 판정 SoT 박제.
- **핵심 진입점**: `payment/gateway/PaymentGateway.java`·`notification/adapter/NotificationSender.java`(확립 seam 2건).
- **핵심 SoT**: seam 확립 = 인터페이스 분리·Mock 구현 교체로 실 어댑터 전환 가능. PaymentGateway·NotificationSender가 교체 지점 계약 보유. 택배/주소/이미지는 실호출 경로 부재로 seam 미필요(현 시점).
- **영향 범위**: 문서 단독(decisions.md)·코드·DDL·테스트 무변경.
- **패턴 재사용**: D-27(PaymentGateway 추상화·교체지점 Javadoc)·Track 19 D-86(NotificationSender 1:1 정합).
- **트랩 주의**: ① `attachment/`는 소비처 0건 죽은 코드 뼈대 — 실 이미지 저장은 `ProductImage.image_url`이 담당(attachment로 오인 금지) ② 택배/주소/이미지 실 연동 진입 시 seam 분리 + Mock/실 구현 동시 설계 필요(결제·알림과 달리 Mock 부재).

### §8 carry-over (백로그 — seam 신설)
- **실 연동 seam 신설 (delivery·attachment·address)**: 우선순위 A/S급·처치 시점 = 실 운영 진입 시(MVP 후·실 어댑터 도입 선행). 각 도메인에 PaymentGateway/NotificationSender 패턴 인터페이스 seam 분리 + Mock/실 어댑터 설계. 실측 사양은 진입 트랙 확보(미실측·차기 정찰 대상).
- **attachment 죽은 코드 판정**: attachment Entity·Repository는 현재 소비처 0건(기조 4). 실 이미지 seam 신설 트랙에서 재활용 여부(attachment 승격 vs ProductImage 확장) 판정 필요·현 시점 존치(제거는 별도 결정).
- 백로그 SoT = 본 §8 carry-over(별도 backlog.md 부재 실측·D-110 §8 백로그 나열 선례 정합).

## D-112. Track 27 Seller 재고 self-service 입출고 wrapper — markInbound/markOutbound·3홉 소유권 검증·INBOUND/OUTBOUND enum 소비 [ACTIVE]

**결정일**: 2026-07-03
**관련**: Track 27 / D-101 §2·§13(Inventory 도메인 행위·enum 보존)·D-105 §8·D-106 §8(markInbound/markOutbound 이연)·D-92 Q3(Seller 권한 검증 Service 진입부)·D-59(Product/Seller read-only) / 기조 1(운영 용이성)·기조 4(과잉개발 회피·YAGNI)·기조 5(실측 우선)

### §1 결정 사항 (A~G 확정·구현 완료 실측)
3P 입점 위탁형 마켓플레이스(§9-6 판정)에서 판매자가 자사 재고를 직접 입·출고하는 self-service 진입점을 신설한다. D-105 §8·D-106 §8에서 Track 23+로 이연했던 markInbound/markOutbound를 Seller 액터 wrapper로 확정 구현한다.

- **A (Seller self-service 신설)**: Admin markInbound/markOutbound 신설 안 함·기존 `adjustStock` 유지. Seller 액터 endpoint 2건 신설.
- **B (INBOUND/OUTBOUND enum 소비)**: `InventoryHistoryChangeType.INBOUND/OUTBOUND`(D-101 §13 보존 enum) 소비·`referenceType="seller"`·`referenceId=sellerId`.
- **C (C-α2 Service wrapper 재사용)**: `InventoryService.markInboundBySeller`(L137)·`markOutboundBySeller`(L160) wrapper 2건 신설. 내부에서 기존 `Inventory.adjustStock(±qty)`(entity L134 도메인 행위) 재사용·신규 도메인 메서드 미신설(기조 4).
- **D (URL 대칭)**: `POST /api/v1/seller/inventories/{variantPublicId}/mark-inbound`·`/mark-outbound`. Admin `/api/v1/admin/inventories/`와 대칭.
- **E (γ 계승)**: E10 InventoryAdjusted 미발행. `@RecordApplicationEvents` inventory 이벤트 0건 회귀 잠금(SellerInventoryControllerIntegrationTest T3~T8).
- **F (백로그 흡수)**: `InventoryServiceTest`에 `adjustStock` 단위 6 케이스 동반 보강(기존 8→14).
- **G (Service 진입부 소유권)**: 3홉(variantId→productId→sellerId) 소유권 검증을 `InventoryService.authorizeSellerAccess`(L183) 진입부에서 수행(D-92 Q3 횡단 원칙).

**소유권 검증 실 로직**: Inventory는 seller_id 직접 참조 없음(§9-5·INV-6 1:1) → `productVariantRepository.findById`(변형 미존재 404) → `productRepository.findById`(FK 무결성 위반 500 IllegalStateException) → `Product.getSellerId().equals(sellerId)` 불일치 시 `ProductVariantNotFoundException`(404 은닉) 3홉. 권한 위반은 미존재와 동일 은닉(**결정 H**·아래 §1-A).

**신설 3**: `SellerInventoryController`·`SellerInventoryMarkInboundRequest`·`SellerInventoryMarkOutboundRequest`. **수정 1**: `InventoryService`(ProductRepository·ProductVariantRepository DI 추가·wrapper 2·helper 1). **응답 DTO**: 기존 `InventoryAdjustResponse` 재사용(신설 안 함).

**검증**: `./gradlew.bat cleanTest test` 541 tests·failures 0·errors 0·skipped 0(117 suites·3m21s). baseline 527 + SellerInventoryControllerIntegrationTest 8(T1~T8) + InventoryServiceTest adjustStock 6 = 541·기존 회귀 0.

### §1-A 옵션 검토 (채택/기각·결정 근거 영구화)

**A 옵션 (재고 진입점 방식)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| Admin only(대행) | 기각 | 3P 위탁형에서 판매자 재고 자기관리가 MVP 운영 시나리오(§9-6)·Admin 대행은 3P 모델 부정합. |
| **Seller self-service** | **채택** | README "멀티벤더 입점형"·actors "판매관리자=자사 상품 관리"·Product.seller_id NOT NULL(§9-2·§9-3 실측)→판매자 소유 재고 자기관리 정합. |
| 발주·검수 참조 모델 연동 | 기각 | §9-6·D-105 §1-A: 발주서·검수서 참조 모델은 범위 급증·A→S급 승격 위험. 실 운영자 시나리오 실증 후 별도 S급 트랙(§8). |

**C 옵션 (OUTBOUND/INBOUND 도메인 행위)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α adjustStock 재사용 | (부분) | on_hand ±qty·INV-1·INV-4 가드가 adjustStock와 100% 동일. |
| β 신규 markInbound/markOutbound 도메인 행위 신설 | 기각 | 도메인 행위 신설 시 로직 100% 중복·명명만 상이(기조 4 위배). |
| **C-α2 Service wrapper 재사용** | **채택** | 도메인 primitive는 adjustStock 재사용·액터별 권한·History 유형(INBOUND/OUTBOUND)만 wrapper에 캡슐화(D-92 Q3 sub a‴ 패턴 정합). |

**E 옵션 (E10 발행)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| **γ 계승(미발행)** | **채택** | recipient 산정 정책 미확립(domain-events.md L171 선택 이벤트)·adjustStock γ 잠금 계승·D-108 재진입 트랙 동반 승격 정합. |
| 발행 승격 | 기각 | InventoryAdjusted record + TracedEventPublisher 주입 + 핸들러 신설 파급·recipient 정책 선결 필요(§8 이연). |

**G 옵션 (소유권 검증 위치)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| Controller 진입부 | 기각 | Controller는 HTTP 책임만(D-40 β′)·소유권 로직 혼입은 SellerShipping/SellerClaim 패턴 이탈. |
| **Service 진입부** | **채택** | D-92 Q3 횡단 원칙·기존 3종 wrapper(approveBySeller·registerExchangeShipmentBySeller·prepareShipment) authorize 패턴 정합. |

**H 옵션 (미존재·cross-tenant 예외 매핑·Track 27 결정 라운드 신규)**: task 원안은 `InventoryNotFoundException` 신설이었으나 Phase 1 실측으로 두 트랩 발견 → 재검토 후 기존 예외 재사용(①) 확정.
| 옵션 | 판정 | 근거 |
|---|---|---|
| **① 기존 예외 재사용** | **채택** | cross-tenant·변형 미존재 모두 `ProductVariantNotFoundException`(404·동일 code)→full hiding(SellerShipping ORDER_NOT_FOUND·SellerClaim CLAIM_NOT_FOUND 단일 예외 패턴 정합). inventory row 미존재는 `InventoryInvariantViolationException`(422·adjustStock 정합). 신규 예외·GlobalExceptionHandler 무변경(기조 4). |
| ② InventoryNotFoundException 신설 | 기각 | GlobalExceptionHandler에 일반 NotFound catch-all 부재(각 예외 개별 @ExceptionHandler·L94~133)→핸들러 미등록 시 500 fallback 트랩(task 수정 목록 GEH 누락). cross-tenant code가 controller의 PRODUCT_VARIANT_NOT_FOUND와 상이→변형 존재 부분 노출(full hiding 약화). |

### §2 결정 라운드 재진입·재검토 수렴
- **재진입 1 (사업 모델 정합성 §9 추가 정찰)**: α(Admin 대행) vs β(Seller self-service) 이분화 재정의. README·actors·marketplace-core-domain·Product.seller_id NOT NULL 실측(§9-2~§9-5)으로 **3P 입점 위탁형 확정**·Seller self-service(β) 채택.
- **재진입 2 (Seller 소유권 검증 실 로직 §10 추가 정찰)**: "껍데기 위험" 해소. HeaderSellerActorResolver·ClaimService.authorizeSellerAccess·OrderShippingService.authorize 실 로직 실측(§10-1~§10-8)으로 3홉 검증 패턴 확보·SellerInventoryController 신설 시 동일 패턴 적용 확정.
- **재진입 3 (Phase 1 예외 매핑 트랩)**: task 원안 InventoryNotFoundException이 GEH 404 핸들러 누락(500 트랩)·cross-tenant hiding 약화 유발 실측 → 결정 H① 기존 예외 재사용으로 수렴.
- **백로그 F 동반 흡수**: 트랙 규모 과소분할 이견에 따라 adjustStock 단위 테스트 6 케이스(백로그 F)를 Track 27에 동반 흡수(InventoryServiceTest 8→14).

### §진입점 카드
- **목적**: 3P 판매자가 자사 상품 재고를 직접 입·출고하는 self-service 진입점(markInbound/markOutbound·INBOUND/OUTBOUND enum 소비).
- **핵심 진입점 파일·라인**: `inventory/controller/SellerInventoryController`(mark-inbound·mark-outbound 2 endpoint)·`InventoryService.markInboundBySeller`(L137)·`markOutboundBySeller`(L160).
- **핵심 SoT 메서드**: `InventoryService.authorizeSellerAccess`(L183·3홉 소유권)·`Inventory.adjustStock`(entity L134·±qty 도메인 primitive 재사용).
- **영향 범위**: production 신설 3·수정 1(InventoryService)·test 신설 1·수정 1(InventoryServiceTest). GlobalExceptionHandler·신규 예외·DDL·Flyway 무변경(기존 예외·enum 재사용).
- **패턴 재사용 SoT·회차**: D-92 Q3 Service 진입부 authorize(4회차·approveBySeller·registerExchangeShipmentBySeller·prepareShipment 계승)·D-105 §2 Q2 옵션 A(base path 없음·메서드 절대경로)·AdminInventoryController 응답 조립 1:1.
- **트랩 주의**: ① LT-02 FK 시드 그래프(user→seller→product→product_variant→inventory) try-finally 필수 ② 3홉 소유권 조회 파급(variantId→productId→sellerId·Inventory seller_id 직접 참조 없음·INV-6) ③ cross-tenant는 ProductVariantNotFoundException(404)으로 미존재와 동일 은닉(code 분기 금지).

### §8 carry-over (백로그)
- **E10 InventoryAdjusted 발행·NotificationInventoryAdjustedHandler**: recipient 산정 정책 확립 후 후속 트랙(γ 계승 해제 시점).
- **Admin markInbound/markOutbound**: 명시 미신설 확정·adjustStock 재사용(백로그에서 제거). Admin 입출고 필요 시 adjustStock(±qty)로 충족.
- **발주·검수 참조 모델 연동**: 실 운영자 시나리오 실증 후 별도 S급 트랙 승격(§9-6·D-105 §1-A γ 기각 근거 계승).

---

## D-113. Track 28 Admin Payment mark-cancelled wrapper — markCancelledByAdmin·PaymentNotFoundException·자동전이 유실 수동 보정 [ACTIVE]

**결정일**: 2026-07-03
**관련**: Track 28 / D-106 §1-A 판단 4·5(markCancelledByAdmin·PaymentNotFoundException 이연 원문)·D-94(Refund 자동 트리거)·D-92 Q3(Admin wrapper 진입부)·D-105 §2 Q2(base path 없음·절대경로)·D-71(PAID→CANCELLED 전이) / 기조 1(운영 용이성)·기조 4(과잉개발 회피)·기조 5(실측 우선)

### §1 결정 사항 (구현 완료 실측)
Refund.COMPLETED 후 `PaymentRefundCompletedHandler` 자동전이(PAID→CANCELLED)가 유실된 경우, 운영자가 동일 도메인 로직을 수동 재실행하는 Admin wrapper를 신설한다. D-106 §1-A 판단 4·5에서 Track 23+로 이연했던 markCancelledByAdmin·PaymentNotFoundException carry-over를 종결한다.

- **markCancelledByAdmin(Long) wrapper**: `PaymentService.markCancelled` 재사용(PaymentService L232~236). 전액환불 가드(L202~207)·CANCELLED 멱등 NO-OP 가드(L198) 상속. 신규 도메인 행위 미신설(기조 4).
- **PENDING 취소 미신설**: PENDING→CANCELLED는 `PaymentStatus.canTransitionTo` 불법 전이. Admin PENDING 강제 종결 시나리오 실측상 부재(스케줄러 만료·PG FAILURE/CANCEL 콜백이 PENDING→FAILED 완전 커버).
- **PaymentNotFoundException 신설 + 404 매핑**: Payment 도메인 404 예외 전무·재사용 후보 없음(기존 3예외 모두 422/409). GlobalExceptionHandler catch-all은 500 → 명시 핸들러 필수.
- **markCancelled 400 트랩 동반 수정**: 기존 markCancelled L200 `IllegalArgumentException`이 GlobalExceptionHandler L81 handleMalformed에 흡수되어 404 아닌 **400 오출력**. PaymentNotFoundException 교체로 404 정상화(버그 수정 동반).
- **request body 무**: path variable 단독(mark-delivered 정합). 취소는 추가 입력값 없음.
- **응답 DTO**: Payment 반환(운영자 취소 결과 확인·기조 1).

**검증**: `./gradlew.bat cleanTest test` 547 tests·failures 0·errors 0·skipped 0·회귀 0. baseline 541 + AdminPaymentControllerIntegrationTest 6(T1~T6) = 547.

### §1-A 옵션 검토 (채택/기각·결정 근거 영구화)

**판단 1 (markCancelledByAdmin wrapper 방식·전액환불 가드 상속)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| ① 전액환불 가드 상속(자동전이 유실 보정) | **채택** | state-machine §1 PAID→CANCELLED = "Refund.COMPLETED" 단일 정의. markCancelledByAdmin은 자동 배선(PaymentRefundCompletedHandler) 실패 시 수동 재실행 wrapper → 가드 상속이 SoT 유일 정합. D-106 β 기각 논리("자동 배선 기존재") 계승. |
| ② 환불 무관 강제 취소(가드 bypass) | 기각 | state-machine §1 미정의·SoT 근거 전무. 환불 없는 PAID→CANCELLED 강제 전이는 도메인 정의 위배(기조 5). |

**판단 2 (PENDING 취소 경로)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| 미신설 | **채택** | PENDING→CANCELLED canTransitionTo 불법·PENDING→FAILED는 스케줄러/PG 콜백 완전 커버·Admin PENDING 종결 시나리오 부재(실측). |
| markFailedByAdmin 동반 | 기각 | state-machine L274 "운영자 수동 보정 권한 없음"·D-94 Q3·D-106 §8 3중 차단·RefundAdjustment(D안) 미존재. 이연 유지(§8). |

**판단 3 (예외 매핑)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| ① PaymentNotFoundException 신설 + 404 핸들러 | **채택** | Payment 404 예외 전무·재사용 후보 없음(3예외 모두 422/409). catch-all 500 → 명시 핸들러 필수. L200 400 트랩 동반 정상화. |
| ② 기존 예외 재사용(Track 27 결정 H 재적용) | 기각 | Track 27 H는 cross-tenant 은닉 맥락(ProductVariantNotFoundException 재사용). Payment 404는 재사용 후보 자체가 부재 → 적용 불가. |

**판단 4 (응답 DTO)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| ① Payment 반환(AdminPaymentMarkCancelledResponse) | **채택** | 취소 결과 상태 확인이 운영자 시나리오 핵심(기조 1). InventoryAdjustResponse from 팩토리 정합. self-invocation L1 캐시 재취득(§3). |
| ② void 재조회 없음 | 기각 | 운영자가 취소 반영 여부 확인 불가·mark-delivered와 달리 상태 전이 결과가 의미 있음. |

**판단 5 (이중 호출 멱등)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| ① 상태 기반 NO-OP 상속(신설 무) | **채택** | markCancelled L198 `if(status==CANCELLED) return` 가드 기존재. 자동↔수동 순서 무관 NO-OP(예외 무). 신규 멱등 예외 불요(기조 4). |
| ② RefundIdempotentNoOpException 유사 신설 | 기각 | L198 가드로 완전 보장·Refund idempotency-key와 도메인 성격 상이·중복. |

### §2 결정 라운드 재진입·재검토 수렴
- **재진입 1 (판단 1 SoT 부재 → Phase 1 실측 게이트)**: markCancelled 전액환불 가드 상속이 Admin 의도인지 SoT 근거 미확정 → 추정 금지(기조 5)로 Phase 1 실측 진입. state-machine §1·D-94·D-106 실측 결과 시나리오 ①(전액환불 보정) 유일 정합 확정. ② 강제취소는 SoT 정의 없음.
- **재진입 2 (판단 3 Track 27 결정 H 재적용 검토)**: 초기 "기존 예외 재사용" 가능성 검토했으나 Phase 1 실측으로 Payment 404 재사용 후보 부재 확인 → 신설 필수로 수렴. Track 27 H(cross-tenant 은닉)와 맥락 상이.
- **재진입 3 (판단 4 반환 방식 정정)**: 초기 "재조회 필요" 단언을 기조 5 위반 소지로 정정(더티체킹 반영 가능성 미실측)·Phase 2 검증 대상 격하. 구현 실측 결과 self-invocation(프록시 미경유·단일 영속성 컨텍스트)으로 findById가 L1 캐시 hit·markCancelled 변이 인스턴스 반환 확정.
- **버그 동반 발견**: Phase 1 정찰 2에서 markCancelled L200 IllegalArgumentException 400 오출력 트랩 발견 → PaymentNotFoundException 교체로 404 정상화 동반(별건 fix 트랙 분리 대신 흡수·Track 27 백로그 F 흡수 선례 정합).

### §3 구현 산출 (실측 라인)
**신설 3**:
- `payment/exception/PaymentNotFoundException.java`(12줄·RuntimeException 상속 L7·`PaymentNotFoundException(String)` L9·RefundNotFoundException 1:1).
- `payment/controller/response/AdminPaymentMarkCancelledResponse.java`(19줄·record `(String paymentPublicId, PaymentStatus status)` L12~14·`from(String, Payment)` L16).
- `payment/controller/AdminPaymentController.java`(60줄·`@PostMapping("/api/v1/admin/payments/{paymentPublicId}/mark-cancelled")` L48·base path 없음 절대경로·D-105 §2 Q2 옵션 A).

**수정 2**:
- `payment/service/PaymentService.java`(+27/−2·287줄): `markCancelled` L200 IllegalArgumentException → PaymentNotFoundException 교체(400 트랩 수정)·`markCancelledByAdmin(Long)` wrapper L232~236 신설(L233 markCancelled 위임·L234~235 findById 재취득). markCancelled void 유지(L198).
- `common/web/GlobalExceptionHandler.java`(+9·252줄): `CODE_PAYMENT_NOT_FOUND` L69·`handlePaymentNotFound` L137~142(404·PAYMENT_NOT_FOUND·ProductVariantNotFoundException 블록 템플릿)·import.

**테스트 신설 1**:
- `test/payment/AdminPaymentControllerIntegrationTest.java`(223줄·T1 401·T2 400·T3 404·T4 전액→CANCELLED·T5 멱등 NO-OP·T6 부분 NO-OP·LT-02 try-finally·시드 order→payment(PAID)→refund(COMPLETED)).

### §4 핵심 메서드
- **`AdminPaymentController` mark-cancelled(L48)**: `adminActorResolver.resolve`(L53·검증만·D-93 Q3) → `paymentRepository.findByPublicId.orElseThrow(PaymentNotFoundException)`(L54~55) → `paymentService.markCancelledByAdmin(payment.getId())`(L57) → `AdminPaymentMarkCancelledResponse.from`(L58).
- **`PaymentService.markCancelledByAdmin`(L232~236)**: `markCancelled(paymentId)`(L233·도메인 위임·전이/NO-OP 판정) → `paymentRepository.findById`(L234·self-invocation L1 캐시 hit·변이 인스턴스 반환). self-invocation은 프록시 미경유로 markCancelled @Transactional 재적용 안 됨 → 단일 트랜잭션·단일 영속성 컨텍스트.

### §진입점 카드
- **목적**: Refund.COMPLETED 자동전이(PaymentRefundCompletedHandler) 유실 시 운영자 수동 결제 취소 보정(PAID→CANCELLED).
- **핵심 진입점 파일·라인**: `payment/controller/AdminPaymentController`(mark-cancelled·L48)·`PaymentService.markCancelledByAdmin`(L232~236).
- **핵심 SoT 메서드**: `PaymentService.markCancelled`(L198·전이·전액환불 가드 L202~207·CANCELLED 멱등 가드 L198)·`Payment.cancel`(PAID→CANCELLED 도메인 primitive).
- **영향 범위**: production 신설 3·수정 2(PaymentService·GlobalExceptionHandler)·test 신설 1. Payment 엔티티·PaymentStatus·DDL·Flyway 무변경.
- **패턴 재사용 SoT·회차**: D-92 Q3 Admin wrapper 진입부(AdminActorResolver 6회차)·D-105 §2 Q2 base path 없음 절대경로·AdminRefundController initiateByAdmin 위임 패턴·ProductVariantNotFoundException 404 핸들러 템플릿.
- **트랩 주의**: ① markCancelledByAdmin 재취득 findById가 이론상 PaymentNotFoundException throw 가능하나 동일 TX·직전 markCancelled 조회 성공으로 실질 무영향 ② markCancelled 멱등 NO-OP(L198)·전액환불 미충족 NO-OP(L202)는 예외 없이 return → Admin endpoint도 200 반환(T5·T6) ③ LT-02 시드 order→payment(PAID)→refund(COMPLETED) try-finally 필수 ④ self-invocation 반환은 프록시 미경유 전제(markCancelled를 외부 주입 호출로 바꾸면 트랜잭션 분리 파급).

### §8 carry-over
- **markFailedByAdmin (Refund 수동 보정)**: state-machine L274·D-94 Q3·D-106 §8 3중 차단·RefundAdjustment(D안) 미존재. Track 28 미흡수·이연 유지. RefundAdjustment 설계 트랙(S급) 선행 필요.
- **RefundAdjustment D안 설계 트랙**: PG 운영 데이터 누적 후 진입(state-machine §2 footer L769). markFailedByAdmin·EXCHANGE 차액 환불의 공통 선결 과제.
- 본 트랙 자체 이월 무 — D-106 §1-A 판단 4·5 carry-over(markCancelledByAdmin·PaymentNotFoundException) 전건 종결.

### §특기 SoT 신규 명문화 (박제 동반)
state-machine.md §1 Payment PAID→CANCELLED 절에 Admin 수동 보정 경로 명문화 필요: "PAID→CANCELLED는 (a) Refund.COMPLETED 자동전이(PaymentRefundCompletedHandler) (b) 자동전이 유실 시 운영자 수동 markCancelledByAdmin 재실행 2경로. 둘 다 전액환불 가드·CANCELLED 멱등 가드 공유." 현재 SoT는 (a)만 정의·(b) 공백 → Track 28 첫 박제.

---

## D-114: RefundAdjustment D안 설계 확정 (Track 29·설계 전용 트랙·구현 게이트 분리) [확정 2026-07-03]

**성격**: 설계 전용 트랙 특수 케이스 — 구현 없이 설계 결정만 박제. 구현은 §7 구현 게이트 도달 시 별도 트랙 진입. "구현 후 실측 박제" 원칙(D-104~D-113)의 명시적 예외이며, 예외 사유 = 구현 게이트(D-24 §후속 decisions.md:769 "PG 운영 데이터 누적 후 진입") 미도달 상태에서 markFailedByAdmin·EXCHANGE 차액환불 2개 백로그의 공통 선결인 설계 확정이 선행 필요.

### §1 결정 사항 (판단 1~5 + G-1·G-2)

**판단 1 — 데이터 구조 = α 신규 테이블 (append-only 보정 원장)**
- RefundAdjustment 신규 테이블 신설. 보정 = 새 행 누적. 기존 Refund 행은 읽기전용 유지·불변식 무손상.

**판단 2 — markFailedByAdmin 연동 = α RefundAdjustment 경유**
- Refund 직접 보정 메서드(markFailedByAdmin) 신설하지 않음. 수동 보정은 Adjustment 행 생성으로 표현.
- 결과: 백로그 항목 "markFailedByAdmin"의 명칭·형태 소멸 → "Adjustment 생성 Admin 진입점"(가칭 initiateAdjustmentByAdmin)으로 재정의. 백로그 갱신 대상.

**판단 3 — 상태머신 파급 = α 무파급**
- Refund·Payment·Claim 기존 전이 규칙 무수정. SoT 개정은 state-machine.md L274 "운영자 수동 보정 권한 없음 — 후속 트랙(D안 RefundAdjustment) 검토 사항" 1행을 "운영자 수동 보정은 RefundAdjustment(D-114) 경유" 취지로 갱신하는 문서 1행뿐 (구현 게이트 통과 트랙에서 코드와 동시 갱신).

**판단 4 — 구현 게이트 = α 정성 트리거 2건 중 1건 도달 시**
- 트리거 ①: 실 PG 연동 후 자동전이 유실·콜백 불일치 실사례 1건 발생
- 트리거 ②: EXCHANGE 차액환불(refundAmount>0) 실요구 발생
- 도달 전 구현 착수 금지. 정량 기준(N개월·M건)은 임계값 자체가 추측이라 기각(기조 5).

**판단 5 — EXCHANGE 차액환불 = γ 호환성 판정만·상세 설계 별도 트랙**
- 호환성 판정: 차액환불 = Refund 신규 행 생성 경로(D-98 Q2 "refundAmount>0 흐름은 후속 트랙 신설")이며 기존 환불의 보정이 아님 → RefundAdjustment 비의존. 본 설계는 차액환불 트랙을 차단하지 않음.

**G-1 — 자체 상태 = α 무상태 (append-only 원장·상태 컬럼 없음)**
- 보정 행 = 즉시 확정된 사실 기록. 오기입 정정은 역-Adjustment 새 행으로 표현.
- 승인 워크플로(PENDING→APPROVED) 기각: 단일 운영자 체제(기조 1)와 불일치·기조 4 과잉.

**G-2 — 필드 골격 (구현 게이트 트랙에서 실측 재확정 전제의 설계 사양)**
- id: Long (PK)
- public_id: CHAR·prefix "rfa"·@JdbcTypeCode 적용 (LT-01)·AbstractPublicIdFullAuditableEntity 상속 (Refund와 동일 계보·소프트삭제 미사용)
- refund_id: Long·updatable=false (보정 대상 Refund 참조·필수)
- claim_id: Long·updatable=false (Claim Aggregate 정합·조회 편의)
- adjustment_type: Enum @Enumerated(STRING)·값 2개 = MANUAL_FAIL(PENDING 잔류 Refund를 실패로 수동 판정)·MANUAL_COMPLETE(PENDING 잔류 Refund를 완료로 수동 판정·pg_refund_id 수기 입력 동반 — RFN-1 정합 방식은 구현 게이트 트랙 결정 라운드에서 확정)
- amount: Long·updatable=false
- reason: String·필수 (운영자 보정 사유)
- adjusted_by: String (AdminActorResolver 산출·D-92 계보)
- Aggregate 소속: Claim Aggregate 포함 (aggregate-boundary.md:74 Refund와 동일 — Refund 보정 원장이므로 생명주기 공유)

### §1-A 트랙 범위 옵션 검토 (채택/기각 근거)

| 판단 | 채택 | 기각 옵션·근거 |
|---|---|---|
| 1 | α 신규 테이블 | β Refund 확장: RFN-2(COMPLETED·FAILED 불가역·재시도=새 행·invariants.md §2.13.1) 정면 충돌. γ 이벤트 소싱: 전 6개 Aggregate 상태기반·선례 전무·기조 4 |
| 2 | α Adjustment 경유 | β 직접 보정: 3중 차단(state-machine.md:274·D-94 Q3 decisions.md:3772·소스 메서드 부재) 중 ①② SoT 개정 강제·판단 1 α 모순. γ 병행: 이중 쓰기 정합 리스크·기조 4 |
| 3 | α 무파급 | β Refund 재설계: 불가역 원칙 파괴·handler 체인 회귀 위험. γ 3종 재설계: S급 초과 범위·기조 4 |
| 4 | α 정성 트리거 | β 정량 기준: 임계값 추측·기조 5 위반. γ 즉시 구현: D-24 §후속(decisions.md:769) 원문 위배 |
| 5 | γ 호환성 판정만 | β 동반 상세 설계: 범위 급증·차액환불은 보정 아닌 신규 행 경로로 성격 상이. α 완전 제외: 공통 선결 지위(D-113 §8) 무시·재작업 위험 |
| G-1 | α 무상태 | β 2상태(APPLIED/VOIDED): VOIDED도 역-Adjustment 새 행으로 표현 가능·중복. γ 승인 워크플로: 단일 운영자 불일치·기조 4 |

**핵심 설계 근거 (정찰 실측)**: state-machine.md 전 6개 Aggregate(Order·OrderItem·Payment·Claim·Seller·Refund) 중 종결 상태(CANCELLED·COMPLETED·FAILED·REJECTED)를 이전 상태로 되돌리는 역전이 선례 전무 (grep 보정|역전이|롤백|되돌|reverse|correction 실측·2026-07-03). 유일 "수동 보정" 선례 = Payment markCancelledByAdmin(D-113)이며 이는 순방향 전이(PAID→CANCELLED) 수동 재실행이지 역전이 아님. OrderItem ClaimRejected 복귀(state-machine.md:93~98)는 *_REQUESTED 한정·종결 상태 역전이 아님. → append-only 원장(α)만 전 아키텍처 패턴과 정합.

### §2 결정 라운드 재진입

재진입 1건: G-1·G-2(엔티티 골격)가 판단 1~5 1차 라운드에 미포함 → 판단 3 α("Adjustment 자체 상태는 설계 라운드에서 확정") 잔여로 식별·2차 라운드에서 확정. 판단 1~5 자체의 재진입·번복 없음.

### §3 구현 산출

없음 — 설계 전용 트랙. production·test 코드 0건·DDL·Flyway 0건. docs 단독 커밋.

### §진입점 카드
① 목적: PG 콜백이 못 미치는 Refund 수동 보정을 append-only 원장(RefundAdjustment)으로 수행하는 설계 확정·구현 게이트 조건 명문화
② 핵심 진입점: 본 D-114 (구현물 없음·구현 게이트 트랙에서 AdminRefundAdjustmentController 신설 예정)
③ 핵심 SoT: state-machine.md:274 (개정 예정 1행)·state-machine.md:236~276 (Refund §8)·invariants.md §2.13.1 (RFN-1~3)·aggregate-boundary.md:74 (Claim Aggregate)·D-24 §후속 (decisions.md:769)·D-94 Q3 (decisions.md:3772)·D-98 Q2 (decisions.md:4519~4524)
④ 영향 범위: 설계 시점 0 (코드 무변경). 구현 게이트 통과 시 — 신규 테이블 1·Entity 1·Admin endpoint 1·state-machine.md L274 1행 개정
⑤ 패턴 재사용: AbstractPublicIdFullAuditableEntity 계보(LT-01 자동 적용)·AdminActorResolver(D-92·7회차 예정)·Admin wrapper 절대경로(D-105 §2 Q2)·404 핸들러 템플릿(D-113 계보)
⑥ 트랩 주의: LT-01(신규 Entity public_id — 상속으로 자동 해소)·LT-02(구현 게이트 트랙 통합 테스트 시드 try-finally)·LT-04·LT-05(보정 이벤트 신설 시 핸들러 skip 가드·형제 순서)·MANUAL_COMPLETE의 RFN-1(pg_refund_id 필수) 정합 방식 미결 — 구현 게이트 트랙 결정 라운드 필수 안건

### §8 carry-over

- 구현 게이트 트랙 (판단 4 트리거 도달 시 진입): RefundAdjustment DDL·Flyway·Entity·Admin 진입점(가칭 initiateAdjustmentByAdmin) 구현·state-machine.md L274 개정·MANUAL_COMPLETE × RFN-1 정합 방식 결정
- EXCHANGE 차액환불 상세 설계 트랙 (판단 5 γ): Refund 신규 행(refundAmount>0) 흐름 신설·D-98 Q2 후속·RefundAdjustment 비의존 확인 완료
- 백로그 재정의: "markFailedByAdmin" 항목 → "RefundAdjustment Admin 진입점"으로 명칭·형태 변경 (판단 2 α 귀결)
- 인계 문서 오기 정정 기록: "state-machine.md §2 footer L769" 표기는 오기 — state-machine.md는 318줄·해당 원문 위치 = decisions.md:769 (D-24 §후속). 이후 참조 시 정정된 위치 사용.

### §특기

- 설계 전용 트랙 선례 1호: 구현 없이 설계 결정 박제 + 구현 게이트 조건 명문. 박제 시점 원칙(구현 후 실측)의 예외 요건 = 구현 게이트 미도달 + 후속 백로그 공통 선결.
- 결정 근거 영구화 원칙 11회차 (D-104~D-114).

---

## D-115. Track 30 EXCHANGE 차액환불 구현 — ExchangeShipmentRefundHandler(DeliveryStarted 구독)·Claim.refundAmount·tryCompleteExchange 3조건 수렴·@Version 최초 도입 [ACTIVE]

**결정일**: 2026-07-03
**관련**: Track 30 / D-114 판단 5 γ·§8(EXCHANGE 차액환불 = Refund 신규 행 경로·RefundAdjustment 비의존·별도 트랙)·D-98 Q2(refundAmount>0 흐름 후속 트랙 신설 원문)·D-98 Q5(ExchangeDeliveryCompletedHandler 교환 배송 완료 종결)·D-94(ClaimApproved→Refund 자동 트리거)·D-94 Q6(initiate 멱등 게이트)·D-96 Q3(recordRefundFailed 실패 보상)·D-29(save→publish·Aggregate domainEvents 축적)·D-75(AFTER_COMMIT+REQUIRES_NEW)·D-71(PAID→CANCELLED 전액환불 가드·부분환불 NO-OP)·D-101 §4(Inventory @Version 미도입 선례) / 기조 1(운영 용이성)·기조 4(과잉개발 회피)·기조 5(실측 우선)

### §1 결정 사항 (구현 완료 실측)
EXCHANGE 클레임 승인 시 구매자와 합의된 차액(refundAmount>0)을 교환품 출고 시점에 부분환불로 자동 개시하고, 수거·교환배송완료·차액환불완료 3사실이 순서 무관하게 수렴할 때 Claim을 COMPLETED로 종결한다. D-114 판단 5 γ·§8이 "RefundAdjustment 비의존·별도 트랙"으로 이월한 EXCHANGE 차액환불(D-98 Q2 후속) 백로그를 종결한다. refundAmount==0(차액 없음) 경로는 기존 동작 100% 보존(호환성 절대).

- **결정1 (Refund 생성 트리거 = DeliveryStarted 이벤트 구독·γ)**: 교환 출고 시점에 발행되는 E4 DeliveryStarted를 구독하는 `ExchangeShipmentRefundHandler`(refund/handler)를 신설한다. `ClaimApprovedHandler`(CANCEL 승인 시)·`ClaimPickedUpHandler`(RETURN 수거 시) 자동환불 2핸들러와 1:1 대칭인 3번째 슬롯(AFTER_COMMIT+REQUIRES_NEW·D-75). DeliveryStarted가 claimId를 운반하지 않아 deliveryId로 Delivery 재조회 → `delivery.getClaimId()` 라우팅. 실패 시 `recordRefundFailed` 상속(best-effort 아님).
- **결정2 (차액 저장 = Claim.refundAmount 필드·α)**: 차액은 승인 시점 구매자 합의(=Claim 결정 결과)이므로 `Claim.refundAmount`(nullable·승인 시 확정)에 저장한다. 실행 결과(PG 요청액)인 `Refund.amount`와 의미를 분리한다. `Claim.approve(processedAt, refundAmount)` 2-arg로 변경·`hasRefundDifference()`(refundAmount>0) 헬퍼 추가.
- **결정3 (종결 판정 = ClaimService.tryCompleteExchange 3조건 수렴·β)**: 종결 판정 단일 수렴점을 신설한다. (1) 수거 확인(pickedUpAt≠null) ∧ (2) 교환 배송 완료(OrderItem.EXCHANGED) ∧ (3) 차액 발생 시 Refund.COMPLETED. 이벤트 도달 순서 무관·멱등(이미 COMPLETED면 no-op). refundAmount==0이면 조건(3) 자동 통과 → 현행 동작 동치. 트랜잭션 전파 REQUIRED(호출 핸들러 REQUIRES_NEW에 합류·같은 트랜잭션의 OrderItem.EXCHANGED 관측 필수·REQUIRES_NEW 변경 시 영구 미수렴 트랩).
- **결정4 (동시성 = Claim @Version 낙관적 락·α)**: 두 비동기 이벤트(DeliveryCompleted·RefundCompleted)의 종결 경쟁을 `@Version`으로 방어한다(프로젝트 최초 @Version 도입). 후행 트랜잭션 OptimisticLockException→롤백→publish 미발생으로 ClaimCompleted 중복 차단. status==APPROVED 도메인 가드(비즈니스 불변식)와 @Version(동시성)을 2계층 분리·Aggregate·D-29 save→publish 패턴 무변경. version 컬럼은 refund_amount와 동일 V9 마이그레이션에 합류.
- **무변경**: Payment 전이(D-71·부분환불 시 markCancelled NO-OP·PaymentRefundCompletedHandler 무수정)·RFN invariant(RFN-1~3 상속·신설 0·existsByClaimIdAndStatus 파생 쿼리만 추가).

**검증**: `./gradlew.bat test` 550 tests·failures 0·errors 0·skipped 0·118 suites·회귀 0. baseline 547 + ClaimExchangeIntegrationTest 신규 3(T1 환불先·배송後·T2 refundAmount==0 회귀·T3 배송先·환불後 단일 수렴) = 550.

### §1-A 옵션 검토 (채택/기각·결정 근거 영구화)

**판단 1 (Refund 생성 트리거 시점)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α 승인 시점(approve 내 initiate) | 기각 | 수거·출고 전 환불 = 물품 미회수 상태 환불 리스크. |
| β 수거 시점(ClaimPickedUpHandler 확장) | 기각 | 수거는 출고 선행 → 차액환불이 교환 출고보다 앞서 완료 가능·state-machine §8 순서(교환출고→Refund.COMPLETED→Claim.COMPLETED) 왜곡. |
| γ 이벤트(DeliveryStarted 구독·핸들러 신설) | **채택** | 초기 직접호출(registerExchangeShipment 내 initiate)안에서 재선회 — DeliveryService→RefundService 신규 의존 회피·형제 핸들러(D-94/D-98) 1:1 대칭·Refund가 비동기 Aggregate(initiate→PENDING→webhook→COMPLETED)라 Delivery와 atomic 불요·recordRefundFailed 복구 전략 상속. |

**판단 2 (차액 저장 위치)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α Claim.refundAmount 필드 | **채택** | 차액 = 승인 시점 구매자 합의 = Claim 결정 결과. Refund.amount(실행 결과)와 의미 분리. 단일 값이라 별도 VO는 과설계(기조 4). |
| β 출고 API 파라미터 | 기각 | 합의 근거 미영속·Admin 임의 입력 여지. |
| γ Order 스냅샷 자동 산정 | 기각 | 차액은 운영자 재량·쿠폰·배송비 개입 → Order 계산 불가·MVP 밖. |

**판단 3 (종결 판정 방식)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α 기존 가드 단순 해제 | 기각 | Delivery 완료·차액환불 완료 미판정으로 불충분. |
| β tryCompleteExchange 수렴 메서드 | **채택** | 종결 판정 단일 수렴점·이벤트 순서 무관·멱등·refundAmount==0 시 조건(3) 자동 통과로 현행 동치(호환). |
| γ 별도 종결 핸들러 신설 | 기각 | 판정 분산·형제 핸들러 증가(LT-05 순서 리스크). |

**판단 4 (동시성 제어)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α Claim @Version 낙관적 락 | **채택** | Aggregate·D-29 save→publish 패턴 무변경·후행 TX 롤백으로 ClaimCompleted 중복 차단·status 가드(불변식)와 2계층 분리. |
| β 조건부 벌크 UPDATE | 기각 | Aggregate 우회·이벤트 생성 책임이 Service로 이동·D-29(Aggregate가 domainEvents 축적) 패턴 예외 발생. |
| γ 비관적 락 | 기각 | 락 경합·데드락 운영비용 > 이익. 2개 비동기 이벤트 경쟁 수준엔 과함. |

### §2 결정 라운드 재진입·재검토 수렴
- **재진입 1 (판단 1 배선 재선회)**: 초기 직접호출(registerExchangeShipment→RefundService.initiate) α안 → 이벤트(γ)로 재선회. 근거 = `ClaimApprovedHandler`·`ClaimPickedUpHandler` 실측(이벤트 트리거 선례·recordRefundFailed 복구 전략 실재 확인)·신규 도메인 간 의존 회피.
- **재진입 2 (외부 검토 1차 Q2 race 지적 수용)**: 두 이벤트 부수효과 중복(ClaimCompleted 재발행) 지적 → 재검토에서 동시성 방식 α(@Version) > γ(비관적) > β(벌크 UPDATE) 확정. β는 D-29 패턴 예외 발생으로 최후순위.
- **정찰·실측 게이트**: 구조 정찰 2회(골격 → 미실측 4건 보강)·Phase 1 사전 실측 1회(배선 리스크 R1~R3). ★ 3항목(DeliveryStarted payload에 claimId 부재·recordRefundFailed 신규 (Claim) overload 필요·교환배송완료 신호=OrderItem EXCHANGED) read 실측으로 추정 배제(기조 5).

### §3 구현 산출 (실측 라인)
**신설 3**:
- `db/migration/V9__add_claim_refund_amount_and_version.sql`: `refund_amount BIGINT NULL`(L10·AFTER processed_at·refund.amount BIGINT 정합)·`version BIGINT NOT NULL DEFAULT 0`(L11·AFTER previous_order_item_status·기존 행 0건이나 재적용 안전). 롤백 보상 SQL 주석 동반.
- `refund/handler/ExchangeShipmentRefundHandler.java`(86줄): `handle(DeliveryStarted)`(L51)·claim_id NULL skip(L57·일반 배송)·Claim 재조회(L62)·type≠EXCHANGE skip·`hasRefundDifference()` 가드(L73)·`refundService.initiate(claimId, refundAmount)`(L80)·catch→`recordRefundFailed(claim)`(L83). AFTER_COMMIT+REQUIRES_NEW.
- `claim/controller/request/ClaimApproveRequest.java`(11줄): `record (Long refundAmount)`·Bean Validation 무(음수는 Claim.approve 도메인 검증→400·AdminRefundInitiateRequest 정합). `claim.controller.request` 패키지(기존 컨벤션 답습·`dto` 패키지 신설 안 함).

**수정 production 8**:
- `claim/entity/Claim.java`(254줄): `refundAmount` 필드(L70)·`@Version version`(L80~82)·`approve(LocalDateTime, Long)`(L173·음수 검증·refundAmount 세팅)·`hasRefundDifference()`(L189).
- `claim/service/ClaimService.java`(471줄): `RefundRepository` 주입(L62·L70)·`approve(Long, LocalDateTime, Long)`(L158·비EXCHANGE에 refundAmount 실림 시 warn+무시)·`approveBySeller`(L204·4-arg)·`approveByAdmin`(L240·3-arg)·`tryCompleteExchange`(L330·@Transactional REQUIRED·멱등 L333·type L337·조건1 수거 L342·조건2 EXCHANGED L350·조건3 Refund.COMPLETED L356·markCompleted+publish L362).
- `claim/controller/SellerClaimController.java`: `@RequestBody(required=false) ClaimApproveRequest`·approveBySeller 4-arg 전파(body 부재 시 null).
- `claim/controller/AdminClaimController.java`: 동일 패턴·approveByAdmin 3-arg 전파.
- `refund/repository/RefundRepository.java`: `existsByClaimIdAndStatus(Long, RefundStatus)`(L39·파생 쿼리·바인딩 파라미터).
- `notification/service/NotificationService.java`: `recordRefundFailed(Claim)` overload(L168·기존 (ClaimApproved)·(ClaimPickedUp) 대칭·private (Long,String) 위임).
- `claim/handler/ClaimRefundCompletedHandler.java`: EXCHANGE 분기 `hasRefundDifference()`(L54)→`tryCompleteExchange`(L56)·OLE catch(L57)·차액 없음 시 기존 미전이 유지.
- `claim/handler/ExchangeDeliveryCompletedHandler.java`: `markCompleted`→`tryCompleteExchange`(L110)·OLE catch(L111·L107 소비 순서 OrderItem EXCHANGED→종결 유지).

**수정 test 7 + docs 1**:
- `ClaimExchangeIntegrationTest`(+T1/T2/T3·MockMvc webhook·seedPayment·3→6 tests)·`ClaimServiceTest`(@Mock RefundRepository·approve 3-arg)·`ClaimServiceConfirmPickupTest`·`ClaimIntegrationTest`·`ClaimReturnIntegrationTest`(approve null)·`SellerClaimControllerTest`·`AdminClaimControllerTest`(Mockito matcher +1).
- `docs/architecture-baseline/state-machine.md`: §2 L57 EXCHANGE COMPLETED 조건에 "(차액 발생 시) Refund.status=COMPLETED (D-115)" 추가·§8 L270 연동 순서 정렬(D-115).

### §4 핵심 메서드
- **`ClaimService.tryCompleteExchange`(L330·@Transactional REQUIRED)**: findById→COMPLETED 멱등 return(L333)→type≠EXCHANGE skip(L337)→조건(1) pickedUpAt≠null(L342)→조건(2) OrderItem.EXCHANGED(L350·orderItemRepository 재조회)→조건(3) `hasRefundDifference() && !existsByClaimIdAndStatus(COMPLETED)` 시 미수렴 return(L356)→`markCompleted`+ClaimCompleted publish(L362). REQUIRED 전파는 DeliveryCompleted 경로에서 같은 TX의 미커밋 OrderItem.EXCHANGED를 관측하기 위한 필수 조건(REQUIRES_NEW 전환 시 READ_COMMITTED로 미관측→영구 미수렴 트랩).
- **`ExchangeShipmentRefundHandler.handle`(L51·AFTER_COMMIT+REQUIRES_NEW)**: Delivery 재조회→claim_id NULL skip(일반 배송)→Claim 재조회→type/hasRefundDifference 가드→`initiate(claimId, refundAmount)`→catch RuntimeException→`recordRefundFailed(claim)`.

### §진입점 카드
① **목적**: EXCHANGE 차액환불(refundAmount>0) 부분환불 흐름 — 교환 출고 시 Refund 개시·수거·배송·환불 3사실 수렴 시 Claim 종결.
② **핵심 진입점**: `ExchangeShipmentRefundHandler`(DeliveryStarted 구독·L51)·`ClaimService.tryCompleteExchange`(L330).
③ **핵심 SoT 메서드**: `RefundService.initiate`(재사용·PENDING 생성·멱등 게이트 D-94 Q6)·`Claim.hasRefundDifference`(L189)·`Claim.approve`(2-arg·L173)·`RefundRepository.existsByClaimIdAndStatus`(L39).
④ **영향 범위**: 신설 3(V9 마이그레이션·ExchangeShipmentRefundHandler·ClaimApproveRequest)·수정 production 8(Claim·ClaimService·컨트롤러 2·RefundRepository·NotificationService·핸들러 2)·test 7·docs 1(state-machine §2·§8). ClaimStatus·RefundStatus·ClaimType 무변경.
⑤ **패턴 재사용**: ClaimApprovedHandler/ClaimPickedUpHandler 자동환불 트리거(3번째 슬롯·AFTER_COMMIT+REQUIRES_NEW·D-75)·recordRefundFailed 실패 보상(D-96 Q3)·Actor wrapper(D-92·approveBySeller/ByAdmin)·D-29 save→publish.
⑥ **트랩 주의**: ① @Version 최초 도입 — 마이그레이션 `NOT NULL DEFAULT 0` 필수(기존 행 0값 보장) ② tryCompleteExchange 전파 REQUIRED 고정 — REQUIRES_NEW 전환 시 DeliveryCompleted 경로 영구 미수렴 ③ OptimisticLockException은 핸들러 catch로 흡수(정상 경쟁·재처리 불요) ④ refundAmount==0 호환 절대 — 조건(3) 자동 통과·Refund 미생성 회귀 테스트(T2) 상시 유지.

### §8 carry-over
- **문서-코드 정합 별건(백로그 등재)**: state-machine.md §2 "교환품 발송 완료" 표현 vs 실코드 종결 트리거 = DeliveryCompleted(배달 완료). Track 30 무수정(§2에 차액환불 조건만 추가)·정합 이슈는 백로그 등재만.
- **recordRefundFailed 시그니처 일원화 여지**: (ClaimApproved)·(ClaimPickedUp)·(Claim) 3 overload 공존 — 향후 실패 알림 시그니처 통합 검토 여지(현재 각 호출부 보유 객체 상이로 분리 유지).
- **@Version 최초 도입 선례**: 향후 타 Aggregate 동시성 요구 시 본 트랙이 선례(Inventory는 D-101 §4에서 명시적 미도입). RefundAdjustment(D-114) 구현 게이트 트랙 등에서 재사용 가능.

### §특기
- D-114 판단 5 γ("EXCHANGE 차액환불 = RefundAdjustment 비의존·별도 트랙") 귀결 구현 — D-114 §8 carry-over 종결.
- 결정 근거 영구화 원칙 12회차(D-104~D-115). 프로젝트 최초 JPA @Version 낙관적 락 적용.

---

## D-116. Track 31 Spring Security 정식 도입 (stub 헤더 → SecurityContext 배선) [ACTIVE]

**결정일**: 2026-07-03
**관련**: D-93 Q11(X-*-Id stub 3종 일괄 대체) / D-115(@Version 선례 무관) / 기조 1·2·4·5

### §1 결정 사항 (구현 완료 실측)
stub 헤더 인증(X-Seller-Id·X-Admin-Id·X-Buyer-Id 3종)을 Spring Security로 정식 대체한다. `Authorization: Stub role:id` 단일 헤더 → Filter → Provider → SecurityContext → ActorResolver 배선으로 전환한다. Phase 1(골격) + Phase 2+3(배선 전환·atomic change set) 2단계로 구현·머지 완료했다.

- **결정1 (범위)**: Security 골격 + Stub 인증 파이프라인. 자격증명·로그인·JWT·Session·Password는 후속 트랙 분리.
- **결정2 (인가 모델)**: 경로 기반 SecurityFilterChain(hasRole). claims만 method+path 세분(구체 규칙 우선·first-match).
- **결정3 (authority)**: ROLE_ 프리픽스 표준(hasRole 자동 매핑).
- **결정4 (테스트)**: Authorization 헤더 빌더 헬퍼(AuthHeaders·실 필터 통과 end-to-end).
- **결정5 (프로파일 게이팅)**: Stub 인증 `@Profile("!prod")` 전용. prod는 permitAll 체인 별도(현 보안 무 동작 보존·기본 잠금 차단).
- **결정6 (anyRequest)**: `authenticated()` fail-closed(secure-by-default). 규칙 없는 미지 경로 401 차단.
- **무변경**: 소유권 authorize 3곳(ClaimService:451·InventoryService:183·OrderShippingService:71) — hasRole(coarse)와 독립·상보. principal 출처만 헤더→SecurityContext 전환.

**검증**: 555 tests PASS·회귀 0·hasRole 활성 검증(@SpringBootTest 13건 실 SecurityConfig 로드).

### §1-A 옵션 검토 (채택/기각·결정 근거 영구화)

**판단 1 (트랙 범위)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α 설계 트랙 종결(구현 0) | 기각 | 대체 표면 안 닫힘. |
| β seam 통일 + Security 골격 | **채택** | 외부 검토로 SecurityContext seam·AuthenticationProvider 분리·@Profile 게이팅 추가하여 구체화. |
| γ 풀 구현(자격증명 V10·로그인·18파일) | 기각 | 자격증명 스키마 부재로 인증 체계 신설까지 끌려옴·MVP blast radius 과대. |

**판단 2 (인가 모델)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α 경로 기반 SecurityFilterChain | **채택** | URL이 이미 /seller·/admin·/orders 역할 분리·설정 1곳 집중. |
| β @PreAuthorize 메서드 기반 | 기각 | 어노테이션 컨트롤러 산재·stub 산재 문제 형태만 재현. |

- 실측 예외: /api/v1/claims/** Buyer+Seller 혼재 → method+path 세분 규칙으로 해소(β 불요).

**판단 3 (authority 표기)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α ROLE_ 프리픽스 표준 | **채택** | hasRole 자동 매핑·JWT 전환 시 규약 불변. |
| β 커스텀 authority | 기각 | 표준 우회 이득 0. |

**판단 4 (테스트 헬퍼)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α RequestPostProcessor(SecurityContext 직접 주입) | 기각 | Stub 필터 우회·필터 자체 미검증. |
| β Authorization 헤더 빌더 | **채택** | 필터·Provider·Resolver 전 경로 통과·JWT 시 헬퍼 내부만 교체. |

**판단 5 (구현 분할)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α 단일 프롬프트 일괄 | 기각 | 실패 원인 격리 불가. |
| β Phase 분할(각 Phase green 게이트) | **채택** | 회귀 격리·기조 5 실측 게이트. |

**판단 6 (Phase 2·3 통합 여부)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α Phase 2+3 통합(atomic change set) | **채택** | header fallback 병존 = 두 인증 경로 동시 운영·목표 미달성. 중간 상태 배선 미완이라 단일 변경 집합 필수. |
| β fallback 병존·제거 이연 | 기각 | 데드코드 경로 양산·실 배선 미검증. |

**판단 7 (Resolver 전환 방식)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α 인터페이스 유지·구현체 내부만 SecurityContext 조회 | **채택** | 호출부·mock 무변경·인증 구현이 웹 계층으로 누출 안 됨·DDD/DIP 정합. |
| β 컨트롤러가 SecurityContext 직접 조회/@AuthenticationPrincipal | 기각 | seam 상실·Security API가 컨트롤러 누출. |

**판단 8 (@WebMvcTest 6건 인가 활성 처리)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| (a) addFilters=false 유지 | **채택** | 슬라이스는 @Profile SecurityConfig 미로드·hasRole 검증 불가. 억지 @Import는 과결합. 실 인가는 @SpringBootTest 13건이 검증. |
| (b) 우회 해제+@Import | 기각 | 슬라이스 과결합. |

**판단 9 (401 테스트 처리)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α 유지·의미 전환(주체 resolver→필터) | **채택** | 인증 거부 계약 유효·안전망 보존. |
| β 제거 | 기각 | 회귀 방어 손실. |

**판단 10 (헬퍼 형태)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α static 유틸 AuthHeaders | **채택** | 상속 무관·공유 base 미발견·18파일 호출 가능. |
| β base class 상속 | 기각 | base 신설·18파일 상속 강제·blast radius↑. |

**판단 11 (prod 기본 잠금 처리)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| (A) prod permitAll 체인 추가 | **채택** | 현 보안 무 동작 보존·기본 잠금만 차단·라이브 트랩 선제. |
| (B) 이연 | 기각 | Phase 3 전 배포 시 prod 잠김. |

**판단 12 (계약 변경·malformed)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| malformed 자격증명 400 MALFORMED → 401 UNAUTHENTICATED | **채택** | SecurityContext principal은 이미 Long·컨트롤러 형식 파싱 소멸·malformed credential=인증 실패=401·HTTP 의미 정합·2건(SellerInventory·AdminPayment T2). |

### §2 결정 라운드 재진입·재검토 수렴
- **재진입 1 (범위 구체화)**: β 초안(seam=ActorResolver) → 외부 검토로 seam=SecurityContext 상향 + AuthenticationProvider 분리 + @Profile 게이팅 추가 → 정석 구조 승격·전면 채택.
- **재진입 2 (인가 예외)**: 정찰로 /api/v1/claims/** Buyer+Seller 혼재 발견 → α 유지하되 method+path 세분 규칙 부착으로 해소(β 전환 아님).
- **재진입 3 (트랩 2건)**: starter-security classpath 반입 시 @WebMvcTest 슬라이스 기본 잠금·prod 기본 잠금 발견 → (a)addFilters=false·(A)prod permitAll로 무회귀 보존.

### §3 구현 산출 (실측 라인)
**Phase 1 (PR #104·머지 af24bdd)**:
- 신설 main 5: `common/security/` StubRole·StubAuthenticationToken·StubAuthenticationProvider·StubAuthenticationFilter·SecurityConfig.
- 신설 test 1: StubAuthenticationFilterTest(5케이스).
- 수정: `build.gradle.kts`(starter-security+spring-security-test)·@WebMvcTest 6건 `addFilters=false`.
- 결과: 550→555(+5)·회귀 0.

**Phase 2+3 (PR #105·머지 d03aee7·atomic change set)**:
- 신설 main 4: `common/auth/` BuyerActorResolver·SecurityContextBuyerActorResolver·SecurityContextActorSupport · `common/security/` StubSecurityErrorHandler.
- 신설 test 1: `common/security/` AuthHeaders.
- 수정 production 7: HeaderSeller/AdminActorResolver·SellerActorResolver·AdminActorResolver(Javadoc)·BuyerClaimController·BuyerOrderController·SecurityConfig(hasRole 활성).
- 수정 test 15: 통합 13 + Buyer 슬라이스 2 + dead ACTOR_HEADER 상수 10파일 제거.
- 결과: 555 유지(in-place 전환·신설/삭제 0)·회귀 0.

### §4 핵심 메서드·구조
- **인증 배선**: `Authorization: Stub role:id` → StubAuthenticationFilter → ProviderManager(StubAuthenticationProvider) → SecurityContext principal(actorId:Long).
- **ActorResolver**: `resolve(HttpServletRequest)` 시그니처 유지 → `SecurityContextActorSupport.requireActorId()` 위임.
- **인가 2계층**: 경로 hasRole(필터·coarse·403) + 소유권 authorize(서비스·fine·404) 독립·상보.
- **에러 통일**: StubSecurityErrorHandler(AuthenticationEntryPoint+AccessDeniedHandler) → GlobalExceptionHandler ProblemDetail 포맷.

### §진입점 카드
① **목적**: stub 헤더 3종 → Spring Security 정식 대체·인증 진입점 단일화.
② **핵심 진입점**: SecurityConfig(경로 인가 규칙)·StubAuthenticationFilter(파싱)·SecurityContextActorSupport(principal 조회).
③ **핵심 SoT 메서드**: `SecurityContextActorSupport.requireActorId()`·`ActorResolver.resolve(HttpServletRequest)`.
④ **영향 범위**: 전 인증 컨트롤러(Buyer·Seller·Admin)·통합테스트 13·슬라이스 5. 소유권 3곳 무변경.
⑤ **패턴 재사용**: 신규 actor 유형 추가 시 ActorResolver 인터페이스+SecurityContext 구현체 대칭 신설(Buyer 승격 선례). 테스트는 `AuthHeaders.role(id)`.
⑥ **트랩 주의**: (1) @WebMvcTest 슬라이스는 SecurityConfig 미로드→addFilters=false·resolver mock (2) prod 프로파일 기본 잠금→permitAll 체인 필수 (3) claims 인가 규칙 순서(구체 SELLER 먼저·first-match).

### §8 carry-over
- **RoleCode(6값) 저장 전용·런타임 인가 미소비 확정**(STEP 4.5 실측: Role·UserRole·RolePermission·Permission 4 Repo 주입 0건·Role.java:36·44만 참조). 실 인가는 소유권 ID 매칭. StubRole(coarse)·RoleCode(fine RBAC) 입도 상이·양쪽 다 미소비 → RoleCode 배선 or 정리는 후속 트랙(RBAC 세분 권한 실도입 시).
- **Track 31 신규 트랩 3건**(단건이라 LT 임계 미달·≥3건 누적 시 live-traps.md 이관 관례): (1) starter-security classpath→@WebMvcTest 슬라이스 기본 잠금 (2) prod 프로파일 기본 잠금 (3) claims method+path 인가 규칙 순서 의존. 동일 트랩 재발생 시 LT-06~ 이관.
- **prod smoke 테스트 미추가**: prod 체인 permitAll·의존성 0·컴파일 검증만. 실 인가 활성(자격증명 트랙) 시 prod-프로파일 컨텍스트 로드 테스트 동반 권장.
- **generated security password 로그**(코스메틱): prod 체인 AuthenticationProvider 부재로 UserDetailsServiceAutoConfiguration 미사용 유저 생성 로그. permitAll이라 무해. 억제 시 auto-config exclude(범위 밖).
- **후속 트랙(자격증명 인증)**: V10 credential 스키마·로그인·JWT/Session·Password 정책. Stub→JWT는 Filter/Provider 교체 1점(AuthHeaders 내부만 Bearer 전환·18파일 불변).

### §특기
결정 근거 영구화 원칙 13회차(D-104~D-116). Track 31 = 프로젝트 최초 Spring Security 도입·인증 진입점 단일화. 외부 검토가 seam을 ActorResolver→SecurityContext로 상향하여 설계 완성도 실증 상승(S급 외부 검토 권장 정합).

---

## D-117. Track 32 ELK 로그 수집 인프라 — Logback JSON appender → Filebeat → Elasticsearch data stream + ILM [ACTIVE]

**결정일**: 2026-07-03
**관련**: Track 32 / D-100 Q16(correlationId 누락 검증 α·로그 수집 인프라 후속 트랙 명시 이연·decisions.md:5518) / D-48(TraceIdFilter traceId MDC) / D-111(외부 연동 seam·ELK만 실 통합 대상·나머지 Mock) / 기조 1(운영 용이성)·기조 4(과잉개발 회피)·기조 5(실측 우선)

### §1 결정 사항 (구현 완료 실측)
외부 통합 중 유일한 실 통합 대상인 ELK 로그 수집 인프라를 배선한다. prod 프로파일 로그를 logback JSON file appender로 기록하고 Filebeat가 tailing해 공유 zslab_elasticsearch(8.13.0)의 data stream으로 전송한다. D-100 Q16이 후속 트랙으로 명시 이연한 "로그 수집 인프라 도입"을 종결한다. 도메인 코드 무변경(로깅 인프라 계층 한정)·delivery/attachment/address seam 분리는 별 트랙(D-111 §8) 미포함 확정.

- **결정1 (수집 방식 = α Logback JSON file appender → Filebeat → ES)**: 앱은 /app/logs에 JSON 기록·Filebeat가 tailing해 ES 전송. β Logstash 경유·γ Logback ES appender 직접 대비 채택. LOG_PATH=/app/logs 예약 자산 정합·ES 장애가 앱에 전파되지 않는 파일 버퍼 디커플링·Logstash 대비 신설 컨테이너 경량.
- **결정2 (인덱스 = β data stream + ILM·zslab-mall-* 전용 등록)**: data stream `zslab-mall-logs` + ILM policy(hot rollover 7d/10gb·delete 30d) 등록. nori 미적용(message = 동적 매핑 text+keyword). 로그는 traceId/level 필터·집계 주용도라 한국어 전문 검색 수요 낮음(nori 오버스펙·기조 4). data stream/ILM은 ES 클러스터에 zslab-mall-* prefix 전용 신규 오브젝트 등록(기존 컨테이너·기존 인덱스·기존 정책 무수정).
- **결정3 (환경 게이팅 = α prod 전용)**: JSON file appender·Filebeat 연동은 @Profile("prod")에서만 활성. local/test는 기존 콘솔 출력(%5p [%X{traceId:-}]) 유지. 로그 수집은 실 운영 가시성 목적이라 prod 정합·Security 빈 @Profile("prod")/!prod 패턴 대칭.
- **결정4 (ES 오브젝트 페이로드 파일화)**: ILM policy·index template PUT 페이로드를 docker/elasticsearch/에 파일화(ilm-policy.json·index-template.json·README.md). 로컬 ES ≠ 서버 ES → 서버 배포 시 재등록 근거·형상관리.
- **무변경**: 도메인 코드 전건·TraceIdFilter(ULID traceId/correlationId/eventName MDC 주입 기존재·재사용)·application.yml logging.pattern.level.

**"ES 무수정" 인지 확정 (사용자 명시)**: zslab_elasticsearch 컨테이너 자체(라이브러리·플러그인·설정파일·기동옵션) 무변경이 제약. API로 연결해 index template·ILM policy·data stream·문서를 등록·적재하는 것은 전부 허용(ES 정상 사용). 컨테이너·인프라 설정 손대기(금지) vs API 오브젝트 등록·적재(허용) 구분.

**검증**: 로컬 Docker prod 기동 실측 통과. 555 tests PASS·회귀 0(Phase 1 logback 배선). data stream GREEN·백킹 인덱스(.ds-zslab-mall-logs-2026.07.03-000001)·docs.count>0·_source 최상위 traceId/correlationId 승격·ILM managed hot/rollover 연결 4종 확인. local 프로파일 복귀.

### §1-A 옵션 검토 (채택/기각·결정 근거 영구화)

**결정1 (수집 방식)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α Logback JSON file → Filebeat → ES | **채택** | LOG_PATH 예약 자산 정합·파일 버퍼로 ES 장애 앱 미전파·Logstash 대비 경량. |
| β Logback → Logstash(TCP) → ES | 기각 | Logstash 컨테이너 신설 부담·공유 인프라에 Logstash 부재(Filebeat만 신설로 충족). |
| γ Logback ES appender 직접 | 기각 | 앱-ES 결합·ES 장애 앱 전파·라이브러리 유지보수 정체 리스크(기조 1 상충). |

**결정2 (인덱스 전략)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α 플레인 일자 인덱스·무등록(동적 매핑) | 기각 | 롤오버/보존 수동·ILM 자동화 이점 상실. 단 "ES 무수정=컨테이너 무수정"으로 인지 확정 후 등록이 허용되어 β 가능해짐. |
| β data stream + ILM(zslab-mall-* 전용 등록) | **채택** | 롤오버·보존 ES 관리로 단일 운영자 부담 최소(기조 1)·prefix 전용 신규 오브젝트라 기존 무영향. |
| γ 단일 인덱스·롤오버 없음 | 기각 | 무한 증가·수동 관리(기조 1 상충). |
| nori 적용 | 기각 | 로그 = 필터/집계 주용도·한글 전문 검색 수요 낮음·nori 오버스펙(기조 4). ES에 nori 플러그인 기설치이나 로그 인덱스는 동적 매핑으로 충분. 한글 로그 전문 검색 실수요 도달 시 template message 필드에 analyzer:nori 지정만으로 확장(ES 무수정·§8 이월). |

**결정3 (환경 게이팅)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α prod 전용 | **채택** | 실 운영 가시성 목적·로컬은 콘솔 디버깅 유용·ES 미가동 환경 Filebeat 부담 무·Security 빈 프로파일 패턴 대칭. |
| β prod+local 양쪽 | 기각 | 로컬 디스크 쓰기·이중 출력·포맷 검증은 appender 단위로 충분. |
| γ 프로파일 무관 상시 | 기각 | test 실행마다 파일 생성 불필요. |

**data stream 이름·기존 인덱스 처리 (Phase 3 정찰 후 재검토)**:
| 옵션 | 판정 | 근거 |
|---|---|---|
| α 검증 인덱스 삭제 + data stream zslab-mall-logs(패턴 zslab-mall-logs*) | **채택** | Phase 2 검증 더미 인덱스(zslab-mall-logs-2026.07.03·6줄)는 보존 가치 무·data stream 표준 설계·네이밍 일관성(기조 1). |
| β 기존 인덱스 존치 + data stream 이름 회피 | 기각 | 존치 1건 위해 영구 네이밍 이원화·패턴 설계 복잡성 부담. |
| γ data stream 포기·일반 인덱스+ILM alias | 기각 | 라운드 2 확정 설계(data stream) 철회. |

### §2 결정 라운드 재진입·재검토 수렴
- **재진입 1 ("ES 무수정" 인지 정정·핵심)**: 사용자 "ELK 무수정" 지시를 초기에 "ES에 오브젝트 등록도 금지"로 해석하여 인덱스 전략을 α(무등록)로 좁힘. 사용자 명시로 "컨테이너 자체 무변경이 제약·API 오브젝트 등록은 허용" 확정 → 인덱스 전략 β(data stream+ILM) 원안 복귀. 제약 의미 실측 없이 좁게 단정한 것이 기조 5 소지 → 사용자 확인으로 수렴.
- **재진입 2 (로컬 ES 존재 오판 정정·기조 5 위반)**: docker-compose.mall.yml에 ES 서비스 없음·zslab_zslab_net external:true만 보고 "로컬에 ES 없음·서버에만 존재"로 단언. 사용자 `docker ps` 실측으로 로컬 Docker에 zslab_elasticsearch(8.13.0) 10시간째 가동 확인 → 정정. compose 파일 추정으로 상태 단언한 명백한 기조 5 위반·실측(docker ps)으로 해소. 로컬=개발서버·운영서버(218.232.94.150) 동일 구조 인지 확립.
- **재진입 3 (검증 인덱스 존치→삭제 재검토)**: Phase 2 후 검증 인덱스 존치(β) 결정했으나 Phase 3 정찰에서 동일 이름 일반 인덱스가 data stream 생성과 충돌 실측 → 삭제(α)로 재검토. 존치 결정이 "충돌 실측 전"이라 재검토 정당.
- **정찰·실측 게이트**: 정찰 3회(Phase 2 배선 지점·Phase 3 네임스페이스 충돌·전 Phase read-only)·prod 로컬 검증 1회. filebeat type:log↔filestream parsers 혼용 트랩은 Claude Code 구현 전 발견·수정(아래 §트랩).

### §3 구현 산출 (실측)
**PR #(Phase 1+2·머지 337a501)**:
- 신설: `backend/src/main/resources/logback-spring.xml`(defaults.xml+console-appender.xml include·springProperty LOG_PATH·!prod CONSOLE·prod JSON_FILE RollingFileAppender+LogstashEncoder·includeMdcKeyName 3종)·`docker/filebeat/filebeat.yml`(filestream+ndjson parser).
- 수정: `backend/build.gradle.kts`(logstash-logback-encoder:8.0)·`docker-compose.mall.yml`(LOG_PATH env·mall_backend_logs named volume·filebeat 서비스 8.13.0)·`.env.example`(ES_HOST·ES_PORT).

**PR #(Phase 3·머지 9a360de)**:
- 신설: `docker/elasticsearch/ilm-policy.json`(hot rollover 7d/10gb·delete 30d)·`docker/elasticsearch/index-template.json`(index_patterns zslab-mall-logs*·data_stream{}·priority 200·ilm zslab-mall-logs-policy·replica 0·@timestamp date)·`docker/elasticsearch/README.md`(서버 ES 재등록 절차).
- 수정: `docker/filebeat/filebeat.yml`(output index 일자패턴 제거→data stream zslab-mall-logs 라우팅).

**ES 런타임 등록(로컬 검증·서버는 배포 시 README 절차)**: PUT /_ilm/policy/zslab-mall-logs-policy·PUT /_index_template/zslab-mall-logs.

### §4 핵심 구조
- **로그 파이프라인**: logback-spring.xml(prod JSON_FILE·LogstashEncoder·MDC 3종 승격) → /app/logs/zslab-mall.json → mall_backend_logs named volume 공유 → Filebeat(filestream·ndjson 파싱·필드 루트 승격) → zslab_elasticsearch:9200 → data stream zslab-mall-logs(template priority 200·ILM hot rollover).
- **인가/게이팅**: prod 프로파일만 JSON_FILE+CONSOLE·!prod는 CONSOLE 단독. Filebeat는 로컬 개발 시 미기동(prod 배포 전용).
- **MDC 3종**: traceId·correlationId·eventName(TraceIdFilter·TracedEventPublisher 기존 주입 재사용·D-48). includeMdcKeyName 화이트리스트로 3종만 JSON 승격(나머지 MDC 필터링·계약 고정).

### §진입점 카드
① **목적**: 외부 통합 유일 실 통합(ELK) 로그 수집 배선 — prod 로그를 data stream으로 수집·운영 가시성 확보.
② **핵심 진입점 파일·라인**: `backend/src/main/resources/logback-spring.xml`(prod JSON_FILE appender)·`docker/filebeat/filebeat.yml`(filestream→data stream)·`docker/elasticsearch/{ilm-policy,index-template}.json`(ES 오브젝트).
③ **핵심 SoT**: data stream 이름 zslab-mall-logs·template priority 200(내장 logs 100 상회)·ILM zslab-mall-logs-policy(hot rollover 7d/10gb·delete 30d)·MDC 3종 승격(includeMdcKeyName 화이트리스트).
④ **영향 범위**: 로깅 인프라 계층 단독·도메인 코드 무변경·backend build.gradle.kts+logback-spring.xml·docker(filebeat·elasticsearch)·compose·.env.example. Flyway·Entity·Service·Controller 무변경.
⑤ **패턴 재사용**: D-48 TraceIdFilter MDC 주입·Security 빈 @Profile("prod")/!prod 게이팅 대칭·D-111 seam 확립 원칙(ELK=실 통합·나머지 Mock).
⑥ **트랩 주의**: 아래 §트랩 4건.

### §트랩 (실측 발견·재발 시 LT-06~ 이관 후보)
1. **filebeat type:log ↔ filestream parsers 혼용**: parsers: ndjson 블록은 filestream 입력 전용. type:log에 parsers 지정 시 8.13에서 무시되어 NDJSON 파싱 실패·traceId/correlationId/eventName 승격 실패(서버 전용 라이브 트랩). type:filestream + id 필수. Claude Code 구현 전 발견·수정.
2. **prod JAR shadow**: prod Dockerfile은 /app/app.jar 빌드하나 compose backend에 ./backend:/app bind mount가 JAR을 가림 → up --build 시 Unable to access jarfile. prod 기동 시 volumes override(logs 볼륨만·소스 마운트 제외) 필요.
3. **언더스코어 Host → Tomcat 400**: 컨테이너명 zslab_mall_backend의 _를 Tomcat 10.1(Spring Boot 3.4)이 무효 호스트명 거부 → 직접 호출 시 앱 도달 전 400. 운영은 nginx가 유효 도메인 Host 주입해 무해·검증 시 -H "Host: localhost" 우회.
4. **서버 LOG_PATH 정합**: 로컬 .env가 LOG_PATH=./logs 재정의 가능(로컬은 local 프로파일·콘솔 전용이라 무해). 서버 .env는 반드시 LOG_PATH=/app/logs여야 3경로(logback 출력·볼륨 마운트·Filebeat tail) 일치. .env.example은 /app/logs 정상 명시.

### §8 carry-over
- **nori 형태소 검색 확장**: 한글 로그 전문 검색 실수요 도달 시 승격. ES nori 플러그인 기설치·zslab-mall-* index template의 message 필드에 analyzer:nori 지정만으로 확장(ES 컨테이너 무수정). 현재 미적용(로그=필터/집계 주용도·기조 4). 별건.
- **서버 ES 오브젝트 등록**: 로컬 ES ≠ 서버 ES → 운영서버(218.232.94.150) 배포 시 docker/elasticsearch/README 절차로 ILM policy·index template 1회 등록 필요(data stream은 첫 로그 유입 시 자동 생성).
- **운영 규모 ILM 재검증**: rollover 7d/10gb·delete 30d는 운영 초기 보수적 기본값. 실 로그량 누적 후 보존 정책 재조정 대상(zslab-mall-* 전용 독립 조정).
- **Kibana 대시보드·경보**: data stream 적재 후 시각화·ES Watcher 경보는 실 수요 도달 시 후속 트랙(별건).
- **delivery/attachment/address seam 분리**: D-111 §8 이월 유지·Track 32 미포함 확정(ELK는 로깅 인프라라 도메인 seam 불요).

### §특기
- 결정 근거 영구화 원칙 14회차(D-104~D-117). Track 32 = 외부 통합 중 유일한 실 통합(ELK) 배선·프로젝트 최초 실 로그 수집 인프라. D-100 Q16 후속 이연 종결.
- 기조 5 위반 2건 실증·정정(재진입 1 "ES 무수정" 인지·재진입 2 로컬 ES 존재 오판). 둘 다 compose 파일·제약 문구 추정으로 상태 단언 → 사용자 실측(docker ps)·명시 확인으로 수렴. "실측 우선·추정 금지" 원칙의 실패-정정 사이클 기록.

---

## D-118. 자격증명 인증(JWT·로그인·Password 정책·Stub→JWT 전환) [ACTIVE]

- 등급: S급 · 결정 근거 영구화 15회차 · 실측일 2026-07-04
- 상태: 구현·검증 완료(561 tests PASS)·미머지(박제 후 3-split 커밋·PR 예정)

### §1 결정 요지
user 테이블 기반 단일 credential + JWT STATELESS 인증 도입. Stub 인증 파이프라인(D-116)을 JWT로 원자적 교체. 통합 로그인 endpoint /api/v1/auth/login + role 자격 검증 계약(seam) 확립. RBAC 세분 권한은 별 트랙 분리.

### §1-A 판단별 α/β/γ 채택·기각
- 인증 매체: **JWT STATELESS** 채택 / Session+Redis 기각 — Redis 가용(zslab_redis 실측)이나 강제 로그아웃·세션 무효화 실수요 미도달·Stub 이미 STATELESS·교체 1점 정합. 단 TokenProvider 인터페이스로 격리 → Redis 세션 전환 seam 확보(사용자 향후 전환 의향 명시).
- 역할 격리: **단일 active-role(JWT role claim)** 채택 / 쿠키 prefix 분리 기각 — 프론트 미구성이라 도메인 구조 확정 불가·현 hasRole 매칭 정합.
- Password 해싱: **BCryptPasswordEncoder** 채택 — Spring Security 표준.
- credential 위치: **user 테이블 ALTER(V10 password_hash)** 채택 / user_credential 별도 테이블 기각 — 로그인 주체가 user로 수렴(Admin=user+user_role·Seller구성원=seller_user→user 실측)·1:1 조인 실익 없음.
- NULL 정책: **NULL 허용 + 로그인 가능 조건 명문화** 채택 / NOT NULL+더미백필 기각 — 데이터 의미 왜곡·비식별화 대상 오염. 로그인 조건 = password_hash IS NOT NULL AND deleted_at IS NULL AND withdrawn_at IS NULL (※user.status 컬럼 미존재 실측→기존 소프트삭제·탈퇴 필드로 판정).
- 로그인 endpoint: **통합 /api/v1/auth/login** 채택 / actor별 분리 기각 — 인증 주체 user 단일 수렴·분리 시 Controller/DTO/Matcher/테스트 3배 중복.
- Stub 게이팅: **Jwt 전 프로파일 통일·Stub 4클래스 제거·단일 SecurityConfig 체인** 채택 / !prod=Stub·prod=Jwt 분리 기각 — 테스트가 local이라 prod 인증 경로 미검증→회귀 미포착. principal=Long 유지로 Resolver 3종·SecurityContextActorSupport 무수정.
- role 자격 검증: **RoleAuthorization 계약 + Stub 구현(항상 통과)** 채택 / 무검증 신뢰 기각 — 클라이언트 role 위조 위험. 계약은 지금 확립·실 조회(user_role/seller_user)는 RBAC 트랙. 매 로그인 호출되므로 죽은 코드 아님(기조4).
- 인증 실패 응답: **외부 401 통일 + 내부 로그 사유 구분** 채택 / 사유별 메시지 기각 — account enumeration 취약. 외부 "Invalid email or password."·내부 USER_NOT_FOUND/PASSWORD_MISMATCH/ROLE_MISMATCH/ACCOUNT_DISABLED.

### §1-B 구현 트랩(실측)
1. 필터 이중 등록 트랩: JwtAuthenticationFilter @Component 시 서블릿 자동 등록+SecurityConfig addFilterBefore 이중 실행 → A안(빈 아님·SecurityConfig가 new 생성·의존성만 빈 주입)으로 회피. StubAuthenticationFilter 원작자 의도(코드 박제)와 동일 패턴.
2. throw-only 필터 위치 트랩: 필터 verify 실패 throw-only(entryPoint 미주입) 설계 시 addFilterBefore(UsernamePasswordAuthenticationFilter.class) 위치면 ExceptionTranslationFilter(ETF)보다 앞→throw가 컨테이너 전파→500. addFilterBefore(AuthorizationFilter.class)로 ETF 뒤 배치해야 ETF 캐치→401.

### §2 결정 라운드 재진입
- user.status 컬럼 부재 실측으로 로그인 가능 조건을 status=ACTIVE→소프트삭제·탈퇴 필드로 정정(외부 검토 전제 오류 수렴).
- 필터 이중 등록·throw-only 위치 트랩 2건 Claude Code 착수 전 발견·A안+AuthorizationFilter 위치로 수렴.

### §8 carry-over
- RBAC 세분 권한(RoleCode 6값 배선): RoleAuthorization 실 구현(user_role/seller_user 실조회)·별 트랙. 현 StubRoleAuthorization 항상 통과.
- prod smoke 확장: 현재 컨텍스트 로드+401+유효토큰 3케이스. 실 운영 시 endpoint별 인가 매트릭스 확장 여지.
- JWT 리프레시 토큰: 현재 단일 access token(1h)·리프레시 흐름 미구현·실 운영 UX 수요 도달 시.
- Redis 세션 전환: TokenProvider seam 확보. 강제 로그아웃·동시로그인 제어 수요 시 SessionProvider 교체 1점.
- Track 31 트랩 3건·Track 32 트랩 4건: 재발생 시 live-traps.md LT-06~ 이관 대기(변동 없음).

### §진입점 카드
1. 목적: user 기반 JWT 인증·통합 로그인·Stub→JWT 원자 교체·role 검증 seam
2. 핵심 진입점: AuthController(/api/v1/auth/login) · AuthService(5단계 검증) · JwtAuthenticationFilter(Bearer→verify→SecurityContext) · SecurityConfig(단일 체인)
3. SoT 메서드: TokenProvider.issue/verify · JwtTokenProvider(HS256) · RoleAuthorization.isAuthorized(Stub) · AuthService 인증 5단계
4. 영향 범위: V10(user.password_hash) · Stub 4클래스 삭제 · 단일 SecurityConfig · AuthHeaders 빈 전환(68 호출부 Bearer) · principal=Long 유지(Resolver 3종 무수정)
5. 패턴 재사용: 인터페이스 격리 seam(D-111 Mock 어댑터)·throw-only+ETF 위임·A안 필터 비-빈 생성(D-116 StubFilter 패턴)
6. 트랩 주의: 필터 이중 등록(§1-B-1)·throw-only 위치(§1-B-2)·principal 타입 Long 계약 이탈 금지

---

## D-119. Track 34 회원가입·비밀번호 설정 — Buyer 셀프가입·credential 생성 경로·role seed 배선·비밀번호 변경 [ACTIVE]

**결정일**: 2026-07-04
**관련**: Track 34 / D-118(JWT 인증·AuthService 5단계·RoleAuthorization seam·user.password_hash·§8 credential 생성 경로 후속)·D-116 §8(RoleCode 저장 전용·미배선)·D-92 Q3(actor 소유권 Service 진입부)·D-111(Mock 어댑터 seam·이메일 인증 실 어댑터 이연) / 기조 1(운영 용이성)·기조 4(과잉개발 회피)·기조 5(실측 우선)

### §1 결정 요지
D-118이 로그인(credential 검증)을 세웠으나 credential 생성 경로 부재로 전 user의 password_hash=NULL·실 로그인 가능 계정 0이던 공백을 종결한다. Buyer 셀프가입 endpoint로 credential을 생성하고, 가입 시 BUYER role을 배선해 "가입→로그인→BUYER API 접근" 최소 사용자 흐름을 Track 34 단독 머지로 완결한다. 비밀번호 변경(인증 필수) endpoint를 동반 제공한다. Seller/Admin 가입·RBAC 세분 인가·실 이메일 인증은 별 트랙 분리.

- credential 생성: POST /api/v1/users(Buyer 셀프가입·permitAll)·email 중복 검증·PasswordPolicy 검증·BCrypt 해시·user 저장·BUYER role 매핑 save.
- role 인프라: V11 role seed 6값 + uk_role_code UNIQUE + RoleRepository.findByCode(RoleCode). D-116 §8 "role 매핑 미배선"을 가입 경로 한정 흡수.
- 비밀번호 변경: PATCH /api/v1/users/me/password(인증 필수·현재 비번 확인 후 교체·204).
- 입력 상한: SignupRequest @Size(max)를 DB @Column length를 SoT로 부여(4xx 계약 완결).

**검증**: `./gradlew.bat test` 575 tests PASS·failures 0·errors 0·skipped 0·124 suites·회귀 0. baseline 561(D-118) + 14(Phase 1 fixture 전환 순증 0·Phase 2 가입 6+보강 1·Phase 3 비번변경 7) = 575.

### §1-A 판단별 α/β/γ 채택·기각 (결정 근거 영구화)

| 판단 | 채택 | 기각·근거 |
|---|---|---|
| 1 가입 주체별 경로 | **α Buyer 셀프가입만** | β 3경로(Buyer/Seller/Admin)·γ Admin/Seller 운영자 생성 공통 endpoint 기각 — Seller/Admin 계정 생성은 초대·승인·RBAC와 얽혀 별 트랙. MVP 실사용 진입로 = Buyer 셀프가입(기조 4). |
| 2 password_hash 설정 방식 | **α User.create 유지 + 인스턴스 메서드** | β 팩토리 오버로드·γ create 시그니처 확장(호출부 전수 수정) 기각 — 최초가입·비번변경 공용 단일 경로·기존 호출부 무영향. |
| 3 password 정책 검증 위치 | **γ DTO @Size + PasswordPolicy 이중** | α DTO 단일·β 서비스 단일 기각 — DTO는 HTTP 진입점만 방어. PasswordPolicy는 **현재 가입 서비스가 실호출하는 도메인 정책 SSOT**(미래 Admin/Batch 근거 아님·소비처 실재하므로 기조 4 부합). MVP는 길이 규칙 1개로 최소. |
| 4 email 중복 응답 | **β 409 명시** | α 일반 메시지(enumeration 방지) 기각 — 가입은 로그인과 달리 시도로 존재 노출 불가피·UX상 "이미 사용 중인 이메일" 명시 표준. 로그인 401 통일과 결이 다른 근거 명문. |
| 5 가입 시 role 배선 | **β BUYER role save** | α 미배선 기각 — role 없는 user는 인가 시 무자격("가입 성공→로그인 성공→인가 실패" 모순 상태). 셀프가입 논리적 완결 = BUYER role 부여까지. |
| 5R role seed·조회 시점 | **α V11 seed+findByCode+uk_role_code 포함** | β 배선 이연·γ seed만 포함(배선 이연) 기각 — β는 Track 34가 만든 Buyer 계정을 전부 불완전 상태로 영구 저장. γ는 seed 소비처 부재(기조 4). Track 34 단독 머지로 최소 흐름 완결이 정당성 근거. |
| (5R 세부) seed 방식 | **α-1 Flyway V11 SQL seed** | α-2 CommandLineRunner seeder 기각 — role 6값은 환경 불변 reference data·SQL 마이그레이션이 운영 일관·재현성 우위·프로파일 제어 부담 회피. |
| (Phase 1 회귀) 기존 테스트 seed 충돌 | **방향 A + A-1** | 방향 B(강행 후 레드)·C(seed 이연) 기각 — seed 도입 시 기존 10지점 Role 직접 save가 uk_role_code 충돌. fixture 공급원을 findByCode 조회로 전환(구현 버그 아닌 fixture 전략 변경의 필연적 정합). 9지점 assert 불변·RoleRepositoryTest 1건만 "seed 조회 계약 검증"으로 목적 전환(A-1·삭제 아님). |
| 6 비번설정 endpoint | **α 변경만(인증 필수)** | β 최초 설정+변경 2 endpoint 기각 — 판단 1α에서 가입=해시 생성이므로 최초 설정 경로 불요. 미인증 최초 설정은 Seller/Admin 운영자 생성 트랙에서 신설. |
| 7 PasswordPolicy 배치 | **α user/policy 신규·서비스 SSOT** | β DTO 단일 기각 — 가입 서비스가 실호출하는 도메인 정책 객체(죽은 코드 아님). |
| 8 hash 설정 네이밍 | **α assignPasswordHash(String)** | assignPassword 기각 — 메서드명 Password+파라미터 passwordHash 불일치로 raw password 오해 소지. hash만 다루는 계약을 메서드명에 명시. set* 컨벤션 위반. |
| 9 email 중복 예외 | **EmailAlreadyExistsException 신설·409·EMAIL_ALREADY_EXISTS** | 재사용 후보 없음(Duplicate*/AlreadyExists* 부재)·기존 409 선례(IdempotencyKeyInProgress 등) 매핑 정합. |
| 10 입력 길이 상한 | **α @Size(max)·DB SoT** | β 이연 기각 — email 254 초과가 DB 제약 위반→fallback 500으로 새는 실재 결함. @Size는 임의 규칙 아니라 **@Column length 실측값과 동일**(email 254·name 50·phone 20). 잘못된 입력=4xx 계약 완결(기조 4 결함 처치). |
| A userId 획득 | **A2 AuthenticatedUserResolver 신설** | A1 BuyerActorResolver 차용·A3 requireActorId public화 기각 — role중립 public 추출기 부재(전제 오류). requireActorId()가 package-private. "Buyer" 차용은 role 오도. requireActorId() 위임 role중립 resolver가 seam 정확 표현·기존 3 resolver 위임 패턴 동일. |
| B findById 부재 예외 | **B1 IllegalStateException·500** | B2 401 재해석 기각 — 방금 인증된 토큰의 userId가 DB에 없음=토큰 유효·데이터 정합 이상(5xx 정직). Phase 2 findByCode(BUYER) 누락과 동일 계열. |
| 11 비번변경 스펙 | **11a-β 204·11b-α PasswordPolicy 재사용·11c-β 400** | 200/204: 반환 리소스 없음→204. currentPassword 불일치: 401 기각(이미 JWT 인증된 사용자의 입력값 검증 실패이지 신원 확인 실패 아님)→IllegalArgumentException 400. 새 비번 검증은 가입과 동일 SSOT. |
| (Signup 응답) Location 헤더 | **α 생략·body userPublicId** | β Location 부여 기각 — 회원 조회 endpoint 부재로 Location=죽은 링크(기조 4). 회원 조회 endpoint 트랙에서 Location 도입·현재는 body publicId만. |

### §1-B 구현 트랩(실측)
1. **seed↔테스트 fixture 충돌 트랩**: V11 role seed 도입 시 기존 4개 테스트 클래스 10지점이 `roleRepository.save(Role.create(RoleCode.X,...))`로 role 직접 심어 uk_role_code UNIQUE 충돌. 원인=구현 버그 아닌 fixture 공급원 변경. seed 존재 시 테스트는 findByCode 조회 재사용이 정합(방향 A). assert 대상 불변 원칙으로 임의 축소 차단.
2. **role중립 public 추출기 부재 트랩**: 비번변경 userId 획득 시 확정 설계 "기존 Resolver 재사용"이 전제한 role중립 public 추출기 부재. SecurityContextActorSupport.requireActorId()가 package-private(common.auth 전용). AuthenticatedUserResolver(A2) 신설로 해소·"Buyer" resolver 차용 회피.

### §2 결정 라운드 재진입
- 판단 3: 외부 검토로 α→γ 상향(비-HTTP 경로 방어). 단 근거를 "미래 Admin/Batch"에서 "현재 가입 서비스 실호출 SSOT"로 교체(Track 34에 비-HTTP 가입 경로 미실재·기조 5).
- 판단 5R: 게이트 미충족(role seed·findByCode·uk_role_code 3건 부재) 실측으로 판단 5(β)가 선행 인프라 3건을 Track 34로 흡수하는 5R-α 신규 라운드 진입.
- 판단 A/B: Phase 3 착수 전 Claude Code 실측(requireActorId package-private)으로 확정 설계 "Resolver 재사용" 전제 오류 발견→A2 신설로 수렴. 임의 결정 없이 보고 후 판단.
- 판단 10: Phase 2 구현 중 Claude Code가 email 254 초과 500 누수 지적→범위 밖이나 실재 결함으로 흡수(Track 27 백로그 F·Track 28 400 트랩 흡수 선례 정합).

### §3 구현 산출 (실측·라인은 커밋 전 파일 대조 재확인)
**Phase 1 (role 인프라·561 green 유지·순증 0)**:
- 신설: `db/migration/V11__role_seed_and_unique.sql`(uk_role_code UNIQUE→role 6값 seed·NOW(6)·한글명·롤백 주석).
- 수정: `auth/repository/RoleRepository.java`(findByCode)·`user/repository/UserRepository.java`(existsByEmail)·테스트 4클래스(UserRoleRepositoryTest·RolePermissionRepositoryTest·SellerUserRepositoryTest fixture 조회 전환·RoleRepositoryTest 목적 전환).

**Phase 2 (Buyer 가입·567→568)**:
- 신설: `user/policy/PasswordPolicy.java`(@Component·MIN_LENGTH=8·SSOT)·`user/exception/EmailAlreadyExistsException.java`·`user/controller/request/SignupRequest.java`(record·@NotBlank·@Pattern·@Size DB SoT·password @Size(min=8,max=72) BCrypt)·`user/controller/response/SignupResponse.java`(userPublicId)·`user/service/UserService.java`(register)·`user/controller/UserController.java`(POST·201·Location 생략).
- 수정: `user/entity/User.java`(assignPasswordHash·null/blank 가드)·`common/web/GlobalExceptionHandler.java`(EmailAlreadyExists 409·EMAIL_ALREADY_EXISTS)·`common/security/SecurityConfig.java`(POST /api/v1/users permitAll·GET fail-closed).
- 테스트: UserServiceTest 3·SignupIntegrationTest 3(+보강 tooLongEmail 400 1).

**Phase 3 (비번변경·568→575)**:
- 신설: `common/auth/AuthenticatedUserResolver.java`(@Component·requireActorId 위임·role중립)·`user/controller/request/ChangePasswordRequest.java`(currentPassword·newPassword @Size).
- 수정: `user/service/UserService.java`(changePassword·hash null||불일치 400 통일·내부 로그 사유 구분)·`user/controller/UserController.java`(PATCH /me/password·204).
- 테스트: UserServiceTest +4·ChangePasswordIntegrationTest 3.

### §4 핵심 메서드
- **UserService.register**: passwordPolicy.validate → existsByEmail true→EmailAlreadyExistsException(409) → User.create → assignPasswordHash(encode) → save → findByCode(BUYER).orElseThrow(IllegalStateException·seed 누락 500) → userRoleRepository.save(UserRole.create(userId, buyer)) → SignupResponse.
- **UserService.changePassword**: findById.orElseThrow(IllegalStateException·B1) → passwordHash null || !matches(current) → IllegalArgumentException(400·외부 통일·내부 NO_CREDENTIAL/PASSWORD_MISMATCH 로그) → passwordPolicy.validate(new) → assignPasswordHash(encode(new)).
- **role 배선 실검증**: SignupIntegrationTest user_role⋈user⋈role WHERE code='BUYER' COUNT=1(실 DB·Mock 아님). 비번변경 204 실 DB rehash matches 검증.

### §진입점 카드
1. **목적**: Buyer credential 생성 경로 확립·BUYER role 배선·비번변경 — "가입→로그인→BUYER API" 최소 흐름 완결.
2. **핵심 진입점**: `UserController`(POST /api/v1/users·PATCH /me/password) · `UserService`(register·changePassword) · `V11__role_seed_and_unique.sql` · `AuthenticatedUserResolver`.
3. **핵심 SoT 메서드**: `UserService.register`·`changePassword` · `User.assignPasswordHash`(hash 저장 계약·set* 금지) · `PasswordPolicy.validate`(SSOT) · `RoleRepository.findByCode` · `UserRole.create(userId, Role)`.
4. **영향 범위**: user 도메인 신규(policy·service·controller·request·response·exception)·auth/RoleRepository·user/UserRepository·common/auth(AuthenticatedUserResolver)·common/web(GlobalExceptionHandler)·common/security(SecurityConfig)·V11. password_hash 재사용(V10·신규 컬럼 무). role 매핑 배선 D-116 §8 가입 경로 한정 해소.
5. **패턴 재사용**: D-118 PasswordEncoder·AuthService 검증 흐름·principal=Long · D-92 Q3 Service 진입부 · Admin/Buyer resolver 위임(AuthenticatedUserResolver) · 201 Created(BuyerOrderController) · 409 핸들러(IdempotencyKeyInProgress 템플릿) · @Size DB length SoT(신규 원칙).
6. **트랩 주의**: (1) seed↔fixture 충돌(§1-B-1·신규 role 직접 save 테스트 금지·findByCode 조회) (2) role중립 추출기 부재(§1-B-2·requireActorId package-private) (3) findByCode(BUYER) empty=seed 누락 500(V11 미적용 배포 오류) (4) SecurityConfig POST /users만 permitAll·GET fail-closed(회원 조회 인증 요구) (5) LT-02 FK 시드 그래프 try-finally.

### §8 carry-over
- **RBAC 세분 권한(RoleCode 6값 인가 배선)**: RoleAuthorization 실 구현(user_role/seller_user 실조회)·별 트랙. Track 34는 role 매핑 생성까지·인가 판정은 StubRoleAuthorization(항상 통과·D-118 §8 계승).
- **Seller/Admin 가입 경로**: 초대·승인·운영자 생성·미인증 최초 비번 설정 endpoint(판단 6β)는 해당 트랙 진입 시.
- **회원 조회 endpoint + Location 헤더**: 조회 리소스 신설 트랙에서 Signup 응답에 Location 도입(판단 응답 α).
- **입력 상한 전역 정책**: @Size DB length SoT 원칙(판단 10)을 타 입력 DTO에 일관 적용 여지.
- **PasswordPolicy 복잡도 규칙**: 현재 길이 1개·복잡도 수요 도달 시 강화(β/γ 승격).
- **실 이메일 인증(가입 확인 메일)**: NotificationSender 실 어댑터 트랙(D-111 §8·현 Mock).
- **Track 34 신규 트랩 2건**: seed↔fixture 충돌·role중립 추출기 부재. 동일 트랩 재발생 시 LT-06~ 이관.

### §특기
- 결정 근거 영구화 원칙 16회차(D-104~D-119). Track 34 = D-118 credential 검증의 논리적 짝(생성 경로) 완결·실 로그인 가능 계정 0 리스크 해소.
- D-116 §8(RoleCode 저장 전용·미배선) 가입 경로 한정 해소·전역 RBAC 인가는 별 트랙 유지.
- 프로젝트 최초 @Size DB @Column length SoT 원칙 명문화.

---

## D-120. Track 35 RBAC 인가 실동작화 — role 위조 차단(fail-closed) [ACTIVE]

**결정일**: 2026-07-04
**관련**: Track 35 / D-118(JWT 인증·AuthService ④ RoleAuthorization seam·verify 서명/만료/role-null만·§8 RBAC 실조회 후속)·D-119(V11 role seed·findByCode·user_role BUYER 배선·2-도메인 스키마·§8 SELLER/ADMIN 생성 경로 이연)·D-116 §8(RoleCode 저장 전용) / 기조 2(객관적 판단)·기조 4(과잉개발 회피)·기조 5(실측 우선)

### §1 결정 요지
D-118이 세운 RoleAuthorization seam(StubRoleAuthorization·항상 통과)을 DB 실조회 fail-closed 구현(DbRoleAuthorization)으로 교체해, 로그인 ④단계에서 클라이언트가 주장한 role의 위조를 차단한다. 판정은 요청 ActorRole 3값을 현 2-도메인 매핑 테이블 실조회로 검증하고, 해당 role 데이터 부재 시 거부(fail-closed)한다. 인가 입도는 coarse Role(ActorRole 3값)에서 종료하며 Permission 세분 배선은 실 소비처 도래 트랙으로 이연한다.

- 실 구현: DbRoleAuthorization(@Component·UserRole/SellerUser Repository 생성자 주입)으로 StubRoleAuthorization 교체(삭제). 인터페이스·시그니처 무변경 → AuthService 주입 무변경.
- 판정 배선: BUYER→user_role (userId, code=BUYER) 존재 / ADMIN→user_role code∈{SUPER_ADMIN, ADMIN_OPERATOR} 존재 / SELLER→seller_user user_id 행 존재(seller 내 role 종류 무관).
- 조회: code 기준 파생 쿼리(existsByUserIdAndRole_Code·existsByUserIdAndRole_CodeIn·existsByUserId)·seed-id 하드코딩 없음·신규 마이그레이션 없음(현 2-도메인 스키마 그대로).
- 6→3 환원: ActorRole→RoleCode 집합 매핑은 DbRoleAuthorization 내부 private switch + EnumSet 상수(ADMIN_CODES)로. 당초 RoleCode.toActorRole() 폐기.

**검증**: `./gradlew.bat test` BUILD SUCCESSFUL·576 tests PASS·failures 0·errors 0·skipped 0·124 suites·회귀 0. baseline 575(D-119) + 1(AuthControllerIntegrationTest 5→6·(5) SELLER fail-closed 401 전환 + (6) seller_user 보유 SELLER 성공 신설) = 576.

### §1-A 판단별 α/β/γ 채택·기각 (결정 근거 영구화)

| 판단 | 채택 | 기각·근거 |
|---|---|---|
| 1 인가 입도 | **α Role coarse(ActorRole 3값)** | β Permission·RolePermission 세분 배선 기각 — Permission·RolePermission 프로덕션 소비 0·SecurityConfig hasRole 3종만·Repository 미주입. 지금 배선 시 소비처 없는 코드(기조 4). 트랙명이 "세분 권한"이나 판정 기준은 MVP 실사용이지 명칭 아님. |
| 2 Stub 교체 범위 | **α 3값 전부 실조회 fail-closed** | β BUYER만 실조회 기각 — 방어 목표=role 위조 차단. SELLER·ADMIN 데이터 부재 시 거부가 정확한 fail-closed(계정 없는 role 거부)·생성 트랙 도래 시 데이터 채워지면 자동 통과. β는 SELLER/ADMIN 위조 통과 잔존(방어 절반). |
| (2 판정 배선) | BUYER→user_role code=BUYER 존재 / ADMIN→user_role code∈{SUPER_ADMIN,ADMIN_OPERATOR} 존재 / SELLER→seller_user user_id 존재(role 무관) | 현 2-도메인 스키마(user=BUYER·ADMIN, seller_user=SELLER) 그대로·신규 마이그레이션 없음. |
| 3 6→3 환원 규칙 위치 | **폐기→DbRoleAuthorization 내부 private switch + EnumSet** | 당초 α RoleCode.toActorRole() enum 메서드 채택했으나 P1 정찰서 폐기 — fail-closed 판정은 "요청 ActorRole→조회 RoleCode 집합" 방향이라 6→3 환원(RoleCode→ActorRole) 미소비. toActorRole() 추가 시 호출자 없는 미사용 메서드(기조 4). β enum 귀속·γ 별도 Mapper 기각(소비처 1곳·과잉). |
| M2 exists 조회 기준 | **code 기준 파생 쿼리** | id 기준 기각 — seed-id 하드코딩·findByCode 왕복 회피. UserRole.role(@ManyToOne)→Role.code 중첩 프로퍼티(Role_Code) traverse로 code 직접 필터. |
| M4 테스트 세트 | **성공·실패 각 1(SELLER)** | SELLER fail-closed 401(seller_user 부재) + seller_user 보유 성공 신설. CLAUDE.md 성공·실패 흐름 각 최소 1 준수. |

### §2 결정 라운드 재진입
- 추가 정찰 1회 진입(blocker 2건: JWT role 전파·SELLER/ADMIN 데이터 경로). S급 추가 정찰 패시지 허용 규정에 따름.
- 결정 3 α→폐기 재진입(P1 미세결정 M1): toActorRole 소비처 부재 실측으로 당초 채택을 뒤집음. 사전 박제가 아닌 구현 직전 실측이 미사용 코드 오류를 예방한 사례.

### §8 carry-over
- **JWT 스냅샷 경계**: JWT는 로그인 시점 권한 스냅샷·토큰 유효기간 중 role 변경(부여·회수) 즉시 미반영. verify()는 서명·만료·role-null만 검증·DB 재조회 없음 → role 위조 방어점은 로그인 ④ 단 1곳. 요청별 재검증은 토큰 무효화·즉시 반영·성능·캐시가 얽힌 별도 설계 주제로 Track 35 범위 밖(외부 검토 합의). "왜 요청마다 재검증 안 했는가"의 근거 박제.
- **로그인 도메인 분리(3자 독립 로그인·자격 공간 분리)**: 현 2-도메인 스키마(user 통합·role 구분)와 다른 계정 도메인 분리 요구. user 스키마 재설계 + 가입 3경로 + 로그인 3경로 재설계라 별 트랙. SELLER/ADMIN 계정 생성 경로 부재가 선결. 백로그 S급.
- **SELLER/ADMIN 계정 생성 경로 부재**: seller_user·ADMIN user_role INSERT 프로덕션 경로 부재 실측(D-119 §8 연장)·별 트랙. 현재 실조회 시 해당 role 로그인은 데이터 부재로 정상 차단.
- **Permission/RolePermission 세분 권한**: 실 소비처 생기는 트랙에서(판단 1β 이연).

### §진입점 카드
1. **목적**: 로그인 role 위조 차단(fail-closed 인가).
2. **핵심 진입점**: `AuthService.login` ④ `roleAuthorization.isAuthorized` / `DbRoleAuthorization.isAuthorized`.
3. **핵심 SoT 메서드**: `UserRoleRepository.existsByUserIdAndRole_Code`·`existsByUserIdAndRole_CodeIn` / `SellerUserRepository.existsByUserId`.
4. **영향 범위**: `DbRoleAuthorization`(신규)·`StubRoleAuthorization`(삭제)·`UserRoleRepository`·`SellerUserRepository`·`AuthControllerIntegrationTest`(6건). 신규 마이그레이션·엔티티 무변경.
5. **패턴 재사용**: findByCode seed 조회(D-119)·user_role 배선(D-119)·소유권 authorize 2계층 상보(hasRole coarse + authorize fine·본 트랙 미변경)·인터페이스 격리 seam 교체 1점(D-118).
6. **트랩 주의**: (1) user_role updated_at 컬럼 부재(created_at만)·seller_user는 created_at/updated_at 둘 다 NOT NULL (2) FK ON DELETE RESTRICT로 테스트 cleanup은 user_role·seller_user·seller→user 순 선삭제 필수 (3) DbRoleAuthorization의 ActorRole switch는 default 없이 3값 전수(ActorRole 확장 시 컴파일 에러로 강제 노출).

### §특기
- 결정 근거 영구화 원칙 17회차(D-104~D-120). Track 35 = D-118 인증(who)·D-119 credential 생성에 이은 인가(what-role) 실동작화로 "가입→로그인→role 검증" 방어 사슬 완성.
- Track 35 트랩 후보(user_role updated_at 부재·FK RESTRICT cleanup 순서)는 §진입점 카드 트랩 주의 기재로 갈음 — live-traps.md 이관 규정(≥3건 누적·재발생) 미충족으로 이관 보류.

---

## D-121. Track 36 γ — 판매자 구성원 user 통합·seller_user user_id 단독 UNIQUE 전환 [ACTIVE]

**착수일**: 2026-07-04
**관련**: Track 36 / D-119(V11 role seed·user_role BUYER 배선·2-도메인 스키마)·D-120(DbRoleAuthorization 실조회·SellerUserRepository.existsByUserId·§8 seller_user INSERT 프로덕션 경로 부재 실측) / 기조 4(과잉개발 회피)·기조 5(실측 우선)

### §1 착수 요지 (Phase 1 한정·본문 박제는 Phase 4 실측 후)
Track 36(γ) = 판매자 구성원을 user 계정으로 통합하고 seller_user 매핑으로 도메인을 분리한다. 선결 제약으로 seller_user UNIQUE를 복합 (seller_id, user_id)→**user_id 단독**으로 전환해 "1 user = 1 seller"를 강제한다(이후 Phase resolver가 user.id→seller.id를 단건 해소하기 위한 선결). 정찰 실측(프로덕션 seller_user INSERT 경로 부재·중복 user_id fixture 부재·D-120 §8 연장·자체 재측정)으로 기존 데이터·테스트와 충돌 없음.

**Phase 1 산출**: 본 착수 헤더 · docs/track-36/RECON.md(Phase 1 정찰 실측) · Flyway V12__seller_user_user_id_unique.sql(seller_user user_id 단독 UNIQUE·단일 atomic ALTER).

**V12 실측 트랩(RECON §3 상술)**: 복합 uk_seller_user (seller_id, user_id)가 FK fk_seller_user_seller의 유일 지원 인덱스를 겸함 → bare DROP INDEX uk_seller_user 시 MariaDB ERROR 1553. seller_id 보상 인덱스(idx_seller_user_seller) 선행이 필수 → A안(단일 atomic ALTER: ADD idx_seller_user_seller + DROP uk_seller_user + ADD UNIQUE uk_seller_user_user_id) 채택·MariaDB 11.4 end-to-end 실측 통과. 역방향 ROLLBACK도 동일 트랩 대칭 존재(user_id 인덱스 선복원 필수·RECON §3.4)로 4-op 보상 주석 확정. STEP 3 검증 이전 정찰 단계에서 선발견(라이브 트랩 예방).

**본문 박제 예고**: 판단별 α/β/γ 채택·기각·§진입점 카드·최종 검증 수치는 구현·검증 완료 후 Phase 4에서 실측 기반 박제(기조 5).

### §1-B 결정 요지 (구현·검증 완료)
Track 36(γ) = 판매자 구성원을 user 계정으로 통합하고 seller_user 매핑으로 도메인 분리한다. "완전 독립 계정"(seller 전용 자격 저장소 신설) 요구는 폐기하고 "user 통합·진입점/토큰 스코프만 도메인 분리"로 재확정했다. 핵심 = resolver가 user.id를 seller_user 실조회로 seller.id에 단건 해소하도록 교정(passthrough 결함 해소)하고, 선결로 seller_user UNIQUE를 user_id 단독으로 전환("1 user = 1 seller").

- 선결 제약: Flyway V12로 seller_user 복합 UNIQUE(seller_id, user_id) → user_id 단독 UNIQUE 전환. user.id→seller.id 최대 1건 보장.
- 해소 배선: SellerUserRepository.findSellerIdByUserId(@Query·su.seller.id projection·Optional) 신설. HeaderSellerActorResolver가 user.id passthrough → 이 조회로 seller.id 해소. 해소 실패(빈 Optional=매핑 없는 user) → UnauthenticatedException(401) fail-closed.
- 무변경: JWT 4클래스(subject=user.id·role-중립)·3 authorize 본체(Claim·Inventory·OrderShipping·이미 seller.id 대조)·Buyer/Admin resolver·SellerUser 엔티티.
- 정합 결함 교정: SELLER 요청의 user.id를 seller_id 컬럼과 직접 대조하던 결함을 4개 통합테스트가 user.id==seller.id 우연일치로 은폐 중이었음. resolver 실 매핑 전환으로 결함 해소 + 테스트를 actorId≠seller_id 분리·seller_user 실 시드로 재작성해 은폐 제거.

**검증**: `./gradlew.bat test --rerun-tasks` BUILD SUCCESSFUL·suites 124·tests 576·failures 0·errors 0·skipped 0·회귀 0(캐시 무효화 실측). baseline 576(D-120) 동일 — 신규 @Test 0·fixture/DisplayName 정비만.

### §1-A 판단별 α/β/γ 채택·기각 (결정 근거 영구화)

| 판단 | 채택 | 기각·근거 |
|---|---|---|
| 1 자격 저장소 형태 | **γ user 통합(기존 user+seller_user 활용)** | α(seller.id=자격 PK): 판매사=계정 1개 강제 → seller_user 복수 구성원 모델과 배치·기각. β(seller_account 신설): 구성원별 독립 자격 저장소 → 소비 데이터 부재·계층 과잉(기조 4)·기각. γ = 신규 저장소 0·계층 최소. |
| 2 "완전 독립 계정" 요구 | **폐기·재정의** | 원 의도 아닌 중간 삽입 요구로 실측 확인(seller 자격이 이미 user에 귀속). "user 통합·진입점/토큰 스코프만 도메인 분리"로 재확정. seller 전용 자격 저장소 신설이 유지안의 실작업이나 소비 데이터 부재로 과잉. |
| 3 seller_user UNIQUE 전환 형태 | **α 단일 atomic ALTER(seller_id 보상 인덱스 선행)** | 당초 2문(ADD/DROP)은 복합 UNIQUE가 FK fk_seller_user_seller 유일 지원 인덱스 겸용이라 bare DROP 시 MariaDB ERROR 1553(실측). idx_seller_user_seller 선행 추가로 FK 지원 보존. B안(복합을 비-UNIQUE 강등) 기각 — user_id 전역 UNIQUE 이후 복합 prefix 이득 0·2컬럼 과잉(기조 4). |
| 4 복수 소속(1 user N seller) | **범위 밖(β 확장)** | 현 소비 데이터 없어 과잉(기조 4). 향후 user_id UNIQUE 제거 1건으로 덧쌓기(α→β 재작업 아님). |
| 5 해소 실패 처리 | **UnauthenticatedException(401) 재사용** | resolver 기존 유일 실패 표현 재사용·새 예외 미신설. 403은 resolver가 컨트롤러 내부 실행이라 filter-layer 403 핸들러 미도달·GlobalExceptionHandler 500 fallback에 삼켜짐 → 신규 @ExceptionHandler 필요(새 관례 신설이라 기각). 기존 관례로 도달 가능한 정확한 fail-closed = 401. |
| 6 진입점 물리 분리(endpoint 3분할) | **범위 밖(프론트 트랙)** | 백엔드는 단일 endpoint + role param로 이미 진입점 분리 성립(role별 검증 DbRoleAuthorization). endpoint 3분할은 프론트 표면 사안. |

### §2 결정 라운드 재진입
- 유지/완화 이분법 → γ(제3안) 재진입: 별도 정찰(JWT subject 파급)로 "JWT subject=user.id 전제 부재·3 authorize 이미 seller.id 대조" 실측 → seller 자격 저장소 신설 없이 resolver 1점 해소로 성립함을 확인, 유지(스키마 신설)·완화(자격 공유)를 모두 넘어선 γ 도출.
- V12 SQL 재진입: 당초 2문 SQL이 ERROR 1553으로 실패(정찰 docker 실측) → A안(보상 인덱스 선행 atomic ALTER)으로 교정. 역방향 ROLLBACK도 대칭 트랩 존재 → 4-op 보상 주석. STEP 3 검증 이전 정찰 단계 선발견(라이브 트랩 예방).

### §8 carry-over
- **복수 소속(1 user N seller)**: 현 소비 데이터 부재·과잉. 수요 도래 시 user_id UNIQUE 제거 1건으로 확장(덧쌓기·재작업 아님). 백로그 β.
- **입점·구성원 등록 경로**: seller_user·seller INSERT 프로덕션 경로 여전히 부재(D-119·D-120 §8 연장). 판매자 등록 트랙에서 신설 시 "1 user=1 seller"(V12) 전제 반영 필수. 별 트랙.
- **로그인 진입점 물리 분리(endpoint 3분할)**: 백엔드 단일 endpoint+role param로 성립 → 프론트 트랙.
- **SellerActorResolver·Seller 컨트롤러 레거시 Javadoc 표류**: X-Seller-Id 헤더 stub 기술이 SecurityContext 대체 후에도 잔존(HeaderSellerActorResolver는 본 트랙서 갱신됨). 나머지 컨트롤러 주석 표류는 B급 백로그.
- **클래스명 rename 이연**: HeaderSellerActorResolver는 헤더 파싱 아닌 SecurityContext 해소이나 클래스명 레거시 유지(rename 이연).

### §진입점 카드
1. **목적**: 판매자 구성원을 user 계정에 통합하고 seller_user 실 매핑으로 user.id→seller.id 해소(passthrough 정합 결함 교정).
2. **핵심 진입점**: `HeaderSellerActorResolver.resolve` (user.id → findSellerIdByUserId → seller.id·해소 실패 시 401).
3. **핵심 SoT 메서드**: `SellerUserRepository.findSellerIdByUserId`(@Query su.seller.id projection·Optional).
4. **영향 범위**: V12(seller_user user_id 단독 UNIQUE)·SellerUserRepository(M·조회 신설)·HeaderSellerActorResolver(M·해소 배선)·4 통합테스트(Inventory·Shipping·Delivery·Claim·seller_user 시드 재작성)·SellerUserRepositoryTest(#2 실검증 전환). JWT·3 authorize 본체·SellerUser 엔티티 무변경.
5. **패턴 재사용**: @ManyToOne.id projection @Query(OrderItemRepository.findOrderIdById 선례)·common→seller repo 의존(DbRoleAuthorization 선례·D-120)·FK_CHECKS=0 try-finally 시드(LT-02)·role code SELECT seed(seed-id 하드코딩 회피·D-119).
6. **트랩 주의**: (1) seller_user 복합 UNIQUE가 FK fk_seller_user_seller 유일 지원 인덱스 겸용 → bare DROP INDEX 시 ERROR 1553·seller_id 보상 인덱스 선행 필수 (2) 역방향 ROLLBACK도 대칭 트랩(user_id 인덱스 선복원 4-op) (3) user.id==seller.id 우연일치는 통합테스트 은폐성 GREEN 유발 → actorId≠seller_id 분리 시드로 실 매핑 강제.

### §특기
- 결정 근거 영구화 18회차(D-104~D-121). γ = 유지/완화 이분법을 넘어선 제3안 도출·정찰 docker 실측이 V12 라이브 트랩(ERROR 1553)을 사전 포착한 사례.
- Track 36 트랩 후보(1553 정·역·은폐성 GREEN)는 §진입점 카드 트랩 주의 + RECON §3 기재로 갈음 — live-traps.md 이관 규정(≥3건 누적·재발생) 미충족.

---

## D-122. Track 37 — SELLER 계정 provisioning 경로 신설(관리자 주도)·seller/seller_user 원자 INSERT·Seller 쓰기 도입 [ACTIVE]

**결정일**: 2026-07-04
**관련**: Track 37 / D-119(V11 role seed·findByCode·Buyer 셀프가입 POST /api/v1/users·2-도메인 스키마)·D-120(DbRoleAuthorization 실조회·SellerUserRepository.existsByUserId·§8 SELLER/ADMIN 계정 생성 경로 부재 실측)·D-121(seller_user user_id 단독 UNIQUE V12·findSellerIdByUserId·HeaderSellerActorResolver 해소·§8 "판매자 등록 트랙에서 1 user=1 seller 전제 반영 필수")·D-92 Q3(actor 소유권 Service 진입부)·D-105 §2 Q2(Admin base path 없음·절대경로)·D-59(Seller read-only) / 기조 1(운영 용이성)·기조 4(과잉개발 회피)·기조 5(실측 우선)

### §1 결정 요지 (구현·검증 완료 실측)
D-119~121이 인가 소비처(RBAC 실조회·seller_user user.id→seller.id 해소)를 완성했으나 그 인가가 대조할 seller/seller_user 데이터 공급원(프로덕션 INSERT 경로)이 전무하던 공백을 종결한다. 운영관리자 주도(A 경로: 관리자 요청 접수→계정 생성→판매자 배포→판매자 첫 로그인) provisioning endpoint로 seller 입점 INSERT + 최초 owner seller_user INSERT를 단일 원자 트랜잭션으로 신설한다. ADMIN user_role 부여·Manager/Staff 구성원 등록·B 셀프가입 경로는 별 트랙 분리.

- 진입점: POST /api/v1/admin/sellers(hasRole("ADMIN")·SecurityConfig /api/v1/admin/** 기존 규칙 포함·무변경). 201 + body(sellerPublicId)·Location 헤더 없음(Buyer signup 일관).
- Seller 쓰기 도입: Seller.create(companyName, businessNo, ceoName, contactEmail, contactPhone, SellerStatus status) 정적 팩토리 신설. read-only 박제("Track 4 이연"·protected 생성자+@Getter만) 해제. NOT NULL 대상(companyName·ceoName·status) null 가드·SellerUser.create/UserRole.create 스타일 일관.
- 원자 provisioning: SellerProvisioningService(@Transactional) — ownerUserId existsById 검증(404) → status 초기값 검증(PENDING·ACTIVE만·400) → findByCode(SELLER_OWNER) 조회(seed 누락 500) → seller save → seller_user saveAndFlush(DataIntegrityViolationException catch → 409). owner 없는 seller(고아 aggregate) 불가·전체 롤백.
- 무변경: SecurityConfig·PaymentGateway·JWT·소유권 authorize 3곳·SellerUser 엔티티·enum 바인딩 400 핸들러(HttpMessageNotReadable.handleMalformed 재사용).

**검증**: `./gradlew.bat test --rerun-tasks` BUILD SUCCESSFUL·suites 125·tests 582·failures 0·errors 0·skipped 0·회귀 0(캐시 무효화 실측·4m6s). baseline 576(D-121) + 6(AdminSellerControllerIntegrationTest: ①ADMIN 성공 seller1·seller_user1 role=SELLER_OWNER DB검증 ②비ADMIN 403 ③owner 미존재 404 ④V12 중복소속 409 ⑤status=SUSPENDED 400 ⑥롤백 seller count 0 원자성 실증) = 582.

### §1-A 판단별 α/β/γ 채택·기각 (결정 근거 영구화)

| 판단 | 채택 | 기각·근거 |
|---|---|---|
| 1 트랙 스코프 | **β seller 계열(입점+최초 owner seller_user)만** | α 3경로(seller/seller_user/ADMIN user_role) 전부 기각 — ADMIN 부여는 seller INSERT·Seller 박제 해제와 무관한 독립 경로(user_role INSERT만)·인증 성격 상이(기존 ADMIN만 부여 vs 입점 셀프신청). 한 트랙에 성격 다른 인증 결정 2개 혼입 시 결정 라운드 비대·§1-A 판단표 혼탁. γ(seller+owner 최소·추가 구성원 이연)는 β와 seller 계열 범위 동일이나 ADMIN 분리 명시가 β. seller 입점↔owner는 원자적 결속(고아 aggregate 불가)이라 분리 불가·함께. |
| 2 Seller 쓰기 도입 방식 | **α Seller.create 정적 팩토리** | β 생성자 public+빌더 기각 — 필드 6개에 빌더 과함(과잉). γ Service native INSERT(테스트 시드 승격) 기각 — JPA 우회로 Aggregate 캡슐화 파괴·D-01 경계 위배. α = SellerUser.create·UserRole.create 기확립 팩토리 패턴 일관·null 가드/불변식 캡슐화 지점 확보. |
| 3 status 초기값 결정 | **α-2 파라미터 주입(호출자 결정)** | α-1 create() 내부 PENDING 고정 기각 — 초기 추천은 셀프신청 심사대기 전제로 PENDING 고정이었으나, A 경로(운영관리자가 요청 검토 후 생성)는 이미 승인 판단 완료 상태라 ACTIVE 직행이 자연·PENDING 강제는 A 흐름과 불일치. status 결정을 팩토리에 가두지 않고 호출자 위임 시 A(ACTIVE)·B 셀프신청(PENDING→심사→ACTIVE) 두 경로를 같은 팩토리로 커버(과잉개발 회피·seam 확정). 내부 null 가드는 유지(NOT NULL 방어). |
| 4 provisioning 진입점 형태 | **α ADMIN endpoint POST /api/v1/admin/sellers** | β 인가 없는 형태로 진입점만 확보·인가 이연 기각 — API 선공개+인가 다음 트랙 = 운영 위험(라이브 트랩). γ 컨트롤러 없이 Service만 기각 — 호출자 없음·Controller layer 테스트 부재로 provisioning flow 미검증·Track 목적 절반 미달. A 흐름이 "운영관리자가 생성"이라 진입점부터 ADMIN 인가 정합·hasRole("ADMIN") 3종 재사용. |
| 4-1 owner userId 존재 검증 | **α-1 앱단 existsById 선검증+404** | α-2 DB FK 위반 위임(PersistenceException) 기각 — FK 위반 예외는 메시지 불친절·원인 불명확. 관리자 도구는 명확한 실패 사유(404 USER_NOT_FOUND) 필요. |
| 5 owner seller_user 역할(roleId) | **α SELLER_OWNER 고정** | β 파라미터 주입(관리자 역할 선택) 기각 — 입점 시 최초 등록자는 정의상 owner(Aggregate invariant). MANAGER/STAFF 선택 허용 시 owner 없는 seller 생성 가능 = 모델 파괴. "누가 구성원을 초대·권한 관리하는가"가 owner 부재 시 미설명. roleId는 findByCode(SELLER_OWNER) seed 조회(BUYER 패턴 재사용). |
| 6 트랜잭션 원자성·INSERT 순서 | **α 단일 @Transactional** | β seller·seller_user 별도 트랜잭션 기각 — **owner 없는 Seller = Aggregate 불변식 위반**. 부분 실패 시 고아 seller 잔존 = 데이터 정합 파괴. FK RESTRICT·NOT NULL로 순서 강제(user·role 선존재→seller save로 id 확보→seller_user). Buyer 셀프가입 User+UserRole 단일 트랜잭션 선례 일관. |
| 7 status 유효성 제한 | **β PENDING·ACTIVE만 허용** | α 전 값 허용(관리자 재량) 기각 — **Lifecycle invariant상 생성 가능 초기 상태는 PENDING·ACTIVE뿐**. SUSPENDED(운영 중 seller 정지)·TERMINATED(종료 절차 완료)는 이미 운영 이력 존재를 전제하는 상태라 신규 생성 초기값으로 부적합. 근거를 "생성 직후 부적합(UX)"이 아닌 상태 머신 설계로 명문화(외부 검토 보강 반영). |
| 8 통합테스트 범위 | **β α6종(성공·403·404·409·400·롤백)** | α 최소 4종(성공·403·404·409) 기각 — status 경계(400)·롤백 검증이 T2·T3·T4 실측 트랩의 실증. 특히 롤백 검증은 사안6 원자성의 CI 게이트 박제 가치. 이번 트랙 핵심 가치 = Atomic provisioning이라 미검증 시 핵심 미테스트. |
| 9 중복 소속(V12) 가드 설계 | **옵션 A DB 제약 기반(pre-check 제거·saveAndFlush catch→409)** | 옵션 B P2 pre-check(existsByUserId→seller INSERT 전 409) 기각·옵션 C 하이브리드(pre-check+DB 백스톱) 기각 — pre-check 하에서는 409 경로에 seller INSERT가 발생하지 않아 test-6(롤백)이 @Transactional 제거해도 통과 = **우연일치 테스트**(d4c314b "우연일치 은폐 제거"·기조 5 위반). pre-check 하 seller INSERT 후 seller_user 결정론적 실패 경로 부재(FK 3개 선충족·uk는 pre-check가 배제). 옵션 A만 seller INSERT→seller_user 실패→롤백을 실제 관통해 원자성 실증(@Transactional 제거 시 test-6 실패)·DB uk가 동시 provisioning까지 방어(최종 방어선). 비용=흔치 않은 중복 시 seller INSERT 1회 낭비되나 롤백으로 잔존 0·관리자 저빈도 경로라 무시 가능. |
| (참고) AdminActorResolver 호출 생략 | **생략** | 기존 Admin 컨트롤러 5종은 AdminActorResolver.resolve 호출하나 provisioning은 actor id를 소비하지 않음(생성 주체 식별 불요·SecurityConfig hasRole가 실 인가 강제). 미소비 배선 추가 = 과잉개발(기조 4). 기존 5종과 다른 점 명시. |

### §1-B 구현 트랩(실측)
1. **P2 pre-check ↔ P6 롤백 테스트 진정성 충돌(판단 9 귀결)**: 초안 P2가 existsByUserId pre-check로 V12를 seller INSERT 전 차단하도록 명시했으나, 같은 시나리오로 P6 test-6이 seller 롤백(원자성)을 요구 → 동시 진짜 충족 불가. pre-check가 있으면 409 경로에서 seller INSERT 자체가 없어 test-6이 @Transactional과 무관하게 통과(우연일치). Claude Code가 구현 착수 전 실측으로 포착 → 옵션 A(pre-check 제거·DB 제약 기반)로 수렴. saveAndFlush로 즉시 flush해야 catch 지점 확보(flush 없으면 트랜잭션 커밋 시점까지 예외 지연).
2. **enum 바인딩 400 경로**: 잘못된 status 문자열(ENUM 밖)은 역직렬화 단계에서 HttpMessageNotReadableException → 기존 handleMalformed(400 MALFORMED_REQUEST) 흡수. status 값 제한(PENDING·ACTIVE) 위반은 Service 가드 IllegalArgumentException → 동일 handleMalformed(400). 신규 예외 핸들러 불요·기존 재사용 확인.

### §2 결정 라운드 재진입
- 판단 3(status): 초기 α-1(PENDING 고정) 추천 → 사용자 운영 시나리오(A 관리자 주도 생성·status 선택 요구·B 셀프신청 MVP 후) 명시로 α-2(파라미터 주입)로 정정. 팩토리 고정이 A 흐름과 불일치함을 확인·호출자 위임이 A·B 공통 커버.
- 판단 7(status 제한): 외부 검토가 근거를 "생성 직후 부적합(UX)"에서 "Lifecycle invariant"로 격상 권고 → 재검토 아닌 근거 명문화 내재화(β 유지·근거 교체).
- 판단 9(V12 가드): Claude Code가 P2 pre-check ↔ P6 롤백 진정성 충돌을 구현 착수 전 실측 포착 → 옵션 A로 수렴. 사전 확정 설계(pre-check)를 실측이 뒤집어 우연일치 테스트를 예방한 사례(D-120 toActorRole 폐기 계열).
- 외부 검토 흡수: 사안 3~8 전면 채택(이견·부분수용 0)·사안 5·7 근거 명문화 2건 보강 내재화.

### §3 구현 산출 (실측·라인은 커밋 전 파일 대조 재확인)
**신설 production 6**:
- `seller/service/SellerProvisioningService.java` — @Transactional provision(existsById 404 → validateInitialStatus 400 → findByCode(SELLER_OWNER) → sellerRepository.save → sellerUserRepository.saveAndFlush catch DataIntegrityViolationException→409).
- `seller/controller/AdminSellerController.java` — POST /api/v1/admin/sellers·@RequestBody @Valid·201·Location 없음·base path 없음 절대경로(D-105 §2 Q2).
- `seller/controller/request/SellerProvisioningRequest.java` — record(seller 필드+status+ownerUserId·@Size DB length SoT·status/ownerUserId @NotNull).
- `seller/controller/response/SellerProvisioningResponse.java` — record(sellerPublicId).
- `seller/exception/SellerUserAlreadyExistsException.java` — 409·SELLER_USER_ALREADY_EXISTS.
- `user/exception/UserNotFoundException.java` — 404·USER_NOT_FOUND.

**수정 production 2**:
- `seller/entity/Seller.java` — create(...) 정적 팩토리 추가(companyName·ceoName·status null 가드)·"Track 4 read-only 이연" Javadoc 갱신·protected no-args+@Getter 유지.
- `common/web/GlobalExceptionHandler.java` — handleUserNotFound(404·USER_NOT_FOUND)·handleSellerUserAlreadyExists(409·SELLER_USER_ALREADY_EXISTS) 매핑 추가.

**신설 test 1**:
- `test/seller/controller/AdminSellerControllerIntegrationTest.java` — 6종(①ADMIN 성공 DB검증 ②비ADMIN 403 ③owner 미존재 404 ④V12 409 ⑤status=SUSPENDED 400 ⑥롤백 seller count 0). user·seller_user 실 시드·우연일치 은폐 금지·LT-02 try-finally.

**무변경 확인 2**: SecurityConfig(/api/v1/admin/**→hasRole("ADMIN")가 신규 경로 포함)·enum 바인딩 400(HttpMessageNotReadableException→handleMalformed 재사용).

### §4 핵심 메서드
- **SellerProvisioningService.provision(@Transactional)**: userRepository.existsById(ownerUserId) false→UserNotFoundException(404) → validateInitialStatus(status·PENDING/ACTIVE 아니면 IllegalArgumentException 400) → roleRepository.findByCode(SELLER_OWNER).orElseThrow(IllegalStateException·seed 누락 500) → sellerRepository.save(Seller.create(...)) → sellerUserRepository.saveAndFlush(SellerUser.create(seller, ownerUserId, owner.getId())) catch DataIntegrityViolationException→SellerUserAlreadyExistsException(409·전체 롤백) → SellerProvisioningResponse(seller.getPublicId()).
- **원자성 실증**: test-6이 @Transactional 제거 시 실패하도록 구성(pre-check 없이 seller INSERT가 실제 발생→seller_user 실패→롤백 관통). 우연일치 은폐 없음.

### §진입점 카드
1. **목적**: 인가(D-119~121)가 참조할 seller/seller_user 데이터 공급원 신설 — 운영관리자 주도 seller 입점+최초 owner 원자 provisioning.
2. **핵심 진입점**: `AdminSellerController`(POST /api/v1/admin/sellers) · `SellerProvisioningService.provision`.
3. **핵심 SoT 메서드**: `Seller.create`(쓰기 도입·status null 가드) · `SellerUser.create(Seller, Long userId, Long roleId)`(재사용) · `RoleRepository.findByCode(SELLER_OWNER)` · `UserRepository.existsById` · `SellerUserRepository`(saveAndFlush·uk_seller_user_user_id V12 방어선).
4. **영향 범위**: seller 도메인 신규(service·controller·request·response·exception)·user/exception(UserNotFoundException)·common/web(GlobalExceptionHandler). Seller 엔티티 쓰기 도입(read-only 박제 해제). SecurityConfig·JWT·소유권 authorize 3곳·SellerUser 엔티티·Flyway 무변경.
5. **패턴 재사용**: Buyer 셀프가입 register 흐름(D-119·findByCode seed 조회·단일 트랜잭션 role 배선)·D-92 Q3 Service 진입부 검증·D-105 §2 Q2 Admin 절대경로·201 Created body-only(Buyer signup·Location 생략)·409 핸들러(EmailAlreadyExists 템플릿)·SellerUser.create 팩토리(D-121)·@Size DB length SoT(D-119).
6. **트랩 주의**: (1) V12 가드는 pre-check 아닌 saveAndFlush catch→409(pre-check는 롤백 테스트 우연일치 유발·§1-B-1) (2) SELLER_OWNER seed 부재 시 500(V11 미적용 배포 오류) (3) FK RESTRICT·NOT NULL로 INSERT 순서 강제(user·role 선존재→seller→seller_user) (4) status는 PENDING·ACTIVE만(SUSPENDED/TERMINATED는 운영 이력 전제·Lifecycle invariant) (5) AdminActorResolver 미호출(provisioning actor id 미소비·기존 Admin 5종과 상이) (6) LT-02 FK 시드 그래프(user→seller→role) try-finally.

### §8 carry-over
- **ADMIN user_role 부여 경로**: Track 37 β 스코프에서 분리(백로그 상단 승계). ADMIN 계정에 user_role(SUPER_ADMIN·ADMIN_OPERATOR) INSERT 프로덕션 경로 여전히 부재. 성격상 입점 셀프신청과 상이(기존 ADMIN만 부여)·별 트랙.
- **Manager/Staff 구성원 등록**: owner가 이후 추가하는 구성원(SELLER_MANAGER·SELLER_STAFF) 등록 경로. 추가 API·권한 정책·역할별 테스트 동반이라 별 유스케이스·별 트랙.
- **B 셀프가입 경로(판매관리자 셀프신청→승인)**: MVP 후 진입. 호출자가 status=PENDING 주입→심사→ACTIVE 전이(같은 Seller.create·SellerProvisioningService 팩토리 재사용). seller 심사·승인 워크플로 신설 동반.
- **status ACTIVE 승격 심사 경로**: A 경로는 관리자가 생성 시점에 status 직접 결정(ACTIVE 직행 가능). PENDING→ACTIVE 전이(심사 승인) 별도 진입점은 B 셀프가입 트랙과 동반.
- **HeaderSellerActorResolver rename**: D-121 §8 연장·클래스명 레거시 유지(표면 정리 트랙).
- **Track 37 신규 트랩**: P2 pre-check↔롤백 진정성 충돌(§1-B-1). 동일 트랩 재발생 시 LT-06~ 이관.

### §특기
- 결정 근거 영구화 원칙 19회차(D-104~D-122). Track 37 = D-119(credential 생성)·D-120(인가 실동작)·D-121(seller_user 해소)이 완성한 인가 소비처의 **데이터 공급원** 신설로 "가입→로그인→role 검증→데이터 존재" 사슬 완결. 인가가 빈 테이블 위에서 돌던 갭 해소.
- Seller 엔티티 read-only 박제(D-59·"Track 4 이연") 최초 쓰기 해제 — 인가 데이터 공급원 부재라는 실재 결함 처치가 근거(범위 확대 아님·기조 4).
- Claude Code 실측이 사전 확정 설계(P2 pre-check)를 뒤집어 우연일치 테스트를 예방(§1-B-1·판단 9). "구현 직전 실측이 미검증 설계 오류를 예방"의 D-120(toActorRole 폐기)·D-119(requireActorId package-private) 계열 3번째 사례.
- Track 37 트랩 후보(pre-check↔롤백 진정성 충돌)는 §1-B-1 + §진입점 카드 트랩 주의 기재로 갈음 — live-traps.md 이관 규정(≥3건 누적·재발생) 미충족.

---

## D-123. Track 38 — ADMIN 계정 공급 경로 신설(최초 SUPER_ADMIN 부트스트랩·운영 ADMIN_OPERATOR provisioning) [ACTIVE]

**결정일**: 2026-07-04
**관련**: Track 38 / D-118(JWT coarse ActorRole·SecurityConfig hasRole)·D-119(V11 role seed·findByCode·User.assignPasswordHash·BUYER role 배선·@Size DB SoT)·D-120(DbRoleAuthorization 실조회·existsByUserIdAndRole_Code·existsByUserIdAndRole_CodeIn·fail-closed·ADMIN 판정 code∈{SUPER_ADMIN,ADMIN_OPERATOR})·D-122(AdminSellerController 절대경로·SellerProvisioningService 원자 트랜잭션·saveAndFlush catch→409·§8 "ADMIN user_role 부여 경로 분리·백로그 상단 승계")·D-92 Q3(actor 소유권 Service 진입부)·D-105 §2 Q2(Admin base path 없음·절대경로) / 기조 1(운영 용이성)·기조 4(과잉개발 회피)·기조 5(실측 우선)

### §1 결정 요지 (구현·검증 완료 실측)
D-119~122가 인가 사슬(credential 생성·RBAC 실조회·seller 데이터 공급원)을 완성했으나 ADMIN user_role INSERT 프로덕션 경로가 전무해, DbRoleAuthorization(D-120)의 ADMIN 판정이 빈 데이터 위에서 돌고 /api/v1/admin/** 전체가 호출 불가한 구조적 deadlock을 종결한다. 두 공급 경로를 분리 신설한다: (1) 최초 SUPER_ADMIN을 앱 기동 시 환경변수로 1회 공급하는 부트스트랩(순환 탈출), (2) 운영 중 SUPER_ADMIN이 기존 회원에 ADMIN_OPERATOR를 부여하는 provisioning endpoint. Manager/Staff·B 셀프신청은 별 트랙 유지.

- 부트스트랩: SuperAdminBootstrapRunner(CommandLineRunner·@Transactional). 존재확인(existsByRole_Code(SUPER_ADMIN)) → 부재 시에만 env 검증(Fail Fast) → User.create + UserRole(SUPER_ADMIN) 원자 생성. env = ADMIN_BOOTSTRAP_EMAIL·ADMIN_BOOTSTRAP_PASSWORD(application.yml admin.bootstrap.*). 생성 전용·수정 금지·복구 아님.
- endpoint: POST /api/v1/admin/admin-operators(hasRole("ADMIN") coarse 게이트·SecurityConfig 무변경). 201 + body(userPublicId)·Location 없음. 대상 = 기존 user에 ADMIN_OPERATOR role 부여(신규 user 생성 아님).
- 세분 인가: JWT가 SUPER_ADMIN authority 미보유 → 서비스 진입 시 existsByUserIdAndRole_Code(caller, SUPER_ADMIN) 실조회 fail-closed(403). AdminActorResolver.resolve로 caller userId 획득.
- 중복 가드: uk_user_role(user_id, role_id)(V1) 최종 방어선·saveAndFlush catch DataIntegrityViolationException→409(D-122 옵션 A 패턴 정합).
- 무변경: SecurityConfig(/api/v1/admin/**→hasRole("ADMIN") 기존 규칙이 신규 경로 포함)·JWT 4클래스·소유권 authorize 3곳·User/UserRole 엔티티·Flyway.

**검증**: `./gradlew.bat test --rerun-tasks` BUILD SUCCESSFUL·suites 127·tests 591·failures 0·errors 0·skipped 0·회귀 0(캐시 무효화 실측·4m23s). baseline 582(D-122) + 9(SuperAdminBootstrapRunnerIntegrationTest 3: A생성·B멱등불변·C env blank Fail Fast / AdminOperatorControllerIntegrationTest 6: ①SUPER_ADMIN 성공 ②BUYER 필터 403 ③비SUPER_ADMIN 서비스 403 ④대상 404 ⑤중복 409 ⑥409 후 매핑 1건 원자성) = 591.

### §1-A 판단별 α/β/γ 채택·기각 (결정 근거 영구화)

| 판단 | 채택 | 기각·근거 |
|---|---|---|
| 1 최초 ADMIN 부트스트랩 방식 | **β CommandLineRunner(.env·멱등·Fail Fast)** | α Flyway seed(user+user_role INSERT) 기각 — BCrypt 해시를 리포에 박제·전 환경 동일 초기 비번(시크릿 커밋 철칙 위반·기조 5). γ 수동 SQL 기각 — 매 해시 생성·SQL 실행 수작업·재현성 낮음·단일 운영자 편의 저해(기조 1). β = .env 변수화로 시크릿 회피·멱등 재기동 안전·자동 재현. |
| 2-1 endpoint 인가 주체 | **α SUPER_ADMIN만** | β ADMIN 전체(SUPER_ADMIN·ADMIN_OPERATOR) 허용 기각 — ADMIN_OPERATOR가 ADMIN을 증식하는 권한 상승 경로. 권한 부여=신뢰 기점 조작이라 최소권한. SecurityConfig 세분 매처 불가(JWT coarse)라 서비스단 fail-closed로 강제. |
| 2-2 생성 대상 role 범위 | **β ADMIN_OPERATOR만** | α SUPER_ADMIN·ADMIN_OPERATOR 둘 다 기각 — SUPER_ADMIN(최상위 신뢰 기점)은 부트스트랩(.env)으로 단일화·endpoint는 운영 ADMIN 공급만. SUPER_ADMIN endpoint 생성 허용 시 최상위 증식 경로. 역할 분리 명확. |
| 3 부트스트랩 멱등 판정 기준 | **β SUPER_ADMIN 존재 여부(existsByRole_Code)** | α 부트스트랩 email 존재 여부 기각 — 목표는 "SUPER_ADMIN 부재 방지"이지 특정 email 고정 아님. email 변경해도 최상위 있으면 중복 생성 안 함. 단 복구 기능 아님 명문(§1-B invariant). |
| 4 endpoint 중복 부여 가드 | **β saveAndFlush catch→409** | α pre-check(existsBy) 기각 — D-122 §1-B-1 실증: pre-check는 롤백 진정성 테스트 우연일치 유발. DB 제약을 최종 방어선으로 신뢰하는 catch만 원자성 관통·동시 요청 방어. Track 37 옵션 A 패턴 일관. |
| A 부트스트랩 실패 정책 | **α Fail Fast(기동 중단)** | β 로그만 남기고 기동 기각 — bootstrap 실패=ADMIN 공급원 확보 실패→Admin API 전체 무력화. 초기화 실패 은폐한 채 부팅이 더 위험(외부 검토 제기·채택). env blank·seed 누락·INSERT 실패 전부 IllegalStateException 전파→Spring Boot 기동 중단. |
| B enable flag 여부 | **α 항상 실행+skip(flag 없음)** | β BOOTSTRAP_ADMIN_ENABLED flag 기각 — 멱등(판단 3)이 이미 재실행 안전 보장·flag는 중복 방어. flag off 상태 최초 기동 시 오히려 deadlock 재현(새 실패 지점 추가). 단일 운영자 결정 지점 최소화(기조 1). 의도는 Runner Javadoc+D-123 박제로 대체. |
| (인가 구현 방식) | **SecurityConfig 무변경 + 서비스 진입 DB 실검증(fail-closed)** | hasRole("SUPER_ADMIN") 매처 불가 실측 — JWT는 coarse ActorRole(BUYER/SELLER/ADMIN)만 담아 SUPER_ADMIN·ADMIN_OPERATOR 구분 authority 없음. /api/v1/admin/**→hasRole("ADMIN")를 1차 코어스 게이트로 두고 existsByUserIdAndRole_Code(caller,SUPER_ADMIN)로 세분 인가(D-120 fail-closed 정합). |
| (AdminActorResolver 호출) | **호출(Track 37과 상이)** | D-122 provisioning은 actor id 미소비로 AdminActorResolver 미호출이었으나, Track 38 세분 인가(SUPER_ADMIN 실조회)에 caller userId가 필수라 AdminActorResolver.resolve 호출. 기존 Admin 5종 패턴 복귀·D-122와 다른 점 명시. |

### §1-B 구현 트랩·invariant(실측)
1. **부트스트랩 실행 순서 트랩(판단 A·3 귀결)**: 플랜은 "env 선검증→존재확인" 순이었으나, 이 순서면 이미 SUPER_ADMIN 존재해도 env blank 시 예외를 던져 "이미 존재 시 무작업(skip)" 계약과 충돌. Claude Code가 구현 착수 전 실측 포착 → **존재확인 first → 부재 시에만 env Fail Fast**로 교정. 이 순서만이 멱등·무작업·Fail Fast 3요건 동시 충족(D-122 판단 9·D-120 toActorRole 폐기 계열 4번째).
2. **테스트 컨텍스트 Fail Fast 트랩**: Runner 상시 실행 + 기존 @SpringBootTest가 SUPER_ADMIN 미seed → 전 컨텍스트 로드 실패(ZslabMallApplicationTests가 카나리아). build.gradle.kts test task에 더미 ADMIN_BOOTSTRAP_* env 주입으로 해소(프로파일 무관·프로덕션 코드 무변경·enable flag 금지 설계 보존·각 컨텍스트가 실 startup 경로로 SUPER_ADMIN 1회 생성 겸사 검증). @DataJpaTest는 CommandLineRunner 미실행이라 무관.
3. **invariant(외부 검토 부분수용 박제)**: 부트스트랩은 (a) 생성만 담당·수정 절대 금지(이미 SUPER_ADMIN 존재 시 email/password 덮어쓰기 안 함) (b) 계정 복구 기능 아님(복구는 별도 운영 절차). "SUPER_ADMIN row 존재"≠"복구 가능한 SUPER_ADMIN 존재"·계정 disable/비번 분실 복구는 부트스트랩 책임 밖.

### §2 결정 라운드 재진입
- 판단 A·B: 외부 검토가 추가 안건으로 제기(부트스트랩 실패 정책·enable flag 여부) → A(Fail Fast)·B(flag 없음) 채택. 초기 결정 라운드에 없던 안건을 검토가 보강.
- 판단 3: 외부 검토 부분수용 — β 유지하되 "복구 기능 아님" invariant 명문화(§1-B-3). "SUPER_ADMIN row 존재≠복구 가능 존재" 시나리오는 별도 운영 절차로 분리.
- 부트스트랩 순서: 플랜(env 선검증 first)을 Claude Code 실측이 뒤집어 존재확인 first로 교정(§1-B-1). 사전 확정 플랜이 무작업 계약과 충돌함을 구현 직전 실측이 예방.
- 외부 검토 보완 3건 내재화: Runner Javadoc "왜 항상 실행" 의도 명시·skip/create INFO 로그(email/password 미출력)·env 검증 위치(존재확인 후로 순서 교정 반영).

### §3 구현 산출 (실측·라인은 커밋 전 파일 대조 재확인)
**신설 production 7**:
- `auth/bootstrap/SuperAdminBootstrapRunner.java` — CommandLineRunner·@Transactional run(존재확인 skip→env Fail Fast→User.create+assignPasswordHash+UserRole(SUPER_ADMIN) 원자)·INFO 로그·의도 Javadoc.
- `auth/controller/AdminOperatorController.java` — POST /api/v1/admin/admin-operators·@RequestBody @Valid·AdminActorResolver.resolve→callerUserId·201·base path 없음 절대경로(D-105 §2 Q2).
- `auth/service/AdminOperatorProvisioningService.java` — @Transactional provision(caller SUPER_ADMIN 실조회 403 → findById 404 → findByCode(ADMIN_OPERATOR) → saveAndFlush catch→409).
- `auth/controller/request/AdminOperatorProvisioningRequest.java` — record(@NotNull Long userId).
- `auth/controller/response/AdminOperatorProvisioningResponse.java` — record(userPublicId).
- `auth/exception/SuperAdminRequiredException.java` — 403·FORBIDDEN(GlobalExceptionHandler에서 CODE_FORBIDDEN 재사용·필터 계층 403과 code 통일).
- `auth/exception/AdminOperatorAlreadyExistsException.java` — 409·ADMIN_OPERATOR_ALREADY_EXISTS.

**수정 production 4**:
- `auth/repository/UserRoleRepository.java` — existsByRole_Code(RoleCode) 추가(부트스트랩 멱등 판정·role.code traverse·seed-id 하드코딩 회피).
- `common/web/GlobalExceptionHandler.java` — handleSuperAdminRequired(403)·handleAdminOperatorAlreadyExists(409) + code 상수 2 + import + 클래스 Javadoc.
- `application.yml` — admin.bootstrap.email/password(${ADMIN_BOOTSTRAP_EMAIL:}·${ADMIN_BOOTSTRAP_PASSWORD:}).
- `.env.example`(루트) + `backend/.env.example` — ADMIN_BOOTSTRAP_EMAIL/PASSWORD 플레이스홀더.

**신설 test 2**:
- `test/auth/bootstrap/SuperAdminBootstrapRunnerIntegrationTest.java` — 3종(A 생성·BCrypt 인코딩 / B 멱등 무작업·email·password 불변 / C env blank Fail Fast IllegalStateException·미생성).
- `test/auth/controller/AdminOperatorControllerIntegrationTest.java` — 6종(①SUPER_ADMIN 성공 201·매핑 1건 ②BUYER 필터 게이트 403 ③비SUPER_ADMIN 서비스 403 fail-closed ④대상 404 ⑤중복 409 ⑥409 후 매핑 정확히 1건·saveAndFlush catch 관통).

**빌드 수정 1**:
- `backend/build.gradle.kts` — test task 더미 ADMIN_BOOTSTRAP_* env 주입(테스트 컨텍스트 Fail Fast 해소·프로덕션 코드 무변경).

**무변경 확인 2**: SecurityConfig(/api/v1/admin/**→hasRole("ADMIN")가 신규 경로 포함)·JWT/authorize 3곳.

### §4 핵심 메서드
- **SuperAdminBootstrapRunner.run(@Transactional)**: existsByRole_Code(SUPER_ADMIN) true→log INFO skip·return → email/password isBlank→IllegalStateException(Fail Fast) → findByCode(SUPER_ADMIN).orElseThrow(seed 누락) → User.create(email,null,null)+assignPasswordHash(encode)+save → UserRole.create(userId, role) save → log INFO created. email/password 평문 미로그.
- **AdminOperatorProvisioningService.provision(@Transactional)**: existsByUserIdAndRole_Code(callerUserId, SUPER_ADMIN) false→SuperAdminRequiredException(403·인가를 대상 존재 확인보다 선행해 대상 노출 차단) → findById(userId).orElseThrow(UserNotFoundException 404) → findByCode(ADMIN_OPERATOR).orElseThrow(500) → saveAndFlush(UserRole.create) catch DataIntegrityViolationException→AdminOperatorAlreadyExistsException(409·롤백) → response(publicId).
- **원자성 실증**: 부트스트랩 User+UserRole 단일 트랜잭션(부분 생성 방지)·endpoint 409 후 매핑 정확히 1건(우연일치 은폐 없음).

### §진입점 카드
1. **목적**: ADMIN 공급원 부재 deadlock 해소 — 최초 SUPER_ADMIN 부트스트랩(순환 탈출) + 운영 ADMIN_OPERATOR provisioning.
2. **핵심 진입점**: `SuperAdminBootstrapRunner.run`(기동 시 SUPER_ADMIN 1회 공급) · `AdminOperatorController`(POST /api/v1/admin/admin-operators) · `AdminOperatorProvisioningService.provision`.
3. **핵심 SoT 메서드**: `UserRoleRepository.existsByRole_Code`(멱등 판정) · `existsByUserIdAndRole_Code`(세분 인가·D-120 재사용) · `User.create`+`assignPasswordHash`(D-119) · `UserRole.create` · `RoleRepository.findByCode`.
4. **영향 범위**: auth 도메인 신규(bootstrap·controller·service·request·response·exception 2)·auth/UserRoleRepository(조회 추가)·common/web(GlobalExceptionHandler)·application.yml·.env.example 2·build.gradle.kts(test env). SecurityConfig·JWT·authorize 3곳·User/UserRole 엔티티·Flyway 무변경.
5. **패턴 재사용**: D-122 provisioning 원자 트랜잭션·saveAndFlush catch→409·Admin 절대경로(D-105 §2 Q2)·201 body-only(Location 생략)·findByCode seed 조회(D-119)·User.create+assignPasswordHash(D-119)·existsByUserIdAndRole_Code fail-closed(D-120)·AdminActorResolver.resolve(기존 Admin 5종).
6. **트랩 주의**: (1) 부트스트랩 존재확인 first 필수(env 선검증 first면 무작업 계약 위반·§1-B-1) (2) Runner 상시 실행이 테스트 컨텍스트 Fail Fast 유발·build.gradle.kts test env 필수·@DataJpaTest 무관(§1-B-2) (3) 부트스트랩 생성 전용·수정 금지·복구 아님(§1-B-3 invariant) (4) 세분 인가는 SecurityConfig 아닌 서비스단 실조회(JWT coarse·hasRole("SUPER_ADMIN") 매처 불가) (5) SUPER_ADMIN/ADMIN_OPERATOR seed 부재 시 500(V11 미적용 배포 오류) (6) 인가를 대상 존재 확인보다 선행(비-SUPER_ADMIN에 대상 노출 차단) (7) LT-02 FK 시드 그래프 try-finally.

### §8 carry-over
- **Manager/Staff 구성원 등록**: owner가 SELLER_MANAGER/STAFF 추가(D-122 §8 연장)·별 트랙.
- **B 셀프가입 경로(판매자 셀프신청→승인)**: MVP 후·seller 심사 워크플로 동반(D-122 §8)·별 트랙.
- **부트스트랩 계정 복구 경로**: 계정 disable·SUPER_ADMIN 비번 분실 복구는 부트스트랩 책임 밖(§1-B-3)·별도 운영 절차·수요 도래 시.
- **복수 ADMIN role 공존**: uk_user_role(user_id, role_id)는 (user,role) 쌍만 중복 방지 → 동일 user에 BUYER+ADMIN_OPERATOR 공존 가능(V12 seller_user user_id 단독 UNIQUE와 제약 성격 상이). 현 endpoint는 이 특성 그대로 수용(별도 제약 미추가).
- **Track 38 신규 트랩**: 부트스트랩 순서(§1-B-1)·테스트 컨텍스트 Fail Fast(§1-B-2). 동일 트랩 재발생 시 LT-06~ 이관.

### §특기
- 결정 근거 영구화 원칙 20회차(D-104~D-123). Track 38 = D-119(credential)·D-120(인가)·D-121(seller 해소)·D-122(seller 공급원)에 이은 **ADMIN 공급원** 신설로 "가입→로그인→role 검증→데이터 존재→ADMIN 운영 주체" 사슬 완결. DbRoleAuthorization ADMIN 판정이 빈 데이터 위에서 돌던 deadlock 해소.
- 최초 ADMIN 부트스트랩 = ADMIN 게이트로 잠긴 시스템의 순환 의존 탈출. flag 없는 상시 실행+멱등 skip으로 최초 기동 deadlock 방지(외부 검토 A·B 안건).
- Claude Code 실측이 사전 확정 플랜(env 선검증 first)을 뒤집어 무작업 계약 충돌을 예방(§1-B-1). D-122(pre-check)·D-120(toActorRole)·D-119(requireActorId) 계열 4번째 사례.
- D-122와 상이점 명시: provisioning이 세분 인가(SUPER_ADMIN 실조회)에 caller userId를 소비하므로 AdminActorResolver 호출(D-122는 미소비로 미호출).
- Track 38 트랩 후보(부트스트랩 순서·테스트 컨텍스트 Fail Fast)는 §1-B + §진입점 카드 기재로 갈음 — live-traps.md 이관 규정(≥3건 누적·재발생) 미충족.

---

## D-124. Track 39 상품 등록(Product/ProductVariant provisioning·S급) [ACTIVE]

### §1. 개요
seller 주도 상품 등록 프로덕션 write 경로 신설. 단일 요청(POST /api/v1/seller/products)으로
Product·OptionGroup·OptionValue·ProductVariant·초기 재고(Inventory)를 하나의 트랜잭션에 원자 생성.
D-59/D-81/D-85로 일관 이연돼 온 Product 계열 write 경로(Track 7 deferred 잔여분)를 해소한다.
등급 S급(5엔티티 단일 TX·교차 불변식·소유권 인가·옵션 다중성)·외부 검토 3회 통과.

### §1-A. 판단별 채택/기각 근거
[M1] 트랜잭션 경계 — α 단일 원자 endpoint 채택 / β 단계 분리 기각
  (부분저장 상태관리 비용>이점·MVP 소비처 없음)
[M2/R2] Product 초기 status — α SALE 서버 고정 채택. status는 요청 DTO에서 배제.
  DRAFT/PENDING 기각(승인 워크플로 부재로 SALE 승격 경로 없음→구매 사이클 차단). γ 파라미터 기각.
  향후 승인 도입 시 create() 내 SALE→DRAFT 한 줄 변경.
[M3] Variant 초기 status — α SALE 고정 채택 / β 파라미터 기각(ENUM SALE/HIDDEN/STOPPED·소비처 없음)
[M4] Inventory 초기화 — α InventoryService.initializeInventory 전용 메서드 채택(도메인 응집)
  / β repo 직접 save 기각. 초기 이력(InventoryHistory) 동반 기록.
[M5/R1] 옵션값↔variant 매핑 — β 임시키(TempKey) 채택 / α 인덱스 기각(reorder·삭제·삽입에 취약)
  / γ 옵션값 직접 중첩 기각(정규화·오타 위험). 클라 임시키→서버 valueId 해소.
[M6/R3'] 제약 위반 처리 — α saveAndFlush catch→409 채택 / β 사전조회 기각(우연통과가 실버그 은폐).
  flush 위치는 "Constraint 집중 구간 직후" 원칙(고정 규칙 아님·확장 대비).
[M7] 검증 경계 — α Service 진입부 집중 채택. 기준: "타 엔티티 알아야 검증 가능=Service /
  자기 invariant(길이·null·음수)=팩토리". β 팩토리 분산 기각.
[R4] 핵심 불변식 INV-A~D(Service 진입부·in-memory·400):
  INV-A 각 그룹서 정확히 1값 / INV-B arity 일치(그룹수=optionKeys수·pigeonhole 완전커버리지) /
  INV-C variant 참조 옵션값은 해당 Product 소속(타 Product FK 논리오류 차단) / INV-D 그룹≤3(PRD-4)
[R5-1] variant_code/seller_sku/barcode UNIQUE — β 미도입(조회 기능 부재·과잉설계)
[R5-2'] 초기 재고 0 — 허용만 확정. available=0 품절 의미·VariantStatus×Inventory×manual 관계는 이연.
[R5-3] display_order — β 중복 금지·gap 허용(연속성 비강제). 그룹·값·변형 3계층 검증.
[R5-4] is_soldout_manual — 등록 시 false 서버 고정·DTO 배제(정책성 상태 서버 결정·R2 동일 철학)
[STEP3 이력 A-1] 초기 재고 이력 = INBOUND·referenceType="product"·referenceId=productId.
  INITIAL ENUM 신설 기각(라이브 ENUM 변경·마이그레이션·범위 밖). ADJUST 기각(등록≠조정).
  referenceId=null(A-2) 기각(append-only 비가역·쌍 규약 정합·오케스트레이터가 productId 보유·비용0).

### §2. 결정 라운드 재진입 근거
- 경계 A(옵션 필수) → 경계 B(옵션 포함) 재진입: option1_value_id NOT NULL + option_value seed 전무 +
  우회 전무 실측 → 경계 A는 프로덕션에서 option_value 공급원 없어 성립 불가. 실동작 최소 단위 =
  Product+OptionGroup+OptionValue+Variant+Inventory 단일 TX. 팩토리 기존재라 추가 신설은 오케스트레이션·검증에 집중.
- "옵션 필수(0그룹 400)" → "단순상품 지원(DEFAULT 자동생성·α′)" 재진입: 옵션 없는 상품도 판매 가능해야 한다는
  요구. option1_value_id NOT NULL 구조라 옵션 0개 상품은 variant 생성 불가 → 서버가 DEFAULT 옵션 1조 합성해
  기존 파이프라인 통합(스키마 무변경). 판정: optionGroups=[] && variant 1개 && optionKeys=[](위반 400).
  상수 sentinel 1곳(DEFAULT_OPTION_GROUP_NAME·VALUE="DEFAULT"·TempKey="__default__").
- INV-E 신규 재진입: uk_product_variant_options가 MariaDB NULL distinct로 1~2그룹 상품(option2/3=NULL)의
  동일 옵션조합을 못 막음 → M6의 "uk를 dedup 최종방어선으로 신뢰" 전제가 1~2그룹서 붕괴. 요청 내 variant
  옵션조합(valueId 튜플) 중복을 in-memory 400으로 선차단(INV-E). uk는 3그룹 non-NULL 보조 방어선 잔존.
  (id 조합 비교·옵션값 이름 유일성은 범위 밖)

### §3. 실측 결과(구현 완료)
- 신설 production: ProductRegistrationService·ProductRegistrationController·CategoryNotFoundException·
  ProductVariantOptionConflictException·DTO 5(ProductRegistrationRequest·OptionGroup/Value/Variant Request·
  ProductRegistrationResponse)
- 엔티티 freeze 해제 3: Product.create·ProductVariant.create·Inventory.create(정적 팩토리 신설·Track 4 read-only 해제)
- 수정: InventoryService.initializeInventory(variantId·productId·initialStock·void) 신설·
  GlobalExceptionHandler 404/409 매핑 2건 + code 상수 2(CATEGORY_NOT_FOUND·PRODUCT_VARIANT_OPTION_CONFLICT)·
  ProductRegistrationRequest/VariantRequest @NotEmpty 완화(구조검증 Service 이관)
- SecurityConfig 무변경(/api/v1/seller/**→hasRole("SELLER") 기존재·first-match 정합)
- 검증 계층: categoryId 404 → 단순상품 판정/DEFAULT 합성 → INV-A~D → R5-3 → INV-E →
  저장(Product→Option→Variant·flush→uk catch 409) → initializeInventory
- 테스트: ProductRegistrationControllerIntegrationTest 17종(실 MariaDB testcontainer)
- 최종 회귀: 608 tests·128 suites·failures 0·errors 0·skipped 0·--rerun-tasks 실측(4m44s)·
  기존 591 회귀 0 + 신규 17

### §진입점 카드
1. 목적: seller 주도 상품 등록(Product+옵션+variant+초기재고 원자 생성)
2. 핵심 진입점: ProductRegistrationController(POST /api/v1/seller/products)·
   ProductRegistrationService.registerProduct(sellerId, request)
3. 핵심 SoT 메서드: synthesizeDefaultOptionIfSimple(단순상품 DEFAULT)·validateVariantOptionCombinations(INV-E)·
   InventoryService.initializeInventory(초기재고+History)
4. 영향 범위: product 도메인 write 신설·inventory(초기화 메서드 추가)·category(존재검증 소비)·GlobalExceptionHandler
5. 패턴 재사용 SoT: D-122 provisioning(원자 TX·saveAndFlush catch 409)·D-121 HeaderSellerActorResolver(소유권)·
   D-101 InventoryService
6. 트랩 주의: uk NULL distinct(INV-E로 보강)·LT-02 FK_CHECKS try-finally(테스트 seed)·
   LT-03 @SQLRestriction 직접선언(Product/Variant)

### §8. Carry-over(이연 항목)
- available=0 품절 의미 정의·VariantStatus×Inventory×is_soldout_manual 3자 관계 → 조회/판매 정책 트랙
- 옵션값 이름 유일성 검증(현재 id 조합만·이름 중복 미차단) → 수요 시
- 상품 조회/카탈로그 read API + "DEFAULT" sentinel 표시 필터 → 조회 트랙
- variant_code/seller_sku/barcode 유일성 → 조회 기능 도입 시
- 상품 승인 워크플로(DRAFT→APPROVED·status 서버 정책 변경) → 승인 트랙
- 등록 후 상품 수정/variant 추가 write 경로(현재 등록 전용) → 수요 시

### §특기. 전체 E2E 목표(zslab 지시·2026-07-04)
회원가입 → 상품등록 → 장바구니 → 구매 → 구매완료 → 배송, 한 사이클 E2E 실행 가능이 우선 목표.
Track 39(상품등록)로 "상품" 관문 확보. 다음 관문 = 장바구니(CartItem) write 경로.
인가 사슬(D-118~123) + 상품 공급(D-124) 완료 → 남은 사이클: 장바구니·주문·결제·배송 실사용 배선.

### §결정 근거 영구화 21회차
D-104~D-124 실증. 다음 트랙 종결 시 D-125 동일 구조(§1-A·§2·§8·진입점 카드) 의무.

---

## D-125. 장바구니 담기 경로 신설 (CartItem provisioning · buyer 주도) [ACTIVE]

Track 40 · 2026-07-04 · 등급 A(외부 검토 생략) · 결정 근거 영구화 22회차

### 목적
buyer가 ProductVariant를 장바구니에 담는 write 경로 신설. E2E 사이클(회원가입→상품등록→**장바구니**→구매→배송)의 장바구니 관문. POST /api/v1/cart/items · hasRole("BUYER").

### §1-A 판단별 채택·기각

- **M1 중복 담기 정책 → α(수량 누적) 채택**
  - α 수량 누적: 동일 variant 재담기 시 quantity += n. 장바구니 표준 UX·E2E 자연스러움. → 채택
  - β 409 거부: provisioning 패턴 그대로. UX 부자연. → 기각
  - γ 멱등 덮어쓰기: set n. 누적 손실. → 기각

- **M2 variant 존재검증 → α(existsById 404) 채택**
  - α existsById 선검증 404: D-122~124 provisioning 표준·명시적 404. → 채택
  - β FK 위반 catch: 암묵적. → 기각

- **M3 quantity 상한 → α(상한 없음) 채택**
  - α 상한 없음: 기존 CHECK quantity>=1 하한만. 상한 실사용 요구 없음(기조 4). 재고 초과는 주문 시점 SoT. → 채택
  - β 상한 도입(99): 근거 없는 매직넘버. → 기각

- **D-1 variant 404 예외 → A(기존 재사용) 채택**
  - A 기존 ProductVariantNotFoundException 재사용: GlobalExceptionHandler L164-169 이미 404(PRODUCT_VARIANT_NOT_FOUND) 매핑. 신규 파일 0·기조 4. → 채택
  - B cart 예외 신설: 의미 중복. → 기각. 반론(cart 응답 code가 product 명칭)은 계약 분리 요구 부재로 수용 안 함.

- **D-2 응답 식별자 노출 → B(식별자 제외) 채택**
  - B 식별자 제외(userId·variantId·quantity·selected 4필드): cart_item public_id 컬럼 부재(HARD·id-only). 내부 PK 외부 노출은 public_id 관례(D-124) 위반. 관례 정합 우선. → 채택
  - A 내부 id(Long) 노출: 프롬프트 "식별자" 문구엔 부합하나 관례 위반. → 기각(문구를 관례에 양보)

- **R2 동시삽입 race 처리 → R2(house 패턴 catch→409) 채택**  ← 구현 전 실측으로 표면화
  - R1 스펙 문자 그대로(catch 후 같은 TX 계속): flush의 uk 위반이 TX를 rollback-only 마킹 → commit 시 UnexpectedRollbackException 500. **라이브 트랩**. → 채택 불가
  - R2 house 패턴: findBy present→addQuantity(순차 누적) / empty→saveAndFlush catch DataIntegrityViolationException→409 rethrow(원자 롤백). Track 37/39 대칭·트랩 0. race는 409, 클라 재시도 시 findBy가 누적. → 채택
  - R3 REQUIRES_NEW 헬퍼(race 자동누적): 스펙 intent 보존하나 별도 빈·2차 TX·복잡도↑. 단일 운영자 MVP 과잉(기조 1/4). → 기각
  - 예외: 기존 OptimisticLockingFailureException(409) 재사용(기조 4·의미 소폭 확장 허용).

### §2 재진입 근거
E2E 목표에서 인가 사슬(D-118~123)·상품 공급(D-124) 완료 후 다음 관문이 장바구니. 장바구니 없이 구매 시나리오 진입 불가. 정찰 실측: CartItem 엔티티·create()·BuyerActorResolver·UNIQUE 제약 존재하나 write 경로(Controller/Service) 전무 → 신설 필요.

### §3 배선 실측
- **인가**: SecurityConfig .requestMatchers("/api/v1/cart/**").hasRole("BUYER") 〔라인 확인〕 (orders/claims 매처 대칭). hasRole→ROLE_BUYER authority 매칭. 컨트롤러 @PreAuthorize 미사용.
- **소유권**: SecurityContextBuyerActorResolver.resolve() → JWT actorId = user.id = CartItem.userId. buyer≡user(별도 Buyer 엔티티 없음·BuyerProfile은 user_id 공유 PK 종속).
- **오케스트레이션 CartService** 〔경로/라인 확인〕:
  1) ProductVariantRepository.existsById(variantId) false → ProductVariantNotFoundException(404)
  2) findByUserIdAndVariantId present → CartItem.addQuantity(n)(dirty flush) / empty → CartItem.create(userId,variantId,quantity) → saveAndFlush → catch DataIntegrityViolationException → OptimisticLockingFailureException(409)
- **재고 무연계**: 담기 시점 Inventory 호출 없음. 재고 SoT는 주문 시점(OrderPlaced·InventoryOrderPlacedHandler·D-87).
- **스키마 무변경**: V1__init.sql:527-542 cart_item(UNIQUE uk_cart_item_user_variant·CHECK quantity>=1·FK RESTRICT) 재사용. Flyway 신규 0.

### §진입점 카드
1. **목적**: buyer가 ProductVariant를 장바구니에 담는 write 경로(수량 누적·A급).
2. **핵심 진입점 파일**: CartController.java(POST /api/v1/cart/items) · CartService.java · SecurityConfig.java(/api/v1/cart/** 매처) 〔라인 확인〕
3. **핵심 SoT 메서드**: CartService.add〔시그니처 확인〕 · CartItem.addQuantity(int) · CartItemRepository.findByUserIdAndVariantId(Long,Long)
4. **영향 범위**: 신설 7(Controller·Service·요청/응답 DTO 2·통합/단위 테스트 2) · 수정 3(CartItem·CartItemRepository·SecurityConfig) · GlobalExceptionHandler 무변경 · DDL 무변경.
5. **패턴 재사용 SoT**: provisioning 원자 TX·saveAndFlush catch→409(D-122 Seller/D-124 Product·Track 37/39) · buyer 해소 SecurityContextBuyerActorResolver(D-119 계열) · variant 참조 ProductVariant(D-124).
6. **트랩 주의**: R2 실측(catch 후 same-TX 계속 = rollback-only→500). 순차 재담기는 pre-check findBy로 처리, race만 409 rethrow. 재고는 담기 아닌 주문 시점(D-87).

### §특기
- 회귀: 618 tests PASS(130 suites·failures 0·errors 0·skipped 0·--rerun-tasks·4m43s). 608(Track 39)→618, 신규 10(통합 6·단위 4), 기존 608 전원 통과·회귀 0.
- 신규 파일 0 예외(D-1)·스키마 무변경(기조 4)·freeze 유지(addQuantity 추가·setter 무).
- E2E 목표: 장바구니 관문 완료. 잔여 = 주문·결제·배송 실사용 배선.

### §8 carry-over
- "바로 결제하기"(장바구니 우회 직접 주문) 경로: Track 41 주문 정찰 시 α(장바구니 우회)/β(내부 경유) 결정. 현 미배선. 구현 가능 상황 확인됨(variant+quantity 구조·buyer resolver·재고 주문시점 SoT 재사용).
- CartItem 조회/삭제/수량변경 경로 부재(담기 write만 신설). 장바구니 조회 API는 구매 UX 트랙에서.
- Track 40 트랩(R2 rollback-only) = 본 D-125 §1-A/카드 박제. 재발생 시 LT-06 이관.

---

## D-126 [ACTIVE] Track 41 — 장바구니 결제 경로 신설 (Cart Checkout·β·2026-07-05)

> [일부 SUPERSEDED·D-151] 'OrderPlaced 3번째 소비자=Cart'(§3·§진입점 6) 전제는 Track 67에서 Cart 소진이 OrderPlaced→PaymentCompleted로 이동하며 무효(핸들러도 CartPaymentCompletedHandler로 rename). OrderPlaced 잔여 소비처 Inventory 예약·Notification 2종은 유효. CartItem 소비 통합 정책(①)·삭제 방식(D-A)·경계 논지는 유지.

주문 write는 이미 완비돼 있었고(Track 4/8·정찰로 태스크 전제 반전 확인), 실제 부재는
"장바구니→주문 연결"이었다. Track 41은 장바구니 selected 품목을 주문으로 확정하는 경로를
신설한다(A급·외부 검토 2라운드 수렴). 구현 3커밋(63514e2·a0ea73e·1d23e39) + test 커밋.

### §1-A 판단별 채택·기각

- 범위: β 채택(Cart Checkout 경로 신설) / α 기각(직접주문만 인정+장바구니 소비 — 장바구니가
  구매 입력이 아니라 목표 "장바구니→구매" 미충족) / γ 기각(완료 close — CartItem 소비 미배선
  불일치 잔존). β는 신규 Aggregate 없이 기존 CheckoutService·OrderService 재사용 배선이라 A급 유지.
- 공존: 공존-α 채택(직접주문=Buy Now 유지 + Cart Checkout 신설·둘 다 CheckoutService 공유·
  Command 생성만 분기) / 공존-β 기각(직접주문 제거 — 실 결함 없는 코드·618 테스트 폐기 churn·기조 4 위반).
- 변환 방식: b 채택(CheckoutService에 내부 id 해소 경로 추가·Cart는 variantId(Long) 전달) /
  a 기각(public_id 역해소 — Product·Variant 이중 로드·어댑터에 역해소 책임).
- 계약 형태(A): A-1 채택(내부 id 전용 CartCheckoutCommand 별도·기존 CheckoutItemCommand 무변경·
  회귀 격리) / A-2 기각(기존 command 확장 — public_id 경로 테스트까지 회귀 표면 확대).
- 해소 분기 지점(B): B-1 채택(createOrder 식별자 해소 구간만 타입별 분기·오케스트레이션 100% 공유) /
  B-2 기각(createOrder 전체 복제 — 코어 중복·쿠폰/세금 추가 시 수정 누락·기조 3 위반).
- seam(i) 채택(공통 인터페이스 CheckoutContext — 오케스트레이션은 인터페이스 참조·회귀 무변경) /
  (ii)파라미터화·(iii)선해소 기각(오케스트레이션·진입 계약 변경·618 회귀 표면 확대).
- 경로(D1): POST /api/v1/cart/checkout 채택(장바구니 맥락·cart/** 인가 커버) / orders/from-cart 기각.
- 조립 계층(D2): CartCheckoutService 신설 채택(CheckoutService의 CartItemRepository 미의존 유지·
  결합 회피) / CheckoutService 확장 기각(관심사 혼입).
- 빈 주문 가드(D3): CartCheckoutService 선가드 채택(EmptyCartCheckoutException·422·CART_CHECKOUT_EMPTY·
  ORD-1 도달 전 차단) / OrderService 위임 기각(빈 items 深전파·메시지 모호). 400/404 부적합(well-formed·
  카트 리소스 존재)·422 계열(OrderNotPayable·CheckoutItemMismatch) 정합.
- 응답 변환(D4): CheckoutOutcomeSupport 정적 헬퍼 채택(checkout.controller·두 컨트롤러 공유) /
  CheckoutOutcome 메서드 기각(record HTTP 비의존 javadoc 위반) / 복제 기각(중복·기조 3).
- CartItem 소비 정책(①): 경로 무관 통합 삭제 채택 / ②checkoutSource 이벤트 추가 기각(소비처가
  CartHandler 하나뿐·공용 이벤트에 발행자 정보 유입·소비처 늘면 그때 추가·YAGNI) / ③서비스 직접
  삭제 기각(후처리 실행 모델 이원화·AFTER_COMMIT 일관성 파괴·결정 2-2 충돌).
  → by-design 정책: 주문 경로 무관(Cart Checkout·Buy Now)하게 주문된 variant가 장바구니에 있으면
     소비. 직접주문한 variant가 장바구니에 있으면 함께 제거됨(부작용 아닌 의도). 되돌림 필요 시
     CartOrderPlacedHandler + OrderPlaced에 checkoutSource 추가 3지점 수정으로 ②전환 가능(격리됨).
- 삭제 방식(D-A): γ 파생 deleteByUserIdAndVariantIdIn 채택(파생 관례·@Modifying flush/clear 첫 도입
  비용 회피·CartItem 소수) / α find+deleteAll·β @Modifying 벌크 기각.
- selected 정책(C-1'): selected=true 전체 주문 + "선택 토글은 후속 트랙 지원·Checkout API는 토글
  추가돼도 불변" 명문화 / C-2 기각(cartItem id 재전달 시 selected 필드 의미 소멸).

### §2 결정 라운드 재진입
- 범위 α→β: 외부 검토가 α의 목표 미충족 지적. E2E "장바구니→구매" 충족 위해 β 재진입.
- 변환 a→b: 초기 추천 a(회귀 격리)에서 b로. 내부 id 로더 findByIdIn이 revalidatePayable에 이미
  존재·단일 로드·오케스트레이션 공유로 회귀 격리 성립 확인 후 전환.
- Phase 4 정책: UI 직관(Buy Now≠Cart 분리)에서 출발했으나 3라운드 검토로 구조 일관성(공용 이벤트·
  AFTER_COMMIT 후처리 일관·계약 안정) 우선하여 ① 통합으로 수렴. checkoutSource는 본질적 이벤트
  속성 아님(주문 사실 vs 진입 흐름 메타데이터).

### §3 배선 실측 (HEAD 1d23e39·MCP read 인용)
- 진입 seam: CheckoutService.checkout(CheckoutContext) 단일 진입(오버로드 미도입 — mock ambiguous
  회피·기존 테스트 0수정). resolveItems가 instanceof로 CheckoutCommand→resolveByPublicId /
  CartCheckoutCommand→resolveByInternalId 분기. toOrderItemCommand(Product,ProductVariant,int) 공통 tail
  (D-64 가격산정). resolvedItems 수렴 후 revalidateInventory·CreateOrderCommand·orderService.createOrder
  ·OrderPlaced 100% 공유.
- CartCheckoutController: POST /api/v1/cart/checkout·BuyerActorResolver.resolve→buyerId·Idempotency-Key
  헤더·CheckoutOutcomeSupport.toResponseEntity. SecurityConfig /api/v1/cart/**→hasRole("BUYER") 커버
  (컨트롤러 애노테이션 불요).
- CartCheckoutService: findByUserIdAndSelectedTrue(buyerId)→empty시 EmptyCartCheckoutException→
  CartCheckoutItemCommand(variantId,quantity) 변환→CartCheckoutCommand 조립→checkoutService.checkout 위임.
  @Transactional 없음(D-52 다단 커밋 보존).
- CartOrderPlacedHandler: @TransactionalEventListener(AFTER_COMMIT)+@Transactional(REQUIRES_NEW)·
  orderRepository.findByIdWithItems(orderId)→buyerId+variantIds(Set·distinct)→deleteByUserIdAndVariantIdIn·
  실패 흡수(6표준키 log+recordFailed·rethrow 없음)·order 미발견 방어 skip. OrderPlaced 3번째 소비자
  (Inventory·Notification 형제).
- OrderPlaced(publicId,orderId,occurredAt) 계약 무변경. CartItemRepository: findByUserIdAndSelectedTrue·
  deleteByUserIdAndVariantIdIn(파생·@Modifying 미도입) 추가.

### §진입점 카드
1. 목적: buyer가 장바구니 selected 품목을 주문·첫 결제로 확정하는 경로(Cart Checkout·β).
2. 핵심 진입점: CartCheckoutController POST /api/v1/cart/checkout · CartCheckoutService.checkout ·
   CheckoutService.checkout(CheckoutContext).
3. 핵심 SoT 메서드: CheckoutService.resolveByInternalId(내부 id 해소) ·
   CartCheckoutService.checkout(selected 조회·조립·위임) · CartOrderPlacedHandler.handle(소비).
4. 영향 범위: cart(controller·service·handler·repository·exception·request) · checkout(command·
   service·controller) · order(BuyerOrderController D4 치환) · common(GlobalExceptionHandler 422).
   OrderPlaced·OrderService·Inventory·Payment 무변경.
5. 패턴 재사용: provisioning 원자 TX·saveAndFlush→409(D-125 22회차) · AFTER_COMMIT+REQUIRES_NEW+
   실패 흡수 핸들러(InventoryOrderPlacedHandler·Track 17) · BuyerActorResolver(D-125) ·
   findByIdIn 로더(revalidatePayable) · InventoryEventIntegrationTest 통합 패턴(publishInTx·Track 17).
6. 트랩 주의: LT-05(형제 핸들러 순서 비결정 — HARD DELETE 자연 멱등·상태 skip 가드 불요로 자동 충족).

### §8 carry-over
- 장바구니 조회/삭제/수량변경·selected 토글 API(PATCH /cart/{id}/selected) 부재(백로그·D-125 §8 연속).
- resolveIdempotencyKey 중복(BuyerOrderController·CartCheckoutController 복제·향후 공통화 후보).
- 결제 완료(PaymentCompleted)·배송 실사용 배선(E2E 다음 관문). 오버셀 window(reserve AFTER_COMMIT
  별도 TX·실패 흡수)는 별도 트랙 분리 확정(이번 범위 제외).
- ② 경로 분리(checkoutSource) 전환 여지 보존(소비처 확대 시).

---

## D-127 [ACTIVE] Track 42 — 결제 완료(PaymentCompleted) 배선 실측 확인 + SoT 정정 (2026-07-05)

결제 완료 배선(webhook 수신→PaymentCompleted 발행→주문 PAID 전이→재고 commit)은 이미 완비돼
있었고(정찰 2회차로 태스크 전제 반전 확인·Track 41에 이은 2연속), 실제 잔여는 SoT 문서 1곳 드리프트뿐이었다.
Track 42는 코드 변경 0으로 배선을 실측 확인하고 domain-events.md E2 멱등 서술을 코드·D-101 §6 A′에
정합시킨다(A급·외부 검토 생략). recon-report.md(구 RECON.md) 2절 정찰 산출.

### §1-A 판단별 채택·기각

- 스코프: α 정정+완결 채택(문서 정정 후 트랙 종결) / β 흡수·즉시 이동 기각(SoT 드리프트 방치 시
  다음 트랙에서 E2 멱등을 item_status 가드로 오독할 위험 잔존) / γ 최소 정정 기각(DDL·invariants
  원문 대조 미완 상태의 부분 정정은 재정찰 유발·기조 5 위반).
- 등급: S급 예상 → A급 확정 채택. 근거: 신규 구현 0·핵심 경로 기 테스트 고정(webhook e2e + Inventory
  T2/T3)·잔여는 문서 정정 1곳. 외부 검토 생략(문서 문안 교체·구조 변경 없음).

### §2 결정 라운드 재진입
- 인계 §3이 "진짜 부재 후보 4건"(webhook 수신부·PaymentCompleted 발행·PENDING_PAYMENT→PAID 전이·
  Mock PG seam)으로 지목한 것이, 정찰 실측 결과 전부 기구현·배선·테스트 완료로 확인됨(§3). Track 41의
  "주문 write 완비" 반전에 이은 2연속 전제 반전. 따라서 "신규 구현 트랙"에서 "배선 실측 확인 + SoT
  정정 트랙"으로 재정의. 2차 대조 정찰에서 DDL ENUM·invariants PAY·D-101 §6 A′ 원문까지 확보 후 정정 확정.

### §3 배선 실측 (MCP read 인용·경로:라인)
- webhook 수신부: `PaymentWebhookController` `POST /api/webhooks/payments`
  (PaymentWebhookController.java:21 @RequestMapping·:31-34 @PostMapping→handleCallback 위임).
- PaymentCompleted 발행: `Payment.complete()`가 PENDING→PAID 전이 직후 이벤트 누적(Payment.java:148)
  + `PaymentService.handleCallback`이 pull→save(flush)→동기 발행(PaymentService.java:169-171).
- 주문 전이: `OrderEventHandler.onPaymentCompleted`(@EventListener·동기·OrderEventHandler.java:30-37)
  → `OrderService.markPaid`(OrderService.java:94-96) → `Order.markPaid`(Order.java:142-149·전 OrderItem
  PAID 후 status=PAID). PENDING_PAYMENT 초기값 Order.java:99.
- Inventory commit: `InventoryPaymentCompletedHandler`(@TransactionalEventListener AFTER_COMMIT +
  REQUIRES_NEW·commitReservation 호출 :43-50·1차 핸들러 가드 없음 :26-28) / 대칭 실패 경로
  `InventoryPaymentFailedHandler`(PaymentFailed→release).
- Mock PG seam: `PaymentGateway` 인터페이스(PaymentGateway.java:11-34·완료 통지는 webhook 책임이라
  인터페이스 제외) + `MockPaymentGateway`(@Component·MockPaymentGateway.java:16·실 PG 도입 시 본 구현만 교체).
- checkout 배선: `CheckoutService.completeWithInitiate` → `PaymentService.initiate`
  (CheckoutService.java:289-293·신규주문·재결제 공통 tail).
- DDL: payment.status DB 레벨 잠금 `ENUM('PENDING','PAID','FAILED','CANCELLED') NOT NULL`
  (V1__init.sql:626). Java backed enum @Enumerated(EnumType.STRING) 정합(Payment.java:68-70).
- 불변식: PAY-1(과환불 차단)·PAY-2(status 전이=state-machine §1)·PAY-3a(주문당 PAID≤1)·
  PAY-3b((pg_provider,pg_tid) UNIQUE·콜백 중복 방어) (invariants.md:115-120).
- E2E 테스트: webhook SUCCESS→Payment.PAID·OrderItem.PAID·Order.PAID 실커밋 검증
  (PaymentWebhookIntegrationTest.java:86-96) + Inventory T2 PaymentCompleted→commitReservation·
  T3 PaymentFailed→release(InventoryEventIntegrationTest.java:116-136·§6 라이브 트랩 회귀 방지).

### §진입점 카드
1. 목적: 결제 완료 콜백 수신 → PaymentCompleted → 주문 PAID·재고 commit 배선(기구현·본 트랙은
   실측 확인 + E2 멱등 SoT 정정·코드 변경 0).
2. 핵심 진입점: PaymentWebhookController(POST /api/webhooks/payments·:21/:31-34) ·
   PaymentService.handleCallback(:169-171) · OrderEventHandler.onPaymentCompleted→markPaid(:30-37).
3. 핵심 SoT 메서드: Payment.complete()(:140-149) · OrderService.markPaid(:94-96) ·
   InventoryPaymentCompletedHandler.handle→commitReservation(:43-50).
4. 영향 범위: 결제·주문·재고 3연계(읽기 실측만·코드 변경 0). domain-events.md E2 서술 1곳만 정정.
5. 패턴 재사용: 이벤트 핸들러(AFTER_COMMIT + REQUIRES_NEW + 실패 흡수·D-101) · 동기 @EventListener
   동일 TX 소비(D-29) · 이벤트 통합 테스트(InventoryEventIntegrationTest publishInTx·Track 17).
6. 트랩 주의: D-101 §6 A′ — E2 원안 "PAID면 skip" 1차 가드는 AFTER_COMMIT 시점 item이 이미 PAID여서
   commitReservation 영구 미실행(데드코드)라 폐기됨(decisions.md D-101 §6·recon-report.md 2절). 재전달
   방어는 PAY-3b UNIQUE + at-most-once 인메모리 publisher + INV-3 backstop 이중화로 대체.

### §8 carry-over
- domain-events.md E2 멱등 서술 정정 = 본 트랙 완료. 관련 잔여 없음.
- 백로그 유지: 배송(Delivery) 실사용 배선 = E2E 사이클 마지막 관문(D-126 §8 연속).
- 미결제 주문 자동 취소(Order.status) 미구현·별도 트랙 이연(domain-events.md:235·결제 완료 관문 범위 밖).

---

## D-128. Track 43 배송 — Seller markDelivered endpoint 신설 + E2E 관통 테스트 [ACTIVE] (2026-07-05)

### §1-A 판단별 옵션 (α/β/γ 채택·기각)
- **트랙 범위**: α E2E 관통 검증만(기각) / α+β 통합 = 관통 검증 + Seller markDelivered 신설(채택). 기각 사유: markDelivered 부재(Admin 단독·Seller 부재)는 소비처 있는 실재 부채. 범위 크기로 분리·이연은 기조 4 위반("범위가 크니 이연"은 근거 불가). 실재 결함 처치는 범위 무관 정당.
- **M1 endpoint 자리**: α delivery 키 `/api/v1/deliveries/{dlv}/mark-delivered`(채택·Admin 대칭) / β order-item 키 확장(기각·Admin 비대칭)
- **M2 E2E 방식**: α 실 HTTP 연쇄(채택·트리거 배선까지 검증) / β JDBC 시드 단축(기각·결제→배송 트리거 단절·세그먼트 테스트와 차별성 없음)
- **M3 일반배송 HTTP 커버**: α 신규 endpoint 테스트로 확보(채택·기존 HTTP는 EXCHANGE만 T5) / β Service 레벨 유지(기각·공백 방치)
- **M4 비-SHIPPING 진입**: α 422 명시 매핑(채택·신규 endpoint 방어 계약) / β primitive 그대로(기각)
- **응답 DTO**: α `RegisterExchangeShipmentResponse` 재사용(채택·Admin 대칭·신규 DTO 0) / β `MarkDeliveredResponse` 신설(기각·실익 이름뿐)
- **등급**: A급(신규 구현 소규모·코어 배선 기 테스트 고정·저위험)

### §2 결정 라운드 재진입 근거
정찰 실측 결과 배송 코어 배선(Entity·Service·Controller 2종·Repository·Enum·E4/E5·핸들러·Flyway·테스트 28건)은 전 계층 기구현. Track 41·42에 이은 3연속 "전제 반전". 진짜 부재 = 외부 택배 seam(D-111 이연)·일반주문 Seller markDelivered·자동 구매확정(범위 밖). 이 중 markDelivered만 E2E 종점(배송완료) 달성에 필요한 실재 부채로 확정 → 트랙 범위 = 관통 검증 + markDelivered.

### §3 배선 실측 (경로:라인)
- Seller wrapper: `OrderShippingService.markDeliveredBySeller(sellerId, deliveryId)` + `authorizeDelivery` 신설. `DeliveryRepository` 주입. 소유권 = delivery→`getOrderItemId()`→OrderItem→`getSellerId().equals`, 미존재·타셀러 모두 `DeliveryNotFoundException`(404 은닉). `IllegalStateException`→`DeliveryInvalidStateException`(422) 흡수. primitive `markDelivered` 재사용(전이·E5 무변경).
- 배치 근거: delivery⊥order(delivery 패키지 order import 0 실측) → 소유권 가드는 order 패키지.
- 컨트롤러: `SellerDeliveryCompletionController` (order 패키지) — `POST /api/v1/deliveries/{deliveryPublicId}/mark-delivered`. `SellerActorResolver`→sellerId·`findByPublicId` 404→service→OSIV 재조회 응답 (Admin `AdminDeliveryController.java:77-90` 대칭).
- 인가: `SecurityConfig` `/api/v1/deliveries/**`→`hasRole("SELLER")` (admin prefix 상이·미충돌).
- 재사용 자산: `RegisterExchangeShipmentResponse`·`DeliveryInvalidStateException`(422)·`DeliveryNotFoundException`(404) — 신규 예외·DTO 0.
- primitive: `DeliveryService.markDelivered`(DeliveryService.java:65-72)·`markDeliveredByAdmin` 1:1 위임(152-155) 선례.

### 진입점 카드
1. **목적**: 일반주문 배송완료를 Seller가 수행하는 endpoint 신설 + 회원가입→…→배송완료 E2E 실 HTTP 관통 검증.
2. **핵심 진입점 파일/라인**: `SellerDeliveryCompletionController`(POST /api/v1/deliveries/{dlv}/mark-delivered) / `OrderShippingService.markDeliveredBySeller` / `SecurityConfig`(/api/v1/deliveries/**).
3. **핵심 SoT 메서드**: `OrderShippingService.markDeliveredBySeller` / `authorizeDelivery` / `DeliveryService.markDelivered`(primitive 재사용).
4. **영향 범위**: 배송·주문·재고·클레임·알림 연계. 코드 변경 = 소유권 가드+wrapper+컨트롤러+SecurityConfig. 전이·이벤트·핸들러 무변경.
5. **패턴 재사용 SoT + 회차**: Admin markDelivered 위임 패턴(D-104) / 소유권 404 은닉 계약(`OrderShippingService.authorize`) / 이벤트 통합 테스트 패턴(InventoryEventIntegrationTest) / 컨트롤러 통합 테스트(SellerShippingControllerIntegrationTest).
6. **트랩 주의**: LT-02(FK_CHECKS try-finally) / 클래스 @Transactional 금지(AFTER_COMMIT 실커밋) / DTO 이름-의미 드리프트(RegisterExchangeShipmentResponse 재사용).

### 테스트
- `SellerDeliveryCompletionControllerIntegrationTest` (4): 무인증 401 / 소유셀러 SHIPPING 일반배송(claim_id NULL)→200·DELIVERED·OrderItem/Order DELIVERED·E5 1회 / cross-tenant 404 / 비-SHIPPING(READY) 422.
- `OrderFulfillmentE2EIntegrationTest` (1): 주문(buyer)→결제완료 webhook SUCCESS→발송(seller)→배송완료(seller 신설) 실 HTTP 4연쇄·각 단계 JDBC 검증·최종 Payment PAID·Order/OrderItem/Delivery DELIVERED.
- 회귀: 645 tests·failures 0·errors 0·--rerun-tasks 실측·BUILD SUCCESSFUL.

### §8 carry-over
- 응답 DTO 이름-의미 드리프트(`RegisterExchangeShipmentResponse` 재사용) — 필요 시 후속 정합.
- "X-Seller-Id 헤더 stub" Javadoc 레거시 명칭 드리프트(실제 Bearer JWT) — 본 트랙 범위 밖·백로그.
- 외부 택배 seam(D-111 이연)·자동 구매확정(DELIVERED→CONFIRMED·E6)·부분배송 1:N — 후속 트랙.
- E2E 남은 관문: 없음(회원가입→상품등록→장바구니→구매→구매완료→배송완료 관통 달성).

---

## D-129. 구매자 상품 카탈로그 조회 API 신설 (Track 44) [ACTIVE]

- 일자: 2026-07-05 / 등급: A급(신규 read 도메인·외부 검토 수령) / 선행 main: 21b3b91(D-128)
- 요약: 구매자용 상품 목록·단건 조회 API 신설. 노출대상(D1)·품절(D2)·대표가(D3) 정책 단일화. 공개 카탈로그(GET permitAll). 상태전이·금전 로직 없는 순수 read-path.

### §1-A. 판단별 옵션·채택/기각

M1 트랙 범위
- α(채택) 카탈로그 read-path 5항목: 목록·단건·필터·품절정책·DEFAULT sentinel 필터. 근거: 품절·sentinel은 조회 쿼리·DTO를 공유(recon C-3 결합점)해 분리 시 1~3 재작성 유발. 결합 처치는 범위 커도 정당(기조 4).
- β(기각) 5항목+장바구니 조회. 장바구니는 별 aggregate·read-path 재사용 이득 없음 → Track 45 분리.
- γ(기각) 목록만. sentinel/품절 미결정 시 재작업 위험.

M2 인가
- 공개 카탈로그(채택): GET /api/v1/products/** permitAll. 근거: 로그인 전 탐색·공유 URL·SEO 관행. GET 전용·데이터 변경 없어 공격면 증가 제한적. 내부필드(sellerId 원본·costPrice) 응답 제외로 보완.
- BUYER 한정(기각): 현행 authenticated로 자동 커버되나 로그인 전 탐색 불가·커머스 관행 이탈.

M3 대표가(displayPrice)
- basePrice + MIN(판매가능 variant additional_price)(채택). 근거: 실판매가 = base+additional이라 basePrice 단독은 구매 불가 가격 노출(외부검토 반박 수용). "N원~" 표기 의도의 최저값 정수 필드.
- basePrice 단독(기각)·최저~최고 범위(기각·MVP 과복잡).

M4 판매자 상태 결합
- ACTIVE만 노출·SUSPENDED/TERMINATED/PENDING 비노출(채택). 단건은 404 은닉(제재/탈퇴 사실 외부 비노출). displayable 단일 개념. 카탈로그 정책 ⊥ 주문/클레임 스냅샷(기존 거래 조회 무영향).

M5 상세 variant 노출
- SALE variant만 노출(채택). HIDDEN/STOPPED SKU 유출 방지. SALE·재고0 variant는 soldOut 배지 노출. D2의 variantStatus!=SALE 항은 "비노출"로 흡수.
- 전체 노출+배지(기각): 판매중단 옵션 유출.

M6 품절(soldOut)
- available==0 OR soldoutManual OR variantStatus!=SALE(채택). 상품 단위 soldOut = 판매가능 variant가 전부 구매불가. Inventory 별 테이블·findByVariantIdIn 배치(N+1 회피).

M7 대표 이미지
- product.thumbnail_url(채택·DDL '대표 이미지 URL' 지정 컬럼·목록 배치쿼리 불요). product_image.is_main(기각). 상세는 product_image 전체.

M8 정렬
- LATEST(기본)/PRICE_ASC/PRICE_DESC/NAME. tiebreaker created_at DESC→id DESC(안정정렬). 잘못된 sort 400.

M9 페이징
- Spring Pageable + PagedResponse(size 1~100 클램프·BuyerOrderQueryService 정합)(채택). Cursor(기각·MVP 과잉).

### §2. 결정 라운드 재진입
- 외부 검토 수령. 수용분 무발화 내재화. 반박 1(displayPrice basePrice 단독)·부분수용 1(판매자 상태)만 재검토 재진입 → M3 basePrice 철회·M4 확정.
- Seller/Product/Variant 상태 enum·삭제 컬럼 미측정 상태로 D1·D2 값 미확정 → 상태값 정찰 재진입 후 확정(기조 5).

### §3. 배선 실측 (구현·검증 후 실측·라인은 recon-report.md 대조)
- Repository: ProductRepository.findDisplayable(categoryId, sort, pageable) 조인쿼리(Product.status=SALE ∧ Seller.status=ACTIVE·삭제는 @SQLRestriction 자동). ProductVariantRepository.findByProductIdAndStatus / findByProductIdInAndStatus(SALE). InventoryRepository.findByVariantIdIn 재사용.
- Service: ProductCatalogService.listProducts / getProduct. 정책 단일화 = displayPrice / isPurchasable / isProductSoldOut. 단건 미존재·비노출 전부 ProductNotFoundException→404 은닉.
- DTO: ProductSummaryResponse(record)·ProductDetailResponse(record·Variant/OptionGroup/OptionValue/Image/Option 중첩)·ProductCatalogSort(enum).
- Controller: ProductCatalogController — GET /api/v1/products(목록·categoryId·sort·page·size)·GET /api/v1/products/{publicId}(상세). HTTP 책임만(D-40).
- Security: SecurityConfig GET /api/v1/products/** permitAll(POST /api/v1/users permitAll 다음·구체 규칙 앞). GET 한정·다른 verb anyRequest authenticated.
- Exception: ProductNotFoundException + GlobalExceptionHandler 404 매핑.
- 검증: ./gradlew.bat test --rerun-tasks → BUILD SUCCESSFUL·suites=137·tests=660·failures=0·신규 15/15.

### §진입점 카드
1. 목적: 구매자 상품 목록·단건 조회(공개 카탈로그·노출/품절/대표가 정책 단일).
2. 핵심 진입점: ProductCatalogController(GET /api/v1/products·/{publicId}) / SecurityConfig(GET products permitAll).
3. 핵심 SoT 메서드: ProductCatalogService.listProducts·getProduct / displayPrice·isPurchasable·isProductSoldOut(정책 단일화) / ProductRepository.findDisplayable.
4. 영향 범위: product read-path 신설(상태전이·금전 무변경). SecurityConfig permitAll 1매처 추가. 기존 트랙 무회귀(660 GREEN).
5. 패턴 재사용: PagedResponse·size클램프(BuyerOrderQueryService) / 절대경로 컨트롤러(ProductRegistrationController·CartController) / findByIdIn enrich / @SQLRestriction 삭제 자동제외.
6. 트랩 주의: Inventory 별 테이블·soft-delete 없음(LT 후보·N+1) / status·soldoutManual 쓰기 경로 부재(등록 시 SALE·false 고정·Track 7 이연) → 다양 status 데이터는 테스트 JDBC 시드 필요.

### §8. carry-over
- SALE variant 0개 product: displayPrice가 basePrice 단독 fallback(.orElse(0L)). soldOut=true·목록 정상 노출이라 결함 아님·후속 정합 후보.
- 상태 전이 쓰기 경로 부재(Track 7 이연) — HIDDEN/STOPPED/SUSPENDED/TERMINATED 필터는 실동작하나 실데이터 미생성.
- 장바구니 조회/삭제/수량변경·selected 토글(Track 45 분리·recon A 부재 확정).
- resolveIdempotencyKey 공통화·외부 택배 seam·자동 구매확정(E6) — 기존 이연 유지.

---

## D-130. Track 45 — 장바구니 조회/삭제/수량변경/selected 토글 (A급·2026-07-05) [ACTIVE]

buyer 장바구니 관리 4기능(조회 GET·삭제 DELETE·수량변경 PATCH·selected 토글 PATCH) 신설. 담기(Track 40)만 있던 cart에 조회·수정·삭제 경로를 배선해 구매 UX를 완성한다. 인가·결제 로직 무변경.

### §1-A 판단별 채택·기각

- M1 대상 식별키 = **variantId** (α채택)
  · α variantId: 내부 PK 미노출(D-124 관례)·UK(user_id, variant_id)로 buyer당 유일·소유권 userId 스코프 조회로 자동 성립.
  · β cart_item.id 노출: 단건 식별 직관적이나 내부 PK 노출로 D-124 관례 이탈·소유권 별도 검증 필요 → 기각.

- M2 삭제 범위 = **단건 + 다건(variantId 배열)** (α채택)
  · α 다건 배열(단건도 배열 1개): 기존 deleteByUserIdAndVariantIdIn(주문 후 비우기용) 그대로 재사용·엔드포인트 1개로 단·다건 흡수.
  · β 단건 전용 신규 쿼리: 다건 삭제 시 N회 호출·기존 다건 쿼리 방치 → 기각.

- M3 수량변경 = **절대값 지정 신규 mutator** (α채택)
  · α changeQuantity(절대값): 프론트 수량 입력 UX 정합·addQuantity(누적)와 의미 분리·CRT-2 하한 재검증(팩토리/mutator 불변조건 경계 정합).
  · β addQuantity 증분 재사용: 절대값 UX에 델타 계산 강제·의미 오염 → 기각.

- M4 selected 토글 = **단건 + 전체 선택/해제** (α채택)
  · α 단건 + 전체: "전체 선택/해제" 장바구니 표준 UX 포함. 결제 소비처(findByUserIdAndSelectedTrue) 무변경으로 부분결제 자동 실동작·전부 해제는 기존 EmptyCartCheckoutException(422) 가드 작동.
  · β 단건만: 전체 토글 프론트 N회 호출 강제 → 기각. create() selected=true 기본 유지(신규 담기 기본 선택).

- M5 dangling(담긴 후 variant soft-delete·비-SALE·판매자 비-ACTIVE) = **purchasable=false 표기 유지** (α채택)
  · α 표기 유지: 즉시 자동삭제는 사용자 혼란(장바구니에서 말없이 사라짐). purchasable 플래그로 프론트가 구매불가 표기·수량조정 유도.
  · β 조회 시 자동 제외/삭제: 사용자 의도 없는 삭제·UX 불투명 → 기각.

- M6 조회 페이징 = **없음(전량 반환)** (α채택)
  · α 전량: 장바구니 규모 소규모·페이징 선례 없음·단순.
  · β 페이징: 오버엔지니어링(기조 4·소비처 없는 복잡도) → 기각.

- M7 대상 미담김 예외 = **CartItemNotFoundException 신규(404)** (α채택·보강 정찰 근거)
  · 보강 정찰 실측: cart에 item-not-found 예외 부재(EmptyCartCheckout 422·ProductVariantNotFound는 product 도메인). PATCH 대상 미담김 표현 예외 없음.
  · α cart 전용 신규 예외: "장바구니에 안 담김"≠"상품 변형 자체 부재"·의미 분리·타 buyer 소유도 동일 404 은닉.
  · β ProductVariantNotFoundException 재사용: 의미 오염(상품 부재로 오인) → 기각.

### §2 재진입 근거
Track 44 정찰에서 cart 4항목(조회 목록 쿼리·수량 mutator·selected mutator·not-found 예외) 부재 확인 → Track 45 정밀 정찰(recon-report.md)로 재사용 표면 확정 후 착수. 구현 중 M7·enrich 소스 게터 시그니처 미실측 발견 → 보강 정찰(read-only)로 실측 확정 후 구현 재개(추측 구현 차단·기조 5).

### §3 배선 실측 (파일:라인)
- CartItem.changeQuantity(int)·select()·deselect() 신설(CartItem.java). CRT-2 재검증 메시지 create()와 동일.
- CartItemRepository.findByUserId(Long) 파생 쿼리 신설.
- CartService.getCart(enrich·variant→product→seller·inventory)·removeItems·changeQuantity·setSelected·setSelectedAll 신설. enrich = findByIdIn/findByVariantIdIn 4종 재사용·정책은 variant 단위 재구현.
- displayPrice = product.getBasePrice() + variant.getAdditionalPrice().
- 품절 = variant.isSoldoutManual() OR inventory.getQuantityAvailable()==0 (2엔티티 결합·Inventory 단독 불가).
- purchasable = variant≠null ∧ product≠null ∧ seller≠null ∧ !soldoutManual ∧ available>0 ∧ variant.status==SALE ∧ product.status==SALE ∧ seller.status==ACTIVE.
- **dangling 재고 게이트**: Inventory는 soft-delete/@SQLRestriction 없음 → variant soft-delete 시에도 재고행 잔존. toView에서 variant==null이면 available=0으로 게이트(표기 정합·테스트 T1 포착).
- CartItemNotFoundException(cart/exception)·GlobalExceptionHandler CODE_CART_ITEM_NOT_FOUND 404 매핑 신설.
- CartController: GET /api/v1/cart·DELETE /api/v1/cart/items·PATCH /api/v1/cart/items/quantity·/selected·/selected/all 5종 신설.
- SecurityConfig 무변경(/api/v1/cart/**→hasRole("BUYER") 기존 매처가 GET/DELETE/PATCH 커버).

### 진입점 카드
1. 목적: buyer 장바구니 조회·삭제·수량변경·selected 토글(구매 UX 완성).
2. 진입점 파일: CartController(5 endpoint)·CartService.getCart·CartItem.changeQuantity/select/deselect.
3. 핵심 SoT 메서드: CartService.getCart(enrich)·removeItems(deleteByUserIdAndVariantIdIn 재사용)·setSelected/setSelectedAll(결제 소비처 무변경).
4. 영향 범위: cart 도메인 한정. 결제(CartCheckoutService)·인가(SecurityConfig)·재고·주문 무변경.
5. 패턴 재사용: enrich Repository findByIdIn 4종(D-129)·deleteByUserIdAndVariantIdIn(Track 40)·BuyerActorResolver·not-found 404 은닉(D-129 카탈로그). 26회차.
6. 트랩 주의: Inventory soft-delete 부재→dangling 재고 게이트 필수(신규 LT 후보·D-129 §카드 N+1과 동일 원인). CRT-2 하한 엔티티 재검증.

### §8 carry-over
- Inventory soft-delete/@SQLRestriction 부재로 dangling 재고 게이트를 서비스에서 방어(구조적·LT-06 이관 후보 ≥3건 시).
- selected 전체 토글은 findByUserId 로드 후 순회 update(대량 시 벌크 update 미도입·장바구니 소규모 전제).
- 누적 이월: SALE variant 0개 fallback(D-129)·응답 DTO 명칭 드리프트(D-128)·X-Seller-Id Javadoc 레거시·상태 전이 쓰기 부재(Track 7)·외부 택배 seam·자동 구매확정(E6).

---

## D-131. Category 생성 경로 신설 (Track 46) [ACTIVE]

작성일: 2026-07-05 · 등급: B급(마스터 데이터·상태머신/이벤트 없음·단일 정찰) · 외부 검토: 생략 대상이나 자발적 2회 수행(M4·D3 실측 종속 판정 확정용)

### 목적
category 테이블이 비어(seed·INSERT 경로 부재) 상품 등록의 categoryId existsById가 구조적으로 항상 실패하던 blocker를 해소. ADMIN 전용 루트 카테고리 생성 쓰기 경로를 신설.

### §1-A. 결정 옵션·채택/기각 근거

**M1. 초기 데이터 공급** — α 채택
- α(채택) 생성 API로만 부트스트랩·seed 미도입: 운영 흐름(ADMIN 생성→SELLER 상품등록)이 자연 경로. seed는 소비처 없어 테스트 전용 데이터가 됨(기조 4).
- β(기각) seed 마이그레이션: 우회일 뿐 생성 경로 부재 문제 미해결. 통합테스트도 생성→상품등록 체이닝이 실운영 시나리오와 정합.

**M2. 트리 계층** — α+수정 채택
- α(채택) API Flat·parent=null 고정: MVP 유일 소비처(상품등록 categoryId 참조)가 평면으로 충족. 계층 소비 기능(breadcrumb·카테고리별 조회) 0개(기조 4).
- β(기각) 계층 지원: 소비처 부재.
- 외부 검토 반영 수정: Service 메서드명 `createRootCategory`로 절단선을 이름에 명시. 도메인은 트리 전제(entity self-ref·DDL self-FK) 유지, API만 루트 한정. 향후 `createChildCategory` 확장 대비.

**M3. depth/sortOrder 산출** — α 채택
- α(채택) depth 서버 고정=1·sortOrder 요청 수용(기본 0): depth는 트리 파생값이라 클라이언트가 알 정보 아님. 평면이면 항상 1. sortOrder만 정렬 용도 수용.
- β(기각) 둘 다 클라이언트 제공: depth는 derived field.

**M4. 식별자 노출** — α 채택 (2차 정찰로 확정)
- α(채택) 내부 Long id 노출·DDL/entity 무변경: 2차 정찰 실측 — ProductSummaryResponse(Track 44)가 이미 `categoryId` Long을 "공개 taxonomy 식별자"로 응답에 박제(shipped 계약). category는 public_id 컬럼 부재. α가 기존 계약 정합.
- β(기각) public_id 신설: ① category에 public_id 부재 ② shipped된 Long categoryId 공개 계약과 충돌 ③ 마이그레이션+entity+상품등록 파급(기조 1·4). 외부 검토가 "public_id가 표준이면 맞춰야"라 지적했으나 정찰 결과 표준 아님(public_id 보유 12 Aggregate만 노출·category는 의도적 Long 노출)이 실측 확정 → α.

**M5. 권한** — ADMIN 전용
- SecurityConfig `/api/v1/admin/**`→hasRole("ADMIN") matcher 강제(@PreAuthorize 미사용·house 관습). 카테고리는 플랫폼 정책 데이터·판매자 생성 데이터 아님.

**D1. displayName 중복 정책** — γ 채택 (2차 정찰 종속)
- 2차 정찰 실측: category UNIQUE 전무·앱단 중복 방어 코드 부재("현재 무방어" 확정).
- α(기각) 중복 허용: 분류 혼란.
- β(기각) 전역 UNIQUE: 향후 계층 확장 시 정상 케이스(전자제품>액세서리 / 패션>액세서리) 차단.
- γ(채택) 형제 스코프 (parent_id, display_name) UNIQUE: Flat 현행에선 루트 형제 유니크로 동작·계층 확장 정합. → 단 구현 시 블로커로 후퇴(아래 §블로커).

**D1b. soft-delete UNIQUE 상호작용** — β 채택
- β(채택) deleted_at 포함 UNIQUE: soft-delete 후 동일명 재생성 허용. @SQLRestriction("deleted_at IS NULL") 정합.

**D2. sortOrder 유효 범위** — β 채택
- β(채택) @PositiveOrZero: 정렬 인덱스 음수 불필요. @NotNull 동반.

**D3. 재생성 동시성** — house 가드 채택
- saveAndFlush→DataIntegrityViolationException→CategoryDuplicateException(409). 앱단 existsBy 선검사 불필요(DB 최종 방어선). 외부 검토가 "앱 체크만으로 SELECT→INSERT race 미방어" 지적 → DB UNIQUE 기반 가드로 확정.

**D4. 삭제 정책** — carry-over(미구현)
- 이번 트랙 생성만. 삭제 소비처 없음(기조 4). fk_product_category ON DELETE RESTRICT가 상품 참조 중 물리삭제를 DB 레벨 차단(기본 무결성 보장).

### §2. 결정 라운드 재진입 근거
- 1차 정찰 후 M1~M5 제시 → 외부 검토 1차: M4 재검토 권고·운영 규칙 4건(D1~D4) 누락 지적.
- M4·D1·D3 실측 종속 판정 → 2차 정찰(public_id 관습·category UNIQUE) 후 확정.
- 외부 검토 2차: M2 네이밍 수용·D3 동시성 보완 지적 반영.

### §3. 배선 실측 (구현 결과)
- V13 마이그레이션: dedup_key STORED generated + uk_category_dedup_key
- 요청 DTO CreateCategoryRequest(@NotBlank @Size(200) displayName·@NotNull @PositiveOrZero sortOrder)
- 응답 DTO CreateCategoryResponse(Long categoryId)
- CategoryService.createRootCategory(parent=null·depth=1·saveAndFlush→409 house 가드)
- AdminCategoryController POST /api/v1/admin/categories 201
- CategoryDuplicateException + GlobalExceptionHandler CODE_CATEGORY_DUPLICATE 409
- 검증: 680 tests·failures 0·--rerun-tasks(캐시 아님)·category 6/6 GREEN

### §블로커 실측 (구현 중 발생·설계 갱신 2건)

**블로커 1**: 확정 DDL UNIQUE(parent_id, display_name, deleted_at)가 MariaDB NULL≠NULL로 무효.
- 원인: 루트(parent_id NULL)·활성(deleted_at NULL) 행이 NULL 포함으로 중복 판정 제외 → 무제한 중복 허용·saveAndFlush 409 미발동.
- 해결(경로 B·승인): STORED generated dedup_key로 우회. 활성 행만 non-null·soft-delete 시 NULL.

**블로커 2**: generated 식에 parent_id 사용 불가.
- 원인: fk_category_parent(self-FK·V1)의 ON UPDATE CASCADE. MariaDB는 참조 액션이 값을 바꿀 수 있는 컬럼을 GENERATED 식에서 금지.
- 해결(경로 1·승인): dedup_key를 display_name만으로 구성 = "활성 display_name 전역 유니크". 루트 전용 현 상태에선 형제 스코프와 동치. parent_id 포함(진짜 형제 스코프)은 §8 carry-over.
- 경로 2(ON UPDATE CASCADE 제거)를 지금 안 한 이유: 자식 생성 기능·자식 행 0개 → 존재하지 않는 자식용 개조는 YAGNI(기조 4). dead 제약 제거지만 결함 처치 아닌 미래 대비 개조. FK 재생성이 최소 변경 원칙 이탈.

### §진입점 카드
1. **목적**: ADMIN 루트 카테고리 생성 쓰기 경로(빈 category→상품등록 불가 blocker 해소)
2. **핵심 진입점 파일/라인**: AdminCategoryController(POST /api/v1/admin/categories)·CategoryService.createRootCategory·V13__add_category_dedup_unique.sql
3. **핵심 SoT 메서드**: CategoryService.createRootCategory(parent=null·depth=1·saveAndFlush→409)
4. **영향 범위**: category 도메인 신설(controller/service/dto/exception)·GlobalExceptionHandler 409 추가·category 테이블 V13(generated 컬럼+UNIQUE)
5. **패턴 재사용 SoT**: saveAndFlush→DataIntegrityViolationException→409 house 가드(D-115 SellerProvisioning·Track 39 ProductRegistration)·admin SecurityConfig matcher·record DTO
6. **트랩 주의**: MariaDB UNIQUE NULL≠NULL(블로커1)·generated 컬럼 ON UPDATE CASCADE 참조 금지(블로커2)·soft-delete+UNIQUE dedup_key 기법

### §8. carry-over
- **자식 카테고리 생성 경로 신설 시**: (1) fk_category_parent의 ON UPDATE CASCADE 제거 선행 → (2) dedup_key를 CONCAT(COALESCE(parent_id,0),':',display_name)로 확장해 형제 스코프 재검토. (블로커2 근거·V13 주석 박제)
- **D4 삭제 정책**: 미구현. 삭제 경로 신설 시 상품 FK(ON DELETE RESTRICT)·soft-delete·하위 카테고리 관계 동반 결정.
- **dedup_key collation**: 테이블 기본 utf8mb4_unicode_ci 상속 → 대소문자 무구분 유니크(uk_user_email 관습 정합).
- **soft-delete + UNIQUE 기법**: house 첫 generated-컬럼 dedup 선례. ≥3건 누적 시 live-traps.md LT 이관 후보.

---

## D-132. 구매확정(E6) buyer 수동 확정 경로 신설 (Track 47) [ACTIVE]

작성일: 2026-07-05 · 등급: A급(상태 전이 쓰기·외부 검토 선택·생략) · 선행 관계: Settlement 정산 트리거(CONFIRMED) 상태 확보용 선행

### 목적
buyer가 배송완료(DELIVERED)된 OrderItem을 구매확정(CONFIRMED)으로 전이하는 수동 쓰기 경로 신설. 직전 정찰에서 CONFIRMED가 enum·상태머신·OrderStatusResolver에만 존재하고 전이 쓰기 경로가 전무(데이터로 CONFIRMED 미생성)함이 실측 확정 → 정산이 소비할 확정 상태를 데이터로 생성하기 위한 선행 트랙.

### §1-A. 결정 옵션·채택/기각 근거

**E1. 트리거 방식** — α 채택
- α(채택) buyer 수동 확정만: MVP 기본 플로우(buyer가 구매확정 버튼). 소유검증 SoT(SellerDeliveryCompletionController·D-92) 복제로 충족.
- β(기각) 자동 배치 확정: state-machine.md:174에서 명시적 이연 상태·N일 값 미정. @EnableScheduling 인프라는 이미 가동 중이라 향후 착수 비용 낮음 → 지금 열 필요 없음(기조 4).
- γ(기각) 둘 다: 자동까지 지금 열 소비처 없음.
- carry-over: 자동 구매확정(배송 후 N일→CONFIRMED)은 §8 이연 유지.

**E2. 이벤트 발행 범위** — α 채택
- α(채택) CONFIRMED 전이 + Order.status 재계산까지만: CONFIRMED 전이는 소비처 있음(OrderStatusResolver.java:47-48이 전 항목 CONFIRMED→Order.status=CONFIRMED 파생, 이미 구현) → 죽은 코드 아님.
- β(기각) 이벤트 발행 + 정산 적재 핸들러 포함(정산 통합 트랙화): E6+Settlement 한 트랙 합침 → S급화·규모 증대.
- PurchaseConfirmed 이벤트 record·publish·정산 적재는 전부 Settlement 트랙 이월. 순수 이벤트만 발행(소비처 0)은 기조 4 위반이라 배제. 발행 지점은 코드 TODO 주석이 아니라 Settlement 트랙 진입점 카드로 인계(기조 3).

### §2. 결정 라운드 재진입 근거
- Settlement 착수 정찰 → 판정 B(부분 선행 필요): 정산 트리거(CONFIRMED)·수수료 정책 부재 실측.
- 사용자 결정 Q1=β(구매확정 기준 정산)·Q2=α(판매자 단일율) → E6 선행 필수 확정.
- E6 범위 정찰 → 규모 中(전 요소 기존 SoT 1:1 복제) → 이 채팅 추가 트랙으로 진행.

### §3. 배선 실측 (구현 결과)

신설(4):
- OrderItemInvalidStateException — 구매확정 불가 상태 422(order 도메인 예외)
- BuyerOrderConfirmService.confirmPurchase(buyerId, orderPublicId, orderItemPublicId) — 소유권 대조→항목 매칭→멱등 no-op→changeStatus(CONFIRMED)→recalculateStatus
- ConfirmPurchaseResponse(orderItemId·status)
- BuyerOrderConfirmControllerIntegrationTest 5건

수정(3):
- GlobalExceptionHandler — CODE_ORDER_ITEM_INVALID_STATE 422 매핑(import·상수·핸들러)
- BuyerOrderController — POST /api/v1/orders/{orderPublicId}/items/{orderItemPublicId}/confirm + 의존성 주입
- BuyerOrderControllerTest — @MockitoBean 추가(회귀 방지)+confirm 슬라이스 2건

재사용 SoT(무수정):
- OrderItem.changeStatus(OrderItem.java:117)·OrderItemStatus.canTransitionTo(DELIVERED→CONFIRMED 이미 합법)
- OrderService.recalculateStatus(OrderService.java:105)·OrderStatusResolver(Order.status 파생 무수정)
- 멱등 no-op(DeliveryCompletedHandler 패턴)·소유권 대조(BuyerOrderQueryService.getOrder D-92)·IllegalStateException→422 흡수(OrderShippingService.markDeliveredBySeller 패턴)·BuyerActorResolver 인증

검증: 687 tests·failures 0·--rerun-tasks(캐시 아님)·Track 46(680) 대비 +7·기존 680 전원 통과(회귀 0). 통합 5: T1 401·T2 200(DELIVERED→CONFIRMED·Order CONFIRMED 실 커밋)·T3 404(타 buyer 은닉)·T4 422(SHIPPING)·T5 멱등 200.

### §관습 정합 판정 (예외 신설 근거·중요)
확정 설계는 "IllegalStateException→422 흡수(markDeliveredBySeller 패턴)"였으나, 그 패턴이 쓰는 DeliveryInvalidStateException을 재사용하면 응답 code가 DELIVERY_INVALID_STATE로 나가 buyer 구매확정 실패에 "배송 상태 위반" 코드가 붙는 오분류가 발생. ClaimInvalidStateException·DeliveryInvalidStateException이 도메인별 422 예외를 각자 두는 선례(GlobalExceptionHandler §422)에 맞춰 order 전용 OrderItemInvalidStateException을 신설. "기존 스타일 유지"의 자연 귀결이며 임의 확장 아님(Claude.ai MCP read 검증 완료: 서비스·예외·핸들러·컨트롤러 4지점 대조).

### §식별키·소유권 결정 (정찰 근거)
- 확정 대상 식별키 = orderItemPublicId(oit_): OrderItemResponse가 orderItemId를 public_id로 노출. 멀티벤더(order_item.seller_id)라 항목별 확정이 정합. 경로에 orderPublicId도 포함해 주문 소유·항목 소속 동시 해소.
- 소유권 = order→buyerId 대조(BuyerOrderQueryService.getOrder 패턴). findByPublicIdWithItems로 items 선로딩 후 매칭. 미존재·타인·미소속 전부 404 은닉(§2).

### §진입점 카드
1. 목적: buyer 수동 구매확정(DELIVERED→CONFIRMED) 쓰기 경로·정산 트리거 상태 확보
2. 핵심 진입점: BuyerOrderController POST /api/v1/orders/{orderPublicId}/items/{orderItemPublicId}/confirm · BuyerOrderConfirmService.confirmPurchase
3. 핵심 SoT 메서드: OrderItem.changeStatus(CONFIRMED)·OrderService.recalculateStatus
4. 영향 범위: order 도메인(service/controller/exception/dto 신설)·GlobalExceptionHandler 422 추가. Order.status 파생은 OrderStatusResolver 기구현 재사용(무수정)
5. 패턴 재사용 SoT: 소유권 대조(getOrder D-92)·멱등 no-op(DeliveryCompletedHandler)·IllegalStateException→422 흡수(markDeliveredBySeller)·도메인별 422 예외 신설(Claim/Delivery 선례)
6. 트랩 주의: IllegalStateException 직접 매핑 금지(500 fallback)·delivery 예외 재사용 시 code 오분류

### §8. carry-over
- **PurchaseConfirmed 이벤트 발행**: Settlement 트랙에서 BuyerOrderConfirmService.confirmPurchase 전이 지점에 publish 추가 + record 신설 + 정산 적재 핸들러를 한 몸으로 배선(소비처와 짝). Read Model(SellerSalesDaily·BuyerPurchaseAggregate) 소비는 PR-03 영역.
- **자동 구매확정(배송 후 N일→CONFIRMED)**: state-machine.md:174 이연. 착수 시 ExpirePaymentScheduler 패턴 복제(@EnableScheduling 가동 중)·delivered_at 기준 조회(delivery join 신규 쿼리)·N일 값 결정 필요.
- **판매자 수수료율 정책(Q2=α 단일율)**: Settlement 트랙에서 seller 컬럼 + Flyway 마이그레이션으로 신설. fee 계산 입력값.

---

## D-133 [ACTIVE] Settlement 월 배치 정산 생성 (Track 48·S급)

### §진입점
- 트랙: Track 48 (Settlement 정산·S급·외부 검토 수행)
- 선행: D-132 (Track 47 구매확정 CONFIRMED 쓰기 경로 — 정산 트리거 상태의 데이터 공급원)
- 마이그레이션: V14__settlement_track_columns.sql
- 신규 엔드포인트: POST /api/v1/admin/settlements (ADMIN)
- 핵심 파일: SettlementCreationService·AdminSettlementController·OrderItemRepository.aggregateGrossBySeller·RefundRepository.aggregateRefundBySeller
- 회귀: 699 tests PASS (failures 0·--rerun-tasks 실측)

### §1-A 옵션 검토·채택/기각

**결정축 1 — 정산 생성 트리거 (외부 검토 수행)**
- α 이벤트 실시간 개별 적재 / β 운영자 API 월 배치 / γ 혼합(대기 라인+배치)
- **채택 = β.** settlement DDL이 period_start/end + gross/fee/refund/net 합산 구조 = 기간 롤업 전제(집계 Aggregate). α는 order_item별 실시간 1:1 적재와 DDL 불일치. γ의 대기 라인 테이블은 배치가 CONFIRMED order_item을 직접 쿼리하면 불요.
- **기각 α**: 실시간 적재는 SettlementLine 등 별도 적재 테이블 필요 = DDL 개편. **기각 γ**: 신규 테이블·이벤트·핸들러·중복방지 전부 추가 = MVP 과잉. `SELECT ... GROUP BY seller` 한 번으로 충분.
- 인계 지시("PurchaseConfirmed 이벤트+정산 핸들러 한 몸 배선")와 DDL 실측(기간 롤업)이 충돌 → DDL 기준 수렴(문서는 이미 구현된 모델, 인계는 설계 의도).

**결정축 2 — 수수료율 저장처**
- α seller 컬럼 / β 전역 상수 / γ code 테이블
- **채택 = α.** seller.commission_rate 신설. 판매자 단일율 확정과 정합. β는 판매자별 차등 표현 불가·γ는 값 하나에 과잉.

**결정축 3 — 집계·환불**
- gross = 기간 CONFIRMED order_item.total_price 합 / fee = gross × commission_rate / 10000 / refund = refunded_at 기준 COMPLETED refund 합 / net = gross − fee − refund.
- 환불 귀속 = refunded_at 기준 주기(구매확정 주기 아님). 운영 회계 관행(실제 환불 시점 비용 인식)·구매확정 주기 소급 재계산 회피.

**결정축 4 — 기간 overlap 방지**
- α 월 고정 주기(year/month 입력·서버 기간 계산) / β 자유 기간+overlap 검증
- **채택 = α.** UNIQUE(seller_id, period_start, period_end)는 동일 기간만 차단하므로 overlap은 월 고정으로 구조적 제거. β의 자유 기간은 overlap 쿼리·경계·409 부담만 추가(주간·분기 요구 실재 시 도입).

**추가 결정**
- **D-133-a net 음수**: 저장 허용(가드 없음). 지급 가능 여부·상계 정책은 지급(PAID 전이) 트랙 이연. 책임 경계: Settlement 생성 = 정확한 산출·기록 / 지급 트랙 = 음수 지급·상계 결정.
- **D-133-b commission_rate 스냅샷**: settlement.commission_rate 신설. 정산 생성 시점 seller 율 박제 → 사후 율 변경(10%→8%→12%)과 무관한 재현성·감사성.
- **D-133-c 중복 방지**: UNIQUE(seller_id, period_start, period_end). order_item settled 표식(상태·롤백 추가) 기각.
- **D-133-d confirmed_at 신설**: order_item에 confirmed_at 컬럼 신설(updated_at proxy 기각). updated_at은 "최종수정"이라 CONFIRMED 행에 후속 갱신이 닿으면 정산 월 귀속 오염 → 도메인 시점(Event Time)을 기술 수정 시각과 분리.
- **D-133-e created_by NULL 유지**: AuditorAwareImpl이 항상 empty 반환(전 엔티티 created_by NULL)이므로 Settlement만 operator 특례 기각. AuditorAware 개편은 운영자 추적 요구 실재 시 별도 트랙.
- **D-133-f commission_rate 자료형**: basis-point 정수(INT·1000=10.00%). DECIMAL 도입 기각(전 스키마 BIGINT 정수 관례). default 1000(10%)·기존 seller backfill 1000·신규 seller 1000.

### §2 결정 라운드 재진입·수렴
- 외부 검토 회신: 결정축 1=β·2=α·3=추천안 전부 지지. 반박 없음. 추가 지적(중복 생성 방지·commission_rate 스냅샷·net 음수 허용)을 D-133-a/b/c로 흡수.
- 착수 전 정찰에서 표면화한 3블로커(confirmed_at 부재·created_by NULL·commission_rate 자료형) → D-133-d/e/f로 수렴.
- PurchaseConfirmed 이벤트: 발행하지 않음 확정. 정산이 운영자 월 배치라 이벤트 소비처 부재 → 발행 시 호출자 없는 코드. 실 소비처(read model·알림·적립 등) 도입 시 confirmPurchase에 발행 추가(Aggregate 무손상).

### §3 배선 실측
- **V14**: order_item.confirmed_at(DATETIME(6) NULL)·seller.commission_rate(INT NOT NULL DEFAULT 1000)·settlement.commission_rate(INT NOT NULL DEFAULT 1000)·uk_settlement_seller_period UNIQUE(seller_id, period_start, period_end).
- **Settlement.create()**: 시그니처에 Integer commissionRate 추가(feeAmount·refundAmount 사이). null 검증 포함. netAmount = grossAmount − feeAmount − refundAmount 불변.
- **confirmedAt 배선**: BuyerOrderConfirmService.confirmPurchase에서 changeStatus(CONFIRMED) 성공 후 target.markConfirmedAt(LocalDateTime.now()) 호출. 멱등 no-op 경로 미호출. markConfirmedAt 자체 기존값 미덮어쓰기 가드.
- **gross 집계**: OrderItemRepository.aggregateGrossBySeller — JPQL·itemStatus=:status(CONFIRMED 바인딩)·confirmed_at 양끝 경계·COALESCE(SUM(total_price),0)·GROUP BY seller_id·confirmed_at NULL 자연 제외.
- **refund 집계**: RefundRepository.aggregateRefundBySeller — theta-join(r.claimId=c.id AND c.orderItemId=oi.id·전부 Long ID 참조로 연관 탐색 불가)·status=:status(COMPLETED)·refunded_at 경계·COALESCE(SUM(amount),0)·GROUP BY oi.seller_id.
- **SettlementCreationService.createMonthlySettlements(year, month)**: YearMonth로 periodStart(1일 00:00:00.000000)·periodEnd(말일 23:59:59.999999) 산정 → gross·refund 두 집계 Map을 seller_id 합집합(TreeSet 결정적) 순회 → seller별 fee = gross×commissionRate/10000(long·버림)·Settlement.create·saveAndFlush. 중복 exists 선확인 seller별 skip + DataIntegrityViolation→SettlementAlreadyExistsException(409). seller 부재·주 정산계좌(is_primary) 부재는 skip+WARN(배치 무중단).
- **예외**: SettlementPeriodInvalidException(400·year 2000~2100·month 1~12 범위 밖)·SettlementAlreadyExistsException(409). GlobalExceptionHandler 매핑.
- **타임존**: confirmed_at 저장·집계 조회가 동일 LocalDateTime 바인딩 경로라 세션 TZ 오프셋 양쪽 상쇄 → periodEnd .999999 경계 정합. 테스트 시드도 EntityManager LocalDateTime 바인딩으로 통일(raw JDBC 시드는 경계 밀림 트랩 회피).

### §8 carry-over (이월)
1. **Settlement 전이/지급 트랙**: PENDING→CONFIRMED(운영자 금액 확정)·PAID(지급)·SettlementStatus.canTransitionTo(STL-2)·전이 AuditLog(STL-4)·net 음수 지급/상계 정책. 이번 트랙은 PENDING 생성까지.
2. **PurchaseConfirmed 이벤트**: 실 소비처(read model·알림·적립) 도입 트랙에서 confirmPurchase에 발행 배선.
3. **계좌 부재 seller 가시성**: 매출 있으나 주 정산계좌 부재로 skip된 seller를 응답 DTO에 노출(현재 WARN 로그만). 운영 요구 시.
4. **fee 오버플로우 경계**: gross > 약 920조 시 gross×10000 long 오버플로우(현실적 무위험·이론 경계 기록).

---

## D-134 [ACTIVE] Settlement 전이/지급 (Track 49·S급)

### §진입점
- 트랙: Track 49 (Settlement 전이/지급·S급·외부 검토 수행)
- 선행: D-133 (Track 48 PENDING 정산 생성 — 전이 대상 데이터 공급원)
- 마이그레이션: 없음 (V1 기존 status ENUM·paid_at 컬럼 재사용·DDL 무변경·V15 미생성)
- 신규 엔드포인트: POST /api/v1/admin/settlements/{id}/confirm·/{id}/pay (ADMIN·200)
- 핵심 파일: SettlementTransitionService·SettlementStatus.canTransitionTo·Settlement.markConfirmed/markPaid·SettlementRepository.findByIdForUpdate
- 회귀: 721 tests PASS (699→+22·failures 0·--rerun-tasks 실측)

### §1-A 옵션 검토·채택/기각

**결정 1 — STL-4 AuditLog 기록 범위** — α 채택 (외부 검토 지지)
- α(채택) 이번 트랙 제외·전역 감사 트랙 이연: AuditLog 기록 서비스가 프로젝트 전역 부재(선례 0·정찰 실측). 전이 트랙에 기록 인프라 신설을 얹으면 S급 2트랙 규모로 팽창. STL-2(불법 전이 차단)는 canTransitionTo가 Domain에서 보장하므로 감사 부재가 MVP 정합을 깨지 않음.
- β/γ(기각) 최소·full 기록: actorUserId 조달·targetType 매핑·diff 정책 미확정. 감사는 전역 일괄 도입이 응집도 높음. 코드 TODO 주석 미삽입(기조 3·진입점 카드로 인계).

**결정 2 — 지급(CONFIRMED→PAID) 트리거** — α 채택
- α(채택) 운영자 수동 마킹만: PAID = "외부 송금 완료 사실의 내부 기록". 실 송금 실행(은행·PG 연동)은 MVP 시나리오에서 호출 경로 없음(기조 4 이연 대상).
- β(기각) 입금 콜백 seam(Mock): 실 어댑터 도입 시에만 호출되는 경로. 향후 콜백 도입 시 진입점(BankTransferCallbackHandler→pay)만 추가·Domain 재사용이라 재설계 비용 낮음.

**결정 3 — 전이 API 형태** — α 채택
- α(채택) 상태별 개별 엔드포인트(confirm·pay): confirm(금액 확정)·pay(paid_at 세팅)는 부수효과가 달라 행위별 분리가 명확. 운영자 UI 버튼("확정"·"지급 완료 처리")과 자연 대응. 기존 전이 서비스 선례가 행위별 메서드.
- β(기각) 단일 엔드포인트+target status: pay 전용 분기 필요·상태 추가 시 복잡도 증가.

**결정 4 — 경로 식별키** — α 채택
- α(채택) 내부 PK(Long id) 노출: Settlement는 ARCHIVE·public_id 컬럼 부재(entity Javadoc). ADMIN 전용 엔드포인트라 public_id 은닉(구매자 대상 관례·ADR-001 12 Aggregate)의 이유 부재. 열거 공격은 인증·권한 게이트가 차단.
- β(기각) seller_id+period 복합 조회: 식별 규칙을 API에 노출·조회 조건 증가.
- 관례 불일치(oit_·ord_ public_id vs Settlement Long) 인지·의도적 선택으로 박제.

**결정 5 — CONFIRMED 시 금액 재검증** — α′ 채택 (기존 코드 동작의 명문화)
- α′(채택) 생성 시점 스냅샷 잠금·재집계 없음: Settlement.create() 시점 집계 결과가 회계 스냅샷. Track 48 refund 집계가 refunded_at 기간 필터(D-133 결정축3)이므로 생성 이후 발생 환불은 코드상 이미 다음 주기로 자연 이월 → 스냅샷 잠금이 이미 성립.
- 외부 검토는 "정책 명문화 필요"로 봤으나 실측상 신규 정책 아닌 기존 구현의 문서화. 추가 개발 0. β(재집계 대조) 기각: 기간 계산 로직 중복(기조 4).

**결정 6 — 전이 위반 예외** — α 채택
- α(채택) SettlementInvalidStateException 신규(422): 기존 예외 재사용 시 error code 오분류(D-132 §관습 정합 선례·Delivery 예외 재사용→DELIVERY_INVALID_STATE 오염). IllegalStateException 직접 매핑은 500 fallback 누수. 재사용 후보 없음.

**결정 7 — 동시성 제어** — β 채택 (조건부 UPDATE 기각·비관적 락)
- β(채택) 비관적 락 SELECT FOR UPDATE + Aggregate mutator: 동시성 제어가 프로젝트 전역 비관적 락으로 통일(InventoryRepository.findByVariantIdForUpdate·D-101 house pattern·정찰 실측). Aggregate 정상 통과(mutator→dirty checking→UPDATE)·AuditingEntityListener 정상 작동(벌크 UPDATE 감사 우회 문제 소멸).
- α(기각) @Modifying 조건부 UPDATE: 원자적 이중 전이 차단 목적은 동일하나, 신규 패턴 도입·감사필드 수동 처리·Aggregate 행위 우회. 외부 검토가 조건부 UPDATE를 예시로 지지했으나 house pattern(비관적 락) 실측 후 재확정 → 외부 검토 2차 비관적 락 지지.
- 이중 클릭·2세션 경쟁: findByIdForUpdate 행 락으로 직렬화·후행 TX는 갱신 상태 재조회 후 멱등 no-op 또는 canTransitionTo로 안전 종료.

**STL-5 신규 불변식**: status=PAID ⟺ paid_at≠null · Enforcement=Domain(markPaid) · 회계 상태 일관성(지급 시각 없는 지급 완료 차단). markPaid만 paid_at 세팅·paidAt null 가드 내장.

### §2 결정 라운드 재진입·수렴
- 외부 검토 1차: 결정 1/2/3/4/6 지지·결정 5만 수정 권고(스냅샷 잠금 명문화)·추가 리스크 4건(동시성·멱등 no-op·paidAt 불변식·canTransitionTo 위치) 제기.
- 결정 5: α′로 보정 — "신규 정책"이 아닌 기존 코드 동작(refunded_at 기간 필터)의 명문화로 수렴. 외부 검토 재검토 동의.
- 동시성: 외부 검토 β 지지하되 조건부 UPDATE 예시 제시 → 구현 직전 정찰에서 house pattern(비관적 락·D-101) 실측 → 조건부 UPDATE 기각·비관적 락 재확정. 외부 검토 2차 비관적 락 지지(house 일관·Aggregate 통과·Auditing 정합).
- 멱등 no-op·canTransitionTo·paidAt 불변식: 추가 리스크를 설계에 흡수(멱등 return·enum canTransitionTo·STL-5 신설).

### §3 배선 실측
- **SettlementStatus.canTransitionTo(next)**: switch — PENDING→CONFIRMED·CONFIRMED→PAID·PAID→false(종결 불가역). OrderItemStatus 패턴.
- **Settlement.markConfirmed()**: canTransitionTo(CONFIRMED) 위반 시 IllegalStateException·성공 시 status=CONFIRMED.
- **Settlement.markPaid(LocalDateTime)**: paidAt null 가드(IllegalArgumentException·STL-5)·canTransitionTo(PAID) 위반 시 IllegalStateException·성공 시 status=PAID+paidAt 세팅.
- **SettlementRepository.findByIdForUpdate(id)**: @Lock(PESSIMISTIC_WRITE) + @Query("SELECT s FROM Settlement s WHERE s.id = :id"). Inventory house pattern.
- **SettlementTransitionService.confirm(id)/pay(id)**: @Transactional 클래스. findByIdForUpdate→멱등 no-op(이미 목표 상태면 return)→try mutator catch IllegalStateException→SettlementInvalidStateException(422). pay는 markPaid(LocalDateTime.now()). 소유권 대조 없음(ADMIN 전용·SecurityConfig 게이트).
- **예외**: SettlementInvalidStateException(422·RuntimeException)·SettlementNotFoundException(404). GlobalExceptionHandler CODE_SETTLEMENT_INVALID_STATE 422·CODE_SETTLEMENT_NOT_FOUND 404 매핑(import·상수·핸들러).
- **AdminSettlementController**: POST /api/v1/admin/settlements/{id}/confirm·/{id}/pay·200. base path 없음·@PreAuthorize 없음(SecurityConfig /admin/** 강제·create() 선례). SettlementTransitionResponse(settlementId·status·paidAt).
- **invariants.md**: STL-5 행 추가(2.5 Settlement).
- 검증: 721 tests·failures 0·--rerun-tasks(캐시 아님)·Track 48(699) 대비 +22·회귀 0. 신규 테스트 22(enum 3·entity mutator 6·service 7·controller 통합 6).

### §트랩 실측 (구현 중 발생·coincidental test 실증)
- 전이 controller 통합테스트 T1/T2(성공 전이 경로) 초기 500: settlement seed가 seller·seller_bank_account 부모 없이 FK_CHECKS=0 INSERT만 함. 전이 UPDATE는 앱 커넥션(FK_CHECKS=1)에서 fk_settlement_seller를 재검증하다 실패.
- 핵심: 422/404/403 케이스는 전이 UPDATE가 없어 부모 부재를 통과하던 위양성 = 프로젝트 금지 coincidental test 안티패턴 실증. 부모 행 실시드(create-path 정합)로 해소 → 전이 성공 경로가 실제 FK 재검증을 거침.

### §8 carry-over (이월)
1. **STL-4 상태·금액 변경 AuditLog**: 전역 감사 인프라 도입 트랙에서 Settlement 전이 포함 일괄. 최소 기록 항목 = settlementId·fromStatus·toStatus·actorUserId·changedAt. 현재 기록 서비스 전역 부재.
2. **net 음수 지급/상계 정책**: 현재 pay()는 net 부호 무관 PAID 전이 허용. 음수 정산 상계·다음 주기 이월 정책 미도입(운영 요구 시).
3. **실 송금 연동·입금 콜백 seam**: PAID는 지급 완료 사실 기록만. 실 송금 어댑터·콜백 도입 시 진입점(BankTransferCallbackHandler→pay())만 추가·SettlementTransitionService 로직 재사용.
4. **전이 대상 조회(GET) 엔드포인트**: 운영자 UI에서 PENDING/CONFIRMED 목록 조회 필요 시. 현재 전이 write path만·findById만 가용((seller_id,status) 인덱스 기존재).

---

## D-135 [ACTIVE] 상품 승인 워크플로 (Track 50·A급)

### §진입점
- 트랙: Track 50 (상품 승인 워크플로·A급·외부 검토 수행·MVP full 최종 항목)
- 선행: D-59 (Product 신설·조회 전용)·Track 39 (등록 provisioning·create 도입)
- 마이그레이션: 없음 (rejection_reason 미도입·DDL 무변경·V15 미생성)
- 신규 엔드포인트: POST /api/v1/admin/products/{publicId}/approve·/reject (ADMIN·200)
- 핵심 파일: ProductApprovalService·ProductStatus.canTransitionTo·Product.approve/reject·ProductRepository.findByPublicIdForUpdate·ProductInvalidStateException·AdminProductController
- 회귀: 728 tests PASS (721→+7·failures 0·--rerun-tasks 실측)

### §1-A 옵션 검토·채택/기각

**결정 1 — 승인 워크플로 상태 흐름** — α 채택 (외부 검토 지지)
- α(채택) 등록 PENDING 직행→승인 시 SALE (2단·APPROVED 스킵·승인=즉시 판매): 카탈로그 조회가 status=SALE 기준이라 APPROVED 중간정지는 판매개시 재조작(운영 2스텝) 필요. 단일 운영자 편의상 승인=판매 허가가 자연.
- β(기각) PENDING→APPROVED→판매개시 SALE (3단·판매자 개시 분리): 판매자 재량 개시 요구 부재·운영 복잡도만 증가.
- γ(기각) 등록 SALE 유지+승인 사후 도구: 게이트 무실효 → 승인 경로가 죽은 코드(기조 4).
- APPROVED enum 미활용은 기존 값이라 신규 죽은 코드 아님. 억지 소비처 신설이 오히려 YAGNI 위반.

**결정 2 — 거부(REJECT) 경로·사유 필드** — β 채택 (외부 검토 수정 수용)
- β(채택) 거부 포함·REJECTED 상태만·rejection_reason 미도입: 승인·거부는 짝(거부 없으면 워크플로 불완전). 사유 필드 소비처(판매자 통보/조회)가 MVP 시나리오에 부재 → 지금 컬럼 신설 시 소비처 없는 컬럼(기조 4).
- α(기각) rejection_reason 신설: 판매자 공개 사유 저장 요구 실재 시 후속. 명문화(외부 검토 권고): 본 결정은 "사유 개념 배제"가 아니라 "판매자에게 노출되는 사유 저장 요구가 아직 없음"이라는 의사결정 — 후속 확장 지점을 의도적으로 남김.
- γ(기각) 거부 이연(승인만): 워크플로 불완전.

**결정 3 — AuditLog 적재 인프라** — β 채택 (외부 검토 지지·D-134 정합)
- β(채택) 이연: AuditLog 적재 경로 전역 부재(save 호출부 0·정찰 실측). 승인/거부 하나 위해 인프라 부분 도입은 소비처 1곳·정책 일관성 훼손(전역 감사 트랙 일괄이 응집도 높음). 전이 정합성은 canTransitionTo가 Domain에서 보장하므로 감사 부재가 MVP를 깨지 않음(D-134 STL-4 판단 동일).
- α(기각) 이번 도입: AuditLogAction.APPROVE/REJECT 첫 소비처가 되나 인프라 신설 얹으면 트랙 팽창.

**추가 결정**
- D-135-a 재심사 정책 = REJECTED 종료 상태: canTransitionTo에 PENDING→SALE·PENDING→REJECTED 2개만. REJECTED→PENDING 미도입 — 재신청 트리거(판매자 상품 수정 엔드포인트)가 전무하므로 열면 호출자 없는 전이(기조 4). 상품 수정/재심사 트랙에서 도입(§8).
- D-135-b 멱등 no-op: 이미 SALE에 재승인·이미 REJECTED에 재거부는 no-op 반환(200). SettlementTransitionService 동형(MCP 실측 재확인). 운영자 중복클릭 안전. 비합법 전이(SALE→REJECTED 등)는 422 유지.
- D-135-c 경로 식별키 = public_id(prd_): 상품은 public_id 노출 Aggregate(Settlement Long id와 상이). findByPublicIdForUpdate 단일 락 쿼리로 조회+락(publicId 변환 2쿼리 아님).
- D-135-d 동시성 = 비관적 락: findByPublicIdForUpdate @Lock(PESSIMISTIC_WRITE)·D-101 house pattern. Aggregate mutator→dirty checking. @SQLRestriction이 삭제 상품 자동 제외.
- D-135-e 전이 위반 예외 신설: ProductInvalidStateException(422). 타 도메인 InvalidState 재사용 시 code 오분류(D-132·D-134 §관습 정합 선례). IllegalStateException 직접 매핑 금지(500 누수).

**PRD-6 신규 불변식**: PENDING만 승인/거부 대상·SALE=카탈로그 노출·REJECTED=미노출. Enforcement=Domain(canTransitionTo)+조회 쿼리(status=SALE).

### §2 결정 라운드 재진입·수렴
- 외부 검토 회신: 결정 1·3 이견 없음. 결정 2는 β 유지하되 "사유 저장 이연" 결정의 문서화 권고 → §1-A 명문화로 수용.
- 추가축 3건 제기: (1) REJECTED 이후 재심사 정책 (2) SALE 이후 재승인 (3) 상태 전이 불변식.
- 재심사(1): 1α 확정 — REJECTED 종료·재신청 사용자 행위 자체가 전무(상품 수정 경로 없음)·열면 소비처 없는 전이. state-machine에 확장 지점만 표기.
- SALE 이후 재승인(2): 이번 범위 밖 확정 — 상품 수정 경로(전무)와 묶임. state-machine 확장 지점 표기.
- 불변식(3): PRD-6 신설로 수용(문서-상태머신 일치·구현 증가 아님).
- 멱등 no-op: 브리핑이 명시 안 해 초기 구현은 422 반환 → SettlementTransitionService MCP 실측(이미 목표 상태면 return)으로 house pattern 확인 → 멱등 no-op으로 재수렴.

### §3 배선 실측
- ProductStatus.canTransitionTo(next): switch — PENDING→(SALE|REJECTED)·default false. REJECTED 종료. SettlementStatus 패턴.
- Product.approve(): canTransitionTo(SALE) 위반 시 IllegalStateException·성공 시 status=SALE. reject(): canTransitionTo(REJECTED) 위반 시 IllegalStateException·성공 시 status=REJECTED. setter 없이 mutator 내부 전이(캡슐화).
- Product.create(): status=ProductStatus.PENDING 서버 고정(기존 SALE→변경). Javadoc 갱신.
- ProductRepository.findByPublicIdForUpdate(publicId): @Lock(PESSIMISTIC_WRITE)·public_id 기준 단일 락 쿼리. SettlementRepository.findByIdForUpdate 준용.
- ProductApprovalService.approve/reject(publicId): @Transactional 클래스. findByPublicIdForUpdate→멱등 no-op(이미 목표 상태 return)→try mutator catch IllegalStateException→ProductInvalidStateException(422). dirty checking 커밋. 소유권 대조 없음(ADMIN 전용·SecurityConfig 게이트).
- ProductInvalidStateException(422·RuntimeException): GlobalExceptionHandler CODE_PRODUCT_INVALID_STATE 422 매핑(import·상수·핸들러·SettlementInvalidStateException 선례).
- AdminProductController: POST /api/v1/admin/products/{publicId}/approve·/reject·200. @PreAuthorize 없음(SecurityConfig /admin/** 강제). ProductApprovalResponse(publicId·status).
- 회귀 수선: ProductRegistrationControllerIntegrationTest:125-126 등록 결과 status SALE→PENDING·ProductCatalogControllerIntegrationTest:28-29 stale 주석 정합.
- 문서: state-machine.md §10 Product.status 절 신설·invariants.md §2.7 PRD-6.
- 검증: 728 tests·failures 0·--rerun-tasks(캐시 아님)·Track 49(721) 대비 +7·회귀 0. 신규 통합 7(T1 승인200·T2 거부200·T3 SALE→거부 422·T4 멱등 approve·T5 멱등 reject·T6 404·T7 BUYER 403).

### §트랩 실측
- coincidental test 방지: AdminProductControllerIntegrationTest가 부모 category·seller 실완비 seed → 전이 UPDATE가 앱 커넥션(FK_CHECKS=1)에서 실 락경로(findByPublicIdForUpdate) 통과(D-134 §트랩 재발 차단).
- 멱등 no-op 초기 누락(브리핑 미명시)→SettlementTransitionService 실측으로 house pattern 확인→수선. 자가보고 신뢰 대체 금지(4지점 MCP 대조 완료: service·mutator·예외·핸들러).

### §8 carry-over (이월)
1. 판매자 상품 수정/재심사: 상품 수정 엔드포인트 도입 시 REJECTED→PENDING 전이 canTransitionTo 추가(재신청 경로). SALE 상품 수정 시 재심사(SALE→PENDING) 여부도 이때 결정.
2. rejection_reason(판매자 공개 사유): 판매자 통보/조회 요구 실재 시 컬럼+Flyway 신설. 거부 사유 저장.
3. AuditLog APPROVE/REJECT 적재: 전역 감사 인프라 트랙에서 상품 승인/거부를 소비처로 편입(D-134 STL-4와 일괄).
4. APPROVED enum 활용: 승인·판매개시 분리 요구(판매자 재량 개시) 실재 시 PENDING→APPROVED→SALE 3단 확장.

---

## D-136 [ACTIVE] 구매자 등급 자동 산정 (Track 51·MVP #17 완료)

**결정일**: 2026-07-05
**상태**: [ACTIVE]
**참조**: invariants.md §2.3(GRD-1~4)·recon-report.md(R1·R2·R3)·D-88 Q3·D-101·D-134/135

### 진입점
- Service: backend/src/main/java/com/zslab/mall/grade/service/GradeService.java (recalculate)
- Batch: grade/service/GradeRecalculationBatchService.java (recalculateAll)
- Controller: grade/controller/AdminGradeController.java (POST /api/v1/admin/grades/recalculate·POST /api/v1/admin/buyers/{publicId}/grade/recalculate)
- 예외: grade/exception/GradePolicyUnavailableException.java (500·GlobalExceptionHandler CODE_GRADE_POLICY_UNAVAILABLE)
- 시드: db/migration/V15__seed_grade_policy.sql
- 집계 쿼리: order/repository/OrderItemRepository.java (sumConfirmedTotalPriceByBuyerId)

### §1-A 옵션 검토

**축1·2 산정 트리거 + 집계 공급** — 채택 α(운영자 수동 배치·전량 재계산)
- α 운영자 수동 배치: BuyerPurchaseAggregate가 Order CONFIRMED SUM으로 재집계 가능한 Read Model(invariants §5)이라 이벤트 공급 없이 완결. 기채택.
- β 증분 이벤트(M-16 실시간): 기각 — E6 PurchaseConfirmed 미발행(BuyerOrderConfirmService Javadoc "이벤트 미발행")·배선 부담. Read Model vs M-16은 충돌 아님(현재=배치·향후 이벤트 생기면 공급원 교체).

**축3·R1-a 교환 처리** — 채택 α(EXCHANGED 0 기여)
- lifetime = Σ CONFIRMED total_price. EXCHANGED/CANCELLED/RETURNED는 종결 상태로 CONFIRMED와 disjoint(OrderItemStatus·D-88 Q3 확정 후 클레임 차단). 환불 차감 불요(필터가 원천 배제).
- ⚠️ 정산 net=gross−refund 모델 복제 금지: seller 현금흐름 회계라 gross/refund가 서로 다른 품목 집계 → 구매자 누적액 적용 시 이중 차감.

**축4 정책 선택** — 채택: Repository 활성 조회·Service 구간 선택
- GradePolicyRepository.findActivePolicies(now)=활성 목록만 반환. GradeService가 반개구간 매칭·version DESC findFirst(GRD-1). Repository 단일 등급 반환 금지.

**R2-a 구간 겹침 방지** — 채택: 시드 책임(GradePolicySeedOverlapTest 안전망)·구현 가드/DB 제약 미도입
- version DESC LIMIT 1은 결정성만 보장·정책 정합성 미보장. Service 검증·DB 제약 신설은 과잉(기조4). 시드 겹침 테스트 + invariants GRD-4 명시로 대체.

**R2-b 경계 구간** — 채택 α(반개구간 [min, max))
- 경계값 이중매칭 제거. PLATINUM max=BIGINT MAX(9223372036854775807) 센티넬.

**R3 lock/source 규칙** — 채택 α(lock=제어자·source=출처 메타)
- grade_locked_until > now 이면 AUTO skip(고정). lock 없으면 source 무관 재산정 후 source=AUTO. MANUAL/EVENT 보호는 운영자가 lock_until 동반 부여해야 성립.
- 근거: DDL COMMENT 정합·decisions.md:2720 "gradeLockedUntil·gradeUpdatedAt 정책 로직 Track 8+" 이연분 확정. β(하락만 억제)는 직관 위배·복잡도 증가로 기각.

**정책 미매칭 예외** — 채택: 500(GRADE_POLICY_UNAVAILABLE)
- 시드가 [0, MAX] 전구간 커버 → 미매칭=정책 비활성/손상=서버 설정 오류. 클라 교정 불가라 422 아님. 전용 code로 무분류 fallback 차단.

**배치 TX 경계·R1-b** — 채택 α(buyer별 개별 TX·부분 성공)
- 배치는 @Transactional 미부여·별도 빈 GradeService.recalculate(클래스 @Transactional) 호출마다 REQUIRED 독립 TX(자기호출 프록시 함정 회피). RuntimeException 흡수·failure 집계·log.warn 후 진행. 1건 실패가 전체 미차단(SettlementCreationService skip 관례 정합).

**R1-c last_ordered_at** — 채택 α(미갱신)
- 산정 미사용·소비처 없음(기조4). 향후 소비처 생기면 MAX(confirmed_at) 도입.

**시드값(R2-c)**: SILVER [0,300000)·GOLD [300000,1000000)·PLATINUM [1000000,MAX)·discount 0/3/5%·point 1/2/3%. effective_from='2026-01-01'·effective_to='9999-12-31 23:59:59.999999'(NOT NULL 대응 센티넬).

### §2 결정 라운드 재진입
- 외부 검토 1회(A급). R2-a만 부분 수용(운영 규칙만→시드 테스트+문서 명시로 강화). 나머지 α 채택 내재.
- effective_to NOT NULL blocker(Phase 1 실측): 시드 NULL 전제 오류 → far-future 센티넬(옵션 A) 채택. 스키마·엔티티 무변경(옵션 B ALTER 기각·V1 계약 변경 회피).

### §3 배선 실측
- lifetime SUM: OrderItemRepository.sumConfirmedTotalPriceByBuyerId (oi.order.buyerId 조인·itemStatus=CONFIRMED·COALESCE 0)
- 등급 반영: BuyerProfile.applyGrade(gradeId, source, updatedAt) — grade_locked_until 미변경(책임 분리)·@ManyToOne 금지 유지(Long 필드·D-01)
- 권한: SecurityConfig /api/v1/admin/**→hasRole("ADMIN") 재사용
- 검증: 737 tests PASS(--rerun-tasks·Phase 2 733→Phase 3 737)

### §8 carry-over
- E6 PurchaseConfirmed 이벤트 배선(M-16 실시간 공급원 교체) → Settlement 후속 트랙과 공유
- Grade 산정 이력 AuditLog 적재 → 전역 감사 인프라 트랙(소비처 부재로 이연)
- last_ordered_at 갱신 → 소비처 발생 시

---

## D-137 [ACTIVE] 전역 감사 적재 인프라·3소비처 배선 (Track 52·이연 AuditLog 적재 완결)

**결정일**: 2026-07-05
**상태**: [ACTIVE]
**참조**: invariants.md §2.16(AUD-1~4)·audit-policy.md:26,39,44·recon-report.md(§1~§9)·D-134/135 §8(감사 이연)·D-136 §8·LT-04

### 진입점
- 공통 인프라: audit/service/AuditRecorder.java(record)·DiffBuilder.java(diff·toJson)·Masker.java(mask)·AuditContext.java(값객체)
- Actor role: common/auth/ActorRoleResolver.java(requireCoarseRole)
- 소비처: settlement/service/SettlementTransitionService.java(confirm·pay)·product/service/ProductApprovalService.java(approve·reject)·grade/service/GradeService.java(recalculate 오버로드)
- 컨트롤러: AdminSettlementController·AdminProductController·AdminGradeController(actor 해석·AuditContext 조립)
- entity/DDL(기존): audit/entity/AuditLog.java(create)·db/migration/V1__init.sql:736(audit_log)
- DDL 신규: 없음(기존 스키마 커버·V16 미사용)

### §1-A 옵션 검토

**결정1 적재 방식** — 채택 α(변경 지점 동기 적재)
- α 동기: mutator 직후 같은 TX에서 record()·감사 실패 시 호출자 TX 롤백(무결성 우선). 근거 = producer 3건 수준에서 이벤트 계층(이벤트 클래스·payload·AFTER_COMMIT publish 설계) 추가 비용이 이득 초과(외부 검토 보강). LT-04는 AFTER_COMMIT DB 재조회 before 복원만 반증하나, 소비처 3건 복잡도 최소화가 본질 근거.
- β 이벤트+AFTER_COMMIT 기각: producer 3건에 이벤트 계층 과설계. before 캡처도 동기 지점이 단순.

**결정2 적재 범위** — 채택 α(핵심 3건·회수 이연)
- α 정산·상품승인·등급만: audit-policy.md:39,44가 Settlement 상태·금액·grade 변경을 감사 필수로 명시(policy-mandated). 축소 시 정책 미이행.
- β 권한 회수(AUTH-4) 포함 기각: 회수(HARD delete) 기능 자체가 코드에 부재(정찰 실측). 감사 위해 기능 신설은 별 트랙·기조4 범위 오염. 회수 기능 신설 → 그 시점 동기 감사 배선 순서(§8).

**결정3 유틸 구조** — 채택 β(협력 객체 분리·외부 검토 수용)
- β AuditRecorder(orchestration)+DiffBuilder+Masker: diff 규칙·마스킹 정책·저장 방식이 서로 독립 변경 가능 → 책임 분리. 소비처 3건 공통 재사용이라 과잉추상화 아님.
- α 단일 AuditRecorder 집중 기각: diff·마스킹·저장 전부 떠안으면 책임 경계 흐려짐(God object 우려).

**결정4 ip/UA 처리** — 채택 α(미수집·nullable·확장 여지)
- α: audit-policy 필수 아님·스키마 nullable·소비처 없음. AuditContext에 ip/ua 필드는 두되 2-인자 팩토리 표준(후행 수집 seam 추가 용이). 수집 코드 미신설.
- β 컨트롤러 HttpServletRequest 수집 기각: 가치 대비 복잡도(시그니처 증가·비웹 배치 처리) 초과. RequestContextHolder seam도 전무(신설 부담).

**결정5 actor_role** — 채택 α(coarse ADMIN/SELLER/BUYER)
- α coarse: 감사는 "행위 기록"이지 권한 관리 시스템 아님. actor_user_id로 당시 권한 사후 추적 가능. 세분 RoleCode DB 조회 비용 회피. SecurityContext authority에서 ROLE_ 제거.
- β 세분 RoleCode DB 조회 기각: "어떤 관리자 권한으로 승인했는가"가 감사 대상이 될 때 재검토(§8).

**결정6 등급 target_type** — 채택 α(USER·targetId=buyerId)
- α USER: 감사 대상 = 변경된 엔티티 = buyer(BuyerProfile.grade). audit-policy.md:44 정합.
- β BUYER_GRADE 기각: 등급 정의(카탈로그) 엔티티라 "누가 변경됐는가" 희미·targetId 모호.

### §2 결정 라운드 재진입·수렴
- 외부 검토 1회(A급). 결정1·2 채택 내재. 결정3 부분 수용(단일→협력 객체 분리). Blocker 1건 = actor 컨텍스트 소스 → 구현 전 보충 정찰로 해소.
- 보충 정찰 수렴: actorId=AdminActorResolver/AuthenticatedUserResolver(Long)·coarse role=기존 유틸 부재로 ActorRoleResolver 신설·ip/UA seam 전무(미수집 확정)·PolymorphicTargetType SETTLEMENT·PRODUCT·USER 기존 보유(enum 추가 불요).
- Grade 오버로드(구현 발생·결정 외): recalculate(Long)=무-actor 배치(무변경 강제·GradeRecalculationBatchService caller)·recalculate(Long,AuditContext)=감사. null=배치 경로. 배치 파일 무변경 달성.

### §3 배선 실측
- AuditRecorder.record(): 무 @Transactional(호출자 TX 참여)·diff→mask→toJson→AuditLog.create→save·마스킹 후 빈 diff면 skip(멱등 무변경). 이벤트·AFTER_COMMIT 미사용.
- DiffBuilder: before≠after key만 {field:{before,after}}(AUD-3)·Objects.equals 비교·기존 ObjectMapper 주입·직렬화 실패 IllegalStateException(묵살 금지). 빈 diff="{}"
- Masker: 민감필드 화이트리스트 8종·원본 불변 새 맵·현 3소비처 no-op(AUD-2 seam 확정·과잉규칙 회피).
- ActorRoleResolver.requireCoarseRole(): authority ROLE_ 제거→coarse·인증 부재 UnauthenticatedException(결정5·DB 조회 없음).
- Settlement confirm/pay: findByIdForUpdate→멱등 no-op(record 미호출)→before status 캡처→mutator→record(UPDATE·SETTLEMENT·settlement.id).
- Product approve/reject: findByPublicIdForUpdate→멱등 no-op→before status→mutator→record(APPROVE·REJECT·PRODUCT·product.id).
- Grade recalculate(Long,AuditContext): before gradeId→applyGrade→auditContext!=null이면 record(UPDATE·USER·buyerId). 동일 등급 재산정=diff 빈 맵→skip(멱등).
- 검증: 756 tests PASS(--rerun-tasks·캐시 무효). Phase 1 신규 15(752)·Phase 2 통합 4(756)·회귀 0. 4지점 MCP 대조(AuditRecorder·DiffBuilder·Masker·ActorRoleResolver + 3소비처 서비스).

### §트랩 실측
- 멱등 no-op은 record 미호출(전이 실제 발생 시만 적재) → 재요청 감사 미적재가 AUD-3(changed_fields) 정합.
- @MockBean→@MockitoBean 정정(코드베이스 관례 12파일).
- BOM 오염 트랩: Set-Content -Encoding utf8이 커밋 제목 선두 BOM 삽입 → .NET UTF8Encoding($false) BOM-free 유지(push 전 amend 해소).

### §8 carry-over (이월)
1. 권한 회수(AUTH-4) 감사: 회수(HARD delete) 기능 신설 트랙에서 동기 감사 배선(기능 부재로 이번 제외).
2. ip/user_agent 수집: 포렌식·이상접속 감사 요구 실재 시 컨트롤러 수집 seam 추가(AuditContext 4-인자 팩토리 진입점 준비됨).
3. actor_role 세분화: "어떤 관리자 권한으로 승인했는가"가 감사 대상이 될 때 user_role DB 조회 도입.
4. diff 값 타입 직렬화: BigDecimal scale·Enum 등 소비처 값 타입 확대 시 직렬화 형태 통합 테스트 보강(현 3소비처는 status·gradeId만).
5. E6 PurchaseConfirmed 이벤트 배선 시 감사 이벤트 인접 검토(Settlement 후속·D-136 §8 공유).

---

## D-138 [ACTIVE] 권한 회수(AUTH-4) 신설·동기 감사 배선 (Track 53·Track 52 감사 인프라 소비 완결)

**결정일**: 2026-07-06
**상태**: [ACTIVE]
**참조**: invariants.md §2.2(AUTH-1~6·AUTH-5/6 신규)·D-137(감사 인프라·§8-1 회수 이연)·recon-report.md(track-53)·외부 검토 2회

### 진입점
- Service: auth/service/RoleRevocationService.java (revoke)
- Controller: auth/controller/AdminUserRoleController.java (DELETE /api/v1/admin/users/{userPublicId}/roles/{roleCode}·204)
- Repository: auth/repository/UserRoleRepository.java (deleteByUserIdAndRoleCode·countByRole_Code)·RoleRepository.java (findByCodeForUpdate)
- 예외: auth/exception/{SelfRoleRevocationException(403)·LastSuperAdminRevocationException(409)·RoleAssignmentNotFoundException(404)}·GlobalExceptionHandler 매핑
- 감사 소비: audit/service/AuditRecorder.java(record·DELETE·USER) 재사용(D-137 인프라)
- DDL 신규: 없음(HARD delete·inbound FK 부재·V16 미사용)

### §1-A 옵션 검토

**축1 삭제 방식** — 채택 HARD(결정 불요·AUTH-4 명시)
- user_role inbound FK 부재(실측 V1:210-219)로 DELETE 제약 위반 없음. 이력은 감사로 보존. soft delete 미도입(스키마 무변경).

**축2 회수 대상 식별** — 채택 α(userId+roleCode·public_id 경로 노출)
- UserRole public_id 부재(실측)·부여가 userId 기반이라 대칭. 외부 경로는 public_id(usr_) 관습(전 컨트롤러 정합·raw BIGINT path 선례 전무) → path=public_id, 서비스 내부 userId 해소. β(UserRole.id 노출) 기각: 외부 식별자 신설 과잉.

**축3 감사 형태** — 채택 targetType=USER·targetId=userId·before={role}·after={}
- USER enum 기존 보유. 회수=특정 사용자의 권한 상태 변경이라 대상=User. UserRole.id를 targetId로 쓰면 삭제 후 소실되어 부적합. DiffBuilder가 role 키 삭제를 {before:X,after:null}로 잡아 비지 않음(skip 회피 실측).

**축4 미보유 응답** — 채택 404(외부 검토 근거 교체 수용)
- 200/204도 REST상 타당하나, 운영자 콘솔 API라 "명령 실패를 명확히 알려 즉시 조치"가 유리. 근거를 "부여와 대칭"에서 "운영자 명령 실패 명시"로 교체(외부 검토 지적). User 미존재·역할 미보유·경합 선삭제를 404로 통합 은닉(정보 비노출).

**축5 self-revoke 방어(AUTH-5)** — 채택 안 B(SUPER_ADMIN 대상 한정)
- caller는 게이트로 항상 SUPER_ADMIN. 위험은 자기 SUPER_ADMIN 강등(락아웃·불가역)뿐. 자기 ADMIN_OPERATOR 회수는 SUPER_ADMIN 자격 유지로 무해. 안 A(전 roleCode 자기회수 금지)는 무해 케이스까지 막는 과잉방어(기조4). self 검사를 roleCode==SUPER_ADMIN 블록·last 판정 앞 배치.

**축6 last-SUPER_ADMIN 방어(AUTH-6)** — 채택 Role 행 비관적 락 직렬화(외부 검토 보완 수용)
- 단순 count if 폐기: 동시 회수 2건이 각자 count>1 통과→0 도달 race. SUPER_ADMIN Role 행 FOR UPDATE(uk_role_code 단일 행 실측)로 동시 회수를 동일 행에서 직렬화 후 countByRole_Code≤1 차단. DB 트리거 기각(현 프로젝트 과함·외부 검토 정합).

**축7 TOCTOU 흡수** — 채택 @Modifying 단일 delete(외부 검토 신규 지적 수용)
- find→delete 사이 타 TX 선삭제 시 0 row 문제 → deleteByUserIdAndRoleCode 단일 쿼리로 조회 없이 삭제·affected 반환. 0=404·1=감사. before diff는 요청 roleCode로 구성(엔티티 조회 불요). ⚠️ 근거 한정: "요청 재구성 가능"이 아니라 "현 UserRole 모델이 roleCode 외 감사 대상 상태를 갖지 않음"에서만 성립. 부여 메타(assignedBy 등) 추가 시 재검토(§8).

**축8 권한 게이트** — 채택 SUPER_ADMIN 전용·existsByUserIdAndRole_Code 재사용
- 부여 게이트 선례 동일. 게이트를 public_id 해소보다 먼저(비-SUPER_ADMIN에 대상 존재 비노출·AdminOperatorProvisioningService 정보은닉 정합).

### §2 결정 라운드 재진입·수렴
- 외부 검토 2회(A급·외부 검토 생략 권장했으나 진행). 1차: D3·D4 강한 동의·D5 근거 재검토·D6 방식 보완. 2차: D5 404 유지·근거 교체 확정·D6 Role 행 락 직렬화 전제 실측 조건부·D8 근거 "현 모델 한정" 명시.
- D5 근거 교체: "부여와 대칭"(약함)→"운영자 명령 실패 명시"(API 성격 정합) 수용.
- D6 전제 실측(구현 전): SUPER_ADMIN Role V11 단일 시드·uk_role_code UNIQUE로 모든 회수가 동일 행 잠금 확인 → 직렬화 성립.
- self 범위 A/B: 구현 시 B 확정(락아웃 대상=SUPER_ADMIN 한정·과잉방어 회피).

### §3 배선 실측
- 흐름: SUPER_ADMIN 게이트(403)→findByPublicId 해소(404 통합 은닉)→roleCode==SUPER_ADMIN이면{self 차단 403→findByCodeForUpdate 락→countByRole_Code≤1 차단 409}→deleteByUserIdAndRoleCode→0 row 404→record(DELETE·USER·targetUserId·{role},{}).
- RoleRepository.findByCodeForUpdate: @Lock(PESSIMISTIC_WRITE)+@Query("SELECT r FROM Role r WHERE r.code=:code"). @Query 필수(파생 파싱 억제·§트랩).
- UserRoleRepository.deleteByUserIdAndRoleCode: @Modifying @Query JPQL·int 반환. countByRole_Code: long(exists는 마지막 1명 식별 불가라 count 신설).
- 예외 3종 GlobalExceptionHandler: SelfRoleRevocation→403 FORBIDDEN(SuperAdminRequiredException 선례 동일 code)·LastSuperAdminRevocation→409 LAST_SUPER_ADMIN·RoleAssignmentNotFound→404 ROLE_ASSIGNMENT_NOT_FOUND.
- Controller: DELETE·public_id 2 path variable·roleCode enum 바인딩(오값 400)·AuditContext.of(callerUserId, requireCoarseRole())·204. AdminOperatorController와 분리(단일 책임).
- 검증: 765 tests PASS(--rerun-tasks·캐시 무효). Track 52(756) 대비 +9(회수 통합 9케이스)·회귀 0. FOR UPDATE 절 실 SQL 4회 출현 실증.

### §트랩 실측
- 파생 쿼리 파싱 트랩(신규·LT-06 후보): findByCodeForUpdate @Lock 단독(@Query 부재)은 Spring Data가 Code+ForUpdate 파싱→code.forUpdate 프로퍼티 탐색 실패→전 @SpringBootTest context 로드 실패·앱 기동 불가. compileJava는 통과(파생 검증=런타임). Phase 1·2가 compileJava만 돌려 잠복→Phase 3 --rerun-tasks에서 표면화. 교훈: 신규 파생 쿼리는 @Query 명시 또는 context 기동 검증 필수. @Modifying delete는 @Query 보유라 무사.
- 자가보고 신뢰 대체 금지: 4지점 MCP 대조(Service 흐름·repo 2종·예외 핸들러·테스트 단언 정정 프로덕션 무변경 확인).
- audit diff after=Map.of() skip 우려: DiffBuilder 키 합집합으로 role before-only 엔트리 잔존→비지 않음→적재. AUTH-4 감사 누락 없음 실측.

### §8 carry-over (이월)
1. 부여 메타 컬럼(assignedBy·assignedAt): 요구 실재 시 UserRole 컬럼+Flyway 신설. 도입 시 회수 before diff를 요청 재구성이 아닌 삭제 전 조회로 전환(§1-A 축7 근거 재검토).
2. ip/user_agent 수집: D-137 §8-2 공유(포렌식 요구 시).
3. 부여(provision) 감사 배선: AdminOperatorProvisioningService는 현재 AuditRecorder 미주입. 부여 이력 감사 요구 실재 시 회수와 대칭 배선.
4. E6 PurchaseConfirmed 이벤트 배선: Settlement 후속·D-136/137 §8 공유.

---

## D-139 [ACTIVE] 부여 감사 커버리지 완결 (Track 55·감사 배선 비대칭 해소)

**결정일**: 2026-07-06
**상태**: [ACTIVE]
**참조**: invariants.md §2.16(AUD-1~4)·audit-policy.md·D-137(감사 인프라)·D-138(회수 감사·§8-3 부여 배선 이월)·recon-report.md(track-55)

### 진입점
- 소비처: auth/service/AdminOperatorProvisioningService.java(provision)·seller/service/SellerProvisioningService.java(provision)
- 컨트롤러: auth/controller/AdminOperatorController.java·seller/controller/AdminSellerController.java(actor 해석·AuditContext 조립)
- 감사 인프라(재사용): audit/service/AuditRecorder.java(record)·common/auth/ActorRoleResolver·AdminActorResolver(D-137)
- action/targetType(기존): AuditLogAction.CREATE·PolymorphicTargetType.USER·SELLER
- DDL 신규: 없음

### §1-A 옵션 검토

**축1 배선 범위** — 채택 α(운영자 조작 부여만)
- 운영 관리자 부여 + 판매자 입점. 회원가입(register)·정산 배치 생성 제외. 감사는 권한자 행위 추적이지 셀프서비스·자동 배치가 아님. 정산 배치는 STL-4가 이미 상태·금액 변경 감사 커버(Track 52). β(배치 포함)·γ(회원가입 포함) 기각: 소비처 없는 과잉 적재(기조4).
- assign*류는 전부 엔티티 세터(서비스 아님)라 α 미배선 write path 추가 없음(grep 실측).

**축2 운영 관리자 부여 감사 형태** — 채택 USER·role(회수 대칭)
- targetType=USER·targetId=대상 userId·action=CREATE·before={}·after={role:ADMIN_OPERATOR}. 순수 role 부여라 회수(DELETE·before={role}·after={})의 정확한 대칭. DiffBuilder 키-추가 방향 diff 포착 실측.

**축3 판매자 입점 감사 형태** — 채택 해석 A(SELLER 생성 관점)
- targetType=SELLER·targetId=seller.id·action=CREATE·before={}·after={sellerPublicId·status·ownerUserId 최소셋}.
- 근거: ② provision의 write 본체는 seller 입점 생성이고 seller_user(role=SELLER_OWNER)는 부속. 감사 대상="무엇이 생성됐는가"=seller. 해석 B(USER·role:SELLER_OWNER·회수 대칭) 기각: seller 입점이라는 도메인 사건이 role 부여로 축소돼 감사 의미 희미. ①과 달리 ②는 복합 생성이라 사건 성격이 다름. companyName·businessNo는 최소셋·AUD-2 민감정보 회피로 제외.

**축4 AuditContext 조립 위치** — 채택 Controller 조립·Service 전달
- AuditContext.of(adminActorResolver.resolve(request), actorRoleResolver.requireCoarseRole()) Controller 조립 후 Service 인자 전달. 회수·상품승인·정산·등급 일관 패턴 재사용.

### §2 결정 라운드 재진입·수렴
- 외부 검토 생략(A급·선례 전량 재사용·규모 소·신규 파일 0). ② targetType 분기만 채팅 결정 → 해석 A 확정(seller 생성 본체 관점).
- 컨트롤러 주입 비대칭 실측: ①은 AdminActorResolver 기존 주입·ActorRoleResolver만 +1. ②는 AdminSellerController가 resolver 둘 다 미주입이라 +2·"actor 미소비" 전제 주석 폐기 동반.
- ② record 배치: 지시는 "seller_user saveAndFlush try 성공 직후"였으나 catch 밖 성공 경로로 조정. try 내부 배치 시 record의 auditLogRepository.save가 던질 수 있는 DataIntegrityViolationException이 seller_user 중복 409 catch에 오분류되는 트랩. 성공 경로 실행 위치는 동일(그 사이 코드 없음)·의미 일치.

### §3 배선 실측
- AdminOperatorProvisioningService: AuditRecorder 주입·provision(callerUserId, request, AuditContext) 확장·saveAndFlush try/catch 밖 성공 경로 record(CREATE·USER·target.getId()·Map.of()·Map.of("role","ADMIN_OPERATOR")) — :78-90.
- AdminOperatorController: ActorRoleResolver +1 주입·AuditContext.of(callerUserId, requireCoarseRole()) 조립·provision 전달 — :48-54.
- SellerProvisioningService: AuditRecorder 주입·provision(request, AuditContext) 확장·seller_user saveAndFlush catch 밖 성공 경로 record(CREATE·SELLER·seller.getId()·Map.of()·{sellerPublicId·status·ownerUserId}) — :64-97.
- AdminSellerController: AdminActorResolver+ActorRoleResolver +2 주입·조립·전달·주석 갱신 — :47-58.
- provision 시그니처 확장 호출부 = 두 컨트롤러뿐(grep 실측·타 호출자 없음).
- 검증: 765 tests PASS(--rerun-tasks·캐시 무효)·증분 0(기존 성공 케이스에 감사 단언 추가·신규 메서드 아님)·회귀 0.

### §트랩 실측
- record 배치 트랩: try 내부 배치 시 record의 save가 던지는 DataIntegrityViolationException을 provision 중복 409 catch가 오분류. catch 밖 성공 경로 전용 배치로 회피(same-transaction 오분류 계열). 롤백 경로(409) record 미도달을 통합테스트로 실증(409 케이스 감사 0건·성공 케이스만 1건).
- 기존 부여 통합테스트 audit_log 미정리 → 감사 단언 추가 시 cleanup(actor_user_id 기준 DELETE) 동반(테스트 오염 차단·AdminOperatorControllerIntegrationTest·AdminSellerControllerIntegrationTest).
- DiffBuilder 키-추가 방향(before={}·after={role|입점셋}) diff 포착 = 회수 키-삭제 대칭 실측.

### §8 carry-over (이월)
1. ip/user_agent 수집: D-137 §8-2 공유(포렌식 요구 시).
2. 부여 메타 컬럼: D-138 §8-1 공유(UserRole assignedBy 등 추가 시).

---

## D-140 [ACTIVE] 가입 시 BuyerProfile 미생성(BL-8) 수정·기존 buyer 백필 (Track 57)

**결정일**: 2026-07-06
**상태**: [ACTIVE]
**참조**: backend-audit-v1.md(BL-8·Track 56 전수검사 특정 유일 치명 실결함)·recon-report.md(track-57)·invariants.md(GRD·"모든 buyer 가입 시 프로필 보유" 불변식)·외부 검토 1회

### 진입점
- Service: user/service/UserService.java (register·BuyerProfile 배선 :84-86·메서드 :69-90)
- Repository 신규: grade/repository/BuyerGradeRepository.java (findByCode :10)
- Repository(기존): user/repository/BuyerProfileRepository.java (save)
- Factory/Entity(기존): user/entity/BuyerProfile.java (create·프로덕션 호출처 0→1)·grade/entity/BuyerGrade.java (code)
- 마이그레이션 신규: db/migration/V16__backfill_buyer_profile.sql (기존 buyer 소급 백필)
- 테스트: user/service/UserServiceTest.java·user/integration/SignupIntegrationTest.java
- DDL 신규: 없음(기존 buyer_profile 스키마 V1:174 재사용·V16=데이터 백필 전용)

### §1-A 옵션 검토

**축1 가입 초기 grade_source 값** — 채택 A(AUTO)
- AUTO: 신규값 추가 없이 배정. recalculate가 첫 배치 산정 시 grade_source를 무조건 AUTO로 덮음(GradeService.java:88 실측) → INITIAL 신규값의 수명이 "첫 재산정 전"뿐이고 소비처 0. C(INITIAL 신규·DB ENUM+Java enum+DTO 다층 잠금) 기각: 수명 짧고 활용처 부재 = 과잉개발(기조4). B(MANUAL) 기각: 운영자 수동 부여와 의미 혼동.

**축2 수정 방향** — 채택 α+백필(β 미채택)
- α 가입 배선 + V16 백필: register에 BuyerProfile.create 배선(근본 해결) + 기존 buyer 소급. 완료 시 전 buyer가 프로필 보유 → 불변식 복원·강제. β(GradeService.recalculate orElseThrow→log.warn+return) 기각: 근본수정 후 orElseThrow는 불변식 위반 즉시 노출하는 fail-fast 트립와이어로 유지해야 함. skip으로 격하 시 재발 은폐·배치 조용한 누락 지속(외부 검토 정합·β 단독=결함 은폐). γ(α+β) 기각: β가 방어 아닌 은폐.

**축3 백필 실행 범위** — 채택 단일 실행
- Testcontainers가 매번 전체 마이그레이션 실행으로 V16 커버·솔로 MVP 기존 buyer 소수. 분할 실행 불요(운영 대량 시 재론).

**축4 탈퇴/soft-delete user 백필 범위** — 채택 포함
- buyer_profile은 deleted_at 컬럼 부재·User root 경유 SOFT 상속(V1:174 실측). post-fix에도 가입 후 탈퇴한 user는 profile 물리 row 유지 → pre-fix 탈퇴자 백필이 향후 동작과 동일 종단 상태. 불변식("모든 BUYER-role user는 profile 보유")을 예외 없이 복원하며, 제외 시 pre-fix 탈퇴자만 유일 특수 케이스로 잔존. buyer_profile은 PII 부재(grade_id·source·timestamp)로 탈퇴 개인정보 라이프사이클 무관. 배치의 탈퇴자 재산정 문제는 기존 속성·BL-5 소관으로 BL-8과 직교(§8).

### §2 결정 라운드 재진입·수렴
- 외부 검토 1회(A급). D1(AUTO)·D2(α+백필)·D3(단일) 전량 동의·반박 0. 추가 4건은 설계 변경 아닌 구현 확인사항으로 내재: (1) register 트랜잭션 원자성(클래스 @Transactional 기존 보장) (2) SILVER 조회 실패 fail-fast(IllegalStateException·BUYER Role seed 선례 동일) (3) SignupIntegrationTest 보강(회귀 가드) (4) V16 멱등(NOT EXISTS).
- 교차검증 발굴(결정 하중): recalculate가 첫 배치에서 grade_source를 AUTO로 덮음(GradeService:88) → 축1 AUTO 확정 강화(INITIAL 무의미 실증).
- D5 탈퇴자 백필: 구현 단계 표면화(user.withdrawn_at·deleted_at 존재·물리 row 잔존) → 포함 확정(불변식 완전 복원·post-fix 일관).

### §3 배선 실측
- UserService.register: userRoleRepository.save 직후 buyerGradeRepository.findByCode(SILVER).orElseThrow(IllegalStateException·V15 seed 누락 500)→buyerProfileRepository.save(BuyerProfile.create(saved, silver.getId(), GradeSource.AUTO)). User→UserRole→BuyerProfile 동일 TX(클래스 @Transactional·별도 미추가). DI +2(BuyerProfileRepository·BuyerGradeRepository·수동 생성자 필드/파라미터/대입 각 +2). Javadoc 2곳 사실 정합 갱신(클래스 가입흐름 서술·메서드 @throws SILVER seed 500).
- BuyerGradeRepository.findByCode(BuyerGradeCode): Optional<BuyerGrade> 반환·grade_id 하드코딩 회피(AUTO_INCREMENT seed).
- V16__backfill_buyer_profile.sql: BUYER role 보유·profile 부재 user 전원 INSERT...SELECT...WHERE NOT EXISTS·grade_id=(SELECT id FROM buyer_grade WHERE code='SILVER') subselect·grade_source='AUTO'·멱등. 보상 마이그레이션 주석 포함(grade_updated_at IS NULL=백필 직후 식별·MANUAL/EVENT/재산정 결과 보존).
- 검증: 766 tests PASS(--rerun-tasks·캐시 무효). 기준선 765 대비 +1(register_missingSilverGradeSeed_throws 신규)·회귀 0. 기존 GradeServiceTest 4케이스 유지(seedBuyerProfile native INSERT 방식 무변경). 4지점 MCP 대조(register 배선·V16 SQL·user DDL·BuyerGradeRepository).

### §트랩 실측
- findByCode 파생 쿼리: 단순 프로퍼티(code) 파싱이라 안전(D-138 LT-06 findByCodeForUpdate 파생 트랩과 달리 @Query 불요). context 기동 검증은 SignupIntegrationTest가 커버.
- SignupIntegrationTest 회귀 가드: validSignup에 buyer_profile COUNT=1·grade=SILVER·grade_source=AUTO 검증 추가 = BL-8이 통과해온 근본 원인(user·user_role만 COUNT 검증) 봉쇄. 이 한 줄이 재발 영구 차단. buyer_profile cleanup 동반(테스트 오염 차단·감사 계열 선례 동일).
- BL-8 통과 원인 실측: GradeServiceTest는 seedBuyerProfile() native INSERT(FK_CHECKS=0)로 프로필 직접 주입·가입 경로 무관. UserServiceTest는 Mockito 단위로 BuyerProfileRepository mock 부재. 양쪽 모두 "가입이 프로필을 만들어야 한다"는 불변식 미검증.

### §8 carry-over (이월)
1. 가입 초기 전용 grade_source(INITIAL 등): GradeHistory 이력 Aggregate 도입·"최초 자동배정" 통계/CS 추적/BI 요구 실재 시 source 체계 재설계(현 소비처 0·첫 배치 AUTO 소멸로 현시점 무의미).
2. 탈퇴/휴면 user grade 배치 처리: 탈퇴자 등급 재산정 제외 요구 실재 시 GradeRecalculationBatchService 필터 또는 recalculate 진입 가드 도입(BL-5 탈퇴/휴면 라이프사이클 트랙 소관·BL-8과 직교).

---

## D-141 [ACTIVE] 회원 프로필 조회/수정·주소록 CRUD (Track 58·BL-3+BL-4)

**결정일**: 2026-07-06
**상태**: [ACTIVE]
**참조**: backend-audit-v1.md(BL-3·4·"엔티티/스키마만·실행 미구현"·User.java Track 8+ 이연)·recon-report.md(track-58)·deletion-policy.md(SOFT 8·A5)·CartItemNotFoundException(소유권 404 은닉 선례)·외부 검토 생략(B급)

### 진입점
- Service: user/service/UserService.java(getMyProfile·updateMyProfile 확장)·user/service/UserAddressService.java(신설·list·create·update·delete·setDefault)
- Controller: user/controller/UserController.java(GET·PATCH /me)·user/controller/UserAddressController.java(신설·/me/addresses 5 엔드포인트)
- Repository: user/repository/UserAddressRepository.java(findByUserId·findByIdAndUserId·findByUserIdAndIsDefaultTrue·countByUserId 추가)
- Entity: user/entity/User.java(updateProfile mutator)·user/entity/UserAddress.java(create 9-arg 확장·updateDetails·markDefault·unmarkDefault)·common/entity/AbstractSoftDeletableEntity.java(markDeleted·코드베이스 최초 soft-delete 구현)
- 예외: user/exception/AddressNotFoundException.java(404)·GlobalExceptionHandler(ADDRESS_NOT_FOUND 매핑)
- DTO: request/UpdateProfileRequest·CreateAddressRequest·UpdateAddressRequest·response/ProfileResponse·AddressResponse
- DDL 신규: 없음(user·user_address 스키마 V1 완비·V16 최신 유지)

### §1-A 옵션 검토

**축1 프로필 수정 필드 범위** — 채택 name·phone만
- email 제외: email은 로그인 자격증명(AuthService)·uk_user_email UK라 변경 시 인증 파급·중복검증·재확인 플로우 발생. MVP 요구 부재 → 제외(요구 실재 시 별도). updateProfile은 name·phone만 교체·email 세터 미추가.

**축2 주소 API 식별자** — 채택 내부 id + 소유권 스코프
- UserAddress는 public_id 부재(V1 설계·public_id는 12테이블 한정). 컨트롤러가 requireUserId() 후 findByIdAndUserId(id, userId)로 소유권 스코프 → 타 user 접근 404 은닉(BOLA 차단). public_id 추가(컬럼·마이그레이션·ULID 배선) 기각: 소유권 스코프로 IDOR 이미 차단·과잉(기조1·4).

**축3 is_default 단일성** — 채택 앱 로직 demote-then-set
- MariaDB는 "user당 is_default=true 1건" 부분 UK 미지원((user_id, is_default) UK는 is_default=false 다건을 막아 불가). 앱 로직으로 보장: 기본 지정 시 기존 기본 강등(unmarkDefault)→대상 승격(markDefault)·동일 TX. 첫 주소는 countByUserId==0으로 기본 강제(요청값 무관). setDefault는 대상이 이미 기본이면 강등·승격 상쇄로 멱등.

**축4 Flyway 신규** — 채택 불요
- 축2에서 내부 id 채택 → 스키마 변경 없음. user·user_address V1 완비. V16 최신 유지.

**축5 구조** — 채택 프로필=UserService 확장·주소록=UserAddressService 분리
- 프로필 수정은 User 대상이라 가입·비번을 가진 UserService 확장이 응집. 주소록 CRUD 5종은 별 리소스·별 Repository → UserAddressService 분리가 단일 책임. 회귀 0.

**축6 주소록 CRUD 범위** — 채택 목록·생성·수정·삭제(soft)·기본설정 5종
- 단건 조회 제외(목록으로 커버·YAGNI·프론트 요구 실재 시 저비용 추가). 삭제는 soft(markDeleted·@SQLRestriction 자동 숨김). 기본 주소 삭제 시 다른 주소 자동 승격 없음(재설정은 사용자 몫·YAGNI).

### §2 결정 라운드 재진입·수렴
- B급·단일 정찰·외부 검토 생략. D1~D6 채팅 결정 전량 권장 채택.
- 정찰 부수 확인: UserAddress.create 팩토리가 필수 6필드만 세팅하고 옵션 3필드(addressLabel·addressJibun·addressDetail) 미배선 → 생성 시 옵션값 손실 결함. 팩토리 9-arg로 확장해 옵션 필드 배선.
- markDeleted 최초 구현: deletion-policy.md가 soft-delete를 설계로 명시(@SQLRestriction·A5 @SQLDelete 미사용)했으나 실제 마킹 메서드가 코드베이스에 부재했음. BL-4 삭제가 첫 소비처 → AbstractSoftDeletableEntity에 markDeleted 신설(순수 추가·기존 동작 무변경).

### §3 배선 실측
- UserService: getMyProfile(userId)→ProfileResponse(publicId·email·name·phone)·없으면 IllegalStateException(changePassword 선례 동일)·updateMyProfile→updateProfile(name,phone)→save.
- User.updateProfile(name, phone): null·blank 검증 후 교체·email 불변.
- UserAddressService: 소유권 모든 단건 접근 findByIdAndUserId 스코프(BOLA·404 은닉)·create의 makeDefault=countByUserId==0||request.isDefault()·demoteCurrentDefault(findByUserIdAndIsDefaultTrue·있으면 unmark) 공통 선행·delete=markDeleted soft·setDefault=demote-then-set 멱등.
- UserAddress: create 9-arg(옵션 3필드 배선)·updateDetails(isDefault·user 제외 상세 교체)·markDefault/unmarkDefault.
- UserAddressController: 전 엔드포인트 /api/v1/users/me/** authenticated(SecurityConfig 무변경)·requireUserId() 재사용·GET·PATCH /me(프로필)·GET·POST /me/addresses·PATCH·DELETE /me/addresses/{id}·PATCH /me/addresses/{id}/default.
- AddressNotFoundException→404 ADDRESS_NOT_FOUND(CartItem 선례·소유권 미충족도 동일 404 은닉).
- 검증: 796 tests PASS(--rerun-tasks·캐시 무효)·기준선 766 +30·회귀 0. 신규 30(UserService +4·UserAddressService 11·ProfileControllerIntegration 4·UserAddressControllerIntegration 11). 4지점 MCP 대조(UserAddressService·AbstractSoftDeletableEntity·AddressNotFoundException·GlobalExceptionHandler 매핑).

### §트랩 실측
- is_default 단일성은 DB 제약 불가(MariaDB 부분 UK 미지원)·앱 로직 demote-then-set 동일 TX 강제. setDefault 멱등(강등·승격 상쇄).
- 소유권 스코프 findByIdAndUserId로 미소유·미존재 통합 404(존재 여부 비노출·타 user 주소 접근 차단 통합테스트 실증).
- UserAddress.create 옵션 3필드 미배선 결함(팩토리가 필수 6개만 세팅) → 9-arg 확장으로 해소(정찰 부수 발견).
- soft delete 삭제분 @SQLRestriction("deleted_at IS NULL") 자동 제외로 목록에서 숨김(통합테스트 실증).

### §8 carry-over (이월)
1. UserAddress equals/hashCode 가이드 위반(부수 발견·무발현): UserAddress는 @Id에 @EqualsAndHashCode.Include만 있고 클래스 레벨 @EqualsAndHashCode(onlyExplicitlyIncluded=true, callSuper=false) 애노테이션 부재 → Lombok equals 미생성·부모(Include 0) equals 상속·인스턴스 미구분(AbstractSoftDeletableEntity Javadoc Q8=C 가이드 위반). JPA id 기반 영속이라 현재 무발현(테스트 same() 우회). Category·ProductImage 동일 패턴 추정 → 3종 확정 정찰 후 클래스 애노테이션 일괄 부착(잔존물/품질 정리 트랙 병합 후보). 긴급도 낮음.
2.

---

## D-142 [ACTIVE] MVP 잔여 백로그 — 이연 확정 항목 (Track 58 후 정리)

**결정일**: 2026-07-06
**상태**: [ACTIVE]
**참조**: backend-audit-v1.md §6·D-137~141 §8 이월 취합

### 이연 확정 (현 시스템 동작에 불필요·재론 트리거 명시)
| ID | 항목 | 이연 근거 | 재론 트리거 |
|---|---|---|---|
| BL-9 | 순환의존(refund↔claim·payment↔refund) | 도메인상 정당한 교차 조회·동작 무해 | 결합도 문제 실발현 시 |
| BL-2 | 셀러 승인 워크플로우 + 미사용 SellerStatus | 관리자 provision으로 입점 완료·승인 플로우 MVP 밖 | KYC/KYB·셀프 입점 요구 실재 시 |
| BL-7 | review 도메인 부재 | 리뷰 없어도 거래 성립 | 리뷰 기능 요구 실재 시 |
| 잔존물 | 미사용 enum 6·테이블 3·Repository 10·code 고립 | 동작 무관·순수 청소 | 정리 트랙 착수 시 |
| equals/hashCode | UserAddress·Category·ProductImage 3종 | 무발현(JPA id 기반 영속) | 정리 트랙 병합(D-141 §8-1) |
| A-2 | ip/user_agent 수집 | 소비처 부재·seam 준비됨 | 포렌식 감사 요구 시 |
| A-3 | actor_role 세분화 | coarse로 충분 | 세분 권한 감사 대상화 시 |
| A-4 | diff 값 타입 직렬화(BigDecimal·Enum) | 현 소비처 status·gradeId만 | 소비처 값 타입 확대 시 |
| A-5 | PurchaseConfirmed 이벤트 배선 | 월배치 풀집계로 정산 동작 중 | 이벤트 기반 전환 시 |
| A-6 | Code(공통코드) Aggregate | 골격만·MVP 밖 | 공통코드 운영 요구 시 |
| 품질 | Clock 미주입 34건 | 동작 무관 개선 | 시간 의존 테스트 확대 시 |

### 배포 전 필수 (이연 아님·별도 표기)
- graceful shutdown 미설정 — **배포 직전 1회 필수**(무중단 배포 안전·기능 아님).

### 판단 보류 (BL-5·6 = 목표 성격에 따라 갈림)
- BL-5 탈퇴/휴면(개인정보 라이프사이클)·BL-6 상품 이미지 등록 write.
- 실배포 운영이면 필수(BL-5 법적·BL-6 상품 필수), 기능 데모면 이연 가능. 목표 확정 후 트랙화 판단.

---

## D-143 [ACTIVE] 셀러 상품 이미지 관리 CRUD write (Track 59·BL-6)

**결정일**: 2026-07-06
**상태**: [ACTIVE]
**참조**: backend-audit-v1.md(BL-6·"등록 write ❌ 조회만"·B급)·recon-report.md(track-59)·D-141(UserAddressService demote-then-set·소유권 404 은닉 선례)·D-142(BL-6 판단보류 해소)·외부 검토 생략(A급·write 표면 협소·거래 핵심 비결합)

### 진입점
- Service: product/service/SellerProductImageService.java(신설·add·designateMain·reorder·delete·private applyMain·requireOwnedImage·requireOwnedProduct)
- Controller: product/controller/SellerProductImageController.java(신설·/api/v1/seller/products/{productId}/images·POST 201·PATCH /{imageId}/main 200·PATCH /reorder 200·DELETE /{imageId} 204·SellerActorResolver 소유자 해소·SecurityConfig 무변경)
- Repository: product/repository/ProductImageRepository.java(findByIdAndProduct_IdAndProduct_SellerId·findByProductIdAndMainTrue·countByProductId 추가·findByProductId 유지)·product/repository/ProductRepository.java(findByIdAndSellerId 추가·실측 부재 확인 후 신설)
- Entity: product/entity/ProductImage.java(markMain·unmarkMain·changeDisplayOrder 추가·create/필드/equals 무변경)
- 예외: product/exception/ProductImageNotFoundException.java(신설·404)·GlobalExceptionHandler(PRODUCT_IMAGE_NOT_FOUND 매핑)·product 소유권=ProductNotFoundException 재사용(404)·reorder 불일치=IllegalArgumentException 재사용(handleMalformed 400)
- DTO: request/AddProductImageRequest·ReorderProductImagesRequest·response/ProductImageResponse(카탈로그 ProductDetailResponse.Image 미재사용·셀러 응답 분리)
- DDL 신규: 없음(product_image V1:401-417 완비·V16 최신 유지)

### §1-A 옵션 검토

**축1 write 구조** — 채택 β 별도 이미지 관리 엔드포인트(풀 CRUD)
- 등록 트랜잭션 통합(α) 기각: 통합만으론 수정·삭제·재정렬·대표변경을 못 덮음(BL-6 요구=등록·수정·삭제 write). 별도 엔드포인트가 CRUD 전량 커버·UserAddress 주소록 엔드포인트와 동형. 등록 DTO에 images 필드 없음(정찰 §5) → 등록 시 초기 이미지 첨부는 프론트 요구 부재라 YAGNI(기조4).

**축2 is_main 단일성** — 채택 α 서비스 demote-then-set(DB 무제약)
- MariaDB 부분 UK(is_main=true 1건) 미지원(NULL≠NULL·D-131 dedup 트랩 동류). UserAddress is_default가 동일 문제를 검증된 서비스 로직으로 해결(D-141 축3)·직접 이식. DB STORED generated 트릭은 과잉(기조1·4). applyMain이 강등(기존 대표 unmarkMain)→승격(target markMain)·동일 TX·대상이 이미 대표면 필터로 제외되어 멱등.

**축3 is_main ↔ product.thumbnail_url** — 채택 β 독립 유지(동기화 안 함)
- 현재 목록=thumbnail_url·상세=is_main 이원화(정찰 §4). β는 신규 위험 0·신규 마이그레이션 불요(기존 불일치를 BL-6가 악화시키지 않음). α(동기화) 이행 용이성은 대표 확정 로직을 단일 private applyMain으로 격리하고 `// SEAM: 향후 thumbnail_url 동기화 지점(Track 59 결정3-β)` 주석 부착으로 확보 — 향후 α는 그 지점 1줄(product.updateThumbnail) 삽입으로 완결. T2가 main=true 지정 후 thumbnail_url NULL 유지를 실증(독립 계약 고정).

**축4 2-hop 소유권** — 채택 α 리포지토리 조인 스코프
- seller→product→image 2-hop. 단건 접근을 findByIdAndProduct_IdAndProduct_SellerId(image)·findByIdAndSellerId(product)로 스코프해 타 판매자 소유·미존재를 404 은닉(BOLA 차단·UserAddress/CartItem 1-hop 선례의 2-hop 확장). 서비스 이중 조회+대조(β) 기각: 소유권 검증 지점 분산·실수 여지.

**축5 식별자** — 채택 내부 id + 소유권 스코프
- product_image public_id 부재 실측(정찰 §1·§2). public_id 추가(컬럼·마이그레이션) 기각: 소유권 스코프로 IDOR 이미 차단·과잉(D-141 축2 동일 판단).

**축6 equals/hashCode** — 채택 현행 유지(범위 밖)
- ProductImage는 @Id에 @EqualsAndHashCode.Include만·클래스 레벨 애노테이션 부재(형제 UserAddress·ProductOptionGroup 동일 패턴). D-141 §8-1·D-142가 3종(UserAddress·Category·ProductImage) 정리 트랙 병합으로 이연 확정 → BL-6 단독 이탈 금지·무손질. JPA id 기반 영속이라 write 컬렉션 처리 무발현.

### §2 결정 라운드 재진입·수렴
- A급(Product 도메인)·외부 검토 생략(write 표면 협소·엔티티/리포/조회 소비처 기존재·거래 핵심 비결합·신규 마이그레이션 불요). 정찰이 구현 깊이 도달해 2차 정찰 생략.
- 결정1·2·4·5 권장 채택. 결정3만 사용자 재지정: 초안 α 권장(약)→사용자 β 선택(리스크 낮으면 β·α 이행 용이 설계 조건) → SEAM 격리로 β 확정.
- 정찰 부수 실측(Javadoc 불일치): ProductSummaryResponse.mainImageUrl Javadoc "is_main 우선"과 실코드 thumbnail_url 전달 불일치(정찰 §4-B). 본 트랙 범위 밖·독립 계약(축3 β)이라 무손질·인지만 기록.

### §3 배선 실측
- SellerProductImageService: add=requireOwnedProduct→displayOrder=countByProductId(append)→ProductImage.create(main=false)→save→request.main()이면 applyMain 경유. designateMain=requireOwnedImage→applyMain(멱등). reorder=requireOwnedProduct→findByProductId 활성 집합과 imageIds 정확 일치 검증(size≠set.size 중복·keySet≠requestedSet 누락/과잉 → IllegalArgumentException 400)→순서대로 changeDisplayOrder(0..n-1). delete=requireOwnedImage→markDeleted soft. applyMain=findByProductIdAndMainTrue.filter(≠target)→unmarkMain·save(강등)→SEAM 주석→markMain·save(승격).
- ProductImage: markMain/unmarkMain(this.main 토글)·changeDisplayOrder(this.displayOrder 교체). create/필드/equals 무변경.
- 예외 매핑: ProductImageNotFoundException→404 PRODUCT_IMAGE_NOT_FOUND(AddressNotFound 선례 미러)·product 소유권 미충족=ProductNotFoundException 재사용(404·기존 카탈로그 은닉 선례)·reorder 집합 불일치=IllegalArgumentException(handleMalformed 400 재사용·HTTP 의미 동일).
- 검증: 810 tests PASS(--rerun-tasks·캐시 무효·4 tasks executed). 기준선 796 +14·회귀 0. 신규 14(SellerProductImageControllerIntegrationTest·성공·단일성·정렬·soft delete·2-hop 404·401/403·검증400). 4지점 MCP 대조(SellerProductImageService·ProductImage mutator·ProductImageNotFoundException·GlobalExceptionHandler 매핑).

### §트랩 실측
- is_main 단일성 T4 실검증: 3이미지 순차 designateMain 시 "활성 대표 정확히 1개 + 직전 강등"을 assert(우연일치 아님·pre-check 우회 금지).
- 2-hop 소유권: 타 판매자 product(T9)·image(T10) 접근을 404 은닉(존재 여부 비노출).
- reorder 집합 동일성: size 비교로 중복 참조, keySet 동일성으로 누락·과잉 동시 차단(400).
- soft delete: markDeleted 후 @SQLRestriction("deleted_at IS NULL")로 조회 자동 제외(T 실증).

### §8 carry-over (이월)
1. displayOrder append=count tie 가능성: 중간 이미지 soft delete로 활성 수 감소 시 이후 add의 displayOrder(=count)가 생존 상위 이미지와 동값 가능(예: [0·1·2] 중 1 삭제 → 활성 [0·2], 다음 add=2). display_order UK 부재로 오류 아님·상세 응답 정렬 tie만 발생·reorder(0..n-1)가 완전 해소. 설계 명시(count append)+reorder 완화로 현행 유지. 재론 트리거=삭제 후 append 정렬 안정성 실요구 시 append=MAX(display_order)+1로 전환.
2. ProductImage equals/hashCode(D-141 §8-1·D-142): 정리 트랙 병합 이월·BL-6 무손질 확정.

---

## D-144 [ACTIVE] 회원 self-withdraw write (Track 60·BL-5)

**결정일**: 2026-07-06
**상태**: [ACTIVE]
**참조**: backend-audit-v1.md(BL-5·"탈퇴/휴면 라이프사이클 write 부재"·A급)·recon-report.md(track-60·1차 상태+2차 seam)·D-141(UserService 라이프사이클 op 응집·changePassword 선례)·D-142(BL-5 판단보류 해소)·deletion-policy.md §3(탈퇴=withdrawn_at 마킹·비식별화 별개 축)·D-22(비식별화 후 재가입 정책)·외부 검토 수행(결정 a~k 전량·반박 0)

### 진입점
- Entity: user/entity/User.java(withdraw() 추가·멱등 no-op·withdrawn_at 마킹·create/assignPasswordHash/updateProfile 무변경)
- Service: user/service/UserService.java(withdraw(Long userId) 추가·findById orElseThrow IllegalStateException→user.withdraw()→save·클래스 @Transactional 재사용·UserWithdrawalService 미신설)
- Controller: user/controller/UserController.java(POST /api/v1/users/me/withdraw→204 noContent·requireUserId()·changePassword 미러)
- 인증·인가: AuthenticatedUserResolver.requireUserId() 재사용·SecurityConfig 무수정(/api/v1/users/me/** = anyRequest().authenticated() 자동 커버)·AuthService withdrawn_at 가드 무수정(읽기측 불변·탈퇴 시 자동 발동)
- 예외: 신규 예외 없음(멱등 성공만 존재·인증됐으나 부재=IllegalStateException 500 fallback·changePassword 계약)
- DDL 신규: 없음(withdrawn_at·anonymized_at V1:31-32 완비·V16 최신 유지)

### §1-A 옵션 검토

**결정1 트랙 목표(β 데모 최소 soft withdraw)** — 채택 β 탈퇴+재로그인 차단만
- α(S급 PII 비식별화 풀스코프) 기각: 실 PII 부재(데모/포트폴리오)·현 소비처 없음(기조4). 비식별화·법적 보관·연관데이터 종단 처리는 실서비스 런칭 시 별 트랙(S급)이 타이밍상 정확. 재론 트리거=실 PII 발생·법적 요구 실재 시.

**결정a 휴면 포함 여부** — 채택 α 탈퇴만
- 휴면 인프라 전무(dormant 상태·last_login·전환배치 0). 휴면은 기능이 아니라 신규 라이프사이클 축 신설이며 데모 시나리오에 호출 경로 부재(기조4). BL-5 제목의 "휴면"만으로 구현 근거 불성립(외부 검토 정합).

**결정b status enum 도입** — 채택 α 미도입(timestamp 유지)
- withdrawn_at·anonymized_at·deleted_at 3축으로 활성/탈퇴/비식별 표현 가능. enum 도입은 SoT 이중화(status=ACTIVE ↔ withdrawn_at!=null 불일치 가능)·4층위 잠금+신규 마이그레이션 비용·소비처 부재(기조4).

**결정c 연관 데이터(user_address·buyer_profile) 처리** — 채택 α 미처리(현행)
- User측 cascade/orphanRemoval 0·child측 단방향 소유·FK RESTRICT. withdrawn_at 마킹만으로 로그인 차단→접근 봉쇄. cascade화는 Aggregate 경계 확대. 물리 잔존 처리는 비식별화 배치 트랙 소관.

**결정d 비식별화(anonymized_at) 배치** — 제외 확정
- 배치 이연 확정(deletion-policy §5). β는 withdrawn_at까지. anonymized_at read/write 0 유지.

**결정e 즉시 세션 강제 만료** — 채택 α 재로그인 차단만
- JWT STATELESS·access token 1h·블록리스트 미구현. 재로그인 차단으로 탈퇴 실효. 기존 토큰 ≤1h 자연 만료. 블록리스트는 별도 인프라·데모 유지비 초과(기조1·4).

**결정f 탈퇴 API 멱등성** — 채택 α 멱등 성공(재탈퇴 no-op 204)
- 이미 탈퇴는 이미 원하는 상태 도달 → 멱등 성공이 운영 편의(기조1). β(409) 기각. 핵심: no-op은 withdrawn_at을 덮어쓰지 않고 최초 탈퇴 시각을 유지(감사 정보 보존·if(withdrawnAt!=null) return).

**결정g 동일 이메일 재가입 정책** — 채택 α 재가입 불가(현행 유지·명시)
- UNIQUE(email) + withdrawn_at만 마킹 구조에서 재가입 자연 실패가 기본 동작. 허용은 email 비식별화·unique 정책·식별/감사 정책 동반 필요 → 비식별화 트랙 분리. 정책 공백만 명시로 메움.

**결정h WithdrawnUser 아카이브 write** — 채택 α 미사용
- WithdrawnUser.create()가 요구하는 reason·legal_retention_until은 실서비스 보존 정책 연결값. 정책 자체를 이연했는데 엔티티만 채우려 null·임의날짜·더미 reason 주입은 데이터 품질 저하·소비처 없는 값(기조4). "엔티티 있으니 쓰자"가 아니라 "소비처·정책 생기면 쓰자". 탈퇴 reason 수집도 동반 제외.

**결정i 탈퇴 API 권한 범위** — 채택 α 본인 self-withdraw만
- 현 소비처=self뿐. /users/me/** authenticated 정합·AuthService 가드 재사용. 운영자 강제 탈퇴는 권한 모델·관리자 API·감사·운영 UX 신규 소비처 동반 → MVP 밖(기조4).

**결정j withdraw 서비스 배치** — 채택 α UserService에 메서드 추가(UserWithdrawalService 미신설)
- withdraw는 User Aggregate 단일 mutation·changePassword와 동형. User 라이프사이클 op(가입·비번·프로필) 응집처=UserService. UserAddressService 분리는 별 리소스(주소록 CRUD 5종)였던 반면 withdraw는 별 리소스 아님. 단일 메서드 전용 서비스는 과잉(기조1·4). 향후 WithdrawnUser·비식별화 예약·이벤트·운영자 탈퇴 등 다중 Aggregate 조율 발생 시 분리가 자연스러움.

**결정k 신규 예외** — 채택 제외(실측 반영)
- 멱등 성공만 존재(실패 케이스 없음). 인증됐으나 부재=기존 IllegalStateException→500 fallback(changePassword 계약). AlreadyWithdrawnException은 멱등 설계와 모순·UserNotFoundException은 기존 예외 처리 방식과 불일치.

### §2 결정 라운드 재진입·수렴
- A급(USR 루트·인증 경로 교차)이나 write 표면 협소·거래 로직 비결합·인증 읽기측 불변으로 확정. 외부 검토 수행(결정 a~k 전량 제시) → 전 항목 동의·설계 반박 0. 검토가 (f)멱등 세부(최초 시각 유지)·(g)재가입 정책 명시·(h)아카이브 미사용 사유 명시·(i)권한 범위 확인을 추가 권장 → 각각 f·g·h·i 결정으로 승격·수렴.
- 2차 정찰(구현 seam)이 (j)(k) 유발: 초안 스코프의 UserWithdrawalService·신규 예외를 실측 근거로 축소(단일 mutation 응집·기존 500 계약 재사용). 외부 검토 동의로 확정.

### §3 배선 실측
- User.withdraw(): withdrawnAt!=null이면 no-op(최초 시각 유지·멱등)·아니면 LocalDateTime.now() 마킹. deleted_at·anonymized_at 무변경(별개 축).
- UserService.withdraw(userId): findById→없으면 IllegalStateException("인증된 userId에 해당하는 User가 없습니다")→user.withdraw()→save→log. changePassword 관용구 동형.
- UserController: POST /me/withdraw→requireUserId()→withdraw→204 noContent. SecurityConfig /api/v1/users/me/** = anyRequest().authenticated() 자동 커버(무수정·코드 확인).
- 재로그인 차단: 기존 AuthService withdrawn_at!=null→ACCOUNT_DISABLED(통합 401) 무수정 자동 발동.
- 검증: 814 tests PASS(--rerun-tasks·4 tasks executed·캐시 무효·6m47s). 기준선 810 +4·회귀 0. 신규 4(WithdrawControllerIntegrationTest·성공204+DB마킹·멱등 최초시각유지·탈퇴후 재로그인 401 실효·미인증 401). 4지점 MCP 대조(User.withdraw·UserService.withdraw·UserController 엔드포인트·통합테스트).

### §트랩 실측
- 멱등 최초 시각 유지: 재탈퇴 후 second==first assert(가드 제거 회귀 시 덮어쓰기로 실패·우연일치 아님).
- 재로그인 차단 우연일치 배제: 탈퇴 前 동일 credential 로그인 200(완전 로그인 가능 실증) → 後 401 대조. 실 BCrypt password_hash + BUYER user_role seed 필요.
- LT-02 준수: SET FOREIGN_KEY_CHECKS=0/1 복원 finally + @AfterEach cleanup. @Transactional 미부착(JdbcTemplate 실 커밋 검증·coincidental 회피). user_role role_id는 V11 seed code 조회(하드코딩 금지).

### §8 carry-over (이월)
1. 비식별화 배치(anonymized_at 세팅)·법적 보관기간·연관 데이터(user_address·buyer_profile) 종단 처리: 실서비스 런칭 시 별 트랙(S급). 재론 트리거=실 PII 발생·법적 요구 실재 시(결정1·c·d·h).
2. 휴면(dormant) 라이프사이클: 상태·last_login·전환배치 전무. 실서비스 확정 시 별 트랙(결정a).
3. 운영자 강제 탈퇴: 권한 모델·관리자 API·감사·UX 동반 시 도입(결정i). WithdrawnUser 아카이브 소비도 이때 결정(결정h).

---

## D-145 [ACTIVE] graceful shutdown 설정 (Track 61)

**결정일**: 2026-07-06
**상태**: [ACTIVE]
**참조**: D-142(배포 전 필수·"graceful shutdown 미설정·배포 직전 1회 필수·기능 아님")·recon-report 생략(설정 부재 3곳 직접 실측)

### 진입점
- 설정: backend/src/main/resources/application.yml(spring.lifecycle.timeout-per-shutdown-phase: 20s·server.shutdown: graceful 신규 최상위 블록)
- 인프라: docker-compose.mall.yml(zslab_mall_backend.stop_grace_period: 30s·backend 서비스 한정·filebeat 미포함)
- 코드 변경 없음(설정 2파일 4줄)·마이그레이션 없음·테스트 없음(설정 검증=전 @SpringBootTest 컨텍스트 로드로 application.yml 실 파싱)

### §1-A 옵션 검토

**축1 실효 완결 범위** — 채택 α 앱 yml + docker stop_grace_period 동시
- graceful shutdown은 앱 설정과 컨테이너 grace period가 짝이다. 앱만 graceful로 켜도 docker stop_grace_period 기본 10s 후 SIGKILL이 진행 요청을 끊어 반쪽이 된다(실측: compose에 stop_grace_period 부재=기본 10s). β(앱만 지금·docker는 배포 트랙 이연) 기각: 미완 상태가 배포 트랙까지 잔존하고 docker를 재차 수정(재작업). 총 4줄이라 지금 완결이 기조1·4 정합.

**축2 timeout 값 커플링** — 채택 앱 20s < docker 30s
- 앱 grace(timeout-per-shutdown-phase)가 docker grace(stop_grace_period)보다 작아야 앱이 진행 요청을 완료(≤20s)하고 정상 종료한 뒤 docker의 SIGKILL(30s) 전에 여유가 생긴다. 역전(앱≥docker) 시 앱 완료 전 KILL. 단일 운영자·저트래픽이라 20s면 진행 요청 완료 충분(기조1). 기본값(앱 30s/무명시) 대신 명시 20s로 커플링을 코드에 고정.

**축3 actuator health probe 연동** — 채택 제외(YAGNI)
- Spring graceful 시 /actuator/health를 OUT_OF_SERVICE로 돌려 로드밸런서가 라우팅을 끊는 연동은 gateway가 health 기반 라우팅을 할 때만 유효. gateway_nginx가 정적 프록시라 소비처 부재(기조4). 앱 graceful + docker grace로 무중단 배포 안전 확보 충분.

### §2 결정 라운드 재진입·수렴
- 기능 아님·배포 안전 설정·회귀 표면 0(설정 변경). 정찰=설정 부재 3곳(application.yml·application-prod.yml·docker-compose) 직접 실측으로 recon-report 파일 생략. 축1 α 권장 채택·축2·3 권장 채택.

### §3 배선 실측
- application.yml: spring.lifecycle.timeout-per-shutdown-phase: 20s(jackson 아래·spring 하위 2-space)·server.shutdown: graceful(management 앞 최상위 신규 블록). spring 최상위 키 중복 없음(^spring: 1건·^server: 1건 실측).
- docker-compose.mall.yml: zslab_mall_backend.stop_grace_period: 30s(restart 아래 서비스 키 레벨). filebeat·volumes·networks·env 무변경.
- 검증: 814 tests PASS(--rerun-tasks·회귀 0·설정 변경 무영향). 전 @SpringBootTest 컨텍스트 로드 정상=application.yml 실 파싱 검증. docker compose config --quiet 통과(YAML 유효).

### §트랩 실측
- 앱/컨테이너 grace 역전 트랩: 앱 timeout ≥ docker stop_grace_period면 graceful이 무의미(앱 완료 전 SIGKILL). 20s < 30s로 앱이 항상 먼저 완료. 배포 트랙에서 stop_grace_period 조정 시 앱 timeout과의 대소 관계 유지 필수.

### §8 carry-over (이월)
1. actuator readiness/liveness probe·로드밸런서 graceful 연동: gateway가 health 기반 라우팅 도입 시 재론(축3). 현재 정적 프록시라 불요.

---

## D-146 — 백엔드 CI/CD 자동 배포 파이프라인 (Track 62)

### §진입점
1. 목적: main push → 서버 자동 배포(빌드·재기동·헬스체크) 파이프라인 확립.
2. 핵심 진입점: .github/workflows/deploy.yml·docker-compose.mall.yml·docker-compose.dev.yml·backend/Dockerfile.
3. 핵심 SoT: deploy.yml(appleboy/ssh-action v1.2.4·docker compose up -d --build --wait).
4. 영향 범위: 배포 인프라(도메인 코드 무변경).
5. 패턴 재사용: zslab-lms A′ 방식(push→Actions→서버 SSH→git pull→compose) 이식 1회차.
6. 트랩 주의: LT(BOM)·LT(bind-mount 은폐) 하단 기재.

### §1-A 옵션 검토
- 방식: α(A′ push→Actions→서버 SSH→git pull→docker compose up)·기각 β(ghcr.io 이미지 빌드). 채택 α — Actions 분 절약·운영 Dockerfile 자기완결·lms 검증 패턴.
- 트리거 α(main push 자동)·CI 테스트 α(미실행·로컬 --rerun-tasks 게이트)·싱글톤 합류 α(미합류·별도 트랙)·무중단 α(graceful recreate)·롤백 α(직전 커밋 재배포)·HEALTHCHECK α(compose 블록 --wait).

### §2 결정 라운드
- D4 실측: temurin:21-jre에 curl 존재 → curl healthcheck·Dockerfile 불변.
- 서버 재원 실측(가용 RAM ~1.8Gi) → 힙 캡 도입: 빌드 GRADLE_OPTS -Xmx1g·런타임 -Xmx512m·compose mem_limit 1g. 앱 실기동 512m로 Started 24~28s 확인(충분).
- 배포 검증: 서버 재배포 컨테이너 Up (healthy)·Started 24.193s·prod·SUPER_ADMIN 재기동 멱등 skip 실측.

### 결함 교정 (2건·실측)
- compose env 누락: environment에 JWT_SECRET·ADMIN_BOOTSTRAP_* 미전달 → prod jwt.secret fail-fast·신선DB bootstrap fail-fast. 3키 ${VAR} 주입 추가로 해소.
- bind-mount 운영 jar 은폐: - ./backend:/app가 운영 이미지 /app/app.jar 은폐 → "Unable to access jarfile" 재시작 루프. mall.yml 운영 기본화(dev 마운트 제거)·docker-compose.dev.yml 오버라이드 분리로 해소. 서버 이미지 단독 기동 Started 28s로 검증.

### 트랩 (2건)
- PS5.1 커밋 BOM: Out-File -Encoding utf8이 BOM 삽입·커밋 제목 U+FEFF 오염. [System.IO.File]::WriteAllText + UTF8Encoding($false)로 BOM 없이 작성. 커밋 87bebd6 오염·amend 교정 실증.
- dev 마운트 prod 은폐: Dockerfile.dev 전용 소스 바인드가 운영 이미지 산출물 은폐. prod/dev compose 분리 필수.

### §8 이월
- 싱글톤 컨테이너 전환(Track 59 정찰 완료·CI 테스트 미실행으로 시급성↓·별도 트랙).
- gateway_nginx mall 도메인 upstream+SSL(후속·수동).
- backend/.env.example ↔ 루트 .env.example 스키마 불일치(경미).

---

## D-147 — 테스트 싱글톤 컨테이너 전환 (Track 63)

### §진입점
1. 목적: 테스트 클래스마다 개별 기동하던 MariaDBContainer(자체선언 57)를 JVM당 1개 싱글톤으로 일원화 → 전체 테스트 실행 시간 단축.
2. 핵심 진입점: backend/src/test/java/com/zslab/mall/support/ — MariaDbTestContainer.java(INSTANCE L16·static start L18-21·private 생성자 L23)·AbstractDataJpaTest.java(@DataJpaTest L21·@DynamicPropertySource L26-33·@AfterEach restoreForeignKeyChecks L64-72)·AbstractIntegrationTest.java(@SpringBootTest L16·@DynamicPropertySource L19-26).
3. 핵심 SoT: MariaDbTestContainer.INSTANCE(싱글톤 컨테이너·never stop)·AbstractDataJpaTest.restoreForeignKeyChecks(Session.doWork raw JDBC·FK_CHECKS=1 무조건 복원).
4. 영향 범위: 테스트 인프라 한정(운영 코드·마이그레이션 0). 자체선언 57 전환(3 DataJpaTest 베이스 + 54 @SpringBootTest)·베이스 상속 39 서브클래스 무변경 승계.
5. 패턴 재사용: Testcontainers 싱글톤 홀더 + 슬라이스별 추상 베이스 패턴 신규 확립(이식 1회차).
6. 트랩 주의: LT-02(FK_CHECKS 세션 누수·싱글톤 공유풀서 증폭)·autoflush 트랩(§트랩·doWork 필수).

### §1-A 옵션 검토
- 축1 구조 — 채택 α(홀더 MariaDbTestContainer + 슬라이스별 추상 베이스 2종 extends). 기각 β(spring.factories ContextCustomizerFactory 전역 주입): 사후 Java 학습자료라 명시적 상속이 전역 매직보다 추적·디버깅·회귀 원인 격리 우위. β 편집량 이점은 Claude Code 자동 편집으로 상쇄. 기각 γ(하이브리드).
- 축2 FK_CHECKS 누수 복원 — 채택 α(AbstractDataJpaTest 공통 @AfterEach 무조건 =1 복원). 기각 β(테스트별 복원): FK=0 누수는 제약 완화 방향이라 테스트 실패로 안 나타남(거짓 GREEN)·GREEN 게이트가 검출 못 함 → 개별 규율 의존 시 한 곳만 빠져도 은폐. α는 공통 베이스 1곳 구조적 전수 커버. 기각 γ(현행 유지).
- 축3 FK 복원 실행 방식 — 채택 Session.doWork raw JDBC. 기각 createNativeQuery().executeUpdate(): 실행 직전 autoflush 유발 → 제약위반 기대 테스트의 예외-오염 세션서 org.hibernate.AssertionFailure·복원 중단·FK=0 누수. 기각 flushMode=COMMIT(덜 견고)·HikariCP 반납 리셋(범위 과대).

### §2 결정 라운드 재진입·수렴
- A급(~57 클래스 재작성·고회귀)·외부 검토 선택적(생략). 정찰=Track 59 인계 recon + Claude Code 전수 목록화(자체선언 57·상속 39·mariadb:11.4 단일·비-MariaDB 0).
- 단계적 마이그레이션(홀더/베이스 → 파일럿 6 → 전량 51). 파일럿 게이트가 싱글톤 공유풀 전용 잠복결함 2건(autoflush·FK 누수) 조기 포착·교정. 축2 α의 초안 "no-op 무해" 가정을 파일럿서 교정(EntityManager 접촉 자체가 autoflush)→doWork로 확정.

### §3 배선 실측
- MariaDbTestContainer: public static final INSTANCE·static 블록 start()·private 생성자·never stop(Ryuk JVM 종료 회수).
- AbstractDataJpaTest: @DataJpaTest+@AutoConfigureTestDatabase(NONE)+@Import(AuditingConfig) 상위 승격·@DynamicPropertySource 4키 INSTANCE 참조·protected TestEntityManager·@AfterEach restoreForeignKeyChecks(Session.doWork raw JDBC SET=1·package-private).
- AbstractIntegrationTest: @SpringBootTest만 상위(@AutoConfigureMockMvc 미승격·MockMvc 필요 서브클래스 개별 병기)·@DynamicPropertySource 4키.
- 전환: 3 DataJpaTest 베이스 extends AbstractDataJpaTest(헬퍼 disableForeignKeyChecks·buildFullOrder·buildPayment 보존)·54 @SpringBootTest extends AbstractIntegrationTest(MockMvc·@Mockito(Spy)Bean·FK =0/=1 try-finally·시드 보존). ProdSecurityContextSmokeTest는 자체 props(jwt.secret·@ActiveProfiles) 보존·datasource 4줄만 상위 위임.
- 검증: 814 tests GREEN·회귀 0. before 416.3s(6m56s·de9ae31 stash 복원) → after 61.72s(1m1s·전량 싱글톤)·85.2% 단축·6.74×(동일 머신·--rerun-tasks 무캐시·stash 왕복). 4지점 MCP 대조(support 3종 + 전환 표본).

### §트랩 실측
- LT-02 증폭: 기존 베이스별 컨테이너 분리가 FK_CHECKS=0 누수를 은폐 → 싱글톤 단일 공유풀서 발현. 3 DataJpaTest 베이스 통합 시 단일 컨텍스트·단일 풀로 Order/Payment의 =0이 Batch1 계열(35 서브클래스) 전파 가능 → 공통 @AfterEach 무조건 복원으로 차단.
- autoflush 트랩(신규): createNativeQuery().executeUpdate()가 실행 직전 영속성 컨텍스트 autoflush → 제약위반 기대 테스트("don't flush after exception" 세션)서 org.hibernate.AssertionFailure. Session.doWork는 바인딩 커넥션에 raw JDBC 실행·flush 우회 → 소멸·FK 미사용 테스트에도 진짜 no-op. createNativeQuery 회귀 금지(AbstractDataJpaTest Javadoc 박제).
- 거짓 GREEN 검출 한계: FK=0 누수는 GREEN이 검출 못 함 → 파일럿 게이트서 "@SpringBootTest =0 후 미복원 0건" grep 눈 확인 보강. 실측 =0 63·=1 62·차이 2=Order/Payment 베이스(상위 @AfterEach 복원)·=0만 @SpringBootTest 0건.
- @AfterEach 접근제어자: 상위 protected 승격 시 동일명 restoreForeignKeyChecks 보유 서브클래스와 오버라이드 충돌(약한 접근 불가 컴파일 에러) → package-private 유지(타 패키지 무충돌·JUnit5 계층 순회 호출 유지).

### §8 carry-over (이월)
1. autoflush 트랩 live-traps.md LT-06 승격 여부: 현재 D-147 §트랩 + AbstractDataJpaTest Javadoc 박제로 커버. Testcontainers/@DataJpaTest 재작업서 재발현(≥재사용 임계) 시 승격.
2. 그 외 없음.

---

## D-148 — gateway_nginx zslab-mall 도메인 upstream + SSL (서버 prod + 로컬 dev) (Track 64)

### §진입점
1. 목적: zslab_mall_backend를 gateway_nginx에 zslab-mall.duckdns.org 프록시 연결 + SSL. 서버=운영(Let's Encrypt), 로컬=개발 미러(mkcert 로컬CA).
2. 핵심 진입점: [서버] /home/gateway/nginx/nginx.conf(mall 443 vhost·80은 catch-all server_name _ 재사용)·/home/gateway/certs/zslab-mall.duckdns.org/{fullchain.crt,privkey.key}. [로컬] C:\Users\pc\projects\gateway\nginx\nginx.conf(mall 443 vhost)·gateway\certs\zslab-mall.duckdns.org\. [upstream] docker-compose.mall.yml(zslab_mall_backend·8080·gateway_net external).
3. 핵심 SoT: proxy_pass 변수 방식(set $upstream_mall http://zslab_mall_backend:8080; proxy_pass $upstream_mall;)·전역 proxy_set_header(http 블록)·resolver 127.0.0.11 재사용.
4. 영향 범위: 인프라 한정(코드·git·마이그레이션 0). gateway_nginx 공유 → 타 도메인 무회귀 필수.
5. 패턴 재사용: zslab-shop/lms 443 vhost 미러(이식). 로컬 mkcert 로컬CA 신규.
6. 트랩 주의: §트랩(sed -i inode·Docker Desktop 단일파일 RO·cert 복사본 갱신 갭·same-domain HSTS).

### §1-A 옵션 검토
- 서버 SSL — 채택 α(certbot webroot HTTP-01·기존 80 catch-all이 .well-known 서빙). 기각 DNS-01(webroot 충분·불요).
- 서버 80 — 채택: mall 전용 80 블록 불요(catch-all server_name _ 가 ACME + 80→443 redirect 처리). 
- 로컬 SSL — 채택 α(mkcert 로컬CA). 기각 β(LE DNS-01: 로컬 cert=prod 동일도메인 중복서 → LE duplicate rate limit prod 공유·갱신 오작동 시 prod 잠식·DuckDNS 토큰 로컬 노출·90일 갱신). 기각 γ(서버 cert 복사: prod privkey 로컬 반출 보안위반·90일 재복사 갭). mkcert가 운영편의·YAGNI 정합.
- 로컬 도메인 — 채택 β(prod 동일 zslab-mall.duckdns.org·hosts 127.0.0.1). 기각 γ(로컬전용 .local): 사용자 "운영과 똑같이" 명시. same-domain HSTS 충돌은 로컬 https(mkcert 신뢰)로 해소.

### §2 결정 라운드 재진입·수렴
- A급 아님(인프라·경미). 2-phase 정찰(선례 443 vhost 미러·gateway_net 조인·DNS·cert 규약) 후 변경. 서버·로컬 각각 사전 nginx -t -c 검증 → 적용 → 재검증(게이트웨이 전체 다운 반경 방어).
- 서버 검증: curl -I https 200·openssl SNI CN=zslab-mall·기존 도메인 무회귀. 로컬 검증: mall443=200·mall80=301·lms=301.

### §3 배선 실측
- 서버: mall 443 vhost 삽입(http 블록 말미)·certbot certonly --webroot -w /home/gateway/webroot -d zslab-mall.duckdns.org·발급본 /etc/letsencrypt/live/.../{fullchain,privkey}.pem → /home/gateway/certs/zslab-mall.duckdns.org/{fullchain.crt,privkey.key} 복사(심볼릭 아님). 만료 2026-10-04.
- 로컬: mkcert v1.4.4·-install(Windows 신뢰저장소)·mall cert 발급(만료 2028-10-06)·gateway/certs 규약 배치·:80 mall 커스텀 블록 제거 → :443 vhost 교체(catch-all 80→443 승계)·WriteAllText in-place + docker restart. 결과 mall443=200/mall80=301/lms=301.

### §트랩 실측
- sed -i inode 교체(서버·치명): 단일파일 bind-mount(/home/gateway/nginx/nginx.conf)에 sed -i는 inode 교체 → 컨테이너가 마운트 시점 옛 inode 계속 서빙·nginx -t/reload가 옛 config 기준 오탐 PASS·mall 블록 미적용(default_server 인증서 반환). 교정: 컨테이너 in-place cat /tmp/new > /etc/nginx/nginx.conf(inode 보존) 후 reload. 규약 정정: 기존 "gateway 편집 sed -i 사용" 폐기.
- Docker Desktop 단일파일 마운트 RO(로컬): 컨테이너측 /etc/nginx/nginx.conf 쓰기 "Read-only file system". 교정: 호스트측 [System.IO.File]::WriteAllText(경로유지·rename 아님) + docker restart(전파 확정) + 컨테이너 grep 로드 실측.
- cert 복사본 갱신 갭: /home/gateway/certs/ 는 letsencrypt live 심볼릭 아닌 복사본 → certbot renew 시 미갱신. prod mall 만료(2026-10-04) 전 재복사+reload 필요. (LT 승격 후보)
- same-domain HSTS 충돌: prod 443 vhost의 Strict-Transport-Security(1년) 핀 → 로컬 http 접속을 요청 전 https 자동업그레이드 → 로컬 :443 부재 시 502+cert경고. 로컬 https(mkcert) 도입으로 해소·teardown 시 chrome://net-internals/#hsts 삭제 필요.
- (부수) Windows hosts 127.0.0.1 도메인 잔존이 "로컬만 502"를 유발(클라이언트측·게이트웨이 무관). 진단 시 curl 외부 fetch로 서버/클라이언트 분리 판별.

### §8 carry-over (이월)
1. gateway 편집 규약(sed -i 폐기 → 서버 cat>file·로컬 WriteAllText+restart) live-traps.md LT 승격: 재발현(≥재사용 임계) 시. 현재 D-148 §트랩 커버.
2. prod mall cert 갱신 자동화: certbot renew --deploy-hook로 /home/gateway/certs 재복사+reload 자동화 미도입. 만료 임박 시 별건.
3. CLAUDE-DEV.md/컨텍스트 "공유 인프라 > gateway_nginx 편집 sed -i 사용" 문구 정정 필요(본 D 반영).

---

---

## D-149 — 장바구니 외부 대상키 정상화: 내부 variantId → variantPublicId 스냅샷 (Track 65)

> [갱신·D-151] §진입점 4·§트랩의 CartOrderPlacedHandler는 Track 67에서 CartPaymentCompletedHandler로 rename·구독 이벤트 OrderPlaced→PaymentCompleted로 이동. deleteByUserIdAndVariantIdIn(내부 소진 경로) 메서드·외부/내부 공용 경계 논지는 무변경으로 유효.

### §진입점
1. 목적: 장바구니 외부 계약의 대상 식별키를 내부 variant_id(BIGINT PK)에서 variant_public_id(var_·CHAR(30))로 정상화. 상세 응답은 var_ publicId만 노출하나 cart 계약이 내부 PK를 요구해 상세→담기 브리지 불가였던 seam 불일치 해소(recon-report-74 §5 블로커·FE-10 선결).
2. 핵심 진입점: V17__add_cart_item_variant_public_id.sql·cart/entity/CartItem(variantPublicId 스냅샷)·cart/service/CartService(addItem findByPublicId 해소)·cart/repository/CartItemRepository(파생 2 신설)·요청 DTO 4·응답 DTO 2·CartController 배선.
3. 핵심 SoT: cart_item.variant_public_id(비정규화 스냅샷·외부 대상키)·CartService.addItem(findByPublicId→내부 id 해소·insertNew publicId 스냅샷 저장).
4. 영향 범위: cart Aggregate 외부 계약·마이그레이션 1(V17). 내부 variant_id FK·UK(user_id,variant_id)·CartOrderPlacedHandler(주문→cart 정리 내부 경로) 무변경.
5. 패턴 재사용: 자식 Aggregate가 참조 대상의 외부 식별자를 담김 시점 스냅샷으로 소유(외부 계약이 참조 대상 생명주기에 비의존) 신규 확립.
6. 트랩 주의: LT-01(CHAR public_id @JdbcTypeCode)·dangling 스냅샷 노출.

### §1-A 옵션 검토
- 축1 seam 정합 방향 — 채택 β(cart 계약을 variantPublicId 수용). 기각 α(상세 응답에 내부 variantId 노출): cart가 이미 내부 PK를 외부 계약으로 노출한다는 이유로 catalog를 맞추면 위반 확산·내부 PK 열거 노출 유지. 원론적 외부 식별자는 var_ publicId(전 Aggregate 관례)이므로 cart를 관례로 정합. 기각 γ(publicId→id 변환 엔드포인트): 담기마다 왕복 1회 추가·운영/UX 열위. β는 머지된 cart 계약 개변 비용 있으나 "일반적 흐름"(cart 라인이 외부키 소유) 정합·우회 제거.
- 축2 dangling publicId 처리 — 채택 b-i(cart_item에 variant_public_id 비정규화). 기각 b-ii(내부 id 유지·경계 해소 + soft-delete 무시 native 조회): dangling(variant soft-delete)의 publicId를 @SQLRestriction 우회로 얻는 방식은 소프트삭제 불변식을 깨는 신규 우회. b-i는 마이그레이션 1개 비용으로 우회 제거·mutate/delete가 스냅샷 직접 매칭이라 요청별 해소 불요(서비스 단순화)·dangling 완전 관리(항상 외부키 보유·조회/삭제 가능).
- 축3 저장 구조 — 이중 보유(내부 variant_id FK 존치 + variant_public_id 스냅샷). variant_id는 enrich 조인·UK 중복담기 판정 유지, public_id는 외부 대상키. 단일 대체 불가(enrich·UK가 내부 id 의존).

### §2 결정 라운드 재진입·수렴
- 실측 게이트: cart 계약 표면(요청 4·응답 2·서비스·리포·핸들러 경계) MCP read-only 실측 후 결정. recon-report-74가 축2(dangling) 과소평가 → 결정 라운드서 b-i/b-ii 분기 추가·b-i 수렴.
- 경계 사수: deleteByUserIdAndVariantIdIn은 외부 removeItems와 내부 CartOrderPlacedHandler(OrderItem 내부 variantId) 공용 → 외부만 publicId 전환·내부 메서드 존치로 주문→cart 정리 무회귀.

### §3 배선 실측
- V17: ADD variant_public_id CHAR(30) NULL AFTER variant_id → UPDATE JOIN product_variant 백필(native·@SQLRestriction 무관·dangling 포함) → MODIFY NOT NULL. charset utf8mb4_unicode_ci 상속(cart_item 기본·V1:542)·product_variant.public_id(V1:456) 동일 collation 조인 정합.
- CartItem: @JdbcTypeCode(CHAR) variant_public_id(updatable=false·LT-01)·create() 4인자·null 가드.
- CartItemRepository: findByUserIdAndVariantPublicId·deleteByUserIdAndVariantPublicIdIn 신설. findByUserIdAndVariantId(addItem 누적·UK)·deleteByUserIdAndVariantIdIn(핸들러) 존치.
- CartService: addItem findByPublicId(404 ProductVariantNotFoundException 보존)→variant.getId() 누적 체크→insertNew에 variant.getPublicId() 스냅샷 저장. toView item.getVariantPublicId() 노출(enrich 조인은 내부 id). changeQuantity·setSelected·removeItems publicId 매칭.
- DTO: 요청 Add·QuantityUpdate·Select(variantPublicId @NotBlank @Size(max=30))·Delete(List<@NotBlank String> @NotEmpty)·SelectAll 무변경. 응답 CartItemView·CartItemAddResponse 식별필드 publicId.
- 검증: --rerun-tasks(캐시 불인정) 816 tests GREEN(0 실패)·1m6s. cart 8클래스 49테스트(CartItemRepositoryTest 7→9). 6지점 MCP 대조(V17·CartService·CartItem·Repository·CartItemView + 리포 경계).

### §트랩 실측
- CHAR(30) 매핑(LT-01 재확인): 스냅샷 컬럼 variant_public_id에 @JdbcTypeCode(SqlTypes.CHAR) 미부여 시 Hibernate VARCHAR 매핑 → 트레일링 공백 불일치로 파생 쿼리 미스. public_id 관례 동일 적용 필수.
- dangling 스냅샷 노출(설계 근거): cart 외부키를 variant 테이블서 실시간 해소하면 soft-delete 시 @SQLRestriction에 걸려 응답 null·삭제 불가(기능 회귀). cart_item 스냅샷 소유로 dangling도 항상 외부키 보유. 백필 UPDATE도 native 조인이라 soft-delete variant 참조행까지 채움.
- 공용 삭제 메서드 경계: deleteByUserIdAndVariantIdIn 외부/내부 공용 → 외부 계약만 publicId 전환·내부 이벤트 경로 메서드 존치. 일괄 치환 시 주문→cart 정리 파손.

### §8 carry-over (이월)
1. CartItemAddResponse.userId(Long) 내부 PK 노출: 이번 범위 밖(seam 무관·FE 미소비). 외부 계약 정합 후속서 재론 여부.
2. 이미지 저장 form(절대/상대) 미확정: FE-10 렌더 시 seed 실측(recon-74 §1).
3. 인라인 Javadoc 드리프트(CartService "existsById로만"·CartItemView "대상키는 variantId"·CartController 대상키 서술) 본 커밋서 교정.

---

## D-150 (Track 66·BE) 장바구니 체크아웃 멱등 replay 빈 카트 가드 정합

장바구니 체크아웃(POST /api/v1/cart/checkout)에서, 주문 성공 후 동일 Idempotency-Key로 재요청 시 빈 카트 가드가 CheckoutService 위임 전에 422를 던져 멱등 계약(replay→cached 200)을 위반하던 잠재 버그를 해소한다. CartCheckoutService의 빈 카트 선가드를 멱등 인지형으로 교체한다.

### §1-A 채택·기각 근거

- **채택 A — 멱등 인지형 빈 카트 가드**: `selectedItems.isEmpty() && !isIdempotentReplay(buyerId, key)`로 가드를 교체한다. selected가 비어 있어도 `(buyerId, key)` 멱등 레코드가 존재하면 throw 없이 빈 `itemCommands`로 CheckoutService에 위임한다 → CheckoutService가 items 접근 전에 cached/복구를 short-circuit 처리해 멱등 계약(replay→cached 200)을 보존한다. `OrderIdempotencyKeyRepository`를 read 용도로 주입하며 `findByBuyerIdAndIdempotencyKey`를 CheckoutService와 재사용한다. `isIdempotentReplay`는 key가 null/blank면 false(매 요청 신규·§8)로 진짜 빈 카트를 구분한다. `@Transactional`·소진 로직은 추가하지 않는다.
- **기각 B — 동기 deleteAll 소진 추가**: 소진은 이미 `CartOrderPlacedHandler`(D-75·D-126)가 `OrderPlaced`를 AFTER_COMMIT·REQUIRES_NEW로 소비해 `deleteByUserIdAndVariantIdIn`으로 담당한다. CartCheckoutService에 동기 `deleteAll`을 넣으면 이중화·죽은 로직이 되어 과잉개발 회피·최소변경 원칙에 반한다.
- **기각 C — 핸들러 제거·동기 소진 일원화**: 이벤트 소진은 Buy Now·Cart Checkout 통합 정책(D-126)과 재실행 멱등(LT-05·HARD DELETE 0행 무해)으로 이미 정합하다. 핸들러를 제거하면 Buy Now 경로의 소진까지 상실하고 불필요한 회귀를 유발한다.

### §2 결정 라운드 재진입

최초 진단("CartCheckoutService가 주문만 만들고 cart는 미변경")은 오류였다. 실측 대조 결과 소진은 `OrderService.createOrder`가 발행하는 `OrderPlaced`(E1)를 `CartOrderPlacedHandler`가 소비해 이미 수행하고 있었다(CartOrderPlacedEventIntegrationTest로 소진·멱등·비대상 잔존 검증 완료). 이에 결정 대상이 "소진 추가"에서 "멱등 replay 계약 위반 해소"로 재정의되었다. 진짜 결함은 소진 부재가 아니라, 소진 완료 후 동일 Idempotency-Key 재요청 시 빈 selected가 빈 카트 가드(422)를 CheckoutService 위임 전에 유발해 replay가 cached 200 대신 422를 받던 점이었다.

### §8 carry-over

- 수정 파일 2개: `cart/service/CartCheckoutService.java`(멱등 인지 가드·idempotencyRepository read 주입), `cart/service/CartCheckoutServiceTest.java`(idempotencyRepository mock·5케이스: 신규 위임·cached 위임·genuine empty[key 없음]·genuine empty[레코드 없음]·replay-after-consume 위임).
- 검증: `./gradlew.bat test --rerun-tasks` BUILD SUCCESSFUL, CartCheckoutServiceTest 5/5 GREEN, 전체 무회귀.
- 소진 실검증은 기존 `CartOrderPlacedEventIntegrationTest`가 담당(본 트랙에서 재검증하지 않음).
- 정찰 방식 전환: 이후 정찰은 Claude Code + 산출물 생성 기본(본 트랙에서 MCP 정찰이 이벤트 소진 경로를 누락한 것이 계기).
- 다음: FE-11 체크아웃 착수.

---

## D-151 (Track 67·BE) 장바구니 소진 시점 이동: OrderPlaced → PaymentCompleted

카트 소진(HARD DELETE)을 주문 생성(OrderPlaced) 시점에서 결제 완료(PaymentCompleted) 시점으로 이동한다. "결제 확정 전에는 카트를 비우지 않는다" 원칙을 충족해, 주문은 생성됐으나 결제가 실패/취소/중단된 경우 카트가 이미 비워지던 결함을 해소한다. CartOrderPlacedHandler를 CartPaymentCompletedHandler로 rename하고 구독 이벤트 타입만 교체한다(본문 삭제 로직·AFTER_COMMIT+REQUIRES_NEW 트랜잭션 정책 무변경).

### §1-A 채택·기각 근거
- 채택 해법1 (소진 시점 이동): PaymentCompleted(E2·orderId 페이로드 보유)가 이미 존재하고, 핸들러가 event.orderId()로 Order 재조회해 buyerId·variantIds를 얻는 방식이라 본문 무변경. 형제 InventoryPaymentCompletedHandler가 예약(OrderPlaced)→확정(PaymentCompleted)/해제(PaymentFailed) 2단계 모델을 선례로 확립. 핸들러 1개 구독 타입 교체로 결함 해소·최소 변경. OrderPlaced 잔여 소비처(Inventory 예약·Notification)는 무영향.
- 기각 해법2 (소진 유지 + 실패/취소 시 복원): 복원 로직·삭제 전 스냅샷·취소 트리거 이벤트 3종 신설 필요. 특히 PAID→CANCELLED는 이벤트 미발행(Payment.cancel 상태 전이만·환불 흐름 Track 5 이연)이라 복원 트리거 확보 자체가 막힘. 과잉개발(§4)·현시점 실현 불가.

### §2 결정 라운드 재진입
- 계기: FE-11 결제 흐름 구현 중, checkout 201 직후 카트가 이미 소진되는 결함을 사용자가 브라우저 흐름에서 실측 발견("결제 안 했는데 장바구니 비워짐").
- 실측 게이트: recon-report-fe-11-cart-timing(해법 실현성 Q1~Q5)·recon-report-track-67(rename·참조·테스트·박제 범위 Q1~Q6) 2건 Claude Code 정찰.
- T3(재발행 멱등 통합 테스트) 제거: PaymentCompleted 재발행 시 동기 형제 OrderEventHandler.markPaid(@EventListener·동일 TX)가 ORDERED→PAID 단방향 가드로 2회차를 거부 → TX 롤백 → AFTER_COMMIT 미발화 → 카트 핸들러 미실행. 즉 "카트 핸들러 재발행 멱등"은 프로덕션 도달 불가 경로이며 실 이중발행 검증이 markPaid와 충돌. 도달 불가 시나리오 제거로 커버리지 손실 없음(소진·형제통합·비대상잔존은 T1·T2·T4 커버).

### §트랩 실측 (LT 후보)
- PaymentCompleted 동기 markPaid 형제 얽힘: OrderPlaced와 달리 PaymentCompleted는 동기 소비자 OrderEventHandler(markPaid·ORDERED→PAID 단방향)를 가진다. 실 이중발행 시 2회차가 markPaid 불법 전이로 폭발·TX 롤백. 따라서 PaymentCompleted 구독 핸들러의 멱등/재발행 검증은 실 이중발행이 아니라 단위(handle 직접 호출)로 격리하거나, 도달 불가면 생략한다. AFTER_COMMIT 형제는 markPaid 커밋 후에만 발화하므로 재발행 시 아예 실행되지 않는다.

### §8 carry-over
- 수정: cart/handler/CartPaymentCompletedHandler.java(신규·CartOrderPlacedHandler 삭제)·테스트 2건 rename+개정(단위 4케이스·통합 T1/T2/T4)·주석 3곳 정정(CartItemRepository·CartCheckoutService·OrderPlaced.java "CartItem 소비" 제거).
- 검증: --rerun-tasks 818 GREEN·무회귀·1m1s.
- 행동 변화(의도된 결과): 카트가 주문 생성~결제 완료(PENDING window) 동안 유지됨. 다른 탭에서 결제 전까지 품목 노출. 원칙과 일치.
- 갱신 대상: D-126 "OrderPlaced 3번째 소비자(Cart)" 전제 → Cart 제외(Inventory·Notification 2소비처 유효). D-131 CartOrderPlacedHandler 참조 → CartPaymentCompletedHandler. D-150 기각 B/C 소진 경로 서술은 당시 정합·본 D-151로 이동. LT-05 "OrderPlaced 형제" → "PaymentCompleted 형제"(HARD DELETE 상태 무관 멱등 논지 유지).
- 미이동 잔여(표면화): 결제 실패 시 Order를 CANCELLED로 되돌리는 로직 부재(PaymentFailed 구독자는 Inventory 예약해제만)·PAID후취소 이벤트 미발행(Track 5 이연). 실패 후 Order는 ORDERED로 잔존. 별건 트랙 고려 대상.
- 다음: FE-11 STEP 2(모의 PG + webhook) 재개.

---

## D-152. 주문 단건 응답 OrderItemResponse에 표시용 productName 추가 (FE-12a)

### §1 결정
주문 상세 화면이 품목을 상품명으로 표시하도록 OrderItemResponse에 productName(표시용 enrich)을 추가한다.
OrderResponse.fromOrderWithItems는 이미 productById를 주입받아 조립 루프에서 product 객체를 확보하고 있으므로
(기존엔 product.getPublicId()만 사용), 같은 객체에서 name을 꺼내 노출한다. 추가 조회·Repository·도메인룰 0.
목록(OrderSummaryResponse.previewTitle)은 서버 조립 상품명을 이미 썼으나 단건은 productId만 노출하던 비대칭을 해소한다.

### §1-A 채택/기각 근거
- γ(채택): OrderItemResponse에 productName 추가. 서버가 목록에서 쓰던 productById(findByIdIn 단일 IN 조회) 패턴을
  단건 조립부가 이미 공유 중 → 재사용만으로 완결. 실변경 = record 필드 1개 + 조립 삼항 1줄 + 통합테스트 단언 1줄.
- β(기각): FE에서 품목별 GET /products/{id}로 enrich. 프론트가 Aggregate Join 책임(N+1·삭제상품 404·로딩·캐시)을
  영구 부담. 운영 복잡도 상승(기조 1 위배).
- α(기각): 상품명 미표시(productId만). 주문 상세 UX 빈약·실서비스 부적합.

### §2 삭제 상품 방어값
productById miss(삭제 상품) 시 productName=null → §15 NON_NULL 직렬화로 생략. 기존 productId 방어
(product != null ? product.getPublicId() : null)와 동형으로 productName도 (product != null ? product.getName() : null).
삭제 시 productId·productName 동반 생략 → FE는 productName 부재를 삭제 신호로 소비(빈 문자열 대신 null 채택:
정상 상품(이름 빈값)과 삭제 상품 구분 보존). 삭제는 주문 스냅샷 존재로 실발생 희소하나 방어값은 명시.

### §8 carry-over
- 재결제 노출·PENDING_PAYMENT 주문 정리는 본 트랙 범위 밖(FE-12b 이관).
- 필드 순서: orderItemId, productId, productName, variantId, quantity, unitPrice, totalPrice.
- 검증: 818 tests GREEN·무회귀. CheckoutIntegrationTest 단건 seller 그룹 케이스에 productName 단언 통과.

---

