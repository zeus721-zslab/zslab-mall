<script setup lang="ts">
import { mdiArrowLeft } from '@mdi/js'
import type { AdminSettlementDetail, AdminSettlementItem, AdminSettlementRegenerateResponse } from '#layers/admin/app/types/admin-settlement'
import {
  ADMIN_SETTLEMENT_ITEM_TABS,
  ADMIN_SETTLEMENT_PAGE_SIZES,
  ADMIN_SETTLEMENT_STATUS_SEMANTIC,
  DEFAULT_ADMIN_SETTLEMENT_ITEM_TAB,
  DEFAULT_ADMIN_SETTLEMENT_PAGE_SIZE,
  type AdminSettlementItemType,
} from '#layers/admin/app/lib/constants/admin-settlement'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'
import { settlementConfirmMessage, settlementPayMessage } from '#layers/admin/app/lib/admin-risk-confirm'
import {
  bankAccountSourceLabel,
  canConfirm,
  canPay,
  canRegenerate,
  formatBankAccount,
  formatDateOnly,
  formatSettlementPeriod,
  isNegativeNet,
  payBlockedReason,
  settlementStatusLabel,
} from '#layers/admin/app/lib/admin-settlement-view'
import { ADMIN_SETTLEMENTS_PATH, resolveBackPath } from '#layers/admin/app/lib/admin-back-path'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { formatWon } from '#layers/admin/app/lib/format'
import { formatDateTime } from '~/lib/utils/datetime'
import { SETTLEMENT_ITEM_TYPE_LABELS } from '~/lib/constants/settlement'
import { useAdminSettlements } from '#layers/admin/app/composables/useAdminSettlements'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'
import { formatPhone } from '~/lib/format/phone'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '정산 상세 · zslab-mall 관리자' })

// 정산 상세(Track 85 FE·D-179). 헤더(셀러·기간·금액 4종·상태·지급예정일·지급일)·연락처(BE 마스킹본)·계좌(스냅샷/현재 구분)를 읽기 전용으로 보이고,
// 액션은 상태별로 활성(PENDING → 확정·재생성 / CONFIRMED → 지급완료 / PAID → 없음). 전이 후에는 상세를 다시 읽는다. 재생성 성공 시 새 정산
// 상세로 이동(삭제만이면 목록). 품목 탭(판매/환불/이월 차감)·페이지는 URL query(?tab=·?page=·?size=)에 반영한다. 미존재(404)는 안내 + 목록 이동.
const route = useRoute()
const router = useRouter()
const settlementsApi = useAdminSettlements()
const toast = useAdminToast()

const settlementId = computed<number>(() => Number(route.params.id))

/** 처리 이력 첫 페이지 로더(Track 101-A). 정산 id가 바뀌면 참조가 바뀌어 섹션이 다시 읽는다. */
const auditLoader = computed(() => {
  const id = settlementId.value
  return () => settlementsApi.auditLogs(id)
})
const backPath = computed(() => resolveBackPath(route.query.back, ADMIN_SETTLEMENTS_PATH))

const detail = ref<AdminSettlementDetail | null>(null)
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
      loadError.value = toAdminErrorMessage(error)
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)

const periodLabel = computed(() => (detail.value ? formatSettlementPeriod(detail.value.periodStart) : ''))
const confirmAllowed = computed(() => (detail.value ? canConfirm(detail.value) : false))
const regenerateAllowed = computed(() => (detail.value ? canRegenerate(detail.value) : false))
const payAllowed = computed(() => (detail.value ? canPay(detail.value) : false))
const payBlocked = computed(() => (detail.value ? payBlockedReason(detail.value) : null))
const bankAccountText = computed(() => formatBankAccount(detail.value?.bankAccount))

// ---------- 액션 다이얼로그 ----------
type SettlementDialog = 'confirm' | 'pay' | 'regenerate'
const activeDialog = ref<SettlementDialog | null>(null)
const actionBusy = ref(false)

