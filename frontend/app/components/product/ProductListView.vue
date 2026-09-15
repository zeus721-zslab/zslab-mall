<script setup lang="ts">
import { DEFAULT_PRODUCT_SORT, PRODUCT_SORT_OPTIONS } from '~/lib/constants/product'
import type { ProductSort } from '~/types/product'

/**
 * 상품 목록 공용 뷰(FE-20·products/index.vue 본문 승격). 데이터·상태는 useProductList가, 이 컴포넌트는 조립(탭·정렬·4상태·
 * 무한스크롤)만 담당한다. 소비처 = /products·/categories/[id]·/search 세 페이지(껍데기). 레이아웃은 시안 반복 교체 대상이라
 * 페이지가 아닌 여기 한 곳만 바꾸면 세 페이지에 동시 반영된다.
 */
const props = withDefaults(
  defineProps<{
    title: string
    categoryId?: number | null
    keyword?: string | null
    showCategoryTabs?: boolean
  }>(),
  { categoryId: null, keyword: null, showCategoryTabs: true },
)

const route = useRoute()
const router = useRouter()

// URL 쿼리에서 정렬 초기값 복원. sort 미지정·비허용값이면 LATEST.
const validSorts = PRODUCT_SORT_OPTIONS.map((option) => option.value)
const rawSort = route.query.sort
const initialSort: ProductSort =
  typeof rawSort === 'string' && (validSorts as string[]).includes(rawSort)
    ? (rawSort as ProductSort)
    : DEFAULT_PRODUCT_SORT

const sort = ref<ProductSort>(initialSort)
// props는 페이지 route에 따라 바뀔 수 있어(예: /search?keyword= 변경은 페이지 재마운트 없음) Ref로 넘겨 composable이 watch한다.
const categoryId = toRef(props, 'categoryId')
const keyword = toRef(props, 'keyword')

const { items, pending, error, hasNext, loadMore, reset } = useProductList(sort, categoryId, keyword)

// sort 변경 → URL 쿼리 동기화(기존 쿼리 보존·history 미증가 replace). page는 URL 미반영(무한스크롤 누적).
watch(sort, (value) => {
  router.replace({ query: { ...route.query, sort: value } })
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

// 그리드 열 수: 모바일 2 / sm 3 / md 4 / lg 5 / xl 6. 스켈레톤·성공 그리드가 같은 클래스를 써야 레이아웃 점프가 없다.
const GRID_CLASS = 'grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 md:gap-6 lg:grid-cols-5 xl:grid-cols-6'
const SKELETON_COUNT = 12
</script>

<template>
  <div class="py-14 md:py-20">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <div class="mb-6 flex items-center justify-between gap-4">
        <h1 class="text-2xl font-medium tracking-tight text-gray-900">{{ title }}</h1>
        <ProductSortSelect v-model="sort" />
      </div>

      <ProductCategoryTabs v-if="showCategoryTabs" :category-id="categoryId" class="mb-8" />

      <!-- Loading (초기·필터 변경 재조회) -->
      <div v-if="pending" :class="GRID_CLASS">
        <CommonLoadingSkeleton :count="SKELETON_COUNT" />
      </div>

      <!-- Error -->
      <CommonErrorState v-else-if="error" @retry="reset" />

      <!-- Empty -->
      <CommonEmptyState v-else-if="items.length === 0" />

      <!-- Success -->
      <template v-else>
        <div :class="GRID_CLASS">
          <ProductCard v-for="item in items" :key="item.productPublicId" :product="item" />
        </div>
        <!-- 무한스크롤 관측 지점: 다음 page가 있을 때만 렌더(끝나면 제거되어 추가 요청 중단). -->
        <div v-if="hasNext" ref="sentinel" class="h-px w-full" aria-hidden="true"></div>
      </template>
    </div>
  </div>
</template>
