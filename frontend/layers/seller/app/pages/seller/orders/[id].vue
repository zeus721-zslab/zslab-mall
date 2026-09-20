<script setup lang="ts">
import { mdiArrowLeft, mdiTruckDeliveryOutline } from '@mdi/js'
import type { SellerOrderItemDetail } from '#layers/seller/app/types/seller-order'
import { orderItemStatusLabel } from '~/lib/constants/claim'
import { formatDateTime } from '~/lib/utils/datetime'
import {
  SELLER_DELIVERY_CARRIER_LABEL,
  SELLER_DELIVERY_STATUS_LABEL,
  SELLER_DELIVERY_STATUS_SEMANTIC,
  SELLER_ORDER_ITEM_STATUS_SEMANTIC,
} from '#layers/seller/app/lib/constants/seller-order'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'
import { canPrepareShipment } from '#layers/seller/app/lib/seller-order-view'
import { SELLER_DELIVERIES_PATH, SELLER_ORDERS_PATH, resolveBackPath } from '#layers/seller/app/lib/seller-back-path'
import { extractErrorCode, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { formatWon } from '#layers/seller/app/lib/format'
import { useSellerOrders } from '#layers/seller/app/composables/useSellerOrders'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '주문 품목 상세 · zslab-mall 셀러' })

// 셀러 품목 상세(Track 90-B-3·D-191). 품목 정보 + 배송지 스냅샷(마스킹 없음·출고 라벨용·수령인 정보만) + 원 발송 배송 상태. 구매자 계정 정보·주문 총액은
// BE가 싣지 않는다. 출고(PAID)는 여기서도 가능하고 배송완료·송장 정정은 배송 화면으로 안내한다. 미존재·타 셀러·미결제(404)는 안내 + 목록 이동.
const route = useRoute()
const ordersApi = useSellerOrders()

const orderItemId = computed<string>(() => String(route.params.id))
const backPath = computed(() => resolveBackPath(route.query.back, SELLER_ORDERS_PATH))

