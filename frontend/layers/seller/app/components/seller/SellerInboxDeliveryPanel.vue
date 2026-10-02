<script setup lang="ts">
import type { SellerDeliverySummary } from '#layers/seller/app/types/seller-delivery'
import { DEFAULT_SELLER_DELIVERY_QUERY } from '#layers/seller/app/lib/seller-delivery-query'
import { SELLER_DELIVERY_CARRIER_LABEL } from '#layers/seller/app/lib/constants/seller-order'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerDeliveries } from '#layers/seller/app/composables/useSellerDeliveries'
import { INBOX_LOOKUP_PAGE_SIZE, findInboxSourceRow, inboxLookupKeyword } from '~/lib/inbox-view'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 셀러 인박스 장기 배송중 패널(D-251 · 상세 패널 안). 셀러 배송 단건 조회가 없어 기존 배송 목록을 주문번호(행 부제)·배송중으로 읽고 ref(배송 publicId)와
 * 같은 행을 고른다. 못 찾으면 처리 없이 "원래 화면에서 열기"만 남는다. 처리는 기존 배송완료 다이얼로그를 쓴다.
 */
const props = defineProps<{ deliveryPublicId: string; orderNo: string | null }>()
const emit = defineEmits<{ processed: [outcome: 'done' | 'stale'] }>()

const deliveriesApi = useSellerDeliveries()

const row = ref<SellerDeliverySummary | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
const notFound = ref(false)
let loadSequence = 0

async function load(deliveryPublicId: string, orderNo: string | null): Promise<void> {
  const sequence = ++loadSequence
  row.value = null
  notFound.value = false
  loadError.value = null
  const keyword = inboxLookupKeyword(orderNo)
  if (keyword === null) {
    notFound.value = true
    return
  }
  loading.value = true
  try {
    const page = await deliveriesApi.list({ ...DEFAULT_SELLER_DELIVERY_QUERY, keyword, status: 'SHIPPING', size: INBOX_LOOKUP_PAGE_SIZE })
    if (sequence !== loadSequence) return
    row.value = findInboxSourceRow(page.items, deliveryPublicId, (candidate) => candidate.deliveryId)
    notFound.value = row.value === null
  } catch (error) {
    if (sequence !== loadSequence) return
    loadError.value = toSellerErrorMessage(error)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const completeOpen = ref(false)
function onCompleted(outcome: 'done' | 'stale'): void {
  completeOpen.value = false
  emit('processed', outcome)
}

// 항목이 바뀌면 열린 다이얼로그를 닫고 새로 읽는다(다이얼로그 상태 선언 뒤에 둔다 — immediate 실행).
watch(() => props.deliveryPublicId, () => {
  completeOpen.value = false
  void load(props.deliveryPublicId, props.orderNo)
}, { immediate: true })
</script>

<template>
  <div data-testid="inbox-delivery-panel">
    <v-skeleton-loader v-if="loading" type="list-item-two-line" />
    <v-alert v-else-if="loadError" type="error" variant="tonal" density="compact" data-testid="inbox-delivery-error">{{ loadError }}</v-alert>
    <p v-else-if="notFound" class="text-body-2 text-medium-emphasis mb-2" data-testid="inbox-delivery-not-found">
      배송 목록에서 이 배송을 찾지 못했습니다. 원래 화면에서 확인하세요.
    </p>
    <template v-else-if="row">
      <v-row dense class="mb-2">
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">송장</div>
          <div class="text-body-2" data-testid="inbox-delivery-tracking">{{ SELLER_DELIVERY_CARRIER_LABEL[row.carrier] }} {{ row.trackingNo ?? '-' }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">발송</div>
          <div class="text-body-2">{{ row.shippedAt ? formatDateTime(row.shippedAt) : '-' }}</div>
        </v-col>
      </v-row>
      <div class="d-flex flex-wrap ga-2 mb-2">
        <SellerSuspendedGuard v-if="row.status === 'SHIPPING'" v-slot="{ suspended }">
          <v-btn color="primary" variant="flat" :disabled="suspended" data-testid="inbox-delivery-complete" @click="completeOpen = true">
            배송완료 처리
          </v-btn>
        </SellerSuspendedGuard>
      </div>
    </template>

    <SellerMarkDeliveredDialog
      :open="completeOpen"
      :item="row"
      @done="onCompleted('done')"
      @stale="onCompleted('stale')"
      @cancel="completeOpen = false"
    />
  </div>
</template>
