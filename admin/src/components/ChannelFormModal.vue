<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  NButton,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  NSpace,
  NSwitch,
} from 'naive-ui'
import * as channelsApi from '@/api/modules/channels'
import type { Channel, ChannelInput } from '@/api/types'
import { toErrorMessage } from '@/api/errors'
import { toast } from '@/utils/feedback'

const props = defineProps<{
  show: boolean
  channel?: Channel | null
  groups: string[]
  defaultGroup?: string
}>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'saved'): void
}>()

const saving = ref(false)
const errors = reactive<{ name: string }>({ name: '' })

/** 频道本身的可编辑字段（不含 epg_id，见下面 epgId 的注释） */
const form = reactive<ChannelInput>({
  name: '',
  group: '',
  number: undefined,
  logo: '',
  tvg_id: '',
  enabled: true,
})

/**
 * EPG 映射是**独立的写操作**，不跟着频道请求体走。
 *
 * 原因：`POST /channels` 与 `PUT /channels/{id}` 的请求体里都没有 `epg_id` 字段，
 * 而服务端 `decode()` 开了 `DisallowUnknownFields` —— 把它塞进 payload 就是
 * 400 `参数错误: json: unknown field "epg_id"`，整个「保存」直接失败。
 * 映射只能走 `PUT /channels/{id}/epg`（`channelsApi.setChannelEpg`）。
 */
const epgId = ref('')

const isEdit = computed(() => Boolean(props.channel?.id))

const groupOptions = computed(() =>
  props.groups.map((g) => ({ label: g, value: g })),
)

watch(
  () => props.show,
  (open) => {
    if (!open) return
    errors.name = ''
    const c = props.channel
    form.name = c?.name ?? ''
    form.group = c?.group ?? props.defaultGroup ?? ''
    form.number = c?.number
    form.logo = c?.logo ?? ''
    form.tvg_id = c?.tvg_id ?? ''
    form.enabled = c?.enabled ?? true
    epgId.value = c?.epg_id ?? ''
  },
)

async function submit(): Promise<void> {
  errors.name = form.name.trim() ? '' : '频道名不能为空'
  if (errors.name) return
  saving.value = true
  try {
    const payload: ChannelInput = {
      name: form.name.trim(),
      group: (form.group || '').trim() || '未分组',
      number: typeof form.number === 'number' ? form.number : undefined,
      logo: (form.logo ?? '').trim(),
      tvg_id: (form.tvg_id ?? '').trim(),
      enabled: form.enabled,
    }
    const wantedEpg = (epgId.value || '').trim()
    /**
     * 半步失败要能看见。
     *
     * 「保存频道」其实是两次 HTTP：先写频道字段，再单独写 EPG 映射（不是事务，
     * 服务端也没有跨这两个请求的事务能力）。如果第二次失败，频道字段**已经生效**，
     * 这时绝不能报「保存失败」——用户会以为没存上、反复重试，甚至重复新建。
     * 所以单独记一条提示，最后用 warning 说清「哪一步成了、哪一步没成」。
     */
    let epgWarning = ''
    if (props.channel?.id) {
      const id = props.channel.id
      // 先保存频道字段（部分更新，不会碰 epg_id）
      await channelsApi.patchChannel(id, payload)
      const before = (props.channel.epg_id ?? '').trim()
      /**
       * 什么时候要补打一次 EPG 接口：不只是「用户改了映射」，还包括「本来就有映射」。
       *
       * 原因：老服务端的 `PUT /channels/{id}` 是**全量覆盖**且 SET 里带 `epg_id=''`，
       * 也就是说只要保存一次频道字段，映射就会被清掉。如果这里只在「有变化」时补写，
       * 连上没升级的 NAS 时改个名字就会静默丢掉节目单。所以：只要这台频道**原本**
       * 有映射，就无条件重写一遍（等值写入，幂等、无害）。服务端修好之后这一次
       * 就是纯粹的冗余请求，可以按 A1 落地情况再收紧。
       */
      if (wantedEpg !== before || before !== '') {
        try {
          await channelsApi.setChannelEpg(id, wantedEpg || null)
        } catch (e) {
          epgWarning = `频道信息已保存，但 EPG 关联没写进去：${toErrorMessage(e)}。可到「EPG 管理 → 频道 ↔ EPG 映射」页重试。`
        }
      }
    } else {
      const created = await channelsApi.createChannel(payload)
      // 新建时服务端不接 epg_id，只能建完之后单独映射一次。
      // 注意：这里**不能**失败后留在弹窗里让用户再点一次「添加」——那会建出重复频道。
      if (wantedEpg && created?.id) {
        try {
          await channelsApi.setChannelEpg(created.id, wantedEpg)
        } catch (e) {
          epgWarning = `频道已建好，但 EPG 未关联：${toErrorMessage(e)}。可到「EPG 管理 → 频道 ↔ EPG 映射」页补上。`
        }
      }
    }
    if (epgWarning) {
      toast.warning(epgWarning)
    } else {
      toast.success(props.channel?.id ? '已保存' : '频道已添加')
    }
    emit('update:show', false)
    emit('saved')
  } catch (e) {
    toast.error(toErrorMessage(e))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="isEdit ? '编辑频道' : '新增频道'"
    style="width: 600px; max-width: 94vw"
    :bordered="false"
    :mask-closable="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <NForm label-placement="top" @submit.prevent>
      <NFormItem
        label="频道名"
        :feedback="errors.name"
        :validation-status="errors.name ? 'error' : undefined"
      >
        <NInput v-model:value="form.name" placeholder="例如：CCTV-1 综合" maxlength="60" />
      </NFormItem>

      <div class="two">
        <NFormItem label="分组">
          <NSelect
            v-model:value="form.group"
            :options="groupOptions"
            filterable
            tag
            clearable
            placeholder="选一个已有分组，或直接输入新的"
          />
        </NFormItem>
        <NFormItem label="频道号">
          <NInputNumber v-model:value="form.number" :min="1" :max="9999" placeholder="留空则自动排" style="width: 100%" />
        </NFormItem>
      </div>

      <NFormItem label="台标地址">
        <NInput v-model:value="form.logo" placeholder="https://…/cctv1.png（可留空）" />
      </NFormItem>

      <div class="two">
        <NFormItem label="tvg-id（源里的标识）">
          <NInput v-model:value="form.tvg_id" placeholder="例如 cctv1" />
        </NFormItem>
        <NFormItem label="EPG 频道 ID（用于节目单）">
          <NInput v-model:value="epgId" placeholder="留空表示还没匹配" />
        </NFormItem>
      </div>

      <NFormItem label="启用">
        <NSpace align="center" :size="10">
          <NSwitch v-model:value="form.enabled" />
          <span class="tip">停用后电视上不会显示这个频道</span>
        </NSpace>
      </NFormItem>
    </NForm>

    <template #footer>
      <NSpace justify="end">
        <NButton quaternary @click="emit('update:show', false)">取消</NButton>
        <NButton type="primary" :loading="saving" @click="submit">
          {{ isEdit ? '保存' : '添加' }}
        </NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
.two {
  display: grid;
  grid-template-columns: 1fr 160px;
  gap: 12px;
}
@media (max-width: 560px) {
  .two {
    grid-template-columns: 1fr;
  }
}
.tip {
  font-size: 12px;
  color: #74838e;
}
</style>
