import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { EDGE_DEPLOY } from '@/deploy'

/**
 * ★ 必须用 hash 模式：飞牛网关把 /app/litv/* 全量转发给后端 SPA 兜底路由，
 * history 模式下深链接刷新容易 404，hash 最稳。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '应急登录', bare: true },
  },
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
    path: '/blocked',
    name: 'blocked',
    component: () => import('@/views/BlockedView.vue'),
    // 反向的例外：这项**只有边缘部署**才有（NAS 服务端没有这个接口）。
    meta: { title: '屏蔽', edgeOnly: true },
  },
  {
    path: '/live',
    name: 'live',
    component: () => import('@/views/LiveView.vue'),
    // 边缘部署没有 `/web/play/{id}`（浏览器播放要走 NAS 的中转），
    // 这个页面在边缘版只会报错，干脆从导航和路由里都摘掉。
    meta: { title: '直播预览', edgeUnsupported: true },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/SettingsView.vue'),
    // 边缘版的配置在 wrangler.toml 和 Secrets 里，`system/config` 一律 2006。
    meta: { title: '系统设置', edgeUnsupported: true },
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

/**
 * 边缘部署里"本来就不存在"的页面直接踢回概览。
 *
 * 不做成"点了弹一个不支持"是因为这些页面一进去就会发请求：
 * 直播预览要 `/web/play/{id}`（边缘没有中转），系统设置要 `system/config`
 * （边缘返回 2006）。留着入口只会让人以为是自己配错了。
 */
router.beforeEach((to) => {
  const meta = to.meta as { edgeUnsupported?: boolean; edgeOnly?: boolean }
  if (EDGE_DEPLOY && meta.edgeUnsupported === true) return { path: '/' }
  // edgeOnly 是反过来的：NAS 上没有这个接口，别让人点进去吃一个 404
  if (!EDGE_DEPLOY && meta.edgeOnly === true) return { path: '/' }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} · LITV 管理台` : 'LITV 管理台'
})

export default router
