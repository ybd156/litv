<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NInput, useMessage } from 'naive-ui'
import { request, setAdminToken } from '@/api'

/**
 * 登录页。
 *
 * 正常路径是「从飞牛桌面点图标」——身份由网关自动注入，根本不用登录。
 * 这个页面是**应急入口**：网关没配好、或者用 IP 直连时，用密码进来。
 *
 * 「使用 NAS 登录」= 回飞牛桌面走网关，那才是推荐方式。
 */

const message = useMessage()
const username = ref(localStorage.getItem('litv_admin_user') ?? 'admin')
const password = ref('')
const remember = ref(true)
const showPwd = ref(false)
const loading = ref(false)
const error = ref('')

/** 页面顶部显示的 NAS 名（就是当前访问的主机名） */
const nasName = computed(() => (window.location.hostname || 'NAS').toUpperCase())

async function submit(): Promise<void> {
  if (!password.value) {
    error.value = '请输入密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const r = await request<{ token: string }>('auth/login', {
      method: 'POST',
      body: { username: username.value, password: password.value },
    })
    setAdminToken(r.token)
    if (remember.value) localStorage.setItem('litv_admin_user', username.value)
    else localStorage.removeItem('litv_admin_user')
    message.success('登录成功')
    location.reload()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    loading.value = false
  }
}

/** 回飞牛桌面走网关（推荐方式，不用密码） */
function useNasLogin(): void {
  // 网关入口就是当前站点的根，从桌面点图标进来即可
  location.href = '/'
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
        <NInput
          v-model:value="username"
          size="large"
          placeholder="用户名"
          :input-props="{ autocomplete: 'username' }"
        />

        <NInput
          v-model:value="password"
          size="large"
          :type="showPwd ? 'text' : 'password'"
          placeholder="密码"
          show-password-on="click"
          :input-props="{ autocomplete: 'current-password' }"
          @keyup.enter="submit"
        />

        <div class="row">
          <label class="remember">
            <input v-model="remember" type="checkbox" />
            <span>记住账号</span>
          </label>
          <a class="forget" href="javascript:void 0" @click="message.info('密码在服务端 config.yaml 的 auth.admin_password')">
            忘记密码？
          </a>
        </div>

        <p v-if="error" class="err">{{ error }}</p>

        <NButton type="primary" size="large" block :loading="loading" @click="submit">
          登录
        </NButton>

        <NButton size="large" block quaternary class="nas-btn" @click="useNasLogin">
          使用 NAS 登录
        </NButton>

        <p class="tip">
          「使用 NAS 登录」会回到飞牛桌面，由网关自动带上你的账号——推荐用这种方式，
          不需要密码。下面的密码登录只在网关不可用时使用。
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
</style>
