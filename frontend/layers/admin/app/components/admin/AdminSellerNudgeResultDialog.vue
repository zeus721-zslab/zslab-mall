<script setup lang="ts">
import type { AdminSellerNudgeResponse } from '#layers/admin/app/types/admin-seller-delay'
import { SELLER_NUDGE_RESULT_LABELS } from '~/lib/constants/inbox'

// 셀러 지연 일괄 독촉 결과(D-252). 토스트는 집계만 보여주고 셀러별 결과는 여기서 본다(입력 순서).
// titles: 실행 시점 선택 항목의 상호(재조회로 목록에서 빠진 뒤에도 어느 셀러인지 보이게).
defineProps<{ open: boolean; result: AdminSellerNudgeResponse | null; titles: Record<string, string> }>()
const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <v-dialog :model-value="open" max-width="560" @update:model-value="(value: boolean) => !value && emit('close')">
    <v-card data-testid="admin-seller-nudge-result-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">셀러 독촉 결과</v-card-title>
      <v-card-text class="px-5">
        <v-list density="compact" class="pa-0">
          <v-list-item
            v-for="(item, index) in result?.results ?? []"
            :key="`${item.sellerPublicId}-${index}`"
            class="px-0"
            data-testid="seller-nudge-result-item"
          >
            <v-list-item-title>{{ titles[item.sellerPublicId] ?? item.sellerPublicId }}</v-list-item-title>
            <v-list-item-subtitle>{{ SELLER_NUDGE_RESULT_LABELS[item.result] }}</v-list-item-subtitle>
          </v-list-item>
        </v-list>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn color="primary" variant="flat" data-testid="seller-nudge-result-close" @click="emit('close')">닫기</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
