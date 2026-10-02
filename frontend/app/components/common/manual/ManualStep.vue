<script setup lang="ts">
import type { ManualCaptureMeta, ManualRole, ManualStep } from '~/types/manual'
import ManualCapture from '~/components/common/manual/ManualCapture.vue'

/**
 * 매뉴얼 단계 1개(C8): 순번·제목 → 설명 → 캡처 → 되돌릴 수 없는 동작 경고 → 규칙·제약 메모.
 * 경고 블록은 앱의 위험 조작 시각 규약(risk-action.css: 흰 바탕 + 2px 빨강 테두리 + 빨강 굵은 글자)을 그대로 따른다 —
 * 화면에서 본 위험 버튼과 매뉴얼의 경고가 같은 모양이어야 같은 성격의 동작으로 읽힌다.
 */
defineProps<{
  role: ManualRole
  anchorId: string
  order: number
  step: ManualStep
  capture: ManualCaptureMeta | null
}>()
</script>

<template>
  <article :id="anchorId" class="manual-step" tabindex="-1" :data-testid="`manual-step-${step.id}`">
    <span class="manual-step__order" aria-hidden="true">{{ order }}</span>
    <h3 class="manual-step__title">{{ step.title }}</h3>
    <p v-for="(paragraph, index) in step.paragraphs" :key="index" class="manual-step__paragraph">{{ paragraph }}</p>

    <ManualCapture v-if="step.captureId" :role="role" :capture="capture" :alt="step.captureAlt" :callouts="step.callouts" />

    <aside v-for="warning in step.warnings" :key="warning.title" class="manual-irreversible" data-testid="manual-irreversible">
      <p class="manual-irreversible__title">되돌릴 수 없는 동작: {{ warning.title }}</p>
      <p class="manual-irreversible__body">{{ warning.body }}</p>
    </aside>

    <section v-for="rule in step.rules" :key="rule.title" class="manual-rule" data-testid="manual-rule">
      <h4 class="manual-rule__title">{{ rule.title }}</h4>
      <ul class="manual-rule__items">
        <li v-for="(item, index) in rule.items" :key="index">{{ item }}</li>
      </ul>
    </section>
  </article>
</template>

<style scoped>
.manual-step {
  position: relative;
  padding: 0 0 56px 52px;
  scroll-margin-top: var(--manual-scroll-offset, 88px);
}

.manual-step:focus {
  outline: none;
}

/* 단계 순번을 잇는 세로선 — 흐름이 순서대로 이어진다는 정보를 선이 전한다. */
.manual-step::before {
  content: '';
  position: absolute;
  top: 34px;
  bottom: 6px;
  left: 15px;
  width: 2px;
  background: linear-gradient(rgba(var(--v-theme-primary), 0.35), rgba(var(--v-theme-primary), 0.08));
}

.manual-step:last-of-type::before {
  display: none;
}

.manual-step__order {
  position: absolute;
  top: 0;
  left: 0;
  width: 32px;
  height: 32px;
  border: 2px solid rgb(var(--v-theme-primary));
  border-radius: 50%;
  background: #fff;
  color: rgb(var(--v-theme-primary));
  font-size: 14px;
  font-weight: 750;
  line-height: 28px;
  text-align: center;
}

.manual-step__title {
  margin: 2px 0 12px;
  color: #18181b;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.01em;
  line-height: 28px;
}

.manual-step__paragraph {
  max-width: 68ch;
  margin: 0 0 10px;
  color: #3f3f46;
  font-size: 15px;
  line-height: 27px;
}

.manual-irreversible {
  max-width: 76ch;
  margin: 18px 0 0;
  padding: 14px 18px;
  border: 2px solid var(--op-risk-fg, #b91c1c);
  border-radius: 10px;
  background: #fff;
}

.manual-irreversible__title {
  margin: 0 0 4px;
  color: var(--op-risk-fg, #b91c1c);
  font-size: 14.5px;
  font-weight: 700;
  line-height: 22px;
}

.manual-irreversible__body {
  margin: 0;
  color: #3f3f46;
  font-size: 14px;
  line-height: 23px;
}

.manual-rule {
  max-width: 76ch;
  margin: 14px 0 0;
  padding: 14px 18px;
  border-radius: 10px;
  background: #f4f4f5;
}

.manual-rule__title {
  margin: 0 0 6px;
  color: #18181b;
  font-size: 14px;
  font-weight: 700;
  line-height: 22px;
}

.manual-rule__items {
  margin: 0;
  padding-left: 18px;
  color: #3f3f46;
  font-size: 14px;
  line-height: 23px;
}

.manual-rule__items li + li {
  margin-top: 4px;
}
</style>
