import router from "@/router"
import { ElMessage } from "element-plus"

/**
 * 处理认证错误
 * 当 API 返回 401 未授权或登录超时时调用
 */
export const handleAuthError = () => {
  ElMessage({
    message: "登录已过期，请重新登录",
    type: "warning",
    duration: 3000,
  })

  clearAuthStorage()
  router.push({ name: "login" })
}

export const clearAuthStorage = () => {
  localStorage.removeItem("token")
  localStorage.removeItem("userRole")
  localStorage.removeItem("userId")
  localStorage.removeItem("username")
}

/**
 * 主动退出登录
 */
export const logout = () => {
  clearAuthStorage()
  ElMessage({
    message: "已成功退出登录",
    type: "success",
    duration: 3000,
  })
  router.push({ name: "login" })
}

export default handleAuthError
