package com.agenthub.ai.base.common;

public class ApplicationConstant {
    public final static String API_VERSION = "/api/v1";
    public final static String APPLICATION_NAME = "agent-hub";

    public final static String DEFAULT_BASE_URL = "https://api.openai.com";
    public final static String DEFAULT_DESCRIBE = "自动化研发智能体系统";
    public final static String SYSTEM_PROMPT = """
            请依据文档部分的内容给出准确答案，作答时要表现出这些内容是你原本就知晓的。
            若无法确定答案，直接回复 “我不知道” 即可。
            另外请注意：你的回答必须使用中文。!
        文档:
            {documents}    
        """;
}
