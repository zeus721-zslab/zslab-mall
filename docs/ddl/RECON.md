# DDL 작성 트랙 — 정찰 입력 사본

> 본 파일은 V1__init.sql 작성 트랙의 "DDL 정찰 — 37 테이블 전수 매핑 (2026-06-24)" **단일 보유처**다. `docs/architecture-baseline/RECON.md` 에는 본 위상정렬·테이블별 상세 섹션이 부재하며(M-09 카운트 정찰만 보유), 본 파일이 V1 토폴로지 SoT다.
> 목적: V1__init.sql 작성 트랙의 단일 입력 보존(작성 중 architecture-baseline 4문서 재참조 부담 제거).
> 원본 정찰은 read-only 모드로 산출되었고, 본 파일이 작성 트랙의 SoT 입력이다.

---

## DDL 정찰 — 37 테이블 전수 매핑 (2026-06-24)

> 소스: db-schema v2.4·ERD 5종·invariants(62건)·index-strategy·deletion-policy·aggregate-boundary·state-machine·audit-policy·decisions D-01~D-22·erd-update E-01~E-06
> 목적: V1__init.sql 작성 트랙의 단일 입력. 본 표 외 문서 재참조 불필요한 수준.

### 1. 테이블 그룹·생성 순서 (FK 의존성 위상정렬)

> 규칙: 부모 테이블 先생성. Category는 self-FK(parent_id nullable)이므로 단일 테이블 내 처리. 순환 없음.

| 순서 | 테이블 | 그룹 | FK 의존(선행 테이블) |
|---|---|---|---|
| 1 | User | 회원 | (없음) |
| 2 | Role | 권한 | (없음) |
| 3 | Permission | 권한 | (없음) |
| 4 | BuyerGrade | 등급 | (없음) |
| 5 | Seller | 판매자 | (없음) |
| 6 | CodeGroup | 코드 | (없음) |
| 7 | Category | 상품 | Category(self·nullable) |
| 8 | WithdrawnUser | 회원 | User |
| 9 | BuyerProfile | 회원 | User, BuyerGrade |
| 10 | UserAddress | 회원 | User |
| 11 | UserRole | 권한 | User, Role |
| 12 | RolePermission | 권한 | Role, Permission |
| 13 | SellerUser | 권한·판매자 | Seller, User, Role |
| 14 | GradePolicy | 등급 | BuyerGrade |
| 15 | BuyerPurchaseAggregate | 등급·집계 | User(논리참조·FK 미적용 §1.9) |
| 16 | SellerBankAccount | 판매자 | Seller |
| 17 | Settlement | 판매자 | Seller, SellerBankAccount |
| 18 | SellerSalesDaily | 집계 | Seller(논리참조·FK 미적용 §1.9) |
| 19 | Code | 코드 | CodeGroup |
| 20 | Product | 상품 | Seller, Category |
| 21 | ProductImage | 상품 | Product |
| 22 | ProductOptionGroup | 상품 | Product |
| 23 | ProductOptionValue | 상품 | ProductOptionGroup |
| 24 | ProductVariant | 상품 | Product, ProductOptionValue×3 |
| 25 | Inventory | 재고 | ProductVariant |
| 26 | InventoryHistory | 재고 | Inventory |
| 27 | CartItem | 주문 | User, ProductVariant |
| 28 | Order | 주문 | User |
| 29 | OrderItem | 주문 | Order, Product, ProductVariant, Seller |
| 30 | OrderShippingSnapshot | 주문 | Order |
| 31 | Payment | 결제 | Order |
| 32 | Delivery | 배송 | OrderItem |
| 33 | Claim | 클레임 | OrderItem |
| 34 | Refund | 클레임 | Claim, Payment |
| 35 | Attachment | 공통 | (없음·polymorphic) |
| 36 | AuditLog | 공통 | (없음·polymorphic) |
| 37 | NotificationLog | 공통 | (없음·polymorphic) |

> 검증: 37건 전수. FK 사이클 0 (Category self-FK는 nullable 단일 테이블 처리).

