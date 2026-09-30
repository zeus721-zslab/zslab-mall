<script setup lang="ts">
import type { ProductQuestionSuggestion } from '~/types/product-question'
import { suggestionTypeLabel } from '~/lib/constants/product-question'
import RenewBadge from './RenewBadge.vue'

// 즉시 답 카드 목록(Track 106-2 · 묻기 섹션과 섹션 바의 물어보기 패널이 같은 카드를 쓴다). 유형 라벨(Q&A·리뷰·상품 설명) · Q&A는 답변을 함께 보인다.
defineProps<{ suggestions: ProductQuestionSuggestion[] }>()
</script>

<template>
  <div data-testid="product-question-suggestions">
    <p class="text-small font-semibold text-sub">이런 답이 있어요</p>
    <ul class="mt-2 space-y-2">
      <li
        v-for="(suggestion, index) in suggestions"
        :key="`${suggestion.type}-${suggestion.id ?? index}`"
        class="rounded-card bg-surface-muted px-4 py-3"
        :data-type="suggestion.type"
        data-testid="product-question-suggestion"
      >
        <RenewBadge :tone="suggestion.type === 'QNA' ? 'info' : suggestion.type === 'REVIEW' ? 'success' : 'neutral'" data-testid="suggestion-type">
          {{ suggestionTypeLabel(suggestion.type) }}
        </RenewBadge>
        <p class="mt-2 whitespace-pre-line break-keep text-body text-ink">
          <span v-if="suggestion.type === 'QNA'" class="font-semibold">Q. </span>{{ suggestion.text }}
        </p>
        <p v-if="suggestion.answer" class="mt-1 whitespace-pre-line break-keep text-body text-sub" data-testid="suggestion-answer">
          <span class="font-semibold text-ink">A. </span>{{ suggestion.answer }}
        </p>
      </li>
    </ul>
  </div>
</template>
