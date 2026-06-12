<template>
  <div class="chat-container">
    <!-- 聊天面板 -->
    <div class="chat-panel">
      <!-- 工具栏 -->
      <div class="chat-toolbar">
        <span class="toolbar-label">模型：</span>
        <el-select
          v-model="selectedModel"
          placeholder="选择模型"
          size="small"
          style="width: 200px"
          @change="onModelChange"
        >
          <el-option label="GPT-4" value="gpt-4" />
          <el-option label="Claude 3.5 Sonnet" value="claude-3.5" />
          <el-option label="本地模型 (LLaMA)" value="llama" />
          <el-option
            v-for="cfg in appStore.modelConfigs"
            :key="cfg.id"
            :label="cfg.name"
            :value="cfg.modelName"
          />
        </el-select>

        <div class="toolbar-actions">
          <el-button text size="small" @click="clearChat">
            <el-icon><Delete /></el-icon> 清空对话
          </el-button>
        </div>
      </div>

      <!-- 消息列表 -->
      <div class="messages-container" ref="messagesRef">
        <template v-for="msg in messageStore.messages" :key="msg.id">
          <!-- 普通消息 -->
          <div v-if="msg.type !== 'card'" class="message-item">
            <div :class="['msg-row', msg.type]">
              <div v-if="msg.type === 'agent'" class="msg-avatar agent-avatar">🤖</div>
              <div class="msg-bubble">{{ msg.content }}</div>
              <div v-if="msg.type === 'user'" class="msg-avatar user-avatar">👤</div>
            </div>
          </div>

          <!-- 拆解结果卡片 -->
          <div v-else-if="msg.cardType === 'decompose'" class="message-item">
            <div class="msg-card-row">
              <div class="msg-avatar agent-avatar">🤖</div>
              <div class="msg-card">
                <div class="card-head">📋 需求拆解结果</div>
                <div class="card-body">
                  <div
                    v-for="(item, idx) in msg.cardData"
                    :key="idx"
                    class="decompose-item"
                    :class="{ last: idx === msg.cardData.length - 1 }"
                  >
                    <div class="item-head">
                      <span class="item-idx">{{ idx + 1 }}</span>
                      <span class="item-title">{{ item.title }}</span>
                      <span :class="['item-priority', item.priority]">
                        {{ priorityLabel(item.priority) }}
                      </span>
                    </div>
                    <div class="item-desc">{{ item.desc }}</div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 审核确认卡片 -->
          <div v-else-if="msg.cardType === 'review'" class="message-item">
            <div class="msg-card-row">
              <div class="msg-avatar agent-avatar">🤖</div>
              <div class="msg-card">
                <div class="card-head">✅ 请审核确认</div>
                <div class="card-body">
                  <p class="review-tip">
                    {{ msg.cardData?.tip || "以上是我对您需求的拆解结果，请确认是否可以执行？" }}
                  </p>
                  <div class="review-actions">
                    <el-button type="primary" size="small" @click="confirmDecompose">
                      确认执行
                    </el-button>
                    <el-button size="small" @click="adjustDecompose">需要调整</el-button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 执行结果汇总卡片 -->
          <div v-else-if="msg.cardType === 'aggregate'" class="message-item">
            <div class="msg-card-row">
              <div class="msg-avatar agent-avatar">🤖</div>
              <div class="msg-card">
                <div class="card-head">📊 执行结果汇总</div>
                <div class="card-body">
                  <div
                    v-for="(item, idx) in msg.cardData?.results"
                    :key="idx"
                    class="result-item"
                    :class="{ last: idx === msg.cardData?.results?.length - 1 }"
                  >
                    <span :class="['result-icon', item.status === 'ok' ? 'ok' : 'fail']">
                      {{ item.status === "ok" ? "✔" : "✘" }}
                    </span>
                    <span class="result-text">{{ item.text }}</span>
                  </div>
                  <div v-if="msg.cardData?.summary" class="aggregate-summary">
                    <div
                      class="summary-circle"
                      :style="progressStyle(msg.cardData.summary.percent)"
                    >
                      {{ msg.cardData.summary.percent }}%
                    </div>
                    <p class="summary-text">{{ msg.cardData.summary.text }}</p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>

        <!-- 思考中 -->
        <div v-if="messageStore.isChating" class="message-item">
          <div class="msg-row agent">
            <div class="msg-avatar agent-avatar">🤖</div>
            <div class="msg-bubble processing">正在思考...</div>
          </div>
        </div>

        <!-- 空状态 -->
        <div v-if="messageStore.messages.length === 0 && !messageStore.isChating" class="empty-state">
          <div class="empty-icon">🤖</div>
          <p class="empty-title">您好！我是需求拆解 Agent</p>
          <p class="empty-desc">请输入您的需求，我会帮您拆解成可执行的具体任务。</p>
        </div>
      </div>

      <!-- 输入区域 -->
      <div class="input-area">
        <div class="input-wrapper">
          <el-input
            v-model="inputText"
            type="textarea"
            :rows="3"
            placeholder="请输入您的需求，我会帮您拆解并执行..."
            :disabled="messageStore.isChating"
            @keydown="handleKeydown"
            resize="vertical"
          />
          <div class="input-actions">
            <span class="input-tip">Enter 发送，Shift + Enter 换行</span>
            <el-button
              type="primary"
              :disabled="!inputText.trim() || messageStore.isChating"
              :loading="messageStore.isChating"
              @click="sendMessage"
            >
              发送
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Delete } from "@element-plus/icons-vue"
import { ElMessage } from "element-plus"
import { useAppStore } from "@/store/app"
import { useChatMessageStore } from "@/store/message"
import { agentApi } from "@/api/AgentApi"
import type { DecomposeTask } from "@/api/dto"

