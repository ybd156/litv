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
import type { EpgSource } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { confirmAction, toast } from '@/utils/feedback'
import { fmtCount, fmtDateTime, timeAgo } from '@/utils/format'

const { data, loading, error, run } = useAsyncData<EpgSource[]>(epgApi.listEpgSources)
const sources = computed(() => data.value ?? [])

const showAdd = ref(false)
const saving = ref(false)
const refreshing = ref(false)
const form = ref({ name: '', url: '' })

async function refreshAll(): Promise<void> {
  refreshing.value = true
  try {
    await epgApi.refreshEpg()
    toast.success('已开始刷新 EPG，节目单会在几分钟内更新')
    await run(true)
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    refreshing.value = false
  }
}

async function submit(): Promise<void> {
  if (!form.value.name.trim() || !form.value.url.trim()) {
    toast.warning('名称和地址都要填')
    return
  }
  saving.value = true
  try {
    await epgApi.createEpgSource({
      name: form.value.name.trim(),
      url: form.value.url.trim(),
      enabled: true,
    })
    toast.success('EPG 源已添加')
    showAdd.value = false
    form.value = { name: '', url: '' }
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
    width: 90,
    fixed: 'right',
    render: (row) =>
      h(
        NButton,
        { size: 'tiny', quaternary: true, type: 'error', onClick: () => remove(row) },
        { default: () => '删除' },
      ),
  },
]

onMounted(() => void run())
</script>

<template>
  <div>
    <div class="toolbar">
      <NButton size="small" :loading="refreshing" type="primary" @click="refreshAll">
        <template #icon><Icon name="refresh" :size="15" /></template>
        立即刷新全部 EPG
      </NButton>
      <NButton size="small" secondary @click="showAdd = true">
        <template #icon><Icon name="plus" :size="15" /></template>
        添加 EPG 源
      </NButton>
      <span class="spacer" />
      <span class="hint-text">EPG 就是电视上的「节目单」，刷新会重新下载 XMLTV 并解析。</span>
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
          <NButton size="small" type="primary" @click="showAdd = true">
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

    <NModal
      v-model:show="showAdd"
      preset="card"
      title="添加 EPG 源"
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
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton quaternary @click="showAdd = false">取消</NButton>
          <NButton type="primary" :loading="saving" @click="submit">添加</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.table-wrap {
  padding: 6px 6px 2px;
  overflow: hidden;
}
</style>
