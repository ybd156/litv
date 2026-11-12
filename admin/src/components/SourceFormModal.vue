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
const form = reactive<SourceInput>({
  name: '',
  type: 'm3u',
  url: '',
  enabled: true,
  user_agent: '',
})
const errors = reactive<{ name: string; url: string }>({ name: '', url: '' })

const isEdit = computed(() => Boolean(props.source?.id))

/** 常见 UA 预设：源站校验 UA 时，先试这几个基本就够 */
const uaPresets: Array<{ label: string; value: string }> = [
  { label: '不设置（用全局默认）', value: '' },
  {
    label: 'Android 手机浏览器',
    value:
      'Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36',
  },
  {
    label: 'Android 电视盒子',
    value: 'Mozilla/5.0 (Linux; Android 9; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0 Safari/537.36',
  },
  {
    label: 'iPhone / Safari',
    value:
      'Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1',
  },
  { label: 'VLC', value: 'VLC/3.0.20 LibVLC/3.0.20' },
  { label: 'okhttp', value: 'okhttp/4.12.0' },
]

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
    form.user_agent = s?.user_agent ?? ''
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
      user_agent: (form.user_agent ?? '').trim(),
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

      <NFormItem label="User-Agent（可选）">
        <NSelect
          :value="form.user_agent || ''"
          :options="uaPresets"
          @update:value="(v: string) => (form.user_agent = v)"
        />
        <NInput
          v-model:value="form.user_agent"
          placeholder="留空用全局默认；源站校验 UA 时填这里"
          style="margin-top: 8px"
          maxlength="300"
        />
        <p class="tip">
          很多源站会校验 UA：不带就返回 403 或空列表（表现为「导入 0 个频道」）。
          填了之后，抓取源、服务端代理、客户端直连播放都会带这个 UA。
        </p>
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
