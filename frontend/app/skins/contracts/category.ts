import type { ProductPageListVm } from './product-page'

/** pages/categories/[id].vue → CategoryView. categoryId가 null이면 잘못된 id(빈 상태). list는 productList 선언 스킨만(FE-69). */
export interface CategoryPageVm {
  categoryId: number | null
  list?: ProductPageListVm
}
