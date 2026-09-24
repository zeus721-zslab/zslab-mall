<script setup lang="ts">
import type { OrdersPageVm } from '~/skins/contracts/orders'
import type { OrderSummary, OrderSummaryItem } from '~/types/order'
import { X } from '@lucide/vue'
import { ITEM_CONFIRM_TARGET_CAPTION, itemConfirmTarget } from '~/lib/constants/order'
import { ITEM_STATUS_FILTER_PERIOD_MONTHS } from '~/lib/constants/order-tabs'
import { orderSummaryStageLabel } from '~/lib/utils/order-summary-stages'
import MypageFrame from '../components/MypageFrame.vue'
import RenewBadge from '../components/RenewBadge.vue'
import RenewNotice from '../components/RenewNotice.vue'
import { CLAIM_NEUTRAL_CHIP_CLASS, CLAIM_TYPE_BADGE_TONE } from '../claim-type-tone'
import { orderItemStatusTone } from '../order-item-tone'
import { ORDER_NO_CHIP_CLASS } from '../order-no-chip'

// renew 주문 내역(FE-73). 탭 2개(전체 주문 · 취소·반품·교환) + 번호 페이지. 전체 주문은 주문 카드(헤더 + 품목 행),
// 취소·반품·교환은 클레임 카드(누르면 클레임 상세). 품목 행의 구매확정은 확인 모달(DialogConfirm), 클레임은 주문 상세와 같은 경로로 이동한다.
// 배송 조회·요청 상세 버튼은 목록에 두지 않는다(D-223 — 주문 상세·클레임 상세가 담당).
// FE-80: 상태는 품목 행마다 배지로 보인다(주문 상태는 품목 상태 파생이라 카드에 따로 두지 않는다). 품목 상태 필터(D-224)는 전체 주문 탭에만 걸린다.
const props = defineProps<{ vm: OrdersPageVm }>()

// 확인창 설명 = 대상(FE-79 · FE-80 두 줄 "구매 확정할 품목" / 대상). 닫히는 동안 대상이 비어도 문구가 바뀌어 보이지 않게 열려 있을 때의 값을 붙잡아 둔다.
const confirmTargetLabel = ref('')
watch(
  () => props.vm.confirmTarget,
  (target) => {
    if (target) confirmTargetLabel.value = itemConfirmTarget(target.item.productName, target.item.optionLabel)
  },
)

// 빈 목록 문구: 품목 상태 필터(D-224 · 기간 3개월) > 클레임 유형 필터 > 탭 기본 문구.
const emptyText = computed(() => {
  if (props.vm.isOrderTab && props.vm.itemStatusFilter) {
    return `최근 ${ITEM_STATUS_FILTER_PERIOD_MONTHS}개월 동안 ${orderSummaryStageLabel(props.vm.itemStatusFilter)} 품목이 있는 주문이 없어요`
  }
  if (!props.vm.isOrderTab && props.vm.claimTypeFilter) return '해당 유형의 요청이 없습니다'
  return props.vm.emptyMessage
})

// 카드 머리 요약: "상품 N개 · 총 {금액}원". N = 품목 행 수이고, 품목 요약이 없는 응답(옛 캐시)은 개수를 빼고 총액만 쓴다.
function orderItemCount(order: OrderSummary): number | null {
  return order.items === undefined || order.items.length === 0 ? null : order.items.length
}

function claimTypesOf(item: OrderSummaryItem) {
  return props.vm.claimableTypes(item.status.code, item.exchangeCompleted)
}
function hasActions(item: OrderSummaryItem): boolean {
  return item.status.code === 'DELIVERED' || claimTypesOf(item).length > 0
}
function onConfirmOpenChange(open: boolean): void {
  if (!open) props.vm.cancelConfirm()
}

const CARD = 'rounded-card bg-white shadow-e1'
const SKELETON = 'rounded-full bg-surface-muted'
// 품목 액션: ≥768 세로 열(btn-sm 36) · <768 한 줄 최대 2개 균등 폭(44).
const ACTION_BUTTON = 'btn btn-sm w-full max-md:min-h-11'
</script>

