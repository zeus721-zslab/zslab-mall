import type { CategorySummary } from '~/types/category'

/**
 * 공개 루트 카테고리 목록 조회(GET /api/v1/categories·FE-20). 헤더 드롭다운과 카테고리 탭이 같은 페이지에서 함께 호출하므로
 * useAsyncData 고정 key('categories')로 요청·상태를 공유한다(동일 key는 Nuxt가 중복 호출을 합친다).
 * API base 이원화는 useProductList와 동일(SSR=apiInternalBase+/api·브라우저=public.apiBase||'/api').
 * 실패는 error로 노출하고 소비처가 "전체"만 표시하는 식으로 degrade한다(목록 영역은 영향 없음).
 */
export function useCategories() {
  const config = useRuntimeConfig()
  const baseURL = import.meta.server
    ? `${config.apiInternalBase}/api`
    : config.public.apiBase || '/api'

  return useAsyncData<CategorySummary[]>(
    'categories',
    () => $fetch<CategorySummary[]>('/v1/categories', { baseURL }),
  )
}
