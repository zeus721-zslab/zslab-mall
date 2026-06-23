# ERD 인덱스

> **기반 문서**: [db-schema-decisions.md v2.4](../db-schema-decisions.md)
> **총 테이블**: 38개 (집계 포함) / 도메인 5개 다이어그램

---

## 다이어그램 목록

| 파일 | 도메인 | 포함 엔티티 수 | 설명 |
|---|---|---|---|
| [01-user-permission-grade.md](./01-user-permission-grade.md) | 회원·권한·등급 | 12 | User, 탈퇴, 주소록, RBAC, 판매자 소속, 구매 등급·정책·집계 |
| [02-seller-settlement.md](./02-seller-settlement.md) | 판매자·정산 | 4 | Seller, 정산 계좌(암호화), 정산 내역(금액 분해), 종료 판매자 아카이브 |
| [03-product-inventory.md](./03-product-inventory.md) | 상품·재고 | 8 | 카테고리 계층, 상품, 이미지, 옵션 그룹/값, SKU, 실재고, 변동 이력 |
| [04-order-payment-delivery-claim.md](./04-order-payment-delivery-claim.md) | 주문·결제·배송·클레임 | 8 | 장바구니, 주문, 배송지 스냅샷, 결제, 배송, 클레임, 환불 |
| [05-common-code-aggregate.md](./05-common-code-aggregate.md) | 코드·공통·집계 | 6 | 상태 코드, 첨부파일, 감사 로그, 알림 이력, 일별 매출 집계 |

---

## architecture-baseline 트랙 확정 항목 (v2.4)

> ✅ **v2.3 확정 (enum 정책)**: 신규 type/status 컬럼 분류 정책 (A/B/D) 및 Java enum 정책 (E-2~E-5) 확정. 상세는 db-schema-decisions.md §1.13 참조.

> ✅ **v2.4 확정 (erd-update 트랙)**: 아래 3건은 architecture-baseline 트랙(PR-00~05)에서 모두 확정되었습니다. DDL 작성 전 별도 결정 불필요.

### 1. ✅ Inventory.quantity_available 갱신 방식 — 확정 (D-09)

**확정**: 애플리케이션 갱신.

`quantity_available = quantity_on_hand - quantity_reserved` 재계산을 Inventory Aggregate 단일 진입점에서 수행. DB 트리거는 디버깅 난이도·ORM 마찰 문제로 기각 (ADR-005·docs/domain/inventory-policy.md §5 참조).

### 2. ✅ AuditLog.diff_json 컬럼 타입 — 확정 (D-11)

**확정**: JSON 타입.

MariaDB `JSON` = LONGTEXT + CHECK 제약 alias. JSON 경로 함수(`JSON_EXTRACT`) 지원·유효성 검증 CHECK 자동 적용 (docs/architecture-baseline/audit-policy.md·ADR-006 참조).

### 3. ✅ OrderItem.item_status ↔ Order.status 동기화 규칙 — 확정 (D-04·D-16)

**확정**: 방식 B (명시적 전이 조건·`OrderStatusResolver` Domain Service).

OrderItem 상태 변경 시 `OrderStatusResolver`가 방식 B 7조건([5]→[6]→[7]→[4]→[3]→[2] 평가 순서)을 적용해 Order.status를 재계산 (docs/architecture-baseline/state-machine.md §5 참조).

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
| `aud_` | AuditLog |
