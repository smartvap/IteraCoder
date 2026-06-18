package com.agenthub.ai.base.controller;

import com.agenthub.ai.base.annotation.Loggable;
import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.context.BaseContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;


/**
 * @Title: ChatController
 * 
 * @Package com.agenthub.ai.controller
 * @description: 对话接口
 */

@Tag(name="AiRagController",description = "chat对话接口")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/chat")
public class ChatController {

//    private final ChatClient chatClient;
    private final Map<String, ChatClient> chatClients;
    private final ChatMemory chatMemory;

    public ChatController(@Qualifier("gemma2ChatClient") ChatClient gemma2ChatClient,
                          @Qualifier("qwenChatClient") ChatClient qwenChatClient,
                          @Qualifier("dashscopeDeepseekChatClient") ChatClient dashscopeDeepseekChatClient,
                          @Qualifier("dashscopeQwenMaxChatClient") ChatClient dashscopeQwenMaxChatClient,
                          ChatMemory chatMemory) {
        this.chatClients = Map.of(
                "gemma2", gemma2ChatClient,
                "qwen3", qwenChatClient,
                "deepseek-v4-pro", dashscopeDeepseekChatClient,
                "qwen-max", dashscopeQwenMaxChatClient
                );
        this.chatMemory = chatMemory;

//        ChatOptions options = ChatOptions.builder()
//                .model("gemma2:2b")
//                .temperature(0.7)
//                .build();
//        this.chatClient = ChatClient.builder(chatModel)
//                .defaultOptions(options)
//                .defaultSystem("你是自动化研发智能体系统的客户客服代理...")
//                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
//                .build();
    }

//    public ChatController(ChatClient.Builder builder,ChatMemory chatMemory) {
//        ChatOptions options = ChatOptions.builder()
//                .model("gemma4:e4b")
//                .temperature(0.7)
//                .build();
//        this.chatClient = builder
//                .defaultOptions(options)
//                .defaultSystem("""
//                        你是自动化研发智能体系统的客户客服代理。请友好乐于助人，充满喜悦地回复。
//                        """)
//                .defaultAdvisors(
//                        MessageChatMemoryAdvisor.builder(chatMemory).build() // CHAT MEMORY
//
//                        )
//                .build();
//    }

    @Operation(summary = "stream",description = "流式对话接口")
    @GetMapping(value = "/stream",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Loggable("message")
    public Flux<String> streamRagChat(@RequestParam(value = "message", defaultValue = "你好" ) String message,
                                      @RequestParam(value = "prompt", defaultValue = "你是一名自动化研发智能体系统助手，致力于帮助人们解决问题.") String prompt){

        Long userId = BaseContext.getCurrentId();

        ChatClient selectedClient = chatClients.getOrDefault("gemma2".toLowerCase(), chatClients.get("gemma2"));

        return selectedClient.prompt()
                .system(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userId))
                .user(message)
                .stream()
                .content();

//        return chatClient.prompt()
//                .system(prompt)
//                .advisors(a -> a
//                        .param(ChatMemory.CONVERSATION_ID, userId))
//                .user(message)
//                .stream()
//                .content();
    }



    @Operation(summary = "stream2",description = "流式对话接口(无data前缀)")
    @PostMapping(value = "/stream2", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Loggable("message")
    public Flux<String> streamRagChat2(
                                        @RequestParam(value = "model", defaultValue = "qwen3") String model,
                                        @RequestParam(value = "message", defaultValue = "你好") String message,
                                        @RequestParam(value = "prompt", defaultValue = "你是一名自动化研发智能体系统助手，致力于帮助人们解决问题.请以中文回答问题") String prompt){

        log.info("收到流式请求 - message: {}, prompt: {}", message, prompt);

        Long userId = BaseContext.getCurrentId();
        ChatClient selectedClient = chatClients.getOrDefault(model.toLowerCase(), chatClients.get(model));

        return selectedClient.prompt()
                .system(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userId))
                .user(message)
                .stream()
                .content()
                .map(chunk -> chunk.replace("\n", "").replace("\r", ""));
    }


}
