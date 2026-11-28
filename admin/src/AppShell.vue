<script setup lang="ts">
import { NButton, NSpin } from 'naive-ui'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AdminLayout from '@/layouts/AdminLayout.vue'
import LoginView from '@/views/LoginView.vue'
import NotAuthorizedView from '@/views/NotAuthorizedView.vue'
import SetupView from '@/views/SetupView.vue'
import { authApi } from '@/api'
import { loadSession, markUnauthenticated, session } from '@/session'
import { setUnauthorizedHandler } from '@/api/http'
import { usesPassword } from '@/deploy'

const route = useRoute()

/**
 * 公网 / 自建服务端（以及 edge）：没登录就直接给登录表单。
 *
 * 以前这里统一显示 NotAuthorizedView，一个未登录的人要读三段解释、再点「去登录」
 * 才看到输入框——公网部署上这是纯噪音。只有飞牛网关那套（身份由网关注入、
 * 密码只是应急）才需要那段说明。
 */
const needLogin = computed(() => session.state === 'unauthenticated' && usesPassword())

/**
 * 还没设过管理口令时，直接把「设置密码」表单推上来。
 *
 * 判定必须问服务端（库里有没有哈希、配置里有没有明文），不能靠前端猜：
 * 猜错的后果是一个未初始化实例把「设密码」入口藏起来，用户只能去翻 YAML。
 * 探测失败按 false 处理——服务不可用时不该把设密码的入口摆给公网。
 */
const setupRequired = ref(false)
const needSetup = computed(
  () => session.state === 'unauthenticated' && setupRequired.value && usesPassword(),
)

onMounted(async () => {
  setUnauthorizedHandler(markUnauthenticated)
  try {
    const state = await authApi.fetchSetupState()
    setupRequired.value = state.required
  } catch {
    setupRequired.value = false
  }
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

  <SetupView v-else-if="needSetup" />

  <LoginView v-else-if="needLogin" />

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
