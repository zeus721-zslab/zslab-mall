# Track B 기대 명세 가이드 (expected-spec.md)

> 목적: Track B (Order Aggregate) 산출물의 기대 구조·인터페이스·검증 포인트 박제. 정찰 단계의 SoT.
> 작성 주체: Claude.ai (실제 코드 미참조·QB·SoT 문서 기준 중립 작성)
> 작성 시점: Track B 작성·발견 #2 흡수 보고 수령 후·정찰 진입 전·외부 검토 2차 라운드 반영 후
> 정합 기준: QB-1~QB-13·gate-conditions §1·§2·§3·§4·invariants ORD-1~5·state-machine §3·§4·§5

---

## 1. 패키지 구조

```
backend/src/main/java/com/zslab/mall/order/
├── entity/
│   ├── Order.java
│   ├── OrderItem.java
│   └── OrderShippingSnapshot.java
├── enums/
│   ├── OrderStatus.java
│   └── OrderItemStatus.java
├── repository/
│   ├── OrderRepository.java
│   ├── OrderItemRepository.java
│   └── OrderShippingSnapshotRepository.java
├── service/
│   ├── OrderService.java          (Application)
│   └── OrderStatusResolver.java   (Domain)
├── command/
│   ├── CreateOrderCommand.java
│   ├── OrderItemCommand.java
│   └── ShippingAddressCommand.java
└── event/
    └── OrderPlaced.java
```

**검증 포인트**:
- 패키지 분리 정합 (entity·enums·repository·service·command·event 6 하위)
- 외부 노출 없는 내부 매핑 없음 (Track 4 Controller·DTO Response 없음)
- order 패키지 외 신규 추가 없음 (Scope Gate 준수)

---

## 2. Entity 3건

### 2.1 Order

**상속**: `AbstractPublicIdFullAuditableEntity` (prefix "ord")

