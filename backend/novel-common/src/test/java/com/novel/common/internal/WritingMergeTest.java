package com.novel.common.internal;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WritingMergeTest {
 @Test void nonOverlappingEditsOnBothSidesKeepAuthorChanges(){String base="开场\n目标场景\n结尾";var result=WritingMerge.preview(base,"新开场\n目标场景\n新结尾",3,7,"目标场景","新的场景");assertTrue(result.clean());assertEquals("新开场\n新的场景\n新结尾",result.merged());}
 @Test void overlappingAndRepeatedTargetsRequireExplicitResolution(){assertFalse(WritingMerge.preview("abcdef","abXYef",2,4,"cd","新").clean());assertFalse(WritingMerge.preview("一段一段","前一段一段",0,2,"一段","新").clean());}
 @Test void insertionAndEmojiOffsetsRemainUnicodeSafe(){var result=WritingMerge.preview("😀开场结尾","😀新开场结尾",6,6,"","新段");assertTrue(result.clean());assertEquals("😀新开场结尾新段",result.merged());assertThrows(RuntimeException.class,()->WritingMerge.preview("😀开场","😀开场",1,2,"","x"));}
 @Test void excessiveEditsFallBackToManualResolution(){assertFalse(WritingMerge.preview("a".repeat(1000),"b".repeat(1000),500,500,"","新").clean());}
 @Test void randomDiffsReplayExactly(){var random=new Random(7001);String[] tokens={"甲","乙","😀","\n","a"};for(int trial=0;trial<500;trial++){var a=new StringBuilder();var b=new StringBuilder();for(int i=0;i<random.nextInt(30);i++)a.append(tokens[random.nextInt(tokens.length)]);for(int i=0;i<random.nextInt(30);i++)b.append(tokens[random.nextInt(tokens.length)]);String text=a.toString();var edits=WritingMerge.edits(text,b.toString());for(int i=edits.size()-1;i>=0;i--){var e=edits.get(i);WritingText.range(text,e.start(),e.end());text=text.substring(0,e.start())+e.replacement()+text.substring(e.end());}assertEquals(b.toString(),text);}}
}
