<script setup lang="ts">
import type { CheckoutRequest, PaymentMethod, ShippingAddress } from '~/types/checkout'
import type { Address } from '~/types/address'
import { PAYMENT_METHODS } from '~/lib/constants/payment'
import {
  RECIPIENT_NAME_MAX,
  RECIPIENT_PHONE_MAX,
  ZONECODE_MAX,
  ADDRESS_ROAD_MAX,
  ADDRESS_JIBUN_MAX,
  ADDRESS_DETAIL_MAX,
} from '~/lib/constants/account'
import { isUnchanged, buildCreateAddressRequest, type CheckoutAddressForm } from '~/lib/utils/address-form'

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

// ── FE-16 배송지 연동 ──────────────────────────────────────────────
// 저장 배송지 목록을 SSR로 로드한다(useAddresses는 setup 최상위 호출·LT-12: useAsyncData 콜백 내 store 접근 회피).
const { listAddresses, createAddress } = useAddresses()
const { data: addressList, error: addressError } = await useAsyncData('checkout-addresses', () => listAddresses())

// 선택 상태: 'new'(새 주소 입력) 또는 저장 주소 id의 문자열(네이티브 select 값은 문자열).
const selectedKey = ref<string>('new')

// 폼의 공유 6필드 스냅샷(순수 함수 입력용). deliveryMemo는 주소록 저장 대상이 아니라 제외한다.
const formSnapshot = computed<CheckoutAddressForm>(() => ({
  recipientName: recipientName.value,
  recipientPhone: recipientPhone.value,
  zonecode: zonecode.value,
  addressRoad: addressRoad.value,
  addressJibun: addressJibun.value,
  addressDetail: addressDetail.value,
}))

// 현재 선택된 저장 주소(없거나 '새 주소'면 null).
const selectedAddress = computed<Address | null>(() => {
  if (selectedKey.value === 'new') return null
  return addressList.value?.find((address) => address.id === Number(selectedKey.value)) ?? null
})

// 저장 주소가 1건 이상일 때만 드롭다운 노출(0건·로드 실패 시 숨김·수기 입력).
const hasAddresses = computed<boolean>(() => (addressList.value?.length ?? 0) > 0)

// 목록 로드 실패(401 제외)면 배송지 영역에 안내(수기 입력·제출은 그대로 허용).
const addressLoadFailed = computed<boolean>(() => {
  const status = (addressError.value as { statusCode?: number } | null)?.statusCode
  return Boolean(addressError.value) && status !== 401
})

// 배송지 저장 체크박스(기본 체크). 선택 주소와 무변경이면 저장 불필요라 숨긴다(확정 6).
const saveAddress = ref<boolean>(true)
const showSaveCheckbox = computed<boolean>(() => !isUnchanged(selectedAddress.value, formSnapshot.value))

/** 저장 주소의 공유 6필드를 폼에 채운다(deliveryMemo는 주문별 값이라 건드리지 않는다). */
function applyAddress(address: Address): void {
  recipientName.value = address.recipientName
  recipientPhone.value = address.recipientPhone
  zonecode.value = address.zonecode
  addressRoad.value = address.addressRoad
  addressJibun.value = address.addressJibun ?? ''
  addressDetail.value = address.addressDetail ?? ''
}

/** '새 주소 입력' 선택 시 주소 6필드를 비운다(deliveryMemo는 유지). */
function clearAddressFields(): void {
  recipientName.value = ''
  recipientPhone.value = ''
  zonecode.value = ''
  addressRoad.value = ''
  addressJibun.value = ''
  addressDetail.value = ''
}

/** 드롭다운 변경 핸들러: 저장 주소 선택 시 폼 교체, '새 주소 입력' 시 폼 비움(확정 3·4). */
function onSelectAddress(): void {
  const address = selectedAddress.value
  if (address) applyAddress(address)
  else clearAddressFields()
}

