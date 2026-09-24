package com.agenthub.ai.dbaccess.registry;

import com.agenthub.ai.dbaccess.constant.DbAccessConstants;
import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ${ENV} 占位符解析器（凭据脱敏：密码仅以环境变量引用形式解析，结果不落日志/异常/响应）。
 */
@Component
public class EnvPlaceholderResolver {

    private static final Pattern ANY_PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

    private final Environment environment;

    public EnvPlaceholderResolver(Environment environment) {
        this.environment = environment;
    }

    /**
     * 解析密码引用：整串必须形如 ${ENV_VAR}。
     *
     * @param passwordRef 密码引用
     * @return 解析后的明文（仅进程内使用）
     * @throws DbAccessException 引用格式非法（PARAM_INVALID）或环境变量缺失（DS_NOT_READY，message 不含变量名）
     */
    public String resolvePasswordRef(String passwordRef) {
        if (passwordRef == null || passwordRef.isBlank()) {
            throw new DbAccessException(DbAccessErrorCode.DS_NOT_READY, "数据源未就绪：只读密码未配置");
        }
        Matcher matcher = DbAccessConstants.PASSWORD_REF_PATTERN.matcher(passwordRef.trim());
        if (!matcher.matches()) {
            throw DbAccessException.param("readonlyPasswordRef 必须为 ${ENV} 形式引用，禁止明文密码");
        }
        String varName = matcher.group(1);
        return lookupOrThrow(varName);
    }

    /**
     * 解析文本中全部 ${ENV} 占位（如 jdbc_url/账号中允许环境变量）。
     */
    public String resolveAll(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        Matcher matcher = ANY_PLACEHOLDER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String value = lookupOrThrow(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 环境变量安全查找（不泄露变量名/值）。
     */
    private String lookupOrThrow(String varName) {
        Optional<String> value = lookup(varName);
        if (value.isEmpty()) {
            throw new DbAccessException(DbAccessErrorCode.DS_NOT_READY,
                    "数据源未就绪：依赖的环境变量未注入");
        }
        return value.get();
    }

    private Optional<String> lookup(String varName) {
        if (varName == null || varName.isBlank()) {
            return Optional.empty();
        }
        String fromEnv = environment.getProperty(varName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(fromEnv);
        }
        String fromSystem = System.getenv(varName);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return Optional.of(fromSystem);
        }
        return Optional.empty();
    }
}
