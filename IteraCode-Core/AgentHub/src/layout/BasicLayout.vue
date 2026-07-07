<template>
  <!-- 登录页不使用侧边栏布局 -->
  <div v-if="isLoginPage" class="login-fullscreen">
    <RouterView />
  </div>

  <!-- 其他页面使用侧边栏布局 -->
  <div v-else id="basic-layout" :class="{ 'aside-collapsed': asideCollapsed }">
    <el-container>
      <el-aside :style="asideStyle" :class="{ collapsed: asideCollapsed }">
        <BasicAside
          :collapsed="asideCollapsed"
          :menu-bg-color="appStore.menuBgColor"
          @toggle-aside="toggleAside"
        />
      </el-aside>
      <el-container class="main-container">
        <el-header class="layout-header">
          <div class="header-left">
            <el-button text class="collapse-btn" @click="toggleAside">
              <el-icon :size="18">
                <Fold v-if="!asideCollapsed" />
                <Expand v-else />
              </el-icon>
            </el-button>
            <span class="header-title">{{ currentTitle }}</span>
          </div>
          <div class="header-right">
            <el-dropdown v-if="appStore.isLoggedIn" @command="handleCommand">
              <span class="user-dropdown">
                <el-icon :size="16"><User /></el-icon>
                {{ username }}
                <el-icon :size="12" style="margin-left:2px"><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="settings">
                    <el-icon :size="16"><Setting /></el-icon> 系统设置
                  </el-dropdown-item>
                  <el-dropdown-item divided command="logout">
                    <el-icon :size="16"><SwitchButton /></el-icon> 退出登录
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button v-else type="primary" size="small" @click="router.push('/login')">
              <el-icon :size="14" style="margin-right:4px"><User /></el-icon>
              登录
            </el-button>
            <!-- Electron 窗口控制按钮 -->
            <div v-if="isElectron" class="electron-controls">
              <button class="win-btn" title="最小化" @click="minimizeWin">
                <svg width="12" height="12" viewBox="0 0 12 12"><rect y="5" width="12" height="2" fill="currentColor"/></svg>
              </button>
              <button class="win-btn" title="最大化" @click="maximizeWin">
                <svg width="12" height="12" viewBox="0 0 12 12"><rect x="1" y="1" width="10" height="10" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>
              </button>
              <button class="win-btn win-close" title="关闭" @click="closeWin">
                <svg width="12" height="12" viewBox="0 0 12 12"><line x1="2" y1="2" x2="10" y2="10" stroke="currentColor" stroke-width="1.5"/><line x1="10" y1="2" x2="2" y2="10" stroke="currentColor" stroke-width="1.5"/></svg>
              </button>
            </div>
          </div>
        </el-header>
        <el-main class="layout-main">
          <RouterView v-slot="{ Component }">
            <KeepAlive :include="['AgentChat']">
              <component :is="Component" />
            </KeepAlive>
          </RouterView>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { logout } from "@/api/authUtils"
import { useAppStore } from "@/store/app"
import { i18n } from "@/locales"
import BasicAside from "@/components/BasicAside.vue"
import { User, ArrowDown, Setting, SwitchButton, Fold, Expand } from "@element-plus/icons-vue"
import routes from "@/router/config"

const isElectron = !!(window as any).electronAPI

function minimizeWin() { (window as any).electronAPI?.minimize() }
function maximizeWin() { (window as any).electronAPI?.maximize() }
function closeWin() { (window as any).electronAPI?.close() }

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()

const asideCollapsed = ref(false)

const asideStyle = computed(() => ({
  height: "100vh",
  width: asideCollapsed.value ? "64px" : "210px",
  backgroundColor: appStore.menuBgColor,
  transition: "width 0.3s",
}))

const isLoginPage = computed(() => route.path === "/login")
const username = computed(() => appStore.username || "用户")

const currentTitle = computed(() => {
  const r = routes.find((r) => r.path === route.path)
  const desc = r?.meta?.description as string
  return desc ? i18n.global.t(desc) : "Agent Hub"
})

function toggleAside() {
  asideCollapsed.value = !asideCollapsed.value
}

const handleCommand = (command: string) => {
  if (command === "settings") {
    router.push("/settings")
  } else if (command === "logout") {
    appStore.logout()
    logout()
  }
}
</script>

<style scoped lang="less">
.login-fullscreen {
  height: 100vh;
  overflow: hidden;
}

#basic-layout {
  height: 100vh;
  overflow: hidden;
  background: var(--bg-color, #f5f7fa);

  .main-container {
    margin-left: 10px;
    margin-right: 10px;
    overflow: hidden;
  }
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 48px;
  padding: 0 12px;
  margin-top: 10px;
  background-color: #fff;
  border-radius: 4px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  -webkit-app-region: drag;

  .header-left {
    display: flex;
    align-items: center;
    gap: 8px;

    .collapse-btn {
      color: #606266;
      padding: 4px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      &:hover {
        color: #409eff;
      }
      .el-icon {
        font-size: 18px;
      }
    }

    .header-title {
      font-size: 16px;
      font-weight: 600;
      color: #303133;
    }
  }

  .header-right {
    .user-dropdown {
      display: flex;
      align-items: center;
      gap: 4px;
      cursor: pointer;
      color: #606266;
      font-size: 14px;
      line-height: normal;
      &:hover { color: #409eff; }
    }
  }
}

.electron-controls {
  display: flex; align-items: center; gap: 4px; margin-left: 12px;
  -webkit-app-region: no-drag;
}
.win-btn {
  display: flex; align-items: center; justify-content: center;
  width: 28px; height: 28px; border: none; border-radius: 4px;
  background: transparent; color: #666; cursor: pointer;
  transition: background .1s;
}
.win-btn:hover { background: #e5e5e5; }
.win-close:hover { background: #e81123; color: #fff; }

.layout-main {
  height: calc(100vh - 68px);
  margin-top: 10px;
  padding: 0;
  background-color: var(--bg-color, #f3f3f3);
  overflow-y: auto;
  overflow-x: hidden;
}
</style>
