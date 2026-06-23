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
