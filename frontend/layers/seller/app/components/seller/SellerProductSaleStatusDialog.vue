<script setup lang="ts">
import type { SellerProductSummary } from '#layers/seller/app/types/seller-product'
import { SELLER_SALE_ACTION_LABEL, type SellerSaleAction } from '#layers/seller/app/lib/constants/seller-product'
import { saleActionConfirmMessage } from '#layers/seller/app/lib/seller-product-sale-status'
import { extractErrorCode, isSellerSuspendedError, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerProducts } from '#layers/seller/app/composables/useSellerProducts'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'

/**
 * 판매중지·재판매 확인 다이얼로그(Track 96-5·D-206·SellerInventoryAdjustDialog 복제·상품 1건 고정·action으로 분기).
 * 성공 시 success 토스트 후 done(부모가 재조회) · 422 PRODUCT_STOPPED_BY_ADMIN(관리자가 그사이 중지)·PRODUCT_INVALID_STATE(상태 변경)·404는 warning 토스트 후
 * stale · **403 SELLER_SUSPENDED는 여기서 danger 토스트로 직접 표시**(FE-44 §8) 후 cancel.
 */
const props = defineProps<{
  open: boolean
  action: SellerSaleAction
  product: Pick<SellerProductSummary, 'productPublicId' | 'name'> | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const productsApi = useSellerProducts()
const toast = useSellerToast()
const submitting = ref(false)

const actionLabel = computed(() => SELLER_SALE_ACTION_LABEL[props.action])
const message = computed(() => (props.product ? saleActionConfirmMessage(props.action, props.product.name) : ''))

watch(() => props.open, (open) => { if (open) submitting.value = false })

async function submit(): Promise<void> {
  if (submitting.value || !props.product) return
  submitting.value = true
  try {
    await productsApi.changeSaleStatus(props.product.productPublicId, props.action)
    toast.success(`"${props.product.name}" ${actionLabel.value} 처리했습니다.`)
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (isSellerSuspendedError(error)) {
      toast.danger(toSellerErrorMessage(error))
      emit('cancel')
    } else if (code === 'PRODUCT_STOPPED_BY_ADMIN' || code === 'PRODUCT_INVALID_STATE' || code === 'PRODUCT_NOT_FOUND') {
      // 조회~처리 사이 상태가 바뀐 경우(관리자 중지·승인 취소 등): 안내 후 목록을 다시 읽는다.
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
    <v-card data-testid="seller-sale-status-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5" data-testid="sale-status-title">{{ actionLabel }}</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-0" data-testid="sale-status-message">{{ message }}</p>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="sale-status-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn
          :color="action === 'STOP' ? 'warning' : 'primary'"
          variant="flat"
          :loading="submitting"
          :disabled="submitting || !product"
          data-testid="sale-status-ok"
          @click="submit"
        >
          {{ actionLabel }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
