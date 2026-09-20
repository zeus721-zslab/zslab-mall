<script setup lang="ts">
import { mdiAlertCircleOutline, mdiWarehouse } from '@mdi/js'
import type { SellerInventorySummary, SellerInventoryListQuery } from '#layers/seller/app/types/seller-product'
import type { SellerInventoryAdjustMode } from '#layers/seller/app/lib/constants/seller-product'
import {
  DEFAULT_SELLER_INVENTORY_QUERY,
  hasActiveInventoryFilters,
  parseSellerInventoryQuery,
  toSellerInventoryRouteQuery,
} from '#layers/seller/app/lib/seller-inventory-query'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerInventory } from '#layers/seller/app/composables/useSellerInventory'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '재고 · zslab-mall 셀러' })

// 셀러 재고(Track 90-C-3·90-C-1 목록 + D-112 입출고·주문 화면 골격 복제). 행 = variant. URL query가 필터·페이지의 단일 소스: 화면 조작 → router.replace →
// route.query watch → 조회. 입고·출고는 행 버튼에서 공용 다이얼로그(mode)를 열고, 성공·stale 모두 목록을 재조회한다. 재고 변동 이력은 범위 밖(이월).
const route = useRoute()
const router = useRouter()
const inventoryApi = useSellerInventory()

const query = computed<SellerInventoryListQuery>(() => parseSellerInventoryQuery(route.query))

// ---------- 목록 ----------
const items = ref<SellerInventorySummary[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await inventoryApi.list(query.value)
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    items.value = response.items
    totalCount.value = response.totalCount
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toSellerErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

// router.replace가 반영되기 전에 연속 확정되면 앞 변경이 유실되므로 마지막으로 보낸 상태를 기준으로 합친다.
let pendingQuery: SellerInventoryListQuery | null = null
watch(() => route.query, () => { pendingQuery = null; void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<SellerInventoryListQuery>, resetPage = true): void {
  const next: SellerInventoryListQuery = { ...(pendingQuery ?? query.value), ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  pendingQuery = next
  void router.replace({ query: toSellerInventoryRouteQuery(next) })
}

function resetQuery(): void {
  pendingQuery = null
  void router.replace({ query: toSellerInventoryRouteQuery({ ...DEFAULT_SELLER_INVENTORY_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveInventoryFilters(query.value))

// ---------- 입출고 다이얼로그 ----------
const adjustItem = ref<SellerInventorySummary | null>(null)
const adjustMode = ref<SellerInventoryAdjustMode>('INBOUND')
const pendingIds = computed<Set<string>>(() => (adjustItem.value ? new Set([adjustItem.value.variantPublicId]) : new Set()))

function openAdjust(item: SellerInventorySummary, mode: SellerInventoryAdjustMode): void {
  adjustMode.value = mode
  adjustItem.value = item
}

function closeAdjust(refresh: boolean): void {
  adjustItem.value = null
  if (refresh) void load()
}
</script>

<template>
  <div data-testid="seller-inventory">
    <SellerPageHeader title="재고" description="옵션(변형) 단위 재고를 조회하고 입고·출고를 처리합니다. 주문에 묶인 예약 수량은 가용에서 제외됩니다." />

    <SellerInventoryFilterCard :query="query" @apply="applyQuery" @reset="resetQuery" />

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="seller-inventory-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="seller-inventory-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <SellerInventoryTable
        v-else
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        :pending-ids="pendingIds"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
        @inbound="(item) => openAdjust(item, 'INBOUND')"
        @outbound="(item) => openAdjust(item, 'OUTBOUND')"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="seller-inventory-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiWarehouse" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <template v-if="filtersActive">
              <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 재고가 없습니다</p>
              <p class="text-body-2 text-medium-emphasis mb-3">검색어·상품 한정 필터를 바꾸거나 초기화해 보세요.</p>
              <v-btn size="small" variant="outlined" @click="resetQuery">필터 초기화</v-btn>
            </template>
            <template v-else>
              <p class="text-subtitle-2 font-weight-medium mb-1">재고가 없습니다</p>
              <p class="text-body-2 text-medium-emphasis">등록된 상품의 옵션(변형)이 여기에 표시됩니다.</p>
            </template>
          </div>
        </template>
      </SellerInventoryTable>
    </v-card>

    <SellerInventoryAdjustDialog
      :open="adjustItem !== null"
      :mode="adjustMode"
      :item="adjustItem"
      @done="closeAdjust(true)"
      @stale="closeAdjust(true)"
      @cancel="closeAdjust(false)"
    />
  </div>
</template>
