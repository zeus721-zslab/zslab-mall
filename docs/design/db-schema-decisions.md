# DB 스키마 의사결정 종합 (ERD 진입 인풋) — v2.5
 
ERD/DDL 작성의 단일 레퍼런스. 이 문서만으로 스키마 재구성 가능해야 함.
 
> **v2 변경 요약**: 과잉 설계 7건 정리.
> 삭제: `CodeTransition`, `ProductVariantOptionValue`, `SellerSalesMonthly`, `grade_changed_reason`, `Inventory.warehouse_id`, `Cart`(헤더), `Code.color`
> 변경: ProductVariant 옵션 연결 → `option1~3_value_id` 컬럼식 / Cart → CartItem 단일 / 상품이미지 → ProductImage 전용 분리
>
> **v2.1 변경**: `public_id` 부여 범위 명시 (외부 노출 엔티티만 한정).
>
> **v2.2 변경**: `SellerBankAccount` 추가 (정산 계좌 별도 분리·암호화) / `NotificationLog` 추가 (알림 발송 이력).
>
> **v2.3 변경**: §1.13 신규 — enum 분류 정책 확정 (A. 잠금 / B. Code 참조 / D. polymorphic). 23개 type/status 컬럼 카테고리 부착. Java enum 정책 4건 (네이밍·패키지·EnumType.STRING 고정·라벨 정책) 확정.
>
> **v2.4 변경**: erd-update 트랙 — §1.13 B#2·B#3 값집합 확정 (Order.status 8값·OrderItem.item_status 12값)·§4 보류 3건 해소 (D-09·D-11·D-04 확정)·§2.2 BuyerPurchaseAggregate 갱신 트리거 E6 PurchaseConfirmed 정합·§1.1 prefix 예 aud_ 추가 (12건 정합).
>
> **v2.5 변경**: Track 3 SoT 정정 — §1.1 prefix `pat_` 추가 (payment_attempt_key·D-35) · §2.5 Payment 컬럼 3종 추가 (`failure_code`·`payment_attempt_key`·`expires_at`·D-31·D-32·D-35) + 제약 3건 명시 (UNIQUE (pg_provider, pg_tid)·UNIQUE (payment_attempt_key)·CHECK (pg_tid IS NULL OR pg_provider IS NOT NULL)).
 
---
 
## 0. 범위·전제
 
- 상업 운영 가능한 입점형(Marketplace) 쇼핑몰
- 운영자 편의 극대화 + 유지보수성 + 정합성 우선
- 1차 도메인 범위: **B (상업 운영 1차)** — 약 25~30 테이블
- 1차 제외(2차 검토): Coupon, Promotion, Point, Review, Notification, Banner, SearchIndex, Warehouse
- DB: MariaDB (공유 `zslab_mariadb` / 스키마 `zslab_mall`)
- 설계 원칙: **확장 가능성보다 운영 가능성 우선**. 미래 확장 자리 확보용 컬럼·테이블은 도입 시점에 추가.
---
 
## 1. 글로벌 정책
 
### 1.1 PK·식별자
| 항목 | 규칙 |
|---|---|
| 내부 PK | `BIGINT AUTO_INCREMENT` |
| 외부 노출 ID | `public_id CHAR(30)` (ULID 26자 + prefix 4자 = 30자 고정) |
| prefix 예 | `usr_`, `ord_`, `oit_`, `prd_`, `var_`, `slr_`, `pay_`, `dlv_`, `clm_`, `rfn_`, `att_`, `aud_`, `pat_` |
| 생성 시점 | 애플리케이션 INSERT 전 ULID 생성 |
| 제약 | `public_id` UNIQUE INDEX 필수 |
 
**public_id 부여 범위** (외부 노출 가능성·URL 사용 기준):
 
| 부여 | 미부여 |
|---|---|
| User, Seller, Product, ProductVariant, Order, OrderItem, Payment, Delivery, Claim, Refund, Attachment, AuditLog | Settlement, Category, Code, CodeGroup, BuyerGrade, GradePolicy, BuyerProfile, UserAddress, ProductImage, ProductOptionGroup, ProductOptionValue, Inventory, InventoryHistory, CartItem, OrderShippingSnapshot, Role, Permission, UserRole, RolePermission, SellerUser, WithdrawnUser, WithdrawnSeller, SellerSalesDaily, BuyerPurchaseAggregate |
 
