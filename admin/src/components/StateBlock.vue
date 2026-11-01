<script setup lang="ts">
import { NButton, NEmpty, NSpin } from 'naive-ui'
import Icon from './Icon.vue'

withDefaults(
  defineProps<{
    loading?: boolean
    error?: string
    empty?: boolean
    emptyTitle?: string
    emptyHint?: string
    retryText?: string
    minHeight?: number
  }>(),
  {
    loading: false,
    error: '',
    empty: false,
    emptyTitle: '这里还是空的',
    emptyHint: '',
    retryText: '重新加载',
    minHeight: 220,
  },
)

defineEmits<{ (e: 'retry'): void }>()
</script>

<template>
  <div
    class="state-block"
    :class="{ centered: loading || Boolean(error) || empty }"
    :style="{ minHeight: `${minHeight}px` }"
  >
    <div v-if="loading" class="inner">
      <n-spin size="large" />
      <p class="hint">正在加载…</p>
    </div>

    <div v-else-if="error" class="inner">
      <span class="badge danger"><Icon name="warning" :size="22" /></span>
      <p class="title">没加载出来</p>
      <p class="hint">{{ error }}</p>
      <n-button size="small" @click="$emit('retry')">{{ retryText }}</n-button>
    </div>

    <div v-else-if="empty" class="inner">
      <slot name="empty">
        <n-empty :description="emptyTitle" size="large">
          <template #extra>
            <p v-if="emptyHint" class="hint">{{ emptyHint }}</p>
            <slot name="empty-action" />
          </template>
        </n-empty>
      </slot>
    </div>

    <slot v-else />
  </div>
</template>

<style scoped>
.state-block {
  display: flex;
  flex-direction: column;
}
.state-block.centered {
  justify-content: center;
}
.inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 32px 16px;
  text-align: center;
}
.badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 14px;
}
.badge.danger {
  color: #e4635c;
  background: rgba(228, 99, 92, 0.14);
}
.title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #e6edf2;
}
.hint {
  margin: 0;
  max-width: 420px;
  font-size: 13px;
  line-height: 1.6;
  color: #7f8f9b;
}
</style>
