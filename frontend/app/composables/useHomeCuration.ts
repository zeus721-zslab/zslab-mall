import type { SellerPick } from '~/skins/contracts/home'
import type { CategorySummary } from '~/types/category'
import type { ProductListQuery, ProductListResponse, ProductSummary } from '~/types/product'

// 섹션별 노출 건수(Track 105-2c 디자인 기준).
const NEW_ARRIVALS_SIZE = 5
const POPULAR_SIZE = 5
const BUDGET_SIZE = 10
const BUDGET_MAX_PRICE = 20000
const CATEGORY_PICKS_SIZE = 5
// 셀러 픽: 최신 상품 이만큼에서 서로 다른 셀러를 찾고, 셀러마다 상품 몇 개를 보여 줄지.
const SELLER_DISCOVERY_SIZE = 20
const SELLER_PICK_COUNT = 3
const SELLER_PICK_PRODUCT_SIZE = 3

/**
 * 메인 큐레이션 조회(FE-69·renew HomeView). 페이지(pages/index.vue)가 스킨이 homeCuration을 선언했을 때만 호출한다.
 * 섹션마다 별도 useAsyncData라 SSR에서 병렬로 조회되고, 섹션 조회 실패·빈 결과는 그 섹션만 빈 배열이 된다(뷰가 숨김·페이지 에러 아님).
 * 카테고리 목록은 헤더·탭과 같은 useCategories 키를 공유해 추가 요청이 없다.
 */
export function useHomeCuration() {
  const config = useRuntimeConfig()
  const baseURL = import.meta.server
    ? `${config.apiInternalBase}/api`
    : config.public.apiBase || '/api'

  async function fetchProducts(query: ProductListQuery): Promise<ProductSummary[]> {
    const response = await $fetch<ProductListResponse>('/v1/products', { baseURL, query })
    return response.items
  }

  // 실패는 섹션 숨김이므로 error를 빈 배열로 접는다.
  function itemsOrEmpty<T>(state: { data: Readonly<Ref<T[] | null | undefined>>; error: Readonly<Ref<unknown>> }) {
    return computed<T[]>(() => (state.error.value ? [] : state.data.value ?? []))
  }

  const newArrivals = useAsyncData('home-curation:new', () =>
    fetchProducts({ sort: 'LATEST', size: NEW_ARRIVALS_SIZE }))
  const popular = useAsyncData('home-curation:popular', () =>
    fetchProducts({ sort: 'SALES', size: POPULAR_SIZE }))
  const budget = useAsyncData('home-curation:budget', () =>
    fetchProducts({ sort: 'LATEST', size: BUDGET_SIZE, maxPrice: BUDGET_MAX_PRICE }))

  // 카테고리별 추천: 탭 선택이 없으면 첫 카테고리. 탭 전환 시 해당 카테고리로 재조회한다.
  const categoriesState = useCategories()
  const categories = itemsOrEmpty<CategorySummary>(categoriesState)
  const selectedCategoryId = ref<number | null>(null)
  const activeCategoryId = computed<number | null>(() => selectedCategoryId.value ?? categories.value[0]?.categoryId ?? null)
  const categoryPicks = useAsyncData(
    'home-curation:category',
    async () => {
      // 카테고리 목록(공유 키)이 끝나야 첫 카테고리를 안다.
      await categoriesState
      return activeCategoryId.value === null
        ? []
        : fetchProducts({ sort: 'LATEST', size: CATEGORY_PICKS_SIZE, categoryId: activeCategoryId.value })
    },
    { watch: [selectedCategoryId] },
  )

  function selectCategory(categoryId: number): void {
    selectedCategoryId.value = categoryId
  }

  // 셀러 픽: 최신 상품에서 서로 다른 셀러 3곳 → 셀러 필터로 셀러마다 상품 조회(신규 API 없이 조합·D-221).
  const sellerPicks = useAsyncData('home-curation:sellers', async (): Promise<SellerPick[]> => {
    const latest = await fetchProducts({ sort: 'LATEST', size: SELLER_DISCOVERY_SIZE })
    const sellers = new Map<string, string>()
    for (const product of latest) {
      if (sellers.size >= SELLER_PICK_COUNT) break
      if (!sellers.has(product.sellerPublicId)) sellers.set(product.sellerPublicId, product.sellerName)
    }
    const picks = await Promise.all(
      [...sellers].map(async ([sellerPublicId, sellerName]) => ({
        sellerPublicId,
        sellerName,
        items: await fetchProducts({ sort: 'LATEST', size: SELLER_PICK_PRODUCT_SIZE, sellerPublicId }),
      })),
    )
    return picks.filter((pick) => pick.items.length > 0)
  })

  return {
    categories,
    newArrivals: itemsOrEmpty<ProductSummary>(newArrivals),
    popular: itemsOrEmpty<ProductSummary>(popular),
    budget: itemsOrEmpty<ProductSummary>(budget),
    budgetMaxPrice: BUDGET_MAX_PRICE,
    categoryPicks: itemsOrEmpty<ProductSummary>(categoryPicks),
    categoryPicksPending: categoryPicks.pending,
    activeCategoryId,
    selectCategory,
    sellerPicks: itemsOrEmpty<SellerPick>(sellerPicks),
  }
}
