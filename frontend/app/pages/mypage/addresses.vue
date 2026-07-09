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
    resetForm()
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
  // 삭제는 되돌릴 수 없으므로 명시적 확인 후 실행.
  if (!window.confirm('이 배송지를 삭제하시겠습니까?')) return
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
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[880px] px-4 md:px-6">
      <NuxtLink to="/mypage" class="mb-4 inline-block text-sm text-sub hover:underline">← 마이페이지</NuxtLink>
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">배송지 관리</h1>

      <!-- 로딩 -->
      <div v-if="pending" class="space-y-3">
        <div v-for="n in 3" :key="n" class="h-24 animate-pulse rounded-card bg-gray-100"></div>
      </div>

      <!-- 에러 / 없음 -->
      <CommonErrorState v-else-if="error || !data" message="배송지를 불러오지 못했습니다" @retry="refresh" />

      <template v-else>
        <!-- 목록 -->
        <ul v-if="data.length > 0" class="mb-8 space-y-3">
          <li
            v-for="address in data"
            :key="address.id"
            class="rounded-card border border-line p-5"
            :class="{ 'border-gray-300': address.isDefault }"
          >
            <div class="flex items-start justify-between gap-4">
              <div class="min-w-0">
                <div class="flex items-center gap-2">
                  <p class="text-base font-medium text-ink">{{ address.recipientName }}</p>
                  <span
                    v-if="address.isDefault"
                    class="rounded-badge bg-primary px-2 py-0.5 text-xs font-medium text-primary-foreground"
                  >
                    기본
                  </span>
                  <span v-if="address.addressLabel" class="text-xs text-sub">{{ address.addressLabel }}</span>
                </div>
                <p class="mt-1 text-sm text-sub">{{ address.recipientPhone }}</p>
                <p class="mt-1 text-sm text-ink">
                  ({{ address.zonecode }}) {{ address.addressRoad }}
                  <template v-if="address.addressDetail"> {{ address.addressDetail }}</template>
                </p>
              </div>
            </div>
            <div class="mt-3 flex items-center justify-end gap-2 border-t border-line pt-3">
              <Button
                v-if="!address.isDefault"
                variant="outline"
                size="sm"
                @click="handleSetDefault(address.id)"
              >
                기본 지정
              </Button>
              <Button variant="outline" size="sm" @click="startEdit(address)">수정</Button>
              <Button variant="ghost" size="sm" @click="handleRemove(address.id)">삭제</Button>
            </div>
          </li>
        </ul>
        <CommonEmptyState v-else message="등록된 배송지가 없습니다" />

        <!-- 추가/수정 폼 -->
        <section class="rounded-card border border-line p-5">
          <div class="mb-4 flex items-center justify-between">
            <h2 class="text-base font-semibold text-ink">
              {{ editingId === null ? '새 배송지 추가' : '배송지 수정' }}
            </h2>
            <Button v-if="editingId !== null" variant="ghost" size="sm" @click="resetForm">새로 추가</Button>
          </div>

          <form class="space-y-4" @submit.prevent="handleSubmit">
            <div class="space-y-1.5">
              <label for="recipientName" class="block text-sm font-medium text-ink">받는 사람</label>
              <input
                id="recipientName"
                v-model="form.recipientName"
                type="text"
                required
                :maxlength="RECIPIENT_NAME_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="받는 사람 이름"
              />
            </div>

            <div class="space-y-1.5">
              <label for="recipientPhone" class="block text-sm font-medium text-ink">연락처</label>
              <input
                id="recipientPhone"
                v-model="form.recipientPhone"
                type="tel"
                required
                :maxlength="RECIPIENT_PHONE_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="휴대폰 번호"
              />
            </div>

            <div class="space-y-1.5">
              <label for="zonecode" class="block text-sm font-medium text-ink">우편번호</label>
              <input
                id="zonecode"
                v-model="form.zonecode"
                type="text"
                required
                :maxlength="ZONECODE_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="우편번호"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressRoad" class="block text-sm font-medium text-ink">도로명 주소</label>
              <input
                id="addressRoad"
                v-model="form.addressRoad"
                type="text"
                required
                :maxlength="ADDRESS_ROAD_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="도로명 주소"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressDetail" class="block text-sm font-medium text-ink">상세 주소 (선택)</label>
              <input
                id="addressDetail"
                v-model="form.addressDetail"
                type="text"
                :maxlength="ADDRESS_DETAIL_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="상세 주소(동·호수 등)"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressJibun" class="block text-sm font-medium text-ink">지번 주소 (선택)</label>
              <input
                id="addressJibun"
                v-model="form.addressJibun"
                type="text"
                :maxlength="ADDRESS_JIBUN_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="지번 주소"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressLabel" class="block text-sm font-medium text-ink">배송지 이름 (선택)</label>
              <input
                id="addressLabel"
                v-model="form.addressLabel"
                type="text"
                :maxlength="ADDRESS_LABEL_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="예: 집, 회사"
              />
            </div>

            <!-- isDefault는 생성 시에만 노출(수정은 별도 기본 지정 경로). -->
            <label v-if="editingId === null" class="flex items-center gap-2 text-sm text-ink">
              <input v-model="form.isDefault" type="checkbox" class="h-4 w-4 rounded border-line" />
              기본 배송지로 설정
            </label>

            <p v-if="errorMessage" role="alert" class="text-sm text-soldout">{{ errorMessage }}</p>

            <Button type="submit" size="lg" class="w-full" :disabled="submitting">
              {{ submitting ? '저장 중…' : editingId === null ? '배송지 추가' : '수정 저장' }}
            </Button>
          </form>
        </section>

        <p v-if="successMessage" role="status" class="mt-4 text-sm text-primary">{{ successMessage }}</p>
      </template>
    </div>
  </div>
</template>
