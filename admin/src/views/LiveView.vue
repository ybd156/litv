<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import Hls from 'hls.js'
import { NInput, NSpin, NTag, useMessage } from 'naive-ui'
import { request } from '@/api'
import PageHeader from '@/components/PageHeader.vue'
import StateBlock from '@/components/StateBlock.vue'

/**
 * Web 端直接看：浏览器里直接播直播，不用装 App。
 *
 * 技术约束（很重要）：
 *   浏览器**播不了裸 MPEG-TS**，只认 HLS（m3u8）或 MP4。
 *   所以这个页面拿到的地址一定是 HLS：
 *     - 上游本来就是 m3u8 → 直接代理
 *     - 上游是裸 TS → 服务端用 ffmpeg 转封装成 HLS（只转封装不转码）
 *   服务端没配 ffmpeg 时，裸 TS 频道会明确报错而不是黑屏。
 */

interface Channel {
  id: number
  name: string
  group: string
  logo: string
  stream_count: number
}

interface WebPlay {
  kind: string
  url: string
  channel: { id: number; name: string; logo: string }
}

const message = useMessage()

const channels = ref<Channel[]>([])
const keyword = ref('')
const loading = ref(true)
const playing = ref<Channel | null>(null)
const playError = ref('')
const buffering = ref(false)

const videoEl = ref<HTMLVideoElement | null>(null)
const hls = shallowRef<Hls | null>(null)

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return channels.value
  return channels.value.filter(
    (c) => c.name.toLowerCase().includes(k) || c.group.toLowerCase().includes(k),
  )
})

const grouped = computed(() => {
  const m = new Map<string, Channel[]>()
  for (const c of filtered.value) {
    const g = c.group || '未分组'
    if (!m.has(g)) m.set(g, [])
    m.get(g)!.push(c)
  }
  return [...m.entries()]
})

async function loadChannels() {
  loading.value = true
  try {
    const r = await request<{ total: number; items: Channel[] }>('/channels', {
      query: { limit: 500, enabled: true },
    })
    channels.value = r.items ?? []
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载频道失败')
  } finally {
    loading.value = false
  }
}

function destroyPlayer() {
  if (hls.value) {
    hls.value.destroy()
    hls.value = null
  }
  if (videoEl.value) {
    videoEl.value.removeAttribute('src')
    videoEl.value.load()
  }
}

async function play(ch: Channel) {
  playing.value = ch
  playError.value = ''
  destroyPlayer()

  try {
    const info = await request<WebPlay>(`/web/play/${ch.id}`)
    const url = info.url
    const el = videoEl.value
    if (!el) return

    if (Hls.isSupported()) {
      const h = new Hls({
        // 直播低延迟优先：只保留很短的回看缓冲
        liveDurationInfinity: true,
        backBufferLength: 10,
        maxBufferLength: 6,
        lowLatencyMode: true,
      })
      h.on(Hls.Events.ERROR, (_evt, data) => {
        if (!data.fatal) return
        switch (data.type) {
          case Hls.ErrorTypes.NETWORK_ERROR:
            // 网络抖动：hls.js 自己能重试，别打扰用户
            h.startLoad()
            break
          case Hls.ErrorTypes.MEDIA_ERROR:
            h.recoverMediaError()
            break
          default:
            playError.value = `播放失败：${data.details}`
            h.destroy()
        }
      })
      h.on(Hls.Events.MANIFEST_PARSED, () => {
        el.play().catch(() => {
          /* 浏览器可能要求先点一下，忽略 */
        })
      })
      h.loadSource(url)
      h.attachMedia(el)
      hls.value = h
    } else if (el.canPlayType('application/vnd.apple.mpegurl')) {
      // Safari 原生支持 HLS
      el.src = url
      el.play().catch(() => {})
    } else {
      playError.value = '这个浏览器不支持 HLS 播放'
    }
  } catch (e) {
    playError.value = e instanceof Error ? e.message : '获取播放地址失败'
  }
}

watch(playing, () => {
  buffering.value = true
})

onMounted(loadChannels)
onBeforeUnmount(destroyPlayer)
</script>

<template>
  <div class="live">
    <PageHeader title="直播预览" desc="在浏览器里直接看，不用装 App。裸 TS 源会由服务端自动转封装成 HLS。">
      <template #extra>
        <NInput v-model:value="keyword" placeholder="搜频道 / 分组" clearable style="width: 220px" />
      </template>
    </PageHeader>

    <div class="body">
      <!-- 左：频道 -->
      <div class="channels">
        <NSpin :show="loading">
          <StateBlock
            v-if="!loading && channels.length === 0"
            title="还没有频道"
            desc="先去「源管理」导入一条 M3U/TXT，再回来这里看"
          />
          <div v-for="[group, list] in grouped" :key="group" class="group">
            <div class="group-title">{{ group }}<NTag size="small" :bordered="false">{{ list.length }}</NTag></div>
            <button
              v-for="ch in list"
              :key="ch.id"
              class="ch"
              :class="{ active: playing?.id === ch.id }"
              @click="play(ch)"
            >
              <img v-if="ch.logo" :src="ch.logo" alt="" />
              <span class="name">{{ ch.name }}</span>
              <span v-if="ch.stream_count > 1" class="badge">{{ ch.stream_count }} 线路</span>
            </button>
          </div>
        </NSpin>
      </div>

      <!-- 右：播放器 -->
      <div class="player">
        <div class="stage">
          <video ref="videoEl" controls autoplay playsinline @waiting="buffering = true" @playing="buffering = false" />
          <div v-if="!playing" class="placeholder">
            <div class="ph-title">选一个频道开始</div>
            <div class="ph-desc">左边点任意频道即可播放</div>
          </div>
          <div v-if="playError" class="err">{{ playError }}</div>
        </div>
        <div v-if="playing" class="meta">
          <span class="now">{{ playing.name }}</span>
          <span class="hint">当前：{{ playing.group }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.live {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 12px;
}
.body {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 16px;
  flex: 1;
  min-height: 0;
}
.channels {
  overflow-y: auto;
  background: var(--litv-panel, #151a21);
  border-radius: 10px;
  padding: 8px;
}
.group + .group {
  margin-top: 12px;
}
.group-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #8b949e;
  padding: 6px 8px;
}
.ch {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #c9d1d9;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.ch:hover {
  background: #1c232c;
}
.ch.active {
  background: #2dd4bf22;
  color: #2dd4bf;
}
.ch img {
  width: 22px;
  height: 22px;
  object-fit: contain;
  flex: 0 0 auto;
}
.ch .name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ch .badge {
  font-size: 11px;
  color: #2dd4bf;
  opacity: 0.8;
}
.player {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.stage {
  position: relative;
  flex: 1;
  background: #000;
  border-radius: 10px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
.stage video {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.placeholder {
  text-align: center;
  color: #8b949e;
}
.ph-title {
  font-size: 16px;
  margin-bottom: 4px;
}
.ph-desc {
  font-size: 13px;
  opacity: 0.7;
}
.err {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  max-width: 70%;
  padding: 14px 18px;
  border-radius: 8px;
  background: #f8514922;
  color: #ff8f88;
  font-size: 13px;
  line-height: 1.6;
  text-align: center;
}
.meta {
  display: flex;
  gap: 12px;
  align-items: baseline;
  padding: 8px 4px 0;
}
.meta .now {
  font-size: 15px;
  color: #e6edf3;
}
.meta .hint {
  font-size: 12px;
  color: #8b949e;
}
</style>
