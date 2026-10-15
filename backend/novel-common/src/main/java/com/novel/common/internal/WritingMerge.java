package com.novel.common.internal;

import java.util.*;

/** Bounded Unicode Myers diff followed by a three-way merge of one author-selected AI patch. */
public final class WritingMerge {
    private WritingMerge() {}
    public record Edit(int start,int end,String replacement) {}
    public record Preview(boolean clean,String base,String current,String candidate,String merged,List<String> conflicts) {}
    public static Preview preview(String base,String current,int start,int end,String original,String replacement) {
        if(base==null||current==null||replacement==null||base.length()>100000||current.length()>100000||replacement.length()>20000)throw new com.novel.common.exception.BusinessException(422,"文本超过合并边界",422);
        String candidate=WritingText.replace(base,start,end,original,replacement);
        if(base.equals(current))return new Preview(true,base,current,candidate,candidate,List.of());
        // Repeated targets are ambiguous after concurrent edits; never silently choose an occurrence.
        if(!original.isEmpty()&&occurrences(base,original)>1)return conflict(base,current,candidate,"基准正文包含重复选区，请人工定位并解决");
        String anchor=base.substring(Math.max(0,start-80),Math.min(base.length(),end+80));
        if(!anchor.isEmpty()&&occurrences(base,anchor)>1)return conflict(base,current,candidate,"选区周围存在重复锚点，请人工解决");
        List<Edit> changes;
        try{changes=edits(base,current);}catch(IllegalArgumentException e){return conflict(base,current,candidate,"改动过多，自动合并已停止，请人工解决");}
        int shift=0;
        for(var edit:changes){
            boolean insertion=edit.start()==edit.end();
            boolean overlap=start==end?edit.start()<=start&&edit.end()>=start:
                    insertion?edit.start()>=start&&edit.start()<=end:edit.start()<end&&edit.end()>start;
            if(overlap)return conflict(base,current,candidate,"作者编辑与 AI 选区重叠，不能自动覆盖");
            if(edit.end()<=start)shift+=edit.replacement().length()-(edit.end()-edit.start());
        }
        int from=start+shift,to=end+shift;
        if(from<0||to>current.length()||!current.substring(from,to).equals(original))return conflict(base,current,candidate,"选区无法可靠映射到当前正文");
        return new Preview(true,base,current,candidate,WritingText.replace(current,from,to,original,replacement),List.of());
    }
    private static Preview conflict(String base,String current,String candidate,String reason){return new Preview(false,base,current,candidate,current,List.of(reason));}
    private static int occurrences(String text,String target){int count=0,at=0;while((at=text.indexOf(target,at))>=0){count++;at++;if(count>1)return count;}return count;}
    public static List<Edit> edits(String before,String after){
        int[] a=before.codePoints().toArray(),b=after.codePoints().toArray();int bound=Math.min(512,a.length+b.length),offset=bound+1;
        int[] v=new int[bound*2+3];var trace=new ArrayList<int[]>();int distance=-1;
        outer:for(int d=0;d<=bound;d++){
            for(int k=-d;k<=d;k+=2){int pos=offset+k;
                int x=k==-d||k!=d&&v[pos-1]<v[pos+1]?v[pos+1]:v[pos-1]+1;int y=x-k;
                while(x<a.length&&y<b.length&&a[x]==b[y]){x++;y++;}v[pos]=x;
                if(x>=a.length&&y>=b.length){trace.add(v.clone());distance=d;break outer;}
            }trace.add(v.clone());
        }
        if(distance<0)throw new IllegalArgumentException("diff edit distance exceeded");
        var reversed=new ArrayList<Character>();int x=a.length,y=b.length;
        for(int d=distance;d>0;d--){int[] previous=trace.get(d-1);int k=x-y;
            int previousK=k==-d||k!=d&&previous[offset+k-1]<previous[offset+k+1]?k+1:k-1;
            int previousX=previous[offset+previousK],previousY=previousX-previousK;
            while(x>previousX&&y>previousY){reversed.add('=');x--;y--;}
            if(x==previousX){reversed.add('+');y--;}else{reversed.add('-');x--;}
        }
        while(x>0&&y>0){reversed.add('=');x--;y--;}while(x-->0)reversed.add('-');while(y-->0)reversed.add('+');Collections.reverse(reversed);
        var edits=new ArrayList<Edit>();int ai=0,bi=0,fromA=-1,fromB=-1;
        for(char op:reversed){if(op=='='){
                if(fromA>=0){edits.add(new Edit(before.offsetByCodePoints(0,fromA),before.offsetByCodePoints(0,ai),new String(b,fromB,bi-fromB)));fromA=-1;}
                ai++;bi++;
            }else{if(fromA<0){fromA=ai;fromB=bi;}if(op=='-')ai++;else bi++;}}
        if(fromA>=0)edits.add(new Edit(before.offsetByCodePoints(0,fromA),before.offsetByCodePoints(0,ai),new String(b,fromB,bi-fromB)));
        return edits;
    }
}
