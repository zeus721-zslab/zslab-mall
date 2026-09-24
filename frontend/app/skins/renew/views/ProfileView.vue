<script setup lang="ts">
import type { ProfilePageVm } from '~/skins/contracts/profile'
import MypageFrame from '../components/MypageFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 회원 정보(FE-72). 폼 동작·검증·문구는 classic과 같은 페이지 vm을 쓴다 — 이메일은 자격증명이라 읽기 전용 표시만.
defineProps<{ vm: ProfilePageVm }>()

const CARD = 'max-w-[720px] rounded-[28px] bg-white p-6 md:p-8'
const LABEL = 'mb-1.5 block text-sm font-bold text-ink'
const INPUT =
  'h-12 w-full rounded-[14px] border border-line bg-white px-4 text-sm text-ink transition duration-200 placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
</script>

<template>
  <MypageFrame title="회원 정보">
    <section :class="CARD" aria-label="회원 정보 수정">
      <!-- 로딩 -->
      <div v-if="vm.pending" class="space-y-5" aria-hidden="true">
        <div v-for="index in 3" :key="index" class="space-y-2">
          <div class="h-3 w-16 rounded-full bg-surface-card"></div>
          <div class="h-12 rounded-[14px] bg-surface-card"></div>
        </div>
      </div>

      <!-- 에러 / 없음 -->
      <CommonErrorState v-else-if="vm.error || !vm.data" message="회원 정보를 불러오지 못했습니다" @retry="vm.refresh" />

      <!-- 폼 -->
      <form v-else class="space-y-5" @submit.prevent="vm.handleSubmit">
        <div>
          <label for="email" :class="LABEL">이메일</label>
          <input
            id="email"
            :value="vm.data.email"
            type="email"
            readonly
            class="h-12 w-full cursor-not-allowed rounded-[14px] border border-line bg-surface-page px-4 text-sm text-sub"
          />
        </div>

        <div class="grid gap-5 sm:grid-cols-2">
          <div>
            <label for="name" :class="LABEL">이름</label>
            <input
              id="name"
              v-model="vm.name"
              type="text"
              autocomplete="name"
              required
              :maxlength="vm.NAME_MAX"
              :class="INPUT"
              placeholder="이름을 입력하세요"
            />
          </div>
          <div>
            <label for="phone" :class="LABEL">휴대폰</label>
            <input
              id="phone"
              v-model="vm.phone"
              type="tel"
              autocomplete="tel"
              required
              :maxlength="vm.PHONE_MAX"
              :class="INPUT"
              placeholder="휴대폰 번호를 입력하세요"
            />
          </div>
        </div>

        <RenewNotice v-if="vm.errorMessage" tone="danger">{{ vm.errorMessage }}</RenewNotice>
        <RenewNotice v-if="vm.successMessage" tone="success">{{ vm.successMessage }}</RenewNotice>

        <button
          type="submit"
          class="flex h-14 w-full items-center justify-center gap-2 rounded-full bg-primary text-base font-bold text-primary-foreground transition duration-200 hover:bg-primary-hover focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-primary sm:w-48"
          :disabled="vm.submitting"
        >
          <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
          {{ vm.submitting ? '저장 중…' : '저장' }}
        </button>
      </form>
    </section>
  </MypageFrame>
</template>
