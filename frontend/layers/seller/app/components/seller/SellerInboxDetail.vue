<script setup lang="ts">
import { mdiOpenInNew, mdiSleep } from '@mdi/js'
import type { SellerOrderItemDetail } from '#layers/seller/app/types/seller-order'
import type { SellerProductQuestionItem } from '#layers/seller/app/types/seller-product-question'
import { inboxQuestionAnswerItem, sellerInboxDeadlineChipClass } from '#layers/seller/app/lib/seller-inbox-view'
import { itemLabel } from '#layers/seller/app/lib/seller-order-view'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerOrders } from '#layers/seller/app/composables/useSellerOrders'
import { inboxItemTypeLabel } from '~/lib/constants/inbox'
import { type InboxItem, inboxDeadline, inboxPanelAction } from '~/lib/inbox-view'
import { inboxTargetRoute } from '~/lib/inbox-target'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 셀러 인박스 상세 패널(D-248 · 관리자 AdminInboxDetail 복제 · 레이어 격리). 공통 정보 · 원래 화면 이동 · 보류 · 패널 처리(발송 대기 송장 등록 ·
 * Q&A 답변 · 장기 배송 배송완료·재고 임박 입고 D-251). 처리 다이얼로그는 기존 컴포넌트를 그대로 쓰고 processed로 페이지에 알린다.
 */
const props = defineProps<{ item: InboxItem | null; nowMs: number }>()
const emit = defineEmits<{ processed: [outcome: 'done' | 'stale'] }>()

const ordersApi = useSellerOrders()

const action = computed(() => (props.item ? inboxPanelAction('SELLER', props.item.type) : null))
const deadline = computed(() => inboxDeadline(props.item?.dueAt ?? null, props.nowMs))
const target = computed(() => (props.item ? inboxTargetRoute('SELLER', props.item) : null))

const snoozeOpen = ref(false)
const answerOpen = ref(false)
const shipmentOpen = ref(false)

function finish(outcome: 'done' | 'stale'): void {
  snoozeOpen.value = false
  answerOpen.value = false
  shipmentOpen.value = false
  emit('processed', outcome)
}

// ---------- Q&A 답변 ----------
const questionItem = computed<SellerProductQuestionItem | null>(() => (props.item ? inboxQuestionAnswerItem(props.item) : null))

// ---------- 발송 대기: 품목 단건 조회(송장 등록 다이얼로그 입력 · 셀러 주문 상세와 같은 방식) ----------
const orderItem = ref<SellerOrderItemDetail | null>(null)
const orderItemLoading = ref(false)
const orderItemError = ref<string | null>(null)
let orderItemSequence = 0

async function loadOrderItem(orderItemPublicId: string): Promise<void> {
  const sequence = ++orderItemSequence
  orderItemLoading.value = true
  orderItemError.value = null
  orderItem.value = null
  try {
    const detail = await ordersApi.detail(orderItemPublicId)
    if (sequence !== orderItemSequence) return
    orderItem.value = detail
  } catch (error) {
    if (sequence !== orderItemSequence) return
    orderItemError.value = toSellerErrorMessage(error)
  } finally {
    if (sequence === orderItemSequence) orderItemLoading.value = false
  }
}

watch(() => props.item?.key, () => {
  snoozeOpen.value = false
  answerOpen.value = false
  shipmentOpen.value = false
  if (props.item && action.value === 'SHIPMENT') void loadOrderItem(props.item.sourceRef)
  else orderItem.value = null
}, { immediate: true })
</script>

