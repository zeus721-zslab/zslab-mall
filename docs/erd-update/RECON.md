# erd-update 1차 정찰 (read-only)

> 소스: glossary.md·architecture-baseline 11종(baseline-plan·aggregate-boundary·state-machine·domain-events·read-model·audit-policy·deletion-policy·invariants·ddl-ready-checklist·index-strategy·RECON)·decisions.md(D-01~D-21)·domain/inventory-policy.md·ADR-001/003/005/006·db-schema-decisions.md·ERD 01~05+README
> 목적: architecture-baseline 확정 결정(PR-00~05·PR-04.5)을 ERD 5종+README+db-schema에 반영하기 위한 정찰. 본 문서는 수집·제안만 — 수정·확정 없음.
> 핵심 발견: ERD/README는 architecture-baseline **이전**에 작성되어, 그 트랙에서 확정된 보류 결정(D-04·D-09·D-11)을 아직 "보류"로 표기. PR-04/04.5는 기계적 정정(char30·aud_·카운트 37)만 반영 — **의미 결정(값집합·동기화·재분류)은 미반영**.

---

## §1. 반영 대상 결정 카탈로그

> 분류: [반영됨] 추가 작업 불요 · [일부] 일부 위치만 반영 · [미반영] 전부 미반영.
> "반영 방식"은 권장 1·대안 1만 제시 — 확정은 구현 PR decisions.md에서.

### C-1. 보류 3건 해소 표현 (D-09·D-11·D-04) — 트랙 핵심

architecture-baseline가 확정한 3건이 ERD/README/db-schema에 여전히 "DDL 작성 전 결정 보류"로 표기됨. ddl-ready-checklist §4는 이미 "확정 완료"로 인용.

| 결정 | 확정값 | 미반영 위치 | 현재 |
|---|---|---|---|
| D-09 | quantity_available = 애플리케이션 갱신 | ERD 03 설계메모·README 보류#1·db-schema §4-1 | [미반영] "보류·추천" |
| D-11 | AuditLog.diff_json = JSON | ERD 05 설계메모·README 보류#2·db-schema §4-2 | [일부] ERD 05 mermaid는 `json` 반영·메모/README는 "보류" |
| D-04 | OrderItem↔Order 동기화 = 방식 B | ERD 04 설계메모·README 보류#3·db-schema §4-3 | [미반영] "보류" |

- **반영 방식 (권장)**: README "DDL 작성 전 결정 보류 항목" 3건을 "✅ 확정"으로 전환(D-09·D-11·D-04·ADR 참조) + ERD 03/04/05 설계메모의 "결정 보류 (README 참조)" 문구를 "확정" 1줄로 교체.
- **대안**: ERD 메모는 "확정 — state-machine §5·ADR-005·D-11 참조"로 참조만 두고 본문 중복 회피(드리프트 최소화).
- **db-schema 동반**: **Y** — db-schema §4 "ERD 진입 시 결정 보류 항목"이 README와 동일 3건을 "보류"로 보유. ERD/README만 고치면 db-schema §4가 단독으로 stale. §4를 "확정(D-09·D-11·D-04 인용)"으로 동반 갱신 필요.

### C-2. Order.status 8값 확정 (D-02·B/ORDER_STATUS)

| 항목 | 내용 |
|---|---|
| 영향 위치 | ERD 04 Order.status mermaid(`enum status "Code 참조 (B분류)·db-schema §1.13"`)·설계메모 / db-schema §1.13 B#2(`(DDL 결정 보류 §3 참조)`) |
| 현재 | [미반영] 8값(PENDING_PAYMENT~PARTIAL_CANCEL) 미기재 |
| 반영 방식 (권장) | ERD 04 설계메모에 8값 표 추가 + mermaid 주석 "8값 확정·§1.13". db-schema §1.13 B#2 값집합 "(DDL 결정 보류)" → 8값 기재 |
| 대안 | 값 SoT는 state-machine.md §4 유지 · ERD/db-schema는 "8값 확정(D-02)" 참조만 |
| db-schema 동반 | **Y** — §1.13 B#2 "(DDL 결정 보류)" 표현이 D-02 확정과 모순 |

