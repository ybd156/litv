<script setup lang="ts">
import { NButton, NEmpty, NSpin } from 'naive-ui'
import Icon from './Icon.vue'

/**
 * 列表/面板的「加载中 / 出错 / 空」三态唯一实现。
 *
 * ★ 为什么必须只有这一个：以前各页面自己写「正在加载…」「没数据」「失败了」，
 * 文案、间距、能不能重试全不一样；空态还经常只写「暂无数据」——
 * 用户既不知道为什么空，也不知道下一步该点哪。
 * 约定：
 *   空态  → `empty-title` 说「为什么空」，`empty-hint` 说「下一步做什么」，`#empty-action` 给按钮
 *   错误态 → `error` 必须是服务端真实原因 + 一句「下一步」（`errorHint`），外加重试按钮
 *   加载态 → 统一 spinner + 统一文案（`loadingText`）
 * `compact` 用于展开行/抽屉这类小区域，避免 32px padding 把行撑高。
 */
withDefaults(
  defineProps<{
    loading?: boolean
    error?: string
    empty?: boolean
    emptyTitle?: string
    emptyHint?: string
    /** 出错时补一句「下一步怎么做」；不传就不显示（不要编原因） */
    errorHint?: string
    retryText?: string
    loadingText?: string
    minHeight?: number
    /** 紧凑模式：给展开行/侧栏用，padding 和内边距都收小 */
    compact?: boolean
  }>(),
  {
    loading: false,
    error: '',
    empty: false,
    emptyTitle: '这里还是空的',
    emptyHint: '',
    errorHint: '',
    retryText: '重新加载',
    loadingText: '正在加载…',
    minHeight: 220,
    compact: false,
  },
)

defineEmits<{ (e: 'retry'): void }>()
</script>

<template>
  <div
    class="state-block"
    :class="{ centered: loading || Boolean(error) || empty, compact }"
    :style="{ minHeight: `${minHeight}px` }"
  >
    <div v-if="loading" class="inner">
      <n-spin :size="compact ? 'small' : 'large'" />
      <p class="hint">{{ loadingText }}</p>
    </div>

    <div v-else-if="error" class="inner">
      <span class="badge danger"><Icon name="warning" :size="compact ? 16 : 22" /></span>
      <p class="title">没加载出来</p>
      <p class="hint">{{ error }}</p>
      <p v-if="errorHint" class="hint next">{{ errorHint }}</p>
      <n-button size="small" @click="$emit('retry')">{{ retryText }}</n-button>
    </div>

    <div v-else-if="empty" class="inner">
      <slot name="empty">
        <n-empty :description="emptyTitle" :size="compact ? 'medium' : 'large'">
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
/* 紧凑模式：展开行里用，别让三态把整行撑得比内容还高 */
.compact .inner {
  padding: 14px 8px;
  gap: 6px;
}
.badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 14px;
}
.compact .badge {
  width: 28px;
  height: 28px;
  border-radius: 9px;
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
.compact .title {
  font-size: 13.5px;
}
.hint {
  margin: 0;
  max-width: 420px;
  font-size: 13px;
  line-height: 1.6;
  color: #7f8f9b;
}
.compact .hint {
  font-size: 12.5px;
}
/* 「下一步」那句用一点对比度，和错误原因区分开 */
.hint.next {
  color: #a9b8c3;
}
</style>
