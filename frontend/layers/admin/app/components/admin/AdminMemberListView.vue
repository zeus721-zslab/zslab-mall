<script setup lang="ts">
import { mdiAccountOutline, mdiAlertCircleOutline, mdiMagnify, mdiRefresh } from '@mdi/js'
import type { AdminMemberListQuery, AdminMemberSummary } from '#layers/admin/app/types/admin-member'
import {
  ADMIN_MEMBER_KEYWORD_MAX,
  ADMIN_MEMBER_PAGE_SIZES,
  ADMIN_MEMBER_SORT_OPTIONS,
  type AdminMemberStatus,
} from '#layers/admin/app/lib/constants/admin-member'
import {
  DEFAULT_ADMIN_MEMBER_QUERY,
  hasActiveFilters,
  memberRowNumber,
  parseAdminMemberQuery,
  toAdminMemberRouteQuery,
} from '#layers/admin/app/lib/admin-member-query'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { formatDateTime } from '~/lib/utils/datetime'
import { formatPhone } from '~/lib/format/phone'
import { useAdminMembers } from '#layers/admin/app/composables/useAdminMembers'

/**
 * 관리자 회원 목록 공용 뷰(Track 84 FE). 일반회원(ACTIVE)·탈퇴회원(WITHDRAWN) 페이지가 status만 다르게 주입한다.
 * URL query가 검색어·정렬·페이지의 단일 소스(orders/index.vue 패턴): 화면 조작 → router.replace → route.query watch → 조회.
 * 행 번호는 최신 가입 순 역번호(totalCount − page×size − rowIndex), 최종구매일 없음은 "-".
 */
const props = defineProps<{
  status: AdminMemberStatus
}>()

const route = useRoute()
const router = useRouter()
const membersApi = useAdminMembers()

const query = computed<AdminMemberListQuery>(() => parseAdminMemberQuery(route.query))
const isWithdrawn = computed(() => props.status === 'WITHDRAWN')

