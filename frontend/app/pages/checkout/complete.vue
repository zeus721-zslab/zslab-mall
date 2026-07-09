<script setup lang="ts">
// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const cart = useCartStore()

// 결제 완료 후 mock 페이지가 관통시킨 주문번호. 직접 진입 등으로 없으면 주문번호만 생략한다(에러 아님).
const orderPublicId = String(route.query.orderPublicId ?? '')

// 완료 화면 진입 시 카트 서버 상태를 재조회한다. SUCCESS webhook의 AFTER_COMMIT 소진이 완료화면 진입보다
// 선행하므로(BE 정찰 확정·recon-report-fe-11-be-event) 이 시점 재조회는 비워진 카트를 반영한다.
// load 실패는 완료 안내를 막지 않는다(뱃지 정합만 지연·치명 아님·흡수).
onMounted(async () => {
  try {
    await cart.load()
  } catch {
    // 카트 재조회 실패는 주문 완료 표시에 영향 주지 않는다.
  }
})

useSeoMeta({ title: '주문 완료 · zslab-mall' })
</script>

<template>
  <div class="py-12 md:py-16">
    <div class="mx-auto max-w-[480px] px-4 text-center md:px-6">
      <h1 class="text-2xl font-bold tracking-tight text-ink">주문이 완료되었습니다</h1>
      <p class="mt-2 text-sm text-sub">결제가 정상적으로 처리되었습니다. 감사합니다.</p>

      <div v-if="orderPublicId" class="mt-6 rounded-card border border-line p-5">
        <p class="text-sm text-sub">주문번호</p>
        <p class="mt-1 break-all font-mono text-sm font-medium text-ink">{{ orderPublicId }}</p>
      </div>

      <div class="mt-8 space-y-2">
        <!-- FE-12에서 [주문 내역 보기]를 /orders/{orderPublicId} 상세로 교체. 지금은 존재 경로(상품 목록)로 임시 연결(404 방지). -->
        <Button size="lg" class="w-full" as-child>
          <NuxtLink to="/products">주문 내역 보기</NuxtLink>
        </Button>
        <Button size="lg" variant="outline" class="w-full" as-child>
          <NuxtLink to="/">홈으로</NuxtLink>
        </Button>
      </div>
    </div>
  </div>
</template>
