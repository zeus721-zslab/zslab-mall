<script setup lang="ts">
import type { AdminInquiryListItem } from '#layers/admin/app/types/admin-inquiry'
import { INQUIRY_ANSWER_MAX, inquiryCategoryLabel } from '~/lib/constants/inquiry'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { useAdminInquiries } from '#layers/admin/app/composables/useAdminInquiries'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'
import { useAnswerDraft } from '~/composables/useAnswerDraft'
import AnswerDraftBox from '~/components/common/AnswerDraftBox.vue'
import AdminFaqEditDialog from '#layers/admin/app/components/admin/AdminFaqEditDialog.vue'
import type { AdminFaqPrefill } from '#layers/admin/app/types/admin-faq'
import { faqPrefillFromInquiry } from '#layers/admin/app/lib/admin-faq-view'

/**
 * 운영자 문의 답변 등록·수정 다이얼로그(Track 106-4 · SellerProductQuestionAnswerDialog 선례). 답변 1~1000자(trim · 공백만 불가) · 수정이면 기존
 * 답변을 채워 연다. 같은 본문으로 다시 저장해도 그대로 보낸다(BE가 무변경 처리). 성공(204) → success 토스트 후 done(부모가 목록 재조회) ·
 * INQUIRY_NOT_FOUND(구매자 삭제)·INQUIRY_INVALID_STATE는 warning 토스트 후 stale · 400은 필드 오류. 열 때마다 답안 초안을 조회해 근거와 "초안 사용"을
 * 보인다(D-253 · 실패해도 답변은 그대로 가능). FAQ 후보이면 "답변 저장 후 FAQ로 등록"을 고를 수 있고, 고른 채 저장에 성공하면 미리 채운 FAQ 등록
 * 다이얼로그를 연 뒤 그 다이얼로그가 끝나야 done을 보낸다.
 */
const props = defineProps<{ open: boolean; item: AdminInquiryListItem | null }>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const inquiriesApi = useAdminInquiries()
const toast = useAdminToast()

const content = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)
const {
  result: draftResult, loading: draftLoading, failed: draftFailed, load: loadDraft, reset: resetDraft,
} = useAnswerDraft((inquiryId) => inquiriesApi.answerDraft(inquiryId))

/** FAQ 후보(FAQ 근거 0건)일 때만 보이는 "답변 저장 후 FAQ로 등록" 선택 · 저장 뒤 여는 FAQ 등록 다이얼로그의 미리 채우기. */
const registerFaq = ref(false)
const faqPrefill = ref<AdminFaqPrefill | null>(null)
const faqCandidate = computed(() => draftResult.value?.faqCandidate === true)

watch(() => props.open, (open) => {
  if (!open) {
    resetDraft()
    return
  }
  content.value = props.item?.answerContent ?? ''
  errors.value = {}
  submitting.value = false
  registerFaq.value = false
  faqPrefill.value = null
  if (props.item) void loadDraft(props.item.inquiryId)
})

/** "초안 사용"을 눌렀을 때만 입력란을 채운다(D-253 · 기존 답변 자동 덮어쓰기 금지). */
function applyDraft(draft: string): void {
  content.value = draft
  errors.value = { ...errors.value, content: '' }
}

/** FAQ 등록 다이얼로그가 끝나면(저장·취소·stale 모두) 답변 완료로 닫는다. */
function finishFaq(): void {
  faqPrefill.value = null
  emit('done')
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
    await inquiriesApi.answer(props.item.inquiryId, trimmed)
    toast.success(editing.value ? '답변을 수정했습니다.' : '답변을 등록했습니다.')
    if (faqCandidate.value && registerFaq.value) {
      // 답변은 저장됐다 — FAQ 등록(또는 취소)이 끝난 뒤 done을 보낸다. 인박스는 답변 저장의 변경 신호로 먼저 재조회해 이 다이얼로그를 닫을 수 있는데,
      // FAQ 다이얼로그는 faqPrefill로만 열고 닫으므로 그대로 남아 등록을 마칠 수 있다(done은 finishFaq 1회).
      const orderEvidenceLines = (draftResult.value?.evidence ?? []).filter((evidence) => evidence.kind === 'ORDER').map((evidence) => evidence.summary)
      faqPrefill.value = faqPrefillFromInquiry(props.item.category, props.item.content, trimmed, orderEvidenceLines)
      return
    }
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
  <!-- FAQ 등록 다이얼로그를 여는 동안에는 답변 다이얼로그를 숨긴다(답변은 이미 저장됨 · 재제출 방지). -->
  <v-dialog
    :model-value="open && faqPrefill === null"
    max-width="600"
    :persistent="submitting"
    @update:model-value="(value: boolean) => !value && emit('cancel')"
  >
    <v-card data-testid="admin-inquiry-answer-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ editing ? '답변 수정' : '답변하기' }}</v-card-title>
      <v-card-text class="px-5">
        <template v-if="item">
          <p class="text-caption text-medium-emphasis mb-1">
            {{ inquiryCategoryLabel(item.category) }} · {{ item.buyerEmailMasked ?? '작성자 정보 없음' }}<template v-if="item.orderNo"> · 주문 {{ item.orderNo }}</template>
          </p>
          <p class="text-body-2 mb-4" style="white-space: pre-line" data-testid="answer-inquiry">Q. {{ item.content }}</p>
        </template>
        <AnswerDraftBox :result="draftResult" :loading="draftLoading" :failed="draftFailed" :disabled="submitting" @use="applyDraft" />
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
        <div v-if="faqCandidate" class="d-flex align-center flex-wrap ga-2" data-testid="answer-faq-candidate">
          <v-chip size="small" color="info" variant="tonal">FAQ 후보</v-chip>
          <span class="text-caption text-medium-emphasis">일치하는 FAQ가 없는 문의입니다.</span>
          <v-checkbox
            v-model="registerFaq"
            label="답변 저장 후 FAQ로 등록"
            density="compact"
            hide-details
            :disabled="submitting"
            data-testid="answer-faq-register"
          />
        </div>
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
  <!-- default-category는 필수 prop이라 넘기지만 prefill이 항상 있어 쓰이지 않는다(카테고리는 prefill.category · 기타 문의는 미선택). -->
  <AdminFaqEditDialog
    :open="faqPrefill !== null"
    :item="null"
    default-category="ORDER_PAYMENT"
    :prefill="faqPrefill"
    @done="finishFaq"
    @stale="finishFaq"
    @cancel="finishFaq"
  />
</template>
