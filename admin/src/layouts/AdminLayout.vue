<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { NButton, NIcon, NMenu, NTag, type MenuOption } from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'
import type { IconName } from '@/components/icons'
import { mockEnabled } from '@/api/mock'
import { session } from '@/session'

const route = useRoute()
const router = useRouter()

const collapsed = ref(false)
const isMock = mockEnabled()

interface NavItem {
  key: string
  label: string
  icon: IconName
}

const navItems: NavItem[] = [
  { key: 'dashboard', label: '概览', icon: 'dashboard' },
  { key: 'sources', label: '源管理', icon: 'sources' },
  { key: 'channels', label: '频道管理', icon: 'channels' },
  { key: 'epg', label: 'EPG 管理', icon: 'epg' },
  { key: 'devices', label: '终端设备', icon: 'devices' },
  { key: 'settings', label: '系统设置', icon: 'settings' },
]

function renderIcon(name: IconName) {
  return () => h(NIcon, { size: 18 }, { default: () => h(Icon, { name, size: 18 }) })
}

const menuOptions = computed<MenuOption[]>(() =>
  navItems.map((i) => ({ key: i.key, label: i.label, icon: renderIcon(i.icon) })),
)

const activeKey = computed(() => (typeof route.name === 'string' ? route.name : 'dashboard'))
const currentTitle = computed(() => (route.meta.title as string | undefined) ?? '')

function onSelect(key: string): void {
  void router.push({ name: key })
}

onMounted(() => {
  if (window.innerWidth < 1080) collapsed.value = true
})
</script>

<template>
  <div class="shell">
    <aside class="sider" :class="{ collapsed }">
      <div class="brand">
        <span class="logo">LITV</span>
        <span v-if="!collapsed" class="sub">管理台</span>
      </div>

      <NMenu
        :value="activeKey"
        :options="menuOptions"
        :collapsed="collapsed"
        :collapsed-width="64"
        :collapsed-icon-size="20"
        :indent="18"
        @update:value="onSelect"
      />

      <div class="sider-foot">
        <NButton quaternary size="tiny" block @click="collapsed = !collapsed">
          {{ collapsed ? '»' : '« 收起' }}
        </NButton>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <span class="dot" />
          <span>{{ currentTitle }}</span>
        </div>
        <div class="right">
          <NTag v-if="isMock" size="small" :bordered="false" type="warning">Mock 数据</NTag>
          <span class="user">
            <Icon name="user" :size="15" />
            <span class="name">{{ session.me?.username ?? '未登录' }}</span>
            <NTag v-if="session.me?.is_admin" size="tiny" :bordered="false" type="success">管理员</NTag>
          </span>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--litv-bg);
}
.sider {
  width: 208px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: #101820;
  border-right: 1px solid #1c262e;
  transition: width 0.18s ease;
  padding: 0 8px;
}
.sider.collapsed {
  width: 72px;
}
.brand {
  height: 58px;
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 0 10px;
  flex: none;
}
.logo {
  font-size: 17px;
  font-weight: 800;
  letter-spacing: 3px;
  color: #eaf6fa;
}
.sub {
  font-size: 12px;
  color: #5f7280;
  letter-spacing: 1px;
}
.sider-foot {
  margin-top: auto;
  padding: 10px 4px 14px;
}
.main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.topbar {
  height: 58px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px;
  border-bottom: 1px solid #1c262e;
  background: rgba(16, 24, 32, 0.72);
  backdrop-filter: blur(6px);
}
.crumb {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 14px;
  font-weight: 600;
  color: #d5e2ea;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--litv-brand);
  box-shadow: 0 0 10px rgba(31, 162, 192, 0.9);
}
.right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.user {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  color: #a9b8c3;
}
.user .name {
  font-weight: 500;
}
.content {
  flex: 1 1 auto;
  overflow: auto;
  padding: 20px 22px 40px;
}
@media (max-width: 760px) {
  .content {
    padding: 16px 12px 32px;
  }
}
</style>
