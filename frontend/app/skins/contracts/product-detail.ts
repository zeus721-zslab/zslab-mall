import type { ProductDetail, ProductImage, ProductSummary, ProductVariant } from '~/types/product'
import type { ProductQuestionsVm } from './product-questions'
import type { ProductReviewsVm } from './product-reviews'
import type { ProductSectionNavVm } from './product-section-nav'

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
  /** 담기 성공 신호(FE-99). 성공할 때마다 1씩 오른다 — 스킨은 값 변화로 스낵바·비행·뱃지를 띄운다. */
  addedSignal: number
  addErrorMessage: string
  handleAddToCart: () => Promise<void>
  /** 옵션 값 품절 표시(현재 다른 그룹 선택값과의 조합 기준·조합 미완료에서도 판단). */
  isOptionValueSoldOut: (groupName: string, value: string) => boolean
  /** 옵션 값 비활성(다른 선택과 무관하게 이 값으로 구매 가능한 variant가 없음). */
  isOptionValueUnavailable: (groupName: string, value: string) => boolean
  /** 총 상품 금액 = 표시 단가 × 수량. variant 확정 전에는 null. */
  totalPrice: number | null
  sellerProducts: ProductSummary[]
  /** 리뷰 요약·목록(Track 106-1). 스킨이 productReviews를 선언했을 때만 있고 그 외에는 null. */
  reviews: ProductReviewsVm | null
  /** 묻기·즉시 답·질문 목록(Track 106-2). 스킨이 productQuestions를 선언했을 때만 있고 그 외에는 null. */
  questions: ProductQuestionsVm | null
  /** 한눈에 칩·진행형 섹션 바(Track 106-2). 데이터 조회가 없어 스킨 선언과 무관하게 늘 있다. */
  sectionNav: ProductSectionNavVm
}