> `pat_`(`payment_attempt_key`): Payment 행 자체의 `public_id`(pay_)와 **별개** 키. 결제 시도 식별자·PG 전달·콜백 매핑 전용. 외부 API URL 직접 노출 없음 — D-35.
> 외부 노출 없는 내부 엔티티·매핑 테이블·집계 테이블은 BIGINT id로 충분. 인덱스 비용 절감.
> public_id 부여 기준(외부 직접 참조 가능성)은 invariants.md COM-1 단일 정의 참조 (외부 검토 2차).
 
### 1.2 시간
| 항목 | 규칙 |
|---|---|
| 저장 | UTC, `DATETIME(6)` |
| timezone 컬럼 | **없음** (row-level 미보유) |
| 세션 timezone | `Asia/Seoul` (DB 세션 설정) |
| 표시 변환 | 표시 계층(서버/프론트)에서 수행 |
 
### 1.3 금액
- KRW 전제: `BIGINT` (원 단위 정수)
- DECIMAL 미사용
### 1.4 문자열 길이 표준
| 용도 | 타입 |
|---|---|
| email | VARCHAR(254) |
| name | VARCHAR(50) |
| phone | VARCHAR(20) |
| title | VARCHAR(200) |
| url | VARCHAR(2048) |
| code | VARCHAR(50) |
| public_id | CHAR(30) |
| memo | TEXT |
| description | LONGTEXT |
 
> 무지성 VARCHAR(255) 금지.
 
### 1.5 Charset / Collation
- `utf8mb4` / `utf8mb4_unicode_ci` (테이블·컬럼 일괄)
### 1.6 인덱스 명명
| 종류 | 규칙 | 예 |
|---|---|---|
| PK | `pk_{table}` | pk_user |
| FK | `fk_{table}_{ref}` | fk_order_user |
| Unique | `uk_{table}_{column}` | uk_user_email |
| Index | `ix_{table}_{column}` | ix_order_status |
| 복합 | `ix_{table}_{col1}_{col2}` | ix_order_status_created |
 
### 1.7 Audit 컬럼 (공통)
모든 핵심 테이블:
```
created_at  DATETIME(6) NOT NULL
created_by  BIGINT      NULL    -- user_id, FK 없음 (시스템 작업 허용)
updated_at  DATETIME(6) NOT NULL
updated_by  BIGINT      NULL    -- FK 없음
```
 
소프트 삭제 적용 테이블 추가:
```
deleted_at      DATETIME(6) NULL
deleted_by      BIGINT      NULL  -- FK 없음
delete_reason   VARCHAR(255) NULL
```
 
### 1.8 소프트 삭제 적용 범위
| 적용 | 미적용 |
|---|---|
| User, Seller, Product, ProductVariant, Category, Attachment, UserAddress, ProductImage | Order, OrderItem, Payment, Settlement, Claim, Refund, AuditLog, Inventory, InventoryHistory, 집계 테이블, CartItem, WithdrawnSeller(ARCHIVE) |
 
> 주문·결제·정산은 "삭제"가 아니라 "상태 관리"로 처리.
> → 상세 분류(SOFT/HARD/ARCHIVE)·경계 케이스·비식별화 흐름은 `docs/architecture-baseline/deletion-policy.md` 단일 레퍼런스 참조.
 
### 1.9 외래키(FK) 적용 범위
| 적용 (강한 결합) | 미적용 (느슨/성능) |
|---|---|
| Order ↔ OrderItem, Product ↔ Variant, User ↔ Address, Seller ↔ Product 등 핵심 도메인 관계 | AuditLog, Attachment (polymorphic), 집계 테이블, created_by/updated_by, VIEW 참조 |
 