// ---------- 목록 ----------
const items = ref<AdminMemberSummary[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await membersApi.list(query.value, props.status)
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

// router.replace가 반영되기 전에 연속 확정되면 앞 변경이 유실되므로 마지막으로 보낸 상태를 기준으로 합친다.
let pendingQuery: AdminMemberListQuery | null = null
watch(() => route.query, () => { pendingQuery = null; void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<AdminMemberListQuery>, resetPage = true): void {
  const next: AdminMemberListQuery = { ...(pendingQuery ?? query.value), ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  pendingQuery = next
  void router.replace({ query: toAdminMemberRouteQuery(next) })
}

function resetQuery(): void {
  pendingQuery = null
  void router.replace({ query: toAdminMemberRouteQuery({ ...DEFAULT_ADMIN_MEMBER_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveFilters(query.value))

// ---------- 필터(검색어는 로컬 입력값·검색 버튼/Enter로 확정) ----------
const keywordInput = ref<string>(query.value.keyword)
watch(() => query.value.keyword, (next) => { keywordInput.value = next })

function submitKeyword(): void {
  applyQuery({ keyword: keywordInput.value.trim() })
}

// ---------- 표 ----------
const headers = computed(() => [
  { title: '번호', key: 'rowNumber', sortable: false, width: 72 },
  { title: '이름', key: 'name', sortable: false },
  { title: '이메일', key: 'email', sortable: false },
  { title: '연락처', key: 'phone', sortable: false },
  { title: '가입일', key: 'createdAt', sortable: false },
  { title: '최종구매일', key: 'lastPaidAt', sortable: false },
  ...(isWithdrawn.value ? [{ title: '탈퇴일', key: 'withdrawnAt', sortable: false }] : []),
  { title: '관리', key: 'actions', sortable: false, align: 'end' as const },
])

function rowNumber(index: number): number {
  return memberRowNumber(totalCount.value, query.value.page, query.value.size, index)
}

function open(item: AdminMemberSummary): void {
  // 현재 목록 URL(필터·페이지)을 back으로 넘겨 상세에서 같은 목록으로 복귀한다(FE-26 패턴).
  void navigateTo({ path: `/admin/members/${item.publicId}`, query: { back: route.fullPath } })
}
</script>

<template>
  <div>
    <v-card class="mb-4" data-testid="admin-member-filters">
      <v-card-text class="pa-4">
        <v-row dense align="center">
          <v-col cols="12" md="8">
            <v-text-field
              v-model="keywordInput"
              label="이름 · 이메일 · 연락처"
              placeholder="이름·이메일·연락처 일부"
              :prepend-inner-icon="mdiMagnify"
              :maxlength="ADMIN_MEMBER_KEYWORD_MAX"
              hide-details
              clearable
              data-testid="filter-keyword"
              @keyup.enter="submitKeyword"
              @click:clear="applyQuery({ keyword: '' })"
            />
          </v-col>
          <v-col cols="12" md="4">
            <v-select
              :model-value="query.sort"
              :items="ADMIN_MEMBER_SORT_OPTIONS"
              label="정렬"
              hide-details
              data-testid="filter-sort"
              @update:model-value="(value) => applyQuery({ sort: value })"
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
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="admin-member-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="admin-member-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <v-data-table-server
        v-else
        :headers="headers"
        :items="items"
        :items-length="totalCount"
        :page="query.page + 1"
        :items-per-page="query.size"
        :items-per-page-options="ADMIN_MEMBER_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
        :loading="loading"
        items-per-page-text="페이지당"
        page-text="{0}-{1} / {2}"
        loading-text="불러오는 중…"
        item-value="publicId"
        hover
        class="adm-table adm-table--compact"
        data-testid="admin-member-table"
        @update:page="(next: number) => applyQuery({ page: next - 1 }, false)"
        @update:items-per-page="(next: number) => applyQuery({ size: next })"
      >
        <template #[`item.rowNumber`]="{ index }">
          <span class="text-medium-emphasis" data-testid="row-number">{{ rowNumber(index) }}</span>
        </template>
        <template #[`item.name`]="{ item }">
          <span class="font-weight-medium" data-testid="row-name">{{ item.name ?? '—' }}</span>
        </template>
        <template #[`item.email`]="{ item }">
          <span data-testid="row-email">{{ item.email ?? '—' }}</span>
        </template>
        <template #[`item.phone`]="{ item }">
          {{ formatPhone(item.phone ?? '—') }}
        </template>
        <template #[`item.createdAt`]="{ item }">
          {{ formatDateTime(item.createdAt) }}
        </template>
        <template #[`item.lastPaidAt`]="{ item }">
          <span data-testid="row-last-paid-at">{{ item.lastPaidAt ? formatDateTime(item.lastPaidAt) : '-' }}</span>
        </template>
        <template #[`item.withdrawnAt`]="{ item }">
          <span data-testid="row-withdrawn-at">{{ item.withdrawnAt ? formatDateTime(item.withdrawnAt) : '-' }}</span>
        </template>
        <template #[`item.actions`]="{ item }">
          <div class="d-flex justify-end">
            <v-btn size="small" variant="outlined" color="primary" data-testid="row-open" @click="open(item)">회원변경</v-btn>
          </div>
        </template>

        <template #no-data>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-member-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiAccountOutline" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <template v-if="filtersActive">
              <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 회원이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis mb-3">검색어를 바꾸거나 초기화해 보세요.</p>
              <v-btn size="small" variant="outlined" @click="resetQuery">필터 초기화</v-btn>
            </template>
            <template v-else>
              <p class="text-subtitle-2 font-weight-medium mb-1">{{ isWithdrawn ? '탈퇴한 회원이 없습니다' : '회원이 없습니다' }}</p>
              <p class="text-body-2 text-medium-emphasis">{{ isWithdrawn ? '아직 탈퇴 처리된 회원이 없습니다.' : '아직 가입한 회원이 없습니다.' }}</p>
            </template>
          </div>
        </template>
      </v-data-table-server>
    </v-card>
  </div>
</template>
