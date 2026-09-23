<script setup lang="ts">
import { mdiArrowLeft } from '@mdi/js'
import type { SellerSettlementDetail, SellerSettlementItem } from '#layers/seller/app/types/seller-settlement'
import {
  DEFAULT_SELLER_SETTLEMENT_ITEM_TAB,
  DEFAULT_SELLER_SETTLEMENT_PAGE_SIZE,
  SELLER_SETTLEMENT_ITEM_TABS,
  SELLER_SETTLEMENT_PAGE_SIZES,
  SELLER_SETTLEMENT_STATUS_SEMANTIC,
  type SellerSettlementItemType,
} from '#layers/seller/app/lib/constants/seller-settlement'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'
import {
  bankAccountSourceLabel,
  formatBankAccount,
  formatDateOnly,
  formatSettlementPeriod,
  isNegativeNet,
  settlementStatusLabel,
} from '#layers/seller/app/lib/seller-settlement-view'
import { SELLER_SETTLEMENTS_PATH, resolveBackPath } from '#layers/seller/app/lib/seller-back-path'
import { extractErrorCode, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { formatWon } from '#layers/seller/app/lib/format'
import { formatDateTime } from '~/lib/utils/datetime'
import { SETTLEMENT_ITEM_TYPE_LABELS } from '~/lib/constants/settlement'
import { useSellerSettlements } from '#layers/seller/app/composables/useSellerSettlements'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '정산 상세 · zslab-mall 셀러' })

// 셀러 정산 상세(Track 90-B-3·Track 85 BE·관리자 settlements/[id].vue 골격 복제·읽기 전용·액션 없음). 헤더(기간·금액 4종·상태·지급예정일·지급일)·
// 계좌(끝 4자리·스냅샷/현재 구분)·품목 탭(판매/환불/이월 차감·URL query ?tab=·?page=·?size=). 미존재·타 셀러·확정 대기(404)은 안내 + 목록 이동.
const route = useRoute()
const router = useRouter()
const settlementsApi = useSellerSettlements()

const settlementId = computed<number>(() => Number(route.params.id))
const backPath = computed(() => resolveBackPath(route.query.back, SELLER_SETTLEMENTS_PATH))

