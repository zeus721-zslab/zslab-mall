<script setup lang="ts">
import type { SellerDeliverySummary } from '#layers/seller/app/types/seller-delivery'
import { SELLER_DELIVERY_CARRIER_LABEL } from '#layers/seller/app/lib/constants/seller-order'
import { extractErrorCode, isSellerSuspendedError, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerDeliveries } from '#layers/seller/app/composables/useSellerDeliveries'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'

/**
 * 배송완료 확인 다이얼로그(Track 90-B-3·관리자 AdminMarkDeliveredDialog 복제·배송 1건 고정). 확인하면 mark-delivered를 호출한다.
 * 성공 시 success 토스트 후 done · 422(이미 완료 등)·404는 warning 토스트 후 stale · **403 SELLER_SUSPENDED는 danger 토스트로 직접 표시**(FE-44 §8) 후 cancel.
 */
const props = defineProps<{
  open: boolean
  item: Pick<SellerDeliverySummary, 'deliveryId' | 'orderNo' | 'productName' | 'carrier' | 'trackingNo'> | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const deliveriesApi = useSellerDeliveries()
const toast = useSellerToast()

const submitting = ref(false)
watch(() => props.open, (open) => { if (open) submitting.value = false })

async function submit(): Promise<void> {
  if (submitting.value || !props.item) return
  submitting.value = true
  try {
    await deliveriesApi.markDelivered(props.item.deliveryId)
    toast.success('배송완료로 처리했습니다.')
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (isSellerSuspendedError(error)) {
      toast.danger(toSellerErrorMessage(error))
      emit('cancel')
    } else if (code === 'DELIVERY_INVALID_STATE' || code === 'DELIVERY_NOT_FOUND') {
      toast.warning(toSellerErrorMessage(error))
      emit('stale')
    } else {
      toast.danger(toSellerErrorMessage(error))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="480" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="seller-mark-delivered-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">배송완료 처리</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-3">선택한 배송을 배송완료로 바꿉니다. 되돌릴 수 없습니다.</p>
        <p v-if="item" class="text-body-2 mb-0" data-testid="delivered-target">
          {{ item.productName ?? '—' }} · {{ SELLER_DELIVERY_CARRIER_LABEL[item.carrier] }} {{ item.trackingNo ?? '' }}
          <span class="text-caption text-medium-emphasis d-block">주문번호 {{ item.orderNo ?? '—' }}</span>
        </p>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="delivered-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="success" variant="flat" :loading="submitting" :disabled="submitting || !item" data-testid="delivered-dialog-ok" @click="submit">
          배송완료
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
