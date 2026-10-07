<script setup lang="ts">
import { MessageSquareQuote } from '@lucide/vue'
import type { ProductReviewsVm } from '~/skins/contracts/product-reviews'
import { REVIEW_SORT_OPTIONS } from '~/lib/constants/review'
import { PRODUCT_SECTION_IDS } from '~/lib/constants/product-sections'
import ProductReviewCard from './ProductReviewCard.vue'
import RenewLightbox from './RenewLightbox.vue'
import RenewMoreButton from './RenewMoreButton.vue'
import RenewPhotoStrip from './RenewPhotoStrip.vue'
import RenewRatingBar from './RenewRatingBar.vue'
import RenewRatingStars from './RenewRatingStars.vue'

// 상품 상세 리뷰 섹션(Track 106-1 PR2 · ProductDetailView 전용). 머리(리뷰 N · ★평균) → 요약(평균 + 분포 막대 · 키워드 집계 막대 · 한 줄 요약 말풍선)
// → 최근 사진 띠 → 필터 칩(전체 · 사진만 · 키워드 · 옵션) + 정렬 → 카드 목록 → 더보기. 0건이면 빈 상태 하나만 보인다.
// 리뷰 0건이면 BE가 averageRating·summaryText 키를 빼므로(NON_NULL) 평균·말풍선 블록을 그리지 않는다.
const props = defineProps<{ reviews: ProductReviewsVm }>()

const reviewCount = computed(() => props.reviews.summary?.reviewCount ?? props.reviews.totalCount)
const averageRating = computed(() => props.reviews.summary?.averageRating)

// 확대 보기: 최근 사진 띠 또는 카드 사진에서 연다(같은 라이트박스 하나).
const lightboxOpen = ref(false)
const lightboxPhotos = ref<string[]>([])
const lightboxStart = ref(0)
function openLightbox(photos: string[], index: number): void {
  lightboxPhotos.value = photos
  lightboxStart.value = index
  lightboxOpen.value = true
}

function onOptionChange(event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  props.reviews.setOption(value === '' ? null : value)
}

const CARD = 'rounded-card bg-white px-5 py-8 shadow-e1 md:px-10 md:py-10'
</script>

