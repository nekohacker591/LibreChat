package com.librechat.app.server;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

public class LocalDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "librechat_internal.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_CONVERSATIONS = "conversations";
    public static final String TABLE_MESSAGES = "messages";
    public static final String TABLE_SETTINGS = "settings";
    public static final String TABLE_FILES = "files";

    public LocalDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_CONVERSATIONS + " (" +
                "conversation_id TEXT PRIMARY KEY, " +
                "title TEXT, " +
                "endpoint TEXT, " +
                "model TEXT, " +
                "created_at INTEGER, " +
                "updated_at INTEGER)");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_MESSAGES + " (" +
                "message_id TEXT PRIMARY KEY, " +
                "conversation_id TEXT, " +
                "parent_message_id TEXT, " +
                "sender TEXT, " +
                "text TEXT, " +
                "is_user INTEGER, " +
                "error INTEGER, " +
                "files_json TEXT, " +
                "token_count INTEGER, " +
                "metadata_json TEXT, " +
                "created_at INTEGER)");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SETTINGS + " (" +
                "key_name TEXT PRIMARY KEY, " +
                "key_value TEXT)");

        createFilesTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CONVERSATIONS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SETTINGS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_FILES);
        onCreate(db);
    }

    /**
     * Schema evolves without bumping DATABASE_VERSION: an upgrade run wipes all
     * user data (conversations, messages, keys), so new tables/columns are
     * added idempotently on open instead.
     */
    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        createFilesTable(db);
        addColumnIfMissing(db, TABLE_MESSAGES, "files_json TEXT");
        addColumnIfMissing(db, TABLE_MESSAGES, "token_count INTEGER");
        addColumnIfMissing(db, TABLE_MESSAGES, "metadata_json TEXT");
    }

    private static void createFilesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FILES + " (" +
                "file_id TEXT PRIMARY KEY, " +
                "filename TEXT, " +
                "mime TEXT, " +
                "bytes INTEGER, " +
                "path TEXT, " +
                "width INTEGER, " +
                "height INTEGER, " +
                "created_at INTEGER)");
    }

    private static void addColumnIfMissing(SQLiteDatabase db, String table, String columnDefinition) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + columnDefinition);
        } catch (Exception ignored) {
            /** Already present. */
        }
    }

    // Conversations
    public synchronized void saveConversation(String conversationId, String title, String endpoint, String model) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("conversation_id", conversationId);
        cv.put("title", title != null ? title : "New Chat");
        cv.put("endpoint", endpoint != null ? endpoint : "LLM Gateway");
        cv.put("model", model != null ? model : "gpt-4o-mini");
        long now = System.currentTimeMillis();
        cv.put("updated_at", now);

        int rows = db.update(TABLE_CONVERSATIONS, cv, "conversation_id = ?", new String[]{conversationId});
        if (rows == 0) {
            cv.put("created_at", now);
            db.insertWithOnConflict(TABLE_CONVERSATIONS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private static String formatIsoDate(long time) {
        if (time <= 0) time = System.currentTimeMillis();
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new java.util.Date(time));
    }

    public synchronized JSONArray getConversationsJson() {
        JSONArray arr = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_CONVERSATIONS, null, null, null, null, null, "updated_at DESC");
        try {
            while (c.moveToNext()) {
                JSONObject obj = new JSONObject();
                obj.put("conversationId", c.getString(c.getColumnIndexOrThrow("conversation_id")));
                obj.put("title", c.getString(c.getColumnIndexOrThrow("title")));
                obj.put("endpoint", c.getString(c.getColumnIndexOrThrow("endpoint")));
                obj.put("model", c.getString(c.getColumnIndexOrThrow("model")));
                obj.put("createdAt", formatIsoDate(c.getLong(c.getColumnIndexOrThrow("created_at"))));
                obj.put("updatedAt", formatIsoDate(c.getLong(c.getColumnIndexOrThrow("updated_at"))));
                arr.put(obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            c.close();
        }
        return arr;
    }

    public synchronized boolean deleteConversation(String conversationId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MESSAGES, "conversation_id = ?", new String[]{conversationId});
        int rows = db.delete(TABLE_CONVERSATIONS, "conversation_id = ?", new String[]{conversationId});
        return rows > 0;
    }

    public synchronized void clearAllConversations() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MESSAGES, null, null);
        db.delete(TABLE_CONVERSATIONS, null, null);
    }

    public synchronized JSONObject getConversationJson(String conversationId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_CONVERSATIONS, null, "conversation_id = ?", new String[]{conversationId}, null, null, null);
        try {
            if (c.moveToNext()) {
                JSONObject obj = new JSONObject();
                obj.put("conversationId", c.getString(c.getColumnIndexOrThrow("conversation_id")));
                obj.put("title", c.getString(c.getColumnIndexOrThrow("title")));
                obj.put("endpoint", c.getString(c.getColumnIndexOrThrow("endpoint")));
                obj.put("model", c.getString(c.getColumnIndexOrThrow("model")));
                obj.put("createdAt", formatIsoDate(c.getLong(c.getColumnIndexOrThrow("created_at"))));
                obj.put("updatedAt", formatIsoDate(c.getLong(c.getColumnIndexOrThrow("updated_at"))));
                return obj;
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            c.close();
        }
        return null;
    }

    public synchronized void updateConversationTitle(String conversationId, String title) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        cv.put("updated_at", System.currentTimeMillis());
        db.update(TABLE_CONVERSATIONS, cv, "conversation_id = ?", new String[]{conversationId});
    }

    // Messages
    public synchronized void saveMessage(String messageId, String conversationId, String parentMessageId,
                                        String sender, String text, boolean isUser, boolean error) {
        saveMessage(messageId, conversationId, parentMessageId, sender, text, isUser, error, null, 0, null);
    }

    public synchronized void saveMessage(String messageId, String conversationId, String parentMessageId,
                                        String sender, String text, boolean isUser, boolean error,
                                        String filesJson, int tokenCount, String metadataJson) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("message_id", messageId);
        cv.put("conversation_id", conversationId);
        cv.put("parent_message_id", parentMessageId);
        cv.put("sender", sender);
        cv.put("text", text);
        cv.put("is_user", isUser ? 1 : 0);
        cv.put("error", error ? 1 : 0);
        if (filesJson != null && !filesJson.isEmpty()) {
            cv.put("files_json", filesJson);
        }
        if (tokenCount > 0) {
            cv.put("token_count", tokenCount);
        }
        if (metadataJson != null && !metadataJson.isEmpty()) {
            cv.put("metadata_json", metadataJson);
        }
        cv.put("created_at", System.currentTimeMillis());
        db.insertWithOnConflict(TABLE_MESSAGES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);

        // Update conversation timestamp
        ContentValues convCv = new ContentValues();
        convCv.put("updated_at", System.currentTimeMillis());
        db.update(TABLE_CONVERSATIONS, convCv, "conversation_id = ?", new String[]{conversationId});
    }

    public synchronized JSONArray getMessagesJson(String conversationId) {
        JSONArray arr = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_MESSAGES, null, "conversation_id = ?", new String[]{conversationId},
                null, null, "created_at ASC");
        try {
            while (c.moveToNext()) {
                JSONObject obj = new JSONObject();
                obj.put("messageId", c.getString(c.getColumnIndexOrThrow("message_id")));
                obj.put("conversationId", c.getString(c.getColumnIndexOrThrow("conversation_id")));
                obj.put("parentMessageId", c.getString(c.getColumnIndexOrThrow("parent_message_id")));
                obj.put("sender", c.getString(c.getColumnIndexOrThrow("sender")));
                obj.put("text", c.getString(c.getColumnIndexOrThrow("text")));
                obj.put("isCreatedByUser", c.getInt(c.getColumnIndexOrThrow("is_user")) == 1);
                obj.put("error", c.getInt(c.getColumnIndexOrThrow("error")) == 1);
                obj.put("createdAt", formatIsoDate(c.getLong(c.getColumnIndexOrThrow("created_at"))));
                obj.put("updatedAt", formatIsoDate(c.getLong(c.getColumnIndexOrThrow("created_at"))));

                int filesIdx = c.getColumnIndex("files_json");
                if (filesIdx >= 0 && !c.isNull(filesIdx)) {
                    try {
                        obj.put("files", new JSONArray(c.getString(filesIdx)));
                    } catch (Exception ignored) {
                    }
                }
                int tokenIdx = c.getColumnIndex("token_count");
                if (tokenIdx >= 0 && !c.isNull(tokenIdx) && c.getInt(tokenIdx) > 0) {
                    obj.put("tokenCount", c.getInt(tokenIdx));
                }
                int metaIdx = c.getColumnIndex("metadata_json");
                if (metaIdx >= 0 && !c.isNull(metaIdx)) {
                    try {
                        obj.put("metadata", new JSONObject(c.getString(metaIdx)));
                    } catch (Exception ignored) {
                    }
                }
                arr.put(obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            c.close();
        }
        return arr;
    }

    // Files (user attachments kept on device)
    public synchronized void saveFile(String fileId, String filename, String mime, long bytes,
                                      String path, int width, int height) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("file_id", fileId);
        cv.put("filename", filename);
        cv.put("mime", mime);
        cv.put("bytes", bytes);
        cv.put("path", path);
        cv.put("width", width);
        cv.put("height", height);
        cv.put("created_at", System.currentTimeMillis());
        db.insertWithOnConflict(TABLE_FILES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Returns {file_id, filename, mime, bytes, path, width, height} or null. */
    public synchronized JSONObject getFileRecord(String fileId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_FILES, null, "file_id = ?", new String[]{fileId}, null, null, null);
        try {
            if (c.moveToFirst()) {
                JSONObject obj = new JSONObject();
                obj.put("file_id", c.getString(c.getColumnIndexOrThrow("file_id")));
                obj.put("filename", c.getString(c.getColumnIndexOrThrow("filename")));
                obj.put("mime", c.getString(c.getColumnIndexOrThrow("mime")));
                obj.put("bytes", c.getLong(c.getColumnIndexOrThrow("bytes")));
                obj.put("path", c.getString(c.getColumnIndexOrThrow("path")));
                obj.put("width", c.getInt(c.getColumnIndexOrThrow("width")));
                obj.put("height", c.getInt(c.getColumnIndexOrThrow("height")));
                return obj;
            }
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        return null;
    }

    public synchronized void deleteFile(String fileId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_FILES, "file_id = ?", new String[]{fileId});
    }

    // Settings / Keys
    public synchronized void setSetting(String key, String value) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("key_name", key);
        cv.put("key_value", value);
        db.insertWithOnConflict(TABLE_SETTINGS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String getSetting(String key, String defaultValue) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_SETTINGS, new String[]{"key_value"}, "key_name = ?", new String[]{key},
                null, null, null);
        try {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
        } finally {
            c.close();
        }
        return defaultValue;
    }

    public synchronized void deleteSetting(String key) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_SETTINGS, "key_name = ?", new String[]{key});
    }

    public synchronized void deleteSettingsByPrefix(String prefix) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_SETTINGS, "key_name LIKE ?", new String[]{prefix + "%"});
    }
}
