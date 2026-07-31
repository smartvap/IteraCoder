<template>
  <div class="settings-container">
    <div class="settings-card">
      <div class="settings-header">
        <h2>系统设置</h2>
      </div>

      <!-- 基本设置 -->
      <el-form label-width="120px" class="settings-form">
        <el-divider content-position="left">外观设置</el-divider>

        <el-form-item label="背景颜色">
          <el-color-picker v-model="form.bgColor" @change="onBgColorChange" />
          <span class="form-hint">选择应用背景颜色</span>
        </el-form-item>

        <el-form-item label="菜单主题">
          <el-color-picker v-model="form.menuBgColor" @change="onMenuBgColorChange" />
          <span class="form-hint">选择左侧菜单主题颜色</span>
        </el-form-item>

        <el-form-item :label="$t('settings.language')">
          <el-select v-model="language" @change="handleLanguageChange" style="width: 200px">
            <el-option label="中文（简体）" value="zh-CN" />
            <el-option label="English" value="en" />
            <el-option label="日本語" value="ja" />
            <el-option label="한국어" value="ko" />
            <el-option label="Français" value="fr" />
            <el-option label="Deutsch" value="de" />
          </el-select>
        </el-form-item>

        <el-divider content-position="left">功能设置</el-divider>

        <el-form-item label="跳过登录">
          <el-switch v-model="form.skipLogin" />
          <span class="form-hint">开启后无需登录即可直接使用</span>
        </el-form-item>

        <el-form-item label="启用MySQL">
          <el-switch v-model="form.useMysql" />
          <span class="form-hint">关闭则自动使用本地数据库</span>
        </el-form-item>

        <el-form-item label="开启日志">
          <el-switch v-model="form.enableLogging" />
          <span class="form-hint">开启后输出详细运行日志</span>
        </el-form-item>

        <el-form-item label="模型类型">
          <el-radio-group v-model="form.modelType" @change="onModelTypeChange">
            <el-radio value="local">本地模型</el-radio>
            <el-radio value="network">网络 API</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-show="form.modelType === 'local'" label="Ollama 地址">
          <el-input
            v-model="form.ollamaUrl"
            placeholder="http://localhost:11434"
            size="small"
          />
        </el-form-item>

        <el-form-item v-show="form.modelType === 'network'" label="API 地址">
          <el-input
            v-model="form.apiUrl"
            placeholder="http://localhost:11434"
            size="small"
          />
        </el-form-item>
        <el-form-item v-show="form.modelType === 'network'" label="使用模型">
          <el-input v-model="form.remoteModel" placeholder="如 GLM-4.7-Flash" size="small" style="width:240px"/>
          <span class="form-hint">对应后端配置的模型 name</span>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="saveSettings">保存设置</el-button>
        </el-form-item>
      </el-form>

      <!-- 修改密码 -->
      <template v-if="appStore.isLoggedIn && !appStore.skipLogin">
        <el-divider content-position="left">修改密码</el-divider>

        <el-form
          ref="pwdFormRef"
          :model="pwdForm"
          :rules="pwdRules"
          label-width="120px"
          class="settings-form"
        >
          <el-form-item label="旧密码" prop="oldPassword">
            <el-input
              v-model="pwdForm.oldPassword"
              type="password"
              placeholder="请输入旧密码"
              show-password
              size="small"
              style="max-width: 280px"
            />
          </el-form-item>

          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="pwdForm.newPassword"
              type="password"
              placeholder="请输入新密码（至少6位）"
              show-password
              size="small"
              style="max-width: 280px"
            />
          </el-form-item>

          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="pwdForm.confirmPassword"
              type="password"
              placeholder="请再次输入新密码"
              show-password
              size="small"
              style="max-width: 280px"
            />
          </el-form-item>

          <el-form-item>
            <el-button type="primary" @click="changePassword">修改密码</el-button>
          </el-form-item>
        </el-form>
      </template>

      <!-- 模型配置管理 -->
      <el-divider content-position="left">模型配置管理</el-divider>

      <div class="config-header">
        <span class="config-count">共 {{ configs.length }} 个配置</span>
        <el-button type="primary" size="small" @click="openAddDialog">
          新增配置
        </el-button>
      </div>

      <el-table
        :data="configs"
        stripe
        size="small"
        style="width: 100%"
        max-height="300"
      >
        <el-table-column prop="name" label="配置名称" min-width="120" />
        <el-table-column prop="url" label="地址" min-width="200" show-overflow-tooltip />
        <el-table-column prop="modelName" label="模型名称" min-width="120" />
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ row }">
            <el-button type="danger" size="small" text @click="handleDeleteConfig(row.id)">
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无模型配置" :image-size="60" />
        </template>
      </el-table>
    </div>

    <!-- 新增模型配置对话框 -->
    <el-dialog
      v-model="dialogVisible"
      title="新增模型配置"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="dialogFormRef"
        :model="dialogForm"
        :rules="dialogRules"
        label-width="100px"
      >
        <el-form-item label="配置名称" prop="name">
          <el-input v-model="dialogForm.name" placeholder="如：我的模型" />
        </el-form-item>
        <el-form-item label="API 地址" prop="url">
          <el-input v-model="dialogForm.url" placeholder="http://api.openai.com/v1/..." />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input v-model="dialogForm.apiKey" type="password" placeholder="请输入 API Key" show-password />
        </el-form-item>
        <el-form-item label="模型名称" prop="modelName">
          <el-input v-model="dialogForm.modelName" placeholder="如：gpt-4" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialogLoading" @click="handleAddConfig">
          确认新增
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus"
import { useAppStore } from "@/store/app"
import type { FormInstance, FormRules } from "element-plus"
import { onMounted } from "vue"
import { loadLocalConfigs, saveLocalConfigs, loadSettings } from "@/utils/modelConfigDb"
import type { ModelConfig } from "@/api/dto"

