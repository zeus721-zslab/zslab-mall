<script setup lang="ts">
import { Star } from '@lucide/vue'
import { REVIEW_RATING_MAX } from '~/lib/constants/review'

// renew 별점 표시(Track 106-1). 소수 평균은 별마다 채움 폭으로 그린다(4.3 = 별 4개 + 다섯째 30%). 읽기는 한 덩어리 이미지로 "5점 만점에 N점".
// 색은 채움 = 포인트색(primary) · 빈 별 = 선 색(line).
const props = withDefaults(defineProps<{ value: number; size?: 'sm' | 'md' | 'lg' }>(), { size: 'md' })

const SIZE_CLASSES: Record<'sm' | 'md' | 'lg', string> = {
  sm: 'h-3.5 w-3.5',
  md: 'h-4 w-4',
  lg: 'h-6 w-6',
}

const clamped = computed(() => Math.min(Math.max(props.value, 0), REVIEW_RATING_MAX))
// 별 하나의 채움 비율(0~100%).
const fills = computed(() =>
  Array.from({ length: REVIEW_RATING_MAX }, (_, index) => `${Math.round(Math.min(Math.max(clamped.value - index, 0), 1) * 100)}%`),
)
const label = computed(() => `별점 ${REVIEW_RATING_MAX}점 만점에 ${Number.isInteger(clamped.value) ? clamped.value : clamped.value.toFixed(1)}점`)
</script>

<template>
  <span role="img" :aria-label="label" class="inline-flex items-center gap-0.5" data-testid="rating-stars" :data-value="clamped">
    <span v-for="(fill, index) in fills" :key="index" :class="['relative inline-block shrink-0', SIZE_CLASSES[size]]" aria-hidden="true">
      <Star :class="['absolute inset-0 text-line', SIZE_CLASSES[size]]" fill="currentColor" :stroke-width="0" />
      <span class="absolute inset-y-0 left-0 overflow-hidden" :style="{ width: fill }">
        <Star :class="['text-primary', SIZE_CLASSES[size]]" fill="currentColor" :stroke-width="0" />
      </span>
    </span>
  </span>
</template>
