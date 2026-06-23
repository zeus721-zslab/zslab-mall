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
