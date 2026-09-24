<script setup lang="ts">
import type { HomePageVm } from '~/skins/contracts/home'
import { categoryTheme } from '../category-theme'
import CategoryIllustration from '../components/CategoryIllustration.vue'
import RenewProductCard from '../components/RenewProductCard.vue'
import SectionHeading from '../components/SectionHeading.vue'

// renew 메인. 섹션 데이터는 페이지가 useHomeCuration으로 조회해 vm으로 넘긴다(FE-69). 빈 섹션(조회 실패 포함)은 렌더하지 않는다.
// 테마·기획전 섹션은 운영 구조(D-220)와 함께 설계하므로 자리만 예약한다(데이터 없음·미렌더).
defineProps<{ vm: HomePageVm }>()

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
// 상품 열 수: ≥1280 5 · ≥1024 4 · ≥768 3 · 미만 2. 세로 간격은 호버 면(카드 바깥 12px)이 겹치지 않을 만큼.
const PRODUCT_GRID = 'grid grid-cols-2 gap-x-4 gap-y-10 md:grid-cols-3 md:gap-x-6 lg:grid-cols-4 xl:grid-cols-5'
const SECTION = 'mt-20'
const PILL = 'flex min-h-11 shrink-0 items-center whitespace-nowrap rounded-full px-5 text-sm font-bold transition duration-200'
// <768 한 줄 가로 스크롤 규칙(스크롤바 숨김·스냅·오른쪽 흐림). 호버로 떠오르는 카드가 잘리지 않게 위아래 여백을 두고 같은 만큼 음수 마진.
const HORIZONTAL_SCROLL_MOBILE =
  'flex max-md:-my-3 max-md:overflow-x-auto max-md:py-3 max-md:scrollbar-none max-md:snap-x max-md:snap-mandatory max-md:fade-right'
const ROUND_BUTTON =
  'flex h-11 w-11 items-center justify-center rounded-full border border-line bg-white text-ink transition duration-200 hover:bg-surface-muted focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary disabled:cursor-default disabled:opacity-40 disabled:hover:bg-white'
// 스크롤 위치 판정 여유(소수점 픽셀 오차).
const EDGE_TOLERANCE = 1

// 2만원 이하 줄의 양끝 도달 여부(버튼 비활성·흐림 제거). 화면 상태만 다룬다.
const budgetScroller = ref<HTMLElement | null>(null)
const budgetAtStart = ref(true)
const budgetAtEnd = ref(false)

function updateBudgetEdges(): void {
  const element = budgetScroller.value
  if (!element) return
  budgetAtStart.value = element.scrollLeft <= EDGE_TOLERANCE
  budgetAtEnd.value = element.scrollLeft + element.clientWidth >= element.scrollWidth - EDGE_TOLERANCE
}

// 보이는 폭만큼 이동(스냅이 카드 경계에 맞춘다). 움직임 줄이기 설정이면 즉시 이동.
function scrollBudget(direction: 1 | -1): void {
  const element = budgetScroller.value
  if (!element) return
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  element.scrollBy({ left: direction * element.clientWidth, behavior: reduceMotion ? 'auto' : 'smooth' })
}

// 섹션은 데이터가 온 뒤 나타날 수 있어(클라이언트 이동) 요소가 생길 때도 판정한다.
watch(budgetScroller, updateBudgetEdges, { flush: 'post' })
onMounted(() => {
  updateBudgetEdges()
  window.addEventListener('resize', updateBudgetEdges)
})
onBeforeUnmount(() => window.removeEventListener('resize', updateBudgetEdges))
</script>

