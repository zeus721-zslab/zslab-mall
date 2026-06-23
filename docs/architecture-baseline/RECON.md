# PR-01 정찰 결과

> 소스: db-schema-decisions.md v2.3·ERD 01~05·marketplace-core-domain.md·buyer-grade.md·permission-uml.md·additional-entities.md
> 목적: aggregate-boundary.md·state-machine.md·ADR-001·ADR-003 작성 전 근거 수집

---

## 1. Aggregate 경계 후보

### 1.1 분할 기준 (4가지)

| 기준 | 설명 |
|---|---|
| 함께 생성 | Root 없이 존재할 수 없는 엔티티 |
| 함께 삭제 | Root 삭제 시 같이 사라지는 엔티티 |
| 트랜잭션 동반 | 항상 같은 트랜잭션 안에서 변경 |
| 독립 수정 불가 | Root를 거치지 않고 직접 변경 불가 |

### 1.2 Aggregate 분할 안 (17개)

| # | Aggregate | Root | 포함 엔티티 | 외부 ID 참조 |
|---|---|---|---|---|
| 1 | User | User | WithdrawnUser, BuyerProfile, UserAddress | BuyerGrade.id |
| 2 | Auth | Role | Permission, UserRole, RolePermission | User.id |
| 3 | BuyerGrade | BuyerGrade | GradePolicy | - |
| 4 | Seller | Seller | SellerBankAccount, SellerUser | User.id, Role.id |
| 5 | Settlement | Settlement | - (단독) | Seller.id, SellerBankAccount.id |
| 6 | Category | Category | - (self-ref 계층) | Category.id(parent) |
| 7 | Product | Product | ProductImage, ProductOptionGroup, ProductOptionValue, ProductVariant | Seller.id, Category.id |
| 8 | Inventory | Inventory | InventoryHistory | ProductVariant.id |
| 9 | CartItem | CartItem | - (단독) | User.id, ProductVariant.id |
| 10 | Order | Order | OrderItem, OrderShippingSnapshot | User.id, ProductVariant.id, Seller.id |
| 11 | Payment | Payment | - (단독) | Order.id |
| 12 | Delivery | Delivery | - (단독) | OrderItem.id |
| 13 | Claim | Claim | Refund | OrderItem.id, Payment.id |
| 14 | Code | CodeGroup | Code | - |
| 15 | Attachment | Attachment | - (단독, polymorphic) | target_type/target_id |
| 16 | AuditLog | AuditLog | - (단독, append-only) | actor_user_id |
| 17 | NotificationLog | NotificationLog | - (단독) | recipient_user_id |

> 총 37개 테이블 → 17개 Aggregate + Read Model 2건 (PR-03 이연) (db-schema-decisions.md §3 "29개" 표기는 오류 — 실제 37개)

### 1.2.1 Read Model 후보 (PR-03 이연)

본 PR 범위 외. PR-03 Read Model + Audit Policy 트랙에서 처리.

| # | 테이블 | 분류 근거 |
|---|---|---|
| R1 | BuyerPurchaseAggregate | db-schema §2.2 "Read Model" 명시·이벤트 핸들러로 갱신되는 파생 데이터 |
| R2 | SellerSalesDaily | db-schema §1.11·§2.8 "집계 테이블 (배치 갱신)" 명시 |

근거: baseline-plan.md §10 — Read Model PR-03 이연

### 1.3 경계 모호 3건 (사용자 확정 필요)

**[모호-A] SellerUser 귀속 위치**
- 본 안 (Seller 포함): permission-uml.md "Seller → 1:N SellerUser". Seller 컨텍스트에서 생성·삭제.
- 대안 (Auth 포함): SELLER_* Role을 Role Aggregate에서 관리하므로 권한 컨텍스트 통합 가능.
- 의존성: SellerUser는 seller_id·user_id·role_id 3개를 동시 참조. 어느 Aggregate도 완전 소유 불가.

**[모호-B] CartItem 귀속 위치**
- 본 안 (독립 Aggregate): User 트랜잭션과 무관하게 CartItem 조작. 주문 생성 시 CartItem 소비는 이벤트.
- 대안 (User 포함): 장바구니는 개념적으로 User의 일부. UserAddress와 대칭 처리 가능.
- 차이: User Aggregate에 포함하면 CartItem 조작 시 항상 User를 로드해야 함.

**[모호-C] Delivery 귀속 위치**
- 본 안 (독립 Aggregate): Delivery는 판매자/배송 컨텍스트에서 독립 갱신. OrderItem.id 외부 참조.
- 대안 (Order 포함): 배송은 주문의 하위. Order Aggregate 트랜잭션 일관성.
- 차이: OrderItem 1:N Delivery(부분 배송 지원)이므로 Order에 포함 시 Aggregate 비대화 위험.

---

## 2. State Machine 대상 4건

### 2.1 Payment (A분류 — 값 집합 확정)

```
PENDING ──→ PAID ──→ CANCELLED
        ↘ FAILED
```

| 상태 | 진입 조건 | 롤백 |
|---|---|---|
| PENDING | Payment 행 생성 시 | - |
| PAID | PG 결제 성공 콜백 | - |
| FAILED | PG 결제 실패 콜백 | 재시도 = 새 Payment 행 생성 |
| CANCELLED | Claim 환불 처리 완료 (Refund.COMPLETED) | 불가 |

> Payment.method (CARD·BANK·VBANK·KAKAO): A분류 확정. 확장 시 마이그레이션.

### 2.2 Claim (A분류 — 값 집합 확정)

```
REQUESTED ──→ APPROVED ──→ COMPLETED
          ↘ REJECTED
```

| 상태 | 진입 조건 | 롤백 |
|---|---|---|
| REQUESTED | 구매자 취소/반품/교환 요청 | - |
| APPROVED | 관리자/판매자 승인 | - |
| REJECTED | 관리자/판매자 거절 | 재요청 = 새 Claim 행 생성 (정찰 의견) |
| COMPLETED | 환불/수거/교환발송 완료 처리 | 불가 |

Claim.type별 COMPLETED 조건:
- CANCEL: Refund.status = COMPLETED
- RETURN: 수거 확인 + Refund.status = COMPLETED
- EXCHANGE: 수거 확인 + 교환품 발송 완료 (별도 Delivery)

### 2.3 Order.status (B분류 — 값 집합 DDL 보류, 확정 필요)

ERD 04: `enum status "Code 참조 (B분류)"`. db-schema-decisions.md §2.6 패턴 코드: `PAID, READY, SHIPPING, DELIVERED, CONFIRMED, ...`

후보 값 집합 (7 + 선택 1):

| 값 | 설명 | 진입 조건 |
|---|---|---|
| PENDING_PAYMENT | 결제 전 | Order 생성 직후 |
| PAID | 결제완료 | Payment.PAID → 모든 OrderItem PAID |
| PREPARING | 준비중 | 최초 OrderItem PREPARING |
| SHIPPING | 배송중 | 최초 OrderItem SHIPPING |
| DELIVERED | 배송완료 | 모든 OrderItem DELIVERED |
| CONFIRMED | 구매확정 | 모든 OrderItem CONFIRMED |
| CANCELLED | 취소 | 모든 OrderItem CANCELLED |
| [선택] PARTIAL_CANCEL | 부분취소 | 일부 CANCELLED·나머지 CONFIRMED |

> PARTIAL_CANCEL 필요 여부: 한 주문 내 일부 취소 후 나머지가 구매확정되는 케이스 발생 가능.
> 운영 화면에서 "부분 취소 주문" 필터 필요 시 별도 상태 유용. 없을 경우 Order.status = CONFIRMED로 통합.

### 2.4 OrderItem.item_status (B분류 — 값 집합 DDL 보류, 확정 필요)

ERD 04: `enum item_status "Code 참조 (B분류)"`. 값 집합 미확정.

후보 값 집합 (11개):

| 값 | 설명 | 진입 조건 |
|---|---|---|
| ORDERED | 주문됨 | OrderItem 생성 직후 |
| PAID | 결제완료 | Payment.PAID 처리 |
| PREPARING | 준비중 | 판매자 출고 준비 |
| SHIPPING | 배송중 | Delivery 등록 + SHIPPING |
| DELIVERED | 배송완료 | Delivery.DELIVERED |
| CONFIRMED | 구매확정 | 구매자 확정 또는 자동 확정(예: 배송 후 N일) |
| CANCEL_REQUESTED | 취소요청 | Claim(CANCEL) REQUESTED |
| CANCELLED | 취소완료 | Claim(CANCEL) COMPLETED |
| RETURN_REQUESTED | 반품요청 | Claim(RETURN) REQUESTED |
| RETURNED | 반품완료 | Claim(RETURN) COMPLETED |
| EXCHANGE_REQUESTED | 교환요청 | Claim(EXCHANGE) REQUESTED |

> EXCHANGED (교환완료) 상태 추가 필요 여부: 교환 처리 후 최종 상태. 추가 시 12개.
> 교환 완료 후 OrderItem이 CONFIRMED와 동등 처리라면 CONFIRMED 재사용 가능.

