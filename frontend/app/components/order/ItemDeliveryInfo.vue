<script setup lang="ts">
import { deliveryCarrierLabel } from '~/lib/constants/delivery'
import { formatDateTime } from '~/lib/utils/datetime'
import type { OrderItemDelivery } from '~/types/order'

/**
 * 주문 상세 품목 배송 정보 블록(Track 96-2 FE-54·C-05). 원 발송 Delivery가 있으면 택배사·송장번호(복사 버튼)·발송일·배송완료일을,
 * 없고 품목이 발송 전 상태(결제완료·상품준비중)면 "발송 준비 중"을 보인다. 그 외(미결제·취소 등)는 렌더하지 않는다.
 * 복사 결과는 인라인 안내(구매자 앱은 토스트 인프라 부재·FE-53 §1-A와 같은 이유). 외부 추적 링크는 두지 않는다(결정 범위 외).
 */
const props = defineProps<{
  delivery: OrderItemDelivery | null | undefined
  itemStatusCode: string
}>()

/** 발송 전 품목 상태(BE OrderItemStatus PAID·PREPARING). 발송 처리가 PAID→SHIPPING을 1TX로 전이하므로 이 둘이 곧 "발송 준비 중"이다. */
const PRE_SHIPMENT_STATUSES: readonly string[] = ['PAID', 'PREPARING']

const showPreparing = computed<boolean>(() => !props.delivery && PRE_SHIPMENT_STATUSES.includes(props.itemStatusCode))

const copyNotice = ref<{ tone: 'success' | 'error'; text: string } | null>(null)

async function copyTrackingNo(trackingNo: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(trackingNo)
    copyNotice.value = { tone: 'success', text: '송장번호를 복사했습니다.' }
  } catch (copyError) {
    // 권한 거부·비보안 컨텍스트 등. 사용자가 직접 선택해 복사할 수 있도록 안내만 한다.
    console.warn('송장번호 복사 실패', copyError)
    copyNotice.value = { tone: 'error', text: '복사하지 못했습니다. 송장번호를 길게 눌러 복사해 주세요.' }
  }
}
</script>

<template>
  <!-- 흰 카드 안 옅은 면(surface-muted) · 복사는 옅은 면 위 보조 버튼이라 흰 바탕(FE-82 · FE-76 기준). -->
  <div v-if="delivery" class="space-y-1 rounded-card bg-surface-muted px-4 py-3 text-small text-sub" data-testid="item-delivery">
    <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
      <span class="font-semibold text-ink" data-testid="item-delivery-carrier">{{ deliveryCarrierLabel(delivery.carrier) }}</span>
      <template v-if="delivery.trackingNo">
        <span class="select-all tabular-nums text-ink" data-testid="item-delivery-tracking-no">{{ delivery.trackingNo }}</span>
        <button
          type="button"
          class="btn btn-sm bg-white text-primary max-md:min-h-11"
          data-testid="item-delivery-copy"
          @click="copyTrackingNo(delivery.trackingNo)"
        >
          송장번호 복사
        </button>
      </template>
    </div>
    <p v-if="delivery.shippedAt" data-testid="item-delivery-shipped-at">발송일 {{ formatDateTime(delivery.shippedAt) }}</p>
    <p v-if="delivery.deliveredAt" data-testid="item-delivery-delivered-at">배송완료일 {{ formatDateTime(delivery.deliveredAt) }}</p>
    <p
      v-if="copyNotice"
      role="status"
      :class="copyNotice.tone === 'error' ? 'text-soldout' : 'text-primary'"
      data-testid="item-delivery-copy-notice"
    >{{ copyNotice.text }}</p>
  </div>
  <p v-else-if="showPreparing" class="text-small text-sub" data-testid="item-delivery-preparing">발송 준비 중</p>
</template>