### 2. 테이블별 상세 매핑

> 공통: 전 테이블 `id BIGINT AUTO_INCREMENT PK`(집계 2건·BuyerProfile 공유 PK 예외·§3), charset `utf8mb4`/`utf8mb4_unicode_ci`, 시간 `DATETIME(6)` UTC, 금액 `BIGINT`. audit 컬럼·soft delete 컬럼은 §3 일괄 적용. 아래는 도메인 컬럼·제약·invariant 매핑만 기재.

#### 2.1 회원·권한 (9)

**User** — Aggregate USR · SOFT · public_id `usr_` — public_id CHAR(30) NN UK / email VARCHAR(254) NULL UK(USR-1·재가입 D-22) / name VARCHAR(50) NULL(비식별화 NULL) / phone VARCHAR(20) NULL(비식별화 HASH) / withdrawn_at DATETIME(6) NULL / anonymized_at DATETIME(6) NULL. PK pk_user·SOFT(deleted_at).

**WithdrawnUser** — USR 종속 · ARCHIVE(경계) — original_user_id BIGINT NN(FK) / withdraw_reason VARCHAR(255) NULL / legal_retention_until DATETIME(6) NULL / anonymized_at DATETIME(6) NULL. FK fk_withdrawn_user_user.

**BuyerProfile** — USR 종속 · SOFT(상속) · 미부여 · 공유 PK — user_id BIGINT NN(PK·FK→User) / grade_id BIGINT NN(FK→BuyerGrade) / grade_source ENUM(AUTO,MANUAL,EVENT) NN(A#1) / grade_locked_until DATETIME(6) NULL / grade_updated_at DATETIME(6) NULL.

**UserAddress** — USR 종속 · SOFT — user_id BIGINT NN(FK) / is_default BOOLEAN NN / address_label VARCHAR(50) NULL / recipient_name VARCHAR(50) NN / recipient_phone VARCHAR(20) NN / zonecode VARCHAR(10) NN / address_road VARCHAR(200) NN / address_jibun VARCHAR(200) NULL / address_detail VARCHAR(200) NULL. FK fk_user_address_user·SOFT.

**Role** — Aggregate Auth(Root) · HARD · 미부여 — code ENUM(SUPER_ADMIN,ADMIN_OPERATOR,BUYER,SELLER_OWNER,SELLER_MANAGER,SELLER_STAFF) NN(A#2·AUTH-3) / name VARCHAR(50) NN.

**Permission** — Auth 종속 · HARD — code VARCHAR(50) NN / name VARCHAR(200) NN.

**UserRole** — Auth 종속 · HARD — user_id BIGINT NN(FK) / role_id BIGINT NN(FK). UK uk_user_role(user_id,role_id)·AUTH-1.

**RolePermission** — Auth 종속 · HARD — role_id BIGINT NN(FK) / permission_id BIGINT NN(FK). UK uk_role_permission(role_id,permission_id)·AUTH-2.

**SellerUser** — Aggregate Seller 귀속 · SOFT(상속) · 미부여 — seller_id BIGINT NN(FK) / user_id BIGINT NN(FK) / role_id BIGINT NN(FK). UK uk_seller_user(seller_id,user_id)·SLR-5.

#### 2.2 등급 (3)

**BuyerGrade** — Aggregate BuyerGrade(Root) · SOFT · 미부여 — code ENUM(SILVER,GOLD,PLATINUM) NN(A#3·GRD-2) / name VARCHAR(50) NN.

**GradePolicy** — BuyerGrade 종속 · SOFT — grade_id BIGINT NN(FK) / min_amount BIGINT NN / max_amount BIGINT NN / discount_rate INT NN / point_rate INT NN / effective_from DATETIME(6) NN / effective_to DATETIME(6) NN / version INT NN / is_active BOOLEAN NN. FK fk_grade_policy_grade·CHECK chk_grade_policy_amount(min_amount<=max_amount)·GRD-3.

**BuyerPurchaseAggregate** — Read Model · ARCHIVE · 미부여 · 단일 PK buyer_id — buyer_id BIGINT PK / lifetime_purchase_amount BIGINT NN / last_ordered_at DATETIME(6) NULL / updated_at DATETIME(6) NN. FK 미적용(§1.9 집계)·ERD는 FK 표기(§10-②).

#### 2.3 판매자 (3)

**Seller** — Aggregate Seller(Root) · SOFT · public_id `slr_` — public_id CHAR(30) NN UK / company_name VARCHAR(100) NN(V2 NULL·D-23) / business_no VARCHAR(20) NULL(UK·SLR-1·재등록 D-22·보정1) / ceo_name VARCHAR(50) NN(V2 NULL·D-23) / contact_email VARCHAR(254) NULL / contact_phone VARCHAR(20) NULL / status ENUM(PENDING,ACTIVE,SUSPENDED,TERMINATED) NN(B·SELLER_STATUS·SLR-4).

**SellerBankAccount** — Seller 종속 · ARCHIVE(경계) — seller_id BIGINT NN(FK) / bank_code VARCHAR(20) NN / account_number VARCHAR(255) NN(AES·SLR-2) / account_holder VARCHAR(50) NN / is_primary BOOLEAN NN(SLR-3) / verified_at DATETIME(6) NULL / status ENUM(PENDING,VERIFIED,REJECTED) NN(A#4).

**Settlement** — Aggregate Settlement(Root) · ARCHIVE · 미부여 — seller_id BIGINT NN(FK) / bank_account_id BIGINT NN(FK·스냅샷 STL-3) / period_start DATETIME(6) NN / period_end DATETIME(6) NN / gross_amount BIGINT NN / fee_amount BIGINT NN / refund_amount BIGINT NN / net_amount BIGINT NN(STL-1·Domain) / status ENUM(PENDING,CONFIRMED,PAID) NN(A#5·STL-2) / paid_at DATETIME(6) NULL.

#### 2.4 상품·재고 (8)

**Category** — Aggregate Category(Root) · SOFT · 미부여 — parent_id BIGINT NULL(self-FK·CAT-1) / display_name VARCHAR(200) NN / depth INT NN(CAT-2) / sort_order INT NN.

**Product** — Aggregate Product(Root) · SOFT · public_id `prd_` — public_id CHAR(30) NN UK / seller_id BIGINT NN(FK·PRD-1) / category_id BIGINT NN(FK) / name VARCHAR(200) NN / description LONGTEXT NULL / status ENUM(DRAFT,PENDING,APPROVED,REJECTED,SALE,HIDDEN,STOPPED) NN(A#6) / base_price BIGINT NN / thumbnail_url VARCHAR(2048) NULL.

**ProductImage** — Product 종속 · SOFT — product_id BIGINT NN(FK) / image_url VARCHAR(2048) NN / display_order INT NN / is_main BOOLEAN NN.

**ProductOptionGroup** — Product 종속 · SOFT(상속) — product_id BIGINT NN(FK) / name VARCHAR(50) NN / display_order INT NN. 상품당 최대 3개=Service(PRD-4).

**ProductOptionValue** — Product 종속 · SOFT(상속) — option_group_id BIGINT NN(FK) / value VARCHAR(100) NN / display_order INT NN.

**ProductVariant** — Product 종속 · SOFT · public_id `var_` — public_id CHAR(30) NN UK / product_id BIGINT NN(FK·PRD-3) / variant_code VARCHAR(50) NN / seller_sku VARCHAR(100) NULL / barcode VARCHAR(100) NULL / additional_price BIGINT NN / status ENUM(SALE,HIDDEN,STOPPED) NN(A#7) / is_soldout_manual BOOLEAN NN / display_order INT NN / option1_value_id BIGINT NN(FK·PRD-5) / option2_value_id BIGINT NULL(FK) / option3_value_id BIGINT NULL(FK). UK uk_product_variant_options(product_id,option1~3_value_id)·PRD-2.

**Inventory** — Aggregate Inventory(Root) · ARCHIVE · 미부여 — variant_id BIGINT NN(FK·UK·INV-6 1:1) / quantity_on_hand INT NN(CHECK≥0·INV-4) / quantity_reserved INT NN(CHECK≥0·INV-3) / quantity_available INT NN(CHECK≥0·INV-1·캐시 on_hand−reserved·INV-2 App·D-09).

**InventoryHistory** — Inventory 종속 · ARCHIVE · append-only — inventory_id BIGINT NN(FK) / change_type ENUM(ORDER,CANCEL,RETURN,ADJUST,INBOUND,OUTBOUND) NN(A#8) / quantity_delta INT NN / reference_type VARCHAR(50) NN(D분류·앱검증) / reference_id BIGINT NULL / reason VARCHAR(255) NULL.

#### 2.5 주문·결제·배송·클레임 (8)

**CartItem** — Aggregate CartItem(Root) · HARD · 미부여 — user_id BIGINT NN(FK) / variant_id BIGINT NN(FK) / quantity INT NN(CHECK≥1·CRT-2) / selected BOOLEAN NN. UK uk_cart_item_user_variant(user_id,variant_id)·CRT-1.

**Order** — Aggregate Order(Root) · ARCHIVE · public_id `ord_` — public_id CHAR(30) NN UK / buyer_id BIGINT NN(FK) / order_no VARCHAR(50) NN(UK·ORD-4) / status ENUM(8값) NN(B·ORDER_STATUS·ORD-2 Resolver) / total_price BIGINT NN / discount_amount BIGINT NN / shipping_fee BIGINT NN / paid_at DATETIME(6) NULL / ordered_at DATETIME(6) NULL. status 8값: PENDING_PAYMENT,PAID,PREPARING,SHIPPING,DELIVERED,CONFIRMED,CANCELLED,PARTIAL_CANCEL.

**OrderItem** — Order 종속 · ARCHIVE · public_id `oit_` — public_id CHAR(30) NN UK / order_id BIGINT NN(FK) / product_id BIGINT NN(FK) / variant_id BIGINT NN(FK) / seller_id BIGINT NN(FK·멀티벤더 ORD-3) / quantity INT NN / unit_price BIGINT NN / total_price BIGINT NN(ORD-5) / item_status ENUM(12값) NN(B·ORDER_ITEM_STATUS). item_status 12값: ORDERED,PAID,PREPARING,SHIPPING,DELIVERED,CONFIRMED,CANCEL_REQUESTED,CANCELLED,RETURN_REQUESTED,RETURNED,EXCHANGE_REQUESTED,EXCHANGED.

**OrderShippingSnapshot** — Order 종속 · ARCHIVE(상속) · 미부여 — order_id BIGINT NN(FK·1:1) / recipient_name VARCHAR(50) NN / recipient_phone VARCHAR(20) NN / zonecode VARCHAR(10) NN / address_road VARCHAR(200) NN / address_jibun VARCHAR(200) NULL / address_detail VARCHAR(200) NULL / delivery_memo TEXT NULL.

**Payment** — Aggregate Payment(Root) · ARCHIVE · public_id `pay_` — public_id CHAR(30) NN UK / order_id BIGINT NN(FK) / method ENUM(CARD,BANK,VBANK,KAKAO) NN(A#9) / amount BIGINT NN / status ENUM(PENDING,PAID,FAILED,CANCELLED) NN(A#10·PAY-2) / pg_provider VARCHAR(50) NULL / pg_tid VARCHAR(100) NULL(멱등 PAY-3) / paid_at DATETIME(6) NULL.

**Delivery** — Aggregate Delivery(Root) · ARCHIVE · public_id `dlv_` — public_id CHAR(30) NN UK / order_item_id BIGINT NN(FK·DLV-2) / carrier ENUM(CJ,HANJIN,POST,LOGEN) NN(A#11) / tracking_no VARCHAR(100) NULL(UK·DLV-1) / status ENUM(READY,SHIPPING,DELIVERED) NN(A#12) / shipped_at DATETIME(6) NULL / delivered_at DATETIME(6) NULL(DLV-3).

**Claim** — Aggregate Claim(Root) · ARCHIVE · public_id `clm_` — public_id CHAR(30) NN UK / order_item_id BIGINT NN(FK) / type ENUM(CANCEL,RETURN,EXCHANGE) NN(A#13) / reason_code VARCHAR(50) NN(B·CLAIM_REASON·값집합 미확정 §10-③·ENUM 미적용) / reason_detail TEXT NULL / status ENUM(REQUESTED,APPROVED,REJECTED,COMPLETED) NN(A#14·CLM-4) / requested_by BIGINT NULL(논리참조) / requested_at DATETIME(6) NULL / processed_at DATETIME(6) NULL.

**Refund** — Claim 종속 · ARCHIVE · public_id `rfn_` — public_id CHAR(30) NN UK / claim_id BIGINT NN(FK) / payment_id BIGINT NN(FK·N:1) / amount BIGINT NN(PAY-1 Domain) / status ENUM(PENDING,COMPLETED,FAILED) NN(A#15) / refunded_at DATETIME(6) NULL / pg_refund_id VARCHAR(100) NULL.

#### 2.6 코드·공통 (5)

**CodeGroup** — Aggregate Code(Root) · SOFT(is_system=F만) · 미부여 — code VARCHAR(50) NN / name VARCHAR(100) NN / description TEXT NULL.

**Code** — Code 종속 · SOFT(is_system=F만) — group_id BIGINT NN(FK) / code VARCHAR(50) NN / label VARCHAR(100) NN / display_order INT NN / is_active BOOLEAN NN / is_system BOOLEAN NN(COD-1). UK uk_code_group_code(group_id,code)·COD-2.

**Attachment** — Aggregate Attachment(Root) · SOFT · public_id `att_` · polymorphic·FK 없음 — public_id CHAR(30) NN UK(ATT-2) / target_type VARCHAR(50) NN(D·ATT-1) / target_id BIGINT NN(논리참조) / file_name VARCHAR(200) NN / file_path VARCHAR(2048) NN / mime_type VARCHAR(100) NULL / file_size BIGINT NULL / display_order INT NN.

**AuditLog** — Aggregate AuditLog(Root) · ARCHIVE · public_id `aud_` · append-only·FK 없음 — public_id CHAR(30) NN UK(AUD-4) / actor_user_id BIGINT NULL / actor_role VARCHAR(50) NULL / action ENUM(CREATE,UPDATE,DELETE,APPROVE,REJECT,LOGIN,LOGOUT) NN(A#18) / target_type VARCHAR(50) NN(D) / target_id BIGINT NN / diff_json JSON NULL(CHECK JSON_VALID·AUD-3·D-11) / ip_address VARCHAR(45) NULL / user_agent VARCHAR(500) NULL.

**NotificationLog** — Infra/Event Processing(D-18) · ARCHIVE · 미부여 · FK 없음 — recipient_user_id BIGINT NULL / channel ENUM(EMAIL,SMS,PUSH,IN_APP) NN(A#16·NOT-2) / template_code VARCHAR(100) NN / target_type VARCHAR(50) NN(D·NOT-3) / target_id BIGINT NN / title VARCHAR(200) NULL / content TEXT NULL / status ENUM(PENDING,SENT,FAILED) NN(A#17) / sent_at DATETIME(6) NULL / failed_reason TEXT NULL.

#### 2.7 집계 (1)

**SellerSalesDaily** — Read Model · ARCHIVE · 미부여 · 복합 PK — seller_id BIGINT PK / sale_date DATE PK / order_count INT NN / gross_amount BIGINT NN / refund_amount BIGINT NN / net_amount BIGINT NN. FK 미적용(§1.9)·seller_id 논리참조.

### 3. 전역 정책 일괄 적용 항목

| 항목 | 적용 |
|---|---|
| charset/collation | utf8mb4 / utf8mb4_unicode_ci (전 테이블·컬럼) |
| 시간 | DATETIME(6)·UTC (timezone 컬럼 없음·DEFAULT 미지정) |
| 금액 | BIGINT (KRW 정수·DECIMAL 금지) |
| 내부 PK | BIGINT AUTO_INCREMENT (예외: BuyerProfile=user_id 공유 PK·BuyerPurchaseAggregate=buyer_id 단일 PK·SellerSalesDaily=복합 PK) |
| public_id | CHAR(30) (ULID 26+prefix 4)·UNIQUE — **12 테이블 한정** |
| public_id 12 대상 | User(usr_)·Seller(slr_)·Product(prd_)·ProductVariant(var_)·Order(ord_)·OrderItem(oit_)·Payment(pay_)·Delivery(dlv_)·Claim(clm_)·Refund(rfn_)·Attachment(att_)·AuditLog(aud_) |
| audit 컬럼 | §1.7 기준·실제 적용 분류는 작성 트랙 결정 1(5분류 규칙) |
| soft delete 3컬럼 | deleted_at·deleted_by·delete_reason — **SOFT 8 테이블 한정**(§1.8) |
| SOFT 8 대상 | User·Seller·Product·ProductVariant·Category·Attachment·UserAddress·ProductImage |
| created_by/updated_by/deleted_by | FK 없음(§1.7·시스템 작업 NULL 허용) |
| 인덱스 명명 | pk_·fk_·uk_·ix_ (§1.6) |

### 4. CHECK·ENUM 제약 일괄 표 (invariants → DB)

**순수 DB CHECK (수치·JSON) — 6건**
| invariant | 테이블 | CHECK 표현식 |
|---|---|---|
| INV-1 | inventory | quantity_available >= 0 |
| INV-3 | inventory | quantity_reserved >= 0 |
| INV-4 | inventory | quantity_on_hand >= 0 |
| GRD-3 | grade_policy | min_amount <= max_amount |
| CRT-2 | cart_item | quantity >= 1 |
| AUD-3 | audit_log | JSON_VALID(diff_json) |

**ENUM 값집합 잠금 (A분류 18·B분류 3 = 21 컬럼)** — `ENUM(...)`. B분류 reason_code는 VARCHAR(50)(§10-③ 채택).
| 분류 | 컬럼(테이블.컬럼) |
|---|---|
| A(18) | buyer_profile.grade_source·role.code·buyer_grade.code·seller_bank_account.status·settlement.status·product.status·product_variant.status·inventory_history.change_type·payment.method·payment.status·delivery.carrier·delivery.status·claim.type·claim.status·refund.status·notification_log.channel·notification_log.status·audit_log.action |
| B(3) | seller.status·order.status(8값)·order_item.item_status(12값) |
| B(VARCHAR) | claim.reason_code (ENUM 미적용·Code 시드 정합 COD-3) |

**D분류 polymorphic (4)** — VARCHAR·DB CHECK 면제(앱 검증): inventory_history.reference_type·attachment.target_type·audit_log.target_type·notification_log.target_type.

### 5. UNIQUE KEY 일괄 표

| 테이블 | UK 명 | 컬럼 | 근거 |
|---|---|---|---|
| user | uk_user_public_id / uk_user_email | (public_id) / (email) | COM-1 / USR-1·D-22 |
| seller | uk_seller_public_id / uk_seller_business_no | (public_id) / (business_no) | COM-1 / SLR-1·D-22 |
| product | uk_product_public_id | (public_id) | COM-1 |
| product_variant | uk_product_variant_public_id | (public_id) | COM-1 |
| product_variant | uk_product_variant_options | (product_id,option1_value_id,option2_value_id,option3_value_id) | PRD-2 |
| order | uk_order_public_id / uk_order_order_no | (public_id) / (order_no) | COM-1 / ORD-4 |
| order_item | uk_order_item_public_id | (public_id) | COM-1 |
| payment | uk_payment_public_id | (public_id) | COM-1 |
| delivery | uk_delivery_public_id / uk_delivery_tracking_no | (public_id) / (tracking_no) | COM-1 / DLV-1 |
| claim | uk_claim_public_id | (public_id) | COM-1 |
| refund | uk_refund_public_id | (public_id) | COM-1 |
| attachment | uk_attachment_public_id | (public_id) | COM-1·ATT-2 |
| audit_log | uk_audit_log_public_id | (public_id) | COM-1·AUD-4 |
| inventory | uk_inventory_variant | (variant_id) | INV-6(1:1) |
| cart_item | uk_cart_item_user_variant | (user_id,variant_id) | CRT-1 |
| user_role | uk_user_role | (user_id,role_id) | AUTH-1 |
| role_permission | uk_role_permission | (role_id,permission_id) | AUTH-2 |
| seller_user | uk_seller_user | (seller_id,user_id) | SLR-5 |
| code | uk_code_group_code | (group_id,code) | COD-2 |

> 복합 PK(UK 아님·PK): seller_sales_daily(seller_id,sale_date). 단일 PK: buyer_purchase_aggregate(buyer_id)·buyer_profile(user_id 공유 PK).

### 6. FK 일괄 표

> ON DELETE RESTRICT·ON UPDATE CASCADE 기본 (SOFT·ARCHIVE Root 물리 삭제 미발생·deletion-policy 정합).

| 테이블 | FK 명 | 컬럼 → 참조 |
|---|---|---|
| withdrawn_user | fk_withdrawn_user_user | original_user_id → user.id |
| buyer_profile | fk_buyer_profile_user / fk_buyer_profile_grade | user_id → user.id / grade_id → buyer_grade.id |
| user_address | fk_user_address_user | user_id → user.id |
| user_role | fk_user_role_user / fk_user_role_role | user_id → user.id / role_id → role.id |
| role_permission | fk_role_permission_role / fk_role_permission_permission | role_id → role.id / permission_id → permission.id |
| seller_user | fk_seller_user_seller / fk_seller_user_user / fk_seller_user_role | seller_id → seller.id / user_id → user.id / role_id → role.id |
| grade_policy | fk_grade_policy_grade | grade_id → buyer_grade.id |
| seller_bank_account | fk_seller_bank_account_seller | seller_id → seller.id |
| settlement | fk_settlement_seller / fk_settlement_bank_account | seller_id → seller.id / bank_account_id → seller_bank_account.id |
| code | fk_code_group | group_id → code_group.id |
| category | fk_category_parent | parent_id → category.id (nullable·self) |
| product | fk_product_seller / fk_product_category | seller_id → seller.id / category_id → category.id |
| product_image | fk_product_image_product | product_id → product.id |
| product_option_group | fk_product_option_group_product | product_id → product.id |
| product_option_value | fk_product_option_value_group | option_group_id → product_option_group.id |
| product_variant | fk_product_variant_product / fk_product_variant_option1 / fk_product_variant_option2 / fk_product_variant_option3 | product_id → product.id / option1~3_value_id → product_option_value.id |
| inventory | fk_inventory_variant | variant_id → product_variant.id |
| inventory_history | fk_inventory_history_inventory | inventory_id → inventory.id |
| cart_item | fk_cart_item_user / fk_cart_item_variant | user_id → user.id / variant_id → product_variant.id |
| order | fk_order_user | buyer_id → user.id |
| order_item | fk_order_item_order / fk_order_item_product / fk_order_item_variant / fk_order_item_seller | order_id → order.id / product_id → product.id / variant_id → product_variant.id / seller_id → seller.id |
| order_shipping_snapshot | fk_order_shipping_snapshot_order | order_id → order.id |
| payment | fk_payment_order | order_id → order.id |
| delivery | fk_delivery_order_item | order_item_id → order_item.id |
| claim | fk_claim_order_item | order_item_id → order_item.id |
| refund | fk_refund_claim / fk_refund_payment | claim_id → claim.id / payment_id → payment.id |

> FK 미적용(§1.9): attachment·audit_log·notification_log(polymorphic)·buyer_purchase_aggregate·seller_sales_daily(집계)·created_by/updated_by/deleted_by·claim.requested_by·notification_log.recipient_user_id·audit_log.actor_user_id(논리참조). InnoDB는 FK 선언 시 자식 컬럼 인덱스 자동 생성. FK 총 41건(code→code_group 포함).

### 7. INDEX 일괄 표 (index-strategy → CREATE INDEX 후보)

> index-strategy는 패턴·전략만 정의. 아래는 문서 명시 후보 한정(카디널리티 추정 보조 인덱스 금지).

| 테이블 | INDEX 후보 | 컬럼 |
|---|---|---|
| order | ix_order_buyer_status | (buyer_id,status) |
| order_item | ix_order_item_seller_status | (seller_id,item_status) |
| product | ix_product_category_status / ix_product_status | (category_id,status) / (status) |
| seller | ix_seller_status | (status) |
| claim | ix_claim_status | (status) |
| settlement | ix_settlement_seller_status / ix_settlement_period | (seller_id,status) / (period_start,period_end) |
| cart_item | (생략·uk_cart_item_user_variant 좌측 prefix가 user_id 커버) | — |
| inventory | ix_inventory_available | (quantity_available) |
| attachment | ix_attachment_target | (target_type,target_id) |
| audit_log | ix_audit_log_target / ix_audit_log_actor | (target_type,target_id,created_at) / (actor_user_id,created_at) |
| notification_log | ix_notification_log_target | (target_type,target_id) |
| SOFT 8 테이블 | ix_{table}_deleted_at | (deleted_at) — §4.2 일반 인덱스 형 채택 |
| buyer_purchase_aggregate | ix_bpa_last_ordered | (last_ordered_at) |

### 8. 검증 체크

| 항목 | 결과 |
|---|---|
| 37 테이블 전수 매핑 완료 | Yes (§1·§2 37건) |
| FK 위상정렬 순환 없음 | Yes (Category self-FK nullable·사이클 0) |
| invariants 62건 → CHECK/UK/ENUM/Service 분류 완료 | Yes (DB CHECK 6·ENUM 21·UK 19행+) |
| index-strategy 전수 반영 | Yes (패턴·후보·실제 명세 DDL 트랙 확정) |
| deletion-policy SOFT 대상 deleted_* 부착 | Yes (SOFT 8·§1.8) |
| audit 컬럼 부착 | 작성 트랙 결정 1(5분류)로 확정 |
| public_id 부여 대상 12 테이블 일치 | Yes (aud_ 포함) |
| db-schema §3 테이블 목록 37 = 본 정찰 37 | 일치 |

### 9. 작성 트랙 입력 명세

- 단일 SQL 파일: `V1__init.sql` (docs/ddl/·결정 2)
- 생성 순서: §1 위상정렬 1→37
- 테이블별 DDL: §2 컬럼 + §3 전역 + §4 CHECK/ENUM + §5 UK + §6 FK + §7 INDEX
- audit 컬럼: 결정 1 5분류 규칙

### 10. 발견 사항 (작성 트랙 사용자 확정 완료)

- ① audit 컬럼 적용 범위 → **결정 1 5분류 규칙 채택**(핵심 full / append-only / 매핑 / 시드성 / 집계).
- ② 집계 테이블 FK 표기 충돌(ERD↔§1.9) → **FK 미적용**(§1.9 SoT 우선).
- ③ Claim.reason_code 값집합 미확정 → **VARCHAR(50) + Code 시드 정합**(ENUM·FK 미적용).
- ④ zonecode 길이(§2.1 "5자리" vs ERD VARCHAR(10)) → **VARCHAR(10)**.
- ⑤ "17 Aggregate" 표현 lag(D-13·ddl-ready-checklist §2) → 본 트랙 범위 외·TODO 등재.

---

> **V2 propagation note (D-23·2026-06-24)**: 본 정찰은 V1__init.sql(37 테이블) 스코프다. V2(withdrawn_seller CREATE·seller company_name·ceo_name NULL ALTER)는 별도 마이그레이션이며 본 V1 정찰 범위 외. 현재 스키마 누적 총계는 38 테이블(db-schema §3·deletion-policy §2.3·ERD README).
