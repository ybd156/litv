<script setup lang="ts">
/**
 * 页面头（标题 + 说明 + 右侧操作区）。
 *
 * ★ `desc` 和 `#extra` 是必须有的：
 * 直播预览页（`views/LiveView.vue`）一直用 `desc="…"` 和 `#extra` 插槽放搜索框，
 * 但这个组件只认 `subtitle` 和 `#actions`。Vue 不会报错，它会**静默丢掉**
 * 没有对应出口的插槽内容——所以那个页面的搜索框从来没渲染出来过，
 * 用户只能在几百个台的列表里硬翻。别把这两个出口删掉。
 */
withDefaults(defineProps<{ title: string; subtitle?: string; desc?: string }>(), {
  subtitle: '',
  desc: '',
})
</script>

<template>
  <div class="page-header">
    <div class="text">
      <h1>{{ title }}</h1>
      <p v-if="subtitle || desc">{{ subtitle || desc }}</p>
    </div>
    <div class="actions">
      <slot name="extra" />
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 650;
  letter-spacing: 0.2px;
  color: #eaf1f5;
}
p {
  margin: 6px 0 0;
  font-size: 13px;
  color: #7f8f9b;
  max-width: 760px;
  line-height: 1.6;
}
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
