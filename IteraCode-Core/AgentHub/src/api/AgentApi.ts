import service from "@/http"
import { AuthApi, AgentApi, AdminApi } from "./common"
import type {
  LoginDTO,
  RegisterDTO,
  PasswordDTO,
  DecomposeTask,
  ModelConfig,
  ApiResponse,
} from "./dto"

// ==================== 认证 API ====================
export const authApi = {
  login(data: LoginDTO): Promise<ApiResponse> {
    return service.post(AuthApi.Login, data)
  },
  register(data: RegisterDTO): Promise<ApiResponse> {
    return service.post(AuthApi.Register, data)
  },
  changePassword(data: PasswordDTO): Promise<ApiResponse> {
    return service.put(AuthApi.Password, data)
  },
  getUserInfo(): Promise<ApiResponse> {
    return service.get(AuthApi.UserInfo)
  },
}

// ==================== Agent 需求拆解 API ====================
export const agentApi = {
  /** 需求拆解 */
  decompose(requirement: string): Promise<ApiResponse> {
    return service.post(AgentApi.Decompose, { requirement })
  },

  /** 确认或调整拆解结果 */
  confirm(taskId: string, action: "confirm" | "adjust"): Promise<ApiResponse> {
    return service.post(AgentApi.Confirm, { taskId, action })
  },

  /** 获取执行结果 */
  getResult(taskId: string): Promise<ApiResponse> {
    return service.get(`${AgentApi.Result}/${taskId}`)
  },
}

// ==================== 管理 API ====================
export const adminApi = {
  getModelConfigs(): Promise<ApiResponse> {
    return service.get(AdminApi.Models)
  },
  addModelConfig(config: Omit<ModelConfig, "id">): Promise<ApiResponse> {
    return service.post(AdminApi.Models, config)
  },
  deleteModelConfig(id: number): Promise<ApiResponse> {
    return service.delete(`${AdminApi.Models}/${id}`)
  },
}
