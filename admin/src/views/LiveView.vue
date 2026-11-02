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
const muted = ref(true)
const fullscreen = ref(false)

const videoEl = ref<HTMLVideoElement | null>(null)
const stageEl = ref<HTMLElement | null>(null)
const hls = shallowRef<Hls | null>(null)

// B05：播放请求的代号。每点一次台就 +1，响应回来时对不上号就丢弃。
// 用一个普通变量而不是 ref：它只用于判定"这份响应是否还有效"，不需要响应式。
let playGeneration = 0

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
  // B05：请求序列保护。
  //
  // 连续点 A、B 时，A 的响应可能比 B 晚到（服务端要拉上游清单），
  // 后到的旧响应会把新选的频道覆盖掉——用户看到的是"点 B 却放了 A"。
  // 每发起一次播放就递增 generation，回来时对不上号就直接丢弃。
  const gen = ++playGeneration
  playing.value = ch
  playError.value = ''
  destroyPlayer()

  try {
    const info = await request<WebPlay>(`/web/play/${ch.id}`)
    // 回来时已经切到别的台了：这份响应作废，绝不能碰播放器。
    if (gen !== playGeneration) return
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
        // 已经切台了：这个实例的报错不该影响当前画面
        if (gen !== playGeneration) {
          h.destroy()
          return
        }
        switch (data.type) {
          case Hls.ErrorTypes.NETWORK_ERROR:
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
        if (gen !== playGeneration) return
        el.play().catch(() => {
          /* 浏览器可能要求先点一下，忽略 */
        })
      })
      h.loadSource(info.url)
      h.attachMedia(el)
      hls.value = h
    } else if (el.canPlayType('application/vnd.apple.mpegurl')) {
      el.src = info.url
      el.play().catch(() => {})
    } else {
      playError.value = '这个浏览器不支持 HLS 播放'
    }
  } catch (e) {
    // 失败也可能是过期的：别让旧请求的错误覆盖新台的状态
    if (gen !== playGeneration) return
    playError.value = e instanceof Error ? e.message : '获取播放地址失败'
  }
}

function toggleMute() {
  const el = videoEl.value
  if (!el) return
  muted.value = !muted.value
  el.muted = muted.value
}

async function toggleFullscreen() {
  const el = stageEl.value
  if (!el) return
  try {
    if (!document.fullscreenElement) {
      await el.requestFullscreen()
      fullscreen.value = true
    } else {
      await document.exitFullscreen()
      fullscreen.value = false
    }
  } catch {
    message.warning('这个浏览器不支持全屏')
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
      <aside class="channels">
        <NSpin :show="loading">
          <StateBlock
            v-if="!loading && channels.length === 0"
            title="还没有频道"
            desc="先去「源管理」导入一条 M3U/TXT，再回来这里看"
          />
          <div v-for="[group, list] in grouped" :key="group" class="group">
            <div class="group-title">
              {{ group }}
              <NTag size="small" :bordered="false">{{ list.length }}</NTag>
            </div>
            <button
              v-for="ch in list"
              :key="ch.id"
              class="ch"
              :class="{ active: playing?.id === ch.id }"
              @click="play(ch)"
            >
              <img v-if="ch.logo" :src="ch.logo" alt="" />
              <span class="name">{{ ch.name }}</span>
              <span v-if="ch.stream_count > 1" class="badge">{{ ch.stream_count }}</span>
            </button>
          </div>
        </NSpin>
      </aside>

      <!-- 右：播放器 -->
      <section class="player">
        <div ref="stageEl" class="stage">
          <video
            ref="videoEl"
            playsinline
            muted
            @waiting="buffering = true"
            @playing="buffering = false"
            @click="toggleMute"
          />

          <!-- 占位：绝对定位盖在画面上，不参与 flex 排版 -->
          <div v-if="!playing" class="overlay">
            <div class="ph-mark">LITV</div>
            <div class="ph-title">选一个频道开始</div>
            <div class="ph-desc">左边点任意频道即可播放</div>
          </div>

          <div v-if="playError" class="overlay err">{{ playError }}</div>

          <div v-if="buffering && playing && !playError" class="overlay">
            <NSpin size="large" />
          </div>

          <!-- 悬浮控制条 -->
          <div v-if="playing" class="controls">
            <span class="live-dot" />
            <span class="cname">{{ playing.name }}</span>
            <span class="grow" />
            <button class="ctl" :title="muted ? '取消静音' : '静音'" @click="toggleMute">
              {{ muted ? '🔇' : '🔊' }}
            </button>
            <button class="ctl" title="全屏" @click="toggleFullscreen">⛶</button>
          </div>
        </div>

        <div v-if="playing" class="meta">
          <span class="now">{{ playing.name }}</span>
          <span class="hint">{{ playing.group || '未分组' }}</span>
          <span class="grow" />
          <span class="hint">点画面可静音 / 取消静音</span>
        </div>
      </section>
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
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 16px;
  flex: 1;
  min-height: 0;
}

/* ---------------- 左侧频道 ---------------- */
.channels {
  overflow-y: auto;
  background: var(--litv-panel, #151a21);
  border-radius: 10px;
  padding: 8px;
  min-height: 0;
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

/* ---------------- 右侧播放器 ---------------- */
.player {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

/*
 * ★ 这里必须是 position:relative + 绝对定位的子元素。
 *   之前用 display:flex 排版，结果 <video> 和占位文字被摆成一左一右，
 *   占位文字跑到画面外面去了。
 */
.stage {
  position: relative;
  flex: 1;
  min-height: 0;
  background: #000;
  border-radius: 10px;
  overflow: hidden;
}
.stage video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
  cursor: pointer;
}

/* 占位 / 报错 / 缓冲：全部盖在画面正中 */
.overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-align: center;
  pointer-events: none;
  padding: 0 24px;
}
.overlay.err {
  color: #ff8f88;
  font-size: 13px;
  line-height: 1.6;
  background: #000000aa;
  pointer-events: auto;
}
.ph-mark {
  font-size: 26px;
  font-weight: 800;
  letter-spacing: 6px;
  color: #ffffff22;
}
.ph-title {
  font-size: 15px;
  color: #c9d1d9;
}
.ph-desc {
  font-size: 12px;
  color: #8b949e;
}

/* 悬浮控制条 */
.controls {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: linear-gradient(to top, #000000cc, transparent);
  opacity: 0;
  transition: opacity 0.18s;
}
.stage:hover .controls {
  opacity: 1;
}
.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #f85149;
  box-shadow: 0 0 6px #f85149;
}
.cname {
  font-size: 13px;
  color: #e6edf3;
  max-width: 50%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ctl {
  border: 0;
  background: #ffffff14;
  color: #e6edf3;
  width: 30px;
  height: 30px;
  border-radius: 7px;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
}
.ctl:hover {
  background: #ffffff26;
}
.grow {
  flex: 1;
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
