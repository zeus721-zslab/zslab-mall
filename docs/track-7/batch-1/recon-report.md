# Track 7 Batch-1 정찰 보고서 — 시드 7 (read-only)

> 작성일: 2026-06-28  
> 브랜치: main (정찰 전용·코드 변경 0)  
> 범위: role · permission · buyer_grade · code_group · code · category · grade_policy  
> 근거: D-81 §1 Batch-1 (시드성·B급·외부 검토 생략)

---

## §1. 시드 7 테이블 DDL 1:1 추출 (V1__init.sql 기준)

### 1.1 role (테이블 #2)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| code | ENUM('SUPER_ADMIN','ADMIN_OPERATOR','BUYER','SELLER_OWNER','SELLER_MANAGER','SELLER_STAFF') | NOT NULL | 역할 코드·A#2 잠금(AUTH-3) |
| name | VARCHAR(50) | NOT NULL | 역할 표시명 |
| created_at | DATETIME(6) | NOT NULL | |
| updated_at | DATETIME(6) | NOT NULL | |

- PK: id
- UK: 없음 (role.code는 ENUM 잠금이나 UK 없음)
- FK: 없음
- INDEX: 없음
- soft-delete 컬럼: 없음
- public_id 컬럼: 없음
- 시드 데이터 INSERT: V1에 없음
- COMMENT: `역할(Auth Aggregate Root·HARD·시드성)`

### 1.2 permission (테이블 #3)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| code | VARCHAR(50) | NOT NULL | 권한 코드 |
| name | VARCHAR(200) | NOT NULL | 권한 표시명 |
| created_at | DATETIME(6) | NOT NULL | |
| updated_at | DATETIME(6) | NOT NULL | |

- PK: id
- UK: 없음
- FK: 없음
- INDEX: 없음
- soft-delete 컬럼: 없음
- public_id 컬럼: 없음
- 시드 데이터 INSERT: V1에 없음
- COMMENT: `권한(Auth 종속·HARD·시드성)`
- 주의: code가 VARCHAR(50) — role.code와 달리 ENUM 아님

### 1.3 buyer_grade (테이블 #4)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| code | ENUM('SILVER','GOLD','PLATINUM') | NOT NULL | 등급 코드·A#3 잠금(GRD-2) |
| name | VARCHAR(50) | NOT NULL | 등급 표시명 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: 없음
- FK: 없음
- INDEX: 없음
- soft-delete 컬럼: **없음** (COMMENT는 "SOFT"이나 DDL에 deleted_at 없음 — §6 WARN-1)
- public_id 컬럼: 없음
- 시드 데이터 INSERT: V1에 없음
- COMMENT: `구매자 등급(GRD Aggregate Root·SOFT)`

### 1.4 code_group (테이블 #6)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| code | VARCHAR(50) | NOT NULL | 코드 그룹 코드 (예: ORDER_STATUS) |
| name | VARCHAR(100) | NOT NULL | 코드 그룹명 |
| description | TEXT | NULL | 설명 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: 없음 (code 컬럼이 사실상 유니크하나 DDL UK 선언 없음)
- FK: 없음
- INDEX: 없음
- soft-delete 컬럼: **없음** (COMMENT는 "SOFT(is_system=F)"이나 DDL에 deleted_at 없음 — §6 WARN-2)
- public_id 컬럼: 없음
- 시드 데이터 INSERT: V1에 없음
- COMMENT: `코드 그룹(COD Aggregate Root·SOFT(is_system=F))`

