# 권한 UML (RBAC)
 
플랫폼 권한과 판매자 내부 권한을 **분리 적용**한 RBAC 설계.
 
## Role 계층
 
```
SYSTEM
├── SUPER_ADMIN
└── ADMIN_OPERATOR
 
SELLER
├── SELLER_OWNER
├── SELLER_MANAGER
└── SELLER_STAFF
 
BUYER
└── BUYER
```
 
> 시스템 권한과 판매자 내부 권한은 **별도 컨텍스트**.
> 같은 User가 여러 Seller에 소속될 수 있고, Seller마다 다른 역할 보유 가능.
 
## 엔티티
 
### User
```
User
────────────────
id PK
```
 
### Role
```
Role
────────────────
id PK
code
name
```
 
### UserRole (플랫폼 권한)
```
UserRole
────────────────
user_id FK
role_id FK
```
 
> SYSTEM·BUYER 역할용. 단순 User-Role N:M.
 
### Seller
```
Seller
────────────────
id PK
company_name
status
```
 
### SellerUser (판매자 내부 권한)
```
SellerUser
────────────────
seller_id FK
user_id FK
role_id FK
```
 
> SELLER_* 역할용. 한 사용자가 Seller A에선 OWNER, Seller B에선 STAFF 가능.
 
## 관계
 
```
User
 └── N:M Role (via UserRole)
 
Seller
 └── 1:N SellerUser
 
SellerUser
 └── N:1 Role
```
 
## 판매자 권한 매트릭스
 
| 권한 | OWNER | MANAGER | STAFF |
|------|:-----:|:-------:|:-----:|
| 판매자 정보 수정 | O | △ | X |
| 관리자 초대 | O | O | X |
| 상품 등록 | O | O | O |
| 상품 승인 요청 | O | O | O |
| 주문 처리 | O | O | O |
| 정산 조회 | O | O | X |
| 정산 계좌 수정 | O | X | X |
| 권한 관리 | O | X | X |
 
> △ = 제한적 (예: 일부 필드만)
 
## 설계 포인트
 
- **컨텍스트 분리**: 플랫폼 권한(UserRole)과 판매자 내부 권한(SellerUser) 테이블 분리 — 데이터 스코프 명확.
- **멀티 소속 지원**: SellerUser N:M으로 한 User가 복수 Seller 관리 가능.
- **Permission 단위 확장**: Role-Permission N:M 추가 시 세밀한 권한 제어 가능 (별도 설계).
 