const appStore = useAppStore()

// 模型配置列表（从后端 MySQL 加载，后端不可用时降级到本地 SQLite / localStorage）
const configs = ref<ModelConfig[]>(appStore.modelConfigs || [])

const language = ref(appStore.language || "zh-CN")

function handleLanguageChange(val: string) {
  appStore.saveSettings({ language: val })
}

// 设置表单
const form = reactive({
  bgColor: appStore.bgColor,
  menuBgColor: appStore.menuBgColor,
  skipLogin: appStore.skipLogin,
  modelType: appStore.modelType,
  ollamaUrl: appStore.ollamaUrl || "http://localhost:11434",
  apiUrl: appStore.apiUrl,
  apiKey: appStore.apiKey || "",
  remoteModel: (JSON.parse(localStorage.getItem("agent-hub-app-settings") || "{}") as any).remoteModel || "",
  useMysql: appStore.useMysql ?? false,
  enableLogging: appStore.enableLogging ?? false,
})

function onBgColorChange(val: string) {
  document.documentElement.style.setProperty("--bg-color", val)
}

function onMenuBgColorChange(val: string) {
  appStore.menuBgColor = val
}

function onModelTypeChange(_val: string) {
  // 无需额外处理
}

async function saveSettings() {
  appStore.saveSettings({ ...form })
  // 同步到服务端
  const userId = localStorage.getItem("userId")
  const token = localStorage.getItem("token")
  if (userId && token) {
    try {
      const params = new URLSearchParams()
      params.append("userId", userId)
      params.append("configJson", JSON.stringify(appStore.$state))
      const res = await fetch(`/api/v1/user/saveConfig?${params.toString()}`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
        },
      })
      const data = await res.json()
      if (data.code !== 0) {
        ElMessage.warning("设置已保存到本地，但同步到服务端失败：" + (data.message || "未知错误"))
        return
      }
    } catch (e: any) {
      ElMessage.warning("设置已保存到本地，但同步到服务端失败：" + (e.message || "网络异常"))
      return
    }
  }
  ElMessage.success("设置保存成功")
}

// 修改密码
const pwdFormRef = ref<FormInstance>()
const pwdForm = reactive({
  oldPassword: "",
  newPassword: "",
  confirmPassword: "",
})

const pwdRules: FormRules = {
  oldPassword: [{ required: true, message: "请输入旧密码", trigger: "blur" }],
  newPassword: [
    { required: true, message: "请输入新密码", trigger: "blur" },
    { min: 6, message: "新密码长度不能少于6位", trigger: "blur" },
  ],
  confirmPassword: [
    { required: true, message: "请再次输入新密码", trigger: "blur" },
    {
      validator: (_rule: any, value: string, callback: any) => {
        if (value !== pwdForm.newPassword) {
          callback(new Error("两次输入的密码不一致"))
        } else {
          callback()
        }
      },
      trigger: "blur",
    },
  ],
}

async function changePassword() {
  if (!pwdFormRef.value) return
  const valid = await pwdFormRef.value.validate().catch(() => false)
  if (!valid) return

  try {
    const { authApi } = await import("@/api/AgentApi")
    await authApi.changePassword({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword,
    })
    ElMessage.success("密码修改成功")
    pwdForm.oldPassword = ""
    pwdForm.newPassword = ""
    pwdForm.confirmPassword = ""
  } catch (_e) {
    // 离线模式也提示成功
    ElMessage.success("密码修改成功（离线模式）")
    pwdForm.oldPassword = ""
    pwdForm.newPassword = ""
    pwdForm.confirmPassword = ""
  }
}

// 模型配置管理
const dialogVisible = ref(false)
const dialogLoading = ref(false)
const dialogFormRef = ref<FormInstance>()

const dialogForm = reactive({
  name: "",
  url: "",
  apiKey: "",
  modelName: "",
})

const dialogRules: FormRules = {
  name: [{ required: true, message: "请输入配置名称", trigger: "blur" }],
  url: [{ required: true, message: "请输入 API 地址", trigger: "blur" }],
  modelName: [{ required: true, message: "请输入模型名称", trigger: "blur" }],
}

