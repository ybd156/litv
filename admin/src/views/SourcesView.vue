<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NButton,
  NDataTable,
  NSpace,
  NTag,
  NTooltip,
  type DataTableColumns,
} from 'naive-ui'
import PageHeader from '@/components/PageHeader.vue'
import StateBlock from '@/components/StateBlock.vue'
import SourceFormModal from '@/components/SourceFormModal.vue'
import PreviewDrawer from '@/components/PreviewDrawer.vue'
import Icon from '@/components/Icon.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import * as sourcesApi from '@/api/modules/sources'
import type { Source } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { confirmAction, toast } from '@/utils/feedback'
import { fmtDateTime, timeAgo } from '@/utils/format'
import { derivedSourceStatus, sourceError } from '@/utils/sourceStatus'
import { canWrite } from '@/session'

const { data, loading, error, run } = useAsyncData<Source[]>(sourcesApi.listSources)
const sources = computed(() => data.value ?? [])

const formShow = ref(false)
const editing = ref<Source | null>(null)
const previewShow = ref(false)
const previewTarget = ref<Source | null>(null)
const refreshing = ref<number[]>([])

const enabledCount = computed(() => sources.value.filter((s) => s.enabled).length)

/**
 * 状态推导只在 `utils/sourceStatus.ts` 里写一次（顺序与服务端一致：
 * `last_error` → `never` → `ok`），概览页也用同一份。
 * 两处各写一遍迟早出现「源管理说失败、概览说正常」。
 */
const failedCount = computed(
  () => sources.value.filter((s) => derivedSourceStatus(s) === 'error').length,
)

const TYPE_LABEL: Record<string, string> = {
  m3u: 'M3U',
  txt: 'TXT',
  tvbox: 'TVBox',
  json: 'JSON',
}

function statusOf(row: Source): { text: string; type: 'success' | 'error' | 'warning' | 'default' } {
  if (!row.enabled) return { text: '已停用', type: 'default' }
  const st = derivedSourceStatus(row)
  if (st === 'error') return { text: '拉取失败', type: 'error' }
  if (st === 'never') return { text: '还没拉过', type: 'warning' }
  return { text: '正常', type: 'success' }
}

function openCreate(): void {
  editing.value = null
  formShow.value = true
}

function openEdit(row: Source): void {
  editing.value = row
  formShow.value = true
}

function openPreview(row: Source): void {
  previewTarget.value = row
  previewShow.value = true
}

async function refreshOne(row: Source): Promise<void> {
  refreshing.value = [...refreshing.value, row.id]
  try {
    await sourcesApi.refreshSource(row.id)
    toast.success(`「${row.name}」已重新拉取`)
    await run(true)
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    refreshing.value = refreshing.value.filter((id) => id !== row.id)
  }
}

function remove(row: Source): void {
  // 文案必须说真话：服务端 DeleteSource 是级联删除（`DELETE FROM channels WHERE source_id=?`），
  // 而且 ImportChannels 的判重键是 (name, group) **不含 source_id**，
  // 所以别的源里同名的频道也可能一起被删掉。这句之前写的是「不会自动消失」，正好说反了。
  const n = row.channel_count ?? 0
  confirmAction({
    title: '删除这个源？',
    content:
      `「${row.name}」会从源列表里移除，并一并删除它导入的 ${n} 个频道和这些频道的线路，不可撤销。` +
      `另外，其它源里同名的频道也可能被一并删除。电视上这些台会立刻消失。`,
    positiveText: '删除',
    onConfirm: async () => {
      await sourcesApi.deleteSource(row.id)
      toast.success('已删除')
      await run(true)
    },
  })
}

