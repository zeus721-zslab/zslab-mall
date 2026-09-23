<script setup lang="ts">
import { claimTypeLabel, type ClaimType } from '~/lib/constants/claim'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { formatWon } from '#layers/admin/app/lib/format'
import { refundInitiateMessage } from '#layers/admin/app/lib/admin-risk-confirm'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 환불 개시 대상 최소 정보(목록 행). amount는 품목 결제금액(표시용), remainingRefundable은 품목 잔여 환불 상한(품목 금액 − 기환불액·
 * Track 104-4)으로 기본값이자 최댓값이 된다 — 품목 전액을 기본값으로 두면 앞선 환불이 있는 품목에서 BE 품목 상한 422가 났다.
 */
export interface AdminRefundInitiateTarget {
  claimId: string
  type: ClaimType
  productName: string
  amount: number | null
  remainingRefundable: number
}

/**
 * 관리자 수동 환불 개시 다이얼로그(FE-36·Track 89-A·BE D-106 fallback). 자동 환불(취소 승인·반품 검수 합격)이 유실됐거나 실패한 APPROVED
 * 클레임에서 운영자가 환불을 다시 개시한다. 금액은 품목 잔여 상한을 기본으로 보여주되 1 이상·잔여 상한 이하로 수정할 수 있다(결제액 초과는 BE 422).
 * 호출·토스트는 다이얼로그가 소유하고 422(상태 경합·한도 초과)는 warning 후 stale(부모 재조회)·400은 필드 표시(AdminClaimRejectDialog 패턴).
 */
const props = defineProps<{
  open: boolean
  target: AdminRefundInitiateTarget | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const ordersApi = useAdminOrders()
const toast = useAdminToast()

const amountInput = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

const lastTarget = ref<AdminRefundInitiateTarget | null>(null)
watch(() => props.target, (next) => { if (next) lastTarget.value = next })

function reset(): void {
  amountInput.value = props.target ? String(props.target.remainingRefundable) : ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

const maxAmount = computed<number>(() => (props.target ?? lastTarget.value)?.remainingRefundable ?? 0)
const amountOverMax = computed<boolean>(() => Number(amountInput.value) > maxAmount.value)
const amountValue = computed<number | null>(() => {
  const parsed = Number(amountInput.value)
  return Number.isInteger(parsed) && parsed >= 1 && parsed <= maxAmount.value ? parsed : null
})
const amountErrors = computed<string[]>(() => {
  if (errors.value.amount) return [errors.value.amount]
  return amountOverMax.value ? [`환불 가능 잔액 ${formatWon(maxAmount.value)} 이하로 입력하세요.`] : []
})
const confirmDisabled = computed(() => submitting.value || amountValue.value === null)
const title = computed(() => `${claimTypeLabel(lastTarget.value?.type ?? 'CANCEL')} 환불 개시`)

async function submit(): Promise<void> {
  if (submitting.value || !props.target) return
  const amount = amountValue.value
  if (amount === null) {
    errors.value = { amount: `1원 이상 ${formatWon(maxAmount.value)} 이하의 정수 금액을 입력하세요.` }
    return
  }
  submitting.value = true
  try {
    const response = await ordersApi.initiateRefund(props.target.claimId, { amount })
    if (response.status === 'FAILED') {
      toast.warning('환불 요청이 PG에서 실패했습니다. 잠시 후 다시 시도하세요.')
    } else {
      toast.info(`${formatWon(response.amount)} 환불을 개시했습니다.`) // 완료는 콜백 후 재조회 시 반영
    }
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      errors.value = mapFieldErrors(error)
      if (Object.keys(errors.value).length === 0) errors.value = { amount: toAdminErrorMessage(error) }
    } else if (code === 'CLAIM_STATE_INVALID' || code === 'REFUND_INVARIANT_VIOLATION') {
      // 조회~개시 사이 상태가 바뀌었거나(비승인) 누적 환불이 결제액을 넘는 경우: 안내 후 부모가 최신 데이터를 다시 읽는다.
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
    <v-card data-testid="admin-refund-initiate-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ title }}</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-1" style="white-space: pre-line" data-testid="refund-initiate-notice">{{ refundInitiateMessage(lastTarget?.productName ?? '') }}</p>
        <p class="text-body-2 mb-3">
          품목 금액 <span class="font-weight-medium" data-testid="refund-initiate-item-amount">{{ lastTarget?.amount != null ? formatWon(lastTarget.amount) : '—' }}</span>
        </p>
        <v-text-field
          v-model="amountInput"
          label="환불 금액(원)"
          type="number"
          min="1"
          :max="maxAmount"
          step="1"
          :error-messages="amountErrors"
          :disabled="submitting"
          data-testid="refund-initiate-amount"
          @update:model-value="errors = { ...errors, amount: '' }"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="refund-initiate-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn variant="flat" class="op-risk-action" :loading="submitting" :disabled="confirmDisabled" data-testid="refund-initiate-dialog-ok" @click="submit">
          환불 개시
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
