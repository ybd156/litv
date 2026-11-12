<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { NButton, NSwitch, NTag } from 'naive-ui'
import StateBlock from './StateBlock.vue'
import Icon from './Icon.vue'
import * as systemApi from '@/api/modules/system'
import { toErrorMessage } from '@/api/errors'
import { toast } from '@/utils/feedback'

const lines = ref<string[]>([])
const loading = ref(false)
const error = ref('')
/** 服务端给的说明（现在它才是空日志时唯一有用的信息） */
const hint = ref('')
const autoRefresh = ref(false)
const boxRef = ref<HTMLElement | null>(null)
let timer: number | undefined

const lineCount = computed(() => lines.value.length)

/**
 * 空态提示。
 *
 * ★ 不要替服务端编原因：`GET /system/logs` 目前返回 `{hint, lines: []}`，
 * 也就是说服务端还没做日志环形缓冲，空是**预期**的。以前这里写死
 * 「服务刚起来，或者日志级别设太高了」，把人往两个错误方向引——
 * 用户会去翻日志级别，而真正的原因是接口没接上。
 * 现在优先显示服务端那句 hint（日志文件到底在哪），实在没有才给中性文案。
 */
const emptyHint = computed(
  () => hint.value || '服务端暂未提供日志内容。服务端日志由 fnOS 写入数据目录，可在 NAS 上直接查看。',
)

async function load(silent = false): Promise<void> {
  loading.value = true
  if (!silent) error.value = ''
  try {
    const view = await systemApi.fetchLogView(200)
    lines.value = view.lines
    hint.value = view.hint
    await nextTick()
    if (boxRef.value) boxRef.value.scrollTop = boxRef.value.scrollHeight
  } catch (e) {
    error.value = toErrorMessage(e)
  } finally {
    loading.value = false
  }
}

watch(autoRefresh, (on) => {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
  if (on) {
    timer = window.setInterval(() => void load(true), 5000)
  }
})

function levelOf(line: string): 'error' | 'warn' | 'info' {
  if (/\b(ERROR|FATAL)\b/.test(line)) return 'error'
  if (/\bWARN\b/.test(line)) return 'warn'
  return 'info'
}

function copyAll(): void {
  void navigator.clipboard
    .writeText(lines.value.join('\n'))
    .then(() => toast.success('日志已复制'))
    .catch(() => toast.error('复制失败，浏览器可能不允许'))
}

onMounted(() => void load())
onBeforeUnmount(() => {
  if (timer !== undefined) window.clearInterval(timer)
})
</script>

<template>
  <div>
    <div class="toolbar">
      <NButton size="small" :loading="loading" @click="load()">
        <template #icon><Icon name="refresh" :size="15" /></template>
        刷新
      </NButton>
      <NSwitch v-model:value="autoRefresh" size="small" />
      <span class="hint-text">自动刷新（5 秒）</span>
      <NTag size="small" :bordered="false">{{ lineCount }} 行</NTag>
      <span class="spacer" />
      <NButton size="small" quaternary @click="copyAll">
        <template #icon><Icon name="copy" :size="14" /></template>
        复制全部
      </NButton>
    </div>

    <StateBlock :loading="loading && lines.length === 0" :error="error" :min-height="260" @retry="load()">
      <StateBlock
        :empty="lines.length === 0"
        empty-title="服务端没有返回日志内容"
        :empty-hint="emptyHint"
        :min-height="260"
      >
        <div ref="boxRef" class="log-box">
          <div v-for="(l, i) in lines" :key="i" class="log-line" :class="levelOf(l)">
            <span class="no">{{ i + 1 }}</span>
            <span class="txt">{{ l }}</span>
          </div>
        </div>
      </StateBlock>
    </StateBlock>

    <p class="hint-text" style="margin-top: 10px">
      显示最近 200 行。日志文件在 <code class="mono">$TRIM_PKGVAR/litv.log</code>（飞牛惯例路径）。
    </p>
  </div>
</template>

<style scoped>
.log-box {
  max-height: 420px;
  overflow: auto;
  padding: 10px 12px;
  border-radius: 11px;
  background: #0b1116;
  border: 1px solid #1c262e;
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.7;
}
.log-line {
  display: flex;
  gap: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  color: #a8bcc7;
}
.log-line.error {
  color: #e4635c;
}
.log-line.warn {
  color: #d8a856;
}
.no {
  flex: none;
  width: 34px;
  text-align: right;
  color: #3f4c56;
  user-select: none;
}
.txt {
  flex: 1 1 auto;
  min-width: 0;
}
</style>
