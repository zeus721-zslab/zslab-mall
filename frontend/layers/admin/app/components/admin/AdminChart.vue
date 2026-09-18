<script setup lang="ts">
import type { ApexOptions } from 'apexcharts'
import type { AdminChartSeries } from '#layers/admin/app/lib/admin-dashboard-view'
import { defineAsyncComponent } from 'vue'

/**
 * 관리자 차트 공용 래퍼(FE-33). apexcharts는 window 의존이라 클라이언트에서만 동적 import한다(관리자 레이어 한정 로딩·사용자 entry 번들 무오염·
 * D-12 α와 같은 원칙). 페이지는 이 컴포넌트만 쓰고 vue3-apexcharts를 직접 import하지 않는다. 옵션(축·툴팁·색)은 호출부가 lib/admin-dashboard-view.ts
 * 순수 함수로 만들어 넘긴다.
 *
 * <p>트랩: vue3-apexcharts 1.11은 options 변경 시 JSON 깊은 복사(copyData)로 updateOptions를 호출해 formatter 함수가 사라진다(최초 init은 함수 보존).
 * 그래서 options가 바뀌면 key를 올려 컴포넌트를 다시 마운트시켜 항상 init 경로(함수 보존)로 그린다(애니메이션은 옵션에서 꺼 두어 비용 없음).
 */
const props = defineProps<{
  type: 'bar' | 'area' | 'line'
  series: AdminChartSeries
  options: ApexOptions
  height?: number
}>()

const VueApexCharts = import.meta.client
  ? defineAsyncComponent(() => import('vue3-apexcharts').then((module) => module.default))
  : null

const remountKey = ref(0)
watch(() => props.options, () => {
  remountKey.value += 1
})
</script>

<template>
  <ClientOnly>
    <component
      :is="VueApexCharts"
      v-if="VueApexCharts"
      :key="remountKey"
      :type="type"
      :series="series"
      :options="options"
      :height="height ?? 280"
      width="100%"
    />
  </ClientOnly>
</template>