### C-3. OrderItem.item_status 12값 확정 (D-03·B/ORDER_ITEM_STATUS)

| 항목 | 내용 |
|---|---|
| 영향 위치 | ERD 04 item_status mermaid(`enum item_status "Code 참조 (B분류)·DDL 보류§3"`)·설계메모 / db-schema §1.13 B#3 |
| 현재 | [미반영] 12값(ORDERED~EXCHANGED) 미기재·"DDL 보류" 표현 |
| 반영 방식 (권장) | C-2와 동일 패턴 — 설계메모 12값 표 + mermaid 주석에서 "DDL 보류§3" 제거 |
| 대안 | state-machine.md §3 참조만 |
| db-schema 동반 | **Y** — §1.13 B#3 동일 |

> Claim.reason_code(B/CLAIM_REASON)는 ERD 04 line 86·ERD 05 line 103에 이미 [반영됨] — "B분류 enum 3건" 중 신규 반영 대상은 Order.status·OrderItem.item_status 2건.

### C-4. OrderStatusResolver·동기화 방식 B (D-04 갱신·D-16)

| 항목 | 내용 |
|---|---|
| 영향 위치 | ERD 04 설계메모("동기화 규칙은 DDL 작성 전 결정 보류") |
| 현재 | [미반영] Resolver·Domain Service·방식 B 미언급 |
| 반영 방식 (권장) | ERD 04 메모 "방식 B 확정·`OrderStatusResolver`(Domain Service)·state-machine §5 참조" 1줄 (C-1과 동일 메모 블록) |
| 대안 | state-machine §5 참조만 — ERD엔 컴포넌트명 미노출(물리 스키마 문서 성격 유지) |
| db-schema 동반 | **N** — Resolver는 architecture-baseline SoT(state-machine·ADR-003)·db-schema §1.12는 canTransition 일반 언급 유지 |

### C-5. NotificationLog Infra/Event 재분류 (D-18·D-01 갱신·16+1)

| 항목 | 내용 |
|---|---|
| 영향 위치 | ERD 05 NotificationLog 표기·설계메모 / README 05행 설명 / db-schema §3 분류 |
| 현재 | [미반영] ERD/README/db-schema는 Aggregate 라벨 자체를 표기하지 않음 — NotificationLog는 일반 엔티티로만 존재 |
| 반영 방식 (권장) | ERD 05 설계메모 1줄 추가 "NotificationLog = Infra/Event Processing(이벤트 소비 기록·aggregate-boundary §2.7·D-18)" |
| 대안 | 표기 추가 안 함 — Aggregate 분류는 aggregate-boundary.md 단일 SoT, ERD는 물리 스키마라 무영향 |
| db-schema 동반 | **N** — §3은 물리 카테고리(코드·공통 5)·Aggregate 분류 미표기 |

### C-6. char30·aud_ prefix (M-21·M-22·PR-04.5)

| 항목 | 내용 |
|---|---|
| 영향 위치 | ERD mermaid 14곳·README prefix 목록·db-schema §1.1/§1.4 |
| 현재 | [반영됨] ✅ — ERD 01~05 char30·ERD 05 `aud_`·README 12 prefix·db-schema §1.1 부여 12건 전부 정합 |
| 반영 방식 | 변경 없음 — 재확인만 |
| db-schema 동반 | N (완료) |

### C-7. invariant 기반 DB 제약 표기 (D-21·invariants.md) — 선택

| 항목 | 내용 |
|---|---|
| 영향 위치 | ERD 5종 mermaid(현재 PK·FK 일부만)·UK/CHECK 미표기 |
| 현재 | [일부] PK/FK는 표기·UNIQUE(email·order_no·option 조합 등)·CHECK(quantity≥0 등)는 미표기 |
| 반영 방식 (권장) | ERD엔 추가하지 않음 — UK/CHECK/FK 후보는 invariants.md·ddl-ready-checklist 단일 SoT 유지(§3 M-24) |
| 대안 | ERD 설계메모에 핵심 UK만 1~2줄(PRD-2 옵션조합·CRT-1·ORD-4 order_no) 보강 |
| db-schema 동반 | N |

