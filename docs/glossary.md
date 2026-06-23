# Glossary (DDD·CQRS·이벤트 용어 풀이)

> 본 프로젝트에서 사용된 핵심 용어의 한국어 풀이.
> 출처 문서를 함께 표기해 의문 발생 시 즉시 참조 가능.

## 1. Domain-Driven Design (DDD)

### Aggregate
도메인 객체들을 묶은 단위. 트랜잭션 일관성 경계와 도메인 불변식의 주체.
본 프로젝트는 16개 Aggregate + Infra/Event Processing 1건.
- 출처: docs/architecture-baseline/aggregate-boundary.md · decisions.md D-01

### Aggregate Root
Aggregate의 진입점. 외부에서 Aggregate 내부 엔티티에 직접 접근 금지·Root를 통해서만 변경 가능.
- 예: Order Aggregate의 Root는 Order. OrderItem·OrderShippingSnapshot은 Order를 통해서만 변경.

### Bounded Context
도메인 모델의 경계. 같은 용어라도 다른 컨텍스트에서 다른 의미를 가질 수 있음.
본 프로젝트는 멀티벤더 마켓플레이스로 단일 Bounded Context.

### Domain Service
단일 Aggregate 내부의 비즈니스 로직·파생 계산을 담당하는 서비스.
- 예: OrderStatusResolver — Order Aggregate 내부에서 OrderItem 상태 집계 → Order.status 결정
- 출처: docs/adr/003-order-model.md · state-machine.md §5

### Application Service
여러 Aggregate를 오케스트레이션하는 서비스. 외부 시스템·이벤트·트랜잭션 조율.
- 예: 주문 생성 시 Order + Inventory(예약) 동기 트랜잭션 조율
- 출처: docs/architecture-baseline/aggregate-boundary.md §1

### Invariant (불변조건)
도메인 규칙 중 절대 깨지면 안 되는 조건. 상태 상위 개념·위반 시 시스템 정합성 붕괴.
- 예: Inventory.quantity_available ≥ 0 (oversell 방지) · Refund 총액 ≤ Payment.amount (과환불 차단)
- 출처: docs/architecture-baseline/invariants.md

### Enforcement Point
Invariant를 강제하는 위치. 가장 낮은 레이어(DB CHECK/UK/FK) 우선, 불가능 시 Service/Domain.
- 예: quantity_available ≥ 0 → DB CHECK / Order는 OrderItem 최소 1개 → Service

## 2. CQRS / Read Model

### Source of Truth (SoT)
단일 진실 공급원. 한 데이터의 권위 있는 원천.
- 예: 재고 SoT = Inventory 테이블 (Product·ProductVariant에 재고 컬럼 없음)
- 출처: docs/domain/inventory-policy.md · decisions.md D-07

### Write Model
도메인 변경 트랜잭션의 주체. Aggregate 일관성 보장.
- 예: Order·OrderItem·Inventory

### Read Model
조회 최적화·집계용 파생 데이터. Write Model로부터 재생성 가능.
- 예: BuyerPurchaseAggregate(이벤트 핸들러 갱신)·SellerSalesDaily(배치 갱신)·VIEW 4건
- 출처: docs/architecture-baseline/read-model.md · decisions.md D-10

### CQRS (Command Query Responsibility Segregation)
쓰기(Command)와 읽기(Query) 모델을 분리하는 패턴.
본 프로젝트는 Write/Read Model 논리 분리·Read DB 물리 분리는 트래픽 증가 시 재검토.

## 3. 이벤트·동시성

### Domain Event
Aggregate 트랜잭션 경계를 넘는 상태 변경을 표현하는 이벤트.
본 프로젝트는 10건 (E1 OrderPlaced ~ E10 InventoryAdjusted).
- 출처: docs/architecture-baseline/domain-events.md · decisions.md D-06

### 멱등성 (Idempotency)
같은 요청을 여러 번 처리해도 결과가 같은 성질.
- 예: PG 콜백 중복 수신 시 pg_tid 멱등성 키 + OrderItem.item_status 가드로 재차감 방지

### 동기 이벤트
같은 트랜잭션 내에서 처리되는 이벤트. 일관성 우선.
- 예: OrderPlaced → Inventory 예약 (동일 트랜잭션·oversell 방지)

### 비동기 이벤트
별도 트랜잭션에서 처리되는 이벤트. 결합도 완화·재시도 가능.
- 예: PurchaseConfirmed → Settlement·Read Model 갱신 (비동기·지수 백오프·DLQ)

