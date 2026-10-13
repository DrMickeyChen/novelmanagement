package com.novel.modules.content.importing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import com.novel.common.security.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service
@Slf4j
public class NovelImportService {
    public record TaskView(String taskId, String filename, String encoding, String parseMode,
                           String status, int totalChapters, int importedChapters, Long novelId,
                           String errorMessage, TxtNovelParser.Preview preview) {}
    private final NovelImportTaskMapper tasks;
    private final NovelSourceMapper sources;
    private final NovelImportWriter writer;
    private final ObjectMapper json;
    private final Path directory;
    private final LocalDateTime startedAt = LocalDateTime.now();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), r -> { Thread t = new Thread(r, "txt-novel-import"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());

    public NovelImportService(NovelImportTaskMapper tasks, NovelSourceMapper sources, NovelImportWriter writer,
                              ObjectMapper json, @Value("${novel.import.directory:${java.io.tmpdir}/novel-txt-import}") String directory) throws IOException {
        this.tasks = tasks; this.sources = sources; this.writer = writer; this.json = json;
        this.directory = Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(this.directory);
    }
    private void requireAdmin() {
        if (!UserContext.hasRole("ADMIN")) throw new BusinessException(ResultCode.FORBIDDEN);
    }
    public TaskView preview(MultipartFile file, String encoding, String mode) {
        requireAdmin();
        if (file.isEmpty() || file.getSize() > 50L * 1024 * 1024) throw bad("请选择不超过 50MB 的 TXT 文件");
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        filename = filename.replace('\\', '/'); filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".txt")) throw bad("仅支持 TXT 文件");
        if (filename.length() > 255) throw bad("文件名过长");
        String id = UUID.randomUUID().toString();
        Path raw = path(id, ".txt"), parsed = path(id, ".jsonl");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(file.getInputStream(), digest); OutputStream out = Files.newOutputStream(raw, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192]; int n; long bytes = 0;
                while ((n = in.read(buffer)) != -1) { bytes += n; if (bytes > 50L * 1024 * 1024) throw bad("文件超过 50MB"); out.write(buffer, 0, n); }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            NovelSource duplicate = sources.selectOne(new LambdaQueryWrapper<NovelSource>().eq(NovelSource::getFileHash, hash));
            if (duplicate != null) throw bad("该文件已导入，作品 ID：" + duplicate.getNovelId());
            List<String> encodings = switch (encoding.toUpperCase(Locale.ROOT)) {
                case "AUTO" -> List.of("UTF-8", "GB18030");
                case "UTF-8", "GB18030", "GBK", "UTF-16LE", "UTF-16BE" -> List.of(encoding);
                default -> throw bad("不支持的文本编码");
            };
            byte[] bom = new byte[2];
            try (InputStream in = Files.newInputStream(raw)) { in.read(bom); }
            if (encoding.equalsIgnoreCase("AUTO")) {
                if (bom[0] == (byte)0xff && bom[1] == (byte)0xfe) encodings = List.of("UTF-16LE");
                if (bom[0] == (byte)0xfe && bom[1] == (byte)0xff) encodings = List.of("UTF-16BE");
            }
            TxtNovelParser.Preview preview = null; String usedEncoding = null;
            for (String candidate : encodings) {
                try (Reader reader = new InputStreamReader(Files.newInputStream(raw), Charset.forName(candidate).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
                     BufferedWriter out = Files.newBufferedWriter(parsed, StandardCharsets.UTF_8)) {
                    preview = new TxtNovelParser().parse(reader, filename, mode, c -> { out.write(json.writeValueAsString(c)); out.newLine(); });
                    usedEncoding = candidate; break;
                } catch (CharacterCodingException e) { if (encodings.size() == 1) throw bad("编码不匹配，请切换编码重新解析"); }
            }
            if (preview == null) throw bad("无法解码 TXT，请选择正确编码后重试");
            NovelImportTask task = new NovelImportTask();
            task.setId(id); task.setOperatorId(UserContext.requireUserId()); task.setFilename(filename);
            task.setFileHash(hash); task.setEncoding(usedEncoding); task.setParseMode(mode);
            task.setStatus("PREVIEW"); task.setPreviewJson(json.writeValueAsString(preview));
            task.setTotalChapters(preview.chapterCount()); task.setImportedChapters(0);
            task.setCreateTime(LocalDateTime.now()); task.setUpdateTime(LocalDateTime.now()); tasks.insert(task);
            Files.deleteIfExists(raw);
            return view(task);
        } catch (BusinessException e) { deleteFiles(id); throw e; }
        catch (IllegalArgumentException e) { deleteFiles(id); throw bad(e.getMessage()); }
        catch (Exception e) { deleteFiles(id); log.error("TXT preview failed task={}", id, e); throw bad("解析失败，请检查文本编码、内容和章节格式"); }
    }
    public TaskView get(String id) { requireAdmin(); return view(owned(id)); }
    public TaskView submit(String id, NovelImportOptions options) {
        requireAdmin(); NovelImportTask task = owned(id);
        if (!task.getStatus().equals("PREVIEW")) return view(task); // idempotent confirmation
        if (task.getTotalChapters() == 0) throw bad("未识别章节，请重新解析");
        if (task.getCreateTime().isBefore(LocalDateTime.now().minusHours(24))) throw bad("预览已过期，请重新上传");
        writer.validateCategory(options.getCategoryId());
        try {
            int changed = tasks.update(null, new LambdaUpdateWrapper<NovelImportTask>().eq(NovelImportTask::getId, id)
                    .eq(NovelImportTask::getStatus, "PREVIEW").set(NovelImportTask::getStatus, "QUEUED")
                    .set(NovelImportTask::getOptionsJson, json.writeValueAsString(options)).set(NovelImportTask::getUpdateTime, LocalDateTime.now()));
            if (changed == 0) return get(id);
            LoginUser operator = UserContext.get();
            try { executor.execute(() -> run(id, operator)); }
            catch (RejectedExecutionException e) {
                tasks.update(null, new LambdaUpdateWrapper<NovelImportTask>().eq(NovelImportTask::getId, id)
                        .eq(NovelImportTask::getStatus, "QUEUED").set(NovelImportTask::getStatus, "PREVIEW"));
                throw bad("导入队列已满，请稍后再次确认");
            }
            return get(id);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    public void discard(String id) {
        requireAdmin(); NovelImportTask task = owned(id);
        int changed = tasks.update(null, new LambdaUpdateWrapper<NovelImportTask>().eq(NovelImportTask::getId, id)
                .eq(NovelImportTask::getStatus, "PREVIEW").set(NovelImportTask::getStatus, "CANCELLED"));
        if (changed == 1) deleteFiles(task.getId());
    }
    private void run(String id, LoginUser operator) {
        UserContext.set(operator);
        NovelImportTask task = tasks.selectById(id);
        try {
            NovelImportOptions options = json.readValue(task.getOptionsJson(), NovelImportOptions.class);
            writer.begin(task, options);
            try (BufferedReader in = Files.newBufferedReader(path(id, ".jsonl"), StandardCharsets.UTF_8)) {
                List<TxtNovelParser.ChapterText> batch = new ArrayList<>(); String line; int start = 1; long chars = 0;
                while ((line = in.readLine()) != null) {
                    var chapter = json.readValue(line, TxtNovelParser.ChapterText.class);
                    batch.add(chapter); chars += chapter.content().length();
                    if (batch.size() >= 100 || chars >= 1_000_000) { writer.batch(task, start, batch); start += batch.size(); batch.clear(); chars = 0; }
                }
                if (!batch.isEmpty()) writer.batch(task, start, batch);
            }
            writer.complete(task);
        } catch (Exception e) {
            log.error("TXT import failed task={}", id, e);
            try { writer.fail(task, "导入失败，未公开作品。请检查数据库、分类或重复文件后重新导入。"); }
            catch (Exception cleanup) { log.error("TXT import cleanup failed task={}; book remains unpublished", id, cleanup); }
        } finally { UserContext.clear(); deleteFiles(id); }
    }
    private NovelImportTask owned(String id) {
        NovelImportTask task = tasks.selectById(id);
        if (task == null || !Objects.equals(task.getOperatorId(), UserContext.requireUserId())) throw bad("导入任务不存在");
        return task;
    }
    private TaskView view(NovelImportTask task) {
        return new TaskView(task.getId(), task.getFilename(), task.getEncoding(), task.getParseMode(), task.getStatus(),
                task.getTotalChapters(), task.getImportedChapters(), task.getNovelId(), task.getErrorMessage(), readPreview(task));
    }
    private TxtNovelParser.Preview readPreview(NovelImportTask task) {
        try { return json.readValue(task.getPreviewJson(), TxtNovelParser.Preview.class); }
        catch (IOException e) { throw new IllegalStateException("预览记录损坏", e); }
    }
    private Path path(String id, String suffix) {
        UUID.fromString(id); return directory.resolve(id + suffix);
    }
    private void deleteFiles(String id) {
        try { Files.deleteIfExists(path(id, ".txt")); Files.deleteIfExists(path(id, ".jsonl")); }
        catch (IOException e) { log.warn("Cannot remove temporary import file task={}", id, e); }
    }
    private BusinessException bad(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }
    /** Current deployment runs one content instance. Interrupted jobs are cleaned up, never auto-published. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        for (NovelImportTask task : tasks.selectList(new LambdaQueryWrapper<NovelImportTask>()
                .in(NovelImportTask::getStatus, "QUEUED", "IMPORTING").lt(NovelImportTask::getCreateTime, startedAt))) {
            try { writer.fail(task, "服务重启中断导入，作品未公开，请重新上传。"); deleteFiles(task.getId()); }
            catch (Exception e) { log.error("Cannot recover import task={}", task.getId(), e); }
        }
    }
    @Scheduled(fixedDelayString = "PT1H")
    public void expirePreviews() {
        for (NovelImportTask task : tasks.selectList(new LambdaQueryWrapper<NovelImportTask>()
                .eq(NovelImportTask::getStatus, "PREVIEW").lt(NovelImportTask::getCreateTime, LocalDateTime.now().minusHours(24)))) {
            int changed = tasks.update(null, new LambdaUpdateWrapper<NovelImportTask>().eq(NovelImportTask::getId, task.getId())
                    .eq(NovelImportTask::getStatus, "PREVIEW").set(NovelImportTask::getStatus, "EXPIRED"));
            if (changed == 1) deleteFiles(task.getId());
        }
    }
    @PreDestroy public void shutdown() {
        executor.shutdown();
        try { if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException e) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
