# Track B 정찰 보고서 (recon-report.md)

> 목적: docs/track-b/expected-spec.md(기대 명세) ↔ Track B 실제 코드 1:1 대조 결과 박제
> 작성 주체: Claude Code Sonnet 4.6 (read-only 정찰)
> 작성 시점: 2026-06-25
> 정찰 기준: expected-spec.md 14 섹션 + 정찰 우선순위 10항

---

## 정찰 요약

| 분류 | 건수 |
|---|---|
| PASS | 43 |
| FAIL — 코드 결함 | 0 |
| FAIL — 기대 명세 결함 | 1 |
| WARN — 보완 권고 | 2 |
| OUT-OF-SCOPE | 2 |

결론: **push 진입 가능** — FAIL 코드 결함 0건. 기대 명세 결함 1건(코드는 올바름·명세 보정 필요). WARN 2건(보완 권고·블로커 아님). OOS 2건(후속 트랙·PR 생성 시 확인).

---

## 정찰 우선순위 10항 결과

### 1. 발견 #1 적용 정합 (PublicId 엔티티 @EqualsAndHashCode·@ToString 재선언 0)
- 대상: Order.java·OrderItem.java
- 기대: 클래스 레벨 @EqualsAndHashCode·@ToString 재선언 0
- 실제:
  - Order.java: 클래스 레벨에 @EqualsAndHashCode·@ToString 없음. Javadoc에 "재선언하지 않는다(Q8=C·base javadoc)" 명시.
  - OrderItem.java: 동일.
- 분류: **PASS**

### 2. 양방향 매핑 차단
- 대상: OrderShippingSnapshot.order·OrderItem.order 필드
- 기대: @Getter(AccessLevel.NONE)·assignOrder package-private
- 실제:
  - OrderShippingSnapshot.order: `@Getter(AccessLevel.NONE)` ✅, `void assignOrder(Order order)` package-private ✅
  - OrderItem.order: `@Getter(AccessLevel.NONE)` ✅, `void assignOrder(Order order)` package-private ✅
- 분류: **PASS**

### 3. ORD-1~5 invariant 강제 위치 정합
- ORD-1 (OrderItem ≥1): `OrderService.createOrder`에 `command.items().isEmpty()` 가드·예외 메시지에 "ORD-1" 명시 ✅
- ORD-2 (status = Resolver 결과): `applyResolvedStatus`로만 status 변경. markPaid는 동기화 규칙 [1]로 Resolver 미경유 — javadoc에 명시 ✅
- ORD-3 (멀티벤더 혼재 허용): `sellerId Long` 필드만 존재·제약 없음 ✅
- ORD-4 (order_no UNIQUE): `existsByOrderNo + DB UK + 1회 재시도` 구현 ✅
- ORD-5 (total_price = unit_price × quantity): `OrderItem.create`에서 `totalPrice != unitPrice * quantity` 검증·예외 메시지 "ORD-5" 명시 ✅
- 분류: **PASS (전 5항목)**

### 4. OrderStatusResolver 평가 순서 정확
- 기대: [5]→[6]→[7]→[4]→[3]→[2]→기본 PAID
- 실제 코드 순서:
  ```
  allMatch(CANCELLED)          → CANCELLED       [5]
  contains(CANCELLED) && allNonCancelledIn(CONFIRMED_LIKE) → PARTIAL_CANCEL  [6]
  allIn(CONFIRMED_LIKE)        → CONFIRMED       [7]
  allMatch(DELIVERED)          → DELIVERED       [4]
  contains(SHIPPING)           → SHIPPING        [3]
  contains(PREPARING)          → PREPARING       [2]
  기본                         → PAID
  ```
  CONFIRMED_LIKE = {CONFIRMED, RETURNED, EXCHANGED} — EnumSet 상수 분리 ✅
- 분류: **PASS**

