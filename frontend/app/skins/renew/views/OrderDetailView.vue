<script setup lang="ts">
import type { OrderDetailPageVm } from '~/skins/contracts/order-detail'
import type { OrderItem } from '~/types/order'
import { formatPhone } from '~/lib/format/phone'
import { itemConfirmDescription } from '~/lib/constants/order'
import MypageFrame from '../components/MypageFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 주문 상세(FE-73). 섹션 순서는 classic과 같다: 헤더 → 결제하기 → 셀러 그룹별 품목 → 총 결제금액 → 배송지 → 목록 링크.
// 품목 썸네일을 더하고, 구매확정 확인은 인라인 패널 대신 확인 모달(DialogConfirm)을 쓴다 — 확정 상태·함수·testid는 페이지 vm 그대로다.
const props = defineProps<{ vm: OrderDetailPageVm }>()

const confirmTargetItem = computed<OrderItem | null>(() => {
  const targetId = props.vm.confirmTargetId
  if (targetId === null || !props.vm.data) return null
  return props.vm.data.sellers.flatMap((seller) => seller.items).find((item) => item.orderItemId === targetId) ?? null
})

// 확인창 설명 = 대상(FE-79). 닫히는 동안 대상이 비어도 문구가 바뀌어 보이지 않게 열려 있을 때의 값을 붙잡아 둔다.
const confirmDescription = ref('')
watch(confirmTargetItem, (item) => {
  if (item) confirmDescription.value = itemConfirmDescription(item.productName, item.optionLabel)
})

function claimTypesOf(item: OrderItem) {
  return props.vm.claimableTypes(item.status.code, item.exchangeCompleted ?? false)
}
function onConfirmOpenChange(open: boolean): void {
  if (!open && !props.vm.confirming) props.vm.confirmTargetId = null
}
function onConfirm(): void {
  if (confirmTargetItem.value) props.vm.submitConfirm(confirmTargetItem.value)
}

const CARD = 'rounded-[28px] bg-white p-6 md:p-8'
const SMALL_PILL =
  'flex min-h-10 items-center justify-center rounded-full px-4 text-sm font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40'
const PILL = 'flex min-h-11 cursor-pointer items-center gap-2 rounded-full border px-5 text-sm font-bold transition duration-200 has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-primary'
</script>

