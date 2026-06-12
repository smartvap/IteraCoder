/**
 * 数据接口定义（DTO）
 */

// ==================== AgentHub 原型类型 ====================

export interface Message {
  role: string
  content: string
}

export interface ChatOptions {
  model: string
  maxHistoryLength: number
  temperature: number
}

export interface ChatDTO {
  messages: Message[]
  chatOptions: ChatOptions
  prompt: string
}

export interface LoginDTO {
  username: string
  password: string
}

export interface RegisterDTO {
  username: string
  password: string
  name: string
  phone: string
}

export interface PasswordDTO {
  oldPassword: string
  newPassword: string
}

export interface ModelConfig {
  id: number
  name: string
  url: string
  apiKey: string
  modelName: string
}

export interface DecomposeTask {
  title: string
  desc: string
  priority: "high" | "mid" | "low"
}

export interface DecomposeResult {
  tasks: DecomposeTask[]
  taskId: string
}

export interface ExecutionResult {
  results: Array<{ text: string; status: "ok" | "fail" }>
  summary: {
    percent: number
    text: string
  }
}

export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
}

// ==================== rda-ai-web 类型 ====================

export interface SelectDto {
  page: number
  pageSize: number
}

export interface AddDto {
  baseUrl: string
  apiKey: string
  describe: string
}

export interface QueryFileDto {
  page: number
  pageSize: number
  fileName: string | undefined
}

export interface DrawOptions {
  model: string
  width: number
  height: number
  format: string
}

export interface DrawImageDto {
  prompt: string
  options: DrawOptions
}

export interface LogQueryParams {
  page: number
  size: number
  methodName?: string
  className?: string
  requestParams?: string
}

export interface LogInfo {
  id: string
  methodName: string
  className: string
  requestTime: string
  requestParams: string
  response: string | null
}

export interface StoreFile {
  id: number
  url: string
  fileName: string
  vectorId: string[]
  createTime: Date
  updateTime: Date
}