<template>
  <section
    :id="PRODUCT_SECTION_IDS.reviews"
    :class="['mt-20 scroll-mt-(--product-section-offset)', CARD]"
    aria-labelledby="product-reviews-title"
    data-testid="product-reviews"
  >
    <!-- 머리 -->
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <p class="text-caption uppercase tracking-[0.12em] text-sub">Reviews</p>
        <h2 id="product-reviews-title" class="mt-1 flex flex-wrap items-center gap-x-3 text-h2 text-ink">
          <!-- 건수: 첫 페이지 응답 전·조회 실패 시 기본값 0을 보이지 않는다(아래 빈 상태의 !reviews.pending과 같은 판정 · FE-111 · UX-02). -->
          <span>리뷰 <span v-if="!reviews.pending && !reviews.failed" class="tabular-nums" data-testid="product-reviews-count">{{ reviewCount.toLocaleString('ko-KR') }}</span></span>
          <span v-if="averageRating !== undefined" class="inline-flex items-center gap-1.5 text-h3" data-testid="product-reviews-average">
            <RenewRatingStars :value="averageRating" size="md" />
            <span class="tabular-nums">{{ averageRating.toFixed(1) }}</span>
          </span>
        </h2>
      </div>
    </div>

    <!-- 불러오기 실패 -->
    <div v-if="reviews.failed && reviews.items.length === 0" class="mt-8">
      <CommonErrorState message="리뷰를 불러오지 못했습니다" @retry="reviews.retry" />
    </div>

    <!-- 0건(필터 없음): 빈 상태 — 필터 중 0건은 아래 목록 자리의 "조건에 맞는 리뷰가 없어요"로 두어 필터를 되돌릴 수 있게 한다. -->
    <div
      v-else-if="reviewCount === 0 && !reviews.pending && !reviews.isFiltered"
      class="mt-8 flex flex-col items-center gap-4 py-12 text-center"
      data-testid="product-reviews-empty"
    >
      <MessageSquareQuote class="h-14 w-14 text-line" :stroke-width="1.5" aria-hidden="true" />
      <p class="text-sub">첫 리뷰를 기다리고 있어요</p>
    </div>

    <template v-else>
      <!-- 요약: 평균 + 분포 · 키워드 집계 · 한 줄 요약 -->
      <div v-if="reviews.summary && averageRating !== undefined" class="mt-8 grid gap-8 md:grid-cols-2 md:gap-12" data-testid="product-reviews-summary">
        <div class="flex items-center gap-6">
          <div class="shrink-0 text-center">
            <p class="text-display font-bold tabular-nums text-ink">{{ averageRating.toFixed(1) }}</p>
            <RenewRatingStars :value="averageRating" size="sm" />
          </div>
          <div class="min-w-0 flex-1 space-y-1.5" aria-label="별점 분포">
            <RenewRatingBar v-for="bar in reviews.ratingBars" :key="bar.key" :label="bar.label" :ratio="bar.ratio" :value-text="bar.valueText" :strong="bar.strong" />
          </div>
        </div>
        <div v-if="reviews.keywordBars.length > 0" class="space-y-1.5" aria-label="많이 고른 키워드" data-testid="product-reviews-keywords">
          <RenewRatingBar v-for="bar in reviews.keywordBars" :key="bar.key" :label="bar.label" :ratio="bar.ratio" :value-text="bar.valueText" stacked />
        </div>
      </div>

      <!-- 한 줄 요약 말풍선(summaryText 없으면 생략) -->
      <div v-if="reviews.summary?.summaryText" class="mt-6 flex items-start gap-3" data-testid="product-reviews-summary-text">
        <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-(--pastel-lavender-bg) text-(--pastel-lavender-ink)" aria-hidden="true">
          <MessageSquareQuote class="h-5 w-5" />
        </span>
        <p class="relative rounded-card rounded-tl-md bg-surface-muted px-4 py-3 text-body text-ink">
          <span class="sr-only">리뷰 한 줄 요약: </span>{{ reviews.summary.summaryText }}
        </p>
      </div>

      <!-- 최근 사진 띠 -->
      <div v-if="reviews.recentPhotoThumbnails.length > 0" class="mt-8">
        <RenewPhotoStrip :photos="reviews.recentPhotoThumbnails" @open="(index: number) => openLightbox(reviews.recentPhotoUrls, index)" />
      </div>

      <!-- 필터 · 정렬 -->
      <div class="mt-8 flex flex-col gap-3 border-t border-line pt-6 md:flex-row md:items-start md:justify-between">
        <div class="flex flex-wrap gap-2" role="group" aria-label="리뷰 필터" data-testid="product-reviews-filters">
          <button type="button" class="chip" :aria-pressed="!reviews.isFiltered" data-testid="review-filter-all" @click="reviews.resetFilter()">전체</button>
          <button
            type="button"
            class="chip"
            :aria-pressed="reviews.filter.photoOnly"
            data-testid="review-filter-photo"
            @click="reviews.setPhotoOnly(!reviews.filter.photoOnly)"
          >
            사진만
          </button>
          <button
            v-for="keyword in reviews.summary?.keywords ?? []"
            :key="keyword.code"
            type="button"
            class="chip"
            :aria-pressed="reviews.filter.keyword === keyword.code"
            :data-testid="`review-filter-keyword-${keyword.code}`"
            @click="reviews.setKeyword(reviews.filter.keyword === keyword.code ? null : keyword.code)"
          >
            {{ keyword.label }}
          </button>
          <select
            v-if="reviews.optionChoices.length > 0"
            :value="reviews.filter.option ?? ''"
            aria-label="옵션으로 거르기"
            :class="['chip appearance-none pr-4', reviews.filter.option ? 'border-primary text-primary' : '']"
            data-testid="review-filter-option"
            @change="onOptionChange"
          >
            <option value="">옵션 전체</option>
            <option v-for="choice in reviews.optionChoices" :key="choice" :value="choice">{{ choice }}</option>
          </select>
        </div>
        <div class="flex shrink-0 gap-1 rounded-full bg-surface-muted p-1" role="group" aria-label="정렬" data-testid="review-sort">
          <button
            v-for="option in REVIEW_SORT_OPTIONS"
            :key="option.value"
            type="button"
            :class="[
              'min-h-11 rounded-full px-4 text-small font-semibold transition duration-fast ease-soft focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary md:min-h-9',
              reviews.filter.sort === option.value ? 'bg-white text-ink shadow-e1' : 'text-sub hover:text-ink',
            ]"
            :aria-pressed="reviews.filter.sort === option.value"
            :data-testid="`review-sort-${option.value}`"
            @click="reviews.setSort(option.value)"
          >
            {{ option.label }}
          </button>
        </div>
      </div>

      <!-- 목록 -->
      <div v-if="reviews.pending" class="space-y-4 py-6" aria-hidden="true">
        <div v-for="index in 2" :key="index" class="space-y-2">
          <div class="h-3 w-24 rounded-full bg-surface-muted"></div>
          <div class="h-4 w-3/4 rounded-full bg-surface-muted"></div>
        </div>
      </div>
      <p v-else-if="reviews.items.length === 0" class="py-10 text-center text-sub" data-testid="product-reviews-filter-empty">조건에 맞는 리뷰가 없어요</p>
      <div v-else class="divide-y divide-line" data-testid="product-reviews-list">
        <ProductReviewCard
          v-for="item in reviews.items"
          :key="item.reviewId"
          :item="item"
          :helpful-pending="reviews.helpfulPendingId === item.reviewId"
          :helpful-disabled="item.writtenByMe === true || reviews.ownReviewIds.includes(item.reviewId)"
          :helpful-notice="reviews.helpfulNotice?.reviewId === item.reviewId ? reviews.helpfulNotice.text : ''"
          @open-photo="(index: number) => openLightbox(item.photos.map((photo) => photo.url), index)"
          @toggle-helpful="reviews.toggleHelpful(item)"
        />
      </div>

      <div v-if="reviews.hasNext" class="mt-4 flex justify-center">
        <RenewMoreButton :loading="reviews.loadingMore" label="리뷰 더보기" @more="reviews.loadMore()" />
      </div>
    </template>

    <RenewLightbox v-model:open="lightboxOpen" :photos="lightboxPhotos" :start-index="lightboxStart" />
  </section>
</template>
