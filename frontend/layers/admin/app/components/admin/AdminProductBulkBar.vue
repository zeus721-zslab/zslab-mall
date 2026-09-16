<script setup lang="ts">
import type { AdminProductBulkStatusTarget } from '#layers/admin/app/lib/constants/product'
import { ADMIN_PRODUCT_BULK_STATUS_OPTIONS, ADMIN_PRODUCT_SOLD_OUT_OPTIONS } from '#layers/admin/app/lib/constants/product'

// 일괄 바(FE-25). 선택이 있을 때만 부모가 렌더한다. 목표 선택 후 "적용"을 누르면 부모가 확인 다이얼로그를 띄운다.
defineProps<{ selectedCount: number; busy: boolean }>()
const emit = defineEmits<{
  applyStatus: [status: AdminProductBulkStatusTarget]
  applySoldOut: [soldOut: boolean]
  clear: []
}>()

const status = ref<AdminProductBulkStatusTarget | null>(null)
const soldOut = ref<boolean | null>(null)
</script>

<template>
  <v-card class="adm-bulk-bar mb-4" color="primary" variant="tonal" data-testid="admin-bulk-bar">
    <v-card-text class="d-flex align-center flex-wrap ga-3 py-3">
      <span class="font-weight-medium" data-testid="bulk-count">{{ selectedCount }}개 선택</span>
      <v-select
        v-model="status"
        :items="ADMIN_PRODUCT_BULK_STATUS_OPTIONS"
        label="상태 변경"
        hide-details
        density="compact"
        style="max-width: 240px"
        data-testid="bulk-status"
      />
      <v-btn :disabled="!status || busy" color="primary" data-testid="bulk-status-apply" @click="status && emit('applyStatus', status)">상태 적용</v-btn>
      <v-select
        v-model="soldOut"
        :items="ADMIN_PRODUCT_SOLD_OUT_OPTIONS.map((option) => ({ value: option.value, title: option.value ? '수동 품절 켜기' : '수동 품절 끄기' }))"
        label="품절 변경"
        hide-details
        density="compact"
        style="max-width: 200px"
        data-testid="bulk-soldout"
      />
      <v-btn :disabled="soldOut === null || busy" color="primary" data-testid="bulk-soldout-apply" @click="soldOut !== null && emit('applySoldOut', soldOut)">품절 적용</v-btn>
      <v-spacer />
      <v-btn variant="text" :disabled="busy" data-testid="bulk-clear" @click="emit('clear')">선택 해제</v-btn>
    </v-card-text>
  </v-card>
</template>
