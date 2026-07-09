<script setup lang="ts">
import type { CheckoutRequest, PaymentMethod, ShippingAddress } from '~/types/checkout'
import { PAYMENT_METHODS } from '~/lib/constants/payment'

// BUYER 전용 페이지 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다(recon §9).
definePageMeta({ middleware: 'buyer' })

const checkout = useCheckout()

// 배송지 필수 4 + 선택 3. 우편번호 검색 API는 이연 — zonecode는 수기 입력(FE-11 범위).
const recipientName = ref<string>('')
const recipientPhone = ref<string>('')
const zonecode = ref<string>('')
const addressRoad = ref<string>('')
const addressJibun = ref<string>('')
const addressDetail = ref<string>('')
const deliveryMemo = ref<string>('')
const method = ref<PaymentMethod>(PAYMENT_METHODS[0]!.value)

const submitting = ref<boolean>(false)
const errorMessage = ref<string>('')
// 빈 카트(CART_CHECKOUT_EMPTY) 시에만 장바구니로 돌아가는 링크를 노출한다.
const showCartLink = ref<boolean>(false)

// 필수값 전부 입력됐는지(공백 제거 후 판정). 버튼 활성·제출 가드 공용.
const canSubmit = computed<boolean>(
  () =>
    recipientName.value.trim() !== '' &&
    recipientPhone.value.trim() !== '' &&
    zonecode.value.trim() !== '' &&
    addressRoad.value.trim() !== '',
)

/**
 * 결제 시작 응답의 redirectUrl(모의 PG)에서 attemptKey·amount·method를, Location 헤더에서 orderPublicId를 파싱해
 * 내부 /payment/mock으로 넘긴다(외부 PG 미방문). Location = /api/v1/orders/{orderPublicId}.
 */
async function goToMockPayment(redirectUrl: string, location: string | null): Promise<void> {
  const url = new URL(redirectUrl)
  const attemptKey = url.searchParams.get('attemptKey') ?? ''
  const amount = url.searchParams.get('amount') ?? ''
  const paymentMethod = url.searchParams.get('method') ?? ''
  const orderPublicId = location ? location.split('/').pop() ?? '' : ''
  await navigateTo(
    `/payment/mock?attemptKey=${encodeURIComponent(attemptKey)}`
      + `&amount=${encodeURIComponent(amount)}&method=${encodeURIComponent(paymentMethod)}`
      + `&orderPublicId=${encodeURIComponent(orderPublicId)}`,
  )
}

