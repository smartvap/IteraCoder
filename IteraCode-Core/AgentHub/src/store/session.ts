import { defineStore } from "pinia"
import { ref, computed } from "vue"

const STORAGE_KEY = "agent-hub-sessions"
const CURRENT_KEY = "agent-hub-current-session"

export interface ChatMessage {
  id: number
  role: "user" | "assistant"
  content: string
  reasoning?: string
  _showReasoning?: boolean
  loading?: boolean
  elapsed?: number
  tokenCount?: number
  streamDuration?: number
  status?: string
}

export interface ChatSession {
  id: string
  title: string
  model: string
  messages: ChatMessage[]
  timeCreated: number
  timeUpdated: number
}

function loadFromLocalStorage(): Record<string, ChatSession> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return JSON.parse(raw)
  } catch { /* ignore */ }
  return {}
}

function saveToLocalStorage(sessions: Record<string, ChatSession>) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions))
}

function cloneForIpc<T>(obj: T): T {
  return JSON.parse(JSON.stringify(obj))
}

const db = () => window.electronAPI?.db

export const useSessionStore = defineStore("session", () => {
  const sessions = ref<Record<string, ChatSession>>({})
  const currentSessionId = ref<string | null>(null)
  const loaded = ref(false)

  const currentSession = computed(() => {
    if (!currentSessionId.value) return null
    return sessions.value[currentSessionId.value] ?? null
  })

  const sessionList = computed(() => {
    return Object.values(sessions.value)
      .sort((a, b) => b.timeUpdated - a.timeUpdated)
  })

  async function init() {
    if (db()) {
      const list = await db()!.listSessions()
      sessions.value = {}
      for (const s of list) {
        sessions.value[s.id] = s
      }
      const cid = await db()!.getConfig("current-session-id")
      currentSessionId.value = cid
    } else {
      sessions.value = loadFromLocalStorage()
      currentSessionId.value = localStorage.getItem(CURRENT_KEY)
    }
    loaded.value = true
  }

  async function persist() {
    if (db()) {
      if (currentSessionId.value) {
        await db()!.setConfig("current-session-id", currentSessionId.value)
      }
    } else {
      saveToLocalStorage(sessions.value)
      if (currentSessionId.value) {
        localStorage.setItem(CURRENT_KEY, currentSessionId.value)
      } else {
        localStorage.removeItem(CURRENT_KEY)
      }
    }
  }

  async function createSession(model: string): Promise<string> {
    if (db()) {
      const id = await db()!.createSession(model)
      const now = Date.now()
      sessions.value[id] = {
        id, title: "新对话", model, messages: [], timeCreated: now, timeUpdated: now,
      }
      currentSessionId.value = id
      await db()!.setConfig("current-session-id", id)
      return id
    }
    const id = "chat_" + Date.now() + "_" + Math.random().toString(36).slice(2, 6)
    const now = Date.now()
    sessions.value[id] = {
      id, title: "新对话", model, messages: [], timeCreated: now, timeUpdated: now,
    }
    currentSessionId.value = id
    persist()
    return id
  }

  async function switchSession(id: string) {
    if (!sessions.value[id]) return
    currentSessionId.value = id
    if (db()) {
      await db()!.setConfig("current-session-id", id)
    } else {
      localStorage.setItem(CURRENT_KEY, id)
    }
  }

  async function deleteSession(id: string) {
    delete sessions.value[id]
    if (db()) {
      await db()!.deleteSession(id)
      if (currentSessionId.value === id) {
        const remaining = Object.keys(sessions.value)
        currentSessionId.value = remaining.length > 0 ? remaining[0] : null
        await db()!.setConfig("current-session-id", currentSessionId.value ?? "")
      }
    } else {
      if (currentSessionId.value === id) {
        const remaining = Object.keys(sessions.value)
        currentSessionId.value = remaining.length > 0 ? remaining[0] : null
        if (currentSessionId.value) {
          localStorage.setItem(CURRENT_KEY, currentSessionId.value)
        } else {
          localStorage.removeItem(CURRENT_KEY)
        }
      }
      persist()
    }
  }

  async function setSessionTitle(id: string, title: string) {
    const session = sessions.value[id]
    if (!session) return
    session.title = title
    if (db()) {
      await db()!.setSessionTitle(id, title)
    } else {
      persist()
    }
  }

  function getSession(id: string): ChatSession | null {
    return sessions.value[id] ?? null
  }

  async function saveMessages(id: string, messages: ChatMessage[]) {
    const session = sessions.value[id]
    if (!session) return
    session.messages = messages
    session.timeUpdated = Date.now()
    if (session.title === "新对话") {
      const firstUser = messages.find(m => m.role === "user")
      if (firstUser) {
        session.title = firstUser.content.length > 40
          ? firstUser.content.slice(0, 40) + "..."
          : firstUser.content
      }
    }
    if (db()) {
      await db()!.saveMessages(id, cloneForIpc(messages))
    } else {
      persist()
    }
  }

  async function addMessage(id: string, msg: ChatMessage) {
    const session = sessions.value[id]
    if (!session) return
    session.messages.push(msg)
    session.timeUpdated = Date.now()
    if (session.title === "新对话" && msg.role === "user") {
      session.title = msg.content.length > 40
        ? msg.content.slice(0, 40) + "..."
        : msg.content
    }
    if (db()) {
      await db()!.saveMessages(id, cloneForIpc(session.messages))
    } else {
      persist()
    }
  }

  function hasSession(): boolean {
    return currentSessionId.value !== null && !!sessions.value[currentSessionId.value!]
  }

  return {
    sessions, currentSessionId, currentSession, sessionList, loaded,
    init, createSession, switchSession, deleteSession, setSessionTitle,
    getSession, saveMessages, addMessage, hasSession,
  }
})
