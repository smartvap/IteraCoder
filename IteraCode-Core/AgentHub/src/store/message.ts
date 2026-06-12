// 聊天消息状态管理
import type { DecomposeTask, ExecutionResult } from "@/api/dto"
import { defineStore } from "pinia"

export interface ChatMessage {
  id: number
  type: "user" | "agent" | "card"
  content: string
  cardType?: "decompose" | "review" | "aggregate"
  cardTitle?: string
  cardData?: DecomposeTask[] | ExecutionResult | any
  timestamp: string
}

export const useChatMessageStore = defineStore("message", {
  state: () => ({
    messages: [] as ChatMessage[],
    currAIMessage: "",
    isChating: false,
  }),

  getters: {
    getMessages(): ChatMessage[] {
      return this.messages
    },
    getIsChating(): boolean {
      return this.isChating
    },
  },

  actions: {
    setIsChating(isChating: boolean) {
      this.$patch({ isChating })
    },

    addMessage(msg: Omit<ChatMessage, "id" | "timestamp">) {
      this.messages.push({
        id: Date.now() + Math.random(),
        ...msg,
        timestamp: new Date().toISOString(),
      })
    },

    setCurrMessage(content: string) {
      let len = this.messages.length
      if (len > 0) {
        this.messages[len - 1].content = content
      }
    },

    resetMessages() {
      this.messages = []
      this.currAIMessage = ""
    },
  },
})
