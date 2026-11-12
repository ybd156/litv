<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  NButton,
  NForm,
  NFormItem,
  NInputNumber,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
} from 'naive-ui'
import PageHeader from '@/components/PageHeader.vue'
import StateBlock from '@/components/StateBlock.vue'
import LogPanel from '@/components/LogPanel.vue'
import Icon from '@/components/Icon.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import * as systemApi from '@/api/modules/system'
import type { SystemConfig, SystemInfo } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { toast } from '@/utils/feedback'
import { fmtDuration } from '@/utils/format'

interface SettingsData {
  config: SystemConfig | null
  info: SystemInfo | null
}

const { data, loading, error, run } = useAsyncData<SettingsData>(async () => {
  const [c, i] = await Promise.allSettled([systemApi.fetchSystemConfig(), systemApi.fetchSystemInfo()])
  // config 是**必需**请求：拿不到就不能让人编辑。
  //
  // 以前这里是「两个都失败才报错」。于是 config 500 / info 200 时 config 为
  // null，hydrate() 直接 return，snapshot 停在空串、dirty 恒为 true（表单是本地
  // 默认值），保存按钮可用——一点保存就把服务端真实的端口/并发/转码/日志
  // 全用默认值覆盖掉，重启后连接入口都变了。
  if (c.status === 'rejected') throw c.reason
  return {
    config: c.value,
    // info 只用于展示，独立降级：拿不到就显示「—」，不影响编辑配置
    info: i.status === 'fulfilled' ? i.value : null,
  }
})

const form = reactive<SystemConfig>({
  // 与服务端 config.Default() 和安装向导 wizard/install 对齐（28027）。
  // 这里曾经写 9527，新装用户看到的默认端口和实际监听端口对不上。
  port: 28027,
  max_concurrent: 6,
  transcode_enabled: false,
  log_level: 'info',
  epg_refresh_hour: 4,
  allow_lan: true,
})

const snapshot = ref<string>('')
const saving = ref(false)

const logLevelOptions = [
  { label: 'debug（调试，最啰嗦）', value: 'debug' },
  { label: 'info（常规）', value: 'info' },
  { label: 'warn（只记警告）', value: 'warn' },
  { label: 'error（只记错误）', value: 'error' },
]

const info = computed(() => data.value?.info ?? null)
const dirty = computed(() => JSON.stringify(form) !== snapshot.value)

function hydrate(): void {
  const c = data.value?.config
  if (!c) return
  form.port = c.port ?? 28027
  form.max_concurrent = c.max_concurrent ?? 6
  form.transcode_enabled = Boolean(c.transcode_enabled)
  form.log_level = c.log_level ?? 'info'
  form.epg_refresh_hour = typeof c.epg_refresh_hour === 'number' ? c.epg_refresh_hour : 4
  form.allow_lan = c.allow_lan ?? true
  snapshot.value = JSON.stringify(form)
}

async function save(): Promise<void> {
  if (form.port < 1 || form.port > 65535) {
    toast.warning('端口要在 1~65535 之间')
    return
  }
  saving.value = true
  try {
    // 服务端会回传 restart_needed，明确告诉我们哪些项改了但还没生效。
    // 不再笼统地说"已保存"——用户需要知道到底要不要重启。
    const res = await systemApi.saveSystemConfig({
      port: form.port,
      max_concurrent: form.max_concurrent,
      transcode_enabled: form.transcode_enabled,
      log_level: form.log_level,
      epg_refresh_hour: form.epg_refresh_hour,
      allow_lan: form.allow_lan,
    })
    snapshot.value = JSON.stringify(form)

    const need = res?.restart_needed ?? []
    if (need.length > 0) {
      toast.warning(`已保存；${need.map(restartFieldName).join('、')} 需要重启服务后才生效`)
    } else {
      toast.success('已保存并生效')
    }
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    saving.value = false
  }
}

/** 把后端字段名翻成人话，别在界面上直接暴露 port / allow_lan。 */
function restartFieldName(field: string): string {
  switch (field) {
    case 'port':
      return '服务端口'
    case 'allow_lan':
      return '局域网访问'
    case 'epg_refresh_hour':
      return 'EPG 刷新时间'
    default:
      return field
  }
}

async function load(): Promise<void> {
  await run()
  hydrate()
}

onMounted(() => void load())
</script>

