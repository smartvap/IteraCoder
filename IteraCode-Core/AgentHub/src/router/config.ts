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
  // {
  //   path: "/ragChat",
  //   name: "ragChat",
  //   component: () => import("@/view/ragChat/RagChatView.vue"),
  //   meta: {
  //     isMenu: true,
  //     description: "AI问答",
  //     icon: "ChatDotRound",
  //     requiresAuth: true,
  //   },
  // },
  {
    path: "/agent-chat",
    name: "agentChat",
    component: () => import("@/view/chat/AgentChat.vue"),
    meta: {
      isMenu: true,
      description: "agentChat",
      icon: "ChatDotRound",
      requiresAuth: true,
    },
  },
  {
    path: "/chat-index",
    name: "chatIndex",
    component: () => import("@/view/chat/ChatIndex.vue"),
    meta: {
      isMenu: true,
      description: "chat",
      icon: "ChatDotRound",
      requiresAuth: true,
    },
  },
  // {
  //   path: "/decompose",
  //   name: "decompose",
  //   component: () => import("@/view/chat/ChatView2.vue"),
  //   meta: {
  //     isMenu: true,
  //     description: "chat",
  //     icon: "ChatDotRound",
  //     requiresAuth: true,
  //   },
  // },
  // {
  //   path: "/chat2",
  //   name: "chat2",
  //   component: () => import("@/view/chat/ChatView2.vue"),
  //   meta: {
  //     isMenu: true,
  //     description: "本地模型对话",
  //     icon: "Monitor",
  //     requiresAuth: true,
  //   },
  // },
  // {
  //   path: "/draw",
  //   name: "draw",
  //   component: () => import("@/view/draw/DrawImageView.vue"),
  //   meta: {
  //     isMenu: true,
  //     description: "AI绘画",
  //     icon: "PictureRounded",
  //     requiresAuth: true,
  //   },
  // },
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
    path: "/stats",
    name: "Stats",
    component: () => import("@/view/stats/StatsView.vue"),
    meta: { title: "Token 统计" },
  },
  // {
  //   path: "/user",
  //   name: "user",
  //   component: () => import("@/view/user/UserView.vue"),
  //   meta: {
  //     isMenu: true,
  //     description: "用户管理",
  //     icon: "UserFilled",
  //     requiresAuth: true,
  //   },
  // },
  // {
  //   path: "/logInfo",
  //   name: "logInfo",
  //   component: () => import("@/view/logInfo/LogInfoView.vue"),
  //   meta: {
  //     isMenu: true,
  //     description: "日志管理",
  //     icon: "List",
  //     requiresAuth: true,
  //   },
  // },
]

export default routes
