-- Statements are separated by -- @statement, not by their internal semicolons.
-- Capture all write paths, including imports and direct maintenance SQL, in the source transaction.
-- @statement
CREATE TRIGGER writer_chapter_update AFTER UPDATE ON chapter FOR EACH ROW
BEGIN
 IF NOT(NEW.content_version <=> OLD.content_version) OR NOT(NEW.deleted <=> OLD.deleted)
    OR NOT(NEW.status <=> OLD.status) OR NOT(NEW.chapter_no <=> OLD.chapter_no) OR NOT(NEW.title <=> OLD.title) THEN
  INSERT INTO writing_source_event(owner_user_id,novel_id,chapter_id,kind,source_version)
  SELECT author_id,NEW.novel_id,NEW.id,IF(NEW.deleted=1,'CHAPTER_DELETE','CHAPTER_UPDATE'),NEW.content_version FROM novel WHERE id=NEW.novel_id;
 END IF;
END
-- @statement
CREATE TRIGGER writer_body_insert AFTER INSERT ON chapter_content FOR EACH ROW
BEGIN
 INSERT INTO writing_source_event(owner_user_id,novel_id,chapter_id,kind,source_version)
 SELECT n.author_id,c.novel_id,c.id,'CHAPTER_UPDATE',c.content_version FROM chapter c JOIN novel n ON n.id=c.novel_id WHERE c.id=NEW.chapter_id AND c.deleted=0;
END
-- @statement
CREATE TRIGGER writer_body_update AFTER UPDATE ON chapter_content FOR EACH ROW
BEGIN
 IF NOT(NEW.content <=> OLD.content) THEN
  INSERT INTO writing_source_event(owner_user_id,novel_id,chapter_id,kind,source_version)
  SELECT n.author_id,c.novel_id,c.id,'CHAPTER_UPDATE',c.content_version FROM chapter c JOIN novel n ON n.id=c.novel_id WHERE c.id=NEW.chapter_id AND c.deleted=0;
 END IF;
END
-- @statement
CREATE TRIGGER writer_body_delete AFTER DELETE ON chapter_content FOR EACH ROW
BEGIN
 INSERT INTO writing_source_event(owner_user_id,novel_id,chapter_id,kind,source_version)
 SELECT n.author_id,c.novel_id,c.id,'CHAPTER_UPDATE',c.content_version FROM chapter c JOIN novel n ON n.id=c.novel_id WHERE c.id=OLD.chapter_id AND c.deleted=0;
END
-- @statement
CREATE TRIGGER writer_chapter_delete AFTER DELETE ON chapter FOR EACH ROW
BEGIN
 INSERT INTO writing_source_event(owner_user_id,novel_id,chapter_id,kind,source_version)
 SELECT author_id,OLD.novel_id,OLD.id,'CHAPTER_DELETE',OLD.content_version FROM novel WHERE id=OLD.novel_id;
END
-- @statement
CREATE TRIGGER writer_novel_update AFTER UPDATE ON novel FOR EACH ROW
BEGIN
 IF NEW.deleted=1 AND OLD.deleted=0 OR NOT(NEW.author_id <=> OLD.author_id) THEN
  INSERT INTO writing_source_event(owner_user_id,novel_id,kind) VALUES(OLD.author_id,OLD.id,'NOVEL_DELETE');
 END IF;
END
-- @statement
CREATE TRIGGER writer_novel_delete AFTER DELETE ON novel FOR EACH ROW
BEGIN
 INSERT INTO writing_source_event(owner_user_id,novel_id,kind) VALUES(OLD.author_id,OLD.id,'NOVEL_DELETE');
END
