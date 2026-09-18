<script setup lang="ts">
import { mdiInformationOutline } from '@mdi/js'
import { donutChart, type DistributionRowView } from '#layers/admin/app/lib/admin-order-stats-view'

/**
 * 분포 카드(FE-35): 도넛 + 옆 표(라벨·건수·비중). 합 0이면 도넛 대신 빈 문구. 표 열을 바꿔야 하는 경우(등급 분포·매출 열)는 기본 슬롯으로
 * 표를 대체한다. 도넛 옵션은 lib donutChart(계열 number[]·AdminChart type 'donut').
 */
const props = defineProps<{
  title: string
  rows: DistributionRowView[]
  /** 툴팁 단위("건"·"명"). */
  unit: string
  notice?: string
  testid: string
  /** 도넛 열 폭(md·기본 5). 표 열이 많으면 줄인다. */
  chartCols?: number
}>()

const SHARE_FRACTION_DIGITS = 2
const chart = computed(() => donutChart(props.rows, props.unit))
</script>

<template>
  <v-card class="h-100" :data-testid="testid">
    <v-card-text class="pa-5">
      <p class="text-subtitle-1 font-weight-bold mb-1">{{ title }}</p>
      <p v-if="notice" class="text-caption text-medium-emphasis mb-3" :data-testid="`${testid}-notice`">
        <v-icon :icon="mdiInformationOutline" size="14" class="mr-1" />{{ notice }}
      </p>
      <v-row dense align="center">
        <v-col cols="12" :md="chartCols ?? 5">
          <p v-if="chart.empty" class="text-body-2 text-medium-emphasis text-center py-8 mb-0" :data-testid="`${testid}-empty`">데이터 없음</p>
          <AdminChart v-else type="donut" :series="chart.series" :options="chart.options" :height="240" />
        </v-col>
        <v-col cols="12" :md="12 - (chartCols ?? 5)">
          <slot>
            <v-table density="compact" class="adm-table adm-table--compact" :data-testid="`${testid}-table`">
              <thead>
                <tr>
                  <th class="text-start adm-nowrap">항목</th>
                  <th class="text-end adm-nowrap">건수</th>
                  <th class="text-end adm-nowrap">비중</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="rows.length === 0">
                  <td colspan="3" class="text-center text-medium-emphasis py-4">데이터 없음</td>
                </tr>
                <tr v-for="row in rows" :key="row.key" :data-testid="`${testid}-row`">
                  <td class="text-body-2">{{ row.label }}</td>
                  <td class="text-end text-body-2 adm-nowrap">{{ row.count.toLocaleString('ko-KR') }}{{ unit }}</td>
                  <td class="text-end text-body-2 adm-nowrap">{{ row.share.toFixed(SHARE_FRACTION_DIGITS) }}%</td>
                </tr>
              </tbody>
            </v-table>
          </slot>
        </v-col>
      </v-row>
    </v-card-text>
  </v-card>
</template>
