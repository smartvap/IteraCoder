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
            <!-- ========== 工作流卡片 ========== -->
            <div v-if="msg.workflow" class="workflow-card" :class="{ 'wf-new-round': msg.workflow._newRound, 'wf-historical': msg.workflow._isHistorical }">
              <div v-if="msg.workflow._newRound" class="wf-round-separator">
                <span class="wf-round-line"></span>
                <span class="wf-round-label">↻ 第 {{ msg.workflow._round }} 轮 · 继续修复</span>
                <span class="wf-round-line"></span>
              </div>
              <div class="wf-header">
                <span class="wf-title">智能体工作流<template v-if="msg.workflow._round">（第{{ msg.workflow._round }}轮）</template><template v-if="msg.workflow._isHistorical"><span class="wf-historical-badge">历史记录</span></template></span>
                <span class="wf-status" :class="'wf-status-' + msg.workflow.status.toLowerCase()">{{ statusText(msg.workflow.status) }}</span>
              </div>
              <div v-if="msg.workflow.progress" class="wf-progress">
                <div v-for="(step, si) in msg.workflow.progress" :key="si" class="wf-step-row">
                  <div class="wf-step" :class="{ done: step.done, active: step.active }">
                    <span class="wf-step-icon">{{ step.done ? '\u2713' : step.active ? '\u22EF' : '\u25CB' }}</span>
                    <span class="wf-step-name">{{ step.name }}</span>
                    <span v-if="step.done && step._content" class="wf-step-toggle" @click="step._showContent = !step._showContent">
                      {{ step._showContent ? '收起' : '展开' }}
                    </span>
                  </div>
                  <!-- 活跃步骤：思考中 -->
                  <div v-if="step.active && !step._content" class="wf-step-body"><span class="wf-thinking">思考中<span class="thinking-dot">.</span><span class="thinking-dot">.</span><span class="thinking-dot">.</span></span></div>
                  <!-- 已完成步骤：默认截断预览，点击展开全文 -->
                  <div v-if="step.done && step._content && !step._showContent" class="wf-step-body wf-step-preview" @click="step._showContent = true">
                    <span class="wf-step-expand-hint">{{ truncateContent(step._content, 200) }}<template v-if="step._content.length > 200">...（点击展开全文）</template></span>
                  </div>
                  <div v-if="step.done && step._content && step._showContent" class="wf-step-body markdown">
                    <span class="wf-step-collapse-btn" @click="step._showContent = false">▴ 收起</span>
                    <div v-html="renderMarkdown(step._content)"></div>
                  </div>
                  <!-- 活跃步骤有内容：流式展示 -->
                  <div v-if="step.active && step._content" class="wf-step-body markdown" v-html="renderMarkdown(step._content)"></div>
                </div>
              </div>
              <!-- 实时统计 -->
              <div v-if="msg.workflow._liveStats && !isWorkflowDone(msg)" class="wf-live-bar">
                <span>⏱ {{ fmtElapsed(msg.workflow._liveStats.elapsed) }}</span>
                <span class="wf-live-sep">|</span>
                <span>{{ fmtTokenNum(msg.workflow._liveStats.tokens) }} tokens</span>
                <span class="wf-live-sep">|</span>
                <span>{{ msg.workflow._liveStats.rate }} t/s</span>
              </div>
              <!-- 需求拆解编辑区（仅审核阶段展示，避免与步骤内容重复） -->
              <div v-if="msg.workflow.status === 'WAITING_REVIEW' && msg.workflow.state" class="wf-review-area">
                <div class="wf-review-label">需求拆解项（可逐条选择调整）</div>
                <div v-for="(item, di) in getDecompItems(msg)" :key="di" class="wf-item-row" :class="{ selected: item._selected }">
                  <el-checkbox v-model="item._selected" class="wf-item-check" :disabled="isWorkflowDone(msg)" />
                  <div class="wf-item-body">
                    <div class="wf-item-title">{{ item.title }}</div>
                    <div v-if="item.desc" class="wf-item-desc">{{ item.desc }}</div>
                  </div>
                  <el-button v-if="!isWorkflowDone(msg)" size="small" text type="primary" @click="editDecompItem(msg, di)">调整</el-button>
                </div>
                <div v-if="getDecompItems(msg).length === 0" style="font-size:12px;color:#999;text-align:center;padding:10px;">未能解析需求拆解项，请查看原始结果</div>
                <!-- 原始拆解结果折叠 -->
                <div v-if="msg.workflow.state.decomposition_result" class="wf-review-block" style="margin-top:8px;">
                  <div class="wf-review-block-header" @click="toggleReviewSection(msg, 'decomp')">
                    <span class="wf-toggle-icon">{{ getReviewToggle(msg, 'decomp') ? '\u25BC' : '\u25B6' }}</span>
                    <span>查看原始拆解结果</span>
                  </div>
                  <div v-if="getReviewToggle(msg, 'decomp')" class="wf-review-block-body markdown" v-html="renderMarkdown(String(msg.workflow.state.decomposition_result))"></div>
                </div>
                <!-- 推理结果折叠 -->
                <div v-if="msg.workflow.state.parallel_reasoning_result" class="wf-review-block">
                  <div class="wf-review-block-header" @click="toggleReviewSection(msg, 'reason')">
                    <span class="wf-toggle-icon">{{ getReviewToggle(msg, 'reason') ? '\u25BC' : '\u25B6' }}</span>
                    <span>并行推理汇总</span>
                  </div>
                  <div v-if="getReviewToggle(msg, 'reason')" class="wf-review-block-body markdown" v-html="renderMarkdown(String(msg.workflow.state.parallel_reasoning_result))"></div>
                </div>
              </div>
              <div class="wf-review-bar">
                <strong>{{ msg.workflow.status === 'WAITING_REVIEW' ? '⏸ 等待人工审核' : isWorkflowDone(msg) ? '✅ 工作流已结束' : '🔄 工作流执行中' }}</strong>
                <el-input v-model="msg.workflow._comment" type="textarea" :rows="2" placeholder="审核备注（可选）" class="wf-comment" />
                <div class="wf-review-btns">
                  <el-button v-if="canRecoverWorkflow(msg)" type="warning" size="small" :loading="msg.workflow._recovering" @click="recoverWorkflow(i)">🔄 恢复执行</el-button>
                  <template v-if="msg.workflow._codegenDone">
                    <el-button type="primary" size="small" @click="downloadProject(i)">📥 下载</el-button>
                    <el-button type="success" size="small" @click="deployProject(i)">🚀 运行部署</el-button>
                  </template>
                  <template v-else>
                    <el-button type="success" size="small" :disabled="msg.workflow.status !== 'WAITING_REVIEW' || msg.workflow._reviewing" :loading="msg.workflow._reviewing === 'APPROVED'" @click="resumeWorkflow(i, 'APPROVED')">✓ 通过</el-button>
                    <el-button type="warning" size="small" :disabled="msg.workflow.status !== 'WAITING_REVIEW' || msg.workflow._reviewing" :loading="msg.workflow._reviewing === 'SENT_BACK'" @click="resumeWorkflow(i, 'SENT_BACK')">↩ 继续修复</el-button>
                    <el-button v-if="!isWorkflowDone(msg)" type="danger" size="small" :disabled="!!msg.workflow._reviewing" :loading="msg.workflow._reviewing === 'TERMINATED'" @click="resumeWorkflow(i, 'TERMINATED')">✗ 结束</el-button>
                  </template>
                </div>
              </div>
              <div v-if="isWorkflowDone(msg)" class="wf-final-status" :class="'wf-final-' + msg.workflow.status.toLowerCase()">
                <strong>{{ finalStatusIcon(msg) }} 工作流{{ msg.workflow.status }}</strong>
              </div>
              <div v-if="isWorkflowDone(msg) && msg.workflow._tokenStats" class="wf-token-stats">
                <div class="wf-stats-header"><span class="wf-stats-icon">📊</span><span>Token 使用统计</span></div>
                <div class="wf-stats-summary">
                  <div class="wf-stats-item"><span class="wf-stats-label">总 Token</span><span class="wf-stats-value">{{ fmtTokenNum(msg.workflow._tokenStats.totalTokens) }}</span></div>
                  <div class="wf-stats-item"><span class="wf-stats-label">输出 Token</span><span class="wf-stats-value">{{ fmtTokenNum(msg.workflow._tokenStats.completionTokens) }}</span></div>
                  <div class="wf-stats-item"><span class="wf-stats-label">总耗时</span><span class="wf-stats-value">{{ fmtElapsed(msg.workflow._tokenStats.totalDurationMs) }}</span></div>
                  <div class="wf-stats-item"><span class="wf-stats-label">吞吐速率</span><span class="wf-stats-value">{{ fmtTokenRate(msg.workflow._tokenStats.totalTokens, msg.workflow._tokenStats.totalDurationMs) }}</span></div>
                </div>
              </div>
            </div>

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
                <!-- 实时统计（流式+完成后都展示） -->
                <span v-if="msg.role === 'assistant' && (msg.status === 'streaming' || msg.status === 'thinking' || msg.status === 'done') && msg._msgStart" class="assistant-elapsed">
                  {{ fmtTime(getMsgElapsed(msg)) }}
                  {{ getMsgTokenInfo(msg) }}
                </span>
                <span v-else-if="msg.elapsed && msg.status !== 'error'" class="assistant-elapsed">
                  {{ fmtTime(msg.elapsed) }}
                  <template v-if="msg._tokenInfo"> · {{ fmtTokenNum(msg._tokenInfo.tokens) }} tokens · {{ msg._tokenInfo.rate }} t/s</template>
                </span>
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
import { ElMessage, ElInput, ElButton } from "element-plus"
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
import { routeChatMessage, startWorkflowApi, getWorkflowState, resumeWorkflowApi, recoverWorkflowApi, type WorkflowResumeRequest } from "@/api/WorkflowApi"
import { reportTokenUsage, getDailyCumulative, saveConversation, saveWorkflowRecord, getWorkflowRecords, downloadWorkflowCode } from "@/api/ChatApi"
import { ref as vueRef, onMounted as vueMounted, onBeforeUnmount as vueUnmount } from "vue"
import JSZip from "jszip"
import { saveAs } from "file-saver"
import { ElMessageBox } from "element-plus"

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
  _msgStart?: number
  _streamStart?: number
  _tokenInfo?: { tokens: number; elapsed: number; rate: number }
  workflow?: any
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
    if (session.model) {
      // 校验模型是否仍存在，避免使用过期的模型名
      const modelExists = availableModels.value.some(m => m.name === session.model)
      selectedModel.value = modelExists ? session.model : (availableModels.value[0]?.name || session.model)
    }
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
  // 如果为0，尝试全局累计统计
  if (todayTokens.value === 0) {
    try {
      const dc: any = await getDailyCumulative()
      todayTokens.value = (dc.totalPromptTokens || 0) + (dc.totalCompletionTokens || 0)
    } catch { }
  }
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
  if (ms < 1000) return ms + "ms"
  if (ms < 60000) return (ms / 1000).toFixed(1) + "s"
  return Math.floor(ms / 60000) + "m" + Math.round((ms % 60000) / 1000) + "s"
}

