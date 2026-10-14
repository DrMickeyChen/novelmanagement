package com.novel.modules.agent.bookfinder.knowledge;

import com.fasterxml.jackson.databind.*;
import com.novel.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Explicit opt-in, independent from the chat provider. No credentials are exposed by status(). */
@Service
public class RetrievalModels {
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @Value("${novel.book-finder.embedding.url:}") private String embeddingUrl;
    @Value("${novel.book-finder.embedding.model:}") private String embeddingModel;
    @Value("${novel.book-finder.embedding.key:}") private String embeddingKey;
    @Value("${novel.book-finder.rerank.url:}") private String rerankUrl;
    @Value("${novel.book-finder.rerank.model:}") private String rerankModel;
    @Value("${novel.book-finder.rerank.key:}") private String rerankKey;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private EmbeddingConfigService configuration;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private RerankConfigService rerankConfiguration;
    @Value("${novel.book-finder.rerank.use-database:true}") private boolean useRerankDatabase=true;
    private RerankConfigService.Runtime reranking() {
        var saved=rerankConfiguration==null || !useRerankDatabase?null:rerankConfiguration.runtime();
        return saved!=null?saved:new RerankConfigService.Runtime(rerankUrl,rerankModel,rerankKey,8);
    }
    @Value("${novel.book-finder.embedding.use-database:true}") private boolean useDatabase=true;
    private EmbeddingConfigService.Runtime embedding() {
        var saved=configuration==null || !useDatabase?null:configuration.runtime();
        return saved!=null?saved:new EmbeddingConfigService.Runtime(embeddingUrl,embeddingModel,embeddingKey,8);
    }
    public RetrievalModels(ObjectMapper json) { this.json=json; }
    public boolean embeddingEnabled() { try { var cfg=embedding(); return !cfg.url().isBlank()&&!cfg.model().isBlank()&&!cfg.key().isBlank(); } catch(BusinessException e) { return false; } }
    public boolean rerankEnabled() { try { var cfg=reranking(); return !cfg.url().isBlank()&&!cfg.model().isBlank()&&!cfg.key().isBlank(); } catch(BusinessException e) { return false; } }
    public String embeddingIdentity() {
        EmbeddingConfigService.Runtime cfg;
        try { cfg=embedding(); } catch(BusinessException e) { return ""; }
        try { return cfg.model()+"@"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(cfg.url().getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,16); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public Map<String,Object> status() {
        var status=new LinkedHashMap<String,Object>();
        status.put("embeddingConfigured",embeddingEnabled()); status.put("rerankConfigured",rerankEnabled());
        try { status.put("embeddingModel",embedding().model()); } catch(BusinessException e) { status.put("embeddingModel",""); status.put("embeddingNotice",e.getMessage()); }
        try { status.put("rerankModel",reranking().model()); } catch(BusinessException e) { status.put("rerankModel",""); status.put("rerankNotice",e.getMessage()); }
        status.put("vectorSearch","ES 原生 HNSW 近邻 + BM25 + RRF 融合"); return status;
    }
    public List<List<Double>> embed(List<String> texts) throws Exception {
        var cfg=embedding();
        if(cfg.url().isBlank() || cfg.model().isBlank() || cfg.key().isBlank() || texts.isEmpty() || texts.size()>16) throw new BusinessException(400,"Embedding 未配置或批次过大");
        var result=new ArrayList<List<Double>>();
        for(int start=0;start<texts.size();start+=cfg.batchSize())
            result.addAll(embedBatch(texts.subList(start,Math.min(start+cfg.batchSize(),texts.size())),cfg));
        if(result.stream().anyMatch(v->v.size()!=result.get(0).size())) throw new BusinessException(502,"Embedding 维度不一致");
        return result;
    }
    private List<List<Double>> embedBatch(List<String> texts,EmbeddingConfigService.Runtime cfg) throws Exception {
        var body=json.createObjectNode().put("model",cfg.model()); body.set("input",json.valueToTree(texts));
        body.put("encoding_format","float");
        var root=post(cfg.url(),cfg.key(),body);
        var data=root.path("data");
        if(!data.isArray() || data.size()!=texts.size()) throw new BusinessException(502,"Embedding 响应数量不正确");
        var ordered=new TreeMap<Integer,List<Double>>();
        for(var item:data) {
            int index=item.path("index").asInt(-1); var vector=item.path("embedding");
            if(index<0 || index>=texts.size() || ordered.containsKey(index) || !vector.isArray() || vector.isEmpty() || vector.size()>4096)
                throw new BusinessException(502,"Embedding 响应格式不正确");
            var values=new ArrayList<Double>(); double norm=0;
            for(var n:vector) { double v=n.asDouble(Double.NaN); if(!n.isNumber() || !Double.isFinite(v)) throw new BusinessException(502,"Embedding 数值不正确"); values.add(v); norm+=v*v; }
            if(norm==0) throw new BusinessException(502,"Embedding 返回零向量");
            ordered.put(index,values);
        }
        int dimension=ordered.firstEntry().getValue().size();
        if(ordered.values().stream().anyMatch(v->v.size()!=dimension)) throw new BusinessException(502,"Embedding 维度不一致");
        return new ArrayList<>(ordered.values());
    }
    public List<Integer> rerank(String query,List<String> texts) throws Exception {
        var cfg=reranking();
        if(cfg.url().isBlank() || cfg.model().isBlank() || cfg.key().isBlank() || texts.isEmpty() || texts.size()>20)
            throw new BusinessException(400,"Rerank 未配置或候选数量不合法");
        var body=json.createObjectNode().put("model",cfg.model()).put("query",query).put("top_n",texts.size());
        body.set("documents",json.valueToTree(texts));
        var data=post(cfg.url(),cfg.key(),body).path("results");
        if(!data.isArray() || data.size()!=texts.size()) throw new BusinessException(502,"Rerank 响应数量不正确");
        var scores=new HashMap<Integer,Double>();
        for(var item:data) {
            int i=item.path("index").asInt(-1); double score=item.path("relevance_score").asDouble(Double.NaN);
            if(i<0 || i>=texts.size() || !Double.isFinite(score) || scores.put(i,score)!=null) throw new BusinessException(502,"Rerank 响应格式不正确");
        }
        return scores.keySet().stream().sorted(Comparator.<Integer>comparingDouble(scores::get).reversed().thenComparingInt(i->i)).toList();
    }
    private JsonNode post(String url,String key,JsonNode body) throws Exception {
        URI uri=URI.create(url);
        if(!Set.of("https","http").contains(uri.getScheme()) || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw new BusinessException(400,"检索模型 URL 不合法，请使用无凭据的完整接口地址");
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json")
                .header("Authorization","Bearer "+key).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var future=http.sendAsync(request,HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> response;
        try { response=future.get(8,TimeUnit.SECONDS); }
        catch(InterruptedException|TimeoutException e) { future.cancel(true); throw e; }
        if(response.statusCode()!=200 || response.body().length>4_000_000) throw new BusinessException(502,"检索模型接口暂时不可用");
        return json.readTree(response.body());
    }
}
