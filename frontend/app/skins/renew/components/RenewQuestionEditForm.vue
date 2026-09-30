<script setup lang="ts">
import { PRODUCT_QUESTION_CONTENT_MAX } from '~/lib/constants/product-question'

// 질문 수정 폼(Track 106-2 · 상품 상세 Q&A 목록과 마이페이지 내 질문이 함께 쓴다). 입력값은 폼이 들고 저장·취소만 부모에 알린다 —
// 저장 성공 여부(규칙 거절 422 등)와 편집 닫기는 부모가 정한다.
const props = defineProps<{ initialContent: string; pending: boolean }>()
const emit = defineEmits<{ save: [content: string]; cancel: [] }>()

const content = ref(props.initialContent)
</script>

<template>
  <form class="mt-3" data-testid="question-edit-form" @submit.prevent="emit('save', content)">
    <label class="sr-only" for="question-edit-content">질문 수정</label>
    <textarea
      id="question-edit-content"
      v-model="content"
      :maxlength="PRODUCT_QUESTION_CONTENT_MAX"
      rows="3"
      class="w-full rounded-control border border-line bg-white px-4 py-3 text-body text-ink transition duration-fast ease-soft focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary"
      data-testid="question-edit-content"
    ></textarea>
    <div class="mt-2 flex items-center justify-end gap-2">
      <span class="mr-auto text-caption font-normal tabular-nums text-sub">{{ content.length }}/{{ PRODUCT_QUESTION_CONTENT_MAX }}</span>
      <button type="button" class="btn btn-tertiary btn-sm" :disabled="pending" data-testid="question-edit-cancel" @click="emit('cancel')">취소</button>
      <button type="submit" class="btn btn-primary btn-sm" :disabled="pending" :aria-busy="pending" data-testid="question-edit-save">
        {{ pending ? '저장 중…' : '저장' }}
      </button>
    </div>
  </form>
</template>
