# Track 7 Batch-3a 정찰 보고서 — User Aggregate 4 (read-only)

> 작성일: 2026-06-28
> 브랜치: docs/track-7-batch-3a-recon (정찰 전용·코드 변경 0)
> 범위: user · withdrawn_user · buyer_profile · user_address
> 근거: D-81 §1 Batch-3 PR-3a (User Aggregate 4·A급·선택적 외부 검토)

---

## §1. User Aggregate 4 테이블 V1 DDL 1:1 추출

### 1.1 user (테이블 #1)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| public_id | CHAR(30) | NOT NULL | UK·prefix usr_·외부 노출 식별자 |
| email | VARCHAR(254) | NULL | 로그인 이메일·UK·탈퇴 비식별화 시 NULL (D-22) |
| name | VARCHAR(50) | NULL | 회원명·비식별화 대상 |
| phone | VARCHAR(20) | NULL | 휴대폰·비식별화 대상 |
| withdrawn_at | DATETIME(6) | NULL | 탈퇴 요청 시각 |
| anonymized_at | DATETIME(6) | NULL | 비식별화 완료 시각 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |
| deleted_at | DATETIME(6) | NULL | soft-delete 마킹 |
| deleted_by | BIGINT | NULL | |
| delete_reason | VARCHAR(255) | NULL | |

- PK: id
- UK: uk_user_public_id (public_id), uk_user_email (email)
- FK: 없음 (Aggregate Root)
- INDEX: ix_user_deleted_at (deleted_at)
- soft-delete 컬럼: Y (deleted_at·deleted_by·delete_reason)
- public_id 컬럼: Y (prefix usr_)
- COMMENT: `회원(USR Aggregate Root·SOFT·public_id usr_)`

