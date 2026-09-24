<script setup lang="ts">
import type { OrdersPageVm } from '~/skins/contracts/orders'
import type { OrderSummaryItem } from '~/types/order'
import MypageFrame from '../components/MypageFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'
import { CLAIM_NEUTRAL_CHIP_CLASS, CLAIM_TYPE_BADGE_CLASS } from '../claim-type-tone'

// renew 주문 내역(FE-73). 탭 2개(전체 주문 · 취소·반품·교환) + 번호 페이지. 전체 주문은 주문 카드(헤더 + 품목 행),
// 취소·반품·교환은 클레임 카드(누르면 클레임 상세). 품목 행의 구매확정은 확인 모달(DialogConfirm), 클레임은 주문 상세와 같은 경로로 이동한다.
// 배송 조회·요청 상세 버튼은 목록에 두지 않는다(D-223 — 주문 상세·클레임 상세가 담당).
const props = defineProps<{ vm: OrdersPageVm }>()

function claimTypesOf(item: OrderSummaryItem) {
  return props.vm.claimableTypes(item.status.code, item.exchangeCompleted)
}
function hasActions(item: OrderSummaryItem): boolean {
  return item.status.code === 'DELIVERED' || claimTypesOf(item).length > 0
}
function onConfirmOpenChange(open: boolean): void {
  if (!open) props.vm.cancelConfirm()
}

const CARD = 'rounded-[28px] bg-white'
const ENTER = 'motion-safe:transition motion-safe:duration-300 motion-safe:ease-out motion-safe:starting:translate-y-1.5 motion-safe:starting:opacity-0'
const SMALL_PILL =
  'flex min-h-10 items-center justify-center rounded-full px-4 text-sm font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40'
const PAGE_BUTTON =
  'flex h-11 min-w-11 items-center justify-center rounded-full border border-line bg-white px-4 text-sm font-bold text-ink transition duration-200 hover:border-ink disabled:cursor-default disabled:opacity-40 disabled:hover:border-line'
</script>

