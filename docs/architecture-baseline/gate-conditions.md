# Gate Conditions — Track A~F 통과 조건

> 외부 검토 2차 반영 (D-25). 옵션 C 트랙 구조의 Gate 통과 측정 가능 조건.
> Gate 통과 = Track 7 (나머지 Entity 일괄 확장) 진입 허가.

## 트랙 구조

- Track 1: Base (BaseEntity 5종·UlidIdentifier·AuditorAware·@EnableJpaAuditing·common)
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
| Entity 수정율 ≤10% | Track 2~5에서 생성된 12 Entity 중 Gate 시점 수정 누적 ≤ 1.2 Entity (1건 미만) |
| FK 재설계 0 | DDL V1·V2의 FK 정의 변경 0건 |
| Aggregate 경계 변경 0 | aggregate-boundary.md §변경 0건 |

## §2. 기능 Gate

| 조건 | 측정 |
|---|---|
| 주문 생성 성공 | E2E 테스트: User 로그인 → Cart 추가 → Order 생성 → OrderItem 다건 → 정상 응답 |
| 결제 성공 | E2E 테스트: Order 생성 → Payment.PENDING → MockPaymentGateway 콜백 → Payment.PAID |
| 환불 성공 | E2E 테스트: Payment.PAID → Claim 생성 → Refund.PENDING → MockPaymentGateway 환불 → Refund.COMPLETED → Claim.COMPLETED |
| 상태 전이 검증 | Payment·Claim·OrderItem·Order·Refund 5 enum canTransition 메서드 100% 분기 테스트 |

## §3. 기술 Gate

| 조건 | 측정 |
|---|---|
| Flyway clean+migrate 성공 | docker compose down -v → up → Flyway V1·V2 자동 적용·오류 0 |
| Transaction rollback 검증 | OrderItem 다건 INSERT 중 1건 실패 시 Order 포함 전체 rollback·DB 정합성 검증 |
| API 통합 테스트 | Track 4 Order API·Track 5 Refund Flow API 합산 ≥10 endpoint·@SpringBootTest 통과율 100% |

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
