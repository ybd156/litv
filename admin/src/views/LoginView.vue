<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NInput, useMessage } from 'naive-ui'
import { request, setAdminToken } from '@/api'
import { EDGE_DEPLOY } from '@/deploy'

/**
 * 登录页。
 *
 * 两种部署形态共用这一页，但入口完全不同：
 *
 *   - NAS：正常路径是「从飞牛桌面点图标」，身份由网关自动注入，根本不用登录。
 *     这一页只是**应急入口**：网关没配好、或者用 IP 直连时，用密码进来。
 *     「使用 NAS 登录」= 回飞牛桌面走网关，那才是推荐方式。
 *   - edge（Cloudflare Worker）：没有网关，口令就是**唯一**入口，
 *     所以不能提示「回飞牛桌面」，也没有「用户名」这个概念（Worker 只认口令）。
 */

const message = useMessage()
const username = ref(localStorage.getItem('litv_admin_user') ?? 'admin')
const password = ref('')
const remember = ref(true)
const showPwd = ref(false)
const loading = ref(false)
const error = ref('')

/** 页面顶部显示的主机名（NAS 名 / worker 域名） */
const nasName = computed(() => (window.location.hostname || 'NAS').toUpperCase())

async function submit(): Promise<void> {
  if (!password.value) {
    error.value = EDGE_DEPLOY ? '请输入管理口令' : '请输入密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const r = await request<{ token: string }>('auth/login', {
      method: 'POST',
      // 服务端应急登录只接受 password；多传 username 会被严格 JSON 解码拒绝。
      body: { password: password.value },
    })
    setAdminToken(r.token)
    if (!EDGE_DEPLOY && remember.value) localStorage.setItem('litv_admin_user', username.value)
    else localStorage.removeItem('litv_admin_user')
    message.success('登录成功')
    location.reload()
  } catch (e) {
    // request() 已经把服务端 msg 放在 message 里，直接显示它，
    // 不要再套一层「未认证」之类的模板（那会盖掉真正的原因）。
    error.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    loading.value = false
  }
}

/** 回飞牛桌面走网关（推荐方式，不用密码）。edge 部署没有这个概念。 */
function useNasLogin(): void {
  // 网关入口就是当前站点的根，从桌面点图标进来即可
  location.href = '/'
}

function showPasswordHint(): void {
  message.info(
    EDGE_DEPLOY
      ? '口令来自部署时的 ADMIN_PASSWORD（GitHub Secret 或 wrangler secret）；没配置时等同 6 位绑定码'
      : '密码在服务端 config.yaml 的 auth.admin_password',
  )
}
</script>

<template>
  <div class="login-page">
    <div class="card">
      <div class="brand">
        <div class="mark">LITV</div>
        <h1>LITV 直播管理</h1>
        <p class="nas">{{ nasName }}</p>
      </div>

      <div class="form">
        <!-- edge 部署没有「账号」概念，Worker 只比对口令；留个输入框会误导 -->
        <NInput
          v-if="!EDGE_DEPLOY"
          v-model:value="username"
          size="large"
          placeholder="用户名"
          :input-props="{ autocomplete: 'username' }"
        />

        <NInput
          v-model:value="password"
          size="large"
          :type="showPwd ? 'text' : 'password'"
          :placeholder="EDGE_DEPLOY ? '管理口令' : '密码'"
          show-password-on="click"
          :input-props="{ autocomplete: 'current-password' }"
          @keyup.enter="submit"
        />

        <div class="row">
          <label v-if="!EDGE_DEPLOY" class="remember">
            <input v-model="remember" type="checkbox" />
            <span>记住账号</span>
          </label>
          <span v-else />
          <a class="forget" href="javascript:void 0" @click="showPasswordHint">忘记密码？</a>
        </div>

        <p v-if="error" class="err">{{ error }}</p>

        <NButton type="primary" size="large" block :loading="loading" @click="submit">
          登录
        </NButton>

        <template v-if="!EDGE_DEPLOY">
          <NButton size="large" block quaternary class="nas-btn" @click="useNasLogin">
            使用 NAS 登录
          </NButton>

          <p class="tip">
            「使用 NAS 登录」会回到飞牛桌面，由网关自动带上你的账号——推荐用这种方式，
            不需要密码。下面的密码登录只在网关不可用时使用。
          </p>
        </template>

        <p v-else class="tip">
          这是 Cloudflare 边缘部署，没有飞牛网关，只能用管理口令登录。
          建议把口令与 6 位绑定码分开（部署时配置 <code>ADMIN_PASSWORD</code>）。
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(1200px 600px at 50% -10%, #1b2a4a 0%, #0b0e11 60%);
  padding: 24px;
}
.card {
  width: 100%;
  max-width: 420px;
  background: rgba(22, 27, 34, 0.72);
  border: 1px solid #ffffff14;
  border-radius: 18px;
  padding: 36px 32px 28px;
  backdrop-filter: blur(14px);
}
.brand {
  text-align: center;
  margin-bottom: 26px;
}
.mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 16px;
  background: linear-gradient(160deg, #1e3a8a, #0f1e3d);
  color: #fff;
  font-weight: 800;
  font-size: 18px;
  letter-spacing: 1px;
  margin-bottom: 12px;
}
h1 {
  margin: 0;
  font-size: 20px;
  color: #e6edf3;
  font-weight: 600;
}
.nas {
  margin: 6px 0 0;
  font-size: 13px;
  letter-spacing: 2px;
  color: #8b949e;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
}
.remember {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #8b949e;
  cursor: pointer;
}
.forget {
  color: #8b949e;
  text-decoration: none;
}
.forget:hover {
  color: #2dd4bf;
}
.err {
  margin: 0;
  color: #ff8f88;
  font-size: 13px;
}
.nas-btn {
  margin-top: 4px;
}
.tip {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #6b7681;
}
.tip code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #1b262e;
  color: #5fd3ea;
  font-size: 11.5px;
}
</style>
