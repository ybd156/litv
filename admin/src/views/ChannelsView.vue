<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NButton,
  NCheckbox,
  NCheckboxGroup,
  NDataTable,
  NInput,
  NModal,
  NPopover,
  NSelect,
  NSpace,
  NTag,
  NTooltip,
  type DataTableColumns,
  type DataTableRowKey,
} from 'naive-ui'
import type { HTMLAttributes } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import StateBlock from '@/components/StateBlock.vue'
import ChannelFormModal from '@/components/ChannelFormModal.vue'
import ChannelStreamsPanel from '@/components/ChannelStreamsPanel.vue'
import Icon from '@/components/Icon.vue'
import { useDragSort } from '@/composables/useDragSort'
import * as channelsApi from '@/api/modules/channels'
import type { Channel, GroupStat } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { confirmAction, toast } from '@/utils/feedback'

const groups = ref<GroupStat[]>([])
const rows = ref<Channel[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const loaded = ref(false)

const group = ref('')
const keyword = ref('')
const page = ref(1)
const pageSize = ref(20)
const checkedKeys = ref<DataTableRowKey[]>([])

const formShow = ref(false)
const editing = ref<Channel | null>(null)

const batchGroupShow = ref(false)
const batchGroupValue = ref('')

const ALL_COLUMN_KEYS = ['logo', 'number', 'name', 'group', 'streams', 'epg', 'status'] as const
const COLUMN_LABELS: Record<string, string> = {
  logo: '台标',
  number: '频道号',
  name: '名称',
  group: '分组',
  streams: '线路数',
  epg: 'EPG',
  status: '状态',
}
const visibleColumns = ref<string[]>([...ALL_COLUMN_KEYS])

const totalAll = computed(() => groups.value.reduce((sum, g) => sum + g.count, 0))
const groupNames = computed(() => groups.value.map((g) => g.name))
const selectedIds = computed(() => checkedKeys.value.map((k) => Number(k)))

async function loadGroups(): Promise<void> {
  try {
    groups.value = await channelsApi.listGroups()
  } catch {
    // 分组侧栏失败不阻塞主表
    groups.value = []
  }
}

async function loadChannels(silent = false): Promise<void> {
  loading.value = true
  if (!silent) error.value = ''
  try {
    const res = await channelsApi.listChannels({
      group: group.value || undefined,
      q: keyword.value.trim() || undefined,
      page: page.value,
      size: pageSize.value,
    })
    rows.value = res.items
    total.value = res.total
    loaded.value = true
    error.value = ''
  } catch (e) {
    error.value = toErrorMessage(e)
  } finally {
    loading.value = false
  }
}

async function reloadAll(): Promise<void> {
  checkedKeys.value = []
  await Promise.all([loadGroups(), loadChannels(true)])
}

let searchTimer: number | undefined
watch(keyword, () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    if (page.value === 1) void loadChannels()
    else page.value = 1
  }, 320)
})

watch(group, () => {
  if (page.value === 1) void loadChannels()
  else page.value = 1
})

watch([page, pageSize], () => void loadChannels(true))

function selectGroup(name: string): void {
  if (group.value === name) return
  group.value = name
}

// ---------- 拖拽排序 ----------
const { dragKey, overKey, start, over, end, drop, move } = useDragSort<Channel>({
  items: rows,
  keyOf: (c) => c.id,
  onDrop: async (next) => {
    try {
      await channelsApi.reorderChannels(next.map((c) => c.id))
      toast.success('顺序已保存')
      await loadChannels(true)
    } catch (e) {
      toast.error(toErrorMessage(e))
      await loadChannels(true)
    }
  },
})

function rowProps(row: Channel): HTMLAttributes {
  const isDragging = dragKey.value === row.id
  const isOver = overKey.value === row.id && dragKey.value !== null && !isDragging
  return {
    draggable: true,
    class: isDragging ? 'dragging' : isOver ? 'drag-over' : '',
    onDragstart: (e: Event) => start(row.id, e as DragEvent),
    onDragover: (e: Event) => over(row.id, e as DragEvent),
    onDrop: (e: Event) => {
      e.preventDefault()
      void drop(row.id)
    },
    onDragend: () => end(),
  }
}

// ---------- 批量操作 ----------
async function batch(
  action: 'enable' | 'disable' | 'delete' | 'set_group',
  value?: string,
): Promise<void> {
  const ids = selectedIds.value
  if (!ids.length) return
  try {
    await channelsApi.batchChannels(ids, action, value)
    toast.success(`已处理 ${ids.length} 个频道`)
    await reloadAll()
  } catch (e) {
    toast.error(toErrorMessage(e))
  }
}

