<script setup lang="ts">
import { claimTypeLabel, CLAIM_REJECT_MEMO_MAX, type ClaimRejectReasonCode, type ClaimType } from '~/lib/constants/claim'
import { rejectConfirmMessage } from '#layers/admin/app/lib/admin-claim-view'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { rejectReasonItems, validateRejectForm } from '#layers/admin/app/lib/admin-claim-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/** 거부 대상 최소 정보(목록 행·주문 상세 클레임 행 공용). */
export interface AdminClaimRejectTarget {
  claimId: string
  type: ClaimType
  productName: string
}

/**
 * 관리자 클레임 거부 다이얼로그(FE-28·Track 80 D-169). 사유 select(필수·클레임 유형에 따라 ALREADY_SHIPPED는 취소만 노출) + 메모(선택·500).
 * 호출·토스트는 다이얼로그가 소유하며 성공 시 danger 토스트 후 done, 422(상태 경합)는 warning 토스트 후 stale(부모가 다시 읽음),
 * 400은 fieldErrors를 필드에 표시한다(AdminOrderCancelDialog 패턴 1:1). 처리 중에는 닫기·재제출을 막는다.
 */
const props = defineProps<{
  open: boolean
  target: AdminClaimRejectTarget | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const ordersApi = useAdminOrders()
const toast = useAdminToast()

const reasonCode = ref<ClaimRejectReasonCode | null>(null)
const memo = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

// 닫힘 애니메이션 동안 유형·상품명이 사라지지 않도록 마지막 대상을 유지한다(target은 즉시 null).
const lastTarget = ref<AdminClaimRejectTarget | null>(null)
watch(() => props.target, (next) => { if (next) lastTarget.value = next })

const reasonItems = computed(() => rejectReasonItems(lastTarget.value?.type ?? 'CANCEL'))
const title = computed(() => `${claimTypeLabel(lastTarget.value?.type ?? 'CANCEL')} 요청 거부`)

function reset(): void {
  reasonCode.value = null
  memo.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

const confirmDisabled = computed(() => submitting.value || reasonCode.value === null)

async function submit(): Promise<void> {
  if (submitting.value || !props.target) return
  const validation = validateRejectForm({ reasonCode: reasonCode.value, memo: memo.value })
  errors.value = validation
  if (Object.keys(validation).length > 0 || !reasonCode.value) return

  submitting.value = true
  try {
    await ordersApi.rejectClaim(props.target.claimId, {
      reasonCode: reasonCode.value,
      memo: memo.value.trim() || undefined,
    })
    toast.danger(`${claimTypeLabel(props.target.type)} 요청을 거부했습니다.`) // 거부는 부정적 의미
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      errors.value = mapFieldErrors(error)
      if (Object.keys(errors.value).length === 0) toast.danger(toAdminErrorMessage(error))
    } else if (code === 'MALFORMED_REQUEST') {
      // 유형 부적합 사유(도메인 검증)·body 오류: FE에서 걸러지므로 화면과 서버 상태가 어긋난 경우다. 사유 필드에 안내한다.
      errors.value = { reasonCode: '선택한 사유는 이 클레임 유형에 사용할 수 없습니다.' }
    } else if (code === 'CLAIM_STATE_INVALID') {
      // 조회~거부 사이 상태가 바뀐 경우: 안내 후 부모가 최신 데이터를 다시 읽는다.
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
  <v-dialog :model-value="open" max-width="520" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-claim-reject-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ title }}</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-3" style="white-space: pre-line" data-testid="reject-notice">{{ rejectConfirmMessage(lastTarget?.type ?? 'CANCEL', lastTarget?.productName ?? '') }}</p>
        <v-select
          :model-value="reasonCode"
          :items="reasonItems"
          label="거부 사유"
          placeholder="사유를 선택하세요"
          :error-messages="errors.reasonCode ? [errors.reasonCode] : []"
          :disabled="submitting"
          class="mb-2"
          data-testid="reject-reason"
          @update:model-value="(value) => { reasonCode = value ?? null; errors = { ...errors, reasonCode: '' } }"
        />
        <v-textarea
          v-model="memo"
          label="메모 (선택)"
          rows="3"
          auto-grow
          :maxlength="CLAIM_REJECT_MEMO_MAX"
          :counter="CLAIM_REJECT_MEMO_MAX"
          :error-messages="errors.memo ? [errors.memo] : []"
          :disabled="submitting"
          data-testid="reject-memo"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="reject-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn variant="flat" class="op-risk-action" :loading="submitting" :disabled="confirmDisabled" data-testid="reject-dialog-ok" @click="submit">
          거부
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