### DLQ (Dead Letter Queue)
N회 재시도 후에도 실패한 메시지를 보관하는 큐. 운영자 수동 처리 대상.

## 4. 트랙 운영 (본 프로젝트 워크플로우)

### ADR (Architecture Decision Record)
아키텍처 결정 기록. Status·Context·Decision·Consequences·Alternatives 구조.
본 프로젝트 4건 (ADR-001·003·005·006).
- 출처: docs/adr/

### decisions.md
각 PR에서 확정된 결정을 누적 기록. PR-01~05의 D-01~D-21 결정 누적.
- 출처: docs/architecture-baseline/decisions.md

### RECON.md
정찰(read-only) 결과 누적. 결정·구현 전 근거 수집.
- 출처: docs/architecture-baseline/RECON.md

### baseline-plan.md
트랙의 단일 레퍼런스. 결정·배제·산출물·이연 항목 잠금.
- 출처: docs/architecture-baseline/baseline-plan.md

## 5. 식별자·enum

### public_id
외부 노출용 식별자. ULID 26자 + prefix 4자 = CHAR(30) 고정.
- 부여 대상: 12개 (usr_·slr_·prd_·var_·ord_·oit_·pay_·dlv_·clm_·rfn_·att_·aud_)
- 출처: docs/adr/001-public-id.md · db-schema-decisions.md §1.1

### ULID (Universally Unique Lexicographically Sortable Identifier)
시간 정렬 가능한 분산 ID. UUID v4 대비 B-Tree 인덱스 효율 우수.

### enum 분류 A/B/D
type/status 컬럼 값 집합 관리 정책.
- **A. 잠금**: 시스템 의존 상태값. 값 추가 = Flyway 마이그레이션 필수 (예: Payment.status·Claim.type)
- **B. Code 참조**: A와 동일하나 운영자가 Code 테이블에서 label·정렬·활성 편집 가능 (예: Order.status·Seller.status)
- **D. polymorphic**: 동적 확장값. DB CHECK 미적용·애플리케이션 enum 검증 (예: target_type·reference_type)
- 출처: docs/design/db-schema-decisions.md §1.13

## 6. 삭제 정책

### SOFT DELETE
deleted_at 마킹 후 보존. 복구 가능·이력/분쟁 대응.

### HARD DELETE
물리 삭제. 개인정보·임시 데이터·시스템 마스터.

### ARCHIVE
영구 보존(삭제 불가·법정 보관·상태 관리). 주문·결제·정산·감사 로그.

### 비식별화 (Anonymization)
개인정보를 불가역으로 파기 (email NULL·phone HASH 등). 식별자는 유지(정합성 보존).
- 소프트 삭제 ≠ 비식별화 — 소프트 삭제는 복구 가능, 비식별화는 불가역
- 출처: docs/architecture-baseline/deletion-policy.md · docs/adr/006-soft-delete.md

## 7. 설계 균형 원칙

### 정규화 70 / 조회 최적화 30
쇼핑몰 도메인 균형 권고. 일부러 중복 허용하는 영역: 카테고리·주문·상품검색·통계.
- 예: Order.status는 OrderItem 집계 캐시 (의도된 비정규화)
- 출처: docs/architecture-baseline/baseline-plan.md §7

## 8. 참조 문서

| 카테고리 | 문서 |
|---|---|
| 트랙 룰 | CLAUDE-DEV.md · CLAUDE.md |
| 설계 단일 레퍼런스 | docs/architecture-baseline/baseline-plan.md |
| 결정 누적 | docs/architecture-baseline/decisions.md (D-01~D-21) |
| 도메인 경계 | docs/architecture-baseline/aggregate-boundary.md |
| 상태 전이 | docs/architecture-baseline/state-machine.md |
| 이벤트 | docs/architecture-baseline/domain-events.md |
| 재고 | docs/domain/inventory-policy.md |
| 조회 모델 | docs/architecture-baseline/read-model.md |
| 감사 | docs/architecture-baseline/audit-policy.md |
| 삭제 | docs/architecture-baseline/deletion-policy.md |
| 불변조건 | docs/architecture-baseline/invariants.md |
| DDL 준비 | docs/architecture-baseline/ddl-ready-checklist.md |
| 인덱스 | docs/architecture-baseline/index-strategy.md |
| DB 스키마 | docs/design/db-schema-decisions.md |
| ERD | docs/design/erd/ |
| ADR | docs/adr/ |
| 트랙 로드맵 | docs/TODO.md · docs/TODO-complete.md |
