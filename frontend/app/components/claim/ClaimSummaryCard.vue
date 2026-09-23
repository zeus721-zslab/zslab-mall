<script setup lang="ts">
import type { ClaimSummary } from '~/types/claim'
import { CLAIM_REASON_LABELS, claimRejectReasonLabel, claimStatusLabel, claimTypeLabel, refundStatusLabel } from '~/lib/constants/claim'
import { formatDateTime } from '~/lib/utils/datetime'

/** 클레임 목록 카드(FE-63·claims/index.vue 본문 승격). 표시만 담당하고 조회·페이징은 페이지가 가진다. */
defineProps<{ claim: ClaimSummary }>()
</script>

<template>
  <NuxtLink
    :to="`/claims/${claim.publicId}`"
    class="block rounded-card border border-line p-5 transition duration-normal hover:border-gray-300"
    data-testid="claim-card"
  >
    <div class="flex items-start justify-between gap-4">
      <div class="min-w-0">
        <!-- 주문 탭과 나란히 놓이므로 "어느 주문의 무슨 상품인지"를 유형과 함께 보여준다(FE-63·해소 실패 값은 생략). -->
        <p class="truncate text-base font-medium text-ink">
          {{ claimTypeLabel(claim.claimType) }}<template v-if="claim.productName"> · {{ claim.productName }}</template>
        </p>
        <p class="mt-1 text-sm text-sub">
          <template v-if="claim.orderNo">주문 {{ claim.orderNo }} · </template>
          {{ CLAIM_REASON_LABELS[claim.reasonCode] }} · {{ formatDateTime(claim.requestedAt) }}
        </p>
      </div>
      <div class="flex shrink-0 flex-col items-end gap-1">
        <span class="rounded-badge bg-gray-100 px-3 py-1 text-xs font-medium text-ink">
          {{ claimStatusLabel(claim.status) }}
        </span>
        <!-- 거부 사유·환불 상태(FE-28·Track 80): 값이 있을 때만 무채색 보조 배지 -->
        <span v-if="claim.rejectReasonCode" class="rounded-badge border border-line px-2 py-0.5 text-[11px] text-sub" data-testid="claim-reject-reason">
          {{ claimRejectReasonLabel(claim.rejectReasonCode) }}
        </span>
        <span v-if="claim.refundStatus" class="rounded-badge border border-line px-2 py-0.5 text-[11px] text-sub" data-testid="claim-refund-status">
          {{ refundStatusLabel(claim.refundStatus) }}
        </span>
      </div>
    </div>
  </NuxtLink>
</template>
