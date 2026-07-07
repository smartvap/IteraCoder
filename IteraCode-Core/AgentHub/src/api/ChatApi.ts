/**
 * 对话 API 模块
 *
 * <p>封装了与后端对话相关的接口，包括传统 fetch 方式和 SSE 流式方式。</p>
 *
 * <p>所有接口参数均通过 URL query string 传递，以兼容 Spring WebFlux。</p>
 *
 * @module ChatApi
 */
import service from "@/http";
import { getStreamChat } from "./StreamApi";

/** 后端 API 路径常量 */
export const ChatApi = {
  /** 普通对话接口 */
  Chat: "/chat/stream",
  /** RAG 检索增强对话接口 */
  RagChat: "/ai/rag",
};

/**
 * 聊天消息接口
 *
 * @property role     消息角色，user 或 assistant
 * @property content  消息内容
 * @property isTyping 是否正在输入（可选，用于显示输入状态）
 */
export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  isTyping?: boolean;
}

/**
 * 发送对话消息（传统 fetch 方式，非流式）
 *
 * <p>适用于简单的一问一答场景，等待完整响应后返回。</p>
 *
 * @param message 用户消息
 * @returns Response 对象
 */
export const sendChatMessageApi = async (message: string): Promise<Response> => {
  return fetch(`${service.defaults.baseURL}${ChatApi.Chat}?message=${encodeURIComponent(message)}`, {
    headers: {
      'Authorization': `Bearer ${localStorage.getItem('token')}`
    }
  }).then(response => {
    if (response.status === 401) {
      // 401 未授权，跳转登录页
      import('@/api/authUtils').then(module => {
        module.default();
      });
    }
    return response;
  });
};

/**
 * 发送 RAG 检索增强消息
 *
 * <p>将用户消息与指定知识源进行检索，返回增强后的回答。</p>
 *
 * @param message 用户消息
 * @param sources 知识源列表（如文件名、数据库表名等）
 * @returns Response 对象
 */
export const sendRagChatMessageApi = async (message: string, sources: string[] = []): Promise<Response> => {
  const params = new URLSearchParams();
  params.set('message', message);
  
  if (sources && sources.length > 0) {
    sources.forEach(source => {
      params.append('sources', source);
    });
  }
  
  return fetch(`${service.defaults.baseURL}${ChatApi.RagChat}?${params.toString()}`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
    }
  }).then(response => {
    if (response.status === 401) {
      // 401 未授权，跳转登录页
      import('@/api/authUtils').then(module => {
        module.default();
      });
    }
    return response;
  });
};

/**
 * 发送 SSE 流式对话消息
 *
 * <p>调用 {@link getStreamChat} 发起 SSE 流式请求，支持推理过程展示。</p>
 *
 * @param message   用户消息
 * @param onMessage 收到 SSE 事件时的回调
 * @param onError   发生错误时的回调
 * @param onClose   连接关闭时的回调
 * @param model     模型名称（可选）
 * @returns AbortController 用于中断请求
 */
export const sendChatMessageWithSSE = (
  message: string,
  onMessage: (event: any) => void,
  onError: (error: any) => void,
  onClose: () => void,
  model?: string
): AbortController => {
  return getStreamChat(
    decodeURIComponent(message),
    '/chat/stream2',
    onMessage,
    onError,
    onClose,
    undefined,
    model
  );
};

/**
 * 获取今日 token 用量统计
 *
 * @returns 今日用量数据，包含请求次数、token 数、总耗时
 */
export function getTodayTokenUsage(): Promise<{
  totalRequests: number
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
  totalDurationMs: number
}> {
  return service.get("/stats/token/usage")
}

/**
 * 分页查询 token 使用明细
 *
 * @param params 分页参数
 * @returns 分页明细数据
 */
export function getTokenUsageDetail(params: { page: number; pageSize: number }): Promise<any> {
  return service.get("/stats/token/detail", { params })
}
