<script setup lang="ts">
import {
  RECIPIENT_NAME_MAX,
  RECIPIENT_PHONE_MAX,
  ADDRESS_LABEL_MAX,
  ZONECODE_MAX,
  ADDRESS_ROAD_MAX,
  ADDRESS_JIBUN_MAX,
  ADDRESS_DETAIL_MAX,
} from '~/lib/constants/account'
import type { Address, CreateAddressRequest, UpdateAddressRequest } from '~/types/address'
import type { AddressesPageVm } from '~/skins/contracts/addresses'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const { listAddresses, createAddress, updateAddress, removeAddress, setDefaultAddress } = useAddresses()

// 목록 SSR 로드(useOrders·profile과 동일하게 lazy=false·pending 사용).
const { data, pending, error, refresh } = useAsyncData('mypage-addresses', () => listAddresses())

// 세션 만료(401)는 로그인으로 유도(미들웨어는 진입 UX만·실인가 SoT는 서버).
watch(
  error,
  (fetchError) => {
    if ((fetchError as { statusCode?: number } | null)?.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent('/mypage/addresses')}`)
    }
  },
  { immediate: true },
)

// 단일 폼으로 생성·수정 겸용. editingId=null이면 생성(isDefault 노출), 값이 있으면 수정(isDefault 제외).
const editingId = ref<number | null>(null)
const form = reactive({
  addressLabel: '',
  recipientName: '',
  recipientPhone: '',
  zonecode: '',
  addressRoad: '',
  addressJibun: '',
  addressDetail: '',
  isDefault: false,
})
const submitting = ref<boolean>(false)
const errorMessage = ref<string>('')
const successMessage = ref<string>('')
// 폼·삭제 확인 모달 열림 상태(FE-72 보완 1·renew 뷰가 사용). classic 뷰는 쓰지 않아 기존 동작 그대로다.
const formOpen = ref<boolean>(false)
const removeTargetId = ref<number | null>(null)
const removing = ref<boolean>(false)

function resetForm(): void {
  editingId.value = null
  form.addressLabel = ''
  form.recipientName = ''
  form.recipientPhone = ''
  form.zonecode = ''
  form.addressRoad = ''
  form.addressJibun = ''
  form.addressDetail = ''
  form.isDefault = false
  errorMessage.value = ''
}

function startEdit(address: Address): void {
  editingId.value = address.id
  form.addressLabel = address.addressLabel ?? ''
  form.recipientName = address.recipientName
  form.recipientPhone = address.recipientPhone
  form.zonecode = address.zonecode
  form.addressRoad = address.addressRoad
  form.addressJibun = address.addressJibun ?? ''
  form.addressDetail = address.addressDetail ?? ''
  form.isDefault = address.isDefault
  errorMessage.value = ''
  successMessage.value = ''
  formOpen.value = true
}

function openCreate(): void {
  resetForm()
  formOpen.value = true
}

// 닫기만 한다 — 초기화는 뷰가 닫힘 애니메이션이 끝난 뒤 resetForm으로 한다(FE-79). 다시 열 때는 openCreate·startEdit가 값을 채운다.
function closeForm(): void {
  formOpen.value = false
}

// 옵션 필드는 빈 문자열이면 undefined로 보내 서버에 저장하지 않는다($fetch가 undefined 키를 생략).
function buildBody(): CreateAddressRequest {
  return {
    isDefault: form.isDefault,
    addressLabel: form.addressLabel || undefined,
    recipientName: form.recipientName,
    recipientPhone: form.recipientPhone,
    zonecode: form.zonecode,
    addressRoad: form.addressRoad,
    addressJibun: form.addressJibun || undefined,
    addressDetail: form.addressDetail || undefined,
  }
}

// 뮤테이션 공통 에러 처리(checkout 관습 복제). caught error는 호출부에서 읽는 shape로 캐스팅해 전달한다.
function handleMutationError(mutationError: { statusCode?: number }, fallback: string): void {
  if (mutationError.statusCode === 401) {
    navigateTo(`/login?redirect=${encodeURIComponent('/mypage/addresses')}`)
    return
  }
  // 검증(400)·미소유(404) 등 → 사유 은닉·단일 fallback 문구.
  errorMessage.value = fallback
}

async function handleSubmit(): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    if (editingId.value === null) {
      await createAddress(buildBody())
      successMessage.value = '배송지가 추가되었습니다'
    } else {
      // 수정은 isDefault 제외(기본 전환은 별도 setDefault 경로).
      const body: UpdateAddressRequest = {
        addressLabel: form.addressLabel || undefined,
        recipientName: form.recipientName,
        recipientPhone: form.recipientPhone,
        zonecode: form.zonecode,
        addressRoad: form.addressRoad,
        addressJibun: form.addressJibun || undefined,
        addressDetail: form.addressDetail || undefined,
      }
      await updateAddress(editingId.value, body)
      successMessage.value = '배송지가 수정되었습니다'
    }
    formOpen.value = false
    await refresh()
  } catch (submitError) {
    handleMutationError(submitError as { statusCode?: number }, '저장에 실패했습니다. 입력을 확인하세요')
  } finally {
    submitting.value = false
  }
}

async function handleSetDefault(addressId: number): Promise<void> {
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await setDefaultAddress(addressId)
    successMessage.value = '기본 배송지가 변경되었습니다'
    await refresh()
  } catch (mutationError) {
    handleMutationError(mutationError as { statusCode?: number }, '기본 배송지 변경에 실패했습니다')
  }
}

async function handleRemove(addressId: number): Promise<void> {
  // 삭제는 되돌릴 수 없으므로 명시적 확인 후 실행(classic 뷰 경로).
  if (!window.confirm('이 배송지를 삭제하시겠습니까?')) return
  await removeById(addressId)
}

// 삭제 확인 모달 경로(renew 뷰): 요청 → 모달 확인 → 삭제. 확인 문구는 모달이 보여 준다.
function requestRemove(addressId: number): void {
  removeTargetId.value = addressId
}

function cancelRemove(): void {
  removeTargetId.value = null
}

// 삭제가 끝날 때까지 확인 모달을 열어 둔다(removing = 모달 pending · FE-79). 성공·실패 모두 끝나면 닫고 결과는 페이지 알림으로 보인다.
async function confirmRemove(): Promise<void> {
  const addressId = removeTargetId.value
  if (addressId === null || removing.value) return
  removing.value = true
  try {
    await removeById(addressId)
  } finally {
    removing.value = false
    removeTargetId.value = null
  }
}

// 삭제 실행부(두 경로 공유).
async function removeById(addressId: number): Promise<void> {
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await removeAddress(addressId)
    // 수정 중인 항목을 삭제했다면 폼도 초기화.
    if (editingId.value === addressId) resetForm()
    successMessage.value = '배송지가 삭제되었습니다'
    await refresh()
  } catch (mutationError) {
    handleMutationError(mutationError as { statusCode?: number }, '삭제에 실패했습니다')
  }
}

useSeoMeta({ title: '배송지 관리 · zslab-mall', description: 'zslab-mall 배송지 관리' })

const vm: AddressesPageVm = reactive({
  pending,
  error,
  data,
  refresh,
  editingId,
  form,
  submitting,
  errorMessage,
  successMessage,
  resetForm,
  startEdit,
  handleSubmit,
  handleSetDefault,
  handleRemove,
  formOpen,
  openCreate,
  closeForm,
  removeTargetId,
  removing,
  requestRemove,
  cancelRemove,
  confirmRemove,
  RECIPIENT_NAME_MAX,
  RECIPIENT_PHONE_MAX,
  ADDRESS_LABEL_MAX,
  ZONECODE_MAX,
  ADDRESS_ROAD_MAX,
  ADDRESS_JIBUN_MAX,
  ADDRESS_DETAIL_MAX,
})
</script>

<template>
  <component :is="useSkinView('AddressesView')" :vm="vm" />
</template>
