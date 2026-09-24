/** pages/categories/[id].vue → CategoryView. categoryId가 null이면 잘못된 id(빈 상태). */
export interface CategoryPageVm {
  categoryId: number | null
}
