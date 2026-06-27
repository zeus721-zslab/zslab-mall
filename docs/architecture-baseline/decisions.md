# architecture-baseline 결정 누적

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
| 교환 | ClaimCompleted EXCHANGE(E9) | 회수분 복구 + 교환품 신규 차감 (트랜잭션 분리) | RETURN(회수) + ORDER(재출고) |

- **M-11 처리**: InventoryHistory는 on_hand 변동만 기록. 예약/해제(reserved만 변동)는 컬럼 갱신만·History 미기록. change_type A분류(ORDER/CANCEL/RETURN/ADJUST/INBOUND/OUTBOUND)에 RESERVE/RELEASE 부재와 정합.
- **M-12 처리**: 교환은 change_type EXCHANGE 부재 → 회수=RETURN·재출고=ORDER 2건 분리 기록(A분류 enum 확장 회피).
- **M-13 처리**: 예약은 주문 생성과 동일 트랜잭션(동기) — oversell 방지.
- **M-14 처리**: 결제 만료 자동 예약 해제는 정책만 명시·타이머/배치는 구현 단계 이연.

**Why**: 결제 전 점유(예약)와 실물 차감(결제 후)을 분리해 oversell 방지·미결제 점유 회수 가능. 복구를 결제 전/후로 분기해 reserved/on_hand 정확히 환원.

**Impact**: 재고 차감/복구 핸들러는 멱등(OrderItem.item_status 가드). 교환은 복구·차감 2트랜잭션 분리로 부분 실패 시 보상 가능.

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

### D-26: AbstractPublicId* publicId 매핑 CHAR(30) 명시 (Track B 발견 #2 흡수)

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
4. 만료는 상태 전이 트리거가 아님 — 차단 해제 신호만(state-machine.md §1).
5. FAILED 행은 영구 보관(ARCHIVE). 결제 화면=PAID만 / 운영 화면=전체.

**근거**: 만료를 "차단 해제 신호"로만 한정해 상태 머신을 단순하게 유지. 결제 성립은 PG 수락 사실을 우선.

**영향 범위**: V3 `expires_at`·`failure_code`·`PaymentService.initiate`·`Payment.isExpired`.

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
