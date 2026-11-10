<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NCheckbox, NInput, NTag } from 'naive-ui'
import StateBlock from './StateBlock.vue'
import Icon from './Icon.vue'
import * as channelsApi from '@/api/modules/channels'
import * as epgApi from '@/api/modules/epg'
import type { Channel, EpgChannel, EpgMappingConflict, EpgUnmatchedChannel } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { toast } from '@/utils/feedback'

const channels = ref<Channel[]>([])
const epgChannels = ref<EpgChannel[]>([])
const loading = ref(false)
const error = ref('')
/** 服务端没有 GET /epg/channels 时降级：用频道表里出现过的 epg_id 反推 */
const degraded = ref(false)
/**
 * 接口通了但 EPG 库是空的。
 *
 * 和 degraded 是两回事：
 *   degraded = 接口不可用，只能从已匹配频道反推（功能受限）
 *   empty    = 接口正常，只是还没导入节目单（去加个 EPG 源就好）
 * 之前两者混在一起，导致明明接口正常也提示「服务端还没实现」。
 */
const epgEmpty = ref(false)

const chSearch = ref('')
const epgSearch = ref('')
const onlyUnmatched = ref(false)
const selectedId = ref<number | null>(null)
const dragId = ref<number | null>(null)
const hoverEpg = ref<string | null>(null)

// ---- B1/B2/B4/B6：自动匹配与诊断 ----
const automapping = ref(false)
const byNumbering = ref(false)
const unmatchedDiag = ref<EpgUnmatchedChannel[]>([])
const conflicts = ref<EpgMappingConflict[]>([])

async function loadDiag(): Promise<void> {
  try {
    const r = await epgApi.fetchEpgMapping()
    unmatchedDiag.value = r.unmatched
    conflicts.value = r.conflicts
  } catch {
    unmatchedDiag.value = []
    conflicts.value = []
  }
}

/** B1/B2：按频道名自动匹配，并把结果说清楚。 */
async function runAutomap(): Promise<void> {
  automapping.value = true
  try {
    const r = await epgApi.automapEpg()
    toast.success(`自动匹配完成：扫描 ${r.scanned}，匹配 ${r.matched}，未匹配 ${r.unmatched.length}`)
    await load()
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    automapping.value = false
  }
}

/** B4：按台号匹配（只在数字唯一对应时才生效）。 */
async function runByNumber(): Promise<void> {
  byNumbering.value = true
  try {
    const r = await epgApi.mapEpgByNumber()
    toast.success(`按台号匹配完成：匹配 ${r.matched}，未匹配 ${r.unmatched.length}`)
    await load()
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    byNumbering.value = false
  }
}

const filteredChannels = computed(() => {
  const kw = chSearch.value.trim().toLowerCase()
  return channels.value.filter((c) => {
    if (onlyUnmatched.value && c.epg_id) return false
    if (!kw) return true
    return c.name.toLowerCase().includes(kw) || (c.tvg_id ?? '').toLowerCase().includes(kw)
  })
})

const filteredEpg = computed(() => {
  const kw = epgSearch.value.trim().toLowerCase()
  if (!kw) return epgChannels.value
  return epgChannels.value.filter(
    (e) => e.name.toLowerCase().includes(kw) || e.id.toLowerCase().includes(kw),
  )
})

const matchedCount = computed(() => channels.value.filter((c) => c.epg_id).length)
const unmatchedCount = computed(() => channels.value.length - matchedCount.value)

function usage(epgId: string): number {
  return channels.value.filter((c) => c.epg_id === epgId).length
}