### 1.10 멀티테넌시(셀러 격리)
- 공유 스키마 + `seller_id` 컬럼 패턴
- 자사 스코프 강제: 애플리케이션 레이어에서 자동 주입 (ORM 비종속, AOP/Interceptor 패턴)
- 권한 계층: `platform_scope` / `seller_scope` 분리
### 1.11 조회 최적화 전략
| 케이스 | 방식 |
|---|---|
| 단순 JOIN, 조건 조회, 페이징 | `CREATE ALGORITHM=MERGE VIEW` |
| GROUP BY / DISTINCT / UNION / 대량 집계 | 집계 테이블 (배치 갱신) 또는 즉시 집계 VIEW |
| 이벤트 기반 실시간 집계 | Aggregate 테이블 (이벤트 핸들러 갱신) |
 
VIEW 예: `vw_order_admin`, `vw_seller_dashboard`, `vw_seller_sales_monthly`(Daily 즉시 집계)
집계 예: `seller_sales_daily`, `buyer_purchase_aggregate`
 
> CQRS 본격 도입은 트래픽 증가 시점에 재검토.
 
### 1.12 상태 전이 관리 (변경)
- ❌ DB 테이블(`CodeTransition`)로 관리 안 함
- ✅ 코드 레이어(Java enum + Service 메서드 `canTransition(from, to, role)`)에서 관리
- 운영자 편집 가능 범위: 상태 라벨(`Code.label`), 표시 순서, 활성화 여부
- 상태 전이 규칙 자체는 코드로 박음 (배포로 변경)

### 1.13 신규 type/status enum 분류 정책

CLAUDE.md "4층위 enum 잠금 의무"를 ERD에 적용. 모든 type/status 컬럼은 아래 3분류 중 하나로 명시 분류.

#### 분류 정의

| 분류 | 정의 | DB 컬럼 타입 | 4층위 룰 |
|---|---|---|---|
| **A. 잠금** | 시스템 의존 상태값. 값 추가 = 마이그레이션 필수 | `ENUM(...)` | 전체 적용 |
| **B. Code 참조** | A와 동일하나 운영자가 Code 테이블에서 label·정렬·활성 편집 가능. 코드 값 자체는 시스템 의존 | `ENUM(...)` + Code 테이블 조인 (varchar code ↔ 매칭, FK 없음) | 전체 적용 |
| **D. polymorphic** | 도메인 엔티티 이름 등 동적 확장값 (target_type·reference_type). 무한 확장 | `VARCHAR` + 애플리케이션 enum 검증 (DB CHECK 미적용) | (1) DB 잠금 면제, (2)~(4) 적용 |

> "확장형(C)"은 본 정책에서 A로 통합. 신규 값은 마이그레이션으로 추가하여 일관성 확보·라이브 트랩 방지.

#### Java enum 정책 (E-2 ~ E-5)
- 네이밍: `{Entity}{Suffix}` — Suffix는 `Status`·`Type`·`Method`·`Channel`·`Carrier` 등 컬럼 의미에 부합
- 패키지: `com.zslab.mall.{domain}.enums` (도메인별 분산, DDD 정합)
- DB 저장: JPA `@Enumerated(EnumType.STRING)` 고정. `ORDINAL` 금지
- 라벨(한글 표시): A·B 분류는 Code 테이블 조인으로 운영자 편집 가능 유지 (enum 내부 label 필드 보유 금지). D 분류는 도메인별 enum 내부 label 또는 i18n 리소스 허용

#### 분류 카테고리 (26개 컬럼 = type/status 24 + code 2)

**A. 잠금 (18건)**

| # | 엔티티 | 컬럼 | 값 집합 |
|---|---|---|---|
| 1 | BuyerProfile | grade_source | AUTO, MANUAL, EVENT |
| 2 | Role | code | SUPER_ADMIN, ADMIN_OPERATOR, BUYER, SELLER_OWNER, SELLER_MANAGER, SELLER_STAFF |
| 3 | BuyerGrade | code | SILVER, GOLD, PLATINUM |
| 4 | SellerBankAccount | status | PENDING, VERIFIED, REJECTED |
| 5 | Settlement | status | PENDING, CONFIRMED, PAID |
| 6 | Product | status | DRAFT, PENDING, APPROVED, REJECTED, SALE, HIDDEN, STOPPED |
| 7 | ProductVariant | status | SALE, HIDDEN, STOPPED |
| 8 | InventoryHistory | change_type | ORDER, CANCEL, RETURN, ADJUST, INBOUND, OUTBOUND |
| 9 | Payment | method | CARD, BANK, VBANK, KAKAO (확장 시 마이그레이션) |
| 10 | Payment | status | PENDING, PAID, FAILED, CANCELLED |
| 11 | Delivery | carrier | CJ, HANJIN, POST, LOGEN (확장 시 마이그레이션) |
| 12 | Delivery | status | READY, SHIPPING, DELIVERED |
| 13 | Claim | type | CANCEL, RETURN, EXCHANGE |
| 14 | Claim | status | REQUESTED, APPROVED, REJECTED, COMPLETED |
| 15 | Refund | status | PENDING, COMPLETED, FAILED |
| 16 | NotificationLog | channel | EMAIL, SMS, PUSH, IN_APP |
| 17 | NotificationLog | status | PENDING, SENT, FAILED |
| 18 | AuditLog | action | CREATE, UPDATE, DELETE, APPROVE, REJECT, LOGIN, LOGOUT (확장 시 마이그레이션) |

