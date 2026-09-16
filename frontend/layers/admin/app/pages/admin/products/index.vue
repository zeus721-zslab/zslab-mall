<script setup lang="ts">
import { mdiAlertCircleOutline, mdiPackageVariantClosed, mdiPlus } from '@mdi/js'
import type {
  AdminProductBulkResponse,
  AdminProductListQuery,
  AdminProductSummary,
  AdminSellerSummary,
} from '#layers/admin/app/types/admin-product'
import type { CategorySummary } from '~/types/category'
import type { AdminProductBulkStatusTarget, AdminProductStatusTarget } from '#layers/admin/app/lib/constants/product'
import { ADMIN_PRODUCT_STATUS_LABEL } from '#layers/admin/app/lib/constants/product'
import {
  DEFAULT_ADMIN_PRODUCT_QUERY,
  hasActiveFilters,
  parseAdminProductQuery,
  toAdminProductRouteQuery,
} from '#layers/admin/app/lib/admin-product-query'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { soldOutToggleSemantic, summarizeBulkResult } from '#layers/admin/app/lib/admin-product-view'
import { useAdminProducts } from '#layers/admin/app/composables/useAdminProducts'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '상품 목록 · zslab-mall 관리자' })

// 관리자 상품 목록(FE-25·Track 76 BE). URL query가 필터·정렬·페이지의 단일 소스: 화면 조작 → router.replace → route.query watch → 조회.
// 행 단위 변경(품절 토글·상태 전환)은 낙관 갱신 없이 응답 후 해당 행만 갱신하고, 실패 시 원복 + 토스트. 일괄은 results 집계 후 재조회.
const route = useRoute()
const router = useRouter()
const productsApi = useAdminProducts()
const toast = useAdminToast()

const query = computed<AdminProductListQuery>(() => parseAdminProductQuery(route.query))