### 1.5 code (테이블 #19)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| group_id | BIGINT | NOT NULL | FK→code_group(N:1) |
| code | VARCHAR(50) | NOT NULL | 코드 값 |
| label | VARCHAR(100) | NOT NULL | 표시 라벨(운영자 편집) |
| display_order | INT | NOT NULL | 정렬 순서 |
| is_active | TINYINT(1) | NOT NULL | 활성 여부 |
| is_system | TINYINT(1) | NOT NULL | 시스템 코드 여부·삭제 금지(COD-1) |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: uk_code_group_code(group_id, code)
- FK: fk_code_group → code_group(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: InnoDB 자동 (group_id FK)
- soft-delete 컬럼: **없음** (COMMENT "SOFT(is_system=F)" — is_system=F만 삭제 허용·DB deleted_at 없음 — §6 WARN-2 동일)
- public_id 컬럼: 없음
- 시드 데이터 INSERT: V1에 없음
- COMMENT: `코드(COD 종속·SOFT(is_system=F))`

### 1.6 category (테이블 #7, self-ref)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| parent_id | BIGINT | NULL | FK→category(id) self-ref·루트는 NULL |
| display_name | VARCHAR(200) | NOT NULL | 카테고리 표시명 |
| depth | INT | NOT NULL | 계층 깊이(CAT-2) |
| sort_order | INT | NOT NULL | 정렬 순서 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |
| deleted_at | DATETIME(6) | NULL | |
| deleted_by | BIGINT | NULL | |
| delete_reason | VARCHAR(255) | NULL | |

- PK: id
- UK: 없음
- FK: fk_category_parent → category(id) ON DELETE RESTRICT ON UPDATE CASCADE (self-ref)
- INDEX: ix_category_deleted_at(deleted_at)
- soft-delete 컬럼: **있음** (deleted_at, deleted_by, delete_reason)
- public_id 컬럼: 없음
- 시드 데이터 INSERT: V1에 없음
- COMMENT: `카테고리(CAT Aggregate Root·SOFT)`

### 1.7 grade_policy (테이블 #14)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| grade_id | BIGINT | NOT NULL | FK→buyer_grade(N:1) |
| min_amount | BIGINT | NOT NULL | 등급 최소 누적구매액 |
| max_amount | BIGINT | NOT NULL | 등급 최대 누적구매액 |
| discount_rate | INT | NOT NULL | 할인율(정수 basis) |
| point_rate | INT | NOT NULL | 적립율(정수 basis) |
| effective_from | DATETIME(6) | NOT NULL | 정책 적용 시작 |
| effective_to | DATETIME(6) | NOT NULL | 정책 적용 종료 |
| version | INT | NOT NULL | 정책 버전 |
| is_active | TINYINT(1) | NOT NULL | 활성 여부 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: 없음
- FK: fk_grade_policy_grade → buyer_grade(id) ON DELETE RESTRICT ON UPDATE CASCADE
- CHECK: chk_grade_policy_amount (min_amount <= max_amount)
- INDEX: InnoDB 자동 (grade_id FK)
- soft-delete 컬럼: 없음
- public_id 컬럼: 없음
- 시드 데이터 INSERT: V1에 없음
- COMMENT: `등급 정책(GRD 종속·SOFT 상속)`

---

## §2. Aggregate 경계 정합 검증

aggregate-boundary.md §2.1·§2.3·§2.6 명시 귀속 ↔ V1 DDL FK 방향 1:1 대조

| Entity | aggregate-boundary 귀속 | DDL FK 방향 | 결과 |
|---|---|---|---|
| role | Auth Root (§2.1) | FK 없음 (Root는 부모 없음) | **PASS** |
| permission | Auth 종속 (§2.1) | FK 없음 — role↔permission 연결은 role_permission N:M (Batch-2) | **PASS** |
| buyer_grade | BuyerGrade Root (§2.1) | FK 없음 (Root는 부모 없음) | **PASS** |
| grade_policy | BuyerGrade 종속 (§2.1) | FK→buyer_grade.id (자식→Root) | **PASS** |
| code_group | Code Root (§2.6) | FK 없음 (Root는 부모 없음) | **PASS** |
| code | Code 종속 (§2.6) | FK→code_group.id (자식→Root) | **PASS** |
| category | Category Root·self-ref (§2.3) | FK→category.id (self·계층 표현) | **PASS** |

**외부 Aggregate 참조**: Batch-1 7개 Entity 전원 외부 Aggregate ID 참조 없음.  
**D-01 위반**: 없음 (Batch-1 내부 완결·FK는 동일 Aggregate 내부만).

---

## §3. Common Entity abstract 매칭 사전 검토

### abstract 8종 요약

| 클래스 | 컬럼 | Javadoc 적용 대상 명시 |
|---|---|---|
| AbstractSeedEntity | created_at + updated_at | Role·Permission (시드성 2) |
| AbstractMappingEntity | created_at | UserRole·RolePermission (매핑 2) |
| AbstractCreatedOnlyEntity | created_at + created_by | InventoryHistory·NotificationLog·AuditLog (append-only 3) |
| AbstractFullAuditableEntity | created_at + created_by + updated_at + updated_by | Settlement·Inventory·SellerBankAccount 등 |
| AbstractSoftDeletableEntity | AbstractFullAuditableEntity + deleted_at + deleted_by + delete_reason | Category·UserAddress·ProductImage (직접 상속 3) |
| AbstractPublicIdFullAuditableEntity | AbstractFullAuditableEntity + public_id | Order·OrderItem·Payment·Delivery·Claim·Refund (6) |
| AbstractPublicIdSoftDeletableEntity | AbstractSoftDeletableEntity + public_id | User·Seller·Product·ProductVariant·Attachment (5) |
| AbstractAggregateEntity | updated_at | BuyerPurchaseAggregate·SellerSalesDaily (집계 Read Model 2) |

### Batch-1 Entity별 매칭 결과

| Entity | 권고 abstract | soft_del | public_id | PK 타입 | 근거 |
|---|---|---|---|---|---|
| role | **AbstractSeedEntity** | N | N | BIGINT AUTO | Javadoc 명시·DDL = created_at+updated_at only (no _by) |
| permission | **AbstractSeedEntity** | N | N | BIGINT AUTO | Javadoc 명시·DDL = created_at+updated_at only (no _by) |
| buyer_grade | **AbstractFullAuditableEntity** | N | N | BIGINT AUTO | DDL full audit 4컬럼·deleted_at 없음 (§6 WARN-1) |
| code_group | **AbstractFullAuditableEntity** | N | N | BIGINT AUTO | DDL full audit 4컬럼·deleted_at 없음 (§6 WARN-2) |
| code | **AbstractFullAuditableEntity** | N | N | BIGINT AUTO | DDL full audit 4컬럼·deleted_at 없음 (§6 WARN-2) |
| category | **AbstractSoftDeletableEntity** | Y | N | BIGINT AUTO | DDL deleted_at+deleted_by+delete_reason 존재·Javadoc "직접 상속 3" 명시 |
| grade_policy | **AbstractFullAuditableEntity** | N | N | BIGINT AUTO | DDL full audit 4컬럼·deleted_at 없음 |

**@SQLRestriction 적용 대상**: category만 해당 (AbstractSoftDeletableEntity 상속 시 자동 적용).

---

## §4. Track 2~5 Entity 구현 패턴 학습 결과

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
```

### 4.2 외부 Aggregate 참조 패턴 (D-01)

```java
// 외부 Aggregate는 ID 필드만 — @ManyToOne 금지
@Column(name = "buyer_id", nullable = false)
private Long buyerId;

// 같은 Aggregate 내부 FK: @ManyToOne LAZY + @JoinColumn
@Getter(AccessLevel.NONE)                // 역참조 외부 노출 차단 시
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "order_id", nullable = false, updatable = false)
private Order order;
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
    e.status = SomeEnum.INITIAL_STATE;
    return e;
}
```

### 4.4 equals/hashCode 규칙

- AbstractPublicId* 상속 → publicId가 Include 처리·하위 클래스 추가 불필요
- **public_id 없는 Entity** → id 필드에 `@EqualsAndHashCode.Include` 명시 의무
- 클래스 레벨: `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` (base class에서 이미 선언)

### 4.5 TINYINT(1) / CHAR(30) 매핑

```java
// TINYINT(1) → boolean 원시 타입 (Boolean 래퍼 금지)
@Column(name = "is_active", nullable = false)
private boolean isActive;

