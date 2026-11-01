import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

/**
 * ★ 必须用 hash 模式：飞牛网关把 /app/litv/* 全量转发给后端 SPA 兜底路由，
 * history 模式下深链接刷新容易 404，hash 最稳。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '概览' },
  },
  {
    path: '/sources',
    name: 'sources',
    component: () => import('@/views/SourcesView.vue'),
    meta: { title: '源管理' },
  },
  {
    path: '/channels',
    name: 'channels',
    component: () => import('@/views/ChannelsView.vue'),
    meta: { title: '频道管理' },
  },
  {
    path: '/epg',
    name: 'epg',
    component: () => import('@/views/EpgView.vue'),
    meta: { title: 'EPG 管理' },
  },
  {
    path: '/devices',
    name: 'devices',
    component: () => import('@/views/DevicesView.vue'),
    meta: { title: '终端设备' },
  },
  {
    path: '/live',
    name: 'live',
    component: () => import('@/views/LiveView.vue'),
    meta: { title: '直播预览' },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/SettingsView.vue'),
    meta: { title: '系统设置' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} · LITV 管理台` : 'LITV 管理台'
})

export default router
