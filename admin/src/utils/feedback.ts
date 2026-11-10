import { h, type VNode } from 'vue'
import type { MessageApi, DialogApi } from 'naive-ui'

/**
 * 让 api 层 / 任意 ts 文件也能弹提示。
 * NaiveBridge.vue 在 provider 内部把 message / dialog 实例注册进来。
 */
let message: MessageApi | null = null
let dialog: DialogApi | null = null

export function bindFeedback(m: MessageApi, d: DialogApi): void {
  message = m
  dialog = d
}

export const toast = {
  success(msg: string): void {
    message?.success(msg)
  },
  error(msg: string): void {
    message?.error(msg)
  },
  warning(msg: string): void {
    message?.warning(msg)
  },
  info(msg: string): void {
    message?.info(msg)
  },
}

export interface ConfirmOptions {
  title: string
  /**
   * 只接受字符串或**返回 VNode 的函数**。
   *
   * naive-ui 的 dialog content 类型是 `string | (() => VNodeChild)`，
   * 直接传一个 VNode 实例是不接受的（会 TS2322）。这里跟着收窄，
   * 而不是在调用点写 `as any` 把类型问题藏起来。
   */
  content: string | (() => VNode)
  positiveText?: string
  negativeText?: string
  danger?: boolean
  onConfirm: () => void | Promise<void>
}

/**
 * 破坏性操作统一走这里：二次确认 + 执行时按钮 loading。
 *
 * @deprecated 危险操作请用 `confirmDanger()`——它强制列出「影响数量 / 级联影响 /
 * 是否可撤销」，避免每个调用点自己写一段深浅不一的文案。
 * 这个函数只留给「有明确自定义说明且不是破坏性操作」的场景。
 */
export function confirmAction(opts: ConfirmOptions): void {
  if (!dialog) return
  const handler = opts.danger === false ? dialog.warning : dialog.error
  handler({
    title: opts.title,
    content: opts.content,
    positiveText: opts.positiveText ?? '确认',
    negativeText: opts.negativeText ?? '取消',
    maskClosable: false,
    onPositiveClick: async () => {
      await opts.onConfirm()
    },
  })
}

/**
 * 危险操作的统一确认框。
 *
 * 为什么要有它：之前删源、删频道、批量删、停用频道、删设备、删 EPG 源
 * 各写一段 `content` 字符串，深浅完全不同——有的只说「确定删除？」（等于没说），
 * 有的把级联影响写全。用户没法从文案里判断这件事到底动了什么。
 *
 * 这个函数把内容**固定成四段**，缺哪段就不显示那段（而不是编一段）：
 *   影响   —— 动多少东西（必须是真数，不是估算）
 *   级联   —— 还会连带失效什么（收藏 / 续播 / 提醒 / 录制）
 *   注意   —— 容易踩的坑（例如「其它源的同名频道也可能被删」）
 *   撤销   —— 能不能恢复，不能就标红
 */
export interface DangerConfirmOptions {
  /** 动作名，动词开头，带上对象名字：「删除源「央视频道 4K 源」」 */
  action: string
  /** 影响对象数量（**真数**；不知道就别传，不要写 0 冒充「没有」） */
  count?: number
  /** 数量的单位：「个频道」「条线路」「台设备」 */
  unit?: string
  /** 级联失效的东西，例：`['收藏', '续播进度']` → 「收藏 / 续播进度会失效」 */
  cascade?: string[]
  /** 额外提醒一句（例如同名前缀、跨源共用） */
  note?: string
  /** 能否恢复。默认 `false`（破坏性操作默认按不可撤销处理） */
  reversible?: boolean
  /** 确认按钮文字，默认就是动作名 */
  confirmText?: string
  onConfirm: () => void | Promise<void>
}

const LABEL_STYLE = 'color:#8b949e;font-size:12.5px;line-height:1.9;white-space:nowrap'

function row(label: string, value: string, danger = false): VNode {
  return h('p', { style: 'margin:2px 0;display:flex;gap:8px;align-items:baseline' }, [
    h('span', { style: `${LABEL_STYLE};flex:none` }, `${label}`),
    h(
      'span',
      {
        style: `font-size:13px;line-height:1.7;color:${danger ? '#e4635c' : '#d5e2ea'}`,
      },
      value,
    ),
  ])
}

export function confirmDanger(opts: DangerConfirmOptions): void {
  if (!dialog) return
  const lines: VNode[] = []
  if (opts.count !== undefined) {
    lines.push(row('影响', `${opts.count} ${opts.unit ?? '项'}`))
  }
  if (opts.cascade && opts.cascade.length > 0) {
    lines.push(row('级联', `${opts.cascade.join(' / ')} 会失效`))
  }
  if (opts.note) lines.push(row('注意', opts.note))
  lines.push(row('撤销', opts.reversible ? '可以恢复' : '不可撤销', !opts.reversible))

  dialog.error({
    title: opts.action,
    content: () => h('div', { style: 'padding-top:2px' }, lines),
    positiveText: opts.confirmText ?? '确认',
    negativeText: '取消',
    maskClosable: false,
    onPositiveClick: async () => {
      await opts.onConfirm()
    },
  })
}
