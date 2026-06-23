# ERD 인덱스

> **기반 문서**: [db-schema-decisions.md v2.2](../db-schema-decisions.md)
> **총 테이블**: 29개 (집계 포함) / 도메인 5개 다이어그램

---

## 다이어그램 목록

| 파일 | 도메인 | 포함 엔티티 수 | 설명 |
|---|---|---|---|
| [01-user-permission-grade.md](./01-user-permission-grade.md) | 회원·권한·등급 | 12 | User, 탈퇴, 주소록, RBAC, 판매자 소속, 구매 등급·정책·집계 |
| [02-seller-settlement.md](./02-seller-settlement.md) | 판매자·정산 | 3 | Seller, 정산 계좌(암호화), 정산 내역(금액 분해) |
| [03-product-inventory.md](./03-product-inventory.md) | 상품·재고 | 8 | 카테고리 계층, 상품, 이미지, 옵션 그룹/값, SKU, 실재고, 변동 이력 |
| [04-order-payment-delivery-claim.md](./04-order-payment-delivery-claim.md) | 주문·결제·배송·클레임 | 8 | 장바구니, 주문, 배송지 스냅샷, 결제, 배송, 클레임, 환불 |
| [05-common-code-aggregate.md](./05-common-code-aggregate.md) | 코드·공통·집계 | 6 | 상태 코드, 첨부파일, 감사 로그, 알림 이력, 일별 매출 집계 |

---

## DDL 작성 전 결정 보류 항목

> 아래 3건은 ERD 단계에서 확정되지 않은 사항입니다. DDL 작성 시작 전 결정 필요.

### 1. Inventory.quantity_available 갱신 방식

`quantity_available = quantity_on_hand - quantity_reserved` 캐시 컬럼.

| 방식 | 장점 | 단점 |
|---|---|---|
| **애플리케이션 갱신** | 디버깅 용이, 트랜잭션 명시적 제어 | 갱신 누락 시 캐시 불일치 가능 |
| DB 트리거 | 강제 일관성 | 디버깅 난이도 ↑, ORM과 마찰 |

> 추천: **애플리케이션 갱신** (트리거 디버깅 난이도 및 ORM 마찰 고려).

### 2. AuditLog.diff_json 컬럼 타입

| 타입 | 비고 |
|---|---|
| `JSON` | MariaDB 10.2+에서 LONGTEXT + CHECK 제약 alias. JSON 경로 함수(`JSON_EXTRACT`) 사용 가능 |
| `LONGTEXT` | 단순 저장, 경로 함수 미지원 |

> 추천: **JSON 타입** (경로 기반 쿼리, 유효성 검증 CHECK 제약 자동 적용).

### 3. OrderItem.item_status ↔ Order.status 동기화 규칙

`OrderItem.item_status`(항목별 상태)와 `Order.status`(주문 전체 상태) 이중 관리 정책 명세 필요.

결정해야 할 사항:
- 모든 OrderItem이 `DELIVERED` → Order를 `COMPLETED`로 자동 전이하는 주체 (이벤트 핸들러 vs 배치)
- 일부 항목만 클레임인 경우 Order.status 정책 (부분 클레임 상태 표현 방법)
- OrderItem.item_status가 Order.status와 충돌 시 우선순위

---

## 참고: public_id prefix 목록

| prefix | 엔티티 |
|---|---|
| `usr_` | User |
| `slr_` | Seller |
| `prd_` | Product |
| `var_` | ProductVariant |
| `ord_` | Order |
| `oit_` | OrderItem |
| `pay_` | Payment |
| `dlv_` | Delivery |
| `clm_` | Claim |
| `rfn_` | Refund |
| `att_` | Attachment |
