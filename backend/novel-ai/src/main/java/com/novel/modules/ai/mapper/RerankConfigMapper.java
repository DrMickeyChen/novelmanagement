package com.novel.modules.ai.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface RerankConfigMapper {
    // Deliberately no Lombok toString: this row contains encrypted credentials.
    class Row {
        public String url;
        public String model;
        public String apiKey;
        public int batchSize;
    }
    @Select("SELECT url,model,api_key AS apiKey,batch_size AS batchSize FROM ai_rerank_config WHERE id=1")
    Row current();
    @Insert("INSERT INTO ai_rerank_config(id,url,model,api_key,batch_size) VALUES(1,#{url},#{model},#{apiKey},#{batchSize}) " +
            "ON DUPLICATE KEY UPDATE url=VALUES(url),model=VALUES(model),api_key=VALUES(api_key),batch_size=VALUES(batch_size)")
    void save(Row row);
}
