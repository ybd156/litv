<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NButton,
  NDataTable,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NSpace,
  NTag,
  type DataTableColumns,
} from 'naive-ui'
import StateBlock from './StateBlock.vue'
import Icon from './Icon.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import * as epgApi from '@/api/modules/epg'
import type { EpgSource } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { confirmAction, toast } from '@/utils/feedback'
import { fmtCount, fmtDateTime, timeAgo } from '@/utils/format'

const { data, loading, error, run } = useAsyncData<EpgSource[]>(epgApi.listEpgSources)
const sources = computed(() => data.value ?? [])

const showAdd = ref(false)
const saving = ref(false)
const refreshing = ref(false)
const refreshingId = ref<number | null>(null)
const importing = ref(false)
/** 非 null 表示正在编辑这个源（A4） */
const editing = ref<EpgSource | null>(null)
/** 隐藏的文件输入，供「导入本地 XMLTV」触发 */
const importInput = ref<HTMLInputElement | null>(null)

const form = ref({
  name: '',
  url: '',
  // NInput 的 value 是 string；提交时再 Number()
  priority: '0',
  user_agent: '',
  referer: '',
})

function openAdd(): void {
  editing.value = null
  form.value = { name: '', url: '', priority: '0', user_agent: '', referer: '' }
  showAdd.value = true
}

function openEdit(row: EpgSource): void {
  editing.value = row
  form.value = {
    name: row.name,
    url: row.url,
    priority: String(row.priority ?? 0),
    user_agent: row.user_agent ?? '',
    referer: row.referer ?? '',
  }
  showAdd.value = true
}

async function refreshAll(): Promise<void> {
  refreshing.value = true
  try {
    const results = await epgApi.refreshEpg()
    // A3：把每个源的结果直接摊开说清楚，不再只报「已开始刷新」
    const list = Array.isArray(results) ? results : []
    const okCount = list.filter((r) => r?.ok).length
    const failed = list.filter((r) => r && !r.ok)
    if (failed.length > 0) {
      toast.warning(`刷新完成：${okCount} 个成功，${failed.length} 个失败（见状态列）`)
    } else if (list.length > 0) {
      toast.success(`刷新完成：${okCount} 个源已更新`)
    } else {
      toast.success('没有启用的 EPG 源')
    }
    await run(true)
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    refreshing.value = false
  }
}

/** 刷新单个源（A2）。 */
async function refreshRow(row: EpgSource): Promise<void> {
  refreshingId.value = row.id
  try {
    const r = await epgApi.refreshOneEpg(row.id)
    if (r?.ok) {
      toast.success(`${row.name}：${r.channels} 个频道 / ${r.programs} 条节目（${r.elapsed_ms} ms）`)
    } else {
      toast.error(`${row.name} 刷新失败：${r?.error || '未知原因'}`)
    }
    await run(true)
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    refreshingId.value = null
  }
}

/** 导入本地 XMLTV（A8）。 */
async function onImport(ev: Event): Promise<void> {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  importing.value = true
  try {
    await epgApi.importEpg(file, file.name)
    toast.success(`已导入 ${file.name}`)
    await run(true)
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    importing.value = false
    input.value = ''
  }
}

async function submit(): Promise<void> {
  if (!form.value.name.trim() || !form.value.url.trim()) {
    toast.warning('名称和地址都要填')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name.trim(),
      url: form.value.url.trim(),
      enabled: editing.value ? editing.value.enabled !== false : true,
      priority: Number(form.value.priority) || 0,
      user_agent: form.value.user_agent.trim(),
      referer: form.value.referer.trim(),
    }
    if (editing.value) {
      await epgApi.updateEpgSource(editing.value.id, payload)
      toast.success('已保存')
    } else {
      await epgApi.createEpgSource(payload)
      toast.success('EPG 源已添加')
    }
    showAdd.value = false
    editing.value = null
    form.value = { name: '', url: '', priority: '0', user_agent: '', referer: '' }
    await run(true)
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    saving.value = false
  }
}

function remove(row: EpgSource): void {
  confirmAction({
    title: '删除这个 EPG 源？',
    content: `「${row.name}」删掉后，用它做节目单的频道会没有节目信息。`,
    positiveText: '删除',
    onConfirm: async () => {
      await epgApi.deleteEpgSource(row.id)
      toast.success('已删除')
      await run(true)
    },
  })
}