**클래스 어노테이션**:
- `@Entity`·`@Table(name = "`order`")` (예약어 백틱)
- `@Getter`·`@NoArgsConstructor(access = AccessLevel.PROTECTED)`
- `@EqualsAndHashCode`·`@ToString` **재선언 금지** (발견 #1·base 위임)

**필드** (DDL V1 §28 정합):
- `id` Long — `@Id @GeneratedValue(IDENTITY)`
- `buyerId` Long — `@Column(name = "buyer_id", nullable = false)`
- `orderNo` String — `@Column(name = "order_no", nullable = false, length = 50, updatable = false)`
- `status` OrderStatus — `@Enumerated(STRING)`·`@Column(name = "status", nullable = false)`
- `totalPrice` Long — `@Column(name = "total_price", nullable = false)`
- `discountAmount` Long — `@Column(name = "discount_amount", nullable = false)`
- `shippingFee` Long — `@Column(name = "shipping_fee", nullable = false)`
- `paidAt` LocalDateTime — nullable
- `orderedAt` LocalDateTime — nullable
- `items` List<OrderItem> — `@OneToMany(mappedBy = "order", cascade = {PERSIST, MERGE}, fetch = LAZY)`·`@Getter(AccessLevel.NONE)` (직접 노출 금지·읽기 전용 메서드 경유)
- `shippingSnapshot` OrderShippingSnapshot — `@OneToOne(mappedBy = "order", cascade = PERSIST, fetch = LAZY)` (QB-10 A'-1)

**메서드** (Aggregate 메서드·QB-2):
- `static Order create(Long buyerId, String orderNo, Long discountAmount, Long shippingFee)` — 골격 생성·status 초기값 PENDING_PAYMENT·totalPrice 0
- `List<OrderItem> getItems()` — `Collections.unmodifiableList` 반환 (읽기 전용)
- `void addItem(OrderItem item)` — items 추가·item.assignOrder(this)·totalPrice 누적
- `void attachSnapshot(OrderShippingSnapshot snapshot)` — 1:1 연결·snapshot.assignOrder(this)
- `void markPaid(LocalDateTime paidAt)` — 모든 OrderItem.markPaid()·status = PAID·paidAt 갱신 (동기화 규칙 [1]·Resolver 미경유)
- `void applyResolvedStatus(OrderStatus resolvedStatus)` — Resolver 결과 반영 (ORD-2)
- `getPublicIdPrefix()` override — return "ord"

**totalPrice 누적 패턴 전제 (외부 검토 2차 반영)**:
- Track B 범위에서 OrderItem 불변 (수량·금액 변경 없음·itemStatus만 변경) 전제로 누적 패턴 채택
- Track 5 (Refund Flow·부분취소·부분환불·부분반품) 진입 시 OrderItem 불변 전제 재검토·재계산 패턴(recalculateTotal) 도입 검토

**검증 포인트**:
- equals/hashCode·toString 재선언 0 (base publicId 위임)
- items getter는 비공개(`@Getter(AccessLevel.NONE)`) + `getItems()`에서 `unmodifiable` 반환 경유
- shippingSnapshot getter는 공개 (Order → Snapshot 단방향 탐색 허용·QB-10 A'-1 설계 의도)
- 외부 setter 노출 0 (모든 변경은 도메인 메서드 경유)
- totalPrice 초기값 0L·addItem 누적 패턴
- markPaid는 Resolver 미경유·OrderItem.markPaid() 직접 호출
- buyerId·productId 등 외부 참조는 Long 필드 (객체 매핑 X)

### 2.2 OrderItem

**상속**: `AbstractPublicIdFullAuditableEntity` (prefix "oit")

**클래스 어노테이션**:
- `@Entity`·`@Table(name = "order_item")`
- `@Getter`·`@NoArgsConstructor(access = AccessLevel.PROTECTED)`
- `@EqualsAndHashCode`·`@ToString` **재선언 금지** (base 위임)

**필드** (DDL V1 §29 정합):
- `id` Long — `@Id @GeneratedValue(IDENTITY)`
- `order` Order — `@ManyToOne(fetch = LAZY, optional = false)`·`@JoinColumn(name = "order_id", nullable = false, updatable = false)`·`@Getter(AccessLevel.NONE)` (역참조 비공개)
- `productId` Long — `@Column(name = "product_id", nullable = false)`
- `variantId` Long — `@Column(name = "variant_id", nullable = false)`
- `sellerId` Long — `@Column(name = "seller_id", nullable = false)` (멀티벤더·ORD-3)
- `quantity` int — `@Column(name = "quantity", nullable = false)`
- `unitPrice` Long — `@Column(name = "unit_price", nullable = false)`
- `totalPrice` Long — `@Column(name = "total_price", nullable = false)`
- `itemStatus` OrderItemStatus — `@Enumerated(STRING)`·`@Column(name = "item_status", nullable = false)`

**불변 전제 (외부 검토 2차 반영)**:
- Track B 범위 한정 — OrderItem 생성 후 수량·금액·외부 참조 변경 없음·itemStatus 전이만 발생
- Track 5 이후 (부분취소·부분환불·부분반품) 진입 시 불변 전제 재검토

**메서드**:
- `static OrderItem create(productId, variantId, sellerId, quantity, unitPrice, totalPrice)`
  - 필수값 null 가드
  - quantity < 1 가드
  - **ORD-5 검증**: totalPrice = unitPrice × quantity 일치 검증
  - 초기 itemStatus = ORDERED
- `void assignOrder(Order order)` — **package-private** (외부 호출 금지·Order.addItem 전용)
- `void changeStatus(OrderItemStatus next)` — null 가드·canTransitionTo 검증·불법 전이 시 IllegalStateException
- `void markPaid()` — changeStatus(PAID) 위임
- `getPublicIdPrefix()` override — return "oit"

**검증 포인트**:
- order 필드는 `@Getter(AccessLevel.NONE)`로 역참조 getter 미노출
- assignOrder는 package-private (Order.addItem 경유 강제)
- ORD-5 검증 IllegalArgumentException 메시지 명확
- changeStatus는 OrderItemStatus.canTransitionTo 결과 그대로 따름

### 2.3 OrderShippingSnapshot

**상속**: `AbstractFullAuditableEntity` (publicId 없음)

**클래스 어노테이션**:
- `@Entity`·`@Table(name = "order_shipping_snapshot")`
- `@Getter`·`@NoArgsConstructor(access = AccessLevel.PROTECTED)`
- `@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)` (표준 id 엔티티·Q8=C)
- `@ToString(onlyExplicitlyIncluded = true)`

**필드** (DDL V1 §30 정합):
- `id` Long — `@Id @GeneratedValue(IDENTITY)`·`@EqualsAndHashCode.Include`·`@ToString.Include`
- `order` Order — `@OneToOne(fetch = LAZY, optional = false)`·`@JoinColumn(name = "order_id", nullable = false, updatable = false)`·`@Getter(AccessLevel.NONE)` (QB-10 A'-1·역참조 비공개)
- `recipientName` String — VARCHAR(50)
- `recipientPhone` String — VARCHAR(20)
- `zonecode` String — VARCHAR(10)
- `addressRoad` String — VARCHAR(200)
- `addressJibun` String — VARCHAR(200)·nullable
- `addressDetail` String — VARCHAR(200)·nullable
- `deliveryMemo` String — `@Column(columnDefinition = "TEXT")`·nullable

**메서드**:
- `static OrderShippingSnapshot create(recipientName, recipientPhone, zonecode, addressRoad, addressJibun, addressDetail, deliveryMemo)` — FK 미연결 상태로 생성
- `void assignOrder(Order order)` — **package-private** (Order.attachSnapshot 전용)
- `boolean belongsTo(Long orderId)` — 도메인 메서드 (역참조 getter 미노출 대체·QB-10)
  - 외부 검토 2차 반영: 현재 사용처 없음·Order 역참조 getter 미노출을 위한 보조 메서드·향후 활용 시점 도래 전까지 javadoc 명시 유지

**검증 포인트**:
- FK 소유측 (DDL order_id 컬럼 보유) + JPA 매핑 정합
- order 필드 getter 미노출 (도메인 단방향 유지)
- belongsTo 메서드만 노출 (getter 대체)
- assignOrder package-private
- belongsTo javadoc에 "현재 사용처 없음·보조 메서드" 명시 권장

---

## 3. Enum 2건

### 3.1 OrderStatus

**값 집합** (8값·DDL ENUM 정합):
- PENDING_PAYMENT·PAID·PREPARING·SHIPPING·DELIVERED·CONFIRMED·CANCELLED·PARTIAL_CANCEL

**메서드**:
- `canTransitionTo` **미구현** 허용 (ORD-2: Resolver 경유 강제이므로 enum 자체 검증 미필요·프롬프트 §6 "필요 시" 정합)

### 3.2 OrderItemStatus

**값 집합** (12값·DDL ENUM 정합):
- ORDERED·PAID·PREPARING·SHIPPING·DELIVERED·CONFIRMED·CANCEL_REQUESTED·CANCELLED·RETURN_REQUESTED·RETURNED·EXCHANGE_REQUESTED·EXCHANGED

**메서드** (QB-11 매트릭스):
- `boolean canTransitionTo(OrderItemStatus next)`
  - 진행 단계 순방향 인접: ORDERED → PAID → PREPARING → SHIPPING → DELIVERED → CONFIRMED
  - *_REQUESTED → 대응 종결: CANCEL_REQUESTED → CANCELLED·RETURN_REQUESTED → RETURNED·EXCHANGE_REQUESTED → EXCHANGED
  - 종결 4값 (CONFIRMED·CANCELLED·RETURNED·EXCHANGED) → 어떤 전이도 불가 (false)
  - 역방향·건너뛰기 차단
  - **Claim 진입 전이 (진행 → *_REQUESTED) 미포함** — Track 5 이연·javadoc 명시 필수

**검증 포인트**:
- switch 표현식 12값 분기 완전 커버
- 종결 4값 case는 false 반환
- 진행→REQUESTED 전이는 매트릭스에 포함되지 않음·javadoc에 Track 5 이연 명시

---

## 4. Repository 3건

### 4.1 OrderRepository

`extends JpaRepository<Order, Long>`

**메서드**:
- `Optional<Order> findByPublicId(String publicId)`
- `Optional<Order> findByOrderNo(String orderNo)`
- `boolean existsByOrderNo(String orderNo)`

### 4.2 OrderItemRepository

`extends JpaRepository<OrderItem, Long>`

**메서드**:
- `List<OrderItem> findByOrderId(Long orderId)`
- `List<OrderItem> findByOrderIdIn(Collection<Long> orderIds)`

### 4.3 OrderShippingSnapshotRepository

`extends JpaRepository<OrderShippingSnapshot, Long>`

**메서드**:
- `Optional<OrderShippingSnapshot> findByOrderId(Long orderId)`

**검증 포인트** (3건 공통):
- JpaRepository 단일·Custom interface·Querydsl 미도입 (QB-5)
- 메서드 이름 쿼리만·@Query 0
- 외부 ID 기반 조회는 ID Long 타입 (객체 매핑 X)
- buyer 주문 목록 등 Track 4 범위 메서드 0 (Scope Gate)

---

## 5. Domain Service: OrderStatusResolver

**위치**: `com.zslab.mall.order.service.OrderStatusResolver`

**어노테이션**: `@Component`

**메서드**:
- `OrderStatus resolve(List<OrderItemStatus> itemStatuses)`

**평가 순서** (state-machine §5 정합):
1. [5] 모든 CANCELLED → CANCELLED
2. [6] 일부 CANCELLED + 나머지 ∈ {CONFIRMED·RETURNED·EXCHANGED} → PARTIAL_CANCEL
3. [7] 모든 ∈ {CONFIRMED·RETURNED·EXCHANGED} → CONFIRMED
4. [4] 모든 DELIVERED → DELIVERED
5. [3] 최초 SHIPPING → SHIPPING
6. [2] 최초 PREPARING → PREPARING
7. 외 → PAID 기본 (또는 PENDING_PAYMENT — itemStatuses에 PAID 미포함 케이스 검토)

**검증 포인트**:
- 평가 순서 정확 (종결 우선·진행 역순)
- [1] PAID 일괄 전환은 resolve 미경유 (OrderService.markPaid 직접 처리)
- 빈 리스트 가드 (정의 명시 필요)
- 외부 의존성 0 (순수 함수)

---

## 6. Application Service: OrderService

**위치**: `com.zslab.mall.order.service.OrderService`

**어노테이션**: `@Service`·`@Transactional` (메서드 단위·QB-1)

**의존성**:
- OrderRepository
- OrderStatusResolver
- ApplicationEventPublisher

**메서드**:

### 6.1 `Order createOrder(CreateOrderCommand command)`
- ORD-1 가드 (items ≥1)
- order_no 생성 (외부 검토 2차 반영·문서 표현 느슨화):
  - 유일성·불변성·조회 가능성 보장 (필수 요건)
  - 현재 구현: `yyyyMMdd-XXXXXX` 형식 (QB-9·날짜 + ULID 후미 6자·15자)
  - 충돌 처리 디테일 (existsByOrderNo·재시도·예외)은 구현 세부·기대명세 검증 대상 외
  - 향후 추상화 (OrderNumberGenerator interface) 도입 가능·Track 4 이후 결정
- Order.create → addItem 반복 → attachSnapshot → orderRepository.save (cascade PERSIST 활용)
- `applicationEventPublisher.publishEvent(new OrderPlaced(publicId, orderId, occurredAt))`
- return Order

### 6.2 `Order markPaid(Long orderId, LocalDateTime paidAt)`
- 조회·order.markPaid(paidAt) 호출

### 6.3 `Order recalculateStatus(Long orderId)`
- 조회·items의 itemStatuses 추출·resolver.resolve → order.applyResolvedStatus

**검증 포인트**:
- @Transactional 메서드 단위 (클래스 레벨 + 메서드 레벨 혼합 가능)
- Claim 이벤트 핸들러 메서드 미작성 (Track 5)
- order_no 형식 정합 (yyyyMMdd-XXXXXX) 검증·구현 세부 비검증
- OrderPlaced payload 3 필드만·Order 엔티티 통째 전달 0 (QB-13)
- ApplicationEventPublisher 직접 publish 또는 @TransactionalEventListener(AFTER_COMMIT) 둘 중 선택 + 사유 명시

---

## 7. Command/DTO 3건

### 7.1 CreateOrderCommand
**필드**: buyerId·items (List<OrderItemCommand>)·shipping (ShippingAddressCommand)·discountAmount·shippingFee
**형식**: record 권장 (불변)

### 7.2 OrderItemCommand
**필드**: productId·variantId·sellerId·quantity·unitPrice·totalPrice
**형식**: record 권장

### 7.3 ShippingAddressCommand
**필드**: recipientName·recipientPhone·zonecode·addressRoad·addressJibun·addressDetail·deliveryMemo
**형식**: record 권장

**검증 포인트**:
- record 사용 시 불변성 자동 확보
- 검증 로직은 Service에서 수행 (Bean Validation @Valid는 Track 4)
- Controller 노출 DTO 아님 (입력 Command 전용)

---

## 8. Event: OrderPlaced

**위치**: `com.zslab.mall.order.event.OrderPlaced`

**형식**: record

**필드 (QB-13 박제·payload 3 필드 한정)**:
- `String publicId`
- `Long orderId`
- `LocalDateTime occurredAt`

**검증 포인트**:
- Order 엔티티 통째 전달 0
- OrderItem 목록 전달 0
- 금액·buyerId 등 추가 필드 전달 0
- 핸들러 0 (Track 7 이연·Inventory·CartItem·NotificationLog)

---

## 9. Test 산출물

### 9.1 Unit Test

**OrderItemStatusTest** (canTransitionTo 매트릭스 분기):
- 진행 단계 순방향 6 케이스 PASS
- *_REQUESTED → 종결 3 케이스 PASS
- 종결 4값 → 어떤 전이도 false (12 케이스 × 12 = 144 또는 parameterized)
- 역방향·건너뛰기 차단 검증

**OrderStatusTest** (canTransitionTo 미구현 시):
- 미구현 정합 검증 또는 enum 값 집합 검증 (8값)

**OrderStatusResolverTest** (평가 순서):
- [5]~[2] 각 분기 케이스 + 기본 분기 (외)
- 빈 리스트 케이스 (가드 검증)

**OrderTest** (도메인 메서드 + X1):
- addItem: items 추가·totalPrice 누적·assignOrder 호출 검증
- attachSnapshot: 1:1 연결 검증
- markPaid: 모든 OrderItem PAID 전이·status PAID·paidAt 갱신
- applyResolvedStatus: status 갱신
- **X1 (필수)**: publicId 기반 equals/hashCode
  - 동일 publicId → equals true·hashCode 동일
  - 다른 publicId → equals false
  - 양쪽 NULL → onlyExplicitlyIncluded 정합 동작

**OrderItemTest**:
- create ORD-5 검증 (unitPrice × quantity ≠ totalPrice → 예외)
- create quantity < 1 → 예외
- changeStatus 합법/불법 전이
- markPaid

**OrderServiceTest** (Mockito):
- createOrder: ORD-1 가드·order_no 생성·재시도 1회·publishEvent 호출
- markPaid: Repository 조회·order.markPaid 위임
- recalculateStatus: resolver 호출·applyResolvedStatus 호출

### 9.2 @DataJpaTest (testcontainers-mariadb)

**OrderDataJpaTestBase**:
- `@Testcontainers`·`@Container static MariaDBContainer`·`@DynamicPropertySource`
- `@Import(AuditingConfig.class)` (JPA Auditing 활성화·발견 #3 흡수)
- Flyway V1·V2 자동 적용
- 패턴 동치 허용: `@Testcontainers·@Container` 어노테이션 대신 static initializer로 `MARIADB.start()` 직접 호출하는 패턴 허용 (abstract + @DataJpaTest 조합 안정성 확보·기능 동치)

**OrderRepositoryTest**:
- save·findByPublicId·findByOrderNo·existsByOrderNo

**OrderItemRepositoryTest**:
- findByOrderId·Order 저장 시 cascade PERSIST로 OrderItem 함께 영속

**OrderShippingSnapshotRepositoryTest**:
- findByOrderId·Order 저장 시 Snapshot cascade PERSIST 자동 영속

**검증 포인트**:
- @SpringBootTest 확대 0 (QB-12·Track 6 이연)
- testcontainers BOM 사용
- 실제 MariaDB 11.x 컨테이너 부트스트랩
- public_id CHAR(30) 매핑 정합 검증 (발견 #2 해소 후)

---

## 10. 빌드 의존성

**backend/build.gradle.kts testImplementation**:
- `platform("org.testcontainers:testcontainers-bom:<Spring Boot 3.4.1 호환 안정 버전>")`
- `org.testcontainers:junit-jupiter`
- `org.testcontainers:mariadb`

**검증 포인트**:
- BOM 사용·개별 버전 명시 0
- mariadb 모듈 (mysql·postgresql 미사용)
- testImplementation만 (implementation·runtimeOnly 0)

---

## 11. Track A 잔여 4건 흡수

**R1** — AuditorAwareImpl javadoc:
- 다음 2줄 추가 확인:
  - "Current policy: Audit actor is intentionally unresolved before Security integration."
  - "NULL audit values are allowed by Q1 decision."

**X1** — publicId equals/hashCode 검증 테스트:
- OrderTest에 포함 (§9.1 명시)

**X2** — @PrePersist private 유지 확인:
- AbstractPublicIdFullAuditableEntity·AbstractPublicIdSoftDeletableEntity
- 변경 0 확인 (read-only 검증)

**R4** — PR 본문 체크리스트:
- Track B 첫 PR 본문에 "equals/hashCode @Include 부착 확인" 1줄 (PR 생성 시점 확인)

---

## 12. 발견 #2 흡수 (D-26)

**AbstractPublicIdFullAuditableEntity·AbstractPublicIdSoftDeletableEntity publicId 필드**:
- `@JdbcTypeCode(SqlTypes.CHAR)` 어노테이션 추가 (1줄씩·총 2지점)
- import: `org.hibernate.annotations.JdbcTypeCode`·`org.hibernate.type.SqlTypes`

**docs/architecture-baseline/decisions.md**:
- D-26 신규 등재
- 원인 분류: 설계 문제
- 데이터 손실 없음·롤백 가능·재현성 4점 추적 명시

**검증 포인트**:
- @JdbcTypeCode 정확히 2지점만 적용
- 다른 base 클래스 (Soft·CreatedOnly·Mapping·Seed·Aggregate·Full) 영향 없음
- D-26 등재 위치 (D-25 다음)·기존 D-* 패턴 정합

---

## 13. 금지 사항 검증 (gate-conditions §4.4·Scope Gate)

다음 항목이 **0건**이어야 함:
- 신규 Aggregate 생성
- 양방향 매핑 (Snapshot·OrderItem 역참조 getter·setter 노출)
- Cascade ALL
- FetchType.EAGER
- @SpringBootTest 확대 (contextLoads 1건 외)
- DDL 직접 수정 (V1·V2 본문)
- Controller·RestController (Track 4)
- Payment/Refund/Claim Entity (Track 3·5)
- Querydsl·CustomRepository (Track 5 이상)

**Track B 범위 한정 전제 (외부 검토 2차 반영)**:
- OrderItem 수량·금액 변경 메서드 없음 (Track 5 이후 부분취소·부분환불·부분반품 진입 시 재검토)

---

## 14. 전체 검증 (gate-conditions §1·§2·§3 부분 충족)

§1 구조 Gate (Track B 부분):
- Entity 수정율 ≤10% — Track B 자체 수정 0건·Track A 회귀 2 파일은 D-26 정합 (별도 추적)
- FK 재설계 0
- Aggregate 경계 변경 0

§3 기술 Gate:
- Flyway clean+migrate 성공 — testcontainers 실 검증
- API 통합 테스트는 Track 4 이후 측정

§2 기능 Gate:
- 주문 생성·결제·환불 E2E는 Track 4·5·6 이후 측정

---

## 정찰 시 점검 우선순위

1. **발견 항목 #1 적용 정합** (PublicId 엔티티 @EqualsAndHashCode·@ToString 재선언 0)
2. **양방향 매핑 차단** (Snapshot.order·OrderItem.order getter 비공개·assignOrder package-private)
3. **ORD-1~5 invariant 강제 위치 정합**
4. **OrderStatusResolver 평가 순서 정확**
5. **OrderItemStatus.canTransitionTo 매트릭스 정합** (QB-11)
6. **OrderPlaced payload 3 필드 한정**
7. **Scope Gate 위반 0** (Track 3·4·5·6·7 범위 침범 0)
8. **발견 #2 흡수 정합** (@JdbcTypeCode 2지점·D-26 등재)
9. **order_no 형식 정합** (yyyyMMdd-XXXXXX·구현 세부 비검증)
10. **OrderItem 불변 전제 정합** (수량·금액 변경 메서드 0)

---

## 외부 검토 2차 반영 요약

| 항목 | 반영 |
|---|---|
| §2.1 Order totalPrice | "Track B 범위 OrderItem 불변·Track 5 재계산 검토" 단서 추가 |
| §2.2 OrderItem | "Track B 한정 불변 전제·Track 5 재검토" 단서 추가 |
| §2.3 Snapshot.belongsTo | "현재 사용처 없음·보조 메서드" javadoc 명시 권장 |
| §6 orderNo 생성 | 구현 세부 (existsByOrderNo·재시도) 검증 대상 외·문서 표현 느슨화 |
| §13 금지 사항 | OrderItem 불변 전제 추가 |
| §정찰 우선순위 | order_no 형식 정합·OrderItem 불변 전제 검증 항목 추가 |