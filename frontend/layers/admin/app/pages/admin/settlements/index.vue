<script setup lang="ts">
import { mdiMagnify, mdiPlus, mdiRefresh } from '@mdi/js'
import type { AdminSettlementListQuery, AdminSettlementMonthlyTotals, AdminSettlementSummary } from '#layers/admin/app/types/admin-settlement'
import {
  ADMIN_SETTLEMENT_KEYWORD_MAX,
  ADMIN_SETTLEMENT_STATUS_OPTIONS,
  type AdminSettlementStatus,
} from '#layers/admin/app/lib/constants/admin-settlement'
import {
  defaultAdminSettlementQuery,
  hasActiveFilters,
  parseAdminSettlementQuery,
  settlementYearOptions,
  toAdminSettlementRouteQuery,
} from '#layers/admin/app/lib/admin-settlement-query'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminSettlements } from '#layers/admin/app/composables/useAdminSettlements'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '정산 내역 · zslab-mall 관리자' })

// 정산 내역(Track 85 FE·D-179). URL query(year·month·status·keyword·page·size)가 단일 소스(AdminMemberListView 패턴): 화면 조작 → router.replace →
// route.query watch → 조회. year·month는 항상 URL에 있고 기본은 지난달. 합계 카드는 BE totals(필터 무관·월 전체). "정산 생성"은 선택 월 배치 생성이며
// 성공 시 목록을 다시 읽는다.
const route = useRoute()
const router = useRouter()
const settlementsApi = useAdminSettlements()
const toast = useAdminToast()

const query = computed<AdminSettlementListQuery>(() => parseAdminSettlementQuery(route.query))

// ---------- 목록 ----------
const items = ref<AdminSettlementSummary[]>([])
const totals = ref<AdminSettlementMonthlyTotals | null>(null)
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await settlementsApi.list(query.value)
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    items.value = response.items
    totals.value = response.totals
    totalCount.value = response.totalCount
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

// router.replace가 반영되기 전에 연속 확정되면 앞 변경이 유실되므로 마지막으로 보낸 상태를 기준으로 합친다.
let pendingQuery: AdminSettlementListQuery | null = null
watch(() => route.query, () => {
  pendingQuery = null
  // year·month 없이 진입(메뉴 클릭)하면 기본 월(지난달)을 URL에 박아 공유·새로고침에도 같은 월을 가리키게 한다. replace 후 watch가 다시 돈다.
  if (route.query.year === undefined || route.query.month === undefined) {
    void router.replace({ query: toAdminSettlementRouteQuery(query.value) })
    return
  }
  void load()
}, { immediate: true, deep: true })

