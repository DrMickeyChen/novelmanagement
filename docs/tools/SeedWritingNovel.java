import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import java.security.MessageDigest;

/** Inserts one author-private draft fixture in a single transaction; never updates existing works. */
class SeedWritingNovel {
    record Part(int no,String title,String notes,String body) {}
    static int words(String text){return (int)text.codePoints().filter(c->!Character.isWhitespace(c)).count();}
    static String section(String text,String heading){int start=text.indexOf(heading+"\n");if(start<0)throw new IllegalArgumentException("Missing heading: "+heading);start+=heading.length()+1;int end=text.indexOf("\n## ",start);return text.substring(start,end<0?text.length():end).trim();}
    static long insert(Connection c,String sql,Object... values)throws SQLException{
        try(var p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();try(var r=p.getGeneratedKeys()){if(!r.next())throw new SQLException("No generated ID");return r.getLong(1);}}
    }
    static void execute(Connection c,String sql,Object... values)throws SQLException{try(var p=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();}}
    static long outline(Connection c,long book,Long parent,int type,String title,String notes,Long chapter,int sort)throws SQLException{
        return insert(c,"INSERT INTO novel_outline(novel_id,parent_id,node_type,title,content,chapter_id,sort) VALUES(?,?,?,?,?,?,?)",book,parent,type,title,notes,chapter,sort);
    }
    static void verify(Connection c,long book,List<Part> parts,String bookNotes,String volumeTitle,String volumeNotes)throws SQLException{
        long root,volume;
        try(var p=c.prepareStatement("SELECT id,content FROM novel_outline WHERE novel_id=? AND parent_id IS NULL AND node_type=1 AND deleted=0")){p.setLong(1,book);try(var r=p.executeQuery()){if(!r.next()||!bookNotes.equals(r.getString(2)))throw new SQLException("Root notes mismatch");root=r.getLong(1);if(r.next())throw new SQLException("Duplicate root");}}
        try(var p=c.prepareStatement("SELECT id,title,content FROM novel_outline WHERE novel_id=? AND parent_id=? AND node_type=2 AND deleted=0")){p.setLong(1,book);p.setLong(2,root);try(var r=p.executeQuery()){if(!r.next()||!volumeTitle.equals(r.getString(2))||!volumeNotes.equals(r.getString(3)))throw new SQLException("Volume notes mismatch");volume=r.getLong(1);if(r.next())throw new SQLException("Duplicate volume");}}
        try(var p=c.prepareStatement("SELECT ch.chapter_no,ch.title,ch.word_count,cc.content,o.content,o.sort,ch.status FROM chapter ch JOIN chapter_content cc ON cc.chapter_id=ch.id JOIN novel_outline o ON o.chapter_id=ch.id AND o.novel_id=ch.novel_id AND o.parent_id=? AND o.node_type=3 AND o.deleted=0 WHERE ch.novel_id=? AND ch.deleted=0 ORDER BY ch.chapter_no")){p.setLong(1,volume);p.setLong(2,book);try(var r=p.executeQuery()){int i=0;while(r.next()){if(i>=parts.size())throw new SQLException("Extra chapter binding");var part=parts.get(i++);if(r.getInt(1)!=part.no()||!r.getString(2).equals(part.title())||r.getInt(3)!=words(part.body())||!r.getString(4).equals(part.body())||!r.getString(5).equals(part.notes())||r.getInt(6)!=part.no()-1||r.getInt(7)!=0)throw new SQLException("Chapter body/notes/version binding mismatch "+part.no());}if(i!=10)throw new SQLException("Missing chapter");}}
        System.out.println("Verified persisted notes, all ten bodies, chapter order and draft bindings");
    }
    public static void main(String[] args)throws Exception{
        String text=Files.readString(Path.of(args[0]),StandardCharsets.UTF_8).replace("\r\n","\n");
        String title=text.lines().findFirst().orElseThrow().replaceFirst("^# ","").trim();
        String intro=section(text,"## 简介"),bookNotes=section(text,"## 全书剧情笔记");
        String volumeHeading=text.lines().filter(s->s.startsWith("## 第一卷：")).findFirst().orElseThrow();
        String volumeTitle=volumeHeading.substring(3),volumeNotes=section(text,volumeHeading);
        var parts=new ArrayList<Part>();
        var matcher=Pattern.compile("(?m)^## 第(\\d+)章：(.+)\\n### 剧情笔记\\n([\\s\\S]*?)\\n### 正文\\n([\\s\\S]*?)(?=\\n## |\\z)").matcher(text);
        while(matcher.find())parts.add(new Part(Integer.parseInt(matcher.group(1)),matcher.group(2).trim(),matcher.group(3).trim(),matcher.group(4).trim()));
        if(parts.size()!=10||bookNotes.isBlank()||volumeNotes.isBlank())throw new IllegalArgumentException("Need ten chapters and all notes");
        int total=0;for(int i=0;i<parts.size();i++){var p=parts.get(i);int n=words(p.body());if(p.no()!=i+1||n<1350||n>1750||p.notes().isBlank()||p.notes().length()>300)throw new IllegalArgumentException("Invalid chapter "+p.no()+" chars="+n);total+=n;System.out.println("Chapter "+p.no()+": "+n+" non-whitespace characters");}
        String url=Objects.requireNonNull(System.getenv("WRITING_DB_URL")),user=Objects.requireNonNull(System.getenv("WRITING_DB_USER")),password=Objects.requireNonNull(System.getenv("WRITING_DB_PASSWORD"));
        try(var c=DriverManager.getConnection(url,user,password)){
            if(!"novel".equals(c.getCatalog()))throw new IllegalStateException("Only novel development schema is supported");
            c.setAutoCommit(false);
            try {
                long owner;try(var p=c.prepareStatement("SELECT u.id FROM sys_user u WHERE u.username='admin' AND u.status=1 AND u.deleted=0 AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.role_code='ADMIN' AND r.status=1 AND r.deleted=0) FOR UPDATE")){try(var r=p.executeQuery()){if(!r.next())throw new IllegalStateException("Active admin account not found");owner=r.getLong(1);if(r.next())throw new IllegalStateException("Ambiguous admin");}}
                long category;try(var p=c.prepareStatement("SELECT id,name FROM novel_category WHERE deleted=0 AND status=1 ORDER BY CASE WHEN name='玄幻' THEN 0 ELSE 1 END,sort,id LIMIT 1")){try(var r=p.executeQuery()){if(!r.next())throw new IllegalStateException("No enabled category");category=r.getLong(1);System.out.println("Owner admin ID="+owner+" category="+r.getString(2));}}
                try(var p=c.prepareStatement("SELECT id FROM novel WHERE author_id=? AND title=? AND deleted=0")){p.setLong(1,owner);p.setString(2,title);try(var r=p.executeQuery()){if(r.next()){long existing=r.getLong(1);if(r.next())throw new IllegalStateException("Duplicate fixture title");verify(c,existing,parts,bookNotes,volumeTitle,volumeNotes);System.out.println("Existing fixture novel ID="+existing+"; no writes performed");c.rollback();return;}}}
                if(args.length<2||!args[1].equals("apply")){System.out.println("Validated fixture: chapters=10 characters="+total+"; dry run, no writes");c.rollback();return;}
                long book=insert(c,"INSERT INTO novel(title,author_id,category_id,intro,tags,status,is_finish,word_count,chapter_count) VALUES(?,?,?,?,?,0,0,0,0)",title,owner,category,intro,"原创测试,悬疑,玄幻,记忆,写作Agent");
                long root=outline(c,book,null,1,"全书大纲",bookNotes,null,0),volume=outline(c,book,root,2,volumeTitle,volumeNotes,null,0);
                long firstNode=0,lastNode=0;
                for(var part:parts){long chapter=insert(c,"INSERT INTO chapter(novel_id,chapter_no,title,word_count,status,is_free,content_version) VALUES(?,?,?,?,0,1,1)",book,part.no(),part.title(),words(part.body()));execute(c,"INSERT INTO chapter_content(chapter_id,content) VALUES(?,?)",chapter,part.body());long node=outline(c,book,volume,3,part.title(),part.notes(),chapter,part.no()-1);if(firstNode==0)firstNode=node;lastNode=node;}
                // Published-only summary fields deliberately remain zero for author-private drafts.
                try(var p=c.prepareStatement("SELECT COUNT(*),SUM(ch.word_count),COUNT(o.id) FROM chapter ch JOIN chapter_content cc ON cc.chapter_id=ch.id LEFT JOIN novel_outline o ON o.chapter_id=ch.id AND o.novel_id=ch.novel_id AND o.parent_id=? AND o.node_type=3 AND o.deleted=0 WHERE ch.novel_id=? AND ch.deleted=0 AND ch.status=0")){p.setLong(1,volume);p.setLong(2,book);try(var r=p.executeQuery()){r.next();if(r.getInt(1)!=10||r.getInt(2)!=total||r.getInt(3)!=10)throw new SQLException("Fixture verification failed");}}
                verify(c,book,parts,bookNotes,volumeTitle,volumeNotes);
                c.commit();
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
                System.out.println("COMMITTED novelId="+book+" ownerId="+owner+" rootId="+root+" volumeId="+volume+" firstNodeId="+firstNode+" lastNodeId="+lastNode+" chapterCount=10 totalChars="+total+" sourceSha256="+hash);
            }catch(Exception e){c.rollback();throw e;}
        }
    }
}