function confirmBatchDelete(): void {
  const ids = selectedIds.value
  if (!ids.length) return
  confirmAction({
    title: `删除选中的 ${ids.length} 个频道？`,
    content: '频道和它的线路都会被移除，电视上也就看不到了。这一步不可撤销。',
    positiveText: '删除',
    onConfirm: () => batch('delete'),
  })
}

async function applyBatchGroup(): Promise<void> {
  const name = batchGroupValue.value.trim()
  if (!name) {
    toast.warning('先填一个分组名')
    return
  }
  await batch('set_group', name)
  batchGroupShow.value = false
  batchGroupValue.value = ''
}

// ---------- 单条操作 ----------
function openCreate(): void {
  editing.value = null
  formShow.value = true
}

function openEdit(row: Channel): void {
  editing.value = row
  formShow.value = true
}

function remove(row: Channel): void {
  confirmAction({
    title: '删除这个频道？',
    content: `「${row.name}」以及它的 ${row.stream_count ?? 0} 条线路都会被移除。`,
    positiveText: '删除',
    onConfirm: async () => {
      await channelsApi.deleteChannel(row.id)
      toast.success('已删除')
      await reloadAll()
    },
  })
}

async function toggleEnabled(row: Channel): Promise<void> {
  try {
    await channelsApi.updateChannel(row.id, { enabled: !row.enabled })
    row.enabled = !row.enabled
    toast.success(row.enabled ? '已启用' : '已停用')
  } catch (e) {
    toast.error(toErrorMessage(e))
  }
}

function markRowChanged(): void {
  void loadChannels(true)
  void loadGroups()
}

// ---------- 列 ----------
function logoCell(row: Channel) {
  if (row.logo) {
    return h('img', { src: row.logo, class: 'logo-thumb', alt: row.name, loading: 'lazy' })
  }
  return h('span', { class: 'logo-fallback' }, row.name.slice(0, 2))
}

const allColumns = computed<Record<string, DataTableColumns<Channel>[number]>>(() => ({
  logo: {
    title: '台标',
    key: 'logo',
    width: 60,
    render: (row) => logoCell(row),
  },
  number: {
    title: '号',
    key: 'number',
    width: 56,
    render: (row) => h('span', { class: 'mono muted' }, row.number ?? '—'),
  },
  name: {
    title: '频道名',
    key: 'name',
    minWidth: 168,
    ellipsis: { tooltip: true },
    render: (row) =>
      h('div', { class: 'name-cell' }, [
        h('span', { class: 'nm' }, row.name),
        row.tvg_id ? h('span', { class: 'tid mono' }, row.tvg_id) : null,
      ]),
  },
  group: {
    title: '分组',
    key: 'group',
    width: 100,
    render: (row) =>
      h(NTag, { size: 'small', bordered: false }, { default: () => row.group || '未分组' }),
  },
  streams: {
    title: '线路',
    key: 'stream_count',
    width: 76,
    render: (row) => {
      const n = row.stream_count ?? 0
      return h(
        NTag,
        { size: 'small', bordered: false, type: n === 0 ? 'error' : n > 1 ? 'success' : 'default' },
        { default: () => (n === 0 ? '无线路' : `${n} 条`) },
      )
    },
  },
  epg: {
    title: 'EPG 匹配',
    key: 'epg_id',
    width: 132,
    render: (row) =>
      row.epg_id
        ? h('span', { class: 'mono', style: 'color:#8fb6c4' }, row.epg_id)
        : h(NTag, { size: 'small', bordered: false, type: 'warning' }, { default: () => '未匹配' }),
  },
  status: {
    title: '状态',
    key: 'enabled',
    width: 84,
    render: (row) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.enabled ? 'success' : 'default' },
        { default: () => (row.enabled ? '启用中' : '已停用') },
      ),
  },
}))

