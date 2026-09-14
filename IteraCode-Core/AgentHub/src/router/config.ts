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
      description: "智研协同平台",
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
      title: "对话",
      description: "对话",
      icon: "ChatDotRound",
      requiresAuth: true,
    },
  },
  {
    path: "/history",
    name: "history",
    redirect: "/conversation-history",
    meta: {
      isMenu: true,
      title: "历史记录",
      description: "历史记录",
      icon: "Clock",
      requiresAuth: true,
    },
    children: [
      {
        path: "/conversation-history",
        name: "conversationHistory",
        component: () => import("@/view/history/ConversationHistoryView.vue"),
        meta: {
          isMenu: true,
          description: "对话历史",
          icon: "ChatLineSquare",
          requiresAuth: true,
        },
      },
      {
        path: "/workflow-list",
        name: "workflowList",
        component: () => import("@/view/workflow/WorkflowListView.vue"),
        meta: {
          isMenu: true,
          description: "工作流查询",
          icon: "Cpu",
          requiresAuth: true,
        },
      },
      {
        path: "/stats-token",
        name: "tokenStats",
        component: () => import("@/view/stats/TokenStatsView.vue"),
        meta: {
          isMenu: true,
          description: "Token统计",
          icon: "DataAnalysis",
          requiresAuth: true,
        },
      },
    ],
  },
  {
    path: "/system",
    name: "system",
    redirect: "/sensitive-word",
    meta: {
      isMenu: true,
      title: "系统管理",
      description: "系统管理",
      icon: "Setting",
      requiresAuth: true,
    },
    children: [
      {
        path: "/sensitive-word",
        name: "sensitiveWord",
        component: () => import("@/view/sensitive/SensitiveWordView.vue"),
        meta: {
          isMenu: true,
          description: "敏感词管理",
          icon: "WarningFilled",
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
          icon: "Tools",
          requiresAuth: true,
        },
      },
    ],
  },
  {
    path: "/personal",
    name: "personal",
    redirect: "/profile",
    meta: {
      isMenu: true,
      title: "个人中心",
      description: "个人中心",
      icon: "User",
      requiresAuth: true,
    },
    children: [
      {
        path: "/profile",
        name: "profile",
        component: () => import("@/view/profile/ProfileView.vue"),
        meta: {
          isMenu: true,
          description: "个人资料",
          icon: "User",
          requiresAuth: true,
        },
      },
    ],
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
