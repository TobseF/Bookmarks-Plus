package de.bookmarksplus.data

import app.cash.sqldelight.db.SqlDriver

/**
 * Triggers for the sync change log (`sync_change`). They live outside the SQLDelight schema because its
 * grammar does not resolve `NEW`/`OLD` in triggers; they are recreated on every open and so always up to date.
 * While sync changes are being applied (setting `sync.applying`), they log nothing.
 * During sync, placements and tag assignments belong to the bookmark entry – their triggers also mark the bookmark.
 */
object SyncTriggers {
    private val NAMES = listOf("placement_uuid", "sync_folder_ins", "sync_folder_upd", "sync_folder_del", "sync_bookmark_ins", "sync_bookmark_upd", "sync_bookmark_del", "sync_placement_ins", "sync_placement_upd", "sync_placement_del", "sync_tag_ins", "sync_tag_upd", "sync_tag_del", "sync_bt_ins", "sync_bt_del", "sync_icon_ins", "sync_setting_ins", "sync_setting_upd", "sync_i18n_ins", "sync_i18n_upd", "sync_i18n_del")

    private val TRIGGERS = listOf(
        """CREATE TRIGGER placement_uuid AFTER INSERT ON placement WHEN NEW.uuid IS NULL
BEGIN
    UPDATE placement SET uuid = lower(hex(randomblob(16))) WHERE id = NEW.id;
END;""",
        """CREATE TRIGGER sync_folder_ins AFTER INSERT ON folder WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('folder', CASE WHEN NEW.parent_id IS NULL THEN '@root' ELSE NEW.uuid END, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
END;""",
        """CREATE TRIGGER sync_folder_upd AFTER UPDATE ON folder WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('folder', CASE WHEN NEW.parent_id IS NULL THEN '@root' ELSE NEW.uuid END, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
END;""",
        """CREATE TRIGGER sync_folder_del AFTER DELETE ON folder WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying') AND OLD.parent_id IS NOT NULL
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('folder', OLD.uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 1, 1);
END;""",
        """CREATE TRIGGER sync_bookmark_ins AFTER INSERT ON bookmark WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('bookmark', NEW.uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
END;""",
        """CREATE TRIGGER sync_bookmark_upd AFTER UPDATE ON bookmark WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('bookmark', NEW.uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
END;""",
        """CREATE TRIGGER sync_bookmark_del AFTER DELETE ON bookmark WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('bookmark', OLD.uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 1, 1);
END;""",
        """CREATE TRIGGER sync_placement_ins AFTER INSERT ON placement WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying') AND NEW.uuid IS NOT NULL
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('placement', NEW.uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) SELECT 'bookmark', uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1 FROM bookmark WHERE id = NEW.bookmark_id;
END;""",
        """CREATE TRIGGER sync_placement_upd AFTER UPDATE ON placement WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying') AND NEW.uuid IS NOT NULL
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('placement', NEW.uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) SELECT 'bookmark', uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1 FROM bookmark WHERE id = NEW.bookmark_id;
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) SELECT 'bookmark', uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1 FROM bookmark WHERE id = OLD.bookmark_id AND OLD.bookmark_id <> NEW.bookmark_id;
END;""",
        """CREATE TRIGGER sync_placement_del AFTER DELETE ON placement WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying') AND OLD.uuid IS NOT NULL
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('placement', OLD.uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 1, 1);
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) SELECT 'bookmark', uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1 FROM bookmark WHERE id = OLD.bookmark_id;
END;""",
        """CREATE TRIGGER sync_tag_ins AFTER INSERT ON tag WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('tag', lower(NEW.name), CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
END;""",
        """CREATE TRIGGER sync_tag_upd AFTER UPDATE ON tag WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('tag', lower(NEW.name), CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) SELECT 'tag', lower(OLD.name), CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 1, 1 WHERE lower(OLD.name) <> lower(NEW.name);
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) SELECT 'bookmark_tag', b.uuid || '|' || lower(NEW.name), CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1 FROM bookmark_tag bt JOIN bookmark b ON b.id = bt.bookmark_id WHERE bt.tag_id = NEW.id AND lower(OLD.name) <> lower(NEW.name);
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) SELECT 'bookmark', b.uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1 FROM bookmark_tag bt JOIN bookmark b ON b.id = bt.bookmark_id WHERE bt.tag_id = NEW.id AND OLD.name <> NEW.name;
END;""",
        """CREATE TRIGGER sync_tag_del AFTER DELETE ON tag WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('tag', lower(OLD.name), CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 1, 1);
END;""",
        """CREATE TRIGGER sync_bt_ins AFTER INSERT ON bookmark_tag WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying') AND (SELECT uuid FROM bookmark WHERE id = NEW.bookmark_id) || '|' || (SELECT lower(name) FROM tag WHERE id = NEW.tag_id) IS NOT NULL
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('bookmark_tag', (SELECT uuid FROM bookmark WHERE id = NEW.bookmark_id) || '|' || (SELECT lower(name) FROM tag WHERE id = NEW.tag_id), CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) SELECT 'bookmark', uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1 FROM bookmark WHERE id = NEW.bookmark_id;
END;""",
        """CREATE TRIGGER sync_bt_del AFTER DELETE ON bookmark_tag WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying') AND (SELECT uuid FROM bookmark WHERE id = OLD.bookmark_id) || '|' || (SELECT lower(name) FROM tag WHERE id = OLD.tag_id) IS NOT NULL
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('bookmark_tag', (SELECT uuid FROM bookmark WHERE id = OLD.bookmark_id) || '|' || (SELECT lower(name) FROM tag WHERE id = OLD.tag_id), CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 1, 1);
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) SELECT 'bookmark', uuid, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1 FROM bookmark WHERE id = OLD.bookmark_id;
END;""",
        """CREATE TRIGGER sync_icon_ins AFTER INSERT ON icon WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('icon', NEW.hash, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
END;""",
        """CREATE TRIGGER sync_setting_ins AFTER INSERT ON setting WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying') AND NEW.key IN ('savedSearches')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('setting', NEW.key, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
END;""",
        """CREATE TRIGGER sync_setting_upd AFTER UPDATE ON setting WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying') AND NEW.key IN ('savedSearches')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('setting', NEW.key, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
END;""",
        """CREATE TRIGGER sync_i18n_ins AFTER INSERT ON tag_i18n WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('tag_i18n', lower(NEW.name), CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
END;""",
        """CREATE TRIGGER sync_i18n_upd AFTER UPDATE ON tag_i18n WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('tag_i18n', lower(NEW.name), CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 0, 1);
END;""",
        """CREATE TRIGGER sync_i18n_del AFTER DELETE ON tag_i18n WHEN NOT EXISTS (SELECT 1 FROM setting WHERE key = 'sync.applying')
BEGIN
    INSERT OR REPLACE INTO sync_change(entity, key, changed_at, deleted, pending) VALUES ('tag_i18n', lower(OLD.name), CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), 1, 1);
END;""",
    )

    fun install(driver: SqlDriver) {
        NAMES.forEach { driver.execute(null, "DROP TRIGGER IF EXISTS $it", 0) }
        TRIGGERS.forEach { driver.execute(null, it, 0) }
        // placements from older versions without UUID
        driver.execute(null, "UPDATE placement SET uuid = lower(hex(randomblob(16))) WHERE uuid IS NULL", 0)
    }
}