**B. Code 참조 (4건)**

| # | 엔티티 | 컬럼 | 값 집합 | Code Group |
|---|---|---|---|---|
| 1 | Seller | status | PENDING, ACTIVE, SUSPENDED, TERMINATED | SELLER_STATUS |
| 2 | Order | status | PENDING_PAYMENT, PAID, PREPARING, SHIPPING, DELIVERED, CONFIRMED, CANCELLED, PARTIAL_CANCEL (8값·D-02·state-machine §4) | ORDER_STATUS |
| 3 | OrderItem | item_status | ORDERED, PAID, PREPARING, SHIPPING, DELIVERED, CONFIRMED, CANCEL_REQUESTED, CANCELLED, RETURN_REQUESTED, RETURNED, EXCHANGE_REQUESTED, EXCHANGED (12값·D-03·state-machine §3) | ORDER_ITEM_STATUS |
| 4 | Claim | reason_code | (운영자 관리 — 단순변심, 파손, 불량, 주소오류 등) | CLAIM_REASON |

> B 분류의 ENUM 값과 Code 테이블 데이터는 시드(seed) 단계에서 일치 보장. 운영자는 Code 테이블의 label·display_order·is_active만 편집. 코드 값(code) 신규 추가는 마이그레이션 + Code 시드 동시 수행.

**D. polymorphic (4건)**

| # | 엔티티 | 컬럼 | 비고 |
|---|---|---|---|
| 1 | InventoryHistory | reference_type | Order, Claim, Manual + 신규 도메인 추가 가능 |
| 2 | Attachment | target_type | CLAIM, REVIEW, SELLER_DOCUMENT + 신규 도메인 추가 가능 |
| 3 | AuditLog | target_type | 모든 엔티티명 (대상 무제한) |
| 4 | NotificationLog | target_type | Order, Claim, Settlement + 신규 도메인 추가 가능 |

> polymorphic은 신규 도메인 추가 시 자동 확장. DB CHECK 적용 시 도메인 추가마다 마이그레이션 필요 → 트레이드오프상 DB 제약 면제. 단, 애플리케이션 레이어에서 enum 또는 화이트리스트 검증 의무.
---
 
## 2. 도메인별 엔티티 정의
 
### 2.1 회원 / 권한
 
#### User
- 계정 기본. 소프트 삭제(`withdrawn_at`)
- 탈퇴 시점 = 로그인 차단
- `anonymized_at` = 비식별화 완료 시점 (배치 처리)
#### WithdrawnUser
```
original_user_id
withdraw_reason
legal_retention_until   -- 전자상거래법 5년 등
anonymized_at
```
탈퇴 흐름: 탈퇴 → 로그인 차단 → 법정보관기간 유지 → 배치 → 비식별화
 
비식별화 시:
- email → NULL
- phone → HASH
- name → NULL
- user_id, order_id 등 식별자는 유지 (정합성 보존)
재가입 허용 조건: 비식별화 완료 상태 (email NULL 처리 완료 후 재사용 허용) — D-22
Seller 비식별화 시 business_no도 NULL 처리 대상 — 구체 흐름은 §2.3 Seller 비식별화 흐름·D-23
#### BuyerProfile (User 1:0..1 확장)
```
user_id PK FK(User.id)
grade_id FK
grade_source         ENUM(AUTO, MANUAL, EVENT)
grade_locked_until   nullable
grade_updated_at
```
> `grade_changed_reason` 제거 — AuditLog로 통일. 운영 화면에서 사유 표시 필요 시 `vw_buyer_grade_history` VIEW로 대응.
 
