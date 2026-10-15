package com.novel.modules.agent.writing.infrastructure.client;

import com.novel.modules.agent.writing.domain.WritingTaskRules;
import org.springframework.stereotype.Component;
import javax.net.ssl.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Resolves once, validates every address, then connects to that exact IP with normal hostname TLS validation. */
@Component
public class WritingMcpTransport {
    public record Response(int status,Map<String,String> headers,String body) {}
    public static URI validate(String url){
        try{var uri=URI.create(url);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getQuery()!=null||uri.getPort()!=-1&&uri.getPort()!=443||url.length()>1000)throw new IllegalArgumentException();return uri;}
        catch(Exception e){throw WritingTaskRules.error(422,"MCP 端点必须是公共 HTTPS 地址，不含凭据、查询参数或片段");}
    }
    public static boolean publicAddress(InetAddress address){
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] a=address.getAddress();if(a.length==16){if((a[0]&0xe0)!=0x20)return false;if((a[0]&255)==0x20&&(a[1]&255)==2)return false;if((a[0]&255)==0x20&&(a[1]&255)==1&&((a[2]&255)==0&&(a[3]&255)==0||(a[2]&255)==0x0d&&(a[3]&255)==0xb8))return false;return true;}
        int x=a[0]&255,y=a[1]&255,z=a[2]&255;return !(x==0||x==10||x==127||x>=224||x==169&&y==254||x==172&&y>=16&&y<=31||x==192&&(y==168||y==0&&z==0||y==88&&z==99)||x==100&&y>=64&&y<=127||x==198&&(y==18||y==19)||x==192&&y==0&&z==2||x==198&&y==51&&z==100||x==203&&y==0&&z==113);
    }
    public Response post(String url,String token,String session,String protocol,String body)throws IOException{
        var uri=validate(url);InetAddress[] addresses=InetAddress.getAllByName(uri.getHost());if(addresses.length==0||Arrays.stream(addresses).anyMatch(a->!publicAddress(a)))throw WritingTaskRules.error(422,"MCP 解析到不允许的网络地址");
        return pinned(uri,addresses[0],token,session,protocol,body);
    }
    protected Response pinned(URI uri,InetAddress address,String token,String session,String protocol,String body)throws IOException{
        for(String header:List.of(Objects.toString(token,""),Objects.toString(session,""),Objects.toString(protocol,"")))if(header.length()>4096||header.chars().anyMatch(c->c<32||c>126))throw WritingTaskRules.error(422,"MCP 请求头无效");
        try(Socket raw=new Socket()){
            raw.connect(new InetSocketAddress(address,443),5000);raw.setSoTimeout(10000);
            try(SSLSocket socket=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,uri.getHost(),443,true)){
                var parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));socket.setSSLParameters(parameters);socket.setSoTimeout(10000);socket.startHandshake();
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);if(bytes.length>12000)throw WritingTaskRules.error(422,"MCP 请求超过上限");
                String path=uri.getRawPath();if(path==null||path.isBlank())path="/";
                String headers="POST "+path+" HTTP/1.1\r\nHost: "+uri.getHost()+"\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nAccept-Encoding: identity\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n";
                if(token!=null&&!token.isBlank())headers+="Authorization: Bearer "+token+"\r\n";if(session!=null&&!session.isBlank())headers+="Mcp-Session-Id: "+session+"\r\n";if(protocol!=null&&!protocol.isBlank())headers+="MCP-Protocol-Version: "+protocol+"\r\n";
                var output=socket.getOutputStream();output.write((headers+"\r\n").getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.flush();var input=new BufferedInputStream(socket.getInputStream());
                String first=line(input);var pieces=first.split(" ");if(pieces.length<2)throw new IOException("Invalid HTTP response");int status=Integer.parseInt(pieces[1]);var responseHeaders=new HashMap<String,String>();int count=0;
                for(String value;!(value=line(input)).isEmpty();){if(++count>100)throw new IOException("Too many HTTP headers");int colon=value.indexOf(':');if(colon<=0)throw new IOException("Invalid HTTP header");responseHeaders.put(value.substring(0,colon).toLowerCase(Locale.ROOT),value.substring(colon+1).trim());}
                if(status>=300&&status<400)throw WritingTaskRules.error(422,"MCP 重定向被拒绝，请填写最终端点");
                var buffer=new ByteArrayOutputStream();boolean sse=responseHeaders.getOrDefault("content-type","").contains("text/event-stream");String requestId="";try{requestId=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("id").asText();}catch(Exception ignored){}
                if("chunked".equalsIgnoreCase(responseHeaders.get("transfer-encoding"))){while(true){String size=line(input).split(";",2)[0];int n=Integer.parseInt(size.trim(),16);if(n==0)break;read(input,buffer,n);if(!line(input).isEmpty())throw new IOException("Invalid chunk delimiter");if(sse&&completeEvent(buffer.toString(StandardCharsets.UTF_8),requestId))break;}}
                else if(responseHeaders.containsKey("content-length"))read(input,buffer,Integer.parseInt(responseHeaders.get("content-length")));
                else {byte[] data=new byte[1024];for(int n;(n=input.read(data))!=-1;){if(buffer.size()+n>131072)throw new IOException("MCP response too large");buffer.write(data,0,n);if(sse&&completeEvent(buffer.toString(StandardCharsets.UTF_8),requestId))break;}}
                return new Response(status,responseHeaders,buffer.toString(StandardCharsets.UTF_8));
            }
        }
    }
    private static void read(InputStream input,ByteArrayOutputStream buffer,int n)throws IOException{if(n<0||n+buffer.size()>131072)throw new IOException("MCP response too large");byte[] data=input.readNBytes(n);if(data.length!=n)throw new EOFException();buffer.write(data);}
    public static boolean completeEvent(String body,String id){if(id==null||id.isBlank())return false;var mapper=new com.fasterxml.jackson.databind.ObjectMapper();String normalized=body.replace("\r\n","\n");int end=normalized.lastIndexOf("\n\n");if(end<0)return false;for(String event:normalized.substring(0,end).split("\n\n")){var data=new StringBuilder();for(String line:event.split("\n"))if(line.startsWith("data:")){if(!data.isEmpty())data.append('\n');data.append(line.substring(5).stripLeading());}try{var value=mapper.readTree(data.toString());if(value!=null&&value.path("jsonrpc").asText().equals("2.0")&&value.path("id").asText().equals(id)&&(value.has("result")||value.has("error")))return true;}catch(Exception ignored){}}return false;}
    private static String line(InputStream input)throws IOException{var buffer=new ByteArrayOutputStream();for(int c;(c=input.read())!=-1;){if(c=='\n')return buffer.toString(StandardCharsets.US_ASCII).replaceFirst("\r$","");if(buffer.size()>=8192)throw new IOException("HTTP line too large");buffer.write(c);}throw new EOFException();}
}
