<script setup lang="ts">
import type { AdminProductBulkResponse } from '#layers/admin/app/types/admin-product'
import { toBulkFailureMessage } from '#layers/admin/app/lib/admin-error-message'
import { countEscalated } from '#layers/admin/app/lib/admin-product-view'

// 일괄 결과 상세(FE-25). 스낵바는 집계만 보여주고, 실패 사유 목록은 이 다이얼로그에서 확인한다.
const props = defineProps<{ open: boolean; result: AdminProductBulkResponse | null }>()
const emit = defineEmits<{ close: [] }>()

const failures = computed(() => (props.result?.results ?? []).filter((item) => !item.success))
// D-206 보정: 셀러 중지 → 관리자 중지 전환 건은 성공이지만 상태가 바뀌지 않았으므로 따로 알린다.
const escalated = computed(() => (props.result ? countEscalated(props.result) : 0))
</script>

<template>
  <v-dialog :model-value="open" max-width="560" @update:model-value="(value: boolean) => !value && emit('close')">
    <v-card data-testid="admin-bulk-result-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">일괄 변경 결과</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-3">
          성공 <strong data-testid="bulk-result-success">{{ result?.successCount ?? 0 }}</strong> ·
          실패 <strong data-testid="bulk-result-failure">{{ result?.failureCount ?? 0 }}</strong>
        </p>
        <p v-if="escalated > 0" class="text-body-2 text-medium-emphasis mb-3" data-testid="bulk-result-escalated">
          성공 중 <strong>{{ escalated }}</strong>건은 셀러 중지 상품이라 상태는 그대로 두고 관리자 중지로 전환했습니다(셀러 재판매 불가).
        </p>
        <v-list v-if="failures.length" density="compact" class="pa-0">
          <v-list-item v-for="item in failures" :key="item.productPublicId" class="px-0">
            <v-list-item-title class="adm-product-id">{{ item.productPublicId }}</v-list-item-title>
            <v-list-item-subtitle>{{ toBulkFailureMessage(item.code, item.message) }}</v-list-item-subtitle>
          </v-list-item>
        </v-list>
        <p v-else class="text-body-2 text-medium-emphasis mb-0">실패한 항목이 없습니다.</p>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn color="primary" variant="flat" data-testid="bulk-result-close" @click="emit('close')">닫기</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
