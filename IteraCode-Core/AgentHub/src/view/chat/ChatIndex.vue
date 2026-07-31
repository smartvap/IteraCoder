<template>
  <div class="chat-index">
    <div class="chat-header">
      <h1 class="chat-title">本地大模型对话</h1>
      <div class="header-actions">
        <el-tooltip content="导出对话日志" placement="bottom">
          <el-button text size="small" @click="exportLog">导出日志</el-button>
        </el-tooltip>
        <el-select
          v-model="selectedModel"
          placeholder="选择模型"
          size="small"
          style="width: 240px"
          :loading="loadingModels"
          :disabled="isStreaming"
        >
          <el-option
            v-for="m in availableModels"
            :key="m.name"
            :label="m.label"
            :value="m.name"
          />
        </el-select>
        <el-tooltip content="刷新模型列表" placement="bottom">
          <el-button text size="small" @click="fetchModels" :loading="loadingModels">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
    </div>

    <div class="chat-messages" ref="messagesRef">
      <div v-if="messages.length === 0" class="empty-state">
        <div class="empty-icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#c0c0c0" stroke-width="1.5">
            <path d="M12 3c.132 0 .263 0 .393 0a7.5 7.5 0 0 0 7.92 12.446a9 9 0 1 1 -8.313 -12.454z"/>
            <path d="M17 4a2 2 0 0 0 2 2a2 2 0 0 0 -2 2a2 2 0 0 0 -2 -2a2 2 0 0 0 2 -2"/>
          </svg>
        </div>
        <p class="empty-title">开始对话</p>
        <p class="empty-desc">输入研发需求自动启动智能体工作流，或输入其他问题正常对话</p>
      </div>

      <template v-for="(msg, i) in messages" :key="i">
        <div v-if="msg.role === 'user'" class="message-user">
          <div class="message-user-content">{{ msg.content }}</div>
        </div>
        <div v-else class="message-assistant">
          <div v-if="msg.role === 'assistant'" class="msg-avatar agent-avatar">AI</div>
          <div v-if="msg.loading && !msg.workflow" class="message-loading">
            <span class="loading-spinner"></span>
            <span>思考中...</span>
          </div>

          <!-- ========== 工作流卡片 ========== -->
          <div v-else-if="msg.workflow" class="workflow-card" :class="{ 'wf-collapsed': msg.workflow._collapsed }">
            <div class="wf-header">
              <span class="wf-title">
                智能体工作流
                <template v-if="msg.workflow._round">（第{{ msg.workflow._round }}轮）</template>
              </span>
              <div style="display:flex;align-items:center;gap:8px;">
                <span class="wf-status" :class="'wf-status-' + msg.workflow.status.toLowerCase()">
                  {{ statusText(msg.workflow.status) }}
                </span>
                <!-- 已完成轮次：折叠/展开切换 -->
                <span
                  v-if="msg.workflow._finalStatus"
                  class="wf-collapse-toggle"
                  @click="msg.workflow._collapsed = !msg.workflow._collapsed"
                >{{ msg.workflow._collapsed ? '展开详情 \u25BC' : '收起 \u25B2' }}</span>
              </div>
            </div>

            <!-- 折叠状态：步骤摘要 + 可展开查看内容 -->
            <template v-if="msg.workflow._collapsed">
              <div class="wf-step-summary">
                <span v-for="(step, si) in msg.workflow.progress" :key="si" class="wf-step-tag" :class="{ done: step.done }">
                  {{ step.done ? '\u2713' : step.active ? '\u22EF' : '\u25CB' }} {{ step.name }}
                </span>
              </div>
              <div v-if="msg.workflow._round && msg.workflow._round > 1" class="wf-round-label">
                🔄 第 {{ msg.workflow._round }} 轮 · {{ msg.workflow.status === 'COMPLETED' ? '已通过' : msg.workflow.status === 'TERMINATED' ? '已终止' : '已完成' }}
              </div>
              <!-- 折叠状态下的内容预览（可展开） -->
              <div v-if="msg.workflow.state" class="wf-collapsed-content">
                <!-- 需求拆解 -->
                <div v-if="msg.workflow.state.decomposition_result" class="wf-collapsed-block">
                  <div class="wf-collapsed-block-header" @click="toggleReviewSection(msg, 'decomp')">
                    <span class="wf-toggle-icon">{{ msg.workflow._showDecomp ? '\u25BC' : '\u25B6' }}</span>
                    <span>📋 需求拆解结果</span>
                  </div>
                  <div v-if="msg.workflow._showDecomp" class="wf-collapsed-block-body markdown"
                    v-html="renderMarkdown(String(msg.workflow.state.decomposition_result).slice(0, 2000))"></div>
                </div>
                <!-- 并行推理汇总 -->
                <div v-if="msg.workflow.state.parallel_reasoning_result" class="wf-collapsed-block">
                  <div class="wf-collapsed-block-header" @click="toggleReviewSection(msg, 'reason')">
                    <span class="wf-toggle-icon">{{ msg.workflow._showReason ? '\u25BC' : '\u25B6' }}</span>
                    <span>🧠 并行推理汇总</span>
                  </div>
                  <div v-if="msg.workflow._showReason" class="wf-collapsed-block-body markdown"
                    v-html="renderMarkdown(String(msg.workflow.state.parallel_reasoning_result).slice(0, 2000))"></div>
                </div>
                <!-- 各模型推理结果 -->
                <div v-for="(ck, cki) in getReasoningKeys(msg)" :key="cki" class="wf-collapsed-block">
                  <div class="wf-collapsed-block-header" @click="toggleReviewSection(msg, ck)">
                    <span class="wf-toggle-icon">{{ getReviewToggle(msg, ck) ? '\u25BC' : '\u25B6' }}</span>
                    <span>🤖 {{ reasoningLabel(ck) }}</span>
                  </div>
                  <div v-if="getReviewToggle(msg, ck)" class="wf-collapsed-block-body markdown"
                    v-html="renderMarkdown(String(msg.workflow.state[ck]).slice(0, 2000))"></div>
                </div>
              </div>
            </template>

            <!-- 展开状态：完整内容 -->
            <template v-else>
            <!-- 历史轮次摘要 -->
            <div v-if="getHistoryRounds(i).length > 0" class="wf-history">
              <div class="wf-history-header" @click="msg.workflow._showHistory = !msg.workflow._showHistory">
                <span class="wf-toggle-icon">{{ msg.workflow._showHistory ? '\u25BC' : '\u25B6' }}</span>
                <span>历史轮次 ({{ getHistoryRounds(i).length }} 轮)</span>
              </div>
              <div v-if="msg.workflow._showHistory" class="wf-history-body">
                <div v-for="(hr, hi) in getHistoryRounds(i)" :key="hi" class="wf-history-item">
                  <div class="wf-history-round">第 {{ hr.round }} 轮</div>
                  <div class="wf-history-decision">
                    决策：<strong>{{ hr.label }}</strong>
                    <span v-if="hr.comment" class="wf-history-comment"> | {{ hr.comment }}</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- 进度步骤条 -->
            <div class="wf-progress">
              <div
                v-for="(step, si) in msg.workflow.progress"
                :key="si"
                class="wf-step"
                :class="{ done: step.done, active: step.active }"
                :data-step-key="step.key"
              >
                <span class="wf-step-icon">{{ step.done ? '\u2713' : step.active ? '\u22EF' : '\u25CB' }}</span>
                <span class="wf-step-name">{{ step.name }}</span>
                <span v-if="step._time" class="wf-step-time">{{ step._time }}</span>
                <!-- 步骤内容体 -->
                <div v-if="step._content" class="wf-step-body" :data-step="step.key">
                  <pre class="wf-body-pre">{{ step._content }}</pre>
                  <!-- 思考中提示（活跃步骤无新内容时） -->
                  <div v-if="step.active && step._stalling" class="wf-thinking">
                    ⏳ 思考中<span class="thinking-dot">.</span><span class="thinking-dot">.</span><span class="thinking-dot">.</span>
                  </div>
                </div>
                <div v-else-if="step.active" class="wf-step-body" :data-step="step.key">
                  <span class="wf-thinking">思考中<span class="thinking-dot">.</span><span class="thinking-dot">.</span><span class="thinking-dot">.</span></span>
                </div>
              </div>
            </div>

            <!-- 审核内容区（WAITING_REVIEW 时逐条展示） -->
            <div v-if="msg.workflow.status === 'WAITING_REVIEW' && msg.workflow.state" class="wf-review-area">
              <div class="wf-review-label">需求拆解项（可逐条选择调整）</div>

              <!-- 逐条需求项 -->
              <div v-for="(item, di) in getDecompItems(msg)" :key="di" class="wf-item-row" :class="{ selected: item._selected }">
                <el-checkbox v-model="item._selected" class="wf-item-check" />
                <div class="wf-item-body">
                  <div class="wf-item-title">{{ item.title }}</div>
                  <div v-if="item.desc" class="wf-item-desc">{{ item.desc }}</div>
                </div>
                <el-button size="small" text type="primary" @click="editItem(msg, di)">调整</el-button>
              </div>

              <div v-if="getDecompItems(msg).length === 0" style="font-size:12px;color:#999;text-align:center;padding:10px;">
                未能解析需求拆解项，请查看原始结果
              </div>

              <!-- 原始结果（折叠查看） -->
              <div v-if="msg.workflow.state.decomposition_result" class="wf-review-block" style="margin-top:8px;">
                <div class="wf-review-block-header" @click="toggleReviewSection(msg, 'decomp')">
                  <span class="wf-toggle-icon">{{ msg.workflow._showDecomp ? '\u25BC' : '\u25B6' }}</span>
                  <span>查看原始拆解结果</span>
                </div>
                <div v-if="msg.workflow._showDecomp" class="wf-review-block-body markdown"
                  v-html="renderMarkdown(String(msg.workflow.state.decomposition_result))"></div>
              </div>

              <!-- 推理结果（折叠） -->
              <div v-if="msg.workflow.state.parallel_reasoning_result" class="wf-review-block">
                <div class="wf-review-block-header" @click="toggleReviewSection(msg, 'reason')">
                  <span class="wf-toggle-icon">{{ msg.workflow._showReason ? '\u25BC' : '\u25B6' }}</span>
                  <span>并行推理汇总</span>
                </div>
                <div v-if="msg.workflow._showReason" class="wf-review-block-body markdown"
                  v-html="renderMarkdown(String(msg.workflow.state.parallel_reasoning_result))"></div>
              </div>
              <div v-for="(ck, cki) in getReasoningKeys(msg)" :key="cki" class="wf-review-block">
                <div class="wf-review-block-header" @click="toggleReviewSection(msg, ck)">
                  <span class="wf-toggle-icon">{{ getReviewToggle(msg, ck) ? '\u25BC' : '\u25B6' }}</span>
                  <span>{{ reasoningLabel(ck) }}</span>
                </div>
                <div v-if="getReviewToggle(msg, ck)" class="wf-review-block-body markdown"
                  v-html="renderMarkdown(String(msg.workflow.state[ck]))"></div>
              </div>
            </div>

            <!-- 审核操作区域（始终显示，参考 workflow.html 的 reviewSection） -->
            <div class="wf-review-bar">
              <div class="wf-review-alert">
                <span class="wf-review-icon">{{ msg.workflow.status === 'WAITING_REVIEW' ? '\u23F8' : isWorkflowDone(msg) ? '\u2705' : '\uD83D\uDD04' }}</span>
                <div>
                  <strong>
                    <template v-if="msg.workflow.status === 'WAITING_REVIEW'">等待人工审核</template>
                    <template v-else-if="isWorkflowDone(msg)">工作流已结束</template>
                    <template v-else>工作流执行中</template>
                  </strong>
                  <div class="wf-review-sub">
                    <template v-if="msg.workflow.status === 'WAITING_REVIEW'">请审核以上推理结果后选择操作</template>
                    <template v-else-if="isWorkflowDone(msg)">不再接受新操作，可继续对话</template>
                    <template v-else>到达审核节点后将自动暂停</template>
                  </div>
                </div>
              </div>
              <el-input
                v-model="msg.workflow._comment"
                type="textarea"
                :rows="2"
                placeholder="输入审核备注（可选）：通过/驳回的理由..."
                size="small"
                class="wf-comment"
                :disabled="msg.workflow.status !== 'WAITING_REVIEW'"
              />
              <!-- 按钮区：代码生成完成时显示下载/部署，否则显示审核按钮 -->
              <div v-if="msg.workflow._codegenDone" class="wf-btns">
                <el-button type="primary" size="small" @click="downloadProject(i)">下载</el-button>
                <el-button type="success" size="small" @click="deployProject(i)">运行部署</el-button>
              </div>
              <div v-else class="wf-btns">
                <el-button type="success" size="small"
                  :disabled="msg.workflow.status !== 'WAITING_REVIEW'"
                  @click="resumeWorkflow(i, 'APPROVED')">✓ 通过</el-button>
                <el-button type="warning" size="small"
                  :disabled="msg.workflow.status !== 'WAITING_REVIEW'"
                  @click="resumeWorkflow(i, 'SENT_BACK')">↩ 继续修复</el-button>
                <el-button type="danger" size="small"
                  :disabled="isWorkflowDone(msg)"
                  @click="resumeWorkflow(i, 'TERMINATED')">✗ 结束</el-button>
              </div>
              <div class="wf-action-hint">
                <template v-if="msg.workflow._codegenDone">下载 → 保存到浏览器 | 运行部署 → 服务器 Docker 部署</template>
                <template v-else>通过 → 工作流完成 | 继续修复 → 重新需求拆解 | 结束 → 终止流程</template>
              </div>
            </div>

            <!-- 终态信息 -->
            <div v-if="isWorkflowDone(msg)" class="wf-final-status" :class="'wf-final-' + msg.workflow.status.toLowerCase()">
              <strong>{{ finalStatusIcon(msg) }} 工作流{{ msg.workflow.status }}</strong>
              <div v-if="msg.workflow.state?.workflow_message" class="wf-final-msg">
                {{ msg.workflow.state.workflow_message }}
              </div>
            </div>
            </template>
          </div>

          <div v-else class="message-content markdown" v-html="renderMarkdown(msg.content)"></div>
        </div>
      </template>
    </div>

    <div class="chat-input-area">
      <div class="input-container">
        <textarea
          ref="textareaRef"
          v-model="inputText"
          class="chat-textarea"
          rows="3"
          placeholder="请输入您的问题..."
          :disabled="isStreaming"
          @keydown="handleKeydown"
        />
        <div class="input-actions">
          <button v-if="isStreaming" class="send-btn stop" @click="stopChat">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
            停止
          </button>
          <button v-else class="send-btn" :class="{ primary: canSend }" :disabled="!canSend" @click="handleSend">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 19V5M5 12l7-7 7 7"/></svg>
            发送
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import "highlight.js/styles/atom-one-dark.css"
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from "vue"
import { marked } from "marked"
import hljs from "highlight.js"
import { BASE_URL } from "@/http/config"
import { useAppStore } from "@/store/app"
import { ElMessage, ElMessageBox } from "element-plus"
import JSZip from "jszip"
import { saveAs } from "file-saver"
import { Refresh } from "@element-plus/icons-vue"
import service from "@/http"
import {
  routeChatMessage,
  startWorkflowApi,
  getWorkflowState,
  resumeWorkflowApi,
  type WorkflowResumeRequest,
} from "@/api/WorkflowApi"