const columns = computed<DataTableColumns<Channel>>(() => {
  const list: DataTableColumns<Channel> = [
    { type: 'selection' },
    {
      type: 'expand',
      renderExpand: (row) =>
        h(ChannelStreamsPanel, {
          channelId: row.id,
          channelName: row.name,
          onChanged: markRowChanged,
        }),
    },
    {
      title: '',
      key: 'drag',
      width: 36,
      render: () => h('span', { class: 'drag-handle', title: '按住这一行拖动排序' }, [h(Icon, { name: 'grip', size: 15 })]),
    },
  ]
  for (const key of ALL_COLUMN_KEYS) {
    if (visibleColumns.value.includes(key)) list.push(allColumns.value[key])
  }
  list.push({
    title: '操作',
    key: 'actions',
    width: 168,
    fixed: 'right',
    render: (row) =>
      h(NSpace, { size: 2, wrap: false, align: 'center' }, {
        default: () => [
          h(
            NButton,
            { size: 'tiny', quaternary: true, type: 'primary', onClick: () => openEdit(row) },
            { default: () => '编辑' },
          ),
          h(NTooltip, null, {
            trigger: () =>
              h(
                NButton,
                { size: 'tiny', quaternary: true, onClick: () => void move(row.id, -1) },
                { icon: () => h(Icon, { name: 'up', size: 14 }) },
              ),
            default: () => '上移',
          }),
          h(NTooltip, null, {
            trigger: () =>
              h(
                NButton,
                { size: 'tiny', quaternary: true, onClick: () => void move(row.id, 1) },
                { icon: () => h(Icon, { name: 'down', size: 14 }) },
              ),
            default: () => '下移',
          }),
          h(NTooltip, null, {
            trigger: () =>
              h(
                NButton,
                {
                  size: 'tiny',
                  quaternary: true,
                  type: row.enabled ? 'default' : 'success',
                  onClick: () => void toggleEnabled(row),
                },
                { default: () => (row.enabled ? '停用' : '启用') },
              ),
            default: () => (row.enabled ? '停用这个频道' : '重新启用'),
          }),
          h(
            NButton,
            { size: 'tiny', quaternary: true, type: 'error', onClick: () => remove(row) },
            { icon: () => h(Icon, { name: 'trash', size: 14 }) },
          ),
        ],
      }),
  })
  return list
})

const pagination = computed(() => ({
  page: page.value,
  pageSize: pageSize.value,
  itemCount: total.value,
  showSizePicker: true,
  pageSizes: [20, 50, 100],
  prefix: (info: { itemCount?: number }) => `共 ${info.itemCount ?? 0} 个频道`,
  onChange: (p: number) => {
    page.value = p
  },
  onUpdatePageSize: (s: number) => {
    pageSize.value = s
    page.value = 1
  },
}))

onMounted(async () => {
  await Promise.all([loadGroups(), loadChannels()])
})
</script>