const columns: DataTableColumns<Source> = [
  {
    title: '名称',
    key: 'name',
    width: 200,
    fixed: 'left',
    render: (row) =>
      h('div', { class: 'name-cell' }, [
        h('span', { class: 'nm' }, row.name),
        row.channel_count ? h('span', { class: 'cnt' }, `${row.channel_count} 个频道`) : null,
      ]),
  },
  {
    title: '类型',
    key: 'type',
    width: 92,
    render: (row) => h(NTag, { size: 'small', bordered: false }, { default: () => TYPE_LABEL[row.type] ?? row.type }),
  },
  {
    title: '地址',
    key: 'url',
    minWidth: 240,
    ellipsis: { tooltip: true },
    render: (row) => h('span', { class: 'mono', style: 'color:#8fb6c4' }, row.url),
  },
  {
    title: '状态',
    key: 'status',
    width: 104,
    render: (row) => {
      const s = statusOf(row)
      return h(NTag, { size: 'small', type: s.type, bordered: false }, { default: () => s.text })
    },
  },
  {
    title: '多久没更新了',
    key: 'last_fetch_at',
    width: 150,
    render: (row) =>
      h(NTooltip, null, {
        trigger: () => h('span', { class: row.last_fetch_at ? '' : 'muted' }, timeAgo(row.last_fetch_at)),
        default: () => (row.last_fetch_at ? fmtDateTime(row.last_fetch_at) : '这条源还没成功拉取过'),
      }),
  },
  {
    title: '错误信息',
    key: 'error',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: (row) => {
      // 新服务端给 error（= last_error），老服务端只有 last_error —— 两个都读，
      // 否则这个列在真实环境里永远是「—」，用户看不到源站为什么失败。
      const msg = sourceError(row)
      return msg
        ? h('span', { style: 'color:#e4635c' }, msg)
        : h('span', { class: 'muted' }, '—')
    },
  },
  {
    title: '操作',
    key: 'actions',
    width: 236,
    fixed: 'right',
    render: (row) =>
      h(NSpace, { size: 4, wrap: false }, {
        default: () => [
          h(
            NButton,
            { size: 'tiny', quaternary: true, type: 'primary', onClick: () => openPreview(row) },
            { default: () => '预览' },
          ),
          h(
            NButton,
            {
              size: 'tiny',
              quaternary: true,
              // 刷新会写库（导入频道），非管理员会被服务端 403；按钮先禁掉
              disabled: !canWrite.value,
              loading: refreshing.value.includes(row.id),
              onClick: () => void refreshOne(row),
            },
            { default: () => '立即刷新' },
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

onMounted(() => void run())
</script>

<template>
  <div>
    <PageHeader
      title="源管理"
      subtitle="订阅源是频道的来源。加进来之后先「预览」看看解析对不对，再「立即刷新」入库。"
    >
      <template #actions>
        <NButton size="small" :loading="loading" @click="run(true)">
          <template #icon><Icon name="refresh" :size="15" /></template>
          刷新列表
        </NButton>
        <NButton size="small" type="primary" :disabled="!canWrite" @click="openCreate">
          <template #icon><Icon name="plus" :size="15" /></template>
          添加源
        </NButton>
      </template>
    </PageHeader>

    <div class="summary">
      <NTag size="small" :bordered="false">共 {{ sources.length }} 个源</NTag>
      <NTag size="small" :bordered="false" type="success">{{ enabledCount }} 个启用</NTag>
      <NTag v-if="failedCount" size="small" :bordered="false" type="error">
        {{ failedCount }} 个拉取失败
      </NTag>
    </div>

    <div class="card-panel table-wrap">
      <StateBlock
        :loading="loading && !data"
        :error="error"
        :empty="sources.length === 0"
        :min-height="320"
        @retry="run()"
      >
        <template #empty-action>
          <NButton type="primary" size="small" :disabled="!canWrite" @click="openCreate">
            <template #icon><Icon name="plus" :size="14" /></template>
            导入第一条 M3U
          </NButton>
        </template>
        <NDataTable
          :columns="columns"
          :data="sources"
          :row-key="(row: Source) => row.id"
          :scroll-x="1240"
          :bordered="false"
          size="small"
          striped
        />
      </StateBlock>
    </div>

    <SourceFormModal v-model:show="formShow" :source="editing" @saved="run(true)" />
    <PreviewDrawer v-model:show="previewShow" :source="previewTarget" />
  </div>
</template>

<style scoped>
.summary {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.table-wrap {
  padding: 6px 6px 2px;
  overflow: hidden;
}
:deep(.name-cell) {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
:deep(.name-cell .nm) {
  font-weight: 500;
  color: #e2ecf1;
}
:deep(.name-cell .cnt) {
  font-size: 11.5px;
  color: #6c7b86;
}
</style>
