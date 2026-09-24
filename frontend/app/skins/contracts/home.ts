import type { CategorySummary } from '~/types/category'
import type { ProductSummary } from '~/types/product'

/** 셀러 픽 1곳(최신 상품에서 찾은 셀러 + 그 셀러 상품). */
export interface SellerPick {
  sellerPublicId: string
  sellerName: string
  items: ProductSummary[]
}

/**
 * pages/index.vue → HomeView. 스킨이 homeCuration을 선언했을 때만 채워진다(FE-69·useHomeCuration). classic HomeView는 vm 없이 렌더한다.
 * 섹션 배열이 비어 있으면(조회 실패 포함) 뷰가 그 섹션을 숨긴다.
 */
export interface HomePageVm {
  categories: CategorySummary[]
  newArrivals: ProductSummary[]
  popular: ProductSummary[]
  budget: ProductSummary[]
  budgetMaxPrice: number
  categoryPicks: ProductSummary[]
  categoryPicksPending: boolean
  activeCategoryId: number | null
  selectCategory: (categoryId: number) => void
  sellerPicks: SellerPick[]
}
