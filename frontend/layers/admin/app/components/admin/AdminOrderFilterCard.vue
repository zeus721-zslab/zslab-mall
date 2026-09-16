<script setup lang="ts">
import { mdiMagnify, mdiRefresh } from '@mdi/js'
import type { AdminOrderListQuery } from '#layers/admin/app/types/admin-order'
import { ORDER_STATUS_LABELS } from '~/lib/constants/order'
import {
  ADMIN_DELIVERY_STATUS_OPTIONS,
  ADMIN_ORDER_KEYWORD_MAX,
  ADMIN_ORDER_SORT_OPTIONS,
  ADMIN_ORDER_STATUS_CODES,
  ADMIN_PAYMENT_STATUS_OPTIONS,
} from '#layers/admin/app/lib/constants/admin-order'
import { isPeriodInverted, normalizeDateOnly } from '#layers/admin/app/lib/admin-order-query'

// 필터 카드(FE-27·AdminProductFilterCard 패턴). 검색어는 로컬 입력값을 두고 검색 버튼·Enter로만 확정한다.
// 기간(네이티브 date 2개)·드롭다운(주문/결제/배송 상태·정렬)은 변경 즉시 확정. 모든 확정은 emit('apply')로 부모(URL 단일 소스)에 넘긴다.
const props = defineProps<{
  query: AdminOrderListQuery
}>()

const emit = defineEmits<{
  apply: [patch: Partial<AdminOrderListQuery>]
  reset: []
}>()

const keywordInput = ref<string>(props.query.keyword)
watch(() => props.query.keyword, (next) => { keywordInput.value = next })

const orderStatusItems = ADMIN_ORDER_STATUS_CODES.map((value) => ({ value, title: ORDER_STATUS_LABELS[value] }))

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
  <v-card class="mb-4" data-testid="admin-order-filters">
    <v-card-text class="pa-4">
      <v-row dense align="center">
        <v-col cols="12" md="6">
          <v-text-field
            v-model="keywordInput"
            label="주문번호 · 주문자 · 상품명"
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
        <v-col cols="6" md="3">
          <v-text-field
            :model-value="query.from ?? ''"
            type="date"
            label="주문일 시작"
            hide-details
            data-testid="filter-from"
            @update:model-value="(value) => applyDate('from', value)"
          />
        </v-col>
        <v-col cols="6" md="3">
          <v-text-field
            :model-value="query.to ?? ''"
            type="date"
            label="주문일 종료"
            hide-details
            data-testid="filter-to"
            @update:model-value="(value) => applyDate('to', value)"
          />
        </v-col>
        <v-col cols="12" sm="4">
          <v-select
            :model-value="query.status"
            :items="orderStatusItems"
            label="주문상태"
            hide-details
            clearable
            data-testid="filter-status"
            @update:model-value="(value) => emit('apply', { status: value ?? null })"
          />
        </v-col>
        <v-col cols="6" sm="4">
          <v-select
            :model-value="query.paymentStatus"
            :items="ADMIN_PAYMENT_STATUS_OPTIONS"
            label="결제상태"
            hide-details
            clearable
            data-testid="filter-payment-status"
            @update:model-value="(value) => emit('apply', { paymentStatus: value ?? null })"
          />
        </v-col>
        <v-col cols="6" sm="4">
          <v-select
            :model-value="query.deliveryStatus"
            :items="ADMIN_DELIVERY_STATUS_OPTIONS"
            label="배송상태"
            hide-details
            clearable
            data-testid="filter-delivery-status"
            @update:model-value="(value) => emit('apply', { deliveryStatus: value ?? null })"
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
