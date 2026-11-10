<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
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
  // 文案必须说真话：服务端 DeleteSource 是级联删除（`DELETE FROM channels WHERE source_id=?`），
  // 而且 ImportChannels 的判重键是 (name, group) **不含 source_id**，
  // 所以别的源里同名的频道也可能一起被删掉。这句之前写的是「不会自动消失」，正好说反了。
  //
  // ★ 数字取 `channel_count`（库内真数），不是 `parsed_count`（上游解析条数）：
  // 删的是库里那些行，用上游条数会多报；两个数语义不同，不能混。
  const n = row.channel_count ?? 0
  confirmAction({
    title: '删除这个源？',
    content:
      `「${row.name}」会从源列表里移除，并一并删除库里属于它的 ${n} 个频道和这些频道的线路，不可撤销。` +
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
    width: 210,
    fixed: 'left',
    render: (row) => {
      // 上次刷新结果（新增/更新/下线）挂在名字下面：用户最关心的就是「这次刷新到底有没有变化」，
      // 而它以前只能靠 toast 一闪而过，错过就再也看不到。
      const counts = sourceCountsOf(row)
      const detail = refreshDetail(counts)
      const line = refreshSummary(counts) || detail
      // ★ 两个数语义不同，分开显示、不要相加也不要互相顶替：
      //   channel_count = 这个源在**库内**有几个频道（删源时删的就是这些）
      //   parsed_count  = 上一轮**上游给了**多少条（可能因为去重/过滤入库更少）
      const meta: string[] = []
      if (typeof row.channel_count === 'number') meta.push(`${row.channel_count} 个频道`)
      if (typeof row.parsed_count === 'number') meta.push(`上游 ${row.parsed_count} 条`)
      const cells = [
        h('span', { class: 'nm' }, row.name),
        meta.length ? h('span', { class: 'cnt' }, meta.join(' · ')) : null,
      ]
      if (line) {
        cells.push(
          h(
            NTooltip,
            null,
            {
              trigger: () => h('span', { class: 'cnt' }, `上次刷新：${line}`),
              default: () => detail || line,
            },
          ),
        )
      }
      return h('div', { class: 'name-cell' }, cells)
    },
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
      // 刷新中优先于其它状态：否则一行显示「正常」而按钮在转圈，用户会以为卡了
      if (rowRefreshing(row)) {
        return h(NTag, { size: 'small', type: 'info', bordered: false }, { default: () => '刷新中…' })
      }
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
              // 刷新会写库（导入频道），非管理员会被服务端 403；按钮先禁掉。
              // 已经在刷新的行也禁掉：服务端虽然是单飞（第二次直接回 running），
              // 但让按钮可点只会造出「点了没反应」的错觉。
              disabled: !canWrite.value || rowRefreshing(row),
              loading: refreshing.value.includes(row.id),
              onClick: () => void refreshOne(row),
            },
            { default: () => (rowRefreshing(row) ? '刷新中…' : '立即刷新') },
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
