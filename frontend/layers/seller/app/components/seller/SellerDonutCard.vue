<script setup lang="ts">
import { sellerDonutChart } from '#layers/seller/app/lib/seller-order-stats-view'
import type { DistributionRowView } from '~/lib/stats-view'

/**
 * 셀러 분포 카드(Track 90-E-2·관리자 AdminDonutCard 복제): 도넛 + 옆 표(라벨·건수·비중). 합 0이면 도넛 대신 빈 문구.
 * 도넛 옵션은 lib sellerDonutChart(계열 number[]·SellerChart type 'donut').
 */
const props = defineProps<{
  title: string
  rows: DistributionRowView[]
  /** 툴팁 단위("건"). */
  unit: string
  testid: string
}>()

const SHARE_FRACTION_DIGITS = 2
const CHART_COLS = 5
const chart = computed(() => sellerDonutChart(props.rows, props.unit))
</script>

<template>
  <v-card class="h-100" :data-testid="testid">
    <v-card-text class="pa-5">
      <p class="text-subtitle-1 font-weight-bold mb-3">{{ title }}</p>
      <v-row dense align="center">
        <v-col cols="12" :md="CHART_COLS">
          <p v-if="chart.empty" class="text-body-2 text-medium-emphasis text-center py-8 mb-0" :data-testid="`${testid}-empty`">데이터 없음</p>
          <SellerChart v-else type="donut" :series="chart.series" :options="chart.options" :height="240" />
        </v-col>
        <v-col cols="12" :md="12 - CHART_COLS">
          <v-table density="compact" class="slr-table slr-table--compact" :data-testid="`${testid}-table`">
            <thead>
              <tr>
                <th class="text-start slr-nowrap">항목</th>
                <th class="text-end slr-nowrap">건수</th>
                <th class="text-end slr-nowrap">비중</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="rows.length === 0">
                <td colspan="3" class="text-center text-medium-emphasis py-4">데이터 없음</td>
              </tr>
              <tr v-for="row in rows" :key="row.key" :data-testid="`${testid}-row`">
                <td class="text-body-2">{{ row.label }}</td>
                <td class="text-end text-body-2 slr-nowrap">{{ row.count.toLocaleString('ko-KR') }}{{ unit }}</td>
                <td class="text-end text-body-2 slr-nowrap">{{ row.share.toFixed(SHARE_FRACTION_DIGITS) }}%</td>
              </tr>
            </tbody>
          </v-table>
        </v-col>
      </v-row>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.slr-nowrap {
  white-space: nowrap;
}
</style>