<template>
  <MypageFrame title="주문 상세" active-to="/orders">
    <!-- 로딩 -->
    <div v-if="vm.pending" class="space-y-4" aria-hidden="true">
      <div :class="[CARD, 'h-28']"></div>
      <div :class="[CARD, 'h-64']"></div>
    </div>

    <!-- 에러 / 없음(404 포함) -->
    <CommonErrorState v-else-if="vm.error || !vm.data" :message="vm.errorMessage" @retry="vm.refresh" />

    <div v-else class="space-y-6">
      <!-- 헤더: 주문번호 + 상태 · 결제 대기/미결제 종료 안내 -->
      <section :class="CARD" aria-label="주문 정보">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <p class="text-xs text-sub">주문번호</p>
            <p class="mt-1 break-all font-mono text-base font-semibold text-ink md:text-lg">{{ vm.data.orderId }}</p>
          </div>
          <span class="shrink-0 rounded-full bg-surface-muted px-3 py-1 text-sm font-bold text-ink">{{ vm.orderStatusLabel(vm.data.status.code) }}</span>
        </div>
        <!-- 결제 대기 안내(FE-53·C-16): 값은 lib/constants/order.ts PAYMENT_EXPIRE_MINUTES(BE 설정과 일치). -->
        <RenewNotice v-if="vm.data.status.code === 'PENDING_PAYMENT'" tone="info" class="mt-5" data-testid="order-payment-expire-guide">
          {{ vm.PAYMENT_EXPIRE_GUIDE }}
        </RenewNotice>
        <!-- 미결제 종료 주문: 결제 버튼 대신 왜 결제할 수 없는지를 말한다. -->
        <RenewNotice v-else-if="vm.isPaymentExpired(vm.data.status.code)" tone="info" class="mt-5" data-testid="order-payment-expired-notice">
          {{ vm.PAYMENT_EXPIRED_NOTICE }}
        </RenewNotice>
      </section>

      <!-- 결제 재개(Track 102 FE-64): 결제대기 주문에서 체크아웃과 같은 결제수단을 고르고 결제창으로 진입한다. -->
      <section v-if="vm.canResumePayment(vm.data.status.code)" :class="CARD" data-testid="order-resume-payment" aria-labelledby="order-resume-title">
        <h2 id="order-resume-title" class="text-lg font-bold text-ink">결제하기</h2>
        <div role="radiogroup" aria-labelledby="order-resume-title" class="mt-5 flex flex-wrap gap-2">
          <label
            v-for="option in vm.PAYMENT_METHODS"
            :key="option.value"
            :class="[PILL, vm.payMethod === option.value ? 'border-ink bg-ink text-white' : 'border-line bg-white text-ink hover:border-ink']"
          >
            <input v-model="vm.payMethod" type="radio" :value="option.value" name="resume-payment-method" class="sr-only" />
            {{ option.label }}
          </label>
        </div>
        <button
          type="button"
          class="mt-6 flex h-14 w-full items-center justify-center gap-2 rounded-full bg-primary text-base font-bold text-primary-foreground transition duration-200 hover:bg-primary-hover focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-primary sm:w-56"
          :disabled="vm.paying"
          data-testid="order-resume-payment-submit"
          @click="vm.submitResumePayment"
        >
          <span v-if="vm.paying" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
          {{ vm.paying ? '결제 준비 중…' : '결제하기' }}
        </button>
        <RenewNotice v-if="vm.payError" tone="danger" class="mt-4" data-testid="order-resume-payment-error">{{ vm.payError }}</RenewNotice>
      </section>

      <!-- 셀러 그룹별 품목 -->
      <section v-for="seller in vm.data.sellers" :key="seller.sellerId" :class="CARD" :aria-label="`${seller.companyName} 주문 품목`">
        <p class="text-sm font-bold text-sub">{{ seller.companyName }}</p>
        <ul class="mt-2 divide-y divide-line">
          <li v-for="item in seller.items" :key="item.orderItemId" class="space-y-3 py-5">
            <div class="flex gap-4">
              <div class="h-20 w-20 shrink-0 overflow-hidden rounded-[18px] bg-(--image-placeholder)">
                <img v-if="item.thumbnailUrl" :src="item.thumbnailUrl" :alt="item.productName ?? '상품 이미지'" class="h-full w-full object-cover" />
              </div>
              <div class="min-w-0 flex-1">
                <div class="flex items-start justify-between gap-4">
                  <div class="min-w-0">
                    <!-- 품목 상태 배지(BE label=code이므로 FE 라벨 매핑). -->
                    <span class="rounded-full bg-surface-muted px-2.5 py-0.5 text-xs font-bold text-ink">{{ vm.orderItemStatusLabel(item.status.code) }}</span>
                    <!-- productName은 표시용 enrich. 삭제 상품(null/부재) 시 방어 문구. -->
                    <p class="mt-1.5 truncate text-sm font-bold text-ink md:text-base" data-testid="item-product-name">{{ item.productName ?? '삭제된 상품' }}</p>
                    <p v-if="item.optionLabel" data-testid="item-option-label" class="truncate text-xs text-sub">{{ item.optionLabel }}</p>
                    <p class="mt-0.5 text-xs text-sub">
                      <span class="font-mono">{{ item.unitPrice.toLocaleString('ko-KR') }}</span>원 · 수량 <span class="font-mono">{{ item.quantity }}</span>
                    </p>
                  </div>
                  <p class="shrink-0 whitespace-nowrap text-ink">
                    <span class="font-mono text-base font-semibold">{{ item.totalPrice.toLocaleString('ko-KR') }}</span><span class="ml-0.5 text-xs">원</span>
                  </p>
                </div>
              </div>
            </div>

            <!-- 배송 정보(FE-54·C-05): 원 발송 송장·발송일·배송완료일 / 발송 전이면 "발송 준비 중". 공용 컴포넌트 그대로. -->
            <OrderItemDeliveryInfo :delivery="item.delivery" :item-status-code="item.status.code" />

            <!-- 구매확정(배송완료만) · 클레임 진입점(품목 상태가 허용하는 유형만) -->
            <div v-if="claimTypesOf(item).length || item.status.code === 'DELIVERED'" class="flex flex-wrap gap-2">
              <button
                v-if="item.status.code === 'DELIVERED'"
                type="button"
                :class="[SMALL_PILL, 'bg-primary text-primary-foreground hover:bg-primary-hover']"
                :disabled="vm.confirming"
                data-testid="item-confirm-purchase"
                @click="vm.openConfirm(item)"
              >
                구매확정
              </button>
              <button
                v-for="type in claimTypesOf(item)"
                :key="type"
                type="button"
                :class="[SMALL_PILL, 'border border-line bg-white text-ink hover:border-ink']"
                :data-testid="`item-claim-${type.toLowerCase()}`"
                @click="vm.goClaim(item, type)"
              >
                {{ vm.claimTypeLabel(type) }} 요청
              </button>
            </div>
            <!-- 배송완료 안내(FE-53·C-16): 값은 lib/constants/order.ts AUTO_CONFIRM_DAYS(BE 설정과 일치). -->
            <p v-if="item.status.code === 'DELIVERED'" class="text-xs text-sub" data-testid="item-auto-confirm-guide">{{ vm.AUTO_CONFIRM_GUIDE }}</p>

            <RenewNotice
              v-if="vm.confirmNotice && vm.confirmNotice.orderItemId === item.orderItemId"
              :tone="vm.confirmNotice.tone === 'error' ? 'danger' : 'success'"
              data-testid="item-confirm-notice"
            >
              {{ vm.confirmNotice.text }}
            </RenewNotice>
          </li>
        </ul>
        <div class="flex items-baseline justify-between border-t border-line pt-4">
          <span class="text-sm text-sub">판매자 소계</span>
          <span class="text-ink"><span class="font-mono text-base font-semibold">{{ seller.subtotal.toLocaleString('ko-KR') }}</span><span class="ml-0.5 text-xs">원</span></span>
        </div>
      </section>

      <!-- 주문 합계 -->
      <section :class="[CARD, 'flex items-baseline justify-between gap-4']" aria-label="총 결제금액">
        <span class="text-base font-bold text-ink">총 결제금액</span>
        <span class="text-ink"><span class="font-mono text-2xl font-semibold">{{ vm.data.totalPrice.toLocaleString('ko-KR') }}</span><span class="ml-0.5 text-sm">원</span></span>
      </section>

      <!-- 배송지: 스냅샷 부재 시 미표시 -->
      <section v-if="vm.data.shippingAddress" :class="CARD" aria-labelledby="order-address-title">
        <h2 id="order-address-title" class="text-lg font-bold text-ink">배송지</h2>
        <dl class="mt-4 space-y-2 text-sm">
          <div class="flex gap-4">
            <dt class="w-14 shrink-0 text-sub">받는 분</dt>
            <dd class="min-w-0 font-bold text-ink">{{ vm.data.shippingAddress.recipientName }}</dd>
          </div>
          <div class="flex gap-4">
            <dt class="w-14 shrink-0 text-sub">연락처</dt>
            <dd class="min-w-0 font-mono text-ink">{{ formatPhone(vm.data.shippingAddress.recipientPhone) }}</dd>
          </div>
          <div class="flex gap-4">
            <dt class="w-14 shrink-0 text-sub">주소</dt>
            <dd class="min-w-0 break-keep text-ink">
              ({{ vm.data.shippingAddress.zonecode }}) {{ vm.data.shippingAddress.addressRoad }}
              <template v-if="vm.data.shippingAddress.addressDetail"> {{ vm.data.shippingAddress.addressDetail }}</template>
            </dd>
          </div>
          <div v-if="vm.data.shippingAddress.deliveryMemo" class="flex gap-4">
            <dt class="w-14 shrink-0 text-sub">메모</dt>
            <dd class="min-w-0 break-keep text-ink">{{ vm.data.shippingAddress.deliveryMemo }}</dd>
          </div>
        </dl>
      </section>

      <!-- 목록으로 -->
      <NuxtLink
        to="/orders"
        class="flex min-h-14 w-full items-center justify-center rounded-full border border-line bg-white text-base font-bold text-ink transition duration-200 hover:border-ink focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary"
      >
        주문 내역으로
      </NuxtLink>
    </div>

    <!-- 구매확정 확인 모달(FE-73): 인라인 확인 패널 대체. 설명 = 대상 · 결과 = 안내(규약 경고, FE-79) · testid는 기존 패널과 같다. -->
    <DialogConfirm
      :open="vm.confirmTargetId !== null"
      title="이 품목을 구매확정할까요?"
      :description="confirmDescription"
      :confirm-label="vm.confirming ? '확정 중…' : '구매 확정하기'"
      :pending="vm.confirming"
      content-test-id="item-confirm-panel"
      notice-test-id="item-confirm-warning"
      cancel-test-id="item-confirm-cancel"
      confirm-test-id="item-confirm-submit"
      @update:open="onConfirmOpenChange"
      @confirm="onConfirm"
    >
      <template #notice>{{ vm.ITEM_CONFIRM_WARNING }}</template>
    </DialogConfirm>
  </MypageFrame>
</template>
