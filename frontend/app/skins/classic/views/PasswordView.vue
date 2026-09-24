<script setup lang="ts">
import type { PasswordPageVm } from '~/skins/contracts/password'

defineProps<{ vm: PasswordPageVm }>()
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-sm px-4 md:px-6">
      <NuxtLink to="/mypage" class="mb-4 inline-block text-sm text-sub hover:underline">← 마이페이지</NuxtLink>
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">비밀번호 변경</h1>

      <p v-if="vm.temporaryNotice" role="status" class="mb-4 rounded-card border border-line bg-gray-50 p-3 text-sm text-ink" data-testid="password-temporary-notice">
        임시 비밀번호로 로그인했습니다. 새 비밀번호로 변경해 주세요.
      </p>

      <form class="space-y-4" @submit.prevent="vm.handleSubmit">
        <div class="space-y-1.5">
          <label for="currentPassword" class="block text-sm font-medium text-ink">현재 비밀번호</label>
          <input
            id="currentPassword"
            v-model="vm.currentPassword"
            data-testid="password-current"
            type="password"
            autocomplete="current-password"
            required
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="현재 비밀번호를 입력하세요"
          />
        </div>

        <div class="space-y-1.5">
          <label for="newPassword" class="block text-sm font-medium text-ink">새 비밀번호</label>
          <input
            id="newPassword"
            v-model="vm.newPassword"
            data-testid="password-new"
            type="password"
            autocomplete="new-password"
            required
            :minlength="vm.PASSWORD_MIN"
            :maxlength="vm.PASSWORD_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            :placeholder="`새 비밀번호를 입력하세요 (${vm.PASSWORD_MIN}자 이상)`"
          />
        </div>

        <div class="space-y-1.5">
          <label for="newPasswordConfirm" class="block text-sm font-medium text-ink">새 비밀번호 확인</label>
          <input
            id="newPasswordConfirm"
            v-model="vm.newPasswordConfirm"
            data-testid="password-new-confirm"
            type="password"
            autocomplete="new-password"
            required
            :minlength="vm.PASSWORD_MIN"
            :maxlength="vm.PASSWORD_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="새 비밀번호를 다시 입력하세요"
          />
        </div>

        <p v-if="vm.errorMessage" role="alert" class="text-sm text-soldout">{{ vm.errorMessage }}</p>

        <Button type="submit" size="lg" class="w-full" :disabled="vm.submitting" data-testid="password-submit">
          {{ vm.submitting ? '변경 중…' : '비밀번호 변경' }}
        </Button>
      </form>
    </div>
  </div>
</template>
