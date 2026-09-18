<script setup lang="ts">
import type { AdminSettlementRegenerateResponse } from '#layers/admin/app/types/admin-settlement'
import { ADMIN_SETTLEMENT_REGENERATE_REASON_MAX } from '#layers/admin/app/lib/constants/admin-settlement'
import { validateRegenerateReason } from '#layers/admin/app/lib/admin-settlement-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminSettlements } from '#layers/admin/app/composables/useAdminSettlements'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 정산 재생성 다이얼로그(Track 85 FE·D-179 결정 9). 사유(필수·1~200자) 입력 후 POST /regenerate. 호출·토스트는 다이얼로그가 소유하며
 * 성공 시 응답을 done으로 올려 부모가 이동(새 정산 상세 또는 삭제만이면 목록)을 결정한다. 422(PENDING 아님·상태 경합)는 warning 토스트 후
 * stale(부모가 다시 읽음). 처리 중에는 닫기·재제출을 막는다(AdminClaimRejectDialog 패턴).
 */
const props = defineProps<{
  open: boolean
  settlementId: number | null
  periodLabel: string
}>()
const emit = defineEmits<{ done: [response: AdminSettlementRegenerateResponse]; stale: []; cancel: [] }>()

const settlementsApi = useAdminSettlements()
const toast = useAdminToast()

const reason = ref('')
const reasonError = ref<string | null>(null)
const submitting = ref(false)

function reset(): void {
  reason.value = ''
  reasonError.value = null
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

const confirmDisabled = computed(() => submitting.value || reason.value.trim() === '')

async function submit(): Promise<void> {
  if (submitting.value || props.settlementId === null) return
  reasonError.value = validateRegenerateReason(reason.value)
  if (reasonError.value) return

  submitting.value = true
  try {
    const response = await settlementsApi.regenerate(props.settlementId, reason.value.trim())
    emit('done', response)
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'SETTLEMENT_INVALID_STATE') {
      // 조회~재생성 사이 상태가 바뀐 경우: 안내 후 부모가 최신 데이터를 다시 읽는다.
      toast.warning(toAdminErrorMessage(error))
      emit('stale')
    } else if (code === 'VALIDATION_FAILED') {
      reasonError.value = `사유는 1~${ADMIN_SETTLEMENT_REGENERATE_REASON_MAX}자여야 합니다.`
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="480" :persistent="submitting" @update:model-value="(value: boolean) => !value && !submitting && emit('cancel')">
    <v-card data-testid="settlement-regenerate-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">정산 재생성</v-card-title>
      <v-card-text class="px-5 text-body-2">
        <p class="mb-3">{{ periodLabel }} 정산을 삭제하고 같은 셀러·기간의 구매확정 매출과 환불을 다시 집계합니다. 재집계 대상이 없으면 삭제만 됩니다.</p>
        <v-textarea
          v-model="reason"
          label="재생성 사유"
          placeholder="예: 반품 완료 누락분 반영"
          :maxlength="ADMIN_SETTLEMENT_REGENERATE_REASON_MAX"
          :counter="ADMIN_SETTLEMENT_REGENERATE_REASON_MAX"
          :error-messages="reasonError ?? []"
          rows="3"
          auto-grow
          data-testid="settlement-regenerate-reason"
          @update:model-value="reasonError = null"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="settlement-regenerate-dialog-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn color="warning" variant="flat" :disabled="confirmDisabled" :loading="submitting" data-testid="settlement-regenerate-dialog-ok" @click="submit">재생성</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
