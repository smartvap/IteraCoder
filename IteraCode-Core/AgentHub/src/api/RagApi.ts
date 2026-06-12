import service from "@/http";
import {RagApi} from "@/api/common.ts";

export const streamRagApi = async (message: string = '你好', sources: string[] = []): Promise<any> => {
    return await service.post(RagApi.StreamRag, { message, sources });
};

// 发送带sources的RAG消息接口（POST请求）
export const sendRagWithSourcesApi = async (message: string, sources: string[]): Promise<any> => {
    return await service.post(RagApi.StreamRag, { message, sources });
};