<template>
  <MypageFrame title="주문 내역">
    <!-- 탭(FE-73): 전체 주문 · 취소·반품·교환. 현재 탭은 aria-current(classic과 같은 관례). -->
    <nav aria-label="주문 내역 구분" data-testid="order-tabs" class="mb-6">
      <ul class="inline-flex gap-1 rounded-full bg-white p-1">
        <li v-for="item in vm.ORDER_LIST_TABS" :key="item">
          <button
            type="button"
            :class="[
              'min-h-11 whitespace-nowrap rounded-full px-5 text-sm font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary',
              item === vm.tab ? 'bg-ink text-white' : 'text-sub hover:text-ink',
            ]"
            :aria-current="item === vm.tab ? 'page' : undefined"
            @click="vm.moveTo(item, 0)"
          >
            {{ vm.ORDER_LIST_TAB_LABELS[item] }}
          </button>
        </li>
      </ul>
    </nav>

    <!-- 취소·반품·교환 탭 유형 필터 칩(FE-73 보완 1): 전체 · 취소 · 반품 · 교환. 바꾸면 첫 페이지. -->
    <nav v-if="!vm.isOrderTab" aria-label="요청 유형" data-testid="claim-type-filter" class="-mt-2 mb-6">
      <ul class="flex flex-wrap gap-2">
        <li v-for="type in vm.CLAIM_TYPE_FILTERS" :key="type ?? 'ALL'">
          <button
            type="button"
            :class="[
              'min-h-10 rounded-full px-4 text-sm font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary',
              type === vm.claimTypeFilter ? 'bg-ink text-white' : 'border border-line bg-white text-sub hover:border-ink hover:text-ink',
            ]"
            :aria-pressed="type === vm.claimTypeFilter"
            @click="vm.moveToClaimType(type)"
          >
            {{ type === null ? '전체' : vm.claimTypeLabel(type) }}
          </button>
        </li>
      </ul>
    </nav>

    <!-- 로딩 -->
    <div v-if="vm.pending" class="space-y-4" aria-hidden="true">
      <div v-for="index in 3" :key="index" :class="[CARD, 'space-y-4 p-6 md:p-8']">
        <div class="h-4 w-1/3 rounded-full bg-surface-muted"></div>
        <div class="flex gap-4">
          <div class="h-20 w-20 shrink-0 rounded-[18px] bg-(--image-placeholder)"></div>
          <div class="flex-1 space-y-2 pt-1">
            <div class="h-3 w-1/4 rounded-full bg-surface-muted"></div>
            <div class="h-4 w-2/3 rounded-full bg-surface-muted"></div>
          </div>
        </div>
      </div>
    </div>

    <!-- 에러 -->
    <CommonErrorState v-else-if="vm.error" :message="vm.errorMessage" @retry="vm.retry" />

    <!-- 빈 목록 -->
    <div v-else-if="vm.isEmpty" :class="[CARD, ENTER, 'flex flex-col items-center px-6 py-16 text-center']">
      <span class="flex h-16 w-16 items-center justify-center rounded-full bg-surface-muted text-primary" aria-hidden="true">
        <svg class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.6">
          <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z" />
        </svg>
      </span>
      <p class="mt-5 text-base font-bold text-ink">{{ !vm.isOrderTab && vm.claimTypeFilter ? '해당 유형의 요청이 없습니다' : vm.emptyMessage }}</p>
      <NuxtLink
        v-if="vm.isOrderTab"
        to="/products"
        :class="[SMALL_PILL, 'mt-5 min-h-11 bg-primary px-6 text-primary-foreground hover:bg-primary-hover']"
      >
        쇼핑하러 가기
      </NuxtLink>
    </div>

    <template v-else>
      <!-- 전체 주문: 주문 카드 -->
      <ul v-if="vm.isOrderTab && vm.orders" :class="[ENTER, 'space-y-4']">
        <li v-for="order in vm.orders.items" :key="order.orderId" :class="CARD" data-testid="order-card">
          <!-- 헤더: 주문일 · 주문번호 · (결제대기) 결제하기 · 주문 상세 -->
          <div class="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line px-6 py-4 md:px-8">
            <p class="text-sm font-bold text-ink">{{ vm.formatDateTime(order.orderedAt) }}</p>
            <p class="min-w-0 truncate font-mono text-xs text-sub">{{ order.orderId }}</p>
            <div class="ml-auto flex items-center gap-1">
              <!-- 결제 재개 진입점(Track 102 FE-64): 결제대기 주문만 상세의 결제 영역으로 보낸다. -->
              <NuxtLink
                v-if="vm.canResumePayment(order.status.code)"
                :to="`/orders/${order.orderId}`"
                :class="[SMALL_PILL, 'bg-primary text-primary-foreground hover:bg-primary-hover']"
                data-testid="order-card-resume-payment"
              >
                결제하기
              </NuxtLink>
              <NuxtLink
                :to="`/orders/${order.orderId}`"
                class="group flex min-h-10 items-center gap-0.5 rounded-full px-3 text-sm font-bold text-sub transition duration-200 hover:bg-surface-muted hover:text-ink"
              >
                주문 상세
                <svg class="h-4 w-4 transition-transform duration-200 motion-safe:group-hover:translate-x-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
                </svg>
              </NuxtLink>
            </div>
          </div>

          <!-- 진행 중 클레임 배지(FE-63): 유형 색(FE-73 보완 1)·누르면 취소·반품·교환 탭의 해당 유형 -->
          <div v-if="vm.toActiveClaimBadges(order.activeClaims).length > 0" class="flex flex-wrap gap-1.5 px-6 pt-4 md:px-8" data-testid="order-claim-badges">
            <template v-for="badge in vm.toActiveClaimBadges(order.activeClaims)" :key="badge.label">
              <NuxtLink
                v-if="badge.tab && badge.claimType"
                :to="{ path: '/orders', query: { tab: badge.tab, type: vm.CLAIM_TYPE_QUERY_VALUES[badge.claimType] } }"
                :class="['rounded-full px-3 py-1 text-xs font-bold transition duration-200 hover:opacity-80', CLAIM_TYPE_BADGE_CLASS[badge.claimType]]"
              >
                진행 중 {{ badge.label }}
              </NuxtLink>
              <span v-else class="rounded-full border border-line px-3 py-1 text-xs font-bold text-sub">{{ badge.label }}</span>
            </template>
          </div>

          <!-- 품목 행 -->
          <ul v-if="order.items && order.items.length > 0" class="divide-y divide-line px-6 md:px-8">
            <li v-for="item in order.items" :key="item.orderItemId" class="py-5" data-testid="order-item-row">
              <div class="flex gap-4">
                <div class="h-20 w-20 shrink-0 overflow-hidden rounded-[18px] bg-(--image-placeholder)">
                  <img v-if="item.thumbnailUrl" :src="item.thumbnailUrl" :alt="item.productName ?? '상품 이미지'" class="h-full w-full object-cover" />
                </div>
                <div class="min-w-0 flex-1 md:flex md:items-center md:gap-6">
                  <div class="min-w-0 flex-1">
                    <div class="flex flex-wrap items-center gap-2">
                      <span class="rounded-full bg-surface-muted px-2.5 py-0.5 text-xs font-bold text-ink">{{ vm.orderItemStatusLabel(item.status.code) }}</span>
                      <span v-if="item.sellerName" class="truncate text-xs text-sub">{{ item.sellerName }}</span>
                    </div>
                    <p class="mt-1.5 truncate text-sm font-bold text-ink md:text-base">{{ item.productName ?? '삭제된 상품' }}</p>
                    <p class="mt-0.5 truncate text-xs text-sub">
                      <template v-if="item.optionLabel">{{ item.optionLabel }} · </template>수량 <span class="font-mono">{{ item.quantity }}</span>개
                    </p>
                  </div>
                  <p class="mt-2 whitespace-nowrap text-ink md:mt-0">
                    <span class="font-mono text-base font-semibold">{{ item.totalPrice.toLocaleString('ko-KR') }}</span><span class="ml-0.5 text-xs">원</span>
                  </p>
                  <div v-if="hasActions(item)" class="mt-3 flex flex-wrap gap-2 md:mt-0 md:w-56 md:shrink-0 md:justify-end">
                    <button
                      v-if="item.status.code === 'DELIVERED'"
                      type="button"
                      :class="[SMALL_PILL, 'bg-primary text-primary-foreground hover:bg-primary-hover']"
                      :disabled="vm.confirming"
                      data-testid="order-item-confirm-purchase"
                      @click="vm.openConfirm(order, item)"
                    >
                      구매확정
                    </button>
                    <button
                      v-for="type in claimTypesOf(item)"
                      :key="type"
                      type="button"
                      :class="[SMALL_PILL, 'border border-line bg-white text-ink hover:border-ink']"
                      :data-testid="`order-item-claim-${type.toLowerCase()}`"
                      @click="vm.goClaim(item, type)"
                    >
                      {{ vm.claimTypeLabel(type) }} 요청
                    </button>
                  </div>
                </div>
              </div>
              <RenewNotice
                v-if="vm.confirmNotice && vm.confirmNotice.orderItemId === item.orderItemId"
                :tone="vm.confirmNotice.tone === 'error' ? 'danger' : 'success'"
                class="mt-4"
                data-testid="order-item-confirm-notice"
              >
                {{ vm.confirmNotice.text }}
              </RenewNotice>
            </li>
          </ul>
          <!-- 품목 요약이 없는 응답(옛 캐시 등)은 대표 상품명 한 줄로 대체 -->
          <p v-else class="truncate px-6 py-5 text-base font-bold text-ink md:px-8">{{ order.previewTitle }}</p>
        </li>
      </ul>

      <!-- 취소·반품·교환: 클레임 카드(누르면 클레임 상세) -->
      <ul v-else-if="vm.claims" :class="[ENTER, 'space-y-4']">
        <li v-for="claim in vm.claims.items" :key="claim.publicId">
          <NuxtLink
            :to="`/claims/${claim.publicId}`"
            :class="[CARD, 'group flex items-center gap-4 px-6 py-5 transition duration-300 ease-out hover:shadow-[0_16px_32px_-20px_rgba(34,31,43,0.35)] focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary motion-safe:hover:-translate-y-0.5 md:px-8']"
            data-testid="claim-card"
          >
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <!-- 유형 = 색 배지 · 상태·거부 사유·환불 상태 = 중립 칩(FE-73 보완 1) -->
                <span :class="['rounded-full px-2.5 py-0.5 text-xs font-bold', CLAIM_TYPE_BADGE_CLASS[claim.claimType]]" data-testid="claim-type-badge">{{ vm.claimTypeLabel(claim.claimType) }}</span>
                <span :class="['rounded-full px-2.5 py-0.5 text-xs font-bold', CLAIM_NEUTRAL_CHIP_CLASS]">{{ vm.claimStatusLabel(claim.status) }}</span>
                <!-- 거부 사유·환불 상태(FE-28·Track 80): 값이 있을 때만 보조 배지 -->
                <span v-if="claim.rejectReasonCode" :class="['rounded-full px-2.5 py-0.5 text-xs', CLAIM_NEUTRAL_CHIP_CLASS]" data-testid="claim-reject-reason">
                  {{ vm.claimRejectReasonLabel(claim.rejectReasonCode) }}
                </span>
                <span v-if="claim.refundStatus" :class="['rounded-full px-2.5 py-0.5 text-xs', CLAIM_NEUTRAL_CHIP_CLASS]" data-testid="claim-refund-status">
                  {{ vm.refundStatusLabel(claim.refundStatus) }}
                </span>
              </div>
              <p class="mt-2 truncate text-base font-bold text-ink">{{ claim.productName ?? '주문 품목' }}</p>
              <p class="mt-1 truncate text-xs text-sub">
                요청일 {{ vm.formatDateTime(claim.requestedAt) }} · {{ vm.CLAIM_REASON_LABELS[claim.reasonCode] }}
                <template v-if="claim.orderNo"> · 주문 <span class="font-mono">{{ claim.orderNo }}</span></template>
              </p>
            </div>
            <svg class="h-5 w-5 shrink-0 text-sub transition-transform duration-200 motion-safe:group-hover:translate-x-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
            </svg>
          </NuxtLink>
        </li>
      </ul>

      <!-- 번호 페이지: hasNext 기준 이전/다음(누적 아님·page 왕복). 탭이 달라도 같은 페이저를 쓴다. -->
      <div class="mt-8 flex items-center justify-center gap-3">
        <button type="button" :class="PAGE_BUTTON" :disabled="vm.page === 0" @click="vm.moveTo(vm.tab, Math.max(vm.page - 1, 0))">이전</button>
        <span class="min-w-8 text-center font-mono text-sm font-semibold text-ink">{{ vm.page + 1 }}</span>
        <button type="button" :class="PAGE_BUTTON" :disabled="!vm.hasNext" @click="vm.moveTo(vm.tab, vm.page + 1)">다음</button>
      </div>
    </template>

    <!-- 구매확정 확인 모달(FE-73): 설명은 위험 조작 문구 규약의 경고(되돌릴 수 없음). testid는 주문 상세의 확정 패널과 같다. -->
    <DialogConfirm
      :open="vm.confirmTarget !== null"
      title="이 품목을 구매확정할까요?"
      :description="vm.ITEM_CONFIRM_WARNING"
      :confirm-label="vm.confirming ? '확정 중…' : '구매확정'"
      destructive
      :pending="vm.confirming"
      content-test-id="item-confirm-panel"
      description-test-id="item-confirm-warning"
      cancel-test-id="item-confirm-cancel"
      confirm-test-id="item-confirm-submit"
      @update:open="onConfirmOpenChange"
      @confirm="vm.submitConfirm"
    />
  </MypageFrame>
</template>
