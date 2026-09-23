<script setup lang="ts">
import { AUTO_CONFIRM_GUIDE, ITEM_CONFIRM_WARNING, PAYMENT_EXPIRE_GUIDE, orderStatusLabel } from '~/lib/constants/order'
import { claimableTypes, claimTypeLabel, orderItemStatusLabel, type ClaimType } from '~/lib/constants/claim'
import { PAYMENT_METHODS } from '~/lib/constants/payment'
import { canResumePayment, isPaymentExpired, paymentResumeFailure, PAYMENT_EXPIRED_NOTICE } from '~/lib/utils/payment-resume'
import type { PaymentResumeErrorLike } from '~/lib/utils/payment-resume'
import { resolvePaymentRedirect } from '~/lib/payment-redirect'
import type { PaymentMethod } from '~/types/checkout'
import type { OrderItem } from '~/types/order'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const orderPublicId = route.params.orderPublicId as string

const { data, pending, error, refresh } = useOrderDetail(orderPublicId)

// 401(세션 만료)은 /login 유도. 404(타인·미존재)는 존재 은닉이라 안내만(재조회 버튼 없이 목록으로 유도).
watch(
  error,
  (fetchError) => {
    if ((fetchError as { statusCode?: number } | null)?.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent(`/orders/${orderPublicId}`)}`)
    }
  },
  { immediate: true },
)

// 404와 그 외 오류 문구 구분(존재 은닉이라 미노출도 404).
const errorMessage = computed<string>(() =>
  (error.value as { statusCode?: number } | null)?.statusCode === 404
    ? '주문을 찾을 수 없습니다'
    : '주문을 불러오지 못했습니다',
)

function formatPrice(value: number): string {
  return `${value.toLocaleString('ko-KR')}원`
}

// 클레임 요청 폼으로 진입(name은 표시용·서버 미전송). 원주문 id는 폼이 query로 받지 않으므로 성공 후 /orders로 유도.
// 교환(FE-30-1 α)은 옵션 후보 조회·필터용으로 상품/변형 public id·주문 단가를 함께 넘긴다(규칙 보장은 BE).
function goClaim(item: OrderItem, type: ClaimType): void {
  const base = `/claims/new?orderItem=${item.orderItemId}&type=${type}&name=${encodeURIComponent(item.productName ?? '')}`
  const exchangeQuery = type === 'EXCHANGE'
    ? `&product=${item.productId ?? ''}&variant=${item.variantId ?? ''}&unitPrice=${item.unitPrice}`
    : ''
  navigateTo(base + exchangeQuery)
}

// 결제 재개(Track 102 FE-64): 결제대기 주문에서 BE 재결제(D-60)를 호출하고 체크아웃과 같은 방식으로 결제창에 진입한다.
// 결제수단 기본값은 체크아웃 폼과 같은 첫 선택지다. 실패는 인라인 안내만 한다(구매자 앱에 토스트 인프라가 없다).
const { retryPayment } = useCheckout()
const payMethod = ref<PaymentMethod>(PAYMENT_METHODS[0]!.value)
const paying = ref<boolean>(false)
const payError = ref<string>('')

async function submitResumePayment(): Promise<void> {
  if (paying.value) return
  paying.value = true
  payError.value = ''
  try {
    const result = await retryPayment(orderPublicId, payMethod.value)
    const payment = result.data.payment
    if (payment.publicId === null || !payment.redirectUrl) {
      payError.value = '결제 준비에 실패했습니다. 잠시 후 다시 시도해 주세요.'
      return
    }
    // 재결제 응답의 Location은 결제(payment)를 가리키므로 주문번호를 직접 넘긴다(payment-redirect 주석 참조).
    const redirect = resolvePaymentRedirect(payment.redirectUrl, result.location, orderPublicId)
    if (redirect.kind === 'external') {
      await navigateTo(redirect.url, { external: true })
      return
    }
    await navigateTo(redirect.path)
  } catch (payFetchError) {
    // 실패 8종의 문구·후속 동작 판정은 순수 함수가 갖는다(Track 102 보완·payment-resume.ts).
    const failure = paymentResumeFailure(payFetchError as PaymentResumeErrorLike)
    if (failure.login) {
      await navigateTo(`/login?redirect=${encodeURIComponent(`/orders/${orderPublicId}`)}`)
      return
    }
    payError.value = failure.message
    // 상태가 이미 바뀐 실패는 상세를 다시 읽어 결제 영역 자체가 사라지게 한다.
    if (failure.refresh) await refresh()
  } finally {
    paying.value = false
  }
}

// 구매확정(FE-53·C-06): DELIVERED 품목만. 확인 패널(인라인·구매자 앱은 다이얼로그·토스트 인프라 부재)에서 반품·교환 불가 경고 후 호출한다.
// 성공·실패 안내도 같은 품목 아래 인라인. 제출 중에는 버튼을 잠가 중복 호출을 막는다.
const { confirmPurchase } = useOrderActions()
const confirmTargetId = ref<string | null>(null)
const confirming = ref<boolean>(false)
const confirmNotice = ref<{ orderItemId: string; tone: 'success' | 'error'; text: string } | null>(null)

function openConfirm(item: OrderItem): void {
  confirmNotice.value = null
  confirmTargetId.value = item.orderItemId
}

async function submitConfirm(item: OrderItem): Promise<void> {
  if (confirming.value) return
  confirming.value = true
  try {
    await confirmPurchase(orderPublicId, item.orderItemId)
    confirmNotice.value = { orderItemId: item.orderItemId, tone: 'success', text: '구매확정이 완료되었습니다.' }
    confirmTargetId.value = null
    await refresh()
  } catch (submitError) {
    // 401은 로그인 유도, 그 외(422 상태 불일치·404 등)는 서버 detail 우선 표시 후 재조회로 최신 상태 반영(.catch(()=>{}) 금지).
    const failure = submitError as { statusCode?: number; data?: { detail?: string } }
    if (failure.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent(`/orders/${orderPublicId}`)}`)
      return
    }
    confirmNotice.value = {
      orderItemId: item.orderItemId,
      tone: 'error',
      text: failure.data?.detail ?? '구매확정에 실패했습니다. 잠시 후 다시 시도하세요.',
    }
    confirmTargetId.value = null
    await refresh()
  } finally {
    confirming.value = false
  }
}