### 5. OrderItemStatus.canTransitionTo 매트릭스 정합
- 기대: 진행 순방향 6·*_REQUESTED→종결 3·종결 4값 false·역/건너뛰기 차단·Claim 진입 이연+javadoc
- 실제 switch:
  ```
  ORDERED → PAID | PAID → PREPARING | PREPARING → SHIPPING
  SHIPPING → DELIVERED | DELIVERED → CONFIRMED
  CANCEL_REQUESTED → CANCELLED | RETURN_REQUESTED → RETURNED
  EXCHANGE_REQUESTED → EXCHANGED
  CONFIRMED, CANCELLED, RETURNED, EXCHANGED → false
  ```
  - 12값 완전 커버 ✅, 종결 4값 false ✅, Claim 진입 전이 미포함 ✅
  - javadoc에 "Track 5(Refund Flow)에서 추가" 명시 ✅
- 분류: **PASS**

### 6. OrderPlaced payload 3 필드 한정
- 기대: publicId·orderId·occurredAt (3 필드)
- 실제: `record OrderPlaced(String publicId, Long orderId, LocalDateTime occurredAt)` — 정확히 3필드
- Order 엔티티 통째 전달 0·OrderItem 목록 0·금액 0 ✅
- 분류: **PASS**

### 7. Scope Gate 위반 0
- @RestController/@Controller: 0건 (grep 확인)
- FetchType.EAGER: 0건 (grep 확인)
- CascadeType.ALL: 0건 (grep 확인)
- Querydsl/CustomRepository: 0건 (glob 확인)
- order 패키지 외 신규 파일: 0건 (glob 결과 정확히 14파일·order 패키지 내)
- Payment/Refund/Claim Entity: 0건
- Controller/DTO Response: 0건
- 분류: **PASS**

### 8. 발견 #2 흡수 정합
- AbstractPublicIdFullAuditableEntity.publicId: `@JdbcTypeCode(SqlTypes.CHAR)` ✅, import 2건 ✅
- AbstractPublicIdSoftDeletableEntity.publicId: `@JdbcTypeCode(SqlTypes.CHAR)` ✅, import 2건 ✅
- decisions.md D-26: D-25 다음 등재 ✅, 원인 분류(설계 문제) ✅, 4점 추적(데이터 손실 없음·롤백 가능·재현성) ✅
- 다른 base 클래스(AbstractCreatedOnlyEntity·AbstractFullAuditableEntity 등) 영향 없음 ✅
- 분류: **PASS**

### 9. order_no 형식 정합
- 기대: yyyyMMdd-XXXXXX (구현 세부 비검증)
- 실제:
  ```java
  private static final DateTimeFormatter ORDER_NO_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
  private static final int ULID_SUFFIX_START = 20;
  // generateOrderNo():
  String ulidSuffix = UlidCreator.getMonotonicUlid().toString().substring(ULID_SUFFIX_START).toUpperCase();
  return LocalDate.now().format(ORDER_NO_DATE_FORMAT) + "-" + ulidSuffix;
  ```
  BASIC_ISO_DATE = "yyyyMMdd"·ULID 후미 6자(index 20~25) 대문자 = "XXXXXX" → yyyyMMdd-XXXXXX 15자
- OrderServiceTest에서 `result.getOrderNo().matches("\\d{8}-[0-9A-Z]{6}")` 검증 ✅
- 분류: **PASS**

### 10. OrderItem 불변 전제 정합
- 기대: 수량·금액 변경 메서드 0·itemStatus 전이만
- 실제: OrderItem 공개 메서드 = create(static)·assignOrder(package-private)·changeStatus·markPaid
  - 수량(quantity) 변경 메서드 없음 ✅
  - 금액(unitPrice·totalPrice) 변경 메서드 없음 ✅
  - itemStatus 전이만: changeStatus·markPaid ✅
- 분류: **PASS**

---

## 14 섹션 대조 결과

