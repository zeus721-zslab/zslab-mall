# ENUM-AUDIT Report (2026-06-23)

> 점검 기준: db-schema-decisions.md §1.13 (ENUM 정책 v2.3)
> 작업 유형: read-only 정찰 — 코드·DDL·문서 수정 없음

---

## 1. 점검 대상 문서 목록

| # | 파일 | 역할 |
|---|---|---|
| 1 | docs/design/db-schema-decisions.md | 스키마 단일 레퍼런스 (§1.13 ENUM 정책 원본) |
| 2 | docs/design/erd/01-user-permission-grade.md | 회원·권한·등급 ERD |
| 3 | docs/design/erd/02-seller-settlement.md | 판매자·정산 ERD |
| 4 | docs/design/erd/03-product-inventory.md | 상품·재고 ERD |
| 5 | docs/design/erd/04-order-payment-delivery-claim.md | 주문·결제·배송·클레임 ERD |
| 6 | docs/design/erd/05-common-code-aggregate.md | 코드·공통·집계 ERD |
| 7 | docs/design/erd/README.md | ERD 인덱스 + 결정 보류 항목 |
| 8 | docs/design/buyer-grade.md | 구매자 등급 UML (초기 설계 문서) |
| 9 | docs/design/permission-uml.md | RBAC 권한 UML |
| 10 | docs/design/permission-matrix.md | 권한 매트릭스 |
| 11 | docs/design/actors.md | 액터 구조 |
| 12 | docs/design/marketplace-core-domain.md | 핵심 도메인 UML (초기 설계 문서) |
| 13 | docs/design/additional-entities.md | 추가 엔티티 추천 목록 |

---

## 2. 분류별 컬럼 인벤토리

### A. 잠금 (18건) — §1.13 기준

| # | 엔티티 | 컬럼 | 값 집합 (§1.13) | ERD 표기 | 정합 |
|---|---|---|---|---|---|
| 1 | BuyerProfile | grade_source | AUTO, MANUAL, EVENT | `enum grade_source "AUTO\|MANUAL\|EVENT"` | ✅ |
| 2 | Role | code | SUPER_ADMIN, ADMIN_OPERATOR, BUYER, SELLER_OWNER, SELLER_MANAGER, SELLER_STAFF | `enum code "SUPER_ADMIN\|ADMIN_OPERATOR\|BUYER\|SELLER_OWNER\|SELLER_MANAGER\|SELLER_STAFF"` | ✅ |
| 3 | BuyerGrade | code | SILVER, GOLD, PLATINUM | `enum code "SILVER\|GOLD\|PLATINUM"` | ✅ |
| 4 | SellerBankAccount | status | PENDING, VERIFIED, REJECTED | `enum status "PENDING\|VERIFIED\|REJECTED"` | ✅ |
| 5 | Settlement | status | PENDING, CONFIRMED, PAID | `enum status "PENDING\|CONFIRMED\|PAID"` | ✅ |
| 6 | Product | status | DRAFT, PENDING, APPROVED, REJECTED, SALE, HIDDEN, STOPPED | `enum status "DRAFT\|PENDING\|APPROVED\|REJECTED\|SALE\|HIDDEN\|STOPPED"` | ✅ |
| 7 | ProductVariant | status | SALE, HIDDEN, STOPPED | `enum status "SALE\|HIDDEN\|STOPPED"` | ✅ |
| 8 | InventoryHistory | change_type | ORDER, CANCEL, RETURN, ADJUST, INBOUND, OUTBOUND | `enum change_type "ORDER\|CANCEL\|RETURN\|ADJUST\|INBOUND\|OUTBOUND"` | ✅ |
| 9 | Payment | method | CARD, BANK, VBANK, KAKAO | `enum method "CARD\|BANK\|VBANK\|KAKAO\|..."` | ✅ |
| 10 | Payment | status | PENDING, PAID, FAILED, CANCELLED | `enum status "PENDING\|PAID\|FAILED\|CANCELLED"` | ✅ |
| 11 | Delivery | carrier | CJ, HANJIN, POST, LOGEN | `enum carrier "CJ\|HANJIN\|POST\|LOGEN\|..."` | ✅ |
| 12 | Delivery | status | READY, SHIPPING, DELIVERED | `enum status "READY\|SHIPPING\|DELIVERED"` | ✅ |
| 13 | Claim | type | CANCEL, RETURN, EXCHANGE | `enum type "CANCEL\|RETURN\|EXCHANGE"` | ✅ |
| 14 | Claim | status | REQUESTED, APPROVED, REJECTED, COMPLETED | `enum status "REQUESTED\|APPROVED\|REJECTED\|COMPLETED"` | ✅ |
| 15 | Refund | status | PENDING, COMPLETED, FAILED | `enum status "PENDING\|COMPLETED\|FAILED"` | ✅ |
| 16 | NotificationLog | channel | EMAIL, SMS, PUSH, IN_APP | `enum channel "EMAIL\|SMS\|PUSH\|IN_APP"` | ✅ |
| 17 | NotificationLog | status | PENDING, SENT, FAILED | `enum status "PENDING\|SENT\|FAILED"` | ✅ |
| 18 | AuditLog | action | CREATE, UPDATE, DELETE, APPROVE, REJECT, LOGIN, **LOGOUT** | `enum action "CREATE\|UPDATE\|DELETE\|APPROVE\|REJECT\|LOGIN\|..."` | ⚠️ ISS-03 |

