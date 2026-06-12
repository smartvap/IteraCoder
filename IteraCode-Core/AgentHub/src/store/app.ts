// 应用全局状态管理
import type { ModelConfig } from "@/api/dto"
import { defineStore } from "pinia"

const APP_SETTINGS = "agent-hub-app-settings"

interface AppSettings {
  bgColor: string
  menuBgColor: string
  skipLogin: boolean
  modelType: "local" | "network"
  apiUrl: string
  currentModel: string
  modelConfigs: ModelConfig[]
}

const defaultSettings: AppSettings = {
  bgColor: "#f5f7fa",
  menuBgColor: "#1A478A",
  skipLogin: false,
  modelType: "local",
  apiUrl: "",
  currentModel: "",
  modelConfigs: [],
}

export const useAppStore = defineStore("app", {
  state: () => {
    let settingsStr = localStorage.getItem(APP_SETTINGS)
    let settings: AppSettings =
      settingsStr === null ? defaultSettings : JSON.parse(settingsStr)
    return {
      ...settings,
      isLoggedIn: !!localStorage.getItem("token"),
      username: localStorage.getItem("username") || "",
    }
  },

  getters: {
    isElectron(): boolean {
      return !!(window as any).electronAPI
    },
  },

  actions: {
    saveSettings(partial: Partial<AppSettings>) {
      Object.assign(this, partial)
      const toSave: AppSettings = {
        bgColor: this.bgColor,
        menuBgColor: this.menuBgColor,
        skipLogin: this.skipLogin,
        modelType: this.modelType,
        apiUrl: this.apiUrl,
        currentModel: this.currentModel,
        modelConfigs: this.modelConfigs,
      }
      localStorage.setItem(APP_SETTINGS, JSON.stringify(toSave))
    },

    login(user: string) {
      this.isLoggedIn = true
      this.username = user
    },

    skipLoginAction() {
      this.isLoggedIn = true
      this.username = "游客"
    },

    logout() {
      this.isLoggedIn = false
      this.username = ""
      localStorage.removeItem("token")
      localStorage.removeItem("username")
      localStorage.removeItem("userRole")
      localStorage.removeItem("userId")
    },

    addModelConfig(config: ModelConfig) {
      this.modelConfigs.push(config)
      this.saveSettings({ modelConfigs: this.modelConfigs })
    },

    removeModelConfig(id: number) {
      this.modelConfigs = this.modelConfigs.filter((c) => c.id !== id)
      this.saveSettings({ modelConfigs: this.modelConfigs })
    },
  },
})