function epgName(id?: string | null): string {
  if (!id) return ''
  return epgChannels.value.find((e) => e.id === id)?.name ?? id
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [ch, epg] = await Promise.allSettled([
      channelsApi.listChannels({ page: 1, size: 500 }),
      epgApi.listEpgChannels(),
    ])
    if (ch.status === 'rejected') throw ch.reason
    channels.value = ch.value.items
    if (epg.status === 'fulfilled') {
      // 接口正常。空就只是"还没导入节目单"，不是接口没实现
      epgChannels.value = epg.value
      degraded.value = false
      epgEmpty.value = epg.value.length === 0
    } else {
      // 真降级：接口不可用，只能拿已匹配的频道反推出 EPG 频道清单
      const seen = new Map<string, EpgChannel>()
      for (const c of channels.value) {
        if (c.epg_id && !seen.has(c.epg_id)) seen.set(c.epg_id, { id: c.epg_id, name: c.epg_id })
      }
      epgChannels.value = [...seen.values()]
      degraded.value = true
      epgEmpty.value = false
    }
  } catch (e) {
    error.value = toErrorMessage(e)
  } finally {
    loading.value = false
    // 自动匹配/按台号匹配之后都要刷新诊断，否则未匹配列表还是旧的
    void loadDiag()
  }
}

/**
 * 建立映射。
 *
 * ★ 必须走 `setChannelEpg`（`PUT /channels/{id}/epg`），不能塞进 `updateChannel`：
 * 频道更新接口的请求体里没有 `epg_id`，服务端又开了 `DisallowUnknownFields`，
 * 塞进去就是 400 —— 之前这个页面的拖拽对应功能在真机上 100% 失败。
 */
async function assign(channel: Channel, epgId: string): Promise<void> {
  if (channel.epg_id === epgId) return
  try {
    await channelsApi.setChannelEpg(channel.id, epgId)
    channel.epg_id = epgId
    selectedId.value = null
    toast.success(`「${channel.name}」已对应到 ${epgName(epgId)}`)
  } catch (e) {
    toast.error(toErrorMessage(e))
  }
}

/**
 * 解除映射。
 *
 * 传 `null` 给模块层，模块层会发**空字符串**（`{"epg_id": ""}`）而不是字符串
 * `"null"` —— 服务端 `SetChannelEpgID(id, "")` 才是「清空」的语义，
 * 发字面量 `"null"` 会被当成一个真的 epg id 存进去。
 */
async function clearMatch(channel: Channel): Promise<void> {
  try {
    await channelsApi.setChannelEpg(channel.id, null)
    channel.epg_id = null
    toast.success('已解除对应关系')
  } catch (e) {
    toast.error(toErrorMessage(e))
  }
}

function onDragStart(channel: Channel, e: DragEvent): void {
  dragId.value = channel.id
  selectedId.value = channel.id
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'link'
    e.dataTransfer.setData('text/plain', String(channel.id))
  }
}

function onDragEnd(): void {
  dragId.value = null
  hoverEpg.value = null
}

function onDropOn(epg: EpgChannel): void {
  const id = dragId.value
  dragId.value = null
  hoverEpg.value = null
  if (id === null) return
  const channel = channels.value.find((c) => c.id === id)
  if (channel) void assign(channel, epg.id)
}

function pickTarget(epg: EpgChannel): void {
  if (selectedId.value === null) return
  const channel = channels.value.find((c) => c.id === selectedId.value)
  if (channel) void assign(channel, epg.id)
}

