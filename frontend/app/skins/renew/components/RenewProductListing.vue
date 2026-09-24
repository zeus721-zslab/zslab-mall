<script setup lang="ts">
import { ShoppingBag } from '@lucide/vue'
import type { ProductPageListVm } from '~/skins/contracts/product-page'
import { categoryTheme, categoryThemes } from '../category-theme'
import { followActiveItem } from '../scroll-active'
import { trackScrollEdges } from '../scroll-edges'
import CategoryIllustration from './CategoryIllustration.vue'
import RenewPagination from './RenewPagination.vue'
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

const title = computed(() => props.list.activeCategoryName ?? (props.categoryId === null ? '전체 상품' : '카테고리'))
// 배너 색·아이콘은 메인 카테고리 타일과 같게 목록 단위 규칙을 쓴다(FE-82). 목록에 없는 id·전체는 단일 규칙.
const theme = computed(() => {
  const index = props.list.categories.findIndex((category) => category.categoryId === props.categoryId)
  return categoryThemes(props.list.categories)[index] ?? categoryTheme(props.categoryId, props.list.activeCategoryName)
})

const tabsElement = ref<HTMLElement | null>(null)
let stopFollowingActiveTab: (() => void) | null = null
onMounted(() => {
  if (tabsElement.value) stopFollowingActiveTab = followActiveItem(tabsElement.value)
})
onBeforeUnmount(() => stopFollowingActiveTab?.())
// 오른쪽 흐림은 줄 끝에 닿기 전까지만(FE-77).
const tabsEdges = trackScrollEdges(tabsElement)
</script>

<template>
  <div class="pb-8 pt-6 md:pt-10">
    <div :class="CONTAINER">
      <!-- 카테고리 배너: 색 전환 150ms -->
      <section
        class="flex items-center justify-between gap-6 overflow-hidden rounded-(--panel-radius) px-6 py-10 transition-colors duration-fast ease-soft md:px-12 md:py-14"
        :style="{ background: theme.background, color: theme.ink }"
      >
        <div>
          <p class="text-caption uppercase tracking-[0.12em] opacity-80">Category</p>
          <h1 class="mt-2 text-h1">{{ title }}</h1>
          <p class="mt-3 text-small">
            <span class="font-semibold tabular-nums">{{ list.totalCount.toLocaleString('ko-KR') }}</span>개의 상품
          </p>
        </div>
        <CategoryIllustration :name="theme.illustration" class="h-20 w-20 shrink-0 md:h-28 md:w-28" />
      </section>

      <!-- 최상위 카테고리 탭 + 정렬 -->
      <div class="mt-8 flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <!-- 탭 줄: 한 줄 가로 스크롤. <768은 스크롤바 숨김·스냅·오른쪽 흐림, 현재 탭은 보이는 위치로.
             랜드마크 이름은 헤더 카테고리 메뉴("카테고리")와 겹치지 않게 따로 둔다(landmark-unique · Track 105-4g-3). -->
        <nav
          ref="tabsElement"
          aria-label="목록 카테고리"
          data-testid="category-tabs"
          :class="[
            'relative -mx-1 overflow-x-auto px-1 pb-1 max-md:scrollbar-none max-md:snap-x max-md:snap-mandatory max-md:scroll-px-1',
            tabsEdges.atEnd ? '' : 'max-md:fade-right',
          ]"
        >
          <!-- 탭은 링크라 선택 표시는 aria-current(이동 위치) + chip의 data-state=on(보라 채움). -->
          <ul class="flex gap-2 max-md:[&>li]:snap-start">
            <li>
              <NuxtLink
                to="/products"
                class="chip shrink-0"
                :aria-current="categoryId === null ? 'page' : undefined"
                :data-state="categoryId === null ? 'on' : undefined"
              >
                전체
              </NuxtLink>
            </li>
            <li v-for="category in list.categories" :key="category.categoryId">
              <NuxtLink
                :to="`/categories/${category.categoryId}`"
                class="chip shrink-0"
                :aria-current="categoryId === category.categoryId ? 'page' : undefined"
                :data-state="categoryId === category.categoryId ? 'on' : undefined"
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
            <div class="mt-3 h-3 w-2/3 rounded-full bg-surface-muted"></div>
            <div class="mt-2 h-3 w-1/3 rounded-full bg-surface-muted"></div>
          </div>
        </div>
        <CommonErrorState v-else-if="list.hasError" @retry="list.retry()" />
        <!-- 빈 목록: 검색 빈 결과와 같은 흰 카드 톤(FE-82). 카테고리 화면만 전체 상품으로 가는 보조 버튼을 둔다. -->
        <div
          v-else-if="list.items.length === 0"
          class="flex flex-col items-center rounded-card bg-white px-6 py-16 text-center shadow-e1"
          data-testid="listing-empty"
        >
          <span class="flex h-20 w-20 items-center justify-center rounded-full bg-surface-muted text-primary" aria-hidden="true">
            <ShoppingBag class="h-10 w-10" :stroke-width="1.6" />
          </span>
          <p class="mt-6 text-h3 text-ink">등록된 상품이 없습니다</p>
          <NuxtLink v-if="categoryId !== null" to="/products" class="btn btn-secondary btn-md mt-6" data-testid="listing-empty-products-link">
            전체 상품 보기
          </NuxtLink>
        </div>
        <div v-else :class="PRODUCT_GRID">
          <RenewProductCard v-for="item in list.items" :key="item.productPublicId" :product="item" />
        </div>
      </div>

      <!-- 번호 페이지 -->
      <RenewPagination :page="list.page" :total-pages="list.totalPages" @change="list.goToPage" />
    </div>
  </div>
</template>
