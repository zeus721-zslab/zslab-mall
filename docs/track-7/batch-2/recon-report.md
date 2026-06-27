# Track 7 Batch-2 정찰 보고서 — 매핑·집계 6 (read-only)

> 작성일: 2026-06-28  
> 브랜치: docs/track-7-batch-2-recon (정찰 전용·코드 변경 0)  
> 범위: user_role · role_permission · seller_user · buyer_purchase_aggregate · seller_sales_daily · inventory_history  
> 근거: D-81 §1 Batch-2 (매핑·집계·B급·외부 검토 생략)

---

## §1. 매핑·집계 6 테이블 DDL 1:1 추출 (V1__init.sql 기준)

### 1.1 user_role (테이블 #11, N:M)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| user_id | BIGINT | NOT NULL | FK→user·N:M 좌측 |
| role_id | BIGINT | NOT NULL | FK→role·N:M 우측 |
| created_at | DATETIME(6) | NOT NULL | 매핑 생성 시각 |

- PK: id
- UK: uk_user_role (user_id, role_id)
- FK: fk_user_role_user → user(id) ON DELETE RESTRICT ON UPDATE CASCADE
- FK: fk_user_role_role → role(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: InnoDB 자동 (user_id·role_id FK)
- soft-delete 컬럼: 없음
- public_id 컬럼: 없음
- COMMENT: `회원-역할 매핑(Auth 종속·HARD·N:M)`

### 1.2 role_permission (테이블 #12, N:M)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| role_id | BIGINT | NOT NULL | FK→role·N:M 좌측 |
| permission_id | BIGINT | NOT NULL | FK→permission·N:M 우측 |
| created_at | DATETIME(6) | NOT NULL | 매핑 생성 시각 |

- PK: id
- UK: uk_role_permission (role_id, permission_id)
- FK: fk_role_permission_role → role(id) ON DELETE RESTRICT ON UPDATE CASCADE
- FK: fk_role_permission_permission → permission(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: InnoDB 자동 (role_id·permission_id FK)
- soft-delete 컬럼: 없음
- public_id 컬럼: 없음
- COMMENT: `역할-권한 매핑(Auth 종속·HARD·N:M)`

### 1.3 seller_user (테이블 #13)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| seller_id | BIGINT | NOT NULL | FK→seller (내부·Seller Root) |
| user_id | BIGINT | NOT NULL | FK→user (외부·User Aggregate) |
| role_id | BIGINT | NOT NULL | FK→role (외부·Auth Aggregate) |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: uk_seller_user (seller_id, user_id)
- FK: fk_seller_user_seller → seller(id) ON DELETE RESTRICT ON UPDATE CASCADE
- FK: fk_seller_user_user → user(id) ON DELETE RESTRICT ON UPDATE CASCADE
- FK: fk_seller_user_role → role(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: InnoDB 자동 (seller_id·user_id·role_id FK)
- soft-delete 컬럼: **없음** (COMMENT "SOFT 상속"이나 DDL에 deleted_at 없음 — §6 WARN-1)
- public_id 컬럼: 없음
- COMMENT: `판매자 구성원(Seller 귀속·SOFT 상속)`

### 1.4 buyer_purchase_aggregate (테이블 #15, 단일 논리 PK·Read Model)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| buyer_id | BIGINT | NOT NULL | User.id 논리참조·PK(FK 미적용 ②) |
| lifetime_purchase_amount | BIGINT | NOT NULL | 생애 누적 구매액 |
| last_ordered_at | DATETIME(6) | NULL | 최근 주문 시각 |
| updated_at | DATETIME(6) | NOT NULL | 집계 갱신 시각(이벤트 핸들러 E6) |

- PK: buyer_id (**AUTO_INCREMENT 없음**·외부 할당)
- UK: 없음
- FK: **없음** (논리 참조 ②·User.id 직접 FK 미적용)
- INDEX: ix_buyer_purchase_aggregate_last_ordered (last_ordered_at)
- soft-delete 컬럼: 없음
- public_id 컬럼: 없음
- COMMENT: `구매 집계 Read Model(ARCHIVE·집계·FK 미적용)`

### 1.5 seller_sales_daily (테이블 #18, 복합 PK·Read Model)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| seller_id | BIGINT | NOT NULL | Seller.id 논리참조·복합 PK(FK 미적용 ②) |
| sale_date | DATE | NOT NULL | 집계 일자·복합 PK |
| order_count | INT | NOT NULL | 주문 건수 |
| gross_amount | BIGINT | NOT NULL | 총 매출 |
| refund_amount | BIGINT | NOT NULL | 환불액 |
| net_amount | BIGINT | NOT NULL | 순 매출 |
| updated_at | DATETIME(6) | NOT NULL | 집계 갱신 시각(배치) |

- PK: **(seller_id, sale_date) 복합 PK** — AUTO_INCREMENT 없음
- UK: 없음 (PK = 유일성)
- FK: **없음** (논리 참조 ②·Seller.id 직접 FK 미적용)
- INDEX: 없음
- soft-delete 컬럼: 없음
- public_id 컬럼: 없음
- COMMENT: `판매자 일별 집계 Read Model(ARCHIVE·집계·FK 미적용)`

### 1.6 inventory_history (테이블 #26, append-only)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| inventory_id | BIGINT | NOT NULL | FK→inventory(N:1) |
| change_type | ENUM('ORDER','CANCEL','RETURN','ADJUST','INBOUND','OUTBOUND') | NOT NULL | 변동 유형·A#8 |
| quantity_delta | INT | NOT NULL | 재고 증감량 (부호 포함·음수 허용) |
| reference_type | VARCHAR(50) | NOT NULL | polymorphic 참조 유형·D분류(앱 검증) |
| reference_id | BIGINT | NULL | polymorphic 참조 id·논리 참조 |
| reason | VARCHAR(255) | NULL | 변동 사유 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |

- PK: id
- UK: 없음
- FK: fk_inventory_history_inventory → inventory(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: InnoDB 자동 (inventory_id FK)
- soft-delete 컬럼: 없음 (append-only·삭제 금지)
- public_id 컬럼: 없음
- COMMENT: `재고 변동 이력(INV 종속·ARCHIVE·append-only)`

---

## §2. Aggregate 경계 정합 검증

aggregate-boundary.md §2.1·§2.2·§2.3·§3 명시 귀속 ↔ V1 DDL FK 방향 1:1 대조

| Entity | aggregate-boundary 귀속 | DDL FK 방향 | 결과 |
|---|---|---|---|
| user_role | Auth 종속 §2.1 (Role Root·UserRole 포함) | FK→user(외부)·FK→role(내부 Root) | **PASS** |
| role_permission | Auth 종속 §2.1 (Role Root·RolePermission 포함) | FK→role(내부 Root)·FK→permission(내부) | **PASS** |
| seller_user | Seller 종속 §2.2 (SellerUser 포함) | FK→seller(내부 Root)·FK→user(외부)·FK→role(외부) | **PASS** |
| buyer_purchase_aggregate | Read Model §3 (집계·파생 데이터) | FK 없음 (논리 참조 ②) | **PASS** |
| seller_sales_daily | Read Model §3 (집계·파생 데이터) | FK 없음 (논리 참조 ②) | **PASS** |
| inventory_history | Inventory 종속 §2.3 (InventoryHistory 포함) | FK→inventory(내부 Root) | **PASS** |

### 경계 상세 — seller_user Auth 귀속 대안 기각 확인

aggregate-boundary §2.1 경계 결정문: `SellerUser`는 Seller 컨텍스트에서 생성·삭제 → Seller Aggregate 귀속 (Auth 귀속 대안 기각: Role 조회는 ID 참조로 충분).

seller_user.role_id → Role(Auth Aggregate)는 DB FK가 있으나 JPA에서 Long roleId 필드만 사용 (ID 참조 정합·D-01 준수).

### D-01 위반 검증

| Entity | 외부 Aggregate FK | D-01 처치 | 결과 |
|---|---|---|---|
| user_role | user_id → User (외부) | Long userId 필드만·@ManyToOne 금지 | **PASS** |
| role_permission | 없음 (role·permission 모두 Auth 내부) | — | **PASS** |
| seller_user | user_id → User (외부)·role_id → Role (외부) | Long userId·Long roleId 필드만 | **PASS** |
| buyer_purchase_aggregate | 없음 (FK 미적용·논리 참조) | — | **PASS** |
| seller_sales_daily | 없음 (FK 미적용·논리 참조) | — | **PASS** |
| inventory_history | 없음 (inventory_id는 내부 FK) | — | **PASS** |

**외부 Aggregate 참조 총계**: user_role 1건·seller_user 2건 — 모두 ID 필드로 처치 필요.  
**D-01 위반**: 없음 (DDL FK ≠ JPA @ManyToOne·외부 참조는 ID 필드로 구현).

---

## §3. Common Entity abstract 매칭

### abstract 8종 Batch-2 적용 대상

| 클래스 | 컬럼 | Batch-2 적용 대상 |
|---|---|---|
| AbstractMappingEntity | created_at | UserRole·RolePermission (N:M 매핑 2) |
| AbstractFullAuditableEntity | created_at + created_by + updated_at + updated_by | SellerUser (full audit 4컬럼) |
| AbstractAggregateEntity | updated_at | BuyerPurchaseAggregate·SellerSalesDaily (집계 Read Model 2) |
| AbstractCreatedOnlyEntity | created_at + created_by | InventoryHistory (append-only) |
| AbstractSeedEntity | created_at + updated_at | 없음 (시드성 Entity는 Batch-1 완료) |
| AbstractSoftDeletableEntity | AbstractFullAuditableEntity + 3 soft_del 컬럼 | **없음** (Batch-2 전원 deleted_at 부재) |
| AbstractPublicIdFullAuditableEntity | AbstractFullAuditableEntity + public_id | 없음 |
| AbstractPublicIdSoftDeletableEntity | AbstractSoftDeletableEntity + public_id | 없음 |

### Batch-2 Entity별 매칭 결과

| Entity | 권고 abstract | soft_del | public_id | PK 타입 | 근거 |
|---|---|---|---|---|---|
| user_role | **AbstractMappingEntity** | N | N | BIGINT AUTO | created_at 단일·COMMENT "HARD·N:M" 정합·Javadoc 명시 |
| role_permission | **AbstractMappingEntity** | N | N | BIGINT AUTO | created_at 단일·COMMENT "HARD·N:M" 정합·Javadoc 명시 |
| seller_user | **AbstractFullAuditableEntity** | N | N | BIGINT AUTO | DDL full audit 4컬럼·deleted_at 없음 (§6 WARN-1) |
| buyer_purchase_aggregate | **AbstractAggregateEntity** | N | N | BIGINT 논리 PK (AUTO 없음) | updated_at 단일·Javadoc "집계 2" 명시 |
| seller_sales_daily | **AbstractAggregateEntity** | N | N | 복합 PK (seller_id·sale_date) | updated_at 단일·Javadoc "집계 2" 명시 |
| inventory_history | **AbstractCreatedOnlyEntity** | N | N | BIGINT AUTO | created_at+created_by·append-only·Javadoc "INV 종속" 명시 |

**@SQLRestriction 적용 대상**: **없음** — Batch-2 6 Entity 모두 deleted_at 컬럼 없음 → LT-03 해당 없음 (확인 완료).

---

## §4. Batch-1 + Track 2~5 구현 패턴 학습 결과

### 4.1 어노테이션 컨벤션

```java
@Entity
@Table(name = "table_name")        // 예약어 시 백틱: @Table(name = "`order`")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EntityName extends AbstractXxx {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    // public_id 없는 Entity: @EqualsAndHashCode.Include 여기에
    private Long id;

    @Enumerated(EnumType.STRING)    // 반드시 STRING (ORDINAL 금지)
    @Column(name = "status", nullable = false)
    private StatusEnum status;

    @Column(name = "amount", nullable = false)    // length는 VARCHAR만
    private Long amount;

    @Column(name = "immutable_field", nullable = false, updatable = false)
    private Long immutableId;
}
```

### 4.2 외부 Aggregate 참조 패턴 (D-01)

```java
// 외부 Aggregate는 ID 필드만 — @ManyToOne 금지
@Column(name = "user_id", nullable = false, updatable = false)
private Long userId;

// 같은 Aggregate 내부 FK: @ManyToOne LAZY + @JoinColumn 허용
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "seller_id", nullable = false, updatable = false)
private Seller seller;
```

### 4.3 Factory method 패턴

```java
public static Entity create(Long fieldA, SomeEnum type, ...) {
    if (fieldA == null || type == null) {
        throw new IllegalArgumentException("필수값 누락...");
    }
    Entity e = new Entity();
    e.fieldA = fieldA;
    e.type = type;
    return e;
}
```

### 4.4 equals/hashCode 규칙

- AbstractPublicId* 상속 → publicId가 Include 처리·하위 클래스 추가 불필요
- **public_id 없는 표준 id Entity** → id 필드에 `@EqualsAndHashCode.Include` 명시 의무
- **특수 PK Entity** (논리 PK·복합 PK) → 도메인 키 필드에 `@EqualsAndHashCode.Include` 명시
- 클래스 레벨: `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` (base class에서 이미 선언)

### 4.5 TINYINT(1) / CHAR(30) 매핑

```java
// TINYINT(1) → boolean 원시 타입 (Boolean 래퍼 금지)
@Column(name = "is_active", nullable = false)
private boolean isActive;

// DATE → LocalDate (@Column(name = "sale_date"))
@Column(name = "sale_date", nullable = false, updatable = false)
private LocalDate saleDate;
```

### 4.6 Batch-2 적용 차이점 (Batch-1 대비 신규)

#### 차이점 A — 매핑 N:M HARD delete (권한 회수·deletion-policy §4)

AbstractMappingEntity Javadoc 명시: "권한 회수는 HARD delete이며 회수 이력은 AuditLog로 보존한다(AUTH-4·deletion-policy.md §4)".  
user_role·role_permission에 softDelete 메서드·deleted_at 필드 없음. **delete()는 Repository.delete() 직접 호출** (Track 8+ Application Service 범위).

#### 차이점 B — Read Model 단일/복합 PK·논리 참조·FK 미적용 (②)

- **buyer_purchase_aggregate**: `buyer_id`가 PK이나 `@GeneratedValue` 없음·외부(이벤트 핸들러)에서 User.id를 직접 할당.
- **seller_sales_daily**: `(seller_id, sale_date)` 복합 PK·`@IdClass` 권고 (§5 상세).
- 두 테이블 모두 FK 없음 — `@Column(name = "seller_id")` / `@Column(name = "buyer_id")` ID 필드 선언만 (논리 참조·D-01 적용 대상 아님).

#### 차이점 C — append-only @Immutable 미사용 (A2 결정)

AbstractCreatedOnlyEntity Javadoc: "@Immutable은 사용하지 않는다(A2 결정·NotificationLog status 전이 허용)".  
inventory_history는 append-only이지만 `@Immutable` 어노테이션 부착 금지. 필드는 모두 `updatable = false` 수준에서만 관리.

---

## §5. 매핑 설계 사전 검토

### 5.1 패키지 구조 권고

```
com.zslab.mall.auth.entity      → UserRole, RolePermission
com.zslab.mall.auth.repository  → UserRoleRepository, RolePermissionRepository

com.zslab.mall.seller.entity    → SellerUser, SellerSalesDaily
com.zslab.mall.seller.repository → SellerUserRepository, SellerSalesDailyRepository

com.zslab.mall.grade.entity     → BuyerPurchaseAggregate
com.zslab.mall.grade.repository → BuyerPurchaseAggregateRepository

com.zslab.mall.inventory.entity     → InventoryHistory
com.zslab.mall.inventory.enums      → InventoryHistoryChangeType
com.zslab.mall.inventory.repository → InventoryHistoryRepository
```

**BuyerPurchaseAggregate 패키지 선택 근거**: grade 도메인이 구매 누적액 기반 등급 산정의 소비자이므로 `grade` 패키지 귀속. 별도 `readmodel` 패키지는 2건 대상이라 과잉 추상화.

### 5.2 Entity별 설계 사전 검토

#### UserRole
- abstract: `AbstractMappingEntity`
- PK: `@Id @GeneratedValue(IDENTITY) @EqualsAndHashCode.Include Long id`
- user_id → `@Column(name = "user_id", nullable = false, updatable = false) Long userId` **(외부·D-01·@ManyToOne 금지)**
- role_id → `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "role_id", nullable = false, updatable = false) Role role` **(내부 Auth Aggregate·@ManyToOne 허용)**
- UK (user_id, role_id) 보장: DB UK에 의존·Entity에 별도 unique 선언 불필요
- Factory: `create(Long userId, Role role)`
- softDelete 없음·HARD delete 전용

#### RolePermission
- abstract: `AbstractMappingEntity`
- PK: `@Id @GeneratedValue(IDENTITY) @EqualsAndHashCode.Include Long id`
- role_id → `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "role_id", nullable = false, updatable = false) Role role` **(내부·@ManyToOne 허용)**
- permission_id → `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "permission_id", nullable = false, updatable = false) Permission permission` **(내부·@ManyToOne 허용)**
- role·permission 모두 Auth 내부 → 양쪽 @ManyToOne 허용
- Factory: `create(Role role, Permission permission)`
- softDelete 없음·HARD delete 전용

#### SellerUser
- abstract: `AbstractFullAuditableEntity`
- PK: `@Id @GeneratedValue(IDENTITY) @EqualsAndHashCode.Include Long id`
- seller_id → `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "seller_id", nullable = false, updatable = false) Seller seller` **(내부 Seller Aggregate·@ManyToOne 허용)**
- user_id → `@Column(name = "user_id", nullable = false, updatable = false) Long userId` **(외부 User Aggregate·D-01·@ManyToOne 금지)**
- role_id → `@Column(name = "role_id", nullable = false) Long roleId` **(외부 Auth Aggregate·D-01·@ManyToOne 금지)** — updatable=false 여부는 업무 정책에 따름 (역할 변경 허용 시 updatable=true·Track 8+ 결정 사항)
- UK (seller_id, user_id) 보장: DB UK에 의존
- Factory: `create(Seller seller, Long userId, Long roleId)`

#### BuyerPurchaseAggregate
- abstract: `AbstractAggregateEntity`
- **PK: `@Id @Column(name = "buyer_id") @EqualsAndHashCode.Include Long buyerId`** — @GeneratedValue **없음** (AUTO_INCREMENT 없는 논리 PK·이벤트 핸들러 E6에서 User.id 직접 할당)
- `@Column(name = "lifetime_purchase_amount", nullable = false) Long lifetimePurchaseAmount`
- `@Column(name = "last_ordered_at") LocalDateTime lastOrderedAt` — NULL 허용 (첫 주문 전)
- updated_at: AbstractAggregateEntity 상속·@LastModifiedDate 자동 갱신
- Factory: `create(Long buyerId)` — 첫 이벤트 핸들러 E6 호출 시 초기 레코드 생성

#### SellerSalesDaily
- abstract: `AbstractAggregateEntity`
- **복합 PK: `@IdClass(SellerSalesDailyId.class)` 권고** — 상세 §5.2.1 참조
- `@Id @EqualsAndHashCode.Include @Column(name = "seller_id") Long sellerId`
- `@Id @EqualsAndHashCode.Include @Column(name = "sale_date") LocalDate saleDate`
- 집계 컬럼: INT (orderCount)·BIGINT (grossAmount·refundAmount·netAmount)
- updated_at: AbstractAggregateEntity 상속
- Factory: `create(Long sellerId, LocalDate saleDate, int orderCount, long grossAmount, long refundAmount, long netAmount)`

#### §5.2.1 SellerSalesDaily 복합 PK 전략 선택

**권고: `@IdClass`**

| 항목 | @IdClass | @EmbeddedId |
|---|---|---|
| 코드 가독성 | Entity 필드 직접 접근 (`e.sellerId·e.saleDate`) | `e.id.sellerId·e.id.saleDate` — depth 증가 |
| JPQL | `WHERE e.sellerId = :id AND e.saleDate = :date` | `WHERE e.id.sellerId = :id AND e.id.saleDate = :date` |
| Serializable 구현 | SellerSalesDailyId 클래스 필요 | @Embeddable 클래스 필요 |
| Spring Data | `repository.findById(new SellerSalesDailyId(...))` | `repository.findById(new SellerSalesDailySK(...))` |
| 단순성 | 더 단순 | 동등 |

@IdClass 권고 근거: `sale_date` + `seller_id` 두 필드만의 단순 복합 키에서 @EmbeddedId는 depth 불필요 추가. Batch-1 패턴(단순함 우선)과 일관.

```java
// SellerSalesDailyId.java (같은 패키지)
public class SellerSalesDailyId implements Serializable {
    private Long sellerId;
    private LocalDate saleDate;
    // no-arg constructor 필수·equals·hashCode 필수
}
```

#### InventoryHistory
- abstract: `AbstractCreatedOnlyEntity`
- PK: `@Id @GeneratedValue(IDENTITY) @EqualsAndHashCode.Include Long id`
- inventory_id → `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "inventory_id", nullable = false, updatable = false) Inventory inventory` **(내부 INV Aggregate·@ManyToOne 허용)**
- change_type → `@Enumerated(EnumType.STRING) @Column(name = "change_type", nullable = false, updatable = false) InventoryHistoryChangeType changeType`
- quantity_delta → `@Column(name = "quantity_delta", nullable = false, updatable = false) int quantityDelta` — INT 원시 타입·부호 포함·양수/음수 모두 허용
- reference_type → `@Column(name = "reference_type", nullable = false, length = 50, updatable = false) String referenceType` — VARCHAR 50·D분류·앱 검증
- reference_id → `@Column(name = "reference_id", updatable = false) Long referenceId` — NULL 허용·논리 참조·FK 없음
- reason → `@Column(name = "reason", length = 255, updatable = false) String reason` — NULL 허용
- @Immutable **사용 금지** (A2 결정·AbstractCreatedOnlyEntity Javadoc 정합)
- Factory: `create(Inventory inventory, InventoryHistoryChangeType changeType, int quantityDelta, String referenceType, Long referenceId, String reason)`

**InventoryHistoryChangeType enum** (신규):
```
package com.zslab.mall.inventory.enums;
// 6값: ORDER, CANCEL, RETURN, ADJUST, INBOUND, OUTBOUND (V1 ENUM 정합·4층위 Layer 2)
```

### 5.3 Repository 단위 테스트 케이스 후보 (@DataJpaTest)

#### 전제: Test Base 선택

**권고: Batch1DataJpaTestBase 재사용**

| 항목 | 재사용 | 신설 (Batch2DataJpaTestBase) |
|---|---|---|
| 기능 차이 | 없음 (MariaDB 11.4·Flyway V1~V5·AuditingConfig 동일) | 동일 setup 복제만 |
| 코드 중복 | 없음 | 테스트 인프라 중복 |
| 명명 일관성 | "Batch1" 네이밍 artifact 허용 | 신설 비용 > 네이밍 이득 |
| Spring Entity 스캔 | @DataJpaTest가 모든 @Entity 자동 스캔 → Batch-2 Entity 포함 | 동일 |

재사용 시 클래스명이 "Batch1"로 고정되어 있어 약간 어색하나, 신설 비용 대비 이득이 없다. 향후 공통 이름(예: `TrackSevenDataJpaTestBase`)으로 리네이밍이 필요하면 Track 7 PR 마지막 단계에서 1회 처리 권고.

#### 테스트 케이스 목록

| Entity | 테스트 케이스 | 검증 포인트 |
|---|---|---|
| UserRole | save+findById 성공 | userId·role 보존 |
| UserRole | UK(user_id, role_id) 중복 삽입 → DataIntegrityViolationException | UK constraint |
| UserRole | user_id FK 위반 삽입 → PersistenceException | FK RESTRICT |
| UserRole | role_id FK 위반 삽입 → PersistenceException | FK RESTRICT |
| RolePermission | save+findById 성공 | role·permission 보존 |
| RolePermission | UK(role_id, permission_id) 중복 삽입 → DataIntegrityViolationException | UK constraint |
| RolePermission | role_id FK 위반 삽입 → PersistenceException | FK RESTRICT |
| RolePermission | permission_id FK 위반 삽입 → PersistenceException | FK RESTRICT |
| SellerUser | save+findById 성공 | seller·userId·roleId 보존 |
| SellerUser | UK(seller_id, user_id) 중복 삽입 → DataIntegrityViolationException | UK constraint |
| SellerUser | seller_id FK 위반 삽입 → PersistenceException | FK RESTRICT |
| SellerUser | user_id FK 위반 삽입 → PersistenceException | FK RESTRICT |
| BuyerPurchaseAggregate | save+findById 성공 (buyerId PK 직접 할당) | 논리 PK·last_ordered_at NULL 허용 |
| SellerSalesDaily | save+findById(@IdClass) 성공 | 복합 PK 보존·LocalDate 매핑 |
| SellerSalesDaily | 동일 (seller_id, sale_date) 중복 삽입 → DataIntegrityViolationException | 복합 PK UK constraint |
| InventoryHistory | save+findById 성공 | inventory·changeType·quantityDelta 보존 |
| InventoryHistory | inventory_id FK 위반 삽입 → PersistenceException | FK RESTRICT |
| InventoryHistory | change_type ENUM 외 값 삽입 → PersistenceException | DB ENUM constraint (Role 테스트 패턴 정합) |

**총 18 케이스** (파일 6개·케이스당 배분: UserRoleRepositoryTest 4·RolePermissionRepositoryTest 4·SellerUserRepositoryTest 4·BuyerPurchaseAggregateRepositoryTest 1·SellerSalesDailyRepositoryTest 2·InventoryHistoryRepositoryTest 3)

#### UK/FK 위반 시드 방식

- **@ManyToOne로 참조하는 내부 Entity**: 정상 Entity 먼저 saveAndFlush 후 사용
- **FK 위반 삽입**: `entityManager.getEntityManager().createNativeQuery(...).executeUpdate()` — Batch-1 패턴 정합 (CodeRepositoryTest·GradePolicyRepositoryTest)
- **user_id·role_id (외부 Long 필드) FK 위반**: nativeQuery INSERT로 99999 등 미존재 ID 직접 삽입

---

## §6. PASS/WARN/FAIL 분류

### WARN-1: seller_user COMMENT vs DDL 불일치

- **현상**: V1 DDL COMMENT=`판매자 구성원(Seller 귀속·SOFT 상속)` → "SOFT 상속"이나 DDL에 deleted_at 컬럼 없음
- **판단**: "SOFT 상속"은 상위 Seller가 soft-delete될 때 구성원 행 처리가 상위에 종속된다는 의미·seller_user 자체 deleted_at은 부재. AbstractSoftDeletableEntity Javadoc 명시 "V1 DDL이 deleted_at 컬럼을 정확히 이 8개 테이블에만 부여한다" → seller_user는 8개 포함 아님·DDL 실측 우선 → **AbstractFullAuditableEntity 적용**
- **조치 필요**: 없음 (DDL 변경 D-25 금지·COMMENT 오해 무시·DDL 기준 매핑)
- Batch-1 WARN-1 (buyer_grade) 동일 패턴

### 종합

| 분류 | 건수 |
|---|---|
| PASS (경계 검증) | 6 / 6 |
| PASS (D-01 위반 없음) | 6 / 6 |
| PASS (abstract 매칭 확정) | 6 / 6 |
| WARN | 1건 (seller_user COMMENT vs DDL) |
| FAIL | 0건 |

---

## §7. OUT-OF-SCOPE (정찰 트랙 한정 차단 사항)

이하 항목은 **Track 7 Batch-2 PR-2 구현 범위 밖**이며 본 보고서에서 설계·언급 금지:

- Application Service 전원 — Track 8+ 이연
- Controller / DTO / API 명세 (@Valid·@Pattern·Request/Response)
- BuyerPurchaseAggregate 갱신 이벤트 핸들러 E6 — Track 8+ 이연 (D-81 §3)
- SellerSalesDaily 배치 갱신 로직 — Track 8+ 이연
- seller_user 역할 배정·회수 Application 로직 — Track 8+ 이연
- InventoryHistory 쓰기 Application 로직 (INV Service) — Track 8+ 이연
- State Machine canTransitionTo — Batch-2에 상태 전이 없음 (해당 없음)
- E2E / 통합 테스트 (@SpringBootTest·Testcontainers 전체 컨텍스트) — Application Service 이연 후 Track 8+
- 도메인 이벤트 발행 핸들러
- Read Model 갱신 정책 확정 (이벤트 핸들러 E6 vs 배치 주기) — Track 8+ 결정 범위
- NotificationLog 발송 연동 로직
- 프론트 constants 파일 (4층위 Layer 4) — 프론트 Track 별도
- 외부 검토 — B급·외부 검토 생략 (D-81 §4)

---

## §8. 후속 권고

**정찰 완료 상태**: §1~§7 전 항목 실측·부정합 0건·WARN 1건(조치 무필요).

**결정 요청 항목**:

| # | 항목 | 권고안 | 결정 필요 이유 |
|---|---|---|---|
| Q1 | SellerSalesDaily 복합 PK: `@IdClass` vs `@EmbeddedId` | **@IdClass 권고** (§5.2.1 상세) | 코드 접근 패턴 결정 |
| Q2 | Test Base: Batch1DataJpaTestBase 재사용 vs 신설 | **재사용 권고** (§5.3 상세) | 네이밍 아티팩트 허용 여부 |
| Q3 | SellerUser.roleId `updatable` 여부 | **`updatable = false` 권고** (초기 배정 후 변경 시 삭제·재생성 패턴) | 역할 변경 업무 정책 |

Q1·Q2는 권고안이 명확하며 사용자 반대 의견 없으면 권고안 채택 후 구현 착수 가능.  
Q3는 업무 정책(판매자 구성원 역할 변경 허용 여부)에 따라 결정·Track 8+ Application Service 진입 전 결정 가능.

**expected-spec.md 박제 여부**: Batch-1 패턴 정합 — 박제 생략. recon-report.md SoT 활용·구현 착수 가능.

**구현 라운드 진입 가능 여부**: WARN 1건 조치 무필요·FAIL 0건·D-01 위반 없음 → **구현 착수 가능**.  
단, Q1·Q2 권고 채택 확인 후 진입 권장 (사용자 검토 후 결정).

---

*참조: D-81 §1~§4 (Track 7 분할·산출물 범위·B급 가드)·D-82 (라이브 트랩·LT-03 확인 완료)·aggregate-boundary.md §2.1·§2.2·§2.3·§3·V1__init.sql #11·#12·#13·#15·#18·#26·live-traps.md LT-03 (Batch-2 해당 없음 확인)*
