<script setup lang="ts">
import type { SignupPageVm } from '~/skins/contracts/signup'

defineProps<{ vm: SignupPageVm }>()
</script>

<template>
  <div class="flex min-h-[70vh] items-center justify-center px-4 py-12">
    <div class="w-full max-w-sm">
      <h1 class="mb-6 text-center text-2xl font-bold tracking-tight text-primary">회원가입</h1>

      <form class="space-y-4" @submit.prevent="vm.handleSubmit">
        <div class="space-y-1.5">
          <label for="email" class="block text-sm font-medium text-ink">이메일</label>
          <input
            id="email"
            v-model="vm.email"
            type="email"
            autocomplete="email"
            required
            :maxlength="vm.EMAIL_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="이메일을 입력하세요"
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

        <div class="space-y-1.5">
          <label for="password" class="block text-sm font-medium text-ink">비밀번호</label>
          <input
            id="password"
            v-model="vm.password"
            type="password"
            autocomplete="new-password"
            required
            :minlength="vm.PASSWORD_MIN"
            :maxlength="vm.PASSWORD_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            :placeholder="`비밀번호를 입력하세요 (${vm.PASSWORD_MIN}자 이상)`"
          />
        </div>

        <!-- 에러: 이메일 중복만 구분·그 외 단일 문구 -->
        <p v-if="vm.errorMessage" role="alert" class="text-sm text-soldout">{{ vm.errorMessage }}</p>

        <Button type="submit" size="lg" class="w-full" :disabled="vm.submitting">
          {{ vm.submitting ? '가입 중…' : '회원가입' }}
        </Button>
      </form>

      <p class="mt-6 text-center text-sm text-gray-500">
        이미 계정이 있으신가요?
        <NuxtLink to="/login" class="font-medium text-primary hover:underline">로그인</NuxtLink>
      </p>
    </div>
  </div>
</template>
