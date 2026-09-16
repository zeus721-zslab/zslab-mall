<script setup lang="ts">
import type { AdminOrderDetail } from '#layers/admin/app/types/admin-order'
import { CLAIM_REASON_CODES, CLAIM_REASON_LABELS, orderItemStatusLabel, type ClaimReasonCode } from '~/lib/constants/claim'
import { ADMIN_ORDER_CANCEL_DETAIL_MAX } from '#layers/admin/app/lib/constants/admin-order'
import { formatWon } from '#layers/admin/app/lib/format'
import {
  cancelResultMessage,
  cancellableItems,
  isUnpaidOrder,
  mapFieldErrors,
  validateCancelForm,
} from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 관리자 주문 취소 다이얼로그(FE-27). 결제 후 주문은 취소 가능 품목(PAID·PREPARING) 체크 목록(기본 전체) + 사유(필수) + 메모(선택·500),
 * 미결제 주문은 품목 선택 없이 전체 종료 안내 + 사유. 성공 시 danger 토스트 후 done, 409/422(상태 경합)는 warning 토스트 후 stale
 * (부모가 상세를 다시 읽음), 400은 fieldErrors를 필드에 표시한다. 처리 중에는 닫기·재제출을 막는다.
 */
const props = defineProps<{
  open: boolean
  detail: AdminOrderDetail | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const ordersApi = useAdminOrders()
const toast = useAdminToast()

const unpaid = computed(() => (props.detail ? isUnpaidOrder(props.detail) : false))
const candidates = computed(() => (props.detail ? cancellableItems(props.detail.items) : []))

const selectedIds = ref<string[]>([])
const reasonCode = ref<ClaimReasonCode | null>(null)
const reasonDetail = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

const reasonItems = CLAIM_REASON_CODES.map((code) => ({ value: code, title: CLAIM_REASON_LABELS[code] }))

function reset(): void {
  selectedIds.value = candidates.value.map((item) => item.orderItemId)
  reasonCode.value = null
  reasonDetail.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

function toggleItem(orderItemId: string, checked: boolean): void {
  const next = new Set(selectedIds.value)
  if (checked) next.add(orderItemId)
  else next.delete(orderItemId)
  selectedIds.value = [...next]
}

const confirmDisabled = computed(() => submitting.value || (!unpaid.value && selectedIds.value.length === 0))

async function submit(): Promise<void> {
  if (submitting.value || !props.detail) return
  const validation = validateCancelForm({
    unpaid: unpaid.value, selectedItemIds: selectedIds.value, reasonCode: reasonCode.value, reasonDetail: reasonDetail.value,
  })
  errors.value = validation
  if (Object.keys(validation).length > 0 || !reasonCode.value) return

  submitting.value = true
  try {
    const response = await ordersApi.cancel(props.detail.orderId, {
      reasonCode: reasonCode.value,
      reasonDetail: reasonDetail.value.trim() || undefined,
      // 미결제는 BE가 품목 목록을 무시하므로 보내지 않는다.
      orderItemPublicIds: unpaid.value ? undefined : selectedIds.value,
    })
    const result = cancelResultMessage(response)
    toast.show(result.semantic, result.text)
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      errors.value = mapFieldErrors(error)
      if (Object.keys(errors.value).length === 0) toast.danger(toAdminErrorMessage(error))
    } else if (code === 'OPTIMISTIC_LOCK_FAILURE' || code === 'CLAIM_STATE_INVALID') {
      // 조회~취소 사이 상태가 바뀐 경우: 안내 후 부모가 최신 상세를 다시 읽는다.
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
    <v-card data-testid="admin-order-cancel-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">주문 취소</v-card-title>
      <v-card-text class="px-5">
        <template v-if="unpaid">
          <v-alert type="warning" variant="tonal" density="compact" class="mb-4" data-testid="cancel-unpaid-notice">
            미결제 주문을 종료합니다. 재고 예약이 해제되며 주문 상태는 "미결제 종료"가 됩니다.
          </v-alert>
        </template>
        <template v-else>
          <p class="text-body-2 mb-2">취소할 품목을 선택하세요. 승인 즉시 환불이 진행됩니다.</p>
          <div class="adm-cancel-items mb-1" data-testid="cancel-items">
            <div
              v-for="item in candidates"
              :key="item.orderItemId"
              class="d-flex align-center ga-2 py-1"
            >
              <v-checkbox-btn
                style="flex: 0 0 auto"
                :model-value="selectedIds.includes(item.orderItemId)"
                :disabled="submitting"
                :data-testid="`cancel-item-${item.orderItemId}`"
                @update:model-value="(value) => toggleItem(item.orderItemId, Boolean(value))"
              />
              <div class="flex-grow-1" style="min-width: 0">
                <div class="text-body-2 font-weight-medium">{{ item.productName }}</div>
                <div class="text-caption text-medium-emphasis">
                  {{ item.optionLabel ? `${item.optionLabel} · ` : '' }}수량 {{ item.quantity }} · {{ formatWon(item.totalPrice) }} · {{ orderItemStatusLabel(item.status) }}
                </div>
              </div>
            </div>
            <p v-if="candidates.length === 0" class="text-body-2 text-medium-emphasis py-2">취소 가능한 품목이 없습니다.</p>
          </div>
          <p v-if="errors.items" class="text-caption text-error mb-3" data-testid="cancel-items-error">{{ errors.items }}</p>
        </template>

        <v-select
          :model-value="reasonCode"
          :items="reasonItems"
          label="취소 사유"
          placeholder="사유를 선택하세요"
          :error-messages="errors.reasonCode ? [errors.reasonCode] : []"
          :disabled="submitting"
          class="mb-2"
          data-testid="cancel-reason"
          @update:model-value="(value) => { reasonCode = value ?? null; errors = { ...errors, reasonCode: '' } }"
        />
        <v-textarea
          v-model="reasonDetail"
          label="상세 사유 (선택)"
          rows="3"
          auto-grow
          :maxlength="ADMIN_ORDER_CANCEL_DETAIL_MAX"
          :counter="ADMIN_ORDER_CANCEL_DETAIL_MAX"
          :error-messages="errors.reasonDetail ? [errors.reasonDetail] : []"
          :disabled="submitting"
          data-testid="cancel-reason-detail"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="cancel-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="error" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="cancel-dialog-ok" @click="submit">
          {{ unpaid ? '주문 종료' : `${selectedIds.length}개 품목 취소` }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
