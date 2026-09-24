<script setup lang="ts">
import type { OrderDetailPageVm } from '~/skins/contracts/order-detail'

defineProps<{ vm: OrderDetailPageVm }>()
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[880px] px-4 md:px-6">
      <!-- 로딩 -->
      <div v-if="vm.pending" class="space-y-4">
        <div class="h-8 w-2/3 animate-pulse rounded bg-gray-100"></div>
        <div class="h-40 animate-pulse rounded-card bg-gray-100"></div>
      </div>

      <!-- 에러 / 없음(404 포함) -->
      <CommonErrorState v-else-if="vm.error || !vm.data" :message="vm.errorMessage" @retry="vm.refresh" />

      <!-- 상세 -->
      <template v-else>
        <!-- 헤더: 주문번호 + 상태 -->
        <div class="mb-6 flex items-start justify-between gap-4">
          <div class="min-w-0">
            <p class="text-sm text-sub">주문번호</p>
            <h1 class="mt-1 break-all font-mono text-lg font-medium text-ink">{{ vm.data.orderId }}</h1>
          </div>
          <span class="shrink-0 rounded-badge bg-gray-100 px-3 py-1 text-sm font-medium text-ink">
            {{ vm.orderStatusLabel(vm.data.status.code) }}
          </span>
        </div>
        <!-- 결제 대기 안내(FE-53·C-16): 값은 lib/constants/order.ts PAYMENT_EXPIRE_MINUTES(BE 설정과 일치). -->
        <p v-if="vm.data.status.code === 'PENDING_PAYMENT'" class="-mt-3 mb-6 text-sm text-sub" data-testid="order-payment-expire-guide">
          {{ vm.PAYMENT_EXPIRE_GUIDE }}
        </p>

        <!--
          결제 재개(Track 102 FE-64): 결제대기 주문에서 결제를 다시 시작한다. BE 재결제(D-60)가 결제수단을 따로 받으므로
          체크아웃과 같은 선택지를 그대로 보여주고, 이동은 체크아웃과 같은 resolvePaymentRedirect 경로를 쓴다.
        -->
        <section v-if="vm.canResumePayment(vm.data.status.code)" class="mb-6 rounded-card border border-line p-5" data-testid="order-resume-payment">
          <h2 class="mb-3 text-base font-semibold text-ink">결제하기</h2>
          <div class="flex flex-wrap gap-2">
            <label
              v-for="option in vm.PAYMENT_METHODS"
              :key="option.value"
              class="flex cursor-pointer items-center gap-2 rounded-card border border-line px-3 py-2 text-sm text-ink"
            >
              <input v-model="vm.payMethod" type="radio" :value="option.value" name="resume-payment-method" class="h-4 w-4" />
              {{ option.label }}
            </label>
          </div>
          <Button class="mt-4" :disabled="vm.paying" data-testid="order-resume-payment-submit" @click="vm.submitResumePayment">
            {{ vm.paying ? '결제 준비 중…' : '결제하기' }}
          </Button>
          <p v-if="vm.payError" role="alert" class="mt-2 text-sm text-soldout" data-testid="order-resume-payment-error">{{ vm.payError }}</p>
        </section>

        <!-- 미결제 종료 주문: 결제 버튼 대신 왜 결제할 수 없는지를 말한다. -->
        <p v-else-if="vm.isPaymentExpired(vm.data.status.code)" class="-mt-3 mb-6 text-sm text-sub" data-testid="order-payment-expired-notice">
          {{ vm.PAYMENT_EXPIRED_NOTICE }}
        </p>

        <!-- seller 그룹별 품목 -->
        <section class="space-y-4">
          <div
            v-for="seller in vm.data.sellers"
            :key="seller.sellerId"
            class="rounded-card border border-line p-5"
          >
            <p class="mb-3 text-sm font-semibold text-seller">{{ seller.companyName }}</p>

            <ul class="space-y-3">
              <li
                v-for="item in seller.items"
                :key="item.orderItemId"
                class="space-y-2"
              >
                <div class="flex items-start justify-between gap-4">
                  <div class="min-w-0">
                    <!-- productName은 표시용 enrich. 삭제 상품(null/부재) 시 방어 문구. -->
                    <p class="truncate text-sm font-medium text-ink" data-testid="item-product-name">
                      {{ item.productName ?? '삭제된 상품' }}
                    </p>
                    <p v-if="item.optionLabel" data-testid="item-option-label" class="truncate text-xs text-sub">{{ item.optionLabel }}</p>
                    <p class="mt-1 text-xs text-sub">
                      {{ vm.formatPrice(item.unitPrice) }} · 수량 {{ item.quantity }}
                    </p>
                  </div>
                  <div class="flex shrink-0 flex-col items-end gap-1">
                    <span class="text-sm font-medium text-ink">{{ vm.formatPrice(item.totalPrice) }}</span>
                    <!-- 품목 상태 배지(BE label=code이므로 FE 라벨 매핑). -->
                    <span class="rounded-badge bg-gray-100 px-2 py-0.5 text-xs font-medium text-sub">
                      {{ vm.orderItemStatusLabel(item.status.code) }}
                    </span>
                  </div>
                </div>

                <!-- 배송 정보(FE-54·C-05): 원 발송 송장·발송일·배송완료일 / 발송 전이면 "발송 준비 중". 교환품 송장은 클레임 상세가 담당. -->
                <OrderItemDeliveryInfo :delivery="item.delivery" :item-status-code="item.status.code" />

                <!-- 클레임 진입점: 품목 상태가 허용하는 유형만 노출(claimableTypes 빈 배열이면 미노출). 배송완료 품목은 구매확정 버튼(C-06)도 함께. -->
                <div v-if="vm.claimableTypes(item.status.code, item.exchangeCompleted ?? false).length || item.status.code === 'DELIVERED'" class="flex flex-wrap gap-2">
                  <Button
                    v-if="item.status.code === 'DELIVERED'"
                    size="sm"
                    :disabled="vm.confirming"
                    data-testid="item-confirm-purchase"
                    @click="vm.openConfirm(item)"
                  >
                    구매확정
                  </Button>
                  <Button
                    v-for="type in vm.claimableTypes(item.status.code, item.exchangeCompleted ?? false)"
                    :key="type"
                    variant="outline"
                    size="sm"
                    :data-testid="`item-claim-${type.toLowerCase()}`"
                    @click="vm.goClaim(item, type)"
                  >
                    {{ vm.claimTypeLabel(type) }} 요청
                  </Button>
                </div>
                <!-- 배송완료 안내(FE-53·C-16): 값은 lib/constants/order.ts AUTO_CONFIRM_DAYS(BE 설정과 일치). -->
                <p v-if="item.status.code === 'DELIVERED'" class="text-xs text-sub" data-testid="item-auto-confirm-guide">{{ vm.AUTO_CONFIRM_GUIDE }}</p>

                <!-- 구매확정 확인 패널(FE-53·C-06): 확정 후 반품·교환 요청 불가 경고 + 가역성 1줄(Track 102 FE-64 규약). -->
                <div v-if="vm.confirmTargetId === item.orderItemId" class="rounded-card border border-line bg-gray-50 p-4" data-testid="item-confirm-panel">
                  <p class="text-sm font-medium text-ink">이 품목을 구매확정할까요?</p>
                  <p class="mt-1 text-sm text-soldout" style="white-space: pre-line" data-testid="item-confirm-warning">{{ vm.ITEM_CONFIRM_WARNING }}</p>
                  <div class="mt-3 flex gap-2">
                    <Button variant="destructive" size="sm" :disabled="vm.confirming" data-testid="item-confirm-submit" @click="vm.submitConfirm(item)">
                      {{ vm.confirming ? '확정 중…' : '확정' }}
                    </Button>
                    <Button variant="outline" size="sm" :disabled="vm.confirming" data-testid="item-confirm-cancel" @click="vm.confirmTargetId = null">취소</Button>
                  </div>
                </div>
                <p
                  v-if="vm.confirmNotice && vm.confirmNotice.orderItemId === item.orderItemId"
                  role="status"
                  class="text-sm"
                  :class="vm.confirmNotice.tone === 'error' ? 'text-soldout' : 'text-primary'"
                  data-testid="item-confirm-notice"
                >{{ vm.confirmNotice.text }}</p>
              </li>
            </ul>

            <div class="mt-3 flex items-center justify-between border-t border-line pt-3">
              <span class="text-xs text-sub">판매자 소계</span>
              <span class="text-sm font-semibold text-ink">{{ vm.formatPrice(seller.subtotal) }}</span>
            </div>
          </div>
        </section>

        <!-- 주문 합계 -->
        <div class="mt-6 flex items-center justify-between rounded-card border border-line p-5">
          <span class="text-base font-semibold text-ink">총 결제금액</span>
          <span class="text-xl font-bold text-price">{{ vm.formatPrice(vm.data.totalPrice) }}</span>
        </div>

        <!-- 배송지: 스냅샷 부재 시 미표시 -->
        <section v-if="vm.data.shippingAddress" class="mt-6 rounded-card border border-line p-5">
          <h2 class="mb-3 text-base font-semibold text-ink">배송지</h2>
          <div class="space-y-1 text-sm text-ink">
            <p>{{ vm.data.shippingAddress.recipientName }} · {{ vm.data.shippingAddress.recipientPhone }}</p>
            <p class="text-sub">
              ({{ vm.data.shippingAddress.zonecode }}) {{ vm.data.shippingAddress.addressRoad }}
              <template v-if="vm.data.shippingAddress.addressDetail"> {{ vm.data.shippingAddress.addressDetail }}</template>
            </p>
            <p v-if="vm.data.shippingAddress.deliveryMemo" class="text-sub">
              메모: {{ vm.data.shippingAddress.deliveryMemo }}
            </p>
          </div>
        </section>

        <!-- 목록으로 -->
        <div class="mt-8">
          <Button variant="outline" size="lg" class="w-full" as-child>
            <NuxtLink to="/orders">주문 내역으로</NuxtLink>
          </Button>
        </div>
      </template>
    </div>
  </div>
</template>