async function runTransition(kind: 'confirm' | 'pay'): Promise<void> {
  if (!detail.value || actionBusy.value) return
  actionBusy.value = true
  try {
    if (kind === 'confirm') {
      await settlementsApi.confirm(detail.value.id)
      toast.success(`${periodLabel.value} 정산을 확정했습니다. 셀러에게 공개되고 SMS가 발송됩니다.`)
    } else {
      await settlementsApi.pay(detail.value.id)
      toast.success(`${periodLabel.value} 정산을 지급완료로 처리했습니다.`)
    }
    activeDialog.value = null
    await load()
  } catch (error) {
    activeDialog.value = null
    const code = extractErrorCode(error)
    if (code === 'SETTLEMENT_INVALID_STATE' || code === 'SETTLEMENT_NET_NEGATIVE' || code === 'SETTLEMENT_BANK_ACCOUNT_MISSING') {
      // 조회~처리 사이 상태·계좌가 바뀐 경우: 안내 후 최신 데이터를 다시 읽는다.
      toast.warning(toAdminErrorMessage(error))
      await load()
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    actionBusy.value = false
  }
}

async function onRegenerated(response: AdminSettlementRegenerateResponse): Promise<void> {
  activeDialog.value = null
  if (response.deletedOnly || response.settlementId === undefined) {
    toast.info(`${periodLabel.value} 정산을 삭제했습니다. 재집계 대상(구매확정 매출)이 없어 새 정산은 만들지 않았습니다.`)
    await navigateTo(backPath.value, { replace: true })
    return
  }
  toast.success(`${periodLabel.value} 정산을 재생성했습니다. 지급액 ${formatWon(response.netAmount)}`)
  // 새 정산 id로 이동(이전 id는 삭제됨·뒤로가기로 돌아오지 않게 replace)
  await navigateTo({ path: `/admin/settlements/${response.settlementId}`, query: { back: route.query.back } }, { replace: true })
  await load()
}

function onRegenerateStale(): void {
  activeDialog.value = null
  void load()
}

// ---------- 품목 탭(URL query 단일 소스: tab·page·size) ----------
function parseTab(value: unknown): AdminSettlementItemType {
  const single = Array.isArray(value) ? value[0] : value
  return ADMIN_SETTLEMENT_ITEM_TABS.some((tab) => tab.value === single) ? (single as AdminSettlementItemType) : DEFAULT_ADMIN_SETTLEMENT_ITEM_TAB
}
function parsePage(value: unknown): number {
  const page = Number(Array.isArray(value) ? value[0] : value)
  return Number.isInteger(page) && page > 0 ? page : 0
}
function parseSize(value: unknown): number {
  const size = Number(Array.isArray(value) ? value[0] : value)
  return ADMIN_SETTLEMENT_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_SETTLEMENT_PAGE_SIZE
}
const activeTab = computed<AdminSettlementItemType>(() => parseTab(route.query.tab))
const itemPage = computed<number>(() => parsePage(route.query.page))
const itemSize = computed<number>(() => parseSize(route.query.size))

function applyItemQuery(patch: { tab?: AdminSettlementItemType; page?: number; size?: number }): void {
  const tab = patch.tab ?? activeTab.value
  const size = patch.size ?? itemSize.value
  const page = patch.tab !== undefined || patch.size !== undefined ? 0 : (patch.page ?? itemPage.value)
  const query: Record<string, string> = {}
  const back = Array.isArray(route.query.back) ? route.query.back[0] : route.query.back
  if (typeof back === 'string') query.back = back
  if (tab !== DEFAULT_ADMIN_SETTLEMENT_ITEM_TAB) query.tab = tab
  if (page > 0) query.page = String(page)
  if (size !== DEFAULT_ADMIN_SETTLEMENT_PAGE_SIZE) query.size = String(size)
  void router.replace({ query })
}

const itemRows = ref<AdminSettlementItem[]>([])
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
    itemError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === itemSequence) itemLoading.value = false
  }
}
watch([activeTab, itemPage, itemSize, settlementId], () => { void loadItems() }, { immediate: true })

const itemEmptyMessage = computed(() => `${SETTLEMENT_ITEM_TYPE_LABELS[activeTab.value]} 품목이 없습니다`)

function tabCount(tab: AdminSettlementItemType): number {
  if (!detail.value) return 0
  const counts: Record<AdminSettlementItemType, number> = {
    SALE: detail.value.saleItemCount,
    REFUND: detail.value.refundItemCount,
    CARRYOVER: detail.value.carryoverItemCount,
  }
  return counts[tab]
}

function openOrder(row: AdminSettlementItem): void {
  void navigateTo({ path: `/admin/orders/${row.orderPublicId}`, query: { back: route.fullPath } })
}
</script>

