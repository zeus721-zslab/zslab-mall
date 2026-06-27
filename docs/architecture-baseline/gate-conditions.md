# Gate Conditions — Track A~F 통과 조건

> 외부 검토 2차 반영 (D-25). 옵션 C 트랙 구조의 Gate 통과 측정 가능 조건.
> Gate 통과 = Track 7 (나머지 Entity 일괄 확장) 진입 허가.

## 트랙 구조

- Track 1: Base (BaseEntity 6종·PublicIdEntity 2종·AuditingConfig·AuditorAwareImpl·PublicIdGenerator·common)
- Track 2: Order Aggregate (Entity·Repository·Application Service)
- Track 3: Payment Mock (PaymentGateway interface·MockPaymentGateway·Resolver)
- Track 4: Order API (Controller·DTO·통합)
- Track 5: Refund Flow (Refund Entity·Repository·Service·Claim 연동)
- Track 6: Integration Test
- **Gate**
- Track 7: 나머지 Entity 38 − 12 ≈ 26 일괄

## §1. 구조 Gate

| 조건 | 측정 |
|---|---|
| Entity 수정율 ≤10% | Track 2~5에서 생성된 11 Entity 중 Gate 시점 수정 누적 ≤ 1.1 Entity (1건 허용) |
| | 비고: 분모 11은 정찰 실측 (Track B 3 + Track 3 1 + Track 4 5 + Track 5 2). WithdrawnSeller JPA Entity는 D-23 정합 Track 7+ 소관·분모 제외. |
| FK 재설계 0 | DDL V1·V2의 FK 정의 변경 0건 |
| Aggregate 경계 변경 0 | aggregate-boundary.md §변경 0건 |

## §2. 기능 Gate

| 조건 | 측정 |
|---|---|
| 주문 생성 성공 | E2E 테스트: Order 생성 → OrderItem 다건 → Payment 진입 → 정상 응답 |
| | 비고: User 로그인·Cart 추가 단계는 Spring Security·Cart 도메인 Track 7+ 진입 시 측정 재진입. |
| 결제 성공 | E2E 테스트: Order 생성 → Payment.PENDING → MockPaymentGateway 콜백 → Payment.PAID |
| 환불 성공 | E2E 테스트: Payment.PAID → Claim 생성 → Refund.PENDING → MockPaymentGateway 환불 → Refund.COMPLETED → Claim.COMPLETED |
| 상태 전이 검증 | Payment·Claim·OrderItem·Refund 4 enum canTransition 메서드 100% 분기 테스트 |
| | 비고: OrderStatus는 OrderStatusResolver Domain Service가 OrderItem 상태 집합으로부터 파생 (ORD-2·D-04·D-16)·canTransitionTo 부재가 의도된 설계. |

## §3. 기술 Gate

| 조건 | 측정 |
|---|---|
| Flyway clean+migrate 성공 | docker compose down -v → up → Flyway V1·V2 자동 적용·오류 0 |
| Transaction rollback 검증 | OrderItem 다건 INSERT 중 1건 실패 시 Order 포함 전체 rollback·DB 정합성 검증 |
| API 통합 테스트 | Track 4 Order API·Track 5 Refund Flow API 합산 ≥6 endpoint·@SpringBootTest 통과율 100% |
| | 비고: 임계 ≥6은 정찰 실측 (Track 4·5 합산 6 endpoint·D-42 Buyer 한정 정책 정합). Track 7+ Aggregate 진입에 따른 endpoint 추가 시 임계 재정의. |

## Gate 통과 검증 절차

1. Track 6 (Integration Test) 머지 후
2. 본 문서 §1·§2·§3 전 항목 자체 검증
3. 검증 결과를 RECON.md 또는 별도 gate-passed.md에 누적
4. Track 7 진입 PR에 Gate 통과 보고 첨부 (커밋 메시지 또는 PR 본문)
5. Gate 미통과 항목 발견 시:
   - 구조 Gate 실패: Track 2 회귀
   - 기능 Gate 실패: Track 4·5 회귀
   - 기술 Gate 실패: 해당 Track 회귀

