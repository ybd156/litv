import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { loadDeployMode } from './deploy'
import './styles/global.css'

// 先问服务端「你是谁」（网关 / 直连 / 边缘），再挂载。
// 必须在 mount 之前：登录页和「未授权」页的文案按形态分支，
// 挂载后才知道形态会先闪一版错的话术。
void loadDeployMode().finally(() => {
  createApp(App).use(router).mount('#app')
})
