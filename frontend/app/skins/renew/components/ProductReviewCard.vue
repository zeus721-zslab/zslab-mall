<script setup lang="ts">
import { ThumbsUp } from '@lucide/vue'
import type { ReviewItem } from '~/types/review'
import { formatDateTime } from '~/lib/utils/datetime'
import RenewRatingStars from './RenewRatingStars.vue'

// 상품 상세 리뷰 카드(Track 106-1 PR2 · ProductReviewSection 전용). 별 · 날짜 · 옵션 · 키워드 칩 · 본문(3줄 접힘/펼침) · 사진 썸네일 · 도움됐어요.
// 본문은 길 때만(글자 수·줄 수 기준) 접고 펼치기 버튼을 둔다 — SSR과 첫 렌더가 같도록 측정 대신 문자열로 판단한다.
// 도움됐어요는 누를 때 잠깐 커졌다 돌아온다(motion-safe). 본인 리뷰로 판정되면(BE 422) 잠근다.
const props = defineProps<{
  item: ReviewItem
  helpfulPending: boolean
  helpfulDisabled: boolean
  helpfulNotice: string
}>()
const emit = defineEmits<{ openPhoto: [index: number]; toggleHelpful: [] }>()

// 이 글자 수나 줄 수를 넘으면 3줄로 접는다(본문 폭 기준 대략 3줄).
const CLAMP_CHARACTERS = 120
const CLAMP_LINES = 3

const expanded = ref(false)
const collapsible = computed(() => props.item.content.length > CLAMP_CHARACTERS || props.item.content.split('\n').length > CLAMP_LINES)
const dateText = computed(() => formatDateTime(props.item.createdAt).slice(0, 10))

const bump = ref(false)
function onHelpful(): void {
  bump.value = true
  emit('toggleHelpful')
}
</script>

<template>
  <article class="py-6" data-testid="review-card">
    <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
      <RenewRatingStars :value="item.rating" size="sm" />
      <span class="text-caption font-normal tabular-nums text-sub">{{ dateText }}</span>
    </div>
    <p v-if="item.optionLabel" class="mt-1 text-caption font-normal text-sub" data-testid="review-card-option">{{ item.optionLabel }}</p>

    <ul v-if="item.keywords.length > 0" class="mt-3 flex flex-wrap gap-1.5" aria-label="선택한 키워드">
      <li
        v-for="keyword in item.keywords"
        :key="keyword.code"
        class="inline-flex h-7 items-center rounded-full bg-surface-muted px-3 text-caption font-semibold text-(--pastel-lavender-ink)"
      >
        {{ keyword.label }}
      </li>
    </ul>

    <p
      :id="`review-content-${item.reviewId}`"
      :class="['mt-3 whitespace-pre-line break-keep text-body text-ink', collapsible && !expanded ? 'line-clamp-3' : '']"
      data-testid="review-card-content"
    >{{ item.content }}</p>
    <button
      v-if="collapsible"
      type="button"
      class="btn btn-tertiary btn-sm -ml-4 mt-1"
      :aria-expanded="expanded"
      :aria-controls="`review-content-${item.reviewId}`"
      data-testid="review-card-expand"
      @click="expanded = !expanded"
    >
      {{ expanded ? '접기' : '펼치기' }}
    </button>

    <ul v-if="item.photos.length > 0" class="mt-3 flex flex-wrap gap-2" aria-label="리뷰 사진">
      <li v-for="(photo, index) in item.photos" :key="photo.url">
        <button
          type="button"
          class="block h-20 w-20 overflow-hidden rounded-[14px] bg-(--image-placeholder) focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary"
          :aria-label="`리뷰 사진 ${index + 1} 크게 보기`"
          data-testid="review-card-photo"
          @click="emit('openPhoto', index)"
        >
          <img :src="photo.thumbnailUrl" alt="" loading="lazy" class="h-full w-full object-cover" />
        </button>
      </li>
    </ul>

    <div class="mt-4 flex flex-wrap items-center gap-3">
      <button
        type="button"
        :class="[
          'inline-flex min-h-11 items-center gap-1.5 rounded-full border px-4 text-small font-semibold transition duration-fast ease-soft focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary disabled:cursor-default disabled:opacity-40 md:min-h-9',
          item.helpedByMe ? 'border-primary bg-primary text-primary-foreground' : 'border-line bg-white text-ink hover:border-ink',
        ]"
        :aria-pressed="item.helpedByMe === true"
        :disabled="helpfulPending || helpfulDisabled"
        :title="helpfulDisabled ? '내가 쓴 리뷰에는 누를 수 없어요' : undefined"
        data-testid="review-card-helpful"
        @click="onHelpful"
      >
        <ThumbsUp
          :class="['h-4 w-4', bump ? 'motion-safe:animate-[review-helpful-bump_320ms_var(--ease-soft)]' : '']"
          aria-hidden="true"
          @animationend="bump = false"
        />
        도움돼요 <span class="tabular-nums" data-testid="review-card-helpful-count">{{ item.helpfulCount }}</span>
      </button>
      <p v-if="helpfulNotice" role="status" class="text-caption font-semibold text-sub" data-testid="review-card-helpful-notice">{{ helpfulNotice }}</p>
    </div>
  </article>
</template>

<style>
/* 도움됐어요 탭 반응: 잠깐 커졌다 돌아온다(motion-safe 클래스에서만 걸린다). */
@keyframes review-helpful-bump {
  40% {
    transform: scale(1.3);
  }
}
</style>
