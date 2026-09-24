<script setup lang="ts">
import type { HomePageVm } from '~/skins/contracts/home'
import { categoryThemes } from '../category-theme'
import CategoryIllustration from '../components/CategoryIllustration.vue'
import RenewProductCard from '../components/RenewProductCard.vue'
import SectionHeading from '../components/SectionHeading.vue'
import { buildHeroCollage } from '../hero-collage'
import { trackScrollEdges } from '../scroll-edges'

// renew 메인. 섹션 데이터는 페이지가 useHomeCuration으로 조회해 vm으로 넘긴다(FE-69). 빈 섹션(조회 실패 포함)은 렌더하지 않는다.
// 테마·기획전 섹션은 운영 구조(D-220)와 함께 설계하므로 자리만 예약한다(데이터 없음·미렌더).
const props = defineProps<{ vm: HomePageVm }>()

const heroTiles = computed(() => buildHeroCollage(props.vm.newArrivals))
// 카테고리 타일 테마는 목록 단위로 만든다(이웃 색 겹침·대체 아이콘 순환 — FE-82).
const categoryTiles = computed(() => {
  const themes = categoryThemes(props.vm.categories)
  return props.vm.categories.flatMap((category, index) => {
    const theme = themes[index]
    return theme ? [{ category, theme }] : []
  })
})

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
// 상품 열 수: ≥1280 5 · ≥1024 4 · ≥768 3 · 미만 2. 세로 간격은 호버 면(카드 바깥 12px)이 겹치지 않을 만큼.
const PRODUCT_GRID = 'grid grid-cols-2 gap-x-4 gap-y-10 md:grid-cols-3 md:gap-x-6 lg:grid-cols-4 xl:grid-cols-5'
// 많이 찾는 띠: ≥1024 4열 고정 + 1위 2열×2행(개수 무관 — 5개면 2행이 꽉 찬다·FE-77).
const POPULAR_GRID = 'grid grid-cols-2 gap-x-4 gap-y-10 md:grid-cols-3 md:gap-x-6 lg:grid-cols-4'
const POPULAR_FIRST = 'lg:col-span-2 lg:row-span-2'
const SECTION = 'mt-20'
// <768 한 줄 가로 스크롤 규칙(스크롤바 숨김·스냅). 오른쪽 흐림은 끝에 닿기 전까지만(FE-77). 호버로 떠오르는 카드가 잘리지 않게 위아래 여백을 두고 같은 만큼 음수 마진.
const HORIZONTAL_SCROLL_MOBILE =
  'flex max-md:-my-3 max-md:overflow-x-auto max-md:py-3 max-md:scrollbar-none max-md:snap-x max-md:snap-mandatory'
const ROUND_BUTTON =
  'flex h-11 w-11 items-center justify-center rounded-full border border-line bg-white text-ink transition duration-fast ease-soft hover:bg-surface-muted focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary disabled:cursor-default disabled:opacity-40 disabled:hover:bg-white'
const HERO_TILE = 'block h-full overflow-hidden rounded-card shadow-e1'
const HERO_TILE_LINK =
  'transition duration-fast ease-soft hover:shadow-e2 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary motion-safe:hover:-translate-y-1'

// 가로 스크롤 줄의 양끝 도달 여부(2만원 이하 버튼 비활성·흐림 제거 · 카테고리 줄 흐림 제거). 화면 상태만 다룬다.
const categoryScroller = ref<HTMLElement | null>(null)
const categoryEdges = trackScrollEdges(categoryScroller)
const budgetScroller = ref<HTMLElement | null>(null)
const budgetEdges = trackScrollEdges(budgetScroller)

// 보이는 폭만큼 이동(스냅이 카드 경계에 맞춘다). 움직임 줄이기 설정이면 즉시 이동.
function scrollBudget(direction: 1 | -1): void {
  const element = budgetScroller.value
  if (!element) return
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  element.scrollBy({ left: direction * element.clientWidth, behavior: reduceMotion ? 'auto' : 'smooth' })
}
</script>

