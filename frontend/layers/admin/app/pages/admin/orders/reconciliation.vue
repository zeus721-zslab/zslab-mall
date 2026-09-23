<script setup lang="ts">
import { mdiAlertCircleOutline, mdiScaleBalance } from '@mdi/js'
import type { AdminReconciliationIssue, AdminReconciliationListQuery } from '#layers/admin/app/types/admin-reconciliation'
import {
  RECONCILIATION_ISSUE_STATUS_OPTIONS,
  RECONCILIATION_ISSUE_TYPE_OPTIONS,
  type ReconciliationIssueStatus,
  type ReconciliationIssueType,
} from '#layers/admin/app/lib/constants/reconciliation'
import {
  DEFAULT_RECONCILIATION_QUERY,
  parseReconciliationQuery,
  toReconciliationRouteQuery,
} from '#layers/admin/app/lib/admin-reconciliation-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminReconciliationIssues } from '#layers/admin/app/composables/useAdminReconciliationIssues'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '불일치 · zslab-mall 관리자' })

// 주문·결제 불일치 목록(Track 104-2 FE-66·BE D-216). URL query가 조회 조건의 단일 소스(deliveries.vue 패턴): 필터 변경 →
// router.replace → route.query watch → 조회. 해결은 다이얼로그가 호출하고 끝나면 다시 읽는다.
const route = useRoute()
const router = useRouter()
const reconciliationApi = useAdminReconciliationIssues()

const query = computed<AdminReconciliationListQuery>(() => parseReconciliationQuery(route.query))

const items = ref<AdminReconciliationIssue[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await reconciliationApi.list(query.value)
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
watch(() => route.query, () => { void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<AdminReconciliationListQuery>): void {
  const next: AdminReconciliationListQuery = { ...query.value, ...patch }
  if (!('page' in patch)) next.page = 0
  void router.replace({ query: toReconciliationRouteQuery(next) })
}

const pageCount = computed(() => Math.max(1, Math.ceil(totalCount.value / query.value.size)))

function openOrder(issue: AdminReconciliationIssue): void {
  if (!issue.orderId) return
  // 현재 목록 URL(필터·페이지)을 back으로 넘겨 상세에서 같은 목록으로 복귀한다(FE-26 패턴).
  void navigateTo({ path: `/admin/orders/${issue.orderId}`, query: { back: route.fullPath } })
}

// ---------- 해결 ----------
const resolveTarget = ref<AdminReconciliationIssue | null>(null)

function closeResolve(refresh: boolean): void {
  resolveTarget.value = null
  if (refresh) void load()
}
</script>

<template>
  <div>
    <AdminPageHeader
      title="불일치"
      description="PG 통지·환불 처리·정기 점검에서 결제·주문·환불 기록이 서로 맞지 않는 건을 모았습니다."
      guide="보정은 주문 상세의 결제 취소·클레임 처리 등 해당 화면에서 하고, 여기서는 확인한 내용을 메모로 남겨 해결 처리합니다."
    />

    <v-card class="mb-4">
      <v-card-text class="d-flex flex-wrap ga-3 pa-4">
        <v-select
          :model-value="query.status"
          :items="RECONCILIATION_ISSUE_STATUS_OPTIONS"
          label="상태"
          clearable
          placeholder="전체"
          persistent-placeholder
          density="compact"
          hide-details
          style="max-width: 200px"
          data-testid="reconciliation-filter-status"
          @update:model-value="(value: ReconciliationIssueStatus | null) => applyQuery({ status: value })"
        />
        <v-select
          :model-value="query.type"
          :items="RECONCILIATION_ISSUE_TYPE_OPTIONS"
          label="유형"
          clearable
          placeholder="전체"
          persistent-placeholder
          density="compact"
          hide-details
          style="max-width: 320px"
          data-testid="reconciliation-filter-type"
          @update:model-value="(value: ReconciliationIssueType | null) => applyQuery({ type: value })"
        />
        <v-spacer />
        <span class="text-body-2 text-medium-emphasis align-self-center" data-testid="reconciliation-total">{{ totalCount.toLocaleString('ko-KR') }}건</span>
      </v-card-text>
    </v-card>

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="reconciliation-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <v-card-text v-else class="px-5">
        <v-progress-linear v-if="loading" indeterminate class="mb-2" />
        <AdminReconciliationIssueList
          v-if="items.length > 0"
          :issues="items"
          show-order
          @resolve="(issue) => (resolveTarget = issue)"
          @open-order="openOrder"
        />
        <div v-else-if="!loading" class="d-flex flex-column align-center text-center py-10" data-testid="reconciliation-empty">
          <v-avatar color="surface-variant" size="48" class="mb-3">
            <v-icon :icon="mdiScaleBalance" size="22" class="text-medium-emphasis" />
          </v-avatar>
          <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 불일치가 없습니다</p>
          <p class="text-body-2 text-medium-emphasis mb-3">상태·유형 필터를 바꾸거나 초기화해 보세요.</p>
          <v-btn size="small" variant="outlined" @click="router.replace({ query: toReconciliationRouteQuery(DEFAULT_RECONCILIATION_QUERY) })">필터 초기화</v-btn>
        </div>
        <v-pagination
          v-if="pageCount > 1"
          :model-value="query.page + 1"
          :length="pageCount"
          density="compact"
          class="mt-2"
          @update:model-value="(page: number) => applyQuery({ page: page - 1 })"
        />
      </v-card-text>
    </v-card>

    <AdminReconciliationResolveDialog
      :open="resolveTarget !== null"
      :issue="resolveTarget"
      @done="closeResolve(true)"
      @stale="closeResolve(true)"
      @cancel="closeResolve(false)"
    />
  </div>
</template>