const appStore = useAppStore()
const messageStore = useChatMessageStore()

const inputText = ref("")
const selectedModel = ref(appStore.currentModel || "gpt-4")
const messagesRef = ref<HTMLElement>()

// 优先级标签
function priorityLabel(p: string) {
  const map: Record<string, string> = { high: "高", mid: "中", low: "低" }
  return map[p] || p
}

// 进度条样式
function progressStyle(percent: number) {
  const color = percent >= 80 ? "#67c23a" : percent >= 50 ? "#e6a23c" : "#f56c6c"
  return {
    background: `conic-gradient(${color} 0deg ${percent * 3.6}deg, #e4e7ed ${percent * 3.6}deg 360deg)`,
  }
}

// 模型切换
function onModelChange(val: string) {
  appStore.saveSettings({ currentModel: val })
}

// 清空对话
function clearChat() {
  messageStore.resetMessages()
}

// 键盘事件
function handleKeydown(e: KeyboardEvent) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

// 发送消息
async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || messageStore.isChating) return

  inputText.value = ""
  messageStore.addMessage({ type: "user", content: text })
  messageStore.setIsChating(true)
  scrollToBottom()

  try {
    // 尝试调用后端 API
    const res = await agentApi.decompose(text)
    if (res.code === 0 && res.data?.tasks) {
      messageStore.addMessage({
        type: "agent",
        content: `已收到您的需求："${text}"，以下是对需求的拆解结果`,
      })
      messageStore.addMessage({
        type: "card",
        cardType: "decompose",
        cardData: res.data.tasks.map((t: any, i: number) => ({
          title: t.title,
          desc: t.description || t.desc,
          priority: t.priority || "mid",
        })),
      })
      messageStore.addMessage({
        type: "card",
        cardType: "review",
        cardData: { tip: "以上是我对您需求的拆解结果，请确认是否可以执行？" },
      })
    } else {
      // 后端返回格式不对，回退到模拟
      simulateResponse(text)
    }
  } catch (_e) {
    // 后端不通时走模拟
    simulateResponse(text)
  } finally {
    messageStore.setIsChating(false)
    scrollToBottom()
  }
}

// 模拟响应（开发调试用）
async function simulateResponse(text: string) {
  await delay(800)
  messageStore.addMessage({
    type: "agent",
    content: `已收到您的需求："${text}"，正在拆解...`,
  })
  scrollToBottom()
  await delay(1200)

  messageStore.addMessage({
    type: "card",
    cardType: "decompose",
    cardData: [
      { title: "创建项目结构", desc: "在指定目录下创建完整的项目文件结构，包括配置文件、源代码目录、资源文件等", priority: "high" },
      { title: "配置开发环境", desc: "安装项目依赖，配置构建工具、代码规范检查、测试框架等开发环境", priority: "high" },
      { title: "实现核心功能模块", desc: "根据需求文档，实现核心业务逻辑和功能模块", priority: "mid" },
      { title: "编写单元测试", desc: "为核心功能编写单元测试用例，确保代码质量", priority: "mid" },
      { title: "编写项目文档", desc: "编写 README、API 文档、使用说明等项目文档", priority: "low" },
    ] as DecomposeTask[],
  })
  scrollToBottom()
  await delay(600)

  messageStore.addMessage({
    type: "card",
    cardType: "review",
    cardData: { tip: "以上是我对您需求的拆解结果，请确认是否可以执行？" },
  })
  scrollToBottom()
}

