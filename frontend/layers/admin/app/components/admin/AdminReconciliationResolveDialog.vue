<script setup lang="ts">
import type { AdminReconciliationIssue } from '#layers/admin/app/types/admin-reconciliation'
import { RECONCILIATION_RESOLUTION_MEMO_MAX } from '#layers/admin/app/lib/constants/reconciliation'
import { reconciliationSummary, resolveConfirmMessage } from '#layers/admin/app/lib/admin-reconciliation-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminReconciliationIssues } from '#layers/admin/app/composables/useAdminReconciliationIssues'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 불일치 해결 다이얼로그(Track 104-2 FE-66·BE D-216). 해결은 표시만 바꾸고 다시 열 수 없어 위험 조작 규약(FE-64)을 따른다 — 확인 문구는
 * riskConfirmMessage(가역성 줄 마지막)·확정 버튼은 .op-risk-action. 메모는 필수(감사 기록). 이미 해결된 건(422)은 warning 후 stale로
 * 부모가 다시 읽게 한다(AdminPaymentCancelDialog·AdminClaimRejectDialog 패턴).
 */
const props = defineProps<{
  open: boolean
  issue: AdminReconciliationIssue | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const reconciliationApi = useAdminReconciliationIssues()
const toast = useAdminToast()

const memo = ref('')
const memoError = ref('')
const submitting = ref(false)

// 닫힘 애니메이션 동안 문구가 사라지지 않도록 마지막 대상을 유지한다(issue는 즉시 null).
const lastIssue = ref<AdminReconciliationIssue | null>(null)
watch(() => props.issue, (next) => { if (next) lastIssue.value = next })

watch(() => props.open, (open) => {
  if (!open) return
  memo.value = ''
  memoError.value = ''
  submitting.value = false
})

const message = computed(() => (lastIssue.value ? resolveConfirmMessage(lastIssue.value) : ''))
const confirmDisabled = computed(() => submitting.value || memo.value.trim() === '')

async function submit(): Promise<void> {
  if (submitting.value || !props.issue) return
  const trimmed = memo.value.trim()
  if (trimmed === '') {
    memoError.value = '처리 내용을 입력하세요.'
    return
  }
  submitting.value = true
  try {
    await reconciliationApi.resolve(props.issue.issueId, { memo: trimmed })
    toast.success('불일치를 해결됨으로 표시했습니다.')
    emit('done')
  } catch (error) {
    if (extractErrorCode(error) === 'RECONCILIATION_ISSUE_INVALID_STATE') {
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
  <v-dialog :model-value="open" max-width="560" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-reconciliation-resolve-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">불일치 해결 처리</v-card-title>
      <v-card-text class="px-5">
        <p v-if="lastIssue" class="text-body-2 text-medium-emphasis mb-2" data-testid="resolve-summary">{{ reconciliationSummary(lastIssue) }}</p>
        <p class="text-body-2 mb-3" style="white-space: pre-line" data-testid="resolve-message">{{ message }}</p>
        <v-textarea
          v-model="memo"
          label="처리 내용 (필수)"
          placeholder="예: PG 관리자 화면에서 환불 확인 후 결제 수동 취소"
          rows="2"
          auto-grow
          :maxlength="RECONCILIATION_RESOLUTION_MEMO_MAX"
          :counter="RECONCILIATION_RESOLUTION_MEMO_MAX"
          :error-messages="memoError ? [memoError] : []"
          :disabled="submitting"
          data-testid="resolve-memo"
          @update:model-value="memoError = ''"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="resolve-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn variant="flat" class="op-risk-action" :loading="submitting" :disabled="confirmDisabled" data-testid="resolve-dialog-ok" @click="submit">
          해결 처리
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
