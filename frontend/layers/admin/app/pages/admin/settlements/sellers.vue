<script setup lang="ts">
import { mdiAlertCircleOutline, mdiStoreOutline } from '@mdi/js'
import type { AdminSettlementSummary } from '#layers/admin/app/types/admin-settlement'
import type { AdminSellerSummary } from '#layers/admin/app/types/admin-product'
import { ADMIN_SETTLEMENT_PAGE_SIZES, DEFAULT_ADMIN_SETTLEMENT_PAGE_SIZE } from '#layers/admin/app/lib/constants/admin-settlement'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminSettlements } from '#layers/admin/app/composables/useAdminSettlements'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '셀러별 정산 · zslab-mall 관리자' })

// 셀러별 정산(Track 85 FE·D-179). 셀러 선택(GET /admin/sellers·상호 검색 autocomplete) 후 GET /admin/sellers/{slr_}/settlements 월별 이력을
// 최신 기간순으로 보인다. 선택 셀러·페이지는 URL query(?seller=·?page=·?size=)가 단일 소스라 새로고침·상세 복귀에도 유지된다.
const route = useRoute()
const router = useRouter()
const settlementsApi = useAdminSettlements()

// ---------- 셀러 선택 ----------
const sellers = ref<AdminSellerSummary[]>([])
const sellersLoading = ref(false)
const sellersError = ref<string | null>(null)

async function loadSellers(): Promise<void> {
  sellersLoading.value = true
  sellersError.value = null
  try {
    sellers.value = await settlementsApi.sellers()
  } catch (error) {
    sellersError.value = toAdminErrorMessage(error)
  } finally {
    sellersLoading.value = false
  }
}
onMounted(loadSellers)

function first(value: unknown): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}
function parsePage(value: unknown): number {
  const page = Number(first(value))
  return Number.isInteger(page) && page > 0 ? page : 0
}
function parseSize(value: unknown): number {
  const size = Number(first(value))
  return ADMIN_SETTLEMENT_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_SETTLEMENT_PAGE_SIZE
}
const selectedSellerId = computed<string | null>(() => first(route.query.seller))
const page = computed<number>(() => parsePage(route.query.page))
const size = computed<number>(() => parseSize(route.query.size))
const selectedSeller = computed(() => sellers.value.find((seller) => seller.sellerPublicId === selectedSellerId.value) ?? null)

const sellerItems = computed(() => sellers.value.map((seller) => ({
  value: seller.sellerPublicId,
  title: seller.companyName,
  subtitle: seller.status === 'ACTIVE' ? undefined : seller.status,
})))

function applyQuery(patch: { seller?: string | null; page?: number; size?: number }): void {
  const seller = patch.seller !== undefined ? patch.seller : selectedSellerId.value
  const nextSize = patch.size ?? size.value
  const nextPage = patch.seller !== undefined || patch.size !== undefined ? 0 : (patch.page ?? page.value)
  const query: Record<string, string> = {}
  if (seller) query.seller = seller
  if (nextPage > 0) query.page = String(nextPage)
  if (nextSize !== DEFAULT_ADMIN_SETTLEMENT_PAGE_SIZE) query.size = String(nextSize)
  void router.replace({ query })
}

// ---------- 이력 ----------
const rows = ref<AdminSettlementSummary[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
const sellerNotFound = ref(false)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  sellerNotFound.value = false
  if (!selectedSellerId.value) {
    rows.value = []
    totalCount.value = 0
    loadError.value = null
    loading.value = false
    return
  }
  loading.value = true
  loadError.value = null
  try {
    const response = await settlementsApi.listBySeller(selectedSellerId.value, page.value, size.value)
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    rows.value = response.items
    totalCount.value = response.totalCount
  } catch (error) {
    if (sequence !== requestSequence) return
    if (extractErrorCode(error) === 'SELLER_NOT_FOUND') {
      sellerNotFound.value = true
      rows.value = []
      totalCount.value = 0
    } else {
      loadError.value = toAdminErrorMessage(error)
    }
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}
watch([selectedSellerId, page, size], () => { void load() }, { immediate: true })

function open(item: AdminSettlementSummary): void {
  void navigateTo({ path: `/admin/settlements/${item.id}`, query: { back: route.fullPath } })
}
</script>

<template>
  <div>
    <AdminPageHeader title="셀러별 정산" description="셀러를 선택하면 월별 정산 이력(최신순)을 보입니다. 상세에서 정상처리·지급완료를 처리합니다." />

    <v-card class="mb-4" data-testid="admin-settlement-seller-picker">
      <v-card-text class="pa-4">
        <v-alert v-if="sellersError" type="error" density="compact" :icon="mdiAlertCircleOutline" class="mb-3" data-testid="seller-list-error">
          <div class="d-flex align-center justify-space-between flex-wrap ga-2">
            <span>{{ sellersError }}</span>
            <v-btn size="small" variant="outlined" color="error" data-testid="seller-list-retry" @click="loadSellers">다시 시도</v-btn>
          </div>
        </v-alert>
        <v-autocomplete
          :model-value="selectedSellerId"
          :items="sellerItems"
          :loading="sellersLoading"
          label="셀러"
          placeholder="상호로 검색"
          :prepend-inner-icon="mdiStoreOutline"
          item-title="title"
          item-value="value"
          hide-details
          clearable
          no-data-text="일치하는 셀러가 없습니다"
          data-testid="seller-select"
          @update:model-value="(value: string | null) => applyQuery({ seller: value ?? null })"
        />
      </v-card-text>
    </v-card>

    <v-card>
      <div v-if="!selectedSellerId" class="d-flex flex-column align-center text-center py-10" data-testid="seller-unselected">
        <v-avatar color="surface-variant" size="48" class="mb-3">
          <v-icon :icon="mdiStoreOutline" size="22" class="text-medium-emphasis" />
        </v-avatar>
        <p class="text-subtitle-2 font-weight-medium mb-1">셀러를 선택하세요</p>
        <p class="text-body-2 text-medium-emphasis mb-0">선택한 셀러의 월별 정산 이력을 보입니다.</p>
      </div>
      <div v-else-if="sellerNotFound" class="d-flex flex-column align-center text-center py-10" data-testid="seller-not-found">
        <p class="text-subtitle-2 font-weight-medium mb-1">셀러를 찾을 수 없습니다</p>
        <p class="text-body-2 text-medium-emphasis mb-0">존재하지 않는 셀러입니다: {{ selectedSellerId }}</p>
      </div>
      <AdminSettlementTable
        v-else
        mode="seller"
        :rows="rows"
        :total-count="totalCount"
        :page="page"
        :size="size"
        :loading="loading"
        :load-error="loadError"
        :empty-title="`${selectedSeller?.companyName ?? '선택한 셀러'}의 정산이 없습니다`"
        empty-message="정산 내역에서 월별 정산을 생성하면 여기에 쌓입니다."
        @update:page="(next) => applyQuery({ page: next })"
        @update:size="(next) => applyQuery({ size: next })"
        @open="open"
        @retry="load"
      />
    </v-card>
  </div>
</template>