<template>
  <div class="pb-8 pt-6 md:pt-10">
    <!-- 히어로: 라벤더 띠 면 한 장(FE-77). 오른쪽은 새로 들어온 앞 3개 콜라주(대체 규칙 hero-collage.ts) — ≥1024 2열(첫 칸 2행) ·
         768~1023 문구 아래 3칸 한 줄 · <768 3칸 한 줄(높이 132). 첫 화면이라 등장 모션 없음. -->
    <section :class="CONTAINER">
      <div class="rounded-(--panel-radius) bg-(--pastel-lavender-bg) px-6 py-10 md:px-12 md:py-14 lg:grid lg:grid-cols-2 lg:items-center lg:gap-12 lg:px-16">
        <div>
          <p class="text-caption uppercase tracking-[0.12em] text-(--pastel-lavender-ink)">zslab-mall</p>
          <h1 class="mt-4 text-display text-ink">당신의 취향을<br />가장 먼저 만나는 곳</h1>
          <!-- 리드 문구 17/28은 히어로 전용 예외(FE-77) -->
          <p class="mt-5 text-[1.0625rem] leading-7 text-sub">엄선한 신상품을 시원하게 둘러보세요.</p>
          <div class="mt-8 flex flex-wrap gap-3">
            <NuxtLink to="/products" class="btn btn-primary btn-lg">지금 둘러보기</NuxtLink>
            <a
              v-if="vm.categories.length > 0"
              href="#home-categories"
              class="btn btn-lg bg-white text-primary hover:bg-surface-muted"
            >
              카테고리 보기
            </a>
          </div>
        </div>
        <ul class="mt-10 grid h-33 grid-cols-3 gap-3 md:h-48 md:gap-4 lg:mt-0 lg:h-96 lg:grid-cols-2 lg:grid-rows-2">
          <li v-for="(tile, index) in heroTiles" :key="tile.key" :class="index === 0 ? 'lg:row-span-2' : ''">
            <NuxtLink
              v-if="tile.product"
              :to="`/products/${tile.product.productPublicId}`"
              :aria-label="tile.product.name"
              :class="[HERO_TILE, HERO_TILE_LINK]"
              :style="{ background: tile.pastel }"
            >
              <img
                v-if="tile.imageUrl"
                :src="tile.imageUrl"
                alt=""
                :loading="index === 0 ? undefined : 'lazy'"
                class="h-full w-full object-cover"
              />
            </NuxtLink>
            <div v-else :class="HERO_TILE" :style="{ background: tile.pastel }" aria-hidden="true"></div>
          </li>
        </ul>
      </div>
    </section>

    <!-- 카테고리 카드(히어로 "카테고리 보기" 앵커 — 고정 헤더 높이만큼 여백) -->
    <section
      v-if="vm.categories.length > 0"
      id="home-categories"
      :class="[CONTAINER, SECTION, 'scroll-mt-32 lg:scroll-mt-[calc(var(--header-height)+2rem)]']"
    >
      <SectionHeading tag="Categories" title="카테고리" />
      <!-- <768: 한 줄 가로 스크롤(카드 폭 고정). ≥768: 격자 4열 · ≥1024 6열, 타일 높이 상한 160(태블릿 과대 방지 — FE-82). -->
      <ul
        ref="categoryScroller"
        :class="[HORIZONTAL_SCROLL_MOBILE, 'gap-4 md:grid md:grid-cols-4 lg:grid-cols-6', categoryEdges.atEnd ? '' : 'max-md:fade-right']"
      >
        <li v-for="{ category, theme } in categoryTiles" :key="category.categoryId" class="max-md:w-36 max-md:shrink-0 max-md:snap-start">
          <NuxtLink
            :to="`/categories/${category.categoryId}`"
            class="flex h-full max-h-40 flex-col justify-between gap-6 rounded-card p-5 transition duration-fast ease-soft hover:shadow-e2 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary motion-safe:hover:-translate-y-1"
            :style="{ background: theme.background, color: theme.ink }"
          >
            <CategoryIllustration :name="theme.illustration" class="h-12 w-12" />
            <span class="text-h3">{{ category.displayName }}</span>
          </NuxtLink>
        </li>
      </ul>
    </section>

    <!-- 새로 들어온 -->
    <section v-if="vm.newArrivals.length > 0" :class="[CONTAINER, SECTION]">
      <SectionHeading tag="New arrivals" title="새로 들어온" size="h1" more-to="/products" />
      <div :class="PRODUCT_GRID">
        <RenewProductCard v-for="item in vm.newArrivals" :key="item.productPublicId" :product="item" />
      </div>
    </section>

    <!-- 많이 찾는: 옅은 라벤더 띠 면(surface-muted) + 순위. ≥1024 1위 확대. -->
    <section v-if="vm.popular.length > 0" :class="[CONTAINER, SECTION]">
      <div class="rounded-(--panel-radius) bg-surface-muted px-5 py-10 md:px-10">
        <SectionHeading tag="Best 7 days" title="많이 찾는" size="h1" />
        <div :class="POPULAR_GRID">
          <RenewProductCard
            v-for="(item, index) in vm.popular"
            :key="item.productPublicId"
            :product="item"
            :rank="index + 1"
            :class="index === 0 ? POPULAR_FIRST : ''"
          />
        </div>
      </div>
    </section>

    <!-- 카테고리별 추천: 칩 탭 -->
    <section v-if="vm.categories.length > 0 && (vm.categoryPicks.length > 0 || vm.categoryPicksPending)" :class="[CONTAINER, SECTION]">
      <SectionHeading
        tag="By category"
        title="카테고리별 추천"
        size="h1"
        :more-to="vm.activeCategoryId === null ? undefined : `/categories/${vm.activeCategoryId}`"
      />
      <div role="tablist" aria-label="추천 카테고리" class="-mx-1 mb-8 flex gap-2 overflow-x-auto px-1 pb-1">
        <button
          v-for="category in vm.categories"
          :key="category.categoryId"
          type="button"
          role="tab"
          :aria-selected="vm.activeCategoryId === category.categoryId"
          class="chip shrink-0"
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
        <li v-for="pick in vm.sellerPicks" :key="pick.sellerPublicId" class="rounded-card bg-white p-5 shadow-e1">
          <p class="text-caption uppercase tracking-[0.12em] text-sub">Seller</p>
          <p class="mt-1 truncate text-h3 text-ink">{{ pick.sellerName }}</p>
          <ul class="mt-5 grid grid-cols-3 gap-3">
            <!-- 링크는 상품명에만 걸고 after:inset-0으로 칸 전체를 누르게 넓힌다(Track 105-4g-3 · 상품 카드와 같은 접근 이름 규칙). -->
            <li v-for="item in pick.items" :key="item.productPublicId" class="group relative rounded-2xl">
              <div class="aspect-square overflow-hidden rounded-2xl bg-(--image-placeholder)">
                <img
                  v-if="item.mainImageUrl"
                  :src="item.mainImageUrl"
                  alt=""
                  loading="lazy"
                  class="h-full w-full object-cover transition duration-fast ease-soft motion-safe:group-hover:scale-[1.04]"
                />
              </div>
              <p class="mt-2 truncate text-caption font-normal text-ink">
                <NuxtLink
                  :to="`/products/${item.productPublicId}`"
                  class="after:absolute after:inset-0 after:rounded-2xl focus-visible:outline-hidden focus-visible:after:ring-2 focus-visible:after:ring-primary"
                >
                  {{ item.name }}
                </NuxtLink>
              </p>
              <p class="text-caption tabular-nums text-ink">{{ item.displayPrice.toLocaleString('ko-KR') }}원</p>
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
            :disabled="budgetEdges.atStart"
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
            :disabled="budgetEdges.atEnd"
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
        >
          <li v-for="item in vm.budget" :key="item.productPublicId" class="w-40 shrink-0 snap-start md:w-52">
            <RenewProductCard :product="item" />
          </li>
        </ul>
        <div
          v-show="!budgetEdges.atEnd"
          class="pointer-events-none absolute inset-y-0 right-0 w-12 bg-linear-to-r from-transparent to-surface-page lg:hidden"
          aria-hidden="true"
        ></div>
      </div>
    </section>
  </div>
</template>
