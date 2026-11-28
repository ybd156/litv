<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NInput, useMessage } from 'naive-ui'
import { authApi, setAdminToken } from '@/api'

/**
 * 首次初始化：这台实例还没有管理口令，让用户自己设一个。
 *
 * 以前只有两条路——去 config.yaml 写明文，或者用安装脚本随机生成的那串
 * （打在终端里、还会进 shell 历史）。真正的产品做法是「第一次打开就让我自己设」。
 *
 * 只在服务端明确回答 `required: true`（库里没哈希、配置也没明文）时出现，
 * 见 AppShell 的判定；设完直接拿到会话，不用再登一次。
 */

const message = useMessage()
const password = ref('')
const confirm = ref('')
const loading = ref(false)
const error = ref('')

const canSubmit = computed(() => password.value.length > 0 && confirm.value.length > 0)

async function submit(): Promise<void> {
  if (password.value.length < 8) {
    error.value = '密码至少 8 位'
    return
  }
  if (password.value !== confirm.value) {
    error.value = '两次输入的密码不一样'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const r = await authApi.setupAdmin(password.value)
    if (r.need_login || !r.token) {
      // 密码已经写进去了，只是会话没建成：别让用户以为失败又设一次（第二次会 409）。
      message.success('管理密码已设置，请用它登录')
      location.hash = '#/login'
      location.reload()
      return
    }
    setAdminToken(r.token)
    message.success('管理密码已设置，正在进入')
    location.hash = '#/'
    location.reload()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '设置失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="setup-page">
    <div class="card">
      <div class="brand">
        <div class="mark">LITV</div>
        <h1>先设一个管理密码</h1>
        <p class="sub">这是第一次启动，还没有任何口令</p>
      </div>

      <div class="form">
        <NInput
          v-model:value="password"
          size="large"
          type="password"
          placeholder="设置管理密码（至少 8 位）"
          show-password-on="click"
          :input-props="{ autocomplete: 'new-password' }"
        />
        <NInput
          v-model:value="confirm"
          size="large"
          type="password"
          placeholder="再输入一次"
          show-password-on="click"
          :input-props="{ autocomplete: 'new-password' }"
          @keyup.enter="submit"
        />

        <p v-if="error" class="err">{{ error }}</p>

        <NButton
          type="primary"
          size="large"
          block
          :loading="loading"
          :disabled="!canSubmit"
          @click="submit"
        >
          设置并进入
        </NButton>

        <p class="tip">
          这个口令用来登录管理台（公网部署时它也是唯一入口），请设完存进密码管理器。<br>
          电视绑定页填的 6 位绑定码是<b>另一回事</b>，不能登录这里。
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.setup-page {
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
.sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: #8b949e;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.err {
  margin: 0;
  color: #ff8f88;
  font-size: 13px;
}
.tip {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #6b7681;
}
.tip b {
  color: #9aa7b2;
}
</style>