<template>
  <div>
    <PageHeader title="系统设置" subtitle="服务端的基本参数、版本信息和日志。改完记得保存，部分项需要重启服务。">
      <template #actions>
        <NButton size="small" :loading="loading" @click="load">
          <template #icon><Icon name="refresh" :size="15" /></template>
          重新读取
        </NButton>
        <NButton size="small" type="primary" :loading="saving" :disabled="!dirty || !data || !data.config" @click="save">
          保存配置
        </NButton>
      </template>
    </PageHeader>

    <div class="cols">
      <section class="card-panel panel">
        <div class="panel-head">
          <h2>运行配置</h2>
          <NTag v-if="dirty" size="small" type="warning" :bordered="false">有改动未保存</NTag>
        </div>

        <StateBlock :loading="loading && !data" :error="error" :min-height="300" @retry="load">
          <NForm label-placement="left" label-width="110" @submit.prevent>
            <NFormItem label="服务端口">
              <div class="field">
                <NInputNumber v-model:value="form.port" :min="1" :max="65535" style="width: 160px" />
                <span class="tip">终端 App 通过这个端口连（默认 28027）。改完要重启服务。</span>
              </div>
            </NFormItem>

            <NFormItem label="并发上限">
              <div class="field">
                <NInputNumber v-model:value="form.max_concurrent" :min="1" :max="64" style="width: 160px" />
                <span class="tip">同时能有多少路直播流。N100 这种小机器建议 4~8。</span>
              </div>
            </NFormItem>

            <NFormItem label="转码">
              <div class="field">
                <NSpace align="center" :size="10">
                  <NSwitch v-model:value="form.transcode_enabled" />
                  <span class="tip">
                    {{ form.transcode_enabled ? '开着：终端解不了的源会走 ffmpeg 转码（吃 CPU）' : '关着：只做透传，最省资源（推荐）' }}
                  </span>
                </NSpace>
              </div>
            </NFormItem>

            <NFormItem label="日志级别">
              <div class="field">
                <NSelect v-model:value="form.log_level" :options="logLevelOptions" style="width: 240px" />
              </div>
            </NFormItem>

            <NFormItem label="EPG 刷新时间">
              <div class="field">
                <NInputNumber v-model:value="form.epg_refresh_hour" :min="0" :max="23" style="width: 160px" />
                <span class="tip">每天几点自动更新节目单（0~23 点）。</span>
              </div>
            </NFormItem>

            <NFormItem label="局域网访问">
              <div class="field">
                <NSpace align="center" :size="10">
                  <NSwitch v-model:value="form.allow_lan" />
                  <span class="tip">允许同一局域网内的终端直接访问（关掉就只能走网关）。</span>
                </NSpace>
              </div>
            </NFormItem>
          </NForm>
        </StateBlock>
      </section>

      <section class="card-panel panel">
        <div class="panel-head">
          <h2>版本信息</h2>
        </div>
        <dl class="kv">
          <div><dt>版本</dt><dd>{{ info?.version ?? '—' }}</dd></div>
          <div><dt>架构</dt><dd class="mono">{{ info?.arch ?? '—' }}</dd></div>
          <div><dt>已运行</dt><dd>{{ fmtDuration(info?.uptime_seconds) }}</dd></div>
          <div><dt>当前并发流</dt><dd>{{ info?.active_streams ?? '—' }} 路</dd></div>
        </dl>
        <p class="hint-text" style="margin-top: 12px">
          部署在飞牛 NAS 上，程序目录由 <code class="mono">$TRIM_APPDEST</code> 决定，配置文件在
          <code class="mono">$TRIM_PKGETC</code>，数据在 <code class="mono">$TRIM_PKGVAR</code>。
        </p>
      </section>
    </div>

    <section class="card-panel panel logs">
      <div class="panel-head">
        <h2>运行日志</h2>
      </div>
      <LogPanel />
    </section>
  </div>
</template>

<style scoped>
.cols {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}
@media (max-width: 1080px) {
  .cols {
    grid-template-columns: 1fr;
  }
}
.panel {
  padding: 16px 18px;
}
.panel-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.panel-head h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #dfeaf0;
}
.field {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.tip {
  font-size: 12px;
  color: #74838e;
  line-height: 1.5;
  max-width: 420px;
}
.kv {
  margin: 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 10px;
}
.kv div {
  padding: 10px 12px;
  border-radius: 10px;
  background: #121a20;
  border: 1px solid #1c262e;
}
.kv dt {
  font-size: 12px;
  color: #74838e;
}
.kv dd {
  margin: 6px 0 0;
  font-size: 14px;
  font-weight: 600;
  color: #e2ecf1;
}
.logs {
  margin-top: 14px;
}
</style>
