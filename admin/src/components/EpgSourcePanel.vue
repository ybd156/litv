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
import type { EpgProgramHit, EpgSource, EpgStats } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { confirmAction, toast } from '@/utils/feedback'
import { fmtCount, fmtDateTime, timeAgo } from '@/utils/format'
import { canWrite } from '@/session'

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

// ---- D1 统计 / D2 清理 / D5 搜索 ----
const stats = ref<EpgStats | null>(null)
const cleaning = ref(false)
const searchQ = ref('')
const searching = ref(false)
const searchHits = ref<EpgProgramHit[]>([])
const showSearch = ref(false)

async function loadStats(): Promise<void> {
  try {
    stats.value = await epgApi.fetchEpgStats()
  } catch {
    stats.value = null
  }
}

function fmtBytes(n?: number): string {
  if (!n || n <= 0) return '—'
  const mb = n / (1024 * 1024)
  return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb.toFixed(1)} MB`
}

async function cleanup(): Promise<void> {
  const keep = stats.value?.keep_days ?? 3
  confirmAction({
    title: `清理 ${keep} 天前的节目？`,
    content: '只删除已经结束的旧节目，不影响正在播和未来的节目单。清理后需要重新刷新才能恢复。',
    positiveText: '清理',
    onConfirm: async () => {
      cleaning.value = true
      try {
        const r = await epgApi.cleanupEpg(keep)
        toast.success(r?.note || `已删除 ${r?.deleted ?? 0} 条`)
        await Promise.all([run(true), loadStats()])
      } catch (e) {
        toast.error(toErrorMessage(e))
      } finally {
        cleaning.value = false
      }
    },
  })
}

async function doSearch(): Promise<void> {
  const q = searchQ.value.trim()
  if (!q) {
    toast.warning('先输入要搜的节目名')
    return
  }
  searching.value = true
  showSearch.value = true
  try {
    searchHits.value = await epgApi.searchEpg(q)
  } catch (e) {
    toast.error(toErrorMessage(e))
    searchHits.value = []
  } finally {
    searching.value = false
  }
}

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
    await Promise.all([run(true), loadStats()])
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
    await Promise.all([run(true), loadStats()])
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
              disabled: !canWrite.value,
              loading: refreshingId.value === row.id,
              onClick: () => void refreshRow(row),
            },
            { default: () => '刷新' },
          ),
          h(
            NButton,
            {
              size: 'tiny',
              quaternary: true,
              disabled: !canWrite.value,
              onClick: () => openEdit(row),
            },
            { default: () => '编辑' },
          ),
          h(
            NButton,
            {
              size: 'tiny',
              quaternary: true,
              type: 'error',
              disabled: !canWrite.value,
              onClick: () => remove(row),
            },
            { default: () => '删除' },
          ),
        ],
      }),
  },
]

onMounted(() => {
  void run()
  void loadStats()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <NButton size="small" :loading="refreshing" type="primary" :disabled="!canWrite" @click="refreshAll">
        <template #icon><Icon name="refresh" :size="15" /></template>
        立即刷新全部 EPG
      </NButton>
      <NButton size="small" secondary :disabled="!canWrite" @click="openAdd">
        <template #icon><Icon name="plus" :size="15" /></template>
        添加 EPG 源
      </NButton>
      <!-- A8：导入本地 XMLTV（不上网，直接上传文件） -->
      <NButton size="small" secondary :loading="importing" :disabled="!canWrite" @click="importInput?.click()">
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
      <!-- D2：手动清理过期节目 -->
      <NButton size="small" secondary :loading="cleaning" :disabled="!canWrite" @click="cleanup">
        清理过期节目
      </NButton>
      <span class="spacer" />
      <!-- D5：按节目名搜索 -->
      <NInput
        v-model:value="searchQ"
        size="small"
        clearable
        placeholder="搜节目名，如「新闻」"
        style="width: 200px"
        @keyup.enter="doSearch"
      />
      <NButton size="small" secondary :loading="searching" @click="doSearch">搜索</NButton>
    </div>

    <!-- D1：总览统计 -->
    <div v-if="stats" class="stats-bar">
      <span>源 <b>{{ stats.enabled_sources }}/{{ stats.sources }}</b></span>
      <span>EPG 频道 <b>{{ fmtCount(stats.channels) }}</b></span>
      <span>节目 <b>{{ fmtCount(stats.programs) }}</b></span>
      <span>覆盖 <b>{{ stats.covered_days }}</b> 天</span>
      <span>占用 <b>{{ fmtBytes(stats.db_bytes) }}</b></span>
      <span>最近同步 <b>{{ stats.last_sync ? timeAgo(stats.last_sync) : '从未' }}</b></span>
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
          <NButton size="small" type="primary" :disabled="!canWrite" @click="showAdd = true">
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

    <!-- D5：搜索结果 -->
    <NModal
      v-model:show="showSearch"
      preset="card"
      :title="`搜索结果：${searchHits.length} 条`"
      style="width: 720px; max-width: 94vw"
      :bordered="false"
    >
      <div v-if="searching" class="muted">搜索中…</div>
      <div v-else-if="searchHits.length === 0" class="muted">没有匹配的节目</div>
      <div v-else class="hits">
        <div v-for="(h, i) in searchHits" :key="i" class="hit">
          <div class="hit-title">{{ h.title }}</div>
          <div class="hit-meta">
            <span>{{ h.channel_name || h.xmltv_id }}</span>
            <span v-if="h.category">· {{ h.category }}</span>
            <span>· {{ fmtDateTime(h.start_at) }} ~ {{ fmtDateTime(h.end_at) }}</span>
          </div>
        </div>
      </div>
      <template #footer>
        <NSpace justify="end">
          <NButton quaternary @click="showSearch = false">关闭</NButton>
        </NSpace>
      </template>
    </NModal>

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
.stats-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  padding: 8px 14px 10px;
  font-size: 12.5px;
  color: var(--text-2, #8b949e);
}
.stats-bar b {
  color: var(--text-1, #e6edf3);
  font-weight: 600;
}
.hits {
  max-height: 52vh;
  overflow: auto;
}
.hit {
  padding: 8px 2px;
  border-bottom: 1px solid rgba(139, 148, 158, 0.15);
}
.hit-title {
  color: #e2ecf1;
  font-size: 13.5px;
}
.hit-meta {
  margin-top: 2px;
  font-size: 12px;
  color: #8b949e;
}
.muted {
  color: #8b949e;
  font-size: 13px;
}
.table-wrap {
  padding: 6px 6px 2px;
  overflow: hidden;
}
</style>
