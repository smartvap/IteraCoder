<template>
  <div id="basic-aside">
    <el-menu
      :default-active="defaultPath"
      class="aside-menu"
      :collapse="collapsed"
      :background-color="menuBgColor"
      text-color="#FFFFFF"
      active-text-color="#91CC75"
    >
      <div class="menu-header" :style="{ backgroundColor: menuBgColor }">
        <h1 v-show="!collapsed">Agent Hub</h1>
        <el-text v-show="!collapsed" style="color: #bfcbd9" size="small">需求拆解智能体</el-text>
        <el-icon v-show="collapsed" size="24" style="color: #fff; margin: 8px 0;">🤖</el-icon>
      </div>
      <el-divider v-show="!collapsed" />

      <template v-for="item in menuRouterList" :key="item.path">
        <el-menu-item
          :index="item.path"
          @click="handleSelect(item)"
        >
          <el-icon>
            <component :is="item.meta?.icon"></component>
          </el-icon>
          <template #title>{{ item.meta?.description ? $t(item.meta.description) : '' }}</template>
        </el-menu-item>
      </template>
    </el-menu>

    <!-- 折叠切换按钮 -->
    <div class="aside-footer" :style="{ backgroundColor: darkenColor(menuBgColor) }" @click="$emit('toggleAside')">
      <el-icon size="18" style="color: #fff; cursor: pointer;">
        <Fold v-if="!collapsed" />
        <Expand v-else />
      </el-icon>
      <span v-show="!collapsed" class="footer-text">收起菜单</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import routes from "@/router/config"
import router from "@/router"
import type { RouteRecordRaw } from "vue-router"
import { Fold, Expand } from "@element-plus/icons-vue"

const props = defineProps<{ collapsed: boolean; menuBgColor: string }>()
const emit = defineEmits(["toggleAside"])

const path = router.currentRoute.value.fullPath
const defaultPath = ref(path === "/" ? "/login" : path)

// 过滤出菜单项，根据登录状态和角色控制显示
const menuRouterList = computed(() => {
  const token = !!localStorage.getItem("token")
  const userRole = localStorage.getItem("userRole")

  return routes.filter((item) => {
    if (!item.meta?.isMenu) return false
    if (item.path === "/login") return !token
    if (item.meta?.requiresAuth && !token) return false
    if (item.meta?.roles && userRole && !item.meta.roles.includes(userRole)) return false
    return true
  })
})

router.afterEach((to) => {
  defaultPath.value = to.path
})

const handleSelect = (item: RouteRecordRaw) => {
  router.push({ path: item.path })
}

// 颜色加深辅助函数（用于折叠按钮区域）
function darkenColor(hex: string, amount: number = 20): string {
  const c = hex.replace("#", "")
  if (c.length < 6) return hex
  const r = Math.max(0, parseInt(c.substring(0, 2), 16) - amount)
  const g = Math.max(0, parseInt(c.substring(2, 4), 16) - amount)
  const b = Math.max(0, parseInt(c.substring(4, 6), 16) - amount)
  return `#${r.toString(16).padStart(2, "0")}${g.toString(16).padStart(2, "0")}${b.toString(16).padStart(2, "0")}`
}
</script>

<style scoped lang="less">
#basic-aside {
  height: 100%;
  display: flex;
  flex-direction: column;
}

:deep(.el-menu) {
  z-index: 10;
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}

.menu-header {
  height: 80px;
  display: flex;
  justify-content: center;
  align-items: center;
  color: #bfcbd9;
  flex-wrap: wrap;
  flex-direction: column;
  padding: 16px 0;
  h1 {
    font-size: 20px;
    margin: 0;
    color: #ffffff;
    letter-spacing: 2px;
  }
}

.aside-menu {
  border-right: none;
  border: 1px solid rgb(239, 239, 239);
  flex: 1;
  box-shadow: 1px 1px 1px 1px rgb(240, 239, 239);
}

.aside-footer {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  border: 1px solid rgb(239, 239, 239);
  border-top: none;
  transition: background-color 0.2s;

  &:hover {
    filter: brightness(0.85);
  }

  .footer-text {
    color: #bfcbd9;
    font-size: 13px;
  }
}
</style>
