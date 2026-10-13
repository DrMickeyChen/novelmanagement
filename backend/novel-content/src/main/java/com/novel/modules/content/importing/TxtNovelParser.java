package com.novel.modules.content.importing;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Deterministic TXT parser. Recognition is a suggestion; administrators confirm metadata. */
public final class TxtNovelParser {
    public record ChapterText(String title, String content, int wordCount) {}
    public record ChapterPreview(int number, String title, int wordCount) {}
    public record Preview(String title, String originalAuthor, String intro, String category,
                          String tags, Integer isFinish, int chapterCount, long wordCount,
                          List<ChapterPreview> chapters, String sample, List<String> warnings) {}
    @FunctionalInterface public interface Sink { void accept(ChapterText chapter) throws IOException; }
    private static final String NUM = "[0-9０-９零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]+";
    private static final Pattern CHINESE = Pattern.compile("^第\\s*" + NUM + "\\s*[章回节集].{0,180}$");
    private static final Pattern ENGLISH = Pattern.compile("(?i)^chapter\\s+[0-9ivxlcdm]+(?:\\s.*|[.:：、].*)?$");
    private static final Pattern NUMBERED = Pattern.compile("^" + NUM + "[、.．]\\s*\\S.{0,150}$");
    private static final Pattern COMBINED = Pattern.compile("^(.*?)\\s*[/／]\\s*作者\\s*[:：]\\s*(.+)$");
    private static final Pattern BY = Pattern.compile("^(?!作者\\s*[:：])(?:书名\\s*[:：]\\s*)?(.+?)\\s*(?:作者\\s*[:：]|\\s+作者\\s+|\\s+by\\s+)(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD = Pattern.compile("^(书名|作品名称|小说名称|作者|作\\s*者|状态|分类|类别|标签|关键词|内容简介|简介)\\s*[:：]\\s*(.*)$");

    public Preview parse(Reader input, String filename, String mode, Sink sink) throws IOException {
        if (!Set.of("AUTO", "CHINESE", "NUMBERED", "ENGLISH", "SINGLE").contains(mode))
            throw new IllegalArgumentException("不支持的章节识别规则");
        BufferedReader reader = new BufferedReader(input);
        List<String> header = new ArrayList<>();
        List<ChapterPreview> previews = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String title = null, sample = "";
        StringBuilder body = new StringBuilder();
        long total = 0;
        String line;
        while ((line = boundedLine(reader)) != null) {
            if (line.indexOf('\0') >= 0) throw new IllegalArgumentException("文件包含非文本内容或编码不正确，请切换编码");
            String trimmed = line.replace("\uFEFF", "").strip();
            boolean heading = trimmed.length() <= 200 && isHeading(trimmed, mode);
            if (heading) {
                if (title != null) {
                    ChapterText chapter = emit(title, body, sink);
                    total += chapter.wordCount();
                    previews.add(new ChapterPreview(previews.size() + 1, chapter.title(), chapter.wordCount()));
                    if (sample.isEmpty()) sample = excerpt(chapter.content());
                }
                title = trimmed;
                body.setLength(0);
            } else if (title != null || mode.equals("SINGLE")) {
                if (title == null) title = "正文";
                body.append(line.replace("\uFEFF", "")).append('\n');
                if (body.length() > 2_000_000) throw new IllegalArgumentException("单章超过 200 万字符，请选择正确的拆章规则");
            } else if (header.size() < 500) {
                header.add(trimmed);
            }
            if (previews.size() >= 20_000) throw new IllegalArgumentException("章节数超过 20000 章");
            if (total + body.length() > 30_000_000) throw new IllegalArgumentException("正文超过 3000 万字符");
        }
        if (title != null) {
            ChapterText chapter = emit(title, body, sink);
            total += chapter.wordCount();
            previews.add(new ChapterPreview(previews.size() + 1, chapter.title(), chapter.wordCount()));
            if (sample.isEmpty()) sample = excerpt(chapter.content());
        }
        Map<String, String> meta = metadata(header);
        String bookTitle = meta.getOrDefault("title", filename.replaceFirst("(?i)\\.txt$", ""));
        if (!meta.containsKey("title")) warnings.add("未识别书名，暂用文件名，请确认。");
        if (!meta.containsKey("author")) warnings.add("未识别原作者，请补充。");
        if (!meta.containsKey("category")) warnings.add("未识别分类，请选择。");
        if (previews.isEmpty()) warnings.add("未识别章节，不能导入。请切换规则；无章节短文可选择整篇正文。");
        if (!header.isEmpty()) warnings.add("章节前的头部内容仅提取书籍资料，不写入正文，请检查首章预览。");
        if (previews.stream().anyMatch(c -> c.wordCount() == 0)) warnings.add("存在空章节，将保留标题、顺序和空正文一同导入，请确认目录。");
        String state = meta.getOrDefault("state", "");
        Integer finish = null;
        if (state.contains("完结") || state.contains("全本")) finish = 1;
        else if (state.contains("连载")) finish = 0;
        return new Preview(bookTitle, meta.get("author"), meta.get("intro"), meta.get("category"),
                meta.get("tags"), finish, previews.size(), total, List.copyOf(previews), sample, warnings);
    }

    private static String boundedLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.append((char)c);
            if (line.length() > 200_000) throw new IllegalArgumentException("单行超过 20 万字符，请整理 TXT 换行后再导入");
        }
        return c == -1 && line.isEmpty() ? null : line.toString();
    }
    private static boolean isHeading(String line, String mode) {
        return switch (mode) {
            case "CHINESE" -> CHINESE.matcher(line).matches();
            case "NUMBERED" -> NUMBERED.matcher(line).matches();
            case "ENGLISH" -> ENGLISH.matcher(line).matches();
            case "AUTO" -> CHINESE.matcher(line).matches() || ENGLISH.matcher(line).matches()
                    || line.matches("^(序章|楔子|引子|尾声|终章)(?:[\\s:：].*)?$");
            default -> false;
        };
    }
    private static ChapterText emit(String title, StringBuilder body, Sink sink) throws IOException {
        String text = body.toString().strip();
        int words = text.replaceAll("[\\s\\u3000]+", "").length();
        ChapterText chapter = new ChapterText(title, text, words);
        sink.accept(chapter);
        return chapter;
    }
    private static String excerpt(String text) { return text.substring(0, Math.min(1200, text.length())); }
    private static String unwrap(String s) {
        return s.replaceAll("^[『【《]+", "").replaceAll("[』】》]+$", "").strip();
    }
    private static Map<String, String> metadata(List<String> header) {
        Map<String, String> values = new HashMap<>();
        StringBuilder intro = new StringBuilder();
        boolean inIntro = false;
        for (String raw : header) {
            String s = unwrap(raw);
            Matcher combined = COMBINED.matcher(s), by = BY.matcher(s), field = FIELD.matcher(s);
            if (combined.matches()) {
                values.put("title", unwrap(combined.group(1))); values.put("author", unwrap(combined.group(2))); inIntro = false;
            } else if (by.matches()) {
                values.put("title", unwrap(by.group(1))); values.put("author", unwrap(by.group(2))); inIntro = false;
            } else if (field.matches()) {
                String key = field.group(1).replaceAll("\\s", ""), value = unwrap(field.group(2));
                inIntro = key.equals("简介") || key.equals("内容简介");
                if (inIntro) intro.append(value).append('\n');
                else values.put(switch (key) {
                    case "作者" -> "author"; case "状态" -> "state";
                    case "分类", "类别" -> "category"; case "标签", "关键词" -> "tags"; default -> "title";
                }, value);
            } else if (inIntro) {
                if (!s.isBlank()) intro.append(s).append('\n');
                if (raw.strip().matches("[』】]+")) inIntro = false;
            } else if (raw.strip().matches("^《[^》]+》$")) values.putIfAbsent("title", s);
        }
        if (!intro.toString().isBlank()) values.put("intro", intro.toString().strip());
        values.entrySet().removeIf(e -> e.getValue().isBlank());
        return values;
    }
}
