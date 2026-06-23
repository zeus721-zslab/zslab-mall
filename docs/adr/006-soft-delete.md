# ADR-006: 삭제 정책 — SOFT / HARD / ARCHIVE 3분류 + 비식별화 분리

- **상태**: 확정 (2026-06-24)
- **맥락**: PR-03 Deletion Policy. baseline-plan.md §8 산출물·decisions.md D-12

---

## 문제

운영 데이터의 삭제를 어떻게 처리할 것인가. 단일 정책으로는 상충하는 두 의무를 동시에 만족할 수 없다.

1. **보관 의무**: 주문·결제·정산·감사 로그는 전자상거래법·분쟁·감사 대응을 위해 삭제하면 안 된다.
2. **파기 의무**: 개인정보(탈퇴 회원 등)는 개인정보보호법상 보관 기간 후 파기해야 한다.

전체를 물리 삭제하면 분쟁·감사 대응이 불가능하고, 전체를 소프트 삭제로 보존하면 개인정보 파기 의무 위반·테이블 비대화가 발생한다.

---

## 결정

**데이터 성격별 SOFT / HARD / ARCHIVE 3분류를 Aggregate Root 단위로 적용하고, 소프트 삭제와 비식별화를 분리한다.**

- **SOFT**: `deleted_at` 마킹·복구 가능 (User·Seller·Product·Category·Attachment·UserAddress·ProductImage·ProductVariant·BuyerGrade·Code 등).
- **HARD**: 물리 삭제 (CartItem·권한 매핑 UserRole·RolePermission·Permission·Role·임시 데이터).
- **ARCHIVE**: 영구 보존(삭제 불가·법정 보관·상태 관리) (Order·Payment·Delivery·Claim·Settlement·Refund·Inventory·AuditLog·NotificationLog·SellerBankAccount·WithdrawnUser·집계 2건).
- **Aggregate Root 단위**: 분류는 17개 Root 기준·종속 엔티티는 Root 정책 자동 상속·경계 케이스만 별도 명시(deletion-policy.md §2).
- **소프트 삭제 + 비식별화 분리**: 탈퇴(withdrawn_at) → 법정 보관(legal_retention_until) → 배치 비식별화(anonymized_at). 소프트 삭제(복구 가능) ≠ 비식별화(불가역).

상세는 [deletion-policy.md](../architecture-baseline/deletion-policy.md) 참조.

---

## 이유

- **3분류**: 보관 의무(ARCHIVE)와 파기 의무(HARD·비식별화)를 데이터 성격별로 분리해 동시 충족한다.
- **Aggregate Root 단위**: 종속 엔티티를 일괄 적용해 분류 누락·후속 트랙 모호함을 차단한다(예: Product SOFT → 하위 옵션·이미지 자동 SOFT).
- **비식별화 분리**: "탈퇴했으나 법정 보관 중" 상태를 표현 가능. 식별자(user_id·order_id)는 유지해 ARCHIVE 데이터와 정합성을 보존한다.

---

## 영향

- 모든 조회 쿼리는 SOFT 대상에 `deleted_at IS NULL` 가드가 필요하다(누락 시 삭제분 노출).
- 소프트 삭제 컬럼(`deleted_at`)은 조회 필터에 쓰이므로 인덱스 영향이 있다(전략은 PR-04).
- 삭제는 전체관리자 한정·삭제 시 AuditLog.action=DELETE 자동 기록(audit-policy.md).
- 자동 비식별화 배치 잡이 필요하다(구현 단계 이연).

---

## 대안

| 대안 | 기각 이유 |
|---|---|
| 전체 HARD DELETE | 주문·정산·감사 이력 소실 → 분쟁·감사 대응 불가 |
| 전체 SOFT DELETE | 개인정보 파기 의무 위반 위험·테이블 비대화·복구 불필요 데이터 누적 |
| RETAIN 4번째 분류 신설 | 분류는 명확하나 스펙 3분류 이탈·ARCHIVE와 실질 차이 낮음(M-20 (b) 기각) |
| 소프트 삭제와 비식별화 통합 | "탈퇴+법정 보관 중" 표현 불가·개인정보 파기 시점 제어 불가 |
