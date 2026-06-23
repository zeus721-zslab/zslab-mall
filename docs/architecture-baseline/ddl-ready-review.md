# DDL Ready 골든 리뷰 (ddl-ready-review)

> 소스: ddl-ready-checklist.md §1~§8 전수 점검·architecture-baseline 전건(PR-00~05)·erd-update 트랙(dd3ebb6·E-01~E-06)
> 범위: DDL(Flyway) 작성 직전 골든 리뷰. ERD·db-schema v2.4·architecture-baseline 산출물에 결정이 누락 없이 반영됐는지 전수 점검.
> 성격: 점검·인벤토리 전용(read-only). 신규 결정 도입 없음·실제 DDL은 다음 트랙.

---

## 1. 개요

### 목적
ddl-ready-checklist.md가 "DDL에 반영될 결정"의 단일 점검 리스트라면, 본 문서는 그 리스트의 각 항목이 **현재 산출물에 실제 정합하게 존재하는지** 전수 검증한 결과다. DDL(Flyway) 트랙 진입 통과/보류를 판정한다.

### 소스 트랙 상태
- **architecture-baseline (PR-00~05)**: 머지 완료. decisions.md D-01~D-21 확정·ADR 001/003/005/006·정책 7종(aggregate-boundary·state-machine·domain-events·read-model·audit-policy·deletion-policy·invariants) + inventory-policy(docs/domain) + index-strategy + ddl-ready-checklist 확정.
- **erd-update (dd3ebb6·E-01~E-06)**: 머지 완료. ERD 5종+README·db-schema v2.4 — 보류 3건 해소·값집합 확정·v2.4 일괄·§1.13 카운트 정정·↔ 복원.

### 점검 범위
글로벌 정책(§A)·State Machine(§B)·Inventory(§C)·AuditLog(§D)·public_id prefix(§E)·보류 3건(§F)·인덱스(§G)·Read Model(§H)·⚠ §7 잔존(§I)·진입 조건(§J)·부수 정합(§K).

### 판정 태그
- **[통과]**: 결정이 산출물에 정합 반영·DDL 직접 활용 가능.
- **[보류·사유]**: DDL 진입 전 해소 필요.
- **[관찰·후속]**: DDL 비차단·후속 경미 정리 권고(문서 정합).

---

## 2. 점검 결과 매트릭스

### §A. 글로벌 정책 (체크리스트 §2 · 10항목)

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| A-1 | 내부 PK BIGINT AUTO_INCREMENT (집계 2 복합 PK) | 통과 | db-schema §1.1·index-strategy §3·ERD 전종 bigint id PK |
| A-2 | public_id CHAR(30) ULID+prefix UNIQUE | 통과 | §1.1·ADR-001 §영향·ERD mermaid 12엔티티 `char30`·§1.4 |
| A-3 | 시간 UTC DATETIME(6)·row TZ 없음 | 통과 | §1.2·ERD `datetime6` 전종 |
| A-4 | 금액 BIGINT·DECIMAL 금지 | 통과 | §1.3·ERD `bigint` 금액 컬럼(total_price·amount 등) |
| A-5 | 문자열 길이 표준 | 통과 | §1.4·ERD(email 254·name 50·phone 20·title 200·url 2048·code 50) |
| A-6 | Charset utf8mb4/unicode_ci | 통과 | §1.5 |
| A-7 | 인덱스 명명 pk/fk/uk/ix | 통과 | §1.6·index-strategy §1(확장 없음) |
| A-8 | Audit 컬럼 created/updated_by FK 없음 | 통과 | §1.7 |
| A-9 | 소프트 삭제 컬럼 SOFT 한정 부착 | 통과 | §1.7/§1.8·D-12·deletion-policy §2·ERD SOFT 대상 `deleted_at` |
| A-10 | FK 적용 범위(강결합 한정·polymorphic/집계/created_by/VIEW 제외) | 통과 | §1.9·index-strategy §3 FK |

**소계: 10 통과 / 0 보류 / 0 관찰**

### §B. State Machine 4건 (체크리스트 §3.1 · D-02~D-05·D-16)

| # | 컬럼 | 분류 | 판정 | 4소스 정합 |
|---|---|---|---|---|
| B-1 | Order.status 8값 | B(ORDER_STATUS) | 통과 | §1.13 B#2·state-machine §4·ERD 04 표·D-02 — 8값 일치 |
| B-2 | OrderItem.item_status 12값 | B(ORDER_ITEM_STATUS) | 통과 | §1.13 B#3·state-machine §3·ERD 04 표·D-03 — 12값 일치 |
| B-3 | Payment.status 4·method 4 | A(잠금) | 통과 | §1.13 A#9·A#10·state-machine §1·ERD 04 |
| B-4 | Claim.status 4·type 3·reason_code | A·A·B(CLAIM_REASON) | 통과 | §1.13 A#13·A#14·B#4·state-machine §2·ERD 04 |

