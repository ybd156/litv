<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { NButton, NDataTable, NSpace, NTag, NTooltip, type DataTableColumns } from 'naive-ui'
import PageHeader from '@/components/PageHeader.vue'
import StateBlock from '@/components/StateBlock.vue'
import BindCodeModal from '@/components/BindCodeModal.vue'
import DeviceConfigModal from '@/components/DeviceConfigModal.vue'
import Icon from '@/components/Icon.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import * as devicesApi from '@/api/modules/devices'
import type { Device } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { confirmAction, toast } from '@/utils/feedback'
import { fmtDateTime, timeAgo } from '@/utils/format'

interface DevicesData {
  devices: Device[]
  pending: Device[]
}

const { data, loading, error, run } = useAsyncData<DevicesData>(async () => {
  const [d, p] = await Promise.allSettled([devicesApi.listDevices(), devicesApi.listPendingDevices()])
  const devices = d.status === 'fulfilled' ? d.value : []
  let pending = p.status === 'fulfilled' ? p.value : []
  if (pending.length === 0) pending = devices.filter((x) => x.status === 'pending')
  return { devices, pending }
})

const devices = computed(() => data.value?.devices ?? [])
const pending = computed(() => data.value?.pending ?? [])
const onlineCount = computed(() => devices.value.filter((d) => d.online).length)

const bindShow = ref(false)
const configShow = ref(false)
const configTarget = ref<Device | null>(null)
const busyId = ref<number | null>(null)

const DECODE_LABEL: Record<string, string> = { hard: '硬解', soft: '软解', auto: '自动' }
const ASPECT_LABEL: Record<string, string> = { auto: '自动', '16:9': '16:9', '4:3': '4:3', fill: '拉伸' }

function configSummary(d: Device): string {
  const c = d.config
  if (!c) return '默认'
  const parts: string[] = []
  if (c.decode) parts.push(DECODE_LABEL[c.decode] ?? c.decode)
  if (c.aspect) parts.push(ASPECT_LABEL[c.aspect] ?? c.aspect)
  if (typeof c.buffer_ms === 'number') parts.push(`${c.buffer_ms}ms`)
  return parts.length ? parts.join(' · ') : '默认'
}

function openConfig(d: Device): void {
  configTarget.value = d
  configShow.value = true
}

async function approve(d: Device): Promise<void> {
  busyId.value = d.id
  try {
    await devicesApi.approveDevice(d.id)
    toast.success(`已批准「${d.name}」`)
    await run(true)
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    busyId.value = null
  }
}

function reject(d: Device): void {
  confirmAction({
    title: '拒绝这台设备？',
    content: `「${d.name}」将无法绑定，需要重新输入绑定码。`,
    positiveText: '拒绝',
    onConfirm: async () => {
      await devicesApi.rejectDevice(d.id)
      toast.success('已拒绝')
      await run(true)
    },
  })
}

function revoke(d: Device): void {
  confirmAction({
    title: '吊销这台设备？',
    content: `「${d.name}」的 token 会立即失效，电视上需要重新生成绑定码才能再连上。`,
    positiveText: '吊销',
    onConfirm: async () => {
      await devicesApi.revokeDevice(d.id)
      toast.success('已吊销')
      await run(true)
    },
  })
}

