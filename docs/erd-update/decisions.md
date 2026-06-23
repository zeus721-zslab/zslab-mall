# erd-update 트랙 결정 (구현 PR)

> 소스: docs/erd-update/RECON.md C-1~C-8·M-24~M-28 (2026-06-24 사용자 확정)
> 범위: ERD 5종·README·db-schema-decisions.md 갱신 — 코드·DDL·Flyway 미포함.

---

## E-01. 보류 3건 해소 표현 반영 (C-1·D-09·D-11·D-04)

**결정안**: architecture-baseline 트랙(PR-00~05)에서 확정된 D-09·D-11·D-04를 ERD/README/db-schema의 "DDL 작성 전 결정 보류" 표현에 일괄 반영.

| 결정 | 확정값 | 반영 위치 |
|---|---|---|
| D-09 | quantity_available = 애플리케이션 갱신 | ERD 03 설계메모·README §1·db-schema §4-1 |
| D-11 | AuditLog.diff_json = JSON 타입 | ERD 05 설계메모·README §2·db-schema §4-2 |
| D-04 | OrderItem↔Order 동기화 = 방식 B | ERD 04 설계메모·README §3·db-schema §4-3 |

**Why**: ddl-ready-checklist §7이 이미 "확정 완료"로 인용하는데 ERD/README/db-schema §4는 동일 3건을 여전히 "보류"로 표기 → DDL 진입 직전 리뷰어가 진위 혼동. 일관성 필수.

**Impact**: erd/README.md "DDL 작성 전 결정 보류 항목" 섹션 재작성 + ERD 03/04/05 설계메모 1~2줄 교체 + db-schema §4 섹션 재작성.

**Alternative**: 각 ERD 메모에 "확정 — state-machine §5·ADR-005·D-11 참조"만 두고 본문 중복 회피. 하지만 README/db-schema §4는 반드시 갱신 필요.

---

## E-02. Order.status·OrderItem.item_status 값집합 ERD 노출 (C-2·C-3·D-02·D-03)

**결정안**: ERD 04 설계메모에 Order.status 8값 표·OrderItem.item_status 12값 표 추가. mermaid enum 주석은 "N값 확정·§1.13"으로 간략화(값 인라인 금지·M-25 결정).

| 항목 | 결정 |
|---|---|
| 값집합 표기 위치 | 설계메모 표 (SoT state-machine §3·§4 인용) |
| mermaid 주석 | "Code 참조 (B분류)·8값 확정·§1.13" / "12값 확정·§1.13" |
| "DDL 보류§3" 표현 | 삭제 |

**Why**: mermaid 문자열 인라인은 수십 자 나열로 다이어그램 가독성 저하. 설계메모 표가 SoT-링크(state-machine §3·§4)와 함께 완결성 제공. D-02·D-03은 이미 state-machine.md에 값집합 확정 → ERD는 참조 + 요약표만 추가.

**Impact**: ERD 04 mermaid `item_status "Code 참조 (B분류)·DDL 보류§3"` 표현 교체. 설계메모에 8값·12값 표 각 1개 추가. db-schema §1.13 B#2·B#3 값집합 "(DDL 결정 보류 §3 참조)" → 값 기재.

**Alternative**: state-machine.md §3·§4 참조만 (표 미추가). 최소 변경이지만 ERD 리뷰 시 state-machine까지 이동 필요.

---

## E-03. NotificationLog Infra/Event Processing ERD 메모 표기 (C-5·D-18·M-26)

**결정안**: ERD 05 설계메모에 1줄 추가 — "NotificationLog = Infra/Event Processing (이벤트 소비 기록·aggregate-boundary §2.7·D-18·16 Aggregate + 1 Infra/Event 구조)".

**Why**: D-18이 NotificationLog를 Aggregate에서 Infra/Event Processing으로 재분류했으나 ERD는 일반 엔티티로만 표기 → DDL·Entity 트랙 진입 시 오분류 위험. 설계메모 1줄로 분류 명시.