## DDL 잠금 (D-25 정합)

Gate 통과 후:
- DDL 수정 금지·필요 시 V3·V4 신규 마이그레이션
- **예외**: 데이터 손실 없는 변경(코멘트·인덱스 추가·NULL 허용 확장 등)·오타·긴급 수정은 사유 누적 후 V3 신규 허용 (D-25 §4·운영 정합·롤백·재현성 3 조건 충족 시)
- State Machine·Invariant 수정 허용
- V1·V2 본문 직접 수정 절대 금지

---

## §4. Track 운영 정책 (외부 검토 2차 보강)

> 외부 검토자 권고 반영. Track 1~7 진행 시 PR 운영·범위·금지 사항 SoT.
> Q4 등재 위치: gate-conditions.md 통합 확정.

### §4.1 Scope Drift 금지

각 Track은 명시된 범위만 처리. Track 범위 외 Entity·API·Migration 추가 시 별도 Track으로 분리.

예 (Track 2 Order Aggregate):
- 허용: Order·OrderItem·OrderShippingSnapshot Entity·Repository·Service
- 금지: Coupon·Settlement·BuyerProfile 등 Track 범위 외 Entity 신규

### §4.2 PR 크기 (경고선)

절대 기준 아닌 경고선. 실 기준은 리뷰 가능·롤백 가능 여부.

| 항목 | 경고선 | 예외 |
|---|---|---|
| Entity | ≤3 | 응집 도메인(Order Aggregate Order·OrderItem·OrderShippingSnapshot)은 3 가능 |
| API endpoint | ≤2 | Order 생성+조회 같은 페어 PR 가능 |
| Migration | ≤1 | DDL V3 단일 |
| Test | 필수 | Test 없는 PR 금지 |

초과 시 PR 분할 또는 본문에 분할 어려운 사유 명시.

### §4.3 DONE 조건 (트랙별 PR 단위)

각 PR DONE 체크리스트:
- API 동작 (해당 시·E2E 또는 Integration Test 통과)
- 테스트 동시 작성·통과 (테스트 없는 구현 금지)
- invariant 위반 없음 (도메인 규칙 충돌 0)
- Scope Drift 없음 (Track 범위 외 변경 0)
- 추적 가능한 TODO만 남김 (모호 TODO·"나중에" 표현 금지)

### §4.4 금지 목록

본 Track 시리즈 전체 적용:

- 신규 Aggregate 생성 금지 (D-01·D-18 확정 16 + Infra/Event 1 외 추가 금지)
- 양방향 매핑 추가 금지 (Aggregate 간 역방향·Entity 정찰 §5 원칙)
- Cascade ALL 금지 (PERSIST·MERGE만 Aggregate 내부 허용·정찰 §5)
- FetchType.EAGER 금지 (LAZY 일괄·정찰 §5 원칙)
- 테스트 없는 구현 금지 (DONE 조건 정합)
- DDL 직접 수정 금지 (D-25 정합·V3 이상 신규 마이그레이션만)

### §4.5 Entity 변경 정책

1차 구현 후 Entity 변경 규칙:

| 변경 유형 | 정책 |
|---|---|
| 필드 추가 | 허용 |
| 관계 변경 (mappedBy·cascade·nullable·fetch) | 제한·변경 사유 PR 본문 명시 필수 |
| Entity 삭제 | 금지 |

자주 흔들리는 항목 강조: `mappedBy`·`cascade`·`nullable`·`fetch`.

### §4.6 DDL 변경 원인 추적

DDL 수정 발생 시 (D-25 §4 예외 적용):

원인 분류:
- 설계 문제 (정찰·결정 단계 누락)
- 구현 문제 (코드 작성 중 발견)
- 외부 요구 (PG 사양·법규 변경 등)

후속 결정(D-26 이상)에 다음 명시:
- 원인 분류 1건
- 데이터 손실 없음 검증
- 롤백 가능성 검증
- 재현성 검증

원인 분류는 향후 트랙 회귀 분석 자료.