### §1 패키지 구조
- 기대: entity·enums·repository·service·command·event 6 하위·main 14파일·test 10파일
- 실제 (glob):
  - main: Order·OrderItem·OrderShippingSnapshot·OrderStatus·OrderItemStatus·OrderRepository·OrderItemRepository·OrderShippingSnapshotRepository·OrderService·OrderStatusResolver·CreateOrderCommand·OrderItemCommand·ShippingAddressCommand·OrderPlaced = **14파일**
  - test: OrderItemStatusTest·OrderStatusTest·OrderTest·OrderItemTest·OrderStatusResolverTest·OrderServiceTest·OrderDataJpaTestBase·OrderRepositoryTest·OrderItemRepositoryTest·OrderShippingSnapshotRepositoryTest = **10파일**
  - 하위 패키지: entity·enums·repository·service·command·event = **6 하위** ✅
- 분류: **PASS**

### §2.1 Order Entity
- 상속: AbstractPublicIdFullAuditableEntity·prefix "ord" ✅
- 클래스 어노테이션: @Entity·@Table(name="`order`")·@Getter·@NoArgsConstructor(PROTECTED) ✅, @EqualsAndHashCode/@ToString 재선언 없음 ✅
- 필드 전체: id·buyerId·orderNo·status·totalPrice·discountAmount·shippingFee·paidAt·orderedAt ✅
  - orderNo: length=50·updatable=false ✅
  - items: @OneToMany(mappedBy="order", cascade={PERSIST,MERGE}, fetch=LAZY)·@Getter(NONE) ✅
  - shippingSnapshot: @OneToOne(mappedBy="order", cascade=PERSIST, fetch=LAZY) ✅
- 메서드: create·getItems(unmodifiable)·addItem·attachSnapshot·markPaid·applyResolvedStatus·getPublicIdPrefix ✅
  - create: status=PENDING_PAYMENT·totalPrice=0L ✅
  - addItem: null 가드·items추가·assignOrder·totalPrice 누적 ✅
  - markPaid: Resolver 미경유·모든 item.markPaid()·status=PAID·paidAt 갱신 ✅
