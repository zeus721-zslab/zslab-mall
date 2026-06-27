# Track 4 (Order API) — Decisions

> Track 4 진입·진행 중 확정된 결정 박제. 전역 `docs/architecture-baseline/decisions.md`와 별개로 본 트랙 범위 결정을 누적 기록한다. 머지 시점에 D-XX 번호로 전역 결정문에 흡수한다.

## D-37 — 인증 진입 시점: 임시 주입 (β)

**결정**: Track 4 범위 내 Spring Security 도입하지 않는다. `buyer_id`는 임시 주입 패턴(요청 헤더 `X-Buyer-Id` 또는 동등 메커니즘)으로 컨트롤러 진입 시점에 주입하고, `AuditorAwareImpl`이 이를 읽어 `created_by`·`updated_by`에 반영한다. Spring Security 정식 도입은 별도 트랙(Track 4.5 또는 Seller·Admin 인증과 묶어 후속 트랙)으로 이연한다.

**배경**:
- Track 4 본질은 "Order Aggregate 위 첫 buyer-facing HTTP 진입 계층 신설"이지 "인증 시스템 구축"이 아님.
- Spring Security 본 트랙 도입(옵션 α)은 필터·UserDetailsService·테스트 패턴까지 동시 확정해야 해 트랙 규모 대폭 확대.
- 하드코딩(옵션 γ)은 배포 불가 — 운영 누락 위험.

**옵션 비교** (요약):
| 옵션 | 채택 | 사유 |
|---|---|---|
| α. Spring Security 본 트랙 도입 | ✗ | 책임 확장·트랙 분할 필요 |
| **β. 임시 주입 + Security 후속 트랙** | **✓** | 진입 계층 책임에 집중·AuditorAwareImpl 자연 연동 |
| γ. 하드코딩 buyer_id 1 + TODO | ✗ | 배포 불가 |

**합의 사항**:
- 헤더 키: `X-Buyer-Id` (단순 정수 buyer_id 값). 실 인증 도입 시 JWT subject로 자연 치환.
- 임시 주입 메커니즘은 `HandlerMethodArgumentResolver` 또는 `@RequestHeader` 단순 패턴 중 구현 단계에서 결정.
- `AuditorAwareImpl.getCurrentAuditor()` 보강 — `ThreadLocal` 또는 `RequestContextHolder` 경유 권장.
- 인증 미주입 요청 (`X-Buyer-Id` 없음) 처리 정책: 400 Bad Request 응답 (구현 단계에서 전역 예외 핸들러와 함께 확정).

**범위 외 (Track 4.5 또는 후속)**:
- Spring Security 도입·JWT 발급·OAuth2/Login·UserDetailsService 구현.
- Seller·관리자 인증.
- Refresh token·세션 관리.

**관련 결정**: Q1=B(AuditorAwareImpl empty 반환 결정·Track 1 합의).

**상태**: ACTIVE (Track 4 진행 중)
