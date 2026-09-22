<script setup lang="ts">
import { mdiAlertCircleOutline, mdiSwapHorizontal } from '@mdi/js'
import type { AdminClaimListQuery, AdminClaimSummary } from '#layers/admin/app/types/admin-claim'
import type { AdminClaimRejectTarget } from '#layers/admin/app/components/admin/AdminClaimRejectDialog.vue'
import type { AdminRefundInitiateTarget } from '#layers/admin/app/components/admin/AdminRefundInitiateDialog.vue'
import type { AdminClaimInspectTarget } from '#layers/admin/app/components/admin/AdminClaimInspectDialog.vue'
import type { AdminExchangeShipmentTarget } from '#layers/admin/app/components/admin/AdminExchangeShipmentDialog.vue'
import { CLAIM_TYPE_LABELS, claimTypeLabel, type ClaimInspectionResult, type ClaimType } from '~/lib/constants/claim'
import {
  DEFAULT_ADMIN_CLAIM_QUERY,
  hasActiveClaimFilters,
  parseAdminClaimQuery,
  toAdminClaimRouteQuery,
} from '#layers/admin/app/lib/admin-claim-query'
import { approveConfirmMessage, confirmPickupMessage, shouldChainExchangeShipment } from '#layers/admin/app/lib/admin-claim-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminClaims } from '#layers/admin/app/composables/useAdminClaims'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '취소·반품·교환 · zslab-mall 관리자' })

// 관리자 클레임 목록(FE-28·Track 80 BE). URL query가 유형 탭·필터·정렬·페이지의 단일 소스: 화면 조작 → router.replace → route.query watch → 조회.
// 승인은 확인 다이얼로그, 거부는 사유 다이얼로그(주문 상세와 공용). 반품 회수 확인(확인 다이얼로그)·검수(검수 다이얼로그)는 목록 전용이며
// 액션은 BE availableActions를 그대로 따른다(FE-29).
const route = useRoute()
const router = useRouter()
const claimsApi = useAdminClaims()
const ordersApi = useAdminOrders()
const toast = useAdminToast()

const query = computed<AdminClaimListQuery>(() => parseAdminClaimQuery(route.query))

// ---------- 유형 탭(전체·취소·반품·교환 → ?type=) ----------
const ALL_TAB = 'ALL'
type ClaimTab = typeof ALL_TAB | ClaimType
const tabs: { value: ClaimTab; label: string }[] = [
  { value: ALL_TAB, label: '전체' },
  ...(Object.keys(CLAIM_TYPE_LABELS) as ClaimType[]).map((value) => ({ value, label: CLAIM_TYPE_LABELS[value] })),
]
const activeTab = computed<ClaimTab>(() => query.value.type ?? ALL_TAB)

function selectTab(tab: ClaimTab): void {
  if (tab === activeTab.value) return
  applyQuery({ type: tab === ALL_TAB ? null : tab })
}

// ---------- 목록 ----------
const items = ref<AdminClaimSummary[]>([])
const totalCount = ref(0)
const pendingCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await claimsApi.list(query.value)
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    items.value = response.items
    totalCount.value = response.totalCount
    pendingCount.value = response.pendingCount
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

