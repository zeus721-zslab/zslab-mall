<script setup lang="ts">
import { mdiAlertCircleOutline, mdiInformationOutline, mdiOpenInNew, mdiPackageVariantRemove, mdiRefresh } from '@mdi/js'
import { SELLER_STATS_MAX_PERIOD_DAYS } from '#layers/seller/app/lib/constants/seller-stats'
import {
  DEFAULT_SELLER_STATS_RANGE_QUERY,
  parseSellerStatsRangeQuery,
  resolveSellerStatsPeriod,
  sellerStatsPeriodError,
  toSellerProductStatsApiParams,
  toSellerStatsRangeRouteQuery,
  type SellerStatsRangeQuery,
} from '#layers/seller/app/lib/seller-stats-query'
import {
  isProductStatsEmpty,
  productRankRows,
  soldOutCaption,
  stockTurnoverRows,
  unsoldRows,
} from '#layers/seller/app/lib/seller-product-stats-view'
import { SELLER_INVENTORY_PATH, SELLER_PRODUCTS_PATH } from '#layers/seller/app/lib/seller-back-path'
import { formatWon } from '#layers/seller/app/lib/format'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import type { SellerProductStatsResponse } from '#layers/seller/app/types/seller-product-stats'
import { useSellerProductStats } from '#layers/seller/app/composables/useSellerProductStats'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '상품 통계 · zslab-mall 셀러' })

// 셀러 상품 통계(Track 90-E-3·D-200·관리자 대응 화면 없음). URL query(preset·from·to)가 단일 소스(매출·주문클레임 통계와 같은 흐름·비교/단위 없음):
// 조작 → router.replace → watch → 조회. 기간 오류(역전·365일 초과·미입력)면 요청하지 않는다. 상위/하위/미판매/재고 회전은 기간, 품절 옵션 수는 현재 시점.
const route = useRoute()
const router = useRouter()
const statsApi = useSellerProductStats()

const query = computed<SellerStatsRangeQuery>(() => parseSellerStatsRangeQuery(route.query))
const period = computed(() => resolveSellerStatsPeriod({ ...query.value, unit: 'DAY', compare: 'NONE', axis: 'PRODUCT' }, new Date()))
const periodError = computed(() => sellerStatsPeriodError(period.value))
const canQuery = computed(() => period.value !== null && periodError.value === null)

let pendingQuery: SellerStatsRangeQuery | null = null
function applyQuery(patch: Partial<SellerStatsRangeQuery>): void {
  const base = pendingQuery ?? query.value
  const next: SellerStatsRangeQuery = { ...base, ...patch }
  if (next.preset === 'custom' && base.preset !== 'custom') {
    if (!('from' in patch)) next.from = period.value?.from ?? null
    if (!('to' in patch)) next.to = period.value?.to ?? null
  }
  pendingQuery = next
  void router.replace({ query: toSellerStatsRangeRouteQuery(next) })
}

const stats = ref<SellerProductStatsResponse | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
let sequence = 0

async function load(): Promise<void> {
  const current = ++sequence
  if (!canQuery.value || period.value === null) {
    stats.value = null
    return
  }
  loading.value = true
  loadError.value = null
  try {
    const response = await statsApi.products(toSellerProductStatsApiParams(period.value))
    if (current !== sequence) return
    stats.value = response
  } catch (error) {
    if (current !== sequence) return
    loadError.value = toSellerErrorMessage(error)
  } finally {
    if (current === sequence) loading.value = false
  }
}

const requestKey = computed(() => JSON.stringify(period.value))
watch(requestKey, () => { pendingQuery = null; void load() }, { immediate: true })

function resetQuery(): void {
  void router.replace({ query: toSellerStatsRangeRouteQuery(DEFAULT_SELLER_STATS_RANGE_QUERY) })
}

// ---------- 표시 ----------
const topRows = computed(() => productRankRows(stats.value?.topProducts ?? []))
const bottomRows = computed(() => productRankRows(stats.value?.bottomProducts ?? []))
const unsold = computed(() => unsoldRows(stats.value?.unsoldProducts ?? []))
const turnover = computed(() => stockTurnoverRows(stats.value?.stockTurnover ?? []))
const soldOutText = computed(() => soldOutCaption(stats.value))
const allEmpty = computed(() => isProductStatsEmpty(stats.value))
const periodDays = computed(() => stats.value?.periodDays ?? null)