useSeoMeta({
  title: () => (data.value ? `주문 ${data.value.orderId} · zslab-mall` : '주문 상세 · zslab-mall'),
  description: 'zslab-mall 주문 상세',
})
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[880px] px-4 md:px-6">
      <!-- 로딩 -->
      <div v-if="pending" class="space-y-4">
        <div class="h-8 w-2/3 animate-pulse rounded bg-gray-100"></div>
        <div class="h-40 animate-pulse rounded-card bg-gray-100"></div>
      </div>

      <!-- 에러 / 없음(404 포함) -->
      <CommonErrorState v-else-if="error || !data" :message="errorMessage" @retry="refresh" />

      <!-- 상세 -->
      <template v-else>
        <!-- 헤더: 주문번호 + 상태 -->
        <div class="mb-6 flex items-start justify-between gap-4">
          <div class="min-w-0">
            <p class="text-sm text-sub">주문번호</p>
            <h1 class="mt-1 break-all font-mono text-lg font-medium text-ink">{{ data.orderId }}</h1>
          </div>
          <span class="shrink-0 rounded-badge bg-gray-100 px-3 py-1 text-sm font-medium text-ink">
            {{ orderStatusLabel(data.status.code) }}
          </span>
        </div>
        <!-- 결제 대기 안내(FE-53·C-16): 값은 lib/constants/order.ts PAYMENT_EXPIRE_MINUTES(BE 설정과 일치). -->
        <p v-if="data.status.code === 'PENDING_PAYMENT'" class="-mt-3 mb-6 text-sm text-sub" data-testid="order-payment-expire-guide">
          {{ PAYMENT_EXPIRE_GUIDE }}
        </p>

        <!--
          결제 재개(Track 102 FE-64): 결제대기 주문에서 결제를 다시 시작한다. BE 재결제(D-60)가 결제수단을 따로 받으므로
          체크아웃과 같은 선택지를 그대로 보여주고, 이동은 체크아웃과 같은 resolvePaymentRedirect 경로를 쓴다.
        -->
        <section v-if="canResumePayment(data.status.code)" class="mb-6 rounded-card border border-line p-5" data-testid="order-resume-payment">
          <h2 class="mb-3 text-base font-semibold text-ink">결제하기</h2>
          <div class="flex flex-wrap gap-2">
            <label
              v-for="option in PAYMENT_METHODS"
              :key="option.value"
              class="flex cursor-pointer items-center gap-2 rounded-card border border-line px-3 py-2 text-sm text-ink"
            >
              <input v-model="payMethod" type="radio" :value="option.value" name="resume-payment-method" class="h-4 w-4" />
              {{ option.label }}
            </label>
          </div>
          <Button class="mt-4" :disabled="paying" data-testid="order-resume-payment-submit" @click="submitResumePayment">
            {{ paying ? '결제 준비 중…' : '결제하기' }}
          </Button>
          <p v-if="payError" role="alert" class="mt-2 text-sm text-soldout" data-testid="order-resume-payment-error">{{ payError }}</p>
        </section>

        <!-- 미결제 종료 주문: 결제 버튼 대신 왜 결제할 수 없는지를 말한다. -->
        <p v-else-if="isPaymentExpired(data.status.code)" class="-mt-3 mb-6 text-sm text-sub" data-testid="order-payment-expired-notice">
          {{ PAYMENT_EXPIRED_NOTICE }}
        </p>

        <!-- seller 그룹별 품목 -->
        <section class="space-y-4">
          <div
            v-for="seller in data.sellers"
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
                    <p class="truncate text-sm font-medium text-ink">
                      {{ item.productName ?? '삭제된 상품' }}
                    </p>
                    <p v-if="item.optionLabel" data-testid="item-option-label" class="truncate text-xs text-sub">{{ item.optionLabel }}</p>
                    <p class="mt-1 text-xs text-sub">
                      {{ formatPrice(item.unitPrice) }} · 수량 {{ item.quantity }}
                    </p>
                  </div>
                  <div class="flex shrink-0 flex-col items-end gap-1">
                    <span class="text-sm font-medium text-ink">{{ formatPrice(item.totalPrice) }}</span>
                    <!-- 품목 상태 배지(BE label=code이므로 FE 라벨 매핑). -->
                    <span class="rounded-badge bg-gray-100 px-2 py-0.5 text-xs font-medium text-sub">
                      {{ orderItemStatusLabel(item.status.code) }}
                    </span>
                  </div>
                </div>

                <!-- 배송 정보(FE-54·C-05): 원 발송 송장·발송일·배송완료일 / 발송 전이면 "발송 준비 중". 교환품 송장은 클레임 상세가 담당. -->
                <OrderItemDeliveryInfo :delivery="item.delivery" :item-status-code="item.status.code" />

                <!-- 클레임 진입점: 품목 상태가 허용하는 유형만 노출(claimableTypes 빈 배열이면 미노출). 배송완료 품목은 구매확정 버튼(C-06)도 함께. -->
                <div v-if="claimableTypes(item.status.code, item.exchangeCompleted ?? false).length || item.status.code === 'DELIVERED'" class="flex flex-wrap gap-2">
                  <Button
                    v-if="item.status.code === 'DELIVERED'"
                    size="sm"
                    :disabled="confirming"
                    data-testid="item-confirm-purchase"
                    @click="openConfirm(item)"
                  >
                    구매확정
                  </Button>
                  <Button
                    v-for="type in claimableTypes(item.status.code, item.exchangeCompleted ?? false)"
                    :key="type"
                    variant="outline"
                    size="sm"
                    @click="goClaim(item, type)"
                  >
                    {{ claimTypeLabel(type) }} 요청
                  </Button>
                </div>
                <!-- 배송완료 안내(FE-53·C-16): 값은 lib/constants/order.ts AUTO_CONFIRM_DAYS(BE 설정과 일치). -->
                <p v-if="item.status.code === 'DELIVERED'" class="text-xs text-sub" data-testid="item-auto-confirm-guide">{{ AUTO_CONFIRM_GUIDE }}</p>

                <!-- 구매확정 확인 패널(FE-53·C-06): 확정 후 반품·교환 요청 불가 경고 + 가역성 1줄(Track 102 FE-64 규약). -->
                <div v-if="confirmTargetId === item.orderItemId" class="rounded-card border border-line bg-gray-50 p-4" data-testid="item-confirm-panel">
                  <p class="text-sm font-medium text-ink">이 품목을 구매확정할까요?</p>
                  <p class="mt-1 text-sm text-soldout" style="white-space: pre-line" data-testid="item-confirm-warning">{{ ITEM_CONFIRM_WARNING }}</p>
                  <div class="mt-3 flex gap-2">
                    <Button variant="destructive" size="sm" :disabled="confirming" data-testid="item-confirm-submit" @click="submitConfirm(item)">
                      {{ confirming ? '확정 중…' : '확정' }}
                    </Button>
                    <Button variant="outline" size="sm" :disabled="confirming" data-testid="item-confirm-cancel" @click="confirmTargetId = null">취소</Button>
                  </div>
                </div>
                <p
                  v-if="confirmNotice && confirmNotice.orderItemId === item.orderItemId"
                  role="status"
                  class="text-sm"
                  :class="confirmNotice.tone === 'error' ? 'text-soldout' : 'text-primary'"
                  data-testid="item-confirm-notice"
                >{{ confirmNotice.text }}</p>
              </li>
            </ul>

            <div class="mt-3 flex items-center justify-between border-t border-line pt-3">
              <span class="text-xs text-sub">판매자 소계</span>
              <span class="text-sm font-semibold text-ink">{{ formatPrice(seller.subtotal) }}</span>
            </div>
          </div>
        </section>

        <!-- 주문 합계 -->
        <div class="mt-6 flex items-center justify-between rounded-card border border-line p-5">
          <span class="text-base font-semibold text-ink">총 결제금액</span>
          <span class="text-xl font-bold text-price">{{ formatPrice(data.totalPrice) }}</span>
        </div>

        <!-- 배송지: 스냅샷 부재 시 미표시 -->
        <section v-if="data.shippingAddress" class="mt-6 rounded-card border border-line p-5">
          <h2 class="mb-3 text-base font-semibold text-ink">배송지</h2>
          <div class="space-y-1 text-sm text-ink">
            <p>{{ data.shippingAddress.recipientName }} · {{ data.shippingAddress.recipientPhone }}</p>
            <p class="text-sub">
              ({{ data.shippingAddress.zonecode }}) {{ data.shippingAddress.addressRoad }}
              <template v-if="data.shippingAddress.addressDetail"> {{ data.shippingAddress.addressDetail }}</template>
            </p>
            <p v-if="data.shippingAddress.deliveryMemo" class="text-sub">
              메모: {{ data.shippingAddress.deliveryMemo }}
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
