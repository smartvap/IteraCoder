import type { RouteRecordRaw } from "vue-router"

const routes: RouteRecordRaw[] = [
  {
    path: "/",
    name: "index",
    redirect: "/login",
    meta: {
      isMenu: false,
      requiresAuth: false,
    },
  },
  {
    path: "/login",
    name: "login",
    component: () => import("@/view/login/LoginView.vue"),
    meta: {
      isMenu: true,
      requiresAuth: false,
      description: "个人中心",
      icon: "Avatar",
    },
  },
  {
    path: "/ragChat",
    name: "ragChat",
    component: () => import("@/view/ragChat/RagChatView.vue"),
    meta: {
      isMenu: true,
      description: "AI问答",
      icon: "ChatDotRound",
      requiresAuth: true,
    },
  },
  {
    path: "/decompose",
    name: "decompose",
    component: () => import("@/view/chat/ChatView.vue"),
    meta: {
      isMenu: true,
      description: "需求拆解",
      icon: "List",
      requiresAuth: true,
    },
  },
  {
    path: "/draw",
    name: "draw",
    component: () => import("@/view/draw/DrawImageView.vue"),
    meta: {
      isMenu: true,
      description: "AI绘画",
      icon: "PictureRounded",
      requiresAuth: true,
    },
  },
  {
    path: "/settings",
    name: "settings",
    component: () => import("@/view/settings/SettingsView.vue"),
    meta: {
      isMenu: true,
      description: "系统设置",
      icon: "Setting",
      requiresAuth: true,
    },
  },
  {
    path: "/user",
    name: "user",
    component: () => import("@/view/user/UserView.vue"),
    meta: {
      isMenu: true,
      description: "用户管理",
      icon: "UserFilled",
      requiresAuth: true,
    },
  },
  {
    path: "/logInfo",
    name: "logInfo",
    component: () => import("@/view/logInfo/LogInfoView.vue"),
    meta: {
      isMenu: true,
      description: "日志管理",
      icon: "List",
      requiresAuth: true,
    },
  },
]

export default routes
