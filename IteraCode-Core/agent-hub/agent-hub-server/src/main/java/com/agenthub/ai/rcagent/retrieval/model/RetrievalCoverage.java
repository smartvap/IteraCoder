package com.agenthub.ai.rcagent.retrieval.model;

import com.agenthub.ai.rcagent.retrieval.constant.RetrievalElementType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索覆盖率（coverage）：命中要素清单（matched）+ 未命中要素清单（missing）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RetrievalCoverage {

    /** 命中要素清单（类型+词） */
    private List<RetrievalElementHit> matched = new ArrayList<>();

    /** 未命中要素清单 */
    private List<RetrievalElementHit> missing = new ArrayList<>();

    /** 要素命中（coverage 数组项：{type, term}） */
    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class RetrievalElementHit {

        /** 要素类型（METRIC/DIMENSION/TIME_RANGE/...） */
        private String type;

        /** 要素词（如 投诉量/营业厅） */
        private String term;

        public static RetrievalElementHit of(String type, String term) {
            RetrievalElementHit hit = new RetrievalElementHit();
            hit.setType(type);
            hit.setTerm(term);
            return hit;
        }

        public static RetrievalElementHit of(RetrievalElementType type, String term) {
            return of(type.getValue(), term);
        }
    }
}
