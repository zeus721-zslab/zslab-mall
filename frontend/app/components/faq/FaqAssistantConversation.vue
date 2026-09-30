<script setup lang="ts">
import { RotateCcw } from '@lucide/vue'
import type { FaqChip, FaqMessage } from '~/composables/useFaqAssistant'
import { type FaqCategory, faqCategoryLabel, orderedFaqCategories } from '~/lib/constants/faq'

// 채팅 도우미 대화 목록(Track 106-3): 말풍선 + 마지막 도우미 말풍선의 칩만 누를 수 있다(지난 칩은 흐름이 끝난 선택지라 그리지 않는다).
// 카테고리 칩은 현재 페이지 기준 첫 칩을 앞에 두고 강조한다 — 페이지를 옮기면 강조만 새 페이지로 바뀐다.
const props = defineProps<{
  messages: FaqMessage[]
  typing: boolean
  firstCategories: FaqCategory[]
}>()
const emit = defineEmits<{ chip: [chip: FaqChip]; category: [category: FaqCategory] }>()

const lastMessageId = computed(() => props.messages.at(-1)?.id)
const categories = computed(() => orderedFaqCategories(props.firstCategories))

function chipLabel(chip: FaqChip): string {
  switch (chip.kind) {
    case 'question':
      return chip.faq.question
    case 'moreInCategory':
      return `${faqCategoryLabel(chip.category)} 다른 질문`
    case 'restart':
      return '처음으로'
    case 'retry':
      return '다시 시도'
    case 'inquiry':
      return '운영자에게 남기기'
  }
}

function chipKey(chip: FaqChip, index: number): string {
  return chip.kind === 'question' ? `q-${chip.faq.id}` : `${chip.kind}-${index}`
}

const listElement = ref<HTMLElement | null>(null)
// 새 말풍선·타이핑 표시가 생기면 맨 아래로 내린다.
watch(() => [props.messages.length, props.typing], async () => {
  await nextTick()
  listElement.value?.scrollTo({ top: listElement.value.scrollHeight })
}, { flush: 'post' })
onMounted(() => listElement.value?.scrollTo({ top: listElement.value.scrollHeight }))
</script>

<template>
  <div
    ref="listElement"
    class="flex-1 space-y-3 overflow-y-auto px-4 py-4"
    role="log"
    aria-live="polite"
    aria-label="도우미 대화"
    data-testid="faq-conversation"
  >
    <div
      v-for="message in messages"
      :key="message.id"
      :class="['faq-bubble-in flex flex-col', message.role === 'user' ? 'items-end' : 'items-start']"
      :data-role="message.role"
      data-testid="faq-message"
    >
      <p
        :class="[
          'max-w-[85%] whitespace-pre-line break-keep rounded-2xl px-4 py-2.5 text-body',
          message.role === 'user'
            ? 'rounded-br-md bg-primary text-white'
            : message.isError
              ? 'rounded-bl-md bg-badge-sale-bg text-ink'
              : 'rounded-bl-md bg-surface-muted text-ink',
        ]"
        :data-answer="message.isAnswer ? 'true' : undefined"
        data-testid="faq-bubble"
      >{{ message.text }}</p>

      <div v-if="message.id === lastMessageId && message.role === 'assistant'" class="mt-2 flex max-w-full flex-wrap gap-2">
        <template v-if="message.showCategories">
          <button
            v-for="category in categories"
            :key="category"
            type="button"
            :class="['chip', firstCategories.includes(category) ? 'border-primary text-primary' : '']"
            :data-emphasized="firstCategories.includes(category) ? 'true' : undefined"
            :disabled="typing"
            data-testid="faq-category-chip"
            @click="emit('category', category)"
          >
            {{ faqCategoryLabel(category) }}
          </button>
        </template>
        <button
          v-for="(chip, index) in message.chips"
          :key="chipKey(chip, index)"
          type="button"
          :class="[
            'chip h-auto whitespace-normal py-2 text-left',
            chip.kind === 'question' ? 'w-full justify-start rounded-2xl' : '',
          ]"
          :disabled="typing"
          :data-kind="chip.kind"
          data-testid="faq-chip"
          @click="emit('chip', chip)"
        >
          <RotateCcw v-if="chip.kind === 'retry'" class="h-4 w-4" aria-hidden="true" />{{ chipLabel(chip) }}
        </button>
      </div>
    </div>

    <div v-if="typing" class="flex items-start" data-testid="faq-typing">
      <span class="inline-flex items-center gap-1 rounded-2xl rounded-bl-md bg-surface-muted px-4 py-3" aria-label="답변을 준비하고 있어요">
        <span v-for="dot in 3" :key="dot" class="faq-typing-dot h-1.5 w-1.5 rounded-full bg-sub" :style="{ animationDelay: `${(dot - 1) * 150}ms` }" />
      </span>
    </div>
  </div>
</template>

<style scoped>
/* 말풍선 등장·타이핑 점. 동작 줄이기(prefers-reduced-motion)는 main.css 전역 규칙이 애니메이션을 사실상 없앤다. */
.faq-bubble-in {
  animation: faq-bubble-in var(--duration-normal) var(--ease-soft);
}
.faq-typing-dot {
  animation: faq-typing-dot 900ms ease-in-out infinite;
}
@keyframes faq-bubble-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@keyframes faq-typing-dot {
  0%,
  80%,
  100% {
    opacity: 0.3;
  }
  40% {
    opacity: 1;
  }
}
</style>
