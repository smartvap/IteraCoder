package com.agenthub.ai.datasource.adapter;

import com.agenthub.ai.llmwiki.client.LlmWikiClient;
import com.agenthub.ai.llmwiki.client.LlmWikiClientException;
import com.agenthub.ai.llmwiki.dto.SpaceCreateRequest;
import com.agenthub.ai.llmwiki.dto.SpaceVO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * llm-wiki 空间访问适配器（brownfield 交互层，FR-BE-006）。
 *
 * <p>仅注入并调用存量 {@link LlmWikiClient}（listSpaces/createSpace/health），不改动存量类；
 * llm-wiki 不可用时由调用方捕获 {@link LlmWikiClientException} 降级（BIND_PENDING），不阻断注册主链路。</p>
 */
@Component
public class DsSpaceAdapter {

    private final LlmWikiClient llmWikiClient;

    public DsSpaceAdapter(LlmWikiClient llmWikiClient) {
        this.llmWikiClient = llmWikiClient;
    }

    /**
     * llm-wiki 是否可用（轻量 /health 探测）。
     */
    public boolean available() {
        try {
            llmWikiClient.health();
            return true;
        } catch (LlmWikiClientException e) {
            return false;
        }
    }

    /**
     * 列出全部知识空间；llm-wiki 不可用时抛 {@link LlmWikiClientException}。
     */
    public List<SpaceVO> listSpaces() {
        return llmWikiClient.listSpaces();
    }

    /**
     * 创建知识空间；失败抛 {@link LlmWikiClientException}。
     *
     * @param types 空间类型（可空，远端默认）
     */
    public SpaceVO createSpace(String name, List<String> types) {
        return llmWikiClient.createSpace(new SpaceCreateRequest(name, types));
    }
}
