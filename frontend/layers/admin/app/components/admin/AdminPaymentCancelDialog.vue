<script setup lang="ts">
import type { AdminOrderPayment } from '#layers/admin/app/types/admin-order'
import { ADMIN_PAYMENT_CANCEL_REASON_MAX } from '#layers/admin/app/lib/constants/admin-order'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { formatWon } from '#layers/admin/app/lib/format'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 관리자 수동 결제 취소 다이얼로그(FE-36·Track 89-A·BE D-113 fallback). 자동 전이(환불 완료 → 결제 취소)가 유실된 PAID 결제를 운영자가
 * CANCELLED로 보정하는 경로라 사유(필수·감사 로그)를 받는다. BE는 전액 환불 완료가 아니면 상태를 바꾸지 않고 200을 돌려주므로(NO-OP)
 * 응답 status가 그대로 PAID면 warning으로 알린다. 호출·토스트는 다이얼로그가 소유하고 부모는 done 시 다시 읽는다(AdminClaimRejectDialog 패턴).
 */
const props = defineProps<{
  open: boolean
  payment: AdminOrderPayment | null
}>()
const emit = defineEmits<{ done: []; cancel: [] }>()

const ordersApi = useAdminOrders()
const toast = useAdminToast()

const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

// 닫힘 애니메이션 동안 금액이 사라지지 않도록 마지막 대상을 유지한다(payment는 즉시 null).
const lastPayment = ref<AdminOrderPayment | null>(null)
watch(() => props.payment, (next) => { if (next) lastPayment.value = next })

function reset(): void {
  reason.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

const confirmDisabled = computed(() => submitting.value || reason.value.trim() === '')

async function submit(): Promise<void> {
  if (submitting.value || !props.payment) return
  const trimmed = reason.value.trim()
  if (trimmed === '') {
    errors.value = { reason: '사유를 입력하세요.' }
    return
  }
  submitting.value = true
  try {
    const response = await ordersApi.markPaymentCancelled(props.payment.paymentId, { reason: trimmed })
    if (response.status === 'CANCELLED') {
      toast.danger('결제를 취소 처리했습니다.') // 결제 취소는 부정적 의미
    } else {
      toast.warning('전액 환불이 완료된 결제만 취소 처리됩니다. 상태가 바뀌지 않았습니다.')
    }
    emit('done')
  } catch (error) {
    if (extractErrorCode(error) === 'VALIDATION_FAILED') {
      errors.value = mapFieldErrors(error)
      if (Object.keys(errors.value).length === 0) toast.danger(toAdminErrorMessage(error))
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
    <v-card data-testid="admin-payment-cancel-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">결제 취소 처리</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-3">
          <span class="font-weight-medium" data-testid="payment-cancel-amount">{{ formatWon(lastPayment?.amount ?? 0) }}</span>
          결제를 취소 상태로 보정합니다. 환불이 전액 완료됐는데 결제가 결제완료로 남아 있을 때만 사용하세요(PG 환불을 새로 만들지 않습니다).
        </p>
        <v-textarea
          v-model="reason"
          label="사유"
          placeholder="예: 환불 완료 콜백 유실로 수동 보정"
          rows="2"
          auto-grow
          :maxlength="ADMIN_PAYMENT_CANCEL_REASON_MAX"
          :counter="ADMIN_PAYMENT_CANCEL_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          data-testid="payment-cancel-reason"
          @update:model-value="errors = { ...errors, reason: '' }"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="payment-cancel-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="error" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="payment-cancel-dialog-ok" @click="submit">
          취소 처리
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
