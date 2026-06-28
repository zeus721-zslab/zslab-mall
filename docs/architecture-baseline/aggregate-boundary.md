# Aggregate Boundary (PR-01)

> 소스: decisions.md D-01 [확정 2026-06-24]
> 기준: DDD Aggregate 4기준 (함께 생성·함께 삭제·트랜잭션 동반·독립 수정 불가)

---

## 1. 원칙

- Aggregate간 참조는 **ID만 허용** (객체 그래프 참조 금지)
- 다른 Aggregate 변경은 도메인 이벤트 또는 Application Service에서 처리
- Root를 통하지 않은 내부 엔티티 직접 수정 금지
- Aggregate 분할은 **확정 결정**이다. 변경이 필요하면 ADR 신규 발행 + decisions.md 누적으로만 갱신한다. "DDL 전 변경 가능" 등 잠금 해제 표현을 쓰지 않는다.

---

## 2. Aggregate 목록 (16개 + Infra/Event Processing 1건)

### 2.1 사용자·권한·등급

| Aggregate | Root | 포함 엔티티 | 외부 ID 참조 |
|---|---|---|---|
| User | User | WithdrawnUser, BuyerProfile, UserAddress | BuyerGrade.id |
| Auth | Role | Permission, UserRole, RolePermission | User.id |
| BuyerGrade | BuyerGrade | GradePolicy | — |

**경계 결정**:
- `WithdrawnUser`·`BuyerProfile`·`UserAddress`는 User 없이 생성 불가 → User 포함
- `Role`은 Auth 컨텍스트의 Root. `Permission`·`UserRole`·`RolePermission`은 Role을 통해서만 변경
- `SellerUser`는 Seller 컨텍스트에서 생성·삭제 → Seller Aggregate 귀속 (Auth 귀속 대안 기각: Role 조회는 ID 참조로 충분)

### 2.2 판매자·정산

| Aggregate | Root | 포함 엔티티 | 외부 ID 참조 |
|---|---|---|---|
| Seller | Seller | SellerBankAccount, SellerUser, WithdrawnSeller | User.id, Role.id |
| Settlement | Settlement | — (단독) | Seller.id, SellerBankAccount.id |

**경계 결정**:
- `SellerBankAccount`·`SellerUser`는 Seller 없이 존재 불가, 판매자 온보딩 트랜잭션 내 생성
- `WithdrawnSeller`는 D-23 V2 신설·Seller Aggregate 종속·Snapshot Metadata(SLR-7 SoT·D-85 Q2). 갱신 주의: SLR-7에 따라 Seller·WithdrawnSeller 동시 수정 금지·Seller SoT
- `Settlement`는 정산 주기별 독립 생성 (Seller 트랜잭션과 별개) → 독립 Aggregate

### 2.3 상품·카테고리·재고

| Aggregate | Root | 포함 엔티티 | 외부 ID 참조 |
|---|---|---|---|
| Category | Category | — (self-ref 계층) | Category.id(parent) |
| Product | Product | ProductImage, ProductOptionGroup, ProductOptionValue, ProductVariant | Seller.id, Category.id |
| Inventory | Inventory | InventoryHistory | ProductVariant.id |

**경계 결정**:
- `ProductVariant`는 Product 없이 생성 불가, 상태 변경도 상품 컨텍스트 내 → Product 포함
- `Inventory`는 주문·취소·반품에 의해 Order 트랜잭션과 별도로 갱신 → 독립 Aggregate (SoT는 PR-02에서 확정)
- `InventoryHistory`는 Inventory 변경 기록 — Inventory와 생명주기 공유

### 2.4 장바구니

| Aggregate | Root | 포함 엔티티 | 외부 ID 참조 |
|---|---|---|---|
| CartItem | CartItem | — (단독) | User.id, ProductVariant.id |

**경계 결정**:
- `CartItem`은 User 트랜잭션과 무관하게 독립 조작 → 독립 Aggregate
- User Aggregate 포함 대안 기각: 장바구니 조작 시 항상 User 로드 필요, 잠금 범위 확대

### 2.5 주문·결제·배송·클레임

| Aggregate | Root | 포함 엔티티 | 외부 ID 참조 |
|---|---|---|---|
| Order | Order | OrderItem, OrderShippingSnapshot | User.id, ProductVariant.id, Seller.id |
| Payment | Payment | — (단독) | Order.id |
| Delivery | Delivery | — (단독) | OrderItem.id |
| Claim | Claim | Refund | OrderItem.id, Payment.id |

**경계 결정**:
- `OrderItem`·`OrderShippingSnapshot`은 Order 생성 트랜잭션 내 함께 생성·삭제 → Order 포함
- `Payment`는 PG 콜백 비동기 처리 컨텍스트 → 독립 Aggregate (Order 포함 대안 기각: 잠금 범위 확대)
- `Delivery`는 판매자/물류 컨텍스트에서 독립 갱신 → 독립 Aggregate (Order 포함 대안 기각: 부분 배송 지원 시 Aggregate 비대화)
- `Refund`는 Claim 승인 후에만 생성, Claim과 생명주기 공유 → Claim 포함

### 2.6 공통·보조

| Aggregate | Root | 포함 엔티티 | 외부 ID 참조 |
|---|---|---|---|
| Code | CodeGroup | Code | — |
| Attachment | Attachment | — (단독, polymorphic) | target_type/target_id |
| AuditLog | AuditLog | — (단독, append-only) | actor_user_id |

**경계 결정**:
- `Code`는 `CodeGroup` 아래 다수의 Code 항목 관리 → CodeGroup이 Root
- `Attachment`는 polymorphic 참조 (target_type/target_id)로 어느 Aggregate에도 종속되지 않음
- `AuditLog`는 append-only 감사 기록 → 독립 단독 Aggregate (감사 의무·분쟁/CS 대응 주체)
- `NotificationLog`는 Aggregate가 아니라 **Infra/Event Processing**으로 분류 → §2.7 참조

### 2.7 Infra/Event Processing

| 분류 | 대상 | 외부 ID 참조 |
|---|---|---|
| Infra/Event Processing | NotificationLog | recipient_user_id, target_type/target_id |

**분류 결정** (D-18·D-01 갱신):
- `NotificationLog`는 도메인 트랜잭션 주체가 아니다 — 자체 비즈니스 불변식·상태 전이 규칙이 없다.
- 다른 Aggregate가 발행한 이벤트(E1·E2·E4·E5·E9·E10)의 **소비 기록**(발송 이력)이다.
- AuditLog와 같은 append-only이나 **감사 의무가 아닌** 발송 로그이므로 Aggregate에서 분리한다(AuditLog는 §2.6 Aggregate 유지).
- 삭제 정책은 Aggregate 여부와 독립 — NotificationLog는 ARCHIVE 유지(deletion-policy.md §2.2).

---

## 3. Read Model 후보 (PR-03 이연)

| 테이블 | 이연 근거 |
|---|---|
| BuyerPurchaseAggregate | db-schema §2.2 "Read Model" 명시. 이벤트 핸들러로 갱신되는 파생 데이터 |
| SellerSalesDaily | db-schema §1.11·§2.8 "집계 테이블 (배치 갱신)" 명시 |

baseline-plan.md §10 — Read Model 작업은 PR-03 트랙에서 처리.

---

## 4. 외부 이연

- **Inventory SoT (Source of Truth)** — 갱신 방식(애플리케이션 vs DB 트리거) → PR-02
- **도메인 이벤트 목록** (CartItem 소비, Order 생성, Payment 완료 등) → PR-02
- **Read Model + Audit Policy** → PR-03
