<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NTabPane, NTabs } from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import EpgSourcePanel from '@/components/EpgSourcePanel.vue'
import EpgMappingPanel from '@/components/EpgMappingPanel.vue'

type TabName = 'sources' | 'mapping'

const route = useRoute()
const router = useRouter()

const tab = ref<TabName>(route.query.tab === 'mapping' ? 'mapping' : 'sources')

/** 支持深链接：#/epg?tab=mapping */
const currentTab = computed<TabName>({
  get: () => tab.value,
  set: (v) => {
    tab.value = v
    void router.replace({ query: { ...route.query, tab: v } })
  },
})

watch(
  () => route.query.tab,
  (v) => {
    const next: TabName = v === 'mapping' ? 'mapping' : 'sources'
    if (next !== tab.value) tab.value = next
  },
)
</script>

<template>
  <div>
    <PageHeader
      title="EPG 管理"
      subtitle="EPG 就是电视上的节目单。源拉回来之后，还要把「你的频道」和「节目单里的频道」对上，电视上才会显示正在播什么。"
    />

    <NTabs v-model:value="currentTab" type="line" animated>
      <NTabPane name="sources" tab="EPG 源">
        <EpgSourcePanel />
      </NTabPane>
      <NTabPane name="mapping" tab="频道 ↔ EPG 映射">
        <EpgMappingPanel />
      </NTabPane>
    </NTabs>
  </div>
</template>