> 동기화 규칙(D-04 방식 B)·평가 순서([5]→[6]→[7]→[4]→[3]→[2])는 state-machine §5·ADR-003·ERD 04 설계메모 일치. §F-3 참조.

**소계: 4 통과 / 0 보류 / 0 관찰**

### §C. Inventory 3컬럼 (체크리스트 §3.2 · D-07~D-09·ADR-005)

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| C-1 | on_hand·reserved·available 3컬럼(INT) | 통과 | ERD 03·db-schema §2.4·inventory-policy §1·INV-1~4 |
| C-2 | available 애플리케이션 갱신·DB 트리거 미생성 | 통과 | D-09·ADR-005·inventory-policy §5·ERD 03 메모·db-schema §4-1·INV-2 |
| C-3 | InventoryHistory append-only·change_type 6값·on_hand 변동만 | 통과 | D-08·inventory-policy §6·§1.13 A#8·INV-5(M-11 정합) |

**소계: 3 통과 / 0 보류 / 0 관찰**

### §D. AuditLog diff_json (체크리스트 §3.3 · D-11)

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| D-1 | diff_json JSON 타입(LONGTEXT+CHECK JSON_VALID) | 통과 | D-11·db-schema §4-2·audit-policy §4·ERD 05·AUD-3 |
| D-2 | changed_fields 한정·민감정보 마스킹 | 통과 | audit-policy §4·AUD-2(M-17) |
| D-3 | FK 없음·append-only·target_type polymorphic | 통과 | §1.9·§1.13 D#3·ERD 05·AUD-1 |

**소계: 3 통과 / 0 보류 / 0 관찰**

### §E. public_id prefix 12건 (체크리스트 §3.4 · ADR-001·M-21·M-22)

| 점검 | 판정 | 근거 |
|---|---|---|
| prefix 12종 매핑 전수 일치 | 통과 | db-schema §1.1 부여표(12)·ADR-001 prefix표(12)·ERD README prefix목록(12)·ERD mermaid(usr·slr·prd·var·ord·oit·pay·dlv·clm·rfn·att·aud)·invariants COM-1(12) |
| CHAR(30) 통일(ULID 26+prefix 4) | 통과 | §1.1/§1.4·ADR-001·ERD 5종 mermaid·index-strategy §4.1 방향 |
| AuditLog aud_ 부여(11→12) | 통과 | M-22·PR-04.5 완료·ERD 05 `char30 public_id "prefix: aud_"` |

> 관찰: ddl-ready-checklist §3.4는 "prefix 11건 + AuditLog ⚠ §7-2 해소 후 반영"의 **PR-04 시점 표현 잔존**. 실제 산출물은 12건 정합(R-02).

**소계: 통과 (관찰 1 → R-02)**

### §F. 보류 결정 3건 확정 인용 (체크리스트 §4 · erd-update 해소)

| # | 항목 | 판정 | 근거(전 위치 확정) |
|---|---|---|---|
| F-1 | D-09 quantity_available 앱갱신 | 통과 | ERD 03·README §1·db-schema §4-1·ADR-005·inventory-policy §5 |
| F-2 | D-11 diff_json JSON | 통과 | ERD 05·README §2·db-schema §4-2·audit-policy §4·ADR-006 |
| F-3 | D-04·D-16 방식 B + OrderStatusResolver(Domain Service) | 통과 | ERD 04·README §3·db-schema §4-3·state-machine §5·ADR-003 |

> erd-update 머지로 "DDL 결정 보류" 표현 전수 해소 확인. "보류" 잔존 0건.

**소계: 3 통과 / 0 보류 / 0 관찰**

### §G. 인덱스 (체크리스트 §5 · index-strategy)

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| G-1 | PK·UK·FK·복합 명명 | 통과 | index-strategy §1·§3·db-schema §1.6 일치 |
| G-2 | public_id UNIQUE | 통과 | index-strategy §4.1·ADR-001 |
| G-3 | 소프트 삭제 인덱스(partial 미지원→일반/복합) | 통과 | index-strategy §4.2·ADR-006 §영향·deletion-policy §4 |
| G-4 | polymorphic 복합 (target_type, target_id) | 통과 | index-strategy §4.3(Attachment·AuditLog·NotificationLog) |
| G-5 | 조회 패턴 5분류 | 통과 | index-strategy §2(운영·판매자·구매자·정산·CS) |

