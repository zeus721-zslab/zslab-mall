# architecture-baseline 설계 기준선

> ERD 확정 전 설계 리스크를 정리하고 후속 PR의 작업 범위를 잠그는 단일 레퍼런스.
> 본 문서가 머지된 이후에만 PR-01 이하 트랙 진입 가능.

## 1. 목적

- ERD·DDL 진입 전에 "싸게 바꿀 수 있는 영역" 확정
- Aggregate 경계·State Machine·Domain Event·Inventory SoT·Read Model·Audit 정책 명문화
- 후속 PR의 작업 범위·산출물·금지 영역 사전 합의
- 다음 골든 리뷰 포인트: **DDL 생성 직전**

## 2. 작업 흐름

    현재 md + ERD
      ↓
    문서 보강 (PR-01~03)
      ↓
    ERD 갱신
      ↓
    DDL Ready (PR-04)
      ↓
    DDL 생성
      ↓
    Entity → API → 구현

원칙: **문서가 코드를 만든다. 코드가 문서를 만들지 않는다.**
(= 설계 확정 전 도메인 코딩 금지. 시점 한정 룰)

## 3. PR 시리즈 구조

| PR | 범위 | ADR 동반 |
|---|---|---|
| PR-00 | baseline-plan.md (본 문서) | 001-public-id (기존 결정 ADR화) |
| PR-01 | Aggregate Boundary + State Machine | 003-order-model 등 |
| PR-02 | Domain Event + Inventory Policy | 005-inventory 등 |
| PR-03 | Read Model + Audit Policy + 삭제 정책 | 006-soft-delete 등 |
| PR-04 | DDL Ready (인덱스 포함) | DDL 직전 골든 리뷰 |

규칙:
- PR-00 머지 이전에 PR-01 이하 시작 금지
- 단일 PR 묶음 금지 (트랙 분할 원칙)
- 각 PR은 docs/architecture-baseline/decisions.md 누적
- ADR은 단일 모음 PR이 아니라 각 PR에서 해당 결정 발생 시 동반 생성 (docs/adr/NNN-*.md)

## 4. 확정 결정 6건

### 결정 1 — Order 상태 책임 통합
- 결론: state-machine.md 내부 섹션으로 통합 (별도 문서 생성 금지)
- 근거: 상태 정의와 파생 규칙이 분리되면 드리프트 발생. Order.status 캐시 규칙은 OrderItem 상태 전이의 일부
- 구조: state-machine.md = Order(상태·전이·계산 규칙) + OrderItem(상태·전이·집계 규칙) + Payment + Claim

### 결정 2 — inventory-policy.md 위치
- 결론: docs/domain/inventory-policy.md
- 근거: 재고는 DB 정책이 아니라 도메인 규칙
- 포함: Source Of Truth · 예약 재고 · 차감 시점 · 복구 정책

### 결정 3 — ADR-003 범위 제한
- 결론: Order Aggregate + 상태 책임만
- 제외: Read Model · 조회 최적화
- 목적: "왜 Order.status를 캐시로 두는가" · "왜 OrderItem이 실제 상태인가"

### 결정 4 — ADR-004 (Multi Tenant) 보류
- 결론: 본 트랙 제외
- 근거: §1.10 이미 정책 확정 · ERD 안정화와 직접 관련 낮음
- TODO: DDL 이후 재검토

### 결정 5 — INIT 트랙 선후
- 결론: INIT → architecture-baseline → ERD → DDL
- 원칙: 문서가 ERD를 수정 (코드가 ERD를 끌고 가지 않음)

### 결정 6 — PR 전략
- 결론: PR-00~04 트랙 분할 (단일 PR 묶음 금지)
- 근거: 리뷰 단위 비대화 방지 · 트랙 일관성 git history 보존

## 5. 배제 항목 (본 트랙 작업 금지)

- API 명세 생성 · OpenAPI
- Controller · Service · Repository · DTO 생성
- Entity · JPA 어노테이션
- DDL · Flyway 마이그레이션
- 비즈니스 로직 구현
- 테이블 자동 추가 · 엔티티 삭제
- 기존 정책 임의 변경 (변경 필요 시 근거 명시 + 사용자 확정 필수)

근거: 본 단계에서 위 산출물이 등장하면 설계 고착(sunk cost) 발생. CLAUDE-DEV.md 트랙별 작업 범위 "architecture-baseline" 금지 항목 준수.

## 6. 문서 품질 기준

좋은 문서 = Why + Impact + Alternative
나쁜 문서 = 테이블 추가 + 추상화 증가 + 코드 생성

## 7. 설계 균형 원칙

쇼핑몰 도메인 특성상 **정규화 70 / 조회 최적화 30** 균형 권고.
중복 허용 영역(의도적 비정규화 후보): 카테고리 · 주문 · 상품검색 · 통계.
본 트랙에서는 균형 표시만 — 실제 비정규화 결정은 ERD 단계.

## 8. 산출물 (PR별)

| PR | 산출물 |
|---|---|
| PR-00 | docs/architecture-baseline/baseline-plan.md (본 문서) |
| PR-01 | docs/architecture-baseline/aggregate-boundary.md · state-machine.md |
| PR-02 | docs/architecture-baseline/domain-events.md · docs/domain/inventory-policy.md |
| PR-03 | docs/architecture-baseline/read-model.md · audit-policy.md · deletion-policy.md |
| PR-04 | docs/architecture-baseline/ddl-ready-checklist.md · index-strategy.md |

PR-01~04는 공통으로 docs/architecture-baseline/decisions.md 누적·필요 시 docs/adr/NNN-*.md 동반 생성.

## 9. 보류 결정 항목 (PR 진행 중 확정)

1. Inventory.quantity_available 갱신 방식 (PR-02에서 확정 · 애플리케이션 권장)
2. AuditLog.diff_json 컬럼 타입 (PR-03에서 확정 · JSON 권장)
3. OrderItem.item_status ↔ Order.status 동기화 규칙 (PR-01 state-machine.md에서 정의)

## 10. 본 트랙 외부 이연 항목

- 배치/워커 자리 확보 (비동기 처리) → 구현 단계 트랙
- API 명세 · OpenAPI → API 트랙
- 권한 모델 정리 → permission-uml.md·permission-matrix.md 기존 확정 (재작성 금지)
