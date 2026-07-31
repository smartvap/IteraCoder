<template>
  <div class="chat-container">
    <div class="chat-panel">
      <div class="chat-toolbar">
        <span class="toolbar-label">模型：</span>
        <el-select
          v-model="selectedModel"
          placeholder="选择模型"
          size="small"
          style="width: 240px"
          :loading="loadingModels"
        >
          <el-option
            v-for="m in availableModels"
            :key="m.name"
            :label="m.label"
            :value="m.name"
          />
        </el-select>

        <div class="toolbar-actions">
          <el-tooltip :content="appStore.showReasoning ? '关闭思考过程' : '显示思考过程'" placement="top">
            <el-button text size="small" @click="appStore.showReasoning = !appStore.showReasoning">
              <el-icon :class="{ active: appStore.showReasoning }"><View /></el-icon>
            </el-button>
          </el-tooltip>
          <el-button text size="small" @click="refreshModels">
            <el-icon><Refresh /></el-icon> 刷新模型
          </el-button>
          <el-button text size="small" @click="clearChat">
            <el-icon><Delete /></el-icon> 清空对话
          </el-button>
        </div>
      </div>

      <div class="messages-container" ref="messagesRef">
        <div v-for="msg in messages" :key="msg.id" class="message-item">
          <div :class="['msg-row', msg.role]">
            <!--<div v-if="msg.role === 'assistant'" class="msg-avatar agent-avatar">🤖</div>-->
            <div class="msg-bubble">
              <div v-if="msg.role === 'assistant' && msg.reasoning && appStore.showReasoning" class="reasoning-block">
                <details open>
                  <summary class="reasoning-summary">思考过程</summary>
                  <div class="reasoning-content" v-html="renderMarkdown(msg.reasoning)"></div>
                </details>
              </div>
              <div v-if="msg.content" class="msg-content" v-html="renderMarkdown(msg.content)"></div>
              <div v-else-if="msg.role === 'assistant' && isChating" class="thinking-loading">
                <el-icon class="is-loading"><Loading /></el-icon> 思考中...
              </div>
              <div v-if="msg.elapsed" class="msg-stats">{{ fmtTime(msg.elapsed) }} · {{ fmtSpeed(msg.tokenCount, msg.streamDuration) }}</div>
            </div>
            <!-- <div v-if="msg.role === 'user'" class="msg-avatar user-avatar">👤</div> -->
          </div>
        </div>

        <div v-if="messages.length === 0 && !isChating" class="empty-state">
          <!--<div class="empty-icon">🤖</div>-->
          <p class="empty-title">您好！我是 AI 对话助手</p>
          <p class="empty-desc">下方选择模型，输入问题开始对话。</p>
        </div>
      </div>

      <div class="input-area">
        <div class="input-wrapper">
          <el-input
            v-model="inputText"
            type="textarea"
            :rows="3"
            placeholder="请输入您的问题..."
            :disabled="isChating"
            @keydown="handleKeydown"
            resize="vertical"
          />
          <div class="input-actions">
            <span class="input-tip">Enter 发送，Shift + Enter 换行</span>
            <el-button
              v-if="!isChating"
              type="primary"
              :disabled="!inputText.trim()"
              @click="sendMessage"
            >
              发送
            </el-button>
            <el-button
              v-else
              type="danger"
              @click="stopChat"
            >
              停止
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import "highlight.js/styles/github.css"
import { Delete, Refresh, View, Loading } from "@element-plus/icons-vue"
import { useAppStore } from "@/store/app"
import service from "@/http"
import { onMounted, ref, nextTick, watch } from "vue"
import { ElMessage } from "element-plus"
import { marked } from "marked"
import hljs from "highlight.js"

marked.setOptions({ breaks: true, gfm: true })

const renderer2 = new marked.Renderer()
renderer2.code = (code: string, infostring: string | undefined, escaped: boolean) => {
  const lang = infostring
  if (lang && hljs.getLanguage(lang)) {
    return `<pre><code class="hljs language-${lang}">${hljs.highlight(code, { language: lang }).value}</code></pre>`
  }
  return `<pre><code class="hljs">${hljs.highlightAuto(code).value}</code></pre>`
}
marked.use({ renderer: renderer2 })