#### UserAddress (주소록)
```
user_id FK
is_default
address_label        -- 집/회사/기타
recipient_name
recipient_phone
zonecode             -- 우편번호 5자리
address_road         -- 도로명
address_jibun        -- 지번 (nullable)
address_detail       -- 상세
```
> Daum 우편번호 API 응답 구조 매핑.
 
#### Role / Permission / UserRole / RolePermission
- 표준 RBAC (User N:M Role, Role N:M Permission)
- SYSTEM·BUYER 권한용
- 역할 코드: SUPER_ADMIN, ADMIN_OPERATOR, BUYER, SELLER_OWNER, SELLER_MANAGER, SELLER_STAFF
#### SellerUser (판매자 내부 권한 — 별도 컨텍스트)
```
seller_id FK
user_id FK
role_id FK
```
> 한 User가 여러 Seller에 서로 다른 역할로 소속 가능.
 
---
 
### 2.2 등급
 
#### BuyerGrade
```
id, code, name
```
예: SILVER, GOLD, PLATINUM
 
#### GradePolicy (버전 관리)
```
grade_id FK
min_amount
max_amount
discount_rate
point_rate
effective_from
effective_to
version
is_active
```
활성 정책 결정:
```sql
WHERE grade_id = ?
  AND NOW() BETWEEN effective_from AND effective_to
  AND is_active = TRUE
ORDER BY version DESC LIMIT 1
```
 
#### BuyerPurchaseAggregate
```
buyer_id PK
lifetime_purchase_amount  -- 원천 (BuyerProfile 미보유)
last_ordered_at
updated_at
```
갱신: E6 PurchaseConfirmed 이벤트 (Order CONFIRMED 전이 시) → Aggregate SUM 누적 → GradeEvaluator → BuyerProfile.grade_id (docs/architecture-baseline/read-model.md·domain-events.md §E6 참조)
 
---
 
### 2.3 판매자
 
#### Seller
```
id, public_id, company_name NULL, status (Code 참조)
business_no NULL, ceo_name NULL, contact_email NULL, contact_phone NULL
status: PENDING / ACTIVE / SUSPENDED / TERMINATED
```
> `company_name`·`ceo_name`: V1 NOT NULL → V2에서 NULL 허용 (비식별화 대상·D-23). 활성 판매자 필수값은 Service 검증으로 강제.
> `contact_email`·`contact_phone`: V1에서 이미 NULL — 배치 비식별화 대상.
> `business_no` NULL 허용 — 비식별화 시 NULL 처리·UK 슬롯 해제 (D-22·D-23 정합).
 
#### WithdrawnSeller (종료 판매자 아카이브 — ARCHIVE·D-23)
```
id (PK)
original_seller_id     FK(Seller.id)  -- NOT NULL·논리 종속
terminate_reason       NULL           -- 종료 사유
legal_retention_until  NULL           -- 법정 보관 만료 시점
anonymized_at          NULL           -- 비식별화 완료 시점
+ audit 4 (created_at·updated_at·created_by·updated_by)
```
> WithdrawnUser 패턴 준용 (회원 §2.1). public_id 없음·ARCHIVE 분류. Seller TERMINATED 진입 시 행 생성 (SLR-6).
 
**Seller 비식별화 흐름** (D-23·User 패턴 대칭):
```
Seller TERMINATED 진입
  → WithdrawnSeller 행 생성 (terminate_reason·legal_retention_until)
  → 법정 보관 기간 유지
  → 배치 → anonymized_at 마킹·비식별화
           company_name·ceo_name·contact_email·contact_phone·business_no → NULL
           SellerBankAccount.account_number → 암호화 키 폐기 (NOT NULL 유지·복호화 불가)
  → 재등록 허용 (business_no UK 슬롯 해제·D-22 정합)
```
 
