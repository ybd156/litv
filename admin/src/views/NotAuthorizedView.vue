<script setup lang="ts">
import { NButton } from 'naive-ui'
import { loadSession } from '@/session'
import { mockEnabled } from '@/api/mock'

function reload(): void {
  location.reload()
}
</script>

<template>
  <div class="unauth">
    <div class="card">
      <div class="mark">LITV</div>
      <h1>请从飞牛桌面打开本应用</h1>
      <p class="lead">
        管理台不设登录页——身份由飞牛网关自动注入。直接访问地址（或收藏夹里的链接）拿不到身份信息，
        所以这里需要你从飞牛桌面重新进来一次。
      </p>

      <ol class="steps">
        <li><span class="idx">1</span>打开飞牛 NAS 的桌面 / 应用中心</li>
        <li><span class="idx">2</span>点 LITV 图标进入（网关会自动带上当前账号）</li>
        <li><span class="idx">3</span>如果还是这一页，确认你的账号是管理员，然后刷新</li>
      </ol>

      <div class="actions">
        <NButton type="primary" @click="loadSession">我已经从桌面进来了，重试</NButton>
        <NButton quaternary @click="reload">刷新页面</NButton>
        <NButton quaternary tag="a" href="#/login">用密码登录（应急）</NButton>
      </div>

      <p v-if="mockEnabled()" class="mock-tip">
        当前是 Mock 模式：地址后加 <code>?mock=1</code> 可绕过身份校验看界面，
        <code>?mock=unauth</code> 就是你现在看到的这一页。
      </p>
    </div>
  </div>
</template>

<style scoped>
.unauth {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 20px;
  background: radial-gradient(1100px 560px at 50% -10%, #17262f 0%, #0d1216 62%);
}
.card {
  width: 100%;
  max-width: 620px;
  padding: 34px 36px 30px;
  border-radius: 18px;
  background: #141d23;
  border: 1px solid #22303a;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.45);
}
.mark {
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 5px;
  color: #2fb9d8;
}
h1 {
  margin: 16px 0 10px;
  font-size: 22px;
  font-weight: 650;
  color: #eef5f8;
}
.lead {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.8;
  color: #8b9aa5;
}
.steps {
  margin: 22px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.steps li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13.5px;
  color: #b8c6d0;
}
.idx {
  width: 22px;
  height: 22px;
  flex: none;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: #5fd3ea;
  background: rgba(31, 162, 192, 0.16);
}
.actions {
  display: flex;
  gap: 10px;
  margin-top: 26px;
  flex-wrap: wrap;
}
.mock-tip {
  margin: 22px 0 0;
  padding-top: 16px;
  border-top: 1px dashed #24313a;
  font-size: 12.5px;
  line-height: 1.7;
  color: #6f7f8a;
}
code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #1b262e;
  color: #5fd3ea;
  font-size: 12px;
}
</style>