### 1.2 withdrawn_user (테이블 #8)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| original_user_id | BIGINT | NOT NULL | FK→user·탈퇴 전 User.id |
| withdraw_reason | VARCHAR(255) | NULL | 탈퇴 사유 |
| legal_retention_until | DATETIME(6) | NULL | 법정 보관 만료 시각 |
| anonymized_at | DATETIME(6) | NULL | 비식별화 완료 시각 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: 없음
- FK: fk_withdrawn_user_user → user(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: InnoDB 자동 (original_user_id FK)
- soft-delete 컬럼: N (deleted_at 없음·ARCHIVE 분류)
- public_id 컬럼: N
- COMMENT: `탈퇴 회원 아카이브(USR 종속·ARCHIVE)`

### 1.3 buyer_profile (테이블 #9) — 공유 PK = user_id 특수 케이스

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| user_id | BIGINT | NOT NULL | PK(공유)·FK→user 1:1 |
| grade_id | BIGINT | NOT NULL | FK→buyer_grade (외부 Aggregate) |
| grade_source | ENUM('AUTO','MANUAL','EVENT') | NOT NULL | 등급 산정 출처·A#1 잠금 |
| grade_locked_until | DATETIME(6) | NULL | 등급 고정 만료 시각 |
| grade_updated_at | DATETIME(6) | NULL | 등급 변경 시각 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: user_id (공유 PK·FK와 동일)
- UK: 없음
- FK: fk_buyer_profile_user → user(id) ON DELETE RESTRICT ON UPDATE CASCADE
- FK: fk_buyer_profile_grade → buyer_grade(id) ON DELETE RESTRICT ON UPDATE CASCADE (외부 Aggregate)
- INDEX: InnoDB 자동 (grade_id FK)
- soft-delete 컬럼: N (deleted_at 없음·SOFT 상속 = User soft-delete 경유)
- public_id 컬럼: N
- 특수: grade_source ENUM A#1 잠금 → 4층위 enum 의무 (Layer 1 DDL ✓·Layer 2 Java enum 구현 필요)
- COMMENT: `구매자 프로필(USR 종속·SOFT 상속·user_id 공유 PK)`

### 1.4 user_address (테이블 #10)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| user_id | BIGINT | NOT NULL | FK→user |
| is_default | TINYINT(1) | NOT NULL | 기본 배송지 여부 |
| address_label | VARCHAR(50) | NULL | 배송지 별칭 |
| recipient_name | VARCHAR(50) | NOT NULL | 수령인명 |
| recipient_phone | VARCHAR(20) | NOT NULL | 수령인 연락처 |
| zonecode | VARCHAR(10) | NOT NULL | 우편번호 |
| address_road | VARCHAR(200) | NOT NULL | 도로명 주소 |
| address_jibun | VARCHAR(200) | NULL | 지번 주소 |
| address_detail | VARCHAR(200) | NULL | 상세 주소 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |
| deleted_at | DATETIME(6) | NULL | soft-delete 마킹 |
| deleted_by | BIGINT | NULL | |
| delete_reason | VARCHAR(255) | NULL | |

- PK: id
- UK: 없음
- FK: fk_user_address_user → user(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: ix_user_address_deleted_at (deleted_at), InnoDB 자동 (user_id FK)
- soft-delete 컬럼: Y (deleted_at·deleted_by·delete_reason)
- public_id 컬럼: N
- COMMENT: `배송지(USR 종속·SOFT)`

---

## §2. Aggregate 경계 정합 검증

### 2.1 aggregate-boundary §2.1 ↔ V1 DDL FK 방향 대조표

| Entity | 분류 | FK 방향 | Aggregate 귀속 |
|---|---|---|---|
| user | Root | 없음 | User Aggregate Root |
| withdrawn_user | 종속 | fk→user (internal) | User 포함 |
| buyer_profile | 종속 | fk→user (internal, 공유 PK)·fk→buyer_grade (external) | User 포함 |
| user_address | 종속 | fk→user (internal) | User 포함 |

**경계 결정 재확인**:
- WithdrawnUser·BuyerProfile·UserAddress 모두 User 없이 생성 불가 → User 포함 (aggregate-boundary §2.1 정합)
- 3종 모두 User Root를 통한 생명주기 공유

### 2.2 D-01 위반 검증

| 참조 | 대상 Aggregate | D-01 처치 |
|---|---|---|
| buyer_profile.grade_id → buyer_grade | BuyerGrade (외부) | Long gradeId 필드 (@ManyToOne 금지) ✓ |
| withdrawn_user.original_user_id → user | User (내부) | @ManyToOne LAZY 허용 (동일 Aggregate) |
| user_address.user_id → user | User (내부) | @ManyToOne LAZY 허용 (동일 Aggregate) |
| buyer_profile.user_id → user | User (내부, 공유 PK) | @MapsId + @OneToOne LAZY 허용 (동일 Aggregate) |

D-01 외부 위반: 0건 (buyer_profile.grade_id → Long gradeId 단일 처치로 해소)

---

## §3. Common Entity Abstract 매칭

| Entity | 권고 abstract | soft_del | public_id | PK 타입 | 근거 |
|---|---|---|---|---|---|
| user | AbstractPublicIdSoftDeletableEntity | Y | Y (usr_) | BIGINT AUTO | DDL public_id CHAR(30)·deleted_at 있음·Javadoc 적용 대상 5종 중 1 |
| withdrawn_user | AbstractFullAuditableEntity | N | N | BIGINT AUTO | ARCHIVE 분류·deleted_at 없음·full audit 4컬럼 |
| buyer_profile | AbstractFullAuditableEntity | N | N | BIGINT 공유 PK (user_id) | full audit 4컬럼·deleted_at 없음·공유 PK 특수(@MapsId) |
| user_address | AbstractSoftDeletableEntity | Y | N | BIGINT AUTO | deleted_at·no public_id·직접 상속 3종 중 1 |

**Abstract 클래스 계층 확인**:
- AbstractFullAuditableEntity: created_at·created_by·updated_at·updated_by (4컬럼)
- AbstractSoftDeletableEntity extends AbstractFullAuditableEntity: + deleted_at·deleted_by·delete_reason·@SQLRestriction (MappedSuperclass)
- AbstractPublicIdSoftDeletableEntity extends AbstractSoftDeletableEntity: + public_id @JdbcTypeCode(CHAR)·@PrePersist generatePublicId()

---

## §4. 라이브 트랩 처치 의무

### 4.1 LT-03: @SQLRestriction @MappedSuperclass → @Entity 비전파 (HHH-17453)

**처치 의무 2건**:

| Entity | 상속 abstract | 처치 방법 | Category 정합 |
|---|---|---|---|
| User | AbstractPublicIdSoftDeletableEntity (경유 AbstractSoftDeletableEntity) | @SQLRestriction("deleted_at IS NULL") @Entity 클래스 직접 선언 | Category 패턴 동일 |
| UserAddress | AbstractSoftDeletableEntity | @SQLRestriction("deleted_at IS NULL") @Entity 클래스 직접 선언 | Category 패턴 동일 |

Category.java 참조 패턴 (Track 7 Batch-1 완료):
```
@Entity
@Table(name = "category")
@SQLRestriction("deleted_at IS NULL")  // 직접 선언 의무 — AbstractSoftDeletableEntity 중복 선언 의도적
public class Category extends AbstractSoftDeletableEntity { ... }
```

→ User·UserAddress도 동일 패턴 직접 선언 필수.

### 4.2 LT-01: CHAR(30) public_id @JdbcTypeCode 자동 적용

User는 AbstractPublicIdSoftDeletableEntity를 상속하며, 해당 abstract 클래스 본문에 이미 `@JdbcTypeCode(SqlTypes.CHAR)` 선언 확인 완료 (Track B D-26·LT-01). 구체 Entity 추가 작업 없음 — 자동 적용.

### 4.3 LT-02: SET FOREIGN_KEY_CHECKS HikariCP 잔류

Batch-3a 직접 적용 대상 없음. 단, 구현 라운드에서 User FK를 갖는 하위 Entity 시딩 시 FK 체인 순서 확인 필요. SET FOREIGN_KEY_CHECKS=0 사용 시 try-finally 복원 패턴 의무 (LT-02 정합).

---

## §5. 매핑 설계 사전 검토

### 5.1 패키지 구조 권고

```
com.zslab.mall.user.entity
  User
  WithdrawnUser
  BuyerProfile
  UserAddress
com.zslab.mall.user.enums
  GradeSource               (4층위 Layer 2·A#1·buyer_profile.grade_source)
com.zslab.mall.user.repository
  UserRepository
  WithdrawnUserRepository
  BuyerProfileRepository
  UserAddressRepository
```

BuyerProfile을 user 패키지에 두는 근거: aggregate-boundary §2.1 User Aggregate 종속 정합. BuyerGrade Aggregate의 grade_id는 Long 필드로 참조 (D-01 외부 Aggregate 참조 정합).

### 5.2 Entity별 설계 사전 검토

#### User

```
@Entity
@Table(name = "user")
@SQLRestriction("deleted_at IS NULL")   // LT-03 직접 선언 의무
extends AbstractPublicIdSoftDeletableEntity
```

주요 필드:
- @Id @GeneratedValue(IDENTITY) Long id + @EqualsAndHashCode.Include
- @Override getPublicIdPrefix() → "usr"
- email VARCHAR(254) nullable (D-22 비식별화·탈퇴 시 NULL)
- name VARCHAR(50) nullable
- phone VARCHAR(20) nullable
- withdrawnAt LocalDateTime nullable
- anonymizedAt LocalDateTime nullable

Factory: `User.create()` (필드 없거나 최소 — 실제 값은 Service 진입 시 설정)

#### WithdrawnUser

```
@Entity
@Table(name = "withdrawn_user")
extends AbstractFullAuditableEntity
```

주요 필드:
- @Id @GeneratedValue(IDENTITY) Long id + @EqualsAndHashCode.Include
- @ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "original_user_id") User originalUser
  (내부 Aggregate → @ManyToOne 허용·SellerUser.seller 패턴 정합)
- withdrawReason String nullable
- legalRetentionUntil LocalDateTime nullable
- anonymizedAt LocalDateTime nullable

Factory: `WithdrawnUser.create(User originalUser, String withdrawReason, LocalDateTime legalRetentionUntil)`

#### BuyerProfile

```
@Entity
@Table(name = "buyer_profile")
extends AbstractFullAuditableEntity
```

주요 필드:
- @Id @Column(name = "user_id") Long userId + @EqualsAndHashCode.Include
- @MapsId @OneToOne(fetch = LAZY, optional = false) @JoinColumn(name = "user_id") User user
  (공유 PK — @MapsId가 user.id를 userId에 자동 채움)
- Long gradeId (nullable = false) — D-01: BuyerGrade 외부 Aggregate → Long ID only
- @Enumerated(EnumType.STRING) GradeSource gradeSource (nullable = false)
- LocalDateTime gradeLoc kedUntil nullable
- LocalDateTime gradeUpdatedAt nullable

Factory: `BuyerProfile.create(User user, Long gradeId, GradeSource gradeSource)`

#### 5.2.1 BuyerProfile 공유 PK 매핑 전략 (Q1 결정 요청 항목)

| 항목 | 옵션 A: @MapsId + @OneToOne | 옵션 B: @Id @Column(user_id) Long userId |
|---|---|---|
| 코드 가독성 | `bp.getUser()` 직접 접근 가능 | userId만 보유·User 재조회 필요 |
| JPA 표준 | 공유 PK 표준 패턴 (@MapsId) | 단순 Long ID·FK 의미 명시 필요 |
| Lazy 로딩 | @OneToOne(LAZY) 가능 | @ManyToOne(LAZY) 별도 추가 시 |
| INSERT 패턴 | User persist → BuyerProfile.create(user) → @MapsId 자동 채움 | user.getId() 명시 set 필요 |
| FK 보장 | @MapsId가 user_id = user.id 강제 | @Column + @JoinColumn 분리 선언 필요 |
| Spring Data | findById(userId) 동일 | 동일 |
| SellerSalesDaily 차이 | 공유 PK (1:1)·@MapsId 표준 | 복합 PK (다차원)·@IdClass 자연 |

권고: 옵션 A (@MapsId + @OneToOne) — JPA 공유 PK 표준 패턴·실수 차단 우위·SellerSalesDaily(@IdClass)와 이유 다름 (공유 PK는 1:1 관계·복합 PK는 다차원).

#### UserAddress

```
@Entity
@Table(name = "user_address")
@SQLRestriction("deleted_at IS NULL")   // LT-03 직접 선언 의무
extends AbstractSoftDeletableEntity
```

주요 필드:
- @Id @GeneratedValue(IDENTITY) Long id + @EqualsAndHashCode.Include
- @ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "user_id") User user
  (내부 Aggregate → @ManyToOne 허용)