#### SellerBankAccount (정산 계좌 — 별도 분리)
```
seller_id FK
bank_code           -- 은행 코드 (표준 코드)
account_number      -- 암호화 저장 (AES)
account_holder      -- 예금주
is_primary          -- 정산 기본 계좌
verified_at         -- 계좌 실명확인 완료 시점
status              -- PENDING / VERIFIED / REJECTED
```
> 평문 저장 금지 (account_number 암호화). 계좌 변경 시 신규 row 추가 + 기존 row `is_primary=false` 처리 (이력 보존). Settlement는 정산 시점의 `bank_account_id` 스냅샷 보유 권장.
> 비식별화: `account_number`는 NULL 대신 **암호화 키 폐기**로 비식별화 — 컬럼·NOT NULL 유지·복호화 불가 → Settlement 스냅샷 정합 보존 (D-23·SLR-2 정합).
 
#### Settlement
```
seller_id FK
bank_account_id FK  -- 정산 시점 계좌 스냅샷
period_start / period_end
gross_amount        -- 매출 총액
fee_amount          -- 플랫폼 수수료
refund_amount       -- 환불 차감
net_amount          -- 정산액
status              -- PENDING / CONFIRMED / PAID
paid_at
```
 
---
 
### 2.4 상품·재고
 
#### Category
- 계층 구조. `parent_id` self FK
- depth / sort_order / display_name
#### Product
```
seller_id FK
category_id FK
name, description
status                -- DRAFT / PENDING / APPROVED / REJECTED / SALE / HIDDEN / STOPPED
base_price
thumbnail_url         -- 대표 썸네일 (단일)
```
 
#### ProductImage (신규 분리)
```
product_id FK
image_url
display_order
is_main               -- 메인 이미지 표시 여부
```
> 상품 이미지 전용. 다중 이미지·정렬·대표 지정. Attachment polymorphic과 분리.
 
#### ProductOptionGroup
```
product_id FK
name              -- "색상", "사이즈"
display_order
```
> 한 상품당 최대 3개 그룹 (애플리케이션 제약).
 
#### ProductOptionValue
```
option_group_id FK
value             -- "빨강", "L"
display_order
```
 
#### ProductVariant (SKU) — 변경
```
product_id FK
variant_code        -- 시스템 생성
seller_sku          -- 판매자 입력
barcode
additional_price    -- 본가 대비 추가금
status              -- SALE / HIDDEN / STOPPED  (운영자 의도)
is_soldout_manual   -- 운영자 강제 품절
display_order
 
option1_value_id FK(ProductOptionValue.id)         -- 필수
option2_value_id FK(ProductOptionValue.id) NULL
option3_value_id FK(ProductOptionValue.id) NULL
```
 
> **변경**: `ProductVariantOptionValue` (M:N 매핑) 제거 → 컬럼식 채택.
> 옵션 그룹 최대 3개 제한 (한국 쇼핑몰 표준).
> UNIQUE: `(product_id, option1_value_id, option2_value_id, option3_value_id)`
 
판매 가능 표시 로직:
```
if status != SALE          → 비노출
elif stock_available <= 0  → 품절
elif is_soldout_manual     → 품절
else                       → 판매중
```
 
#### Inventory (실재고) — 변경
```
variant_id FK
quantity_on_hand
quantity_reserved     -- 주문 점유
quantity_available    -- 컬럼 캐시 (= on_hand - reserved)
updated_at
```
> **변경**: `warehouse_id` 제거. 단일 창고 전제. Warehouse 도입 시 컬럼 추가.
 
#### InventoryHistory (변동 이력)
```
inventory_id FK
change_type        -- ORDER / CANCEL / RETURN / ADJUST / INBOUND / OUTBOUND
quantity_delta     -- 음수/양수
reference_type     -- Order / Claim / Manual
reference_id
reason
created_at / created_by
```
 
---
 
### 2.5 주문·결제·배송
 
#### CartItem — 변경
```
user_id FK
variant_id FK
quantity
selected            -- 주문 선택 여부
created_at
```
> **변경**: `Cart` 헤더 제거. CartItem 단일 테이블.
> UNIQUE: `(user_id, variant_id)`
> 다중 카트(위시리스트·정기구독) 도입 시 `cart_id` 컬럼 추가.
 
