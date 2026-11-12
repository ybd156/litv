import { ref, type Ref } from 'vue'

export interface DragSortOptions<T> {
  items: Ref<T[]>
  keyOf: (item: T) => number | string
  /** 拖拽结束后拿到新顺序（只在本页/本列表内生效） */
  onDrop: (next: T[], movedKey: number | string) => void | Promise<void>
}

/**
 * 原生 HTML5 拖拽排序（不引 sortablejs，省 ~40KB）。
 * 用法：把 onDragStart / onDragOver / onDrop 绑到行元素上，配合 dragKey/overKey 出视觉反馈。
 */
export function useDragSort<T>(opts: DragSortOptions<T>) {
  const dragKey = ref<number | string | null>(null) as Ref<number | string | null>
  const overKey = ref<number | string | null>(null) as Ref<number | string | null>

  function start(key: number | string, e?: DragEvent): void {
    dragKey.value = key
    overKey.value = key
    if (e?.dataTransfer) {
      e.dataTransfer.effectAllowed = 'move'
      e.dataTransfer.setData('text/plain', String(key))
    }
  }

  function over(key: number | string, e: DragEvent): void {
    if (dragKey.value === null) return
    e.preventDefault()
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
    overKey.value = key
  }

  function end(): void {
    dragKey.value = null
    overKey.value = null
  }

  async function drop(key: number | string): Promise<void> {
    const from = dragKey.value
    end()
    if (from === null || from === key) return
    const list = [...opts.items.value]
    const fromIdx = list.findIndex((i) => opts.keyOf(i) === from)
    const toIdx = list.findIndex((i) => opts.keyOf(i) === key)
    if (fromIdx < 0 || toIdx < 0) return
    const [moved] = list.splice(fromIdx, 1)
    list.splice(toIdx, 0, moved)
    opts.items.value = list
    await opts.onDrop(list, opts.keyOf(moved))
  }

  /** 给「上移/下移」按钮用的无拖拽版本 */
  async function move(key: number | string, delta: number): Promise<void> {
    const list = [...opts.items.value]
    const idx = list.findIndex((i) => opts.keyOf(i) === key)
    const target = idx + delta
    if (idx < 0 || target < 0 || target >= list.length) return
    const [moved] = list.splice(idx, 1)
    list.splice(target, 0, moved)
    opts.items.value = list
    await opts.onDrop(list, opts.keyOf(moved))
  }

  return { dragKey, overKey, start, over, end, drop, move }
}
