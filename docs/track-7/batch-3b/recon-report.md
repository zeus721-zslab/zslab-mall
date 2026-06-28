# Track 7 Batch-3b 정찰 보고서 — Seller·Settlement·CartItem·Delivery 5 Entity (read-only)

> 작성일: 2026-06-28
> 브랜치: docs/track-7-batch-3b-recon (정찰 전용·코드 변경 0)
> 범위: seller_bank_account · settlement · withdrawn_seller · cart_item · delivery
> 근거: D-81 §1 Batch-3 PR-3b (Seller·Settlement·CartItem·Delivery 5·A급·외부 검토 선택적)

---

## §1. 5 테이블 V1/V2 DDL 1:1 추출

### 1.1 seller_bank_account (V1 #16)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| seller_id | BIGINT | NOT NULL | FK→seller(N:1) |
| bank_code | VARCHAR(20) | NOT NULL | 은행 코드 |
| account_number | VARCHAR(255) | NOT NULL | 계좌번호·AES 암호화(SLR-2) |
| account_holder | VARCHAR(50) | NOT NULL | 예금주 |
| is_primary | TINYINT(1) | NOT NULL | 주 정산계좌 여부(SLR-3) |
| verified_at | DATETIME(6) | NULL | 계좌 인증 시각 |
| status | ENUM('PENDING','VERIFIED','REJECTED') | NOT NULL | 인증 상태·A#4 잠금 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: 없음
- FK: fk_seller_bank_account_seller → seller(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: InnoDB 자동 (seller_id FK)
- soft-delete 컬럼: N (deleted_at 없음·ARCHIVE 분류)
- public_id 컬럼: N
- COMMENT: `판매자 정산계좌(Seller 종속·ARCHIVE)`

### 1.2 settlement (V1 #17)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| seller_id | BIGINT | NOT NULL | FK→seller(N:1) |
| bank_account_id | BIGINT | NOT NULL | FK→seller_bank_account·스냅샷(STL-3) |
| period_start | DATETIME(6) | NOT NULL | 정산 기간 시작 |
| period_end | DATETIME(6) | NOT NULL | 정산 기간 종료 |
| gross_amount | BIGINT | NOT NULL | 총 매출 |
| fee_amount | BIGINT | NOT NULL | 수수료 |
| refund_amount | BIGINT | NOT NULL | 환불액 |
| net_amount | BIGINT | NOT NULL | 정산액=gross-fee-refund(STL-1) |
| status | ENUM('PENDING','CONFIRMED','PAID') | NOT NULL | 정산 상태·A#5(STL-2) |
| paid_at | DATETIME(6) | NULL | 지급 완료 시각 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: 없음
- FK: fk_settlement_seller → seller(id) ON DELETE RESTRICT ON UPDATE CASCADE
- FK: fk_settlement_bank_account → seller_bank_account(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: ix_settlement_seller_status (seller_id, status)·ix_settlement_period (period_start, period_end)
- soft-delete 컬럼: N (deleted_at 없음·ARCHIVE 분류)
- public_id 컬럼: **N** (DDL 실측 — §6.1 WARN-1 참조)
- COMMENT: `정산(STL Aggregate Root·ARCHIVE)`

### 1.3 withdrawn_seller (V2 신설)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| original_seller_id | BIGINT | NOT NULL | 종료 전 Seller.id·FK(N:1) |
| terminate_reason | VARCHAR(255) | NULL | 종료 사유(탈퇴·강제 종료·승인 거부) |
| legal_retention_until | DATETIME(6) | NULL | 법정 보관 만료 시각 |
| anonymized_at | DATETIME(6) | NULL | 비식별화 완료 시각 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: 없음
- FK: fk_withdrawn_seller_seller → seller(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: InnoDB 자동 (original_seller_id FK)
- soft-delete 컬럼: N (deleted_at 없음·ARCHIVE 분류)
- public_id 컬럼: N
- COMMENT: `종료 판매자 아카이브(SLR 종속·ARCHIVE·D-23·SLR-6)`

### 1.4 cart_item (V1 #27)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| user_id | BIGINT | NOT NULL | FK→user(N:1) |
| variant_id | BIGINT | NOT NULL | FK→product_variant(N:1) |
| quantity | INT | NOT NULL | 수량(CRT-2 ≥1)·CHECK(quantity ≥ 1) |
| selected | TINYINT(1) | NOT NULL | 주문 선택 여부 |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: uk_cart_item_user_variant (user_id, variant_id)
- FK: fk_cart_item_user → user(id) ON DELETE RESTRICT ON UPDATE CASCADE
- FK: fk_cart_item_variant → product_variant(id) ON DELETE RESTRICT ON UPDATE CASCADE
- CHECK: chk_cart_item_quantity CHECK (quantity >= 1)
- INDEX: InnoDB 자동 (user_id·variant_id FK)
- soft-delete 컬럼: N (deleted_at 없음·HARD 분류)
- public_id 컬럼: N
- COMMENT: `장바구니(CRT Aggregate Root·HARD)`

### 1.5 delivery (V1 #32)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT | NOT NULL | PK |
| public_id | CHAR(30) | NOT NULL | ULID+prefix dlv_ |
| order_item_id | BIGINT | NOT NULL | FK→order_item(N:1·DLV-2) |
| carrier | ENUM('CJ','HANJIN','POST','LOGEN') | NOT NULL | 택배사·A#11 잠금 |
| tracking_no | VARCHAR(100) | NULL | 운송장번호·UK(DLV-1)·자연키 |
| status | ENUM('READY','SHIPPING','DELIVERED') | NOT NULL | 배송 상태·A#12 잠금 |
| shipped_at | DATETIME(6) | NULL | 발송 시각 |
| delivered_at | DATETIME(6) | NULL | 배송 완료 시각(DLV-3) |
| created_at | DATETIME(6) | NOT NULL | |
| created_by | BIGINT | NULL | |
| updated_at | DATETIME(6) | NOT NULL | |
| updated_by | BIGINT | NULL | |

- PK: id
- UK: uk_delivery_public_id (public_id)·uk_delivery_tracking_no (tracking_no)
- FK: fk_delivery_order_item → order_item(id) ON DELETE RESTRICT ON UPDATE CASCADE
- INDEX: InnoDB 자동 (order_item_id FK)
- soft-delete 컬럼: N (deleted_at 없음·ARCHIVE 분류)
- public_id 컬럼: Y (prefix dlv_)
- COMMENT: `배송(DLV Aggregate Root·ARCHIVE·public_id dlv_)`

---

## §2. Aggregate 경계 정합 검증

### 2.1 aggregate-boundary §2.2 (Seller·Settlement) ↔ V1/V2 DDL FK 방향 대조

| Entity | aggregate-boundary 귀속 | DDL FK 방향 | 결과 |
|---|---|---|---|
| seller_bank_account | Seller 종속 §2.2 (SellerBankAccount 포함) | FK→seller (내부 Root) | **PASS** |
| settlement | Settlement Aggregate Root §2.2 (단독) | FK→seller (외부)·FK→seller_bank_account (외부) | **PASS** |
| withdrawn_seller | Seller 종속 D-23 SLR-7 (aggregate-boundary §2.2 미명시) | FK→seller (내부·D-23 패턴) | **WARN-2** (§6.5) |

### 2.2 aggregate-boundary §2.4 (CartItem) ↔ V1 DDL FK 방향 대조

| Entity | aggregate-boundary 귀속 | DDL FK 방향 | 결과 |
|---|---|---|---|
| cart_item | CartItem Aggregate Root §2.4 (단독) | FK→user (외부)·FK→product_variant (외부) | **PASS** |

### 2.3 aggregate-boundary §2.5 (Delivery) ↔ V1 DDL FK 방향 대조

| Entity | aggregate-boundary 귀속 | DDL FK 방향 | 결과 |
|---|---|---|---|
| delivery | Delivery Aggregate Root §2.5 (단독) | FK→order_item (외부) | **PASS** |

### 2.4 D-01 처치 매트릭스 (7건)

| 참조 | 대상 Aggregate | 분류 | D-01 처치 | 비고 |
|---|---|---|---|---|
| seller_bank_account.seller_id → seller | Seller (내부 Root) | INTERNAL | @ManyToOne LAZY Seller 허용 | SellerUser.seller 패턴 동일 |
| settlement.seller_id → seller | Seller (외부 Aggregate) | EXTERNAL | Long sellerId 필드 (@ManyToOne 금지) | aggregate-boundary §2.2 외부 ID |
| settlement.bank_account_id → seller_bank_account | Seller Aggregate (외부) | EXTERNAL | Long bankAccountId 필드 [Q1] | STL-3 스냅샷·aggregate-boundary §2.2 외부 ID |
| withdrawn_seller.original_seller_id → seller | Seller (내부·D-23 패턴) | INTERNAL(검토) | @ManyToOne LAZY Seller 권고 [Q2] | WithdrawnUser 패턴·aggregate-boundary §2.2 비명시 |
| cart_item.user_id → user | User (외부 Aggregate) | EXTERNAL | Long userId 필드 (@ManyToOne 금지) | aggregate-boundary §2.4 외부 ID |
| cart_item.variant_id → product_variant | Product Aggregate (외부) | EXTERNAL | Long variantId 필드 (@ManyToOne 금지) | aggregate-boundary §2.4 외부 ID |
| delivery.order_item_id → order_item | Order Aggregate (외부) | EXTERNAL | Long orderItemId 필드 (@ManyToOne 금지) | aggregate-boundary §2.5 외부 ID |

**D-01 외부 위반 가능 케이스**: settlement.bank_account_id (Q1)·withdrawn_seller.original_seller_id (Q2) 결정 후 확정.

---

## §3. Common abstract 매칭

| Entity | 권고 abstract | soft_del | public_id | PK 타입 | 근거 |
|---|---|---|---|---|---|
| seller_bank_account | AbstractFullAuditableEntity | N | N | BIGINT AUTO | ARCHIVE·deleted_at 없음·Javadoc "대표 예시: SellerBankAccount" 명시 |
| settlement | AbstractFullAuditableEntity | N | N | BIGINT AUTO | ARCHIVE·deleted_at 없음·public_id 없음(DDL 실측·WARN-1)·Javadoc "대표 예시: Settlement" 명시 |
| withdrawn_seller | AbstractFullAuditableEntity | N | N | BIGINT AUTO | ARCHIVE·deleted_at 없음·WithdrawnUser 패턴 준용·D-23 Snapshot Metadata |
| cart_item | AbstractFullAuditableEntity | N | N | BIGINT AUTO | HARD·deleted_at 없음·full audit 4컬럼 |
| delivery | AbstractPublicIdFullAuditableEntity | N | Y (dlv_) | BIGINT AUTO | ARCHIVE·public_id CHAR(30) dlv_·Javadoc "적용 대상: Delivery 포함" 명시 |

**Abstract 클래스 계층 확인** (8종 중 Batch-3b 관련):
- AbstractFullAuditableEntity: created_at·created_by·updated_at·updated_by (4컬럼)
- AbstractPublicIdFullAuditableEntity extends AbstractFullAuditableEntity: + public_id @JdbcTypeCode(CHAR)·@PrePersist generatePublicId()
  - Javadoc 명시 "적용 대상: 6 — Order·OrderItem·Payment·**Delivery**·Claim·Refund"
  - Settlement 미포함 확인 (AbstractFullAuditableEntity Javadoc "대표 예시: Settlement·Inventory·SellerBankAccount" 정합)

---

## §4. LT-03 처치 의무 검증

### 4.1 신규 5 Entity LT-03 해당 여부

| Entity | deleted_at 컬럼 | @SQLRestriction 처치 의무 |
|---|---|---|
| seller_bank_account | 없음 | 없음 |
| settlement | 없음 | 없음 |
| withdrawn_seller | 없음 | 없음 |
| cart_item | 없음 | 없음 |
| delivery | 없음 | 없음 |

**신규 5 Entity LT-03 처치 의무 0건** — 전원 deleted_at 컬럼 부재.

### 4.2 기존 Seller.java LT-03 미처치 발견 (중요)

live-traps.md 표: `Seller | AbstractPublicIdSoftDeletableEntity | Batch-3b 진입 시` 처치 예정 명시.

기존 `Seller.java` (Track 4 신설·read-only 최소 구현) 실측:
```java
@Entity
@Table(name = "seller")
// @SQLRestriction 직접 선언 없음! ← LT-03 처치 미적용
public class Seller extends AbstractPublicIdSoftDeletableEntity { ... }
```

Javadoc 설명: "베이스 @SQLRestriction("deleted_at IS NULL")로 soft-delete 행은 조회에서 자동 제외된다" → **HHH-17453 버그로 실제 무효** (AbstractPublicIdSoftDeletableEntity → AbstractSoftDeletableEntity @MappedSuperclass 선언이 @Entity 전파 안 됨).

**처치 의무 1건**: 구현 라운드에서 기존 `Seller.java`에 `@SQLRestriction("deleted_at IS NULL")` 직접 선언 추가 필수 (기존 파일 수정).

Category.java·User.java·UserAddress.java 처치 패턴 동일:
```java
@Entity
@Table(name = "seller")
@SQLRestriction("deleted_at IS NULL")  // 직접 선언 의무 — HHH-17453 처치
public class Seller extends AbstractPublicIdSoftDeletableEntity { ... }
```

---

## §5. LT-01 처치 자동 적용 확인

| Entity | public_id | LT-01 처치 방법 | 별도 처치 |
|---|---|---|---|
| delivery | Y (dlv_) | AbstractPublicIdFullAuditableEntity 경유 @JdbcTypeCode(SqlTypes.CHAR) 자동 적용 | **없음** |
| seller_bank_account | N | 해당 없음 | — |
| settlement | N (DDL 실측) | 해당 없음 | — |
| withdrawn_seller | N | 해당 없음 | — |
| cart_item | N | 해당 없음 | — |

**신규 명시 LT-01 처치 0건** — delivery는 AbstractPublicIdFullAuditableEntity 상속으로 자동 적용.

---

## §6. 특수 케이스 검증

### 6.1 settlement.public_id 비존재 확인 (WARN-1)

**사용자 정찰 지시**: "settlement (V1 #17·Settlement Aggregate Root·ARCHIVE·**public_id stl_**)" 언급.

**DDL 실측 결과**: V1 settlement 테이블에 `public_id` 컬럼 **없음**.

근거:
- V1 DDL (#17): id·seller_id·bank_account_id·period_start·period_end·gross_amount·fee_amount·refund_amount·net_amount·status·paid_at·created_at·created_by·updated_at·updated_by (15컬럼)·public_id 없음
- AbstractPublicIdFullAuditableEntity Javadoc: "적용 대상: 6 — Order·OrderItem·Payment·Delivery·Claim·Refund" — Settlement 미포함
- AbstractFullAuditableEntity Javadoc: "대표 예시: Settlement·Inventory·SellerBankAccount" — AbstractFullAuditableEntity 적용 확인

**처치**: settlement → AbstractFullAuditableEntity (public_id 없음·ARCHIVE 분류 정합).  
`getPublicIdPrefix()` 구현 불필요.

### 6.2 cart_item UK(user_id, variant_id) 보장 방식

V1 DDL: `UNIQUE KEY uk_cart_item_user_variant (user_id, variant_id)` 명시.

user_id·variant_id는 모두 Long 필드 (외부 Aggregate D-01·@ManyToOne 금지). JPA @Table uniqueConstraints 선언 기술적으로 가능:
```java
@Table(name = "cart_item",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "variant_id"}))
```

**권고 [Q4]**: DDL UK 신뢰·@Table uniqueConstraints 선언 생략. DB UK가 제약 주체·JPA 레이어 중복 선언 불필요.

근거: Batch-1 UserRole UK(user_id, role_id)·Batch-2 SellerUser UK(seller_id, user_id) 동일 패턴 (DDL 신뢰·@Table uniqueConstraints 미선언).

### 6.3 delivery.tracking_no UK NULL 다건 허용 확인

V1 DDL: `UNIQUE KEY uk_delivery_tracking_no (tracking_no)` + `tracking_no VARCHAR(100) NULL`.

MariaDB UNIQUE KEY에서 NULL은 비교 제외 → tracking_no NULL 다건 삽입 허용 ✓ (발송 전 운송장 미등록 다건 가능).

Entity 매핑:
```java
@Column(name = "tracking_no", length = 100)
private String trackingNo;   // nullable·UNIQUE NULL 허용 동작은 MariaDB 표준
```

테스트 검증 필요: tracking_no=NULL 다건 삽입 성공 케이스 + NOT NULL 동일값 UK 위반 케이스.

### 6.4 settlement.bank_account_id 스냅샷 의미 (STL-3)

V1 DDL COMMENT: `FK→seller_bank_account·스냅샷(STL-3)`.

의미: 정산 생성 시점의 계좌 스냅샷 ID 보관. 이후 계좌 변경과 무관하게 정산 당시 계좌 추적 가능.

D-23 B-d7: "Settlement 처리 — 비식별화 대상 아님·seller_id 유지 확정". bank_account_id도 비식별화 대상 아님 (ID 보관·복호화 불가 암호화 키 폐기 무관).

**D-01 처치**: Settlement(독립 Aggregate) → SellerBankAccount(Seller Aggregate 내부)는 외부 참조 → `Long bankAccountId` 필드 권고 [Q1]. DB FK 유지 (DDL 제약)·JPA @ManyToOne 금지.

### 6.5 withdrawn_seller.original_seller_id 매핑 적정성 (WARN-2)

D-23 SLR-7: "Seller·WithdrawnSeller 동시 수정 금지 (Seller SoT)".
D-23 B-d1: "WithdrawnSeller 신설·WithdrawnUser 패턴 준용".

aggregate-boundary §2.2 Seller Aggregate 포함 엔티티: "SellerBankAccount, SellerUser" — WithdrawnSeller **미명시** (D-23 V2 신설 이전 작성).

해석: D-23 SLR-7 + WithdrawnUser 패턴 준용 → WithdrawnSeller는 Seller Aggregate 내부 엔티티로 취급 → @ManyToOne LAZY Seller 허용.

**권고 [Q2]**: @ManyToOne LAZY Seller (내부 Aggregate·D-23 SoT 정합·D-84 Q2 WithdrawnUser 패턴 준용).  
**aggregate-boundary §2.2 갱신 필요**: WithdrawnSeller를 Seller Aggregate 포함 엔티티로 추가 (구현 PR-3b 착수 전 또는 동반).

### 6.6 seller_bank_account.account_number AES 암호화 (현 단계 평문)

V1 DDL COMMENT: `계좌번호·AES 암호화(SLR-2)`. VARCHAR(255) NOT NULL.

D-23 B-d4: "account_number 비식별화 = 암호화 키 폐기 (NOT NULL 유지·복호화 불가)".

**Track 7 처치**: 평문 String 매핑. @Convert AES Converter는 Application Service 진입 전 (Track 8+) 구현.
```java
@Column(name = "account_number", nullable = false, length = 255)
private String accountNumber;  // Track 7 평문 String·AES @Converter는 Track 8+ 이연(SLR-2)
```

### 6.7 4층위 enum 잠금 의무 목록 (Batch-3b 신규 4건)

| 컬럼 | DDL ENUM | 번호 | Layer 1 | Layer 2 (신규 Java enum) | Layer 3·4 |
|---|---|---|---|---|---|
| seller_bank_account.status | PENDING·VERIFIED·REJECTED | A#4 | ✓ V1 | SellerBankAccountStatus 신설 필요 | Track 8+ 이연 |
| settlement.status | PENDING·CONFIRMED·PAID | A#5(STL-2) | ✓ V1 | SettlementStatus 신설 필요 | Track 8+ 이연 |
| delivery.carrier | CJ·HANJIN·POST·LOGEN | A#11 | ✓ V1 | DeliveryCarrier 신설 필요 | Track 8+ 이연 |
| delivery.status | READY·SHIPPING·DELIVERED | A#12 | ✓ V1 | DeliveryStatus 신설 필요 | Track 8+ 이연 |

패키지 귀속:
- com.zslab.mall.seller.enums: SellerBankAccountStatus
- com.zslab.mall.settlement.enums: SettlementStatus
- com.zslab.mall.delivery.enums: DeliveryCarrier, DeliveryStatus

---

## §7. Test Base 검토

**권고: Batch1DataJpaTestBase 재사용** (D-83 Q2·D-84 Q4 정합).

@DataJpaTest는 모든 @Entity 자동 스캔 → Batch-3b 5 신규 Entity·기존 Seller·OrderItem 등 모두 포함. 별도 설정 불필요.

**시딩 의존성 체인 (성공 케이스 복잡도)**:

| Entity | 시딩 선행 필요 |
|---|---|
| seller_bank_account | Seller (기존 SellerRepository 사용) |
| settlement | Seller + SellerBankAccount |
| withdrawn_seller | Seller |
| cart_item | user(UserRepository) + product_variant(미구현 → nativeQuery INSERT 또는 건너뜀) |
| delivery | order_item(OrderItemRepository) → Order(OrderRepository) → User(UserRepository) |

**cart_item·delivery 복잡도 주의**: cart_item.variant_id → product_variant (Batch-3c 미구현 상태)·delivery.order_item_id → order_item (기존 구현 확인 필요). 성공 케이스 시딩을 위해 nativeQuery INSERT 또는 FK 체인 완성 후 테스트 작성 고려.

**실용적 대안**: FK 위반 케이스는 nativeQuery 99999 삽입으로 독립 작성 가능·성공 케이스는 nativeQuery로 parent seed 후 진행 (LT-02 FK_CHECKS 복원 패턴 의무).

### 테스트 케이스 계획 (총 18 케이스 권고)

| Entity | 케이스 수 | 케이스 내용 |
|---|---|---|
| SellerBankAccount | 3 | save+findById 성공·seller_id FK 위반→PersistenceException·status ENUM 외 값→PersistenceException |
| Settlement | 4 | save+findById 성공·seller_id FK 위반→PersistenceException·bank_account_id FK 위반→PersistenceException·status ENUM 외 값→PersistenceException |
| WithdrawnSeller | 2 | save+findById 성공·original_seller_id FK 위반→PersistenceException |
| CartItem | 4 | save+findById 성공·UK(user_id, variant_id) 중복→DataIntegrityViolationException·user_id FK 위반→PersistenceException·variant_id FK 위반→PersistenceException |
| Delivery | 5 | save+findById 성공(public_id 자동 생성)·tracking_no NULL 다건 허용·tracking_no 중복 NOT NULL→DataIntegrityViolationException·order_item_id FK 위반→PersistenceException·carrier ENUM 외 값→PersistenceException |

---

## §8. 결정 요청 5건 (구현 PR-3b 진입 전)

| # | 항목 | 권고안 | 권고 이유 | 대안 |
|---|---|---|---|---|
| Q1 | settlement.bank_account_id 매핑 | **Long bankAccountId 권고** | D-01·aggregate-boundary §2.2 Settlement 외부 ID 참조에 SellerBankAccount.id 명시·STL-3 스냅샷 의미 정합·DB FK 유지·JPA @ManyToOne 금지 | @ManyToOne LAZY SellerBankAccount — D-01 위반·기각 |
| Q2 | withdrawn_seller.original_seller_id 매핑 | **@ManyToOne LAZY Seller 권고** | D-23 SLR-7 SoT·WithdrawnUser 패턴(D-84 Q2) 준용·내부 Aggregate 처치 | Long originalSellerId — 외부 ID 처치·D-23 패턴 불일치·대안 가능 |
| Q3 | seller_bank_account.account_number 매핑 | **평문 String 매핑 확정·AES Track 8+ 이연** | Track 7 범위(Entity·Repository)·Application Service 없는 단계·D-23 B-d4 정합 | @Convert AES 즉시 적용 — 범위 초과·Track 7 산출물 한정 위반·기각 |
| Q4 | cart_item UK(user_id, variant_id) Entity 매핑 | **DDL 신뢰·@Table uniqueConstraints 생략** | DB UK가 제약 주체·user_id·variantId 모두 Long 필드·Batch-2 UserRole/SellerUser 패턴 정합 | @Table uniqueConstraints 명시 — 허용 가능·선택적 |
| Q5 | Test Base | **Batch1DataJpaTestBase 재사용** | D-83 Q2·D-84 Q4 정합·신설 비용 0 | 신규 Batch3bDataJpaTestBase — 비용 > 이득·기각 |

**추가 확인 사항 (결정 전 사용자 검토 권장)**:
- aggregate-boundary §2.2에 WithdrawnSeller 추가 필요 여부 (Q2 채택 시 동반 갱신 권고)
- cart_item·delivery 성공 케이스 시딩 전략 (variant_id → Batch-3c 미구현·구현 순서 조정 또는 nativeQuery seed)

---

## §9. PASS/FAIL/WARN/OUT-OF-SCOPE 분류

### 9.1 WARN 목록

| ID | 항목 | 내용 | 조치 |
|---|---|---|---|
| WARN-1 | settlement public_id 비존재 | 정찰 지시에 "public_id stl_" 언급이나 V1 DDL 실측 결과 public_id 컬럼 없음·AbstractFullAuditableEntity 적용 확정 | abstract 매칭 변경 (AbstractPublicIdFull → AbstractFull)·구현 PR에서 getPublicIdPrefix() 구현 불필요 |
| WARN-2 | aggregate-boundary §2.2 WithdrawnSeller 미명시 | D-23 V2 신설 이후 aggregate-boundary §2.2 갱신 누락·WithdrawnSeller Seller Aggregate 귀속 미기록 | 구현 PR-3b 동반 aggregate-boundary §2.2 갱신 권고 (또는 Q2 결정 시 동반) |
| WARN-3 | 기존 Seller.java LT-03 미처치 | live-traps.md 표 "Batch-3b 진입 시" 처치 예정·실측 확인·@SQLRestriction 직접 선언 없음 | 구현 PR-3b에서 기존 Seller.java 수정 (1줄 추가) 필수 |
| WARN-4 | cart_item·delivery 시딩 복잡도 | product_variant(Batch-3c 미구현)·order_item 선행 필요·성공 케이스 시딩 부담 | nativeQuery seed 또는 Batch-3c 완료 후 테스트 보강·FK 위반 케이스는 독립 작성 가능 |

### 9.2 PASS 목록

| 검증 항목 | 결과 |
|---|---|
| §2 경계 대조 (aggregate-boundary §2.2·§2.4·§2.5 ↔ DDL FK) | PASS (4/5·withdrawn_seller WARN-2 조치 무필요) |
| D-01 외부 Aggregate 위반 검증 (신규 5건) | PASS (Q1·Q2 결정 채택 전제) |
| abstract 매칭 5종 | PASS (settlement public_id 없음 WARN-1 처치 후) |
| LT-03 신규 5 Entity 처치 의무 0건 확인 | PASS |
| LT-03 기존 Seller.java 미처치 발견 | PASS (발견·WARN-3 처치 계획 수립) |
| LT-01 delivery public_id 자동 적용 | PASS (AbstractPublicIdFullAuditableEntity 경유) |
| LT-02 적용 대상 | PASS (직접 대상 없음·시딩 복잡 케이스 시 try-finally 의무) |
| delivery.tracking_no UK NULL 다건 허용 확인 | PASS (MariaDB UNIQUE NULL 비교 제외) |
| 4층위 enum 잠금 Layer 1 확인 (4건) | PASS (V1 DDL ENUM 전건 선언) |
| 4층위 enum Layer 2 Java enum 신설 필요 (4건) | PASS (계획 수립·구현 라운드) |

### 9.3 FAIL 목록

**FAIL: 0건**

### 9.4 OUT-OF-SCOPE (Track 7 Batch-3b PR-3b 범위 밖)

- Application Service (SettlementService·SellerBankAccountService·정산 배치 등) → Track 8+ 이연
- Controller·DTO·OpenAPI
- State Machine (Settlement.status·SellerBankAccount.status·Delivery.status 전이 로직) → Track 8+
- Invariant 검증 로직 (STL-1 정산액 계산·CRT-2 수량 ≥1 Service 가드 등) → Track 8+
- AES @Converter 구현 (seller_bank_account.account_number·SLR-2) → Track 8+ [Q3 채택]
- Seller 비식별화 배치 (D-23 흐름·배치 Job) → Track 8+
- CartItem 주문 전환 플로우 → Track 8+
- delivery.delivered_at 자동 마킹 로직 → Track 8+
- E2E·@SpringBootTest 통합 테스트
- 4층위 enum Layer 3 (DTO @ValidEnum)·Layer 4 (프론트) → Track 8+
- Batch-3c (product_image·product_option_group·product_option_value·attachment·audit_log·notification_log) → PR-3c
- aggregate-boundary §2.2 갱신 (WithdrawnSeller 추가) — 권고 사항·동반 처리 가능 (코드 변경 0)

---

## §10. 권고 패키지 구조

```
com.zslab.mall.seller.entity
  SellerBankAccount    (신규)
  WithdrawnSeller      (신규)
  Seller               (기존·LT-03 @SQLRestriction 추가 필요)
com.zslab.mall.seller.enums
  SellerBankAccountStatus  (신규·PENDING·VERIFIED·REJECTED)
com.zslab.mall.seller.repository
  SellerBankAccountRepository  (신규)
  WithdrawnSellerRepository    (신규)

com.zslab.mall.settlement.entity
  Settlement           (신규)
com.zslab.mall.settlement.enums
  SettlementStatus     (신규·PENDING·CONFIRMED·PAID)
com.zslab.mall.settlement.repository
  SettlementRepository (신규)

com.zslab.mall.cart.entity
  CartItem             (신규)
com.zslab.mall.cart.repository
  CartItemRepository   (신규)

com.zslab.mall.delivery.entity
  Delivery             (신규)
com.zslab.mall.delivery.enums
  DeliveryCarrier      (신규·CJ·HANJIN·POST·LOGEN)
  DeliveryStatus       (신규·READY·SHIPPING·DELIVERED)
com.zslab.mall.delivery.repository
  DeliveryRepository   (신규)
```

**신규 파일**: 14건 (Entity 5·Enum 4·Repository 5)  
**기존 파일 수정**: 1건 (`Seller.java` @SQLRestriction 1줄 추가·LT-03 처치)

---

## §11. 구현 라운드 진입 가능 여부

**전제**: Q1~Q5 전건 채택 시 즉시 구현 착수 가능.

**단, 구현 전 확인 필요 사항**:
1. aggregate-boundary §2.2 WithdrawnSeller 갱신 동반 여부 (권고: 동반 처리)
2. cart_item·delivery 성공 케이스 시딩 전략 (product_variant 미구현 상태에서 CartItem 성공 케이스 접근법)
3. 기존 Seller.java 수정 범위 합의 (LT-03 1줄·WARN-3 처치)

**FAIL 0건·WARN 4건 (전건 조치 무필요 또는 구현 라운드 처치)** → 결정 요청 채택 후 구현 착수 가능.

---

*참조: D-01 (Aggregate 외부 ID 참조)·D-23 (Seller 비식별화·SLR-7·WithdrawnSeller)·D-81 (Track 7 분할·PR-3b)·D-82 (live-traps.md·LT-03)·D-83 (Batch-2 진입)·D-84 (Batch-3a 진입·@MapsId)·aggregate-boundary.md §2.2·§2.4·§2.5·live-traps.md LT-01·LT-02·LT-03·V1__init.sql #16·#17·#27·#32·V2__seller_anonymization.sql*