const detail = ref<SellerOrderItemDetail | null>(null)
const loading = ref(true)
const notFound = ref(false)
const loadError = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  notFound.value = false
  loadError.value = null
  try {
    detail.value = await ordersApi.detail(orderItemId.value)
  } catch (error) {
    if (extractErrorCode(error) === 'ORDER_NOT_FOUND') {
      notFound.value = true
    } else {
      loadError.value = toSellerErrorMessage(error)
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)

const shipmentOpen = ref(false)
const shippable = computed(() => (detail.value ? canPrepareShipment(detail.value) : false))
const deliveriesLink = computed(() => (detail.value ? `${SELLER_DELIVERIES_PATH}?keyword=${encodeURIComponent(detail.value.orderNo)}` : SELLER_DELIVERIES_PATH))

function closeShipment(refresh: boolean): void {
  shipmentOpen.value = false
  if (refresh) void load()
}
</script>

<template>
  <div data-testid="seller-order-detail">
    <SellerPageHeader title="주문 품목 상세" :description="detail ? `${detail.orderNo} · ${detail.productName}` : undefined">
      <template #actions>
        <v-btn variant="text" :prepend-icon="mdiArrowLeft" :to="backPath" data-testid="order-detail-back">목록</v-btn>
        <v-btn v-if="shippable" color="primary" variant="flat" :prepend-icon="mdiTruckDeliveryOutline" data-testid="order-detail-prepare-shipment" @click="shipmentOpen = true">출고 처리</v-btn>
      </template>
    </SellerPageHeader>

    <v-card v-if="loading" class="mb-4"><v-card-text><v-skeleton-loader type="article, list-item-two-line" /></v-card-text></v-card>

    <v-card v-else-if="notFound" data-testid="order-detail-not-found">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <p class="text-subtitle-1 font-weight-medium mb-1">주문 품목을 찾을 수 없습니다</p>
        <p class="text-body-2 text-medium-emphasis mb-4">내 품목이 아니거나 미결제 주문이거나 존재하지 않는 품목입니다: {{ orderItemId }}</p>
        <v-btn color="primary" :to="backPath">목록으로</v-btn>
      </v-card-text>
    </v-card>

    <v-alert v-else-if="loadError" type="error" class="mb-4" data-testid="order-detail-error">
      {{ loadError }} <v-btn size="small" variant="outlined" color="error" class="ml-2" @click="load">다시 시도</v-btn>
    </v-alert>

    <template v-else-if="detail">
      <v-row dense>
        <!-- 품목 -->
        <v-col cols="12" md="6">
          <v-card class="mb-4 h-100" data-testid="order-detail-item">
            <v-card-title class="d-flex align-center justify-space-between pt-4 px-5">
              <span class="text-subtitle-2 font-weight-bold">품목</span>
              <v-chip :class="semanticChipClass(SELLER_ORDER_ITEM_STATUS_SEMANTIC[detail.itemStatus])" size="small" variant="flat" data-testid="order-detail-status">
                {{ orderItemStatusLabel(detail.itemStatus) }}
              </v-chip>
            </v-card-title>
            <v-card-text class="px-5 pb-5">
              <div class="text-body-1 font-weight-medium" data-testid="order-detail-product">{{ detail.productName }}</div>
              <div class="text-body-2 text-medium-emphasis mb-3">{{ detail.optionLabel ?? '옵션 없음' }}</div>
              <v-row dense>
                <v-col cols="6"><div class="text-caption text-medium-emphasis">주문번호</div><div class="text-body-2" data-testid="order-detail-order-no">{{ detail.orderNo }}</div></v-col>
                <v-col cols="6"><div class="text-caption text-medium-emphasis">품목 ID</div><div class="slr-product-id">{{ detail.orderItemId }}</div></v-col>
                <v-col cols="6"><div class="text-caption text-medium-emphasis">주문일시</div><div class="text-body-2">{{ formatDateTime(detail.orderedAt) }}</div></v-col>
                <v-col cols="6"><div class="text-caption text-medium-emphasis">결제일시</div><div class="text-body-2" data-testid="order-detail-paid-at">{{ detail.paidAt ? formatDateTime(detail.paidAt) : '—' }}</div></v-col>
                <v-col cols="6"><div class="text-caption text-medium-emphasis">수량 × 단가</div><div class="text-body-2">{{ detail.quantity }} × {{ formatWon(detail.unitPrice) }}</div></v-col>
                <v-col cols="6"><div class="text-caption text-medium-emphasis">품목 금액</div><div class="text-body-2 font-weight-bold" data-testid="order-detail-total">{{ formatWon(detail.totalPrice) }}</div></v-col>
              </v-row>
            </v-card-text>
          </v-card>
        </v-col>
        <!-- 배송지(스냅샷·전체 노출) -->
        <v-col cols="12" md="6">
          <v-card class="mb-4 h-100" data-testid="order-detail-address">
            <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">배송지</v-card-title>
            <v-card-text class="px-5 pb-5">
              <template v-if="detail.shippingAddress">
                <div class="text-body-1 font-weight-medium" data-testid="order-detail-recipient">
                  {{ detail.shippingAddress.recipientName }} <span class="text-body-2 text-medium-emphasis">{{ detail.shippingAddress.recipientPhone }}</span>
                </div>
                <div class="text-body-2 mt-1">
                  [{{ detail.shippingAddress.zonecode }}] {{ detail.shippingAddress.addressRoad }}
                  <span v-if="detail.shippingAddress.addressDetail"> {{ detail.shippingAddress.addressDetail }}</span>
                </div>
                <div v-if="detail.shippingAddress.addressJibun" class="text-caption text-medium-emphasis">지번: {{ detail.shippingAddress.addressJibun }}</div>
                <div v-if="detail.shippingAddress.deliveryMemo" class="text-body-2 mt-1">메모: {{ detail.shippingAddress.deliveryMemo }}</div>
              </template>
              <p v-else class="text-body-2 text-medium-emphasis mb-0">배송지 정보가 없습니다.</p>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- 배송 -->
      <v-card data-testid="order-detail-delivery">
        <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">배송</v-card-title>
        <v-card-text class="px-5 pb-5">
          <template v-if="detail.delivery">
            <div class="d-flex align-center flex-wrap ga-2 mb-2">
              <v-chip :class="semanticChipClass(SELLER_DELIVERY_STATUS_SEMANTIC[detail.delivery.status])" size="small" variant="flat" data-testid="order-detail-delivery-status">
                {{ SELLER_DELIVERY_STATUS_LABEL[detail.delivery.status] }}
              </v-chip>
              <span class="text-body-2" data-testid="order-detail-tracking">{{ SELLER_DELIVERY_CARRIER_LABEL[detail.delivery.carrier] }} {{ detail.delivery.trackingNo }}</span>
            </div>
            <div class="text-caption text-medium-emphasis">
              발송 {{ detail.delivery.shippedAt ? formatDateTime(detail.delivery.shippedAt) : '—' }} · 도착 {{ detail.delivery.deliveredAt ? formatDateTime(detail.delivery.deliveredAt) : '—' }}
            </div>
            <p class="text-body-2 text-medium-emphasis mt-3 mb-0">
              배송완료 처리·송장 정정은 <NuxtLink :to="deliveriesLink" class="text-primary" data-testid="order-detail-deliveries-link">배송 화면</NuxtLink>에서 합니다.
            </p>
          </template>
          <template v-else>
            <p class="text-body-2 text-medium-emphasis mb-0" data-testid="order-detail-no-delivery">
              {{ shippable ? '아직 출고 전입니다. 택배사·송장번호를 입력해 출고 처리하면 배송이 시작됩니다.' : '등록된 배송이 없습니다.' }}
            </p>
          </template>
        </v-card-text>
      </v-card>
    </template>

    <SellerShipmentDialog :open="shipmentOpen" :item="detail" @done="closeShipment(true)" @stale="closeShipment(true)" @cancel="closeShipment(false)" />
  </div>
</template>