interface WorkflowStep {
  key: string; name: string; done: boolean; active: boolean
  _content?: string; _stalling?: boolean; _expanded?: boolean; _time?: string
}

interface WorkflowInfo {
  threadId: string; status: string
  progress: WorkflowStep[]
  state: Record<string, any> | null
  _comment: string; _showDecomp: boolean; _showReason: boolean
  _finalStatus?: boolean; _round?: number; _groupId?: string
  [key: string]: any
}

interface ChatMessage { role: "user" | "assistant"; content: string; loading?: boolean; workflow?: WorkflowInfo }

interface ModelInfo { name: string; label: string; source: string; family: string }

const appStore = useAppStore()
const messages = ref<ChatMessage[]>([])
const inputText = ref("")
const isStreaming = ref(false)
const selectedModel = ref("")
const availableModels = ref<ModelInfo[]>([])
const loadingModels = ref(false)
const messagesRef = ref<HTMLDivElement>()
const textareaRef = ref<HTMLTextAreaElement>()
let abortController: AbortController | null = null

const WORKFLOW_STEPS = [
  { key: "requirement", name: "工作流初始化" },
  { key: "decomposition_result", name: "需求拆解" },
  { key: "_reasoning", name: "并行推理" },
  { key: "parallel_reasoning_result", name: "合并推理结果" },
  { key: "_review", name: "等待人工审核" },
]

const STATUS_TEXT: Record<string, string> = {
  RUNNING: "执行中", WAITING_REVIEW: "等待审核", COMPLETED: "已完成", TERMINATED: "已终止", FAILED: "已失败",
}

// SSE 连接管理
const sseConnections = new Map<number, { es: EventSource | null; poll: any; delayTimer: any }>()

// ==================== 模型列表 ====================
async function fetchModels() {
  loadingModels.value = true
  try {
    const token = localStorage.getItem("token") || ""
    const params = new URLSearchParams()
    if (appStore.modelType === "network" && appStore.apiUrl) { params.set("apiUrl", appStore.apiUrl); if (appStore.apiKey) params.set("apiKey", appStore.apiKey) }
    else if (appStore.ollamaUrl) { params.set("ollamaUrl", appStore.ollamaUrl) }
    const res = await fetch(`${service.defaults.baseURL}/chat/models?${params}`, { headers: { Authorization: `Bearer ${token}` } })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    availableModels.value = await res.json()
    const currentExists = availableModels.value.some(m => m.name === selectedModel.value)
    // network 模式：自动选择 Settings 中配置的远程模型
    const savedSettings = JSON.parse(localStorage.getItem("agent-hub-app-settings") || "{}")
    const remoteModel = (savedSettings as any).remoteModel || ""
    if (appStore.modelType === "network" && remoteModel) {
      const remoteExists = availableModels.value.some(m => m.name === remoteModel)
      selectedModel.value = remoteExists ? remoteModel : (availableModels.value[0]?.name || "")
    } else if (availableModels.value.length > 0 && !currentExists) {
      selectedModel.value = availableModels.value[0].name
    }
  } catch (e: any) { console.error("获取模型列表失败:", e.message) }
  finally { loadingModels.value = false }
}

