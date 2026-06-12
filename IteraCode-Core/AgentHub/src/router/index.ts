import { createRouter, createWebHashHistory } from "vue-router"
import routes from "./config"
import { ElMessage } from "element-plus"

declare module "vue-router" {
  interface RouteMeta {
    isMenu?: boolean
    requiresAuth?: boolean
    roles?: string[]
    description?: string
    icon?: string
  }
}

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

// 全局前置守卫
router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem("token")
  const userRole = localStorage.getItem("userRole")

  if (to.meta.requiresAuth) {
    if (!token) {
      ElMessage({ message: "请先登录", type: "warning" })
      next({ name: "login" })
    } else if (
      to.meta.roles &&
      Array.isArray(to.meta.roles) &&
      !to.meta.roles.includes(userRole)
    ) {
      ElMessage({ message: "您没有权限访问该页面", type: "error" })
      next({ name: "chat" })
    } else {
      next()
    }
  } else {
    next()
  }
})

export default router
