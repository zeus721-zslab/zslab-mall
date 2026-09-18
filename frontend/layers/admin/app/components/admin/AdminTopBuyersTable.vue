<script setup lang="ts">
import type { AdminTopBuyer } from '#layers/admin/app/types/admin-member-stats'
import { topBuyerRows, type TopBuyerRowView } from '#layers/admin/app/lib/admin-member-stats-view'
import { formatCount } from '#layers/admin/app/lib/admin-sales-stats-view'

/**
 * 구매 상위 회원 20 표(FE-35). 순위·이름·이메일(원문·null "—")·주문수·매출. publicId가 있는 행은 클릭 시 관리자 회원 상세로 이동한다
 * (회원 목록과 같은 경로·back 쿼리). 정렬은 BE 매출 내림차순 고정.
 */
const props = defineProps<{
  rows: AdminTopBuyer[]
}>()

const route = useRoute()
const views = computed(() => topBuyerRows(props.rows))

function open(row: TopBuyerRowView): void {
  if (!row.navigable || !row.userPublicId) return
  void navigateTo({ path: `/admin/members/${row.userPublicId}`, query: { back: route.fullPath } })
}
</script>

<template>
  <v-card data-testid="top-buyers">
    <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5 pb-0">구매 상위 회원</v-card-title>
    <p class="text-caption text-medium-emphasis px-5 pt-1 mb-2">기간 내 매출 상위 20명 · 행을 누르면 회원 상세로 이동합니다.</p>
    <v-table density="comfortable" hover class="adm-table adm-table--compact" data-testid="top-buyers-table">
      <thead>
        <tr>
          <th class="text-end">순위</th>
          <th class="text-start">이름</th>
          <th class="text-start">이메일</th>
          <th class="text-end">주문수</th>
          <th class="text-end">매출</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="views.length === 0">
          <td colspan="5" class="text-center text-medium-emphasis py-6" data-testid="top-buyers-empty">데이터 없음</td>
        </tr>
        <tr
          v-for="(row, index) in views"
          :key="row.id"
          :class="{ 'adm-row--navigable': row.navigable }"
          :data-testid="row.navigable ? 'top-buyers-row-navigable' : 'top-buyers-row'"
          @click="open(row)"
        >
          <td class="text-end text-body-2 text-medium-emphasis">{{ index + 1 }}</td>
          <td class="text-body-2 font-weight-medium" data-testid="top-buyers-name">{{ row.name }}</td>
          <td class="text-body-2" data-testid="top-buyers-email">{{ row.email }}</td>
          <td class="text-end text-body-2 adm-nowrap">{{ formatCount(row.orderCount) }}</td>
          <td class="text-end text-body-2 font-weight-medium adm-nowrap">{{ row.revenue }}</td>
        </tr>
      </tbody>
    </v-table>
  </v-card>
</template>

<style scoped>
.adm-row--navigable {
  cursor: pointer;
}
</style>
