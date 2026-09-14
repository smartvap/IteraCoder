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
        <img v-show="!collapsed" class="aside-logo" src="/icon.png" alt="logo" />
        <h1 v-show="!collapsed">智研协同平台</h1>
        <img v-show="collapsed" class="aside-logo-collapsed" src="/icon.png" alt="logo" />
      </div>
      <el-divider v-show="!collapsed" />

      <template v-for="item in menuRouterList" :key="item.path">
        <!-- 有子菜单：渲染二级菜单 -->
        <el-sub-menu v-if="item.children && item.children.length > 0" :index="item.path">
          <template #title>
            <el-icon>
              <component :is="item.meta?.icon"></component>
            </el-icon>
            <span>{{ item.meta?.description ? $t(item.meta.description) : '' }}</span>
          </template>
          <el-menu-item
            v-for="child in item.children"
            :key="child.path"
            :index="child.path"
            @click="handleSelect(child)"
          >
            <el-icon>
              <component :is="child.meta?.icon"></component>
            </el-icon>
            <template #title>{{ child.meta?.description ? $t(child.meta.description) : '' }}</template>
          </el-menu-item>
        </el-sub-menu>

        <!-- 无子菜单：渲染一级菜单 -->
        <el-menu-item
          v-else
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

// 过滤出菜单项，根据登录状态和角色控制显示（支持二级菜单）
const menuRouterList = computed(() => {
  const token = !!localStorage.getItem("token")
  const userRole = localStorage.getItem("userRole")

  const canShow = (item: RouteRecordRaw) => {
    if (!item.meta?.isMenu) return false
    if (item.path === "/login") return !token
    if (item.meta?.requiresAuth && !token) return false
    if (item.meta?.roles && userRole && !item.meta.roles.includes(userRole)) return false
    return true
  }

  return routes
    .filter(canShow)
    .map((item) => {
      if (item.children && item.children.length > 0) {
        const visibleChildren = item.children.filter(canShow)
        return { ...item, children: visibleChildren }
      }
      return item
    })
    .filter((item) => {
      // 父级菜单没有可见子菜单时隐藏
      if (item.children && item.children.length > 0) return item.children.length > 0
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

  .aside-logo {
    width: 28px;
    height: 28px;
    border-radius: 6px;
    margin-bottom: 4px;
  }

  .aside-logo-collapsed {
    width: 24px;
    height: 24px;
    border-radius: 5px;
    margin: 8px 0;
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