const canSend = computed(() => !isStreaming.value && inputText.value.trim().length > 0)

// ==================== Markdown ====================
marked.setOptions({ breaks: false, gfm: true })
marked.use({ renderer: { code(code: string, infostring: string | undefined): string {
  const lang = infostring && hljs.getLanguage(infostring) ? infostring : ""
  const highlighted = lang ? hljs.highlight(code, { language: lang }).value : hljs.highlightAuto(code).value
  const langLabel = lang ? `<span class="code-lang">${lang}</span>` : ""
  return `<div data-code-block="1">${langLabel}<pre><code class="hljs language-${lang || "plaintext"}">${highlighted}</code></pre></div>`
}}})

function renderMarkdown(text: string): string {
  if (!text) return ""
  try {
    let processed = text
    const fenceCount = (processed.match(/^`{3,}/gm) || []).length
    if (fenceCount % 2 !== 0) processed += "\n```"
    return marked.parse(processed) as string
  } catch (e) { return text }
}

function scrollToBottom() { nextTick(() => { const el = messagesRef.value; if (el) el.scrollTop = el.scrollHeight }) }
function handleKeydown(e: KeyboardEvent) { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); if (canSend.value) handleSend() } }

// ==================== 聊天发送 ====================
async function handleSend() {
  const text = inputText.value.trim()
  if (!text || isStreaming.value) return
  inputText.value = ""
  messages.value.push({ role: "user", content: text })
  try {
    const routeResult = await routeChatMessage(text)
    if (routeResult.type === "workflow") {
      await startWorkflowFlow(text)
    } else {
      // chat 模式需要选模型
      if (!selectedModel.value) { ElMessage.warning("请先选择模型"); return }
      messages.value.push({ role: "assistant", content: "", loading: true })
      scrollToBottom()
      await streamReply(text, messages.value.length - 1)
    }
  } catch (err: any) { if (err.name !== "AbortError") ElMessage.error("请求失败：" + (err.message || "未知错误")) }
}

async function streamReply(userText: string, idx: number) {
  const params = `model=${encodeURIComponent(selectedModel.value || "gemma4:latest")}&message=${encodeURIComponent(userText)}`
  const apiKey = appStore.modelType === "network" ? (appStore.apiKey || "") : ""
  const apiUrl = appStore.modelType === "network" ? (appStore.apiUrl || "") : ""
  const keyParam = apiKey ? `&apiKey=${encodeURIComponent(apiKey)}` : ""
  const urlParam = apiUrl ? `&baseUrl=${encodeURIComponent(apiUrl)}` : ""
  const reqUrl = `${BASE_URL}/chat/stream?${params}${keyParam}${urlParam}`
  isStreaming.value = true; abortController = new AbortController()
  try {
    const response = await fetch(reqUrl, { headers: { Authorization: `Bearer ${localStorage.getItem("token") || ""}` }, signal: abortController.signal })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const reader = response.body?.getReader(); if (!reader) throw new Error("No reader")
    const decoder = new TextDecoder(); let buffer = ""
    while (true) {
      const { done, value } = await reader.read(); if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split("\n"); buffer = lines.pop() || ""
      let currentEvent = ""
      for (const raw of lines) {
        const line = raw.replace(/\r$/, "")
        if (!line) { currentEvent = ""; continue }
        if (line.startsWith("event:")) currentEvent = line.slice(6).trim()
        else if (line.startsWith("data:")) {
          const data = line[5] === " " ? line.slice(6) : line.slice(5)
          if (data === "[DONE]") break
          if (currentEvent === "content" || !currentEvent) { messages.value[idx].content += data; messages.value[idx].loading = false; scrollToBottom() }
        }
      }
    }
    if (buffer.trim()) { const line = buffer.trim(); if (line.startsWith("data:")) { const data = line[5] === " " ? line.slice(6) : line.slice(5); if (data !== "[DONE]") { messages.value[idx].content += data; messages.value[idx].loading = false } } }
    messages.value[idx].loading = false
  } catch (err: any) {
    if (err.name !== "AbortError") { messages.value[idx].content = ""; messages.value[idx].loading = false; ElMessage.error("请求失败，请稍后再试") }
    else { messages.value[idx].content = ""; messages.value[idx].loading = false; ElMessage.info("已停止生成") }
  } finally { isStreaming.value = false; abortController = null; scrollToBottom() }
}

function stopChat() {
  abortController?.abort()
}

// ==================== 日志导出 ====================

function exportLog() {
  const now = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19)
  const lines: string[] = [
    "=".repeat(60),
    "AgentHub 对话日志",
    "导出时间: " + new Date().toLocaleString(),
    "消息数量: " + messages.value.length,
    "=".repeat(60) + "\n",
  ]

  messages.value.forEach((msg, i) => {
    const num = i + 1
    if (msg.role === "user") {
      lines.push(`--- 第 ${num} 条 (用户) ---`)
      lines.push(msg.content); lines.push("")
    } else if (msg.workflow) {
      lines.push(`--- 第 ${num} 条 (工作流) ---`)
      lines.push("状态: " + statusText(msg.workflow.status))
      lines.push("轮次: " + (msg.workflow._round || 1))
      if (msg.workflow.progress?.length) {
        msg.workflow.progress.forEach(step => {
          const done = step.done ? "\u2713" : step.active ? "\u22EF" : "\u25CB"
          lines.push(`  ${done} ${step.name}`)
          if (step._content) lines.push("    " + step._content.replace(/\n/g, "\n    "))
        })
      }
      if (msg.workflow.state?.decomposition_result) {
        lines.push("\n需求拆解结果:"); lines.push(String(msg.workflow.state.decomposition_result))
      }
      if (msg.workflow.state?.parallel_reasoning_result) {
        lines.push("\n推理汇总:"); lines.push(String(msg.workflow.state.parallel_reasoning_result))
      }
      if (msg.workflow.state?.generated_files) {
        lines.push("\n生成的文件:")
        ;(msg.workflow.state.generated_files as string[]).forEach(f => lines.push("  " + f))
      }
      lines.push("")
    } else if (msg.content) {
      lines.push(`--- 第 ${num} 条 (AI回复) ---`)
      lines.push(msg.content); lines.push("")
    }
  })

  lines.push("=".repeat(60) + "\n")
  const logText = lines.join("\n")

  // 浏览器下载
  const blob = new Blob([logText], { type: "text/plain;charset=utf-8" })
  saveAs(blob, `chat-log-${now}.txt`)

  // 同时保存到服务端 logs 目录
  service.post("/project/log/save", { content: logText }).then(() => {
    ElMessage.success("日志已导出并保存到服务端 logs/ 目录")
  }).catch(() => {
    ElMessage.success("日志已下载到浏览器")
  })
}

// ==================== 工作流进度 Step UI ====================

function hasReasoningOutput(state: Record<string, any>): boolean {
  return Object.keys(state).some(k => /^reasoning_.+_result$/.test(k) && state[k])
}

// 完全参考 workflow.html 的 checkStepDone
function wfCheckStepDone(state: Record<string, any>, step: { key: string }): boolean {
  if (step.key === "_review") {
    if (state["workflow_status"] === "WAITING_REVIEW") return true
    const d = state["review_decision"]
    return d === "APPROVED" || d === "TERMINATED" || state["workflow_status"] === "COMPLETED"
  }
  if (step.key === "requirement") {
    return !!(state["workflow_message"] || state["decomposition_result"])
  }
  if (step.key === "decomposition_result") {
    const dr = state["decomposition_result"]
    if (dr && typeof dr === "string" && dr.startsWith("[NOT_DEV_REQ]")) return true
    return dr && dr.length > 200
  }
  if (step.key === "_reasoning") {
    return !!state["parallel_reasoning_result"]
  }
  if (step.key === "parallel_reasoning_result") {
    return state["workflow_status"] === "WAITING_REVIEW" || !!state["review_content"]
  }
  return state[step.key] != null
}

// 完全参考 workflow.html 的 getStepContent
function wfGetStepContent(state: Record<string, any>, step: { key: string }): string {
  const key = step.key
  if (key === "requirement") {
    const req = state["requirement"]
    if (!req || !req.trim()) return ""
    let content = "[研发需求]\n" + req
    const feedback = state["review_feedback"]
    if (feedback && feedback.trim()) content += "\n\n[审核备注]\n" + feedback
    return content
  }
  if (key === "decomposition_result") return state["decomposition_result"] || ""
  if (key === "_reasoning") {
    const parts: string[] = []
    for (const k of Object.keys(state).sort()) {
      if (/^reasoning_.+_result$/.test(k) && state[k]) {
        parts.push("===== " + reasoningLabel(k) + " =====\n" + state[k])
      }
    }
    return parts.join("\n\n")
  }
  if (key === "parallel_reasoning_result") return state["parallel_reasoning_result"] || ""
  if (key === "_review") return state["review_content"] || ""
  return ""
}

