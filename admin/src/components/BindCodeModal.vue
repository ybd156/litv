<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { NButton, NModal, NSpin } from 'naive-ui'
import * as devicesApi from '@/api/modules/devices'
import { toErrorMessage } from '@/api/errors'
import { toast } from '@/utils/feedback'
import Icon from './Icon.vue'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const code = ref('')
const remaining = ref(0)
const loading = ref(false)
const errorText = ref('')
let timer: number | undefined

const digits = computed(() => code.value.split(''))
const expired = computed(() => remaining.value <= 0)
const countdown = computed(() => {
  const s = Math.max(0, remaining.value)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
})

function stopTimer(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

function startTimer(): void {
  stopTimer()
  timer = window.setInterval(() => {
    remaining.value -= 1
    if (remaining.value <= 0) stopTimer()
  }, 1000)
}

async function generate(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const res = await devicesApi.createBindCode()
    code.value = String(res.code ?? '')
    remaining.value = res.expires_in ?? 300
    startTimer()
  } catch (e) {
    errorText.value = toErrorMessage(e)
  } finally {
    loading.value = false
  }
}

async function copy(): Promise<void> {
  try {
    await navigator.clipboard.writeText(code.value)
    toast.success('已复制')
  } catch {
    toast.info(`绑定码：${code.value}`)
  }
}

watch(
  () => props.show,
  (open) => {
    if (open) {
      code.value = ''
      remaining.value = 0
      void generate()
    } else {
      stopTimer()
    }
  },
)

onBeforeUnmount(stopTimer)
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    title="生成绑定码"
    style="width: 480px; max-width: 92vw"
    :bordered="false"
    :mask-closable="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="wrap">
      <p class="lead">在电视 / 手机上打开 LITV，选「绑定设备」，输入下面这 6 位数字。</p>

      <div v-if="loading" class="code-box">
        <NSpin size="medium" />
      </div>

      <div v-else-if="errorText" class="err">
        <Icon name="warning" :size="16" />
        <span>{{ errorText }}</span>
      </div>

      <div v-else class="code-box" :class="{ expired }">
        <span v-for="(d, i) in digits" :key="i" class="digit">{{ d }}</span>
      </div>

      <p v-if="!loading && !errorText" class="countdown" :class="{ expired }">
        <template v-if="expired">已过期，重新生成一个吧</template>
        <template v-else>有效期还剩 {{ countdown }}</template>
      </p>

      <p class="tip">
        绑定码只用于换设备 token，5 分钟内有效。生成后请在「终端设备」页批准这台新设备。
      </p>
    </div>

    <template #footer>
      <div class="foot">
        <NButton size="small" quaternary @click="copy">
          <template #icon><Icon name="copy" :size="14" /></template>
          复制
        </NButton>
        <span class="spacer" />
        <NButton size="small" quaternary @click="emit('update:show', false)">关闭</NButton>
        <NButton size="small" type="primary" :loading="loading" @click="generate">
          <template #icon><Icon name="refresh" :size="14" /></template>
          {{ expired || errorText ? '重新生成' : '换一个' }}
        </NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.wrap {
  text-align: center;
  padding: 4px 0 2px;
}
.lead {
  margin: 0 0 18px;
  font-size: 13px;
  color: #93a5b1;
  line-height: 1.7;
}
.code-box {
  display: flex;
  justify-content: center;
  gap: 10px;
  min-height: 84px;
  align-items: center;
}
.code-box.expired {
  opacity: 0.35;
}
.digit {
  width: 52px;
  height: 68px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 36px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: #eaf6fa;
  background: #101a20;
  border: 1px solid #25333d;
  border-radius: 12px;
  box-shadow: inset 0 0 24px rgba(31, 162, 192, 0.12);
}
.countdown {
  margin: 16px 0 0;
  font-size: 13px;
  color: #5fd3ea;
  font-variant-numeric: tabular-nums;
}
.countdown.expired {
  color: #e0a94a;
}
.tip {
  margin: 14px 0 0;
  font-size: 12px;
  color: #6c7b86;
  line-height: 1.7;
}
.err {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #e4635c;
  font-size: 13px;
  min-height: 84px;
}
.foot {
  display: flex;
  align-items: center;
  gap: 8px;
}
.foot .spacer {
  flex: 1 1 auto;
}
</style>
