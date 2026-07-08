<script setup lang="ts">
// FE-03 풀블리드 히어로 배너. CTA는 형태만이며 동작은 후속 트랙에서 연결한다.
</script>

<template>
  <section class="w-full bg-badge-new-bg">
    <div class="mx-auto max-w-[1240px] px-4 py-20 md:px-6 md:py-28">
      <div class="max-w-xl">
        <p class="text-sm font-medium uppercase tracking-widest text-primary">zslab-mall</p>
        <h1 class="mt-4 text-4xl font-bold leading-tight tracking-tight text-primary-hover md:text-5xl">
          당신의 취향을<br />가장 먼저 만나는 곳
        </h1>
        <p class="mt-5 text-lg text-sub">엄선한 신상품을 시원하게 둘러보세요.</p>
        <button
          type="button"
          class="mt-8 rounded-control bg-primary px-7 py-3 text-sm font-semibold text-white transition duration-normal hover:bg-primary-hover focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 focus-visible:ring-offset-badge-new-bg"
        >
          지금 둘러보기
        </button>
      </div>
    </div>
  </section>
</template>