- boolean isDefault (DDL TINYINT(1))
- String addressLabel nullable (length 50)
- String recipientName (nullable = false, length 50)
- String recipientPhone (nullable = false, length 20)
- String zonecode (nullable = false, length 10)
- String addressRoad (nullable = false, length 200)
- String addressJibun nullable (length 200)
- String addressDetail nullable (length 200)

Factory: `UserAddress.create(User user, boolean isDefault, String recipientName, String recipientPhone, String zonecode, String addressRoad)`

### 5.3 Enum: GradeSource (4층위 Layer 2 의무)

grade_source는 ENUM('AUTO','MANUAL','EVENT') A#1 잠금 → CLAUDE.md 4층위 enum 잠금 의무:

| 층위 | 상태 | 비고 |
|---|---|---|
| Layer 1 (DB) | ✓ 완료 | V1 DDL ENUM('AUTO','MANUAL','EVENT') |
| Layer 2 (Java enum) | 구현 필요 | com.zslab.mall.user.enums.GradeSource |
| Layer 3 (DTO @ValidEnum) | Track 8+ 이연 | DTO 미신설 단계 |
| Layer 4 (프론트 유니온 타입) | Track 8+ 이연 | 프론트 미연동 단계 |

GradeSource enum 설계:
```
package com.zslab.mall.user.enums;
// 등급 산정 출처 — V1 DDL ENUM 정합 (4층위 Layer 2·buyer_profile.grade_source A#1)
public enum GradeSource { AUTO, MANUAL, EVENT }
```