function renderMarkdown(text: string): string {
  if (!text) return ""
  return marked.parse(text) as string
}

interface ChatMsg {
  id: number
  role: 'user' | 'assistant'
  content: string
  reasoning?: string
  elapsed?: number
  tokenCount?: number
  streamDuration?: number
}

interface OllamaModel {
  name: string
  label: string
  size: number
  family: string
}

const appStore = useAppStore()

const inputText = ref("")
const selectedModel = ref("")
const messages = ref<ChatMsg[]>([])
const isChating = ref(false)
const messagesRef = ref<HTMLElement>()
const availableModels = ref<OllamaModel[]>([])
const loadingModels = ref(false)
let abortController: AbortController | null = null

async function fetchModels() {
  loadingModels.value = true
  try {
    const token = localStorage.getItem("token") || ""
    const isNetwork = appStore.modelType === "network"
    let url = `${service.defaults.baseURL}/chat2/models`
    if (isNetwork && appStore.apiUrl) {
      url += `?apiUrl=${encodeURIComponent(appStore.apiUrl)}&apiKey=${encodeURIComponent(appStore.apiKey || "")}`
    }
    const res = await fetch(url, {
      headers: { Authorization: `Bearer ${token}` }
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const data: OllamaModel[] = await res.json()
    availableModels.value = data
    if (data.length > 0 && !selectedModel.value) {
      selectedModel.value = data[0].name
    }
  } catch (e: any) {
    ElMessage({ type: "error", message: "获取模型列表失败: " + e.message })
  } finally {
    loadingModels.value = false
  }
}

function refreshModels() {
  fetchModels()
}

function clearChat() {
  messages.value = []
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

function stopChat() {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  isChating.value = false
}

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || isChating.value || !selectedModel.value) return

  inputText.value = ""
  messages.value.push({ id: Date.now(), role: 'user', content: text })
  isChating.value = true
  scrollToBottom()

  const assistantMsg: ChatMsg = { id: Date.now() + 1, role: 'assistant', content: '', reasoning: '' }
  messages.value.push(assistantMsg)
  const lastIndex = messages.value.length - 1
  const msgStart = Date.now()
  let streamStart = 0
  let charCount = 0

  const ctrl = new AbortController()
  abortController = ctrl

  const isNetwork = appStore.modelType === "network"
  const queryParams = new URLSearchParams()
  queryParams.set("model", selectedModel.value)
  queryParams.set("message", text)
  queryParams.set("isLocal", isNetwork ? "false" : "true")
  if (isNetwork && appStore.apiUrl) {
    queryParams.set("apiUrl", appStore.apiUrl)
    queryParams.set("apiKey", appStore.apiKey || "")
  }

  try {
    const res = await fetch(service.defaults.baseURL + "/chat2/stream?" + queryParams.toString(), {
      method: "POST",
      headers: {
        Authorization: `Bearer ${localStorage.getItem("token") || ""}`
      },
      signal: ctrl.signal,
    })
    if (!res.ok) {
      ElMessage({ type: "error", message: `请求失败: HTTP ${res.status}` })
      messages.value[lastIndex].content = "抱歉，请求出错，请稍后重试。"
      finish()
      return
    }
    const reader = res.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ""
    let currentEvent = ""
    let dataLines: string[] = []

    function flushData() {
      if (!currentEvent || dataLines.length === 0) return
      const joined = dataLines.join("\n")
      if (currentEvent === "reasoning") {
        messages.value[lastIndex].reasoning = (messages.value[lastIndex].reasoning || "") + joined
      } else {
        if (!streamStart) streamStart = Date.now()
        messages.value[lastIndex].content += joined
        charCount += joined.length
      }
      scrollToBottom()
      dataLines = []
    }

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      while (true) {
        const idx = buffer.indexOf("\n")
        if (idx < 0) break
        const line = buffer.slice(0, idx).replace(/\r$/, "")
        buffer = buffer.slice(idx + 1)
        if (!line) {
          flushData()
          currentEvent = ""
          continue
        }
        if (line.startsWith("event:")) {
          flushData()
          currentEvent = line.slice(6).replace(/^\s+|\s+$/g, "")
        } else if (line.startsWith("data:")) {
          const data = line[5] === " " ? line.slice(6) : line.slice(5)
          if (data === "[DONE]") break
          dataLines.push(data)
        }
      }
      if (buffer.includes("[DONE]")) break
    }
    if (buffer.trim()) {
      const line = buffer.trim()
      if (line.startsWith("data:")) {
        const data = line[5] === " " ? line.slice(6) : line.slice(5)
        if (data !== "[DONE]") {
          dataLines.push(data)
          flushData()
        }
      }
    } else {
      flushData()
    }
  } catch (err: any) {
    if (err.name !== "AbortError") {
      const msg = messages.value[lastIndex]
      if (msg && msg.content === "") {
        msg.content = "抱歉，请求出错，请稍后重试。"
      }
    }
  } finally {
    finish()
  }

  function finish() {
    isChating.value = false
    abortController = null
    const now = Date.now()
    messages.value[lastIndex].elapsed = now - msgStart
    messages.value[lastIndex].tokenCount = charCount
    messages.value[lastIndex].streamDuration = streamStart ? now - streamStart : 0
    scrollToBottom()
  }
}

