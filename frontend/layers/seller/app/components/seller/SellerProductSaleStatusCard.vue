<script setup lang="ts">
import type { SellerProductDetail } from '#layers/seller/app/types/seller-product'
import {
  SELLER_PRODUCT_STATUS_LABEL,
  SELLER_PRODUCT_STATUS_SEMANTIC,
  SELLER_SALE_ACTION_LABEL,
  SELLER_SALE_STOP_SOURCE_LABEL,
  type SellerSaleAction,
} from '#layers/seller/app/lib/constants/seller-product'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'
import { resolveSellerSaleAction } from '#layers/seller/app/lib/seller-product-sale-status'
import { extractErrorCode, isSellerSuspendedError, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerProducts } from '#layers/seller/app/composables/useSellerProducts'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'

/**
 * 상품 수정 화면 판매 관리 카드(Track 96-5·D-206·FE-57). 판매 상태 칩·중지 주체 · 판매중지/재판매 버튼(확인 다이얼로그는 부모가 띄움) ·
 * 상품 단위 수동 품절 스위치(즉시 PATCH·D3 α). 승인대기·거부됨 상품은 전환 버튼을 숨긴다(BE 422·메뉴 미노출 규칙과 동일).
 *
 * <p><b>폼 보존(외부 검토 R2 Q9 반영)</b>: 카드 조작은 아래 수정 폼을 건드리지 않는다. 품절 성공은 응답(셀러 상세)을 {@code updated}로 올려
 * 부모가 카드 상태만 국소 갱신하고, 실패는 재조회 없이 스위치를 서버 값으로 원복한다. 단 상태가 그사이 바뀐 사유(422 PRODUCT_STOPPED_BY_ADMIN·
 * PRODUCT_INVALID_STATE)와 404는 {@code stale}로 올려 부모가 카드 상태만 재조회한다(폼 재마운트 없음).
 */
const props = defineProps<{
  detail: Pick<SellerProductDetail, 'productPublicId' | 'name' | 'status' | 'saleStopSource' | 'soldoutManual'>
}>()
const emit = defineEmits<{ saleAction: [action: SellerSaleAction]; updated: [detail: SellerProductDetail]; stale: [] }>()

/** 재조회로 카드 상태만 갱신해야 하는 실패 코드(상태가 그사이 바뀜·상품 사라짐). 그 외 실패는 스위치 원복만. */
const STALE_ERROR_CODES = new Set(['PRODUCT_STOPPED_BY_ADMIN', 'PRODUCT_INVALID_STATE', 'PRODUCT_NOT_FOUND'])

const productsApi = useSellerProducts()
const toast = useSellerToast()

const saleAction = computed(() => resolveSellerSaleAction(props.detail.status, props.detail.saleStopSource))
const soldOutSubmitting = ref(false)
// 스위치 로컬 값: 서버 값(prop)을 따르고, 실패 시 재조회 없이 여기서 원복한다.
const soldOut = ref(props.detail.soldoutManual)
watch(() => props.detail.soldoutManual, (value) => { soldOut.value = value })

async function toggleSoldOut(next: boolean): Promise<void> {
  if (soldOutSubmitting.value || next === props.detail.soldoutManual) return
  soldOut.value = next
  soldOutSubmitting.value = true
  try {
    const updated = await productsApi.changeSoldOut(props.detail.productPublicId, next)
    toast.success(next ? '수동 품절로 설정했습니다. 구매자에게 품절로 표시되고 담기·주문이 차단됩니다.' : '수동 품절을 해제했습니다.')
    emit('updated', updated)
  } catch (error) {
    // 403 SELLER_SUSPENDED도 여기서 직접 표시(FE-44 §8). 실패는 재조회 없이 스위치만 원복하고, 상태가 바뀐 사유·404만 카드 재조회(stale).
    toast[isSellerSuspendedError(error) ? 'danger' : 'warning'](toSellerErrorMessage(error))
    soldOut.value = props.detail.soldoutManual
    const code = extractErrorCode(error)
    if (code && STALE_ERROR_CODES.has(code)) emit('stale')
  } finally {
    soldOutSubmitting.value = false
  }
}
</script>

<template>
  <v-card class="mb-4" data-testid="seller-sale-status-card">
    <v-card-text class="pa-5">
      <div class="d-flex align-center flex-wrap ga-3 mb-3">
        <p class="slr-section-title mb-0">판매 관리</p>
        <v-chip :class="semanticChipClass(SELLER_PRODUCT_STATUS_SEMANTIC[detail.status])" size="small" variant="flat" data-testid="sale-card-status-chip">
          {{ SELLER_PRODUCT_STATUS_LABEL[detail.status] }}
        </v-chip>
        <span v-if="detail.status === 'STOPPED' && detail.saleStopSource" class="text-caption text-medium-emphasis" data-testid="sale-card-stop-source">
          {{ SELLER_SALE_STOP_SOURCE_LABEL[detail.saleStopSource] }}
        </span>
      </div>
      <div class="d-flex align-center flex-wrap ga-4">
        <template v-if="saleAction.action">
          <v-btn
            :color="saleAction.action === 'STOP' ? 'warning' : 'primary'"
            variant="flat"
            size="small"
            :disabled="saleAction.disabled"
            data-testid="sale-card-action"
            @click="emit('saleAction', saleAction.action)"
          >
            {{ SELLER_SALE_ACTION_LABEL[saleAction.action] }}
          </v-btn>
          <span v-if="saleAction.note" class="text-caption text-medium-emphasis" data-testid="sale-card-note">{{ saleAction.note }}</span>
        </template>
        <span v-else class="text-caption text-medium-emphasis" data-testid="sale-card-note">승인·거부는 관리자가 처리합니다. 승인 후 판매중지·재판매를 직접 할 수 있습니다.</span>
        <v-spacer />
        <v-switch
          :model-value="soldOut"
          label="수동 품절"
          color="error"
          density="compact"
          hide-details
          inset
          :disabled="soldOutSubmitting"
          :loading="soldOutSubmitting"
          data-testid="sale-card-soldout"
          @update:model-value="(value) => toggleSoldOut(Boolean(value))"
        />
      </div>
      <p class="text-caption text-medium-emphasis mt-3 mb-0">
        판매중지·품절은 즉시 반영됩니다(아래 폼의 저장 전 수정 내용은 유지). 옵션별 품절은 옵션 조합표의 품절 스위치로 따로 지정합니다.
      </p>
    </v-card-text>
  </v-card>
</template>
