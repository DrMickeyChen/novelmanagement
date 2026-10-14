package com.novel.modules.agent.bookfinder.conversation;

/** Only explicit continuations inherit filters; a self-contained request starts a new search. */
public final class BookFinderIntent {
    private BookFinderIntent() {}
    /** Null means no reference; positions are one-based, including invalid explicit zero. */
    public static Integer referencePosition(String message) {
        var match=java.util.regex.Pattern.compile("第([0-9]{1,3}|[一二三四五六七八九十两]{1,3})(?:本|部)").matcher(message);
        if(!match.find()) return null;
        String number=match.group(1);
        if(number.matches("[0-9]+")) return Integer.parseInt(number);
        int total=0, digit=0;
        for(char c:number.toCharArray()) {
            if(c=='十') { total+=(digit==0?1:digit)*10; digit=0; }
            else digit=c=='两'?2:"一二三四五六七八九".indexOf(c)+1;
        }
        return total+digit;
    }
    public static boolean greeting(String message) {
        return message.trim().replaceAll("[，。！？、!?,.\\s]","").matches("(?i)(你好|您好|嗨|哈喽|早上好|晚上好|hello|hi)");
    }
    public static boolean continues(String message) {
        String text=message.trim().replaceAll("[，。！？、\\s]","");
        // References are not restricted to the first three books or Chinese digits.
        if(java.util.regex.Pattern.compile("第(?:[0-9]{1,3}|[一二三四五六七八九十两]{1,3})(?:本|部)").matcher(text).find()) return true;
        return text.matches("^(换一批|换几本|再来一批|再推荐|继续推荐|还有吗|还有别的吗|有没有别的|只要|只看|改成|改为|加上|去掉|不限|放宽|至少|最多|不超过|不少于|第二本|第一本|第三本|这几本|这些书|刚才|上面|上一批).*" )
                || text.matches(".*(和|像|类似|相似)(第一|第二|第三|上面|刚才|上次|之前|这本|那本).*" );
    }
}
