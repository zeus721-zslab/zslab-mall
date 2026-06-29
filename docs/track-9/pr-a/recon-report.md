# Track 9 PR-A 정찰 보고서

> 트랙: Track 9 / PR-A (A급)
> 대상: OrderItemStatus.canTransitionTo Claim 진입/완료 매트릭스 + ClaimStatus 전이 보강
> 정찰일: 2026-06-29
> 정찰 방식: MCP read-only (Claude Code)
> 정찰 commit: main HEAD c5f6c79 (Merge pull request #69 docs/track-9-entry)
> 정찰 룰 적용: 파일 전체 read 의무·메서드 목록 전체 확인 의무 (Track 8 PR-A/PR-B 사후 정정 패턴 차단)

---

## 1. 정찰 범위

| # | 파일 | 정찰 깊이 |
|---|---|---|
| 1 | order/enums/OrderItemStatus.java | 전체 read |
| 2 | order/enums/OrderStatus.java | 전체 read |
| 3 | order/entity/OrderItem.java | 전체 read |
| 4 | claim/enums/ClaimStatus.java | 전체 read |
| 5 | claim/enums/ClaimType.java | 전체 read |
| 6 | claim/entity/Claim.java | 전체 read |
| 7 | claim/service/ClaimService.java | 전체 read |
| 8 | order/enums/OrderItemStatusTest.java | 전체 read (존재 확인) |
| 9 | claim/enums/ClaimStatusTest.java | 전체 read (존재 확인) |

SoT 정독: decisions.md D-88 본문·state-machine.md §2·§3·§5·invariants.md §2.10·§2.13·§2.13.1·track-5/expected-spec.md §1.2·track-8/pr-b/recon-report.md §2.3·live-traps.md LT-01~03

---

## 2. 현 상태 박제

### 2.1 OrderItemStatus.canTransitionTo 현 매트릭스

현 구현 (OrderItemStatus.java:39~52):

| from | 허용 to |
|---|---|
| ORDERED | PAID |
| PAID | PREPARING |
| PREPARING | SHIPPING |
| SHIPPING | DELIVERED |
| DELIVERED | CONFIRMED |
| CANCEL_REQUESTED | CANCELLED |
| RETURN_REQUESTED | RETURNED |
| EXCHANGE_REQUESTED | EXCHANGED |
| CONFIRMED·CANCELLED·RETURNED·EXCHANGED | (종결·전이 불가) |

**Claim 진입 전이 (진행 단계 → *_REQUESTED): 전건 미구현** (Track 5 OOS·의도적 이연·expected-spec §1.2·Track 8 PR-B recon §2.3 박제).

**Claim 완료 전이 (CANCEL_REQUESTED→CANCELLED 등): 이미 완전 구현** (Track 2 시점·QB-11 4규칙 포함).

### 2.2 ClaimStatus.canTransitionTo 현 매트릭스

현 구현 (ClaimStatus.java:29~35·Track 5 박제):

| from | 허용 to |
|---|---|
| REQUESTED | APPROVED·REJECTED |
| APPROVED | COMPLETED |
| REJECTED | (종결·전이 불가·CLM-1) |
| COMPLETED | (종결·전이 불가·CLM-1) |

4×4 전 조합 ClaimStatusTest.java(5케이스)로 커버 완료.

**D-88 PR-A scope "ClaimStatus 전이 보강 (REQUESTED → APPROVED·REJECTED)": Track 5 이미 완료 → 정찰 자체 박제·PR-A 추가 작업 없음** (WARN-2 참조).

### 2.3 ClaimType 값 집합

ClaimType.java (Track 5 박제):

| 값 | 설명 |
|---|---|
| CANCEL | 취소: Refund.COMPLETED → Claim.COMPLETED (Track 5 구동 대상) |
| RETURN | 반품: 수거 확인 + Refund.COMPLETED 필요 (Track 11 이연) |
| EXCHANGE | 교환: 수거 확인 + 교환품 발송 완료 필요 (Track 11 이연) |

3값·A분류·A#13·DDL ENUM 정합.

### 2.4 Claim.java 메서드 목록

| 메서드 | 가시성 | 책임 |
|---|---|---|
| static create(orderItemId, type, reasonCode, reasonDetail, requestedBy, requestedAt) | public | 생성·초기 status=REQUESTED |
| markCompleted(LocalDateTime processedAt) | public | APPROVED → COMPLETED (CLM-4)·processedAt 채움 |
| transitionTo(ClaimStatus next) | private | canTransitionTo 가드 후 전이 |

**부재 메서드**: approve()·reject()·request() 전건 부재 → Track 9 PR-B 소관 (D-88 Q2·ClaimService.approve/reject 4메서드 완성). 현 단계 호출자 없음·추가 = 데드 코드 (기조 4).

### 2.5 ClaimService 메서드 목록

| 메서드 | 책임 |
|---|---|
| markCompleted(Long claimId) | APPROVED→COMPLETED·멱등 no-op (이미 COMPLETED 시) |

1건만 보유. request()·approve()·reject() 전건 부재 → Track 9 PR-B 신설 대상.

### 2.6 OrderItem.changeStatus 호출자 박제

grep 결과 (main 소스 트리):

| 호출 위치 | 내용 |
|---|---|
| OrderItem.java:131 | `changeStatus(OrderItemStatus.PAID)` — markPaid() 내부 self-call |
| Order.java:147 | `item.markPaid()` — Order.markPaid() 내부 |

**Claim 관련 외부 호출자 전건 부재**: changeStatus(CANCEL_REQUESTED)·changeStatus(RETURN_REQUESTED) 등 Claim 진입 전이 호출 위치 없음 → Track 9 PR-B (ClaimService.request) 진입 시 호출자와 함께 신설. 현 단계 추가 = 데드 코드 (기조 4·Track 8 PR-B §4.4 패턴 재현).

### 2.7 테스트 커버리지 박제

**OrderItemStatusTest.java** (4케이스):

| 테스트 | 커버 범위 |
|---|---|
| canTransitionTo_coversFullMatrix | 12×12 전 조합·QB-11 오라클(EnumMap 1:1 단일 타깃) 대조 |
| terminalStatuses_haveNoOutgoingTransition | 종결 4값 × 12 = 48 조합 |
| reverseAndSkipTransitions_blocked | 역방향·건너뛰기 표본 4건 |
| hasTwelveValues | DDL 정합 12값 보유 |

현 오라클 구조: `Map<OrderItemStatus, OrderItemStatus>` (1:1 단일 타깃). PR-A에서 PAID/PREPARING/SHIPPING/DELIVERED 다중 타깃 추가 시 `Map<OrderItemStatus, Set<OrderItemStatus>>` 리팩토링 필요 (ClaimStatusTest 패턴 재적용).

**ClaimStatusTest.java** (5케이스): 4×4 전 조합·전이 세부·종결 차단·DDL 정합 커버.

---

## 3. 부재 전이 (PR-A 신설 대상)

### 3.1 OrderItemStatus 진입 매트릭스 — 일반 (D-88 Q5 정찰 자체 박제)

| from | 신설 to | 분류 | 근거 |
|---|---|---|---|
| PAID | CANCEL_REQUESTED | 일반 | D-88 Q5·state-machine §3 Claim(CANCEL).REQUESTED |
| PREPARING | CANCEL_REQUESTED | 일반 | D-88 Q5·state-machine §3 |
| SHIPPING | RETURN_REQUESTED | 일반 | D-88 Q5 "SHIPPING/DELIVERED → RETURN_REQUESTED" |
| DELIVERED | RETURN_REQUESTED | 일반 | D-88 Q5 |
| DELIVERED | EXCHANGE_REQUESTED | 일반 | D-88 Q5 |
| SHIPPING | CANCEL_REQUESTED | 회사 정책 | Q1 의제 — §4 |
| SHIPPING | EXCHANGE_REQUESTED | 회사 정책 | Q3 의제 — §4 (D-88 Q5 일반 미포함) |
| CONFIRMED | RETURN_REQUESTED·EXCHANGE_REQUESTED | 회사 정책 | Q2 의제 |

일반 항목 확정 후 canTransitionTo 케이스 예상:

```
case PAID      → next == PREPARING || next == CANCEL_REQUESTED
case PREPARING → next == SHIPPING  || next == CANCEL_REQUESTED
case SHIPPING  → next == DELIVERED || next == RETURN_REQUESTED  (+ Q1·Q3 정책 결정 후)
case DELIVERED → next == CONFIRMED || next == RETURN_REQUESTED || next == EXCHANGE_REQUESTED
```

### 3.2 ClaimStatus 보강 대상 — Track 5 이미 완료

REQUESTED → APPROVED·REQUESTED → REJECTED 전이: ClaimStatus.canTransitionTo 이미 완전 구현 (§2.2 정합·Track 5 박제).

PR-A에서 ClaimStatus 추가 작업 없음 — D-88 PR-A scope "ClaimStatus 전이 보강" 보정 대상 (WARN-2 해소).

---

## 4. 회사 정책 결정 결과 (2026-06-29·전건 a 채택)

| Q | 의제 | 채택 결과 | 근거 |
|---|---|---|---|
| Q1 | SHIPPING → CANCEL_REQUESTED | **차단** | 출고 후 CANCEL은 RETURN 경로·물류 반송 절차 분리·기조 2·4 |
| Q2 | CONFIRMED → *_REQUESTED | **차단** | CONFIRMED 종결·구매확정 이후 재요청 불가·매트릭스 변경 없음·기조 2·4 |
| Q3 | SHIPPING → EXCHANGE_REQUESTED | **차단** | 교환은 수령 후 절차·DELIVERED 한정·D-88 Q5 일반 매트릭스 정합·기조 2·4 |
| Q4 | canTransitionTo 시그니처 | **단일 인자 유지** | ClaimType 무관·호출자(ClaimService)가 type 결정·책임 분리·기조 1·4 |

---

## 5. WARN

### WARN-1. state-machine §3 ↔ OrderItemStatus.java Claim 진입 전이 정합 [해소 가능·정찰 자체 박제]

**내용**: state-machine §3 표 CANCEL_REQUESTED 진입 조건 "Claim(CANCEL).REQUESTED" — 어느 상태에서 진입인지 §3 다이어그램에 명시 없음. D-88 Q5 일반 매트릭스로 PAID/PREPARING → CANCEL_REQUESTED 확정.

**해소**: D-88 Q5 결정이 §3 진입 조건의 SoT. state-machine §3 다이어그램과 D-88 Q5 일반 매트릭스 간 충돌 없음 — §3는 어떤 상태가 CANCEL_REQUESTED로 가는지를 규정하지 않고 진입 트리거(이벤트)만 기술. PR-A 구현은 D-88 Q5 기준으로 진행.

**상태**: ✓ 정찰 내 해소

### WARN-2. D-88 PR-A scope "ClaimStatus 전이 보강" vs 실측 [해소 가능·정찰 자체 박제]

**내용**: D-88 PR-A scope에 "ClaimStatus 전이 보강 (REQUESTED → APPROVED·REJECTED)"이 포함되어 있으나, ClaimStatus.java canTransitionTo는 Track 5에서 REQUESTED → APPROVED·REJECTED 전이를 이미 완전 구현함.

**해소**: ClaimStatus.java §2.2 실측 박제·ClaimStatusTest.java 4×4 커버 확인. PR-A에서 ClaimStatus 추가 작업 불필요. Track 8 PR-A/PR-B 사후 정정 패턴 반복 — D-88 본문 PR-A 행 정찰 보정 단락 추가 의무.

**상태**: ✓ 정찰 내 해소

---

## 6. 영향 범위 (확정·2026-06-29 구현 완료)

| 파일 | 실측 변경 |
|---|---|
| order/enums/OrderItemStatus.java | canTransitionTo 케이스 4건 수정 (PAID·PREPARING·SHIPPING·DELIVERED) + Javadoc 교체 |
| order/enums/OrderItemStatusTest.java | 오라클 `Map<OrderItemStatus, OrderItemStatus>` → `Map<OrderItemStatus, Set<OrderItemStatus>>` 리팩토링·12값 전건 오라클 등록 |
| docs/architecture-baseline/decisions.md | D-88 Q6 PR 분할 표 직후 정찰 보정 단락 추가 |
| docs/track-9/pr-a/recon-report.md | §4 결정 의제→결과·§6 예상→확정·§8 Q1~Q4 해소 (본 파일) |

영향 0건:
- ClaimStatus.java·ClaimType.java·Claim.java·ClaimService.java
- OrderItem.java·Order.java·OrderStatus.java·OrderStatusResolver.java·OrderService.java
- 다른 SoT 문서 (state-machine.md·invariants.md·live-traps.md)
- DB·DDL (enum 값 집합 변경 없음·신규 값 없음)

---

## 7. 외부 검토

- A급 트랙·외부 검토 선택적
- OrderItemStatus.java 케이스 추가·테스트 오라클 리팩토링 한정
- 외부 검토 생략 권장 — D-88 가드 2 라벨 "PR-A = A급·외부 검토 선택적" 정합

---

## 8. 진입 조건 (체크리스트)

- [x] WARN-1 해소 (✓ state-machine §3 ↔ D-88 Q5 충돌 없음·정찰 자체 해소)
- [x] WARN-2 해소 (✓ ClaimStatus REQUESTED→APPROVED·REJECTED Track 5 완료·PR-A 범위 보정)
- [x] Q1~Q4 결정 라운드 의제 확정 (전건 차단·시그니처 단일 인자 유지·2026-06-29)
- [x] 일반 매트릭스 정찰 자체 박제 완료 (D-88 Q5 기준 5건: PAID/PREPARING→CANCEL_REQUESTED·SHIPPING/DELIVERED→RETURN_REQUESTED·DELIVERED→EXCHANGE_REQUESTED)

---

## 9. 관련 결정·SoT

| 항목 | § |
|---|---|
| D-88 Track 9 진입 결정 | Q3 (OrderItem 동기화 양방)·Q5 (일반 매트릭스 정찰 박제)·Q6 (PR-A 범위)·가드 2 라벨 |
| D-87 Track 8 진입 결정 | PR-B 정찰 보정·Claim 진입 전이 의도된 이연 박제 |
| state-machine.md | §2 Claim.status 전이·§3 OrderItem.item_status 12값·진입 조건 |
| invariants.md | §2.10 ORD-2 (Order.status Resolver 재계산)·§2.13 CLM-1~4 |
| expected-spec.md | §1.2 Claim 요청 API OOS·후속 트랙 이연 |
| track-8/pr-b/recon-report.md | §2.3 Claim 진입 전이 의도된 이연 확인·§2.1.1 사후 정정 |
| live-traps.md | LT-01~03 — 본 PR 처치 의무 없음 (신규 Entity 없음·FOREIGN_KEY_CHECKS 사용 없음·@SQLRestriction 관련 Entity 없음) |

---

## 10. 다음 단계

1. Q1~Q4 결정 라운드 진행 (회사 정책 항목 4건 확정)
2. 확정된 매트릭스 기준으로 구현 PR 진입:
   - OrderItemStatus.java canTransitionTo 케이스 수정
   - OrderItemStatusTest.java 오라클 리팩토링 + 신규 케이스 추가
   - decisions.md D-88 본문 PR-A 정찰 보정 단락 추가
3. 구현 완료 후 Track 9 PR-B 진입 (S급·ClaimService request/approve/reject·BuyerClaimController·DTO·E2E·외부 검토 권장)
