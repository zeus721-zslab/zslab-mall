<script setup lang="ts">
import type { PasswordPageVm } from '~/skins/contracts/password'
import MypageFrame from '../components/MypageFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 비밀번호 변경(FE-72). 폼 동작·검증·임시 비밀번호 안내는 classic과 같은 페이지 vm을 쓴다(testid·안내 문구 동일).
defineProps<{ vm: PasswordPageVm }>()

const CARD = 'max-w-[720px] rounded-[28px] bg-white p-6 md:p-8'
const LABEL = 'mb-1.5 block text-sm font-bold text-ink'
const INPUT =
  'h-12 w-full rounded-[14px] border border-line bg-white px-4 text-sm text-ink transition duration-200 placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
</script>

<template>
  <MypageFrame title="비밀번호 변경">
    <div class="max-w-[720px] space-y-6">
      <RenewNotice v-if="vm.temporaryNotice" tone="info" data-testid="password-temporary-notice">
        임시 비밀번호로 로그인했습니다. 새 비밀번호로 변경해 주세요.
      </RenewNotice>

      <section :class="CARD" aria-label="비밀번호 변경">
        <form class="space-y-5" @submit.prevent="vm.handleSubmit">
          <div>
            <label for="currentPassword" :class="LABEL">현재 비밀번호</label>
            <input
              id="currentPassword"
              v-model="vm.currentPassword"
              data-testid="password-current"
              type="password"
              autocomplete="current-password"
              required
              :class="INPUT"
              placeholder="현재 비밀번호를 입력하세요"
            />
          </div>

          <div class="grid gap-5 sm:grid-cols-2">
            <div>
              <label for="newPassword" :class="LABEL">새 비밀번호</label>
              <input
                id="newPassword"
                v-model="vm.newPassword"
                data-testid="password-new"
                type="password"
                autocomplete="new-password"
                required
                :minlength="vm.PASSWORD_MIN"
                :maxlength="vm.PASSWORD_MAX"
                :class="INPUT"
                :placeholder="`새 비밀번호를 입력하세요 (${vm.PASSWORD_MIN}자 이상)`"
              />
            </div>
            <div>
              <label for="newPasswordConfirm" :class="LABEL">새 비밀번호 확인</label>
              <input
                id="newPasswordConfirm"
                v-model="vm.newPasswordConfirm"
                data-testid="password-new-confirm"
                type="password"
                autocomplete="new-password"
                required
                :minlength="vm.PASSWORD_MIN"
                :maxlength="vm.PASSWORD_MAX"
                :class="INPUT"
                placeholder="새 비밀번호를 다시 입력하세요"
              />
            </div>
          </div>

          <RenewNotice v-if="vm.errorMessage" tone="danger">{{ vm.errorMessage }}</RenewNotice>

          <button
            type="submit"
            class="flex h-14 w-full items-center justify-center gap-2 rounded-full bg-primary text-base font-bold text-primary-foreground transition duration-200 hover:bg-primary-hover focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-primary sm:w-48"
            :disabled="vm.submitting"
            data-testid="password-submit"
          >
            <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
            {{ vm.submitting ? '변경 중…' : '비밀번호 변경' }}
          </button>
        </form>
      </section>
    </div>
  </MypageFrame>
</template>
