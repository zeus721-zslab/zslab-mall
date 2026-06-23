# 구매자 등급 UML
 
User 확장형 BuyerProfile + 정책 버전 관리 + 운영 수동 제어 포함.
 
## 엔티티
 
### User
```
User
────────────────
id PK
```
 
### BuyerProfile (User 1:0..1 확장)
```
BuyerProfile
────────────────
user_id PK FK(User.id)
grade_id FK
grade_source         ENUM(AUTO, MANUAL, EVENT)
grade_locked_until   nullable
grade_changed_reason nullable varchar(255)
grade_updated_at
```
 
> 회원 중 구매자가 아닐 수 있어 optional (1:0..1).
 
### BuyerGrade
```
BuyerGrade
────────────────
id PK
code
name
```
 
### GradePolicy (버전 관리)
```
GradePolicy
────────────────
id PK
grade_id FK
min_amount
max_amount
discount_rate
point_rate
effective_from
effective_to
version
is_active
```
 
### BuyerPurchaseAggregate (구매 집계 Read Model)
```
BuyerPurchaseAggregate
────────────────
buyer_id PK FK(User.id)
lifetime_purchase_amount
last_ordered_at
updated_at
```
 
## 관계
 
```
User
 │
 └── 1:0..1 BuyerProfile
              │
              ├── N:1 BuyerGrade
              │
              └── 1:1 BuyerPurchaseAggregate
 
BuyerGrade
 │
 └── 1:N GradePolicy
```
 
## 책임 분리
 
| 테이블 | 책임 |
|--------|------|
| User | 계정 |
| BuyerProfile | 현재 사용자 상태 (등급·소스·잠금) |
| BuyerGrade | 등급 정의 |
| GradePolicy | 등급 정책 (시점·버전) |
| BuyerPurchaseAggregate | 구매 집계 (Read Model) |
 
> `lifetime_purchase_amount`는 Aggregate에만 존재. BuyerProfile은 등급 상태만 보유 → 단일 책임·정합성 보장.
 
## 등급 산정 흐름
 
```
Order (COMPLETED)
   ↓
Event Publish
   ↓
BuyerPurchaseAggregate 갱신 (SUM 누적)
   ↓
GradeEvaluator
   ↓
BuyerProfile.grade_id 갱신
```
 
## 활성 정책 결정 규칙
 
```sql
SELECT *
FROM grade_policy
WHERE grade_id = ?
  AND NOW() BETWEEN effective_from AND effective_to
  AND is_active = true
ORDER BY version DESC
LIMIT 1
```
 
> 버전 교체 시 새 버전이 자동 활성 (예: SILVER v1 min=100만 → v2 min=200만).
 
## grade_source 규칙
 
| 값 | 의미 |
|----|------|
| AUTO | 구매금액 자동 산정 |
| MANUAL | CS/관리자 직접 변경 |
| EVENT | 프로모션·VIP·운영 이벤트 |
 
## grade_locked_until 동작
 
자동 재계산 시:
```
if (grade_source != AUTO && now < grade_locked_until)
    skip recalculation
```
 
잠금 종료 후: `grade_source → AUTO`로 복귀, 재산정 수행.
 
## 운영 시나리오
 
### 1. VIP 강제 승급
```
grade        = PLATINUM
source       = MANUAL
locked_until = 2026-12-31
reason       = "VIP 보상"
```
→ 구매금액 하락해도 유지.
 
### 2. 이벤트 승급
```
grade        = GOLD
source       = EVENT
locked_until = +30days
reason       = "신규회원 이벤트"
```
→ 종료 후 자동 복귀.
 
### 3. 일반 사용자
```
grade        = SILVER
source       = AUTO
locked_until = NULL
reason       = NULL
```
→ 항상 자동 계산.
 
## 설계 포인트
 
- **파생 데이터 재계산 가능**: `grade_id`는 Aggregate에서 언제든 재산정 가능. 단, MANUAL/EVENT는 lock으로 보호.
- **이력 추적**: `grade_changed_reason`으로 운영 화면·CS 조회 즉시 대응. 상세 이력은 별도 AuditLog 권장.
- **정책 버전 관리**: GradePolicy version 컬럼으로 정책 변경 이력 보존, 과거 적용 기준 추적 가능.