<template>
  <div class="agent-chat">
    <!-- Session Top Bar -->
    <div class="session-topbar">
      <div class="topbar-scroll">
        <div
          v-for="s in sessionStore.sessionList"
          :key="s.id"
          class="topbar-item"
          :class="{ active: s.id === sessionStore.currentSessionId }"
          @click="handleSwitchSession(s.id)"
        >
          <span class="topbar-item-title">{{ s.title }}</span>
          <button
            class="topbar-item-del"
            @click.stop="handleDeleteSession(s.id)"
            title="删除对话"
          >
            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M18 6L6 18M6 6l12 12"/>
            </svg>
          </button>
        </div>
        <el-tooltip content="新建对话" placement="bottom">
          <button class="topbar-new" @click="handleNewSession">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 5v14M5 12h14"/>
            </svg>
          </button>
        </el-tooltip>
      </div>
    </div>

    <!-- Toolbar -->
    <div class="agent-chat-toolbar">
      <div class="toolbar-left">
        <el-select
          v-model="selectedModel"
          placeholder="选择模型"
          size="small"
          style="width: 220px"
          :loading="loadingModels"
        >
          <el-option v-for="m in availableModels" :key="m.name" :label="m.label" :value="m.name" />
        </el-select>
        <el-tooltip content="刷新模型列表" placement="bottom">
          <el-button text size="small" @click="fetchModels" :loading="loadingModels">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
      <div class="toolbar-right">
        <span class="token-usage" title="今日Token用量">
          {{ $t('chat.todayTokens', { n: todayTokens.toLocaleString() }) }}
        </span>
        <el-tooltip :content="showReasoning ? '关闭思考过程' : '显示思考过程'" placement="bottom">
          <el-button text size="small" :class="{ active: showReasoning }" @click="showReasoning = !showReasoning">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 3c.132 0 .263 0 .393 0a7.5 7.5 0 0 0 7.92 12.446a9 9 0 1 1 -8.313 -12.454z"/>
              <path d="M17 4a2 2 0 0 0 2 2a2 2 0 0 0 -2 2a2 2 0 0 0 -2 -2a2 2 0 0 0 2 -2"/>
              <path d="M19 11h0.01"/>
            </svg>
          </el-button>
        </el-tooltip>
        <el-tooltip content="清空当前对话" placement="bottom">
          <el-button text size="small" @click="clearChat" :disabled="messages.length === 0">
            <el-icon><Delete /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
    </div>

    <div class="session-turn" ref="sessionRef">
      <div class="session-content" ref="contentRef">
        <div v-if="messages.length === 0 && !isStreaming" class="empty-state">
          <div class="empty-icon">
            <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="#8f8f8f" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 3c.132 0 .263 0 .393 0a7.5 7.5 0 0 0 7.92 12.446a9 9 0 1 1 -8.313 -12.454z"/>
              <path d="M17 4a2 2 0 0 0 2 2a2 2 0 0 0 -2 2a2 2 0 0 0 -2 -2a2 2 0 0 0 2 -2"/>
            </svg>
          </div>
          <p class="empty-title">{{ $t('chat.emptyTitle') }}</p>
          <p class="empty-desc">{{ $t('chat.emptyDesc') }}</p>
        </div>

        <!-- 窗口化：隐藏的早期消息提示 -->
        <div v-if="hiddenCount > 0" class="collapsed-hint">
          已折叠 {{ hiddenCount }} 条早期消息
        </div>

        <template v-for="(msg, i) in messages" :key="msg.id">
          <template v-if="isMessageVisible(i)">
          <!-- 消息间分隔线（用户消息前 + 时间戳） -->
          <div v-if="msg.role === 'user' && i > 0" class="turn-separator">
            <span class="turn-sep-line"></span>
            <span class="turn-sep-time">{{ fmtTimeAgo(msg.id) }}</span>
            <span class="turn-sep-line"></span>
          </div>

          <!-- 用户消息——右对齐气泡 -->
          <div v-if="msg.role === 'user'" class="msg-user">
            <div class="msg-user-text">{{ msg.content }}</div>
          </div>

          <!-- 助手消息——左对齐卡片 -->
          <div v-else class="msg-assistant">
            <!-- 思考中指示器 -->
            <div v-if="(msg.status === 'pending' || msg.status === 'thinking') && !msg.content && !msg.reasoning" class="assistant-thinking">
              <span class="thinking-spinner"></span>
              <span>{{ $t('chat.thinking') }}</span>
            </div>

            <!-- 思考过程（折叠区） -->
            <div v-if="msg.reasoning && showReasoning" class="assistant-reasoning">
              <button class="reasoning-toggle" @click="toggleReasoning(msg)">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"
                  :class="{ rotated: msg._showReasoning }" class="reasoning-chevron">
                  <path d="M9 18l6-6-6-6"/>
                </svg>
                <span>{{ $t('chat.reasoning') }}</span>
              </button>
              <div v-if="msg._showReasoning" class="reasoning-content" v-html="renderMarkdown(msg.reasoning)"></div>
            </div>

            <!-- 正式回复 -->
            <div v-if="msg.content" class="assistant-content" :class="{ 'error-state': msg.status === 'error' }">
              <div class="markdown" v-html="renderMarkdown(msg.content)"></div>
              <div class="assistant-meta">
                <button
                  v-if="msg.status === 'done' || msg.content.length > 50"
                  class="copy-btn"
                  :data-tooltip="'复制'"
                  @click="copyContent(msg.content)"
                >
                  <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5">
                    <path d="M6.25 6.25V2.92h10.83v10.83h-2.5M13.75 6.25V17.08H2.92V6.25h10.83z"/>
                  </svg>
                </button>
                <!-- 重新生成按钮 -->
                <button
                  v-if="msg.status === 'done' && !isStreaming"
                  class="copy-btn"
                  title="重新生成"
                  @click="regenerateMessage(i)"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/>
                    <path d="M21 3v5h-5"/>
                    <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/>
                    <path d="M3 21v-5h5"/>
                  </svg>
                </button>
                <!-- 错误重试按钮 -->
                <button
                  v-if="msg.status === 'error' && !isStreaming"
                  class="retry-btn"
                  title="重试"
                  @click="regenerateMessage(i)"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/>
                    <path d="M21 3v5h-5"/>
                    <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/>
                    <path d="M3 21v-5h5"/>
                  </svg>
                  <span>重试</span>
                </button>
                <span v-if="msg.elapsed && msg.status !== 'error'" class="assistant-elapsed">{{ fmtTime(msg.elapsed) }}</span>
              </div>
            </div>
          </div>
          </template><!-- /isMessageVisible -->
        </template>
      </div>

      <button
        v-if="scrollState.showJumpButton"
        class="jump-to-bottom"
        @click="jumpToBottom"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 5v14M5 12l7 7 7-7"/>
        </svg>
      </button>
    </div>

    <div class="prompt-region">
        <div class="prompt-container">
          <div class="prompt-editor" :class="{ focused: isFocused }">
              <textarea
                ref="textareaRef"
                v-model="inputText"
                class="prompt-textarea"
                rows="1"
                :placeholder="$t('chat.placeholder')"
                :disabled="composerState.blocked"
                @focus="isFocused = true"
                @blur="isFocused = false"
                @keydown="handleKeydown"
                @input="autoResize"
              />
            <div class="prompt-actions">
              <span class="prompt-hint">{{ composerState.canStop ? $t('chat.escHint') : $t('chat.sendHint') }}</span>
              <button v-if="composerState.canStop" class="send-btn stop" @click="stopChat">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
              </button>
              <button
                v-else
                class="send-btn"
                :class="{ primary: composerState.canSend }"
                :disabled="!composerState.canSend"
                @click="handleSend"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M12 19V5M5 12l7-7 7 7"/>
                </svg>
              </button>
            </div>
          </div>
        </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import "highlight.js/styles/atom-one-dark.css"