### C-8. 경미 드리프트 (선택·정합성 정리)

| # | 위치 | 현상 | db-schema 동반 |
|---|---|---|---|
| a | ERD 01~05 헤더 `> 소스: db-schema-decisions.md v2.2 §...` | db-schema는 v2.3(changelog)·README는 v2.3 인용 → ERD 헤더만 v2.2 | N(ERD만) |
| b | db-schema §2.2 BuyerPurchaseAggregate "Order COMPLETED 이벤트" | Order.status에 COMPLETED 값 없음(D-02)·read-model.md는 E6 PurchaseConfirmed로 정합 | **Y** |
| c | db-schema §1.1 prefix "예" 목록(11건·aud_ 누락) | 부여 표는 12건 정합·"예" 목록만 11건 | **Y(경미)** |
| d | db-schema 헤더 line 1 `— v2.2` | changelog는 v2.3까지 존재 → 타이틀 버전 미승급 | **Y(경미)** |

> C-8은 모두 경미. 본 트랙 포함 여부는 §3 M-27에서 사용자 확정.

---

## §2. 파일별 반영 영향 매트릭스

| 파일 | 추가 | 수정 | 삭제 |
|---|---|---|---|
| erd/01-user-permission-grade.md | — | 헤더 v2.3(C-8a·선택) | — |
| erd/02-seller-settlement.md | — | 헤더 v2.3(C-8a·선택) | — |
| erd/03-product-inventory.md | — | 설계메모 quantity_available "보류"→D-09 확정(C-1)·헤더(선택) | "보류(README 참조)" 문구 |
| erd/04-order-payment-delivery-claim.md | Order.status 8값·item_status 12값 표(C-2·C-3)·Resolver 메모(C-4) | 동기화 "보류" 메모→방식 B 확정·mermaid enum 주석·헤더(선택) | "DDL 보류§3"·"동기화 보류" 문구 |
| erd/05-common-code-aggregate.md | NotificationLog Infra/Event 메모(C-5·선택) | diff_json "보류"→D-11 확정(C-1)·헤더(선택) | "보류(README 참조)" 문구 |
| erd/README.md | — | 보류 3건 → ✅확정(D-09·D-11·D-04)(C-1) | "결정 보류" 표제 표현 |
| design/db-schema-decisions.md | (선택) §2.2 정합 노트 | §1.13 B#2·B#3 값집합(C-2·C-3)·§4 보류 3건 해소(C-1)·§2.2 Order COMPLETED(C-8b)·§1.1 예 aud_(C-8c)·헤더 v2.4(C-8d) | §4 "보류" 표현 |

> db-schema 행: C-1/C-2/C-3은 **동반 필요(Y)** — §1.13·§4가 ERD/README와 동일 보류를 보유해 단독 stale 발생. C-8b~d는 동반 권장(경미). C-4·C-5·C-7은 db-schema **무영향**.

---

## §3. 불확실 항목 (M-XX)

> 베이스라인 RECON은 M-23까지 사용 — 본 트랙은 M-24부터.

| # | 항목 | 발견 위치 | 결정 필요 | 추천 |
|---|---|---|---|---|
| M-24 | DB CHECK/UK/FK 후보를 ERD에 둘지 vs ddl-ready-checklist/invariants 유지 | C-7 | invariant 표기 한계 | **유지** — ERD는 관계·컬럼만·제약 SoT는 invariants.md(드리프트·중복 회피) |
| M-25 | enum 값집합(8값/12값)을 mermaid enum 문자열 인라인 vs 설계메모 표 | C-2·C-3 | 표기 위치 | **설계메모 표** — mermaid enum 문자열 비대화 회피·mermaid엔 "N값 확정·§1.13" 주석만 |
| M-26 | NotificationLog 재분류를 ERD 05에 표기할지 | C-5 | Aggregate 분류의 ERD 노출 여부 | **설계메모 1줄** — 또는 미표기(aggregate-boundary SoT). 사용자 선호 확인 |
| M-27 | db-schema 동반 갱신 범위·동일 PR 여부 | C-1·C-2·C-3·C-8 | PR 경계 | **동일 PR** — C-1~C-3은 정합상 필수 동반·C-8 경미분도 같은 PR에서 일괄(별도 PR 분리는 오버헤드) |
| M-28 | ERD 헤더 "v2.2" 일괄 갱신 포함 여부 | C-8a | 트랙 스코프 | **포함** — 5파일 1줄·동일 트랙에서 정합 일괄. 또는 본 트랙 제외(후속 정리) |

