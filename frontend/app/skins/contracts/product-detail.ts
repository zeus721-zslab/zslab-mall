import type { ProductDetail, ProductImage, ProductVariant } from '~/types/product'

/**
 * pages/products/[productPublicId].vue → ProductDetailView. activeImageUrl은 뷰가 썸네일 클릭으로 직접 바꾼다.
 * 옵션 선택·수량·담기는 페이지 함수로만 한다.
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
}
