<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  NButton,
  NDataTable,
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
import { confirmDanger, toast } from '@/utils/feedback'
import { fmtDateTime, timeAgo } from '@/utils/format'
import { derivedSourceStatus, sourceError } from '@/utils/sourceStatus'
import { isRefreshRunning, refreshDetail, refreshSummary, sourceCountsOf } from '@/utils/sourceRefresh'
import { canWrite } from '@/session'

const { data, loading, error, run } = useAsyncData<Source[]>(sourcesApi.listSources)
const sources = computed(() => data.value ?? [])

const formShow = ref(false)
const editing = ref<Source | null>(null)
const previewShow = ref(false)
const previewTarget = ref<Source | null>(null)
/** 本页正在等结果的源 id（按钮转圈 + 轮询去重） */
const refreshing = ref<number[]>([])
/** 组件卸载后停止轮询，避免请求泄漏到已销毁的页面 */
let disposed = false
onBeforeUnmount(() => {
  disposed = true
})

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

/** 这一行现在是不是「刷新中」：服务端说在跑，或本页刚点了还没等到结果 */
function rowRefreshing(row: Source): boolean {
  return row.refreshing === true || refreshing.value.includes(row.id)
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

/**
 * 轮询间隔：**不要调小**。
 *
 * `GET /sources` 每行要带 `last_result` 计数，服务端为它做子查询；
 * 3000 频道的库上 200ms 一次会把 NAS 打满。2 秒是「用户觉得很快」与
 * 「不给服务端添乱」之间的折中，配合「页面不可见就停」足够了。
 */
const POLL_INTERVAL_MS = 2000
/** 最多等 60 秒（2s × 30）。大源 + 慢源站也够；再久就让他点「刷新列表」看结果。 */
const POLL_MAX_ATTEMPTS = 30

/**
 * 轮询等一次刷新的结果。
 *
 * ★ 为什么必须轮询：刷新改成异步任务后，POST 立刻返回 `status:"running"`，
 * 真正的五键计数（`last_result`）要等任务跑完、`GET /sources` 里那一行
 * `refreshing` 变 false 才出现。以前是同步等 HTTP 返回，所以
 * 「刷新一次要转很久、还以为卡死了」。
 *
 * ★ 驱动信号是**行级 `refreshing` 布尔值**，不是 `status`——
 * `status` 的取值域是 ok|error|never，不能混进「刷新中」。
 *
 * 失败不弹红条（`listSources` 是 silent）：轮询失败最多是「等不到结果」，
 * 不该干扰用户；超时给一次 warning，告诉他去点「刷新列表」。
 */
async function pollRefresh(row: Source): Promise<void> {
  for (let attempt = 0; attempt < POLL_MAX_ATTEMPTS; attempt++) {
    await sleep(POLL_INTERVAL_MS)
    if (disposed) return
    // 标签页在后台时不轮询：用户在别的窗口，这里只是白耗 NAS 的 IO。
    // 回到前台会继续下一轮，不影响最终结果（结果在服务端，不在页面里）。
    if (typeof document !== 'undefined' && document.hidden) continue
    /**
     * 轮询**不走 `run()`**：`run()` 会把 `loading` 置真，顶部「刷新列表」按钮
     * 每 2 秒闪一次转圈，看着像页面在抽风。这里直接取列表、只换数据，
     * 失败就保持上一次的画面（下一轮再试），不碰 `error` 态——
     * 用户此刻关心的是「那次刷新完了没」，不是「列表接口抖了一下」。
     */
    try {
      data.value = await sourcesApi.listSources()
    } catch {
      continue
    }
    if (disposed) return
    const fresh = sources.value.find((s) => s.id === row.id)
    if (!fresh) return
    if (fresh.refreshing !== true) {
      // 任务结束了（老服务端没有 refreshing 键，第一次轮询就会落到这里）
      const summary = refreshSummary(sourceCountsOf(fresh))
      if (summary) toast.success(`「${row.name}」刷新完成：${summary}`)
      else toast.success(`「${row.name}」刷新完成`)
      return
    }
  }
  toast.warning(`「${row.name}」还在刷新，稍后点「刷新列表」看结果`)
}

async function refreshOne(row: Source): Promise<void> {
  if (rowRefreshing(row)) return
  refreshing.value = [...refreshing.value, row.id]
  try {
    const resp = await sourcesApi.refreshSource(row.id)
    if (isRefreshRunning(resp)) {
      // 异步路径：说「开始了」，绝不说「已重新拉取」——那会让用户以为
      // 频道已经更新完了，然后去看电视发现没变。
      toast.info(`「${row.name}」已开始刷新，结果稍后自动更新到这一行`)
      await runPolling(row)
    } else {
      // 同步路径（小源 / 老服务端）：POST 就带回了计数
      const summary = refreshSummary(resp)
      toast.success(summary ? `「${row.name}」刷新完成：${summary}` : `「${row.name}」已刷新`)
      await run(true)
    }
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    refreshing.value = refreshing.value.filter((id) => id !== row.id)
  }
}

/** 正在跑轮询的源 id：防止「用户点了」和「进页面时补轮询」两条路径重复开轮询 */
const polling = new Set<number>()

async function runPolling(row: Source): Promise<void> {
  if (polling.has(row.id)) return
  polling.add(row.id)
  try {
    await pollRefresh(row)
  } finally {
    polling.delete(row.id)
  }
}

/**
 * 进页面时把「已经在别处开始刷新」的行接上。
 *
 * 场景：用户点了刷新 → 切走 → 又切回来（或换了个标签页）。那一行的
 * `refreshing` 还是 true，但没有轮询在跑，界面就永远停在「刷新中…」，
 * 直到用户手动点一次「刷新列表」。这里补一次轮询把它收敛掉。
 */
function resumeOrphanRefreshes(): void {
  for (const row of sources.value) {
    if (row.refreshing === true) void runPolling(row)
  }
}

function remove(row: Source): void {
  // 数字取 `channel_count`（库内真数），不是 `parsed_count`（上游解析条数）：
  // 删的是库里那些行，用上游条数会多报；两个数语义不同，不能混。
  const n = row.channel_count ?? 0
  confirmDanger({
    action: `删除源「${row.name}」`,
    count: n,
    unit: '个频道（及其线路）',
    cascade: ['这些频道的收藏', '续播进度', '节目提醒'],
    // 这句必须留：服务端 DeleteSource 是级联删（DELETE FROM channels WHERE source_id=?），
    // 而 ImportChannels 的判重键是 (name, group) **不含 source_id**，
    // 所以别的源里同名的频道也可能被一起删掉。之前这里写的是「不会自动消失」，正好说反了。
    note: '其它源里同名的频道也可能被一并删除；电视上这些台会立刻消失。',
    onConfirm: async () => {
      await sourcesApi.deleteSource(row.id)
      toast.success('已删除')
      await run(true)
    },
    confirmText: '删除',
  })
}

/** 展开行里的「标签 + 值」：统一一种排版，别每个字段自己拼字符串 */
function kv(label: string, value: string, opts: { mono?: boolean; danger?: boolean } = {}) {
  const cls = ['kv-value', opts.mono ? 'mono' : '', opts.danger ? 'kv-danger' : ''].filter(Boolean).join(' ')
  return h('div', { class: 'kv-item' }, [
    h('span', { class: 'kv-label' }, label),
    // 长文本（地址、错误串）单行截断 + title 兜底，绝不撑破布局
    h('span', { class: cls, title: value }, value),
  ])
}

/** 次要操作（预览/编辑/删除）：主行只留一个主操作，其余收进展开行 */
function detailActions(row: Source) {
  return h('div', { class: 'src-actions' }, [
    h(
      NButton,
      { size: 'tiny', quaternary: true, onClick: () => openPreview(row) },
      { default: () => '预览解析结果' },
    ),
    h(
      NButton,
      { size: 'tiny', quaternary: true, disabled: !canWrite.value, onClick: () => openEdit(row) },
      { default: () => '编辑' },
    ),
    h(
      NButton,
      { size: 'tiny', quaternary: true, type: 'error', disabled: !canWrite.value, onClick: () => remove(row) },
      { default: () => '删除' },
    ),
  ])
}

/**
 * 列设计：**主行只放 4 列**（名称 / 状态 / 频道数 / 主操作），其余全部进展开行。
 *
 * 原来一行塞了 7 列：名称+解析数+上次刷新+类型+地址+状态+多久没更新+错误。
 * 1280 宽度下横向滚动条压在「操作」列上，用户想点删除要先滚表头；
 * 而且「上游解析 24 条 / 上次刷新：新增 0 · 更新 3 · 下线 2」这种次要信息
 * 天天占着最显眼的位置，真正要看的「这个源现在行不行」反而不突出。
 */
const columns: DataTableColumns<Source> = [
  {
    type: 'expand',
    renderExpand: (row) => {
      const detail = refreshDetail(sourceCountsOf(row))
      const msg = sourceError(row)
      return h('div', { class: 'src-detail' }, [
        kv('类型', TYPE_LABEL[row.type] ?? row.type),
        kv('地址', row.url, { mono: true }),
        kv(
          '上次刷新',
          row.last_fetch_at ? `${timeAgo(row.last_fetch_at)}（${fmtDateTime(row.last_fetch_at)}）` : '还没成功拉取过',
        ),
        kv('上次结果', detail || '—'),
        kv('上游条数', typeof row.parsed_count === 'number' ? `${row.parsed_count} 条` : '—'),
        msg ? kv('错误原因', msg, { danger: true }) : null,
        detailActions(row),
      ])
    },
  },
  {
    title: '名称',
    key: 'name',
    minWidth: 240,
    fixed: 'left',
    ellipsis: { tooltip: true },
    render: (row) => h('span', { class: 'nm' }, row.name),
  },
  {
    title: '状态',
    key: 'status',
    width: 104,
    render: (row) => {
      // 刷新中优先于其它状态：否则一行显示「正常」而按钮在转圈，用户会以为卡了
      if (rowRefreshing(row)) {
        return h(NTag, { size: 'small', type: 'info', bordered: false }, { default: () => '刷新中…' })
      }
      const s = statusOf(row)
      return h(NTag, { size: 'small', type: s.type, bordered: false }, { default: () => s.text })
    },
  },
  {
    title: '频道数',
    key: 'channel_count',
    width: 104,
    render: (row) => {
      // 两个数语义不同：channel_count = 库内真数（删源删的就是这些），
      // parsed_count = 上游给了几条。主行只显示库内真数，上游数放 tooltip + 展开行。
      const n = row.channel_count
      const tip =
        typeof row.parsed_count === 'number'
          ? `库里有 ${n ?? 0} 个频道；上一轮上游给了 ${row.parsed_count} 条`
          : '库里属于这个源的频道数'
      return h(
        NTooltip,
        null,
        {
          trigger: () =>
            h('span', { class: typeof n === 'number' ? 'mono' : 'mono muted' }, typeof n === 'number' ? String(n) : '—'),
          default: () => tip,
        },
      )
    },
  },
  {
    title: '操作',
    key: 'actions',
    width: 116,
    fixed: 'right',
    render: (row) =>
      h(
        NButton,
        {
          size: 'tiny',
          type: 'primary',
          secondary: true,
          // 刷新会写库（导入频道），非管理员会被服务端 403；按钮先禁掉。
          // 已经在刷新的行也禁掉：服务端虽然是单飞（第二次直接回 running），
          // 但让按钮可点只会造出「点了没反应」的错觉。
          disabled: !canWrite.value || rowRefreshing(row),
          loading: refreshing.value.includes(row.id),
          onClick: () => void refreshOne(row),
        },
        { default: () => (rowRefreshing(row) ? '刷新中…' : '立即刷新') },
      ),
  },
]

onMounted(async () => {
  await run()
  if (!disposed) resumeOrphanRefreshes()
})
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
        empty-title="还没有添加任何源"
        empty-hint="源就是频道来源（M3U / TXT / TVBox JSON）。先加一条，再点「立即刷新」把频道导进来。"
        error-hint="确认 LITV 服务在运行；刚改过网关配置的话，刷新页面再试。"
        loading-text="正在加载源列表…"
        :min-height="320"
        @retry="run()"
      >
        <template #empty-action>
          <NButton type="primary" size="small" :disabled="!canWrite" @click="openCreate">
            <template #icon><Icon name="plus" :size="14" /></template>
            添加第一个源
          </NButton>
        </template>
        <NDataTable
          :columns="columns"
          :data="sources"
          :row-key="(row: Source) => row.id"
          :scroll-x="620"
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
