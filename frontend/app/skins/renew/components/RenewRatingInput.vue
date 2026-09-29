<script setup lang="ts">
import { Star } from '@lucide/vue'
import { REVIEW_RATING_LABELS, REVIEW_RATING_MAX } from '~/lib/constants/review'

// renew 별점 입력(Track 106-1). 실제 radio 5개(숨김) + 별 모양 label — 키보드(←→·↑↓·Tab)와 스크린리더는 radio 그룹 그대로 동작한다.
// 포인터를 올리면 그 점수까지 미리 채워 보인다(누르기 전 확인). 이동·확대는 motion-safe에서만.
const props = withDefaults(
  defineProps<{ modelValue: number | null; name: string; label: string; size?: 'md' | 'lg'; disabled?: boolean }>(),
  { size: 'lg', disabled: false },
)
const emit = defineEmits<{ 'update:modelValue': [rating: number] }>()

const SIZE_CLASSES: Record<'md' | 'lg', string> = { md: 'h-8 w-8', lg: 'h-11 w-11' }
const ratings = Array.from({ length: REVIEW_RATING_MAX }, (_, index) => index + 1)

const hovered = ref<number | null>(null)
const shown = computed(() => hovered.value ?? props.modelValue ?? 0)
</script>

<template>
  <div role="radiogroup" :aria-label="label" class="inline-flex items-center gap-1" data-testid="rating-input" @pointerleave="hovered = null">
    <label
      v-for="rating in ratings"
      :key="rating"
      :class="[
        'group relative flex cursor-pointer items-center justify-center rounded-full has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-primary has-[:focus-visible]:ring-offset-2',
        size === 'lg' ? 'h-12 w-12' : 'h-10 w-10',
        disabled ? 'cursor-default opacity-40' : '',
      ]"
      @pointerenter="disabled ? undefined : (hovered = rating)"
    >
      <input
        type="radio"
        class="sr-only"
        :name="name"
        :value="rating"
        :checked="modelValue === rating"
        :disabled="disabled"
        :aria-label="`${rating}점 ${REVIEW_RATING_LABELS[rating]}`"
        :data-testid="`rating-input-${rating}`"
        @change="emit('update:modelValue', rating)"
      />
      <Star
        :class="[
          SIZE_CLASSES[size],
          'transition duration-fast ease-soft motion-safe:group-active:scale-90',
          rating <= shown ? 'text-primary' : 'text-line',
        ]"
        fill="currentColor"
        :stroke-width="0"
        aria-hidden="true"
      />
    </label>
  </div>
</template>
