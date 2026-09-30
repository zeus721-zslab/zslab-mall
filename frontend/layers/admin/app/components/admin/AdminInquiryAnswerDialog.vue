<script setup lang="ts">
import type { AdminInquiryListItem } from '#layers/admin/app/types/admin-inquiry'
import { INQUIRY_ANSWER_MAX, inquiryCategoryLabel } from '~/lib/constants/inquiry'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { useAdminInquiries } from '#layers/admin/app/composables/useAdminInquiries'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 운영자 문의 답변 등록·수정 다이얼로그(Track 106-4 · SellerProductQuestionAnswerDialog 선례). 답변 1~1000자(trim · 공백만 불가) · 수정이면 기존
 * 답변을 채워 연다. 같은 본문으로 다시 저장해도 그대로 보낸다(BE가 무변경 처리). 성공(204) → success 토스트 후 done(부모가 목록 재조회) ·
 * INQUIRY_NOT_FOUND(구매자 삭제)·INQUIRY_INVALID_STATE는 warning 토스트 후 stale · 400은 필드 오류.
 */
const props = defineProps<{ open: boolean; item: AdminInquiryListItem | null }>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const inquiriesApi = useAdminInquiries()
const toast = useAdminToast()

const content = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

watch(() => props.open, (open) => {
  if (!open) return
  content.value = props.item?.answerContent ?? ''
  errors.value = {}
  submitting.value = false
})

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
    await inquiriesApi.answer(props.item.inquiryId, trimmed)
    toast.success(editing.value ? '답변을 수정했습니다.' : '답변을 등록했습니다.')
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { content: toAdminErrorMessage(error) }
    } else if (code === 'INQUIRY_NOT_FOUND' || code === 'INQUIRY_INVALID_STATE') {
      // 조회~답변 사이 구매자가 문의를 지운 경우: 안내 후 목록을 다시 읽는다.
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
  <v-dialog :model-value="open" max-width="600" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-inquiry-answer-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ editing ? '답변 수정' : '답변하기' }}</v-card-title>
      <v-card-text class="px-5">
        <template v-if="item">
          <p class="text-caption text-medium-emphasis mb-1">
            {{ inquiryCategoryLabel(item.category) }} · {{ item.buyerEmailMasked ?? '작성자 정보 없음' }}<template v-if="item.orderNo"> · 주문 {{ item.orderNo }}</template>
          </p>
          <p class="text-body-2 mb-4" style="white-space: pre-line" data-testid="answer-inquiry">Q. {{ item.content }}</p>
        </template>
        <p class="text-caption text-medium-emphasis mb-2">답변은 구매자의 "내 문의"에만 보이며, 수정하면 구매자에게 새 답변으로 표시됩니다.</p>
        <v-textarea
          v-model="content"
          label="답변"
          rows="5"
          auto-grow
          :maxlength="INQUIRY_ANSWER_MAX"
          :counter="INQUIRY_ANSWER_MAX"
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