// 确认执行
function confirmDecompose() {
  messageStore.addMessage({
    type: "agent",
    content: "已确认执行，开始处理中...",
  })
  scrollToBottom()

  setTimeout(() => {
    messageStore.addMessage({
      type: "card",
      cardType: "aggregate",
      cardData: {
        results: [
          { text: "创建项目结构：已完成", status: "ok" },
          { text: "配置开发环境：已完成", status: "ok" },
          { text: "实现核心功能模块：已完成", status: "ok" },
          { text: "编写单元测试：部分用例待补充", status: "fail" },
          { text: "编写项目文档：已完成", status: "ok" },
        ],
        summary: {
          percent: 80,
          text: "5 个任务中，4 个已完成，1 个需要补充。整体完成度 80%。",
        },
      },
    })
    scrollToBottom()
  }, 2000)
}

function adjustDecompose() {
  messageStore.addMessage({
    type: "agent",
    content: "请告诉我您觉得哪些部分需要调整？我会重新为您拆解。",
  })
  scrollToBottom()
}

// 滚动到底部
function scrollToBottom() {
  nextTick(() => {
    const el = messagesRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

// 监听消息变化自动滚动
watch(
  () => messageStore.messages.length,
  () => scrollToBottom()
)

onMounted(() => {
  scrollToBottom()
})
</script>

<style scoped lang="less">
.chat-container {
  display: flex;
  height: 100%;
  padding: 16px;
}

.chat-panel {
  display: flex;
  flex-direction: column;
  width: 100%;
  background: #fff;
  border-radius: 12px;
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
  }
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  background: #f9fafb;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
}

// 空状态
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 12px;

  .empty-icon {
    font-size: 64px;
  }
  .empty-title {
    font-size: 16px;
    color: #303133;
    font-weight: 600;
  }
  .empty-desc {
    font-size: 14px;
    color: #909399;
    text-align: center;
  }
}

// 消息气泡
.message-item {
  margin-bottom: 20px;
}

.msg-row {
  display: flex;
  gap: 12px;
  max-width: 80%;

  &.user {
    flex-direction: row-reverse;
    margin-left: auto;
  }
  &.agent {
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

.msg-row.user .msg-bubble {
  background: #409eff;
  color: white;
  border-top-right-radius: 4px;
}

.msg-row.agent .msg-bubble {
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

  &.user-avatar {
    background: #ecf5ff;
  }
  &.agent-avatar {
    background: #f0f9eb;
  }
}

.processing {
  color: #909399;
}

// 卡片消息
.msg-card-row {
  display: flex;
  gap: 12px;
  margin-right: auto;
  max-width: 90%;
}

.msg-card {
  background: #fff;
  border-radius: 12px;
  border: 1px solid #e4e7ed;
  overflow: hidden;
  min-width: 400px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: linear-gradient(135deg, #409eff, #337ecc);
  color: white;
  font-weight: 600;
  font-size: 14px;
}

.card-body {
  padding: 16px;
}

// 拆解项
.decompose-item {
  padding: 12px;
  border-bottom: 1px solid #f0f0f0;

  &.last {
    border-bottom: none;
  }
}

.item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.item-idx {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #409eff;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.item-title {
  font-weight: 600;
  color: #303133;
  flex: 1;
  font-size: 14px;
}

.item-priority {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;

  &.high {
    background: #fef0f0;
    color: #f56c6c;
  }
  &.mid {
    background: #fdf6ec;
    color: #e6a23c;
  }
  &.low {
    background: #f4f4f5;
    color: #909399;
  }
}

.item-desc {
  font-size: 13px;
  color: #606266;
  line-height: 1.5;
  padding-left: 32px;
}

// 审核
.review-tip {
  color: #606266;
  margin-bottom: 16px;
  line-height: 1.6;
  font-size: 14px;
}

.review-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}

// 结果汇总
.result-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;

  &.last {
    border-bottom: none;
  }
}

.result-icon {
  font-size: 18px;
  flex-shrink: 0;

  &.ok {
    color: #67c23a;
  }
  &.fail {
    color: #f56c6c;
  }
}

.result-text {
  color: #303133;
  font-size: 14px;
}

.aggregate-summary {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 20px 0 0;
}

.summary-circle {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  font-weight: 700;
  color: #303133;
}

.summary-text {
  margin-top: 12px;
  color: #909399;
  font-size: 14px;
  text-align: center;
}

// 输入区域
.input-area {
  padding: 16px 20px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
  flex-shrink: 0;
}

.input-wrapper {
  max-width: 900px;
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
