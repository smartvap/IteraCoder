import service from "@/http"
import { WorkflowApi } from "./common"

// ========== 类型定义 ==========

/** 聊天路由请求 */
export interface ChatRouteRequest {
  message: string
}

/** 聊天路由响应 */
export interface ChatRouteResponse {
  type: "chat" | "workflow"
}

/** 启动工作流请求 */
export interface WorkflowStartRequest {
  requirement: string
}

/** 审核恢复请求 */
export interface WorkflowResumeRequest {
  threadId: string
  reviewDecision: "APPROVED" | "SENT_BACK" | "TERMINATED"
  comment?: string
}

/** 工作流结果 */
export interface WorkflowResult {
  threadId: string
  status: string
  message: string
  interrupted: boolean
  interruptedNode: string | null
  state: Record<string, any> | null
}

/** API 通用响应包装 */
interface ApiResult<T> {
  code: number
  message: string
  data: T
}

// ========== API 函数 ==========

/**
 * 路由判断：分析用户消息意图，返回 chat 或 workflow
 * POST /api/v1/chat/route
 */
export async function routeChatMessage(message: string): Promise<ChatRouteResponse> {
  const res = await service.post<ApiResult<ChatRouteResponse>>(
    WorkflowApi.Route,
    { message }
  )
  return (res as any).data
}

/**
 * 启动研发工作流
 * POST /api/v1/workflow/start
 */
export async function startWorkflowApi(requirement: string): Promise<WorkflowResult> {
  const res = await service.post<ApiResult<WorkflowResult>>(
    WorkflowApi.Start,
    { requirement }
  )
  return (res as any).data
}

/**
 * 查询工作流状态
 * GET /api/v1/workflow/state/{threadId}
 */
export async function getWorkflowState(threadId: string): Promise<WorkflowResult> {
  const res = await service.get<ApiResult<WorkflowResult>>(
    `${WorkflowApi.State}/${encodeURIComponent(threadId)}`
  )
  return (res as any).data
}

/**
 * 审核恢复工作流
 * POST /api/v1/workflow/resume
 */
export async function resumeWorkflowApi(req: WorkflowResumeRequest): Promise<WorkflowResult> {
  const res = await service.post<ApiResult<WorkflowResult>>(
    WorkflowApi.Resume,
    {
      threadId: req.threadId,
      reviewDecision: req.reviewDecision,
      comment: req.comment,
    }
  )
  return (res as any).data
}