import { ref, reactive, nextTick, onMounted, onBeforeUnmount, watch, computed } from "vue"
import { Delete, Refresh } from "@element-plus/icons-vue"
import { ElMessage } from "element-plus"
import { fetchEventSource } from "@microsoft/fetch-event-source"
import service from "@/http"
import { BASE_URL } from "@/http/config"
import { useSessionStore } from "@/store/session"
import { getTodayTokenUsage as getApiTokenUsage } from "@/api/ChatApi"
import { getTodayTokenUsage } from "@/utils/dataRouter"
import { useAppStore } from "@/store/app"
import { marked } from "marked"
import hljs from "highlight.js"
import { i18n } from "@/locales"

/** 不可重试的致命错误 */
class FatalError extends Error {}
/** 可重试的错误 */
class RetriableError extends Error {}

// ===== 图标 SVG =====
const icons = {
  copy: '<svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M6.2513 6.24935V2.91602H17.0846V13.7493H13.7513M13.7513 6.24935V17.0827H2.91797V6.24935H13.7513Z"/></svg>',
  check: '<svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M5 11.9657L8.37838 14.7529L15 5.83398"/></svg>',
}

// ===== marked 配置 =====
// breaks=false：单 \n 不转 <br>，保留 Markdown 结构完整性
marked.setOptions({ breaks: false, gfm: true })

// 自定义 renderer：代码块加 hljs 高亮 + 语言标签 + 复制按钮
// marked v12 签名：code(code: string, infostring?: string, escaped?: boolean)
marked.use({
  renderer: {
    code(code: string, infostring: string | undefined, _escaped: boolean): string {
      const lang = infostring && hljs.getLanguage(infostring) ? infostring : ""
      const highlighted = lang
        ? hljs.highlight(code, { language: lang }).value
        : hljs.highlightAuto(code).value
      const langLabel = lang ? `<span class="code-lang">${lang}</span>` : ""
      return `<div data-code-block="1">${langLabel}<button data-code-copy="1"><span data-copy-icon="1">${icons.copy}</span><span data-check-icon="1">${icons.check}</span></button><pre><code class="hljs language-${lang || 'plaintext'}">${highlighted}</code></pre></div>`
    },
  },
})

/**
 * Markdown 渲染缓存——避免对流式中的同一文本重复 parse
 * key = 原始文本, value = 渲染后的 HTML
 */
const mdCache = new Map<string, string>()

/**
 * 渲染 Markdown 文本为 HTML
 * - 流式输出时对未闭合的代码围栏做容错
 * - 使用缓存避免重复 parse
 */
