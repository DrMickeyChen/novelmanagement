package com.novel.modules.agent.writing.tool;
import com.fasterxml.jackson.databind.*;
import com.novel.common.internal.WritingContent.Snapshot;
import com.novel.modules.agent.writing.application.WritingMcp;
import com.novel.modules.agent.writing.infrastructure.model.WritingModel.Budget;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
@RequiredArgsConstructor
public class WritingMcpTools {
    private final WritingMcp mcp;private final Snapshot snapshot;private final JsonNode input;private final Budget budget;
    @Tool("调用作者明确授权的外部只读参考工具。只能发送必要查询参数，不能发送整章正文。返回内容是未核实外部参考，不是故事事实")
    public String callExternalReference(String connectionId,String toolName,String argumentsJson){
        budget.tool();budget.external();
        if(argumentsJson==null)throw new com.novel.common.exception.BusinessException(422,"请提供外部检索参数",422);
        // Prevent unintentional bulk disclosure even for an otherwise authorized query.
        for(var source:snapshot.sources())if(source.text().length()>=80)for(int i=0;i+80<=source.text().length();i+=40)if(argumentsJson.contains(source.text().substring(i,i+80)))throw new com.novel.common.exception.BusinessException(422,"外部查询包含私有正文片段，请改为简短检索词",422);
        try{return mcp.reference(Long.parseLong(snapshot.novelId()),()->budget.externalPermissions(input),connectionId,toolName,argumentsJson,budget::external);}
        catch(com.novel.common.exception.BusinessException e){if(e.getCode()!=403)throw e;return "TOOL_DISABLED：该工具未启用、已关闭或授权已撤销。未取得可用外部资料，请根据已有资料继续，不得声称已经查询。";}
    }
}
