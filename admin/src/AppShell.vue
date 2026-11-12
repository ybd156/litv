<script setup lang="ts">
import { NButton, NSpin } from 'naive-ui'
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import AdminLayout from '@/layouts/AdminLayout.vue'
import NotAuthorizedView from '@/views/NotAuthorizedView.vue'
import { loadSession, markUnauthenticated, session } from '@/session'
import { setUnauthorizedHandler } from '@/api/http'

const route = useRoute()

onMounted(() => {
  setUnauthorizedHandler(markUnauthenticated)
  void loadSession()
})
</script>

<template>
  <!-- 登录页是独立整页，不套侧边栏 -->
  <RouterView v-if="route.meta.bare" />

  <div v-else-if="session.state === 'loading'" class="splash">
    <div class="mark">LITV</div>
    <NSpin size="large" />
    <p class="tip">正在确认你的身份…</p>
  </div>

  <NotAuthorizedView v-else-if="session.state === 'unauthenticated'" />

  <div v-else-if="session.state === 'error'" class="splash">
    <div class="mark">LITV</div>
    <p class="tip strong">没连上 LITV 服务</p>
    <p class="tip">{{ session.error }}</p>
    <NButton type="primary" size="small" @click="loadSession">重试</NButton>
  </div>

  <AdminLayout v-else />
</template>

<style scoped>
.splash {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  background: radial-gradient(1200px 600px at 50% -10%, #16242c 0%, #0d1216 60%);
}
.mark {
  font-size: 30px;
  font-weight: 800;
  letter-spacing: 6px;
  color: #eaf6fa;
  text-shadow: 0 0 32px rgba(31, 162, 192, 0.55);
}
.tip {
  margin: 0;
  font-size: 13px;
  color: #7f8f9b;
  max-width: 420px;
  text-align: center;
  line-height: 1.7;
}
.tip.strong {
  font-size: 15px;
  color: #e6edf2;
  font-weight: 600;
}
</style>
