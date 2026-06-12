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
            <el-dropdown @command="handleCommand">
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
                    <el-icon :size="16"><SwitchButton /></el-icon> 退出系统
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </el-header>
        <el-main class="layout-main">
          <RouterView />
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { logout } from "@/api/authUtils"
import { useAppStore } from "@/store/app"
import BasicAside from "@/components/BasicAside.vue"
import { User, ArrowDown, Setting, SwitchButton, Fold, Expand } from "@element-plus/icons-vue"
import routes from "@/router/config"

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
  return r?.meta?.description || "Agent Hub"
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

.layout-main {
  height: calc(100vh - 68px);
  margin-top: 10px;
  padding: 0;
  background-color: var(--bg-color, #f3f3f3);
  overflow-y: auto;
  overflow-x: hidden;
}
</style>
