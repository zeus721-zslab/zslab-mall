<script setup lang="ts">
import { SearchX } from '@lucide/vue'
import type { SearchPageVm } from '~/skins/contracts/search'
import RenewPagination from '../components/RenewPagination.vue'
import RenewProductCard from '../components/RenewProductCard.vue'
import RenewSortMenu from '../components/RenewSortMenu.vue'

// renew 검색 결과(FE-74): 제목 면(검색어·결과 수) → 정렬 → 그리드 → 번호 페이지. 목록과 같은 형태이되 카테고리 탭은 없다(검색은 전 카테고리 대상).
// 데이터는 페이지가 검색어로 번호 페이지 목록(useProductPage)을 조회해 vm.list로 넘긴다(productList 선언). 빈 검색어는 조회 없이 안내만 보인다(classic과 같은 규칙).
defineProps<{ vm: SearchPageVm }>()

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
const PRODUCT_GRID = 'grid grid-cols-2 gap-x-4 gap-y-10 md:grid-cols-3 md:gap-x-6 lg:grid-cols-4 xl:grid-cols-5'
const SKELETON_COUNT = 10
const ENTER = 'motion-safe:transition motion-safe:duration-300 motion-safe:ease-out motion-safe:starting:translate-y-1.5 motion-safe:starting:opacity-0'
</script>

<template>
  <div class="pb-8 pt-6 md:pt-10">
    <div :class="CONTAINER">
      <!-- 제목 면: 검색어 + 결과 수(조회가 끝난 뒤에만) -->
      <section class="rounded-(--panel-radius) bg-(--pastel-lavender-bg) px-6 py-10 text-(--pastel-lavender-ink) md:px-12 md:py-14">
        <p class="font-mono text-xs font-medium uppercase tracking-widest opacity-80">Search</p>
        <h1 class="mt-2 break-all text-3xl font-bold tracking-tight md:text-4xl" data-testid="search-title">{{ vm.title }}</h1>
        <p v-if="vm.keyword && vm.list && !vm.list.pending && !vm.list.hasError" class="mt-3 text-sm" data-testid="search-count">
          <span class="font-mono font-semibold">{{ vm.list.totalCount.toLocaleString('ko-KR') }}</span>개의 상품
        </p>
      </section>

      <div v-if="!vm.keyword" class="mt-10">
        <CommonEmptyState message="검색어를 입력하세요" />
      </div>

      <template v-else-if="vm.list">
        <div class="mt-8 flex justify-end">
          <RenewSortMenu :model-value="vm.list.sort" :options="vm.list.sortOptions" @update:model-value="vm.list.setSort" />
        </div>

        <!-- 그리드: 로딩 · 오류 · 빈 결과 · 목록 -->
        <div class="mt-10">
          <div v-if="vm.list.pending" :class="PRODUCT_GRID" aria-hidden="true">
            <div v-for="index in SKELETON_COUNT" :key="index">
              <div class="aspect-square rounded-card bg-(--image-placeholder)"></div>
              <div class="mt-3 h-3 w-2/3 rounded-full bg-surface-card"></div>
              <div class="mt-2 h-3 w-1/3 rounded-full bg-surface-card"></div>
            </div>
          </div>
          <CommonErrorState v-else-if="vm.list.hasError" @retry="vm.list.retry()" />
          <div
            v-else-if="vm.list.items.length === 0"
            :class="[ENTER, 'flex flex-col items-center rounded-[28px] bg-white px-6 py-16 text-center']"
            data-testid="search-empty"
          >
            <span class="flex h-20 w-20 items-center justify-center rounded-full bg-surface-card text-primary" aria-hidden="true">
              <SearchX class="h-10 w-10" :stroke-width="1.6" />
            </span>
            <p class="mt-6 break-all text-lg font-bold text-ink">'{{ vm.keyword }}'에 맞는 상품을 찾지 못했어요</p>
            <p class="mt-2 text-sm text-sub">다른 검색어로 찾아보거나 전체 상품을 둘러보세요.</p>
            <NuxtLink
              to="/products"
              class="mt-6 inline-flex min-h-11 items-center rounded-full bg-primary px-8 text-sm font-bold text-primary-foreground transition duration-200 hover:bg-primary-hover focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2"
              data-testid="search-empty-products-link"
            >
              전체 상품 보기
            </NuxtLink>
          </div>
          <div v-else :class="PRODUCT_GRID">
            <RenewProductCard v-for="item in vm.list.items" :key="item.productPublicId" :product="item" />
          </div>
        </div>

        <RenewPagination :page="vm.list.page" :total-pages="vm.list.totalPages" @change="vm.list.goToPage" />
      </template>
    </div>
  </div>
</template>
