<script setup lang="ts">
import { mdiChevronRight } from '@mdi/js'
import { sortBreakdownRows, type BreakdownSortKey, type SalesBreakdownRowView } from '#layers/admin/app/lib/admin-sales-stats-view'
import { formatWon } from '#layers/admin/app/lib/format'

/**
 * 매출 분해 테이블(FE-34). BE는 매출 DESC 전량(페이징 없음)이라 정렬은 클라이언트(lib sortBreakdownRows)에서 한다 — 기존 관리자 표는 전부
 * 서버 정렬(v-data-table-server·sortable:false)이라 이 컴포넌트가 첫 클라이언트 정렬 선례. 비중은 셀 안 막대. drillable 행은 클릭 시 emit('drill').
 */
const props = defineProps<{
  rows: SalesBreakdownRowView[]
  showCompare: boolean
  loading: boolean
  loadError: string | null
}>()

const emit = defineEmits<{
  drill: [row: SalesBreakdownRowView]
  retry: []
}>()

interface Header {
  key: BreakdownSortKey
  title: string
  align: 'start' | 'end'
  compareOnly?: boolean
}

const HEADERS: Header[] = [
  { key: 'name', title: '이름', align: 'start' },
  { key: 'revenue', title: '매출', align: 'end' },
  { key: 'share', title: '비중', align: 'end' },
  { key: 'orderCount', title: '주문수', align: 'end' },
  { key: 'quantity', title: '수량', align: 'end' },
  { key: 'compareRevenue', title: '비교 매출', align: 'end', compareOnly: true },
  { key: 'rate', title: '증감률', align: 'end', compareOnly: true },
]

const SHARE_FRACTION_DIGITS = 2

const sortKey = ref<BreakdownSortKey>('revenue')
const sortOrder = ref<'asc' | 'desc'>('desc')

const headers = computed(() => HEADERS.filter((header) => !header.compareOnly || props.showCompare))
const sortedRows = computed(() => sortBreakdownRows(props.rows, sortKey.value, sortOrder.value))

/** 같은 열 재클릭은 방향 반전, 다른 열은 숫자 열 DESC·이름 ASC로 시작. */
function toggleSort(key: BreakdownSortKey): void {
  if (sortKey.value === key) {
    sortOrder.value = sortOrder.value === 'desc' ? 'asc' : 'desc'
    return
  }
  sortKey.value = key
  sortOrder.value = key === 'name' ? 'asc' : 'desc'
}

function sortMark(key: BreakdownSortKey): string {
  if (sortKey.value !== key) return ''
  return sortOrder.value === 'desc' ? '▼' : '▲'
}

function onRowClick(row: SalesBreakdownRowView): void {
  if (row.drillable) emit('drill', row)
}
</script>

<template>
  <v-alert v-if="loadError" type="error" variant="tonal" class="ma-4" data-testid="breakdown-error">
    <div class="d-flex align-center justify-space-between flex-wrap ga-2">
      <span>{{ loadError }}</span>
      <v-btn size="small" variant="outlined" color="error" data-testid="breakdown-retry" @click="emit('retry')">다시 시도</v-btn>
    </div>
  </v-alert>
  <v-table v-else density="comfortable" hover class="adm-table adm-table--compact" data-testid="breakdown-table">
    <thead>
      <tr>
        <th
          v-for="header in headers"
          :key="header.key"
          :class="[`text-${header.align}`, 'adm-sortable']"
          role="button"
          :aria-sort="sortKey === header.key ? (sortOrder === 'desc' ? 'descending' : 'ascending') : 'none'"
          :data-testid="`breakdown-sort-${header.key}`"
          @click="toggleSort(header.key)"
        >
          {{ header.title }} <span class="text-caption">{{ sortMark(header.key) }}</span>
        </th>
      </tr>
    </thead>
    <tbody>
      <tr v-if="loading && rows.length === 0">
        <td :colspan="headers.length" class="text-center text-medium-emphasis py-6" data-testid="breakdown-loading">불러오는 중…</td>
      </tr>
      <tr v-else-if="rows.length === 0">
        <td :colspan="headers.length" class="text-center text-medium-emphasis py-6" data-testid="breakdown-empty">데이터 없음</td>
      </tr>
      <tr
        v-for="row in sortedRows"
        :key="row.id"
        :class="{ 'adm-row--drillable': row.drillable }"
        :data-testid="row.drillable ? 'breakdown-row-drillable' : 'breakdown-row'"
        @click="onRowClick(row)"
      >
        <td>
          <div class="d-flex align-center ga-1" style="min-width: 0">
            <span class="text-body-2 text-truncate" :class="{ 'text-medium-emphasis font-italic': row.deleted }" data-testid="breakdown-name">{{ row.name }}</span>
            <v-icon v-if="row.drillable" :icon="mdiChevronRight" size="16" class="text-medium-emphasis flex-shrink-0" />
          </div>
        </td>
        <td class="text-end text-body-2 font-weight-medium adm-nowrap" data-testid="breakdown-revenue">{{ formatWon(row.revenue) }}</td>
        <td class="text-end">
          <div class="d-flex align-center justify-end ga-2">
            <v-progress-linear :model-value="row.share" color="primary" bg-color="grey-lighten-3" height="6" rounded style="max-width: 80px" />
            <span class="text-body-2" data-testid="breakdown-share">{{ row.share.toFixed(SHARE_FRACTION_DIGITS) }}%</span>
          </div>
        </td>
        <td class="text-end text-body-2 adm-nowrap">{{ row.orderCount.toLocaleString('ko-KR') }}건</td>
        <td class="text-end text-body-2 adm-nowrap">{{ row.quantity.toLocaleString('ko-KR') }}개</td>
        <td v-if="showCompare" class="text-end text-body-2 text-medium-emphasis adm-nowrap">{{ row.compareRevenue === null ? '—' : formatWon(row.compareRevenue) }}</td>
        <td v-if="showCompare" class="text-end">
          <v-chip :class="row.rateClass" size="x-small" variant="flat" data-testid="breakdown-rate">{{ row.rateText }}</v-chip>
        </td>
      </tr>
    </tbody>
  </v-table>
</template>

<style scoped>
.adm-sortable {
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}
.adm-row--drillable {
  cursor: pointer;
}
.adm-nowrap {
  white-space: nowrap;
}
</style>
