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

**박제 1줄**: "이벤트 핸들러 멱등 가드 표준은 패턴 5종(A canTransitionTo 자연 흡수·B Service primitive 가드·C type 분기 게이트·D catch+skip+log.warn·E 다단계 복합) 카탈로그 박제·신규 핸들러는 패턴 선택 사유를 Javadoc 1줄 의무 기록·NotificationLog 멱등 저장소는 Outbox 도입 시점 동반."

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

- `backend/src/main/java/com/zslab/mall/common/observability/EventMetricsRecorder.java` (또는 동등 구현) — `zslab.event.published`·`zslab.event.failed` 카운터 발화 위치 (TracedEventPublisher 내부 흡수 또는 별도 클래스)
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