> 관찰: index-strategy §4.1 "현재 부여 11건…확정 시 12건"·§6 "PR-04.5 이연" 표현은 PR-04.5 완료 후 잔존(R-02). 전략·패턴 자체는 정합.

**소계: 5 통과 (관찰 1 → R-02)**

### §H. Read Model (체크리스트 §6 · D-10)

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| H-1 | BuyerPurchaseAggregate(E6 핸들러 갱신) | 통과 | read-model §2.1·D-10·db-schema §2.2(E6 PurchaseConfirmed 정합·M-16) |
| H-2 | SellerSalesDaily(복합 PK·배치 갱신) | 통과 | read-model §2.2·index-strategy §5·db-schema §2.8 |
| H-3 | VIEW 4건 DDL/구현 이연 | 통과 | read-model §2.3(vw_seller_sales_monthly·vw_order_admin·vw_seller_dashboard·vw_buyer_grade_history) |

> 관찰: read-model §1 "원천 데이터(17개 Aggregate)"는 D-18(16+1) 반영 전 표현 잔존(R-01). 카탈로그 본문은 정합.

**소계: 3 통과 (관찰 1 → R-01)**

### §I. ⚠ §7 3건 재확인 (PR-04.5 완료)

| # | 항목 | 판정 | 잔존 검색 결과 |
|---|---|---|---|
| I-1 | 7-1 CHAR(30) 통일 | 통과 | db-schema §1.1/§1.4·ADR-001·ERD 5종 — `char26` 잔존 0 |
| I-2 | 7-2 AuditLog aud_ 부여 | 통과 | §1.1 부여표·ADR-001·ERD 05·README — 부여 12 정합 |
| I-3 | 7-3 ADR-006 partial 표현 보정 | 통과 | ADR-006 §영향·index-strategy §4.2 — "미지원→일반/복합" 구체화 완료 |

> ⚠ §7 본문 3건 모두 "✅ 해소 완료". 핵심 산출물(db-schema·ERD·ADR) 잔존 0. 단 checklist §3.4·index-strategy 일부 **부수 문서의 PR-04 시점 표현**은 미반영(R-02·비차단).

**소계: 3 통과 (잔존 0·관찰은 R-02로 분리)**

### §J. 진입 조건 (체크리스트 §8)

| 조건 | 판정 | 근거 |
|---|---|---|
| §2~§6 항목 DDL 반영 가능 인벤토리 | 충족 | §A~§H 전 통과 |
| ⚠ §7 잔존 0건 | 충족 | §I — 핵심 산출물 잔존 0 |
| ERD 갱신(erd-update) 완료 | 충족 | dd3ebb6 머지·ERD 5종+README v2.4 |
| → DDL 생성(Flyway) 진입 | **통과** | 차단 항목 0·관찰 2건(R-01·R-02) 비차단 |

**판정: 통과 (Yes)**

---

## 2-K. §K. 부수 정합 점검 (본 트랙 신규)

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| K-1 | aggregate-boundary 16+1 구조(D-01·D-18) | 통과 | aggregate-boundary §2 헤딩·§2.7 Infra/Event·invariants 커버리지(16+1) |
| K-2 | D-16 OrderStatusResolver Domain Service 명칭 통일 | 통과 | ADR-003 §영향·state-machine §5·decisions D-04(갱신)·ERD 04 설계메모 — Calculator 잔존 0 |
| K-3 | D-19 Aggregate 잠금 표현 | 통과 | aggregate-boundary §1(ADR+decisions 절차만 변경) |
| K-4 | D-20 삭제 정책 SoT 인용 | 통과 | db-schema §1.8 말미 deletion-policy 인용 1줄 |
| K-5 | D-21 invariants 신규·≈62건 | 통과 | invariants.md 존재·도메인별 ≈62(16 Agg 59+Infra 3)+공통 4·Delivery(DLV 3) 보강 |

**소계: 5 통과 (관찰은 R-01에 합류)**

---

## 3. 불확실·관찰 항목 (R-XX)

> 전 항목 DDL **비차단**. 핵심 산출물(db-schema v2.4·ERD·ADR·invariants·ddl-ready-checklist §7 본문)은 정합. 아래는 PR-01~04 시점에 작성돼 후속 결정(D-18·PR-04.5) 반영 전 **표현이 잔존**하는 부수 문서 정합 건. 자율 정정 금지·사용자 확정 시 별도 처리.

### R-01. "17개 Aggregate(Root)" 표현 잔존 (post-D-18)

