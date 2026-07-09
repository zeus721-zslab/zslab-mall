<script setup lang="ts">
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
    errorMessage.value = '탈퇴에 실패했습니다. 잠시 후 다시 시도해 주세요'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '회원 탈퇴 · zslab-mall', description: 'zslab-mall 회원 탈퇴' })
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-sm px-4 md:px-6">
      <NuxtLink to="/mypage" class="mb-4 inline-block text-sm text-sub hover:underline">← 마이페이지</NuxtLink>
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">회원 탈퇴</h1>

      <div class="rounded-card border border-line p-5">
        <p class="text-sm text-ink">
          탈퇴하시면 계정이 비활성화되며 다시 로그인할 수 없습니다. 계속 진행하시겠어요?
        </p>

        <label class="mt-4 flex items-center gap-2 text-sm text-ink">
          <input v-model="agreed" type="checkbox" class="h-4 w-4 rounded border-line" />
          안내 사항을 확인했으며 탈퇴에 동의합니다
        </label>

        <p v-if="errorMessage" role="alert" class="mt-4 text-sm text-soldout">{{ errorMessage }}</p>

        <Button
          type="button"
          size="lg"
          variant="destructive"
          class="mt-4 w-full"
          :disabled="!agreed || submitting"
          @click="handleWithdraw"
        >
          {{ submitting ? '처리 중…' : '탈퇴하기' }}
        </Button>
      </div>
    </div>
  </div>
</template>
