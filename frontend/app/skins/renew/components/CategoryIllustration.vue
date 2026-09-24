<script setup lang="ts">
import type { CategoryIllustrationName } from '../category-theme'

// 카테고리 장식 일러스트(선 SVG·색은 부모 글자색). 의미 없는 장식이라 스크린리더에서 숨긴다.
defineProps<{ name: CategoryIllustrationName }>()
</script>

<template>
  <svg
    viewBox="0 0 48 48"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <template v-if="name === 'shirt'">
      <path d="M17 8l-9 5 3.5 7.5L16 18v22h16V18l4.5 2.5L40 13l-9-5c-1.2 3-4 5-7 5s-5.8-2-7-5z" />
      <path d="M21 26h6" />
    </template>
    <template v-else-if="name === 'mug'">
      <path d="M10 15h22v17a6 6 0 0 1-6 6H16a6 6 0 0 1-6-6V15z" />
      <path d="M32 19h3a5 5 0 0 1 0 10h-3" />
      <path d="M17 6c-1.2 2 1.2 3 0 5M24 6c-1.2 2 1.2 3 0 5" />
    </template>
    <template v-else-if="name === 'bottle'">
      <path d="M20 6h8v6l3 4v24a2 2 0 0 1-2 2H19a2 2 0 0 1-2-2V16l3-4V6z" />
      <path d="M17 23h14M17 33h14" />
    </template>
    <template v-else-if="name === 'bowl'">
      <path d="M7 22h34a17 17 0 0 1-34 0z" />
      <path d="M18 39h12" />
      <path d="M19 9c-1.2 2 1.2 3 0 5M26 9c-1.2 2 1.2 3 0 5" />
    </template>
    <template v-else-if="name === 'bag'">
      <path d="M10 17h28l-2.2 23H12.2L10 17z" />
      <path d="M18 17v-3a6 6 0 0 1 12 0v3" />
    </template>
    <template v-else-if="name === 'monitor'">
      <rect x="6" y="9" width="36" height="24" rx="3" />
      <path d="M20 33l-2 7M28 33l2 7M15 40h18" />
    </template>
    <template v-else-if="name === 'pencil'">
      <path d="M31 8l9 9-21 21-11 2 2-11L31 8z" />
      <path d="M27 12l9 9M10 29l9 9" />
    </template>
    <!-- 이름 매핑이 없는 카테고리 대체(FE-79) -->
    <template v-else-if="name === 'tag'">
      <path d="M8 10v13.2a3 3 0 0 0 .9 2.1l14.8 14.8a3 3 0 0 0 4.2 0l12.2-12.2a3 3 0 0 0 0-4.2L25.3 8.9A3 3 0 0 0 23.2 8H10a2 2 0 0 0-2 2z" />
      <circle cx="17" cy="17" r="3" />
    </template>
    <template v-else-if="name === 'box'">
      <path d="M24 6l16 8v20l-16 8-16-8V14l16-8z" />
      <path d="M8 14l16 8 16-8M24 22v20" />
    </template>
    <template v-else-if="name === 'sparkle'">
      <path d="M22 8l3.2 9.8L35 21l-9.8 3.2L22 34l-3.2-9.8L9 21l9.8-3.2L22 8z" />
      <path d="M36 30l1.4 4.1L41.5 35.5l-4.1 1.4L36 41l-1.4-4.1-4.1-1.4 4.1-1.4L36 30z" />
    </template>
    <template v-else>
      <rect x="9" y="9" width="12" height="12" rx="3" />
      <rect x="27" y="9" width="12" height="12" rx="3" />
      <rect x="9" y="27" width="12" height="12" rx="3" />
      <rect x="27" y="27" width="12" height="12" rx="3" />
    </template>
  </svg>
</template>
