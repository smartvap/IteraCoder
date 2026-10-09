package com.agenthub.ai.rcagent.retrieval.vo;

import com.agenthub.ai.rcagent.retrieval.model.RetrievalResult;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.beans.BeanUtils;

/**
 * 融合检索主入口 REST 响应（接口1 data：RetrievalSearchVO）。
 *
 * <p>与进程内 {@link RetrievalResult} 结构一致（request_id/ds_id/context/items/coverage/cold_start/degradations/meta），
 * 由 Controller 将 service 返回的 RetrievalResult 拷贝为 VO 输出。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RetrievalSearchVO extends RetrievalResult {

    /** 由进程内结果拷贝为 REST VO */
    public static RetrievalSearchVO from(RetrievalResult result) {
        RetrievalSearchVO vo = new RetrievalSearchVO();
        if (result != null) {
            BeanUtils.copyProperties(result, vo);
        }
        return vo;
    }
}
