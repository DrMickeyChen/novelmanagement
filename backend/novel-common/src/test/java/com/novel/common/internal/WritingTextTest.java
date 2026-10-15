package com.novel.common.internal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WritingTextTest {
    @Test void repeatedTextUsesExactSelection(){assertEquals("甲乙新甲乙",WritingText.replace("甲乙甲乙甲乙",2,4,"甲乙","新"));}
    @Test void insertionAndFullReplacement(){assertEquals("前后",WritingText.replace("前",1,1,"","后"));assertEquals("新",WritingText.replace("旧",0,1,"旧","新"));}
    @Test void refusesDriftAndSplitEmoji(){assertThrows(RuntimeException.class,()->WritingText.replace("新正文",0,1,"旧","X"));assertThrows(RuntimeException.class,()->WritingText.range("a😀b",2,3));WritingText.range("a😀b",1,3);}
    @Test void hashIsStable(){assertEquals(WritingText.hash("文本"),WritingText.hash("文本"));assertNotEquals(WritingText.hash("文本"),WritingText.hash("修改"));}
}
