<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { NButton, NTag } from 'naive-ui'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import StatCard from '@/components/StatCard.vue'
import StateBlock from '@/components/StateBlock.vue'
import Icon from '@/components/Icon.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import * as systemApi from '@/api/modules/system'
import * as sourcesApi from '@/api/modules/sources'
import * as devicesApi from '@/api/modules/devices'
import * as channelsApi from '@/api/modules/channels'
import type { Device, Source, SystemInfo } from '@/api/types'
import { fmtCount, fmtDuration, timeAgo, toMillis } from '@/utils/format'
import { derivedSourceStatus, sourceError } from '@/utils/sourceStatus'

const router = useRouter()

interface DashData {
  info: SystemInfo | null
  devices: Device[]
  sources: Source[]
  channelTotal: number
}

const { data, loading, error, run } = useAsyncData<DashData>(async () => {
  const [info, devices, sources, channels] = await Promise.allSettled([
    systemApi.fetchSystemInfo(),
    devicesApi.listDevices(),
    sourcesApi.listSources(),
    channelsApi.listChannels({ page: 1, size: 1 }),
  ])
  return {
    info: info.status === 'fulfilled' ? info.value : null,
    devices: devices.status === 'fulfilled' ? devices.value : [],
    sources: sources.status === 'fulfilled' ? sources.value : [],
    channelTotal: channels.status === 'fulfilled' ? channels.value.total : 0,
  }
})

const info = computed(() => data.value?.info ?? null)
const devices = computed(() => data.value?.devices ?? [])
const sources = computed(() => data.value?.sources ?? [])

const onlineDevices = computed(() =>
  info.value?.devices_online ?? devices.value.filter((d) => d.online).length,
)
const channelTotal = computed(() => info.value?.channels_total ?? data.value?.channelTotal ?? 0)
const playsToday = computed(() => info.value?.plays_today)
/**
 * 失效源计数：`info.sources_failed` 优先（服务端聚合），
 * 没有就按与源管理页**同一套**推导（`utils/sourceStatus.ts`）从列表里数。
 * 只认 `status === 'error'` 的话，连上没升级的 NAS 时这里会恒为 0。
 */
const failedSources = computed(
  () => info.value?.sources_failed ?? sources.value.filter((s) => derivedSourceStatus(s) === 'error').length,
)
const pendingDevices = computed(() => devices.value.filter((d) => d.status === 'pending').length)

interface Activity {
  key: string
  /** 服务端是 Unix 秒；mock 是 ISO 字符串。排序统一走 toMillis() */
  time?: string | number | null
  title: string
  desc: string
  tone: 'primary' | 'success' | 'warning' | 'danger' | 'neutral'
  icon: 'sources' | 'devices' | 'channels' | 'epg'
}

/** 最近活动：没有专门的接口，就用源拉取时间 + 设备心跳合成一条时间线 */
const activities = computed<Activity[]>(() => {
  const list: Activity[] = []
  for (const s of sources.value) {
    if (!s.last_fetch_at) continue
    const st = derivedSourceStatus(s)
    list.push({
      key: `src-${s.id}`,
      time: s.last_fetch_at,
      title: `源「${s.name}」拉取完成`,
      desc: st === 'error' ? sourceError(s) || '拉取失败，详见源管理页' : `解析出 ${s.channel_count ?? 0} 个频道`,
      tone: st === 'error' ? 'danger' : 'success',
      icon: 'sources',
    })
  }
  for (const d of devices.value) {
    if (!d.last_heartbeat_at) continue
    list.push({
      key: `dev-${d.id}`,
      time: d.last_heartbeat_at,
      title: `${d.name} ${d.online ? '在线心跳' : '最后一次心跳'}`,
      desc: `${d.platform}${d.model ? ` · ${d.model}` : ''}${d.ip ? ` · ${d.ip}` : ''}`,
      tone: d.online ? 'primary' : 'neutral',
      icon: 'devices',
    })
  }
  return list.sort((a, b) => toMillis(b.time) - toMillis(a.time)).slice(0, 8)
})

onMounted(() => void run())
</script>

