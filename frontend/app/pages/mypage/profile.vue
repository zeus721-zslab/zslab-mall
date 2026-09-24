<script setup lang="ts">
import { NAME_MAX, PHONE_MAX, PHONE_PATTERN } from '~/lib/constants/account'
import type { UpdateProfileRequest } from '~/types/user'
import type { ProfilePageVm } from '~/skins/contracts/profile'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const { fetchProfile, updateProfile } = useProfile()

// 진입 시 현재 프로필 SSR 로드(useOrders의 useFetch와 동일하게 lazy=false·pending 사용).
const { data, pending, error, refresh } = useAsyncData('mypage-profile', () => fetchProfile())

// 세션 만료 등으로 서버가 401이면 로그인으로 유도(미들웨어는 진입 UX만·실인가 SoT는 서버).
watch(
  error,
  (fetchError) => {
    if ((fetchError as { statusCode?: number } | null)?.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent('/mypage/profile')}`)
    }
  },
  { immediate: true },
)

const name = ref<string>('')
const phone = ref<string>('')
const submitting = ref<boolean>(false)
const errorMessage = ref<string>('')
const successMessage = ref<string>('')

// 로드·재조회된 프로필로 폼 초기화. email은 자격증명이라 수정 대상이 아니며 읽기전용 표시만 한다.
watchEffect(() => {
  if (data.value) {
    name.value = data.value.name
    phone.value = data.value.phone
  }
})

async function handleSubmit(): Promise<void> {
  if (submitting.value) return
  errorMessage.value = ''
  successMessage.value = ''
  // 휴대폰 형식(BE @Pattern 미러·Track 84)은 서버 왕복 전 클라에서 즉시 안내(400 단일 문구보다 사유가 분명).
  if (!PHONE_PATTERN.test(phone.value.trim())) {
    errorMessage.value = '휴대폰 번호 형식이 올바르지 않습니다 (예: 010-1234-5678)'
    return
  }
  submitting.value = true
  try {
    const request: UpdateProfileRequest = { name: name.value, phone: phone.value }
    data.value = await updateProfile(request)
    successMessage.value = '프로필이 저장되었습니다'
  } catch (submitError) {
    const statusCode = (submitError as { statusCode?: number }).statusCode
    if (statusCode === 401) {
      await navigateTo(`/login?redirect=${encodeURIComponent('/mypage/profile')}`)
      return
    }
    // 검증(400) 등 → 사유 은닉·단일 문구.
    errorMessage.value = '저장에 실패했습니다. 입력을 확인하세요'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '프로필 · zslab-mall', description: 'zslab-mall 프로필' })

const vm: ProfilePageVm = reactive({
  data,
  pending,
  error,
  refresh,
  name,
  phone,
  submitting,
  errorMessage,
  successMessage,
  handleSubmit,
  NAME_MAX,
  PHONE_MAX,
})
</script>

<template>
  <component :is="useSkinView('ProfileView')" :vm="vm" />
</template>
