<script setup lang="ts">
import type { CheckoutRequest, PaymentMethod, ShippingAddress } from '~/types/checkout'
import type { Address } from '~/types/address'
import { PAYMENT_METHODS } from '~/lib/constants/payment'
import { resolvePaymentRedirect } from '~/lib/payment-redirect'
import {
  RECIPIENT_NAME_MAX,
  RECIPIENT_PHONE_MAX,
  ZONECODE_MAX,
  ADDRESS_ROAD_MAX,
  ADDRESS_JIBUN_MAX,
  ADDRESS_DETAIL_MAX,
} from '~/lib/constants/account'
import { isUnchanged, buildCreateAddressRequest, type CheckoutAddressForm } from '~/lib/utils/address-form'
import { buildCheckoutSummary, type CheckoutSummary } from '~/lib/utils/checkout-summary'
import type { CheckoutPageVm } from '~/skins/contracts/checkout'

// BUYER 전용 페이지 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다(recon §9).
definePageMeta({ middleware: 'buyer' })

const checkout = useCheckout()

// ── FE-17 주문 요약 ────────────────────────────────────────────────
// 진입 시 장바구니를 재조회한다(cart.vue 패턴). 렌더는 store의 cart.items(반응)를 읽고, 반환값은 SSR 직렬화·상태 판정용.
const cart = useCartStore()
const { error: cartError, refresh: refreshCart } = useAsyncData('checkout-cart', async () => {
  await cart.load()
  // setup store 외부 접근은 ref가 자동 언랩된다(LT-12) — cart.items는 이미 배열(.value 아님).
  return cart.items.length
})
const summary = computed<CheckoutSummary>(() => buildCheckoutSummary(cart.items))

function formatPrice(value: number): string {
  return `${value.toLocaleString('ko-KR')}원`
}

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

// 필수값 전부 입력됐는지(공백 제거 후 판정) ∧ 선택 품목 1개 이상 ∧ 선택 중 구매 불가 0개(FE-17·확정 5). 버튼 활성·제출 가드 공용.
const canSubmit = computed<boolean>(
  () =>
    recipientName.value.trim() !== '' &&
    recipientPhone.value.trim() !== '' &&
    zonecode.value.trim() !== '' &&
    addressRoad.value.trim() !== '' &&
    summary.value.items.length > 0 &&
    !summary.value.hasUnpurchasableSelected,
)

/**
 * 결제 시작 응답의 redirectUrl로 결제창에 진입한다(Track 97 D-209). Mock PG origin이면 attemptKey·amount·method와 Location 헤더의
 * orderPublicId를 내부 /payment/mock으로 넘기고(외부 PG 미방문), 실 PG면 결제창 URL로 외부 이동한다. Location = /api/v1/orders/{orderPublicId}.
 */
async function goToPayment(redirectUrl: string, location: string | null): Promise<void> {
  const redirect = resolvePaymentRedirect(redirectUrl, location)
  if (redirect.kind === 'external') {
    await navigateTo(redirect.url, { external: true })
    return
  }
  await navigateTo(redirect.path)
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
      await goToPayment(payment.redirectUrl, result.location)
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

const vm: CheckoutPageVm = reactive({
  cartError,
  refreshCart,
  summary,
  addressLoadFailed,
  hasAddresses,
  addressList,
  selectedKey,
  onSelectAddress,
  recipientName,
  recipientPhone,
  zonecode,
  addressRoad,
  addressJibun,
  addressDetail,
  deliveryMemo,
  showSaveCheckbox,
  saveAddress,
  method,
  errorMessage,
  showCartLink,
  submitting,
  canSubmit,
  formatPrice,
  handleSubmit,
  PAYMENT_METHODS,
  RECIPIENT_NAME_MAX,
  RECIPIENT_PHONE_MAX,
  ZONECODE_MAX,
  ADDRESS_ROAD_MAX,
  ADDRESS_JIBUN_MAX,
  ADDRESS_DETAIL_MAX,
})
</script>

<template>
  <component :is="useSkinView('CheckoutView')" :vm="vm" />
</template>