<template>
  <div>
    <PageHeader
      title="频道管理"
      subtitle="这里是电视上看到的台。左边按分组筛，右边直接拖行就能调顺序（只在当前页内生效）。"
    >
      <template #actions>
        <NButton size="small" :loading="loading" @click="reloadAll">
          <template #icon><Icon name="refresh" :size="15" /></template>
          刷新
        </NButton>
        <NButton size="small" type="primary" @click="openCreate">
          <template #icon><Icon name="plus" :size="15" /></template>
          新增频道
        </NButton>
      </template>
    </PageHeader>

    <div class="layout">
      <aside class="groups">
        <div class="g-title">分组</div>
        <button class="g-item" :class="{ active: group === '' }" @click="selectGroup('')">
          <span class="g-name">全部频道</span>
          <span class="g-count">{{ totalAll }}</span>
        </button>
        <button
          v-for="g in groups"
          :key="g.name"
          class="g-item"
          :class="{ active: group === g.name }"
          @click="selectGroup(g.name)"
        >
          <span class="g-name ellipsis">{{ g.name }}</span>
          <span class="g-count">{{ g.count }}</span>
        </button>
        <p v-if="groups.length === 0" class="g-empty">还没有分组</p>
      </aside>

      <div class="main">
        <div class="toolbar">
          <NInput
            v-model:value="keyword"
            size="small"
            clearable
            placeholder="搜频道名 / tvg-id / 频道号"
            style="width: 260px"
          >
            <template #prefix><Icon name="search" :size="15" /></template>
          </NInput>

          <NPopover trigger="click" placement="bottom-start" style="max-width: 240px">
            <template #trigger>
              <NButton size="small" secondary>
                <template #icon><Icon name="grip" :size="14" /></template>
                列（{{ visibleColumns.length }}）
              </NButton>
            </template>
            <NCheckboxGroup v-model:value="visibleColumns">
              <NSpace vertical :size="8">
                <NCheckbox
                  v-for="key in ALL_COLUMN_KEYS"
                  :key="key"
                  :value="key"
                  :label="COLUMN_LABELS[key]"
                />
              </NSpace>
            </NCheckboxGroup>
          </NPopover>

          <span class="spacer" />
          <span class="hint-text">拖动任意一行可以调整顺序</span>
        </div>

        <div v-if="checkedKeys.length > 0" class="batch-bar">
          <span class="count">已选 {{ checkedKeys.length }} 个</span>
          <NButton size="tiny" secondary @click="batch('enable')">批量启用</NButton>
          <NButton size="tiny" secondary @click="batch('disable')">批量停用</NButton>
          <NButton size="tiny" secondary @click="batchGroupShow = true">改分组</NButton>
          <NButton size="tiny" type="error" secondary @click="confirmBatchDelete">删除</NButton>
          <span class="spacer" />
          <NButton size="tiny" quaternary @click="checkedKeys = []">取消选择</NButton>
        </div>

        <div class="card-panel table-wrap">
          <StateBlock
            :loading="loading && !loaded"
            :error="error"
            :empty="loaded && rows.length === 0"
            :min-height="360"
            @retry="loadChannels()"
          >
            <template #empty-action>
              <NSpace :size="8" justify="center">
                <NButton size="small" type="primary" @click="openCreate">
                  <template #icon><Icon name="plus" :size="14" /></template>
                  手动建一个频道
                </NButton>
              </NSpace>
              <p class="hint-text" style="margin-top: 10px">
                通常是先在「源管理」导入 M3U，频道会自动出现；这里也支持手动加。
              </p>
            </template>

            <NDataTable
              v-model:checked-row-keys="checkedKeys"
              :columns="columns"
              :data="rows"
              :row-key="(row: Channel) => row.id"
              :row-props="rowProps"
              :pagination="pagination"
              :loading="loading"
              :scroll-x="980"
              :bordered="false"
              size="small"
              remote
              striped
            />
          </StateBlock>
        </div>
      </div>
    </div>

    <ChannelFormModal
      v-model:show="formShow"
      :channel="editing"
      :groups="groupNames"
      :default-group="group"
      @saved="reloadAll"
    />

    <NModal
      v-model:show="batchGroupShow"
      preset="card"
      title="把这些频道移到哪个分组"
      style="width: 460px; max-width: 92vw"
      :bordered="false"
    >
      <NSelect
        v-model:value="batchGroupValue"
        :options="groupNames.map((g) => ({ label: g, value: g }))"
        filterable
        tag
        clearable
        placeholder="选一个已有分组，或直接输入新的分组名"
      />
      <template #footer>
        <NSpace justify="end">
          <NButton quaternary @click="batchGroupShow = false">取消</NButton>
          <NButton type="primary" @click="applyBatchGroup">确定</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}
.groups {
  width: 190px;
  flex: none;
  padding: 10px 8px;
  border-radius: 14px;
  background: var(--litv-panel);
  border: 1px solid var(--litv-border);
  max-height: calc(100vh - 190px);
  overflow: auto;
  position: sticky;
  top: 0;
}
@media (max-width: 900px) {
  .layout {
    flex-direction: column;
  }
  .groups {
    width: 100%;
    position: static;
    max-height: 180px;
  }
}
.g-title {
  font-size: 12px;
  color: #6c7b86;
  padding: 4px 10px 8px;
}
.g-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: #a9b8c3;
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
}
.g-item:hover {
  background: rgba(31, 162, 192, 0.08);
  color: #d7e6ee;
}
.g-item.active {
  background: rgba(31, 162, 192, 0.16);
  color: #5fd3ea;
  font-weight: 600;
}
.g-name {
  min-width: 0;
}
.g-count {
  font-size: 11.5px;
  color: #6c7b86;
  flex: none;
}
.g-item.active .g-count {
  color: #4fb6cd;
}
.g-empty {
  font-size: 12px;
  color: #5f7280;
  padding: 6px 10px;
  margin: 0;
}
.main {
  flex: 1 1 auto;
  min-width: 0;
}
.batch-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 9px 12px;
  margin-bottom: 10px;
  border-radius: 11px;
  background: rgba(31, 162, 192, 0.1);
  border: 1px solid rgba(31, 162, 192, 0.28);
}
.batch-bar .count {
  font-size: 12.5px;
  font-weight: 600;
  color: #5fd3ea;
}
.batch-bar .spacer {
  flex: 1 1 auto;
}
.table-wrap {
  padding: 6px 6px 2px;
  overflow: hidden;
}
:deep(.name-cell) {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
:deep(.name-cell .nm) {
  color: #e2ecf1;
  font-weight: 500;
}
:deep(.name-cell .tid) {
  color: #66757f;
  font-size: 11px;
}
</style>
