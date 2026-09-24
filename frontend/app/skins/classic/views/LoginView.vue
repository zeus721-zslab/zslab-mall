<script setup lang="ts">
import type { LoginPageVm } from '~/skins/contracts/login'

defineProps<{ vm: LoginPageVm }>()
</script>

<template>
  <div class="flex min-h-[70vh] items-center justify-center px-4 py-12">
    <div class="w-full max-w-sm">
      <h1 class="mb-6 text-center text-2xl font-bold tracking-tight text-primary">로그인</h1>

      <p v-if="vm.passwordChangedNotice" role="status" class="mb-4 rounded-card border border-line bg-gray-50 p-3 text-center text-sm text-ink" data-testid="login-password-changed-notice">
        비밀번호가 변경되었습니다. 다시 로그인해 주세요.
      </p>

      <form class="space-y-4" @submit.prevent="vm.handleSubmit">
        <div class="space-y-1.5">
          <label for="email" class="block text-sm font-medium text-ink">이메일</label>
          <input
            id="email"
            v-model="vm.email"
            type="email"
            autocomplete="email"
            required
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="이메일을 입력하세요"
          />
        </div>

        <div class="space-y-1.5">
          <label for="password" class="block text-sm font-medium text-ink">비밀번호</label>
          <input
            id="password"
            v-model="vm.password"
            type="password"
            autocomplete="current-password"
            required
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="비밀번호를 입력하세요"
          />
        </div>

        <!-- 에러: 사유 무관 단일 문구(계정 열거·자격 노출 방지) -->
        <p v-if="vm.errorMessage" role="alert" class="text-sm text-soldout">{{ vm.errorMessage }}</p>

        <Button type="submit" size="lg" class="w-full" :disabled="vm.submitting">
          {{ vm.submitting ? '로그인 중…' : '로그인' }}
        </Button>
      </form>

      <Button
        v-if="vm.demoEnabled"
        type="button"
        variant="outline"
        size="lg"
        class="mt-3 w-full"
        data-testid="demo-login"
        :disabled="vm.submitting"
        @click="vm.handleDemoLogin"
      >
        데모 계정으로 둘러보기
      </Button>
    </div>
  </div>
</template>
