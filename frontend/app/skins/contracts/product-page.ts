import type { CategorySummary } from '~/types/category'
import type { ProductSort, ProductSummary } from '~/types/product'

/** 번호 페이지 상품 목록(FE-69·useProductPage). ProductsView·CategoryView에서 스킨이 productList를 선언했을 때만 채워진다. */
export interface ProductPageListVm {
  items: ProductSummary[]
  totalCount: number
  pending: boolean
  hasError: boolean
  /** 현재 페이지(1부터). */
  page: number
  totalPages: number
  sort: ProductSort
  sortOptions: { value: ProductSort; label: string }[]
  categories: CategorySummary[]
  activeCategoryName: string | null
  setSort: (value: ProductSort) => Promise<void>
  goToPage: (page: number) => Promise<void>
  retry: () => Promise<void>
}