const columns: DataTableColumns<EpgSource> = [
  {
    title: '名称',
    key: 'name',
    width: 220,
    render: (row) => h('span', { style: 'color:#e2ecf1;font-weight:500' }, row.name),
  },
  {
    title: '地址',
    key: 'url',
    minWidth: 240,
    ellipsis: { tooltip: true },
    render: (row) => h('span', { class: 'mono', style: 'color:#8fb6c4' }, row.url),
  },
  {
    title: '上次刷新',
    key: 'last_refresh_at',
    width: 150,
    render: (row) =>
      h('span', { class: row.last_refresh_at ? '' : 'muted', title: fmtDateTime(row.last_refresh_at) }, timeAgo(row.last_refresh_at)),
  },
  {
    title: '频道数',
    key: 'channel_count',
    width: 96,
    render: (row) => h('span', { class: 'mono' }, fmtCount(row.channel_count)),
  },
  {
    title: '节目条数',
    key: 'program_count',
    width: 110,
    render: (row) => h('span', { class: 'mono' }, fmtCount(row.program_count)),
  },
  {
    title: '优先级',
    key: 'priority',
    width: 90,
    render: (row) => h('span', { class: 'mono' }, String(row.priority ?? 0)),
  },
  {
    title: '状态',
    key: 'error',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: (row) =>
      row.error
        ? h(NTag, { size: 'small', type: 'warning', bordered: false }, { default: () => row.error })
        : h(NTag, { size: 'small', type: 'success', bordered: false }, { default: () => '正常' }),
  },
  {
    title: '操作',
    key: 'actions',
    width: 190,
    fixed: 'right',
    render: (row) =>
      h(NSpace, { size: 4, wrap: false }, {
        default: () => [
          h(
            NButton,
            {
              size: 'tiny',
              quaternary: true,
              loading: refreshingId.value === row.id,
              onClick: () => void refreshRow(row),
            },
            { default: () => '刷新' },
          ),
          h(
            NButton,
            { size: 'tiny', quaternary: true, onClick: () => openEdit(row) },
            { default: () => '编辑' },
          ),
          h(
            NButton,
            { size: 'tiny', quaternary: true, type: 'error', onClick: () => remove(row) },
            { default: () => '删除' },
          ),
        ],
      }),
  },
]

onMounted(() => void run())
</script>

<template>
  <div>
    <div class="toolbar">
      <NButton size="small" :loading="refreshing" type="primary" @click="refreshAll">
        <template #icon><Icon name="refresh" :size="15" /></template>
        立即刷新全部 EPG
      </NButton>
      <NButton size="small" secondary @click="openAdd">
        <template #icon><Icon name="plus" :size="15" /></template>
        添加 EPG 源
      </NButton>
      <!-- A8：导入本地 XMLTV（不上网，直接上传文件） -->
      <NButton size="small" secondary :loading="importing" @click="importInput?.click()">
        <template #icon><Icon name="up" :size="15" /></template>
        导入本地 XMLTV
      </NButton>
      <input
        ref="importInput"
        type="file"
        accept=".xml,.gz,.xml.gz,application/xml,application/gzip"
        style="display: none"
        @change="onImport"
      />
      <span class="spacer" />
      <span class="hint-text">EPG 就是电视上的「节目单」，刷新会重新下载 XMLTV 并解析。</span>
    </div>

    <div class="card-panel table-wrap">
      <StateBlock
        :loading="loading && !data"
        :error="error"
        :empty="sources.length === 0"
        :min-height="280"
        @retry="run()"
      >
        <template #empty-action>
          <NButton size="small" type="primary" @click="showAdd = true">
            <template #icon><Icon name="plus" :size="14" /></template>
            添加第一个 EPG 源
          </NButton>
        </template>
        <NDataTable
          :columns="columns"
          :data="sources"
          :row-key="(row: EpgSource) => row.id"
          :scroll-x="1180"
          :bordered="false"
          size="small"
          striped
        />
      </StateBlock>
    </div>

    <NModal
      v-model:show="showAdd"
      preset="card"
      :title="editing ? '编辑 EPG 源' : '添加 EPG 源'"
      style="width: 560px; max-width: 94vw"
      :bordered="false"
      :mask-closable="false"
    >
      <NForm label-placement="top" @submit.prevent>
        <NFormItem label="名称">
          <NInput v-model:value="form.name" placeholder="例如：EPG 中文总表（xmltv）" />
        </NFormItem>
        <NFormItem label="XMLTV 地址">
          <NInput v-model:value="form.url" placeholder="https://example.com/epg/cn.xml.gz" />
        </NFormItem>
        <NFormItem label="优先级（越大越优先）">
          <NInput v-model:value="form.priority" placeholder="0" />
          <template #feedback>
            同一个 xmltv_id 出现在多个源时，优先级高的覆盖低的。
          </template>
        </NFormItem>
        <NFormItem label="User-Agent（可选）">
          <NInput v-model:value="form.user_agent" placeholder="部分 EPG 站有防盗链，需要带 UA" />
        </NFormItem>
        <NFormItem label="Referer（可选）">
          <NInput v-model:value="form.referer" placeholder="https://example.com/" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton quaternary @click="showAdd = false">取消</NButton>
          <NButton type="primary" :loading="saving" @click="submit">
            {{ editing ? '保存' : '添加' }}
          </NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.table-wrap {
  padding: 6px 6px 2px;
  overflow: hidden;
}
</style>
