<script setup lang="ts">
import type { SellerInventorySummary } from '#layers/seller/app/types/seller-product'
import { DEFAULT_SELLER_INVENTORY_QUERY } from '#layers/seller/app/lib/seller-inventory-query'
import { inventoryItemLabel } from '#layers/seller/app/lib/seller-product-view'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerInventory } from '#layers/seller/app/composables/useSellerInventory'
import { INBOX_LOOKUP_PAGE_SIZE, findInboxSourceRow, inboxLookupKeyword } from '~/lib/inbox-view'

/**
 * 셀러 인박스 재고 임박 패널(D-251 · 상세 패널 안). 재고 단건 조회가 없어 기존 재고 목록을 상품명(행 제목)으로 읽고 ref(옵션 publicId)와 같은 행을 고른다.
 * 못 찾으면 처리 없이 "원래 화면에서 열기"만 남는다. 패널 동작은 입고만이며 기존 입고·출고 다이얼로그를 입고 모드로 쓴다.
 */
const props = defineProps<{ variantPublicId: string; productName: string }>()
const emit = defineEmits<{ processed: [outcome: 'done' | 'stale'] }>()

const inventoryApi = useSellerInventory()

const row = ref<SellerInventorySummary | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
const notFound = ref(false)
let loadSequence = 0

async function load(): Promise<void> {
  const sequence = ++loadSequence
  row.value = null
  notFound.value = false
  loadError.value = null
  const keyword = inboxLookupKeyword(props.productName)
  if (keyword === null) {
    notFound.value = true
    return
  }
  loading.value = true
  try {
    const page = await inventoryApi.list({ ...DEFAULT_SELLER_INVENTORY_QUERY, keyword, size: INBOX_LOOKUP_PAGE_SIZE })
    if (sequence !== loadSequence) return
    row.value = findInboxSourceRow(page.items, props.variantPublicId, (candidate) => candidate.variantPublicId)
    notFound.value = row.value === null
  } catch (error) {
    if (sequence !== loadSequence) return
    loadError.value = toSellerErrorMessage(error)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const inboundOpen = ref(false)
function onInbound(outcome: 'done' | 'stale'): void {
  inboundOpen.value = false
  // 입고 후에도 가용이 1~5면 항목이 남아 같은 키가 다시 선택된다 — 수치가 낡지 않게 패널도 다시 읽는다.
  void load()
  emit('processed', outcome)
}

// 항목이 바뀌면 열린 다이얼로그를 닫고 새로 읽는다(다이얼로그 상태 선언 뒤에 둔다 — immediate 실행).
watch(() => props.variantPublicId, () => {
  inboundOpen.value = false
  void load()
}, { immediate: true })
</script>

<template>
  <div data-testid="inbox-stock-panel">
    <v-skeleton-loader v-if="loading" type="list-item-two-line" />
    <v-alert v-else-if="loadError" type="error" variant="tonal" density="compact" data-testid="inbox-stock-error">{{ loadError }}</v-alert>
    <p v-else-if="notFound" class="text-body-2 text-medium-emphasis mb-2" data-testid="inbox-stock-not-found">
      재고 목록에서 이 옵션을 찾지 못했습니다. 원래 화면에서 확인하세요.
    </p>
    <template v-else-if="row">
      <v-row dense class="mb-2">
        <v-col cols="12">
          <div class="text-caption text-medium-emphasis">옵션</div>
          <div class="text-body-2">{{ inventoryItemLabel(row) }}<span v-if="row.sellerSku"> · {{ row.sellerSku }}</span></div>
        </v-col>
        <v-col cols="4">
          <div class="text-caption text-medium-emphasis">보유</div>
          <div class="text-body-2">{{ row.quantityOnHand }}</div>
        </v-col>
        <v-col cols="4">
          <div class="text-caption text-medium-emphasis">예약</div>
          <div class="text-body-2">{{ row.quantityReserved }}</div>
        </v-col>
        <v-col cols="4">
          <div class="text-caption text-medium-emphasis">가용</div>
          <div class="text-body-2 font-weight-medium" data-testid="inbox-stock-available">{{ row.quantityAvailable }}</div>
        </v-col>
      </v-row>
      <div class="d-flex flex-wrap ga-2 mb-2">
        <v-btn color="primary" variant="flat" data-testid="inbox-stock-inbound" @click="inboundOpen = true">입고</v-btn>
      </div>
    </template>

    <SellerInventoryAdjustDialog
      :open="inboundOpen"
      mode="INBOUND"
      :item="row"
      @done="onInbound('done')"
      @stale="onInbound('stale')"
      @cancel="inboundOpen = false"
    />
  </div>
</template>