const detail = ref<SellerSettlementDetail | null>(null)
const loading = ref(true)
const notFound = ref(false)
const loadError = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  notFound.value = false
  loadError.value = null
  if (!Number.isInteger(settlementId.value) || settlementId.value <= 0) {
    notFound.value = true
    loading.value = false
    return
  }
  try {
    detail.value = await settlementsApi.get(settlementId.value)
  } catch (error) {
    if (extractErrorCode(error) === 'SETTLEMENT_NOT_FOUND') {
      notFound.value = true
    } else {
      loadError.value = toSellerErrorMessage(error)
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)

const periodLabel = computed(() => (detail.value ? formatSettlementPeriod(detail.value.periodStart) : ''))
const bankAccountText = computed(() => formatBankAccount(detail.value?.bankAccount))

// ---------- 품목 탭(URL query 단일 소스: tab·page·size) ----------
function parseTab(value: unknown): SellerSettlementItemType {
  const single = Array.isArray(value) ? value[0] : value
  return SELLER_SETTLEMENT_ITEM_TABS.some((tab) => tab.value === single) ? (single as SellerSettlementItemType) : DEFAULT_SELLER_SETTLEMENT_ITEM_TAB
}
function parsePage(value: unknown): number {
  const page = Number(Array.isArray(value) ? value[0] : value)
  return Number.isInteger(page) && page > 0 ? page : 0
}
function parseSize(value: unknown): number {
  const size = Number(Array.isArray(value) ? value[0] : value)
  return SELLER_SETTLEMENT_PAGE_SIZES.includes(size) ? size : DEFAULT_SELLER_SETTLEMENT_PAGE_SIZE
}
const activeTab = computed<SellerSettlementItemType>(() => parseTab(route.query.tab))
const itemPage = computed<number>(() => parsePage(route.query.page))
const itemSize = computed<number>(() => parseSize(route.query.size))

function applyItemQuery(patch: { tab?: SellerSettlementItemType; page?: number; size?: number }): void {
  const tab = patch.tab ?? activeTab.value
  const size = patch.size ?? itemSize.value
  const page = patch.tab !== undefined || patch.size !== undefined ? 0 : (patch.page ?? itemPage.value)
  const query: Record<string, string> = {}
  const back = Array.isArray(route.query.back) ? route.query.back[0] : route.query.back
  if (typeof back === 'string') query.back = back
  if (tab !== DEFAULT_SELLER_SETTLEMENT_ITEM_TAB) query.tab = tab
  if (page > 0) query.page = String(page)
  if (size !== DEFAULT_SELLER_SETTLEMENT_PAGE_SIZE) query.size = String(size)
  void router.replace({ query })
}

const itemRows = ref<SellerSettlementItem[]>([])
const itemTotal = ref(0)
const itemLoading = ref(false)
const itemError = ref<string | null>(null)
let itemSequence = 0

async function loadItems(): Promise<void> {
  if (!Number.isInteger(settlementId.value) || settlementId.value <= 0) return
  const sequence = ++itemSequence
  itemLoading.value = true
  itemError.value = null
  try {
    const response = await settlementsApi.listItems(settlementId.value, activeTab.value, itemPage.value, itemSize.value)
    if (sequence !== itemSequence) return
    itemRows.value = response.items
    itemTotal.value = response.totalCount
  } catch (error) {
    if (sequence !== itemSequence) return
    // 상세가 404면 품목도 404 — 상세 카드가 안내하므로 품목 표는 조용히 비운다(상세 notFound와 중복 안내 방지).
    itemError.value = extractErrorCode(error) === 'SETTLEMENT_NOT_FOUND' ? null : toSellerErrorMessage(error)
  } finally {
    if (sequence === itemSequence) itemLoading.value = false
  }
}
watch([activeTab, itemPage, itemSize, settlementId], () => { void loadItems() }, { immediate: true })

const itemEmptyMessage = computed(() => `${SETTLEMENT_ITEM_TYPE_LABELS[activeTab.value]} 품목이 없습니다`)

function tabCount(tab: SellerSettlementItemType): number {
  if (!detail.value) return 0
  const counts: Record<SellerSettlementItemType, number> = {
    SALE: detail.value.saleItemCount,
    REFUND: detail.value.refundItemCount,
    CARRYOVER: detail.value.carryoverItemCount,
  }
  return counts[tab]
}
</script>

<template>
  <div data-testid="seller-settlement-detail">
    <SellerPageHeader title="정산 상세" :description="detail ? `${periodLabel} 정산` : undefined">
      <template #actions>
        <v-btn variant="text" :prepend-icon="mdiArrowLeft" :to="backPath" data-testid="settlement-back">목록</v-btn>
      </template>
    </SellerPageHeader>

    <v-card v-if="loading" class="mb-4"><v-card-text><v-skeleton-loader type="article, table" /></v-card-text></v-card>

    <v-card v-else-if="notFound" data-testid="settlement-not-found">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <p class="text-subtitle-1 font-weight-medium mb-1">정산을 찾을 수 없습니다</p>
        <p class="text-body-2 text-medium-emphasis mb-4">존재하지 않거나 아직 확정되지 않은 정산입니다: #{{ route.params.id }}</p>
        <v-btn color="primary" :to="backPath">목록으로</v-btn>
      </v-card-text>
    </v-card>

    <v-alert v-else-if="loadError" type="error" class="mb-4" data-testid="settlement-load-error">
      {{ loadError }} <v-btn size="small" variant="outlined" color="error" class="ml-2" @click="load">다시 시도</v-btn>
    </v-alert>

    <template v-else-if="detail">
      <!-- 헤더 -->
      <v-card class="mb-4" data-testid="settlement-summary">
        <v-card-title class="d-flex align-center justify-space-between flex-wrap ga-2 pt-4 px-5">
          <div class="d-flex align-center ga-2">
            <span class="text-subtitle-2 font-weight-bold">{{ periodLabel }} 정산</span>
            <v-chip :class="semanticChipClass(SELLER_SETTLEMENT_STATUS_SEMANTIC[detail.status])" size="small" variant="flat" data-testid="settlement-status">
              {{ settlementStatusLabel(detail.status) }}
            </v-chip>
          </div>
          <span v-if="detail.status === 'PAID'" class="text-caption text-medium-emphasis" data-testid="settlement-paid-notice">지급이 완료된 정산입니다.</span>
          <span v-else class="text-caption text-medium-emphasis" data-testid="settlement-confirmed-notice">확정된 정산입니다. 지급예정일에 주 정산계좌로 지급됩니다.</span>
        </v-card-title>
        <v-card-text class="px-5 pb-5">
          <v-row dense>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">정산 기간</div><div class="text-body-2" data-testid="settlement-period">{{ formatDateTime(detail.periodStart).slice(0, 10) }} ~ {{ formatDateTime(detail.periodEnd).slice(0, 10) }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">지급예정일</div><div class="text-body-2" data-testid="settlement-scheduled">{{ formatDateOnly(detail.scheduledPayDate) }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">지급일</div><div class="text-body-2" data-testid="settlement-paid-at">{{ detail.paidAt ? formatDateTime(detail.paidAt) : '—' }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">품목</div><div class="text-body-2">판매 {{ detail.saleItemCount }} · 환불 {{ detail.refundItemCount }} · 이월 차감 {{ detail.carryoverItemCount }}</div></v-col>
          </v-row>
          <v-divider class="my-4" />
          <v-row dense>
            <v-col cols="6" md><div class="text-caption text-medium-emphasis">매출</div><div class="text-body-1" data-testid="settlement-gross">{{ formatWon(detail.grossAmount) }}</div></v-col>
            <v-col cols="6" md><div class="text-caption text-medium-emphasis">수수료</div><div class="text-body-1" data-testid="settlement-fee">{{ formatWon(detail.feeAmount) }}</div></v-col>
            <v-col cols="6" md><div class="text-caption text-medium-emphasis">환불</div><div class="text-body-1" data-testid="settlement-refund">{{ formatWon(detail.refundAmount) }}</div></v-col>
            <v-col cols="6" md><div class="text-caption text-medium-emphasis">이월 차감</div><div class="text-body-1" data-testid="settlement-carryover">{{ formatWon(detail.carryoverAmount) }}</div></v-col>
            <v-col cols="6" md><div class="text-caption text-medium-emphasis">지급액</div><div class="text-body-1 font-weight-bold" :class="isNegativeNet(detail) ? 'text-error' : ''" data-testid="settlement-net">{{ formatWon(detail.netAmount) }}</div></v-col>
          </v-row>
          <p v-if="isNegativeNet(detail)" class="text-caption text-error mt-3 mb-0" data-testid="settlement-negative-notice">지급액이 음수인 정산은 다음 정산에서 차감 이월됩니다.</p>
        </v-card-text>
      </v-card>

      <!-- 정산계좌 -->
      <v-card class="mb-4" data-testid="settlement-bank">
        <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">정산계좌</v-card-title>
        <v-card-text class="px-5 pb-5">
          <template v-if="detail.bankAccount && bankAccountText">
            <div class="text-body-2 font-weight-medium" data-testid="settlement-bank-account">{{ bankAccountText }}</div>
            <v-chip size="x-small" variant="tonal" :color="detail.bankAccount.snapshot ? 'primary' : 'default'" class="mt-2" data-testid="settlement-bank-source">
              {{ bankAccountSourceLabel(detail.bankAccount) }}
            </v-chip>
          </template>
          <p v-else class="text-body-2 text-medium-emphasis mb-0" data-testid="settlement-bank-missing">등록된 주 정산계좌가 없습니다. 지급 전 운영자에게 계좌 등록을 요청하세요.</p>
        </v-card-text>
      </v-card>

      <!-- 품목 탭 -->
      <v-card data-testid="settlement-items">
        <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">정산 품목</v-card-title>
        <v-tabs :model-value="activeTab" color="primary" density="comfortable" class="px-2" data-testid="settlement-item-tabs" @update:model-value="(value) => applyItemQuery({ tab: value as SellerSettlementItemType })">
          <v-tab v-for="tab in SELLER_SETTLEMENT_ITEM_TABS" :key="tab.value" :value="tab.value" :data-testid="`settlement-tab-${tab.value}`">
            {{ tab.label }} <span class="text-medium-emphasis ml-1">{{ tabCount(tab.value) }}</span>
          </v-tab>
        </v-tabs>
        <SellerSettlementItemTable
          :rows="itemRows"
          :total-count="itemTotal"
          :page="itemPage"
          :size="itemSize"
          :loading="itemLoading"
          :load-error="itemError"
          :empty-message="itemEmptyMessage"
          @update:page="(page) => applyItemQuery({ page })"
          @update:size="(size) => applyItemQuery({ size })"
          @retry="loadItems"
        />
      </v-card>
    </template>
  </div>
</template>