**invariant 표기 한계 명확화 (M-24 부연)**:
- mermaid 코멘트 vs 설계메모 텍스트: **설계메모 텍스트** 권장(핵심 UK 1~2건만·선택). mermaid 코멘트 과밀 회피.
- DB CHECK/UK/FK 후보 위치: **ddl-ready-checklist §2·§5 + invariants.md 유지**. ERD 중복 표기 시 DDL 트랙과 SoT 이중화·드리프트 위험.

---

## §4. PR 사이즈 추정

| 범위 | 파일 | 대략 변경 라인 |
|---|---|---|
| 핵심(C-1~C-4) | ERD 04 | +18~25 (8값·12값 표·Resolver/동기화 메모·"보류" 삭제) |
| 핵심 | ERD 03·05 | 각 +2~4 (보류→확정 1~2줄) |
| 핵심 | README | ±15 (보류 3건 표 → 확정 3건) |
| 동반 | db-schema-decisions.md | +12~18 (§1.13 값집합·§4 해소·§2.2·헤더) |
| 선택 | ERD 01·02·헤더 일괄 | 5파일 × 1줄 |

- **핵심만(ERD 5종+README·db-schema 제외)**: 약 50~70 라인.
- **db-schema 동반 포함**: 약 70~95 라인.
- **일괄 PR 적정성**: 적정 — 단일 트랙·단일 의미(보류 해소+값집합 반영)·파일 7건. 분할 시 ERD↔db-schema §1.13/§4 정합이 PR 경계로 끊겨 리뷰 부담 증가. **단일 PR 권장**.

---

## §5. 진입 조건 점검

- **누락 정독**: 없음 — 지정 17건 + 연관(domain-events·index-strategy 등) 전수 정독 완료.
- **선행 트랙 상태**: architecture-baseline(PR-00~05·PR-04.5) 전건 머지·ddl-ready-checklist §7 ⚠ 3건 해소 완료·§8 "ERD 갱신 → DDL 생성" 흐름 명시. 본 트랙이 그 "ERD 갱신" 단계.
- **차상위(DDL Ready 골든 리뷰) 필요 ERD 조건**: ddl-ready-checklist §2~§6 항목이 ERD/db-schema에 반영되어야 DDL 진입. 현재 미반영 = 본 트랙 C-1~C-3(값집합·보류 해소). **본 트랙 완료 = DDL 골든 리뷰 진입 가능 상태**.
- **트랙 허용 범위**: CLAUDE-DEV.md "ERD·DDL" 트랙 = ERD 갱신 허용·Entity/Service/API 금지. 본 트랙은 .md(ERD·db-schema) 갱신만 → 범위 내. (단 DDL/Flyway는 본 트랙 산출물 아님 — ERD 반영까지.)
- **결정 게이트**: M-24~M-28(특히 M-25 값집합 위치·M-27 db-schema 동반 PR 경계) 사용자 확정 후 구현 PR 진입. 본 정찰 단계에선 decisions.md 미생성(구현 PR 진입 시 생성).

---

> **요약**: 반영 필요 결정 핵심 5건(C-1 보류3·C-2·C-3·C-4·C-5) + 선택 2건(C-7·C-8). 신규 불확실 M-24~M-28(5건). db-schema 동반 갱신 **필요**(C-1~C-3·§1.13/§4 정합). 예상 PR 70~95 라인·단일 PR 적정.