// 参考 workflow.html 的 resolveActiveStepKey
function resolveActiveStepKey(state: Record<string, any>, sseLastUpdate: Record<string, number>, lastSseKey: string | null): string | null {
  const ws = state["workflow_status"] || ""
  if (ws === "WAITING_REVIEW" || ws === "COMPLETED" || ws === "TERMINATED" || ws === "FAILED") return null

  const now = Date.now()
  for (const k of Object.keys(sseLastUpdate)) {
    if (/^reasoning_.+_result$/.test(k) && now - (sseLastUpdate[k] || 0) < 15000) {
      return "_reasoning"
    }
  }
  if (lastSseKey === "decomposition_result" && now - (sseLastUpdate["decomposition_result"] || 0) < 15000) {
    return "decomposition_result"
  }
  if (state["decomposition_result"] && !hasReasoningOutput(state) && !state["parallel_reasoning_result"]) {
    const decompAge = now - (sseLastUpdate["decomposition_result"] || 0)
    if (decompAge > 5000) return "_reasoning"
  }
  if (lastSseKey && now - (sseLastUpdate[lastSseKey] || 0) < 15000) {
    if (lastSseKey === "parallel_reasoning_result") return "parallel_reasoning_result"
    if (lastSseKey === "review_content") return "_review"
  }
  // 默认：第一个未完成的步骤
  for (const step of WORKFLOW_STEPS) {
    if (!wfCheckStepDone(state, step)) return step.key
  }
  return null
}

// 参考 workflow.html 的 getStallHint
function getStallHint(stepKey: string, sseLastUpdate: Record<string, number>): boolean {
  let lu = sseLastUpdate[stepKey] || 0
  if (stepKey === "_reasoning") {
    for (const k of Object.keys(sseLastUpdate)) {
      if (/^reasoning_.+_result$/.test(k)) {
        lu = Math.max(lu, sseLastUpdate[k] || 0)
      }
    }
  }
  return lu > 0 && (Date.now() - lu) > 4000
}

// 完全参考 workflow.html 的 renderProgress（适配 Vue 响应式）
function buildProgressFull(
  state: Record<string, any> | null,
  activeStepKey: string | null,
  sseLastUpdate: Record<string, number> | null = null
): WorkflowStep[] {
  if (!state) {
    return WORKFLOW_STEPS.map(s => ({ key: s.key, name: s.name, done: false, active: false }))
  }

  const ws = state["workflow_status"] || ""
  const isWaiting = ws === "WAITING_REVIEW"
  const isTerminal = ws === "COMPLETED" || ws === "TERMINATED" || ws === "FAILED"

  // 终态：全部完成
  if (isTerminal) {
    return WORKFLOW_STEPS.map(step => ({
      key: step.key, name: step.name, done: true, active: false,
      _content: wfGetStepContent(state, step),
    }))
  }

  // 解析活跃步骤
  if (!activeStepKey && !isWaiting) {
    activeStepKey = resolveActiveStepKey(state, sseLastUpdate || {}, null)
  }

  return WORKFLOW_STEPS.map(step => {
    let done = wfCheckStepDone(state, step)
    let active = false
    let stalling = false

    if (!isWaiting && !isTerminal) {
      if (step.key === activeStepKey) { active = true; done = false }
      // 检查 stall hint
      if (active && sseLastUpdate) {
        stalling = getStallHint(step.key, sseLastUpdate)
      }
    }
    if (isTerminal) { done = true; active = false }

    const content = (done || active) ? wfGetStepContent(state, step) : ""
    return { key: step.key, name: step.name, done, active, _content: content, _stalling: stalling, _expanded: false }
  })
}

function statusText(status: string): string {
  const map: Record<string, string> = { RUNNING: "执行中", WAITING_REVIEW: "等待审核", COMPLETED: "已完成", TERMINATED: "已终止", FAILED: "已失败" }
  return map[status] || status
}

function isWorkflowDone(msg: ChatMessage): boolean {
  const s = msg.workflow?.status
  return s === "COMPLETED" || s === "TERMINATED" || s === "FAILED"
}

function finalStatusIcon(msg: ChatMessage): string {
  const map: Record<string, string> = { COMPLETED: "\u2705", TERMINATED: "\uD83D\uDED1", FAILED: "\u274C" }
  return map[msg.workflow?.status || ""] || ""
}

function formatWorkflowResult(msg: ChatMessage): string {
  const state = msg.workflow?.state; if (!state) return ""
  const fields: string[] = []
  if (state.decomposition_result) fields.push("## 需求拆解结果\n\n" + String(state.decomposition_result))
  if (state.parallel_reasoning_result) fields.push("## 并行推理汇总\n\n" + String(state.parallel_reasoning_result))
  if (state.review_content) fields.push("## 审核内容\n\n" + String(state.review_content))
  return fields.join("\n\n---\n\n")
}

interface DecompItem {
  title: string; desc: string; _selected: boolean
}

// ==================== 需求拆解项解析与编辑 ====================

function parseDecompItems(state: Record<string, any>): DecompItem[] {
  const raw = state?.decomposition_result
  if (!raw || typeof raw !== "string") return []
  const text = raw.trim()
  if (!text) return []

  const lines = text.split("\n")
  const items: DecompItem[] = []
  let currentTitle = ""
  let currentDesc: string[] = []

  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed) continue
    const isHeader = /^(\d+)[\.\、\)]\s*(.+)/.test(trimmed)
    const isBullet = /^[-*]\s+(.+)/.test(trimmed) && !trimmed.match(/^#{1,3}\s/) // 排除 markdown header 中的 -
    const isMarkdown = /^#{2,3}\s+(.+)/.test(trimmed)

    if (isHeader || isBullet || isMarkdown) {
      if (currentTitle) {
        items.push({ title: currentTitle, desc: currentDesc.join(" ").slice(0, 200), _selected: true })
      }
      currentTitle = trimmed.replace(/^(\d+)[\.\、\)]\s*|^[-*]\s+|^#{2,3}\s*/, "")
      currentDesc = []
    } else if (currentTitle && trimmed.length > 5) {
      currentDesc.push(trimmed)
    }
  }
  if (currentTitle) {
    items.push({ title: currentTitle, desc: currentDesc.join(" ").slice(0, 200), _selected: true })
  }

  if (items.length === 0) {
    const paragraphs = text.split(/\n{2,}/).filter(p => p.trim().length > 10)
    return paragraphs.map((p, i) => ({
      title: (i + 1) + ". " + p.trim().slice(0, 80),
      desc: p.trim().slice(80, 280),
      _selected: true,
    }))
  }
  return items
}

function getDecompItems(msg: ChatMessage): DecompItem[] {
  if (!msg.workflow) return []
  if (!msg.workflow._decompItemsParsed || !msg.workflow._decompItems) {
    msg.workflow._decompItems = parseDecompItems(msg.workflow.state || {})
    msg.workflow._decompItemsParsed = true
  }
  return msg.workflow._decompItems || []
}

function editItem(msg: ChatMessage, idx: number) {
  const items = getDecompItems(msg)
  if (idx < 0 || idx >= items.length) return
  ElMessageBox.prompt("修改需求项内容", "调整需求项", {
    confirmButtonText: "确定",
    cancelButtonText: "取消",
    inputValue: items[idx].title + (items[idx].desc ? "\n" + items[idx].desc : ""),
    inputType: "textarea",
    inputPlaceholder: "输入修改后的需求项内容...",
  }).then(({ value }: any) => {
    if (value?.trim()) {
      items[idx].title = value.trim().split("\n")[0].slice(0, 100)
      items[idx].desc = value.trim().split("\n").slice(1).join(" ").slice(0, 200)
      ElMessage.success("已更新需求项")
    }
  }).catch(() => {})
}

function toggleReviewSection(msg: ChatMessage, key: string) {
  if (!msg.workflow) return
  const fk = key === "decomp" ? "_showDecomp" : key === "reason" ? "_showReason" : "_show_" + key
  msg.workflow[fk] = !msg.workflow[fk]
}
function getReviewToggle(msg: ChatMessage, key: string): boolean {
  if (!msg.workflow) return false
  const fk = key === "decomp" ? "_showDecomp" : key === "reason" ? "_showReason" : "_show_" + key
  return !!msg.workflow[fk]
}
function getReasoningKeys(msg: ChatMessage): string[] {
  if (!msg.workflow?.state) return []
  return Object.keys(msg.workflow.state).filter(k => /^reasoning_.+_result$/.test(k)).sort()
}
function reasoningLabel(key: string): string {
  const match = key.match(/^reasoning_(.+)_result$/); if (!match) return key
  const bn = match[1].replace(/_/g, "").toLowerCase()
  const found = availableModels.value.find(m => m.name.toLowerCase().includes(bn) || m.label.toLowerCase().includes(bn))
  return found ? found.label + " 推理结果" : match[1].replace(/_/g, "-") + " 推理结果"
}

/** 获取当前卡片之前所有已完成的历史轮次 */
function getHistoryRounds(msgIndex: number): { round: number; decision: string; label: string; comment: string }[] {
  const msg = messages.value[msgIndex]
  if (!msg?.workflow?._groupId) return []
  const result: { round: number; decision: string; label: string; comment: string }[] = []
  // 向前查找同组已完成的工作流卡片
  for (let i = 0; i < msgIndex; i++) {
    const prev = messages.value[i]
    if (prev?.workflow && prev.workflow._groupId === msg.workflow._groupId && prev.workflow._finalStatus) {
      const d = String(prev.workflow.state?.review_decision || prev.workflow.status || "")
      const labelMap: Record<string, string> = { APPROVED: "通过", SENT_BACK: "继续修复", TERMINATED: "结束" }
      result.push({
        round: prev.workflow._round || 0,
        decision: d,
        label: labelMap[d] || d,
        comment: prev.workflow._comment || "",
      })
    }
  }
  return result.sort((a, b) => a.round - b.round)
}

