# zslab-mall 트랙 로드맵

> PROGRESS.md(STEP 단위·로컬) ↔ TODO.md(트랙 단위·커밋 추적) 역할 분리.
> 완료 트랙은 docs/TODO-complete.md로 이동.

## 트랙 흐름 (전체)

    INIT → architecture-baseline → ERD → DDL → Entity → API → 구현

## 현재 트랙
**DDL** — Flyway 마이그레이션 작성 단계

목표: 37개 테이블 DDL 생성·Flyway 마이그레이션 스크립트·골든 리뷰(ddl-ready-checklist §8 운영 체크박스 확인) (V1 기준·V2 withdrawn_seller 별도·D-23)

## 진행 대기 트랙

| 트랙 | 내용 |
|---|---|
| DDL | Flyway 마이그레이션 (ddl-ready-checklist §8 운영 체크박스 6개 확인 후 착수) |
| Order.status 복구 정책 | 자동 보정 배치 유무·운영 정책 — 구현 트랙 진입 전 처리 (CR-2) |
| Refund 수동 보정 정책 | D안 RefundAdjustment 신규 테이블 검토 — PG 콜백 누락·장애 대응. 진입 트리거: PG 연동·실 환불 운영 개시 후 콜백 누락/수동 보정 요청 누적/Settlement 불일치 발견 셋 중 하나 (D-24 후속) |
| "17 Aggregate" 표현 lag 보정 | aggregate-boundary/ddl-ready-checklist 등 문서 일부가 "17 Aggregate"로 표기(정합: 16 Aggregate + 1 Infra/Event=NotificationLog·D-13·D-18). DDL 트랙 범위 외·문서 표현 통일 트랙에서 정정 (DDL 정찰 §10-⑤) |

## 옵션 C 트랙 구조 (Track 1~7·외부 검토 2차·D-25)

> backend-init 머지 후 수직 슬라이스(Entity+Repository+Service+Controller+Test) 실행 단위. 기존 Entity→API→구현 선형 흐름을 대체. Gate 통과(gate-conditions.md §1·§2·§3) = Track 7 진입 허가.

| 트랙 | 내용 |
|---|---|
| Track 1 Base | BaseEntity 5종·UlidIdentifier·AuditorAware·@EnableJpaAuditing·common 설정 |
| Track 2 Order Aggregate | Order·OrderItem·OrderShippingSnapshot Entity·Repository·Service |
| Track 3 Payment Mock | PaymentGateway·MockPaymentGateway·Resolver (PG Strategy 통합) |
| Track 4 Order API | Controller·DTO·@RestController 통합 |
| Track 5 Refund Flow | Refund·Claim Entity·Repository·Service·연동 |
| Track 6 Integration Test | E2E·@SpringBootTest |
| **Gate** | gate-conditions.md §1·§2·§3 전건 통과 |
| Track 7 나머지 Entity 26 | 나머지 38−12=26 Entity 일괄 |

## 보류 (Deferred)

| 항목 | 조건 |
|---|---|
| D-22 비식별화 보강 | Deferred until implementation validation (비식별화 구현 후 결정) |
| RFN-4 신규 | Deferred until Refund Flow 구현 (Track 5 진행 시 결정) |

> 즉시 확정 처리 완료(본 PR): public_id 부여 기준 → invariants.md COM-1 흡수 / audit L1~L4 판정 규칙 → audit-policy.md §8 흡수.

## 트랙 운영 규칙
- 트랙 진입 시 docs/{트랙}/ 폴더 생성 권장 (RECON.md·decisions.md 누적)
- 트랙 완료 시: 본 TODO.md에서 제거 → docs/TODO-complete.md로 이동
- 신규 트랙 등재 시 트랙 흐름 다이어그램 갱신