#### Order
```
buyer_id FK
order_no              -- 표시용 (예: 20260623-A1B2)
status                -- Code 참조
total_price
discount_amount
shipping_fee
paid_at
ordered_at
```
 
#### OrderItem
```
order_id FK
product_id FK
variant_id FK
seller_id FK          -- 멀티벤더 정산 핵심
quantity
unit_price
total_price
item_status           -- 항목별 상태 (배송·클레임 단위)
```
 
#### OrderShippingSnapshot (주문 시점 배송정보)
```
order_id FK
recipient_name
recipient_phone
zonecode
address_road
address_jibun
address_detail
delivery_memo
```
> UserAddress 변경과 무관하게 주문 당시 정보 보존.
 
#### Payment
```
order_id FK
public_id                              -- CHAR(30) prefix pay_
payment_attempt_key CHAR(30) NOT NULL  -- prefix pat_·D-35. 결제 시도 식별자. PG metadata 전달·콜백 매핑 1차 키
method         -- CARD / BANK / VBANK / KAKAO / ...
amount
status         -- PENDING / PAID / FAILED / CANCELLED
pg_provider
pg_tid         -- PG 거래 ID. NULL 허용 (VBANK 등 pg_tid 미발급 케이스)
failure_code   -- VARCHAR(50) NULL. 결제 실패 사유 코드 (PaymentFailed 발행 시 채움·D-34)
paid_at
expires_at     -- DATETIME(6) NULL. PENDING 결제 만료 시각 (+30분 기본). 만료 시 자동 배치가 PENDING→FAILED 전이 (Track 25 D-109·state-machine §1). idx_payment_expire (status, expires_at) 인덱스가 만료 배치 조회 커버 (V8)
```

> **추가 제약** <!-- D-31·D-32·D-35 반영 -->
> - UNIQUE `(pg_provider, pg_tid)`: PAY-3b — pg_tid 존재 시 PG 거래 중복 차단
> - UNIQUE `(payment_attempt_key)`: D-35
> - CHECK `(pg_tid IS NULL OR pg_provider IS NOT NULL)`: pg_tid 존재 시 provider 필수
 
#### Delivery
```
order_item_id FK   -- OrderItem 단위 (부분 배송 지원)
carrier            -- CJ / 한진 / 우체국 ...
tracking_no
status             -- READY / SHIPPING / DELIVERED
shipped_at
delivered_at
```
 
#### Claim (취소/반품/교환 요청)
```
order_item_id FK
type           -- CANCEL / RETURN / EXCHANGE
reason_code    -- B분류·CLAIM_REASON Code Group 참조 (운영자 관리)
reason_detail
status         -- REQUESTED / APPROVED / REJECTED / COMPLETED
requested_by
requested_at
processed_at
```
 
#### Refund
```
claim_id FK
payment_id FK
amount
status         -- PENDING / COMPLETED / FAILED
refunded_at
pg_refund_id
```
 
---
 
### 2.6 코드·상태 관리 — 변경
 
#### CodeGroup
```
code            -- ORDER_STATUS / PAYMENT_STATUS / CLAIM_TYPE ...
name
description
```
 
#### Code
```
group_id FK
code            -- PAID, READY, SHIPPING ...
label           -- 운영자 수정 가능 (예: "결제완료" → "입금확인")
display_order
is_active
is_system       -- TRUE면 운영 UI에서 삭제·비활성 불가
```
 
> **변경**:
> - `color` 컬럼 제거 (디자인 시스템 영역)
> - `CodeTransition` 테이블 제거 (상태 전이는 코드 레이어 enum + Service)
> - `is_system` 필수 유지 — 시스템 의존 코드 보호
 
상태 전이 검사 패턴 (코드 레이어):
```java
// 아래 값은 패턴 설명용 예시. Order.status 최종 값 집합은 §1.13 B분류 확정 시점에 결정.
enum OrderStatus {
    PAID, READY, SHIPPING, DELIVERED, CONFIRMED, ...;
 
    boolean canTransitionTo(OrderStatus next, Role role) {
        // 전이 매트릭스 + 권한 검사
    }
}
```
 
---
 
### 2.7 공통
 