// CHAR(30) public_id → @JdbcTypeCode(SqlTypes.CHAR) (D-26 정합)
@JdbcTypeCode(SqlTypes.CHAR)
@Column(name = "public_id", length = 30, nullable = false, updatable = false)
private String publicId;
```

### 4.6 Batch-1 적용 차이점

Track 2~5 대비 Batch-1은:
- 도메인 이벤트 없음 (`@Transient List<Object> domainEvents` 불필요)
- State machine canTransitionTo 없음 (Track 7 범위 외·D-81 §3)
- public_id 없음 (7개 Entity 모두 외부 노출 식별자 불필요)
- category self-ref: parent_id를 ID 필드로 보유하거나 @ManyToOne self-ref 선택 가능 — 내부 Aggregate self-ref이므로 `@ManyToOne(fetch = LAZY)` 허용

---

## §5. 매핑 설계 사전 검토

### 5.1 패키지 구조 권고

```
com.zslab.mall.auth.entity      → Role, Permission
com.zslab.mall.auth.enums       → RoleCode
com.zslab.mall.auth.repository  → RoleRepository, PermissionRepository

com.zslab.mall.grade.entity     → BuyerGrade, GradePolicy
com.zslab.mall.grade.enums      → BuyerGradeCode
com.zslab.mall.grade.repository → BuyerGradeRepository, GradePolicyRepository

