package com.novel.modules.agent.bookfinder;
import com.novel.modules.agent.bookfinder.conversation.BookFinderIntent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BookFinderIntentTest {
    @Test void independentCultivationRequestDoesNotInheritFanfiction() {
        assertFalse(BookFinderIntent.continues("有没有主角不是无脑强的修仙爽文"));
        assertFalse(BookFinderIntent.continues("推荐几本历史小说"));
        assertFalse(BookFinderIntent.continues("想找科幻，有没有推荐的"));
    }
    @Test void explicitRefinementsAndReferencesKeepConditions() {
        for(var input:new String[]{"换一批","只要完结的","不限字数了","和第二本类似的","第二本这种风格","有没有别的",
            "类似第4本的还有吗，要求有恋爱情节","类似第四本的还有吗","第5本这种风格","有没有像第十部的小说"})
            assertTrue(BookFinderIntent.continues(input),input);
    }
}
