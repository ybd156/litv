<script setup lang="ts">
import { computed } from 'vue'
import { NSkeleton } from 'naive-ui'
import Icon from './Icon.vue'
import type { IconName } from './icons'

const props = withDefaults(
  defineProps<{
    label: string
    value: string | number
    unit?: string
    hint?: string
    icon?: IconName
    tone?: 'primary' | 'success' | 'warning' | 'danger' | 'neutral'
    loading?: boolean
  }>(),
  { unit: '', hint: '', icon: 'dashboard', tone: 'primary', loading: false },
)

const toneColor = computed(() => {
  switch (props.tone) {
    case 'success':
      return '#3FBF8F'
    case 'warning':
      return '#E0A94A'
    case 'danger':
      return '#E4635C'
    case 'neutral':
      return '#7f8f9b'
    default:
      return '#1FA2C0'
  }
})
</script>

<template>
  <div class="stat-card">
    <div class="head">
      <span class="icon" :style="{ color: toneColor, background: `${toneColor}1f` }">
        <Icon :name="icon" :size="18" />
      </span>
      <span class="label">{{ label }}</span>
    </div>
    <div class="value">
      <NSkeleton v-if="loading" text style="width: 72px; height: 30px" />
      <template v-else>
        <span class="num">{{ value }}</span>
        <span v-if="unit" class="unit">{{ unit }}</span>
      </template>
    </div>
    <p v-if="hint" class="hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.stat-card {
  position: relative;
  padding: 16px 18px 14px;
  border-radius: 14px;
  background: linear-gradient(160deg, #17212a 0%, #141c22 100%);
  border: 1px solid #1f2a32;
  overflow: hidden;
}
.stat-card::after {
  content: '';
  position: absolute;
  inset: 0 0 auto auto;
  width: 120px;
  height: 120px;
  background: radial-gradient(circle at 100% 0%, rgba(31, 162, 192, 0.16), transparent 70%);
  pointer-events: none;
}
.head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
}
.label {
  font-size: 13px;
  color: #93a5b1;
}
.value {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-top: 12px;
  min-height: 32px;
}
.num {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.1;
  color: #f0f6fa;
  font-variant-numeric: tabular-nums;
}
.unit {
  font-size: 13px;
  color: #7f8f9b;
}
.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #6c7b86;
  line-height: 1.5;
}
</style>
