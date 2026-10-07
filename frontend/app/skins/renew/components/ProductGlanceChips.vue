<script setup lang="ts">
import { MessageCircleQuestionMark, Star } from '@lucide/vue'
import type { ProductQuestionsVm } from '~/skins/contracts/product-questions'
import type { ProductReviewsVm } from '~/skins/contracts/product-reviews'
import type { ProductSectionNavVm } from '~/skins/contracts/product-section-nav'
import { PRODUCT_SECTION_IDS } from '~/lib/constants/product-sections'

// 한눈에 칩(Track 106-2 · 구매 영역 가격 아래). 긴 상품 설명 아래에 묻힌 리뷰·Q&A를 구매 결정 지점에서 바로 보이게 한다.
// 리뷰 칩 "★평균 · 리뷰 n"(0건이면 "첫 리뷰를 기다려요") · 최상위 키워드 1개(있을 때만) · Q&A 칩 "Q&A n"(0건이면 "궁금한 점 물어보기").
// 리뷰 칩·키워드 칩은 리뷰 섹션으로, Q&A 칩은 묻기 섹션으로 이동하며 입력창에 포커스를 준다.
const props = defineProps<{
  reviews: Pick<ProductReviewsVm, 'summary' | 'totalCount' | 'pending' | 'failed'> | null
  questions: Pick<ProductQuestionsVm, 'totalCount' | 'pending' | 'failed'> | null
  nav: ProductSectionNavVm
}>()

const reviewCount = computed(() => (props.reviews ? props.reviews.summary?.reviewCount ?? props.reviews.totalCount : 0))
const averageRating = computed(() => props.reviews?.summary?.averageRating)
const topKeyword = computed(() => (reviewCount.value > 0 ? props.reviews?.summary?.keywords[0]?.label : undefined))
</script>

<template>
  <ul v-if="reviews || questions" class="mt-4 flex flex-wrap gap-2" aria-label="리뷰·Q&A 한눈에 보기" data-testid="product-glance-chips">
    <!-- 리뷰·Q&A 칩: 첫 페이지 응답 전에는 기본값 0이 빈 상태 문구로 바뀌지 않게 칩을 그리지 않는다(섹션의 pending 판정과 같음 · FE-111).
         조회 실패 시에도 같은 이유로 그리지 않는다(UX-02). -->
    <li v-if="reviews && !reviews.pending && !reviews.failed">
      <button type="button" class="chip" data-testid="glance-review" @click="nav.go(PRODUCT_SECTION_IDS.reviews)">
        <template v-if="reviewCount > 0">
          <Star class="h-4 w-4 fill-current text-(--pastel-butter-ink)" aria-hidden="true" />
          <span v-if="averageRating !== undefined" class="tabular-nums">{{ averageRating.toFixed(1) }}</span>
          <span aria-hidden="true">·</span>
          <span>리뷰 <span class="tabular-nums">{{ reviewCount.toLocaleString('ko-KR') }}</span></span>
        </template>
        <template v-else>첫 리뷰를 기다려요</template>
      </button>
    </li>
    <li v-if="topKeyword">
      <button type="button" class="chip" data-testid="glance-keyword" @click="nav.go(PRODUCT_SECTION_IDS.reviews)">“{{ topKeyword }}”</button>
    </li>
    <li v-if="questions && !questions.pending && !questions.failed">
      <button type="button" class="chip" data-testid="glance-questions" @click="nav.ask()">
        <MessageCircleQuestionMark class="h-4 w-4" aria-hidden="true" />
        <template v-if="questions.totalCount > 0">Q&amp;A <span class="tabular-nums">{{ questions.totalCount.toLocaleString('ko-KR') }}</span></template>
        <template v-else>궁금한 점 물어보기</template>
      </button>
    </li>
  </ul>
</template>