function renderMarkdown(text: string): string {
  if (!text) return ""
  // 缓存命中
  const cached = mdCache.get(text)
  if (cached !== undefined) return cached

  let result = text
  try {
    let processed = text
    // 流式容错：统计未闭合的 ``` 围栏，奇数则补上闭合围栏
    const fenceCount = (processed.match(/^`{3,}/gm) || []).length
    if (fenceCount % 2 !== 0) {
      processed += "\n```"
    }
    result = marked.parse(processed) as string
  } catch (e) {
    console.error("[renderMarkdown] parse error:", e)
    result = text
  }

  // 限制缓存大小（防止内存泄漏）
  if (mdCache.size > 200) mdCache.clear()
  mdCache.set(text, result)
  return result
}

type MessageStatus = "pending" | "thinking" | "streaming" | "done" | "error"

interface ChatMessage {
  id: number
  role: "user" | "assistant"
  content: string
  reasoning?: string
  _showReasoning?: boolean
  _userToggledReasoning?: boolean
  status: MessageStatus
  loading?: boolean
  elapsed?: number
  tokenCount?: number
}

interface ModelInfo {
  name: string
  label: string
  source: string
  family: string
}

const sessionStore = useSessionStore()
const appStore = useAppStore()

const messages = reactive<ChatMessage[]>([])
const inputText = ref("")
const isStreaming = ref(false)
const selectedModel = ref("")
const availableModels = ref<ModelInfo[]>([])
const loadingModels = ref(false)
const todayTokens = ref(0)
const showReasoning = ref(true)
const isFocused = ref(false)
const sessionRef = ref<HTMLDivElement>()
const contentRef = ref<HTMLDivElement>()
const textareaRef = ref<HTMLTextAreaElement>()
let abortController: AbortController | null = null
let saveTimer: ReturnType<typeof setTimeout> | null = null
let currentFinish: (() => void) | null = null // 供 stopChat 调用当前流的 finish

// ===== 发送历史记录（localStorage 持久化） =====
const SENT_HISTORY_KEY = "agent-hub-sent-history"
const MAX_HISTORY_SIZE = 50
const sentHistory = ref<string[]>(JSON.parse(localStorage.getItem(SENT_HISTORY_KEY) || "[]"))
let historyIndex = -1

function saveSentHistory() {
  try {
    localStorage.setItem(SENT_HISTORY_KEY, JSON.stringify(sentHistory.value))
  } catch { /* quota exceeded, ignore */ }
}

// ===== 输入区状态机 =====
const composerState = computed(() => ({
  blocked: isStreaming.value,
  canSend: !isStreaming.value && inputText.value.trim().length > 0,
  canStop: isStreaming.value,
  placeholder: isStreaming.value ? "AI 正在回复..." : "输入消息...",
}))

const currentTitle = computed(() => {
  const s = sessionStore.currentSession
  return s ? s.title : ""
})

function fmtDate(ts: number): string {
  const d = new Date(ts)
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  if (sameDay) return d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })
  const thisYear = d.getFullYear() === now.getFullYear()
  if (thisYear) return `${d.getMonth() + 1}/${d.getDate()}`
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`
}

function scheduleSave() {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    if (sessionStore.currentSessionId) {
      sessionStore.saveMessages(sessionStore.currentSessionId, [...messages])
    }
  }, 500)
}

function loadSession(sid: string) {
  messages.splice(0)
  const session = sessionStore.getSession(sid)
  if (session) {
    session.messages.forEach(m => messages.push({ ...m, status: (m.status as MessageStatus) || "done" }))
    if (session.model) selectedModel.value = session.model
  }
}

async function handleNewSession() {
  if (!selectedModel.value) return
  const id = await sessionStore.createSession(selectedModel.value)
  messages.splice(0)
}

async function handleSwitchSession(id: string) {
  if (isStreaming.value) stopChat()
  await sessionStore.switchSession(id)
  loadSession(id)
  nextTick(() => scrollToBottom(true))
}

async function handleDeleteSession(id: string) {
  const wasCurrent = id === sessionStore.currentSessionId
  await sessionStore.deleteSession(id)
  if (wasCurrent) {
    if (sessionStore.currentSessionId) {
      loadSession(sessionStore.currentSessionId)
      nextTick(() => scrollToBottom(true))
    } else {
      messages.splice(0)
    }
  }
}

function toggleReasoning(msg: ChatMessage) {
  msg._userToggledReasoning = true
  msg._showReasoning = !msg._showReasoning
}

async function refreshTokenUsage() {
  try {
    const res: any = await getTodayTokenUsage()
    todayTokens.value = res.totalTokens || 0
  } catch { /* 忽略 */ }
}

async function fetchModels() {
  loadingModels.value = true
  try {
    const token = localStorage.getItem("token") || ""
    const params = new URLSearchParams()
    // 根据模型类型传递对应地址
    if (appStore.modelType === "network" && appStore.apiUrl) {
      params.set("apiUrl", appStore.apiUrl)
      if (appStore.apiKey) params.set("apiKey", appStore.apiKey)
    } else if (appStore.ollamaUrl) {
      params.set("ollamaUrl", appStore.ollamaUrl)
    }
    const url = `${service.defaults.baseURL}/chat2/models?${params}`
    console.log("[fetchModels] modelType=", appStore.modelType, "url=", url)
    const res = await fetch(url, {
      headers: { Authorization: `Bearer ${token}` }
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const data: ModelInfo[] = await res.json()
    availableModels.value = data
    // 验证当前选中的模型是否存在，不存在则默认选第一个
    const currentExists = data.some(m => m.name === selectedModel.value)
    if (data.length > 0 && !currentExists) {
      selectedModel.value = data[0].name
    }
    if (data.length > 0 && !sessionStore.hasSession()) {
      handleNewSession()
    }
  } catch (e: any) {
    console.error("获取模型列表失败:", e.message)
  } finally {
    loadingModels.value = false
  }
}

async function clearChat() {
  messages.splice(0)
  if (sessionStore.currentSessionId) {
    await sessionStore.saveMessages(sessionStore.currentSessionId, [])
  }
}

function stopChat() {
  abortController?.abort()
  // fetchEventSource 在 abort 时 resolve Promise（不是 reject），
  // onclose/onerror/catch 都不会执行，必须手动调用 finish
  if (currentFinish) {
    currentFinish()
    currentFinish = null
  }
}

function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = "auto"
  el.style.height = Math.min(el.scrollHeight, 120) + "px"
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault()
    if (composerState.value.canSend) handleSend()
    return
  }
  if (e.key === "Escape" && composerState.value.canStop) {
    stopChat()
    return
  }
  // ArrowUp 循环浏览发送历史
  if (e.key === "ArrowUp" && sentHistory.value.length > 0 && !composerState.value.blocked) {
    e.preventDefault()
    historyIndex = Math.min(historyIndex + 1, sentHistory.value.length - 1)
    inputText.value = sentHistory.value[historyIndex]
    nextTick(() => {
      const el = textareaRef.value
      if (el) {
        el.selectionStart = el.selectionEnd = el.value.length
        autoResize()
      }
    })
    return
  }
  // ArrowDown 向回浏览历史，到底则清空
  if (e.key === "ArrowDown" && historyIndex >= 0 && !composerState.value.blocked) {
    e.preventDefault()
    historyIndex--
    if (historyIndex < 0) {
      inputText.value = ""
    } else {
      inputText.value = sentHistory.value[historyIndex]
    }
    nextTick(autoResize)
    return
  }
}

function fmtTime(ms: number): string {
  if (ms < 1000) return ms + " ms"
  return (ms / 1000).toFixed(1) + " s"
}

function fmtTimeAgo(id: number): string {
  const d = new Date(id)
  const now = new Date()
  const diff = now.getTime() - d.getTime()
  if (diff < 60000) return "刚刚"
  if (diff < 3600000) return Math.floor(diff / 60000) + " 分钟前"
  const sameDay = d.toDateString() === now.toDateString()
  if (sameDay) return d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })
  return d.getMonth() + 1 + "/" + d.getDate() + " " + d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })
}

async function copyContent(text: string) {
  try {
    await navigator.clipboard.writeText(text)
        ElMessage.success({ message: i18n.global.t('chat.copied'), duration: 1500, grouping: true })
  } catch {
    // fallback
    const ta = document.createElement("textarea")
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand("copy")
    document.body.removeChild(ta)
        ElMessage.success({ message: i18n.global.t('chat.copied'), duration: 1500, grouping: true })
  }
}

/**
 * 代码块复制按钮的事件委托处理
 * 通过事件冒泡监听 [data-code-copy] 按钮的点击
 */
function handleCodeCopyClick(e: MouseEvent) {
  const target = (e.target as HTMLElement)?.closest("[data-code-copy]")
  if (!target) return
  const block = target.closest("[data-code-block]")
  if (!block) return
  const codeEl = block.querySelector("code")
  if (!codeEl) return
  copyContent(codeEl.textContent || "")
  // 2 秒内显示勾选图标
  target.setAttribute("data-copied", "1")
  setTimeout(() => target.removeAttribute("data-copied"), 2000)
}

// ===== 智能滚动管理 =====
const scrollState = reactive({
  atBottom: true,
  userScrolled: false,
  showJumpButton: false,
})

let scrollMark = 0

function isAtBottom(el: HTMLElement): boolean {
  return el.scrollHeight - el.scrollTop - el.clientHeight <= 2
}

function isFarFromBottom(el: HTMLElement): boolean {
  const threshold = Math.max(400, el.clientHeight)
  return el.scrollHeight - el.scrollTop - el.clientHeight > threshold
}

function handleScroll() {
  const el = contentRef.value
  if (!el) return
  const atBottom = isAtBottom(el)
  scrollState.atBottom = atBottom
  if (atBottom) {
    scrollState.userScrolled = false
    scrollMark += 1
  } else {
    // 用户滚离底部，取消待执行的智能滚动
    if (scrollFrame !== undefined) {
      cancelAnimationFrame(scrollFrame)
      scrollFrame = undefined
    }
  }
  // 流式期间不显示跳底按钮（布局抖动会导致误触发），仅非流式时检查
  scrollState.showJumpButton = !isStreaming.value && !atBottom && isFarFromBottom(el)
  handleHistoryScroll()
}

function handleUserScroll() {
  scrollState.userScrolled = !scrollState.atBottom
}

/** 强制滚动到底部（适用于加载/切换等一次性场景） */
function scrollToBottom(force = false) {
  const el = contentRef.value
  if (!el) return
  if (!force && !scrollState.atBottom) return
  el.scrollTop = el.scrollHeight
  scrollState.atBottom = true
  scrollState.showJumpButton = false
}

/** 智能滚动到底部——等待 DOM 布局完成后再滚动（用于流式更新）
  * 流式期间使用 userScrolled 而非 atBottom 判断，
  * 避免布局抖动导致 atBottom 瞬时 false 而停止滚动 */
let scrollFrame: number | undefined
function scheduleSmartScroll() {
  // 流式输出期间：只要用户没有手动滚离底部，就保持跟随
  if (isStreaming.value && scrollState.userScrolled) return
  // 非流式期间：传统 atBottom 语义
  if (!isStreaming.value && !scrollState.atBottom) return
  if (scrollFrame !== undefined) return // 已排期，等待执行
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = requestAnimationFrame(() => {
      scrollFrame = undefined
      const el = contentRef.value
      if (!el) return
      // 流式期间：始终滚底；非流式期间：仅 atBottom 时滚底
      if (!isStreaming.value && !scrollState.atBottom) return
      el.scrollTop = el.scrollHeight
    })
  })
}

function jumpToBottom() {
  const el = contentRef.value
  if (!el) return
  el.scrollTop = el.scrollHeight
  scrollState.atBottom = true
  scrollState.userScrolled = false
  scrollState.showJumpButton = false
  scrollMark += 1
}

// ===== 内容区高度变化自动滚动 =====
let contentObserver: ResizeObserver | null = null

function setupContentObserver() {
  const el = contentRef.value
  if (!el) return
  contentObserver?.disconnect()
  contentObserver = new ResizeObserver(() => {
    scheduleSmartScroll()
    // 仅在非流式时更新跳底按钮状态（流式期间按钮不显示）
    if (!isStreaming.value) {
      const scroller = contentRef.value
      if (scroller) {
        scrollState.showJumpButton = !scrollState.atBottom && isFarFromBottom(scroller)
      }
    }
  })
  contentObserver.observe(el)
}

function teardownContentObserver() {
  contentObserver?.disconnect()
  contentObserver = null
}

// ===== 历史消息加载（预留接口） =====
const historyState = reactive({
  loading: false,
  hasMore: false,
})

// ===== 渲染窗口（性能优化） =====
/** 最多渲染的消息条数（超过后只保留最后 RENDER_LIMIT 条 + 顶部提示） */
const RENDER_LIMIT = 100
/** 当消息总数超过阈值时才启用窗口化 */
const RENDER_THRESHOLD = 120

function isMessageVisible(index: number): boolean {
  if (messages.length <= RENDER_THRESHOLD) return true
  // 最后一条消息（正在流式输出的）始终可见
  if (index >= messages.length - RENDER_LIMIT) return true
  return false
}

const hiddenCount = computed(() => {
  if (messages.length <= RENDER_THRESHOLD) return 0
  return messages.length - RENDER_LIMIT
})

function handleHistoryScroll() {
  const el = contentRef.value
  if (!el || !historyState.hasMore || historyState.loading) return
  if (el.scrollTop < 200) {
    loadMoreHistory()
  }
}

async function loadMoreHistory() {
  historyState.loading = true
  try {
    // TODO: 后端提供历史消息分页接口后接入
    // const older = await fetchOlderMessages(...)
    // messages.unshift(...older)
  } finally {
    historyState.loading = false
  }
}

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || composerState.value.blocked || !selectedModel.value) return
  inputText.value = ""

  // 保存到发送历史（去重 + 限制数量）
  const existingIdx = sentHistory.value.indexOf(text)
  if (existingIdx !== -1) sentHistory.value.splice(existingIdx, 1)
  sentHistory.value.unshift(text)
  if (sentHistory.value.length > MAX_HISTORY_SIZE) sentHistory.value.pop()
  saveSentHistory()
  historyIndex = -1
  nextTick(autoResize)

  if (!sessionStore.hasSession()) {
    await sessionStore.createSession(selectedModel.value)
  }

  messages.push({ id: Date.now(), role: "user", content: text, status: "done" })
  await sessionStore.addMessage(sessionStore.currentSessionId!, messages[messages.length - 1])

  await streamAssistantReply(text, messages.length)
}

/**
 * 发起 SSE 流式请求，将 AI 回复填充到 messages[idx]
 * @param userText 用户提问文本
 * @param idx 助手消息在 messages 数组中的索引
 */
async function streamAssistantReply(userText: string, idx: number) {
  // 如果目标位置已有消息（重新生成场景），先清空
  if (messages[idx]) {
    messages[idx].content = ""
    messages[idx].reasoning = ""
    messages[idx].status = "pending"
    messages[idx].loading = true
    messages[idx].elapsed = undefined
    messages[idx]._showReasoning = false
    messages[idx]._userToggledReasoning = false
  } else {
    messages.push({
      id: Date.now() + 1,
      role: "assistant",
      content: "",
      reasoning: "",
      _showReasoning: false,
      _userToggledReasoning: false,
      status: "pending",
      loading: true,
    })
  }

  const MAX_HISTORY = 0 // 暂时不传递历史记录，每次对话独立
  const raw = messages.slice(0, idx)
  const history = MAX_HISTORY > 0 ? raw.slice(-MAX_HISTORY).map(m => ({
    role: m.role,
    content: m.content
  })) : []

  // Spring WebFlux 的 @RequestParam 只解析 URL query string，不解析 body
  const params = new URLSearchParams()
  params.set("model", selectedModel.value)
  params.set("message", userText)
  if (appStore.modelType === "network" && appStore.apiUrl) {
    params.set("isLocal", "false")
    params.set("apiUrl", appStore.apiUrl)
    if (appStore.apiKey) params.set("apiKey", appStore.apiKey)
  } else {
    params.set("isLocal", "true")
    if (appStore.ollamaUrl) params.set("ollamaUrl", appStore.ollamaUrl)
  }
  params.set("lang", i18n.global.locale.value as string)
  if (history.length > 0) {
    params.set("messages", JSON.stringify(history))
  }

  // ===== SSE 流式请求 =====
  const reqUrl = `${BASE_URL}/chat2/stream?${params.toString()}`
  isStreaming.value = true
  abortController = new AbortController()
  const msgStart = Date.now()
  let streamStart = 0
  let hasReceivedData = false  // 防止流结束后重连

  const finish = () => {
    const now = Date.now()
    if (messages[idx]) {
      messages[idx].loading = false
      messages[idx].status = messages[idx].status === "error" ? "error" : "done"
      messages[idx].elapsed = now - msgStart

      // 思考完成后自动折叠 reasoning（除非用户已手动切换）
      if (!messages[idx]._userToggledReasoning) {
        messages[idx]._showReasoning = false
      }

      // 回退处理：如果流结束后 content 仍为空，但有 reasoning，则将 reasoning 作为 content 显示
      if (!messages[idx].content && messages[idx].reasoning) {
        console.warn("[Chat] 流结束后 content 为空，将 reasoning 内容作为回复显示")
        messages[idx].content = messages[idx].reasoning
        messages[idx].reasoning = ""
      }
    }
    isStreaming.value = false
    abortController = null
    currentFinish = null
    scheduleSave()
    scrollToBottom(true)
    refreshTokenUsage()
  }
  currentFinish = finish

  fetchEventSource(reqUrl, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${localStorage.getItem("token") || ""}`,
    },
    signal: abortController.signal,
    onmessage(msg) {
      if (!msg.data || msg.data === "[DONE]") return

      // 解析 JSON 包装的 data（后端用 JSON.writeValueAsString 发送，防止 \n 破坏 SSE 协议）
      let text = msg.data
      try {
        if ((text.startsWith('"') && text.endsWith('"')) || text.startsWith("\\")) {
          text = JSON.parse(text)
        }
      } catch { /* 兼容旧格式（未 JSON 包装的原始文本） */ }

      if (msg.event === "reasoning") {
        if (!messages[idx].reasoning) messages[idx].reasoning = ""
        messages[idx].reasoning += text
        messages[idx].status = "thinking"
        // 流式思考中自动展开，除非用户已手动切换
        if (!messages[idx]._userToggledReasoning) {
          messages[idx]._showReasoning = true
        }
      } else if (msg.event === "content" || msg.event === "" || !msg.event) {
        // 正式回复内容（event:content 或默认事件）
        if (!streamStart) {
          streamStart = Date.now()
          if (!messages[idx]._userToggledReasoning) {
            messages[idx]._showReasoning = false
          }
        }
        messages[idx].content += text
        messages[idx].status = "streaming"
        hasReceivedData = true
      }
      messages[idx].loading = false
      // 流式期间强制定位到底部，不受 atBottom 限制
      scheduleSmartScroll()
      scheduleSave()
    },
    onerror(err) {
      if (err instanceof FatalError) throw err
      if (hasReceivedData) throw err  // 已收到数据，不再重连
      console.warn("SSE 流式错误，尝试重连:", err)
    },
    onclose() {
      finish()
    },
    onopen: async (response) => {
      if (response.ok) return
      if (response.status === 401) {
        localStorage.removeItem("token")
        window.location.hash = "#/login"
      } else {
        console.error(`SSE 连接失败: HTTP ${response.status}`)
        messages[idx].content = messages[idx].content || "请求失败，请稍后再试"
        messages[idx].status = "error"
        ElMessage.error({ message: `请求失败 (HTTP ${response.status})`, duration: 3000 })
      }
      finish()
      // 返回永不 resolve 的 Promise，阻止 fetchEventSource 继续处理
      return new Promise(() => {})
    },
  }).catch((err: any) => {
    if (err.name !== "AbortError") {
      messages[idx].content = messages[idx].content || "请求失败，请稍后再试"
      messages[idx].status = "error"
      ElMessage.error({ message: i18n.global.t('chat.error'), duration: 3000 })
    } else {
      ElMessage.info({ message: i18n.global.t('chat.stopped'), duration: 1500, grouping: true })
    }
    finish()
  })
}