<template>
  <v-card class="h-100" data-testid="inbox-detail">
    <template v-if="item">
      <v-card-title class="d-flex align-center flex-wrap ga-2 pt-5 px-5">
        <span class="slr-chip slr-chip--neutral" data-testid="inbox-detail-type">{{ inboxItemTypeLabel(item.type) }}</span>
        <span :class="sellerInboxDeadlineChipClass(deadline.tone)" data-testid="inbox-detail-deadline">{{ deadline.text }}</span>
      </v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-1 font-weight-medium mb-1" style="white-space: pre-line" data-testid="inbox-detail-title">{{ item.title }}</p>
        <p v-if="item.subtitle" class="text-body-2 text-medium-emphasis mb-4" data-testid="inbox-detail-subtitle">{{ item.subtitle }}</p>
        <v-row dense class="mb-2">
          <v-col cols="6">
            <div class="text-caption text-medium-emphasis">기한</div>
            <div class="text-body-2" data-testid="inbox-detail-due">{{ item.dueAt ? formatDateTime(item.dueAt) : '없음' }}</div>
          </v-col>
          <v-col cols="6">
            <div class="text-caption text-medium-emphasis">기준 시각</div>
            <div class="text-body-2">{{ item.baseAt ? formatDateTime(item.baseAt) : '-' }}</div>
          </v-col>
        </v-row>

        <template v-if="action === 'SHIPMENT'">
          <v-skeleton-loader v-if="orderItemLoading" type="list-item-two-line" />
          <v-alert v-else-if="orderItemError" type="error" variant="tonal" density="compact" data-testid="inbox-order-item-error">{{ orderItemError }}</v-alert>
          <div v-else-if="orderItem" data-testid="inbox-order-item">
            <div class="text-caption text-medium-emphasis">주문 품목</div>
            <div class="text-body-2">{{ itemLabel(orderItem) }} · 주문 {{ orderItem.orderNo }}</div>
          </div>
        </template>

        <!-- P1c(D-251): 단건 조회가 없는 유형은 기존 목록에서 원천 행을 찾아 기존 다이얼로그로 처리한다. key = 항목 키(관리자 패널과 같은 이유). -->
        <SellerInboxDeliveryPanel
          v-if="action === 'DELIVERY_COMPLETE'"
          :key="item.key"
          :delivery-public-id="item.sourceRef"
          :order-no="item.subtitle"
          @processed="finish"
        />
        <SellerInboxStockPanel v-if="action === 'STOCK_INBOUND'" :key="item.key" :variant-public-id="item.sourceRef" :product-name="item.title" @processed="finish" />
      </v-card-text>
      <v-card-actions class="px-5 pb-5 flex-wrap ga-2">
        <v-btn v-if="action === 'QUESTION_ANSWER' && questionItem" color="primary" variant="flat" data-testid="inbox-detail-action" @click="answerOpen = true">
          답변하기
        </v-btn>
        <v-btn v-if="action === 'SHIPMENT' && orderItem" color="primary" variant="flat" data-testid="inbox-detail-action" @click="shipmentOpen = true">
          발송 처리
        </v-btn>
        <v-btn v-if="target" variant="outlined" :prepend-icon="mdiOpenInNew" :to="{ path: target.path, query: target.query }" data-testid="inbox-detail-open-origin">
          원래 화면에서 열기
        </v-btn>
        <v-btn variant="text" :prepend-icon="mdiSleep" data-testid="inbox-detail-snooze" @click="snoozeOpen = true">보류</v-btn>
      </v-card-actions>
    </template>
    <v-card-text v-else class="text-body-2 text-medium-emphasis text-center py-10" data-testid="inbox-detail-empty">
      왼쪽 목록에서 항목을 고르세요.
    </v-card-text>

    <SellerInboxSnoozeDialog :open="snoozeOpen" :item="item" @done="finish('done')" @stale="finish('stale')" @cancel="snoozeOpen = false" />
    <SellerProductQuestionAnswerDialog :open="answerOpen" :item="questionItem" @done="finish('done')" @stale="finish('stale')" @cancel="answerOpen = false" />
    <SellerShipmentDialog :open="shipmentOpen" :item="orderItem" @done="finish('done')" @stale="finish('stale')" @cancel="shipmentOpen = false" />
  </v-card>
</template>