function applyQuery(patch: Partial<AdminSettlementListQuery>, resetPage = true): void {
  const next: AdminSettlementListQuery = { ...(pendingQuery ?? query.value), ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  pendingQuery = next
  void router.replace({ query: toAdminSettlementRouteQuery(next) })
}

function resetQuery(): void {
  pendingQuery = null
  // 월은 유지하고 상태·검색어·페이지만 초기화한다(월은 필터가 아니라 조회 축).
  void router.replace({ query: toAdminSettlementRouteQuery({ ...defaultAdminSettlementQuery(), year: query.value.year, month: query.value.month, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveFilters(query.value))

// ---------- 월 선택 ----------
const yearOptions = settlementYearOptions()
const monthOptions = Array.from({ length: 12 }, (_, index) => ({ value: index + 1, title: `${index + 1}월` }))
const periodLabel = computed(() => `${query.value.year}년 ${query.value.month}월`)

// ---------- 필터(검색어는 로컬 입력값·검색 버튼/Enter로 확정) ----------
const keywordInput = ref<string>(query.value.keyword)
watch(() => query.value.keyword, (next) => { keywordInput.value = next })

function submitKeyword(): void {
  applyQuery({ keyword: keywordInput.value.trim() })
}

// ---------- 정산 생성 ----------
const createDialogOpen = ref(false)
const creating = ref(false)

async function runCreate(): Promise<void> {
  if (creating.value) return
  creating.value = true
  try {
    const response = await settlementsApi.create(query.value.year, query.value.month)
    createDialogOpen.value = false
    if (response.createdCount === 0) {
      toast.info(`${periodLabel.value} 정산 생성 대상이 없습니다(이미 생성됐거나 구매확정 매출 없음).`)
    } else {
      toast.success(`${periodLabel.value} 정산 ${response.createdCount}건을 생성했습니다.`)
    }
    await load()
  } catch (error) {
    createDialogOpen.value = false
    const code = extractErrorCode(error)
    if (code === 'SETTLEMENT_PERIOD_INVALID' || code === 'SETTLEMENT_ALREADY_EXISTS') {
      toast.warning(toAdminErrorMessage(error))
      if (code === 'SETTLEMENT_ALREADY_EXISTS') await load()
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    creating.value = false
  }
}

function open(item: AdminSettlementSummary): void {
  // 현재 목록 URL(월·필터·페이지)을 back으로 넘겨 상세에서 같은 목록으로 복귀한다(FE-26 패턴).
  void navigateTo({ path: `/admin/settlements/${item.id}`, query: { back: route.fullPath } })
}
</script>

<template>
  <div>
    <AdminPageHeader title="정산 내역" description="월별 셀러 정산을 생성·검수하고 정상처리·지급완료를 처리합니다. 합계는 선택한 월 전체 기준입니다.">
      <template #actions>
        <v-btn color="primary" :prepend-icon="mdiPlus" :disabled="loading" data-testid="settlement-create" @click="createDialogOpen = true">정산 생성</v-btn>
      </template>
    </AdminPageHeader>

    <v-card class="mb-4" data-testid="admin-settlement-filters">
      <v-card-text class="pa-4">
        <v-row dense align="center">
          <v-col cols="6" md="2">
            <v-select
              :model-value="query.year"
              :items="yearOptions"
              label="연도"
              hide-details
              data-testid="filter-year"
              @update:model-value="(value: number) => applyQuery({ year: value })"
            />
          </v-col>
          <v-col cols="6" md="2">
            <v-select
              :model-value="query.month"
              :items="monthOptions"
              label="월"
              hide-details
              data-testid="filter-month"
              @update:model-value="(value: number) => applyQuery({ month: value })"
            />
          </v-col>
          <v-col cols="12" md="3">
            <v-select
              :model-value="query.status"
              :items="ADMIN_SETTLEMENT_STATUS_OPTIONS"
              label="상태"
              placeholder="전체"
              persistent-placeholder
              hide-details
              clearable
              data-testid="filter-status"
              @update:model-value="(value: AdminSettlementStatus | null) => applyQuery({ status: value ?? null })"
            />
          </v-col>
          <v-col cols="12" md="5">
            <v-text-field
              v-model="keywordInput"
              label="셀러 상호"
              placeholder="상호 일부"
              :prepend-inner-icon="mdiMagnify"
              :maxlength="ADMIN_SETTLEMENT_KEYWORD_MAX"
              hide-details
              clearable
              data-testid="filter-keyword"
              @keyup.enter="submitKeyword"
              @click:clear="applyQuery({ keyword: '' })"
            />
          </v-col>
        </v-row>
        <div class="d-flex align-center ga-2 mt-3">
          <v-btn color="primary" :prepend-icon="mdiMagnify" data-testid="filter-search" @click="submitKeyword">검색</v-btn>
          <v-btn variant="text" :prepend-icon="mdiRefresh" data-testid="filter-reset" @click="resetQuery">초기화</v-btn>
        </div>
      </v-card-text>
    </v-card>

    <AdminSettlementTotals :totals="totals" />

    <v-card>
      <AdminSettlementTable
        mode="monthly"
        :rows="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        :load-error="loadError"
        :empty-title="filtersActive ? '조건에 맞는 정산이 없습니다' : `${periodLabel} 정산이 없습니다`"
        :empty-message="filtersActive ? '상태·검색어를 바꾸거나 초기화해 보세요.' : '정산은 매월 1일 이후 전월분이 자동 생성됩니다. 과거 월은 상단의 정산 생성으로 만들 수 있습니다.'"
        :show-reset="filtersActive"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
        @open="open"
        @retry="load"
        @reset="resetQuery"
      />
    </v-card>

    <AdminConfirmDialog
      :open="createDialogOpen"
      title="정산 생성"
      :message="`${periodLabel} 정산을 생성합니다.\n구매확정 매출이 있는 셀러별로 정산이 만들어지며, 이미 생성된 셀러는 건너뜁니다.`"
      confirm-label="생성"
      :loading="creating"
      test-id="settlement-create-dialog"
      @confirm="runCreate"
      @cancel="createDialogOpen = false"
    />
  </div>
</template>
