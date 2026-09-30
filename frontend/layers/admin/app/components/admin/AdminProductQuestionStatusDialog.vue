<script setup lang="ts">
import { mdiAlertOutline } from '@mdi/js'
import type { AdminProductQuestionListItem } from '#layers/admin/app/types/admin-product-question'
import { ADMIN_PRODUCT_QUESTION_TRANSITION_LABEL } from '#layers/admin/app/lib/constants/admin-product-question'
import { productQuestionExcerpt, productQuestionTransitionTarget } from '#layers/admin/app/lib/admin-product-question-query'
import { PRODUCT_QUESTION_REASON_MAX } from '~/lib/constants/product-question'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { useAdminProductQuestions } from '#layers/admin/app/composables/useAdminProductQuestions'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 상품 질문 숨김·해제 다이얼로그(Track 106-2 · AdminReviewStatusDialog 복제). 사유는 숨김·해제 모두 필수(BE @NotBlank · 200자 · 감사 이력).
 * 성공(204)이면 done으로 부모가 목록을 다시 읽는다. 422(이미 그 상태)·404는 토스트 후 stale — 화면이 오래됐을 수 있어 부모가 다시 읽는다.
 */
const props = defineProps<{ open: boolean; question: AdminProductQuestionListItem | null }>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const questionsApi = useAdminProductQuestions()
const toast = useAdminToast()

const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

// 닫힘 애니메이션 동안 문구가 사라지지 않도록 마지막 대상을 유지한다.
const lastQuestion = ref<AdminProductQuestionListItem | null>(null)
watch(() => props.question, (next) => { if (next) lastQuestion.value = next })
watch(() => props.open, (open) => {
  if (!open) return
  reason.value = ''
  errors.value = {}
  submitting.value = false
})

const target = computed(() => (lastQuestion.value ? productQuestionTransitionTarget(lastQuestion.value.status) : null))
const title = computed(() => (target.value ? `질문 ${ADMIN_PRODUCT_QUESTION_TRANSITION_LABEL[target.value]}` : ''))
const message = computed(() =>
  target.value === 'HIDDEN'
    ? '숨기면 상품 페이지 질문 목록과 즉시 답에서 바로 빠지고, 작성자는 수정할 수 없으며 셀러는 답변할 수 없습니다.'
    : '숨김을 해제하면 상품 페이지에 다시 보입니다.',
)
const confirmDisabled = computed(() => submitting.value || reason.value.trim() === '')

async function submit(): Promise<void> {
  if (submitting.value || !props.question || !target.value) return
  const trimmed = reason.value.trim()
  if (trimmed === '') {
    errors.value = { reason: '사유를 입력하세요.' }
    return
  }
  submitting.value = true
  try {
    await questionsApi.changeStatus(props.question.questionId, { status: target.value, reason: trimmed })
    if (target.value === 'HIDDEN') toast.warning('질문을 숨겼습니다.')
    else toast.success('질문 숨김을 해제했습니다.')
    emit('done')
  } catch (error) {
    if (extractErrorCode(error) === 'VALIDATION_FAILED') {
      errors.value = mapFieldErrors(error)
      if (Object.keys(errors.value).length === 0) toast.danger(toAdminErrorMessage(error))
    } else {
      toast.warning(toAdminErrorMessage(error))
      emit('stale')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="560" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-question-status-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ title }}</v-card-title>
      <v-card-text class="px-5">
        <p v-if="lastQuestion" class="text-body-2 mb-3" data-testid="question-status-target">
          <span class="font-weight-medium">{{ lastQuestion.productName ?? '삭제된 상품' }}</span> · {{ productQuestionExcerpt(lastQuestion.content) }}
        </p>
        <v-alert :type="target === 'HIDDEN' ? 'warning' : 'info'" variant="tonal" density="compact" :icon="mdiAlertOutline" class="mb-4" data-testid="question-status-message">
          {{ message }}
        </v-alert>
        <v-textarea
          v-model="reason"
          label="사유 (필수)"
          :placeholder="target === 'HIDDEN' ? '예: 광고성 질문' : '예: 오판 정정'"
          rows="2"
          auto-grow
          :maxlength="PRODUCT_QUESTION_REASON_MAX"
          :counter="PRODUCT_QUESTION_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          autofocus
          data-testid="question-status-reason"
          @update:model-value="errors = { ...errors, reason: '' }"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="question-status-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn variant="flat" class="op-risk-action" :loading="submitting" :disabled="confirmDisabled" data-testid="question-status-ok" @click="submit">
          {{ target ? ADMIN_PRODUCT_QUESTION_TRANSITION_LABEL[target] : '확인' }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