const columns: DataTableColumns<Device> = [
  {
    title: '设备',
    key: 'name',
    width: 210,
    fixed: 'left',
    render: (row) =>
      h('div', { class: 'dev-cell' }, [
        h('span', { class: 'nm' }, row.name),
        row.status === 'pending'
          ? h(NTag, { size: 'tiny', type: 'warning', bordered: false }, { default: () => '待批准' })
          : null,
      ]),
  },
  {
    title: '平台 / 型号',
    key: 'platform',
    width: 200,
    render: (row) =>
      h('div', { class: 'dev-cell' }, [
        h('span', {}, row.platform || '未知平台'),
        h('span', { class: 'sub' }, row.model || '—'),
      ]),
  },
  {
    title: '在线',
    key: 'online',
    width: 96,
    render: (row) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.online ? 'success' : 'default' },
        { default: () => (row.online ? '在线' : '离线') },
      ),
  },
  {
    title: '最后心跳',
    key: 'last_heartbeat_at',
    width: 150,
    render: (row) =>
      h(
        NTooltip,
        null,
        {
          trigger: () =>
            h(
              'span',
              { class: row.last_heartbeat_at ? '' : 'muted' },
              // 心跳为 0 时不能拿去算相对时间——会算成「57 年前」（1970 年）
              row.last_heartbeat_at ? timeAgo(row.last_heartbeat_at) : '从未',
            ),
          default: () => (row.last_heartbeat_at ? fmtDateTime(row.last_heartbeat_at) : '还没上报过心跳'),
        },
      ),
  },
  {
    title: 'IP',
    key: 'ip',
    width: 130,
    render: (row) => h('span', { class: 'mono' }, row.ip || '—'),
  },
  {
    title: '版本',
    key: 'app_version',
    width: 96,
    render: (row) => h('span', { class: 'mono' }, row.app_version || '—'),
  },
  {
    title: '远程配置',
    key: 'config',
    width: 190,
    render: (row) =>
      row.config
        ? h(NTag, { size: 'small', bordered: false, type: 'info' }, { default: () => configSummary(row) })
        : h('span', { class: 'muted' }, '默认（未覆盖）'),
  },
  {
    title: '操作',
    key: 'actions',
    width: 170,
    fixed: 'right',
    render: (row) =>
      h(NSpace, { size: 4, wrap: false }, {
        default: () => [
          h(
            NButton,
            { size: 'tiny', quaternary: true, type: 'primary', onClick: () => openConfig(row) },
            { default: () => '配置' },
          ),
          h(
            NButton,
            { size: 'tiny', quaternary: true, type: 'error', onClick: () => revoke(row) },
            { default: () => '吊销' },
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
      title="终端设备"
      subtitle="电视、手机、盒子都是终端。新设备要先输绑定码，再在这里批准，才能看你的频道。"
    >
      <template #actions>
        <NButton size="small" :loading="loading" @click="run(true)">
          <template #icon><Icon name="refresh" :size="15" /></template>
          刷新
        </NButton>
        <NButton size="small" type="primary" @click="bindShow = true">
          <template #icon><Icon name="key" :size="15" /></template>
          生成绑定码
        </NButton>
      </template>
    </PageHeader>

    <div v-if="pending.length > 0" class="pending">
      <div class="pending-head">
        <Icon name="warning" :size="16" />
        <span>{{ pending.length }} 台设备正在等你批准</span>
      </div>
      <div v-for="d in pending" :key="d.id" class="pending-row">
        <div class="info">
          <span class="nm">{{ d.name }}</span>
          <span class="sub">{{ d.platform }}{{ d.model ? ` · ${d.model}` : '' }}{{ d.ip ? ` · ${d.ip}` : '' }}</span>
        </div>
        <NSpace :size="6">
          <NButton size="tiny" type="primary" :loading="busyId === d.id" @click="approve(d)">批准</NButton>
          <NButton size="tiny" quaternary @click="reject(d)">拒绝</NButton>
        </NSpace>
      </div>
    </div>

    <div class="summary">
      <NTag size="small" :bordered="false">共 {{ devices.length }} 台</NTag>
      <NTag size="small" :bordered="false" type="success">{{ onlineCount }} 台在线</NTag>
    </div>

    <div class="card-panel table-wrap">
      <StateBlock
        :loading="loading && !data"
        :error="error"
        :empty="devices.length === 0"
        :min-height="320"
        @retry="run()"
      >
        <template #empty-action>
          <NButton size="small" type="primary" @click="bindShow = true">
            <template #icon><Icon name="key" :size="14" /></template>
            生成绑定码去绑一台
          </NButton>
        </template>
        <NDataTable
          :columns="columns"
          :data="devices"
          :row-key="(row: Device) => row.id"
          :scroll-x="1220"
          :bordered="false"
          size="small"
          striped
        />
      </StateBlock>
    </div>

    <BindCodeModal v-model:show="bindShow" />
    <DeviceConfigModal v-model:show="configShow" :device="configTarget" @saved="run(true)" />
  </div>
</template>

<style scoped>
.pending {
  padding: 12px 14px;
  margin-bottom: 14px;
  border-radius: 13px;
  background: rgba(224, 169, 74, 0.09);
  border: 1px solid rgba(224, 169, 74, 0.26);
}
.pending-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #e0a94a;
  margin-bottom: 10px;
}
.pending-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border-radius: 9px;
  background: rgba(13, 18, 22, 0.45);
  margin-bottom: 6px;
}
.pending-row:last-child {
  margin-bottom: 0;
}
.pending-row .info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.pending-row .nm {
  font-size: 13px;
  color: #e2ecf1;
  font-weight: 500;
}
.pending-row .sub {
  font-size: 11.5px;
  color: #8b9aa5;
}
.summary {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.table-wrap {
  padding: 6px 6px 2px;
  overflow: hidden;
}
:deep(.dev-cell) {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-width: 0;
}
:deep(.dev-cell .nm) {
  color: #e2ecf1;
  font-weight: 500;
}
:deep(.dev-cell .sub) {
  font-size: 11.5px;
  color: #74838e;
}
</style>
