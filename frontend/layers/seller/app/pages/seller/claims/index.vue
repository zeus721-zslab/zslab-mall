<script setup lang="ts">
import { mdiAlertCircleOutline, mdiClipboardTextOutline } from '@mdi/js'
import type { SellerClaimListQuery, SellerClaimSummary } from '#layers/seller/app/types/seller-claim'
import {
  DEFAULT_SELLER_CLAIM_QUERY,
  hasActiveClaimFilters,
  parseSellerClaimQuery,
  toSellerClaimRouteQuery,
} from '#layers/seller/app/lib/seller-claim-query'
import { SELLER_CLAIMS_PATH } from '#layers/seller/app/lib/seller-back-path'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerClaims } from '#layers/seller/app/composables/useSellerClaims'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '클레임 · zslab-mall 셀러' })

// 셀러 클레임 목록(Track 90-D-1·조회 전용·orders/index.vue 골격 복제). 행 = 자기 품목에 걸린 클레임. URL query가 필터·페이지의 단일 소스:
// 화면 조작 → router.replace → route.query watch → 조회. 처리(승인·거부·검수)는 관리자 전용이라 이 화면에는 어떤 처리 버튼도 없다.
const route = useRoute()
const router = useRouter()
const claimsApi = useSellerClaims()

const query = computed<SellerClaimListQuery>(() => parseSellerClaimQuery(route.query))

// ---------- 목록 ----------
const items = ref<SellerClaimSummary[]>([])
const totalCount = ref(0)
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
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toSellerErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

// router.replace가 반영되기 전에 연속 확정(기간 시작→종료 등)되면 앞 변경이 유실되므로 마지막으로 보낸 상태를 기준으로 합친다.
let pendingQuery: SellerClaimListQuery | null = null
watch(() => route.query, () => { pendingQuery = null; void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<SellerClaimListQuery>, resetPage = true): void {
  const next: SellerClaimListQuery = { ...(pendingQuery ?? query.value), ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  pendingQuery = next
  void router.replace({ query: toSellerClaimRouteQuery(next) })
}

function resetQuery(): void {
  pendingQuery = null
  void router.replace({ query: toSellerClaimRouteQuery({ ...DEFAULT_SELLER_CLAIM_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveClaimFilters(query.value))

function open(item: SellerClaimSummary): void {
  // 현재 목록 URL(필터·페이지)을 back으로 넘겨 상세에서 같은 목록으로 복귀한다.
  void navigateTo({ path: `${SELLER_CLAIMS_PATH}/${item.claimId}`, query: { back: route.fullPath } })
}
</script>

<template>
  <div data-testid="seller-claims">
    <SellerPageHeader title="클레임" description="내 품목에 접수된 취소·반품·교환 요청을 조회합니다. 승인·거부·검수 처리는 관리자가 진행하며 진행 상태만 확인할 수 있습니다." />

    <SellerClaimFilterCard :query="query" @apply="applyQuery" @reset="resetQuery" />

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="seller-claim-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="seller-claim-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <SellerClaimTable
        v-else
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
        @open="open"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="seller-claim-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiClipboardTextOutline" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <template v-if="filtersActive">
              <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 클레임이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis mb-3">검색어·유형·상태·기간 필터를 바꾸거나 초기화해 보세요.</p>
              <v-btn size="small" variant="outlined" @click="resetQuery">필터 초기화</v-btn>
            </template>
            <template v-else>
              <p class="text-subtitle-2 font-weight-medium mb-1">접수된 클레임이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis">내 품목에 취소·반품·교환 요청이 들어오면 여기에 표시됩니다.</p>
            </template>
          </div>
        </template>
      </SellerClaimTable>
    </v-card>
  </div>
</template>