// router.replace가 반영되기 전에 연속 확정되면 앞 변경이 유실되므로 마지막으로 보낸 상태를 기준으로 합친다(FE-27 동일).
let pendingQuery: AdminClaimListQuery | null = null
watch(() => route.query, () => { pendingQuery = null; void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<AdminClaimListQuery>, resetPage = true): void {
  const next: AdminClaimListQuery = { ...(pendingQuery ?? query.value), ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  pendingQuery = next
  void router.replace({ query: toAdminClaimRouteQuery(next) })
}

/** 필터 초기화는 유형 탭·페이지 크기를 유지한다(탭은 필터가 아님). */
function resetQuery(): void {
  pendingQuery = null
  void router.replace({ query: toAdminClaimRouteQuery({ ...DEFAULT_ADMIN_CLAIM_QUERY, type: query.value.type, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveClaimFilters(query.value))

/** 처리 대기 chip 문구: 현재 탭 유형 기준(BE pendingCount는 type만 반영). 0건은 중립 톤. */
const pendingLabel = computed(() => {
  const scope = activeTab.value === ALL_TAB ? '' : `${claimTypeLabel(activeTab.value)} `
  return `${scope}처리 대기 ${pendingCount.value}건`
})

function openOrder(item: AdminClaimSummary): void {
  if (!item.orderId) return
  // 현재 목록 URL(탭·필터·페이지)을 back으로 넘겨 주문 상세에서 같은 목록으로 복귀한다(FE-26 패턴·resolveBackPath 클레임 허용).
  void navigateTo({ path: `/admin/orders/${item.orderId}`, query: { back: route.fullPath } })
}

// ---------- 행 액션: 승인(확인) · 거부(사유 다이얼로그) ----------
const pendingIds = ref<Set<string>>(new Set())
const approveTarget = ref<AdminClaimSummary | null>(null)
const approveBusy = ref(false)
const rejectTarget = ref<AdminClaimRejectTarget | null>(null)

const approveMessage = computed(() =>
  approveTarget.value ? approveConfirmMessage(approveTarget.value.type, approveTarget.value.productName ?? '') : '',
)

async function runApprove(): Promise<void> {
  const target = approveTarget.value
  if (!target || approveBusy.value) return
  approveBusy.value = true
  pendingIds.value = new Set(pendingIds.value).add(target.claimId)
  try {
    await ordersApi.approveClaim(target.claimId)
    toast.info(`${claimTypeLabel(target.type)} 요청을 승인했습니다.`) // 상태 전환은 중립(취소는 재조회 시 환불 완료로 보임)
    approveTarget.value = null
    await load()
  } catch (error) {
    approveTarget.value = null
    if (extractErrorCode(error) === 'CLAIM_STATE_INVALID') {
      toast.warning(toAdminErrorMessage(error))
      await load()
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    approveBusy.value = false
    const next = new Set(pendingIds.value)
    next.delete(target.claimId)
    pendingIds.value = next
  }
}

function openReject(item: AdminClaimSummary): void {
  rejectTarget.value = { claimId: item.claimId, type: item.type, productName: item.productName ?? '' }
}

function closeReject(refresh: boolean): void {
  rejectTarget.value = null
  if (refresh) void load()
}

// ---------- 반품 행 액션(FE-29): 회수 확인(확인 다이얼로그) · 검수(검수 다이얼로그) ----------
const pickupTarget = ref<AdminClaimSummary | null>(null)
const pickupBusy = ref(false)
const inspectTarget = ref<AdminClaimInspectTarget | null>(null)

const pickupMessage = computed(() => (pickupTarget.value ? confirmPickupMessage(pickupTarget.value.productName ?? '', pickupTarget.value.type) : ''))

async function runConfirmPickup(): Promise<void> {
  const target = pickupTarget.value
  if (!target || pickupBusy.value) return
  pickupBusy.value = true
  pendingIds.value = new Set(pendingIds.value).add(target.claimId)
  try {
    await ordersApi.confirmPickupClaim(target.claimId)
    toast.info('회수를 확인했습니다. 검수를 진행하세요.')
    pickupTarget.value = null
    await load()
  } catch (error) {
    pickupTarget.value = null
    if (extractErrorCode(error) === 'CLAIM_STATE_INVALID') {
      toast.warning(toAdminErrorMessage(error))
      await load()
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    pickupBusy.value = false
    const next = new Set(pendingIds.value)
    next.delete(target.claimId)
    pendingIds.value = next
  }
}

// 검수 대상 행 원본(FE-61). 다이얼로그 target에는 교환 옵션 라벨이 없어 교환품 발송을 이어 열 때 행이 필요하다.
const inspectItem = ref<AdminClaimSummary | null>(null)

function openInspect(item: AdminClaimSummary): void {
  inspectItem.value = item
  inspectTarget.value = {
    claimId: item.claimId,
    productName: item.productName ?? '',
    claimType: item.type,
    // 회수 확인 전 진입(C-10): 다이얼로그가 회수 확인 → 검수 순차 호출
    pickupRequired: item.availableActions.includes('CONFIRM_PICKUP'),
  }
}

/**
 * 검수 다이얼로그 종료(FE-61). 갱신된 목록에서 같은 클레임 행을 다시 찾아, 교환 합격이고 그 행이 발송 등록을 허용할 때만
 * 교환품 발송 다이얼로그를 이어 연다. 행이 사라졌거나 액션이 없으면(경합·반품·불합격) 현행대로 목록에 머문다.
 */
async function closeInspect(refresh: boolean, result: ClaimInspectionResult | null = null): Promise<void> {
  const inspected = inspectItem.value
  inspectTarget.value = null
  inspectItem.value = null
  if (!refresh) return
  await load()
  if (inspected === null || result === null) return
  const row = items.value.find((candidate) => candidate.claimId === inspected.claimId)
  if (row && shouldChainExchangeShipment(row.type, result, row.availableActions)) {
    openExchangeShipment(row)
  }
}

// ---------- 수동 환불 개시(FE-36·Track 89-A): BE availableActions INITIATE_REFUND(승인 후 환불 없음·실패)에만 노출 ----------
const refundInitiateTarget = ref<AdminRefundInitiateTarget | null>(null)

function openInitiateRefund(item: AdminClaimSummary): void {
  refundInitiateTarget.value = { claimId: item.claimId, type: item.type, productName: item.productName ?? '', amount: item.amount ?? null }
}

function closeInitiateRefund(refresh: boolean): void {
  refundInitiateTarget.value = null
  if (refresh) void load()
}

// ---------- 교환 행 액션(FE-30·D-177): 교환품 발송(송장 다이얼로그) · 배송완료(확인 다이얼로그 → 기존 markDelivered) ----------
const exchangeShipmentTarget = ref<AdminExchangeShipmentTarget | null>(null)
const exchangeDeliveredTarget = ref<AdminClaimSummary | null>(null)
const exchangeDeliveredBusy = ref(false)

function openExchangeShipment(item: AdminClaimSummary): void {
  exchangeShipmentTarget.value = { claimId: item.claimId, productName: item.productName ?? '', exchangeOptionLabel: item.exchangeOptionLabel ?? null }
}

function closeExchangeShipment(refresh: boolean): void {
  exchangeShipmentTarget.value = null
  if (refresh) void load()
}

const exchangeDeliveredMessage = computed(() => exchangeDeliveredTarget.value
  ? `교환 요청 (${exchangeDeliveredTarget.value.productName ?? ''})의 교환품 배송을 완료 처리합니다.\n완료 시 품목이 교환 옵션으로 바뀌고 배송완료 상태로 돌아갑니다.`
  : '')

async function runMarkExchangeDelivered(): Promise<void> {
  const target = exchangeDeliveredTarget.value
  const deliveryId = target?.reshipment?.deliveryPublicId
  if (!target || !deliveryId || exchangeDeliveredBusy.value) return
  exchangeDeliveredBusy.value = true
  pendingIds.value = new Set(pendingIds.value).add(target.claimId)
  try {
    await ordersApi.markDelivered(deliveryId)
    toast.info('교환품 배송완료 처리했습니다.')
    exchangeDeliveredTarget.value = null
    await load()
  } catch (error) {
    exchangeDeliveredTarget.value = null
    if (extractErrorCode(error) === 'CLAIM_STATE_INVALID' || extractErrorCode(error) === 'DELIVERY_INVALID_STATE') {
      toast.warning(toAdminErrorMessage(error))
      await load()
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    exchangeDeliveredBusy.value = false
    const next = new Set(pendingIds.value)
    next.delete(target.claimId)
    pendingIds.value = next
  }
}
</script>

<template>
  <div>
    <AdminPageHeader title="취소·반품·교환" description="클레임 요청을 유형별로 조회하고 승인·거부합니다. 반품은 회수 확인 후 검수(합격 환불·불합격 재발송)까지 처리합니다.">
      <template #actions>
        <v-chip
          :color="pendingCount > 0 ? 'warning' : undefined"
          :variant="pendingCount > 0 ? 'flat' : 'tonal'"
          size="small"
          data-testid="claim-pending-chip"
        >
          {{ pendingLabel }}
        </v-chip>
      </template>
    </AdminPageHeader>

    <v-card class="mb-4">
      <v-tabs :model-value="activeTab" color="primary" density="comfortable" data-testid="claim-type-tabs" @update:model-value="(value) => selectTab(value as ClaimTab)">
        <v-tab v-for="tab in tabs" :key="tab.value" :value="tab.value" :data-testid="`claim-tab-${tab.value}`">{{ tab.label }}</v-tab>
      </v-tabs>
    </v-card>

    <AdminClaimFilterCard :query="query" @apply="applyQuery" @reset="resetQuery" />

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="admin-claim-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="admin-claim-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <AdminClaimTable
        v-else
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        :pending-ids="pendingIds"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
        @open-order="openOrder"
        @approve="(item) => (approveTarget = item)"
        @reject="openReject"
        @confirm-pickup="(item) => (pickupTarget = item)"
        @inspect="openInspect"
        @register-exchange-shipment="openExchangeShipment"
        @mark-exchange-delivered="(item) => (exchangeDeliveredTarget = item)"
        @initiate-refund="openInitiateRefund"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-claim-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiSwapHorizontal" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <template v-if="filtersActive">
              <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 클레임이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis mb-3">검색어·기간·상태 필터를 바꾸거나 초기화해 보세요.</p>
              <v-btn size="small" variant="outlined" @click="resetQuery">필터 초기화</v-btn>
            </template>
            <template v-else>
              <p class="text-subtitle-2 font-weight-medium mb-1">클레임이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis">{{ activeTab === ALL_TAB ? '아직 접수된 요청이 없습니다.' : `아직 접수된 ${claimTypeLabel(activeTab)} 요청이 없습니다.` }}</p>
            </template>
          </div>
        </template>
      </AdminClaimTable>
    </v-card>

    <AdminConfirmDialog
      :open="approveTarget !== null"
      test-id="admin-claim-approve-dialog"
      title="클레임 승인"
      :message="approveMessage"
      confirm-label="승인"
      :loading="approveBusy"
      @confirm="runApprove"
      @cancel="approveTarget = null"
    />
    <AdminClaimRejectDialog
      :open="rejectTarget !== null"
      :target="rejectTarget"
      @done="closeReject(true)"
      @stale="closeReject(true)"
      @cancel="closeReject(false)"
    />
    <AdminRefundInitiateDialog
      :open="refundInitiateTarget !== null"
      :target="refundInitiateTarget"
      @done="closeInitiateRefund(true)"
      @stale="closeInitiateRefund(true)"
      @cancel="closeInitiateRefund(false)"
    />
    <AdminConfirmDialog
      :open="pickupTarget !== null"
      test-id="admin-claim-pickup-dialog"
      title="회수 확인"
      :message="pickupMessage"
      confirm-label="회수 확인"
      :loading="pickupBusy"
      @confirm="runConfirmPickup"
      @cancel="pickupTarget = null"
    />
    <AdminClaimInspectDialog
      :open="inspectTarget !== null"
      :target="inspectTarget"
      @done="(result) => closeInspect(true, result)"
      @stale="closeInspect(true)"
      @cancel="closeInspect(false)"
    />
    <AdminExchangeShipmentDialog
      :open="exchangeShipmentTarget !== null"
      :target="exchangeShipmentTarget"
      @done="closeExchangeShipment(true)"
      @stale="closeExchangeShipment(true)"
      @cancel="closeExchangeShipment(false)"
    />
    <AdminConfirmDialog
      :open="exchangeDeliveredTarget !== null"
      :loading="exchangeDeliveredBusy"
      title="교환품 배송완료"
      :message="exchangeDeliveredMessage"
      confirm-label="배송완료"
      test-id="exchange-delivered-dialog"
      @confirm="runMarkExchangeDelivered"
      @cancel="exchangeDeliveredTarget = null"
    />
  </div>
</template>
