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
| PR-C | OrderService 확장 + BuyerOrderController 잔여 endpoint + E2E | S | 외부 검토 권장·결정 라운드 의무·앞선 산출물 위에서 안전 |

**진입 순서**: PR-A → PR-B → PR-C. PR-A·B 안착 후 PR-C 진입 시 회귀 표면 축소·결정 라운드 PR-C 집중 가능.

**정찰 보정 (2026-06-28·PR-A 진입)**: PR-A 범위는 회귀 테스트 박제 + Outdated 주석 보정으로 정정. OrderPlaced 핸들러 구현은 후속 트랙(NotificationLog·Inventory·CartItem·가드 2 A급) 자연 진입. 정찰 결과 CheckoutService.idempotentCheckout 내부 try-catch가 이미 4xx 삭제·5xx 잔류 정합·@Valid first-line defense로 ORD-1 등 IllegalArgumentException 도달 경로 차단. 회귀 테스트 3건(itemNotFound 404·itemMismatch 422·5xx 잔류)으로 정합 박제.

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
