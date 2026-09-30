<script setup lang="ts">
import { INQUIRY_CATEGORIES, INQUIRY_CONTENT_MAX, type InquiryCategory, inquiryCategoryLabel } from '~/lib/constants/inquiry'

// 문의 수정 폼(Track 106-4 · RenewQuestionEditForm 선례 + 카테고리 칩). 입력값은 폼이 들고 저장·취소만 부모에 알린다 — 저장 성공 여부(규칙 거절
// 422 등)와 편집 닫기는 부모가 정한다. 첨부 주문은 바꿀 수 없다(BE 계약).
const props = defineProps<{ initialCategory: InquiryCategory; initialContent: string; pending: boolean }>()
const emit = defineEmits<{ save: [category: InquiryCategory, content: string]; cancel: [] }>()

const category = ref<InquiryCategory>(props.initialCategory)
const content = ref(props.initialContent)
</script>

<template>
  <form class="mt-3" data-testid="inquiry-edit-form" @submit.prevent="emit('save', category, content)">
    <div class="flex flex-wrap gap-2" role="group" aria-label="문의 종류">
      <button
        v-for="option in INQUIRY_CATEGORIES"
        :key="option"
        type="button"
        class="chip"
        :aria-pressed="category === option"
        data-testid="inquiry-edit-category"
        @click="category = option"
      >{{ inquiryCategoryLabel(option) }}</button>
    </div>
    <label class="sr-only" for="inquiry-edit-content">문의 수정</label>
    <textarea
      id="inquiry-edit-content"
      v-model="content"
      :maxlength="INQUIRY_CONTENT_MAX"
      rows="4"
      class="mt-3 w-full rounded-control border border-line bg-white px-4 py-3 text-body text-ink transition duration-fast ease-soft focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary"
      data-testid="inquiry-edit-content"
    ></textarea>
    <div class="mt-2 flex items-center justify-end gap-2">
      <span class="mr-auto text-caption font-normal tabular-nums text-sub">{{ content.length }}/{{ INQUIRY_CONTENT_MAX }}</span>
      <button type="button" class="btn btn-tertiary btn-sm" :disabled="pending" data-testid="inquiry-edit-cancel" @click="emit('cancel')">취소</button>
      <button type="submit" class="btn btn-primary btn-sm" :disabled="pending" :aria-busy="pending" data-testid="inquiry-edit-save">
        {{ pending ? '저장 중…' : '저장' }}
      </button>
    </div>
  </form>
</template>
