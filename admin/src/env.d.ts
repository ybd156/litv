/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_MOCK?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

/** 由 vite.config.ts define 注入：构建模式为 mock 时为 true */
declare const __MOCK_MODE__: boolean
