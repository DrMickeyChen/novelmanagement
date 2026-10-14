package com.novel.modules.agent.bookfinder.knowledge;

import com.novel.common.exception.BusinessException;
import com.novel.modules.ai.service.AiCrypto;
import com.novel.modules.ai.mapper.EmbeddingConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;

@Service @RequiredArgsConstructor
public class EmbeddingConfigService {
    private final EmbeddingConfigMapper mapper;
    private final AiCrypto crypto;
    public record Save(String url,String model,String apiKey,boolean clearApiKey,Integer batchSize) {
        @Override public String toString() { return "EmbeddingConfigSave[credentials=redacted]"; }
    }
    public record View(String url,String model,int batchSize,boolean apiKeyConfigured,String apiKeyMasked,
                       String apiKeyNotice,boolean configured) {}
    public record Runtime(String url,String model,String key,int batchSize) {
        @Override public String toString() { return "EmbeddingRuntime[credentials=redacted]"; }
    }
    private EmbeddingConfigMapper.Row row(boolean requiredSchema) {
        try { return mapper.current(); }
        catch(BadSqlGrammarException e) {
            if(!"42S02".equals(e.getSQLException().getSQLState())) throw e;
            if(requiredSchema) throw new BusinessException(503,"请先执行 docs/sql/embedding-config.sql 初始化向量配置表");
            return null; // Old installations retain environment-based configuration until migrated.
        }
    }
    public Runtime runtime() {
        var row=row(false);
        if(row==null) return null;
        String key="";
        if(row.apiKey!=null && !row.apiKey.isBlank()) {
            try { key=crypto.decrypt(row.apiKey); }
            catch(RuntimeException e) { throw new BusinessException(503,"向量 API Key 无法解密，请在管理端重新录入并检查 AI_KEY_SECRET"); }
        }
        return new Runtime(row.url,row.model,key,row.batchSize);
    }
    public View get() {
        var row=row(true);
        if(row==null) return new View("https://api.siliconflow.cn/v1/embeddings","BAAI/bge-m3",8,false,null,null,false);
        boolean saved=row.apiKey!=null && !row.apiKey.isBlank();
        String mask=null,notice=null;
        if(saved) {
            try {
                String key=crypto.decrypt(row.apiKey);
                mask="****"+(key.length()>=4?key.substring(key.length()-4):"");
            } catch(RuntimeException e) { notice="已保存的向量 API Key 无法解密，请检查 AI_KEY_SECRET 或重新录入密钥。"; }
        }
        return new View(row.url,row.model,row.batchSize,saved,mask,notice,saved && notice==null);
    }
    @Transactional
    public View save(Save input) {
        if(input==null) throw new BusinessException(400,"配置不能为空");
        String url=input.url()==null?"":input.url().trim(), model=input.model()==null?"":input.model().trim();
        validateUrl(url);
        if(model.isBlank() || model.length()>120 || model.chars().anyMatch(Character::isISOControl)) throw new BusinessException(400,"请输入有效的向量模型名称");
        int batch=input.batchSize()==null?8:input.batchSize();
        if(batch<1 || batch>16) throw new BusinessException(400,"向量批量大小必须为 1～16");
        String key=input.apiKey()==null?"":input.apiKey().trim();
        if(input.clearApiKey() && !key.isBlank()) throw new BusinessException(400,"新密钥和清空密钥不能同时选择");
        if(key.length()>2000 || key.chars().anyMatch(Character::isISOControl)) throw new BusinessException(400,"API Key 格式不正确");
        var row=row(true);
        if(row==null) row=new EmbeddingConfigMapper.Row();
        row.url=url; row.model=model; row.batchSize=batch;
        if(input.clearApiKey()) row.apiKey=null;
        else if(!key.isBlank()) {
            if(!crypto.isConfigured()) throw new BusinessException(503,"服务端未配置 AI_KEY_SECRET，无法加密保存向量密钥");
            row.apiKey=crypto.encrypt(key);
        }
        mapper.save(row);
        return get();
    }
    private static void validateUrl(String url) {
        try {
            var uri=URI.create(url);
            if(url.length()>500 || !java.util.Set.of("https","http").contains(uri.getScheme()) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
                throw new IllegalArgumentException();
        } catch(Exception e) { throw new BusinessException(400,"请输入无凭据、无查询参数的完整向量接口地址"); }
    }
}
