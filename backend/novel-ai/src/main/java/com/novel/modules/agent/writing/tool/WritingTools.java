package com.novel.modules.agent.writing.tool;

import com.novel.modules.agent.writing.context.WritingRetrieval;
import com.novel.modules.agent.writing.infrastructure.model.WritingModel;
import com.novel.modules.agent.writing.infrastructure.persistence.WritingStore;

import com.fasterxml.jackson.databind.*;
import com.novel.common.internal.WritingContent.*;
import dev.langchain4j.agent.tool.Tool;
import java.util.*;
import lombok.RequiredArgsConstructor;
import com.novel.modules.agent.writing.infrastructure.model.WritingModel.Budget;
@RequiredArgsConstructor
public class WritingTools {
    private final ObjectMapper json;private final WritingRetrieval retrieval;
    public Object[] forPhase(String phase,Snapshot snapshot,JsonNode input,Budget budget){
        var tools=new ArrayList<Object>();tools.add(new Tools(snapshot,input,budget));
        if(retrieval!=null){var research=new ResearchTools(snapshot,budget);tools.add(research);if(phase.equals("CHECK"))tools.add(new ProposalTools(research,budget));}
        return tools.toArray();
    }
    public class Tools {
        private final Snapshot snapshot;private final JsonNode input;private final Budget budget;
        Tools(Snapshot snapshot,JsonNode input,Budget budget){this.snapshot=snapshot;this.input=input;this.budget=budget;}
        @Tool("读取当前章已授权大纲") public String getChapterOutline(){budget.tool();return snapshot.outline();}
        @Tool("读取作者指定的文风、格式和类型") public String getWritingProfile(){budget.tool();return input.path("profile").toString();}
        @Tool("读取本次有限前文及来源标识，不能推断更早全书") public String getStoryRecap(){budget.tool();return json.valueToTree(snapshot.sources()).toString();}
        @Tool("读取当前章节与写作目标") public String getWritingContext(){budget.tool();return input.path("goal").asText()+"\n"+snapshot.content().substring(Math.max(0,snapshot.content().length()-2000));}
        @Tool("在冻结的前文片段中按关键词查找原文证据")
        public String searchPreviousEvidence(String query,int limit){
            budget.tool();if(query==null || query.isBlank() || query.length()>100 || limit<1 || limit>5) throw WritingStore.error(422,"检索参数无效");
            var hits=json.createArrayNode();
            for(var source:snapshot.sources()) {int at=source.text().indexOf(query);if(at<0)continue;
                int from=Math.max(0,at-100),to=Math.min(source.text().length(),from+500);
                hits.addObject().put("sourceId",source.id()).put("start",source.start()+from).put("end",source.start()+to).put("quote",source.text().substring(from,to));
                if(hits.size()>=limit) break;
            }
            return hits.toString();
        }
    }
    public class ResearchTools {
        private final Snapshot snapshot;private final Budget budget;private final List<Source> evidence=new ArrayList<>();
        ResearchTools(Snapshot snapshot,Budget budget){this.snapshot=snapshot;this.budget=budget;evidence.addAll(snapshot.sources());}
        @Tool("读取全书、当前卷和本章剧情笔记。它们是未来创作计划，不是已发生事实") public String getStoryNotes(){budget.tool();return json.valueToTree(snapshot.notes()).toString();}
        @Tool("读取按章节生效的作者确认设定和核心规则，PLAN 类型仅是计划") public String getStorySettings(){budget.tool();return json.valueToTree(snapshot.settings()).toString();}
        @Tool("信息不足时检索当前作品更早正文。只返回已核验原文，找不到不代表事情未发生") public String searchEarlierChapters(String query,int limit){
            budget.tool();var found=retrieval.search(snapshot,query,limit);evidence.addAll(found.sources());budget.evidence(found.sources(),found.mode(),found.notice());return json.valueToTree(found).toString();
        }
    }
    public class ProposalTools {
        private final ResearchTools research;private final Budget budget;
        ProposalTools(ResearchTools research,Budget budget){this.research=research;this.budget=budget;}
        @Tool("从更早正文提取候选记忆，进入作者待确认区，不直接成为正式设定。kind 为 CHARACTER LOCATION ITEM WORLD RULE")
        public String proposeStoryMemory(String name,String kind,String body,String sourceId,String quote){
            budget.tool();var s=research.snapshot;
            var evidence=new Snapshot(s.chapterId(),s.novelId(),s.chapterNo(),s.title(),s.version(),s.content(),s.nodeId(),s.outline(),s.outlineHash(),research.evidence,s.notes(),s.settings(),s.coverage(),s.catalogHash());
            return json.valueToTree(retrieval.propose(evidence,name,kind,body,sourceId,quote)).toString();
        }
    }
}