### 5.4 Repository 단위 테스트 케이스 후보

Batch1DataJpaTestBase 재사용 (D-83 Q2 정합·마커명 아티팩트 허용).

#### UserRepository (4 케이스 권고)

| # | DisplayName | 검증 포인트 |
|---|---|---|
| 1 | save+findById 성공: public_id 자동 생성·deletedAt null | @PrePersist public_id·createdAt·updatedAt 확인 |
| 2 | UK(email) 중복 → DataIntegrityViolationException | uk_user_email 제약 |
| 3 | email NULL 다건 허용 (D-22 정합) | MariaDB NULL UK 비교 제외·2건 INSERT 성공 |
| 4 | **soft-delete 후 findById Optional.empty (@SQLRestriction 검증)** | deleted_at nativeQuery 마킹 → findById empty (LT-03 핵심) |

#### WithdrawnUserRepository (2 케이스 권고)

| # | DisplayName | 검증 포인트 |
|---|---|---|
| 1 | save+findById 성공: originalUser FK 보존·createdAt 자동 | @ManyToOne LAZY 저장 정합 |
| 2 | original_user_id FK 위반 nativeQuery → PersistenceException | FK RESTRICT 제약 |

#### BuyerProfileRepository (3 케이스 권고)

| # | DisplayName | 검증 포인트 |
|---|---|---|
| 1 | save+findById(userId) 성공: @MapsId 자동 채움·gradeSource 보존 | userId = user.id 자동 매핑 확인 |
| 2 | grade_id FK 위반 nativeQuery → PersistenceException | FK RESTRICT (buyer_grade) |
| 3 | user_id(공유 PK) 중복 → DataIntegrityViolationException | 동일 User에 BuyerProfile 2건 생성 시도 |

#### UserAddressRepository (3 케이스 권고)

