<script setup lang="ts">
import { WITHDRAW_NOTICE, demoAccountProtectedMessage } from '~/lib/constants/account'
import type { WithdrawPageVm } from '~/skins/contracts/withdraw'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const { withdraw } = useProfile()
const auth = useAuthStore()
const cart = useCartStore()

const agreed = ref<boolean>(false)
const submitting = ref<boolean>(false)
const errorMessage = ref<string>('')

async function handleWithdraw(): Promise<void> {
  if (submitting.value || !agreed.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await withdraw()
    // 세션 정리는 AppHeader.handleLogout과 동일 조합(auth·cart 순차) 후 홈 이동.
    auth.logout()
    cart.clear()
    await navigateTo('/')
  } catch (withdrawError) {
    const statusCode = (withdrawError as { statusCode?: number }).statusCode
    if (statusCode === 401) {
      await navigateTo(`/login?redirect=${encodeURIComponent('/mypage/withdraw')}`)
      return
    }
    // 409 MEMBER_ACTIVITY_IN_PROGRESS(Track 84·D-178): 진행 중 주문·클레임 보유 → 탈퇴·로그아웃 없이 사유 안내.
    const code = (withdrawError as { data?: { code?: unknown } }).data?.code
    if (statusCode === 409 && code === 'MEMBER_ACTIVITY_IN_PROGRESS') {
      errorMessage.value = '진행 중인 주문 또는 교환·반품이 있어 탈퇴할 수 없습니다.'
      return
    }
    const demoMessage = demoAccountProtectedMessage(withdrawError)
    if (demoMessage) {
      errorMessage.value = demoMessage
      return
    }
    errorMessage.value = '탈퇴에 실패했습니다. 잠시 후 다시 시도해 주세요'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '회원 탈퇴 · zslab-mall', description: 'zslab-mall 회원 탈퇴' })

const vm: WithdrawPageVm = reactive({ notice: WITHDRAW_NOTICE, agreed, submitting, errorMessage, handleWithdraw })
</script>

<template>
  <component :is="useSkinView('WithdrawView')" :vm="vm" />
</template>
