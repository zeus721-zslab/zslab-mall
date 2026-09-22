<script setup lang="ts">
import type { SellerInventorySummary } from '#layers/seller/app/types/seller-product'
import {
  SELLER_INVENTORY_ADJUST_LABEL,
  SELLER_INVENTORY_REASON_MAX,
  type SellerInventoryAdjustMode,
} from '#layers/seller/app/lib/constants/seller-product'
import { inventoryItemLabel, validateInventoryAdjustForm } from '#layers/seller/app/lib/seller-product-view'
import { isLargeInventoryAdjust, largeInventoryAdjustMessage } from '~/lib/utils/inventory-adjust'
import { extractErrorCode, isSellerSuspendedError, mapFieldErrors, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerInventory } from '#layers/seller/app/composables/useSellerInventory'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'

/**
 * 입고·출고 공용 다이얼로그(Track 90-C-3·SellerShipmentDialog 복제·variant 1건 고정·mode로 분기). 수량(양의 정수)·사유(필수·255자).
 * 성공 시 success 토스트 + 조정 후 수치 안내 후 done(부모가 목록 재조회) · 422 INVENTORY_INVARIANT_VIOLATION(실물·가용 부족)·404는 warning 토스트 후 stale ·
 * 400은 fieldErrors 표시 · **403 SELLER_SUSPENDED는 여기서 danger 토스트로 직접 표시**(배너는 useSellerApi가 켜지만 호출부가 삼키면 안 된다·FE-44 §8) 후 cancel.
 */
const props = defineProps<{
  open: boolean
  mode: SellerInventoryAdjustMode
  item: Pick<SellerInventorySummary, 'variantPublicId' | 'productName' | 'optionLabel' | 'quantityOnHand' | 'quantityAvailable'> | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const inventoryApi = useSellerInventory()
const toast = useSellerToast()

const quantity = ref('')
const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)
/** 대량 조정 확인 문구(Track 101-A). 값이 차 있으면 확인 대기 상태이며 다음 제출은 그대로 진행한다. */
const largeWarning = ref('')

const modeLabel = computed(() => SELLER_INVENTORY_ADJUST_LABEL[props.mode])

function reset(): void {
  quantity.value = ''
  reason.value = ''
  errors.value = {}
  submitting.value = false
  largeWarning.value = ''
}
watch(() => props.open, (open) => { if (open) reset() })

function clearError(field: string): void {
  errors.value = { ...errors.value, [field]: '' }
  // 수량을 고쳤으면 확인을 다시 받는다.
  if (field === 'quantity') largeWarning.value = ''
}

async function submit(): Promise<void> {
  if (submitting.value || !props.item) return
  const validation = validateInventoryAdjustForm({ quantity: quantity.value, reason: reason.value })
  errors.value = validation
  if (Object.keys(validation).length > 0) return

  const body = { quantity: Number(quantity.value), reason: reason.value.trim() }
  // 대량 조정은 한 번 확인받고 멈춘다(값 자체는 막지 않는다·Track 101-A). 두 번째 제출은 그대로 진행한다.
  const delta = props.mode === 'INBOUND' ? body.quantity : -body.quantity
  if (largeWarning.value === '' && isLargeInventoryAdjust(delta)) {
    largeWarning.value = largeInventoryAdjustMessage([{ label: inventoryItemLabel(props.item), delta }])
    return
  }

  submitting.value = true
  try {
    const response = props.mode === 'INBOUND'
      ? await inventoryApi.markInbound(props.item.variantPublicId, body)
      : await inventoryApi.markOutbound(props.item.variantPublicId, body)
    toast.success(`${modeLabel.value} ${body.quantity}개 처리했습니다. 보유 ${response.quantityOnHand} · 가용 ${response.quantityAvailable}`)
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (isSellerSuspendedError(error)) {
      toast.danger(toSellerErrorMessage(error))
      emit('cancel')
    } else if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { quantity: toSellerErrorMessage(error) }
    } else if (code === 'INVENTORY_INVARIANT_VIOLATION' || code === 'PRODUCT_VARIANT_NOT_FOUND') {
      // 조회~처리 사이 재고가 바뀐 경우(주문 예약·다른 창의 출고 등): 안내 후 목록을 다시 읽는다.
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
    <v-card data-testid="seller-inventory-adjust-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5" data-testid="adjust-title">{{ modeLabel }} 처리</v-card-title>
      <v-card-text class="px-5">
        <p v-if="item" class="text-body-2 mb-1" data-testid="adjust-item">{{ inventoryItemLabel(item) }}</p>
        <p v-if="item" class="text-caption text-medium-emphasis mb-3">
          현재 보유 {{ item.quantityOnHand }} · 가용 {{ item.quantityAvailable }} ·
          {{ mode === 'INBOUND' ? '입고 수량만큼 보유·가용이 늘어납니다.' : '출고 수량만큼 보유·가용이 줄어듭니다(가용 초과 불가).' }}
        </p>
        <v-text-field
          v-model="quantity"
          :label="`${modeLabel} 수량`"
          type="number"
          min="1"
          step="1"
          :error-messages="errors.quantity ? [errors.quantity] : []"
          :disabled="submitting"
          class="mb-2"
          data-testid="adjust-quantity"
          @update:model-value="clearError('quantity')"
        />
        <v-text-field
          v-model="reason"
          label="사유"
          :maxlength="SELLER_INVENTORY_REASON_MAX"
          :counter="SELLER_INVENTORY_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          data-testid="adjust-reason"
          @update:model-value="clearError('reason')"
          @keyup.enter="submit"
        />
        <v-alert
          v-if="largeWarning"
          type="warning"
          variant="tonal"
          density="compact"
          class="mt-3 text-body-2"
          style="white-space: pre-line"
          data-testid="adjust-large-warning"
        >{{ largeWarning }}</v-alert>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="adjust-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn
          :color="mode === 'INBOUND' ? 'primary' : 'warning'"
          variant="flat"
          :loading="submitting"
          :disabled="submitting || !item"
          data-testid="adjust-dialog-ok"
          @click="submit"
        >
          {{ largeWarning ? `확인하고 ${modeLabel}` : modeLabel }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
