import type { ProductListResponse, ProductSort, ProductSummary } from '~/types/product'

/** 목록 기본 페이지 크기(백엔드 기본 size=20·recon-report-67 B-1). */
const DEFAULT_PAGE_SIZE = 20

/**
 * 구매자 상품 목록 조회 composable(FE-05·offset 무한스크롤). 홈 전용 {@link useProducts}와 달리 sort·categoryId를
 * 파라미터화하고 page 누적(append)을 지원한다.
 *
 * 초기 page 0은 useAsyncData로 조회하고(SSR 페이로드 전송), items·hasNext는 data에서 파생(computed)해 SSR 렌더 시점에
 * 곧바로 반영되게 한다 — items를 별도 watch로 채우면 SSR에서 watcher가 재실행되지 않아 빈 목록으로 렌더되는 문제가 있다.
 * loadMore는 $fetch로 다음 page를 누적분(appendedItems)에 push한다. sort·categoryId 변경 시 data가 refetch되며 누적분을 리셋한다.
 * API base 이원화는 useProducts와 동일(SSR=apiInternalBase+/api·브라우저=public.apiBase||'/api').
 */
export function useProductList(
  sort: Ref<ProductSort>,
  categoryId: Ref<number | null>,
  size: number = DEFAULT_PAGE_SIZE,
) {
  const config = useRuntimeConfig()
  const baseURL = import.meta.server
    ? `${config.apiInternalBase}/api`
    : config.public.apiBase || '/api'

  // page 1+ 누적분. page 0은 data(useAsyncData)가 소유하며 items에서 합성한다.
  const appendedItems = ref<ProductSummary[]>([])
  // 마지막으로 로드한 page의 hasNext. null이면 아직 append 전 → data.hasNext(page 0)를 따른다.
  const appendedHasNext = ref<boolean | null>(null)
  const page = ref(0)
  const loadingMore = ref(false)

  function buildQuery(pageNumber: number) {
    const query: Record<string, string | number> = { sort: sort.value, page: pageNumber, size }
    if (categoryId.value !== null) {
      query.categoryId = categoryId.value
    }
    return query
  }

  // 초기 page 0: SSR 페이로드 전송. key는 sort·categoryId 조합(홈 useProducts의 고정 key와 분리·필터별 캐시 구분).
  const { data, pending, error, refresh } = useAsyncData<ProductListResponse>(
    `product-list:${sort.value}:${categoryId.value ?? 'all'}`,
    () => $fetch<ProductListResponse>('/v1/products', { baseURL, query: buildQuery(0) }),
    { watch: [sort, categoryId] },
  )

  // 필터변경/새로고침으로 page 0이 refetch되면 누적분을 리셋한다(클라이언트 전용 — SSR 초기엔 누적분이 이미 비어 있음).
  watch(data, () => {
    appendedItems.value = []
    appendedHasNext.value = null
    page.value = 0
  })

  // page 0(data) + 누적분 합성. data를 직접 읽어 SSR 렌더 시점에 반영된다.
  const items = computed<ProductSummary[]>(() => [
    ...(data.value?.items ?? []),
    ...appendedItems.value,
  ])
  const hasNext = computed<boolean>(() => appendedHasNext.value ?? data.value?.hasNext ?? false)

  async function loadMore() {
    if (!hasNext.value || loadingMore.value || pending.value) {
      return
    }
    loadingMore.value = true
    try {
      const nextPage = page.value + 1
      const response = await $fetch<ProductListResponse>('/v1/products', {
        baseURL,
        query: buildQuery(nextPage),
      })
      appendedItems.value.push(...response.items)
      appendedHasNext.value = response.hasNext
      page.value = nextPage
    } catch (loadError) {
      // append 실패는 hasNext를 닫아 IntersectionObserver 재요청 루프를 막고 콘솔에 남긴다(초기 로드 error와 별개).
      console.error('[useProductList] loadMore 실패', loadError)
      appendedHasNext.value = false
    } finally {
      loadingMore.value = false
    }
  }

  /** 초기/필터 상태로 page 0 재조회(useAsyncData refresh → watch(data)가 누적분 리셋). */
  function reset() {
    return refresh()
  }

  return { items, pending, error, hasNext, loadMore, reset }
}