---

## 3. OrderItem.item_status ↔ Order.status 동기화 규칙 후보

(baseline-plan.md §9 보류 결정 항목 #3)

OrderItem이 실제 상태, Order.status는 OrderItem 집계 캐시 (baseline-plan.md §4 결정 1 확정).

**방식 A: 우선순위 기반 집계**
```
우선순위: ORDERED < PAID < PREPARING < SHIPPING < DELIVERED < CONFIRMED
Order.status = 모든 OrderItem 중 가장 낮은 우선순위 상태
```
- 장점: 규칙 단순. 코드 1줄.
- 단점: 부분 취소·반품 케이스에서 Order.status 계산이 직관적이지 않음.
  예: 한 OrderItem이 RETURNED인 경우 우선순위 계산 불명확.

**방식 B: 명시적 전이 조건 (권장)**
```
Payment.PAID → 모든 OrderItem = PAID → Order.status = PAID
최초 OrderItem = PREPARING → Order.status = PREPARING
최초 OrderItem = SHIPPING → Order.status = SHIPPING
모든 OrderItem = DELIVERED → Order.status = DELIVERED
모든 OrderItem ∈ {CONFIRMED, CANCELLED, RETURNED} → Order.status = CONFIRMED
모든 OrderItem = CANCELLED → Order.status = CANCELLED
```
- 장점: 케이스별 의도가 명확. 예외 처리 위치 분명.
- 단점: 전이 조건 증가 시 매트릭스 관리 필요. Service.canTransition() 복잡도 증가.

**정찰 의견**: 방식 B 권장. 쇼핑몰 운영 중 CS 케이스별 Order.status 조회가 필요하며,
명시적 조건이 없으면 엣지 케이스(교환 후 상태 등)에서 버그 발생 가능성 높음.

---

## 4. 불확실·모호 항목

| # | 항목 | 출처 | 확정 필요 |
|---|---|---|---|
| M-01 | Order.status 정확한 값 집합 | §2.3 | PR-01 |
| M-02 | OrderItem.item_status 정확한 값 집합 | §2.4 | PR-01 |
| M-03 | PARTIAL_CANCEL 상태 필요 여부 | §2.3 | PR-01 |
| M-04 | EXCHANGED 상태 별도 필요 여부 | §2.4 | PR-01 |
| M-05 | SellerUser 귀속 Aggregate | §1.3 모호-A | PR-01 |
| M-06 | CartItem 귀속 Aggregate | §1.3 모호-B | PR-01 |
| M-07 | Delivery 귀속 Aggregate | §1.3 모호-C | PR-01 |
| M-08 | buyer-grade.md의 grade_changed_reason 존재 vs db-schema §2.1 제거 | 불일치 | 확인 필요 |
| M-09 | db-schema-decisions.md §3 "총 29개" → 실제 37개 테이블 (오류 의심) | 오류 | 확인 후 수정 |
| M-10 | REJECTED Claim 재요청 처리: 새 Claim 행 생성 vs 기존 Claim 상태 변경 | §2.2 | PR-01 |

---

# PR-02 정찰

> 소스: baseline-plan.md §4 결정 2·§8·§9 #1·aggregate-boundary.md·state-machine.md·decisions.md(D-01·D-04·D-05)·db-schema-decisions.md §2.4/§1.11/§2.5·ERD 03/04
> 목적: domain-events.md·inventory-policy.md·ADR-005 작성 전 근거 수집

---

## 1. 도메인 이벤트 후보

### 1.1 발행 원칙 (정찰)

aggregate-boundary.md §1: Aggregate간 참조는 ID만, 다른 Aggregate 변경은 **도메인 이벤트 또는 Application Service**로 처리.
→ Aggregate 트랜잭션 경계를 넘는 상태 변경점이 이벤트 후보.

발행 주체는 반드시 Aggregate Root (17개 중 하나).

### 1.2 경계를 넘는 변경점 인벤토리 (state-machine.md 전이 매핑)

| # | 이벤트 후보 | 발행 주체(Root) | 트리거(State 전이) | 소비 주체 | 동기/비동기 후보 |
|---|---|---|---|---|---|
| E1 | OrderPlaced | Order | Order/OrderItem 생성(ORDERED) | Inventory(예약)·CartItem(소비)·Notification | 예약=동기 / Cart·알림=비동기 |
| E2 | PaymentCompleted | Payment | Payment PENDING→PAID | Order(OrderItem PAID·status 재계산)·Inventory(차감)·Notification | Order·재고=동기 / 알림=비동기 |
| E3 | PaymentFailed | Payment | Payment PENDING→FAILED (+ 결제 만료) | Inventory(예약 해제) | 동기 |
| E4 | DeliveryStarted | Delivery | Delivery →SHIPPING | Order(OrderItem SHIPPING·status 재계산) | 동기 |
| E5 | DeliveryCompleted | Delivery | Delivery →DELIVERED | Order(OrderItem DELIVERED·status 재계산) | 동기 |
| E6 | PurchaseConfirmed | Order | OrderItem →CONFIRMED | Settlement(정산 대상)·Read Model(PR-03 소비) | 비동기 |
| E7 | ClaimRequested | Claim | Claim →REQUESTED | Order(OrderItem *_REQUESTED) | 동기 |
| E8 | ClaimRejected | Claim | Claim →REJECTED | Order(OrderItem 원상 복귀) | 동기 |
| E9 | ClaimCompleted | Claim | Claim →COMPLETED | Order(OrderItem CANCELLED/RETURNED/EXCHANGED)·Inventory(복구)·Payment(CANCELLED via Refund)·Notification | 재고·Order·결제=동기 / 알림=비동기 |
| E10 | InventoryAdjusted (선택) | Inventory | 운영자 입고/조정(INBOUND/OUTBOUND/ADJUST) | Notification(품절 해제 등) | 비동기 |

**내부 전이(이벤트 아님)**: OrderItem →PREPARING(Order 내부 status 재계산)·Claim →APPROVED(Claim 내부)는 Aggregate 내부 invariant 유지로 처리. 별도 도메인 이벤트 불필요.

**경고(state-machine.md §6 경계)**: E4·E5는 Delivery.status(READY/SHIPPING/DELIVERED, ERD 04 기정의값)를 **트리거로 참조**만 함. Delivery 상태 전이 규칙 자체는 정의하지 않음(state-machine.md §6 이연 유지). OrderItem SHIPPING/DELIVERED 진입조건이 PR-01에서 이미 Delivery 상태를 참조하므로 정합. → 사용자 보고 항목.

### 1.3 멱등성·재시도 후보

- **멱등성 키 후보**: PG 콜백(E2)은 중복 수신 가능 → `pg_tid` 또는 event_id를 멱등성 키로. 재고 차감/복구는 OrderItem.item_status 가드(이미 PAID면 재차감 skip)로 멱등 확보.
- **재시도 후보**: 비동기 이벤트(알림·Read Model)는 재시도(지수 백오프)·최대 N회 후 DLQ. 동기 이벤트는 트랜잭션 롤백으로 처리.

---

## 2. Inventory SoT 후보

### 2.1 단일 SoT 확인 (정찰)

- ERD 03·db-schema §2.4 확인: **Product·ProductVariant에 stock/재고 컬럼 없음**. 재고는 Inventory 테이블 단독 보유. → Inventory = 단일 SoT 확정 가능.
- Inventory : ProductVariant = 1:1 (ERD 03 `ProductVariant ||--|| Inventory`).

### 2.2 3컬럼 관계 (db-schema §2.4)

| 컬럼 | 의미 | 갱신 시점 후보 |
|---|---|---|
| quantity_on_hand | 실물 보유 수량 | 차감(결제)·복구(취소/반품)·입고/조정 |
| quantity_reserved | 주문 점유(예약) 수량 | 예약(주문 생성)·해제(결제 실패/만료)·차감 시 감소 |
| quantity_available | 판매가능 캐시 = on_hand − reserved | on_hand·reserved 변경 시 재계산 |

판매가능 판정(ERD 03 설계메모): ① status≠SALE → 비노출 ② quantity_available≤0 → 품절 ③ is_soldout_manual → 강제품절.

### 2.3 갱신 방식 비교 (baseline-plan §9 #1)

| 방식 | 장점 | 단점 |
|---|---|---|
| 애플리케이션 갱신 (권장) | 디버깅 용이·트랜잭션 경계 명확·테스트 가능 | 갱신 누락 위험(코드 규율 필요) |
| DB 트리거 | 누락 불가·원자성 | 디버깅 난이도↑·이식성↓·숨은 로직 |

→ baseline-plan §9 #1·db-schema §4-1 모두 **애플리케이션 갱신 권장**.

---

## 3. 재고 복구 시점 후보 (state-machine.md §6 의거)

state-machine.md §6: "재고 복구 시점(CANCELLED/RETURNED 후) → PR-02 inventory-policy.md".

| OrderItem 전이 | 결제 시점 | 재고 동작 후보 | InventoryHistory change_type |
|---|---|---|---|
| CANCELLED (결제 전) | PENDING_PAYMENT | quantity_reserved −= qty (예약 해제) | 미기록(on_hand 불변) |
| CANCELLED (결제 후) | PAID 이후 | quantity_on_hand += qty (복구) | CANCEL |
| RETURNED | 배송 후 | quantity_on_hand += qty (검수 통과 시) | RETURN |
| EXCHANGED | 배송 후 | 회수분 복구 + 교환품 신규 차감 | RETURN(회수) + ORDER(재출고) |

---

## 4. 불확실·모호 항목

| # | 항목 | 출처 | 처리 |
|---|---|---|---|
| M-11 | InventoryHistory가 reserved 변동(예약/해제)도 기록하는가 | §3 | 제안: on_hand 변동만 기록. change_type A분류(ORDER/CANCEL/RETURN/ADJUST/INBOUND/OUTBOUND)에 RESERVE/RELEASE 없음 → 예약/해제는 reserved 컬럼만 갱신·History 미기록 |
| M-12 | 교환(EXCHANGE) change_type 매핑 | §3 | 제안: change_type EXCHANGE 부재 → 회수=RETURN·재출고=ORDER 2건 분리 기록 (A분류 enum 확장 회피) |
| M-13 | 재고 예약을 주문 생성과 동일 트랜잭션(동기)으로 묶는가 | §1.2 E1 | 제안: 동기 (oversell 방지 — eventual consistency는 초과판매 위험) |
| M-14 | 결제 만료(미결제 자동 취소) 타이머로 예약 자동 해제 | §1.2 E3 | 제안: 정책만 명시·실제 타이머/배치는 구현 단계 이연 |
| M-15 | E4·E5 Delivery 상태 트리거 참조가 state-machine.md §6(Delivery 전이 이연)와 충돌하는가 | §1.2 경고 | 제안: 트리거 참조만 허용(전이 규칙 미정의)·사용자 확정 필요 |

---

# PR-03 정찰

> 소스: baseline-plan.md §5/§8/§9 #2/§10·aggregate-boundary.md §3·state-machine.md·domain-events.md(E2·E6)·decisions.md(D-01~D-09)·inventory-policy.md·ADR-001/003/005·db-schema-decisions.md §1.7/§1.8/§1.11/§2.1/§2.2/§2.7/§2.8/§1.13·ERD 05
> 목적: read-model.md·audit-policy.md·deletion-policy.md·ADR-006 작성 전 근거 수집

---

## 1. Read Model 후보 인벤토리

### 1.1 BuyerPurchaseAggregate (이벤트 핸들러 갱신)

| 항목 | 내용 |
|---|---|
| 컬럼(db-schema §2.2) | buyer_id PK·lifetime_purchase_amount·last_ordered_at·updated_at |
| 원천(Write Model) | Order/OrderItem (CONFIRMED 항목 total_price 합) |
| 갱신 트리거 | **domain-events.md E6 PurchaseConfirmed**(OrderItem→CONFIRMED·발행 주체 Order) |
| 집계 키 | buyer_id |
| 집계 단위 | 구매확정 누적 (lifetime) |
| 갱신 시점 | E6 수신 시 즉시(이벤트 핸들러)·lifetime += confirmed total·last_ordered_at 갱신 |
| 재계산 가능 | 가능 — Order/OrderItem(CONFIRMED) 원천으로 SUM 재집계(이벤트 유실 시 배치 보정) |
| 후속 사용 | GradeEvaluator → BuyerProfile.grade_id (db-schema §2.2) |

> **정합 노트**: db-schema §2.2는 "Order COMPLETED 이벤트" 표기. PR-01에서 Order.status에 COMPLETED 값 부재·CONFIRMED로 확정(D-02). domain-events.md E6 PurchaseConfirmed가 정합 트리거. 등급 산정 원천은 BuyerProfile 미보유(lifetime은 Aggregate 단독 보유).

### 1.2 SellerSalesDaily (배치 갱신)

| 항목 | 내용 |
|---|---|
| 컬럼(db-schema §2.8) | seller_id·sale_date 복합 PK·order_count·gross_amount·refund_amount·net_amount |
| 원천(Write Model) | OrderItem(seller_id별 매출)·Refund(환불 차감) |
| 갱신 방식 | db-schema §1.11·§2.8 "집계 테이블(배치 갱신)" 명시 — 일 1회 마감 집계 |
| 집계 키 | (seller_id, sale_date) |
| 집계 단위 | 판매자 × 일자 |
| 재계산 가능 | 가능 — 특정 일자 재배치(idempotent upsert) |

> **배치 vs 이벤트(M-16)**: db-schema는 배치 명시. refund_amount·net_amount는 당일 확정값 마감이 필요하므로 일 마감 배치가 적합. 이벤트 즉시 집계는 환불·취소가 같은 날 섞일 때 중간 상태 노출 위험.

### 1.3 추가 Read Model 후보 (VIEW — db-schema §1.11)

| 뷰명 | 용도 | 방식(§1.11) |
|---|---|---|
| vw_seller_sales_monthly | 월간 매출(SellerSalesDaily 30개 GROUP BY) | 즉시 집계 VIEW (SellerSalesMonthly 테이블 폐기 대체) |
| vw_order_admin | 주문 관리 화면(단순 JOIN·조건·페이징) | MERGE VIEW |
| vw_seller_dashboard | 판매자 대시보드 | MERGE VIEW |
| vw_buyer_grade_history | 등급 변경 이력(grade_changed_reason 제거 대체·db-schema §2.1) | VIEW (AuditLog 기반) |

> VIEW는 테이블이 아닌 조회 파생. 본 트랙은 뷰명·용도·방식만 분류. 실제 SQL·ALGORITHM·인덱스는 PR-04/구현 이연(§5 배제).

### 1.4 Write↔Read 동기화 패턴 (db-schema §1.11)

| 케이스 | 방식 | 본 도메인 매핑 |
|---|---|---|
| 단순 JOIN·조건·페이징 | MERGE VIEW | vw_order_admin·vw_seller_dashboard |
| GROUP BY·대량 집계 | 집계 테이블(배치) 또는 즉시 집계 VIEW | SellerSalesDaily(배치)·vw_seller_sales_monthly(VIEW) |
| 이벤트 기반 실시간 집계 | Aggregate 테이블(이벤트 핸들러) | BuyerPurchaseAggregate(E6 핸들러) |

---

## 2. Audit Policy 후보

### 2.1 AuditLog 현재 정의 (db-schema §2.7·ERD 05)

- 컬럼: public_id·actor_user_id(nullable)·actor_role·action·target_type(polymorphic D분류)·target_id·diff_json·ip_address·user_agent·created_at
- FK 없음(논리 참조만)·append-only(수정·삭제 없음·비식별화 후에도 정합 유지).

### 2.2 diff_json 컬럼 타입 후보 (baseline-plan §9 #2)

| 타입 | 비고 |
|---|---|
| JSON (권장) | MariaDB에서 JSON = LONGTEXT alias + CHECK(JSON_VALID). 함수 질의(JSON_EXTRACT)·유효성 보장 |
| LONGTEXT | 검증 없음·질의 불편 |

→ baseline-plan §9 #2·ERD 05 설계메모 모두 **JSON 권장**. 본 PR 확정 대상.

### 2.3 추적 액션 인벤토리 (db-schema §1.13 A분류 #18)

- AuditLog.action = CREATE / UPDATE / DELETE / APPROVE / REJECT / LOGIN / LOGOUT (A분류 잠금·확장 시 마이그레이션).

### 2.4 diff_json 기록 범위 후보 (M-17)

- ERD 05 "변경 전후 JSON". changed_fields는 diff_json 내부 키로 표현 가능.
- 후보 구조: `{ changed_fields: [...], before: {...}, after: {...} }` — 변경 필드 한정 기록.
- 민감정보(비밀번호·결제 토큰·계좌번호·주민번호)는 diff에서 제외/마스킹.

### 2.5 적용 우선 대상 테이블 (운영자/시스템 행위 추적 필수)

| 대상 | 추적 사유 |
|---|---|
| Settlement (status·금액) | 정산 분쟁 — 금액·상태 변경 감사 |
| Seller.status | 판매자 정지·해지 운영 행위 |
| Product.status | 상품 승인/거부(APPROVE/REJECT) |
| SellerUser·UserRole·RolePermission | 권한 부여·회수 추적 |
| SellerBankAccount | 정산 계좌 변경(금전 직결) |
| BuyerProfile.grade | 등급 수동 변경(grade_changed_reason 제거→AuditLog 통일·db-schema §2.1) |

---

## 3. 삭제 정책 후보

### 3.1 db-schema §1.8 소프트 삭제 적용 범위

| 적용(8) | 미적용(상태 관리) |
|---|---|
| User, Seller, Product, ProductVariant, Category, Attachment, UserAddress, ProductImage | Order, OrderItem, Payment, Settlement, Claim, Refund, AuditLog, Inventory, InventoryHistory, 집계 테이블, CartItem |

> §1.8 주석: "주문·결제·정산은 삭제가 아니라 상태 관리로 처리".

### 3.2 분류 경계 정찰

- **SOFT DELETE**: §1.8 적용 8개. deleted_at·deleted_by·delete_reason(§1.7) 마킹·복구 가능.
- **HARD DELETE 후보**: CartItem(주문 소비/물리 삭제·보존가치 낮음·M-18)·권한 매핑(UserRole·RolePermission 회수 = 물리 삭제 + AuditLog·M-19). 세션·임시파일·검증토큰은 현재 ERD 미존재 → "도입 시 HARD" 정책만 명시(선제 테이블 생성 금지·§5).
- **삭제 불가(상태 관리·영구 보존)**: Order·OrderItem·Payment·Settlement·Claim·Refund·AuditLog·InventoryHistory·집계. 전자상거래법 보관·분쟁·감사 대응. → **3분류(SOFT/HARD/ARCHIVE)에 매핑 곤란(M-20)**.

### 3.3 소프트 삭제 vs 비식별화 분리 (db-schema §2.1)

- User 탈퇴 흐름: 탈퇴(withdrawn_at) → 로그인 차단 → 법정 보관 기간(WithdrawnUser.legal_retention_until·전자상거래법 5년) 유지 → 배치 → 비식별화(anonymized_at).
- 비식별화: email→NULL·phone→HASH·name→NULL. 식별자(user_id·order_id)는 유지(정합성 보존).
- **개념 분리**: 소프트 삭제(deleted_at/withdrawn_at·복구 가능) ≠ 비식별화(anonymized_at·불가역 개인정보 파기). 두 축을 분리해야 "탈퇴했으나 법정 보관 중" 상태를 표현 가능.

---

## 4. 불확실·모호 항목

| # | 항목 | 출처 | 처리 제안 |
|---|---|---|---|
| M-16 | SellerSalesDaily 갱신 = 배치 vs 이벤트 | §1.2 | 배치(일 마감) — db-schema 명시·환불 마감 정확성. BuyerPurchaseAggregate만 이벤트 |
| M-17 | diff_json 기록 범위 = 전체 vs changed_fields | §2.4 | changed_fields + before/after(변경 필드 한정)·민감정보 마스킹 |
| M-18 | CartItem 삭제 분류 = HARD vs SOFT | §3.2 | HARD — §1.8 소프트 미적용·주문 소비 시 물리 삭제·보존가치 낮음 |
| M-19 | 권한 매핑(UserRole·RolePermission) 삭제 분류 | §3.2 | HARD + AuditLog 기록(회수 이력은 감사 로그로) |
| M-20 | 삭제 불가(상태 관리) 테이블의 3분류 매핑 | §3.2 | **사용자 확정 필요**: (a) ARCHIVE에 "영구 보존(삭제 불가·상태 관리)" 포함 — 3분류 유지 / (b) 4번째 분류 RETAIN(상태 관리) 신설. 제안: (a) — 스펙 3분류 유지·ARCHIVE 정의에 "법정 보관·상태 관리 영구 보존" 명시. "별도 콜드 스토리지 이전"은 운영 이연 |

---

# PR-04 정찰

> 소스: CLAUDE-DEV.md·baseline-plan.md §5/§8/§9·db-schema-decisions.md §1.1/§1.4/§1.6/§1.7/§1.9/§3/§4·ERD 01~05·README·ADR-001/003/005/006·decisions.md D-01~D-12
> 목적: ddl-ready-checklist.md·index-strategy.md 작성 + db-schema §3 카운트 오류(M-09) 수정 전 근거 수집. 본 PR은 DDL "준비" — 실제 DDL·Flyway·테이블별 인덱스 명세는 다음 트랙(§5 배제).

---

## 1. DDL Ready 점검 항목 후보

### 1.1 글로벌 정책(db-schema §1) DDL 반영 점검

| 정책 | 출처 | DDL 반영 점검 |
|---|---|---|
| 내부 PK = BIGINT AUTO_INCREMENT | §1.1 | 전 테이블(집계 2건은 복합 PK) |
| public_id = ULID + prefix·UNIQUE | §1.1·ADR-001 | 부여 대상 한정(§1.1 11건)·타입/부여 불일치 발견(M-21·M-22) |
| 시간 = UTC·DATETIME(6)·timezone 컬럼 없음 | §1.2 | created_at·updated_at 등 전 시각 컬럼 |
| 금액 = BIGINT(KRW 정수)·DECIMAL 금지 | §1.3 | total_price·amount·*_amount·base_price 등 |
| 문자열 길이 표준(email 254·name 50·phone 20·title 200·url 2048·code 50·public_id CHAR(26)) | §1.4 | 무지성 VARCHAR(255) 금지 점검 |
| Charset/Collation = utf8mb4 / utf8mb4_unicode_ci | §1.5 | 테이블·컬럼 일괄 |
| 인덱스 명명 = pk_/fk_/uk_/ix_ | §1.6 | index-strategy.md 단일 소스 |
| Audit 컬럼(created_at·created_by·updated_at·updated_by, created_by/updated_by FK 없음) | §1.7 | 핵심 테이블 공통 |
| 소프트 삭제 컬럼(deleted_at·deleted_by·delete_reason) | §1.7·§1.8·D-12 | SOFT 분류 대상 한정 부착 |
| FK 적용 범위(강한 결합만·polymorphic/집계/created_by 미적용) | §1.9 | index-strategy FK 가이드와 정합 |

### 1.2 PR-00~03 확정 결정 DDL 반영 점검

| 결정 | DDL 반영 점검 항목 |
|---|---|
| D-02 Order.status 8값 / D-03 OrderItem.item_status 12값 | B분류 ENUM 컬럼 + Code 시드 일치(ORDER_STATUS·ORDER_ITEM_STATUS Code Group) |
| D-05 Payment·Claim 전이(A분류) | Payment.status·Claim.status·type ENUM 값집합 잠금 |
| D-04 동기화 방식 B | DDL 산출물 아님(이벤트 핸들러=구현)·체크리스트 인용만 |
| D-06 이벤트 E1~E10 | DDL 산출물 아님(발행/구독 인프라=구현)·체크리스트 인용만 |
| D-07~D-09 Inventory 3컬럼 + 애플리케이션 갱신 | quantity_on_hand·quantity_reserved·quantity_available 컬럼·트리거 미생성 확인 |
| D-10 Read Model 2 테이블 | BuyerPurchaseAggregate(buyer_id PK)·SellerSalesDaily((seller_id,sale_date) 복합 PK)·VIEW 4건은 DDL 트랙 |
| D-11 AuditLog diff_json | JSON 타입(= LONGTEXT + CHECK(JSON_VALID)) |
| D-12 삭제 분류 | SOFT 대상에만 deleted_at 부착·ARCHIVE/HARD는 미부착 |

### 1.3 보류 결정 3건(baseline-plan §9) 확정값 재확인

| # | 항목 | 확정값 | 출처 결정 |
|---|---|---|---|
| 1 | Inventory.quantity_available 갱신 방식 | 애플리케이션 갱신(DB 트리거 기각) | D-09·ADR-005 |
| 2 | AuditLog.diff_json 컬럼 타입 | JSON | D-11 |
| 3 | OrderItem.item_status ↔ Order.status 동기화 | 방식 B(명시적 전이) | D-04·ADR-003 |

> 3건 모두 PR-01~03에서 확정 완료. ddl-ready-checklist는 "재확인·인용"만 — 재논의 금지.

---

## 2. 인덱스 설계 후보

### 2.1 명명 규칙(db-schema §1.6 인용)

`pk_{table}` · `fk_{table}_{ref}` · `uk_{table}_{column}` · `ix_{table}_{column}` · 복합 `ix_{table}_{col1}_{col2}`. 본 PR은 규칙·패턴만, 테이블별 실제 인덱스 명세는 DDL 트랙 이연.

### 2.2 조회 패턴 인벤토리

| 구분 | 대표 패턴 | 후보 인덱스 키(예시) |
|---|---|---|
| 운영 화면 | Seller/Product 승인 대기·Claim 처리·Settlement 조회·AuditLog 조회 | Product.status·Seller.status·Claim.status·Settlement.(seller_id,status) |
| 판매자 대시보드 | 자사 주문 항목·일별 매출·재고 부족 | OrderItem.(seller_id,item_status)·SellerSalesDaily PK·Inventory.quantity_available |
| 구매자 화면 | 상품 검색·주문 내역·마이페이지 | Product.(category_id,status)·Order.(buyer_id,status)·CartItem.(user_id) |
| 정산 | 월간 집계·환불 차감 | SellerSalesDaily.(seller_id,sale_date)·Settlement.(period_start,period_end) |
| CS·감사 | 대상별·행위자별·기간 추적 | AuditLog.(target_type,target_id,created_at)·AuditLog.(actor_user_id,created_at) |

### 2.3 패턴별 인덱스 후보

| 종류 | 적용 |
|---|---|
| PK | 전 테이블 BIGINT AUTO_INCREMENT(집계 2건 복합 PK) |
| UK | public_id(부여 대상)·도메인 유니크 키(uk_user_email·uk_cartitem_user_variant·uk_variant_option_combo(4컬럼)·order_no) |
| FK | §1.9 강한 결합 관계(InnoDB FK는 자식 컬럼 인덱스 자동 생성)·polymorphic/집계/created_by 미적용 |
| 복합 | 조회 패턴별(ix_order_buyer_status·ix_order_item_seller_status·ix_product_category_status 등) |
| 커버링 | 고빈도 조회 SELECT 컬럼 포함 — 구현 단계 측정 후 확정 |

### 2.4 특수 인덱스 정책

| 정책 | 내용 |
|---|---|
| public_id(ADR-001) | 부여 대상에 UNIQUE 인덱스 필수(API/URL 조회 키)·ULID 시간정렬로 B-Tree 효율 |
| 소프트 삭제(ADR-006) | SOFT 대상 조회는 `deleted_at IS NULL` 가드 → deleted_at 단독/복합 인덱스. MariaDB는 partial index 미지원 → 일반 인덱스 또는 (status, deleted_at) 복합 |
| polymorphic | Attachment·AuditLog·NotificationLog의 (target_type, target_id) 복합 인덱스(FK 부재 보완) |
| Read Model | BuyerPurchaseAggregate(buyer_id PK·last_ordered_at)·SellerSalesDaily((seller_id, sale_date) 복합 PK) |

> MariaDB partial index(`WHERE deleted_at IS NULL`) 미지원 → ADR-006 "partial index"는 일반 인덱스로 대체. index-strategy에 명시.

---

## 3. db-schema §3 카운트 오류(M-09)

§3 헤딩 "총 **29개** (집계 1개 제외 시 28개)"는 오류. 카테고리별 합계는 이미 37개로 구성되어 헤딩 숫자만 불일치.

| 카테고리 | 수 | 테이블 |
|---|---|---|
| 회원·권한 | 9 | User, WithdrawnUser, BuyerProfile, UserAddress, Role, Permission, UserRole, RolePermission, SellerUser |
| 등급 | 3 | BuyerGrade, GradePolicy, BuyerPurchaseAggregate |
| 판매자 | 3 | Seller, SellerBankAccount, Settlement |
| 상품·재고 | 8 | Category, Product, ProductImage, ProductOptionGroup, ProductOptionValue, ProductVariant, Inventory, InventoryHistory |
| 주문·결제·배송 | 8 | CartItem, Order, OrderItem, OrderShippingSnapshot, Payment, Delivery, Claim, Refund |
| 코드·공통 | 5 | CodeGroup, Code, Attachment, AuditLog, NotificationLog |
| 집계 | 1 | SellerSalesDaily |
| **합계** | **37** | (9+3+3+8+8+5+1) |

- 수정 위치: §3 헤딩 라인 "총 **29개** (집계 1개 제외 시 28개):" → "총 **37개**" + 합계 라인 추가.
- 동일 오류 잔존: ERD `README.md` 머리말 "총 테이블: 29개" + 다이어그램별 합계(12+3+8+8+6=37)와 불일치 → 본 PR 포함 여부 사용자 확정(M-23).

---

## 4. 불확실·모호 항목

| # | 항목 | 출처 | 처리 제안 |
|---|---|---|---|
| M-21 | public_id 컬럼 타입 불일치 | db-schema §1.1 `CHAR(26)` vs ADR-001 §영향 `VARCHAR(30)` | ULID(26) + prefix(예 `ord_` 4) = 30자 → **VARCHAR(30) 또는 CHAR(30)이 정합**. CHAR(26)은 prefix 미수용. 제안: ddl-ready-checklist에 "DDL 진입 전 해소 필요(⚠)" 등재·사용자 확정(ADR/db-schema 수정은 본 5파일 범위 외) |
| M-22 | AuditLog public_id 부여 여부 불일치 | §1.1 미부여 + ADR-001 prefix 부재 vs ERD 05·db-schema §2.7 `char26 public_id` 보유·ERD 05 메모 "Attachment·AuditLog만" | 부여 대상이 11개(§1.1)인지 12개(AuditLog 포함)인지 미확정. AuditLog 부여 시 prefix(`aud_` 등) 미정의. 제안: 체크리스트 "해소 필요(⚠)" 등재·사용자 확정 |
| M-23 | README.md "총 테이블 29개" 동일 카운트 오류 | ERD README 머리말 | db-schema §3와 동일 오류. 본 PR(5파일)에 README 포함 여부 사용자 확정 |

> M-21·M-22는 ADR-001·db-schema §1.1·ERD를 건드려야 해소 가능 → 본 PR 산출물(5파일) 범위를 벗어나므로 **수정하지 않고 체크리스트에 "DDL 진입 전 해소 필요" 항목으로 등재만** 한다. 실제 정정은 사용자 확정 후 별도 처리(ERD 갱신 트랙 또는 본 PR 범위 확대 결정 시).

---

# PR-04.5 정찰 (정합성 정정)

> 소스: ddl-ready-checklist.md §7 ⚠ 해소 필요 3건·decisions.md D-13~D-15·index-strategy.md §4.2
> 목적: PR-04.5 구현 전 수정 위치 전수 확인. 신규 결정 없음 — 사용자 확정 방향만 반영.

---

## 1. M-21 대상 위치 (public_id 컬럼 타입 CHAR(30) 통일)

| 파일 | 현재 표기 | 수정 방향 |
|---|---|---|
| `docs/design/db-schema-decisions.md` §1.1 표 | `CHAR(26)` | `CHAR(30)` |
| `docs/design/db-schema-decisions.md` §1.4 표 | `public_id \| CHAR(26)` | `CHAR(30)` |
| `docs/adr/001-public-id.md` §영향 | `VARCHAR(30)` | `CHAR(30)` |
| ERD 01 (User) | `char26 public_id "prefix: usr_"` | `char30 public_id "prefix: usr_"` |
| ERD 02 (Seller) | `char26 public_id "prefix: slr_"` | `char30 public_id "prefix: slr_"` |
| ERD 03 (Product) | `char26 public_id "prefix: prd_"` | `char30 public_id "prefix: prd_"` |
| ERD 03 (ProductVariant) | `char26 public_id "prefix: var_"` | `char30 public_id "prefix: var_"` |
| ERD 04 (Order) | `char26 public_id "prefix: ord_"` | `char30 public_id "prefix: ord_"` |
| ERD 04 (OrderItem) | `char26 public_id "prefix: oit_"` | `char30 public_id "prefix: oit_"` |
| ERD 04 (Payment) | `char26 public_id "prefix: pay_"` | `char30 public_id "prefix: pay_"` |
| ERD 04 (Delivery) | `char26 public_id "prefix: dlv_"` | `char30 public_id "prefix: dlv_"` |
| ERD 04 (Claim) | `char26 public_id "prefix: clm_"` | `char30 public_id "prefix: clm_"` |
| ERD 04 (Refund) | `char26 public_id "prefix: rfn_"` | `char30 public_id "prefix: rfn_"` |
| ERD 05 (Attachment) | `char26 public_id "prefix: att_"` | `char30 public_id "prefix: att_"` |
| ERD 05 (AuditLog) | `char26 public_id` | `char30 public_id "prefix: aud_"` (M-22 동시) |

**합계**: 14곳 (`char26` → `char30`). ERD 05 AuditLog는 M-22 prefix 추가와 동시 처리.

---

## 2. M-22 대상 위치 (AuditLog public_id 부여 + prefix `aud_`)

| 파일 | 수정 내용 |
|---|---|
| `docs/design/db-schema-decisions.md` §1.1 "부여" 표 | "부여" 열에 AuditLog 추가 / "미부여" 열에서 AuditLog 제거 (11→12) |
| `docs/adr/001-public-id.md` prefix 목록 | `\| AuditLog \| aud_ \|` 행 추가 (11→12) |
| ERD 05 AuditLog mermaid | `char26 public_id` → `char30 public_id "prefix: aud_"` (M-21 동시) |
| `docs/design/erd/README.md` prefix 목록 | `\| \`aud_\` \| AuditLog \|` 행 추가 |

> 참고: ERD 05 메모 라인 "Attachment(att_), AuditLog만 해당"은 이미 AuditLog 부여를 반영. db-schema §1.1·ADR-001 prefix 목록만 미등재 → 해당 2곳 + ERD mermaid prefix 표기·README 목록 추가.

---

## 3. ADR-006 표현 보정 위치

| 파일 | 현재 표현 | 수정 방향 |
|---|---|---|
| `docs/adr/006-soft-delete.md` §영향 마지막 줄 | "인덱스 영향이 있다(전략은 PR-04)." | "인덱스 영향이 있다. MariaDB는 partial index(`WHERE deleted_at IS NULL`) 미지원 → 일반 인덱스 또는 (status, deleted_at) 복합으로 대체(index-strategy.md §4.2)." |

> 점검: ADR-006 본문에 "partial index"라는 단어 **자체가 없음** — 정정 내용은 "partial index 언급 삭제"가 아니라 "(전략은 PR-04)" 모호한 참조를 구체적 대체 방법으로 치환하는 것.

---

## 4. 본 PR 범위 외 변경 점검

정찰 중 추가 불일치 발견 없음. 아래는 기존 등재 항목과 동일한 범위.

| 항목 | 처리 |
|---|---|
| `db-schema-decisions.md` 헤더 changelog `v2.3` | PR-04.5 반영 후 `v2.4` 로 한 줄 추가 권장 (선택사항) |
| ERD 05 메모 "Attachment(att_), AuditLog만 해당" | AuditLog prefix 없이 이미 부여 언급 → prefix 추가(ERD 05 mermaid 수정)로 충분. 메모 문구 자체 수정 불필요 |

---

# PR-05 정찰 (리뷰 반영 6건)

> 소스: baseline-plan.md §5/§6·aggregate-boundary.md·state-machine.md·domain-events.md·decisions.md(D-01·D-04)·deletion-policy.md·inventory-policy.md·ADR-003·ADR-006·db-schema-decisions.md §1.8/§2.7
> 목적: 리뷰 반영 6건 구현 전 수정 위치 전수 확인 + invariant 인벤토리 확보. 결정 변경 1건(NotificationLog 재분류)·나머지 표현·구조 보강.
> 본 PR 범위 외 추가 불일치 발견 시 §7에만 명시·본 PR 수정 포함 금지.

---

## 1. 적용 1 — OrderStatusResolver 통일 정찰

**`OrderStatusCalculator` 명칭 등장 위치 (전수 3곳)**:

| 파일 | 라인 | 현재 표현 | 수정 방향 |
|---|---|---|---|
| `docs/adr/003-order-model.md` | 77 | "Application Service에 `OrderStatusCalculator` 컴포넌트 필요" | `OrderStatusResolver`·**Domain Service** |
| `docs/architecture-baseline/state-machine.md` | 146 | "Application Service (OrderStatusCalculator)에서 집계 후 Order.status 갱신" | `OrderStatusResolver`·**Domain Service** |
| `docs/architecture-baseline/decisions.md` D-04 | 138 | "Service.canTransition() 또는 OrderStatusCalculator 컴포넌트 필요" | `OrderStatusResolver` |

**Resolver 책임 정의 후보** (state-machine §5 신규 보강):
- **입력**: OrderItem 상태 집합(한 Order의 모든 OrderItem.item_status)
- **처리**: 규칙 평가(방식 B 명시적 전이 조건·평가 순서 [5]→[6]→[7]→[4]→[3]→[2])
- **출력**: Order.status 최종값(8값 중 1)
- **위치**: **Domain Service** (Order Aggregate 내부 파생 로직·외부 Aggregate 미관여 → Application Service 아님)
- **재계산 트리거**: OrderItem 상태 변경(Payment/Delivery/Claim 이벤트 소비 후)

**위치 표현 점검(Application → Domain)**: 현재 ADR-003·state-machine은 "Application Service"로 표기. Resolver는 OrderItem→Order.status **단일 Aggregate 내부** 집계이므로 DDD상 Domain Service가 정합. aggregate-boundary §1 "다른 Aggregate 변경은 …Application Service" 원칙과 충돌 없음(Resolver는 Aggregate 간 변경 아님). → **Domain Service로 정정 제안**(D-16 §불확실 항목에서 사용자 확정).

---

## 2. 적용 2 — Inventory 확장 경로 정찰

| 항목 | 정찰 결과 |
|---|---|
| 메모 추가 위치 | `inventory-policy.md` §7 외부 이연 (신규 하위 항목 "Reservation Tracking") |
| PR-02 D-08 보호 | "예약/해제는 InventoryHistory 미기록·reserved 컬럼만 갱신"(§6 M-11·D-08 확정) — 본문 변경 금지·재확인만 |
| 메모 성격 | **현재 단계 도입 금지** 확장 경로 표기만. 도입 시점 = 별도 ADR 발행 + decisions.md 누적 |
| change_type 정합 | InventoryHistory change_type A분류(ORDER/CANCEL/RETURN/ADJUST/INBOUND/OUTBOUND)에 RESERVE/RELEASE 부재 → 예약 추적 도입 시 enum 확장(=마이그레이션) 필요. 메모에 트레이드오프 명시 |

> 결론: §7에 "Reservation Tracking (현재 단계 도입 금지)" 1개 하위 항목 추가. §1~§6 본문·D-08 무변경.

---

## 3. 적용 3 — NotificationLog 재분류 정찰 (결정 변경 1건)

| 항목 | 정찰 결과 |
|---|---|
| 현재 위치 | `aggregate-boundary.md` §2.6 공통·보조 표 4행(Code·Attachment·AuditLog·NotificationLog) |
| D-01 등재 | `decisions.md` D-01 #17 NotificationLog (Aggregate 17번·"append-only 이벤트 기록") |
| 재분류 후보 위치 | `aggregate-boundary.md` 신규 **§2.7 Infra/Event Processing** 섹션·NotificationLog 이동 |
| D-01 갱신 영향 | 17 Aggregate → **16 Aggregate + 1 Infra/Event Processing**. D-01 [확정 2026-06-24] 마킹 **유지** + [갱신 2026-06-24 PR-05] 추가 |
| 재분류 사유 후보 | (1) 도메인 트랜잭션 주체 아님 (2) 다른 Aggregate가 발행한 이벤트(E1·E2·E4·E5·E9)의 **소비 기록** (3) AuditLog와 성격 유사하나 감사 의무 아님(발송 로그) |
| 영향 범위 점검 | domain-events.md는 NotificationLog를 "소비 주체"로만 참조(E1·E2·E4·E5·E9·E10) → 재분류와 정합(Aggregate 번호 인용 없음). deletion-policy.md §2.2는 NotificationLog ARCHIVE("독립 Aggregate") → **"독립 Aggregate" 문구 점검 필요**(§7-b 참조) |

> 비교: AuditLog는 감사 의무·CS/법적 대응 주체로 **Aggregate 유지**(#16). NotificationLog는 발송 이력 소비 기록으로 Infra/Event Processing 분리. 둘 다 append-only이나 도메인 책임이 다름.

---

## 4. 적용 4 — Aggregate 잠금 표현 정찰

| 항목 | 정찰 결과 |
|---|---|
| 추가 위치 | `aggregate-boundary.md` §1 원칙 (현재 3개 불릿: ID 참조·이벤트/Application Service·Root 경유) |
| 추가 표현 후보 | "Aggregate 분할은 **확정 결정**. 변경 필요 시 **ADR 신규 발행 + decisions.md 누적**으로만 갱신. 'DDL 전 변경 가능' 등 잠금 해제 표현 금지." |
| 자기참조 점검 | 본 PR의 NotificationLog 재분류는 이 절차(decisions.md D-18 + D-01 갱신)를 그대로 따름 → 잠금 표현과 모순 없음(재분류 = ADR 아닌 decisions 누적 절차로 처리) |

---

## 5. 적용 5 — 삭제 정책 SoT 정찰

| 항목 | 정찰 결과 |
|---|---|
| 위치 | `db-schema-decisions.md` §1.8 소프트 삭제 적용 범위 (적용/미적용 2열 표 + "주문·결제·정산은 …상태 관리" 인용) |
| 추가 1줄 후보 | "→ 상세 분류(SOFT/HARD/ARCHIVE)·경계 케이스·비식별화 흐름은 `docs/architecture-baseline/deletion-policy.md` 단일 레퍼런스 참조." |
| 본문 이동 금지 | §1.8 적용/미적용 표·인용은 **그대로 유지**. SoT 인용 1줄만 말미 추가(D-12 deletion-policy가 상세 SoT) |

---

## 6. 적용 6 — Invariant 인벤토리 정찰 (16 Aggregate + Infra/Event 1)

> invariants.md 신규 문서 후보. 각 invariant: Rule·Why·Enforcement Point(DB CHECK/UK/FK · Service · Domain · Batch)·Impact·Alternative.
> Enforcement Point는 "가장 낮은 레이어(DB) 우선·불가능 시 Service/Domain" 원칙(D-09·D-12 정합). 실제 제약 명세·코드는 외부 이연(DDL·Entity·구현 트랙).

### 6.1 Inventory (INV) — 6건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| INV-1 | quantity_available ≥ 0 | oversell 방지 | DB CHECK | Service 검증(DB 강제 누락 위험) |
| INV-2 | quantity_available = on_hand − reserved | 캐시 정합 | Application(D-09) | DB 트리거(기각·D-09) |
| INV-3 | quantity_reserved ≥ 0 | 예약 음수 차단 | DB CHECK | — |
| INV-4 | quantity_on_hand ≥ 0 | 실물 음수 불가 | DB CHECK | — |
| INV-5 | InventoryHistory append-only(on_hand 변동만) | 이력 무결성(M-11) | Domain | reserved 이력화(enum 확장·기각) |
| INV-6 | Inventory : ProductVariant = 1:1 | SoT 단일(D-07) | DB UK(variant_id)+FK | — |

### 6.2 Order (ORD) — 5건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| ORD-1 | Order는 OrderItem 최소 1개 | 빈 주문 불가 | Service | Trigger(기각·복잡) |
| ORD-2 | Order.status = OrderStatusResolver 결과 | 캐시 정합(D-04·ADR-003) | Domain Service | — |
| ORD-3 | OrderItem.seller_id 멀티벤더 혼재 허용 | 멀티벤더 정산 | Domain | 단일벤더 강제(기각) |
| ORD-4 | order_no UNIQUE | 주문번호 중복 차단 | DB UK | — |
| ORD-5 | OrderItem.total_price = unit_price × quantity | 금액 정합 | Service/Domain | — |

### 6.3 Payment (PAY) — 3건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| PAY-1 | Refund 총액 ≤ Payment.amount | 과환불 차단 | Domain(Claim/Refund→Payment.amount 누적 검증) | — |
| PAY-2 | Payment.status 전이 = state-machine §1 | 결제 흐름 정합 | Domain(enum canTransition) | — |
| PAY-3 | 유효 PAID 1건(재시도=새 행·pg_tid 멱등) | 중복 결제 차단 | Domain + pg_tid 멱등 | — |

> PAY-1 배치 점검: Refund는 Claim Aggregate(D-01 #13) 소속이나 규칙은 Payment.amount 참조 → **교차 Aggregate invariant**. invariants.md에 Payment 절 기재 + "Claim/Refund Domain에서 강제" 명시(§7-a 확정 필요).

### 6.3b Delivery (DLV) — 3건 (§7-f 보강·정찰 누락 보정)
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| DLV-1 | tracking_no UNIQUE | 송장 중복 차단 | DB UK | — |
| DLV-2 | Delivery는 OrderItem 없이 생성 불가(order_item_id·1:N) | 부분 배송 지원 | DB FK + NOT NULL | — |
| DLV-3 | shipped_at ≤ delivered_at | 시간 순서 정합 | Service/Domain | — |

> Delivery 상태 전이 규칙은 본 PR 범위 외(state-machine.md §6 이연). 위 invariant는 전이가 아닌 구조·시간 불변식.

### 6.4 Claim (CLM) — 4건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| CLM-1 | COMPLETED 후 상태 변경 금지 | 클레임 종결 보호 | Domain(state-machine §2) | — |
| CLM-2 | REJECTED 재요청 = 새 Claim 행 | 이력 보존(D-05) | Service | 기존 행 복귀(기각) |
| CLM-3 | Refund는 Claim 승인 후에만 생성 | 생명주기 공유(D-01) | Domain | — |
| CLM-4 | Claim.status 전이 = state-machine §2 | 클레임 흐름 정합 | Domain(enum canTransition) | — |

### 6.5 Product (PRD) — 5건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| PRD-1 | Seller 없는 Product 금지 | 멀티벤더 무결성 | DB FK + NOT NULL | — |
| PRD-2 | ProductVariant (product_id, option1~3_value_id) UNIQUE | 옵션 조합 중복 방지 | DB UK(db-schema §2.4) | — |
| PRD-3 | ProductVariant는 Product 없이 생성 불가 | Aggregate 경계 | DB FK + Domain | — |
| PRD-4 | ProductOptionGroup 상품당 최대 3개 | 한국 쇼핑몰 표준 | Service(애플리케이션 제약) | DB CHECK(동적 불가·기각) |
| PRD-5 | option1_value_id NOT NULL·option2/3 nullable | 옵션 구조 | DB NOT NULL | — |

### 6.6 User (USR) — 4건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| USR-1 | User.email UNIQUE | 계정 중복 차단 | DB UK | — |
| USR-2 | 탈퇴(withdrawn_at) 후 로그인 차단 | 보안 | Service(로그인 게이트) | — |
| USR-3 | 비식별화(anonymized_at) 후 식별자 유지·민감정보 NULL/HASH | 법정 보관 + 개인정보 보호 | Batch + Domain(db-schema §2.1) | — |
| USR-4 | legal_retention_until 경과 전 비식별화 금지 | 법정 보관 의무 | Batch | — |

### 6.7 Auth (AUTH) — 4건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| AUTH-1 | UserRole (user_id, role_id) 중복 금지 | 권한 중복 차단 | DB UK | — |
| AUTH-2 | RolePermission (role_id, permission_id) 중복 금지 | 권한 매핑 중복 차단 | DB UK | — |
| AUTH-3 | Role.code 값집합 잠금(A분류) | 권한 무결성 | DB ENUM + enum | — |
| AUTH-4 | 권한 회수(HARD delete) 시 AuditLog 기록 | 감사(M-19·D-12) | Service | — |

### 6.8 BuyerGrade (GRD) — 3건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| GRD-1 | 활성 GradePolicy는 (grade_id·기간) 단일 | 등급 산정 모호 차단 | Service(effective 기간·is_active·version DESC LIMIT 1) | — |
| GRD-2 | BuyerGrade.code 값집합 잠금(SILVER/GOLD/PLATINUM) | 등급 무결성 | DB ENUM | — |
| GRD-3 | GradePolicy.min_amount ≤ max_amount | 구간 정합 | DB CHECK / Service | — |

### 6.9 Seller (SLR) — 5건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| SLR-1 | Seller.business_no UNIQUE | 사업자번호 중복 차단 | DB UK | — |
| SLR-2 | SellerBankAccount.account_number 암호화 저장 | 금융정보 보호 | Domain(AES·db-schema §2.3) | — |
| SLR-3 | SellerBankAccount is_primary 단일 | 정산 계좌 모호 차단 | Service(변경 시 기존 false) | — |
| SLR-4 | Seller.status 전이(B분류 SELLER_STATUS) | 판매자 상태 정합 | Domain(enum canTransition) | — |
| SLR-5 | SellerUser (seller_id, user_id) 중복 금지 | 소속 중복 차단 | DB UK | — |

### 6.10 Settlement (STL) — 4건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| STL-1 | net_amount = gross_amount − fee_amount − refund_amount | 정산 금액 정합 | Domain | — |
| STL-2 | Settlement.status 전이(PENDING→CONFIRMED→PAID·A분류) | 정산 흐름 정합 | Domain(enum canTransition) | — |
| STL-3 | bank_account_id = 정산 시점 스냅샷 | 계좌 변경 무관 정합 | Domain | — |
| STL-4 | 상태·금액 변경 AuditLog 기록 | 감사(D-11) | Service | — |

### 6.11 Category (CAT) — 2건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| CAT-1 | parent_id self-FK 순환 금지 | 계층 무결성 | Service(사이클 검출) | DB(재귀 제약 불가·기각) |
| CAT-2 | depth = parent.depth + 1 일관 | 표시 정합 | Service | — |

### 6.12 CartItem (CRT) — 2건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| CRT-1 | (user_id, variant_id) UNIQUE | 중복 장바구니 차단 | DB UK(db-schema §2.5) | — |
| CRT-2 | quantity ≥ 1 | 0개 담기 차단 | DB CHECK / Service | — |

### 6.13 Code (COD) — 3건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| COD-1 | is_system=TRUE 코드 삭제·비활성 금지 | 시스템 코드 보호 | Service + Domain | — |
| COD-2 | Code (group_id, code) UNIQUE | 코드 중복 차단 | DB UK | — |
| COD-3 | B분류 ENUM 값 ↔ Code 시드 일치 | 코드-enum 정합(db-schema §1.13) | 시드 단계 | — |

### 6.14 Attachment (ATT) — 2건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| ATT-1 | (target_type, target_id) 논리 참조·FK 없음 | polymorphic(D분류) | Domain(화이트리스트 검증) | DB FK(동적 확장 불가·기각) |
| ATT-2 | public_id(att_) UNIQUE | 식별자 무결성(ADR-001) | DB UK | — |

### 6.15 AuditLog (AUD) — 4건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| AUD-1 | append-only(수정·삭제 금지) | 감사 무결성(D-11) | Domain | — |
| AUD-2 | 민감정보(비밀번호·결제 토큰·계좌번호·주민번호) 마스킹/제외 | 개인정보 보호(D-11·M-17) | Domain | — |
| AUD-3 | diff_json = JSON·changed_fields 한정 | 변경 추적(D-11) | DB JSON(CHECK JSON_VALID) + Domain | LONGTEXT(질의 불가·기각) |
| AUD-4 | public_id(aud_) UNIQUE | 식별자 무결성(M-22) | DB UK | — |

### 6.16 Infra/Event Processing — NotificationLog (NOT) — 3건
| # | Rule | Why | Enforcement | Alternative |
|---|---|---|---|---|
| NOT-1 | append-only 발송 이력 | 이력 보존(ARCHIVE·D-12) | Domain | — |
| NOT-2 | channel/status 값집합 잠금(A분류) | 발송 무결성 | DB ENUM + enum | — |
| NOT-3 | (target_type, target_id) 논리 참조·FK 없음 | polymorphic(D분류) | Domain(화이트리스트) | — |

### 6.17 공통 Invariant (전 Aggregate 횡단)
| # | Rule | Enforcement |
|---|---|---|
| COM-1 | public_id 부여 대상(12개) UNIQUE | DB UK·ADR-001 |
| COM-2 | SOFT 대상 조회는 deleted_at IS NULL 가드 | Service·D-12 |
| COM-3 | 시간 UTC·DATETIME(6) | 글로벌 정책 §1.2 |
| COM-4 | 금액 BIGINT(KRW 정수·DECIMAL 금지) | 글로벌 정책 §1.3 |

### 6.18 Read Model (참고 — Aggregate 아님·D-10)
| 테이블 | 성격 | 처리 |
|---|---|---|
| BuyerPurchaseAggregate | buyer_id PK·재계산 가능(Order CONFIRMED SUM) | invariants.md 외부 이연 절 표기만(Write Model 파생) |
| SellerSalesDaily | (seller_id, sale_date) 복합 PK·idempotent upsert | 동일 |

> 합계: 16 Aggregate(INV·ORD·PAY·DLV·CLM·PRD·USR·AUTH·GRD·SLR·STL·CAT·CRT·COD·ATT·AUD) + Infra/Event 1(NOT) + 공통 4 + Read Model 참고 2. 도메인별 invariant 합 ≈ 62건(16 Aggregate 59 + Infra/Event 3). [보정 2026-06-24: Delivery(#12) 누락 → §6.3b 추가·"15개+Category" 오기 정정]
> 주의: invariants.md는 **불변조건 카탈로그**·State Machine 전이 정의 아님(전이는 state-machine.md 4건 한정). DB CHECK/UK 실제 제약·Entity 검증·Service 가드 구현은 외부 이연.

---

## 7. 불확실·모호 항목 (사용자 확정 필요)

| # | 항목 | 정찰 결과 | 처리 제안 |
|---|---|---|---|
| a | PAY-1(Refund≤Payment.amount) 배치 위치 | Refund는 Claim Aggregate 소속이나 규칙은 Payment.amount 참조(교차 Aggregate) | invariants.md **Payment 절** 기재 + "Claim/Refund Domain에서 강제" 명시 ← 제안. 사용자 확정 |
| b | OrderStatusResolver 위치 표현(Application→Domain) | 현재 ADR-003·state-machine "Application Service" 표기. Resolver는 단일 Aggregate 내부 집계 → Domain Service 정합 | **Domain Service로 정정** ← 제안. 표현 변경 동의 여부 확정 |
| c | NotificationLog 재분류 후 deletion-policy.md §2.2 "독립 Aggregate" 문구 | NotificationLog ARCHIVE 근거에 "독립 Aggregate" 표현 존재 | 본 PR 산출물 목록에 deletion-policy 미포함 → **문구 미수정·§7 등재만**(범위 외) 또는 "독립 Infra/Event 기록"으로 동시 정정 중 택1. 사용자 확정 |
| d | Read Model(BuyerPurchaseAggregate·SellerSalesDaily) invariant 포함 여부 | Aggregate 아님(D-10 Read Model) | invariants.md "외부 이연/참고" 절에 표기만(불변조건 카탈로그 본문 제외) ← 제안 |
| e | invariants.md Category·BuyerGrade 등 비핵심 Aggregate 깊이 | 핵심(Inventory·Order·Payment·Claim·Product) 대비 invariant 적음 | 전 16 Aggregate 커버하되 핵심은 상세·보조는 1~3건 ← 제안 |
| f | 정찰 §6에서 Delivery(#12) 누락 | 16 Aggregate 중 DLV 미커버 | §6.3b Delivery(DLV-1~3) 추가·invariants.md §2.12 포함 ← **확정 보강** |

> **확정 처리(2026-06-24)**: a~e 추천 채택·c는 deletion-policy.md 포함(산출물 7파일)·f Delivery 보강. 브랜치 베이스 = fix/pr-04.5 스택. 본 PR 범위 외 추가 불일치 없음.

---

# pre-ddl-cleanup-2 정찰 (2026-06-24)

> 소스: invariants.md·db-schema-decisions.md §2.1/§2.3·decisions.md D-21·TODO.md
> 목적: CR-3 A-1·CR-2 반영 4지점 현 상태 확인. 결정 도입 전 정찰. 신규 결정 제안 금지.

---

## 1. invariants.md USR-1·SLR-1 현 상태

**USR-1~4 현 본문 (§2.1)**:

| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| USR-1 | User.email UNIQUE | 계정 중복 차단 | DB UK | 가입 시 중복 거부 | — |
| USR-2 | 탈퇴(withdrawn_at) 후 로그인 차단 | 보안 | Service(로그인 게이트) | 탈퇴 계정 접근 차단 | — |
| USR-3 | 비식별화(anonymized_at) 후 식별자 유지·민감정보 NULL/HASH | 법정 보관 + 개인정보 보호 | Batch + Domain(db-schema §2.1) | ARCHIVE 데이터 정합 보존 | — |
| USR-4 | legal_retention_until 경과 전 비식별화 금지 | 법정 보관 의무 | Batch | 보관 기간 강제 | — |

- 비식별화 관련 기존 문구: USR-3(anonymized_at 후 식별자 유지·민감정보 NULL/HASH)·USR-4(법정 보관기간 경과 전 비식별화 금지) 존재
- **재가입 허용 여부 invariant: 없음** (USR-1 email UNIQUE가 비식별화 후 email=NULL 전환·재가입 가능성 커버 여부 불명확)
- 추가 위치 후보: USR-4 다음 행

**SLR-1 현 본문 (§2.4)**:

| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| SLR-1 | Seller.business_no UNIQUE | 사업자번호 중복 차단 | DB UK | 동일 사업자 중복 입점 차단 | — |

- business_no 비식별화/탈퇴 후 처리 관련 invariant: **없음** (UNIQUE 제약만 존재)
- 추가 위치 후보: SLR-1 다음 행

---

## 2. db-schema-decisions.md §2.1 현 상태

**User·WithdrawnUser 비식별화 흐름 현 본문 (§2.1)**:
```
탈퇴 흐름: 탈퇴 → 로그인 차단 → 법정보관기간 유지 → 배치 → 비식별화

비식별화 시:
- email → NULL
- phone → HASH
- name → NULL
- user_id, order_id 등 식별자는 유지 (정합성 보존)
```

- 재가입 정책 관련 문구: **없음** (§2.1 전체 검색 — 재가입 언급 없음)
- Seller business_no 비식별화 처리 명시: **없음** (§2.3은 SellerBankAccount account_number 암호화만 다룸)
- **Seller 비식별화 흐름 정의 유무**: §2.1은 User만 정의·§2.3은 계좌 암호화만. Seller 자체(TERMINATED 후) 비식별화 흐름 **미정의**

---

## 3. decisions.md 마지막 결정 번호·D-22 추가 형식

- 마지막 결정: **D-21** [확정 2026-06-24] — Invariant 문서 신규(invariants.md)
- 누적 위치: D-21 본문 끝 `---` 구분자 직후 (line 629 이후)
- D-22 추가 형식 (D-21 구조 그대로):

```
---

### D-22: [결정명]

**상태**: [확정 YYYY-MM-DD]

**결정안**: ...
- 항목1
- 항목2

**Why**: ...

**Impact**: ...

**Alternative**: ...

---
```

---

## 4. TODO.md 진행 대기 트랙 추가 위치

현재 "진행 대기 트랙" 표 (확인):

| 트랙 | 내용 |
|---|---|
| DDL | Flyway 마이그레이션 |
| state-machine 보강 | Refund.status 상태 전이 정의 — 외부 리뷰 발견·신규 결정 도입 트랙·Entity 트랙 진입 전 처리 |
| Entity | ... |
| API | ... |
| 구현 | ... |

"Order.status 복구 정책" 신규 행 추가 위치: **"state-machine 보강" 행 바로 다음** (state-machine 관련 작업 인접 배치·Entity 트랙 진입 전 처리와 동일 성격)

---

## 5. 변경 위험 평가

- 회귀 위험: **낮음** — invariants.md 기존 행 미수정·§2.1/§2.4에 행 추가만·decisions.md D-22 누적·TODO.md 1행 추가. 기존 본문 내용 변경 없음.
- DDL 영향: **있음** — 재가입 조건 invariant 추가 시 email UNIQUE 제약 범위 명확화 필요(비식별화 후 email=NULL → NULL 중복 허용으로 DB 영향 없으나 "재가입 허용" 명시 여부가 DDL 레벨 정책 문서 역할). business_no 비식별화 invariant 추가 시 Seller TERMINATED 후 UNIQUE 해소 방법(NULL vs HASH) DDL 설계에 영향.
- Seller 비식별화 미정의 플래그: **Yes** — §2.1 User만 비식별화 흐름 정의·§2.3 계좌 암호화만·Seller 자체 비식별화 흐름 없음. 본 트랙에서 Seller 비식별화 결정 도입 여부 사용자 재확정 필요.
- 추가 발견 사항: USR-1 email UNIQUE와 비식별화 후 email=NULL 충돌 없음(MariaDB NULL은 UNIQUE 중복 허용). 단 "탈퇴 후 재가입 시 동일 email 허용 여부" 결정이 없으면 비식별화 전 재가입 시도가 차단되지 않음(law gap). business_no도 동일 구조.