com.zslab.mall.code.entity      → CodeGroup, Code
com.zslab.mall.code.repository  → CodeGroupRepository, CodeRepository

com.zslab.mall.category.entity  → Category
com.zslab.mall.category.repository → CategoryRepository
```

### 5.2 Entity별 설계 사전 검토

#### role
- abstract: AbstractSeedEntity
- ENUM: `RoleCode` in `com.zslab.mall.auth.enums` — 4층위 Layer 2 (DB ENUM 이미 Layer 1)
- `@EqualsAndHashCode.Include` on `id`
- Factory create(RoleCode code, String name)

#### permission
- abstract: AbstractSeedEntity
- code는 VARCHAR(50) → String 필드 (enum 아님)
- `@EqualsAndHashCode.Include` on `id`
- Factory create(String code, String name)

#### buyer_grade
- abstract: AbstractFullAuditableEntity
- ENUM: `BuyerGradeCode` in `com.zslab.mall.grade.enums` — 4층위 Layer 2
- `@EqualsAndHashCode.Include` on `id`
- Factory create(BuyerGradeCode code, String name)

#### code_group
- abstract: AbstractFullAuditableEntity
- code는 VARCHAR(50) → String 필드
- `@EqualsAndHashCode.Include` on `id`
- Factory create(String code, String name, String description)

#### code
- abstract: AbstractFullAuditableEntity
- `@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "group_id", nullable = false, updatable = false)` — 내부 Aggregate FK
- is_active, is_system → `boolean`
- `@EqualsAndHashCode.Include` on `id`
- Factory create(CodeGroup group, String code, String label, int displayOrder)

#### category
- abstract: AbstractSoftDeletableEntity
- self-ref: `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id")` nullable=true (루트는 NULL)
- `@SQLRestriction` 자동 적용 (상위 클래스)
- `@EqualsAndHashCode.Include` on `id`
- Factory create(Category parent, String displayName, int depth, int sortOrder)
- 주의: parent null 허용 (루트 카테고리)

#### grade_policy
- abstract: AbstractFullAuditableEntity
- `@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "grade_id", nullable = false, updatable = false)` — 내부 Aggregate FK
- min_amount, max_amount → Long (BIGINT·KRW 정수)
- discount_rate, point_rate, version → int (INT 원시 타입)
- is_active → boolean
- DB CHECK (min_amount <= max_amount) → @Column만 매핑, 검증은 DB CHECK 의존
- `@EqualsAndHashCode.Include` on `id`
- Factory create(BuyerGrade grade, Long minAmount, Long maxAmount, int discountRate, int pointRate, LocalDateTime effectiveFrom, LocalDateTime effectiveTo, int version)

### 5.3 Repository 단위 테스트 케이스 후보 (@DataJpaTest)

| Entity | 테스트 케이스 | 검증 포인트 |
|---|---|---|
| role | save+findById 성공 | 기본 CRUD |
| role | code ENUM 외 값 삽입 시도 | DB ENUM constraint 위반 |
| permission | save+findById 성공 | 기본 CRUD |
| buyer_grade | save+findById 성공 | 기본 CRUD |
| code_group | save+findById 성공 | 기본 CRUD |
| code | UK(group_id, code) 중복 삽입 | DataIntegrityViolationException |
| code | group_id FK 위반 삽입 | DataIntegrityViolationException (FK RESTRICT) |
| category | 루트 카테고리 삽입 (parent_id=null) | parent_id NULL 허용 |
| category | soft-delete 후 findById | Optional.empty() (@SQLRestriction 적용) |
| grade_policy | grade_id FK 위반 삽입 | DataIntegrityViolationException |
| grade_policy | min_amount > max_amount 삽입 | DataIntegrityViolationException (CHECK constraint) |

