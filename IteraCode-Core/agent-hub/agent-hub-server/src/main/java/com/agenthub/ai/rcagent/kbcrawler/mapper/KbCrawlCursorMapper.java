package com.agenthub.ai.rcagent.kbcrawler.mapper;

import com.agenthub.ai.rcagent.kbcrawler.entity.KbCrawlCursor;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * kb_crawl_cursor 采集游标 Mapper。
 */
@Mapper
public interface KbCrawlCursorMapper extends BaseMapper<KbCrawlCursor> {
}
