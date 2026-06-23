# 입점형 쇼핑몰 핵심 도메인 UML
 
멀티벤더(Marketplace) 구조의 핵심 엔티티 설계.
 
## 엔티티
 
### Seller (판매자/입점사)
```
Seller
────────────────────
+ id
+ company_name
+ status
```
 
### SellerAdmin (판매관리자)
```
SellerAdmin
────────────────────
+ user_id
+ seller_id
```
 
### Product (상품)
```
Product
────────────────────
+ id
+ seller_id
+ category_id
+ price
+ stock
```
 
### ProductOption (상품 옵션)
```
ProductOption
────────────────────
+ id
+ product_id
```
 
### Order (주문)
```
Order
────────────────────
+ id
+ buyer_id
+ status
+ total_price
```
 
### OrderItem (주문 항목)
```
OrderItem
────────────────────
+ id
+ order_id
+ product_id
+ seller_id
```
 
> `OrderItem.seller_id`를 보유 — 멀티벤더 정산을 위해 항목 단위로 판매자 추적 필수.
 
### Settlement (정산)
```
Settlement
────────────────────
+ id
+ seller_id
+ amount
+ status
```
 
## 관계도
 
```
Seller
 │
 ├── 1:N Product
 │
 ├── 1:N SellerAdmin
 │
 └── 1:N Settlement
 
 
Buyer
 │
 └── 1:N Order
 
 
Order
 │
 └── 1:N OrderItem
```
 
## 설계 포인트
 
- **OrderItem-Seller 직접 연결**: 한 주문에 여러 판매자 상품 혼합 가능 → 항목별 판매자 식별·정산 분리.
- **Settlement는 Seller 1:N**: 정산 주기(일/주/월)별 누적 레코드.
- **Product.stock**: 단순 단일 재고 필드 (옵션별 재고 분리는 ProductOption 확장 시 추가).