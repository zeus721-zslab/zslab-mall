<script setup lang="ts">
import type { ProductPageListVm } from '~/skins/contracts/product-page'
import { categoryTheme } from '../category-theme'
import { followActiveItem } from '../scroll-active'
import CategoryIllustration from './CategoryIllustration.vue'
import RenewProductCard from './RenewProductCard.vue'
import RenewSortMenu from './RenewSortMenu.vue'

// renew 상품 목록 본문(ProductsView·CategoryView 공용): 카테고리 배너(색·일러스트·상품 수) → 최상위 카테고리 탭 + 정렬 → 그리드 → 번호 페이지.
// 하위 카테고리 탭은 없다(카테고리 API가 루트만 제공·FE-68).
const props = defineProps<{
  list: ProductPageListVm
  categoryId: number | null
}>()

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
const PRODUCT_GRID = 'grid grid-cols-2 gap-x-4 gap-y-10 md:grid-cols-3 md:gap-x-6 lg:grid-cols-4 xl:grid-cols-5'
const SKELETON_COUNT = 10
// 번호 페이지는 현재 페이지 앞뒤로 이만큼만 보인다.
const PAGE_WINDOW = 2
const PILL = 'flex min-h-11 shrink-0 items-center whitespace-nowrap rounded-full px-5 text-sm font-bold transition duration-200'

const title = computed(() => props.list.activeCategoryName ?? (props.categoryId === null ? '전체 상품' : '카테고리'))
const theme = computed(() => categoryTheme(props.list.activeCategoryName))
const pageNumbers = computed<number[]>(() => {
  const first = Math.max(1, props.list.page - PAGE_WINDOW)
  const last = Math.min(props.list.totalPages, props.list.page + PAGE_WINDOW)
  return Array.from({ length: last - first + 1 }, (_, index) => first + index)
})

function tabClass(active: boolean): string {
  return `${PILL} ${active ? 'bg-primary text-primary-foreground' : 'bg-surface-card text-ink hover:bg-(--pastel-lavender-bg)'}`
}

const tabsElement = ref<HTMLElement | null>(null)
let stopFollowingActiveTab: (() => void) | null = null
onMounted(() => {
  if (tabsElement.value) stopFollowingActiveTab = followActiveItem(tabsElement.value)
})
onBeforeUnmount(() => stopFollowingActiveTab?.())
</script>

<template>
  <div class="pb-8 pt-6 md:pt-10">
    <div :class="CONTAINER">
      <!-- 카테고리 배너: 색 전환 0.45s -->
      <section
        class="flex items-center justify-between gap-6 overflow-hidden rounded-(--panel-radius) px-6 py-10 transition-colors duration-[450ms] md:px-12 md:py-14"
        :style="{ background: theme.background, color: theme.ink }"
      >
        <div>
          <p class="font-mono text-xs font-medium uppercase tracking-widest opacity-80">Category</p>
          <h1 class="mt-2 text-3xl font-bold tracking-tight md:text-4xl">{{ title }}</h1>
          <p class="mt-3 text-sm">
            <span class="font-mono font-semibold">{{ list.totalCount.toLocaleString('ko-KR') }}</span>개의 상품
          </p>
        </div>
        <CategoryIllustration :name="theme.illustration" class="h-20 w-20 shrink-0 md:h-28 md:w-28" />
      </section>

      <!-- 최상위 카테고리 탭 + 정렬 -->
      <div class="mt-8 flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <!-- 탭 줄: 한 줄 가로 스크롤. <768은 스크롤바 숨김·스냅·오른쪽 흐림, 현재 탭은 보이는 위치로. -->
        <nav
          ref="tabsElement"
          aria-label="카테고리"
          data-testid="category-tabs"
          class="relative -mx-1 overflow-x-auto px-1 pb-1 max-md:scrollbar-none max-md:snap-x max-md:snap-mandatory max-md:scroll-px-1 max-md:fade-right"
        >
          <ul class="flex gap-2 max-md:[&>li]:snap-start">
            <li>
              <NuxtLink to="/products" :class="tabClass(categoryId === null)" :aria-current="categoryId === null ? 'page' : undefined">
                전체
              </NuxtLink>
            </li>
            <li v-for="category in list.categories" :key="category.categoryId">
              <NuxtLink
                :to="`/categories/${category.categoryId}`"
                :class="tabClass(categoryId === category.categoryId)"
                :aria-current="categoryId === category.categoryId ? 'page' : undefined"
              >
                {{ category.displayName }}
              </NuxtLink>
            </li>
          </ul>
        </nav>
        <!-- 정렬: renew 정렬 목록(알약 트리거·커스텀 패널). 값이 바뀌면 기존과 같이 setSort(쿼리 변경·1페이지·재조회). -->
        <div class="shrink-0 self-start md:self-auto">
          <RenewSortMenu :model-value="list.sort" :options="list.sortOptions" @update:model-value="list.setSort" />
        </div>
      </div>

      <!-- 그리드: 로딩 · 오류 · 빈 목록 · 목록 -->
      <div class="mt-10">
        <div v-if="list.pending" :class="PRODUCT_GRID" aria-hidden="true">
          <div v-for="index in SKELETON_COUNT" :key="index">
            <div class="aspect-square rounded-card bg-(--image-placeholder)"></div>
            <div class="mt-3 h-3 w-2/3 rounded-full bg-surface-card"></div>
            <div class="mt-2 h-3 w-1/3 rounded-full bg-surface-card"></div>
          </div>
        </div>
        <CommonErrorState v-else-if="list.hasError" @retry="list.retry()" />
        <CommonEmptyState v-else-if="list.items.length === 0" />
        <div v-else :class="PRODUCT_GRID">
          <RenewProductCard v-for="item in list.items" :key="item.productPublicId" :product="item" />
        </div>
      </div>

      <!-- 번호 페이지 -->
      <nav v-if="list.totalPages > 1" aria-label="페이지" class="mt-14 flex items-center justify-center gap-1">
        <button
          type="button"
          class="flex h-11 min-w-11 items-center justify-center rounded-full text-sm font-bold text-ink transition duration-200 hover:bg-surface-card disabled:opacity-40"
          :disabled="list.page <= 1"
          aria-label="이전 페이지"
          @click="list.goToPage(list.page - 1)"
        >
          ‹
        </button>
        <button
          v-for="pageNumber in pageNumbers"
          :key="pageNumber"
          type="button"
          :class="[
            'flex h-11 min-w-11 items-center justify-center rounded-full px-3 font-mono text-sm font-semibold transition duration-200',
            pageNumber === list.page ? 'bg-primary text-primary-foreground' : 'text-ink hover:bg-surface-card',
          ]"
          :aria-current="pageNumber === list.page ? 'page' : undefined"
          @click="list.goToPage(pageNumber)"
        >
          {{ pageNumber }}
        </button>
        <button
          type="button"
          class="flex h-11 min-w-11 items-center justify-center rounded-full text-sm font-bold text-ink transition duration-200 hover:bg-surface-card disabled:opacity-40"
          :disabled="list.page >= list.totalPages"
          aria-label="다음 페이지"
          @click="list.goToPage(list.page + 1)"
        >
          ›
        </button>
      </nav>
    </div>
  </div>
</template>