async function handleSubmit(): Promise<void> {
  if (submitting.value || !canSubmit.value) return
  submitting.value = true
  errorMessage.value = ''
  showCartLink.value = false

  const shippingAddress: ShippingAddress = {
    recipientName: recipientName.value.trim(),
    recipientPhone: recipientPhone.value.trim(),
    zonecode: zonecode.value.trim(),
    addressRoad: addressRoad.value.trim(),
    addressJibun: addressJibun.value.trim() || undefined,
    addressDetail: addressDetail.value.trim() || undefined,
    deliveryMemo: deliveryMemo.value.trim() || undefined,
  }
  const request: CheckoutRequest = { shippingAddress, method: method.value }

  try {
    const result = await checkout.submit(request)
    const payment = result.data.payment
    // INITIATE_FAILED(2xx·publicId=null): 결제 준비 실패 안내만. retryPaymentUrl 실배선은 FE-12(방어 안내).
    if (payment.publicId === null) {
      errorMessage.value = '결제 준비에 실패했습니다. 잠시 후 다시 시도해 주세요.'
      return
    }
    if (payment.redirectUrl) {
      await goToMockPayment(payment.redirectUrl, result.location)
      return
    }
    // 2xx인데 redirectUrl 부재는 비정상 — 방어 안내.
    errorMessage.value = '결제 시작 정보를 받지 못했습니다. 다시 시도해 주세요.'
  } catch (submitError) {
    const statusCode = (submitError as { statusCode?: number }).statusCode
    const code = (submitError as { data?: { code?: string } }).data?.code
    if (statusCode === 401) {
      await navigateTo(`/login?redirect=${encodeURIComponent('/checkout')}`)
      return
    }
    if (statusCode === 422 && code === 'CART_CHECKOUT_EMPTY') {
      errorMessage.value = '결제할 선택 품목이 없습니다. 장바구니에서 상품을 선택해 주세요.'
      showCartLink.value = true
      return
    }
    if (statusCode === 422) {
      // 재고 부족 등 결제 불가 상태(OUT_OF_STOCK 등).
      errorMessage.value = '선택하신 상품을 지금 주문할 수 없습니다(재고 부족 또는 판매 중지).'
      return
    }
    errorMessage.value = '주문 처리 중 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '주문/결제 · zslab-mall', description: 'zslab-mall 주문/결제' })
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">주문/결제</h1>

      <form class="grid grid-cols-1 gap-8 lg:grid-cols-[1fr_320px]" @submit.prevent="handleSubmit">
        <!-- 배송지 + 결제수단 -->
        <div class="space-y-8">
          <!-- 배송지 -->
          <section class="space-y-4">
            <h2 class="text-lg font-semibold text-ink">배송지</h2>

            <div class="space-y-1.5">
              <label for="recipientName" class="block text-sm font-medium text-ink">받는 사람 <span class="text-soldout">*</span></label>
              <input
                id="recipientName"
                v-model="recipientName"
                type="text"
                autocomplete="name"
                required
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="받는 사람 이름"
              />
            </div>

            <div class="space-y-1.5">
              <label for="recipientPhone" class="block text-sm font-medium text-ink">연락처 <span class="text-soldout">*</span></label>
              <input
                id="recipientPhone"
                v-model="recipientPhone"
                type="tel"
                autocomplete="tel"
                required
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="010-0000-0000"
              />
            </div>

            <div class="space-y-1.5">
              <label for="zonecode" class="block text-sm font-medium text-ink">우편번호 <span class="text-soldout">*</span></label>
              <input
                id="zonecode"
                v-model="zonecode"
                type="text"
                autocomplete="postal-code"
                required
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="우편번호"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressRoad" class="block text-sm font-medium text-ink">도로명 주소 <span class="text-soldout">*</span></label>
              <input
                id="addressRoad"
                v-model="addressRoad"
                type="text"
                autocomplete="address-line1"
                required
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="도로명 주소"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressJibun" class="block text-sm font-medium text-ink">지번 주소</label>
              <input
                id="addressJibun"
                v-model="addressJibun"
                type="text"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="지번 주소 (선택)"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressDetail" class="block text-sm font-medium text-ink">상세 주소</label>
              <input
                id="addressDetail"
                v-model="addressDetail"
                type="text"
                autocomplete="address-line2"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="상세 주소 (선택)"
              />
            </div>

            <div class="space-y-1.5">
              <label for="deliveryMemo" class="block text-sm font-medium text-ink">배송 메모</label>
              <input
                id="deliveryMemo"
                v-model="deliveryMemo"
                type="text"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="배송 시 요청사항 (선택)"
              />
            </div>
          </section>

          <!-- 결제수단 -->
          <section class="space-y-4">
            <h2 class="text-lg font-semibold text-ink">결제수단</h2>
            <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <label
                v-for="option in PAYMENT_METHODS"
                :key="option.value"
                class="flex cursor-pointer items-center justify-center gap-2 rounded-control border px-4 py-2.5 text-sm font-medium transition duration-normal"
                :class="method === option.value ? 'border-primary text-primary' : 'border-line text-sub hover:bg-gray-50'"
              >
                <input v-model="method" type="radio" name="method" :value="option.value" class="sr-only" />
                {{ option.label }}
              </label>
            </div>
          </section>
        </div>

        <!-- 결제 요약 + 제출 -->
        <aside class="h-fit rounded-card border border-line p-5 lg:sticky lg:top-24">
          <h2 class="mb-4 text-lg font-semibold text-ink">결제</h2>
          <p class="text-sm text-sub">선택하신 장바구니 상품으로 주문을 생성합니다.</p>

          <!-- 오류 -->
          <div v-if="errorMessage" class="mt-4">
            <p role="alert" class="text-sm text-soldout">{{ errorMessage }}</p>
            <NuxtLink v-if="showCartLink" to="/cart" class="mt-1 inline-block text-sm text-primary underline">
              장바구니로 이동
            </NuxtLink>
          </div>

          <Button type="submit" size="lg" class="mt-4 w-full" :disabled="submitting || !canSubmit">
            {{ submitting ? '주문 처리 중…' : '결제하기' }}
          </Button>
        </aside>
      </form>
    </div>
  </div>
</template>
