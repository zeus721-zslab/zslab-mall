<script setup lang="ts">
import type { ProfilePageVm } from '~/skins/contracts/profile'

defineProps<{ vm: ProfilePageVm }>()
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-sm px-4 md:px-6">
      <NuxtLink to="/mypage" class="mb-4 inline-block text-sm text-sub hover:underline">← 마이페이지</NuxtLink>
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">프로필</h1>

      <!-- 로딩 -->
      <div v-if="vm.pending" class="space-y-4">
        <div class="h-11 animate-pulse rounded-control bg-gray-100"></div>
        <div class="h-11 animate-pulse rounded-control bg-gray-100"></div>
      </div>

      <!-- 에러 / 없음 -->
      <CommonErrorState v-else-if="vm.error || !vm.data" message="프로필을 불러오지 못했습니다" @retry="vm.refresh" />

      <!-- 폼 -->
      <form v-else class="space-y-4" @submit.prevent="vm.handleSubmit">
        <div class="space-y-1.5">
          <label for="email" class="block text-sm font-medium text-ink">이메일</label>
          <input
            id="email"
            :value="vm.data.email"
            type="email"
            readonly
            class="w-full cursor-not-allowed rounded-control border border-line bg-gray-50 px-4 py-2.5 text-sm text-sub"
          />
        </div>

        <div class="space-y-1.5">
          <label for="name" class="block text-sm font-medium text-ink">이름</label>
          <input
            id="name"
            v-model="vm.name"
            type="text"
            autocomplete="name"
            required
            :maxlength="vm.NAME_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="이름을 입력하세요"
          />
        </div>

        <div class="space-y-1.5">
          <label for="phone" class="block text-sm font-medium text-ink">휴대폰</label>
          <input
            id="phone"
            v-model="vm.phone"
            type="tel"
            autocomplete="tel"
            required
            :maxlength="vm.PHONE_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="휴대폰 번호를 입력하세요"
          />
        </div>

        <p v-if="vm.errorMessage" role="alert" class="text-sm text-soldout">{{ vm.errorMessage }}</p>
        <p v-if="vm.successMessage" role="status" class="text-sm text-primary">{{ vm.successMessage }}</p>

        <Button type="submit" size="lg" class="w-full" :disabled="vm.submitting">
          {{ vm.submitting ? '저장 중…' : '저장' }}
        </Button>
      </form>
    </div>
  </div>
</template>
