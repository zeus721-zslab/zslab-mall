# ADR-001: Public ID 전략 — ULID + prefix

- **상태**: 확정 (2026-06-24)
- **맥락**: PR-00 baseline-plan.md §3 "PR-00 ADR화" 명시

---

## 문제

데이터베이스 내부 auto-increment PK를 API 외부에 노출하면 두 가지 문제가 발생한다.
1. **순서 노출**: 경쟁사·악의적 사용자가 리소스 수를 추산할 수 있다.
2. **분산 생성 불가**: 여러 서버/마이크로서비스가 동일 시퀀스를 공유해야 한다.

---

## 결정

모든 Aggregate Root에 `public_id` 컬럼을 추가한다.
- **형식**: `{prefix}_{ULID}` (예: `ord_01HZXXXXXXXXXXXXXXXXXXXXX`)
- **생성**: 애플리케이션 레이어에서 생성 (DB 함수 의존 없음)
- **용도**: API 요청/응답·URL 경로 파라미터에서 사용
- **내부 PK**: DB 조인·FK 참조는 기존 auto-increment PK 유지

**Prefix 목록**:

| Aggregate Root | prefix |
|---|---|
| User | `usr_` |
| Order | `ord_` |
| OrderItem | `oit_` |
| Product | `prd_` |
| ProductVariant | `var_` |
| Seller | `slr_` |
| Payment | `pay_` |
| Delivery | `dlv_` |
| Claim | `clm_` |
| Refund | `rfn_` |
| Attachment | `att_` |

---

## 이유

**ULID 선택 근거**:
- 시간 정렬 가능 (UUID v4 무작위 대비 인덱스 효율 우수)
- 충돌 확률 극소 (80비트 랜덤)
- URL-safe (Base32 인코딩 — 대소문자 구분 없음)

**prefix 선택 근거**:
- API 로그·CS 티켓에서 리소스 종류를 한눈에 식별
- 잘못된 ID 타입 전달 시 조기 오류 감지 가능

---

## 영향

- 모든 Aggregate Root 테이블에 `public_id VARCHAR(30) NOT NULL UNIQUE` 컬럼 추가 필요 (DDL은 PR-04)
- API 레이어는 public_id로만 외부 노출; 내부 PK는 서비스 레이어에서 조회
- public_id 조회용 인덱스 필요 (`CREATE INDEX ON table(public_id)`)

---

## 대안

| 대안 | 기각 이유 |
|---|---|
| UUID v4 | 무작위성으로 B-Tree 인덱스 단편화 심화 |
| Snowflake ID | 별도 ID 생성 서버 운영 부담 |
| 내부 PK 직접 노출 | 리소스 수 추산·순서 예측 보안 위험 |
| ULID without prefix | 로그·CS에서 리소스 종류 즉시 식별 불가 |
