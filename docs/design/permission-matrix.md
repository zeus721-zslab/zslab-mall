# 권한 매트릭스
 
액터별 기능 접근 권한. 판매자 측은 OWNER/MANAGER/STAFF 3단계 RBAC 적용.
 
## 플랫폼 전체 매트릭스
 
| 기능 | 구매자 | SELLER_STAFF | SELLER_MANAGER | SELLER_OWNER | 운영자 | 전체관리자 |
|------|:------:|:------------:|:--------------:|:------------:|:------:|:----------:|
| 상품조회 | O | O | O | O | O | O |
| 주문 | O | - | - | - | - | - |
| 상품등록 | - | O | O | O | O | O |
| 상품승인 | - | - | - | - | O | O |
| 판매자정보수정 | - | X | △ | O | O | O |
| 관리자초대 (판매자) | - | X | O | O | O | O |
| 정산조회 (자사) | - | X | O | O | O | O |
| 정산계좌수정 | - | X | X | O | O | O |
| 판매자관리 (전체) | - | - | - | - | O | O |
| 권한관리 | - | - | - | O¹ | - | O |
| 시스템설정 | - | - | - | - | - | O |
 
> ¹ SELLER_OWNER의 권한관리는 **자사(seller_id) 스코프 한정** — 판매자 내부 사용자 역할 배정.
> △ = 제한적 (일부 필드만 허용).
 
## 핵심 원칙
 
- **상품등록 vs 상품승인 분리**: 판매자가 등록 → 운영자가 승인 워크플로우.
- **자사 스코프 데이터 접근**: SELLER_* 역할의 정산·주문 조회는 자사(seller_id) 데이터로 필터링 — Row-level Security 패턴.
- **컨텍스트 분리 권한**: 플랫폼 권한(UserRole)과 판매자 내부 권한(SellerUser)은 별도 테이블·별도 체크. 자세한 구조는 `permission-uml.md` 참조.
- **시스템설정·전체 권한관리는 전체관리자 전용**: 운영자도 접근 불가.
## 구현 시 고려사항
 
- 권한 체크는 단순 Role 비교가 아닌 **Permission 단위 RBAC**로 구현 (Role-Permission N:M).
- 판매자 내부 권한은 `(user_id, seller_id) → role_id` 조회 후 판정 — 멀티 소속 사용자 대응.
- 자사 스코프 강제: API/쿼리 레이어에서 `WHERE seller_id = :current_seller_id` 자동 주입 (예: Spring Data JPA `@Filter`, Hibernate Filter, 혹은 Repository 베이스 추상화).
 