// 세션 만료(401)는 로그인으로 유도(addresses.vue 관습·확정 12). 그 외 오류는 addressLoadFailed 안내로 처리.
watch(
  addressError,
  (fetchError) => {
    if ((fetchError as { statusCode?: number } | null)?.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent('/checkout')}`)
    }
  },
  { immediate: true },
)

// 기본 배송지 자동입력(확정 2): find(isDefault)만 채우고 없으면 빈 폼 유지(순서 의존·첫 항목 채움 금지).
// SSR setup에서 적용해 응답 HTML의 input value에 반영된다.
const defaultAddress = addressList.value?.find((address) => address.isDefault) ?? null
if (defaultAddress) {
  selectedKey.value = String(defaultAddress.id)
  applyAddress(defaultAddress)
}

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

/**
 * 배송지 저장(확정 8·9·10): 주문 생성 성공 직후 호출한다. 저장 대상(체크박스 노출+체크)일 때만 createAddress로
 * 신규 추가하고, 성공 시 응답 주소를 로컬 목록에 넣어 "선택+무변경" 상태로 전환한다(재제출 중복 저장 방지).
 * 실패는 결제 흐름을 막지 않도록 격리하고 사용자 안내 없이 콘솔 경고만 남긴다(확정 9).
 */
async function maybeSaveAddress(): Promise<void> {
  // 무변경(저장 주소 그대로)이거나 체크 해제면 저장하지 않는다.
  if (!showSaveCheckbox.value || !saveAddress.value) return
  try {
    const created = await createAddress(buildCreateAddressRequest(formSnapshot.value))
    addressList.value = [...(addressList.value ?? []), created]
    selectedKey.value = String(created.id)
  } catch (saveError) {
    console.warn('[checkout] 배송지 저장 실패(결제는 계속 진행합니다):', saveError)
  }
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
    // 확정 8: 주문 생성 성공 직후·결제 준비 실패 분기 판정보다 먼저 저장(결제 이동 전).
    await maybeSaveAddress()
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

            <!-- 저장된 배송지 불러오기(FE-16). 0건·로드 실패 시 드롭다운 숨김·수기 입력. -->
            <p v-if="addressLoadFailed" role="status" class="text-sm text-sub">
              저장된 배송지를 불러오지 못했습니다. 배송지를 직접 입력해 주세요.
            </p>
            <div v-if="hasAddresses" class="space-y-1.5">
              <label for="savedAddress" class="block text-sm font-medium text-ink">저장된 배송지</label>
              <select
                id="savedAddress"
                v-model="selectedKey"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                @change="onSelectAddress"
              >
                <option v-for="address in addressList" :key="address.id" :value="String(address.id)">
                  {{ address.recipientName }} · {{ address.addressRoad }}<template v-if="address.isDefault"> (기본)</template>
                </option>
                <option value="new">새 주소 입력</option>
              </select>
            </div>

            <div class="space-y-1.5">
              <label for="recipientName" class="block text-sm font-medium text-ink">받는 사람 <span class="text-soldout">*</span></label>
              <input
                id="recipientName"
                v-model="recipientName"
                type="text"
                autocomplete="name"
                required
                :maxlength="RECIPIENT_NAME_MAX"
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
                :maxlength="RECIPIENT_PHONE_MAX"
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
                :maxlength="ZONECODE_MAX"
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
                :maxlength="ADDRESS_ROAD_MAX"
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
                :maxlength="ADDRESS_JIBUN_MAX"
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
                :maxlength="ADDRESS_DETAIL_MAX"
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

            <!-- 배송지 저장(FE-16): 새 주소이거나 불러온 주소를 수정했을 때만 노출·기본 체크. -->
            <label v-if="showSaveCheckbox" class="flex items-center gap-2 text-sm text-ink">
              <input v-model="saveAddress" type="checkbox" class="h-4 w-4 rounded border-line" />
              이 배송지를 주소록에 저장
            </label>
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
