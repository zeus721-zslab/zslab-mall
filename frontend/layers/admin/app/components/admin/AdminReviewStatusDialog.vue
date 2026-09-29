<script setup lang="ts">
import { mdiAlertOutline } from '@mdi/js'
import type { AdminReviewListItem } from '#layers/admin/app/types/admin-review'
import { ADMIN_REVIEW_REASON_MAX, ADMIN_REVIEW_TRANSITION_LABEL } from '#layers/admin/app/lib/constants/admin-review'
import { reviewExcerpt, reviewTransitionTarget } from '#layers/admin/app/lib/admin-review-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { useAdminReviews } from '#layers/admin/app/composables/useAdminReviews'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 리뷰 숨김·해제 다이얼로그(Track 106-1 · AdminSellerStatusDialog 패턴). 사유는 숨김·해제 모두 필수(BE @NotBlank · 감사 이력).
 * 성공(204)이면 done으로 부모가 목록을 다시 읽는다. 422(이미 그 상태)·404는 토스트 후 stale — 화면이 오래됐을 수 있어 부모가 다시 읽는다.
 */
const props = defineProps<{ open: boolean; review: AdminReviewListItem | null }>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const reviewsApi = useAdminReviews()
const toast = useAdminToast()

const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

// 닫힘 애니메이션 동안 문구가 사라지지 않도록 마지막 대상을 유지한다.
const lastReview = ref<AdminReviewListItem | null>(null)
watch(() => props.review, (next) => { if (next) lastReview.value = next })
watch(() => props.open, (open) => {
  if (!open) return
  reason.value = ''
  errors.value = {}
  submitting.value = false
})

const target = computed(() => (lastReview.value ? reviewTransitionTarget(lastReview.value.status) : null))
const title = computed(() => (target.value ? `리뷰 ${ADMIN_REVIEW_TRANSITION_LABEL[target.value]}` : ''))
const message = computed(() =>
  target.value === 'HIDDEN'
    ? '숨기면 상품 페이지 목록·요약·사진에서 바로 빠지고, 작성자는 수정할 수 없습니다.'
    : '숨김을 해제하면 상품 페이지에 다시 보입니다.',
)
const confirmDisabled = computed(() => submitting.value || reason.value.trim() === '')

async function submit(): Promise<void> {
  if (submitting.value || !props.review || !target.value) return
  const trimmed = reason.value.trim()
  if (trimmed === '') {
    errors.value = { reason: '사유를 입력하세요.' }
    return
  }
  submitting.value = true
  try {
    await reviewsApi.changeStatus(props.review.reviewId, { status: target.value, reason: trimmed })
    if (target.value === 'HIDDEN') toast.warning('리뷰를 숨겼습니다.')
    else toast.success('리뷰 숨김을 해제했습니다.')
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
    <v-card data-testid="admin-review-status-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ title }}</v-card-title>
      <v-card-text class="px-5">
        <p v-if="lastReview" class="text-body-2 mb-3" data-testid="review-status-target">
          <span class="font-weight-medium">{{ lastReview.productName ?? '삭제된 상품' }}</span> · {{ reviewExcerpt(lastReview.content) }}
        </p>
        <v-alert :type="target === 'HIDDEN' ? 'warning' : 'info'" variant="tonal" density="compact" :icon="mdiAlertOutline" class="mb-4" data-testid="review-status-message">
          {{ message }}
        </v-alert>
        <v-textarea
          v-model="reason"
          label="사유 (필수)"
          :placeholder="target === 'HIDDEN' ? '예: 광고성 게시물' : '예: 오판 정정'"
          rows="2"
          auto-grow
          :maxlength="ADMIN_REVIEW_REASON_MAX"
          :counter="ADMIN_REVIEW_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          autofocus
          data-testid="review-status-reason"
          @update:model-value="errors = { ...errors, reason: '' }"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="review-status-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn variant="flat" class="op-risk-action" :loading="submitting" :disabled="confirmDisabled" data-testid="review-status-ok" @click="submit">
          {{ target ? ADMIN_REVIEW_TRANSITION_LABEL[target] : '확인' }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
