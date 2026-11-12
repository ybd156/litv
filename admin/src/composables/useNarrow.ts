import { onBeforeUnmount, ref, type Ref } from 'vue'

/**
 * 窄屏判定（默认 < 1280px）。
 *
 * 为什么需要它：管理台现在是 8 列宽表（名称/来源/线路/EPG/状态…），
 * 在 1024～1280 这类笔记本或分屏宽度下，表格要么横向滚动条压在最后两列上，
 * 要么把「操作」列挤到看不见。约定是：**次要列在窄屏隐藏，信息改放到展开行里**，
 * 主列（名称、状态、主操作）永远可见。
 *
 * 用 `matchMedia` 而不是监听 resize 事件：后者每次拖窗口都会触发，
 * 这里只需要「跨过阈值」这一刻。
 */
export function useNarrowScreen(maxWidth = 1279): Ref<boolean> {
  const isNarrow = ref(false)
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return isNarrow
  }
  const mql = window.matchMedia(`(max-width: ${maxWidth}px)`)
  isNarrow.value = mql.matches
  const onChange = (e: MediaQueryListEvent): void => {
    isNarrow.value = e.matches
  }
  // Safari 14 以下只有 addListener；deployment 目标浏览器不确定，两个都兜住
  if (typeof mql.addEventListener === 'function') {
    mql.addEventListener('change', onChange)
    onBeforeUnmount(() => mql.removeEventListener('change', onChange))
  } else if (typeof mql.addListener === 'function') {
    mql.addListener(onChange)
    onBeforeUnmount(() => mql.removeListener(onChange))
  }
  return isNarrow
}
