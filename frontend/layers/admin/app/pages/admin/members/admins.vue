<script setup lang="ts">
import { mdiAccountPlusOutline, mdiAlertCircleOutline, mdiMagnify, mdiRefresh, mdiShieldAccountOutline } from '@mdi/js'
import type { AdminMe, AdminOperatorListQuery, AdminOperatorSummary } from '#layers/admin/app/types/admin-operator'
import {
  ADMIN_OPERATOR_KEYWORD_MAX,
  ADMIN_OPERATOR_ROLE_OPTIONS,
  ADMIN_OPERATOR_STATUS_OPTIONS,
} from '#layers/admin/app/lib/constants/admin-operator'
import {
  DEFAULT_ADMIN_OPERATOR_QUERY,
  hasActiveFilters,
  parseAdminOperatorQuery,
  toAdminOperatorRouteQuery,
} from '#layers/admin/app/lib/admin-operator-query'
import { provisionBlockedReason, superAdminCountInList } from '#layers/admin/app/lib/admin-operator-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminOperators } from '#layers/admin/app/composables/useAdminOperators'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '관리자 · zslab-mall 관리자' })

// 운영자 관리(FE-39·Track 89-E BE). 목록(역할·상태·검색·페이지는 URL 단일 소스)은 ADMIN 전체가 보고, 등록·회수 버튼은 GET /admin/me의
// superAdmin으로만 활성화한다(실인가는 BE 403/409). 자기 행은 회수 불가, 마지막 SUPER_ADMIN은 목록이 완전할 때만 미리 막는다.
const route = useRoute()
const router = useRouter()
const operatorsApi = useAdminOperators()
const toast = useAdminToast()

const query = computed<AdminOperatorListQuery>(() => parseAdminOperatorQuery(route.query))

// ---------- 현재 관리자(버튼 활성 판정) ----------
const me = ref<AdminMe | null>(null)
async function loadMe(): Promise<void> {
  try {
    me.value = await operatorsApi.me()
  } catch (error) {
    // 실패 시 me=null → 등록·회수 버튼이 전부 비활성(툴팁 안내). 목록 열람은 계속된다.
    toast.warning(`현재 관리자 정보를 불러오지 못했습니다: ${toAdminErrorMessage(error)}`)
  }
}
onMounted(() => { void loadMe() })

// ---------- 목록 ----------
const items = ref<AdminOperatorSummary[]>([])
const totalCount = ref(0)
const hasNext = ref(false)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await operatorsApi.list(query.value)
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    items.value = response.items
    totalCount.value = response.totalCount
    hasNext.value = response.hasNext
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

