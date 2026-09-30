<script setup lang="ts">
import { SendHorizontal } from '@lucide/vue'
import { FAQ_SUGGEST_MAX, FAQ_SUGGEST_MIN } from '~/lib/constants/faq'
import type { FaqItem } from '~/types/faq'

// 채팅 도우미 자유 입력(Track 106-3): 입력 중에는 즉시 답 미리보기를 질문 칩으로 보여 주고, 전송하면 대화에 검색 결과를 남긴다.
const props = defineProps<{ suggestions: FaqItem[]; disabled: boolean }>()
const emit = defineEmits<{ input: [text: string]; submit: [text: string]; pick: [faq: FaqItem] }>()

const draft = ref('')
const canSubmit = computed(() => !props.disabled && draft.value.trim().length >= FAQ_SUGGEST_MIN)

function onInput(event: Event): void {
  draft.value = (event.target as HTMLInputElement).value
  emit('input', draft.value)
}

function submit(): void {
  if (!canSubmit.value) return
  emit('submit', draft.value)
  draft.value = ''
}

function pick(faq: FaqItem): void {
  emit('pick', faq)
  draft.value = ''
}
</script>

<template>
  <div class="border-t border-line px-3 pb-3 pt-2">
    <div v-if="suggestions.length > 0 && draft.trim().length > 0" class="mb-2 flex max-h-32 flex-col gap-1.5 overflow-y-auto" data-testid="faq-live-suggestions">
      <p class="px-1 text-caption font-semibold text-sub">이런 질문이 있어요</p>
      <button
        v-for="faq in suggestions"
        :key="faq.id"
        type="button"
        class="chip h-auto justify-start whitespace-normal rounded-2xl py-2 text-left"
        :disabled="disabled"
        data-testid="faq-live-suggestion"
        @click="pick(faq)"
      >
        {{ faq.question }}
      </button>
    </div>
    <form class="flex items-center gap-2" @submit.prevent="submit">
      <input
        :value="draft"
        type="text"
        :maxlength="FAQ_SUGGEST_MAX"
        class="min-h-11 min-w-0 flex-1 rounded-full border border-line bg-white px-4 text-body text-ink placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary"
        placeholder="궁금한 점을 입력해 주세요"
        aria-label="궁금한 점 입력"
        data-testid="faq-input"
        @input="onInput"
      >
      <button
        type="submit"
        class="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-primary text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--ring) disabled:opacity-40"
        :disabled="!canSubmit"
        aria-label="보내기"
        data-testid="faq-send"
      >
        <SendHorizontal class="h-4 w-4" aria-hidden="true" />
      </button>
    </form>
  </div>
</template>