### 5.4 Track 7 산출물 범위 확인 (D-81 §3 정합)

**포함 (Track 7 PR-1 범위):**
- Entity 7개 (JPA 매핑)
- Repository 인터페이스 7개 (JpaRepository 상속)
- Repository 단위 테스트 (@DataJpaTest·CRUD·UK·FK·CHECK)
- Java enum 2개 (RoleCode·BuyerGradeCode — 4층위 Layer 2)

**이연 (Track 8+):**
- Application Service (GradeService·CodeService 등)
- Controller·DTO·API 명세
- State machine canTransitionTo (Batch-1에 상태 전이 없음·해당 없음)
- Invariant 검증 로직
- E2E/통합 테스트 (Application Service 동반 필요)
- 도메인 이벤트 핸들러

---

## §6. PASS/WARN/FAIL 분류

### WARN-1: buyer_grade COMMENT vs DDL 불일치

- **현상**: V1 DDL COMMENT='구매자 등급(GRD Aggregate Root·SOFT)' → "SOFT" 표기이나 DDL에 deleted_at 컬럼 없음
- **판단**: AbstractSoftDeletableEntity Javadoc이 "V1 DDL이 deleted_at 컬럼을 정확히 8개 테이블에만 부여한다"고 명시. buyer_grade는 8개 포함 아님. DDL 실측 우선 → **AbstractFullAuditableEntity 적용**
- **조치 필요**: 없음 (DDL 변경 D-25 금지·COMMENT 오해 무시하고 DDL 기준 매핑)

### WARN-2: code_group·code COMMENT vs DDL 불일치

- **현상**: COMMENT='SOFT(is_system=F)' — is_system=false인 행만 삭제 허용의 의미이나 DDL에 deleted_at 없음
- **판단**: "SOFT"는 비즈니스 삭제 정책(is_system=F 행만 물리 삭제 허용)이며 DB 소프트 삭제 패턴(deleted_at 마킹)이 아님. DDL 실측 우선 → **AbstractFullAuditableEntity 적용**
- **조치 필요**: 없음 (Application Service에서 is_system 체크 후 HARD delete — Track 8+ 범위)

### 종합

| 분류 | 건수 |
|---|---|
| PASS (경계 검증) | 7 / 7 |
| PASS (abstract 매칭 확정) | 7 / 7 |
| WARN | 2건 (buyer_grade·code_group/code COMMENT vs DDL) |
| FAIL | 0건 |

---

## §7. OUT-OF-SCOPE (정찰 트랙 한정 차단 사항)

이하 항목은 **Track 7 Batch-1 PR-1 구현 범위 밖**이며 본 보고서에서 설계·언급 금지:

- Application Service (GradeService·CodeService·AuthService 등) — Track 8+ 이연
- Controller / DTO / API 명세 (@Valid·@Pattern·Request/Response)
- State Machine canTransitionTo 메서드 — Batch-1 Entity에 상태 전이 없음 (role.code·buyer_grade.code는 식별 enum, 상태 enum 아님)
- Invariant 검증 로직 (grade_policy min≤max는 DB CHECK로만·Track 7 범위)
- E2E / 통합 테스트 (@SpringBootTest·Testcontainers) — Application Service 이연 후 Track 8+
- 도메인 이벤트 발행 핸들러
- 프론트 constants 파일 (4층위 Layer 4) — 프론트 Track 별도
- 외부 검토 — B급·외부 검토 생략 (D-81 §4)

---

## §8. 후속 권고

**정찰 완료 상태**: §1~§7 전 항목 실측·부정합 0건·WARN 2건(조치 무필요).

**expected-spec.md 박제 라운드 준비 상태**: 완료.

다음 단계: `docs/track-7/batch-1/expected-spec.md` 작성 (Batch-1 PR-1 구현 명세·Entity 7개·Repository 7개·Repository 테스트 케이스 목록 박제) 후 구현 착수.

---

*참조: D-81 §1~§4 (Track 7 분할·산출물 범위·B급 가드)·aggregate-boundary.md §2.1·§2.3·§2.6·V1__init.sql #2·#3·#4·#6·#7·#14·#19*
