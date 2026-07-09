/**
 * 장바구니 품목 1건(백엔드 CartItemView record·9필드 대응·recon-report-76 §1-1). 대상 식별키는 variantPublicId이며
 * 내부 PK는 노출되지 않는다. dangling(variant soft-delete 등으로 enrich 누락) 품목은 enrich 필드가 null/0/false로 오나
 * variantPublicId는 항상 보유해 조회·삭제가 가능하다. purchasable=false면 구매 불가(품절·판매중지·판매자 비활성 등).
 */
export interface CartItemView {
  variantPublicId: string
  quantity: number
  selected: boolean
  productName: string | null
  sellerName: string | null
  displayPrice: number
  quantityAvailable: number
  purchasable: boolean
  thumbnailUrl: string | null
}

/**
 * 장바구니 조회 응답(GET /api/v1/cart). 담김 품목 전량(페이징 없음)·담김 없으면 items=[].
 */
export interface CartResponse {
  items: CartItemView[]
}
