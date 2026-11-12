<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { NButton, NDataTable, NInput, NSpace, NTag, type DataTableColumns } from 'naive-ui'
import PageHeader from '@/components/PageHeader.vue'
import StateBlock from '@/components/StateBlock.vue'
import Icon from '@/components/Icon.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import * as blockedApi from '@/api/modules/blocked'
import type { BlockedCategory, BlockedInfo } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { confirmDanger, toast } from '@/utils/feedback'
import { canWrite } from '@/session'

/**
 * 屏蔽：整类或单个频道不下发给客户端。
 *
 * 为什么不是「隐藏」：订阅源里几百个台，购物 / 测试 / 成人这类整类不想要，
 * 逐个点隐藏太费事；而且源每周都会加新的同类频道，藏完还得再藏一遍。
 * 按**分类**屏蔽会连以后新加的同类频道一起挡掉。
 *
 * 分类是**从源里读出来的**（服务端的 candidates），不用手打分组名。
 */
const { data, loading, error, run } = useAsyncData<BlockedInfo>(() => blockedApi.getBlocked())

const categories = computed(() => data.value?.candidates ?? [])
const blockedChannels = computed(() => data.value?.channels ?? [])
const blockedCount = computed(() => data.value?.groups.length ?? 0)

const busy = ref('')
const newChannel = ref('')

function keyOf(kind: string, name: string): string {
  return `${kind}:${name}`
}

async function refresh(): Promise<void> {
  await run(true)
}

function blockCategory(row: BlockedCategory): void {
  confirmDanger({
    action: `屏蔽分类「${row.name}」`,
    count: row.count,
    unit: '个频道',
    // 这个组件会把 cascade 渲染成「A / B 会失效」，所以这里只放名词短语
    cascade: ['这些频道的可见性'],
    note:
      `点确认后立刻生效；以后订阅里新加的同类频道也会一起挡住。` +
      `只是不下发，源里的数据不会被删，随时能在这里解除。`,
    reversible: true,
    confirmText: '屏蔽',
    onConfirm: async () => {
      busy.value = keyOf('group', row.name)
      try {
        await blockedApi.addBlocked('group', row.name)
        toast.success(`已屏蔽分类「${row.name}」`)
        await refresh()
      } catch (e) {
        toast.error(toErrorMessage(e))
      } finally {
        busy.value = ''
      }
    },
  })
}

async function unblockCategory(row: BlockedCategory): Promise<void> {
  busy.value = keyOf('group', row.name)
  try {
    await blockedApi.removeBlocked('group', row.name)
    toast.success(`已解除「${row.name}」`)
    await refresh()
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    busy.value = ''
  }
}

async function blockChannel(): Promise<void> {
  const name = newChannel.value.trim()
  if (!name) {
    toast.error('先填一个频道名')
    return
  }
  busy.value = keyOf('channel', name)
  try {
    await blockedApi.addBlocked('channel', name)
    toast.success(`已屏蔽频道「${name}」`)
    newChannel.value = ''
    await refresh()
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    busy.value = ''
  }
}

async function unblockChannel(name: string): Promise<void> {
  busy.value = keyOf('channel', name)
  try {
    await blockedApi.removeBlocked('channel', name)
    toast.success(`已解除「${name}」`)
    await refresh()
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    busy.value = ''
  }
}

const columns: DataTableColumns<BlockedCategory> = [
  {
    title: '分类',
    key: 'name',
    width: 220,
    fixed: 'left',
    render: (row) =>
      h('div', { class: 'cell' }, [
        h('span', { class: row.blocked ? 'nm off' : 'nm' }, row.name),
        row.blocked ? h(NTag, { size: 'tiny', type: 'error', bordered: false }, { default: () => '已屏蔽' }) : null,
      ]),
  },
  {
    title: '频道数',
    key: 'count',
    width: 100,
    render: (row) => h('span', { class: 'mono' }, String(row.count)),
  },
  {
    title: '客户端能看到',
    key: 'blocked',
    width: 140,
    render: (row) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.blocked ? 'error' : 'success' },
        { default: () => (row.blocked ? '看不到' : '看得到') },
      ),
  },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    fixed: 'right',
    render: (row) =>
      row.blocked
        ? h(
            NButton,
            {
              size: 'tiny',
              quaternary: true,
              type: 'primary',
              disabled: !canWrite.value || busy.value === keyOf('group', row.name),
              loading: busy.value === keyOf('group', row.name),
              onClick: () => void unblockCategory(row),
            },
            { default: () => '解除屏蔽' },
          )
        : h(
            NButton,
            {
              size: 'tiny',
              quaternary: true,
              type: 'error',
              disabled: !canWrite.value || busy.value === keyOf('group', row.name),
              loading: busy.value === keyOf('group', row.name),
              onClick: () => blockCategory(row),
            },
            { default: () => '屏蔽这个分类' },
          ),
  },
]