- **현상**: D-18로 NotificationLog가 Aggregate→Infra/Event 재분류(16 Aggregate + 1)됐으나 아래 문서에 "17개" 표현 잔존.
  - read-model.md §1 "원천 데이터(17개 Aggregate)"
  - domain-events.md 머리말 "aggregate-boundary.md 17개"·§2 "(#)은 §2 Aggregate 번호"
  - deletion-policy.md §1 "17개 Aggregate Root 기준"
  - decisions.md D-12 본문 "17개 Aggregate Root 기준"
- **영향**: 본문 로직·분류는 정합(물리 37 테이블·삭제 분류 무변경·이벤트 번호 #8~#13 유효). NotificationLog는 "소비 주체"로 이름 참조만 → 번호 미인용. **DDL 비차단**.
- **권고**: 후속 경미 정리 시 "16 Aggregate + Infra/Event 1"로 통일. invariants.md(16+1)·aggregate-boundary §2.7가 최신 기준.

### R-02. AuditLog public_id "11건/확정 시/§7-2 해소 후" 표현 잔존 (post-PR-04.5)

- **현상**: PR-04.5에서 AuditLog aud_ 부여(11→12) 완료됐으나 아래에 PR-04 시점 표현 잔존.
  - ddl-ready-checklist.md §3.4 "prefix 11건" + "AuditLog ⚠ §7-2 해소 후 반영"(§7-2 본문은 ✅ 해소 완료와 불일치)
  - index-strategy.md §4.1 "현재 부여 11건…AuditLog 부여 확정 시 12건"·§4.2/§5/§6 "PR-04.5 이연"
- **영향**: 실제 부여 대상은 12건으로 db-schema §1.1·ADR-001·ERD 05/README·invariants COM-1 전부 정합. 표현만 stale. **DDL 비차단**(DDL은 정합 산출물 기준 작성).
- **권고**: 후속 경미 정리 시 "부여 12건(aud_ 포함·확정)"으로 표현 갱신. checklist §3.4↔§7-2 내부 정합도 동시 보정.

---

## 4. 통과 판정

**DDL(Flyway) 트랙 진입: 통과 (Yes)**

- §A~§J·§K **전 항목 통과**, 보류 0건.
- ddl-ready-checklist §8 진입 4조건 모두 충족(인벤토리 확보·⚠ §7 핵심 잔존 0·ERD 갱신 완료).
- R-01·R-02는 부수 문서 표현 정합(documentation lag)으로 **DDL 비차단**. 핵심 DDL 인풋(db-schema v2.4 §1·§1.13·§2·ERD·ADR·invariants)은 정합.

조건부 아님 — 무조건 통과. R-01·R-02 정리는 DDL 트랙과 병행/후속 가능.

---

## 5. 다음 트랙 권고 (DDL/Flyway 진입 시 유의)

1. **체크리스트 실마킹**: ddl-ready-checklist §2~§6 체크박스는 DDL 트랙에서 "DDL에 반영됨" 기준으로 실제 마킹. 본 문서는 인벤토리 정합만 확인.
2. **enum 4층위 잠금 의무**: 신규 type/status는 (1)DB ENUM/CHECK (2)Java backed enum (3)DTO @Pattern/@ValidEnum (4)프론트 constants 4층 동시 적용. A분류 18·B분류 4는 §1.13 값집합 그대로 ENUM·B는 Code 시드 일치(COD-3). D분류 4는 VARCHAR+앱검증(DB CHECK 면제).
3. **VIEW 4건 이연**: vw_seller_sales_monthly·vw_order_admin·vw_seller_dashboard·vw_buyer_grade_history는 DDL/구현 이연(read-model §2.3·index-strategy §5). 테이블 DDL과 분리.
4. **인덱스 명세**: index-strategy는 전략·패턴만. 테이블별 CREATE INDEX·커버링·카디널리티 순서는 DDL 트랙에서 확정. partial index 미지원 → 일반/복합 대체(§4.2).
5. **invariants 제약 매핑**: invariants.md Enforcement Point=DB CHECK/UK/FK 건(INV-1/3/4·GRD-3·CRT-2·PRD-1/2/5·USR-1·AUTH-1/2·SLR-1/5·ORD-4·DLV-1·COD-2·ATT-2·AUD-4 등)을 DDL CHECK/UNIQUE/FK로 직접 구현. Service/Domain Enforcement 건은 Entity/구현 트랙 이연.
6. **public_id**: 부여 12건 CHAR(30) NOT NULL UNIQUE. 애플리케이션 ULID 생성(DB 함수 의존 없음).
7. **R-01·R-02 동반 정리(선택)**: DDL 트랙 진입 전/병행으로 부수 문서 표현 정합 권고. 본 정찰의 TODO 갱신안과 함께 처리 가능.