onMounted(() => {
  void load()
  void loadDiag()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <NTag size="small" :bordered="false" type="success">已匹配 {{ matchedCount }}</NTag>
      <NTag size="small" :bordered="false" type="warning">未匹配 {{ unmatchedCount }}</NTag>
      <NButton size="small" type="primary" :loading="automapping" @click="runAutomap">
        自动匹配
      </NButton>
      <NButton size="small" secondary :loading="byNumbering" @click="runByNumber">
        按台号匹配
      </NButton>
      <NButton size="small" secondary :loading="loading" @click="load">
        <template #icon><Icon name="refresh" :size="15" /></template>
        重新加载
      </NButton>
      <span class="spacer" />
      <span class="hint-text">左边拖到右边就能纠正自动匹配的结果</span>
    </div>

    <!-- B6：映射冲突。两个频道共用一份节目单几乎总是误匹配，必须提示 -->
    <p v-if="conflicts.length > 0" class="note warn">
      <Icon name="warning" :size="14" />
      有 {{ conflicts.length }} 组冲突：多个频道映射到了同一个 EPG 频道
      （{{ conflicts.map((c) => c.channels.join(' / ')).join('；') }}）。
      这通常是自动匹配错了，建议手动改掉。
    </p>

    <p v-if="degraded && !loading" class="note">
      <Icon name="warning" :size="14" />
      读取 EPG 频道清单失败，右侧列表是从已匹配的频道里反推的，
      能改已有匹配但看不到全部 EPG 频道。
    </p>

    <p v-else-if="epgEmpty && !loading" class="note">
      <Icon name="warning" :size="14" />
      EPG 库还是空的。先去「EPG 源」标签页添加一个节目单地址并点「立即刷新」，
      拉回来之后这里才有东西可以对应。
    </p>

    <!-- B2/B5：未匹配诊断。给出最佳候选与分数，用户不用自己翻 EPG 列表 -->
    <div v-if="unmatchedDiag.length > 0" class="diag">
      <div class="diag-head">
        还有 {{ unmatchedDiag.length }} 个频道没有节目单
        <span class="muted">（下面是按名字算出的最接近候选，分数越高越像）</span>
      </div>
      <div class="diag-list">
        <div v-for="u in unmatchedDiag.slice(0, 12)" :key="u.id" class="diag-item">
          <span class="diag-name">{{ u.number }}. {{ u.name }}</span>
          <span v-if="u.best_guess" class="diag-guess">
            最接近：<b>{{ epgName(u.best_guess) }}</b>
            <span class="score">{{ u.best_score }} 分</span>
          </span>
          <span v-else class="muted">没有相似候选</span>
        </div>
      </div>
      <div v-if="unmatchedDiag.length > 12" class="muted">
        …还有 {{ unmatchedDiag.length - 12 }} 个，用左侧「只看没匹配上的」逐个处理
      </div>
    </div>

    <StateBlock :loading="loading && channels.length === 0" :error="error" :min-height="360" @retry="load">
      <div class="map">
        <section class="col">
          <header class="col-head">
            <span class="col-title">频道（{{ filteredChannels.length }}）</span>
            <NInput v-model:value="chSearch" size="tiny" clearable placeholder="搜频道">
              <template #prefix><Icon name="search" :size="13" /></template>
            </NInput>
          </header>
          <NCheckbox v-model:checked="onlyUnmatched" size="small" label="只看没匹配上的" class="only" />

          <div class="list">
            <div
              v-for="c in filteredChannels"
              :key="c.id"
              class="row"
              :class="{ active: selectedId === c.id, dragging: dragId === c.id }"
              draggable="true"
              @dragstart="onDragStart(c, $event)"
              @dragend="onDragEnd"
              @click="selectedId = selectedId === c.id ? null : c.id"
            >
              <img v-if="c.logo" :src="c.logo" class="thumb" :alt="c.name" />
              <span v-else class="thumb fallback">{{ c.name.slice(0, 2) }}</span>
              <span class="nm ellipsis">{{ c.name }}</span>
              <NTag v-if="c.epg_id" size="tiny" :bordered="false" type="success" class="tag">
                {{ epgName(c.epg_id) }}
              </NTag>
              <NTag v-else size="tiny" :bordered="false" type="warning" class="tag">未匹配</NTag>
              <button v-if="c.epg_id" class="unlink" title="解除对应" @click.stop="clearMatch(c)">
                <Icon name="close" :size="13" />
              </button>
            </div>
            <p v-if="filteredChannels.length === 0" class="empty">没有符合条件的频道</p>
          </div>
        </section>

        <section class="col">
          <header class="col-head">
            <span class="col-title">EPG 频道（{{ filteredEpg.length }}）</span>
            <NInput v-model:value="epgSearch" size="tiny" clearable placeholder="搜 EPG 频道">
              <template #prefix><Icon name="search" :size="13" /></template>
            </NInput>
          </header>
          <p class="only hint-text">
            {{ selectedId === null ? '先点左边一个频道，再点右边就能关联' : '已选中频道，点右边任意一行完成关联' }}
          </p>

          <div class="list">
            <div
              v-for="e in filteredEpg"
              :key="e.id"
              class="row target"
              :class="{ hover: hoverEpg === e.id }"
              @dragover.prevent="hoverEpg = e.id"
              @dragleave="hoverEpg = hoverEpg === e.id ? null : hoverEpg"
              @drop.prevent="onDropOn(e)"
              @click="pickTarget(e)"
            >
              <span class="epg-id mono ellipsis">{{ e.id }}</span>
              <span class="nm ellipsis">{{ e.name }}</span>
              <NTag v-if="usage(e.id)" size="tiny" :bordered="false" type="info">
                {{ usage(e.id) }} 个频道在用
              </NTag>
              <span v-else class="free">空闲</span>
            </div>
            <p v-if="filteredEpg.length === 0" class="empty">没有符合条件的 EPG 频道</p>
          </div>
        </section>
      </div>
    </StateBlock>
  </div>
</template>

<style scoped>
.diag {
  margin: 4px 6px 8px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(139, 148, 158, 0.08);
}
.diag-head {
  font-size: 12.5px;
  color: #e2ecf1;
  margin-bottom: 6px;
}
.diag-list {
  display: grid;
  gap: 4px;
}
.diag-item {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12.5px;
  color: #c9d6dc;
}
.diag-name {
  min-width: 180px;
}
.diag-guess b {
  color: #e2ecf1;
}
.score {
  margin-left: 6px;
  color: #2dd4bf;
}
.note.warn {
  color: #f0b429;
}
.muted {
  color: #8b949e;
}

.note {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
  padding: 9px 12px;
  border-radius: 10px;
  background: rgba(224, 169, 74, 0.08);
  border: 1px solid rgba(224, 169, 74, 0.22);
  font-size: 12.5px;
  color: #c9a86a;
  line-height: 1.6;
}
.map {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
@media (max-width: 900px) {
  .map {
    grid-template-columns: 1fr;
  }
}
.col {
  border-radius: 14px;
  background: var(--litv-panel);
  border: 1px solid var(--litv-border);
  padding: 10px 10px 8px;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.col-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.col-title {
  font-size: 12.5px;
  font-weight: 600;
  color: #c2d0d9;
  flex: none;
}
.only {
  margin-bottom: 6px;
  font-size: 12px;
}
.list {
  max-height: 52vh;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-right: 2px;
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 9px;
  border-radius: 9px;
  background: #121a20;
  border: 1px solid #1c262e;
  cursor: pointer;
  min-width: 0;
}
.row:hover {
  border-color: #2a3a44;
  background: #16212a;
}
.row.active {
  border-color: #1fa2c0;
  background: rgba(31, 162, 192, 0.12);
}
.row.dragging {
  opacity: 0.45;
}
.row.target.hover {
  border-color: #3fbf8f;
  background: rgba(63, 191, 143, 0.12);
}
.thumb {
  width: 26px;
  height: 26px;
  border-radius: 6px;
  object-fit: cover;
  flex: none;
  border: 1px solid #1f2a32;
}
.thumb.fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10.5px;
  color: #5f7280;
  background: #10171d;
}
.nm {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  color: #dbe6ec;
}
.epg-id {
  flex: none;
  max-width: 40%;
  font-size: 11.5px;
  color: #6f8794;
}
.tag {
  flex: none;
  max-width: 42%;
  overflow: hidden;
}
.free {
  flex: none;
  font-size: 11px;
  color: #5f7280;
}
.unlink {
  flex: none;
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: #6c7b86;
  cursor: pointer;
}
.unlink:hover {
  background: rgba(228, 99, 92, 0.16);
  color: #e4635c;
}
.empty {
  font-size: 12.5px;
  color: #5f7280;
  text-align: center;
  padding: 20px 0;
  margin: 0;
}
</style>