### B. Code 참조 (3건) — §1.13 기준

| # | 엔티티 | 컬럼 | 값 집합 (§1.13) | ERD 표기 | 정합 |
|---|---|---|---|---|---|
| 1 | Seller | status | PENDING, ACTIVE, SUSPENDED, TERMINATED | `enum status "PENDING\|ACTIVE\|SUSPENDED\|TERMINATED (B분류, Code 라벨 조인)"` | ✅ |
| 2 | Order | status | 결정 보류 (DDL §3) | `enum status "Code 참조 (B분류)·db-schema §1.13"` | ✅ (의도된 보류) |
| 3 | OrderItem | item_status | 결정 보류 (DDL §3) | `enum item_status "Code 참조 (B분류)·DDL 보류§3"` | ✅ (의도된 보류) |

### D. polymorphic (4건) — §1.13 기준

| # | 엔티티 | 컬럼 | 비고 | ERD 표기 | 정합 |
|---|---|---|---|---|---|
| 1 | InventoryHistory | reference_type | Order, Claim, Manual + 확장 | `varchar50 reference_type "polymorphic (D분류)·Order\|Claim\|Manual+"` | ✅ |
| 2 | Attachment | target_type | CLAIM, REVIEW, SELLER_DOCUMENT + 확장 | `varchar50 target_type "polymorphic (D분류)·CLAIM\|REVIEW\|SELLER_DOCUMENT+"` | ✅ |
| 3 | AuditLog | target_type | 모든 엔티티명 | `varchar50 target_type "polymorphic (D분류)"` | ✅ |
| 4 | NotificationLog | target_type | Order, Claim, Settlement + 확장 | `varchar50 target_type "polymorphic (D분류)"` | ✅ |

---

## 3. 발견된 이슈

### 3.1 정합성 위반 (문서 간 값 차이) — 3건

#### ISS-01: ERD 04 설계 메모 A분류 건수 오기

- **위치**: `docs/design/erd/04-order-payment-delivery-claim.md` 설계 메모 마지막 줄
- **현재**: "Payment/Delivery/Claim/Refund의 type·status·method·carrier **9건** = A분류"
- **실제**: Payment(method+status=2) + Delivery(carrier+status=2) + Claim(type+status=2) + Refund(status=1) = **7건**
- **원인 추정**: Order.status + OrderItem.item_status(B분류 2건)를 합산한 9(=7+2)를 그대로 A분류 건수로 오기한 것으로 보임

#### ISS-02: §1.13 컬럼 수 헤딩과 인벤토리 합계 불일치

- **위치**: `docs/design/db-schema-decisions.md §1.13` → `#### 분류 카테고리 (23개 컬럼)` 헤딩
- **현재 헤딩**: "(23개 컬럼)"
- **실제 합계**: A(18) + B(3) + D(4) = **25건**
- **추정 배경**: v2.3 changelog에 "23개 type/status 컬럼"으로 기술하면서 Role.code·BuyerGrade.code(컬럼명 "code"이며 "type/status"가 아님)를 제외한 23건을 헤딩에 그대로 사용. 그러나 §1.13 A분류 표에는 이 2건이 포함되어 있어 헤딩과 표 합산값이 불일치.

#### ISS-03: ERD 05 AuditLog.action Mermaid 표기에 LOGOUT 미포함

- **위치**: `docs/design/erd/05-common-code-aggregate.md` AuditLog 엔티티 Mermaid 블록
- **현재**: `enum action "CREATE|UPDATE|DELETE|APPROVE|REJECT|LOGIN|..."`
- **§1.13 기준**: `CREATE, UPDATE, DELETE, APPROVE, REJECT, LOGIN, LOGOUT` (LOGOUT 명시)
- **문제**: LOGOUT이 "..."로 은폐되어 ERD만으로는 값 집합을 확인할 수 없음. A분류 컬럼의 ERD 표기 원칙("값 집합 명시")에 위배.

