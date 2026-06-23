# zslab-mall 트랙 로드맵

> PROGRESS.md(STEP 단위·로컬) ↔ TODO.md(트랙 단위·커밋 추적) 역할 분리.
> 완료 트랙은 docs/TODO-complete.md로 이동.

## 트랙 흐름 (전체)

    INIT → architecture-baseline → ERD → DDL → Entity → API → 구현

## 현재 트랙
**DDL** — Flyway 마이그레이션 작성 단계

목표: 37개 테이블 DDL 생성·Flyway 마이그레이션 스크립트·골든 리뷰(ddl-ready-checklist §8 운영 체크박스 확인)

## 진행 대기 트랙

| 트랙 | 내용 |
|---|---|
| DDL | Flyway 마이그레이션 (ddl-ready-checklist §8 운영 체크박스 6개 확인 후 착수) |
| state-machine 보강 | Refund.status 상태 전이 정의 — 외부 리뷰 발견·신규 결정 도입 트랙·Entity 트랙 진입 전 처리 |
| Order.status 복구 정책 | 자동 보정 배치 유무·운영 정책 — 구현 트랙 진입 전 처리 (CR-2) |
| Seller 비식별화 흐름 | TERMINATED 후 법정 보관 기간·SellerBankAccount·Settlement 영향 — Entity 트랙 진입 전 처리 |
| "17 Aggregate" 표현 lag 보정 | aggregate-boundary/ddl-ready-checklist 등 문서 일부가 "17 Aggregate"로 표기(정합: 16 Aggregate + 1 Infra/Event=NotificationLog·D-13·D-18). DDL 트랙 범위 외·문서 표현 통일 트랙에서 정정 (DDL 정찰 §10-⑤) |
| Entity | JPA 엔티티 (DDL 확정 후) |
| API | OpenAPI·Controller·Service (Entity 확정 후) |
| 구현 | 기능 단위 트랙 분할 (배치/워커 포함, 별도 등재) |

## 트랙 운영 규칙
- 트랙 진입 시 docs/{트랙}/ 폴더 생성 권장 (RECON.md·decisions.md 누적)
- 트랙 완료 시: 본 TODO.md에서 제거 → docs/TODO-complete.md로 이동
- 신규 트랙 등재 시 트랙 흐름 다이어그램 갱신
