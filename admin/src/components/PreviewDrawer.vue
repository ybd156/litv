<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import { NButton, NDataTable, NDrawer, NDrawerContent, NTag, type DataTableColumns } from 'naive-ui'
import * as sourcesApi from '@/api/modules/sources'
import type { PreviewItem, PreviewResult, Source } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import StateBlock from './StateBlock.vue'
import Icon from './Icon.vue'
import { truncate } from '@/utils/format'

const props = defineProps<{ show: boolean; source: Source | null }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const loading = ref(false)
const error = ref('')
const result = ref<PreviewResult | null>(null)

const items = computed(() => result.value?.items ?? [])

/** 脏数据判定：没有地址、没有名称、地址不是协议开头 */
function isDirty(row: PreviewItem): boolean {
  return !row.url || !row.name || !/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(row.url)
}

const dirtyCount = computed(() => items.value.filter(isDirty).length)

const columns: DataTableColumns<PreviewItem> = [
  {
    title: '#',
    key: 'index',
    width: 54,
    render: (_row, index) => h('span', { class: 'muted mono' }, String(index + 1)),
  },
  {
    title: '频道名',
    key: 'name',
    width: 180,
    ellipsis: { tooltip: true },
    render: (row) =>
      h(
        'span',
        { style: row.name ? '' : 'color:#e4635c' },
        row.name || '（空名称）',
      ),
  },
  {
    title: '分组',
    key: 'group',
    width: 110,
    render: (row) => (row.group ? h(NTag, { size: 'small', bordered: false }, { default: () => row.group }) : h('span', { class: 'muted' }, '未分组')),
  },
  { title: 'tvg-id', key: 'tvg_id', width: 130, render: (row) => h('span', { class: 'mono' }, row.tvg_id || '—') },
  {
    title: '地址',
    key: 'url',
    minWidth: 260,
    ellipsis: { tooltip: true },
    render: (row) =>
      h(
        'span',
        { class: 'mono', style: row.url ? 'color:#8fb6c4' : 'color:#e4635c' },
        row.url || '（空地址）',
      ),
  },
  {
    title: '体检',
    key: 'check',
    width: 88,
    render: (row) =>
      isDirty(row)
        ? h(NTag, { size: 'small', type: 'error', bordered: false }, { default: () => '脏数据' })
        : h(NTag, { size: 'small', type: 'success', bordered: false }, { default: () => '正常' }),
  },
]

async function load(): Promise<void> {
  const s = props.source
  if (!s) return
  loading.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await sourcesApi.previewSource(s.id)
  } catch (e) {
    error.value = toErrorMessage(e)
  } finally {
    loading.value = false
  }
}

watch(
  () => props.show,
  (open) => {
    if (open) void load()
  },
)
</script>

<template>
  <NDrawer
    :show="show"
    :width="820"
    placement="right"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <NDrawerContent closable>
      <template #header>
        <div class="head">
          <span>预览「{{ source?.name ?? '' }}」</span>
          <NTag size="small" :bordered="false" type="info">只读，不入库</NTag>
        </div>
      </template>

      <div class="meta">
        <p class="hint-text">
          这里显示的是刚从源站拉回来的前 50 条。看名字/地址对不对、有没有空行或乱码，
          确认没问题再回去点「立即刷新」把它真正写进频道库。
        </p>
        <div class="chips">
          <NTag size="small" :bordered="false">共 {{ result?.total ?? 0 }} 条</NTag>
          <NTag v-if="result?.truncated" size="small" :bordered="false" type="warning">
            只显示前 50 条
          </NTag>
          <NTag v-if="dirtyCount > 0" size="small" :bordered="false" type="error">
            {{ dirtyCount }} 条像脏数据
          </NTag>
        </div>
      </div>

      <StateBlock :loading="loading" :error="error" :min-height="240" @retry="load">
        <StateBlock
          :empty="items.length === 0"
          empty-title="这个源一条都没解析出来"
          empty-hint="检查一下地址能不能打开、格式是不是选对了"
          :min-height="240"
        >
          <NDataTable
            :columns="columns"
            :data="items"
            :row-key="(row: PreviewItem) => `${row.name}-${row.url}`"
            :max-height="520"
            :scroll-x="1000"
            size="small"
            :bordered="false"
            striped
          />
        </StateBlock>
      </StateBlock>

      <template #footer>
        <div class="foot">
          <span class="hint-text">地址示例：{{ truncate(source?.url ?? '', 60) }}</span>
          <NButton size="small" :loading="loading" @click="load">
            <template #icon><Icon name="refresh" :size="14" /></template>
            重新拉一次
          </NButton>
        </div>
      </template>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 600;
}
.meta {
  margin-bottom: 14px;
}
.chips {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
</style>
