<script setup lang="ts">
import type { PasswordPageVm } from '~/skins/contracts/password'
import MypageFrame from '../components/MypageFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 비밀번호 변경(FE-72). 폼 동작·검증·임시 비밀번호 안내는 classic과 같은 페이지 vm을 쓴다(testid·안내 문구 동일).
defineProps<{ vm: PasswordPageVm }>()

const CARD = 'rounded-card bg-white p-5 shadow-e1 md:p-6'
const LABEL = 'mb-1.5 block text-small font-bold text-ink'
const INPUT =
  'h-12 w-full rounded-control border border-line bg-white px-4 text-body text-ink transition duration-fast ease-soft placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
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

          <button type="submit" class="btn btn-primary btn-lg w-full sm:w-48" :disabled="vm.submitting" data-testid="password-submit">
            <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
            {{ vm.submitting ? '변경 중…' : '비밀번호 변경' }}
          </button>
        </form>
      </section>
    </div>
  </MypageFrame>
</template>
