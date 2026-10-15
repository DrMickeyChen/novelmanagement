package com.novel.modules.agent.writing.context;

import com.novel.modules.agent.writing.infrastructure.persistence.WritingStore;
import com.novel.modules.agent.writing.infrastructure.client.WritingContentClient;
import com.novel.modules.agent.writing.infrastructure.client.WritingSearchClient;

import com.novel.common.internal.*;
import com.novel.common.internal.WritingContent.*;
import com.novel.modules.agent.bookfinder.knowledge.RetrievalModels;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class WritingRetrieval {
    private final WritingContentClient content;private final WritingSearchClient search;private final RetrievalModels models;private final ObjectMapper json;
    public record Evidence(List<Source> sources,String mode,String notice) {}
    public void clear(long chapter,long node) {
        var owned=InternalResults.unwrapBusiness(content.metadata(chapter,node),"writer clear ownership");
        InternalResults.unwrapBusiness(search.delete(owned.get("novelId").toString()),"private writer index clear");
    }
    public Evidence search(Snapshot snapshot,String query,int limit) {
        if(query==null||query.isBlank()||query.length()>200||limit<1||limit>8)throw WritingStore.error(422,"检索参数无效");
        long chapter=Long.parseLong(snapshot.chapterId()),node=Long.parseLong(snapshot.nodeId());
        var catalog=InternalResults.unwrapBusiness(content.chapters(chapter,node),"author research ACL");
        var keys=catalog.stream().filter(row->row.get("chapterNo") instanceof Number number && number.intValue()<snapshot.chapterNo()).map(row->row.get("id")+":"+row.get("version")).toList();
        List<Double> vector=List.of();String identity="",notice="";
        if(models.embeddingEnabled())try{identity=models.embeddingIdentity();vector=models.embed(List.of(query)).getFirst();if(!identity.equals(models.embeddingIdentity())){vector=List.of();notice="向量模型配置已变化，使用关键词召回。";}}catch(Exception e){notice="语义检索不可用，使用关键词召回。";}
        List<Source> hits;String mode;
        try {var recall=InternalResults.unwrapBusiness(search.search(new PrivateRecall(snapshot.novelId(),keys,query,vector,identity,20)),"private writer retrieval");hits=recall.sources();mode=recall.mode();}
        catch(Exception e){return new Evidence(List.of(),"UNAVAILABLE","私有正文索引暂不可用；请在资料中构建检索资料，不能据此确认更早事实。");}
        // Fresh ACL/version/body validation happens before exposing text to rerank or the model.
        hits=InternalResults.unwrapBusiness(content.validate(chapter,node,hits),"fresh evidence validation");
        if(models.rerankEnabled()&&!hits.isEmpty())try{var old=hits;hits=models.rerank(query,hits.stream().map(Source::text).toList()).stream().map(old::get).toList();mode+=" + Rerank";}catch(Exception e){notice+="重排不可用，保留召回顺序。";}
        return new Evidence(hits.stream().limit(limit).toList(),mode,hits.isEmpty()?notice+"未检索到有效证据，不代表此前没有发生。":notice);
    }
    public Map<String,Object> build(long chapter,long node,List<Long> ids,boolean vectors) throws Exception {
        var catalog=InternalResults.unwrapBusiness(content.chapters(chapter,node),"writer build ACL");
        if(ids==null||ids.isEmpty()||ids.size()>8)throw WritingStore.error(422,"一次选择 1～8 个更早章节构建检索资料");
        if(vectors&&!models.embeddingEnabled())throw WritingStore.error(422,"请先在管理端配置向量接口，或仅构建关键词索引");
        var allowed=catalog.stream().map(r->r.get("id").toString()).collect(java.util.stream.Collectors.toSet());
        for(var id:ids)if(!allowed.contains(id.toString()))throw WritingStore.error(404,"不能构建未授权或未来章节");
        // Derive novel from a fresh authorized chapter context, never from client input.
        var access=InternalResults.unwrapBusiness(content.metadata(chapter,node),"writer build ownership");int count=0;
        for(long id:new LinkedHashSet<>(ids)) {
            var chunks=InternalResults.unwrapBusiness(content.chunks(chapter,node,id),"writer build source");
            for(int start=0;start<chunks.size();start+=8){var batch=chunks.subList(start,Math.min(start+8,chunks.size()));
                String identity=vectors?models.embeddingIdentity():"";
                var embeddings=vectors?models.embed(batch.stream().map(Source::text).toList()):List.<List<Double>>of();
                if(vectors&&!identity.equals(models.embeddingIdentity()))throw WritingStore.error(409,"向量配置在构建中发生变化，请重新构建");
                var valid=InternalResults.unwrapBusiness(content.validate(chapter,node,batch),"private index source freshness");
                if(!batch.equals(valid))throw WritingStore.error(409,"构建来源已修改、删除或不可访问，请重新选择");
                var docs=new ArrayList<PrivateChunk>();for(int i=0;i<batch.size();i++)docs.add(new PrivateChunk(access.get("novelId").toString(),batch.get(i),vectors?embeddings.get(i):List.of(),identity));
                InternalResults.unwrapBusiness(search.put(docs),"private index write");count+=batch.size();
            }
        }return Map.of("chunks",count,"mode",vectors?"关键词 + 向量":"关键词","notice","正文修改后旧版本不再参与召回，请重新构建；向量和重排接口可能另行计费。");
    }
    public Map<String,Object> propose(Snapshot snapshot,String name,String kind,String body,String sourceId,String quote) {
        var source=snapshot.sources().stream().filter(s->s.id().equals(sourceId)&&quote!=null&&!quote.isBlank()&&s.text().contains(quote)).findFirst().orElseThrow(()->WritingStore.error(422,"候选记忆必须引用已授权的更早正文"));
        var req=json.createObjectNode().put("name",name).put("kind",kind).put("body",body).put("meaning","FACT").put("effectiveFrom",source.chapterNo()).put("sourceChapterId",Long.parseLong(source.chapterId())).put("sourceQuote",quote);req.putArray("aliases");
        return InternalResults.unwrapBusiness(content.propose(Long.parseLong(snapshot.novelId()),req),"pending story memory");
    }
}
