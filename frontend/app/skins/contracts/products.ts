import type { ProductPageListVm } from './product-page'

/** pages/products/index.vue → ProductsView. categoryId는 ?categoryId= 호환 필터(없으면 null). list는 productList 선언 스킨만(FE-69). */
export interface ProductsPageVm {
  categoryId: number | null
  list?: ProductPageListVm
}
