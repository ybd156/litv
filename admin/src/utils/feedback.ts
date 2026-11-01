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
  content: string
  positiveText?: string
  negativeText?: string
  danger?: boolean
  onConfirm: () => void | Promise<void>
}

/** 破坏性操作统一走这里：二次确认 + 执行时按钮 loading */
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