/**
 * 重新生成指定的助手消息回复
 * 删除该助手消息之后的全部消息，用同样的用户问题重新请求
 */
async function regenerateMessage(idx: number) {
  if (isStreaming.value) return
  // 找到该助手消息前面的最近一条用户消息
  let userText = ""
  for (let i = idx - 1; i >= 0; i--) {
    if (messages[i].role === "user") {
      userText = messages[i].content
      break
    }
  }
  if (!userText) return

  // 删除从 idx 开始到末尾的所有消息
  messages.splice(idx)
  await streamAssistantReply(userText, idx)
}

watch(() => messages.length, () => scrollToBottom(false))

onMounted(async () => {
  await sessionStore.init()
  fetchModels()
  if (sessionStore.currentSessionId) {
    const session = sessionStore.getSession(sessionStore.currentSessionId)
    if (session) {
      selectedModel.value = session.model
      session.messages.forEach(m => messages.push({ ...m, status: (m.status as MessageStatus) || "done" }))
    }
  }
  // 等待 DOM 渲染完成后滚动到底部
  nextTick(() => {
    scrollToBottom(true)
  })

  // 在可滚动容器上绑定事件
  const el = contentRef.value
  if (el) {
    el.addEventListener("scroll", handleScroll, { passive: true })
    el.addEventListener("wheel", handleUserScroll, { passive: true })
    el.addEventListener("touchmove", handleUserScroll, { passive: true })
  }
  // 代码块复制按钮事件委托
  contentRef.value?.addEventListener("click", handleCodeCopyClick)
  setupContentObserver()
  refreshTokenUsage()
})

