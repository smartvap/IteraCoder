export default {
  menu: { aiChat: "AI对话", settings: "系统设置", stats: "Token统计", requirement: "需求拆解", profile: "个人中心" },
  chat: {
    placeholder: "输入问题，Enter 发送，Shift+Enter 换行", send: "发送", stop: "停止输出",
    thinking: "思考中...", reasoning: "思考过程", todayTokens: "今日 {n} tokens",
    newChat: "新建对话", clearChat: "清空对话", refreshModels: "刷新模型列表",
    copy: "复制", retry: "重试", regenerate: "重新生成",
    copied: "已复制到剪贴板", stopped: "已停止生成", error: "网络异常，请稍后重试",
    emptyTitle: "开始新对话", emptyDesc: "输入问题开始与 AI 对话",
    escHint: "Esc 中断输出", sendHint: "Enter 发送 · Shift+Enter 换行",
  },
  settings: {
    title: "系统设置", language: "语言", appearance: "外观设置", functions: "功能设置",
    bgColor: "背景颜色", menuTheme: "菜单主题", skipLogin: "跳过登录",
    modelType: "模型类型", local: "本地", network: "网络", apiUrl: "API 地址", apiKey: "API Key",
    save: "保存设置", changePassword: "修改密码", oldPassword: "旧密码", newPassword: "新密码", confirmPassword: "确认密码",
    modelConfig: "模型配置", modelName: "模型名称", addModel: "添加模型",
  },
  stats: {
    title: "Token 用量统计", todayRequests: "今日请求数", inputTokens: "输入 Token", outputTokens: "输出 Token",
    totalTokens: "总计 Token", detailTitle: "请求明细", time: "时间", model: "模型", ip: "IP",
    duration: "耗时(ms)", status: "状态", success: "成功", failed: "失败",
  },
  common: { copy: "复制", delete: "删除", save: "保存", cancel: "取消", confirm: "确认", retry: "重试", loading: "加载中...", noData: "暂无数据" },
}
