<script setup lang="ts">
import { mdiInformationOutline } from '@mdi/js'
import type { SellerSettlementSummary } from '#layers/seller/app/types/seller-settlement'
import { DEFAULT_SELLER_SETTLEMENT_PAGE_SIZE, SELLER_SETTLEMENT_PAGE_SIZES } from '#layers/seller/app/lib/constants/seller-settlement'
import { pendingSettlementNotice } from '#layers/seller/app/lib/seller-settlement-view'
import { SELLER_SETTLEMENTS_PATH } from '#layers/seller/app/lib/seller-back-path'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerSettlements } from '#layers/seller/app/composables/useSellerSettlements'
import { useSellerMe } from '#layers/seller/app/composables/useSellerMe'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '정산 · zslab-mall 셀러' })

// 셀러 정산 목록(Track 90-B-3·Track 85 BE·관리자 settlements/sellers.vue 골격 복제·읽기 전용). 본인 CONFIRMED·PAID만 최신 기간순·필터 없음.
// 확정 전(PENDING) 정산은 BE가 404로 숨겨 목록에 없는 게 정상이며(D-191 ε), 그 건수는 GET /seller/me.pendingSettlementCount(대시보드 "정산 예정"과 동일)로
// 안내한다. 페이지·크기는 URL query(?page=·?size=) 단일 소스.
const route = useRoute()
const router = useRouter()
const settlementsApi = useSellerSettlements()
const { me: sellerMe } = useSellerMe()

function parsePage(value: unknown): number {
  const page = Number(Array.isArray(value) ? value[0] : value)
  return Number.isInteger(page) && page > 0 ? page : 0
}
function parseSize(value: unknown): number {
  const size = Number(Array.isArray(value) ? value[0] : value)
  return SELLER_SETTLEMENT_PAGE_SIZES.includes(size) ? size : DEFAULT_SELLER_SETTLEMENT_PAGE_SIZE
}
const page = computed<number>(() => parsePage(route.query.page))
const size = computed<number>(() => parseSize(route.query.size))

const items = ref<SellerSettlementSummary[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await settlementsApi.list(page.value, size.value)
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
watch([page, size], () => { void load() }, { immediate: true })

function applyPaging(patch: { page?: number; size?: number }): void {
  const nextSize = patch.size ?? size.value
  const nextPage = patch.size !== undefined ? 0 : (patch.page ?? page.value)
  const query: Record<string, string> = {}
  if (nextPage > 0) query.page = String(nextPage)
  if (nextSize !== DEFAULT_SELLER_SETTLEMENT_PAGE_SIZE) query.size = String(nextSize)
  void router.replace({ query })
}

const pendingNotice = computed(() => pendingSettlementNotice(sellerMe.value?.pendingSettlementCount ?? 0))

function open(item: SellerSettlementSummary): void {
  // 현재 목록 URL(페이지)을 back으로 넘겨 상세에서 같은 목록으로 복귀한다.
  void navigateTo({ path: `${SELLER_SETTLEMENTS_PATH}/${item.id}`, query: { back: route.fullPath } })
}
</script>

<template>
  <div data-testid="seller-settlements">
    <SellerPageHeader title="정산" description="확정·지급완료된 월별 정산 내역입니다. 확정 전 정산은 운영자 확정 후 표시됩니다." />

    <v-alert v-if="pendingNotice" type="info" variant="tonal" density="compact" class="mb-4" :icon="mdiInformationOutline" data-testid="seller-settlement-pending-notice">
      {{ pendingNotice }}
    </v-alert>

    <v-card>
      <SellerSettlementTable
        :rows="items"
        :total-count="totalCount"
        :page="page"
        :size="size"
        :loading="loading"
        :load-error="loadError"
        empty-title="확정된 정산이 없습니다"
        empty-message="구매확정 매출이 있는 달의 정산을 운영자가 확정하면 여기에 표시됩니다."
        @update:page="(next) => applyPaging({ page: next })"
        @update:size="(next) => applyPaging({ size: next })"
        @open="open"
        @retry="load"
      />
    </v-card>
  </div>
</template>
