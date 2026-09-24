/** pages/search.vue → SearchView. keyword는 trim 결과(빈 문자열이면 안내만 표시). */
export interface SearchPageVm {
  keyword: string
  title: string
}
