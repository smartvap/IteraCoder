<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <div class="logo">🤖</div>
        <h2>用户登录</h2>
        <p class="subtitle">RDA-AI 自动化研发智能体系统</p>
      </div>

      <el-form
        ref="loginFormRef"
        :model="loginForm"
        :rules="loginRules"
        label-position="top"
        @keyup.enter="handleLogin"
      >
        <el-form-item prop="userName">
          <el-input
            v-model="loginForm.userName"
            placeholder="请输入用户名"
            :prefix-icon="User"
            size="large"
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            show-password
            size="large"
          />
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            class="btn-block"
            :loading="loading"
            @click="handleLogin"
          >
            登录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="login-footer">
        <el-button link type="primary" @click="showRegisterDialog">
          没有账号？去注册
        </el-button>
        <el-button link class="skip-btn" @click="handleSkipLogin">
          跳过登录，直接使用
        </el-button>
      </div>
    </div>

    <!-- 注册对话框 -->
    <el-dialog
      v-model="registerDialogVisible"
      title="注册"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        label-width="100px"
        v-loading="loading"
      >
        <el-form-item label="用户名" prop="userName">
          <el-input v-model="registerForm.userName" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="registerForm.password" type="password" placeholder="请输入密码" show-password />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="registerForm.phone" placeholder="请输入手机号" />
        </el-form-item>
        <el-form-item label="姓名" prop="name">
          <el-input v-model="registerForm.name" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="性别" prop="sex">
          <el-radio-group v-model="registerForm.sex">
            <el-radio label="男">男</el-radio>
            <el-radio label="女">女</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="身份证号" prop="idNumber">
          <el-input v-model="registerForm.idNumber" placeholder="请输入身份证号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="handleRegister">注册</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { User, Lock } from "@element-plus/icons-vue"
import { ElMessage } from "element-plus"
import { useAppStore } from "@/store/app"
import { registerUserApi } from "@/api/UserApi"
import type { FormInstance, FormRules } from "element-plus"

const router = useRouter()
const appStore = useAppStore()

const loginFormRef = ref<FormInstance>()
const loading = ref(false)

const loginForm = reactive({
  userName: "",
  password: "",
})

const loginRules: FormRules = {
  userName: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
}

async function handleLogin() {
  if (!loginFormRef.value) return
  const valid = await loginFormRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const res = await fetch(`/api/v1/user/login?userName=${loginForm.userName}&password=${loginForm.password}`, { method: "POST" })
    const data = await res.json()
    if (data.code === 0) {
      localStorage.setItem("token", data.data.token)
      localStorage.setItem("userRole", data.data.userName)
      localStorage.setItem("userId", data.data.id)
      appStore.login(loginForm.userName)
      ElMessage.success("登录成功")
      router.push("/ragChat")
    } else {
      ElMessage.error(data.message || "用户名或密码错误")
    }
  } catch (_e) {
    // 离线模式
    localStorage.setItem("token", "dev-token")
    localStorage.setItem("username", loginForm.userName)
    appStore.login(loginForm.userName)
    ElMessage.success("登录成功（离线模式）")
    router.push("/ragChat")
  } finally {
    loading.value = false
  }
}

function handleSkipLogin() {
  appStore.skipLoginAction()
  localStorage.setItem("token", "guest-token")
  ElMessage.success("已跳过登录")
  router.push("/ragChat")
}

// ===== 注册 =====
const registerDialogVisible = ref(false)
const registerFormRef = ref<FormInstance>()

const registerForm = reactive({
  name: "",
  userName: "",
  password: "",
  phone: "",
  sex: "男",
  idNumber: "",
  status: 1,
})

const registerRules: FormRules = {
  userName: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
  phone: [
    { required: true, message: "请输入手机号", trigger: "blur" },
    { pattern: /^1[3-9]\d{9}$/, message: "请输入正确的手机号", trigger: "blur" },
  ],
  name: [{ required: true, message: "请输入姓名", trigger: "blur" }],
  idNumber: [
    { required: true, message: "请输入身份证号", trigger: "blur" },
    { pattern: /(^\d{15}$)|(^\d{18}$)|(^\d{17}(\d|X|x)$)/, message: "请输入正确的身份证号", trigger: "blur" },
  ],
}

function showRegisterDialog() {
  registerForm.name = ""
  registerForm.userName = ""
  registerForm.password = ""
  registerForm.phone = ""
  registerForm.sex = "男"
  registerForm.idNumber = ""
  registerDialogVisible.value = true
}

async function handleRegister() {
  if (!registerFormRef.value) return
  const valid = await registerFormRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const res = await registerUserApi(registerForm)
    if (res.code === 0) {
      ElMessage.success("注册成功")
      registerDialogVisible.value = false
    } else {
      ElMessage.error(res.message || "注册失败")
    }
  } catch (_e) {
    ElMessage.error("注册失败，请稍后重试")
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="less">
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: var(--bg-color, #f5f7fa);
}

.login-card {
  width: 400px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  padding: 44px 40px;
}

.login-header {
  text-align: center;
  margin-bottom: 30px;

  .logo { font-size: 48px; line-height: 1; margin-bottom: 8px; }
  h2 { font-size: 22px; color: #303133; margin-bottom: 4px; font-weight: 600; }
  .subtitle { color: #909399; font-size: 14px; }
}

.btn-block { width: 100%; }

.login-footer {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-top: 18px;
  .skip-btn { color: #909399; font-size: 13px; }
}
</style>
