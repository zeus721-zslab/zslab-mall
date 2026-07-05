# 멀티벤더 쇼핑몰 백엔드 품질 표준 기준선 v1

> Phase A' 산출물. 특정 코드 무관·운영 가능 백엔드의 품질 검사 축 정의. 이후 실제 코드 대조(Phase B')의 기준선.
> 검사 축 정의이며 구현 강제가 아님. 단일 운영자·MVP·Mock 외부연동 맥락에서 일부 항목은 ⊘ 후보로 사전 표시.

## Phase B' 판정 근거 신뢰도 레벨 (B단계 적용)
- ✅ 구현: 파일 + 테스트 + 실행 확인
- ⚠️ 부분: 파일 확인만
- ❌ 미구현: grep + 경로 탐색 완료
- ⊘ 제외: decisions.md 근거

## 축 1. 비기능 (성능·관측성)
- N+1 쿼리·Pagination 누락·Index 적절성·응답시간·대량데이터 처리
- 관측성 3기둥: 메트릭·로그·트레이스. Actuator health(liveness/readiness 분리)·Micrometer 메트릭
- [⊘ 후보] 분산 트레이싱·OTel — 단일 서비스 MVP엔 로그+메트릭으로 충분, 서비스 증가 시 필요

## 축 2. API 설계 품질
- HTTP method 적절성(GET/PUT/DELETE 멱등·POST 비멱등)·상태코드 일관성·Validation·DTO 분리·REST URI(동사 아닌 명사)
- idempotency: POST 재시도 중복 방지 위해 Idempotency-Key 사용해 중복요청 안전 처리 (보존기간은 구현 정책, 표준 아님)
- Error Response 표준: RFC 9457 Problem Details(application/problem+json · type·title·status·detail·instance). RFC 7807은 obsoleted(2023-07) — 참조는 9457. 최소 하나의 포맷을 전 엔드포인트 일관 적용

## 축 3. DB 설계 품질
- FK 정책·Unique/Check 제약·Nullable 적절성·Index·Cascade·Soft/Hard 삭제 정책·Flyway↔엔티티 정합
- Soft delete와 unique 제약 상호작용 (nullable 삭제컬럼이 uniqueness scope에 포함될 때 주의)

## 축 4. 테스트 완전성
- Aggregate별 Unit/Integration/API/State-transition/실패시나리오/권한 테스트 존재 여부
- ⚠️ PASS ≠ 필요한 테스트 존재 — 유형별 커버리지 존재를 검사

## 축 5. 운영성
- 로그·MDC·감사로그·장애추적·운영자 명령 추적·배치 모니터링
- request_id/trace_id 로그 상관·스택트레이스 클라이언트 노출 금지·메트릭 태그 저카디널리티(상태코드·리전 OK / userID·requestID 부적합)
- Graceful Shutdown: SIGTERM 처리·진행중 요청 마무리·배치 중단 정책 (단일 인스턴스에서도 유효)

## 축 6. 보안
- ① MVP 필수: BOLA/IDOR(객체 접근마다 소유권 검증 — 복잡한 ID는 보조방어일 뿐 접근제어가 필수)·권한상승·입력검증(Validation)·JWT(만료·Refresh)
- ② 배포단계 필수(프론트 착수 시 적용): CORS — 불필요한 것이 아니라 적용 시점이 배포단계인 운영 설정. 브라우저 API 제공 시 반드시 점검
- ③ MVP ⊘: CSRF(JWT Stateless·API Only면 불필요)·Rate Limit(단일 운영자 MVP 후순위)

## 축 7. 이벤트 일관성
- 발행 누락·중복 발행·순환 의존·AFTER_COMMIT(⊘ 아님·유지 항목)·소비처 0 이벤트 발행 금지
- [⊘ 후보] Outbox·DLQ·MQ — 단일 DB·단일 인스턴스·동기 배선 MVP엔 과함

## 축 8. 외부 장애 대응
- Payment timeout·Notification/Delivery 실패·Retry·Dead Letter·보상 처리·Circuit Breaker
- [⊘ 후보] 실 어댑터 장애대응(Circuit Breaker·Retry·DLQ) — Mock 단계. 단 Seam 존재 여부는 반드시 검사

## 축 9. 데이터 라이프사이클
- 삭제/보존 정책·Audit 보존·Settlement 보존·개인정보 삭제
- 감사·정산 보존(법적 대상) vs 개인정보 삭제(삭제 대상) 상충을 명시적으로 정의

## 축 10. 코드 품질
- Aggregate boundary 위반·Layer 역참조·순환 의존·Transaction boundary·Domain Service 남용·Anemic model
- 도메인 개념이 DB 테이블/내부구조 아닌 도메인 반영(URI·필드명 포함)

## 축 11. 구성(Configuration) 관리
- 환경별 설정 분리(dev/test/prod)·비밀정보 외부화(환경변수 등)·프로파일 관리
- 운영 장애 상당수가 코드 아닌 설정 차이에서 발생 → MVP에서도 기본 운영 요건

## 축 12. 시간(Time) 처리
- UTC 저장·Timezone 처리·Clock 주입(테스트 가능성)
- 주문·결제·정산·만료 시각은 시간 오류에 민감 → 테스트 가능성·운영 일관성 위해 기본 시간 정책 필요

## ⊘ 후보 종합 (B'단계 실측 확정)
- 분산 트레이싱·OTel (축1) / Outbox·DLQ·MQ (축7) / 실 어댑터 장애대응 (축8) / CSRF·Rate Limit (축6-③)
- CORS는 ⊘ 아님 — 배포단계 필수 점검(축6-②)
- AFTER_COMMIT은 ⊘ 아님 — 유지 항목(축7)
- 판정 원칙: "표준에 있음" ≠ "결함". 해야 하는데 빠진 것만 결함. B'에서 decisions.md 근거로 ⊘ 확정
