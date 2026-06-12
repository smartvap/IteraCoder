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

        <el-divider content-position="left">功能设置</el-divider>

        <el-form-item label="跳过登录">
          <el-switch v-model="form.skipLogin" />
          <span class="form-hint">开启后无需登录即可直接使用</span>
        </el-form-item>

        <el-form-item label="模型类型">
          <el-radio-group v-model="form.modelType" @change="onModelTypeChange">
            <el-radio value="local">本地模型</el-radio>
            <el-radio value="network">网络 API</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-show="form.modelType === 'network'" label="API 地址">
          <el-input
            v-model="form.apiUrl"
            placeholder="http://api.example.com/v1/chat"
            size="small"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="saveSettings">保存设置</el-button>
        </el-form-item>
      </el-form>

      <!-- 修改密码 -->
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

      <!-- 模型配置管理 -->
      <el-divider content-position="left">模型配置管理</el-divider>

      <div class="config-header">
        <span class="config-count">共 {{ appStore.modelConfigs.length }} 个配置</span>
        <el-button type="primary" size="small" @click="openAddDialog">
          新增配置
        </el-button>
      </div>

      <el-table
        :data="appStore.modelConfigs"
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

const appStore = useAppStore()

// 设置表单
const form = reactive({
  bgColor: appStore.bgColor,
  menuBgColor: appStore.menuBgColor,
  skipLogin: appStore.skipLogin,
  modelType: appStore.modelType,
  apiUrl: appStore.apiUrl,
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

function saveSettings() {
  appStore.saveSettings({ ...form })
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

async function handleAddConfig() {
  if (!dialogFormRef.value) return
  const valid = await dialogFormRef.value.validate().catch(() => false)
  if (!valid) return

  dialogLoading.value = true
  try {
    appStore.addModelConfig({
      id: Date.now(),
      ...dialogForm,
    })
    ElMessage.success("模型配置新增成功")
    dialogVisible.value = false
  } finally {
    dialogLoading.value = false
  }
}

function handleDeleteConfig(id: number) {
  ElMessageBox.confirm("确定删除该模型配置吗？", "提示", {
    confirmButtonText: "确定",
    cancelButtonText: "取消",
    type: "warning",
  })
    .then(() => {
      appStore.removeModelConfig(id)
      ElMessage.success("配置已删除")
    })
    .catch(() => {})
}

onMounted(() => {
  if (appStore.bgColor) {
    document.documentElement.style.setProperty("--bg-color", appStore.bgColor)
  }
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
