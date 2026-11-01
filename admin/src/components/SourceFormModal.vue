<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { NButton, NForm, NFormItem, NInput, NModal, NSelect, NSpace, NSwitch } from 'naive-ui'
import * as sourcesApi from '@/api/modules/sources'
import type { Source, SourceInput, SourceType } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { toast } from '@/utils/feedback'

const props = defineProps<{ show: boolean; source?: Source | null }>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'saved'): void
}>()

const saving = ref(false)
const form = reactive<SourceInput>({ name: '', type: 'm3u', url: '', enabled: true })
const errors = reactive<{ name: string; url: string }>({ name: '', url: '' })

const isEdit = computed(() => Boolean(props.source?.id))

const typeOptions: Array<{ label: string; value: SourceType; tip: string }> = [
  { label: 'M3U / M3U8 播放列表', value: 'm3u', tip: '#EXTINF 格式，最常见' },
  { label: 'TXT 频道表', value: 'txt', tip: '「分组,#genre#」+「名称,地址」两列文本' },
  { label: 'TVBox 直播 JSON', value: 'tvbox', tip: 'TVBox 的 lives 数组配置' },
  { label: '自定义 JSON', value: 'json', tip: '自建接口返回的 JSON 列表' },
]

watch(
  () => props.show,
  (open) => {
    if (!open) return
    errors.name = ''
    errors.url = ''
    const s = props.source
    form.name = s?.name ?? ''
    form.type = (s?.type as SourceType) ?? 'm3u'
    form.url = s?.url ?? ''
    form.enabled = s?.enabled ?? true
  },
)

function validate(): boolean {
  errors.name = form.name.trim() ? '' : '给它起个名字，方便以后认出来'
  const url = form.url.trim()
  if (!url) errors.url = '地址不能为空'
  else if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(url) && !url.startsWith('/'))
    errors.url = '要填完整地址，例如 https://example.com/live.m3u'
  else errors.url = ''
  return !errors.name && !errors.url
}

async function submit(): Promise<void> {
  if (!validate()) return
  saving.value = true
  try {
    const payload: SourceInput = {
      name: form.name.trim(),
      type: form.type,
      url: form.url.trim(),
      enabled: form.enabled,
    }
    if (props.source?.id) {
      await sourcesApi.updateSource(props.source.id, payload)
      toast.success('已保存')
    } else {
      await sourcesApi.createSource(payload)
      toast.success('源已添加，建议先点「预览」看看解析对不对')
    }
    emit('update:show', false)
    emit('saved')
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    saving.value = false
  }
}

function close(): void {
  emit('update:show', false)
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="isEdit ? '编辑源' : '添加源'"
    style="width: 620px; max-width: 94vw"
    :bordered="false"
    :mask-closable="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <NForm label-placement="top" @submit.prevent>
      <NFormItem label="名称" :feedback="errors.name" :validation-status="errors.name ? 'error' : undefined">
        <NInput v-model:value="form.name" placeholder="例如：央视频道 4K 源" maxlength="40" />
      </NFormItem>

      <NFormItem label="类型">
        <NSelect v-model:value="form.type" :options="typeOptions" />
        <p class="tip">{{ typeOptions.find((t) => t.value === form.type)?.tip }}</p>
      </NFormItem>

      <NFormItem label="地址" :feedback="errors.url" :validation-status="errors.url ? 'error' : undefined">
        <NInput
          v-model:value="form.url"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 5 }"
          placeholder="https://example.com/live.m3u"
        />
      </NFormItem>

      <NFormItem label="启用">
        <NSpace align="center" :size="10">
          <NSwitch v-model:value="form.enabled" />
          <span class="tip">关掉后不会自动刷新，但频道还在</span>
        </NSpace>
      </NFormItem>
    </NForm>

    <template #footer>
      <NSpace justify="end">
        <NButton quaternary @click="close">取消</NButton>
        <NButton type="primary" :loading="saving" @click="submit">
          {{ isEdit ? '保存' : '添加' }}
        </NButton>
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
