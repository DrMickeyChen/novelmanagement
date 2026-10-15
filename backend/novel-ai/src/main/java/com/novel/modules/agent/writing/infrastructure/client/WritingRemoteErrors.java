package com.novel.modules.agent.writing.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.exception.BusinessException;
import feign.Response;
import feign.codec.ErrorDecoder;

/** Preserve intentional HTTP rejections without exposing raw Feign URLs or response bodies. */
public final class WritingRemoteErrors implements ErrorDecoder {
    @Override public Exception decode(String method,Response response) {
        int status=response.status();
        if(status<400 || status>599) status=502;
        String message=status>=500?"写作资料服务暂不可用，请稍后重试":"作品或章节不可访问";
        if(response.body()!=null && status<500) {
            try(var input=response.body().asInputStream()) {
                var body=new ObjectMapper().readTree(input.readNBytes(8192));
                if(body.path("code").isInt() && body.path("message").isTextual()) {
                    String text=body.path("message").asText();
                    if(!text.isBlank() && text.length()<=300)message=text;
                }
            } catch(Exception ignored) {}
        }
        return new BusinessException(status,message,status);
    }
}
