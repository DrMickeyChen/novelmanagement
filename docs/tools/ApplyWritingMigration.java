import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Additive, repeatable application of the checked-in writer migration. Credentials via env only. */
class ApplyWritingMigration {
    public static void main(String[] args) throws Exception {
        String url=Objects.requireNonNull(System.getenv("WRITING_DB_URL"),"WRITING_DB_URL required");
        String user=Objects.requireNonNull(System.getenv("WRITING_DB_USER"),"WRITING_DB_USER required");
        String password=Objects.requireNonNull(System.getenv("WRITING_DB_PASSWORD"),"WRITING_DB_PASSWORD required");
        boolean apply=args.length==2 && args[1].equals("apply");
        String script=Files.readString(Path.of(args[0]));
        String[] statements=script.contains("-- @statement")?script.split("(?m)^-- @statement\\s*$"):script.replaceAll("(?m)^--.*$","").split(";");
        try(var c=DriverManager.getConnection(url,user,password)) {
            if(c.getCatalog()==null || !c.getCatalog().equals("novel")) throw new IllegalStateException("Only the configured novel development schema is supported");
            for(String sql:statements) {
                sql=sql.replaceAll("(?m)^--.*$","").trim();if(sql.isBlank())continue;
                String object;boolean exists;
                if(sql.startsWith("ALTER TABLE chapter ADD COLUMN content_version")) {
                    object="chapter.content_version";
                    try(var r=c.getMetaData().getColumns(c.getCatalog(),null,"chapter","content_version")){exists=r.next();}
                } else if(sql.startsWith("ALTER TABLE chapter_ai_adoption ADD COLUMN novel_id")) {
                    object="chapter_ai_adoption.novel_id";try(var r=c.getMetaData().getColumns(c.getCatalog(),null,"chapter_ai_adoption","novel_id")){exists=r.next();}
                } else if(sql.equals("UPDATE chapter_ai_adoption a JOIN chapter c ON c.id=a.chapter_id SET a.novel_id=c.novel_id WHERE a.novel_id IS NULL")) {
                    object="chapter_ai_adoption.novel_id backfill";if(apply)try(var s=c.createStatement()){int count=s.executeUpdate(sql);System.out.println("Backfilled adoption ownership: "+count);}continue;
                } else if(sql.startsWith("ALTER TABLE writing_source_event ALTER COLUMN next_attempt_at SET DEFAULT (UTC_TIMESTAMP(3))")) {
                    object="writing_source_event.utc_defaults";try(var p=c.prepareStatement("SELECT column_default FROM information_schema.columns WHERE table_schema=? AND table_name='writing_source_event' AND column_name='next_attempt_at'")){p.setString(1,c.getCatalog());try(var r=p.executeQuery()){exists=r.next()&&Objects.toString(r.getString(1),"").toLowerCase(Locale.ROOT).contains("utc_timestamp");}}
                } else if(sql.equals("UPDATE writing_source_event SET next_attempt_at=UTC_TIMESTAMP(3),created_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE state='NEW' AND next_attempt_at>DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE)")) {
                    object="writing_source_event.utc_repair";if(apply)try(var s=c.createStatement()){System.out.println("Repaired event timestamps: "+s.executeUpdate(sql));}continue;
                } else if(sql.startsWith("CREATE TABLE ")) {
                    String prefix=sql.startsWith("CREATE TABLE IF NOT EXISTS ")?"CREATE TABLE IF NOT EXISTS ":"CREATE TABLE ";
                    object=sql.substring(prefix.length()).split("[ (]",2)[0];
                    if(!Set.of("writing_gate","writing_task","writing_attempt","writing_event","writing_command","chapter_ai_adoption","writing_story_entry","writing_story_revision","writing_private_index_job","writing_asset","writing_asset_revision","writing_sync_config","writing_source_event","writing_chapter_recap","writing_index_receipt","writing_cumulative_recap","writing_derivation_job","writing_draft_revision","writing_task_dependency","writing_mcp_connection","writing_mcp_audit","writing_delete_ledger","writing_assistant_settings").contains(object))throw new IllegalStateException("Unexpected migration object");
                    try(var r=c.getMetaData().getTables(c.getCatalog(),null,object,new String[]{"TABLE"})){exists=r.next();}
                } else if(sql.startsWith("CREATE TRIGGER ")) {
                    object=sql.substring("CREATE TRIGGER ".length()).split("\\s",2)[0];
                    if(!Set.of("writer_chapter_update","writer_body_insert","writer_body_update","writer_body_delete","writer_chapter_delete","writer_novel_update","writer_novel_delete").contains(object))throw new IllegalStateException("Unexpected trigger");
                    try(var p=c.prepareStatement("SELECT 1 FROM information_schema.triggers WHERE trigger_schema=? AND trigger_name=?")){p.setString(1,c.getCatalog());p.setString(2,object);try(var r=p.executeQuery()){exists=r.next();}}
                } else throw new IllegalStateException("Unexpected migration statement");
                if(object.equals("writing_source_event")&&exists&&apply){boolean fingerprint;try(var r=c.getMetaData().getColumns(c.getCatalog(),null,"writing_source_event","processed_hash")){fingerprint=r.next();}if(!fingerprint){try(var s=c.createStatement()){s.execute("ALTER TABLE writing_source_event ADD COLUMN processed_version BIGINT NULL,ADD COLUMN processed_hash CHAR(64) NULL");}System.out.println("Added source processing fingerprints");}}
                if(!exists && apply) {try(var s=c.createStatement()){s.execute(sql);}System.out.println("Created "+object);}
                else System.out.println(object+": "+(exists?"present":"missing"));
            }
        }
    }
}
