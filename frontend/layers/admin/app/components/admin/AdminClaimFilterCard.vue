<script setup lang="ts">
import { mdiMagnify, mdiRefresh } from '@mdi/js'
import type { AdminClaimListQuery } from '#layers/admin/app/types/admin-claim'
import { CLAIM_STATUS_LABELS, REFUND_STATUS_LABELS, type ClaimStatus, type RefundStatus } from '~/lib/constants/claim'
import { ADMIN_ORDER_KEYWORD_MAX, ADMIN_ORDER_SORT_OPTIONS } from '#layers/admin/app/lib/constants/admin-order'
import { isPeriodInverted, normalizeDateOnly } from '#layers/admin/app/lib/admin-order-query'

// 클레임 필터 카드(FE-28·AdminOrderFilterCard 패턴). 유형은 탭(부모)이 소유하므로 여기서는 검색·요청일 기간·상태·정렬만 다룬다.
// 검색어는 로컬 입력값을 두고 검색 버튼·Enter로만 확정, 기간·드롭다운은 변경 즉시 확정. 모든 확정은 emit('apply')로 부모(URL 단일 소스)에 넘긴다.
const props = defineProps<{
  query: AdminClaimListQuery
}>()

const emit = defineEmits<{
  apply: [patch: Partial<AdminClaimListQuery>]
  reset: []
}>()

const keywordInput = ref<string>(props.query.keyword)
watch(() => props.query.keyword, (next) => { keywordInput.value = next })

const statusItems = (Object.keys(CLAIM_STATUS_LABELS) as ClaimStatus[]).map((value) => ({ value, title: CLAIM_STATUS_LABELS[value] }))
const refundStatusItems = (Object.keys(REFUND_STATUS_LABELS) as RefundStatus[]).map((value) => ({ value, title: REFUND_STATUS_LABELS[value] }))

const periodInverted = computed(() => isPeriodInverted(props.query))

function submitKeyword(): void {
  emit('apply', { keyword: keywordInput.value.trim() })
}

/** 네이티브 date 입력값(yyyy-MM-dd 또는 빈 문자열) → 검증 후 확정. 형식이 아니면 해제. */
function applyDate(key: 'from' | 'to', value: string | null): void {
  emit('apply', { [key]: normalizeDateOnly(value === '' ? null : value) })
}
</script>

<template>
  <v-card class="mb-4" data-testid="admin-claim-filters">
    <v-card-text class="pa-4">
      <v-row dense align="center">
        <v-col cols="12" md="4">
          <v-text-field
            v-model="keywordInput"
            label="주문번호 · 구매자 · 상품명"
            placeholder="주문번호 정확히 또는 이름·이메일·상품명 일부"
            :prepend-inner-icon="mdiMagnify"
            :maxlength="ADMIN_ORDER_KEYWORD_MAX"
            hide-details
            clearable
            data-testid="filter-keyword"
            @keyup.enter="submitKeyword"
            @click:clear="emit('apply', { keyword: '' })"
          />
        </v-col>
        <v-col cols="6" md="2">
          <v-text-field
            :model-value="query.from ?? ''"
            type="date"
            label="요청일 시작"
            hide-details
            data-testid="filter-from"
            @update:model-value="(value) => applyDate('from', value)"
          />
        </v-col>
        <v-col cols="6" md="2">
          <v-text-field
            :model-value="query.to ?? ''"
            type="date"
            label="요청일 종료"
            hide-details
            data-testid="filter-to"
            @update:model-value="(value) => applyDate('to', value)"
          />
        </v-col>
        <v-col cols="6" md="2">
          <v-select
            :model-value="query.status"
            :items="statusItems"
            label="처리 상태"
            hide-details
            clearable
            data-testid="filter-status"
            @update:model-value="(value) => emit('apply', { status: value ?? null })"
          />
        </v-col>
        <v-col cols="6" md="2">
          <v-select
            :model-value="query.refundStatus"
            :items="refundStatusItems"
            label="환불 상태"
            hide-details
            clearable
            data-testid="filter-refund-status"
            @update:model-value="(value) => emit('apply', { refundStatus: value ?? null })"
          />
        </v-col>
      </v-row>
      <div class="d-flex align-center justify-space-between flex-wrap ga-2 mt-3">
        <div class="d-flex align-center ga-2">
          <v-btn color="primary" :prepend-icon="mdiMagnify" data-testid="filter-search" @click="submitKeyword">검색</v-btn>
          <v-btn variant="text" :prepend-icon="mdiRefresh" data-testid="filter-reset" @click="emit('reset')">초기화</v-btn>
          <span v-if="periodInverted" class="text-caption text-error" data-testid="filter-period-error">시작일이 종료일보다 늦습니다.</span>
        </div>
        <!-- 정렬 select는 버튼 줄 인라인이라 compact(의도된 예외·FE-25 동일) -->
        <v-select
          :model-value="query.sort"
          :items="ADMIN_ORDER_SORT_OPTIONS"
          label="정렬"
          hide-details
          density="compact"
          style="max-width: 180px"
          data-testid="filter-sort"
          @update:model-value="(value) => emit('apply', { sort: value })"
        />
      </div>
    </v-card-text>
  </v-card>
</template>