#### Attachment (polymorphic, FK 없음) — 범위 축소
```
public_id
target_type     -- CLAIM / REVIEW / SELLER_DOCUMENT ...  (PRODUCT 제외)
target_id
file_name
file_path
mime_type
file_size
display_order
```
> **변경**: 상품 이미지는 `ProductImage` 전용 테이블로 분리. Attachment는 클레임·리뷰·판매자 서류 등 비정형 첨부 전용.
 
#### AuditLog (FK 없음)
```
public_id
actor_user_id        -- nullable (시스템 작업)
actor_role
action               -- CREATE / UPDATE / DELETE / APPROVE ...
target_type
target_id
diff_json            -- 변경 전후 JSON
ip_address
user_agent
created_at
```
 
#### NotificationLog (발송 이력)
```
recipient_user_id    -- nullable (시스템 발송 가능)
channel              -- EMAIL / SMS / PUSH / IN_APP
template_code        -- ORDER_PAID / DELIVERY_STARTED / CLAIM_APPROVED ...
target_type          -- Order / Claim / Settlement ...
target_id
title
content              -- 발송 본문 스냅샷
status               -- PENDING / SENT / FAILED
sent_at
failed_reason
created_at
```
> 1차 범위는 **발송 이력만**. 발송 트리거·재시도·템플릿 관리는 2차.
> 운영자가 "이 고객에게 알림 갔나?" 즉시 조회 가능.
 
---
 
### 2.8 집계 (배치/이벤트 갱신) — 축소
 
#### SellerSalesDaily
```
seller_id, sale_date PK
order_count
gross_amount
refund_amount
net_amount
```
 
> **변경**: `SellerSalesMonthly` 테이블 제거.
> 월간 집계는 `vw_seller_sales_monthly` VIEW로 즉시 집계 (Daily 30개 GROUP BY는 부하 없음).
 
#### BuyerPurchaseAggregate
- 2.2 등급 섹션 참조
---
 
## 3. 최종 테이블 목록
 
총 **38개**:
 
**회원·권한 (9)**
- User, WithdrawnUser, BuyerProfile, UserAddress
- Role, Permission, UserRole, RolePermission, SellerUser
**등급 (3)**
- BuyerGrade, GradePolicy, BuyerPurchaseAggregate
**판매자 (4)**
- Seller, WithdrawnSeller, SellerBankAccount, Settlement
**상품·재고 (8)**
- Category, Product, ProductImage
- ProductOptionGroup, ProductOptionValue, ProductVariant
- Inventory, InventoryHistory
**주문·결제·배송 (8)**
- CartItem, Order, OrderItem, OrderShippingSnapshot
- Payment, Delivery, Claim, Refund
**코드·공통 (5)**
- CodeGroup, Code
- Attachment, AuditLog, NotificationLog
**집계 (1)**
- SellerSalesDaily

**합계**: 9 + 3 + 4 + 8 + 8 + 5 + 1 = **38개** (WithdrawnSeller 신설·D-23)
---
 
## 4. ERD 단계 확정 항목 (erd-update 트랙 v2.4)

아래 3건은 architecture-baseline 트랙(PR-00~05)에서 확정되었습니다. DDL 작성 전 별도 결정 불필요.

1. **✅ Inventory.quantity_available 갱신 방식 — 확정 (D-09)**: 애플리케이션 갱신. Inventory Aggregate 단일 진입점에서 `quantity_available = quantity_on_hand - quantity_reserved` 재계산. DB 트리거 기각 (ADR-005·docs/domain/inventory-policy.md §5).
2. **✅ AuditLog.diff_json 컬럼 타입 — 확정 (D-11)**: JSON 타입. MariaDB JSON = LONGTEXT + CHECK 제약 alias. JSON 경로 함수 지원 (docs/architecture-baseline/audit-policy.md·ADR-006).
3. **✅ OrderItem.item_status ↔ Order.status 동기화 규칙 — 확정 (D-04·D-16)**: 방식 B + `OrderStatusResolver` Domain Service. 7조건 명시적 전이([5]→[6]→[7]→[4]→[3]→[2] 평가 순서) (docs/architecture-baseline/state-machine.md §5).
---
 
## 5. 다음 단계
 
- **B**: ERD 작성 (Mermaid erDiagram)
- **C**: MariaDB DDL 생성