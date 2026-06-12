/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface Window {
  electronAPI?: {
    minimize: () => void
    maximize: () => void
    close: () => void
    getPlatform: () => Promise<string>
  }
}
