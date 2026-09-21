<script setup lang="ts">
import {
  PERIOD_PRESETS,
  PERIOD_PRESET_LABELS,
  STATS_COMPARES,
  STATS_COMPARE_LABELS,
  STATS_UNITS,
  STATS_UNIT_LABELS,
  type PeriodPreset,
  type StatsCompare,
  type StatsUnit,
} from '~/lib/constants/stats'
import { normalizeDateOnly } from '#layers/seller/app/lib/seller-order-query'

/**
 * 셀러 통계 기간 선택기(Track 90-E-1·관리자 AdminPeriodPicker 복제). 프리셋 4 + 직접 지정(from·to 네이티브 date) + 집계 단위 + 비교 기간.
 * 모든 확정은 emit('apply')로 부모(URL 단일 소스)에 넘긴다. 기간 오류(역전·365일 초과)는 부모가 판정해 periodError로 내려주고 요청을 보내지 않는다.
 * 프리셋을 고르면 from·to 입력은 해당 구간을 읽기 전용으로 보여주고, 날짜를 직접 바꾸면 자동으로 custom이 된다.
 */
defineProps<{
  preset: PeriodPreset
  /** 화면에 보여줄 실제 구간(프리셋이면 부모가 계산해 넘김·custom이면 입력값). */
  from: string | null
  to: string | null
  /** 미전달이면 단위 선택을 숨긴다(90-E-3 상품 탭 노출 제어). */
  unit?: StatsUnit
  /** 미전달이면 비교 선택을 숨긴다. */
  compare?: StatsCompare
  /** 기간 검증 오류 문구(없으면 null). */
  periodError: string | null
  maxDays: number
}>()

const emit = defineEmits<{
  apply: [patch: { preset?: PeriodPreset; from?: string | null; to?: string | null; unit?: StatsUnit; compare?: StatsCompare }]
}>()

const presetItems = PERIOD_PRESETS.filter((preset) => preset !== 'custom').map((value) => ({ value, title: PERIOD_PRESET_LABELS[value] }))
const unitItems = STATS_UNITS.map((value) => ({ value, title: STATS_UNIT_LABELS[value] }))
const compareItems = STATS_COMPARES.map((value) => ({ value, title: STATS_COMPARE_LABELS[value] }))

/** 날짜 직접 입력 → custom 전환. 바뀐 쪽만 보낸다(반대쪽 보충은 부모 applyQuery·관리자 E2E 실측 stale 방지). */
function applyDate(key: 'from' | 'to', value: string | null): void {
  emit('apply', { preset: 'custom', [key]: normalizeDateOnly(value === '' ? null : value) })
}
</script>

<template>
  <v-card class="mb-4" data-testid="seller-period-picker">
    <v-card-text class="pa-4">
      <v-row dense align="center">
        <v-col cols="12" lg="5">
          <div class="d-flex align-center flex-wrap ga-2">
            <v-btn-toggle :model-value="preset" color="primary" density="comfortable" variant="outlined" divided mandatory data-testid="period-preset" @update:model-value="(value: PeriodPreset | undefined) => value && emit('apply', { preset: value })">
              <v-btn v-for="item in presetItems" :key="item.value" :value="item.value" size="small" :data-testid="`period-preset-${item.value}`">{{ item.title }}</v-btn>
            </v-btn-toggle>
            <v-chip v-if="preset === 'custom'" size="small" variant="tonal" color="primary" data-testid="period-preset-custom">{{ PERIOD_PRESET_LABELS.custom }}</v-chip>
          </div>
        </v-col>
        <v-col cols="6" md="3" lg="2">
          <v-text-field
            :model-value="from ?? ''"
            type="date"
            label="시작일"
            hide-details
            density="compact"
            :error="periodError !== null"
            data-testid="period-from"
            @update:model-value="(value) => applyDate('from', value)"
          />
        </v-col>
        <v-col cols="6" md="3" lg="2">
          <v-text-field
            :model-value="to ?? ''"
            type="date"
            label="종료일"
            hide-details
            density="compact"
            :error="periodError !== null"
            data-testid="period-to"
            @update:model-value="(value) => applyDate('to', value)"
          />
        </v-col>
        <v-col v-if="unit !== undefined" cols="6" md="3" lg="1">
          <v-select :model-value="unit" :items="unitItems" label="단위" hide-details density="compact" data-testid="period-unit" @update:model-value="(value: StatsUnit) => emit('apply', { unit: value })" />
        </v-col>
        <v-col v-if="compare !== undefined" cols="6" md="3" lg="2">
          <v-select :model-value="compare" :items="compareItems" label="비교" hide-details density="compact" data-testid="period-compare" @update:model-value="(value: StatsCompare) => emit('apply', { compare: value })" />
        </v-col>
      </v-row>
      <p v-if="periodError" class="text-caption text-error mt-2 mb-0" data-testid="period-error">{{ periodError }}</p>
      <p v-else-if="preset === 'custom' && (!from || !to)" class="text-caption text-medium-emphasis mt-2 mb-0" data-testid="period-incomplete">시작일과 종료일을 모두 입력하면 조회합니다.</p>
      <p v-else class="text-caption text-medium-emphasis mt-2 mb-0" data-testid="period-hint">최대 {{ maxDays }}일까지 조회할 수 있습니다.</p>
    </v-card-text>
  </v-card>
</template>