function fmtTime(ms: number): string {
  if (ms < 1000) return ms + " ms"
  return (ms / 1000).toFixed(1) + " s"
}

function calcTokens(chars: number): number {
  return Math.ceil(chars / 2)
}

function fmtSpeed(chars: number = 0, streamMs: number = 0): string {
  if (!chars || !streamMs) return ""
  const tokens = calcTokens(chars)
  const tps = (tokens / (streamMs / 1000)).toFixed(1)
  return tps + " token/s"
}

function scrollToBottom() {
  nextTick(() => {
    const el = messagesRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

watch(() => messages.value.length, () => scrollToBottom())

onMounted(() => {
  fetchModels()
  scrollToBottom()
})
</script>

<style scoped lang="less">
.chat-container {
  display: flex;
  height: 100%;
  padding: 6px 8px;
}

.chat-panel {
  display: flex;
  flex-direction: column;
  width: 100%;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  overflow: hidden;
}

.chat-toolbar {
  display: flex;
  align-items: center;
  padding: 10px 20px;
  border-bottom: 1px solid #e4e7ed;
  flex-shrink: 0;

  .toolbar-label {
    font-size: 13px;
    color: #606266;
    margin-right: 8px;
    flex-shrink: 0;
  }

  .toolbar-actions {
    margin-left: auto;
    display: flex;
    gap: 4px;
  }
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  background: #f9fafb;
  width: 100%;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 12px;

  .empty-icon { font-size: 64px; }
  .empty-title { font-size: 16px; color: #303133; font-weight: 600; }
  .empty-desc { font-size: 14px; color: #909399; }
}

.message-item {
  margin-bottom: 20px;
}

.msg-row {
  display: flex;
  gap: 12px;
  max-width: 90%;

  &.user {
    flex-direction: row-reverse;
    margin-left: auto;
  }
  &.assistant {
    margin-right: auto;
  }
}

.msg-bubble {
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.6;
  word-break: break-word;
  font-size: 14px;
}

.msg-content {
  /* 不使用 pre-wrap，由 marked 的 <br> 和 <p> 标签控制换行 */
}

.msg-content :deep(p) {
  margin: 8px 0;
}

.msg-content :deep(p:first-child) {
  margin-top: 0;
}

.msg-content :deep(p:last-child) {
  margin-bottom: 0;
}

.msg-row.user .msg-bubble {
  background: #409eff;
  color: white;
  border-top-right-radius: 4px;
}

.msg-row.assistant .msg-bubble {
  background: #fff;
  color: #303133;
  border: 1px solid #e4e7ed;
  border-top-left-radius: 4px;
}

.msg-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;

  &.user-avatar { background: #ecf5ff; }
  &.agent-avatar { background: #f0f9eb; }
}

.processing { color: #909399; }

.msg-bubble :deep(pre) {
  margin-top: 12px;
  margin-bottom: 16px;
  background: #f6f8fa;
  border-radius: 6px;
  padding: 12px 16px;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.6;
  border: 0.5px solid #e4e7ed;
  scrollbar-width: none;
  &::-webkit-scrollbar { display: none; }
}

.msg-bubble :deep(code) {
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
  font-size: 13px;
}

.msg-bubble :deep(:not(pre) > code) {
  color: #c7254e;
  font-weight: 500;
  background: #f0f2f5;
  padding: 2px 5px;
  border-radius: 3px;
}

.msg-bubble :deep(pre code) {
  background: none;
  padding: 0;
  color: inherit;
}

.msg-content {
  min-width: 0;
  max-width: 100%;
  overflow-wrap: break-word;
  line-height: 1.6;
}

.msg-content :deep(p) {
  margin-bottom: 12px;
}

.msg-content :deep(p:last-child) {
  margin-bottom: 0;
}

.msg-content :deep(h1),
.msg-content :deep(h2),
.msg-content :deep(h3),
.msg-content :deep(h4),
.msg-content :deep(h5),
.msg-content :deep(h6) {
  font-size: 14px;
  color: #303133;
  font-weight: 600;
  margin-top: 0;
  margin-bottom: 16px;
  line-height: 1.5;
}

.msg-content :deep(h1) { font-size: 1.4em; }
.msg-content :deep(h2) { font-size: 1.25em; }
.msg-content :deep(h3) { font-size: 1.1em; }

.msg-content :deep(strong),
.msg-content :deep(b) {
  color: #303133;
  font-weight: 600;
}

.msg-content :deep(em),
.msg-content :deep(i) {
  font-style: italic;
}

.msg-content :deep(ul),
.msg-content :deep(ol) {
  margin-top: 8px;
  margin-bottom: 12px;
  margin-left: 0;
  padding-left: 32px;
  list-style-position: outside;
}

.msg-content :deep(ul) { list-style-type: disc; }
.msg-content :deep(ol) { list-style-type: decimal; padding-left: 2.25rem; }

.msg-content :deep(li) {
  margin-bottom: 6px;
  line-height: 1.6;
}

.msg-content :deep(li > p:first-child) {
  display: inline;
  margin: 0;
}

.msg-content :deep(li > p + p) {
  display: block;
  margin-top: 0.5rem;
}

.msg-content :deep(li::marker) {
  color: #909399;
}

.msg-content :deep(li > ul),
.msg-content :deep(li > ol) {
  margin-top: 0.25rem;
  margin-bottom: 0.25rem;
  padding-left: 1rem;
}

.msg-content :deep(blockquote) {
  border-left: 2px solid #d9d9d9;
  margin: 16px 0;
  padding-left: 12px;
  color: #909399;
  font-style: normal;
}

.msg-content :deep(hr) {
  border: none;
  height: 0;
  margin: 24px 0;
}

.msg-content :deep(a) {
  color: #409eff;
  text-decoration: none;
}

.msg-content :deep(a:hover) {
  text-decoration: underline;
  text-underline-offset: 2px;
}

.msg-content :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 16px 0;
  font-size: 13px;
  display: block;
  overflow-x: auto;
}

.msg-content :deep(th),
.msg-content :deep(td) {
  border-bottom: 1px solid #e4e7ed;
  padding: 10px 12px;
  text-align: left;
  vertical-align: top;
}

.msg-content :deep(th) {
  color: #303133;
  font-weight: 600;
  border-bottom: 1px solid #c0c4cc;
}

.msg-content :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 4px;
  margin: 12px 0;
  display: block;
}

.reasoning-block {
  margin-bottom: 8px;
  border-left: 2px solid #d9d9d9;
  padding-left: 10px;
}

.reasoning-summary {
  font-size: 13px;
  color: #909399;
  cursor: pointer;
  user-select: none;
}

.reasoning-content {
  margin-top: 6px;
  font-size: 13px;
  color: #666;
  line-height: 1.5;
}

.thinking-loading {
  color: #909399;
  font-size: 13px;
  margin-bottom: 8px;
}

.active { color: #409eff; }

.msg-stats {
  font-size: 12px;
  color: #bbb;
  margin-top: 4px;
  text-align: right;
}

.input-area {
  padding: 16px 20px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
  flex-shrink: 0;
}

.input-wrapper {
  max-width: 98%;
  margin: 0 auto;
}

.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}

.input-tip {
  font-size: 12px;
  color: #909399;
}
</style>
