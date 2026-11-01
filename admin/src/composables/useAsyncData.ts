import { ref, type Ref } from 'vue'
import { toErrorMessage } from '@/api/errors'

export interface AsyncData<T> {
  data: Ref<T | null>
  loading: Ref<boolean>
  /** 首次加载失败的错误文案（有数据时的刷新失败只弹 toast，不覆盖界面） */
  error: Ref<string>
  loaded: Ref<boolean>
  run: (silent?: boolean) => Promise<void>
}

/**
 * 三态（加载中 / 出错 / 有数据）的通用加载器。
 * silent=true 时用于「刷新」，失败不把已有内容清空。
 */
export function useAsyncData<T>(loader: () => Promise<T>): AsyncData<T> {
  const data = ref(null) as Ref<T | null>
  const loading = ref(false)
  const error = ref('')
  const loaded = ref(false)

  async function run(silent = false): Promise<void> {
    loading.value = true
    if (!silent) error.value = ''
    try {
      data.value = await loader()
      loaded.value = true
      error.value = ''
    } catch (e) {
      error.value = toErrorMessage(e)
    } finally {
      loading.value = false
    }
  }

  return { data, loading, error, loaded, run }
}