// router.replace가 반영되기 전에 연속 확정되면 앞 변경이 유실되므로 마지막으로 보낸 상태를 기준으로 합친다.
let pendingQuery: AdminOperatorListQuery | null = null
watch(() => route.query, () => { pendingQuery = null; void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<AdminOperatorListQuery>, resetPage = true): void {
  const next: AdminOperatorListQuery = { ...(pendingQuery ?? query.value), ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  pendingQuery = next
  void router.replace({ query: toAdminOperatorRouteQuery(next) })
}

function resetQuery(): void {
  pendingQuery = null
  void router.replace({ query: toAdminOperatorRouteQuery({ ...DEFAULT_ADMIN_OPERATOR_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveFilters(query.value))
const superAdminCount = computed(() => superAdminCountInList(items.value, query.value, hasNext.value))

// ---------- 필터(검색어는 로컬 입력값·검색 버튼/Enter로 확정) ----------
const keywordInput = ref<string>(query.value.keyword)
watch(() => query.value.keyword, (next) => { keywordInput.value = next })

function submitKeyword(): void {
  applyQuery({ keyword: keywordInput.value.trim() })
}

// ---------- 등록·회수 ----------
const provisionOpen = ref(false)
const provisionBlocked = computed(() => provisionBlockedReason(me.value))
const revokeTarget = ref<AdminOperatorSummary | null>(null)

function closeProvision(refresh: boolean): void {
  provisionOpen.value = false
  if (refresh) void load()
}

function closeRevoke(refresh: boolean): void {
  revokeTarget.value = null
  if (refresh) void load()
}
</script>

<template>
  <div>
    <AdminPageHeader title="관리자" description="관리자 화면에 로그인할 수 있는 운영자 계정과 역할을 관리합니다. 운영자 등록과 역할 회수는 슈퍼 관리자만 할 수 있습니다.">
      <template #actions>
        <v-tooltip :disabled="provisionBlocked === null" location="bottom">
          <template #activator="{ props: tooltipProps }">
            <span v-bind="tooltipProps" data-testid="go-provision-wrapper">
              <v-btn color="primary" :prepend-icon="mdiAccountPlusOutline" :disabled="provisionBlocked !== null" data-testid="go-provision" @click="provisionOpen = true">
                신규 운영자 등록
              </v-btn>
            </span>
          </template>
          <span data-testid="go-provision-blocked">{{ provisionBlocked }}</span>
        </v-tooltip>
      </template>
    </AdminPageHeader>

    <v-card class="mb-4" data-testid="admin-operator-filters">
      <v-card-text class="pa-4">
        <v-row dense align="center">
          <v-col cols="12" md="6">
            <v-text-field
              v-model="keywordInput"
              label="이름 · 이메일"
              placeholder="이름·이메일 일부"
              :prepend-inner-icon="mdiMagnify"
              :maxlength="ADMIN_OPERATOR_KEYWORD_MAX"
              hide-details
              clearable
              data-testid="filter-keyword"
              @keyup.enter="submitKeyword"
              @click:clear="applyQuery({ keyword: '' })"
            />
          </v-col>
          <v-col cols="6" md="3">
            <v-select
              :model-value="query.role"
              :items="ADMIN_OPERATOR_ROLE_OPTIONS"
              label="역할"
              hide-details
              data-testid="filter-role"
              @update:model-value="(value) => applyQuery({ role: value })"
            />
          </v-col>
          <v-col cols="6" md="3">
            <v-select
              :model-value="query.status"
              :items="ADMIN_OPERATOR_STATUS_OPTIONS"
              label="상태"
              hide-details
              data-testid="filter-status"
              @update:model-value="(value) => applyQuery({ status: value })"
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
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="admin-operator-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="admin-operator-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <AdminOperatorTable
        v-else
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        :me="me"
        :show-withdrawn="query.status === 'WITHDRAWN'"
        @revoke="(item) => { revokeTarget = item }"
        @update:page="(next) => applyQuery({ page: next }, false)"
        @update:size="(next) => applyQuery({ size: next })"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-operator-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiShieldAccountOutline" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <template v-if="filtersActive">
              <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 운영자가 없습니다</p>
              <p class="text-body-2 text-medium-emphasis mb-3">역할·상태·검색어를 바꾸거나 초기화해 보세요.</p>
              <v-btn size="small" variant="outlined" @click="resetQuery">필터 초기화</v-btn>
            </template>
            <template v-else>
              <p class="text-subtitle-2 font-weight-medium mb-1">운영자가 없습니다</p>
              <p class="text-body-2 text-medium-emphasis">슈퍼 관리자가 기존 회원에게 운영 관리자 역할을 부여할 수 있습니다.</p>
            </template>
          </div>
        </template>
      </AdminOperatorTable>
    </v-card>

    <AdminOperatorProvisionDialog :open="provisionOpen" @done="closeProvision(true)" @cancel="closeProvision(false)" />
    <AdminOperatorRevokeDialog
      :open="revokeTarget !== null"
      :target="revokeTarget"
      :super-admin-count="superAdminCount"
      @done="closeRevoke(true)"
      @cancel="closeRevoke(false)"
    />
  </div>
</template>