<template>
  <div>
    <AdminPageHeader title="정산 상세" :description="detail ? `${detail.seller.companyName} · ${periodLabel}` : undefined" guide="금액·계좌를 확인하고 확정 → 지급완료를 처리합니다. 확정 전에는 재생성으로 다시 집계할 수 있습니다.">
      <template #actions>
        <v-btn variant="text" :prepend-icon="mdiArrowLeft" :to="backPath" data-testid="settlement-back">목록</v-btn>
      </template>
    </AdminPageHeader>

    <v-card v-if="loading" class="mb-4"><v-card-text><v-skeleton-loader type="article, table" /></v-card-text></v-card>

    <v-card v-else-if="notFound" data-testid="settlement-not-found">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <p class="text-subtitle-1 font-weight-medium mb-1">정산을 찾을 수 없습니다</p>
        <p class="text-body-2 text-medium-emphasis mb-4">존재하지 않거나 재생성으로 삭제된 정산입니다: #{{ route.params.id }}</p>
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
            <v-chip :class="semanticChipClass(ADMIN_SETTLEMENT_STATUS_SEMANTIC[detail.status])" size="small" variant="flat" data-testid="settlement-status">
              {{ settlementStatusLabel(detail.status) }}
            </v-chip>
          </div>
          <div class="d-flex align-center flex-wrap ga-2">
            <v-btn v-if="confirmAllowed" size="small" variant="flat" :disabled="actionBusy" :loading="actionBusy && activeDialog === 'confirm'" class="op-risk-action" data-testid="action-confirm" @click="activeDialog = 'confirm'">확정</v-btn>
            <v-btn v-if="regenerateAllowed" size="small" variant="outlined" color="warning" :disabled="actionBusy" data-testid="action-regenerate" @click="activeDialog = 'regenerate'">재생성</v-btn>
            <v-btn v-if="detail.status === 'CONFIRMED'" size="small" variant="flat" class="op-risk-action" :disabled="!payAllowed || actionBusy" :loading="actionBusy && activeDialog === 'pay'" data-testid="action-pay" @click="activeDialog = 'pay'">지급완료</v-btn>
            <span v-if="detail.status === 'PAID'" class="text-caption text-medium-emphasis" data-testid="settlement-paid-notice">지급이 완료된 정산입니다.</span>
          </div>
        </v-card-title>
        <v-card-text class="px-5 pb-5">
          <p v-if="payBlocked" class="text-caption text-error mb-3" data-testid="settlement-pay-blocked">{{ payBlocked }}</p>
          <v-row dense>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">셀러</div><div class="text-body-2 font-weight-medium" data-testid="settlement-seller">{{ detail.seller.companyName }}</div><div class="adm-product-id">{{ detail.seller.publicId }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">정산 기간</div><div class="text-body-2" data-testid="settlement-period">{{ formatDateTime(detail.periodStart).slice(0, 10) }} ~ {{ formatDateTime(detail.periodEnd).slice(0, 10) }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">지급예정일</div><div class="text-body-2" data-testid="settlement-scheduled">{{ formatDateOnly(detail.scheduledPayDate) }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">지급일</div><div class="text-body-2" data-testid="settlement-paid-at">{{ detail.paidAt ? formatDateTime(detail.paidAt) : '—' }}</div></v-col>
          </v-row>
          <v-divider class="my-4" />
          <v-row dense>
            <v-col cols="6" md><div class="text-caption text-medium-emphasis">매출</div><div class="text-body-1" data-testid="settlement-gross">{{ formatWon(detail.grossAmount) }}</div></v-col>
            <v-col cols="6" md><div class="text-caption text-medium-emphasis">수수료</div><div class="text-body-1" data-testid="settlement-fee">{{ formatWon(detail.feeAmount) }}</div></v-col>
            <v-col cols="6" md><div class="text-caption text-medium-emphasis">환불</div><div class="text-body-1" data-testid="settlement-refund">{{ formatWon(detail.refundAmount) }}</div></v-col>
            <v-col cols="6" md><div class="text-caption text-medium-emphasis">이월 차감</div><div class="text-body-1" data-testid="settlement-carryover">{{ formatWon(detail.carryoverAmount) }}</div></v-col>
            <v-col cols="6" md><div class="text-caption text-medium-emphasis">지급액</div><div class="text-body-1 font-weight-bold" :class="isNegativeNet(detail) ? 'text-error' : ''" data-testid="settlement-net">{{ formatWon(detail.netAmount) }}</div></v-col>
          </v-row>
        </v-card-text>
      </v-card>

      <v-row dense class="mb-1">
        <!-- 셀러 연락처 -->
        <v-col cols="12" md="6">
          <v-card class="mb-4 h-100" data-testid="settlement-contact">
            <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">셀러 연락처</v-card-title>
            <v-card-text class="px-5 pb-5">
              <div class="text-body-2">이메일: <span data-testid="settlement-contact-email">{{ detail.sellerContact?.contactEmail ?? '—' }}</span></div>
              <div class="text-body-2">연락처: <span data-testid="settlement-contact-phone">{{ formatPhone(detail.sellerContact?.contactPhone ?? '—') }}</span></div>
              <div class="text-caption text-medium-emphasis mt-1">개인정보 보호를 위해 일부 마스킹됩니다.</div>
            </v-card-text>
          </v-card>
        </v-col>
        <!-- 정산계좌 -->
        <v-col cols="12" md="6">
          <v-card class="mb-4 h-100" data-testid="settlement-bank">
            <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">정산계좌</v-card-title>
            <v-card-text class="px-5 pb-5">
              <template v-if="detail.bankAccount && bankAccountText">
                <div class="text-body-2 font-weight-medium" data-testid="settlement-bank-account">{{ bankAccountText }}</div>
                <v-chip size="x-small" variant="tonal" :color="detail.bankAccount.snapshot ? 'primary' : 'default'" class="mt-2" data-testid="settlement-bank-source">
                  {{ bankAccountSourceLabel(detail.bankAccount) }}
                </v-chip>
              </template>
              <p v-else class="text-body-2 text-medium-emphasis mb-0" data-testid="settlement-bank-missing">등록된 주 정산계좌가 없습니다. 지급완료 처리 전 셀러 계좌 등록이 필요합니다.</p>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- 품목 탭 -->
      <v-card data-testid="settlement-items">
        <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">정산 품목</v-card-title>
        <v-tabs :model-value="activeTab" color="primary" density="comfortable" class="px-2" data-testid="settlement-item-tabs" @update:model-value="(value) => applyItemQuery({ tab: value as AdminSettlementItemType })">
          <v-tab v-for="tab in ADMIN_SETTLEMENT_ITEM_TABS" :key="tab.value" :value="tab.value" :data-testid="`settlement-tab-${tab.value}`">
            {{ tab.label }} <span class="text-medium-emphasis ml-1">{{ tabCount(tab.value) }}</span>
          </v-tab>
        </v-tabs>
        <AdminSettlementItemTable
          :rows="itemRows"
          :total-count="itemTotal"
          :page="itemPage"
          :size="itemSize"
          :loading="itemLoading"
          :load-error="itemError"
          :empty-message="itemEmptyMessage"
          @update:page="(page) => applyItemQuery({ page })"
          @update:size="(size) => applyItemQuery({ size })"
          @open="openOrder"
          @retry="loadItems"
        />
      </v-card>

      <!-- Track 101-A: 생성·재생성·확정·지급완료가 누구 손에서 이뤄졌는지. 되돌릴 수 없는 전이라 기록이 남아야 한다. -->
      <AdminAuditLogSection class="mt-4" :loader="auditLoader" />
    </template>

    <AdminConfirmDialog
      :open="activeDialog === 'confirm'"
      title="정산 확정"
      :message="settlementConfirmMessage(periodLabel, detail?.seller.companyName ?? '')"
      confirm-label="확정"
      risk
      :loading="actionBusy"
      test-id="settlement-confirm-dialog"
      @confirm="runTransition('confirm')"
      @cancel="activeDialog = null"
    />
    <AdminConfirmDialog
      :open="activeDialog === 'pay'"
      title="정산 지급완료"
      :message="settlementPayMessage(periodLabel, detail?.seller.companyName ?? '', formatWon(detail?.netAmount), bankAccountText ?? '—')"
      confirm-label="지급완료"
      risk
      :loading="actionBusy"
      test-id="settlement-pay-dialog"
      @confirm="runTransition('pay')"
      @cancel="activeDialog = null"
    />
    <AdminSettlementRegenerateDialog
      :open="activeDialog === 'regenerate'"
      :settlement-id="detail?.id ?? null"
      :period-label="periodLabel"
      @done="onRegenerated"
      @stale="onRegenerateStale"
      @cancel="activeDialog = null"
    />
  </div>
</template>