// ==================== SSE 事件监听（完全参考 workflow.html 的 listenEvents） ====================

function connectSSE(msgIndex: number, threadId: string, preserveContent: boolean) {
  const msg = messages.value[msgIndex]; if (!msg?.workflow) return
  closeSSE(msgIndex)

  const state: Record<string, any> = {}
  let lastSseKey: string | null = null
  let sseErrorCount = 0
  const sseLastUpdate: Record<string, number> = {}
  const connObj = { es: null as EventSource | null, poll: null as any, delayTimer: null as any }
  sseConnections.set(msgIndex, connObj)

  function renderNow() {
    if (!msg?.workflow) return
    const activeKey = resolveActiveStepKey(state, sseLastUpdate, lastSseKey)
    const newStatus = state["workflow_status"] || ""
    if (newStatus) msg.workflow.status = newStatus
    msg.workflow.progress = buildProgressFull(state, activeKey, sseLastUpdate)
    msg.workflow.state = { ...state }
  }

  if (preserveContent) {
    getWorkflowState(threadId).then(result => {
      if (result.state) { for (const [k, v] of Object.entries(result.state)) state[k] = v }
      renderNow()
    }).catch(() => {})
  } else {
    renderNow()
  }

  // 连接 SSE（参考 workflow.html：connectSSE 内部函数）
  function connect() {
    connObj.es = new EventSource("/api/v1/workflow/events/" + encodeURIComponent(threadId))

    connObj.es.onmessage = function (e: MessageEvent) {
      try {
        const d = JSON.parse(e.data)
        if (d.key && d.value != null) {
          const isDelta = !!d._delta
          if (isDelta) {
            state[d.key] = (state[d.key] || "") + d.value
          } else {
            // 推理启动空信号不覆盖已有增量
            if (/^reasoning_.+_result$/.test(d.key) && d.value === "" && state[d.key]) { /* skip */ }
            else { state[d.key] = d.value }
          }
          if (d.value.length > 0) { lastSseKey = d.key; sseLastUpdate[d.key] = Date.now() }
          if (/^reasoning_.+_result$/.test(d.key)) {
            lastSseKey = d.key; sseLastUpdate[d.key] = Date.now(); sseLastUpdate["_reasoning"] = Date.now()
          }
          renderNow()
          sseErrorCount = 0 // 重置错误计数

          // 检测终态
          if (d.key === "workflow_status" && d.status && d.status !== "RUNNING") {
            handleWFEnd(msgIndex, threadId, d.status)
          }
        }
      } catch (e) { /* ignore parse errors */ }
    }

    connObj.es.onerror = function () {
      if (connObj.es) { connObj.es.close(); connObj.es = null }
      if (!sseConnections.has(msgIndex)) return

      getWorkflowState(threadId).then(result => {
        if (!sseConnections.has(msgIndex)) return
        if (result.status === "RUNNING" && sseErrorCount < 5) {
          sseErrorCount++
          setTimeout(connect, 1000)
        } else {
          handleWFEnd(msgIndex, threadId, result.status)
        }
      }).catch(() => { handleWFEnd(msgIndex, threadId, null) })
    }
  }

  connect()

  // 后台轮询补漏（参考 workflow.html：延迟 3 秒启动，每 2 秒轮询）
  connObj.delayTimer = setTimeout(() => {
    connObj.poll = setInterval(() => {
      if (!sseConnections.has(msgIndex)) { clearInterval(connObj.poll); return }
      getWorkflowState(threadId).then(result => {
        if (result.state) {
          let updated = false
          for (const [k, v] of Object.entries(result.state)) { if (state[k] !== v) { state[k] = v; updated = true } }
          renderNow()
        }
      }).catch(() => {})
    }, 2000)
  }, 3000)
}

function closeSSE(msgIndex: number) {
  const c = sseConnections.get(msgIndex); if (!c) return
  if (c.es) { c.es.close(); c.es = null }
  if (c.poll) { clearInterval(c.poll); c.poll = null }
  if (c.delayTimer) { clearTimeout(c.delayTimer); c.delayTimer = null }
  sseConnections.delete(msgIndex)
}

// 参考 workflow.html 的 handleWorkflowFinished
function handleWFEnd(msgIndex: number, threadId: string, knownStatus: string | null) {
  if (!sseConnections.has(msgIndex)) return
  const msg = messages.value[msgIndex]; if (!msg?.workflow) return
  if (msg.workflow._finalStatus) return // 防重
  closeSSE(msgIndex)

  // 立即更新状态
  if (knownStatus) {
    msg.workflow.status = knownStatus
  }

  getWorkflowState(threadId).then(result => {
    if (result.state) {
      result.state["workflow_status"] = result.status
      // 用完整 state 重新渲染进度
      msg.workflow!.progress = buildProgressFull(result.state, null, null)
      msg.workflow!.state = result.state
    }
    msg.workflow!.status = result.status

    if (result.status === "WAITING_REVIEW") {
      msg.workflow!._finalStatus = false
    } else if (result.status === "RUNNING") {
      // 兜底重连（保留已有内容）
      connectSSE(msgIndex, threadId, true)
    } else {
      // COMPLETED / TERMINATED / FAILED
      msg.workflow!._finalStatus = true
      msg.workflow!.progress = buildProgressFull(result.state || msg.workflow!.state, null, null)
    }
  }).catch(() => {})
}

// ==================== 启动/审核工作流 ====================
async function startWorkflowFlow(requirement: string) {
  try {
    const result = await startWorkflowApi(requirement)
    const msg: ChatMessage = {
      role: "assistant", content: "",
      workflow: {
        threadId: result.threadId, status: result.status,
        progress: buildProgressFull(result.state, null, null),
        state: result.state, _comment: "", _showDecomp: false, _showReason: false,
        _finalStatus: false, _round: 1, _groupId: result.threadId,
      },
    }
    messages.value.push(msg)
    scrollToBottom()
    connectSSE(messages.value.length - 1, result.threadId, false)
  } catch (err: any) { ElMessage.error("启动工作流失败：" + (err.message || "未知错误")) }
}

async function resumeWorkflow(msgIndex: number, decision: WorkflowResumeRequest["reviewDecision"]) {
  const msg = messages.value[msgIndex]; if (!msg?.workflow) return
  const req: WorkflowResumeRequest = { threadId: msg.workflow.threadId, reviewDecision: decision, comment: msg.workflow._comment || undefined }
  try {
    const result = await resumeWorkflowApi(req)

    if (decision === "APPROVED" && result.status === "RUNNING") {
      // 通过 → 结束当前卡片 → 创建新一轮代码生成卡片
      msg.workflow.status = "COMPLETED"
      msg.workflow._finalStatus = true
      msg.workflow._collapsed = true
      msg.workflow.progress = buildProgressFull(result.state, null, null)

      const items = getDecompItems(msg).filter(it => it._selected)
      const newRound = (msg.workflow._round || 1) + 1
      const codeMsg: ChatMessage = {
        role: "assistant", content: "",
        workflow: {
          threadId: result.threadId, status: "RUNNING",
          progress: buildProgressFull(null, null, null),
          state: null, _comment: "", _showDecomp: false, _showReason: false,
          _finalStatus: false, _round: newRound, _groupId: msg.workflow._groupId,
        },
      }
      messages.value.push(codeMsg)
      scrollToBottom()
      generateCode(messages.value.length - 1, items, msg.workflow.state || {})
      return
    }

    msg.workflow.status = result.status
    msg.workflow.state = result.state || msg.workflow.state

    if (result.status === "RUNNING") {
      if (decision === "SENT_BACK") {
        // 继续修复 → 结束当前卡片，创建新卡片
        msg.workflow.status = "COMPLETED"
        msg.workflow._finalStatus = true
        msg.workflow._collapsed = true
        msg.workflow.progress = buildProgressFull(result.state, null, null)

        const round = (msg.workflow._round || 1) + 1
        const newMsg: ChatMessage = {
          role: "assistant", content: "",
          workflow: {
            threadId: result.threadId, status: "RUNNING",
            progress: buildProgressFull(null, null, null),
            state: null, _comment: "", _showDecomp: false, _showReason: false,
            _finalStatus: false, _round: round, _groupId: msg.workflow._groupId,
          },
        }
        messages.value.push(newMsg)
        scrollToBottom()
        connectSSE(messages.value.length - 1, result.threadId, false)
        ElMessage.info("已驳回，进入第 " + round + " 轮重新执行...")
        return
      }
      connectSSE(msgIndex, result.threadId, false)
    } else if (result.status === "COMPLETED") {
      msg.workflow._finalStatus = true
      msg.workflow.progress = buildProgressFull(result.state, null)
      ElMessage.success(result.message || "审核通过，工作流已完成")
    } else if (result.status === "TERMINATED") {
      msg.workflow._finalStatus = true
      msg.workflow.progress = buildProgressFull(result.state, null)
      ElMessage.info("工作流已终止")
    }
  } catch (err: any) { ElMessage.error("审核操作失败：" + (err.message || "未知错误")) }
}

