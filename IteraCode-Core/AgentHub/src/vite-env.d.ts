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
    db: {
      getSession: (id: string) => Promise<any>
      listSessions: () => Promise<any[]>
      createSession: (model: string) => Promise<string>
      deleteSession: (id: string) => Promise<void>
      saveMessages: (id: string, messages: any[]) => Promise<void>
      setSessionTitle: (id: string, title: string) => Promise<void>
      getConfig: (key: string) => Promise<string | null>
      setConfig: (key: string, value: string) => Promise<void>
    }
  }
}