/** 상품 행 → 셀러 상품 상세(back=현재 통계 URL·resolveBackPath 허용 목록 SELLER_STATS_PRODUCTS_PATH). */
function openProduct(key: string | null): void {
  if (!key) return
  void navigateTo({ path: `${SELLER_PRODUCTS_PATH}/${key}`, query: { back: route.fullPath } })
}
</script>

<template>
  <div data-testid="seller-product-stats">
    <SellerPageHeader title="상품 통계" description="기간 내 판매 상위·하위와 미판매 상품, 입고 대비 판매로 본 재고 회전, 현재 품절 옵션을 봅니다.">
      <template #actions>
        <v-btn variant="text" size="small" data-testid="products-reset" @click="resetQuery">초기화</v-btn>
        <v-btn variant="text" :prepend-icon="mdiRefresh" :loading="loading" data-testid="products-refresh" @click="load">새로고침</v-btn>
      </template>
    </SellerPageHeader>

    <SellerStatsTabs />

    <SellerPeriodPicker
      :preset="query.preset"
      :from="period?.from ?? query.from"
      :to="period?.to ?? query.to"
      :period-error="periodError"
      :max-days="SELLER_STATS_MAX_PERIOD_DAYS"
      @apply="applyQuery"
    />

    <v-alert v-if="loadError" type="error" class="mb-4" :icon="mdiAlertCircleOutline" data-testid="products-error">
      <div class="d-flex align-center justify-space-between flex-wrap ga-2">
        <span>{{ loadError }}</span>
        <v-btn size="small" variant="outlined" color="error" data-testid="products-retry" @click="load">다시 시도</v-btn>
      </div>
    </v-alert>

    <v-progress-linear v-if="loading && !stats" indeterminate color="primary" class="mb-4" data-testid="products-loading" />

    <!-- 현재 품절 옵션 수: 기간과 무관한 현재 시점 값 · 카드 → 재고 화면 -->
    <v-row dense class="mb-4">
      <v-col cols="12" md="6" lg="4">
        <NuxtLink :to="SELLER_INVENTORY_PATH" class="text-decoration-none d-block h-100" data-testid="soldout-card-link">
          <SellerStatCard
            label="현재 품절 옵션"
            :value="stats ? `${stats.soldOutOptionCount.toLocaleString('ko-KR')}개` : '—'"
            :caption="soldOutText"
            :icon="mdiPackageVariantRemove"
            :color="stats && stats.soldOutOptionCount > 0 ? 'warning' : 'info'"
            class="h-100"
          >
            <span class="text-caption text-primary font-weight-medium d-inline-flex align-center" data-testid="soldout-card-value-hint">재고 화면에서 입고하기 <v-icon :icon="mdiOpenInNew" size="12" class="ml-1" /></span>
          </SellerStatCard>
        </NuxtLink>
      </v-col>
    </v-row>

    <p v-if="allEmpty" class="text-body-2 text-medium-emphasis mb-4 d-flex align-center ga-1" data-testid="products-all-empty">
      <v-icon :icon="mdiInformationOutline" size="16" />기간 내 판매·미판매·판매 중 상품이 없습니다.
    </p>

    <v-row dense class="mb-4">
      <v-col cols="12" lg="6">
        <SellerProductRankTable title="판매 상위" description="기간 내 결제 완료 내 품목 매출 기준 상위 10개(주문 시점 상품명)." :rows="topRows" testid="product-top" @open="(row) => openProduct(row.key)" />
      </v-col>
      <v-col cols="12" lg="6">
        <SellerProductRankTable title="판매 하위" description="기간 내 판매가 1건 이상인 상품 중 매출 하위 10개(판매 0은 아래 미판매 표)." :rows="bottomRows" testid="product-bottom" @open="(row) => openProduct(row.key)" />
      </v-col>
    </v-row>

    <v-card class="mb-4" data-testid="product-unsold">
      <v-card-text class="pa-5">
        <p class="text-subtitle-1 font-weight-bold mb-1">미판매 상품</p>
        <p class="text-caption text-medium-emphasis mb-3">판매 중(SALE) 상품 가운데 기간 내 결제 완료 품목이 없는 상품입니다. 판매 중지·승인 대기 상품은 제외됩니다.</p>
        <v-table density="compact" hover class="slr-table slr-table--compact" data-testid="product-unsold-table">
          <thead>
            <tr>
              <th class="text-start">상품</th>
              <th class="text-end slr-nowrap">판매가</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="unsold.length === 0">
              <td colspan="2" class="text-center text-medium-emphasis py-4" data-testid="product-unsold-empty">미판매 상품 없음</td>
            </tr>
            <tr v-for="row in unsold" :key="row.key" class="slr-row--linkable" data-testid="product-unsold-row" @click="openProduct(row.key)">
              <td>
                <div class="d-flex align-center ga-1" style="min-width: 0">
                  <span class="text-body-2 text-truncate" data-testid="product-unsold-name">{{ row.name }}</span>
                  <v-icon :icon="mdiOpenInNew" size="14" class="text-medium-emphasis flex-shrink-0" />
                </div>
              </td>
              <td class="text-end text-body-2 slr-nowrap">{{ formatWon(row.basePrice) }}</td>
            </tr>
          </tbody>
        </v-table>
      </v-card-text>
    </v-card>

    <v-card data-testid="product-turnover">
      <v-card-text class="pa-5">
        <p class="text-subtitle-1 font-weight-bold mb-1">재고 회전</p>
        <p class="text-caption text-medium-emphasis mb-3">
          판매 중 상품별 기간 입고(재고 입고 이력) · 기간 판매(결제 완료 수량) · 현재 가용 재고(현재 시점) · 소진 예상 = 현재 가용 ÷ 기간 일평균 판매
          <span v-if="periodDays !== null" data-testid="product-turnover-period">({{ periodDays }}일 기준)</span>. 판매가 없으면 계산하지 않습니다.
        </p>
        <v-table density="compact" hover class="slr-table slr-table--compact" data-testid="product-turnover-table">
          <thead>
            <tr>
              <th class="text-start">상품</th>
              <th class="text-end slr-nowrap">입고</th>
              <th class="text-end slr-nowrap">판매</th>
              <th class="text-end slr-nowrap">현재 재고</th>
              <th class="text-end slr-nowrap">소진 예상</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="turnover.length === 0">
              <td colspan="5" class="text-center text-medium-emphasis py-4" data-testid="product-turnover-empty">판매 중 상품 없음</td>
            </tr>
            <tr v-for="row in turnover" :key="row.key" class="slr-row--linkable" data-testid="product-turnover-row" @click="openProduct(row.key)">
              <td>
                <div class="d-flex align-center ga-1" style="min-width: 0">
                  <span class="text-body-2 text-truncate" data-testid="product-turnover-name">{{ row.name }}</span>
                  <v-icon :icon="mdiOpenInNew" size="14" class="text-medium-emphasis flex-shrink-0" />
                </div>
              </td>
              <td class="text-end text-body-2 slr-nowrap">{{ row.inboundQuantity.toLocaleString('ko-KR') }}개</td>
              <td class="text-end text-body-2 slr-nowrap">{{ row.soldQuantity.toLocaleString('ko-KR') }}개</td>
              <td class="text-end text-body-2 slr-nowrap">{{ row.availableQuantity.toLocaleString('ko-KR') }}개</td>
              <td class="text-end slr-nowrap">
                <v-chip size="x-small" variant="flat" :class="row.urgent ? 'slr-chip slr-chip--danger' : 'slr-chip slr-chip--neutral'" data-testid="product-turnover-depletion">{{ row.depletionText }}</v-chip>
              </td>
            </tr>
          </tbody>
        </v-table>
      </v-card-text>
    </v-card>
  </div>
</template>

<style scoped>
.slr-row--linkable {
  cursor: pointer;
}
.slr-nowrap {
  white-space: nowrap;
}
</style>
