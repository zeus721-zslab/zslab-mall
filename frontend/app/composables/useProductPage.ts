import { DEFAULT_PRODUCT_SORT, PRODUCT_SORT_OPTIONS } from '~/lib/constants/product'
import type { ProductListQuery, ProductListResponse, ProductSort } from '~/types/product'

/** 한 페이지 상품 수(백엔드 기본 size=20과 동일). */
const PAGE_SIZE = 20
const FIRST_PAGE = 1
const PAGE_QUERY_PATTERN = /^[1-9]\d*$/

/**
 * 번호 페이지 상품 목록(FE-69·renew ProductsView·CategoryView). 페이지가 스킨이 productList를 선언했을 때만 호출한다.
 * classic의 무한스크롤(useProductList)과 달리 URL ?sort=·?page=(1부터)가 상태의 기준이라 SSR·뒤로가기·새로고침이 같은 화면이다.
 * 정렬 선택지는 classic과 같은 PRODUCT_SORT_OPTIONS(SALES 제외). 카테고리 탭·배너용 목록은 헤더와 같은 useCategories 키를 공유한다.
 */
export function useProductPage(categoryId: Ref<number | null>) {
  const route = useRoute()
  const router = useRouter()
  const config = useRuntimeConfig()
  const baseURL = import.meta.server
    ? `${config.apiInternalBase}/api`
    : config.public.apiBase || '/api'

  const sort = computed<ProductSort>(
    () => PRODUCT_SORT_OPTIONS.find((option) => option.value === route.query.sort)?.value ?? DEFAULT_PRODUCT_SORT,
  )
  const page = computed<number>(() => {
    const raw = route.query.page
    return typeof raw === 'string' && PAGE_QUERY_PATTERN.test(raw) ? Number(raw) : FIRST_PAGE
  })

  const { data, pending, error, refresh } = useAsyncData(
    () => `product-page:${sort.value}:${categoryId.value ?? 'all'}:${page.value}`,
    () => {
      const query: ProductListQuery = { sort: sort.value, page: page.value - 1, size: PAGE_SIZE }
      if (categoryId.value !== null) {
        query.categoryId = categoryId.value
      }
      return $fetch<ProductListResponse>('/v1/products', { baseURL, query })
    },
  )

  const { data: categoryData, error: categoryError } = useCategories()
  const categories = computed(() => (categoryError.value ? [] : categoryData.value ?? []))
  const activeCategoryName = computed<string | null>(
    () => categories.value.find((category) => category.categoryId === categoryId.value)?.displayName ?? null,
  )

  const totalCount = computed<number>(() => data.value?.totalCount ?? 0)

  // 정렬을 바꾸면 1페이지로 돌아간다. 정렬은 기록을 남기지 않고(classic과 같은 replace), 페이지 이동은 뒤로가기로 돌아올 수 있게 push한다.
  async function setSort(value: ProductSort): Promise<void> {
    await router.replace({ query: { ...route.query, sort: value, page: undefined } })
  }

  async function goToPage(target: number): Promise<void> {
    await router.push({ query: { ...route.query, page: target === FIRST_PAGE ? undefined : String(target) } })
    if (import.meta.client) {
      window.scrollTo({ top: 0 })
    }
  }

  return {
    items: computed(() => data.value?.items ?? []),
    totalCount,
    pending,
    hasError: computed<boolean>(() => Boolean(error.value)),
    page,
    totalPages: computed<number>(() => Math.max(FIRST_PAGE, Math.ceil(totalCount.value / PAGE_SIZE))),
    sort,
    sortOptions: PRODUCT_SORT_OPTIONS,
    categories,
    activeCategoryName,
    setSort,
    goToPage,
    retry: refresh,
  }
}