// ==================== 代码生成（AI 对话 + JSZip） ====================

async function generateCode(msgIndex: number, items: DecompItem[], state: Record<string, any>) {
  const msg = messages.value[msgIndex]
  if (!msg?.workflow) return

  msg.workflow.progress = [
    { key: "generate", name: "正在流式输出思考过程和代码...", done: false, active: true, _content: "" },
  ]

  if (!items || items.length === 0) {
    ElMessage.warning("请至少勾选一项需求")
    msg.workflow.progress = [{ key: "generate", name: "AI 生成项目代码", done: false, active: false, _content: "失败：未勾选任何需求" }]
    msg.workflow.status = "FAILED"; msg.workflow._finalStatus = true
    return
  }

  const itemsText = items.map((it, i) => (i + 1) + ". " + it.title + (it.desc ? " - " + it.desc : "")).join("\n")

  // 利用工作流上下文：拆解结果、架构设计、API设计（限制长度防 431）
  const decompResult = String(state?.decomposition_result || "").slice(0, 600)
  const reasoningResult = String(state?.parallel_reasoning_result || "").slice(0, 600)
  // 检测前端需求关键词
  const isFrontend = items.some(it => /前端|前端|vue|react|angular|页面|UI|组件|HTML|CSS|typescript|javascript/i.test(it.title + it.desc))

  const contextBlock = [
    decompResult ? "## 需求拆解\n" + decompResult : "",
    reasoningResult ? "## 架构与API设计\n" + reasoningResult : "",
  ].filter(Boolean).join("\n\n")

  const prompt = isFrontend
    ? `你是一个资深全栈工程师。请根据以下需求生成完整的前端项目代码。

${contextBlock}

## 需求列表
${itemsText}

## 输出要求
1. 每个文件严格按以下格式输出（三种格式任选其一，必须严格遵守）：
   格式A: FILE:src/App.vue
   \`\`\`vue
   <template>...</template>
   \`\`\`
   格式B: // filename: src/utils/api.ts
   \`\`\`typescript
   export function ...
   \`\`\`
   格式C: 在代码块上一行写上完整路径，例如：
   src/components/Header.vue
   \`\`\`vue
   ...
   \`\`\`

2. 必须包含的核心文件：index.html、src/App.vue、src/main.js(或main.ts)、package.json、vite.config.js(或ts)
3. 如果是Vue项目，使用Vue 3 Composition API + Element Plus
4. 如果是React项目，使用Hooks + Ant Design
5. 代码必须完整可运行，不要省略(用// TODO可继续扩展)
6. 每个文件互相引用正确（import路径正确）
7. 所有输出文件放在项目根目录下，使用合理的目录结构（src/components/, src/views/, src/api/, src/utils/等）`

    : `你是一个资深Java全栈工程师。请根据以下需求生成完整的Spring Boot项目代码。

${contextBlock}

## 需求列表
${itemsText}

## 输出要求
1. 每个文件严格按以下格式输出（三种格式任选其一，必须严格遵守）：
   格式A: FILE:src/main/java/com/example/Application.java
   \`\`\`java
   @SpringBootApplication
   public class Application { ... }
   \`\`\`
   格式B: // filename: src/main/resources/application.yml
   \`\`\`yaml
   server:
     port: 8080
   \`\`\`
   格式C: 在代码块上一行写上完整路径，例如：
   src/main/java/com/example/controller/UserController.java
   \`\`\`java
   @RestController
   public class UserController { ... }
   \`\`\`

2. 必须包含：pom.xml、Application.java、至少一个Controller/Service/Entity、application.yml、README.md
3. 使用Spring Boot 3.x + MyBatis-Plus + MySQL
4. 每个类添加必要的注解（@RestController/@Service/@Entity等）
5. 代码完整可运行，不要出现省略号或"..."代替实现
6. import语句完整正确
7. 所有文件放在合理的Maven目录结构中（src/main/java/com/example/...）`

  // 使用更大的模型做代码生成
  const codeModel = selectedModel.value && selectedModel.value.includes("gemma") ? "qwen3:4b" : (selectedModel.value || "qwen3:4b")
  const apiKey = appStore.modelType === "network" ? (appStore.apiKey || "") : ""
  const apiUrl = appStore.modelType === "network" ? (appStore.apiUrl || "") : ""
  const keyParam = apiKey ? `&apiKey=${encodeURIComponent(apiKey)}` : ""
  const urlParam = apiUrl ? `&baseUrl=${encodeURIComponent(apiUrl)}` : ""
  // 总 URL 控制在安全范围内，防 HTTP 431
  const maxPromptLen = 2500
  const truncPrompt = prompt.length > maxPromptLen ? prompt.slice(0, maxPromptLen - 100) + "\n（需求内容已截断，请生成上述文件）" : prompt
  const reqUrl = `${BASE_URL}/chat/stream?model=${encodeURIComponent(codeModel)}&message=${encodeURIComponent(truncPrompt)}${keyParam}${urlParam}`
  const token = localStorage.getItem("token") || ""
  let full = ""

  try {
    const response = await fetch(reqUrl, { headers: { Authorization: `Bearer ${token}` } })
    if (!response.ok) throw new Error("HTTP " + response.status)
    const reader = response.body?.getReader(); if (!reader) throw new Error("No reader")
    const decoder = new TextDecoder(); let buf = ""

    while (true) {
      const { done, value } = await reader.read(); if (done) break
      buf += decoder.decode(value, { stream: true })
      const lines = buf.split("\n"); buf = lines.pop() || ""
      for (const raw of lines) {
        const line = raw.replace(/\r$/, "")
        if (line.startsWith("data:")) {
          const data = line[5] === " " ? line.slice(6) : line.slice(5)
          if (data !== "[DONE]") {
            full += data
            // 实时更新卡片显示，显示最近 3000 字符
            msg.workflow!.progress[0]._content = "🤖 " + full.slice(-3000)
          }
        }
      }
    }

    // 解析代码文件
    const files = parseCodeFilesFromText(full)
    if (files.length === 0) {
      console.error("[generateCode] Parse FAILED, raw output:", full)
      // 最后兜底：提取所有 ``` 代码块，按内容分析给默认路径
      const allBlocks = [...full.matchAll(/```([\s\S]*?)```/g)]
      if (allBlocks.length > 0) {
        const seen = new Set<string>()
        let dedupIdx = 0
        for (const b of allBlocks) {
          const content = b[1].replace(/^\w*\s*\n?/, "").trim()
          if (content.length < 50) continue
          let name = "src/file" + (++dedupIdx) + ".txt"
          if (/@SpringBootApplication|@RestController|public class/.test(content)) name = "src/main/java/com/example/Generated" + dedupIdx + ".java"
          else if (/<template>|<script setup>/.test(content)) name = "src/App.vue"
          else if (content.includes("<!DOCTYPE") || content.includes("<html>")) name = "index.html"
          else if (/^\s*\{/.test(content) && content.includes('"name"')) name = "package.json"
          else if (/server:|spring:|datasource:/.test(content)) name = "src/main/resources/application.yml"
          else if (/<project|<dependency/i.test(content)) name = "pom.xml"
          else if (/export |import |const |function /.test(content)) name = "src/index" + dedupIdx + ".ts"
          else if (/^# |^## /.test(content.split("\n")[0])) name = "README.md"
          while (seen.has(name)) name = name.replace(/(\.\w+)$/, dedupIdx + "$1")
          seen.add(name)
          files.push({ path: name, content })
        }
      }
    }

    if (files.length === 0) {
      const preview = full.slice(0, 500).replace(/\n/g, " ")
      ElMessage.error("AI 未输出代码（" + full.length + "字），请展开卡片查看原始输出")
      msg.workflow!.progress = [{ key: "generate", name: "生成失败 - 未识别到代码", done: false, active: false, _content: full.slice(0, 3000) }]
      msg.workflow.status = "FAILED"; msg.workflow._finalStatus = true
      return
    }

    const zip = new JSZip()
    files.forEach(f => { try { zip.file(f.path, f.content) } catch(e) {} })
    const blob = await zip.generateAsync({ type: "blob" })
    const fileName = "project-" + Date.now() + ".zip"

    const projectName = "p" + Date.now()
    msg.workflow!.progress = [
      { key: "generate", name: "代码生成完成 " + files.length + " 个文件", done: true, active: false, _content: "✅ 生成文件: " + files.map(f => f.path).join(", ").slice(0, 500) },
    ]
    msg.workflow.status = "COMPLETED"; msg.workflow._finalStatus = true
    msg.workflow._codegenDone = true; msg.workflow._zipBlob = blob; msg.workflow._zipName = fileName
    msg.workflow.state = { generated_files: files.map(f => f.path), zip_name: fileName, project_name: projectName }
    ElMessage.success("代码生成完成，点击下载或运行部署")

  } catch (err: any) {
    msg.workflow!.progress = [{ key: "generate", name: "生成失败", done: false, active: false, _content: err.message }]
    msg.workflow.status = "FAILED"; msg.workflow._finalStatus = true
    ElMessage.error("代码生成失败：" + (err.message || "未知错误"))
  }
}

/** 从 AI 输出文本解析代码文件 */
function parseCodeFilesFromText(full: string): { path: string; content: string }[] {
  const files: { path: string; content: string }[] = []
  const added = new Set<string>() // 防重复

  function addFile(path: string, content: string) {
    const p = path.trim()
    const c = content.trim()
    if (!p || !c || p.length > 200 || c.length < 10) return
    if (added.has(p)) return
    added.add(p)
    files.push({ path: p, content: c })
  }

  // 清理：统一换行，修复常见格式问题
  let text = full.replace(/\r\n/g, "\n").replace(/\r/g, "\n")
  // 修复三个反引号被转义
  text = text.replace(/"""/g, "```").replace(/'''/g, "```")

  // 模式1: FILE:path\n```lang\ncode\n```
  const r1 = /FILE:\s*(\S[^\n]{0,200}?)\s*\n```(\w*)\s*\n([\s\S]*?)```/g
  let match
  while ((match = r1.exec(text)) !== null) {
    addFile(match[1], match[3])
  }
  if (files.length > 0) return files

  // 模式2: // filename: path\n```lang\ncode\n``` 或 # filename: path
  const r2 = /(?:\/\/|#)\s*filename:\s*(\S[^\n]{0,200}?)\s*\n```(\w*)\s*\n([\s\S]*?)```/g
  while ((match = r2.exec(text)) !== null) {
    addFile(match[1], match[3])
  }
  if (files.length > 0) return files

  // 模式3: 路径行 + 代码块（前一行是路径）
  const lines = text.split("\n")
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim().startsWith("```") && !lines[i].trim().match(/^```\s*$/)) continue
    if (!lines[i].trim().match(/^```\w*$/)) continue
    const prev = lines[i - 1].trim()
    // 路径特征：含斜杠/反斜杠 + 扩展名 或 Maven 目录结构
    const isPath = /[\/\\]/.test(prev) || /^(src|pom\.xml|package\.json|.*\.\w{1,10})$/i.test(prev)
    if (isPath && prev.length < 200) {
      const lang = lines[i].trim().slice(3)
      let j = i + 1
      while (j < lines.length && !lines[j].trim().match(/^```\s*$/)) j++
      if (j < lines.length && j > i + 1) {
        addFile(prev, lines.slice(i + 1, j).join("\n"))
        i = j
      }
    }
  }
  if (files.length > 0) return files

  // 模式4: 任意代码块（最后兜底），按语言映射到默认文件名
  const codeBlockRegex = /```(\w+)\s*\n([\s\S]*?)```/g
  let idx = 0
  const langPaths: Record<string, string> = {
    java: "src/main/java/com/example/App.java", xml: "pom.xml",
    yaml: "src/main/resources/application.yml", yml: "src/main/resources/application.yml",
    json: "package.json", html: "index.html", css: "src/style.css",
    md: "README.md", sql: "schema.sql", py: "app.py",
    js: "src/index.js", ts: "src/index.ts", vue: "src/App.vue",
    jsx: "src/App.jsx", tsx: "src/App.tsx",
    properties: "src/main/resources/application.properties",
    dockerfile: "Dockerfile", sh: "start.sh", bash: "start.sh",
  }
  while ((match = codeBlockRegex.exec(text)) !== null) {
    const lang = match[1].toLowerCase()
    const content = match[2]?.trim()
    if (content && content.length > 20 && content.includes("\n")) {
      addFile(langPaths[lang] || `src/file${++idx}.${lang || "txt"}`, content)
    }
  }
  return files
}

// ==================== 下载 & 部署（代码生成完成后） ====================

function downloadProject(msgIndex: number) {
  const msg = messages.value[msgIndex]
  if (!msg?.workflow?._zipBlob) {
    ElMessage.warning("ZIP 文件已失效")
    return
  }
  saveAs(msg.workflow._zipBlob, msg.workflow._zipName || "project.zip")
  ElMessage.success("下载已开始")
}

async function deployProject(msgIndex: number) {
  const msg = messages.value[msgIndex]
  if (!msg?.workflow?._zipBlob) {
    ElMessage.warning("ZIP 文件已失效")
    return
  }
  const projectName = "p" + Date.now()

  try {
    // 上传 ZIP 到服务器
    const formData = new FormData()
    formData.append("file", msg.workflow._zipBlob, msg.workflow._zipName || "project.zip")
    formData.append("projectName", projectName)

    ElMessage.info("正在上传并部署项目...")
    const saveRes = await service.post("/project/save", formData, {
      headers: { "Content-Type": "multipart/form-data" },
    })
    if ((saveRes as any).data?.code !== 0) {
      ElMessage.error("项目保存失败")
      return
    }

    // 保存成功 → 尝试部署
    const deployRes = await service.post("/project/deploy/" + projectName)
    const deployData = (deployRes as any).data?.data

    if (deployData?.success) {
      ElMessage.success("Docker 部署成功！容器ID: " + deployData.containerId)
    } else if (!deployData?.dockerAvailable) {
      ElMessageBox.alert(deployData?.message || "Docker 未安装", "部署提示", {
        type: "warning",
        confirmButtonText: "知道了",
      })
    } else {
      ElMessage.error(deployData?.message || "部署失败")
    }
  } catch (err: any) {
    ElMessage.error("部署失败：" + (err.message || "未知错误"))
  }
}



// ==================== 生命周期 ====================
onMounted(() => { fetchModels() })
onBeforeUnmount(() => {
  abortController?.abort()
  sseConnections.forEach((c) => {
    if (c.es) c.es.close()
    if (c.poll) clearInterval(c.poll)
    if (c.delayTimer) clearTimeout(c.delayTimer)
  })
  sseConnections.clear()
})
</script>

<style scoped>
/* ===== 基础布局 ===== */
.chat-index { height: 100%; display: flex; flex-direction: column; background: #f5f5f5; font-family: Arial, "Microsoft YaHei", sans-serif; }
.chat-header { display: flex; align-items: center; justify-content: space-between; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e0e0e0; flex-shrink: 0; }
.chat-title { margin: 0; font-size: 18px; font-weight: 600; color: #333; }
.header-actions { display: flex; align-items: center; gap: 8px; }
.chat-messages { flex: 1; overflow-y: auto; padding: 20px; max-width: 900px; margin: 0 auto; width: 100%; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; gap: 12px; opacity: 0.6; }
.empty-icon { margin-bottom: 8px; }
.empty-title { margin: 0; font-size: 18px; color: #333; font-weight: 500; }
.empty-desc { margin: 0; font-size: 14px; color: #888; }

/* ===== 消息 ===== */
.message-user { display: flex; flex-direction: column; margin-bottom: 20px; }
.message-user-content { background: #fff; border: 1px solid #e0e0e0; border-radius: 8px; padding: 12px 16px; font-size: 15px; line-height: 1.6; color: #333; white-space: pre-wrap; word-break: break-word; }
.message-assistant { display: flex; flex-direction: column; margin-bottom: 20px; }
.message-loading { display: flex; align-items: center; gap: 8px; color: #888; font-size: 14px; }
.loading-spinner { width: 16px; height: 16px; border: 2px solid #e0e0e0; border-top-color: #007bff; border-radius: 50%; animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.message-content { background: #fff; border: 1px solid #e0e0e0; border-radius: 8px; padding: 12px 16px; font-size: 15px; line-height: 1.6; color: #333; }

/* ===== 输入区 ===== */
.chat-input-area { flex-shrink: 0; padding: 16px 20px; background: #fff; border-top: 1px solid #e0e0e0; }
.input-container { max-width: 900px; margin: 0 auto; }
.chat-textarea { width: 100%; padding: 12px; font-size: 15px; border: 1px solid #ccc; border-radius: 8px; resize: vertical; min-height: 60px; font-family: inherit; outline: none; transition: border-color 0.2s; box-sizing: border-box; }
.chat-textarea:focus { border-color: #007bff; }
.chat-textarea:disabled { opacity: 0.5; cursor: not-allowed; }
.input-actions { display: flex; align-items: center; justify-content: space-between; margin-top: 12px; gap: 12px; }
.send-btn { display: flex; align-items: center; gap: 6px; padding: 10px 20px; font-size: 14px; background: #f0f0f0; color: #666; border: none; border-radius: 6px; cursor: pointer; transition: all 0.2s; }
.send-btn:hover { background: #e0e0e0; }
.send-btn.primary { background: #007bff; color: #fff; }
.send-btn.primary:hover { background: #0056b3; }
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.send-btn.stop { background: #dc3545; color: #fff; }
.send-btn.stop:hover { background: #c82333; }

/* ===== Markdown ===== */
.markdown :deep(pre) { background: #1e1e2e; padding: 12px; border-radius: 6px; overflow-x: auto; margin: 12px 0; }
.markdown :deep(code) { font-family: Consolas, Monaco, monospace; font-size: 13px; }
.markdown :deep([data-code-block]) { position: relative; margin: 12px 0; border-radius: 6px; overflow: hidden; background: #1e1e2e; }
.markdown :deep(.code-lang) { position: absolute; top: 0; left: 0; font-size: 11px; color: #7f849c; padding: 6px 12px; font-family: monospace; text-transform: uppercase; letter-spacing: 0.5px; }
.markdown :deep(pre code) { display: block; padding: 36px 12px 12px; background: transparent; color: #cdd6f4; line-height: 1.5; }
.markdown :deep(p) { margin: 0 0 12px; line-height: 1.6; }
.markdown :deep(p:last-child) { margin-bottom: 0; }
.markdown :deep(ul),.markdown :deep(ol) { margin: 8px 0; padding-left: 1.5em; }
.markdown :deep(li) { margin-bottom: 4px; line-height: 1.6; }
.markdown :deep(blockquote) { margin: 12px 0; padding: 8px 16px; border-left: 3px solid #ddd; background: #f9f9f9; color: #666; }
.markdown :deep(table) { width: 100%; border-collapse: collapse; margin: 12px 0; }
.markdown :deep(th),.markdown :deep(td) { padding: 8px 12px; border: 1px solid #ddd; text-align: left; }
.markdown :deep(th) { background: #f5f5f5; font-weight: 600; }
.markdown :deep(h1),.markdown :deep(h2),.markdown :deep(h3),.markdown :deep(h4) { margin: 16px 0 8px; font-weight: 600; }
.markdown :deep(h1) { font-size: 1.5em; } .markdown :deep(h2) { font-size: 1.3em; } .markdown :deep(h3) { font-size: 1.15em; }
.markdown :deep(a) { color: #007bff; text-decoration: none; }
.markdown :deep(a:hover) { text-decoration: underline; }
.markdown :deep(strong) { font-weight: 600; }
.markdown :deep(hr) { border: none; height: 1px; background: #e0e0e0; margin: 16px 0; }

/* ===== 工作流卡片 ===== */
.workflow-card { background: #fff; border: 1px solid #e0e0e0; border-radius: 8px; padding: 16px; }
.wf-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; padding-bottom: 10px; border-bottom: 1px solid #eee; }
.wf-title { font-size: 15px; font-weight: 600; color: #333; }
.wf-status { display: inline-block; padding: 2px 10px; border-radius: 12px; font-size: 12px; font-weight: bold; }
.wf-status-running { background: #cce5ff; color: #004085; }
.wf-status-waiting_review { background: #fff3cd; color: #856404; }
.wf-status-completed { background: #d4edda; color: #155724; }
.wf-status-terminated { background: #f8d7da; color: #721c24; }
.wf-status-failed { background: #e2e3e5; color: #383d41; }

.wf-progress { display: flex; flex-direction: column; gap: 4px; margin-bottom: 10px; }
.wf-step { display: flex; align-items: center; padding: 4px 0; font-size: 13px; color: #999; }
.wf-step.done { color: #28a745; }
.wf-step.active { color: #007bff; font-weight: bold; }
.wf-step-icon { width: 20px; height: 20px; border-radius: 50%; border: 2px solid #ccc; margin-right: 10px; flex-shrink: 0; text-align: center; line-height: 16px; font-size: 11px; }
.wf-step.done .wf-step-icon { background: #28a745; border-color: #28a745; color: #fff; }
.wf-step.active .wf-step-icon { border-color: #007bff; animation: wf-pulse 1.5s infinite; }
@keyframes wf-pulse { 0%,100% { box-shadow: 0 0 0 0 rgba(0,123,255,0.4); } 50% { box-shadow: 0 0 0 6px rgba(0,123,255,0); } }

/* 历史轮次 */
.wf-history { margin-bottom: 10px; padding: 8px 10px; background: #f8f9fa; border: 1px solid #dee2e6; border-radius: 4px; }
.wf-history-header { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #666; cursor: pointer; user-select: none; }
.wf-history-body { margin-top: 6px; padding-top: 6px; border-top: 1px solid #dee2e6; }
.wf-history-item { padding: 4px 0; font-size: 12px; }
.wf-history-round { display: inline-block; padding: 1px 6px; background: #007bff; color: #fff; border-radius: 3px; font-size: 11px; margin-right: 6px; }
.wf-history-decision { color: #555; }
.wf-history-comment { color: #999; }
.wf-step-time { margin-left: auto; font-size: 11px; color: #aaa; }

/* 步骤内容体（inline，参考 workflow.html） */
.wf-step-body { margin-left: 30px; margin-top: 4px; padding: 6px 10px; background: #f8f9fa; border-left: 2px solid #e0e0e0; font-size: 12px; color: #555; white-space: pre-wrap; max-height: 300px; overflow-y: auto; border-radius: 0 4px 4px 0; }
.wf-body-pre { margin: 0; white-space: pre-wrap; word-break: break-all; font-family: inherit; color: #555; }
.wf-thinking { color: #ff9800; font-size: 12px; margin-top: 4px; }
.thinking-dot { animation: dotPulse 1.2s infinite; }
.thinking-dot:nth-child(2) { animation-delay: 0.2s; }
.thinking-dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes dotPulse { 0%, 20% { opacity: 0.2; } 50% { opacity: 1; } 80%, 100% { opacity: 0.2; } }

/* 终态面板 */
.wf-final-status { margin-top: 14px; padding: 12px; border-radius: 6px; font-size: 13px; }
.wf-final-completed { background: #d4edda; border: 1px solid #28a745; color: #155724; }
.wf-final-terminated { background: #f8d7da; border: 1px solid #dc3545; color: #721c24; }
.wf-final-failed { background: #e2e3e5; border: 1px solid #6c757d; color: #383d41; }
.wf-final-msg { margin-top: 4px; font-size: 12px; }

/* 审核操作区域（醒目弹窗式） */
.wf-review-bar { margin-top: 14px; padding: 16px; background: #fffbe6; border: 2px solid #ffc107; border-radius: 8px; }
.wf-review-alert { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 12px; }
.wf-review-icon { font-size: 24px; line-height: 1; }
.wf-review-sub { font-size: 12px; color: #856404; margin-top: 2px; }
.wf-comment { margin-bottom: 10px; }
.wf-btns { display: flex; gap: 8px; justify-content: flex-end; }
.wf-action-hint { font-size: 11px; color: #999; margin-top: 6px; text-align: right; }

.wf-result { margin-top: 12px; padding-top: 12px; border-top: 1px solid #eee; }
.wf-result-body { font-size: 14px; line-height: 1.7; }

/* 审核内容区 */
.wf-review-area { margin-bottom: 14px; padding: 12px; background: #fafafa; border: 1px solid #eee; border-radius: 6px; }
.wf-review-label { font-size: 13px; font-weight: 600; color: #666; margin-bottom: 10px; }
.wf-review-block { margin-bottom: 8px; border: 1px solid #e8e8e8; border-radius: 4px; overflow: hidden; }
.wf-review-block:last-child { margin-bottom: 0; }
.wf-review-block-header { display: flex; align-items: center; gap: 6px; padding: 8px 10px; background: #f0f0f0; font-size: 13px; color: #555; cursor: pointer; user-select: none; }
.wf-review-block-header:hover { background: #e8e8e8; }
.wf-toggle-icon { font-size: 10px; width: 14px; text-align: center; flex-shrink: 0; }
.wf-review-block-body { padding: 10px 12px; font-size: 13px; line-height: 1.6; max-height: 400px; overflow-y: auto; border-top: 1px solid #e8e8e8; }

/* 逐条需求项 */
.wf-item-row { display: flex; align-items: flex-start; gap: 8px; padding: 8px 10px; background: #fff; border: 1px solid #e8e8e8; border-radius: 4px; margin-bottom: 6px; }
.wf-item-row:last-child { margin-bottom: 0; }
.wf-item-row.selected { border-color: #007bff; background: #f0f7ff; }
.wf-item-check { flex-shrink: 0; margin-top: 2px; }
.wf-item-body { flex: 1; min-width: 0; }
.wf-item-title { font-size: 13px; font-weight: 500; color: #333; }
.wf-item-desc { font-size: 12px; color: #888; margin-top: 2px; }

/* 折叠状态 */
.wf-collapsed { padding: 12px 16px; }
.wf-collapse-toggle { font-size: 12px; color: #007bff; cursor: pointer; user-select: none; white-space: nowrap; }
.wf-collapse-toggle:hover { text-decoration: underline; }
.wf-step-summary { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 6px; }
.wf-step-tag { font-size: 11px; padding: 2px 8px; border-radius: 10px; background: #f0f0f0; color: #999; }
.wf-step-tag.done { background: #d4edda; color: #155724; }
.wf-round-label { font-size: 12px; color: #666; padding: 4px 8px; background: #e8f4fd; border-radius: 4px; display: inline-block; }
.wf-collapsed-content { margin-top: 8px; }
.wf-collapsed-block { margin-bottom: 6px; border: 1px solid #e8e8e8; border-radius: 4px; overflow: hidden; }
.wf-collapsed-block-header { display: flex; align-items: center; gap: 6px; padding: 6px 10px; background: #f8f8f8; font-size: 12px; color: #555; cursor: pointer; user-select: none; }
.wf-collapsed-block-header:hover { background: #eee; }
.wf-collapsed-block-body { padding: 8px 12px; font-size: 12px; line-height: 1.5; max-height: 300px; overflow-y: auto; border-top: 1px solid #e8e8e8; background: #fff; }
</style>
