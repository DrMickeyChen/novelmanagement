package com.novel.modules.ai.service.impl;

import com.novel.common.security.UserContext;
import com.novel.modules.ai.dto.AiRequestDTO;
import com.novel.modules.ai.enums.AiScene;
import com.novel.modules.ai.service.AiStreamSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "novel.ai.provider", havingValue = "mock")
public class MockAiStreamSource implements AiStreamSource {

    public static final String FAIL_MARKER = "__MOCK_FAIL__";

    private final long delayMs;

    public MockAiStreamSource(@Value("${novel.ai.mock-delay-ms:15}") long delayMs) {
        this.delayMs = delayMs;
        log.info("★★ AI 当前使用【演示模式】(MockAiStreamSource) —— 内容由本地生成，不是真实模型。"
                + "逐字间隔={}ms。★ 这条日志只在【显式】指定 novel.ai.provider=mock 时才会出现；"
                + "默认（deepseek）下本类不会被创建，所以看到它 = 有人主动切回了演练模式", delayMs);
    }

    @Override
    public void stream(AiScene scene, AiRequestDTO request, Handler handler) {
        long start = System.currentTimeMillis();
        String text = generate(scene, request);

        log.info("AI 生成开始 | scene={}({}) | user={} | 长度={}字 | 演示数据",
                scene.getCode(), scene.getLabel(), UserContext.getUsername(),
                text.codePointCount(0, text.length()));

        int totalCodePoints = text.codePointCount(0, text.length());
        int failAfter = hasFailMarker(request) ? Math.max(1, totalCodePoints / 3) : -1;

        int emitted = 0;

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);

            handler.onChunk(text.substring(i, i + charCount));

            i += charCount;
            emitted++;

            if (failAfter > 0 && emitted >= failAfter) {
                log.warn("AI 生成中途失败（测试钩子 {}）| scene={} | 已推送={}字",
                        FAIL_MARKER, scene.getCode(), emitted);
                handler.onError(new IllegalStateException("演示模式：模拟上游模型连接中断"));
                return;
            }

            if (!sleepQuietly()) {
                // 线程被中断（多半是应用正在关闭）—— 静默退出，不当成生成失败
                log.debug("AI 生成被中断 | scene={} | 已推送={}字", scene.getCode(), emitted);
                return;
            }
        }

        handler.onComplete();
        log.info("AI 生成完成 | scene={}({}) | 耗时={}ms | 推送={}字",
                scene.getCode(), scene.getLabel(), System.currentTimeMillis() - start, emitted);
    }

    private String generate(AiScene scene, AiRequestDTO request) {
        if (scene != AiScene.SUMMARY) {
            throw new IllegalStateException("Only chapter summaries are supported; use Writing Agent");
        }
        return summary(request);
    }

    private String summary(AiRequestDTO request) {
        String source = String.valueOf(request.getContext());
        String[] passages = {
                """
                主要人物在上一段里各自做出了一个不可逆的选择：一个把退路封死，
                另一个第一次说了实话，而第三个人什么都没说 —— 但正是那份沉默让前两个人
                意识到，他们以为的「共同目标」从一开始就不是同一个。

                局势上，明面上的冲突暂时平息，暗处那个一直没被点名的角色开始有了动作。
                主角手里多了一条当时看来无关紧要的线索，尚未意识到它的用处。""",
                """
                上一段以一次代价不小的胜利收尾：想做的事做成了，但失去的东西
                比预期多。主角对外表现得若无其事，独处时的反应才是真正的状态。

                值得注意的是两处伏笔 —— 一句被打断的话、一个当时没解释的动作 ——
                它们都还没有回收，后文需要回来交代。反派这一段的动机已经露出冰山一角，
                但真实目的仍被遮着。""",
                """
                这一段的主线是「靠近」：两个人从互相试探走到愿意交换一部分真话，
                各自保留的最后那点秘密成为下一段冲突的引信。

                支线上，配角的那条线第一次和主线交汇，交汇点是一个谁都没在意的细节。
                环境描写里出现过两次的那个意象应当在后文再次出现，用来标记时间的推移。"""
        };
        String body = passages[Math.floorMod(source.hashCode(), passages.length)];

        return "【演示数据】以下梗概由 MockAiStreamSource 生成，未调用真实模型，"
                + "不可作为正式前情使用。本次压缩覆盖正文约 "
                + source.codePointCount(0, source.length()) + " 字。\n\n" + body;
    }

    private static boolean hasFailMarker(AiRequestDTO request) {
        return containsFailMarker(request.getPrompt()) || containsFailMarker(request.getContext());
    }

    private static boolean containsFailMarker(String text) {
        return text != null && text.contains(FAIL_MARKER);
    }

    private boolean sleepQuietly() {
        if (delayMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            return false;
        }
    }
}
