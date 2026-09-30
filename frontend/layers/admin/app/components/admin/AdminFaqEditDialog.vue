<script setup lang="ts">
import type { AdminFaqItem } from '#layers/admin/app/types/admin-faq'
import { faqCategoryMoved } from '#layers/admin/app/lib/admin-faq-view'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminFaqs } from '#layers/admin/app/composables/useAdminFaqs'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'
import { FAQ_ANSWER_MAX, FAQ_CATEGORIES, FAQ_QUESTION_MAX, type FaqCategory, faqCategoryLabel } from '~/lib/constants/faq'

/**
 * FAQ 등록·수정 다이얼로그(Track 106-3 · AdminCategoryEditDialog 선례). 수정은 PUT 전체 치환. 전송 전 trim · 400 VALIDATION_FAILED는 필드 오류,
 * 404 FAQ_NOT_FOUND는 stale(목록 재조회). 수정 중 카테고리를 바꾸면 저장 시 새 카테고리 끝으로 간다는 안내를 보인다.
 */
const props = defineProps<{
  open: boolean
  item: AdminFaqItem | null
  /** 등록 시 기본 카테고리(현재 보고 있는 카테고리). */
  defaultCategory: FaqCategory
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const faqsApi = useAdminFaqs()
const toast = useAdminToast()

const categoryOptions = FAQ_CATEGORIES.map((value) => ({ value, title: faqCategoryLabel(value) }))
const isEdit = computed(() => props.item !== null)
const category = ref<FaqCategory>(props.defaultCategory)
const question = ref('')
const answer = ref('')
const visible = ref(true)
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

function reset(): void {
  category.value = props.item?.category ?? props.defaultCategory
  question.value = props.item?.question ?? ''
  answer.value = props.item?.answer ?? ''
  visible.value = props.item?.visible ?? true
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

function clearError(field: string): void {
  errors.value = { ...errors.value, [field]: '' }
}

const categoryMoved = computed(() => faqCategoryMoved(props.item, category.value))
const confirmDisabled = computed(() => submitting.value || question.value.trim() === '' || answer.value.trim() === '')

async function submit(): Promise<void> {
  if (submitting.value) return
  const body = { category: category.value, question: question.value.trim(), answer: answer.value.trim(), visible: visible.value }
  const localErrors: Record<string, string> = {}
  if (body.question === '') localErrors.question = '질문을 입력하세요.'
  if (body.question.length > FAQ_QUESTION_MAX) localErrors.question = `질문은 ${FAQ_QUESTION_MAX}자 이하여야 합니다.`
  if (body.answer === '') localErrors.answer = '답변을 입력하세요.'
  if (body.answer.length > FAQ_ANSWER_MAX) localErrors.answer = `답변은 ${FAQ_ANSWER_MAX}자 이하여야 합니다.`
  if (Object.keys(localErrors).length > 0) {
    errors.value = localErrors
    return
  }

  submitting.value = true
  try {
    if (props.item === null) {
      await faqsApi.create(body)
      toast.success('FAQ를 등록했습니다.')
    } else {
      await faqsApi.update(props.item.id, body)
      toast.info(categoryMoved.value ? 'FAQ를 수정했습니다. 새 카테고리의 맨 끝으로 이동했습니다.' : 'FAQ를 수정했습니다.')
    }
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { question: toAdminErrorMessage(error) }
    } else if (code === 'FAQ_NOT_FOUND') {
      toast.warning(toAdminErrorMessage(error))
      emit('stale')
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="640" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-faq-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ isEdit ? 'FAQ 수정' : 'FAQ 등록' }}</v-card-title>
      <v-card-text class="px-5">
        <v-select
          v-model="category"
          :items="categoryOptions"
          label="카테고리"
          :disabled="submitting"
          :hint="categoryMoved ? '저장하면 새 카테고리의 맨 끝으로 이동합니다.' : undefined"
          :persistent-hint="categoryMoved"
          data-testid="faq-category"
        />
        <v-text-field
          v-model="question"
          label="질문"
          :maxlength="FAQ_QUESTION_MAX"
          :counter="FAQ_QUESTION_MAX"
          :error-messages="errors.question ? [errors.question] : []"
          :disabled="submitting"
          class="mt-2"
          data-testid="faq-question"
          @update:model-value="clearError('question')"
        />
        <v-textarea
          v-model="answer"
          label="답변"
          rows="6"
          auto-grow
          :maxlength="FAQ_ANSWER_MAX"
          :counter="FAQ_ANSWER_MAX"
          :error-messages="errors.answer ? [errors.answer] : []"
          :disabled="submitting"
          data-testid="faq-answer"
          @update:model-value="clearError('answer')"
        />
        <v-switch
          v-model="visible"
          color="primary"
          :label="visible ? '공개 — 구매자 도우미에 보입니다' : '숨김 — 구매자 도우미에 보이지 않습니다'"
          :disabled="submitting"
          hide-details
          inset
          data-testid="faq-visible"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="faq-dialog-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="faq-dialog-ok" @click="submit">
          {{ isEdit ? '수정' : '등록' }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
