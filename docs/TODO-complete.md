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

### architecture-baseline (PR-00~05 + PR-04.5)
- 산출물: docs/architecture-baseline/ 11종 (baseline-plan·RECON·decisions·aggregate-boundary·state-machine·domain-events·inventory-policy·read-model·audit-policy·deletion-policy·invariants) + docs/adr/001/003/005/006
- 내용: D-01~D-21 결정·Aggregate 16+1 경계·State Machine 4건·Domain Event E1~E10·Inventory SoT·Read Model 2건·Audit Policy·삭제 정책 SOFT/HARD/ARCHIVE·⚠ 3건 정정(CHAR(30)·aud_·ADR-006)
- 상태: main 머지 완료 (PR-00~04·PR-04.5·PR-05)

### erd-update (커밋 dd3ebb6)
- 산출물: docs/erd-update/RECON.md·decisions.md (E-01~E-06) + docs/design/erd/01~05·README + docs/design/db-schema-decisions.md v2.4
- 내용: architecture-baseline 확정 결정(보류 3건 해소·값집합 ERD 노출·NotificationLog Infra/Event Processing 표기·db-schema 동반 갱신·헤더 v2.4 일괄·§1.13 카운트 정정 26개·↔ 복원)
- 상태: main 머지 완료

### ddl-ready-review (정찰 1파일)
- 산출물: docs/architecture-baseline/ddl-ready-review.md (신규 1파일·결정 도입 0건)
- 내용: ddl-ready-checklist §1~§8 전수 점검·§A~§K 전 통과·R-01·R-02 문서 표현 lag 2건(DDL 비차단)·통과 판정 Yes
- 상태: main 반영 완료

### pre-ddl-cleanup-2 (4a619e9)
- 산출물: docs/architecture-baseline/decisions.md (D-22)·invariants.md·db-schema-decisions.md
- 내용: D-22 비식별화 후 재가입·재등록 정책 (email·business_no NULL 후 허용)·USR-1·SLR-1 재가입 정책 명시·R-01/R-02 해소·CR-3 A-1 정책
- 상태: main 머지 완료

### ddl-ready-gate (5f684d8)
- 산출물: docs/architecture-baseline/ddl-ready-checklist.md §8 6건 체크
- 내용: §8 운영 체크박스 6건 마킹·D-22 반영 문구 보정·클린패스 판정
- 상태: main 머지 완료

### DDL V1 (959c819)
- 산출물: backend/src/main/resources/db/migration/V1__init.sql (37 테이블)
- 내용: 37 테이블 V1__init.sql 작성·검증 10건 전건 통과
- 상태: main 머지 완료

### gitignore 복구 (abbb2e3)
- 산출물: .gitignore
- 내용: .env.local·.env.*.local 무시 규칙 재추가
- 상태: main 머지 완료

### schema-sync (4d0f3e8)
- 산출물: docs/design/db-schema-decisions.md·invariants.md
- 내용: business_no NN→NULL 마스터 문서 동기화
- 상태: main 머지 완료

### seller-anonymization (81f63a1)
- 산출물: decisions.md (D-23)·V2__seller_anonymization.sql·WithdrawnSeller 신설
- 내용: D-23 확정 (WithdrawnSeller 신설·SoT=Seller·Snapshot Metadata=WithdrawnSeller)·Seller.status state-machine 전이 정의·9 결정 일괄
- 상태: main 머지 완료

### propagation-38 (c2766ef)
- 산출물: 마스터 문서 38 테이블 정합 일괄
- 내용: 37→38 propagation (3계층 분리: 현재총계·V1스코프·이력)
- 상태: main 머지 완료

### refund-state-machine (1cdce59)
- 산출물: state-machine.md §Refund·decisions.md (D-24)
- 내용: D-24 Refund.status 전이 (PG 콜백 전용·새 행 재시도·케이스별 분기)
- 상태: main 머지 완료

### backend-init (0cc75dc)
- 산출물: backend/ Spring Boot 골격·Flyway SQL 이동·build.gradle.kts (11 의존성)
- 내용: Spring Boot 3.4.1·Java 21·Gradle Kotlin DSL 골격
- 상태: main 머지 완료

### external-review-integration (f421370)
- 산출물: decisions.md (D-25)·invariants.md (SLR-7)·gate-conditions.md §1~§3·audit-policy.md L1~L4·COM-1 보강
- 내용: 외부 검토 2차 반영 (Gate 후 DDL 잠금·예외 4항목·Seller SoT·옵션 C 트랙 구조·audit 판정 규칙)
- 상태: main 머지 완료

### track-operation-policy (c7b9f13)
- 산출물: gate-conditions.md §4 Track 운영 정책
- 내용: Scope Drift·PR 크기·DONE·금지 6건·Entity 변경·DDL 원인 추적
- 상태: main 머지 완료

### settlement-state-machine (907879e)
- 산출물: state-machine.md §9 Settlement.status
- 내용: §9 Settlement.status 등재 (PENDING→CONFIRMED→PAID 불가역)·§6 외부 이연 Settlement 제거
- 상태: main 머지 완료

### track-a-base-infra (32ed69e)
- 산출물: backend/src/main/java/com/zslab/mall/common/ (entity 8·config 2·util 1·11 파일·+448 lines)
- 내용: BaseEntity 6종 (FullAuditable·SoftDeletable·CreatedOnly·Mapping·Seed·Aggregate)·PublicIdEntity 2종 (PublicIdFullAuditable·PublicIdSoftDeletable)·AuditingConfig·AuditorAwareImpl·PublicIdGenerator. Q1~Q9 결정 정합 (B·B·A·A·A·A·A·C·X). docker gradle:8-jdk21 BUILD SUCCESSFUL·경고 0건
- 상태: main 머지 완료

### docker-mall-stack (dd9cd7f·PR 커밋 399419a)
- 산출물: backend/Dockerfile·Dockerfile.dev·.dockerignore·gradlew·gradle/wrapper/*·docker-compose.mall.yml·application.yml·application-local.yml·application-prod.yml·.env.example (신규 8·수정 4·12 파일·+427/−18)
- 내용: 로컬·운영 공용 Docker 스택·Gradle wrapper (8.14.5)·Spring profile 분리 (local·prod). DB_HOST 컨테이너명 박제·IP 박제 없음·container_name 환경 무관 동일·ports 외부 노출 없음 (gateway_nginx 프록시 전제). 검증 5/5 PASS·Flyway V1·V2 38 테이블 적용·actuator health UP·HHH90000025 silence (dialect 자동 감지)
- 상태: main 머지 완료

### chore/gitignore-hardening (247d9e4)
- 산출물: .gitignore (+9/-1)
- 내용: 루트 .gitignore에 OS 부산물 (Thumbs.db·.DS_Store·desktop.ini)·Spring Initializr 부산물 (HELP.md) 안전망 추가
- 상태: main 머지 완료