onBeforeUnmount(() => {
  abortController?.abort()
  if (saveTimer) clearTimeout(saveTimer)
  if (scrollFrame !== undefined) cancelAnimationFrame(scrollFrame)
  teardownContentObserver()

  const el = contentRef.value
  if (el) {
    el.removeEventListener("scroll", handleScroll)
    el.removeEventListener("wheel", handleUserScroll)
    el.removeEventListener("touchmove", handleUserScroll)
    el.removeEventListener("click", handleCodeCopyClick)
  }
})

</script>

<style scoped>
.agent-chat {
  height: 100%; display: flex; flex-direction: column;
  background: #fafafa; color: #1f2937;
  font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}

/* ===== Session Top Bar ===== */
.session-topbar {
  height: 38px; flex-shrink: 0;
  background: #fff; border-bottom: 1px solid #e5e7eb;
  overflow: hidden;
}
.topbar-scroll {
  height: 100%; display: flex; align-items: center;
  gap: 2px; padding: 0 8px; overflow-x: auto;
  scrollbar-width: none;
}
.topbar-scroll::-webkit-scrollbar { display: none; }
.topbar-item {
  display: flex; align-items: center; gap: 4px;
  height: 26px; padding: 0 10px; border-radius: 4px;
  font-size: 12px; color: #6f6f6f; cursor: pointer;
  white-space: nowrap; flex-shrink: 0;
  transition: background .1s;
}
.topbar-item:hover { background: #f0f0f0; }
.topbar-item.active { background: #e8e8e8; color: #171717; font-weight: 500; }
.topbar-item-title { max-width: 140px; overflow: hidden; text-overflow: ellipsis; }
.topbar-item-del {
  display: none; align-items: center; justify-content: center;
  width: 14px; height: 14px; border-radius: 3px;
  border: none; background: transparent; color: #8f8f8f; cursor: pointer;
  flex-shrink: 0; padding: 0;
}
.topbar-item:hover .topbar-item-del { display: flex; }
.topbar-item-del:hover { background: #ddd; color: #6f6f6f; }
.topbar-new {
  display: flex; align-items: center; justify-content: center;
  width: 26px; height: 26px; border-radius: 4px;
  border: none; background: transparent; color: #8f8f8f; cursor: pointer;
  flex-shrink: 0; transition: background .1s;
}
.topbar-new:hover { background: #f0f0f0; color: #6f6f6f; }

.topbar-divider { height: 4px; flex-shrink: 0; background: #f5f6f7; border-bottom: 1px solid #e5e5e5; }

/* ===== Toolbar ===== */
.agent-chat-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 16px; background: #fff;
  border-bottom: 1px solid #e5e7eb; flex-shrink: 0; gap: 8px;
}
.toolbar-left, .toolbar-right { display: flex; align-items: center; gap: 4px; }
.active { color: #034cff; }

.token-usage {
  font-size: 12px; color: #9ca3af; margin-right: 8px;
  white-space: nowrap; user-select: none;
}

/* ===== Session ===== */
.session-turn { flex: 1; min-height: 0; display: flex; position: relative; }
.session-content {
  flex: 1; overflow-y: auto; overflow-x: hidden; scrollbar-width: none; width: 100%;
  padding: 20px 0 48px; word-break: break-word;
  scroll-behavior: smooth;
}
.session-content::-webkit-scrollbar { display: none; }

.empty-state {
  display: flex; flex-direction: column; align-items: center;
  justify-content: center; height: 100%; gap: 10px;
  max-width: 800px; margin: 0 auto; padding: 0 16px;
}
.empty-icon { opacity: .4; }
.empty-title { font-size: 15px; color: #171717; font-weight: 500; margin: 0; }
.empty-desc { font-size: 13px; color: #8f8f8f; margin: 0; }

/* ===== Turn Separator ===== */
.turn-separator {
  display: flex; align-items: center; gap: 10px;
  max-width: 800px; margin: 16px auto 12px; padding: 0 16px; width: 100%;
}
.turn-sep-line { flex: 1; height: 1px; background: #ebebeb; }
.turn-sep-time {
  font-size: 11px; color: #c7c7c7; white-space: nowrap; flex-shrink: 0;
}

/* Collapsed hint */
.collapsed-hint {
  text-align: center; padding: 8px 16px; margin-bottom: 4px;
  font-size: 12px; color: #8f8f8f; background: #f5f5f5;
  border-radius: 6px; max-width: 800px; margin-left: auto; margin-right: auto;
}

/* ===== User Message ===== */
.msg-user {
  display: flex; justify-content: flex-end;
  max-width: 800px; margin: 0 auto; padding: 0 16px; width: 100%;
}
.msg-user-text {
  max-width: min(75%, 60ch); white-space: pre-wrap; word-break: break-word;
  background: #f3f4f6; border: 1px solid #e5e7eb;
  padding: 10px 16px; border-radius: 16px 16px 4px 16px;
  font-size: 15px; line-height: 1.6; color: #1f2937;
}

/* ===== Assistant Message ===== */
.msg-assistant {
  max-width: 800px; margin: 12px auto 0; padding: 0 16px; width: 100%;
  font-size: 15px; line-height: 1.75; color: #374151;
}

/* Thinking indicator */
.assistant-thinking {
  display: flex; align-items: center; gap: 8px;
  color: #8f8f8f; font-size: 13px; padding: 4px 0;
}
.thinking-spinner {
  width: 15px; height: 15px; border: 2px solid #e5e5e5; border-top-color: #8f8f8f;
  border-radius: 50%; animation: spin .7s linear infinite; flex-shrink: 0;
}

/* Reasoning section */
.assistant-reasoning { margin-bottom: 12px; }
.reasoning-toggle {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 13px; color: #8f8f8f; cursor: pointer; user-select: none;
  padding: 4px 8px; border-radius: 6px; border: none; background: transparent;
  transition: background .1s;
}
.reasoning-toggle:hover { background: #f3f3f3; color: #6f6f6f; }
.reasoning-chevron { transition: transform .15s ease; flex-shrink: 0; }
.reasoning-chevron.rotated { transform: rotate(90deg); }
.reasoning-content {
  margin-top: 8px; padding: 12px 16px; border-radius: 8px;
  background: #f9fafb; border: 1px solid #e5e7eb;
  font-size: 14px; line-height: 1.7; color: #6b7280;
}
.reasoning-content :deep(p) { margin: 0 0 8px; }
.reasoning-content :deep(p:last-child) { margin-bottom: 0; }
.reasoning-content :deep(ul), .reasoning-content :deep(ol) { margin: 6px 0; padding-left: 1.5em; }
.reasoning-content :deep(li) { margin-bottom: 4px; }
.reasoning-content :deep(code) {
  background: rgba(135,131,120,.15); padding: 2px 5px; border-radius: 3px;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 0.88em; color: #be185d;
}
.reasoning-content :deep(pre) {
  background: #1e1e2e; padding: 12px; border-radius: 6px; margin: 8px 0;
  overflow-x: auto;
}
.reasoning-content :deep(pre code) {
  background: none; padding: 0; color: #cdd6f4; font-size: 13px;
}

/* Assistant content */
.assistant-content { margin-top: 4px; }
.assistant-content.error-state .markdown { color: #dc2626; }
.assistant-meta {
  display: flex; align-items: center; gap: 8px; margin-top: 6px;
}
.assistant-elapsed { font-size: 11px; color: #c7c7c7; }

/* Copy button */
.copy-btn {
  display: flex; align-items: center; justify-content: center;
  width: 26px; height: 26px; border-radius: 6px;
  border: 1px solid #e5e5e5; background: #fcfcfc; color: #8f8f8f;
  cursor: pointer; transition: all .1s; flex-shrink: 0;
}
.copy-btn:hover { background: #f3f3f3; color: #6f6f6f; border-color: #d0d0d0; }
.copy-btn:active { background: #ebebeb; transform: scale(.95); }

/* Retry button */
.retry-btn {
  display: inline-flex; align-items: center; gap: 4px;
  height: 26px; padding: 0 10px; border-radius: 6px;
  border: 1px solid #f5c2c0; background: #fef2f2; color: #dc2626;
  font-size: 12px; cursor: pointer; transition: all .1s; flex-shrink: 0;
}
.retry-btn:hover { background: #fee2e2; border-color: #f87171; }
.retry-btn:active { transform: scale(.95); }

/* ===== Markdown 排版系统（参考 Claude/ChatGPT 风格）===== */
.markdown {
  min-width: 0; max-width: 100%;
  color: #374151; font-size: 15px; line-height: 1.75;
  word-wrap: break-word; overflow-wrap: break-word;
}
.markdown :deep(> *:first-child) { margin-top: 0 !important; }
.markdown :deep(> *:last-child) { margin-bottom: 0 !important; }

/* 段落 */
.markdown :deep(p) {
  margin: 0 0 16px; line-height: 1.75;
}

/* 标题——渐变层级 */
.markdown :deep(h1) {
  font-size: 1.6em; font-weight: 700; margin: 28px 0 16px;
  line-height: 1.3; color: #111827;
  padding-bottom: 8px; border-bottom: 2px solid #e5e7eb;
}
.markdown :deep(h2) {
  font-size: 1.35em; font-weight: 700; margin: 24px 0 12px;
  line-height: 1.35; color: #111827;
  padding-bottom: 6px; border-bottom: 1px solid #e5e7eb;
}
.markdown :deep(h3) {
  font-size: 1.2em; font-weight: 650; margin: 20px 0 10px;
  line-height: 1.4; color: #1f2937;
}
.markdown :deep(h4) {
  font-size: 1.08em; font-weight: 600; margin: 18px 0 8px;
  line-height: 1.45; color: #1f2937;
}
.markdown :deep(h5) {
  font-size: 1em; font-weight: 600; margin: 16px 0 6px;
  line-height: 1.5; color: #374151;
}
.markdown :deep(h6) {
  font-size: 0.92em; font-weight: 600; margin: 14px 0 6px;
  line-height: 1.5; color: #6b7280; text-transform: uppercase; letter-spacing: .5px;
}
/* 标题后面紧跟的内容不需要额外顶部间距 */
.markdown :deep(h1 + p), .markdown :deep(h2 + p), .markdown :deep(h3 + p) { margin-top: 0; }

/* 加粗和斜体 */
.markdown :deep(strong), .markdown :deep(b) { font-weight: 700; color: #111827; }
.markdown :deep(em), .markdown :deep(i) { font-style: italic; }
.markdown :deep(del), .markdown :deep(s) { color: #9ca3af; text-decoration: line-through; }

/* 链接 */
.markdown :deep(a) {
  color: #2563eb; text-decoration: none;
  border-bottom: 1px solid #93c5fd; transition: all .15s;
}
.markdown :deep(a:hover) { border-bottom-color: #2563eb; background: rgba(37,99,235,.06); }

/* 无序列表 */
.markdown :deep(ul) {
  margin: 8px 0 16px; padding-left: 1.6em;
  list-style: none;
}
.markdown :deep(ul > li) {
  position: relative; margin-bottom: 6px; line-height: 1.75; padding-left: 4px;
}
.markdown :deep(ul > li)::before {
  content: ""; position: absolute; left: -1em; top: 0.7em;
  width: 6px; height: 6px; border-radius: 50%; background: #d1d5db;
}
/* 嵌套无序列表 */
.markdown :deep(ul ul > li)::before { background: #9ca3af; width: 5px; height: 5px; }

/* 有序列表 */
.markdown :deep(ol) {
  margin: 8px 0 16px; padding-left: 1.8em;
  list-style: decimal;
}
.markdown :deep(ol > li) {
  margin-bottom: 6px; line-height: 1.75; padding-left: 4px;
}
.markdown :deep(li::marker) { color: #6b7280; font-weight: 500; }

/* li 内段落处理 */
.markdown :deep(li > p:first-child) { display: inline; margin: 0; }
.markdown :deep(li > p + p) { display: block; margin-top: 6px; }
.markdown :deep(li > ul), .markdown :deep(li > ol) { margin: 6px 0 6px; }

/* 任务列表（GFM） */
.markdown :deep(ul:has(> li > input[type="checkbox"])) { padding-left: 1.2em; list-style: none; }
.markdown :deep(li:has(> input[type="checkbox"])) { list-style: none; padding-left: 0; }
.markdown :deep(li:has(> input[type="checkbox"]))::before { display: none; }
.markdown :deep(input[type="checkbox"]) {
  margin-right: 8px; accent-color: #2563eb; transform: translateY(1px);
}

/* 引用块 */
.markdown :deep(blockquote) {
  margin: 16px 0; padding: 8px 20px;
  border-left: 3px solid #d1d5db; border-radius: 0 6px 6px 0;
  background: #f9fafb; color: #4b5563;
}
.markdown :deep(blockquote p:last-child) { margin-bottom: 0; }
.markdown :deep(blockquote blockquote) { margin: 8px 0; border-left-color: #e5e7eb; }

/* 水平线 */
.markdown :deep(hr) { border: none; height: 1px; background: #e5e7eb; margin: 28px 0; }

/* ===== 代码块 ===== */
/* 代码块外层容器 */
.markdown :deep([data-code-block]) {
  position: relative; margin: 16px 0; border-radius: 8px; overflow: hidden;
  background: #1e1e2e; border: 1px solid #313244;
}
/* 语言标签 */
.markdown :deep(.code-lang) {
  position: absolute; top: 0; left: 0; z-index: 2;
  font-size: 11px; color: #7f849c; padding: 6px 16px;
  font-family: ui-monospace, SFMono-Regular, "Cascadia Code", monospace;
  pointer-events: none; text-transform: uppercase; letter-spacing: .5px;
  font-weight: 500;
}
/* 复制按钮 */
.markdown :deep([data-code-copy]) {
  position: absolute; top: 6px; right: 6px; z-index: 2;
  display: flex; align-items: center; justify-content: center;
  width: 30px; height: 30px; border-radius: 6px;
  border: 1px solid #45475a; background: #313244; color: #bac2de;
  cursor: pointer; opacity: 0; transition: all .15s;
}
.markdown :deep([data-code-block]:hover [data-code-copy]) { opacity: 1; }
.markdown :deep([data-code-copy]:hover) { background: #45475a; color: #cdd6f4; border-color: #585b70; }
.markdown :deep([data-check-icon]) { display: none; }
.markdown :deep([data-copied] [data-copy-icon]) { display: none; }
.markdown :deep([data-copied] [data-check-icon]) { display: inline-flex; color: #a6e3a1; }

/* pre 区域 */
.markdown :deep([data-code-block] pre) {
  margin: 0; padding: 38px 16px 14px;
  background: transparent; border: none; border-radius: 0;
  overflow-x: auto; scrollbar-width: thin; scrollbar-color: #45475a transparent;
}
.markdown :deep([data-code-block] pre)::-webkit-scrollbar { height: 6px; }
.markdown :deep([data-code-block] pre)::-webkit-scrollbar-thumb { background: #45475a; border-radius: 3px; }
.markdown :deep([data-code-block] pre)::-webkit-scrollbar-track { background: transparent; }
.markdown :deep([data-code-block] pre)::-webkit-scrollbar-thumb:hover { background: #585b70; }

/* 独立 pre（未走自定义 renderer 的边缘情况） */
.markdown :deep(pre:not([data-code-block] pre)) {
  background: #1e1e2e; padding: 14px 16px; border-radius: 8px;
  border: 1px solid #313244; margin: 16px 0; overflow-x: auto;
}

/* code 文字 */
.markdown :deep(pre code) {
  background: none; padding: 0; border: none;
  font-size: 13.5px; color: #cdd6f4;
  font-family: ui-monospace, SFMono-Regular, "Cascadia Code", "JetBrains Mono", Consolas, monospace;
  white-space: pre; line-height: 1.6;
  tab-size: 2;
}

/* 行内代码 */
.markdown :deep(code) {
  font-family: ui-monospace, SFMono-Regular, "Cascadia Code", Consolas, monospace;
}
.markdown :deep(p code),
.markdown :deep(li code),
.markdown :deep(h1 code), .markdown :deep(h2 code), .markdown :deep(h3 code),
.markdown :deep(h4 code), .markdown :deep(h5 code), .markdown :deep(h6 code),
.markdown :deep(blockquote code), .markdown :deep(td code), .markdown :deep(th code) {
  background: rgba(135,131,120,.15); color: #be185d;
  padding: 2px 6px; border-radius: 4px; font-size: 0.88em;
  font-weight: 500; white-space: nowrap;
}

/* ===== 表格 ===== */
.markdown :deep(.table-wrapper) {
  overflow-x: auto; margin: 16px 0; border-radius: 8px;
  border: 1px solid #e5e7eb; scrollbar-width: thin;
}
.markdown :deep(.table-wrapper)::-webkit-scrollbar { height: 6px; }
.markdown :deep(.table-wrapper)::-webkit-scrollbar-thumb { background: #d1d5db; border-radius: 3px; }
.markdown :deep(table) {
  width: 100%; border-collapse: collapse; font-size: 14px;
}
.markdown :deep(thead) { background: #f9fafb; }
.markdown :deep(th) {
  padding: 10px 16px; text-align: left; vertical-align: top;
  font-weight: 600; color: #374151;
  border-bottom: 2px solid #e5e7eb; white-space: nowrap;
}
.markdown :deep(td) {
  padding: 10px 16px; text-align: left; vertical-align: top;
  border-bottom: 1px solid #f3f4f6; color: #4b5563;
}
.markdown :deep(tbody tr:hover td) { background: #f9fafb; }
.markdown :deep(tbody tr:last-child td) { border-bottom: none; }

/* 图片 */
.markdown :deep(img) {
  max-width: 100%; height: auto; border-radius: 8px;
  margin: 12px 0; display: block;
  box-shadow: 0 1px 3px rgba(0,0,0,.1);
}

/* hljs 覆盖 */
.markdown :deep(.hljs) { background: transparent; padding: 0; color: #cdd6f4; }

/* ===== Prompt ===== */
.prompt-region { flex-shrink: 0; padding-top: 8px; }
.prompt-container { max-width: 800px; margin: 0 auto; padding: 0 16px 20px; }
.prompt-editor {
  display: flex; flex-direction: column; gap: 0;
  background: #fff; border: 1px solid #e5e7eb;
  border-radius: 12px; padding: 10px 14px;
  transition: border-color .15s, box-shadow .15s;
  box-shadow: 0 1px 3px rgba(0,0,0,.04);
}
.prompt-editor:hover { border-color: #d1d5db; }
.prompt-editor.focused {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37,99,235,.08);
}

.prompt-textarea {
  width: 100%; border: none; outline: none; background: transparent;
  font-size: 15px; line-height: 1.6; resize: none; min-height: 26px;
  max-height: 120px; font-family: inherit; color: #1f2937;
}
.prompt-textarea::placeholder { color: #9ca3af; }
.prompt-textarea:disabled { opacity: .5; }

.prompt-actions {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 6px;
}
.prompt-hint { font-size: 12px; color: #9ca3af; }

.send-btn {
  width: 30px; height: 30px; border-radius: 8px; border: 1px solid transparent;
  display: flex; align-items: center; justify-content: center;
  background: transparent; color: #9ca3af; cursor: pointer;
  flex-shrink: 0; transition: all .15s;
}
.send-btn:hover { background: #f3f4f6; color: #6b7280; }
.send-btn.primary { background: #2563eb; color: #fff; }
.send-btn.primary:hover { background: #1d4ed8; }
.send-btn:disabled { opacity: .4; cursor: not-allowed; }
.send-btn.stop { background: #ef4444; color: #fff; }
.send-btn.stop:hover { background: #dc2626; }

/* ===== Smart Scroll ===== */
.jump-to-bottom {
  position: absolute; bottom: 16px; left: 50%; transform: translateX(-50%);
  display: flex; align-items: center; gap: 6px;
  padding: 6px 16px; border-radius: 20px;
  border: 1px solid #e5e7eb; background: #fff; color: #6b7280;
  font-size: 12px; cursor: pointer; box-shadow: 0 2px 8px rgba(0,0,0,.08);
  transition: all .15s; z-index: 10;
}
.jump-to-bottom:hover { background: #f9fafb; color: #1f2937; border-color: #d1d5db; }

@keyframes spin { to { transform: rotate(360deg); } }
</style>
