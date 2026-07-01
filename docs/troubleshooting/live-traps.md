# Live Traps Catalog

> 라이브 발견 트랩 카탈로그·후속 트랙 진입 전 정독 의무 SoT
> 박제 임계: ≥3건 누적 시 신설 (D-82 정합·운영 first → repetition → promote 원칙)
> 출처: CLAUDE.md "라이브 트랩 방지" 룰·D-82

---

## 목적

- CI·단위 테스트로 탐지 불가하고 라이브 실행·후속 테스트 실행 시점에만 표면화하는 트랩 영구 추적
- 후속 트랙 진입 시 동일 트랩 재발 방지 (정독 의무)
- 단건 트랩은 decisions.md D-XX 박제 유지·≥3건 누적 시 본 카탈로그 신설·기존 단건 결정은 [ARCHIVED] 라벨 후 본 문서로 이관 (D-82 정합)

## 정독 의무 시점

- Track 7 Batch-3 진입 전 (LT-03 영향 7 Entity)
- Track 8+ Application Service 트랙 진입 전 (LT-01·LT-02 후속 영향)
- 신규 라이브 트랩 발견 시 즉시 본 문서 갱신

---

## LT-01. CHAR(N) public_id @JdbcTypeCode 미적용 시 Hibernate VARCHAR 매핑 [ACTIVE]

**발견 트랙**: Track 2 Order Aggregate
**원본 결정**: D-26 [ARCHIVED]

### 증상
public_id 컬럼이 DDL에서 `CHAR(30)`으로 정의되어 있으나 Hibernate가 기본적으로 VARCHAR로 매핑·DDL과 부정합·후속 마이그레이션 충돌 가능.

### 재현
```java
// 트랩 (잘못된 매핑)
@Column(name = "public_id", length = 30, nullable = false, updatable = false)
private String publicId;
```
→ Hibernate가 VARCHAR(30)으로 처리·CHAR(30) 우측 공백 패딩 의미 미반영.

### 처치
```java
@JdbcTypeCode(SqlTypes.CHAR)
@Column(name = "public_id", length = 30, nullable = false, updatable = false)
private String publicId;
```

### 후속 영향
- AbstractPublicIdFullAuditableEntity·AbstractPublicIdSoftDeletableEntity 본문에 @JdbcTypeCode 선언 → 상속 Entity 자동 적용
- 신규 public_id 컬럼 추가 시 abstract 미상속 케이스에서 동일 처치 의무

### 관련
- 원본: D-26 (decisions.md [ARCHIVED])
- abstract 적용 Entity: Order·OrderItem·Payment·Delivery·Claim·Refund·User·Seller·Product·ProductVariant·Attachment

---

## LT-02. Testcontainers SET FOREIGN_KEY_CHECKS HikariCP 잔류 [ACTIVE]

**발견 트랙**: Track 6 PR-A OrderTransactionRollbackTest
**원본 결정**: D-79 [ARCHIVED]

### 증상
invalid FK item 시딩을 위해 `SET FOREIGN_KEY_CHECKS=0` 사용 시 HikariCP 커넥션 풀에 세션 변수 잔류·**후속 테스트에서 FK 비활성 오염** 발생.
CI 미탐지 (단독 실행 시 통과)·다중 테스트 순차 실행 시에만 표면화.

### 재현
```java
// 트랩 (복원 누락)
entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS=0").executeUpdate();
entityManager.persist(invalidFkEntity);
// SET FOREIGN_KEY_CHECKS=1 복원 누락 → HikariCP 커넥션 반환 시 변수 잔류
```

### 처치
```java
try {
    entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS=0").executeUpdate();
    // seed·cleanup 작업
} finally {
    entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS=1").executeUpdate();
}
```
`SET FOREIGN_KEY_CHECKS=0` 사용 시 동일 트랜잭션·동일 커넥션 내 1:1 복원 짝 의무 (try-finally 또는 동등 구조).

### 후속 영향
- 전 Testcontainers 기반 통합 테스트
- @DataJpaTest·@SpringBootTest 양쪽 동일 적용

### 관련
- 원본: D-79 (decisions.md [ARCHIVED])

---

## LT-03. @SQLRestriction @MappedSuperclass → @Entity 비전파 (HHH-17453) [ACTIVE]

**발견 트랙**: Track 7 Batch-1 (Category 구현)
**원본 결정**: D-82 본문 §3

### 증상
`@SQLRestriction("deleted_at IS NULL")`이 abstract @MappedSuperclass 클래스에 선언되어 있으나 **Hibernate 6.6에서 @Entity 서브클래스로 전파되지 않음** (HHH-17453 버그).
→ soft-delete된 행이 `findAll()`·`findById()`에 노출·@SQLRestriction 무효.

### 재현
```java
// 트랩 (abstract 클래스만 선언)
@MappedSuperclass
@SQLRestriction("deleted_at IS NULL")
public abstract class AbstractSoftDeletableEntity extends AbstractFullAuditableEntity {
    // deleted_at·deleted_by·delete_reason 필드
}

// @Entity 서브클래스
@Entity
@Table(name = "category")
public class Category extends AbstractSoftDeletableEntity {
    // @SQLRestriction 전파 안됨·soft-delete 무효
}
```

### 처치
@Entity 서브클래스에 **@SQLRestriction 직접 선언**:
```java
@Entity
@Table(name = "category")
@SQLRestriction("deleted_at IS NULL")  // 직접 선언 의무
public class Category extends AbstractSoftDeletableEntity {
    // ...
}
```

### 후속 영향 (Batch-3 필수 처치)
AbstractSoftDeletableEntity·AbstractPublicIdSoftDeletableEntity 상속 Entity 전원 직접 선언 의무:

