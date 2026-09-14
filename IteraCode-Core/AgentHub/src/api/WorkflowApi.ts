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
  /** 前端选择的模型名（2026-08-13 新增，支持动态选择模型） */
  modelName?: string
}

/** 审核恢复请求 */
export interface WorkflowResumeRequest {
  threadId: string
  reviewDecision: "APPROVED" | "SENT_BACK" | "TERMINATED"
  comment?: string
  /** 前端选择的模型名（可选，恢复执行后代码生成等节点使用） */
  modelName?: string
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
 *
 * @param requirement 研发需求原文
 * @param modelName   前端选择的模型名（2026-08-13 新增，传给后端动态选择模型）
 */
export async function startWorkflowApi(requirement: string, modelName?: string): Promise<WorkflowResult> {
  const res = await service.post<ApiResult<WorkflowResult>>(
    WorkflowApi.Start,
    { requirement, modelName }
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
      modelName: req.modelName,
    }
  )
  return (res as any).data
}

/**
 * 恢复执行工作流（RUNNING / FAILED 状态，从中断点或 checkpoint 继续）
 * POST /api/v1/workflow/recover/{threadId}
 */
export async function recoverWorkflowApi(threadId: string): Promise<WorkflowResult> {
  const res = await service.post<ApiResult<WorkflowResult>>(
    `${WorkflowApi.Recover}/${encodeURIComponent(threadId)}`
  )
  return (res as any).data
}
