<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import {
  NButton,
  NForm,
  NFormItem,
  NInputNumber,
  NModal,
  NRadio,
  NRadioGroup,
  NSelect,
  NSpace,
  NSwitch,
} from 'naive-ui'
import * as devicesApi from '@/api/modules/devices'
import type { AspectMode, DecodeMode, Device, DeviceConfig } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { toast } from '@/utils/feedback'

const props = defineProps<{ show: boolean; device: Device | null }>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'saved'): void
}>()

const saving = ref(false)

const DEFAULTS: DeviceConfig = {
  decode: 'auto',
  buffer_ms: 1500,
  aspect: 'auto',
  autoplay: true,
}

const form = reactive<DeviceConfig>({ ...DEFAULTS })

const aspectOptions: Array<{ label: string; value: AspectMode }> = [
  { label: '自动（按源比例）', value: 'auto' },
  { label: '16:9 宽屏', value: '16:9' },
  { label: '4:3 老电视', value: '4:3' },
  { label: '拉伸填满', value: 'fill' },
]

const decodeOptions: Array<{ label: string; value: DecodeMode; tip: string }> = [
  { label: '硬解', value: 'hard', tip: '用电视芯片解码，省 CPU，但老设备兼容性差' },
  { label: '软解', value: 'soft', tip: '兼容性最好，费 CPU' },
  { label: '自动', value: 'auto', tip: '先试硬解，失败自动回退软解（推荐）' },
]

watch(
  () => props.show,
  (open) => {
    if (!open) return
    const c = props.device?.config ?? {}
    form.decode = (c.decode as DecodeMode) ?? DEFAULTS.decode
    form.buffer_ms = typeof c.buffer_ms === 'number' ? c.buffer_ms : DEFAULTS.buffer_ms
    form.aspect = (c.aspect as AspectMode) ?? DEFAULTS.aspect
    form.autoplay = typeof c.autoplay === 'boolean' ? c.autoplay : DEFAULTS.autoplay
  },
)

async function submit(): Promise<void> {
  const d = props.device
  if (!d) return
  saving.value = true
  try {
    await devicesApi.updateDeviceConfig(d.id, {
      decode: form.decode,
      buffer_ms: form.buffer_ms ?? DEFAULTS.buffer_ms,
      aspect: form.aspect,
      autoplay: form.autoplay,
    })
    toast.success('配置已下发，设备下次心跳时生效')
    emit('update:show', false)
    emit('saved')
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="`远程配置 · ${device?.name ?? ''}`"
    style="width: 560px; max-width: 94vw"
    :bordered="false"
    :mask-closable="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <NForm label-placement="top" @submit.prevent>
      <NFormItem label="解码模式">
        <NRadioGroup v-model:value="form.decode">
          <NSpace :size="14">
            <NRadio v-for="o in decodeOptions" :key="o.value" :value="o.value" :label="o.label" />
          </NSpace>
        </NRadioGroup>
        <p class="tip">{{ decodeOptions.find((o) => o.value === form.decode)?.tip }}</p>
      </NFormItem>

      <NFormItem label="缓冲大小（毫秒）">
        <NInputNumber v-model:value="form.buffer_ms" :min="200" :max="10000" :step="100" style="width: 200px" />
        <p class="tip">缓冲越大越不容易卡，但换台会慢一点。一般 1000~2000 比较合适。</p>
      </NFormItem>

      <NFormItem label="画面比例">
        <NSelect v-model:value="form.aspect" :options="aspectOptions" style="width: 240px" />
      </NFormItem>

      <NFormItem label="启动自动播放">
        <NSpace align="center" :size="10">
          <NSwitch v-model:value="form.autoplay" />
          <span class="tip">打开 App 后直接播上次看的台</span>
        </NSpace>
      </NFormItem>
    </NForm>

    <template #footer>
      <NSpace justify="end">
        <NButton quaternary @click="emit('update:show', false)">取消</NButton>
        <NButton type="primary" :loading="saving" @click="submit">下发配置</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
.tip {
  margin: 6px 0 0;
  font-size: 12px;
  color: #74838e;
  line-height: 1.5;
}
</style>
