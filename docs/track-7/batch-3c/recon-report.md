# Track 7 Batch-3c 정찰 보고서 — Product잔여·공통보조 6 Entity (read-only)

> 작성일: 2026-06-28
> 브랜치: docs/track-7-batch-3c-recon (정찰 전용·코드 변경 0)
> 범위: product_image · product_option_group · product_option_value · attachment · audit_log · notification_log
> 근거: D-81 §1 Batch-3 PR-3c (Track 7 최종 PR·A급·외부 검토 선택적)

---

## §1. 6 테이블 V1 DDL 1:1 추출

### 1.1 product_image (V1 #21)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| product_id | BIGINT | NOT NULL | FK→product(N:1) |
| image_url | VARCHAR(2048) | NOT NULL | 이미지 URL |
| display_order | INT | NOT NULL | 정렬 순서 |
| is_main | TINYINT(1) | NOT NULL | 대표 이미지 여부 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |
| deleted_at | DATETIME(6) | NULL | soft-delete 마킹 |
| deleted_by | BIGINT | NULL | |
| delete_reason | VARCHAR(255) | NULL | |

- PK: id
- UK: 없음
- FK: fk_product_image_product → product(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: ix_product_image_deleted_at (deleted_at)
- soft-delete 컬럼: Y (deleted_at·deleted_by·delete_reason)
- public_id 컬럼: N
- COMMENT: `상품 이미지(PRD 종속·SOFT)`

### 1.2 product_option_group (V1 #22)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| product_id | BIGINT | NOT NULL | FK→product(N:1)·상품당 최대 3(PRD-4) |
| name | VARCHAR(50) | NOT NULL | 옵션 그룹명(예: 색상) |
| display_order | INT | NOT NULL | 정렬 순서 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: 없음
- FK: fk_product_option_group_product → product(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: InnoDB 자동 (product_id FK)
- soft-delete 컬럼: **N** (deleted_at 없음·DDL 실측)
- public_id 컬럼: N
- COMMENT: `상품 옵션 그룹(PRD 종속·SOFT 상속)` — §7 WARN-1 참조

### 1.3 product_option_value (V1 #23)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| option_group_id | BIGINT | NOT NULL | FK→product_option_group(N:1) |
| value | VARCHAR(100) | NOT NULL | 옵션값(예: 빨강) |
| display_order | INT | NOT NULL | 정렬 순서 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: 없음
- FK: fk_product_option_value_group → product_option_group(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: InnoDB 자동 (option_group_id FK)
- soft-delete 컬럼: N (deleted_at 없음·DDL 실측)
- public_id 컬럼: N
- COMMENT: `상품 옵션값(PRD 종속·SOFT 상속)`

### 1.4 attachment (V1 #35)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| public_id | CHAR(30) | NOT NULL | ULID+prefix att_·ATT-2 |
| target_type | VARCHAR(50) | NOT NULL | polymorphic 대상 유형·D분류(ATT-1·앱 검증) |
| target_id | BIGINT | NOT NULL | polymorphic 대상 id 논리참조 |
| file_name | VARCHAR(200) | NOT NULL | 원본 파일명 |
| file_path | VARCHAR(2048) | NOT NULL | 저장 경로/URL |
| mime_type | VARCHAR(100) | NULL | MIME 타입 |
| file_size | BIGINT | NULL | 파일 크기(byte) |
| display_order | INT | NOT NULL | 정렬 순서 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |
| deleted_at | DATETIME(6) | NULL | soft-delete 마킹 |
| deleted_by | BIGINT | NULL | |
| delete_reason | VARCHAR(255) | NULL | |

- PK: id
- UK: uk_attachment_public_id (public_id)
- FK: **없음** (polymorphic 논리참조·FK 없음·D-01)
- INDEX: ix_attachment_target (target_type, target_id)·ix_attachment_deleted_at (deleted_at)
- soft-delete 컬럼: Y (deleted_at·deleted_by·delete_reason)
- public_id 컬럼: Y (prefix att_·CHAR(30))
- COMMENT: `첨부파일(ATT Aggregate Root·SOFT·public_id att_)`

### 1.5 audit_log (V1 #36)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| public_id | CHAR(30) | NOT NULL | ULID+prefix aud_·AUD-4 |
| actor_user_id | BIGINT | NULL | 행위자 User.id 논리참조(FK 미적용) |
| actor_role | VARCHAR(50) | NULL | 행위자 역할 |
| action | ENUM('CREATE','UPDATE','DELETE','APPROVE','REJECT','LOGIN','LOGOUT') | NOT NULL | 행위·A#18·A분류 잠금 |
| target_type | VARCHAR(50) | NOT NULL | polymorphic 대상 유형·D분류(앱 검증) |
| target_id | BIGINT | NOT NULL | polymorphic 대상 id 논리참조 |
| diff_json | LONGTEXT | NULL | 변경 필드 JSON·민감정보 마스킹·AUD-3·D-11 |
| ip_address | VARCHAR(45) | NULL | 요청 IP(IPv6 대응) |
| user_agent | VARCHAR(500) | NULL | User-Agent |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |

- PK: id
- UK: uk_audit_log_public_id (public_id)
- FK: **없음** (polymorphic 논리참조·actor_user_id FK 미적용)
- INDEX: ix_audit_log_target (target_type, target_id, created_at)·ix_audit_log_actor (actor_user_id, created_at)
- CHECK: chk_audit_log_diff_json CHECK (diff_json IS NULL OR JSON_VALID(diff_json))
- soft-delete 컬럼: N (deleted_at 없음·ARCHIVE)
- public_id 컬럼: Y (prefix aud_·CHAR(30))
- 감사 컬럼: **created_at·created_by 2컬럼만** (updated_at·updated_by 없음·append-only)
- COMMENT: `감사 로그(AUD Aggregate Root·ARCHIVE·append-only·public_id aud_)`

### 1.6 notification_log (V1 #37)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| recipient_user_id | BIGINT | NULL | 수신자 User.id 논리참조(FK 미적용) |
| channel | ENUM('EMAIL','SMS','PUSH','IN_APP') | NOT NULL | 발송 채널·A#16·A분류 잠금 |
| template_code | VARCHAR(100) | NOT NULL | 템플릿 코드 |
| target_type | VARCHAR(50) | NOT NULL | polymorphic 대상 유형·D분류(NOT-3) |
| target_id | BIGINT | NOT NULL | polymorphic 대상 id 논리참조 |
| title | VARCHAR(200) | NULL | 알림 제목 |
| content | TEXT | NULL | 알림 본문 |
| status | ENUM('PENDING','SENT','FAILED') | NOT NULL | 발송 상태·A#17·A분류 잠금 |
| sent_at | DATETIME(6) | NULL | 발송 시각 |
| failed_reason | TEXT | NULL | 실패 사유 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |

- PK: id
- UK: 없음
- FK: **없음** (polymorphic 논리참조·recipient_user_id FK 미적용)
- INDEX: ix_notification_log_target (target_type, target_id)
- soft-delete 컬럼: N (deleted_at 없음·ARCHIVE)
- public_id 컬럼: **N** (DDL 실측)
- 감사 컬럼: **created_at·created_by 2컬럼만** (updated_at·updated_by 없음·append-only)
- COMMENT: `알림 로그(Infra/Event·ARCHIVE·append-only)`

---

## §2. Aggregate 경계 정합 검증

### 2.1 Product Aggregate (§2.3 정합)

| Entity | DDL | aggregate-boundary §2.3 포함 여부 | 판정 |
|---|---|---|---|
| product_image | Product 종속·FK→product | `Product | ProductImage, ProductOptionGroup, ProductOptionValue, ProductVariant` | PASS |
| product_option_group | Product 종속·FK→product | 동일 | PASS |
| product_option_value | ProductOptionGroup 종속·FK→product_option_group | 동일 | PASS |

- D-01 정합: 3 Entity 모두 Product Aggregate 내부 → @ManyToOne LAZY 허용 (외부 ID 참조 의무 없음)
- D-12 정합: product_option_group·value는 DDL 물리 컬럼 없이 "SOFT 상속" → Product Root soft-delete cascade 정책 자동 적용

### 2.2 Attachment Aggregate (§2.6 정합)

| Entity | DDL | 판정 |
|---|---|---|
| attachment | 독립 Aggregate·polymorphic FK 없음 | PASS |

- public_id att_ ✓·FK 없음 ✓·soft-delete 컬럼 ✓

### 2.3 AuditLog Aggregate (§2.6 정합)

| Entity | DDL | 판정 |
|---|---|---|
| audit_log | 독립 Aggregate·append-only·FK 없음 | PASS |

- public_id aud_ ✓·append-only(created_at/by 2컬럼) ✓·D-11 diff_json JSON ✓

### 2.4 NotificationLog Infra/Event Processing (§2.7·D-18 정합)

| Entity | DDL | 판정 |
|---|---|---|
| notification_log | Infra/Event Processing·append-only·FK 없음 | PASS |

- Aggregate 아님 ✓·D-18 확정 ✓·public_id 없음 DDL 실측 ✓

---

## §3. Common Abstract 매칭 후보

기존 abstract 8종 확인 (공통 패키지):

| 파일 | 역할 |
|---|---|
| AbstractCreatedOnlyEntity | created_at·created_by 2컬럼·append-only |
| AbstractFullAuditableEntity | 4컬럼(created·updated 각 at/by) |
| AbstractSoftDeletableEntity | full audit + deleted_at·by·reason |
| AbstractPublicIdSoftDeletableEntity | soft-delete + public_id CHAR(30) |
| AbstractPublicIdFullAuditableEntity | full audit + public_id CHAR(30) |
| AbstractMappingEntity | 매핑 테이블용 |
| AbstractSeedEntity | Seed 데이터용 |
| AbstractAggregateEntity | 별도 용도 |

### 3.1 Entity별 abstract 매칭 결과

| Entity | soft-delete | public_id | 감사컬럼 | abstract 후보 | 신규 abstract 필요 |
|---|---|---|---|---|---|
| product_image | Y | N | full(4) | **AbstractSoftDeletableEntity** | 불필요 |
| product_option_group | N | N | full(4) | **AbstractFullAuditableEntity** | 불필요 |
| product_option_value | N | N | full(4) | **AbstractFullAuditableEntity** | 불필요 |
| attachment | Y | Y (att_) | full(4) | **AbstractPublicIdSoftDeletableEntity** | 불필요 |
| audit_log | N | Y (aud_) | created(2) | **AbstractCreatedOnlyEntity + publicId 자체 정의 + @PrePersist 자체 정의** | 불필요 |
| notification_log | N | N | created(2) | **AbstractCreatedOnlyEntity** | 불필요 |

### 3.2 audit_log abstract 근거

AbstractPublicIdFullAuditableEntity.java Javadoc에 이미 명시:
> "audit_log는 append-only 특성상 본 추상 클래스 미상속·AbstractCreatedOnlyEntity 상속 + publicId 필드 자체 정의 + @PrePersist 자체 정의 패턴 적용(Track 7)"

`AbstractPublicIdFullAuditableEntity`는 updated_at/by를 포함하는 full audit 기반이라 audit_log 컬럼과 불일치. 지침대로 자체 정의 패턴 적용.

```java
// AuditLog — AbstractCreatedOnlyEntity 상속 + public_id 자체 정의 패턴
@Entity
@Table(name = "audit_log")
public class AuditLog extends AbstractCreatedOnlyEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include private Long id;

    @EqualsAndHashCode.Include
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "public_id", length = 30, nullable = false, updatable = false)
    private String publicId;

    @PrePersist
    private void generatePublicId() {
        if (publicId == null) {
            publicId = PublicIdGenerator.generate("aud");
        }
    }
    // ... 나머지 필드
}
```

### 3.3 notification_log abstract 근거

AbstractCreatedOnlyEntity Javadoc에 이미 명시:
> "적용 대상: audit append-only 3 — InventoryHistory·NotificationLog·AuditLog(audit-policy.md §8)"

public_id 없음 (DDL 실측) + created_at/by 2컬럼 → AbstractCreatedOnlyEntity 직접 상속.

---

## §4. 외부 Aggregate 참조 D-01 처치 후보

| Entity | 컬럼 | 대상 Aggregate | 내부/외부 | D-01 처치 후보 |
|---|---|---|---|---|
| product_image | product_id | Product (내부 §2.3) | **내부** | @ManyToOne(fetch=LAZY) Product |
| product_option_group | product_id | Product (내부 §2.3) | **내부** | @ManyToOne(fetch=LAZY) Product |
| product_option_value | option_group_id | ProductOptionGroup (내부 §2.3) | **내부** | @ManyToOne(fetch=LAZY) ProductOptionGroup |
| attachment | target_type / target_id | polymorphic | **외부** | FK 없음·String targetType + Long targetId |
| audit_log | actor_user_id | User | **외부** | Long actorUserId (D-01 ID only) |
| audit_log | target_type / target_id | polymorphic | **외부** | FK 없음·String targetType + Long targetId |
| notification_log | recipient_user_id | User | **외부** | Long recipientUserId (D-01 ID only) |
| notification_log | target_type / target_id | polymorphic | **외부** | FK 없음·String targetType + Long targetId |

**내부 참조 근거**: product_image·option_group·option_value는 Product Aggregate 종속(aggregate-boundary §2.3) → D-85 Q2 패턴 정합(@ManyToOne LAZY). D-01 ID only 의무는 Aggregate 간 외부 참조에만 적용.

**polymorphic target_type**: DDL VARCHAR(50)·D분류(앱 검증·FK 없음). DB ENUM 아님 → Java Enum 신설 + DTO @ValidEnum 처치 필요 (§8 Q4).

---

## §5. LT-03 처치 의무 점검

### 5.1 Batch-3c 신규 Entity LT-03 처치 대상

| Entity | 상속 abstract | deleted_at 여부 | LT-03 처치 |
|---|---|---|---|
| ProductImage | AbstractSoftDeletableEntity | Y | **구현 PR에서 @SQLRestriction 직접 선언 의무** |
| Attachment | AbstractPublicIdSoftDeletableEntity | Y | **구현 PR에서 @SQLRestriction 직접 선언 의무** |
| ProductOptionGroup | AbstractFullAuditableEntity | N | 불필요 |
| ProductOptionValue | AbstractFullAuditableEntity | N | 불필요 |
| AuditLog | AbstractCreatedOnlyEntity | N | 불필요 |
| NotificationLog | AbstractCreatedOnlyEntity | N | 불필요 |

### 5.2 기존 Entity LT-03 현황 (코드 직접 확인)

| Entity | 코드 확인 결과 | @SQLRestriction 직접 선언 | 처치 필요 |
|---|---|---|---|
| Category | Track 7 Batch-1 처치 완료 | ✓ 선언됨 | 없음 |
| UserAddress | Track 7 Batch-3a 처치 완료 | ✓ 선언됨 | 없음 |
| User | Track 7 Batch-3a 처치 완료 | ✓ 선언됨 | 없음 |
| Seller | Track 7 Batch-3b 처치 완료 | ✓ 선언됨 | 없음 |
| **Product** | **LT-03 미처치** | **미선언** | **Batch-3c 구현 PR에서 처치** |
| **ProductVariant** | **LT-03 미처치** | **미선언** | **Batch-3c 구현 PR에서 처치** |

**Product.java 확인**: `@SQLRestriction` 미선언. Javadoc에 "베이스 @SQLRestriction으로 자동 제외"라고 오기됨 — 실제로는 HHH-17453 버그로 @MappedSuperclass의 @SQLRestriction이 @Entity로 전파되지 않아 **soft-delete 행이 조회에 노출됨 (트랩 상태)**.

**ProductVariant.java 확인**: 동일. Javadoc 오기 포함. Track 4 read-only 신설 당시 LT-03 발견 전이었음.

### 5.3 Batch-3c 구현 PR LT-03 처치 대상 (4건)

1. **Product.java** — `@SQLRestriction("deleted_at IS NULL")` 직접 선언 + Javadoc 오기 보정
2. **ProductVariant.java** — 동일
3. **ProductImage.java** (신규) — 신설 시 직접 선언
4. **Attachment.java** (신규) — 신설 시 직접 선언

---

## §6. live-traps.md 갱신 의무

**본 정찰 PR(read-only)에서는 live-traps.md 수정 0건. 구현 PR에서 동반 갱신.**

### 6.1 LT-03 표 갱신 필요 항목

| 현재 표기 | 갱신 필요 내용 | 시점 |
|---|---|---|
| `Seller \| Batch-3b 진입 시` | → `Seller \| ✓ 완료 (Track 7 Batch-3b)` | 구현 PR (WARN-2) |
| `ProductImage \| Batch-3c 진입 시` | → `ProductImage \| ✓ 완료 (Track 7 Batch-3c)` | 구현 PR |
| `Product \| Batch-3c 진입 시` | → `Product \| ✓ 완료 (Track 7 Batch-3c)` | 구현 PR |
| `ProductVariant \| Batch-3c 진입 시` | → `ProductVariant \| ✓ 완료 (Track 7 Batch-3c)` | 구현 PR |
| `Attachment \| Batch-3c 진입 시` | → `Attachment \| ✓ 완료 (Track 7 Batch-3c)` | 구현 PR |

---

## §7. WARN·OUT-OF-SCOPE

### WARN

**WARN-1**: product_option_group·option_value DDL COMMENT "SOFT 상속" 표기 해석 명확화

- DDL COMMENT: `COMMENT='상품 옵션 그룹(PRD 종속·SOFT 상속)'`
- DDL 실측: deleted_at 컬럼 없음
- 해석: "SOFT 상속"은 Product Root의 soft-delete 정책을 상속받는다는 의미 (D-12 "종속 엔티티 자동 상속" 정합)
- 물리 컬럼 없음 → AbstractFullAuditableEntity 적용 (deleted_at 불필요)
- 판정: **정합 (DDL COMMENT 오류 아님·해석 명확화만 필요)**

**WARN-2**: live-traps.md LT-03 표 — Seller 항목 미갱신

- 현황: `| Seller | AbstractPublicIdSoftDeletableEntity | Batch-3b 진입 시 |`
- Batch-3b에서 Seller.java LT-03 처치 완료됐으나 live-traps.md 표 갱신 누락
- 처치: 구현 PR에서 `✓ 완료 (Track 7 Batch-3b)` 갱신 동반

**WARN-3**: Product.java·ProductVariant.java Javadoc 오기

- 현황: "베이스 @SQLRestriction("deleted_at IS NULL")로 soft-delete 행은 조회에서 자동 제외된다"
- 실제: LT-03 미처치 상태 — @SQLRestriction이 @Entity에 전파 안됨 (HHH-17453)
- 처치: LT-03 처치(@SQLRestriction 직접 선언) 후 Javadoc 오기 보정 동반

### OUT-OF-SCOPE

| 항목 | 이연 근거 |
|---|---|
| DTO @ValidEnum target_type 검증 | Track 8+ Application Service 트랙 이연 |
| 프론트 constants PolymorphicTargetType | Track 8+ 이연 |
| AuditLog 적재 훅 (Service 레이어) | Track 8+ 이연 (D-11 구현은 Service 책임) |
| diff_json 민감정보 마스킹 로직 | Track 8+ 이연 |
| audit_log·notification_log 보존 기간·파티셔닝 | 운영 이연 |
| notification_log.status PENDING→SENT 전이 로직 | Track 8+ 이연 |

---

## §8. 결정 요청 (구현 진입 전 확정 필요)

### Q1. product_image·product_option_group·product_option_value의 Product/ProductOptionGroup 참조 방식

| 옵션 | 설명 | 근거 |
|---|---|---|
| **A (추천)** | @ManyToOne(fetch=LAZY) Product / ProductOptionGroup | D-85 Q2 패턴 정합·내부 Aggregate·Product 필드 직접 접근 가능 |
| B | Long productId / Long optionGroupId (ID only) | 기존 ProductVariant.java 패턴·단순·Track 4 read-only 일관성 |

**추천 A**: product_image·option_group·option_value는 Product Aggregate **내부** 엔티티 (aggregate-boundary §2.3). D-01 ID only 의무는 Aggregate **간** 외부 참조에만 적용. D-85 Q2 (내부 → @ManyToOne LAZY) 정합. ProductVariant.java가 Long productId를 쓰는 이유는 Track 4 read-only 최소 신설이었기 때문(D-59)이며, Batch-3c 신규 Entity에 동일 제약 없음.

---

### Q2. audit_log abstract 매칭 방식

| 옵션 | 설명 | 근거 |
|---|---|---|
| **A (추천)** | AbstractCreatedOnlyEntity 상속 + publicId 필드 자체 정의 + @PrePersist 자체 정의 | AbstractPublicIdFullAuditableEntity Javadoc 기명시·단일 사용 추상화 회피(CLAUDE.md) |
| B | AbstractPublicIdCreatedOnlyEntity 신규 abstract 신설 | 타 Entity 미사용·단일 사용 추상화 위반 |

**추천 A**: AbstractPublicIdFullAuditableEntity.java Javadoc에 이미 "Track 7 진입 시 본 클래스를 상속하고 publicId 필드·@PrePersist를 자체 정의한다(public_id+append-only 전용 base 미신설·단일 사용 추상화 회피)"로 명시. 신규 abstract 불필요.

---

### Q3. notification_log abstract 매칭 방식

| 옵션 | 설명 | 근거 |
|---|---|---|
| **A (추천)** | AbstractCreatedOnlyEntity 직접 상속 | Javadoc 기명시 적용 대상·public_id 없음 DDL 실측 |
| B | AbstractCreatedOnlyEntity 상속 + publicId 자체 정의 | DDL에 public_id 없음·B안 적용 근거 없음 |

**추천 A**: public_id 없음 DDL 실측. AbstractCreatedOnlyEntity Javadoc "적용 대상: InventoryHistory·NotificationLog·AuditLog" 명시. 직접 상속.

---

### Q4. polymorphic target_type 처치 방식 (attachment·audit_log·notification_log 공유)

| 옵션 | 설명 | 근거 |
|---|---|---|
| **A (추천)** | 공유 Java Enum 신설 (`com.zslab.mall.common.enums.PolymorphicTargetType`) | 3 Entity 동일 target_type 참조·단일 소스·D-01 정합 |
| B | 도메인별 Enum 분리 (AttachmentTargetType·AuditTargetType·NotificationTargetType) | 도메인 격리·서로 다른 target 범위 지원 가능 |

**추천 A**: target_type 값 집합은 Aggregate 목록(D-01) 기반 공유 참조. 3 Entity가 동일한 polymorphic 대상 유형을 참조. 공통 Enum으로 단일 소스 유지. DDL은 VARCHAR(50)·D분류(앱 검증)이므로 DB ENUM 아님 → Java Enum + DTO @ValidEnum (Track 8+) 처치.

**패키지**: `com.zslab.mall.common.enums.PolymorphicTargetType`
**값 후보** (D-01 Aggregate 16 + Infra/Event 1 기반): ORDER, ORDER_ITEM, PAYMENT, DELIVERY, CLAIM, REFUND, USER, SELLER, PRODUCT, PRODUCT_VARIANT, CART_ITEM, SETTLEMENT, ATTACHMENT, AUDIT_LOG (자기 참조 제외 실용)

---

### Q5. audit_log.diff_json 컬럼 Java 매핑 방식

| 옵션 | 설명 | 근거 |
|---|---|---|
| **A (추천)** | `String diffJson` (LONGTEXT 매핑) | append-only·쓰기는 Service에서 JSON 직렬화·복잡성 최소화·AuditLog 구조화 쿼리보다 이력 보존 우선 |
| B | `@JdbcTypeCode(SqlTypes.JSON) String diffJson` | Hibernate JSON 타입·MariaDB JSON_VALID CHECK 정합·JSON 함수 질의 가능 |

**추천 A**: D-11 "MariaDB JSON = LONGTEXT alias + CHECK(JSON_VALID)" — DB 레벨 JSON 검증은 CHECK 제약으로 이미 보장. Java에서 @JdbcTypeCode(SqlTypes.JSON)은 Hibernate가 JSON 컬럼으로 처리해 MariaDB에서 추가 타입 불일치 가능성. String으로 LONGTEXT 매핑 시 직접 JSON 문자열 관리(Jackson 직렬화·Service 책임). AuditLog는 Entity 레이어에서 JSON 구조 분석 불필요. 단순 String 유지.

---

### Q6. Test Base 재사용 여부

| 옵션 | 설명 | 근거 |
|---|---|---|
| **A (추천)** | Batch1DataJpaTestBase 재사용 | D-83 Q2·D-84 Q4·D-85 Q5 연속 정합·신설 비용 0 |
| B | 신규 TestBase 신설 | 필요성 없음 |

**추천 A**: D-83 Q2부터 3 Batch 연속 재사용 확인. Batch-3c 신규 Entity도 동일 패턴. 단, 시딩 복잡도:
- ProductImage: Product 선행 필요 → FK_CHECKS=0 또는 nativeQuery INSERT (LT-02 try-finally)
- ProductOptionGroup: Product 선행 필요 → 동일
- ProductOptionValue: ProductOptionGroup 선행 필요 → 동일
- Attachment·AuditLog·NotificationLog: FK 없음·독립 → 직접 save 가능 (시딩 불필요)

---

## §9. Enum 신설 목록 (4층위 잠금 의무)

| Enum | 소속 Entity | DDL ENUM 값 | Java 패키지 |
|---|---|---|---|
| AuditLogAction | audit_log.action | CREATE·UPDATE·DELETE·APPROVE·REJECT·LOGIN·LOGOUT | `com.zslab.mall.audit.enums` |
| NotificationChannel | notification_log.channel | EMAIL·SMS·PUSH·IN_APP | `com.zslab.mall.notification.enums` |
| NotificationLogStatus | notification_log.status | PENDING·SENT·FAILED | `com.zslab.mall.notification.enums` |

- DB 레이어: DDL ENUM ✓ (기존 V1)
- Java 레이어: 신규 (Batch-3c 구현 시 신설)
- DTO @ValidEnum: Track 8+ 이연
- 프론트: Track 8+ 이연

---

## §10. 패키지 구조 (예상)

```
com.zslab.mall
├── product
│   ├── entity
│   │   ├── ProductImage.java        (신규·AbstractSoftDeletableEntity)
│   │   ├── ProductOptionGroup.java  (신규·AbstractFullAuditableEntity)
│   │   └── ProductOptionValue.java  (신규·AbstractFullAuditableEntity)
│   └── repository
│       ├── ProductImageRepository.java
│       ├── ProductOptionGroupRepository.java
│       └── ProductOptionValueRepository.java
├── attachment
│   ├── entity
│   │   └── Attachment.java          (신규·AbstractPublicIdSoftDeletableEntity)
│   └── repository
│       └── AttachmentRepository.java
├── audit
│   ├── entity
│   │   └── AuditLog.java            (신규·AbstractCreatedOnlyEntity + publicId 자체정의)
│   ├── enums
│   │   └── AuditLogAction.java      (신규 Enum)
│   └── repository
│       └── AuditLogRepository.java
├── notification
│   ├── entity
│   │   └── NotificationLog.java     (신규·AbstractCreatedOnlyEntity)
│   ├── enums
│   │   ├── NotificationChannel.java (신규 Enum)
│   │   └── NotificationLogStatus.java (신규 Enum)
│   └── repository
│       └── NotificationLogRepository.java
└── common
    └── enums
        └── PolymorphicTargetType.java (신규 Enum·Q4 A안 채택 시)
```

**기존 수정 파일**:
- `product/entity/Product.java` — @SQLRestriction 직접 선언 + Javadoc 보정 (LT-03)
- `product/entity/ProductVariant.java` — 동일 (LT-03)
- `docs/troubleshooting/live-traps.md` — LT-03 표 갱신 (WARN-2·Batch-3c 처치 완료 마킹)

---

## §11. 진입 가능 여부 (결정 라운드 확정 후)

| 항목 | 판정 | 비고 |
|---|---|---|
| DDL 실측 완료 | ✓ | 6 테이블 전수 추출 |
| Aggregate 경계 정합 | ✓ PASS | §2.1~§2.4 전부 aggregate-boundary 정합 |
| Abstract 매칭 | ✓ PASS | 신규 abstract 불필요·기존 8종으로 커버 |
| D-01 처치 방향 | Q1 결정 대기 | 내부 @ManyToOne vs Long 선택 |
| LT-03 처치 대상 확정 | ✓ | Product·ProductVariant·ProductImage·Attachment 4건 |
| LT-02 시딩 패턴 | ✓ 기준 수립 | ProductImage·OptionGroup·OptionValue: FK_CHECKS=0 try-finally |
| Enum 신설 목록 | ✓ | AuditLogAction·NotificationChannel·NotificationLogStatus 3건 |
| Q4 PolymorphicTargetType | Q4 결정 대기 | 공유 vs 도메인별 |
| Q5 diff_json 매핑 | Q5 결정 대기 | String vs @JdbcTypeCode(JSON) |
| WARN 처치 | WARN-1 정합·WARN-2·WARN-3 구현 PR 동반 | 진입 차단 없음 |

**결론**: Q1·Q4·Q5 확정 후 즉시 구현 진입 가능. WARN-1은 DDL COMMENT 해석 명확화로 진입 차단 없음.
