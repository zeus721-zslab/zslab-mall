<script setup lang="ts">
import { NAME_MAX, PHONE_MAX } from '~/lib/constants/account'
import type { UpdateProfileRequest } from '~/types/user'

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
  submitting.value = true
  errorMessage.value = ''
  successMessage.value = ''
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
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-sm px-4 md:px-6">
      <NuxtLink to="/mypage" class="mb-4 inline-block text-sm text-sub hover:underline">← 마이페이지</NuxtLink>
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">프로필</h1>

      <!-- 로딩 -->
      <div v-if="pending" class="space-y-4">
        <div class="h-11 animate-pulse rounded-control bg-gray-100"></div>
        <div class="h-11 animate-pulse rounded-control bg-gray-100"></div>
      </div>

      <!-- 에러 / 없음 -->
      <CommonErrorState v-else-if="error || !data" message="프로필을 불러오지 못했습니다" @retry="refresh" />

      <!-- 폼 -->
      <form v-else class="space-y-4" @submit.prevent="handleSubmit">
        <div class="space-y-1.5">
          <label for="email" class="block text-sm font-medium text-ink">이메일</label>
          <input
            id="email"
            :value="data.email"
            type="email"
            readonly
            class="w-full cursor-not-allowed rounded-control border border-line bg-gray-50 px-4 py-2.5 text-sm text-sub"
          />
        </div>

        <div class="space-y-1.5">
          <label for="name" class="block text-sm font-medium text-ink">이름</label>
          <input
            id="name"
            v-model="name"
            type="text"
            autocomplete="name"
            required
            :maxlength="NAME_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="이름을 입력하세요"
          />
        </div>

        <div class="space-y-1.5">
          <label for="phone" class="block text-sm font-medium text-ink">휴대폰</label>
          <input
            id="phone"
            v-model="phone"
            type="tel"
            autocomplete="tel"
            required
            :maxlength="PHONE_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="휴대폰 번호를 입력하세요"
          />
        </div>

        <p v-if="errorMessage" role="alert" class="text-sm text-soldout">{{ errorMessage }}</p>
        <p v-if="successMessage" role="status" class="text-sm text-primary">{{ successMessage }}</p>

        <Button type="submit" size="lg" class="w-full" :disabled="submitting">
          {{ submitting ? '저장 중…' : '저장' }}
        </Button>
      </form>
    </div>
  </div>
</template>
