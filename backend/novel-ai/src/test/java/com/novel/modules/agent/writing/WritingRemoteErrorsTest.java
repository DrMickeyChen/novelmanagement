package com.novel.modules.agent.writing;

import com.novel.modules.agent.writing.infrastructure.client.WritingRemoteErrors;

import com.novel.common.exception.BusinessException;
import feign.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WritingRemoteErrorsTest {
    Response response(int status,String body) {
        return Response.builder().status(status).reason("fixture").headers(Map.of())
                .request(Request.create(Request.HttpMethod.GET,"http://fixture/internal/writing",Map.of(),null,StandardCharsets.UTF_8,null))
                .body(body,StandardCharsets.UTF_8).build();
    }
    @Test void businessOutlineRejectionRemains422() {
        var failure=(BusinessException)new WritingRemoteErrors().decode("snapshot",response(422,"{\"code\":422,\"message\":\"请填写当前章节剧情笔记\"}"));
        assertEquals(422,failure.getHttpStatus());assertEquals("请填写当前章节剧情笔记",failure.getMessage());
    }
    @Test void internalDetailsAndMalformedBodiesAreNotExposed() {
        var failure=(BusinessException)new WritingRemoteErrors().decode("snapshot",response(500,"{\"message\":\"secret database details\"}"));
        assertEquals(500,failure.getHttpStatus());assertFalse(failure.getMessage().contains("secret"));
        assertEquals(403,((BusinessException)new WritingRemoteErrors().decode("snapshot",response(403,"<html>failure</html>"))).getHttpStatus());
    }
}
