package com.novel.modules.agent.bookfinder.controller;

import com.fasterxml.jackson.databind.*;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.*;
import com.novel.common.security.UserContext;
import com.novel.modules.agent.bookfinder.service.*;
import com.novel.modules.agent.bookfinder.conversation.BookFinderStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Authenticated stateless Streamable HTTP, JSON responses, read-only tools. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/ai/book-finder/mcp")
public class BookFinderMcpController {
    private final ObjectMapper json;
    private final BookFinderTools tools;
    private final BookFinderStore store;
    private final BookFinderAgent agent;
    private final Semaphore concurrent=new Semaphore(4);
    @Value("${novel.book-finder.mcp.allowed-origins:http://localhost:3000,http://localhost:8080,http://127.0.0.1:3000,http://127.0.0.1:8080}")
    private String allowedOrigins;
    private void origin(String origin) {
        if(origin!=null && !Arrays.asList(Objects.toString(allowedOrigins,"").split(",")).contains(origin)) throw new BusinessException(403,"MCP Origin 不在允许范围内");
    }
    @GetMapping public ResponseEntity<Void> get(@RequestHeader(value="Origin",required=false) String origin) {
        origin(origin); return ResponseEntity.status(405).allow(HttpMethod.POST).build();
    }
    @PostMapping(consumes="application/json",produces="application/json")
    public ResponseEntity<JsonNode> http(@RequestBody String raw,@RequestHeader(value="Origin",required=false) String origin,
            @RequestHeader(value="MCP-Protocol-Version",required=false) String version) {
        origin(origin);
        if(raw.length()>16000) return ResponseEntity.status(413).build();
        try { return post(json.readTree(raw),origin,version); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { return ResponseEntity.badRequest().body(error(null,-32700,"Parse error")); }
    }
    public ResponseEntity<JsonNode> post(@RequestBody JsonNode input,@RequestHeader(value="Origin",required=false) String origin,
            @RequestHeader(value="MCP-Protocol-Version",required=false) String version) {
        origin(origin);
        if(version!=null && !version.equals("2025-03-26")) return ResponseEntity.badRequest().build();
        if(input==null) return ResponseEntity.badRequest().body(error(null,-32600,"Invalid Request"));
        if(input.toString().length()>16000) return ResponseEntity.status(413).build();
        if(input.isArray()) {
            if(input.isEmpty() || input.size()>10 || java.util.stream.StreamSupport.stream(input.spliterator(),false).anyMatch(n->n.path("method").asText().equals("initialize"))) return ResponseEntity.badRequest().build();
            var result=json.createArrayNode(); for(var item:input) { var response=dispatch(item); if(response!=null) result.add(response); }
            return result.isEmpty()?ResponseEntity.accepted().build():ResponseEntity.ok(result);
        }
        var result=dispatch(input);
        return result==null?ResponseEntity.accepted().build():ResponseEntity.ok(result);
    }
    private JsonNode error(JsonNode id,int code,String message) {
        var row=json.createObjectNode(); row.put("jsonrpc","2.0"); row.set("id",id==null?json.nullNode():id);
        row.set("error",json.valueToTree(Map.of("code",code,"message",message))); return row;
    }
    private JsonNode dispatch(JsonNode input) {
        var id=input.get("id");
        if(!input.isObject() || !input.path("jsonrpc").asText().equals("2.0") || !input.path("method").isTextual()
                || (id!=null && !id.isTextual() && !id.isIntegralNumber())) return error(null,-32600,"Invalid Request");
        if(id==null) return null;
        Object result;
        switch(input.path("method").asText()) {
            case "initialize" -> result=Map.of("protocolVersion","2025-03-26","capabilities",Map.of("tools",Map.of("listChanged",false)),"serverInfo",Map.of("name","novel-book-finder","version","2.0.0"));
            case "ping" -> result=Map.of();
            case "tools/list" -> result=Map.of("tools",definitions());
            case "tools/call" -> {
                var params=input.path("params"); String name=params.path("name").asText();
                if(!Set.of("searchBooks","getBookDetails","searchBookEvidence").contains(name)) return error(id,-32602,"Unknown tool");
                long started=System.currentTimeMillis(); boolean failed=true;
                try {
                    if(!concurrent.tryAcquire()) throw new BusinessException(429,"MCP 工具并发已满，请稍后重试");
                    try {
                        var args=params.path("arguments"); if(!args.isObject()) throw new BusinessException(400,"工具参数必须是对象");
                        boolean admin=UserContext.hasRole("ADMIN");
                        if(!admin) store.acquireQuota(UserContext.getUserId(),agent.dailyLimit());
                        Object data;
                        switch(name) {
                            case "searchBooks" -> {
                                reject(args,Set.of("keyword","category","tag","completed","minWords","maxWords","excludeIds","limit"));
                                var q=json.treeToValue(args,BookFinderQuery.class); data=tools.searchBooks(q);
                            }
                            case "getBookDetails" -> { reject(args,Set.of("ids")); data=tools.getBookDetails(ids(args)); }
                            default -> { reject(args,Set.of("ids","query")); data=tools.searchBookEvidence(new BookKnowledge.EvidenceQuery(ids(args),args.path("query").asText())); }
                        }
                        result=Map.of("content",List.of(Map.of("type","text","text",json.writeValueAsString(data))),"isError",false);
                        failed=false;
                    } finally { concurrent.release(); }
                } catch(Exception e) {
                    if(e instanceof InterruptedException) Thread.currentThread().interrupt();
                    result=Map.of("content",List.of(Map.of("type","text","text",e instanceof BusinessException?e.getMessage():"工具暂时不可用")),"isError",true);
                } finally {
                    if(UserContext.getUserId()!=null) try {
                        store.runLog(UserContext.getUserId(),UUID.randomUUID().toString(),Map.of("protocol","MCP","status",failed?"failed":"done",
                                "usage",new com.novel.modules.agent.bookfinder.BookFinderTypes.Usage(0,1,0,System.currentTimeMillis()-started),
                                "tools",List.of(Map.of("name",name,"durationMs",System.currentTimeMillis()-started))));
                    } catch(RuntimeException ignored) { /* Observation failure must not break the tool response. */ }
                }
            }
            default -> { return error(id,-32601,"Method not found"); }
        }
        var row=json.createObjectNode(); row.put("jsonrpc","2.0"); row.set("id",id); row.set("result",json.valueToTree(result)); return row;
    }
    private void reject(JsonNode args,Set<String> allowed) { args.fieldNames().forEachRemaining(k->{if(!allowed.contains(k)) throw new BusinessException(400,"未知工具参数");}); }
    private List<String> ids(JsonNode args) {
        if(!args.path("ids").isArray()) throw new BusinessException(400,"ids 必须是数组");
        var ids=new ArrayList<String>(); for(var id:args.path("ids")) { if(!id.isTextual()) throw new BusinessException(400,"ID 必须是字符串"); ids.add(id.asText()); } return ids;
    }
    private Object definitions() {
        try(var in=new org.springframework.core.io.ClassPathResource("agent/bookfinder/mcp-tools.json").getInputStream()) { return json.readTree(in); }
        catch(Exception e) { throw new IllegalStateException("MCP schema unavailable",e); }
    }
}
