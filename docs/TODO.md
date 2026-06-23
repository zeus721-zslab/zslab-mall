# zslab-mall 트랙 로드맵

> PROGRESS.md(STEP 단위·로컬) ↔ TODO.md(트랙 단위·커밋 추적) 역할 분리.
> 완료 트랙은 docs/TODO-complete.md로 이동.

## 트랙 흐름 (전체)

    INIT → architecture-baseline → ERD → DDL → Entity → API → 구현

## 현재 트랙
**architecture-baseline** — ERD 확정 전 설계 기준선 단계

목표: Aggregate 경계·State Machine·Domain Event·Inventory SoT·Read Model·Audit·삭제 정책 확정

## 활성 PR 시리즈 (architecture-baseline)

| PR | 내용 | 상태 |
|---|---|---|
| PR-00 | baseline-plan.md (결정 1~6 + 배제 확장) | 대기 |
| PR-01 | Aggregate + State Machine | 대기 (PR-00 머지 후) |
| PR-02 | Domain Event + Inventory Policy | 대기 |
| PR-03 | Read Model + Audit Policy + 삭제 정책 | 대기 |
| PR-04 | DDL Ready + 인덱스 설계 | 대기 (ERD 확정 후 진입) |

규칙:
- PR-00 머지 이전에 PR-01 이하 시작 금지
- 단일 PR 묶음 금지 (트랙 분할 원칙)
- 각 PR은 docs/{트랙}/decisions.md 누적
- ADR은 단일 모음 PR이 아니라 각 PR에서 해당 결정 발생 시 동반 생성 (docs/adr/NNN-*.md)

## architecture-baseline 범위 외 (별도 트랙)

| 항목 | 처리 |
|---|---|
| 배치/워커 자리 확보 (비동기 처리) | 구현 단계 트랙에서 별도 등재 |
| API 명세·OpenAPI | API 트랙에서 처리 |

## 진행 대기 트랙 (architecture-baseline 이후)

| 트랙 | 내용 |
|---|---|
| INIT-1 | docker-compose.mall.yml·.env.example·README 갱신 (축소판 — 코드 골격 제외) |
| ERD | architecture-baseline 결과 반영 + ERD 5종 갱신 |
| DDL | Flyway 마이그레이션 (DDL 직전 골든 리뷰 필수) |
| Entity | JPA 엔티티 (DDL 확정 후) |
| API | OpenAPI·Controller·Service (Entity 확정 후) |
| 구현 | 기능 단위 트랙 분할 (배치/워커 포함, 별도 등재) |

## 보류 결정 항목 (DDL 진입 시 확정)

1. Inventory.quantity_available 갱신 방식 (애플리케이션 권장)
2. AuditLog.diff_json 컬럼 타입 (JSON 권장)
3. OrderItem.item_status ↔ Order.status 동기화 규칙 (PR-01 state-machine.md에서 정의 예정)

## 트랙 운영 규칙
- 트랙 진입 시 docs/{트랙}/ 폴더 생성 권장 (RECON.md·decisions.md 누적)
- 트랙 완료 시: 본 TODO.md에서 제거 → docs/TODO-complete.md로 이동
- 신규 트랙 등재 시 트랙 흐름 다이어그램 갱신
