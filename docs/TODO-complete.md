# zslab-mall 완료 트랙 아카이브

> 완료된 트랙·PR 기록. 트랙 추적 및 회고용.

## 2026

### ERD (5종 작성)
- 산출물: docs/design/erd/01-user-permission-grade.md·02-seller-settlement.md·03-product-inventory.md·04-order-payment-delivery-claim.md·05-common-code-aggregate.md
- 상태: main 머지 완료

### ENUM-POLICY (PR #3)
- 산출물: docs/design/db-schema-decisions.md v2.3 §1.13 신설
- 내용: 23개 type/status 컬럼 분류 (A 잠금 18 / B Code 참조 3 / D polymorphic 4) + Java enum 정책 4건 (네이밍·패키지·EnumType.STRING·Code 라벨 조인)
- 상태: main 머지 완료

### ENUM-AUDIT
- 산출물: docs/design/report/enum-audit-report.md
- 내용: §1.13 정책과 ERD·기타 설계 문서 간 정합성 정찰 (read-only). 이슈 6건 발굴 (정합성 3·누락 2·모호 1)
- 상태: 후속 ENUM-FIX 트랙으로 연계

### ENUM-FIX
- 내용: ENUM-AUDIT 이슈 6건 일괄 반영 (ISS-01~06). Claim.reason_code B분류 확정 (CLAIM_REASON 그룹)
- 수정 파일: db-schema-decisions.md·erd/04·erd/05
- 상태: main 머지 완료

### ENUM-FIX-REPORT
- 내용: ENUM-AUDIT 산출물(docs/design/report/) main 추적 시작
- 상태: main 머지 완료

### CLAUDE-DEV 룰 명문화 (수동 적용)
- 내용: CLAUDE-DEV.md 확장 (개발환경 → 원칙·허용·금지·작업 사이클·Git 규칙·PROGRESS 규칙 추가). 사용자 수동 편집 적용 — PR 트랙 미경유
- 상태: main 반영 완료