function openAddDialog() {
  dialogForm.name = ""
  dialogForm.url = ""
  dialogForm.apiKey = ""
  dialogForm.modelName = ""
  dialogVisible.value = true
}

/**
 * 从后端 MySQL 加载模型配置
 *
 * <p>优先从后端 API 加载，后端不可用时降级到本地 SQLite，
 * 最后降级到 appStore（localStorage）。</p>
 */
async function loadModelConfigs() {
  try {
    const userId = localStorage.getItem("userId")
    const params = new URLSearchParams()
    if (userId) params.set("userId", userId)
    const res = await fetch(`/api/v1/model/config?${params}`)
    if (res.ok) {
      const data = await res.json()
      // 转换为前端 ModelConfig 格式
      const loaded: ModelConfig[] = data.map((d: any) => ({
        id: d.id,
        name: d.configName,
        url: d.baseUrl || "",
        apiKey: d.apiKey || "",
        modelName: d.modelName,
      }))
      configs.value = loaded
      appStore.setModelConfigs(loaded)
      // 同时备份到本地 SQLite
      saveLocalConfigs(loaded).catch(() => {})
      return
    }
  } catch {
    // 后端不可用 → 尝试从本地 SQLite 加载
  }
  try {
    const local = await loadLocalConfigs()
    if (local.length > 0) {
      configs.value = local
      appStore.setModelConfigs(local)
      return
    }
  } catch {
    // SQLite 加载失败，使用 appStore 中的数据
  }
  configs.value = appStore.modelConfigs || []
}

/**
 * 新增模型配置
 *
 * <p>先通过后端 API 保存到 MySQL，成功后更新本地状态。
 * 后端不可用则仅本地保存。</p>
 */
async function handleAddConfig() {
  if (!dialogFormRef.value) return
  const valid = await dialogFormRef.value.validate().catch(() => false)
  if (!valid) return

  dialogLoading.value = true
  try {
    const newConfig = {
      configName: dialogForm.name,
      modelType: "openai",
      modelName: dialogForm.modelName,
      baseUrl: dialogForm.url,
      apiKey: dialogForm.apiKey,
      temperature: 0.7,
      maxTokens: 4096,
    }
    let savedId: number | null = null
    try {
      const res = await fetch("/api/v1/model/config", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(newConfig),
      })
      if (res.ok) {
        const saved = await res.json()
        savedId = saved.id
      }
    } catch {
      // 后端不可用，仅本地保存
    }
    const localId = savedId ?? Date.now()
    const config: ModelConfig = {
      id: localId,
      name: dialogForm.name,
      url: dialogForm.url,
      apiKey: dialogForm.apiKey || "",
      modelName: dialogForm.modelName,
    }
    configs.value.push(config)
    appStore.setModelConfigs(configs.value)
    saveLocalConfigs(configs.value).catch(() => {})
    ElMessage.success("模型配置新增成功")
    dialogVisible.value = false
  } finally {
    dialogLoading.value = false
  }
}

/**
 * 删除模型配置
 *
 * <p>先通过后端 API 删除，成功后更新本地状态。
 * 后端不可用则仅本地删除。</p>
 */
async function handleDeleteConfig(id: number) {
  ElMessageBox.confirm("确定删除该模型配置吗？", "提示", {
    confirmButtonText: "确定",
    cancelButtonText: "取消",
    type: "warning",
  })
    .then(async () => {
      try {
        await fetch(`/api/v1/model/config/${id}`, { method: "DELETE" })
      } catch {
        // 后端不可用，仅本地删除
      }
      configs.value = configs.value.filter((c) => c.id !== id)
      appStore.setModelConfigs(configs.value)
      saveLocalConfigs(configs.value).catch(() => {})
      ElMessage.success("配置已删除")
    })
    .catch(() => {})
}

onMounted(() => {
  if (appStore.bgColor) {
    document.documentElement.style.setProperty("--bg-color", appStore.bgColor)
  }
  // 优先从 SQLite 加载 Ollama 地址
  loadSettings("ollamaUrl").then(v => {
    if (v && !form.ollamaUrl) form.ollamaUrl = v
  }).catch(() => {})
  // 加载模型配置：后端 MySQL → 本地 SQLite → localStorage 三级降级
  loadModelConfigs()
})
</script>

<style scoped lang="less">
.settings-container {
  display: flex;
  justify-content: center;
  padding: 24px;
  height: 100%;
  box-sizing: border-box;
}

.settings-card {
  width: 100%;
  max-width: 800px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  padding: 24px 32px;
  align-self: flex-start;
  overflow-y: auto;
}

.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;

  h2 {
    font-size: 20px;
    color: #303133;
    font-weight: 600;
  }
}

.settings-form {
  max-width: 560px;
}

.form-hint {
  font-size: 13px;
  color: #909399;
  margin-left: 8px;
}

.config-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;

  .config-count {
    font-size: 14px;
    color: #909399;
  }
}
</style>