<template>
  <MypageFrame title="주문 내역">
    <!-- 탭(FE-73): 전체 주문 · 취소·반품·교환. 세그먼트 = 흰 바탕 + 선택 primary 채움(FE-80). 현재 탭은 aria-current(classic과 같은 관례). -->
    <nav aria-label="주문 내역 구분" data-testid="order-tabs" class="mb-6">
      <ul class="inline-flex gap-1 rounded-full bg-white p-1 shadow-e1">
        <li v-for="item in vm.ORDER_LIST_TABS" :key="item">
          <button
            type="button"
            :class="[
              'min-h-11 whitespace-nowrap rounded-full px-5 text-small font-semibold transition duration-fast ease-soft focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary',
              item === vm.tab ? 'bg-primary text-primary-foreground' : 'text-sub hover:text-ink',
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
          <button type="button" class="chip" :aria-pressed="type === vm.claimTypeFilter" @click="vm.moveToClaimType(type)">
            {{ type === null ? '전체' : vm.claimTypeLabel(type) }}
          </button>
        </li>
      </ul>
    </nav>

    <!-- 품목 상태 필터(D-224·FE-80): 마이페이지 홈 주문 현황 숫자에서 들어온다. 전체 주문 탭에만 걸리고 칩 안 버튼으로 해제한다. -->
    <div v-if="vm.isOrderTab && vm.itemStatusFilter" class="-mt-2 mb-6 flex" data-testid="order-item-status-filter">
      <!-- 해제 버튼은 칩 오른쪽 여백 안으로 당겨 붙인다(칩 유틸 padding과 겹치는 pr-* 대신 음수 margin). -->
      <span class="chip" data-state="on">
        <span class="tabular-nums">{{ orderSummaryStageLabel(vm.itemStatusFilter) }} 품목 · 최근 {{ ITEM_STATUS_FILTER_PERIOD_MONTHS }}개월</span>
        <button
          type="button"
          class="-mr-3 flex h-11 w-11 items-center justify-center rounded-full transition duration-fast ease-soft hover:bg-white/15 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--ring) md:-mr-2.5 md:h-8 md:w-8"
          aria-label="필터 해제"
          data-testid="order-item-status-filter-clear"
          @click="vm.clearItemStatusFilter"
        >
          <X class="h-4 w-4" aria-hidden="true" />
        </button>
      </span>
    </div>

    <!-- 로딩 -->
    <div v-if="vm.pending" class="space-y-4" aria-hidden="true">
      <div v-for="index in 3" :key="index" :class="[CARD, 'space-y-4 p-5 md:p-6']">
        <div :class="[SKELETON, 'h-4 w-1/3']"></div>
        <div class="flex gap-4">
          <div class="h-20 w-20 shrink-0 rounded-[18px] bg-(--image-placeholder)"></div>
          <div class="flex-1 space-y-2 pt-1">
            <div :class="[SKELETON, 'h-3 w-1/4']"></div>
            <div :class="[SKELETON, 'h-4 w-2/3']"></div>
          </div>
        </div>
      </div>
    </div>

    <!-- 에러 -->
    <CommonErrorState v-else-if="vm.error" :message="vm.errorMessage" @retry="vm.retry" />

    <!-- 빈 목록 -->
    <div v-else-if="vm.isEmpty" :class="[CARD, 'flex flex-col items-center px-6 py-16 text-center']" data-testid="order-list-empty">
      <span class="flex h-16 w-16 items-center justify-center rounded-full bg-surface-muted text-primary" aria-hidden="true">
        <svg class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.6">
          <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z" />
        </svg>
      </span>
      <p class="mt-5 break-keep text-h3 text-ink">{{ emptyText }}</p>
      <!-- 필터 결과가 비면 쇼핑 대신 필터 해제를 권한다(보조 · 주 버튼은 두지 않는다). -->
      <button
        v-if="vm.isOrderTab && vm.itemStatusFilter"
        type="button"
        class="btn btn-secondary btn-md mt-5"
        data-testid="order-empty-filter-clear"
        @click="vm.clearItemStatusFilter"
      >
        필터 해제
      </button>
      <NuxtLink v-else-if="vm.isOrderTab" to="/products" class="btn btn-primary btn-md mt-5">쇼핑하러 가기</NuxtLink>
    </div>

    <template v-else>
      <!-- 전체 주문: 주문 카드 -->
      <ul v-if="vm.isOrderTab && vm.orders" class="space-y-4">
        <li v-for="order in vm.orders.items" :key="order.orderId" :class="CARD" data-testid="order-card">
          <!-- 머리: ≥768 한 줄(날짜 · 주문번호 · 상품 N개·총액 · 버튼) / <768 1줄 날짜 + 버튼 · 2줄 주문번호 + 개수·총액 -->
          <div
            class="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1 border-b border-line px-5 py-3 md:flex md:flex-wrap md:gap-x-4 md:px-6 md:py-4"
          >
            <p class="text-small font-semibold tabular-nums text-ink">{{ vm.formatDateTime(order.orderedAt) }}</p>
            <div class="col-span-2 row-start-2 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
              <!-- 주문번호 = 사람이 읽는 orderNo(모노 칩) · 없는 옛 응답이면 생략(내부 id 노출 금지 · Track 105-4g-3) -->
              <span v-if="order.orderNo" :class="ORDER_NO_CHIP_CLASS" data-testid="order-card-order-id">{{ order.orderNo }}</span>
              <span class="whitespace-nowrap text-small text-sub" data-testid="order-card-summary">
                <template v-if="orderItemCount(order) !== null">상품 <span class="tabular-nums">{{ orderItemCount(order) }}</span>개 · </template>총
                <span class="font-semibold tabular-nums text-ink">{{ order.totalPrice.toLocaleString('ko-KR') }}</span>원
              </span>
            </div>
            <div class="col-start-2 row-start-1 flex items-center gap-1 md:ml-auto">
              <!-- 결제 재개 진입점(Track 102 FE-64): 결제대기 주문만 상세의 결제 영역으로 보낸다. -->
              <NuxtLink
                v-if="vm.canResumePayment(order.status.code)"
                :to="`/orders/${order.orderId}`"
                class="btn btn-primary btn-sm max-md:min-h-11"
                data-testid="order-card-resume-payment"
              >
                결제하기
              </NuxtLink>
              <NuxtLink :to="`/orders/${order.orderId}`" class="btn btn-tertiary btn-sm max-md:min-h-11" aria-label="주문 상세" data-testid="order-card-detail">
                <span class="md:hidden">상세</span><span class="max-md:hidden">주문 상세</span>
                <svg class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
                </svg>
              </NuxtLink>
            </div>
          </div>

          <!-- 진행 중 클레임 배지(FE-63): 유형 색(FE-73 보완 1)·누르면 취소·반품·교환 탭의 해당 유형 -->
          <div v-if="vm.toActiveClaimBadges(order.activeClaims).length > 0" class="flex flex-wrap gap-1.5 px-5 pt-4 md:px-6" data-testid="order-claim-badges">
            <template v-for="badge in vm.toActiveClaimBadges(order.activeClaims)" :key="badge.label">
              <NuxtLink
                v-if="badge.tab && badge.claimType"
                :to="{ path: '/orders', query: { tab: badge.tab, type: vm.CLAIM_TYPE_QUERY_VALUES[badge.claimType] } }"
                class="inline-flex items-center rounded-full transition-opacity duration-fast ease-soft hover:opacity-80 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary max-md:min-h-11"
              >
                <RenewBadge :tone="CLAIM_TYPE_BADGE_TONE[badge.claimType]">진행 중 {{ badge.label }}</RenewBadge>
              </NuxtLink>
              <span v-else :class="['self-center', CLAIM_NEUTRAL_CHIP_CLASS]">{{ badge.label }}</span>
            </template>
          </div>

          <!-- 품목 행: ≥768 [썸네일+정보 = 상품 링크] [금액 열] [액션 세로 열] / <768 정보 아래 금액 · 버튼 줄 -->
          <ul v-if="order.items && order.items.length > 0" class="divide-y divide-line px-5 md:px-6">
            <li v-for="item in order.items" :key="item.orderItemId" class="py-5" data-testid="order-item-row">
              <div class="md:flex md:items-center md:gap-6">
                <!-- 상품 링크는 이름에 걸고 영역 전체로 넓힌다(삭제 상품 = productId 없음 → 링크 없음). -->
                <div class="group relative flex min-w-0 flex-1 gap-4 rounded-2xl">
                  <div class="h-20 w-20 shrink-0 overflow-hidden rounded-[18px] bg-(--image-placeholder)">
                    <img
                      v-if="item.thumbnailUrl"
                      :src="item.thumbnailUrl"
                      :alt="item.productName ?? '상품 이미지'"
                      loading="lazy"
                      class="h-full w-full object-cover transition duration-fast ease-soft motion-safe:group-hover:scale-[1.04]"
                    />
                  </div>
                  <div class="min-w-0 flex-1">
                    <div class="flex flex-wrap items-center gap-2">
                      <RenewBadge :tone="orderItemStatusTone(item.status.code)" data-testid="order-item-status">{{ vm.orderItemStatusLabel(item.status.code) }}</RenewBadge>
                      <span v-if="item.sellerName" class="truncate text-caption font-normal text-sub">{{ item.sellerName }}</span>
                    </div>
                    <p class="mt-1.5 truncate text-body font-semibold text-ink">
                      <NuxtLink
                        v-if="item.productId"
                        :to="`/products/${item.productId}`"
                        class="after:absolute after:inset-0 after:rounded-2xl group-hover:underline focus-visible:outline-hidden focus-visible:after:ring-2 focus-visible:after:ring-primary"
                        data-testid="order-item-product-link"
                      >
                        {{ item.productName ?? '삭제된 상품' }}
                      </NuxtLink>
                      <template v-else>{{ item.productName ?? '삭제된 상품' }}</template>
                    </p>
                    <p class="mt-0.5 truncate text-small font-normal text-sub">
                      <template v-if="item.optionLabel">{{ item.optionLabel }} · </template>수량 <span class="tabular-nums">{{ item.quantity }}</span>개
                    </p>
                  </div>
                </div>
                <p class="mt-2 whitespace-nowrap pl-24 text-ink md:mt-0 md:w-28 md:shrink-0 md:pl-0 md:text-right">
                  <span class="text-h3 tabular-nums">{{ item.totalPrice.toLocaleString('ko-KR') }}</span><span class="ml-0.5 text-small">원</span>
                </p>
                <!-- 주 버튼은 구매확정 하나(배송 완료)이고, 클레임 요청은 보조다(취소 요청만 있는 품목도 보조).
                     ≥768은 버튼이 없어도 열 폭을 남겨 행마다 금액 열 위치를 맞춘다. -->
                <div :class="[hasActions(item) ? 'mt-3 grid grid-cols-2 gap-2' : 'hidden', 'md:mt-0 md:flex md:w-36 md:shrink-0 md:flex-col md:gap-2']">
                  <button
                    v-if="item.status.code === 'DELIVERED'"
                    type="button"
                    :class="[ACTION_BUTTON, 'btn-primary']"
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
                    :class="[ACTION_BUTTON, 'btn-secondary']"
                    :data-testid="`order-item-claim-${type.toLowerCase()}`"
                    @click="vm.goClaim(item, type)"
                  >
                    {{ vm.claimTypeLabel(type) }} 요청
                  </button>
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
          <p v-else class="truncate px-5 py-5 text-body font-semibold text-ink md:px-6">{{ order.previewTitle }}</p>
        </li>
      </ul>

      <!-- 취소·반품·교환: 클레임 카드(누르면 클레임 상세) · 썸네일(D-224 thumbnailUrl · 없으면 이미지 대기 면) -->
      <ul v-else-if="vm.claims" class="space-y-4">
        <li v-for="claim in vm.claims.items" :key="claim.publicId">
          <NuxtLink
            :to="`/claims/${claim.publicId}`"
            :class="[CARD, 'group flex items-center gap-4 px-5 py-5 transition duration-fast ease-soft hover:shadow-e2 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary motion-safe:hover:-translate-y-1 md:px-6']"
            data-testid="claim-card"
          >
            <div class="h-16 w-16 shrink-0 overflow-hidden rounded-[14px] bg-(--image-placeholder)" data-testid="claim-card-thumbnail">
              <img v-if="claim.thumbnailUrl" :src="claim.thumbnailUrl" :alt="claim.productName ?? '상품 이미지'" loading="lazy" class="h-full w-full object-cover" />
            </div>
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <!-- 유형 = 색 배지 · 상태·거부 사유·환불 상태 = 중립 칩(FE-73 보완 1) -->
                <RenewBadge :tone="CLAIM_TYPE_BADGE_TONE[claim.claimType]" data-testid="claim-type-badge">{{ vm.claimTypeLabel(claim.claimType) }}</RenewBadge>
                <span :class="CLAIM_NEUTRAL_CHIP_CLASS">{{ vm.claimStatusLabel(claim.status) }}</span>
                <!-- 거부 사유·환불 상태(FE-28·Track 80): 값이 있을 때만 보조 배지 -->
                <span v-if="claim.rejectReasonCode" :class="CLAIM_NEUTRAL_CHIP_CLASS" data-testid="claim-reject-reason">
                  {{ vm.claimRejectReasonLabel(claim.rejectReasonCode) }}
                </span>
                <span v-if="claim.refundStatus" :class="CLAIM_NEUTRAL_CHIP_CLASS" data-testid="claim-refund-status">
                  {{ vm.refundStatusLabel(claim.refundStatus) }}
                </span>
              </div>
              <p class="mt-2 truncate text-body font-semibold text-ink">{{ claim.productName ?? '주문 품목' }}</p>
              <p class="mt-1 truncate text-caption font-normal text-sub">
                요청일 <span class="tabular-nums">{{ vm.formatDateTime(claim.requestedAt) }}</span> · {{ vm.CLAIM_REASON_LABELS[claim.reasonCode] }}
                <template v-if="claim.orderNo"> · 주문 {{ claim.orderNo }}</template>
              </p>
            </div>
            <svg class="h-5 w-5 shrink-0 text-sub transition-transform duration-fast ease-soft motion-safe:group-hover:translate-x-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
            </svg>
          </NuxtLink>
        </li>
      </ul>

      <!-- 번호 페이지: hasNext 기준 이전/다음(누적 아님·page 왕복). 탭이 달라도 같은 페이저를 쓴다. 쿼리(필터)는 이어받는다. -->
      <div class="mt-8 flex items-center justify-center gap-3">
        <button type="button" class="btn btn-secondary btn-md" :disabled="vm.page === 0" @click="vm.moveTo(vm.tab, Math.max(vm.page - 1, 0))">이전</button>
        <span class="min-w-8 text-center text-body font-semibold tabular-nums text-ink">{{ vm.page + 1 }}</span>
        <button type="button" class="btn btn-secondary btn-md" :disabled="!vm.hasNext" @click="vm.moveTo(vm.tab, vm.page + 1)">다음</button>
      </div>
    </template>

    <!-- 구매확정 확인 모달(FE-73): 설명 = 대상 · 결과 = 안내(위험 조작 문구 규약의 경고 · FE-79). testid는 주문 상세의 확정 패널과 같다. -->
    <DialogConfirm
      :open="vm.confirmTarget !== null"
      title="이 품목을 구매확정할까요?"
      :confirm-label="vm.confirming ? '확정 중…' : '구매 확정하기'"
      :pending="vm.confirming"
      content-test-id="item-confirm-panel"
      notice-test-id="item-confirm-warning"
      cancel-test-id="item-confirm-cancel"
      confirm-test-id="item-confirm-submit"
      @update:open="onConfirmOpenChange"
      @confirm="vm.submitConfirm"
    >
      <template #description>
        <span class="block text-small font-normal text-sub">{{ ITEM_CONFIRM_TARGET_CAPTION }}</span>
        <span class="mt-1 block text-body font-semibold text-ink" data-testid="item-confirm-target">{{ confirmTargetLabel }}</span>
      </template>
      <template #notice>{{ vm.ITEM_CONFIRM_WARNING }}</template>
    </DialogConfirm>
  </MypageFrame>
</template>