---

### 3.2 누락 (분류 미지정·enum 값 미정의) — 2건

#### ISS-04: Claim.reason_code 분류 미지정

- **위치**: `docs/design/erd/04-order-payment-delivery-claim.md` Claim 엔티티 / `docs/design/db-schema-decisions.md §2.5`
- **현재**: `varchar50 reason_code` — 분류 표기 없음, §1.13 인벤토리에도 미포함
- **문제**: 클레임 사유 코드는 운영자가 관리하는 값 집합("단순변심|파손|불량|주소오류 등")이 될 가능성이 높음. A분류(enum 고정), B분류(Code 참조), D분류(자유입력) 중 명시적 결정이 없음.
- **참고**: `Claim.type`(CANCEL|RETURN|EXCHANGE)은 A분류로 분류되었으나 `reason_code`는 누락.

#### ISS-05: ERD 05 "도메인 간 연결" 표에 `Code ← OrderItem.item_status` 행 누락

- **위치**: `docs/design/erd/05-common-code-aggregate.md` "도메인 간 연결" 표
- **현재**: `Code ← Order.status` / `Code ← Seller.status` 2건만 명시
- **문제**: OrderItem.item_status도 §1.13 B분류(ORDER_ITEM_STATUS Code Group 참조)이나 연결 표에서 누락. 표의 완결성이 깨짐.

---

### 3.3 모호 (분류 경계 애매) — 1건

#### ISS-06: §2.6 Java OrderStatus 코드 샘플이 미확정 값 집합을 암시

- **위치**: `docs/design/db-schema-decisions.md §2.6`
- **현재**: Java 코드 샘플 `enum OrderStatus { PAID, READY, SHIPPING, DELIVERED, CONFIRMED, ... }`
- **문제**: Order.status 값은 §1.13 B분류 "DDL 결정 보류"로 미확정 상태. 그러나 §2.6 코드 샘플에 PAID, READY, SHIPPING 등 구체적 값이 나열되어 있어 확정 값으로 오인될 수 있음. 특히 PAID는 Payment.status(A분류)에도 존재해 도메인 혼선 소지.

---

## 4. 권장 조치 (이슈별 1줄)

| 이슈 | 조치 |
|---|---|
| ISS-01 | ERD 04 설계 메모 "9건" → "7건" 수정 |
| ISS-02 | §1.13 헤딩 "(23개 컬럼)" → "(25개 컬럼)" 수정, 또는 헤딩에 "(23 type/status + 2 code = 25건)" 명시 |
| ISS-03 | ERD 05 AuditLog.action → `"CREATE\|UPDATE\|DELETE\|APPROVE\|REJECT\|LOGIN\|LOGOUT"` (LOGOUT 명시, "..." 제거) |
| ISS-04 | Claim.reason_code 분류 결정(B분류 권장) 후 §1.13 인벤토리에 추가 또는 "분류 제외 사유" 명시 |
| ISS-05 | ERD 05 도메인 간 연결 표에 `Code ← OrderItem.item_status \| ORDER_ITEM_STATUS 그룹 Code 참조` 행 추가 |
| ISS-06 | §2.6 코드 샘플에 "이 값은 설명용 예시, 최종 값은 §1.13 B분류 확정 후 결정" 주석 추가 또는 샘플 단순화 |

---

## 5. 종합 판정

**조치 필요** (심각도: 낮음 — 기능 오류 없음, 문서 내 수치·표기 교정)

| 구분 | 건수 | 세부 |
|---|---|---|
| 3.1 정합성 위반 | 3건 | ISS-01(숫자 오기), ISS-02(헤딩 수치), ISS-03(LOGOUT 누락) |
| 3.2 누락 | 2건 | ISS-04(reason_code 미분류), ISS-05(연결 표 행 누락) |
| 3.3 모호 | 1건 | ISS-06(코드 샘플 값 혼선 소지) |
| **합계** | **6건** | |

**A/B/D 인벤토리 자체는 정합** — 25건 컬럼 모두 ERD에 enum 또는 varchar로 올바르게 표기되어 있으며, 값 집합은 ISS-03(LOGOUT) 1건을 제외하고 §1.13과 일치. B분류 Order.status·OrderItem.item_status 값 미정의는 의도된 설계 보류로 이슈 아님.
