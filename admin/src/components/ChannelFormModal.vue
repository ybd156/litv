<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  NButton,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  NSpace,
  NSwitch,
} from 'naive-ui'
import * as channelsApi from '@/api/modules/channels'
import type { Channel, ChannelInput } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { toast } from '@/utils/feedback'

const props = defineProps<{
  show: boolean
  channel?: Channel | null
  groups: string[]
  defaultGroup?: string
}>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'saved'): void
}>()

const saving = ref(false)
const errors = reactive<{ name: string }>({ name: '' })

const form = reactive<ChannelInput>({
  name: '',
  group: '',
  number: undefined,
  logo: '',
  tvg_id: '',
  epg_id: null,
  enabled: true,
})

const isEdit = computed(() => Boolean(props.channel?.id))

const groupOptions = computed(() =>
  props.groups.map((g) => ({ label: g, value: g })),
)

watch(
  () => props.show,
  (open) => {
    if (!open) return
    errors.name = ''
    const c = props.channel
    form.name = c?.name ?? ''
    form.group = c?.group ?? props.defaultGroup ?? ''
    form.number = c?.number
    form.logo = c?.logo ?? ''
    form.tvg_id = c?.tvg_id ?? ''
    form.epg_id = c?.epg_id ?? null
    form.enabled = c?.enabled ?? true
  },
)

async function submit(): Promise<void> {
  errors.name = form.name.trim() ? '' : '频道名不能为空'
  if (errors.name) return
  saving.value = true
  try {
    const payload: ChannelInput = {
      name: form.name.trim(),
      group: (form.group || '').trim() || '未分组',
      number: typeof form.number === 'number' ? form.number : undefined,
      logo: (form.logo ?? '').trim(),
      tvg_id: (form.tvg_id ?? '').trim(),
      epg_id: form.epg_id ? String(form.epg_id) : null,
      enabled: form.enabled,
    }
    if (props.channel?.id) {
      await channelsApi.updateChannel(props.channel.id, payload)
      toast.success('已保存')
    } else {
      await channelsApi.createChannel(payload)
      toast.success('频道已添加')
    }
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
    :title="isEdit ? '编辑频道' : '新增频道'"
    style="width: 600px; max-width: 94vw"
    :bordered="false"
    :mask-closable="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <NForm label-placement="top" @submit.prevent>
      <NFormItem
        label="频道名"
        :feedback="errors.name"
        :validation-status="errors.name ? 'error' : undefined"
      >
        <NInput v-model:value="form.name" placeholder="例如：CCTV-1 综合" maxlength="60" />
      </NFormItem>

      <div class="two">
        <NFormItem label="分组">
          <NSelect
            v-model:value="form.group"
            :options="groupOptions"
            filterable
            tag
            clearable
            placeholder="选一个已有分组，或直接输入新的"
          />
        </NFormItem>
        <NFormItem label="频道号">
          <NInputNumber v-model:value="form.number" :min="1" :max="9999" placeholder="留空则自动排" style="width: 100%" />
        </NFormItem>
      </div>

      <NFormItem label="台标地址">
        <NInput v-model:value="form.logo" placeholder="https://…/cctv1.png（可留空）" />
      </NFormItem>

      <div class="two">
        <NFormItem label="tvg-id（源里的标识）">
          <NInput v-model:value="form.tvg_id" placeholder="例如 cctv1" />
        </NFormItem>
        <NFormItem label="EPG 频道 ID（用于节目单）">
          <NInput v-model:value="form.epg_id" placeholder="留空表示还没匹配" />
        </NFormItem>
      </div>

      <NFormItem label="启用">
        <NSpace align="center" :size="10">
          <NSwitch v-model:value="form.enabled" />
          <span class="tip">停用后电视上不会显示这个频道</span>
        </NSpace>
      </NFormItem>
    </NForm>

    <template #footer>
      <NSpace justify="end">
        <NButton quaternary @click="emit('update:show', false)">取消</NButton>
        <NButton type="primary" :loading="saving" @click="submit">
          {{ isEdit ? '保存' : '添加' }}
        </NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
.two {
  display: grid;
  grid-template-columns: 1fr 160px;
  gap: 12px;
}
@media (max-width: 560px) {
  .two {
    grid-template-columns: 1fr;
  }
}
.tip {
  font-size: 12px;
  color: #74838e;
}
</style>
