/**
 * SSE 流式对话 API 模块
 *
 * <p>封装了三种流式对话方式：</p>
 * <ul>
 *   <li>{@link postStreamChat} - POST 方式流式对话（旧版，使用 @microsoft/fetch-event-source）</li>
 *   <li>{@link getStreamChat} - POST + query params 方式流式对话（WebFlux 兼容）</li>
 *   <li>{@link postStreamChatWithSources} - 带知识源的 RAG 流式对话</li>
 * </ul>
 *
 * <p>重要：Spring WebFlux 的 {@code @RequestParam} 不解析 {@code application/x-www-form-urlencoded} 的 body，
 * 因此所有参数通过 URL query string 传递（{@code ?key=value}）。</p>
 *
 * @module StreamApi
 */
import { fetchEventSource } from "@microsoft/fetch-event-source";
import {BASE_URL} from "@/http/config.ts";
import service from "@/http";

/** 用于中断请求的错误类型（不重试） */
class FatalError extends Error {}
/** 用于重试的错误类型 */
class RetriableError extends Error {}

/** 回调函数类型，参数为事件数据或 null */
type ResultCallBack = (e: any | null) => void;

const BaseUrl = BASE_URL;

/**
 * POST 方式流式对话（旧版接口）
 *
 * <p>使用 {@code @microsoft/fetch-event-source} 库发送 SSE 请求。</p>
 *
 * @param author    发送者标识
 * @param onMessage 收到消息时的回调
 * @param onError   发生错误时的回调
 * @param onClose   连接关闭时的回调
 */
export const postStreamChat = (
    author: string,
    onMessage: ResultCallBack,
    onError: ResultCallBack,
    onClose: ResultCallBack
) => {
    const ctrl = new AbortController();
    fetchEventSource(BaseUrl + "/post-chat", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            author: author,
        }),
        signal: ctrl.signal,
        onmessage: onMessage,
        onerror: (err: any) => {
            onError(err);
        },
        onclose: () => {
            onClose(null);
        },
        onopen: async (response: any) => {
            if (response.ok) {
                return;
            } else if (
                response.status >= 400 &&
                response.status < 500 &&
                response.status !== 429
            ) {
                onError(new Error(`HTTP ${response.status}: ${response.statusText}`));
                return new Promise(() => {});
            } else {
                onError(new Error(`HTTP ${response.status}: ${response.statusText}`));
                return new Promise(() => {});
            }
        },
    });
};

/**
 * POST + query params 方式流式对话
 *
 * <p>使用原生 fetch + ReadableStream 实现，参数通过 URL query string 传递，
 * 兼容 Spring WebFlux 的 {@code @RequestParam}。</p>
 *
 * <p>后端 SSE 事件类型：</p>
 * <ul>
 *   <li>{@code event:reasoning} - 模型思考过程</li>
 *   <li>{@code event:content} - 模型正式回复</li>
 *   <li>{@code event:done} - 流结束</li>
 * </ul>
 *
 * @param message   用户消息
 * @param url       后端接口路径，默认 {@code /chat/stream2}
 * @param onMessage 收到消息时的回调
 * @param onError   发生错误时的回调
 * @param onClose   连接关闭时的回调
 * @param sources   知识源列表（可选，用于 RAG）
 * @param model     模型名称（可选）
 * @returns AbortController 用于中断请求
 */
export const getStreamChat = (
    message: string,
    url: string = "/chat/stream2",
    onMessage: ResultCallBack,
    onError: ResultCallBack,
    onClose: ResultCallBack,
    sources?: string[],
    model?: string
): AbortController => {
    const ctrl = new AbortController();

    // 将参数编码为 URL query string，避免 WebFlux @RequestParam 解析不到 form body 的问题
    const params = new URLSearchParams();
    params.set('message', decodeURIComponent(message));
    if (model) {
        params.set('model', model);
    }
    if (sources && sources.length > 0) {
        sources.forEach(source => {
            params.append('sources', source);
        });
    }

    fetchEventSource(service.defaults.baseURL + url + "?" + params.toString(), {
        method: "POST",
        headers: {
            "Authorization": `Bearer ${localStorage.getItem("token") || ""}`
        },
        signal: ctrl.signal,
        onmessage: onMessage,
        onerror: (err: any) => {
            onError(err);
        },
        onclose: () => {
            onClose(null);
        },
        onopen: async (response: any) => {
            if (response.ok) {
                return;
            } 
            else if (response.status === 401) {
                // 401 未授权，跳转登录页
                import('@/api/authUtils').then(module => {
                module.default();
                });
            }
            else{
                onError(new Error(`HTTP ${response.status}: ${response.statusText}`));
                return new Promise(() => {});
            }
        },
    });

    return ctrl;
};

/**
 * 带知识源的 RAG 流式对话
 *
 * <p>调用后端 {@code /ai/rag} 接口，将用户消息与指定知识源进行检索增强生成。</p>
 *
 * @param message   用户消息
 * @param sources   知识源名称列表（如文件名、数据库表名等）
 * @param url       后端接口路径，默认 {@code /ai/rag}
 * @param onMessage 收到消息时的回调
 * @param onError   发生错误时的回调
 * @param onClose   连接关闭时的回调
 */
export const postStreamChatWithSources = (
    message: string,
    sources: string[],
    url: string = "/ai/rag",
    onMessage: ResultCallBack,
    onError: ResultCallBack,
    onClose: ResultCallBack,
) => {
    const ctrl = new AbortController();
    
    // RAG 接口同样使用 query params 传递参数
    const params = new URLSearchParams();
    params.set('message', message);
    sources.forEach(source => {
        params.append('sources', source);
    });
    
    fetchEventSource(service.defaults.baseURL + url + "?" + params.toString(), {
        method: "POST",
        headers: {
            "Authorization": `Bearer ${localStorage.getItem("token") || ""}`
        },
        signal: ctrl.signal,
        onmessage: onMessage,
        onerror: (err: any) => {
            onError(err);
        },
        onclose: () => {
            onClose(null);
        },
        onopen: async (response: any) => {
            if (response.ok) {
                return;
            } 
            else if (response.status === 401) {
                import('@/api/authUtils').then(module => {
                module.default();
                });
            }
            else{
                onError(new Error(`HTTP ${response.status}: ${response.statusText}`));
                return new Promise(() => {});
            }
        },
    });
};
