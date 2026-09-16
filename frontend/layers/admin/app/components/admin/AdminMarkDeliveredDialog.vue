<script setup lang="ts">
import type { AdminOrderDetail } from '#layers/admin/app/types/admin-order'
import { ADMIN_DELIVERY_CARRIER_LABEL } from '#layers/admin/app/lib/constants/admin-order'
import { deliverableItems } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 배송완료 확인 다이얼로그(FE-27). 배송중(SHIPPING) 배송을 고르고(1개면 자동 선택) 확인하면 mark-delivered를 호출한다.
 * 성공 시 success 토스트 후 done, 422(이미 완료 등)는 warning 토스트 후 stale.
 */
const props = defineProps<{
  open: boolean
  detail: AdminOrderDetail | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const ordersApi = useAdminOrders()
const toast = useAdminToast()

const candidates = computed(() => (props.detail ? deliverableItems(props.detail.items) : []))
const deliveryOptions = computed(() => candidates.value.map((item) => ({
  value: item.delivery!.deliveryId,
  title: `${item.productName} · ${ADMIN_DELIVERY_CARRIER_LABEL[item.delivery!.carrier]} ${item.delivery!.trackingNo}`,
})))

const deliveryId = ref<string | null>(null)
const submitting = ref(false)

watch(() => props.open, (open) => {
  if (!open) return
  deliveryId.value = candidates.value.length === 1 ? candidates.value[0]!.delivery!.deliveryId : null
  submitting.value = false
})

async function submit(): Promise<void> {
  if (submitting.value || !deliveryId.value) return
  submitting.value = true
  try {
    await ordersApi.markDelivered(deliveryId.value)
    toast.success('배송완료로 처리했습니다.')
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'DELIVERY_INVALID_STATE' || code === 'DELIVERY_NOT_FOUND') {
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
  <v-dialog :model-value="open" max-width="480" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-mark-delivered-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">배송완료 처리</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-3">선택한 배송을 배송완료로 바꿉니다. 되돌릴 수 없습니다.</p>
        <p v-if="candidates.length === 0" class="text-body-2 text-medium-emphasis" data-testid="delivered-empty">배송중인 품목이 없습니다.</p>
        <v-select
          v-else
          :model-value="deliveryId"
          :items="deliveryOptions"
          label="대상 배송"
          :disabled="submitting"
          hide-details
          data-testid="delivered-delivery"
          @update:model-value="(value) => (deliveryId = value ?? null)"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="delivered-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="success" variant="flat" :loading="submitting" :disabled="submitting || !deliveryId" data-testid="delivered-dialog-ok" @click="submit">
          배송완료
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
