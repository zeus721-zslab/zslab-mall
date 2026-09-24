/** pages/products/index.vue → ProductsView. categoryId는 ?categoryId= 호환 필터(없으면 null). */
export interface ProductsPageVm {
  categoryId: number | null
}
