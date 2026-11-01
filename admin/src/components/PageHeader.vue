<script setup lang="ts">
withDefaults(defineProps<{ title: string; subtitle?: string }>(), { subtitle: '' })
</script>

<template>
  <div class="page-header">
    <div class="text">
      <h1>{{ title }}</h1>
      <p v-if="subtitle">{{ subtitle }}</p>
    </div>
    <div class="actions">
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