// ---------- 목록 ----------
const items = ref<AdminProductSummary[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
const selected = ref<string[]>([])
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await productsApi.list(query.value)
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    items.value = response.items
    totalCount.value = response.totalCount
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

watch(() => route.query, () => { selected.value = []; void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<AdminProductListQuery>, resetPage = true): void {
  const next: AdminProductListQuery = { ...query.value, ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  void router.replace({ query: toAdminProductRouteQuery(next) })
}

function resetQuery(): void {
  void router.replace({ query: toAdminProductRouteQuery({ ...DEFAULT_ADMIN_PRODUCT_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveFilters(query.value))

// ---------- 셀러·카테고리 옵션(실패해도 목록은 표시) ----------
const sellers = ref<AdminSellerSummary[]>([])
const categories = ref<CategorySummary[]>([])
const optionsError = ref(false)
onMounted(async () => {
  try {
    const [sellerList, categoryList] = await Promise.all([productsApi.sellers(), productsApi.categories()])
    sellers.value = sellerList
    categories.value = categoryList
  } catch (error) {
    optionsError.value = true
    console.warn('[admin/products] 셀러·카테고리 옵션 조회 실패', error)
  }
})

// ---------- 행 단위 변경 ----------
const pendingIds = ref<Set<string>>(new Set())
function setPending(productPublicId: string, pending: boolean): void {
  const next = new Set(pendingIds.value)
  if (pending) next.add(productPublicId)
  else next.delete(productPublicId)
  pendingIds.value = next
}

function patchRow(productPublicId: string, patch: Partial<AdminProductSummary>): void {
  items.value = items.value.map((item) => (item.productPublicId === productPublicId ? { ...item, ...patch } : item))
}

async function toggleSoldOut(item: AdminProductSummary, soldOut: boolean): Promise<void> {
  if (pendingIds.value.has(item.productPublicId)) return
  setPending(item.productPublicId, true)
  const before = { soldOutManual: item.soldOutManual, soldOut: item.soldOut }
  // 수동 품절을 켜면 판정도 품절. 끄면 재고 기준 판정이라 재조회 전까지 알 수 없어 재고>0이면 재고 있음으로 표시한다.
  patchRow(item.productPublicId, { soldOutManual: soldOut, soldOut: soldOut || item.stockTotal <= 0 })
  try {
    await productsApi.setSoldOut(item.productPublicId, soldOut)
    toast.show(soldOutToggleSemantic(soldOut), `${item.name} 수동 품절을 ${soldOut ? '켰' : '껐'}습니다.`)
  } catch (error) {
    patchRow(item.productPublicId, before)
    toast.danger(toAdminErrorMessage(error))
  } finally {
    setPending(item.productPublicId, false)
  }
}

async function changeStatus(item: AdminProductSummary, target: AdminProductStatusTarget): Promise<void> {
  if (pendingIds.value.has(item.productPublicId)) return
  setPending(item.productPublicId, true)
  try {
    const response = await productsApi.changeStatus(item.productPublicId, item.status, target)
    patchRow(item.productPublicId, { status: response.status })
    toast.info(`${item.name} → ${ADMIN_PRODUCT_STATUS_LABEL[response.status]}`) // 상태 전환은 중립
  } catch (error) {
    toast.danger(toAdminErrorMessage(error))
  } finally {
    setPending(item.productPublicId, false)
  }
}

function edit(item: AdminProductSummary): void {
  // FE-26: 현재 목록 URL(필터·페이지)을 back으로 넘겨 저장/취소 후 같은 목록으로 복귀한다.
  void navigateTo({ path: `/admin/products/${item.productPublicId}`, query: { back: route.fullPath } })
}

// ---------- 삭제(409 → 판매중지 안내) ----------
const deleteTarget = ref<AdminProductSummary | null>(null)
const deleting = ref(false)
const orderHistoryTarget = ref<AdminProductSummary | null>(null)

async function confirmDelete(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return
  deleting.value = true
  try {
    await productsApi.remove(target.productPublicId)
    deleteTarget.value = null
    toast.danger(`${target.name}을(를) 삭제했습니다.`) // 삭제는 결과가 부정적 의미
    await load()
  } catch (error) {
    deleteTarget.value = null
    if (extractErrorCode(error) === 'PRODUCT_HAS_ORDER_HISTORY') {
      orderHistoryTarget.value = target
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    deleting.value = false
  }
}

async function stopSaleInstead(): Promise<void> {
  const target = orderHistoryTarget.value
  if (!target) return
  orderHistoryTarget.value = null
  if (target.status === 'STOPPED') {
    toast.info('이미 판매중지 상태입니다.')
    return
  }
  await changeStatus(target, 'STOPPED')
}

// ---------- 일괄 ----------
type BulkPlan = { kind: 'status'; status: AdminProductBulkStatusTarget } | { kind: 'soldOut'; soldOut: boolean }
const bulkPlan = ref<BulkPlan | null>(null)
const bulkBusy = ref(false)
const bulkResult = ref<AdminProductBulkResponse | null>(null)
const bulkResultOpen = ref(false)

const bulkConfirmMessage = computed<string>(() => {
  const plan = bulkPlan.value
  const count = selected.value.length
  if (!plan) return ''
  if (plan.kind === 'status') {
    return `선택한 ${count}개 상품을 ${ADMIN_PRODUCT_STATUS_LABEL[plan.status]}(으)로 변경합니다.\n허용되지 않는 전이는 항목별로 실패 처리됩니다.`
  }
  return `선택한 ${count}개 상품의 수동 품절을 ${plan.soldOut ? '켭니다' : '끕니다'}.`
})

async function runBulk(): Promise<void> {
  const plan = bulkPlan.value
  if (!plan || selected.value.length === 0) return
  bulkBusy.value = true
  try {
    const response = plan.kind === 'status'
      ? await productsApi.bulkStatus(selected.value, plan.status)
      : await productsApi.bulkSoldOut(selected.value, plan.soldOut)
    bulkResult.value = response
    bulkPlan.value = null
    const summary = summarizeBulkResult(response, plan)
    // 실패가 있으면 토스트 action("상세 보기")으로 결과 다이얼로그를 연다(자동 열림 대신·타이밍은 사용자 선택).
    toast.show(summary.semantic, summary.message, summary.hasFailure
      ? { action: { label: '상세 보기', onClick: () => { bulkResultOpen.value = true } } }
      : undefined)
    selected.value = []
    await load()
  } catch (error) {
    bulkPlan.value = null
    toast.danger(toAdminErrorMessage(error))
  } finally {
    bulkBusy.value = false
  }
}
</script>

<template>
  <div>
    <AdminPageHeader title="상품 목록" description="상태·품절·판매기간을 관리하고 상품을 등록·수정합니다.">
      <template #actions>
        <v-btn color="primary" :prepend-icon="mdiPlus" :to="{ path: '/admin/products/new', query: { back: route.fullPath } }" data-testid="go-create">상품 등록</v-btn>
      </template>
    </AdminPageHeader>

    <AdminProductFilterCard
      :query="query"
      :sellers="sellers"
      :categories="categories"
      :options-error="optionsError"
      @apply="applyQuery"
      @reset="resetQuery"
    />

    <AdminProductBulkBar
      v-if="selected.length > 0"
      :selected-count="selected.length"
      :busy="bulkBusy"
      @apply-status="(status) => (bulkPlan = { kind: 'status', status })"
      @apply-sold-out="(soldOut) => (bulkPlan = { kind: 'soldOut', soldOut })"
      @clear="selected = []"
    />

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="admin-product-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="admin-product-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <AdminProductTable
        v-else
        v-model:selected="selected"
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        :pending-ids="pendingIds"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
        @toggle-sold-out="toggleSoldOut"
        @change-status="changeStatus"
        @edit="edit"
        @remove="(item) => (deleteTarget = item)"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-product-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiPackageVariantClosed" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <template v-if="filtersActive">
              <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 상품이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis mb-3">검색어나 필터를 바꾸거나 초기화해 보세요.</p>
              <v-btn size="small" variant="outlined" @click="resetQuery">필터 초기화</v-btn>
            </template>
            <template v-else>
              <p class="text-subtitle-2 font-weight-medium mb-1">등록된 상품이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis mb-3">첫 상품을 등록해 보세요.</p>
              <v-btn size="small" color="primary" to="/admin/products/new">상품 등록</v-btn>
            </template>
          </div>
        </template>
      </AdminProductTable>
    </v-card>

    <AdminConfirmDialog
      :open="deleteTarget !== null"
      test-id="admin-delete-dialog"
      title="상품 삭제"
      :message="`${deleteTarget?.name ?? ''}을(를) 삭제합니다.\n삭제된 상품은 목록·사용자 화면에서 사라지며 복구할 수 없습니다.`"
      confirm-label="삭제"
      confirm-color="error"
      :loading="deleting"
      @confirm="confirmDelete"
      @cancel="deleteTarget = null"
    />

    <AdminConfirmDialog
      :open="orderHistoryTarget !== null"
      test-id="admin-order-history-dialog"
      title="삭제할 수 없는 상품"
      confirm-color="warning"
      :message="`${orderHistoryTarget?.name ?? ''}에는 주문 이력이 있어 삭제할 수 없습니다.\n대신 판매중지로 전환하면 사용자에게 노출되지 않습니다.`"
      confirm-label="판매중지로 전환"
      @confirm="stopSaleInstead"
      @cancel="orderHistoryTarget = null"
    />

    <AdminConfirmDialog
      :open="bulkPlan !== null"
      test-id="admin-bulk-confirm-dialog"
      title="일괄 변경"
      :message="bulkConfirmMessage"
      confirm-label="적용"
      :loading="bulkBusy"
      @confirm="runBulk"
      @cancel="bulkPlan = null"
    />

    <AdminBulkResultDialog :open="bulkResultOpen" :result="bulkResult" @close="bulkResultOpen = false" />
  </div>
</template>
