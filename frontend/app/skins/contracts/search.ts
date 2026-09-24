import type { ProductPageListVm } from './product-page'

/** pages/search.vue → SearchView. keyword는 trim 결과(빈 문자열이면 안내만 표시). */
export interface SearchPageVm {
  keyword: string
  title: string
  /** 번호 페이지 검색 결과. productList를 선언한 스킨만 채워진다(FE-74). */
  list?: ProductPageListVm
}
