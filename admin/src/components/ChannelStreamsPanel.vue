<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NButton, NInput, NTag } from 'naive-ui'
import * as channelsApi from '@/api/modules/channels'
import type { ChannelStream } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { confirmDanger, toast } from '@/utils/feedback'
import { canWrite } from '@/session'
import StateBlock from './StateBlock.vue'
import Icon from './Icon.vue'

const props = defineProps<{
  channelId: number
  channelName: string
  /** 频道已被上游移除（线路已被清空），面板顶部要说明 */
  channelMissing?: boolean
}>()
const emit = defineEmits<{ (e: 'changed'): void }>()

const streams = ref<ChannelStream[]>([])
const loading = ref(false)
const error = ref('')
const adding = ref(false)
const newUrl = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    streams.value = await channelsApi.listStreams(props.channelId)
  } catch (e) {
    error.value = toErrorMessage(e)
  } finally {
    loading.value = false
  }
}

async function add(): Promise<void> {
  const url = newUrl.value.trim()
  if (!url) {
    toast.warning('先把线路地址填上')
    return
  }
  if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(url)) {
    toast.warning('地址要以 http:// 或 rtmp:// 这种协议开头')
    return
  }
  adding.value = true
  try {
    await channelsApi.addStream(props.channelId, { url })
    newUrl.value = ''
    toast.success('线路已添加')
    await load()
    emit('changed')
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    adding.value = false
  }
}

function remove(stream: ChannelStream): void {
  confirmDanger({
    action: '删除这条线路',
    count: 1,
    unit: '条线路',
    cascade: ['这条线路的播放', '该频道上正在用它的设备'],
    note: `属于「${props.channelName}」。其它线路不受影响。`,
    onConfirm: async () => {
      await channelsApi.deleteStream(props.channelId, stream.id)
      toast.success('已删除')
      await load()
      emit('changed')
    },
    confirmText: '删除',
  })
}

onMounted(() => void load())
</script>

<template>
  <div class="streams">
    <div class="head">
      <span class="title">线路（同一频道可以有多个地址，播放器会挑能用的那条）</span>
      <NTag size="small" :bordered="false">{{ streams.length }} 条</NTag>
    </div>

    <p v-if="channelMissing" class="warn-note">
      上游源里已经没有这个台了：这一轮刷新把它下线并清空了线路。
      如果只是源临时抽风，重新刷新源就能恢复；也可以手动补一条线路继续看。
    </p>

    <!-- 三态统一走 StateBlock（紧凑模式）：以前这里是三行手写文案，和列表页的
         加载/错误/空态长得不一样，展开行里还会把行撑高。 -->
    <StateBlock
      :loading="loading && streams.length === 0"
      :error="error"
      :empty="!loading && streams.length === 0"
      :loading-text="`正在加载「${props.channelName}」的线路…`"
      empty-title="这个频道还没有线路"
      empty-hint="线路是播放的地址。粘一条源地址（m3u8 / ts / rtmp 都行）就能播；也可以先在「源管理」刷新一次源，频道线路会自动带过来。"
      error-hint="检查服务端是否在运行；多次失败可以在「系统设置 → 运行日志」里看原因。"
      :min-height="72"
      compact
      @retry="load"
    >
      <ul class="list">
        <li v-for="s in streams" :key="s.id">
          <span class="idx mono">#{{ s.id }}</span>
          <span class="url mono ellipsis" :title="s.url">{{ s.url }}</span>
          <NTag v-if="s.quality" size="tiny" :bordered="false">{{ s.quality }}</NTag>
          <NTag v-if="typeof s.latency_ms === 'number'" size="tiny" :bordered="false" type="info">
            {{ s.latency_ms }}ms
          </NTag>
          <span class="src">
            {{ s.source_name || (s.source_id ? `源 #${s.source_id}` : '手动添加') }}
          </span>
          <NButton size="tiny" quaternary type="error" :disabled="!canWrite" @click="remove(s)">
            <template #icon><Icon name="trash" :size="13" /></template>
          </NButton>
        </li>
      </ul>
    </StateBlock>

    <div class="add">
      <NInput
        v-model:value="newUrl"
        size="small"
        :disabled="!canWrite"
        placeholder="粘贴一条线路地址，例如 http://live.example.com/cctv1.m3u8"
        @keyup.enter="add"
      />
      <NButton size="small" type="primary" secondary :loading="adding" :disabled="!canWrite" @click="add">
        <template #icon><Icon name="plus" :size="14" /></template>
        加线路
      </NButton>
    </div>
  </div>
</template>

<style scoped>
.streams {
  padding: 6px 10px 12px 14px;
  background: #111920;
  border-radius: 10px;
  border: 1px solid #1c262e;
}
.head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.head .title {
  font-size: 12.5px;
  color: #8b9aa5;
}
.list {
  list-style: none;
  margin: 0 0 10px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 8px;
  background: #141d24;
  border: 1px solid #1e2830;
}
.idx {
  color: #5f7280;
  flex: none;
}
.url {
  flex: 1 1 auto;
  min-width: 0;
  color: #9fc0cc;
}
.src {
  flex: none;
  font-size: 11.5px;
  color: #6c7b86;
}
.add {
  display: flex;
  gap: 8px;
  align-items: center;
}
.err {
  color: #e4635c;
  font-size: 12.5px;
  margin: 0 0 8px;
}
/* 「上游已移除」说明：warning 色，和「加载失败」的红色区分开 */
.warn-note {
  color: #e0a94a;
  font-size: 12.5px;
  line-height: 1.6;
  margin: 0 0 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(224, 169, 74, 0.09);
  border: 1px solid rgba(224, 169, 74, 0.26);
}
.small {
  font-size: 12.5px;
  margin: 0 0 8px;
}
</style>
