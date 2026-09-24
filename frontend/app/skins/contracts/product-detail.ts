import type { ProductDetail, ProductImage, ProductSummary, ProductVariant } from '~/types/product'

/**
 * pages/products/[productPublicId].vue → ProductDetailView. activeImageUrl은 뷰가 썸네일 클릭으로 직접 바꾼다.
 * 옵션 선택·수량·담기는 페이지 함수로만 한다.
 * isOptionValueSoldOut·isOptionValueUnavailable·totalPrice·sellerProducts는 FE-70 renew 상세용이다(classic 뷰는 쓰지 않는다).
 * sellerProducts는 스킨이 productDetailMore를 선언했을 때만 채워지고 그 외에는 빈 배열이다.
 */
export interface ProductDetailPageVm {
  pending: boolean
  error: Error | undefined
  data: ProductDetail | undefined
  refresh: () => Promise<void>
  errorMessage: string
  sortedImages: ProductImage[]
  activeImageUrl: string | null
  unavailableLabel: string | null
  formattedPrice: string
  selectedOptions: Record<string, string>
  selectOption: (groupName: string, value: string) => void
  selectedVariant: ProductVariant | null
  selectedVariantPublicId: string | null
  quantity: number
  decrementQuantity: () => void
  incrementQuantity: () => void
  canAddToCart: boolean
  adding: boolean
  addSucceeded: boolean
  addErrorMessage: string
  handleAddToCart: () => Promise<void>
  /** 옵션 값 품절 표시(현재 다른 그룹 선택값과의 조합 기준·조합 미완료에서도 판단). */
  isOptionValueSoldOut: (groupName: string, value: string) => boolean
  /** 옵션 값 비활성(다른 선택과 무관하게 이 값으로 구매 가능한 variant가 없음). */
  isOptionValueUnavailable: (groupName: string, value: string) => boolean
  /** 총 상품 금액 = 표시 단가 × 수량. variant 확정 전에는 null. */
  totalPrice: number | null
  sellerProducts: ProductSummary[]
}