<template>
  <div class="pb-8 pt-6 md:pt-10">
    <!-- 히어로: 문구·버튼은 classic HomeHero와 같다. 이미지 자리는 placeholder 면. -->
    <section :class="CONTAINER">
      <div class="grid overflow-hidden rounded-(--panel-radius) bg-surface-card lg:grid-cols-2">
        <div class="flex flex-col justify-center px-6 py-12 md:px-12 md:py-16 lg:px-16">
          <p class="font-mono text-sm font-medium uppercase tracking-widest text-sub">zslab-mall</p>
          <h1 class="mt-4 text-4xl font-bold leading-tight tracking-tight text-ink md:text-5xl">
            당신의 취향을<br />가장 먼저 만나는 곳
          </h1>
          <p class="mt-5 text-lg text-sub">엄선한 신상품을 시원하게 둘러보세요.</p>
          <div>
            <NuxtLink
              to="/products"
              class="mt-8 inline-flex min-h-11 items-center rounded-full bg-primary px-8 text-sm font-bold text-primary-foreground transition duration-200 hover:bg-primary-hover focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2"
            >
              지금 둘러보기
            </NuxtLink>
          </div>
        </div>
        <div class="min-h-56 bg-(--image-placeholder) md:min-h-80" aria-hidden="true"></div>
      </div>
    </section>

    <!-- 카테고리 카드 -->
    <section v-if="vm.categories.length > 0" :class="[CONTAINER, SECTION]">
      <SectionHeading tag="Categories" title="카테고리" />
      <!-- <768: 한 줄 가로 스크롤(카드 폭 고정). ≥768: 격자. -->
      <ul :class="[HORIZONTAL_SCROLL_MOBILE, 'gap-4 md:grid md:grid-cols-3 xl:grid-cols-6']">
        <li v-for="category in vm.categories" :key="category.categoryId" class="max-md:w-36 max-md:shrink-0 max-md:snap-start">
          <NuxtLink
            :to="`/categories/${category.categoryId}`"
            class="flex h-full flex-col justify-between gap-6 rounded-card p-5 transition duration-300 ease-out hover:shadow-[0_16px_32px_-18px_rgba(34,31,43,0.35)] focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary motion-safe:hover:-translate-y-1.5"
            :style="{ background: categoryTheme(category.displayName).background, color: categoryTheme(category.displayName).ink }"
          >
            <CategoryIllustration :name="categoryTheme(category.displayName).illustration" class="h-12 w-12" />
            <span class="text-base font-bold">{{ category.displayName }}</span>
          </NuxtLink>
        </li>
      </ul>
    </section>

    <!-- 새로 들어온 -->
    <section v-if="vm.newArrivals.length > 0" :class="[CONTAINER, SECTION]">
      <SectionHeading tag="New arrivals" title="새로 들어온" more-to="/products" />
      <div :class="PRODUCT_GRID">
        <RenewProductCard v-for="item in vm.newArrivals" :key="item.productPublicId" :product="item" />
      </div>
    </section>

    <!-- 많이 찾는: 카드 면 띠(FE-76부터 흰색 — 띠 면 파스텔 전환은 화면 단계) + 순위 -->
    <section v-if="vm.popular.length > 0" :class="[CONTAINER, SECTION]">
      <div class="rounded-(--panel-radius) bg-surface-card px-5 py-10 md:px-10">
        <SectionHeading tag="Best 7 days" title="많이 찾는" />
        <div :class="PRODUCT_GRID">
          <RenewProductCard
            v-for="(item, index) in vm.popular"
            :key="item.productPublicId"
            :product="item"
            :rank="index + 1"
          />
        </div>
      </div>
    </section>

    <!-- 카테고리별 추천: 알약형 탭 -->
    <section v-if="vm.categories.length > 0 && (vm.categoryPicks.length > 0 || vm.categoryPicksPending)" :class="[CONTAINER, SECTION]">
      <SectionHeading
        tag="By category"
        title="카테고리별 추천"
        :more-to="vm.activeCategoryId === null ? undefined : `/categories/${vm.activeCategoryId}`"
      />
      <div role="tablist" aria-label="추천 카테고리" class="-mx-1 mb-8 flex gap-2 overflow-x-auto px-1 pb-1">
        <button
          v-for="category in vm.categories"
          :key="category.categoryId"
          type="button"
          role="tab"
          :aria-selected="vm.activeCategoryId === category.categoryId"
          :class="[PILL, vm.activeCategoryId === category.categoryId ? 'bg-primary text-primary-foreground' : 'bg-surface-muted text-ink hover:bg-(--pastel-lavender-bg)']"
          @click="vm.selectCategory(category.categoryId)"
        >
          {{ category.displayName }}
        </button>
      </div>
      <div :class="[PRODUCT_GRID, vm.categoryPicksPending ? 'opacity-60' : '']">
        <RenewProductCard v-for="item in vm.categoryPicks" :key="item.productPublicId" :product="item" />
      </div>
    </section>

    <!-- 셀러 픽: 셀러 카드 3 -->
    <section v-if="vm.sellerPicks.length > 0" :class="[CONTAINER, SECTION]">
      <SectionHeading tag="Seller pick" title="셀러 픽" />
      <ul class="grid gap-6 md:grid-cols-3">
        <li v-for="pick in vm.sellerPicks" :key="pick.sellerPublicId" class="rounded-card border border-line bg-white p-5">
          <p class="font-mono text-xs uppercase tracking-widest text-sub">Seller</p>
          <p class="mt-1 truncate text-lg font-bold text-ink">{{ pick.sellerName }}</p>
          <ul class="mt-5 grid grid-cols-3 gap-3">
            <li v-for="item in pick.items" :key="item.productPublicId">
              <NuxtLink :to="`/products/${item.productPublicId}`" :aria-label="item.name" class="group block">
                <div class="aspect-square overflow-hidden rounded-2xl bg-(--image-placeholder)">
                  <img
                    v-if="item.mainImageUrl"
                    :src="item.mainImageUrl"
                    :alt="item.name"
                    loading="lazy"
                    class="h-full w-full object-cover transition duration-500 ease-out motion-safe:group-hover:scale-[1.04]"
                  />
                </div>
                <p class="mt-2 truncate text-xs text-ink">{{ item.name }}</p>
                <p class="font-mono text-xs font-semibold text-ink">{{ item.displayPrice.toLocaleString('ko-KR') }}원</p>
              </NuxtLink>
            </li>
          </ul>
        </li>
      </ul>
    </section>

    <!-- 2만원 이하: 한 줄 가로 스크롤(카드 폭 고정·스냅). ≥1024 = 제목 줄 좌우 버튼(양끝 비활성·흐림 없음),
         <1024 = 스와이프 + 오른쪽 흐림(바탕색·끝에 닿으면 제거). 호버 면이 잘리지 않게 줄 안쪽 여백을 두고 같은 만큼 음수 마진. -->
    <section v-if="vm.budget.length > 0" :class="[CONTAINER, SECTION]">
      <SectionHeading :tag="`Under ${vm.budgetMaxPrice.toLocaleString('ko-KR')}`" title="2만원 이하">
        <div class="hidden gap-2 lg:flex">
          <button
            type="button"
            :class="ROUND_BUTTON"
            :disabled="budgetAtStart"
            aria-label="이전 상품 보기"
            @click="scrollBudget(-1)"
          >
            <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M15 6l-6 6 6 6" />
            </svg>
          </button>
          <button
            type="button"
            :class="ROUND_BUTTON"
            :disabled="budgetAtEnd"
            aria-label="다음 상품 보기"
            @click="scrollBudget(1)"
          >
            <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M9 6l6 6-6 6" />
            </svg>
          </button>
        </div>
      </SectionHeading>
      <div class="relative -mx-4 -my-5">
        <ul
          ref="budgetScroller"
          class="flex snap-x snap-mandatory scroll-px-4 gap-6 overflow-x-auto px-4 py-5 scrollbar-none"
          @scroll.passive="updateBudgetEdges"
        >
          <li v-for="item in vm.budget" :key="item.productPublicId" class="w-40 shrink-0 snap-start md:w-52">
            <RenewProductCard :product="item" />
          </li>
        </ul>
        <div
          v-show="!budgetAtEnd"
          class="pointer-events-none absolute inset-y-0 right-0 w-12 bg-linear-to-r from-transparent to-surface-page lg:hidden"
          aria-hidden="true"
        ></div>
      </div>
    </section>
  </div>
</template>
