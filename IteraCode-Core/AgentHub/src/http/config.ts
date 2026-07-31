// Electron 打包后从 file:// 加载，相对路径无效，必须用绝对 URL
const isElectronPackaged = !!(window as any).electronAPI && !window.location.href.includes("localhost")

/** 本地接口地址（未登录/游客模式，走本地 H2 数据库） */
export const LOCAL_BASE_URL = isElectronPackaged ? "http://localhost:8988/api/v1" : "/api/v1"

/** 远程接口地址（已登录用户模式，走后端 MySQL） */
export const REMOTE_BASE_URL = isElectronPackaged ? "http://localhost:8988/api/v1" : "/api/v1"

/** 根据登录状态动态获取接口地址 */
export function getBaseUrl(): string {
  const token = localStorage.getItem("token")
  const isLoggedIn = !!token && token !== "guest-token"
  return isLoggedIn ? REMOTE_BASE_URL : LOCAL_BASE_URL
}

/** 默认使用动态地址 */
export let BASE_URL = getBaseUrl()

/** 登录/退出后重新计算 BASE_URL */
export function refreshBaseUrl() {
  BASE_URL = getBaseUrl()
}

// 请求头的基本信息
export const HEADER = {
  "Content-Type": "application/json;charset=UTF-8",
}
