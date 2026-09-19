<script setup lang="ts">
import { mdiAlertCircleOutline, mdiClockAlertOutline, mdiMagnify, mdiPlus, mdiRefresh } from '@mdi/js'
import type { AdminSellerListItem, AdminSellerListQuery } from '#layers/admin/app/types/admin-seller'
import { ADMIN_SELLER_KEYWORD_MAX, ADMIN_SELLER_STATUS_OPTIONS } from '#layers/admin/app/lib/constants/admin-seller'
import {
  DEFAULT_ADMIN_SELLER_QUERY,
  hasActiveFilters,
  parseAdminSellerQuery,
  toAdminSellerRouteQuery,
} from '#layers/admin/app/lib/admin-seller-query'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { ADMIN_SELLERS_PATH } from '#layers/admin/app/lib/admin-back-path'
import { useAdminSellers } from '#layers/admin/app/composables/useAdminSellers'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '셀러 · zslab-mall 관리자' })

/**
 * 셀러 목록(FE-40·Track 89-D D-187·AdminMemberListView 패턴). URL query가 상태·검색어·페이지의 단일 소스이며 정렬은 BE 고정(등록일 desc).
 * 승인 대기(PENDING)는 최신순 정렬에 묻힐 수 있어 목록 위에 "승인 대기 N건" 안내와 "승인 대기만 보기" 프리셋을 둔다(전체·타 상태 필터일 때만 조회).
 */
const route = useRoute()
const router = useRouter()
const sellersApi = useAdminSellers()

const query = computed<AdminSellerListQuery>(() => parseAdminSellerQuery(route.query))

const items = ref<AdminSellerListItem[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
const pendingCount = ref<number | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const [response, pending] = await Promise.all([
      sellersApi.list(query.value),
      query.value.status === 'PENDING' ? Promise.resolve<number | null>(null) : sellersApi.countPending(),
    ])
    if (sequence !== requestSequence) return
    items.value = response.items
    totalCount.value = response.totalCount
    pendingCount.value = pending
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

let pendingQuery: AdminSellerListQuery | null = null
watch(() => route.query, () => { pendingQuery = null; void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<AdminSellerListQuery>, resetPage = true): void {
  const next: AdminSellerListQuery = { ...(pendingQuery ?? query.value), ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  pendingQuery = next
  void router.replace({ query: toAdminSellerRouteQuery(next) })
}

function resetQuery(): void {
  pendingQuery = null
  void router.replace({ query: toAdminSellerRouteQuery({ ...DEFAULT_ADMIN_SELLER_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveFilters(query.value))

const keywordInput = ref<string>(query.value.keyword)
watch(() => query.value.keyword, (next) => { keywordInput.value = next })
function submitKeyword(): void {
  applyQuery({ keyword: keywordInput.value.trim() })
}

function open(item: AdminSellerListItem): void {
  void navigateTo({ path: `${ADMIN_SELLERS_PATH}/${item.sellerPublicId}`, query: { back: route.fullPath } })
}

const provisionOpen = ref(false)
function onProvisioned(sellerPublicId: string): void {
  provisionOpen.value = false
  void navigateTo({ path: `${ADMIN_SELLERS_PATH}/${sellerPublicId}`, query: { back: route.fullPath } })
}
</script>

<template>
  <div>
    <AdminPageHeader title="셀러" description="입점 셀러를 검색·조회하고 상세에서 상태 전이(승인·정지·종료)·정보 수정을 처리합니다.">
      <template #actions>
        <v-btn color="primary" :prepend-icon="mdiPlus" data-testid="seller-provision-open" @click="provisionOpen = true">입점 등록</v-btn>
      </template>
    </AdminPageHeader>

    <v-alert
      v-if="pendingCount !== null && pendingCount > 0"
      type="warning"
      variant="tonal"
      density="compact"
      :icon="mdiClockAlertOutline"
      class="mb-4"
      data-testid="seller-pending-notice"
    >
      <div class="d-flex align-center justify-space-between flex-wrap ga-2">
        <span>승인 대기 셀러 <span class="font-weight-bold">{{ pendingCount }}건</span>이 있습니다. 상세에서 활성화(입점 승인)하거나 종료(승인 거부)하세요.</span>
        <v-btn size="small" variant="outlined" color="warning" data-testid="seller-pending-filter" @click="applyQuery({ status: 'PENDING', keyword: '' })">승인 대기만 보기</v-btn>
      </div>
    </v-alert>

    <v-card class="mb-4" data-testid="admin-seller-filters">
      <v-card-text class="pa-4">
        <v-row dense align="center">
          <v-col cols="12" md="4">
            <v-select
              :model-value="query.status"
              :items="ADMIN_SELLER_STATUS_OPTIONS"
              label="상태"
              hide-details
              data-testid="filter-status"
              @update:model-value="(value) => applyQuery({ status: value })"
            />
          </v-col>
          <v-col cols="12" md="8">
            <v-text-field
              v-model="keywordInput"
              label="상호명 · 사업자번호 · 담당자 이메일"
              placeholder="상호·사업자번호·이메일 일부"
              :prepend-inner-icon="mdiMagnify"
              :maxlength="ADMIN_SELLER_KEYWORD_MAX"
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

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="admin-seller-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="admin-seller-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <AdminSellerTable
        v-else
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        :filters-active="filtersActive"
        @open="open"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
        @reset="resetQuery"
      />
    </v-card>

    <AdminSellerProvisionDialog :open="provisionOpen" @done="onProvisioned" @cancel="provisionOpen = false" />
  </div>
</template>