<template>
  <div>
    <PageHeader title="概览" subtitle="家里的电视现在什么情况，一眼看清。数据来自服务端实时状态。">
      <template #actions>
        <NButton size="small" :loading="loading" @click="run(true)">
          <template #icon><Icon name="refresh" :size="15" /></template>
          刷新
        </NButton>
      </template>
    </PageHeader>

    <StateBlock
      :loading="loading && !data"
      :error="error"
      :min-height="320"
      @retry="run()"
    >
      <div class="grid">
        <StatCard
          label="在线终端"
          :value="fmtCount(onlineDevices)"
          unit="台"
          icon="devices"
          tone="primary"
          :hint="`共 ${devices.length} 台已绑定${pendingDevices ? `，${pendingDevices} 台待批准` : ''}`"
        />
        <StatCard
          label="频道总数"
          :value="fmtCount(channelTotal)"
          unit="个"
          icon="channels"
          tone="success"
          hint="含所有分组，已禁用的也计算在内"
        />
        <StatCard
          label="今日起播次数"
          :value="playsToday === undefined ? '—' : fmtCount(playsToday)"
          unit="次"
          icon="play"
          tone="neutral"
          :hint="playsToday === undefined ? '服务端暂未提供该统计（见下方说明）' : '今天所有终端切台/起播的累计次数'"
        />
        <StatCard
          label="失效源"
          :value="fmtCount(failedSources)"
          unit="个"
          icon="warning"
          :tone="failedSources > 0 ? 'danger' : 'success'"
          :hint="failedSources > 0 ? '这些源最近一次拉取失败，去源管理看看' : '所有源最近一次拉取都正常'"
        />
      </div>

      <div class="panels">
        <section class="panel">
          <div class="panel-head">
            <h2>最近活动</h2>
            <span class="muted">源拉取 / 设备心跳</span>
          </div>
          <StateBlock
            :empty="activities.length === 0"
            empty-title="还没有活动记录"
            empty-hint="导入一个源、或绑一台电视，这里就会有动静了"
            :min-height="200"
          >
            <ul class="timeline">
              <li v-for="a in activities" :key="a.key">
                <span class="tone" :class="a.tone">
                  <Icon :name="a.icon" :size="15" />
                </span>
                <div class="body">
                  <p class="title">{{ a.title }}</p>
                  <p class="desc">{{ a.desc }}</p>
                </div>
                <span class="time">{{ a.time && a.time !== '0' ? timeAgo(a.time) : '—' }}</span>
              </li>
            </ul>
          </StateBlock>
        </section>

        <section class="panel">
          <div class="panel-head">
            <h2>服务状态</h2>
            <NTag v-if="info?.version" size="small" :bordered="false">{{ info.version }}</NTag>
          </div>
          <dl class="kv">
            <div><dt>运行时长</dt><dd>{{ fmtDuration(info?.uptime_seconds) }}</dd></div>
            <div><dt>当前并发流</dt><dd>{{ info?.active_streams ?? '—' }} 路</dd></div>
            <div><dt>系统架构</dt><dd class="mono">{{ info?.arch ?? '—' }}</dd></div>
            <div><dt>源数量</dt><dd>{{ sources.length }} 个</dd></div>
          </dl>

          <div class="quick">
            <NButton size="small" secondary @click="router.push({ name: 'sources' })">
              <template #icon><Icon name="plus" :size="14" /></template>
              导入源
            </NButton>
            <NButton size="small" secondary @click="router.push({ name: 'channels' })">
              <template #icon><Icon name="channels" :size="14" /></template>
              排频道
            </NButton>
            <NButton size="small" secondary @click="router.push({ name: 'devices' })">
              <template #icon><Icon name="key" :size="14" /></template>
              绑电视
            </NButton>
          </div>

          <p v-if="playsToday === undefined" class="note">
            <Icon name="warning" :size="14" />
            概览里的「今日起播次数」需要服务端在
            <code class="mono">GET /api/v1/system/info</code> 里带上
            <code class="mono">plays_today</code>，目前没拿到，所以显示 “—”。
          </p>
        </section>
      </div>
    </StateBlock>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}
@media (max-width: 1180px) {
  .grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 620px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
.panels {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  gap: 14px;
  margin-top: 14px;
}
@media (max-width: 1080px) {
  .panels {
    grid-template-columns: 1fr;
  }
}
.panel {
  padding: 16px 18px 12px;
  border-radius: 14px;
  background: var(--litv-panel);
  border: 1px solid var(--litv-border);
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.panel-head h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #dfeaf0;
}
.panel-head .muted {
  font-size: 12px;
}
.timeline {
  list-style: none;
  margin: 0;
  padding: 0;
}
.timeline li {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 11px 0;
  border-bottom: 1px solid #1b242b;
}
.timeline li:last-child {
  border-bottom: none;
}
.tone {
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.tone.primary {
  color: #5fd3ea;
  background: rgba(31, 162, 192, 0.14);
}
.tone.success {
  color: #3fbf8f;
  background: rgba(63, 191, 143, 0.14);
}
.tone.warning {
  color: #e0a94a;
  background: rgba(224, 169, 74, 0.14);
}
.tone.danger {
  color: #e4635c;
  background: rgba(228, 99, 92, 0.14);
}
.tone.neutral {
  color: #8b9aa5;
  background: rgba(139, 154, 165, 0.12);
}
.body {
  min-width: 0;
  flex: 1 1 auto;
}
.title {
  margin: 0;
  font-size: 13.5px;
  color: #dde8ee;
}
.desc {
  margin: 3px 0 0;
  font-size: 12.5px;
  color: #7f8f9b;
  line-height: 1.5;
}
.time {
  flex: none;
  font-size: 12px;
  color: #66757f;
  white-space: nowrap;
}
.kv {
  margin: 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 10px;
}
.kv div {
  padding: 9px 11px;
  border-radius: 10px;
  background: #121a20;
  border: 1px solid #1c262e;
}
.kv dt {
  font-size: 12px;
  color: #74838e;
}
.kv dd {
  margin: 5px 0 0;
  font-size: 14px;
  font-weight: 600;
  color: #e2ecf1;
}
.quick {
  display: flex;
  gap: 8px;
  margin-top: 14px;
  flex-wrap: wrap;
}
.note {
  display: flex;
  gap: 8px;
  margin: 14px 0 4px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(224, 169, 74, 0.08);
  border: 1px solid rgba(224, 169, 74, 0.22);
  font-size: 12.5px;
  line-height: 1.65;
  color: #c9a86a;
}
.note code {
  color: #e0c48c;
}
</style>
