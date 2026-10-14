package com.novel.modules.agent.bookfinder.knowledge;

import com.novel.common.internal.BookKnowledge.*;
import java.util.*;

/** Bounded exact retrieval inside a hard-filtered candidate set; relevance is not proof. */
public final class EvidenceRanking {
    private EvidenceRanking() {}
    public record Ranked(Document document,Chunk chunk,double score) {}
    public static List<Chunk> split(Snapshot snapshot) {
        var result=new ArrayList<Chunk>();
        for(var chapter:snapshot.chapters()) {
            String text=chapter.text();
            for(int start=0;start<text.length();) {
                int end=Math.min(text.length(),start+1000);
                result.add(new Chunk(snapshot.book().id()+":"+chapter.id()+":"+start,chapter.id(),chapter.number(),chapter.title(),start,end,text.substring(start,end),List.of()));
                if(end==text.length()) break;
                start=end-150;
            }
        }
        return result;
    }
    public static Set<String> terms(String input) {
        var terms=new HashSet<String>();
        String text=input.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
        for(int i=0;i+1<text.length();i++) terms.add(text.substring(i,i+2));
        if(text.length()==1) terms.add(text);
        return terms;
    }
    public static double lexical(String query,String text) {
        var wanted=terms(query); if(wanted.isEmpty()) return 0;
        var found=terms(text); long overlap=wanted.stream().filter(found::contains).count();
        return overlap/(double)wanted.size();
    }
    public static double cosine(List<Double> a,List<Double> b) {
        if(a==null || b==null || a.isEmpty() || a.size()!=b.size()) return -2;
        double dot=0,x=0,y=0;
        for(int i=0;i<a.size();i++) { dot+=a.get(i)*b.get(i); x+=a.get(i)*a.get(i); y+=b.get(i)*b.get(i); }
        return x==0 || y==0?-2:dot/Math.sqrt(x*y);
    }
    public static List<Ranked> rank(List<Document> docs,String query,List<Double> vector,String model) {
        var lexical=new ArrayList<Ranked>(); var semantic=new ArrayList<Ranked>();
        for(var doc:docs) for(var chunk:doc.chunks()) {
            double score=lexical(query,chunk.text());
            if(score>0) lexical.add(new Ranked(doc,chunk,score));
            if(Objects.equals(model,doc.embeddingModel())) {
                double similarity=cosine(vector,chunk.vector());
                if(similarity>-2) semantic.add(new Ranked(doc,chunk,similarity));
            }
        }
        Comparator<Ranked> order=Comparator.comparingDouble(Ranked::score).reversed().thenComparing(r->r.chunk().id());
        lexical.sort(order); semantic.sort(order);
        if(semantic.isEmpty()) return lexical.stream().limit(20).toList();
        var scores=new HashMap<String,Double>(); var rows=new HashMap<String,Ranked>();
        for(var list:List.of(lexical,semantic)) for(int i=0;i<Math.min(60,list.size());i++) {
            var r=list.get(i); rows.put(r.chunk().id(),r); scores.merge(r.chunk().id(),1.0/(60+i+1),Double::sum);
        }
        return rows.values().stream().map(r->new Ranked(r.document(),r.chunk(),scores.get(r.chunk().id()))).sorted(order).limit(20).toList();
    }
}