- 분류: **PASS** (단, 검증 포인트 §2.1 "shippingSnapshot getter 비공개" 항목은 → FAIL — 기대 명세 결함 #1 참조)

### §2.2 OrderItem Entity
- 상속: AbstractPublicIdFullAuditableEntity·prefix "oit" ✅
- 클래스 어노테이션: @Entity·@Table("order_item")·@Getter·@NoArgsConstructor(PROTECTED) ✅, @EqualsAndHashCode/@ToString 재선언 없음 ✅
- 필드: id·order(@Getter(NONE)·@ManyToOne LAZY·@JoinColumn updatable=false)·productId·variantId·sellerId·quantity(int)·unitPrice·totalPrice·itemStatus(@Enumerated(STRING)) ✅
- 메서드:
  - create: null가드·quantity<1가드·ORD-5(totalPrice != unitPrice*quantity)→IllegalArgumentException ✅
  - assignOrder: package-private ✅
  - changeStatus: null가드·canTransitionTo 검증·불법→IllegalStateException ✅
  - markPaid: changeStatus(PAID) 위임 ✅
- 분류: **PASS**

### §2.3 OrderShippingSnapshot Entity
- 상속: AbstractFullAuditableEntity (publicId 없음) ✅
- 클래스 어노테이션: @Entity·@Table("order_shipping_snapshot")·@Getter·@NoArgsConstructor(PROTECTED)·@EqualsAndHashCode(onlyExplicitlyIncluded=true, callSuper=false)·@ToString(onlyExplicitlyIncluded=true) ✅
- 필드:
  - id: @Id @GeneratedValue(IDENTITY)·@EqualsAndHashCode.Include·@ToString.Include ✅
  - order: @Getter(NONE)·@OneToOne(LAZY, optional=false)·@JoinColumn(nullable=false, updatable=false) ✅
  - addressJibun·addressDetail: nullable ✅, deliveryMemo: columnDefinition="TEXT" ✅
- 메서드: create(FK 미연결 상태 생성)·assignOrder(package-private)·belongsTo ✅
- belongsTo javadoc: "역참조 getter 미노출 대체 도메인 메서드·QB-10" 명시. "현재 사용처 없음·보조 메서드" 미명시 → WARN #1 참조
- 분류: **PASS** (WARN #1 병기)

### §3.1 OrderStatus
- 8값: PENDING_PAYMENT·PAID·PREPARING·SHIPPING·DELIVERED·CONFIRMED·CANCELLED·PARTIAL_CANCEL ✅
- canTransitionTo 미구현: ✅ (해당 메서드 없음)
- 미구현 사유 javadoc: "Resolver 파생 결과이므로 전이 매트릭스를 두면 설계 의도와 충돌" 명시 ✅
- 분류: **PASS**

### §3.2 OrderItemStatus
- 12값 ✅ (OrderItemStatusTest.hasTwelveValues 검증)
- canTransitionTo switch 12값 완전 커버 ✅
- 종결 4값 false ✅
- Claim 진입 전이 미포함·javadoc Track 5 이연 명시 ✅
- 분류: **PASS**

### §4 Repository 3건
- OrderRepository: `findByPublicId`·`findByOrderNo`·`existsByOrderNo` ✅
- OrderItemRepository: `findByOrderId`·`findByOrderIdIn(Collection<Long>)` ✅
- OrderShippingSnapshotRepository: `findByOrderId` ✅
- 공통: JpaRepository 단일 상속·Custom interface 없음·Querydsl 없음·@Query 없음 ✅
- 버그 포인트: findByPublicId가 String으로 조회하므로 @JdbcTypeCode(CHAR) 매핑 정합이 전제 — 발견 #2 흡수로 보장 ✅
- 분류: **PASS**

### §5 OrderStatusResolver
- @Component ✅
- resolve(List<OrderItemStatus>): 빈 리스트·null → IllegalArgumentException ✅
- 평가 순서 [5]→[6]→[7]→[4]→[3]→[2]→PAID ✅
- [1] PAID 일괄 전환은 resolve 미경유(markPaid 직접) ✅
- 외부 의존성 없음 (순수 함수) ✅
- 보조 메서드(allMatch·contains·allIn·allNonCancelledIn) 명확 분리 ✅
- 분류: **PASS**

### §6 OrderService
- @Service·@Transactional(클래스 레벨) ✅ — 검증 포인트 "클래스 레벨 + 메서드 레벨 혼합 가능" 정합
- 의존성: OrderRepository·OrderStatusResolver·ApplicationEventPublisher ✅ (생성자 주입)
- createOrder:
  - ORD-1 가드 ✅
  - generateUniqueOrderNo: yyyyMMdd-XXXXXX·existsByOrderNo·1회 재시도 ✅
  - Order.create→addItem 반복→attachSnapshot→save(cascade PERSIST) ✅
  - publishEvent(new OrderPlaced(publicId, id, LocalDateTime.now())) ✅
- markPaid: findOrder·order.markPaid(paidAt) ✅
- recalculateStatus: findOrder·itemStatuses 추출·resolve→applyResolvedStatus ✅
- Claim 이벤트 핸들러 미작성 + javadoc "Track 5 진입 시 추가" 명시 ✅
- 분류: **PASS**

### §7 Command 3건
- CreateOrderCommand: record(buyerId·items·shipping·discountAmount·shippingFee) ✅
- OrderItemCommand: record(productId·variantId·sellerId·quantity·unitPrice·totalPrice) ✅
- ShippingAddressCommand: record(recipientName·recipientPhone·zonecode·addressRoad·addressJibun·addressDetail·deliveryMemo) ✅
- 불변성 확보(record) ✅, Bean Validation 없음(Track 4 이연 정합) ✅
- 분류: **PASS**

### §8 OrderPlaced
- record ✅
- 3 필드: `String publicId`·`Long orderId`·`LocalDateTime occurredAt` ✅
- 핸들러 0건(Track 7 이연 javadoc 명시) ✅
- 분류: **PASS**

### §9 Test 산출물

**§9.1 Unit Test**

OrderItemStatusTest:
- 12×12 전 조합 오라클 대조(canTransitionTo_coversFullMatrix) ✅
- 종결 4값 → 어떤 전이도 false(terminalStatuses_haveNoOutgoingTransition) ✅
- 역방향/건너뛰기 차단 표본 ✅
- DDL 정합 12값 ✅

OrderStatusTest:
- 8값 hasSize ✅
- 값 이름 집합 containsExactlyInAnyOrder ✅

OrderStatusResolverTest:
- [5]~[2] 각 분기 케이스(7개 @Test) ✅
- 평가 우선순위([5]>[6], [6]>[7], [3]>[2]) ✅
- 빈 리스트·null → IllegalArgumentException ✅

OrderTest:
- addItem 누적·양측 연결 ✅
- attachSnapshot 1:1 연결 ✅
- markPaid 규칙[1] ✅
- applyResolvedStatus ✅
- **X1 (PublicIdEqualsHashCode Nested)**:
  - samePublicId → equals true·hashCode 동일 ✅
  - differentPublicId → equals false ✅
  - 양쪽 null → equals true(onlyExplicitlyIncluded 동작 정합) ✅

OrderItemTest:
- create 정상·ORD-5 위반·quantity<1·필수값 null (4 케이스) ✅
- changeStatus 합법/불법 전이 ✅
- markPaid ✅

OrderServiceTest(Mockito):
- createOrder ORD-1 가드·정상 흐름·재시도 1회·publishEvent ✅
- markPaid 위임·미존재 예외 ✅
- recalculateStatus Resolver 호출·applyResolvedStatus ✅

**§9.2 @DataJpaTest**

OrderDataJpaTestBase:
- `@DataJpaTest·@AutoConfigureTestDatabase(NONE)·@Import(AuditingConfig)` ✅
- `@DynamicPropertySource` ✅
- `@Testcontainers·@Container static MariaDBContainer` 미사용 — static initializer 대체 → **WARN #2 참조**
- Flyway V1·V2 자동 적용·실 MariaDB 스키마 검증 ✅

OrderRepositoryTest: save/@PrePersist public_id·findByPublicId·findByOrderNo·existsByOrderNo ✅
OrderItemRepositoryTest: cascade PERSIST·findByOrderId·findByOrderIdIn ✅
OrderShippingSnapshotRepositoryTest: cascade PERSIST·findByOrderId·belongsTo 도메인 메서드 ✅
@SpringBootTest 확대: 0건 ✅

- 분류: **PASS** (WARN #2 병기)

### §10 빌드 의존성
- `testImplementation("org.testcontainers:junit-jupiter")` ✅
- `testImplementation("org.testcontainers:mariadb")` ✅
- 버전: Spring Boot 3.4.1 dependency-management 플러그인이 import하는 Spring Boot BOM이 Testcontainers 버전 관리 → 개별 버전 명시 0 ✅
- 주석: "버전은 Spring Boot 3.4.1이 import하는 Testcontainers BOM이 관리(단일 소스·드리프트 방지)" 명시 ✅
- testImplementation만·implementation/runtimeOnly 없음 ✅
- 비고: 명세에서 `platform("org.testcontainers:testcontainers-bom:...")` 명시 통보 방식과는 다르지만, Spring Boot dependency-management 경로로 동일 효과 달성. PASS 처리.
- 분류: **PASS**

### §11 Track A 잔여 4건 (R1·X1·X2·R4)
- **R1** AuditorAwareImpl javadoc: "Current policy: Audit actor is intentionally unresolved before Security integration." ✅ / "NULL audit values are allowed by Q1 decision." ✅
- **X1** publicId equals/hashCode 테스트: OrderTest.PublicIdEqualsHashCode nested(3 케이스) 작성 ✅
- **X2** @PrePersist private 유지:
  - AbstractPublicIdFullAuditableEntity: `private void generatePublicId()` ✅
  - AbstractPublicIdSoftDeletableEntity: `private void generatePublicId()` ✅
- **R4** PR 본문 체크리스트("equals/hashCode @Include 부착 확인"): PR 미생성(미push 상태) → OUT-OF-SCOPE #1 참조
- 분류: R1·X1·X2 **PASS** / R4 **OUT-OF-SCOPE**

### §12 발견 #2 흡수 (D-26)
- AbstractPublicIdFullAuditableEntity: @JdbcTypeCode(SqlTypes.CHAR) 부착·import 2건 ✅
- AbstractPublicIdSoftDeletableEntity: @JdbcTypeCode(SqlTypes.CHAR) 부착·import 2건 ✅
- decisions.md D-26: D-25 다음·기존 D-* 패턴·원인 분류(설계 문제)·4점 추적·Impact·Alternative·후속 전체 ✅
- 분류: **PASS**

### §13 금지 사항 0건 검증
- @RestController/@Controller: 0건 ✅
- FetchType.EAGER: 0건 ✅
- CascadeType.ALL: 0건 ✅ (cascade = {PERSIST, MERGE} 또는 PERSIST만)
- Querydsl/CustomRepository: 0건 ✅
- @SpringBootTest 확대: 0건(contextLoads 1건만·order 패키지 외) ✅
- DDL V1·V2 수정: 0건 ✅
- Payment/Refund/Claim Entity: 0건 ✅
- OrderItem 수량·금액 변경 메서드: 0건 ✅
- 분류: **PASS**

### §14 전체 검증 (gate-conditions §1·§2·§3 부분)
- §1 구조 Gate:
  - Entity 수정율 ≤10%: Track B는 신규 생성·Track A 회귀 2파일은 D-26 정합(별도 추적) ✅
  - FK 재설계 0 ✅
  - Aggregate 경계 변경 0 ✅
- §3 기술 Gate: Flyway clean+migrate 성공 — 실 MariaDB testcontainers 검증(STEP 15 보고) ✅
- §2 기능 Gate: 주문 생성·결제·환불 E2E는 Track 4·5·6 이후 → OUT-OF-SCOPE #2 참조
- 분류: §1·§3 부분 **PASS** / §2 E2E **OUT-OF-SCOPE**

---

## FAIL 항목 상세

### FAIL #1: §2.1 검증 포인트 "shippingSnapshot getter 비공개" — 기대 명세 자기모순
- 분류: **FAIL — 기대 명세 결함** (코드는 올바름)
- 위치: expected-spec.md §2.1 검증 포인트 "items·shippingSnapshot getter는 비공개 또는 unmodifiable 반환"
- 기대(검증 포인트): shippingSnapshot getter가 "비공개 또는 unmodifiable 반환"
- 실제 코드: `Order`에 @Getter 클래스 레벨 → `getShippingSnapshot()` public 노출
- 자기모순 근거:
  1. expected-spec.md §2.1 **필드 정의**에 shippingSnapshot의 `@Getter(AccessLevel.NONE)` 미기재 (items는 명시, shippingSnapshot은 미명시)
  2. expected-spec.md §2.3 **관계 정의**: "Order → Snapshot **단방향 탐색만 허용**" — 이는 Order에서 Snapshot 접근이 허용됨을 의미
  3. OrderTest.attachSnapshot_links()가 `order.getShippingSnapshot()`을 호출 → 테스트도 public getter를 전제
  4. 위 3개 근거 모두 public getter를 정당화. 검증 포인트 문장만이 "비공개"를 요구해 자기모순 발생
- 영향도: 코드 회귀 불필요. 명세 보정 필요.
- 권고: expected-spec.md §2.1 검증 포인트를 "**items** getter는 비공개(@Getter(NONE))·unmodifiable 반환 경유. **shippingSnapshot** getter는 공개(Order→Snapshot 단방향 탐색 허용·QB-10 설계 의도)"로 분리 보정.

---

## WARN 항목 상세

### WARN #1: OrderShippingSnapshot.belongsTo javadoc "현재 사용처 없음·보조 메서드" 미명시
- 위치: [OrderShippingSnapshot.java:98-101](backend/src/main/java/com/zslab/mall/order/entity/OrderShippingSnapshot.java#L98-L101)
- 기대: expected-spec.md §2.3 "belongsTo javadoc에 '현재 사용처 없음·보조 메서드' 명시 권장"
- 실제: `"본 스냅샷이 주어진 주문에 속하는지 판정한다(역참조 getter 미노출 대체 도메인 메서드·QB-10)."` — 역할 설명은 있으나 "현재 사용처 없음" 문구 없음
- 권고: javadoc 1줄 추가 — `"현재 직접 사용처 없음·향후 Order 외부에서 Snapshot 귀속 검증이 필요한 시점에 활용(보조 메서드)."`
- 비고: 권장 사항·블로커 아님. 흡수 여부는 Claude.ai 판단.

### WARN #2: OrderDataJpaTestBase — @Testcontainers·@Container 어노테이션 미사용
- 위치: [OrderDataJpaTestBase.java:31-45](backend/src/test/java/com/zslab/mall/order/repository/OrderDataJpaTestBase.java#L31-L45)
- 기대: expected-spec.md §9.2 "`@Testcontainers·@Container static MariaDBContainer`·`@DynamicPropertySource`"
- 실제: `@Testcontainers`·`@Container` 미사용. static initializer로 `MARIADB.start()` 직접 호출.
  ```java
  static {
      MARIADB = new MariaDBContainer<>(DockerImageName.parse("mariadb:11.4"));
      MARIADB.start();
  }
  ```
- 기능적 동치 여부: 동치. 두 패턴 모두 JVM당 싱글톤 컨테이너. 테스트 42건 전건 PASS 확인(STEP 15).
- 패턴 선택 근거 추정: abstract 클래스 + @DataJpaTest 조합에서 `@Testcontainers` 어노테이션이 JUnit 5 확장 상속 문제를 일으킬 가능성. static initializer가 더 안정적.
- 권고: 현재 동작 정상·변경 불필요. 단, 향후 순수 testcontainers 패턴으로 전환 시 `@Testcontainers(disabledWithoutDocker=true)` + `static @Container` 어노테이션 적용 검토.
- 비고: 블로커 아님. 흡수 여부는 Claude.ai 판단.

---

## OUT-OF-SCOPE 항목

### OOS #1: §11 R4 — PR 본문 체크리스트
- 내용: Track B 첫 PR 본문에 "equals/hashCode @Include 부착 확인" 1줄 추가
- 소관: PR 생성 시점(push 후)
- 메모: push 시 PR 생성 단계에서 확인. 본 트랙 처리 0.

### OOS #2: §14 §2 기능 Gate — E2E 검증
- 내용: 주문 생성·결제·환불 E2E
- 소관: Track 4(Controller·DTO)·Track 5(Refund)·Track 6(E2E) 이후 측정
- 메모: Track B 범위 외. 본 트랙 처리 0.

---

## 결론

- **push·PR 진입 가능 여부**: **Yes** — FAIL 코드 결함 0건
- **회귀 필요 항목**: **없음** — 코드 수정 불필요
- **명세 보정 필요 항목**: 1건 — expected-spec.md §2.1 검증 포인트 "shippingSnapshot getter 비공개" 자기모순 문구 (FAIL #1·코드는 맞음·명세만 수정)
- **다음 액션 권고**:
  1. Claude.ai → WARN #1 (belongsTo javadoc) 흡수 여부 결정 — 흡수 시 PR에 포함
  2. Claude.ai → WARN #2 (@Testcontainers 패턴) 전환 여부 결정 — 현재 동작 정상이므로 이연 가능
  3. FAIL #1 (expected-spec.md §2.1 문구 보정) → Claude.ai 명세 보정 후 박제
  4. push → PR 생성 → OOS #1(R4 체크리스트 PR 본문 포함) 확인 → PR merge