/** 实时消息耗时 */
function getMsgElapsed(msg: any): number {
  return msg._tokenInfo?.elapsed || (msg._msgStart ? Date.now() - msg._msgStart : msg.elapsed || 0)
}

/** 实时消息 Token 信息文本 */
function getMsgTokenInfo(msg: any): string {
  if (msg._tokenInfo && msg._tokenInfo.tokens > 0) {
    return ` · ${fmtTokenNum(msg._tokenInfo.tokens)} tokens · ${msg._tokenInfo.rate} t/s`
  }
  if (msg._streamStart && msg.content) {
    const t = Math.ceil(msg.content.length / 2)
    const e = Date.now() - msg._streamStart
    const r = e > 1000 ? Math.round(t / (e / 1000)) : 0
    return t > 0 ? ` · ${fmtTokenNum(t)} tokens · ${r} t/s` : ""
  }
  return ""
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

// 统一实时统计（工作流 + 普通对话）
const liveStats = computed(() => {
  const last = messages[messages.length - 1]
  if (!last) return null
  // 工作流
  if (last.workflow && last.workflow._liveStats && !isWorkflowDone(last)) {
    return last.workflow._liveStats
  }
  // 普通对话流式输出中
  if (last.role === "assistant" && (last.status === "streaming" || last.status === "thinking") && last.content) {
    const elapsed = last._msgStart ? Date.now() - last._msgStart : 0
    const tokens = Math.ceil(last.content.length / 2)
    const rate = elapsed > 1000 ? Math.round(tokens / (elapsed / 1000)) : 0
    return { tokens, elapsed, rate }
  }
  return null
})

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
  // 使用双 requestAnimationFrame 确保 DOM 完全渲染后再滚动
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight
      scrollState.atBottom = true
      scrollState.userScrolled = false
      scrollState.showJumpButton = false
      scrollMark += 1
    })
  })
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
  await nextTick(); jumpToBottom()
  const sendSid = sessionStore.currentSessionId || chatSessionId.value
  saveConversation(sendSid, [{ role: "user", content: text }]).catch(() => {})
  await sessionStore.addMessage(sessionStore.currentSessionId!, messages[messages.length - 1])

  // 路由检测：判断是工作流还是普通对话
  try {
    const route = await routeChatMessage(text)
    if (route.type === "workflow") { await startWorkflowFlow(text); return }
  } catch { /* 路由失败走普通对话 */ }

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
  let hasReceivedData = false
  messages[idx]._msgStart = msgStart

  const finish = () => {
    const now = Date.now()
    if (messages[idx]) {
      messages[idx].loading = false
      messages[idx].status = messages[idx].status === "error" ? "error" : "done"
      messages[idx].elapsed = now - msgStart
      // 计算对话的 Token 统计
      const contentLen = (messages[idx].content || "").length
      const tokens = Math.ceil(contentLen / 2)
      messages[idx]._tokenInfo = { tokens, elapsed: messages[idx].elapsed, rate: messages[idx].elapsed > 1000 ? Math.round(tokens / (messages[idx].elapsed / 1000)) : 0 }

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
          messages[idx]._streamStart = streamStart
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

watch(() => messages.length, () => jumpToBottom())

onMounted(async () => {
  await sessionStore.init()
  await fetchModels()
  if (sessionStore.currentSessionId) {
    const session = sessionStore.getSession(sessionStore.currentSessionId)
    if (session) {
      // 校验模型，过期模型名用第一个可用模型替代
      const modelExists = availableModels.value.some(m => m.name === session.model)
      selectedModel.value = modelExists ? session.model : (availableModels.value[0]?.name || session.model)
      session.messages.forEach(m => messages.push({ ...m, status: (m.status as MessageStatus) || "done" }))
      // 校验历史工作流消息的真实状态（localStorage 中可能存了过期的中间态）
      refreshHistoricalWorkflowStatus()
    }
  }
  // 从 workflow_record 表补充恢复历史工作流卡片（逐条需求显示，多轮工作流每条记录一张卡片）
  await loadWFHistory()
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

// ==================== 工作流功能 ====================
// 2026-08-13 从 ChatIndex.vue 迁移到 AgentChat.vue，并新增以下能力：
//   1. 智能体工作流卡片（需求拆解 → 并行推理 → 人工审核 → 代码生成）
//   2. SSE 增量流式内容（_delta 追加）
//   3. 每轮 Token 统计（总/输出/耗时/速率）+ 实时速率（首字到达时间 + 增量速率）
//   4. 需求拆解项可编辑（勾选 + 调整）
//   5. 审核按钮始终展示，思考中禁用
//   6. 工作流历史记录 MySQL 持久化 + 回显（历史卡片内容默认折叠）
//   7. 前端动态选择模型（通过 startWorkflowApi 传 modelName）
//   8. 代码生成（通过后）→ JSZip 打包 → 下载 / Docker 部署
const WORKFLOW_STEPS = [
  { key: "requirement", name: "工作流初始化" }, { key: "decomposition_result", name: "需求拆解" },
  { key: "_reasoning", name: "并行推理" }, { key: "parallel_reasoning_result", name: "合并推理结果" },
  { key: "_review", name: "等待人工审核" },
]
const STATUS_TEXT: Record<string, string> = { RUNNING: "执行中", WAITING_REVIEW: "等待审核", COMPLETED: "已完成", TERMINATED: "已终止", FAILED: "已失败" }
const sseConnections = new Map<number, { es: any; poll: any; delayTimer: any; statsTimer: any }>()
const STORED_SESSION_KEY = "agenthub_workflow_session"
const chatSessionId = vueRef(localStorage.getItem(STORED_SESSION_KEY) || ("wf_" + Date.now() + "_" + Math.random().toString(36).slice(2, 6)))
let wfAbortController: AbortController | null = null

function statusText(s: string) { return STATUS_TEXT[s] || s }
function isWorkflowDone(msg: any) { const s = msg.workflow?.status; return s === "COMPLETED" || s === "TERMINATED" || s === "FAILED" }
function finalStatusIcon(msg: any) { const m: Record<string, string> = { COMPLETED: "\u2705", TERMINATED: "\uD83D\uDED1", FAILED: "\u274C" }; return m[msg.workflow?.status || ""] || "" }
function fmtTokenNum(n: number) { return n >= 1000 ? (n / 1000).toFixed(1) + "k" : String(n) }
/** 2026-08-13 截断长文本（保留纯文本预览，去掉 markdown 标记） */
function truncateContent(text: string, maxLen: number): string {
  if (!text) return ""
  const plain = text.replace(/```[\s\S]*?```/g, "[代码块]").replace(/[#*`>|]/g, "").replace(/\s+/g, " ").trim()
  return plain.length > maxLen ? plain.slice(0, maxLen) : plain
}
function fmtElapsed(ms: number) { if (!ms || ms <= 0) return "--"; if (ms < 1000) return ms + "ms"; if (ms < 60000) return (ms / 1000).toFixed(1) + "s"; const m = Math.floor(ms / 60000); const s = Math.round((ms % 60000) / 1000); return m + "m" + s + "s" }
function fmtTokenRate(tokens: number, ms: number) { if (!tokens || !ms || ms <= 0) return "--"; return (tokens / (ms / 1000)).toFixed(1) + " token/s" }

function getStepContent(state: any, stepKey: string): string {
  if (stepKey === "_reasoning") {
    const parts: string[] = []
    for (const k of Object.keys(state).sort()) {
      if (/^reasoning_.+_result$/.test(k) && state[k]) parts.push(state[k])
    }
    return parts.join("\n\n")
  }
  if (stepKey === "_review") return state["review_content"] || ""
  return state[stepKey] || ""
}

/** 判断步骤是否完成（处理特殊 key：_reasoning 聚合多个 reasoning_*_result，_review 看审核状态） */
function checkStepDone(state: any, stepKey: string): boolean {
  if (stepKey === "_reasoning") {
    // 有任一 reasoning_*_result 内容 或 已有合并结果
    return Object.keys(state).some(k => /^reasoning_.+_result$/.test(k) && state[k])
      || !!state["parallel_reasoning_result"]
  }
  if (stepKey === "_review") {
    const d = state["review_decision"]
    return state["workflow_status"] === "WAITING_REVIEW" || d === "APPROVED" || d === "TERMINATED" || d === "SENT_BACK"
  }
  return state[stepKey] != null && String(state[stepKey]).length > 0
}

function buildProgressFull(state: any, activeKey: string | null, sseLU: any = null) {
  if (!state) return WORKFLOW_STEPS.map(s => ({ key: s.key, name: s.name, done: false, active: false }))
  const ws = state["workflow_status"] || ""; const isTerminal = ws === "COMPLETED" || ws === "TERMINATED" || ws === "FAILED"
  if (isTerminal) return WORKFLOW_STEPS.map(step => ({ key: step.key, name: step.name, done: true, active: false, _content: getStepContent(state, step.key) }))
  return WORKFLOW_STEPS.map(step => {
    const done = checkStepDone(state, step.key)
    const active = step.key === activeKey
    return { key: step.key, name: step.name, done, active, _content: (done || active) ? getStepContent(state, step.key) : "" }
  })
}

function closeSSE(idx: number) { const c = sseConnections.get(idx); if (c) { if (c.es) c.es.close(); if (c.poll) clearInterval(c.poll); if (c.delayTimer) clearTimeout(c.delayTimer); if (c.statsTimer) clearInterval(c.statsTimer); sseConnections.delete(idx) } }

function connectSSE(msgIndex: number, threadId: string, preserveContent: boolean) {
  const msg = messages[msgIndex]; if (!msg?.workflow) return
  closeSSE(msgIndex)
  const state: Record<string, any> = {}; let lastKey: string | null = null; let errCount = 0
  const sseLU: Record<string, number> = {}; const stepST: Record<string, number> = {}
  const conn = { es: null as any, poll: null as any, delayTimer: null as any, statsTimer: null as any }; sseConnections.set(msgIndex, conn)

  // 实时统计：每秒刷新响应时间和速率（无内容时速率显示 0）
  function refreshStats() {
    if (!msg?.workflow) return
    let liveTokens = 0
    msg.workflow.progress.forEach((s: any) => { if (s._content) liveTokens += Math.ceil(String(s._content).length / 2) })
    if (liveTokens > 0 && !msg.workflow._firstContentTime) msg.workflow._firstContentTime = Date.now()
    const now = Date.now()
    const elapsed = msg.workflow._startTime ? now - msg.workflow._startTime : 0
    // 首次调用初始化基线
    if (msg.workflow._lastRateTime == null) {
      msg.workflow._lastRateTime = now
      msg.workflow._lastTokens = liveTokens
    }
    const deltaT = now - msg.workflow._lastRateTime
    // 保留上次速率，仅在约 1 秒窗口更新（避免每次 SSE 事件重置为 0）
    let rate = msg.workflow._liveStats?.rate || 0
    if (deltaT >= 800) {
      const deltaTok = liveTokens - msg.workflow._lastTokens
      rate = deltaTok > 0 ? Math.round(deltaTok / (deltaT / 1000)) : 0
      msg.workflow._lastTokens = liveTokens
      msg.workflow._lastRateTime = now
    }
    msg.workflow._liveStats = { tokens: liveTokens, elapsed, rate }
  }

  function render() {
    if (!msg?.workflow) return
    const ws = state["workflow_status"]; if (ws) msg.workflow.status = ws
    let activeKey: string | null = null
    if (ws !== "WAITING_REVIEW" && ws !== "COMPLETED" && ws !== "TERMINATED" && ws !== "FAILED") {
      for (const step of WORKFLOW_STEPS) { if (!state[step.key] && step.key !== "_review") { activeKey = step.key; break } }
    }
    msg.workflow.progress = buildProgressFull(state, activeKey, sseLU)
    for (const s of msg.workflow.progress) { if (s.done && !s._elapsed && stepST[s.key]) s._elapsed = Date.now() - stepST[s.key]; if (s.active && !stepST[s.key]) stepST[s.key] = Date.now() }
    msg.workflow.state = { ...state }
    refreshStats()
    // SSE 流式更新时自动滚动
    jumpToBottom()
  }

  if (preserveContent) { getWorkflowState(threadId).then(r => { if (r.state) Object.assign(state, r.state); render() }).catch(() => {}) }
  else render()

  // 每秒定时刷新响应时间和速率（即使无 SSE 事件也更新耗时）
  conn.statsTimer = setInterval(() => { if (sseConnections.has(msgIndex)) refreshStats() }, 1000)

  function connect() {
    conn.es = new EventSource("/api/v1/workflow/events/" + encodeURIComponent(threadId))
    conn.es.onmessage = function (e: MessageEvent) {
      try {
        const d = JSON.parse(e.data)
        if (d.key && d.value != null) {
          const isDelta = !!d._delta
          if (isDelta) {
            // 增量更新：追加到已有内容
            state[d.key] = (state[d.key] || "") + d.value
          } else {
            // 全量覆盖（推理空信号不覆盖已有内容）
            if (/^reasoning_.+_result$/.test(d.key) && d.value === "" && state[d.key]) { /* skip */ }
            else { state[d.key] = d.value }
          }
          if (d.value.length > 0) { lastKey = d.key; sseLU[d.key] = Date.now() }
          if (/^reasoning_.+_result$/.test(d.key)) {
            lastKey = d.key; sseLU[d.key] = Date.now(); sseLU["_reasoning"] = Date.now()
          }
          render(); errCount = 0
          if (d.key === "workflow_status" && d.status && d.status !== "RUNNING") handleWFEnd(msgIndex, threadId, d.status)
        }
      } catch { }
    }
    conn.es.onerror = function () { if (conn.es) { conn.es.close(); conn.es = null }; if (!sseConnections.has(msgIndex)) return; getWorkflowState(threadId).then(r => { if (!sseConnections.has(msgIndex)) return; if (r.status === "RUNNING" && errCount < 5) { errCount++; setTimeout(connect, 1000) } else handleWFEnd(msgIndex, threadId, r.status) }).catch(() => handleWFEnd(msgIndex, threadId, null)) }
  }
  connect()
  conn.delayTimer = setTimeout(() => { conn.poll = setInterval(() => { if (!sseConnections.has(msgIndex)) { clearInterval(conn.poll); return }; getWorkflowState(threadId).then(r => { if (r.state) { Object.assign(state, r.state); render() } }).catch(() => {}) }, 2000) }, 3000)
}

function handleWFEnd(msgIndex: number, threadId: string, knownStatus: string | null) {
  if (!sseConnections.has(msgIndex)) return; const msg = messages[msgIndex]; if (!msg?.workflow || msg.workflow._finalStatus) return
  closeSSE(msgIndex); if (knownStatus) msg.workflow.status = knownStatus
  getWorkflowState(threadId).then(r => {
    // 保存已有耗时
    const oldElapsed: Record<string, number> = {}
    msg.workflow!.progress?.forEach((s: any) => { if (s._elapsed) oldElapsed[s.key] = s._elapsed })
    if (r.state) { r.state["workflow_status"] = r.status; msg.workflow!.progress = buildProgressFull(r.state, null, null); msg.workflow!.state = r.state }
    // 恢复耗时
    msg.workflow!.progress?.forEach((s: any) => { if (oldElapsed[s.key]) s._elapsed = oldElapsed[s.key] })
    msg.workflow!.status = r.status
    if (r.status === "WAITING_REVIEW") msg.workflow!._finalStatus = false
    else if (r.status === "RUNNING") connectSSE(msgIndex, threadId, true)
    else {
      msg.workflow!._finalStatus = true; msg.workflow!.progress = buildProgressFull(r.state || msg.workflow!.state, null, null);
      msg.workflow!.progress?.forEach((s: any) => { if (oldElapsed[s.key]) s._elapsed = oldElapsed[s.key] });
      // COMPLETED：后端工作流已生成并写出代码（Git/本地目录），开启下载/部署按钮（走后端 download-code）
      if (r.status === "COMPLETED") {
        msg.workflow!._codegenDone = true
        msg.workflow!._threadIdForDownload = threadId
      }
      reportWF(msg)
      scheduleSave()
    }
  }).catch(() => {})
}

function reportWF(msg: any) {
  if (!msg.workflow) return
  let totalComp = 0
  const now = Date.now()
  const totalDur = msg.workflow._startTime ? now - msg.workflow._startTime : 0
  const ss: Record<string, any> = {}
  // 优先从 progress 步骤计算，为空时从 state 直接计算
  const state = msg.workflow.state || {}
  msg.workflow.progress?.forEach((s: any) => {
    const content = s._content || state[s.key] || ""
    if (s.done && content) {
      const t = Math.ceil(String(content).length / 2)
      totalComp += t
      ss[s.key] = { tokens: t, elapsed: s._elapsed || 0 }
    }
  })
  // 如果 progress 中无内容，直接从 state 聚合
  if (totalComp === 0 && msg.workflow.state) {
    for (const [key, val] of Object.entries(msg.workflow.state)) {
      if (typeof val === "string" && val.length > 50) {
        const t = Math.ceil(val.length / 2)
        totalComp += t
        if (!ss[key]) ss[key] = { tokens: t, elapsed: 0 }
      }
    }
  }
  msg.workflow._tokenStats = { promptTokens: 0, completionTokens: totalComp, totalTokens: totalComp, totalDurationMs: totalDur, stepStats: Object.keys(ss).length ? ss : undefined }
  const st = msg.workflow.state || {}
  // 上报 token 使用量到后端统计
  reportTokenUsage({ modelName: selectedModel.value || "unknown", promptTokens: 0, completionTokens: totalComp, totalDurationMs: totalDur, source: "workflow" }).catch(() => {})
  // 刷新今日 token 显示
  refreshTokenUsage()
  // 使用 sessionStore 的 session ID 保存（确保与加载一致）
  const saveSid = sessionStore.currentSessionId || chatSessionId.value
  saveWorkflowRecord({ sessionId: saveSid, threadId: msg.workflow.threadId, round: msg.workflow._round || 1, requirement: msg.workflow._requirement || "", status: msg.workflow.status, decompositionResult: st.decomposition_result || null, reasoningResult: st.parallel_reasoning_result || null, reviewDecision: st.review_decision || null, reviewComment: msg.workflow._comment || null, codegenFiles: st.generated_files ? JSON.stringify(st.generated_files) : null, promptTokens: 0, completionTokens: totalComp, totalDurationMs: totalDur, workflowMessage: st.workflow_message || null, startTime: msg.workflow._startTime ? new Date(msg.workflow._startTime).toISOString() : null, endTime: new Date().toISOString() }).catch(() => {})
  saveConversation(saveSid, [{ role: "assistant", content: "[工作流结束]" }]).catch(() => {})
}

async function startWorkflowFlow(requirement: string) {
  try {
    const result = await startWorkflowApi(requirement, selectedModel.value)
    const msg = { id: Date.now(), role: "assistant" as const, content: "", status: "done" as const, workflow: { threadId: result.threadId, status: result.status, progress: buildProgressFull(result.state, null, null), state: result.state, _comment: "", _showDecomp: false, _showReason: false, _finalStatus: false, _round: 1, _groupId: result.threadId, _startTime: Date.now(), _requirement: requirement, _liveStats: { tokens: 0, elapsed: 0, rate: 0 } } }
    messages.push(msg); await nextTick(); jumpToBottom()
    saveConversation(chatSessionId.value, [{ role: "assistant", content: "[工作流启动] " + requirement }]).catch(() => {})
    // 2026-08-13 持久化工作流卡片到 sessionStore，刷新后按正确顺序回显
    scheduleSave()
    connectSSE(messages.length - 1, result.threadId, false)
  } catch (err: any) { ElMessage.error("启动工作流失败：" + (err.message || "未知错误")) }
}

async function resumeWorkflow(msgIndex: number, decision: any) {
  const msg = messages[msgIndex]; if (!msg?.workflow) return
  // 防重复提交：请求进行中禁用所有审核按钮
  if (msg.workflow._reviewing) return
  msg.workflow._reviewing = decision
  const req = { threadId: msg.workflow.threadId, reviewDecision: decision, comment: msg.workflow._comment || undefined, modelName: selectedModel.value || undefined }
  try {
    const result = await resumeWorkflowApi(req)
    if (decision === "APPROVED" && result.status === "RUNNING") {
      // 原审核卡片标记为已完成（本轮需求审核通过）
      msg.workflow.status = "COMPLETED"; msg.workflow._finalStatus = true
      msg.workflow.progress = buildProgressFull(result.state, null, null)
      reportWF(msg)
      // 先保存当前会话页签（含已完成的审核卡片），再新建页签承载代码生成
      const origSid = sessionStore.currentSessionId || chatSessionId.value
      if (origSid) await sessionStore.saveMessages(origSid, [...messages])
      // 通过 → 新建页签（代码生成逻辑在新页签继续，不在需求页复制）
      await sessionStore.createSession(selectedModel.value)
      messages.splice(0)
      const round = (msg.workflow._round || 1) + 1
      const codeMsg = { id: Date.now(), role: "assistant" as const, content: "", status: "done" as const, workflow: {
        threadId: result.threadId, status: "RUNNING", progress: buildProgressFull(null, null, null),
        state: result.state || null, _comment: "", _showDecomp: false, _showReason: false,
        _finalStatus: false, _round: round, _groupId: msg.workflow._groupId,
        _startTime: Date.now(), _requirement: msg.workflow._requirement,
        _liveStats: { tokens: 0, elapsed: 0, rate: 0 }
      } }
      messages.push(codeMsg)
      await nextTick(); jumpToBottom()
      scheduleSave()
      connectSSE(messages.length - 1, result.threadId, false)
      ElMessage.success("已通过，已在新页签开始生成代码...")
      return
    }
    // 保存上一轮的完整 state（SENT_BACK 时用于保留数据展示）
    const prevState = msg.workflow.state
    msg.workflow.status = result.status; msg.workflow.state = result.state || msg.workflow.state
    if (result.status === "RUNNING") {
      if (decision === "SENT_BACK") {
        // 2026-08-13 继续修复：在同一线程基础上继续，轮次+1，上一轮数据保留显示
        msg.workflow.status = "COMPLETED"; msg.workflow._finalStatus = true
        // 用上一轮保存的 state 重建进度并恢复 state（保留完整数据），而非 result.state（RUNNING 新状态）
        msg.workflow.state = prevState || msg.workflow.state
        msg.workflow.progress = buildProgressFull(msg.workflow.state, null, null)
        reportWF(msg)
        const round = (msg.workflow._round || 1) + 1
        const newMsg = { id: Date.now(), role: "assistant" as const, content: "", status: "done" as const, workflow: {
          threadId: result.threadId, status: "RUNNING", progress: buildProgressFull(null, null, null),
          state: null, _comment: "", _showDecomp: false, _showReason: false,
          _finalStatus: false, _round: round, _groupId: msg.workflow._groupId,
          _startTime: Date.now(), _requirement: msg.workflow._requirement, _newRound: true,
          _liveStats: { tokens: 0, elapsed: 0, rate: 0 }
        } }
        messages.push(newMsg); await nextTick(); jumpToBottom()
        scheduleSave()
        connectSSE(messages.length - 1, result.threadId, false)
        ElMessage.info("已进入第 " + round + " 轮继续修复")
        return
      }
      connectSSE(msgIndex, result.threadId, false)
    } else if (result.status === "COMPLETED" || result.status === "TERMINATED") {
      msg.workflow._finalStatus = true; msg.workflow.progress = buildProgressFull(result.state, null)
      // 持久化终态：MySQL 记录 + localStorage（避免刷新后恢复成旧状态）
      reportWF(msg)
      scheduleSave()
    }
  } catch (err: any) {
    ElMessage.error("审核失败：" + (err.message || "未知错误"))
  } finally {
    msg.workflow._reviewing = null
  }
}

/** FAILED / TERMINATED 状态可恢复执行 */
function canRecoverWorkflow(msg: any): boolean {
  const s = msg?.workflow?.status; return s === "FAILED" || s === "TERMINATED"
}

/** 恢复执行失败/强制结束的工作流（后端从 checkpoint 续跑） */
async function recoverWorkflow(msgIndex: number) {
  const msg = messages[msgIndex]; if (!msg?.workflow || msg.workflow._recovering) return
  try {
    await ElMessageBox.confirm(`确定从 checkpoint 恢复执行该工作流吗？`, "恢复执行", { type: "warning", confirmButtonText: "恢复", cancelButtonText: "取消" })
  } catch { return }
  msg.workflow._recovering = true
  try {
    const result = await recoverWorkflowApi(msg.workflow.threadId)
    if (result.status === "RUNNING") {
      msg.workflow.status = "RUNNING"; msg.workflow._finalStatus = false
      msg.workflow._codegenDone = false; msg.workflow.state = result.state || msg.workflow.state
      msg.workflow.progress = buildProgressFull(msg.workflow.state || null, null, null)
      msg.workflow._liveStats = { tokens: 0, elapsed: 0, rate: 0 }
      msg.workflow._lastRateTime = null; msg.workflow._lastTokens = 0
      msg.workflow._startTime = Date.now()
      reportWF(msg)
      scheduleSave()
      connectSSE(msgIndex, msg.workflow.threadId, false)
      ElMessage.success(result.message || "工作流已恢复执行")
    } else {
      ElMessage.error(result.message || "恢复执行失败")
    }
  } catch (err: any) {
    ElMessage.error("恢复执行失败：" + (err.message || "未知错误"))
  } finally {
    msg.workflow._recovering = null
  }
}

/**
 * 2026-08-13 优化需求：将原始需求 + 驳回意见发给 AI，重新优化需求细节。
 * 返回优化后的需求文本。
 */
async function optimizeRequirement(requirement: string, comment: string): Promise<string> {
  const prompt = `你是需求分析师。请根据原始需求和驳回意见，重新优化需求描述，使其更清晰、完整、可执行。只输出优化后的需求，禁止任何解释。

原始需求：${requirement}

驳回意见：${comment || "需求不够清晰"}

优化后的需求：`

  const model = selectedModel.value || "qwen3:8b"
  const reqUrl = `${BASE_URL}/chat2/stream?model=${encodeURIComponent(model)}&message=${encodeURIComponent(prompt)}&maxTokens=1024`
  const token = localStorage.getItem("token") || ""
  let full = ""

  const response = await fetch(reqUrl, { method: "POST", headers: { Authorization: `Bearer ${token}` } })
  if (!response.ok) throw new Error("HTTP " + response.status)
  const reader = response.body?.getReader(); if (!reader) throw new Error("No reader")
  const decoder = new TextDecoder(); let buf = ""
  let currentEvent = ""
  while (true) {
    const { done, value } = await reader.read(); if (done) break
    buf += decoder.decode(value, { stream: true })
    const lines = buf.split("\n"); buf = lines.pop() || ""
    for (const raw of lines) {
      const line = raw.replace(/\r$/, "")
      if (line.startsWith("event:")) { currentEvent = line.slice(6).trim(); continue }
      if (line.startsWith("data:")) {
        let data = line[5] === " " ? line.slice(6) : line.slice(5)
        if (data === "[DONE]") continue
        try {
          if ((data.startsWith('"') && data.endsWith('"')) || data.startsWith("\\")) {
            data = JSON.parse(data)
          }
        } catch { }
        if (currentEvent === "content" || currentEvent === "" || !currentEvent) full += data
      }
    }
  }
  return full.trim() || requirement
}

/**
 * 校验历史工作流消息的真实状态。
 * localStorage 中可能存了工作流运行中的中间态（如 WAITING_REVIEW），
 * 若后端已是终态，则纠正展示状态。
 */
async function refreshHistoricalWorkflowStatus() {
  // 第一步：本地已是终态的消息直接重建 progress（不依赖后端，确保不残留"思考中"活跃步骤）
  messages.forEach(msg => {
    if (!msg.workflow || !msg.workflow.threadId) return
    if (msg.workflow._finalStatus && (msg.workflow.status === "COMPLETED" || msg.workflow.status === "TERMINATED" || msg.workflow.status === "FAILED")) {
      const state = msg.workflow.state || { workflow_status: msg.workflow.status }
      msg.workflow.progress = buildProgressFull(state, null, null)
    }
  })
  // 第二步：向后端查询真实状态纠正（覆盖 localStorage 中过期的中间态）
  const tasks: Promise<void>[] = []
  messages.forEach((msg, idx) => {
    if (!msg.workflow || !msg.workflow.threadId) return
    tasks.push(getWorkflowState(msg.workflow.threadId).then(st => {
      if (st && st.status) {
        const real = st.status
        const wf = messages[idx]?.workflow
        if (!wf) return
        if (real === "COMPLETED" || real === "TERMINATED" || real === "FAILED") {
          wf.status = real
          wf._finalStatus = true
          if (st.state) wf.state = st.state
          // 终态时重建进度：全部步骤置为完成，避免残留"思考中"的活跃步骤
          wf.progress = buildProgressFull(st.state || wf.state, null, null)
        } else if (!wf._finalStatus && real !== wf.status) {
          // 非终态但状态已变化：更新状态
          wf.status = real
        }
      }
    }).catch(() => {}))
  })
  if (tasks.length > 0) {
    await Promise.all(tasks)
    nextTick(() => jumpToBottom?.())
  }
}

async function loadWFHistory() {
  try {
    let sid = chatSessionId.value
    // 优先用 sessionStore 的 session ID（与聊天 session 共享）
    if (sessionStore.currentSessionId) sid = sessionStore.currentSessionId
    // 普通对话消息已由 sessionStore（localStorage）恢复，这里只补充 workflow_record 表中的历史工作流记录
    const wfs = await getWorkflowRecords(sid).catch(() => [])
    if (!wfs || wfs.length === 0) return
    // 已存在于 messages 中的工作流 threadId+round（避免与 localStorage 恢复的卡片重复）
    const existing = new Set(messages
      .filter(m => m.workflow && m.workflow.threadId)
      .map(m => `${m.workflow!.threadId}_${m.workflow!._round || 1}`))
    const items: any[] = []
    for (const r of wfs) items.push({ type: "wf", time: new Date(r.startTime || r.createTime).getTime(), data: r })
    items.sort((a: any, b: any) => a.time - b.time)
    for (const item of items) {
      const r = item.data
      const key = `${r.threadId}_${r.round || 1}`
      if (existing.has(key)) continue
      existing.add(key)
      // 向后端查询真实状态，纠正 workflow_record 中可能过期的状态（如终态未持久化）
      let realStatus = r.status
      try {
        const st = await getWorkflowState(r.threadId)
        if (st && st.status) realStatus = st.status
      } catch { /* 查询失败时用记录中的状态 */ }
      // 重建工作流卡片的状态数据
      const histState: any = { workflow_status: realStatus }
      if (r.decompositionResult) histState["decomposition_result"] = r.decompositionResult
      if (r.reasoningResult) histState["parallel_reasoning_result"] = r.reasoningResult
      if (r.reviewDecision) histState["review_decision"] = r.reviewDecision
      if (r.workflowMessage) histState["workflow_message"] = r.workflowMessage
      // 用状态数据重建进度（终态=全部完成并填充内容）
      const histProgress = buildProgressFull(histState, null, null)
      // 当前会话的记录不标记 _isHistorical，刷新后按正常对话展示
      messages.push({ id: Date.now(), role: "assistant" as const, content: "", status: "done" as const, workflow: {
        threadId: r.threadId, status: realStatus, progress: histProgress,
        state: histState, _comment: r.reviewComment || "", _showDecomp: false, _showReason: false,
        _finalStatus: true, _round: r.round || 1, _groupId: r.threadId,
        _startTime: r.startTime ? new Date(r.startTime).getTime() : undefined, _requirement: r.requirement,
        _tokenStats: { promptTokens: r.promptTokens || 0, completionTokens: r.completionTokens || 0,
          totalTokens: (r.promptTokens || 0) + (r.completionTokens || 0), totalDurationMs: r.totalDurationMs || 0 }
      } })
    }
  } catch (e) { console.error("[loadWFHistory] 加载历史失败:", e) }
}

interface DecompItem { title: string; desc: string; _selected: boolean }

function parseDecompItems(state: Record<string, any>): DecompItem[] {
  const raw = state?.decomposition_result; if (!raw || typeof raw !== "string") return []
  const lines = raw.trim().split("\n"); const items: DecompItem[] = []
  let curTitle = ""; let curDesc: string[] = []
  for (const line of lines) {
    const t = line.trim(); if (!t) continue
    if (/^(\d+)[\.\、\)]\s*(.+)/.test(t) || /^[-*]\s+(.+)/.test(t)) {
      if (curTitle) items.push({ title: curTitle, desc: curDesc.join(" ").slice(0, 200), _selected: true })
      curTitle = t.replace(/^(\d+)[\.\、\)]\s*|^[-*]\s+/, ""); curDesc = []
    } else if (curTitle && t.length > 5) curDesc.push(t)
  }
  if (curTitle) items.push({ title: curTitle, desc: curDesc.join(" ").slice(0, 200), _selected: true })
  if (items.length === 0) {
    const ps = raw.split(/\n{2,}/).filter((p: string) => p.trim().length > 10)
    return ps.map((p, i) => ({ title: (i + 1) + ". " + p.slice(0, 80), desc: p.slice(80, 280), _selected: true }))
  }
  return items
}

function getDecompItems(msg: any): DecompItem[] {
  if (!msg.workflow) return []
  if (!msg.workflow._dcParsed || !msg.workflow._dcItems) {
    msg.workflow._dcItems = parseDecompItems(msg.workflow.state || {})
    msg.workflow._dcParsed = true
  }
  return msg.workflow._dcItems || []
}

function editDecompItem(msg: any, idx: number) {
  const items = getDecompItems(msg); if (idx < 0 || idx >= items.length) return
  ElMessageBox.prompt("修改需求项内容", "调整需求项", { confirmButtonText: "确定", cancelButtonText: "取消", inputValue: items[idx].title + (items[idx].desc ? "\n" + items[idx].desc : ""), inputType: "textarea" }).then(({ value }: any) => {
    if (value?.trim()) { items[idx].title = value.trim().split("\n")[0].slice(0, 100); items[idx].desc = value.trim().split("\n").slice(1).join(" ").slice(0, 200); ElMessage.success("已更新需求项") }
  }).catch(() => {})
}

function toggleReviewSection(msg: any, key: string) {
  if (!msg.workflow) return
  const fk = key === "decomp" ? "_showDecomp" : key === "reason" ? "_showReason" : "_show_" + key
  msg.workflow[fk] = !msg.workflow[fk]
}
function getReviewToggle(msg: any, key: string): boolean {
  if (!msg.workflow) return false
  const fk = key === "decomp" ? "_showDecomp" : key === "reason" ? "_showReason" : "_show_" + key
  return !!msg.workflow[fk]
}

// ==================== 代码生成、下载、部署 ====================

/**
 * 代码生成（2026-08-13 新增/优化）。
 *
 * <p>审核通过后调用：收集勾选需求项 → 构造精简 prompt → SSE 流式调用 AI →
 * 解析代码文件 → JSZip 打包 → 支持下载/Docker 部署。</p>
 *
 * <p><b>优化点</b>：prompt 精简（去除冗余推理上下文）、maxTokens=8192 保证完整项目、
 * 流式展示只显示进度不显示原始 FILE 标记。</p>
 */
async function generateCode(msgIndex: number, items: any[], state: Record<string, any>) {
  const msg = messages[msgIndex]
  if (!msg?.workflow) return
  msg.workflow.progress = [{ key: "generate", name: "正在生成代码...", done: false, active: true, _content: "" }]

  if (!items || items.length === 0) {
    ElMessage.warning("请至少勾选一项需求")
    msg.workflow.progress = [{ key: "generate", name: "AI 生成项目代码", done: false, active: false, _content: "失败：未勾选任何需求" }]
    msg.workflow.status = "FAILED"; msg.workflow._finalStatus = true
    return
  }

  const itemsText = items.map((it, i) => (i + 1) + ". " + it.title + (it.desc ? " - " + it.desc : "")).join("\n")
  // 只保留需求拆解作为上下文（精简 prompt 加快响应，去掉冗余的推理上下文）
  const decompResult = String(state?.decomposition_result || "").slice(0, 400)
  const contextBlock = decompResult ? "## 需求拆解\n" + decompResult : ""

  // 2026-08-13 精简代码生成 prompt：去除冗余说明，直接要求输出 FILE: 格式代码
  const prompt = `你是代码生成器，直接输出代码，禁止任何解释说明。每个文件一行路径+代码块：
FILE:src/main/java/com/example/Application.java
\`\`\`java
@SpringBootApplication
public class Application { }
\`\`\`

${contextBlock}

## 需求
${itemsText}

## 必须文件
pom.xml、Application.java、Controller、Service、Entity、application.yml、Dockerfile、docker-compose.yml、README.md

技术：Spring Boot 3 + MyBatis-Plus + MySQL；Dockerfile 用 openjdk:17-jdk-slim；docker-compose.yml 含 app+mysql。只输出 FILE:路径+代码块，禁止其他文字。`

  const codeModel = selectedModel.value || "qwen3:8b"
  const apiKey = appStore.modelType === "network" ? (appStore.apiKey || "") : ""
  const apiUrl = appStore.modelType === "network" ? (appStore.apiUrl || "") : ""
  const keyParam = apiKey ? `&apiKey=${encodeURIComponent(apiKey)}` : ""
  const urlParam = apiUrl ? `&baseUrl=${encodeURIComponent(apiUrl)}` : ""
  const maxPromptLen = 2500
  const truncPrompt = prompt.length > maxPromptLen ? prompt.slice(0, maxPromptLen - 100) + "\n（需求内容已截断，请生成上述文件）" : prompt
  // 2026-08-13 改用 /chat2/stream（POST，JSON 包装 data 保护换行和代码围栏），与普通对话一致
  const reqUrl = `${BASE_URL}/chat2/stream?model=${encodeURIComponent(codeModel)}&message=${encodeURIComponent(truncPrompt)}&maxTokens=8192${keyParam}${urlParam}`
  const token = localStorage.getItem("token") || ""
  let full = ""
  const codeStart = Date.now()  // 代码生成开始时间（2026-08-13 新增：实时统计响应时间）
  let lastRateTime = codeStart, lastTokens = 0, currentRate = 0  // 瞬时速率基线 + 当前速率

  try {
    const response = await fetch(reqUrl, { method: "POST", headers: { Authorization: `Bearer ${token}` } })
    if (!response.ok) throw new Error("HTTP " + response.status)
    const reader = response.body?.getReader(); if (!reader) throw new Error("No reader")
    const decoder = new TextDecoder(); let buf = ""
    let currentEvent = ""
    while (true) {
      const { done, value } = await reader.read(); if (done) break
      buf += decoder.decode(value, { stream: true })
      const lines = buf.split("\n"); buf = lines.pop() || ""
      for (const raw of lines) {
        const line = raw.replace(/\r$/, "")
        if (line.startsWith("event:")) { currentEvent = line.slice(6).trim(); continue }
        if (line.startsWith("data:")) {
          let data = line[5] === " " ? line.slice(6) : line.slice(5)
          if (data === "[DONE]") continue
          try {
            if ((data.startsWith('"') && data.endsWith('"')) || data.startsWith("\\")) {
              data = JSON.parse(data)
            }
          } catch { /* 兼容未包装的原始文本 */ }
          if (currentEvent === "content" || currentEvent === "" || !currentEvent) {
            full += data
            // 实时统计：响应时间 + 瞬时速率（增量 token / 时间差，非累计平均）
            const now = Date.now()
            const elapsed = now - codeStart
            const tokens = Math.ceil(full.length / 2)
            const deltaT = now - lastRateTime
            // 保留上次速率，仅在约 1 秒窗口更新（避免频繁重置为 0）
            if (deltaT >= 800) {
              const deltaTok = tokens - lastTokens
              currentRate = deltaTok > 0 ? Math.round(deltaTok / (deltaT / 1000)) : 0
              lastTokens = tokens
              lastRateTime = now
            }
            msg.workflow!._liveStats = { tokens, elapsed, rate: currentRate }
            msg.workflow!.progress[0]._content = `⏳ 正在生成代码... ${fmtElapsed(elapsed)} · ${tokens} tokens · ${currentRate} t/s`
          }
        }
      }
    }

    const files = parseCodeFilesFromText(full)
    if (files.length === 0) {
      ElMessage.error("AI 未输出代码，请重试")
      msg.workflow!.progress = [{ key: "generate", name: "生成失败", done: false, active: false, _content: "解析失败，AI 未按 FILE: 格式输出代码。原始输出前 1000 字：\n" + full.slice(0, 1000) }]
      msg.workflow.status = "FAILED"; msg.workflow._finalStatus = true
      return
    }

    const zip = new JSZip()
    files.forEach(f => { try { zip.file(f.path, f.content) } catch(e) {} })
    const blob = await zip.generateAsync({ type: "blob" })
    const fileName = "project-" + Date.now() + ".zip"
    // 完成展示：文件清单 + 每个文件的代码预览（干净的代码块）
    const preview = files.slice(0, 5).map(f => "### " + f.path + "\n```\n" + f.content.slice(0, 400) + (f.content.length > 400 ? "\n..." : "") + "\n```").join("\n\n")
    const moreHint = files.length > 5 ? `\n\n... 共 ${files.length} 个文件，全部文件已打包，点击下载查看完整代码` : ""
    msg.workflow!.progress = [{ key: "generate", name: "代码生成完成 " + files.length + " 个文件", done: true, active: false, _content: "✅ 已生成 " + files.length + " 个文件\n\n" + preview + moreHint }]
    msg.workflow.status = "COMPLETED"; msg.workflow._finalStatus = true
    msg.workflow._codegenDone = true; msg.workflow._zipBlob = blob; msg.workflow._zipName = fileName
    msg.workflow.state = { generated_files: files.map(f => f.path), zip_name: fileName }
    ElMessage.success("代码生成完成，点击下载或运行部署")
    await nextTick(); jumpToBottom()
    scheduleSave()
  } catch (err: any) {
    msg.workflow!.progress = [{ key: "generate", name: "生成失败", done: false, active: false, _content: err.message }]
    msg.workflow.status = "FAILED"; msg.workflow._finalStatus = true
    ElMessage.error("代码生成失败：" + (err.message || "未知错误"))
  }
}

/**
 * 从 AI 输出文本解析代码文件（2026-08-13 增强）。
 *
 * <p>支持多种格式：</p>
 * <ol>
 *   <li>{@code FILE:路径 + 代码块}</li>
 *   <li>{@code // filename:路径} / {@code # filename:路径}</li>
 *   <li>Markdown 标题（{@code ### 1.pom.xml（说明）}）+ 代码块</li>
 *   <li>路径行 + 代码块</li>
 *   <li>任意代码块（按语言映射默认路径兜底）</li>
 * </ol>
 * <p>路径自动清理：去除 {@code ###}、序号 {@code 1.}、括号说明。</p>
 */
function parseCodeFilesFromText(full: string): { path: string; content: string }[] {
  const files: { path: string; content: string }[] = []
  const added = new Set<string>()
  function addFile(path: string, content: string) {
    // 清理路径：去掉 Markdown 标题符号、序号、括号说明
    let p = path.trim()
    p = p.replace(/^#{1,6}\s*/, "")                       // 去 ### 标题
    p = p.replace(/^\d+[\.\、\)]\s*/, "")                 // 去序号 1.
    p = p.replace(/[（(][^（()）]{0,60}[)）]\s*$/, "")    // 去括号说明（中文/英文）
    p = p.trim()
    const c = content.trim()
    if (!p || !c || p.length > 200 || c.length < 10) return
    // 只接受看起来像文件路径的（含斜杠、常见扩展名，或 Dockerfile 等无扩展名特殊文件）
    const looksLikePath = /[\/\\]/.test(p)
      || /\.(java|xml|yml|yaml|json|md|sql|js|ts|vue|html|css|py|properties|txt)$/i.test(p)
      || /^dockerfile$/i.test(p)
    if (!looksLikePath) return
    if (added.has(p)) return
    added.add(p); files.push({ path: p, content: c })
  }
  let text = full.replace(/\r\n/g, "\n").replace(/\r/g, "\n").replace(/"""/g, "```").replace(/'''/g, "```")
  const r1 = /FILE:\s*(\S[^\n]{0,200}?)\s*\n```(\w*)\s*\n([\s\S]*?)```/g
  let match
  while ((match = r1.exec(text)) !== null) addFile(match[1], match[3])
  if (files.length > 0) return files
  const r2 = /(?:\/\/|#)\s*filename:\s*(\S[^\n]{0,200}?)\s*\n```(\w*)\s*\n([\s\S]*?)```/g
  while ((match = r2.exec(text)) !== null) addFile(match[1], match[3])
  if (files.length > 0) return files
  // Markdown 标题 + 代码块：### 1.pom.xml\n```xml\n...```
  const r3 = /^#{1,6}\s*(.{0,200}?)\s*\n```(\w*)\s*\n([\s\S]*?)```/gm
  while ((match = r3.exec(text)) !== null) addFile(match[1], match[3])
  if (files.length > 0) return files
  const lines = text.split("\n")
  for (let i = 1; i < lines.length; i++) {
    if (!/^```\w*$/.test(lines[i].trim())) continue
    const prev = lines[i - 1].trim().replace(/^#{1,6}\s*/, "")
    if (/[\/\\]/.test(prev) && prev.length < 200) {
      let j = i + 1
      while (j < lines.length && !/^```\s*$/.test(lines[j].trim())) j++
      if (j < lines.length && j > i + 1) { addFile(prev, lines.slice(i + 1, j).join("\n")); i = j }
    }
  }
  if (files.length > 0) return files
  // 兜底：无 ``` 围栏的 FILE: 格式（AI 丢失围栏时），按下一个 FILE: 标记切分
  const r0 = /FILE:\s*(\S[^\n]{0,200}?)\s*\n([\s\S]*?)(?=\n?FILE:|$)/g
  while ((match = r0.exec(text)) !== null) {
    let path = match[1]
    let content = match[2].trim()
    // 剥离内容开头的语言名（如 xml、java、yaml）
    const langMatch = content.match(/^(xml|java|yaml|yml|json|md|sql|js|ts|vue|html|css|py|properties|txt)\b/)
    if (langMatch && content.length > langMatch[0].length + 10) {
      content = content.slice(langMatch[0].length).trim()
    }
    addFile(path, content)
  }
  if (files.length > 0) return files
  const langPaths: Record<string, string> = { java: "src/main/java/com/example/App.java", xml: "pom.xml", yaml: "src/main/resources/application.yml", yml: "src/main/resources/application.yml", json: "package.json", html: "index.html", md: "README.md", sql: "schema.sql", js: "src/index.js", ts: "src/index.ts", vue: "src/App.vue" }
  const codeBlockRegex = /```(\w+)\s*\n([\s\S]*?)```/g
  let idx = 0
  while ((match = codeBlockRegex.exec(text)) !== null) {
    const lang = match[1].toLowerCase(); const content = match[2]?.trim()
    if (content && content.length > 20) {
      let path = langPaths[lang] || "src/file" + (++idx) + "." + (lang || "txt")
      addFile(path, content)
    }
  }
  return files
}

async function downloadProject(msgIndex: number) {
  const msg = messages[msgIndex]
  if (!msg?.workflow) return
  if (msg.workflow._zipBlob) {
    saveAs(msg.workflow._zipBlob, msg.workflow._zipName || "project.zip")
    ElMessage.success("下载已开始")
    return
  }
  // 后端生成模式（无前端 zip）：从后端 download-code 接口下载
  const tid = msg.workflow.threadId || msg.workflow._threadIdForDownload
  if (!tid) { ElMessage.warning("缺少线程ID，无法下载"); return }
  try {
    const blob: any = await downloadWorkflowCode([tid])
    saveAs(blob, `projects-${tid}.zip`)
    ElMessage.success("下载已开始")
  } catch (_e) {
    ElMessage.error("下载失败，可能代码尚未生成或已清理")
  }
}

async function deployProject(msgIndex: number) {
  const msg = messages[msgIndex]
  if (!msg?.workflow) return
  let zipBlob = msg.workflow._zipBlob
  let zipName = msg.workflow._zipName || "project.zip"
  // 无前端 zip 时，从后端 download-code 获取
  if (!zipBlob) {
    const tid = msg.workflow.threadId || msg.workflow._threadIdForDownload
    if (!tid) { ElMessage.warning("缺少线程ID，无法部署"); return }
    try {
      zipBlob = await downloadWorkflowCode([tid])
    } catch (_e) {
      ElMessage.error("获取项目代码失败，无法部署")
      return
    }
  }
  const projectName = "p" + Date.now()
  try {
    const formData = new FormData()
    formData.append("file", zipBlob, zipName)
    formData.append("projectName", projectName)
    ElMessage.info("正在上传并部署项目...")
    // 2026-08-13 修复：axios 拦截器已返回 res.data（BaseResponse），code 在顶层
    const saveRes = await service.post("/project/save", formData, { headers: { "Content-Type": "multipart/form-data" } })
    if ((saveRes as any).code !== 0) { ElMessage.error("项目保存失败：" + ((saveRes as any).message || "")); return }
    const deployRes = await service.post("/project/deploy/" + projectName)
    const deployData = (deployRes as any).data
    if (deployData?.success) {
      // 部署成功：展示部署地址
      const url = deployData.url || "http://localhost:8080"
      const containerId = deployData.containerId || ""
      const deployMsg = `✅ Docker 部署成功\n容器ID: ${containerId}\n访问地址: ${url}`
      msg.workflow!._deployInfo = { url, containerId, projectName }
      ElMessageBox.alert(deployMsg, "部署成功", { type: "success", confirmButtonText: "知道了" })
    } else if (!deployData?.dockerAvailable) {
      ElMessageBox.alert(deployData?.message || "Docker 未安装", "部署提示", { type: "warning", confirmButtonText: "知道了" })
    } else {
      ElMessage.error(deployData?.message || "部署失败")
    }
  } catch (err: any) {
    ElMessage.error("部署失败：" + (err.message || "未知错误"))
  }
}

// 2026-08-13 工作流卡片已通过 scheduleSave 持久化到 sessionStore（localStorage），
// onMounted 里 sessionStore.init + session.messages.forEach 已按正确顺序恢复全部消息。
// 不再从 MySQL 单独加载工作流卡片，避免顺序错乱和重复。
vueMounted(() => { localStorage.setItem(STORED_SESSION_KEY, chatSessionId.value) })
vueUnmount(() => { localStorage.setItem(STORED_SESSION_KEY, chatSessionId.value) })

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

.wf-live-bar { display: flex; align-items: center; gap: 8px; padding: 8px 14px; margin-bottom: 12px; background: linear-gradient(90deg, #f0f7ff, #f5f0ff); border: 1px solid #d0d8f0; border-radius: 6px; font-size: 13px; color: #5b6abf; font-weight: 500; }
.wf-live-sep { color: #d0d8f0; }
.wf-bottom-bar { display: flex; align-items: center; justify-content: center; gap: 10px; padding: 8px 16px; min-height: 36px; background: linear-gradient(90deg, #f0f7ff, #f5f0ff); border-top: 1px solid #d0d8f0; font-size: 13px; color: #5b6abf; font-weight: 500; }
.wf-bottom-sep { color: #ccc; }

@keyframes spin { to { transform: rotate(360deg); } }

/* ===== 工作流卡片 ===== */
.workflow-card { background: #fff; border: 1px solid #e0e0e0; border-radius: 8px; padding: 20px; margin-bottom: 24px; }
.workflow-card.wf-historical { background: #fafbfc; border-color: #e8ecf0; border-style: dashed; }
.workflow-card.wf-historical .wf-step-body { opacity: 0.85; }
.wf-new-round { margin-top: 30px; border-color: #ffc107; border-width: 2px; }
.wf-round-separator { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; padding-bottom: 12px; border-bottom: 1px dashed #ffc107; }
.wf-round-line { flex: 1; height: 1px; background: #ffc107; }
.wf-round-label { font-size: 13px; font-weight: 600; color: #856404; white-space: nowrap; }
.wf-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; padding-bottom: 10px; border-bottom: 1px solid #eee; }
.wf-title { font-size: 15px; font-weight: 600; color: #333; }
.wf-historical-badge { display: inline-block; margin-left: 8px; padding: 1px 8px; background: #e5e7eb; color: #6b7280; border-radius: 10px; font-size: 11px; font-weight: 500; vertical-align: middle; }
.wf-status { display: inline-block; padding: 2px 10px; border-radius: 12px; font-size: 12px; font-weight: bold; }
.wf-status-running { background: #d1ecf1; color: #0c5460; }
.wf-status-waiting_review { background: #fff3cd; color: #856404; }
.wf-status-completed { background: #d4edda; color: #155724; }
.wf-status-terminated { background: #fff3cd; color: #856404; }
.wf-status-failed { background: #e2e3e5; color: #383d41; }
.wf-progress { display: flex; flex-direction: column; gap: 2px; margin-bottom: 10px; }
.wf-step-row { display: flex; flex-direction: column; }
.wf-step { display: flex; align-items: center; padding: 6px 0; font-size: 14px; color: #999; }
.wf-step.done { color: #28a745; }
.wf-step.active { color: #007bff; font-weight: bold; }
.wf-step-icon { width: 20px; height: 20px; border-radius: 50%; border: 2px solid #ccc; margin-right: 10px; flex-shrink: 0; text-align: center; line-height: 16px; font-size: 11px; }
.wf-step.done .wf-step-icon { background: #28a745; border-color: #28a745; color: #fff; }
.wf-step.active .wf-step-icon { border-color: #007bff; animation: wf-pulse 1.5s infinite; }
@keyframes wf-pulse { 0%,100% { box-shadow: 0 0 0 0 rgba(0,123,255,0.4); } 50% { box-shadow: 0 0 0 6px rgba(0,123,255,0); } }
.wf-step-name { flex: 1; min-width: 80px; }
.wf-step-body { margin-left: 30px; padding: 10px 14px; background: #f8f9fa; border-left: 2px solid #e0e0e0; font-size: 14px; color: #374151; white-space: normal; max-height: 300px; overflow-y: auto; border-radius: 0 4px 4px 0; line-height: 1.7; word-break: break-word; }
.wf-step-collapsed { cursor: pointer; color: #888; font-size: 13px; text-align: center; padding: 6px 14px; max-height: none; background: #f0f0f0; border-left-color: #ccc; }
.wf-step-preview { cursor: pointer; color: #666; font-size: 13px; padding: 8px 14px; max-height: 80px; overflow: hidden; background: #fafafa; border-left-color: #ccc; }
.wf-step-preview:hover { background: #f0f0f0; }
.wf-step-toggle { margin-left: auto; font-size: 11px; color: #888; cursor: pointer; flex-shrink: 0; }
.wf-step-toggle:hover { color: #555; }
.wf-step-collapsed:hover { background: #e8e8e8; color: #555; }
.wf-step-expand-hint { color: #888; }
.wf-step-collapse-btn { display: block; text-align: right; font-size: 12px; color: #999; cursor: pointer; margin-bottom: 6px; }
.wf-step-collapse-btn:hover { color: #555; }
.wf-step-body.markdown :deep(p) { margin: 0 0 8px; }
.wf-step-body.markdown :deep(p:last-child) { margin-bottom: 0; }
.wf-step-body.markdown :deep(ul), .wf-step-body.markdown :deep(ol) { margin: 6px 0; padding-left: 1.5em; }
.wf-step-body.markdown :deep(li) { margin-bottom: 4px; }
.wf-step-body.markdown :deep(h1), .wf-step-body.markdown :deep(h2), .wf-step-body.markdown :deep(h3) { margin: 12px 0 6px; font-size: 15px; }
.wf-step-body.markdown :deep(code) { background: #e5e7eb; padding: 1px 5px; border-radius: 3px; font-size: 13px; }
.wf-step-body.markdown :deep(pre) { background: #1e1e2e; padding: 10px; border-radius: 4px; overflow-x: auto; }
.wf-step-body.markdown :deep(pre code) { background: none; color: #cdd6f4; }
.wf-body-pre { margin: 0; white-space: pre-wrap; word-break: break-all; font-family: inherit; font-size: 14px; color: #444; line-height: 1.7; }
.wf-thinking { color: #ff9800; font-size: 12px; }
.thinking-dot { animation: dotPulse 1.2s infinite; }
.thinking-dot:nth-child(2) { animation-delay: 0.2s; }
.thinking-dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes dotPulse { 0%, 20% { opacity: 0.2; } 50% { opacity: 1; } 80%, 100% { opacity: 0.2; } }
.wf-final-status { margin-top: 14px; padding: 14px; border-radius: 6px; font-size: 14px; }
.wf-final-completed { background: #e6f7e6; color: #286b28; }
.wf-final-terminated { background: #fff3cd; color: #856404; }
.wf-final-failed { background: #f8d7da; color: #721c24; }
.wf-token-stats { margin-top: 14px; padding: 14px; background: linear-gradient(135deg, #f0f7ff 0%, #f5f0ff 100%); border: 1px solid #d0d8f0; border-radius: 8px; }
.wf-stats-header { display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 600; color: #5b6abf; margin-bottom: 12px; padding-bottom: 8px; border-bottom: 1px solid #d0d8f0; }
.wf-stats-icon { font-size: 14px; }
.wf-stats-summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 10px; margin-bottom: 10px; }
.wf-stats-item { text-align: center; padding: 10px 6px; background: #fff; border-radius: 6px; border: 1px solid #e8ecf4; }
.wf-stats-label { display: block; font-size: 12px; color: #888; margin-bottom: 4px; }
.wf-stats-value { display: block; font-size: 18px; font-weight: 700; color: #333; }
.wf-review-bar { margin-top: 14px; padding: 16px; background: #fffbe6; border: 2px solid #ffc107; border-radius: 8px; }
.wf-review-area { margin-bottom: 14px; padding: 12px; background: #fafafa; border: 1px solid #eee; border-radius: 6px; }
.wf-review-label { font-size: 13px; font-weight: 600; color: #666; margin-bottom: 10px; }
.wf-review-block { margin-bottom: 8px; border: 1px solid #e8e8e8; border-radius: 4px; overflow: hidden; }
.wf-review-block-header { display: flex; align-items: center; gap: 6px; padding: 8px 10px; background: #f0f0f0; font-size: 13px; color: #555; cursor: pointer; user-select: none; }
.wf-review-block-body { padding: 10px 12px; font-size: 14px; line-height: 1.7; max-height: 400px; overflow-y: auto; border-top: 1px solid #e8e8e8; white-space: normal; word-break: break-word; }
.wf-item-row { display: flex; align-items: flex-start; gap: 8px; padding: 8px 10px; background: #fff; border: 1px solid #e8e8e8; border-radius: 4px; margin-bottom: 6px; }
.wf-item-row.selected { border-color: #409eff; background: #ecf5ff; }
.wf-item-check { flex-shrink: 0; margin-top: 2px; }
.wf-item-body { flex: 1; min-width: 0; }
.wf-item-title { font-size: 13px; font-weight: 500; color: #333; }
.wf-item-desc { font-size: 12px; color: #888; margin-top: 2px; }
.wf-toggle-icon { font-size: 11px; color: #999; width: 12px; text-align: center; }
.wf-comment { margin-bottom: 10px; }
.wf-review-btns { display: flex; gap: 10px; }
</style>
