<script setup lang="ts">
import { onMounted } from 'vue'
import { useDialog, useMessage } from 'naive-ui'
import { bindFeedback, toast } from '@/utils/feedback'
import { setApiErrorNotifier } from '@/api/http'

/**
 * 桥接层：把 Naive UI 的 message / dialog 实例交给普通 ts 模块（api 错误提示、二次确认）。
 * 必须放在 n-message-provider / n-dialog-provider 内部。
 */
const message = useMessage()
const dialog = useDialog()

onMounted(() => {
  bindFeedback(message, dialog)
  setApiErrorNotifier((msg: string) => toast.error(msg))
})
</script>

<template>
  <span class="naive-bridge" />
</template>

<style scoped>
.naive-bridge {
  display: none;
}
</style>