| Entity | 상속 abstract | 처치 |
|---|---|---|
| Category | AbstractSoftDeletableEntity | ✓ 완료 (Track 7 Batch-1) |
| UserAddress | AbstractSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3a) |
| ProductImage | AbstractSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3c) |
| User | AbstractPublicIdSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3a) |
| Seller | AbstractPublicIdSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3b) |
| Product | AbstractPublicIdSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3c) |
| ProductVariant | AbstractPublicIdSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3c) |
| Attachment | AbstractPublicIdSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3c) |

### 검증 방법
@DataJpaTest 케이스 필수 1건: "soft-delete 후 findById Optional.empty" (Category 기 적용).

### 관련
- 원본: D-82 (decisions.md ACTIVE·본 카탈로그 신설 결정)
- 외부 참조: Hibernate HHH-17453

---

## LT-04. AFTER_COMMIT 핸들러 시점에 동기 upstream 상태 전이 완료로 item_status 기반 skip 가드 데드코드 [ACTIVE]

**발견 트랙**: Track 17 PR-B 구현 진입 전 정찰 (Claude Code 실측)
**원본 결정**: D-101 §6 갱신 (2026-07-01)

### 증상
AFTER_COMMIT 핸들러의 "item_status == <종결값>이면 skip" 1차 가드가 정상 흐름에서 항상 skip 판정·핸들러 본문 영구 미실행.

### 재현
- 발행처 Service가 이벤트 발행 후 동일 트랜잭션 내 동기 소비 핸들러(@EventListener)가 upstream 상태 전이 수행 (예: OrderEventHandler.markPaid → OrderItem PAID 전이)
- 발행 트랜잭션 커밋 후 AFTER_COMMIT 핸들러 실행 시점에 모든 대상 이미 종결 상태 진입
- "item_status == 종결값이면 skip" 가드 → 항상 skip → 핸들러 본문 데드코드

### 처치
1차 가드 제거 + 재전달 방어선 이중화 (upstream UNIQUE 제약 + at-most-once publisher + 도메인 invariant backstop)

### 후속 영향
- 신규 AFTER_COMMIT 핸들러 신설 시 동기 upstream 상태 전이 여부 실측 의무
- 상태 기반 가드 대신 History 기반 또는 도메인 invariant 단독 방어 우선 검토
- 통합 테스트에서 동기 upstream 실행 경로 실증 의무 (라이브 트랩 방지 회귀 안전망)

### 검증 방법
통합 테스트 1건 필수: "동기 upstream 상태 전이 후 AFTER_COMMIT 핸들러 본문 실제 실행 검증" (Track 17 T2 SoT).

### 관련
- 원본: D-101 §6 갱신 (decisions.md ACTIVE)
- 관련 결정: D-75 (AFTER_COMMIT + REQUIRES_NEW)·D-100 Q1 γ (멱등 패턴 카탈로그)·D-100 Q2 γ (인메모리 publisher)

---

## LT-05. AFTER_COMMIT 형제 핸들러 실행 순서 비결정으로 상태 기반 skip 가드 누락 부작용 [ACTIVE]

**발견 트랙**: Track 17 PR-B 구현 진입 전 정찰 (Claude Code 실측)
**원본 결정**: D-101 §6 갱신 (2026-07-01)

### 증상
동일 이벤트를 소비하는 AFTER_COMMIT 형제 핸들러 2건 이상 존재·`@Order` 미부여 시 실행 순서 비결정. 상태 기반 skip 가드는 형제 핸들러가 먼저 실행되어 상태 전이 완료 시 후속 핸들러 부작용 누락.

### 재현
- 이벤트 E 소비 핸들러 A·B 양자 AFTER_COMMIT
- 핸들러 A: OrderItem *_REQUESTED → 종결 상태 전이
- 핸들러 B: "item_status 종결값이면 skip" 가드 보유 (재고 복구 등)
- 순서가 A → B로 잡히면 B skip → 재고 복구 누락
- 순서가 B → A로 잡히면 B 정상 실행 → 정상 복구
- 순서 비결정적·CI 통과 후 라이브에서 산발 실패

### 처치
상태 무관 멱등 가드로 대체:
- History 존재 조회 (`existsByReferenceTypeAndReferenceId`) 등 순서 독립 가드
- 형제 핸들러가 세팅하는 상태 필드에 비의존

### 후속 영향
- 동일 이벤트 소비 AFTER_COMMIT 핸들러 2건 이상 신설 시 순서 종속성 실측 의무
- `@Order` 강제는 D-100 Q9 γ "AFTER_COMMIT 순서 비보장" 박제 위배·비권장
- 통합 테스트에서 재발행 멱등 시나리오 실증 의무 (Track 17 T5 SoT)

### 검증 방법
통합 테스트 1건 필수: "동일 이벤트 재발행 시 형제 핸들러 순서 무관 멱등 검증" (History 기반 skip 실증·Track 17 T5 SoT).

### 관련
- 원본: D-101 §6 갱신 (decisions.md ACTIVE)
- 관련 결정: D-75 (AFTER_COMMIT + REQUIRES_NEW)·D-100 Q9 γ (핸들러 순서 비보장)·D-100 Q1 γ (멱등 패턴 카탈로그·B-2 History 기반 신설 근거)

---

## 부록. 트랩 추가 절차

1. 라이브 발견 시 즉시 decisions.md D-XX 박제 (단건 처리)
2. 누적 ≥3건 도달 시점에 본 카탈로그 신설 (D-82 패턴)
3. 기 신설 후: 신규 트랩은 본 카탈로그 LT-XX 직접 추가·decisions.md 중복 박제 금지
4. 트랩 해소·무효화 시 [RESOLVED] 라벨 부착·항목 보존 (이력 추적)