package com.novel.modules.content.importing;

import com.novel.common.enums.NovelStatus;
import com.novel.modules.content.entity.Novel;
import com.novel.modules.content.mapper.*;
import com.novel.modules.content.service.NovelService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NovelImportWriterTest {
    // Subclass mocks avoid inline instrumentation of JDK 24 with the project's older Byte Buddy.
    private static <T> T mock(Class<T> type) {
        return org.mockito.Mockito.mock(type, withSettings().mockMaker("mock-maker-subclass"));
    }
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NovelMapper novels = mock(NovelMapper.class);
    private final NovelImportTaskMapper tasks = mock(NovelImportTaskMapper.class);
    private final NovelService service = mock(NovelService.class);
    private final NovelImportWriter writer = new NovelImportWriter(jdbc, novels,
            mock(NovelCategoryMapper.class), mock(NovelSourceMapper.class), tasks, service);

    @Test void incompleteChapterCountCannotPublishBook() {
        var task = new NovelImportTask(); task.setNovelId(999L); task.setTotalChapters(2);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> writer.complete(task));
        verifyNoInteractions(service);
        verify(tasks, never()).updateById(any(NovelImportTask.class));
    }

    @Test void notificationFailureAfterPublicationDoesNotDeletePublicBook() {
        var task = new NovelImportTask(); task.setNovelId(999L);
        var book = new Novel(); book.setId(999L); book.setStatus(NovelStatus.PUBLISHED);
        when(novels.selectById(999L)).thenReturn(book);
        writer.fail(task, "notification failed");
        assertEquals("COMPLETED", task.getStatus());
        verifyNoInteractions(jdbc);
        verify(tasks).updateById(task);
    }
}