| # | DisplayName | 검증 포인트 |
|---|---|---|
| 1 | save+findById 성공: user FK 보존·is_default·deletedAt null | 기본 CRUD |
| 2 | user_id FK 위반 nativeQuery → PersistenceException | FK RESTRICT 제약 |
| 3 | **soft-delete 후 findById Optional.empty (@SQLRestriction 검증)** | deleted_at nativeQuery 마킹 → findById empty (LT-03 핵심) |

**LT-03 검증 케이스 필수**: User(케이스 4)·UserAddress(케이스 3) — 각 1건씩 soft-delete 후 findById Optional.empty.

---

## §6. PASS/WARN/FAIL 분류

| 검증 항목 | 결과 | 내용 |
|---|---|---|
| §2 경계 대조 (aggregate-boundary §2.1 ↔ DDL FK) | PASS | WithdrawnUser·BuyerProfile·UserAddress 모두 User 내부 FK — 경계 정합 |
| D-01 외부 Aggregate 위반 검증 | PASS | buyer_profile.grade_id → Long gradeId 처치 1건으로 해소 |
| abstract 매칭 4종 | PASS | User/AbstractPublicIdSoft·WithdrawnUser/FullAuditable·BuyerProfile/FullAuditable·UserAddress/SoftDeletable |
| LT-03 처치 대상 식별 | PASS | User·UserAddress 2건 @SQLRestriction 직접 선언 필수 명시 |
| LT-01 자동 적용 확인 | PASS | AbstractPublicIdSoftDeletableEntity @JdbcTypeCode 확인 완료·구체 Entity 추가 작업 없음 |
| LT-02 적용 대상 | PASS | Batch-3a 직접 대상 없음·구현 라운드 시딩 시 재점검 |
| buyer_profile.grade_source 4층위 | PASS(L1)·구현 필요(L2) | V1 DDL ENUM ✓·Java enum GradeSource 구현 필요 |
| 테스트 케이스 LT-03 검증 포함 | PASS (계획) | User 케이스 4·UserAddress 케이스 3 명시 |

**FAIL: 0건 / WARN: 0건 → 결정 요청(§8) 채택 후 구현 라운드 진입 가능.**

---

## §7. OUT-OF-SCOPE (Track 7 Batch-3a PR-3a 범위 밖)

- Application Service (UserService·BuyerProfileService·재가입 정책 D-22 가드 등) → Track 8+ 이연
- Controller·DTO·OpenAPI
- D-22 재가입 가드 로직 (Application Service 진입 시점)
- 비식별화 배치 로직 (withdrawn_user.legal_retention_until 만료 후 anonymized_at 마킹)
- BuyerProfile.grade_locked_until 정책 로직
- GradeSource 4층위 Layer 3 (DTO @ValidEnum)·Layer 4 (프론트) → Track 8+
- E2E·통합 테스트
- WithdrawnSeller·SellerBankAccount → PR-3b 이연
- 외부 검토 (A급 선택적·정찰 결과 보고 후 사용자 판단)

---

## §8. 후속 권고 — 결정 요청 항목

| # | 항목 | 권고안 | 권고 이유 |
|---|---|---|---|
| Q1 | BuyerProfile 공유 PK 매핑: @MapsId + @OneToOne vs @Id Long userId | **@MapsId + @OneToOne 권고** | JPA 공유 PK 표준·User 직접 접근·실수 차단·SellerSalesDaily(@IdClass 복합 PK)와 다른 이유 명확 |
| Q2 | WithdrawnUser.original_user_id 매핑: @ManyToOne LAZY User vs Long userId | **@ManyToOne LAZY 권고** | 내부 Aggregate 참조·D-01 무위반·SellerUser.seller 패턴 정합 |
| Q3 | UserAddress.user_id 매핑: @ManyToOne LAZY User vs Long userId | **@ManyToOne LAZY 권고** | 내부 Aggregate 참조·D-01 무위반 |
| Q4 | Test Base | **Batch1DataJpaTestBase 재사용 권고** | D-83 Q2 정합·신설 비용 0 |
| Q5 | User.email·name·phone NULL 정책 명시 여부 | **DDL nullable 유지·D-22 정합** | 비식별화 정책 명문화 완료·Service 가드는 Track 8+ |

**채택 시 구현 라운드 진입 가능 여부**: Q1~Q5 전건 채택 시 즉시 진입 가능. FAIL 0·WARN 0.

**외부 검토 권고 여부**: A급 선택적·정찰 결과만으로 결정 요청 항목이 명확하므로 선택적. 사용자 판단에 따라 진행.