**Impact**: ERD 05 설계메모에 1줄 추가. db-schema §3 물리 카테고리 미영향(Aggregate 분류 미표기).

**Alternative**: 표기 안 함 — Aggregate 분류 SoT는 aggregate-boundary.md 단일, ERD는 물리 스키마라 무영향. 하지만 ERD 05를 보는 독자가 재분류 사실을 놓칠 수 있다.

---

## E-04. db-schema 동반 갱신 동일 PR (C-1~C-3·C-8·M-27)

**결정안**: db-schema-decisions.md 갱신(§1.13 B#2·B#3 값집합·§4 보류 해소·§2.2 이벤트명·§1.1 aud_)을 ERD/README와 동일 PR에 포함.

**Why**: §1.13 B#2·B#3가 "(DDL 결정 보류 §3 참조)" 상태로 ERD만 고치면 db-schema가 단독 stale 발생 → 리뷰어가 ERD/db-schema 간 다른 상태를 보게 됨. §4 "결정 보류" 3건도 동일.

**Impact**: db-schema-decisions.md 7개 지점 수정 (헤더·§1.1·§1.13 B#2·B#3·§2.2·§4). 별도 PR 없음.

**Alternative**: db-schema 별도 PR 분리. 오버헤드 대비 편익 없음 — ERD↔db-schema는 하나의 의미 단위.

---

## E-05. 헤더 버전 v2.2 → v2.4 일괄 (C-8a·M-28)

**결정안**: ERD 01~05 헤더 `db-schema-decisions.md v2.2` → `v2.4` 일괄 교체. db-schema-decisions.md 타이틀 `— v2.2` → `— v2.4`. v2.3은 ENUM-POLICY 트랙 사용 완료.

**Why**: db-schema changelog는 v2.3까지 존재하는데 ERD 헤더만 v2.2를 참조 → 버전 불일치. 본 트랙 갱신으로 v2.4가 신규 버전이므로 일괄 승급이 정합.

**Impact**: ERD 5종 헤더 1줄 + db-schema 타이틀 1줄 + changelog 1줄 추가. 기능 변경 없음.

**Alternative**: 본 트랙 제외 (후속 정리). 5파일 × 1줄이므로 비용이 낮고 동일 PR에서 처리하는 것이 효율적.

---

## E-06. db-schema 정정 보강 (F-1·F-2)

**결정안**:
- F-1: db-schema §1.13 분류 카테고리 헤더 카운트 `25개 컬럼 = type/status 23 + code 2` → `26개 컬럼 = type/status 24 + code 2`
- F-2: §1.13 B 분류 정의 행 `varchar code 값 매칭` → `varchar code ↔ 매칭` (인코딩 손상 복원 1곳)

**정찰 확인**:
- §1.9 표 헤더 (`미적용`) — 파일 내 이미 정상. 수정 불필요.
- §1.13 B row (`값`) — `↔`로 교체 필요. U+2194 누락된 실손상 1건.
- §2.3 SellerBankAccount 메모 (`암호화`) — 파일 내 이미 정상. 수정 불필요.
- U+FFFD 전수 검색: 0건.

**Why**: F-1 미정정 시 §1.13 본문(B 4건·D 4건·A 18건 = 26개)과 헤더(25개·B 3건 가정)가 단독 stale. F-2 `↔` 누락은 B 분류 정의에서 varchar code와 Code 테이블 매칭 관계 표현 불명확.

**Impact**: db-schema-decisions.md 2지점 수정. 본문 의미 무변경.

**Alternative**: F-2를 별도 트랙 분리. 기각 — 동일 파일·1줄 단위·동일 PR 오버헤드 없음.

---

## 본 트랙 제외 항목

- **C-7 invariant 제약 ERD 표기**: UK/CHECK/FK 후보는 invariants.md·ddl-ready-checklist 단일 SoT 유지. ERD 중복 표기 시 DDL 트랙과 SoT 이중화·드리프트 위험. 본 트랙 미반영.
