<script setup lang="ts">
import type { AdminClaimBulkApproveResponse } from '#layers/admin/app/types/admin-claim'
import { claimBulkFailureMessage } from '#layers/admin/app/lib/admin-inbox-view'

// 클레임 일괄 승인 결과 상세(D-250). 토스트는 집계만 보여주고 실패 사유는 여기서 본다. 상품 일괄 결과 다이얼로그와 응답 형태가 달라 따로 둔다.
// titles: 승인 실행 시점 선택 항목의 표시 이름(재조회로 목록에서 빠진 뒤에도 무엇이 실패했는지 보이게).
const props = defineProps<{ open: boolean; result: AdminClaimBulkApproveResponse | null; titles: Record<string, string> }>()
const emit = defineEmits<{ close: [] }>()

const failures = computed(() => (props.result?.results ?? []).filter((item) => !item.success))
</script>

<template>
  <v-dialog :model-value="open" max-width="560" @update:model-value="(value: boolean) => !value && emit('close')">
    <v-card data-testid="admin-claim-bulk-result-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">일괄 승인 결과</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-3">
          성공 <strong data-testid="claim-bulk-result-success">{{ result?.successCount ?? 0 }}</strong> ·
          실패 <strong data-testid="claim-bulk-result-failure">{{ result?.failureCount ?? 0 }}</strong>
        </p>
        <v-list v-if="failures.length" density="compact" class="pa-0">
          <v-list-item v-for="(item, index) in failures" :key="`${item.claimPublicId}-${index}`" class="px-0" data-testid="claim-bulk-result-failure-item">
            <v-list-item-title>{{ titles[item.claimPublicId] ?? item.claimPublicId }}</v-list-item-title>
            <v-list-item-subtitle>{{ claimBulkFailureMessage(item.code, item.message) }}</v-list-item-subtitle>
          </v-list-item>
        </v-list>
        <p v-else class="text-body-2 text-medium-emphasis mb-0">실패한 항목이 없습니다.</p>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn color="primary" variant="flat" data-testid="claim-bulk-result-close" @click="emit('close')">닫기</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
