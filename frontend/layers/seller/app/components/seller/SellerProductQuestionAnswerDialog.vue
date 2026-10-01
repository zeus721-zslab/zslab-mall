<script setup lang="ts">
import type { SellerProductQuestionItem } from '#layers/seller/app/types/seller-product-question'
import { PRODUCT_QUESTION_ANSWER_MAX } from '~/lib/constants/product-question'
import { extractErrorCode, isSellerSuspendedError, mapFieldErrors, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerProductQuestions } from '#layers/seller/app/composables/useSellerProductQuestions'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'
import { useAnswerDraft } from '~/composables/useAnswerDraft'
import AnswerDraftBox from '~/components/common/AnswerDraftBox.vue'

/**
 * 상품 질문 답변 등록·수정 다이얼로그(Track 106-2 · SellerInventoryAdjustDialog 규약). 답변 1~1000자(공백만 불가) · 수정이면 기존 답변을 채워 연다.
 * 성공(204) → success 토스트 후 done(부모가 목록 재조회) · 404(다른 셀러·삭제됨)·422(숨김 질문)는 warning 토스트 후 stale · 400은 필드 오류 ·
 * **403 SELLER_SUSPENDED는 여기서 danger 토스트로 직접 표시**(배너만 남지 않게 · FE-44 §8) 후 cancel. 열 때마다 답안 초안을 조회해 근거와 "초안 사용"을
 * 보인다(D-253 · 실패해도 답변은 그대로 가능).
 */
const props = defineProps<{ open: boolean; item: SellerProductQuestionItem | null }>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const questionsApi = useSellerProductQuestions()
const toast = useSellerToast()

const content = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)
const {
  result: draftResult, loading: draftLoading, failed: draftFailed, load: loadDraft, reset: resetDraft,
} = useAnswerDraft((questionId) => questionsApi.answerDraft(questionId))

watch(() => props.open, (open) => {
  if (!open) {
    resetDraft()
    return
  }
  content.value = props.item?.answerContent ?? ''
  errors.value = {}
  submitting.value = false
  if (props.item) void loadDraft(props.item.questionId)
})

/** "초안 사용"을 눌렀을 때만 입력란을 채운다(D-253 · 기존 답변 자동 덮어쓰기 금지). */
function applyDraft(draft: string): void {
  content.value = draft
  errors.value = { ...errors.value, content: '' }
}

const editing = computed(() => props.item?.answerContent !== undefined)

async function submit(): Promise<void> {
  if (submitting.value || !props.item) return
  const trimmed = content.value.trim()
  if (trimmed === '') {
    errors.value = { content: '답변을 입력하세요.' }
    return
  }
  submitting.value = true
  try {
    await questionsApi.answer(props.item.questionId, trimmed)
    toast.success(editing.value ? '답변을 수정했습니다.' : '답변을 등록했습니다.')
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (isSellerSuspendedError(error)) {
      toast.danger(toSellerErrorMessage(error))
      emit('cancel')
    } else if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { content: toSellerErrorMessage(error) }
    } else if (code === 'PRODUCT_QUESTION_NOT_FOUND' || code === 'PRODUCT_QUESTION_INVALID_STATE') {
      // 조회~답변 사이 질문이 삭제·숨김된 경우: 안내 후 목록을 다시 읽는다.
      toast.warning(toSellerErrorMessage(error))
      emit('stale')
    } else {
      toast.danger(toSellerErrorMessage(error))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="560" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="seller-question-answer-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ editing ? '답변 수정' : '답변하기' }}</v-card-title>
      <v-card-text class="px-5">
        <template v-if="item">
          <p class="text-caption text-medium-emphasis mb-1">{{ item.productName ?? '삭제된 상품' }}</p>
          <p class="text-body-2 mb-4" style="white-space: pre-line" data-testid="answer-question">Q. {{ item.content }}</p>
        </template>
        <AnswerDraftBox :result="draftResult" :loading="draftLoading" :failed="draftFailed" :disabled="submitting" @use="applyDraft" />
        <p class="text-caption text-medium-emphasis mb-2">답변은 상품 페이지에 공개됩니다.</p>
        <v-textarea
          v-model="content"
          label="답변"
          rows="4"
          auto-grow
          :maxlength="PRODUCT_QUESTION_ANSWER_MAX"
          :counter="PRODUCT_QUESTION_ANSWER_MAX"
          :error-messages="errors.content ? [errors.content] : []"
          :disabled="submitting"
          autofocus
          data-testid="answer-content"
          @update:model-value="errors = { ...errors, content: '' }"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="answer-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="submitting || content.trim() === ''" data-testid="answer-dialog-ok" @click="submit">
          {{ editing ? '수정' : '등록' }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
