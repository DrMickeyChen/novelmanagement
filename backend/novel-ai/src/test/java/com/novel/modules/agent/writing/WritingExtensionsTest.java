package com.novel.modules.agent.writing;

import com.fasterxml.jackson.databind.*;
import com.novel.common.exception.BusinessException;
import com.novel.common.security.*;
import com.novel.modules.agent.writing.skill.WritingSkillImport;
import com.novel.modules.agent.writing.infrastructure.client.WritingMcpTransport;
import com.novel.modules.agent.writing.application.*;
import com.novel.modules.ai.service.AiCrypto;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WritingExtensionsTest {
    private final ObjectMapper json=new ObjectMapper();
    private final String skill="---\nid: test-style\nname: 简洁\ndescription: 表达测试\nversion: 1\n---\n使用短句。";
    @AfterEach void cleanup(){UserContext.clear();}
    byte[] zip(Map<String,String> files)throws Exception{var bytes=new ByteArrayOutputStream();try(var out=new ZipOutputStream(bytes)){for(var entry:files.entrySet()){out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));out.closeEntry();}}return bytes.toByteArray();}
    @Test void importsTextAndReferencesWithoutExtractingOrExecuting()throws Exception{var imported=WritingSkillImport.read("styles.zip",zip(Map.of("style/SKILL.md",skill,"style/references/example.txt","例句")));assertEquals("使用短句。",imported.skill().instructions());assertEquals("例句",imported.references().get("style/references/example.txt"));}
    @Test void externalNameDescriptionFormatGetsStableIdAndVersion(){String external="---\nname: dialogue\ndescription: 对话规则\n---\n用对话推进情节。";var imported=WritingSkillImport.read("SKILL.md",external.getBytes(StandardCharsets.UTF_8));assertTrue(imported.skill().id().startsWith("imported-"));assertEquals("1",imported.skill().version());assertEquals(imported.skill().id(),WritingSkillImport.read("SKILL.md",external.getBytes(StandardCharsets.UTF_8)).skill().id());}
    @Test void rejectsTraversalScriptsBombAndInvalidUtf8()throws Exception{
        for(var files:List.of(Map.of("SKILL.md",skill,"../x.txt","escape"),Map.of("SKILL.md",skill,"run.js","code"),Map.of("SKILL.md",skill,"huge.txt","a".repeat(65537))))assertThrows(BusinessException.class,()->WritingSkillImport.read("x.zip",zip(files)));
        assertThrows(BusinessException.class,()->WritingSkillImport.read("SKILL.md",new byte[]{(byte)0xc3,0x28}));assertThrows(BusinessException.class,()->WritingSkillImport.read("SKILL.md","invalid".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void publicNetworkPolicyRejectsInternalAndCredentialUrls()throws Exception{
        for(String ip:List.of("127.0.0.1","10.0.0.1","172.16.0.1","192.168.1.1","169.254.169.254","100.64.0.1","::1","fc00::1","::ffff:127.0.0.1"))assertFalse(WritingMcpTransport.publicAddress(InetAddress.getByName(ip)),ip);
        assertTrue(WritingMcpTransport.publicAddress(InetAddress.getByName("8.8.8.8")));
        for(String url:List.of("http://example.com/mcp","https://user:password@example.com/mcp","https://example.com/mcp?key=secret","https://example.com:8080/mcp"))assertThrows(BusinessException.class,()->WritingMcpTransport.validate(url));
    }
    @Test void exactJsonRpcAndSseResponsesIgnoreServerRequestsAndRejectWrongIds()throws Exception{
        var mcp=new WritingMcp(mock(JdbcTemplate.class),json,mock(WritingAssetService.class),new AiCrypto("test-secret"),mock(WritingMcpTransport.class));
        assertEquals("ok",mcp.decode(new WritingMcpTransport.Response(200,Map.of("content-type","application/json"),"{\"jsonrpc\":\"2.0\",\"id\":\"wanted\",\"result\":\"ok\"}"),"wanted").asText());
        String sse="data: {\"jsonrpc\":\"2.0\",\"method\":\"dangerous/inbound\"}\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"wanted\",\"result\":{\"content\":[]}}\n\n";
        assertTrue(mcp.decode(new WritingMcpTransport.Response(200,Map.of("content-type","text/event-stream"),sse),"wanted").path("content").isArray());
        assertTrue(WritingMcpTransport.completeEvent(sse,"wanted"));assertFalse(WritingMcpTransport.completeEvent(sse.substring(0,sse.length()-2),"wanted"));
        assertThrows(BusinessException.class,()->mcp.decode(new WritingMcpTransport.Response(200,Map.of("content-type","application/json"),"{\"jsonrpc\":\"2.0\",\"id\":\"other\",\"result\":{}}"),"wanted"));
    }
    @Test void mcpClientNegotiatesSessionAndCallsOnlyTaskGrantedTool()throws Exception{
        var user=new LoginUser();user.setUserId(7L);user.setRoles(List.of("AUTHOR"));UserContext.set(user);
        var jdbc=mock(JdbcTemplate.class);var assets=mock(WritingAssetService.class);var crypto=new AiCrypto("test-secret");var row=new HashMap<String,Object>();row.put("id","conn");row.put("owner_user_id",7L);row.put("novel_id",9L);row.put("endpoint","https://example.com/mcp");row.put("version",1);row.put("enabled",true);row.put("catalog_hash","h");row.put("encrypted_token",crypto.encrypt("test-token"));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row));var calls=new ArrayList<String>();
        row.put("grants","[\"lookup\"]");
        var transport=new WritingMcpTransport(){@Override public Response post(String endpoint,String token,String session,String version,String body)throws IOException{
            assertEquals("test-token",token);try{var req=json.readTree(body);String method=req.path("method").asText();calls.add(method);if(method.equals("notifications/initialized")){assertEquals("session",session);return new Response(202,Map.of(),"");}
                var response=json.createObjectNode().put("jsonrpc","2.0");response.set("id",req.get("id"));if(method.equals("initialize"))response.putObject("result").put("protocolVersion","2025-11-25");else {assertEquals("session",session);assertEquals("lookup",req.path("params").path("name").asText());response.putObject("result").putArray("content").addObject().put("type","text").put("text","reference");}return new Response(200,Map.of("content-type","application/json","mcp-session-id","session"),response.toString());
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IOException(e);}}};
        var mcp=new WritingMcp(jdbc,json,assets,crypto,transport);var input=json.createObjectNode();input.putArray("mcpSnapshots").addObject().put("id","conn").put("version",1).put("catalogHash","h").putArray("tools").add("lookup");
        assertThrows(BusinessException.class,()->mcp.reference(9,input,"conn","write","{}",()->{}));assertTrue(calls.isEmpty());
        assertTrue(mcp.reference(9,input,"conn","lookup","{\"query\":\"航海\"}",()->{}).startsWith("EXTERNAL_UNVERIFIED"));assertEquals(List.of("initialize","notifications/initialized","tools/call"),calls);
    }
    @Test void individualToolSelectionRejectsUnapprovedToolsAndRevocationStopsBeforeDispatch()throws Exception{
        var user=new LoginUser();user.setUserId(7L);user.setRoles(List.of("AUTHOR"));UserContext.set(user);
        var jdbc=mock(JdbcTemplate.class);var row=new HashMap<String,Object>();
        row.put("id","conn");row.put("endpoint","https://example.com/mcp");row.put("version",1);row.put("enabled",true);row.put("catalog_hash","h");row.put("encrypted_token",null);
        row.put("grants","[\"search\",\"extract\"]");row.put("catalog","[{\"name\":\"search\",\"inputSchema\":{\"type\":\"object\"}},{\"name\":\"extract\",\"inputSchema\":{\"type\":\"object\"}}]");
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row));
        var live=new java.util.concurrent.atomic.AtomicReference<JsonNode>();var calls=new ArrayList<String>();var revokeAfterCall=new boolean[]{false};
        var transport=new WritingMcpTransport(){@Override public Response post(String endpoint,String token,String session,String version,String body)throws IOException{
            try{var req=json.readTree(body);String method=req.path("method").asText();calls.add(method);
                if(method.equals("notifications/initialized")){if(!revokeAfterCall[0])live.set(json.createObjectNode());return new Response(202,Map.of(),"");}
                var response=json.createObjectNode().put("jsonrpc","2.0");response.set("id",req.get("id"));
                if(method.equals("initialize"))response.putObject("result").put("protocolVersion","2025-11-25");
                else{live.set(json.createObjectNode());response.putObject("result").putArray("content").addObject().put("text","不应向模型返回的晚到参考");}
                return new Response(200,Map.of("content-type","application/json"),response.toString());
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IOException(e);}
        }};
        var mcp=new WritingMcp(jdbc,json,mock(WritingAssetService.class),new AiCrypto("test-secret"),transport);
        var input=json.createObjectNode().put("allowExternalQuery",true);input.putArray("mcpToolSelections").addObject().put("connectionId","conn").putArray("tools").add("search");mcp.freeze(input,9);
        assertEquals(1,input.path("mcpSnapshots").get(0).path("catalog").size());assertEquals("search",input.path("mcpSnapshots").get(0).path("catalog").get(0).path("name").asText());
        var invalid=input.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)invalid.path("mcpToolSelections").get(0).path("tools")).add("write");assertThrows(BusinessException.class,()->mcp.freeze(invalid,9));
        assertThrows(BusinessException.class,()->mcp.reference(9,input,"conn","extract","{}",()->{}));assertTrue(calls.isEmpty());
        live.set(input);assertThrows(BusinessException.class,()->mcp.reference(9,live::get,"conn","search","{}",()->{}));
        assertEquals(List.of("initialize","notifications/initialized"),calls);assertFalse(calls.contains("tools/call"));
        calls.clear();revokeAfterCall[0]=true;live.set(input);
        assertThrows(BusinessException.class,()->mcp.reference(9,live::get,"conn","search","{}",()->{}));
        assertEquals(List.of("initialize","notifications/initialized","tools/call"),calls);
    }
    @Test void disabledToolsAreReportedToModelWithoutPretendingToHaveRetrievedReferences(){
        var mcp=mock(WritingMcp.class);var budget=mock(com.novel.modules.agent.writing.infrastructure.model.WritingModel.Budget.class);
        when(budget.externalPermissions(any())).thenReturn(json.createObjectNode());
        when(mcp.reference(eq(1L),any(java.util.function.Supplier.class),eq("conn"),eq("search"),eq("{}"),any(Runnable.class)))
                .thenThrow(new BusinessException(403,"已关闭",403));
        var snapshot=new com.novel.common.internal.WritingContent.Snapshot("1","1",1,"标题",0,"正文","1","大纲","h",List.of());
        var tools=new com.novel.modules.agent.writing.tool.WritingMcpTools(mcp,snapshot,json.createObjectNode(),budget);
        assertTrue(tools.callExternalReference("conn","search","{}").startsWith("TOOL_DISABLED"));
    }
    @Test void styleRulesNeverCopySamplePlotAndUnicodePartsPreserveAllText()throws Exception{
        var prefs=json.readTree("{\"sentenceLength\":\"SHORT\",\"narrativeDistance\":\"CLOSE\",\"dialogueRatio\":\"HIGH\",\"pacing\":\"FAST\",\"emotion\":\"SHOW\",\"imagery\":\"MINIMAL\"}");assertFalse(WritingAssetService.expressionRules(prefs).contains("林舟"));assertThrows(BusinessException.class,()->WritingAssetService.validateStyle(json.createObjectNode().put("plot","泄露")));
        String text="甲😀乙".repeat(10000);var parts=WritingDerivations.parts(text,18000);assertEquals(text,String.join("",parts));for(var part:parts)assertFalse(Character.isHighSurrogate(part.charAt(part.length()-1)));
    }
}
