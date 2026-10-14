package com.novel.ai.mq;
import com.novel.common.mq.*;
import com.novel.common.constant.MqConstant;
import com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@RocketMQMessageListener(topic=MqConstant.TOPIC,selectorExpression=MqConstant.TAG_NOVEL_INDEX,
        consumerGroup=MqConstant.GROUP_AI_BOOK_KNOWLEDGE,maxReconsumeTimes=3)
public class BookKnowledgeConsumer implements RocketMQListener<NovelIndexMessage> {
    private final BookKnowledgeService knowledge;
    @Override public void onMessage(NovelIndexMessage message) {
        if(message!=null && message.novelId()!=null) knowledge.synchronize(message.novelId().toString());
    }
}
