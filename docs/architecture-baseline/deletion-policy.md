# Deletion Policy (PR-03)

> 소스: decisions.md D-12 [확정 2026-06-24]·db-schema-decisions.md §1.7/§1.8/§2.1
> 범위: SOFT/HARD/ARCHIVE 분류·비식별화 흐름·운영 가이드. 자동 비식별화 배치·백업/복구는 구현/운영 이연.
> ADR: [006-soft-delete](../adr/006-soft-delete.md).

---

## 1. 분류 원칙

세 가지 삭제 정책으로 데이터 성격별 법적 의무(보관/파기)를 동시 충족한다.

- **SOFT DELETE**: `deleted_at` 마킹 후 보존. 복구 가능. 이력·분쟁 대응. (§1.7 deleted_at·deleted_by·delete_reason 컬럼)
- **HARD DELETE**: 물리 삭제. 개인정보·임시 데이터·시스템 마스터.
- **ARCHIVE**: 영구 보존(삭제 불가·법정 보관·상태 관리 통합). 주문·결제·정산·감사 로그 등. 콜드 스토리지 물리 이전은 운영 이연(M-20 (a)).

**Aggregate Root 단위 적용**: 분류는 16 Aggregate Root + 1 Infra/Event Processing(aggregate-boundary.md §2·D-18) 기준으로 정한다. 종속 엔티티(Aggregate 내부 포함 엔티티)는 Root와 동일 정책을 자동 상속한다. Root와 정책이 갈리는 경계 케이스만 별도 명시한다.

> 소프트 삭제 ≠ 비식별화. 소프트 삭제(deleted_at)는 복구 가능, 비식별화(anonymized_at)는 불가역 개인정보 파기다. 두 축을 분리해 "탈퇴했으나 법정 보관 중" 상태를 표현한다(§3).

---

## 2. 테이블별 분류

### 2.1 주 분류표

| 분류 | 대상 테이블 | 근거 |
|---|---|---|
| SOFT | User, Seller, Product, ProductVariant, Category, Attachment, UserAddress, ProductImage | 이력·복구 대응·§1.8 적용 범위 |
| HARD | CartItem, UserRole, RolePermission | 보존가치 낮음·권한 회수(+AuditLog 기록) |
| ARCHIVE | Order, OrderItem, Payment, Settlement, Claim, Refund, AuditLog, Inventory, InventoryHistory, SellerSalesDaily, BuyerPurchaseAggregate | 전자상거래법 보관·상태 관리·append-only |

### 2.2 경계 케이스 보강(8건)

Root와 정책이 갈리거나 주 분류표에 없던 Aggregate Root·종속 엔티티.

| 테이블 | 분류 | 근거 |
|---|---|---|
| SellerBankAccount | ARCHIVE | Seller(SOFT) 종속이나 정산 계좌 이력 = 금전 직결·법정 보관 의무 |
| WithdrawnUser | ARCHIVE | User(SOFT) 종속이나 법정 보관 후 비식별화 별도 흐름(§3·db-schema §2.1) |
| WithdrawnSeller | ARCHIVE | Seller(SOFT) 종속이나 법정 보관 후 비식별화 별도 흐름(§3·db-schema §2.3·D-23) |
| Delivery | ARCHIVE | 독립 Aggregate·송장 이력 보존·운송 분쟁 대응 |
| NotificationLog | ARCHIVE | 독립 Infra/Event 기록(aggregate-boundary §2.7)·발송 이력 보존(보존 기간 후 폐기는 운영 이연) |
| Code · CodeGroup | SOFT (is_system=FALSE만) | is_system=TRUE는 삭제 불가(시스템 의존 코드 보호·ERD 05) |
| Permission · Role | HARD | 시스템 마스터 데이터·운영 단계 변경 거의 없음 |
| BuyerGrade · GradePolicy | SOFT | 등급 정책 변경 이력은 GradePolicy.is_active 컬럼으로 관리 |

### 2.3 종속 엔티티 자동 상속(5건)

분류 원칙(§1)에 따라 Root 정책을 그대로 상속한다 — 별도 분류 불필요.

| 종속 엔티티 | 소속 Aggregate Root | 상속 분류 |
|---|---|---|
| BuyerProfile | User | SOFT |
| ProductOptionGroup | Product | SOFT |
| ProductOptionValue | Product | SOFT |
| OrderShippingSnapshot | Order | ARCHIVE |
| SellerUser | Seller | SOFT |

> 일괄 처리 예: **Product(SOFT)** 삭제 시 종속 ProductImage·ProductOptionGroup·ProductOptionValue·ProductVariant 동일 SOFT 적용. **Order(ARCHIVE)** → OrderShippingSnapshot 동일 ARCHIVE.
> 주의: **SellerUser**는 권한 매핑이지만 Auth의 UserRole·RolePermission(HARD)과 달리 소속 Aggregate Root인 **Seller(SOFT)**를 따른다. 분류는 의미 역할이 아니라 소속 Aggregate를 기준으로 한다.

> 커버리지: 주 분류표 22 + 경계 케이스 11 + 종속 상속 5 = **38개 전 테이블** 분류 완료.

---

## 3. 소프트 삭제 + 비식별화 흐름

User 탈퇴를 예로 한 단계(db-schema §2.1):

```
탈퇴 요청 → withdrawn_at 마킹 (로그인 차단)
         → 법정 보관 기간 유지 (WithdrawnUser.legal_retention_until · 전자상거래법 5년 등)
         → 배치 → anonymized_at 처리 (비식별화 완료)
```

비식별화 시:

| 필드 | 처리 |
|---|---|
| email | NULL |
| phone | HASH |
| name | NULL |
| user_id · order_id 등 식별자 | 유지(정합성 보존) |

- 식별자는 유지해 Order·Payment 등 ARCHIVE 데이터와의 정합성을 보존한다.
- 비식별화는 불가역. 소프트 삭제(복구 가능)와 구분한다.

---

## 4. 운영 가이드

- **삭제 권한**: 전체관리자(SUPER_ADMIN)만. seller_scope·일반 운영자는 삭제 불가.
- **삭제 시 AuditLog**: SOFT/HARD 삭제 모두 AuditLog.action = DELETE 자동 기록(audit-policy.md §2).
- **조회 가드**: SOFT 대상은 모든 조회 쿼리에 `deleted_at IS NULL` 가드 필수(누락 시 삭제분 노출).
- **복구**: SOFT 한정(deleted_at NULL 복원). HARD·ARCHIVE는 복구 경로 없음.
- **권한 회수(HARD)**: UserRole·RolePermission 물리 삭제 시 AuditLog로 회수 이력 보존(M-19).

---

## 5. 외부 이연

- **자동 비식별화 배치**: 만료 판정·anonymized_at 처리 잡 → 구현 단계(baseline-plan §10 배치/워커).
- **백업·복구 정책**: 운영 단계.
- **ARCHIVE 콜드 스토리지 물리 이전**: 운영 단계(본 PR은 "삭제 불가·영구 보존" 분류까지).
- **소프트 삭제 인덱스 전략**: PR-04(index-strategy·deleted_at 부분 인덱스 등).
