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
                "created_at INTEGER)");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SETTINGS + " (" +
                "key_name TEXT PRIMARY KEY, " +
                "key_value TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CONVERSATIONS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SETTINGS);
        onCreate(db);
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
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("message_id", messageId);
        cv.put("conversation_id", conversationId);
        cv.put("parent_message_id", parentMessageId);
        cv.put("sender", sender);
        cv.put("text", text);
        cv.put("is_user", isUser ? 1 : 0);
        cv.put("error", error ? 1 : 0);
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
                arr.put(obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            c.close();
        }
        return arr;
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
}