onMounted(() => void run())
</script>

<template>
  <div>
    <PageHeader
      title="屏蔽"
      subtitle="整类不想看的频道（购物、测试、成人…）在这里挡掉，客户端就再也不会收到。分类是从源里读出来的。"
    >
      <template #actions>
        <NButton size="small" :loading="loading" @click="run(true)">
          <template #icon><Icon name="refresh" :size="15" /></template>
          刷新
        </NButton>
      </template>
    </PageHeader>

    <div class="tip">
      <Icon name="warning" :size="15" />
      <span>
        屏蔽只影响「下发什么」：<b>点完立刻生效</b>，下一次重建时这些频道也不再入库、不进
        playlist.m3u。源里的数据不会被删。按分类屏蔽会连以后新加的同类频道一起挡住；
        只想藏一个台，用频道管理页那一行的「隐藏」。
      </span>
    </div>

    <div class="summary">
      <NTag size="small" :bordered="false" type="success">共 {{ categories.length }} 个分类</NTag>
      <NTag size="small" :bordered="false" :type="blockedCount > 0 ? 'error' : 'default'">
        {{ blockedCount }} 个已屏蔽
      </NTag>
      <NTag size="small" :bordered="false">
        {{ blockedChannels.length }} 个单台屏蔽
      </NTag>
    </div>

    <div class="card-panel table-wrap">
      <StateBlock
        :loading="loading && !data"
        :error="error"
        :empty="categories.length === 0"
        :min-height="280"
        @retry="run()"
      >
        <template #empty-action>
          <span class="muted">先在「源管理」里配一个订阅源，等一次重建之后分类就有了。</span>
        </template>
        <NDataTable
          :columns="columns"
          :data="categories"
          :row-key="(row: BlockedCategory) => row.name"
          :scroll-x="700"
          :bordered="false"
          size="small"
          striped
        />
      </StateBlock>
    </div>

    <div class="card-panel">
      <div class="sec-title">按频道名屏蔽</div>
      <!-- 模板里不能写 markdown 的 ** **，会原样显示成星号；要强调就用 <b> -->
      <div class="sec-sub">精确匹配；改过名的频道按 <b>改后的名字</b> 匹配。</div>
      <NSpace :size="8" align="center" class="row">
        <NInput
          v-model:value="newChannel"
          placeholder="频道名，比如：CCTV-5 体育"
          clearable
          style="width: 320px"
          @keyup.enter="blockChannel"
        />
        <NButton
          size="small"
          type="error"
          :disabled="!canWrite || !newChannel.trim()"
          :loading="busy === keyOf('channel', newChannel.trim())"
          @click="blockChannel"
        >
          屏蔽这个频道
        </NButton>
      </NSpace>

      <div v-if="blockedChannels.length > 0" class="chips">
        <span v-for="c in blockedChannels" :key="c" class="chip">
          {{ c }}
          <button class="chip-x" :disabled="!canWrite" title="解除屏蔽" @click="unblockChannel(c)">×</button>
        </span>
      </div>
      <div v-else class="muted none">还没有屏蔽任何单个频道</div>
    </div>
  </div>
</template>

<style scoped>
.tip {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  margin-bottom: 14px;
  border-radius: 12px;
  font-size: 12.5px;
  line-height: 1.75;
  color: #9fb0bd;
  background: rgba(31, 162, 192, 0.08);
  border: 1px solid rgba(31, 162, 192, 0.22);
}
.summary {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.nm {
  font-weight: 600;
}
.nm.off {
  opacity: 0.55;
  text-decoration: line-through;
}
.card-panel {
  padding: 14px 16px;
  margin-bottom: 16px;
}
.sec-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 4px;
}
.sec-sub {
  font-size: 12px;
  color: #7f8f9b;
  margin-bottom: 10px;
}
.row {
  margin-bottom: 12px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px 4px 10px;
  border-radius: 999px;
  font-size: 12.5px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(13, 18, 22, 0.5);
}
.chip-x {
  border: none;
  background: transparent;
  color: #7f8f9b;
  cursor: pointer;
  font-size: 15px;
  line-height: 1;
  padding: 0 4px;
}
.chip-x:hover {
  color: #ff8f88;
}
.none {
  font-size: 12px;
}
</style>
