# Audit Policy (PR-03)

> 소스: decisions.md D-11 [확정 2026-06-24]·baseline-plan.md §9 #2(diff_json 타입 확정)
> 범위: 추적 원칙·추적 액션·diff_json 정책·적용 우선 대상·보존. 인덱싱·파티셔닝·외부 적재(ES)는 PR-04/운영 이연.

---

## 1. 추적 원칙

- **append-only**: AuditLog는 추가만 한다. 수정·삭제 금지(deletion-policy.md ARCHIVE 분류).
- **FK 없음**: actor_user_id·target_id는 논리 참조만. 비식별화 후에도 로그 정합성 유지(db-schema §2.7).
- **운영자·시스템 행위 모두 기록**: actor_user_id는 nullable(시스템 작업 허용)·actor_role 병기.
- **목적 우선순위**: 분쟁·감사 대응이 최우선. 디버깅 로그가 아니라 "누가·언제·무엇을 바꿨는가"의 법적·CS 증거.

---

## 2. 추적 액션

db-schema §1.13 A분류 #18 AuditLog.action 확정값(잠금·확장 시 마이그레이션).

| action | 설명 |
|---|---|
| CREATE | 신규 생성 |
| UPDATE | 수정 |
| DELETE | 삭제(SOFT/HARD 포함) |
| APPROVE | 승인(상품·판매자·정산 등) |
| REJECT | 거부 |
| LOGIN | 로그인 |
| LOGOUT | 로그아웃 |

---

## 3. 적용 우선 대상 테이블

운영자/시스템 행위 추적이 필수인 대상(분쟁·금전·권한 직결).

| 대상 | 추적 사유 |
|---|---|
| Settlement (status·금액) | 정산 분쟁 — 금액·상태 변경 감사 |
| Seller.status | 판매자 정지·해지 운영 행위 |
| Product.status | 상품 승인/거부(APPROVE/REJECT) |
| SellerUser · UserRole · RolePermission | 권한 부여·회수 추적(deletion-policy.md HARD 회수 이력) |
| SellerBankAccount | 정산 계좌 변경(금전 직결) |
| BuyerProfile.grade | 등급 수동 변경(grade_changed_reason 제거 → AuditLog 통일·db-schema §2.1) |

> 위는 **우선 적용** 대상. 전 테이블 일괄 감사가 아니라 분쟁·금전·권한 직결 대상부터 적용한다. 확대 범위는 운영 단계 결정.

---

## 4. diff_json 정책

- **컬럼 타입**: **JSON** 확정(baseline-plan §9 #2). MariaDB에서 JSON = LONGTEXT alias + `CHECK(JSON_VALID(...))`. 함수 질의(JSON_EXTRACT)·유효성 보장.
- **기록 범위**: 변경 필드 한정(M-17). 전체 행 스냅샷이 아니라 바뀐 필드만 기록한다.
- **구조 예**: `{ "changed_fields": ["status", "net_amount"], "before": { "status": "CONFIRMED" }, "after": { "status": "PAID" } }`
- **민감 정보 제외/마스킹**: 비밀번호·결제 토큰·계좌번호(SellerBankAccount.account_number)·주민번호 등은 diff에 평문 기록 금지(마스킹 또는 제외).

> changed_fields 한정 채택 이유: 전체 스냅샷은 저장 비대화·민감정보 노출 위험. 변경 필드만 담아 감사 목적(무엇이 바뀜)을 충족하면서 비용·노출을 최소화한다.

---

## 5. 보존 기간

- 전자상거래법 등 법정 보관 의무 준수(예: 5년).
- AuditLog는 deletion-policy.md ARCHIVE 분류(삭제 불가·영구 보존).
- 구체 보존 기간·만료 폐기·파티셔닝은 운영/PR-04 이연.

---

## 6. 조회 패턴

- **운영자 화면**: target_type·target_id별·기간별 조회(어느 대상에 무슨 변경이 있었는가).
- **CS 분쟁**: actor·기간 기준 행위 추적.
- VIEW 예: vw_buyer_grade_history(AuditLog 기반 등급 변경 이력·read-model.md §2.3).

---

## 7. 외부 이연

- **인덱싱·파티셔닝 전략**: PR-04(index-strategy). 대량 로그의 (target_type, target_id, created_at) 복합 인덱스 등.
- **로그 외부 적재(Elasticsearch 등)**: 운영 단계. 본 PR은 RDB AuditLog까지만 정의.
- **AuditLog 적재 훅(AOP/Interceptor) 구현**: 구현 단계.

---

## 8. Audit 적용 판정 규칙 (L1~L4)

> 외부 검토 2차 반영. ddl/decisions 결정 1 audit 5분류의 판정 기준 명시.

| 레벨 | 기준 | 적용 분류 |
|---|---|---|
| L1 | 법적 보존 (전자상거래법·개인정보보호법·세무 의무) | full / append-only |
| L2 | 복구 필요 (운영 변경 시 원상 복구 필요) | full |
| L3 | 추적 필요 (도메인 변경·감사 추적) | full / append-only / 매핑 / 시드성 |
| L4 | 운영 분석 (통계·집계·BI) | 집계 |

**5분류 → L 매핑**:

- full (29 테이블): L1·L2·L3 — 결제·환불·정산·개인정보·도메인 운영 데이터
- append-only (3 테이블): L1·L3 — 감사·재고 이력·알림 발송 이력
- 매핑 (2 테이블): L3 — 권한 매핑 추적
- 시드성 (2 테이블): L3 — Role·Permission 시드 변경 추적
- 집계 (2 테이블): L4 — Read Model·BI 집계

**판정 절차**:
1. 신규 테이블 도입 시 L1~L4 평가
2. L1 해당 시 full 또는 append-only 채택
3. L2 해당 시 full 채택
4. L3 단독이면 append-only/매핑/시드성 중 성격에 맞는 분류
5. L4 단독이면 집계 분류
