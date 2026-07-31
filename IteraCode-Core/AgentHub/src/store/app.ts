/**
 * 应用全局状态管理（Pinia Store）
 *
 * <p>管理应用的全局状态，包括：</p>
 * <ul>
 *   <li>用户认证状态（登录/游客）</li>
 *   <li>应用设置（主题颜色、模型配置、推理展示开关等）</li>
 *   <li>多模型配置管理</li>
 * </ul>
 *
 * <p>设置数据持久化到 {@code localStorage}，键名为 {@code agent-hub-app-settings}。</p>
 *
 * @module store/app
 */
import type { ModelConfig } from "@/api/dto"
import { i18n } from "@/locales"
import { defineStore } from "pinia"

/** localStorage 中设置数据的键名 */
const APP_SETTINGS = "agent-hub-app-settings"

/**
 * 应用设置接口
 *
 * @property bgColor       背景颜色
 * @property menuBgColor   菜单背景颜色
 * @property skipLogin     是否跳过登录（游客模式）
 * @property modelType     模型类型：local=本地 Ollama，network=远程 API
 * @property apiUrl        远程 API 地址
 * @property apiKey        远程 API 密钥
 * @property currentModel  当前选中的模型名称
 * @property modelConfigs  用户自定义的模型配置列表
 * @property showReasoning 是否显示推理/思考过程
 */
interface AppSettings {
  bgColor: string
  menuBgColor: string
  skipLogin: boolean
  modelType: "local" | "network"
  ollamaUrl: string
  apiUrl: string
  apiKey: string
  remoteProvider: string
  remoteModel: string
  currentModel: string
  modelConfigs: ModelConfig[]
  showReasoning: boolean
  language: string
  useMysql: boolean
  enableLogging: boolean
}

/** 默认设置值 */
const defaultSettings: AppSettings = {
  bgColor: "#f5f7fa",
  menuBgColor: "#1A478A",
  skipLogin: false,
  modelType: "local",
  ollamaUrl: "http://localhost:11434",
  apiUrl: "",
  apiKey: "",
  remoteProvider: "openai",
  remoteModel: "",
  currentModel: "",
  modelConfigs: [],
  showReasoning: true,
  language: localStorage.getItem("language") || "zh-CN",
  useMysql: false,
  enableLogging: false,
}

/**
 * 应用状态 Store
 *
 * <p>使用 Pinia 管理应用全局状态，初始化时从 localStorage 加载持久化的设置。</p>
 */
export const useAppStore = defineStore("app", {
  /**
   * 初始化状态
   *
   * <p>从 localStorage 读取设置，若不存在则使用默认值。
   * 同时读取 token 和 username 判断登录状态。</p>
   */
  state: () => {
    let settingsStr = localStorage.getItem(APP_SETTINGS)
    let settings: AppSettings =
      settingsStr === null ? defaultSettings : JSON.parse(settingsStr)
    return {
      ...settings,
    }
  },

  getters: {
    /** 判断是否在 Electron 环境中运行 */
    isElectron(): boolean {
      return !!(window as any).electronAPI
    },
    /** 是否已登录（实时读取 localStorage，排除游客令牌） */
    isLoggedIn(): boolean {
      const token = localStorage.getItem("token")
      return !!token && token !== "guest-token"
    },
    /** 当前用户名（已登录时返回，游客模式返回空） */
    username(): string {
      const token = localStorage.getItem("token")
      if (!token || token === "guest-token") return ""
      return localStorage.getItem("username") || ""
    },
  },

  actions: {
    /**
     * 保存应用设置（合并更新）
     *
     * <p>将传入的部分设置合并到当前状态，并持久化到 localStorage。</p>
     *
     * @param partial 需要更新的设置字段
     */
    saveSettings(partial: Partial<AppSettings>) {
      Object.assign(this, partial)
      const toSave: AppSettings = {
        bgColor: this.bgColor,
        menuBgColor: this.menuBgColor,
        skipLogin: this.skipLogin,
        modelType: this.modelType,
        ollamaUrl: this.ollamaUrl,
        apiUrl: this.apiUrl,
        apiKey: this.apiKey,
        currentModel: this.currentModel,
        modelConfigs: this.modelConfigs,
        showReasoning: this.showReasoning,
        language: this.language,
        useMysql: this.useMysql,
        enableLogging: this.enableLogging,
      }
      localStorage.setItem(APP_SETTINGS, JSON.stringify(toSave))
      // 同步关键设置到 SQLite 本地备份
      import("@/utils/modelConfigDb").then(m => {
        m.saveSettings("ollamaUrl", this.ollamaUrl)
      }).catch(() => {})
    },

    /** 退出登录 */
    logout() {
      localStorage.removeItem("token")
      localStorage.removeItem("username")
      localStorage.removeItem("userRole")
      localStorage.removeItem("userId")
      import("@/http/config").then(m => m.refreshBaseUrl()).catch(() => {})
    },

    /** 跳过登录（游客模式） */
    skipLoginAction() {
      this.skipLogin = true
      this.saveSettings({ skipLogin: true })
    },

    /**
     * 添加自定义模型配置
     *
     * @param config 模型配置对象
     */
    addModelConfig(config: ModelConfig) {
      this.modelConfigs.push(config)
      this.saveSettings({ modelConfigs: this.modelConfigs })
    },

    /**
     * 删除自定义模型配置
     *
     * @param id 模型配置 ID
     */
    removeModelConfig(id: number) {
      this.modelConfigs = this.modelConfigs.filter((c) => c.id !== id)
      this.saveSettings({ modelConfigs: this.modelConfigs })
    },

    /**
     * 批量设置模型配置
     *
     * <p>用于从后端加载配置后整体替换本地状态。</p>
     *
     * @param configs 模型配置数组
     */
    setModelConfigs(configs: ModelConfig[]) {
      this.modelConfigs = configs
      localStorage.setItem(
        APP_SETTINGS,
        JSON.stringify({
          bgColor: this.bgColor,
          menuBgColor: this.menuBgColor,
          skipLogin: this.skipLogin,
        modelType: this.modelType,
        ollamaUrl: this.ollamaUrl,
        apiUrl: this.apiUrl,
          apiKey: this.apiKey,
          currentModel: this.currentModel,
          modelConfigs: configs,
          showReasoning: this.showReasoning,
          language: this.language,
          useMysql: this.useMysql,
          enableLogging: this.enableLogging,
        }),
      )
    },
  },
})
