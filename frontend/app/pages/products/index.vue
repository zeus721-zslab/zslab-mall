<script setup lang="ts">
import type { ProductSort } from '~/types/product'

// 정렬 옵션 단일 소스(value=ProductSort 유니온으로 타입 고정·매직 문자열 방지, label=UI 표기).
const PRODUCT_SORT_OPTIONS: { value: ProductSort; label: string }[] = [
  { value: 'LATEST', label: '최신순' },
  { value: 'PRICE_ASC', label: '가격 낮은순' },
  { value: 'PRICE_DESC', label: '가격 높은순' },
  { value: 'NAME', label: '이름순' },
]

const route = useRoute()
const router = useRouter()

// URL 쿼리에서 초기값 복원. sort 미지정·비허용값이면 LATEST. categoryId는 URL 전용(이번 트랙 선택 UI 없음·필터만).
const validSorts = PRODUCT_SORT_OPTIONS.map((option) => option.value)
const rawSort = route.query.sort
const initialSort: ProductSort =
  typeof rawSort === 'string' && (validSorts as string[]).includes(rawSort)
    ? (rawSort as ProductSort)
    : 'LATEST'
const rawCategoryId = route.query.categoryId
const initialCategoryId: number | null =
  typeof rawCategoryId === 'string' && /^\d+$/.test(rawCategoryId) ? Number(rawCategoryId) : null

const sort = ref<ProductSort>(initialSort)
const categoryId = ref<number | null>(initialCategoryId)

const { items, pending, error, hasNext, loadMore, reset } = useProductList(sort, categoryId)

// sort 변경 → URL 쿼리 동기화(기존 쿼리 보존·history 미증가 replace). page는 URL 미반영(무한스크롤 누적).
watch(sort, (value) => {
  router.replace({ query: { ...route.query, sort: value } })
})

useSeoMeta({
  title: '상품 목록 · zslab-mall',
  description: 'zslab-mall 상품 목록. 최신순·가격순·이름순으로 둘러보세요.',
})

// 무한스크롤: 하단 sentinel 교차 시 다음 page 로드. 브라우저 네이티브 IntersectionObserver(외부 라이브러리 없음).
const sentinel = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

onMounted(() => {
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting) {
        loadMore()
      }
    },
    { rootMargin: '200px' },
  )
  // SSR로 이미 렌더된 sentinel은 즉시 관측, 이후 등장/제거는 watch가 처리.
  if (sentinel.value) {
    observer.observe(sentinel.value)
  }
})

// sentinel은 hasNext일 때만 렌더되므로(v-if), 등장·제거 시점에 관측 대상을 갱신한다(SSR·클라이언트 네비 모두 대응).
watch(sentinel, (element, previous) => {
  if (!observer) {
    return
  }
  if (previous) {
    observer.unobserve(previous)
  }
  if (element) {
    observer.observe(element)
  }
})

onBeforeUnmount(() => {
  observer?.disconnect()
})
</script>

<template>
  <div class="py-14 md:py-20">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <div class="mb-8 flex items-center justify-between gap-4">
        <h1 class="text-2xl font-bold tracking-tight text-gray-900 md:text-3xl">상품 목록</h1>
        <select
          v-model="sort"
          aria-label="정렬 기준"
          class="rounded-full border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 transition duration-200 hover:bg-gray-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gray-900"
        >
          <option v-for="option in PRODUCT_SORT_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </div>

      <!-- Loading (초기·필터 변경 재조회) -->
      <div v-if="pending" class="grid grid-cols-2 gap-4 md:grid-cols-3 md:gap-6 lg:grid-cols-4">
        <CommonLoadingSkeleton :count="8" />
      </div>

      <!-- Error -->
      <CommonErrorState v-else-if="error" @retry="reset" />

      <!-- Empty -->
      <CommonEmptyState v-else-if="items.length === 0" />

      <!-- Success -->
      <template v-else>
        <div class="grid grid-cols-2 gap-4 md:grid-cols-3 md:gap-6 lg:grid-cols-4">
          <ProductCard v-for="item in items" :key="item.productPublicId" :product="item" />
        </div>
        <!-- 무한스크롤 관측 지점: 다음 page가 있을 때만 렌더(끝나면 제거되어 추가 요청 중단). -->
        <div v-if="hasNext" ref="sentinel" class="h-px w-full" aria-hidden="true"></div>
      </template>
    </div>
  </div>
</template>
