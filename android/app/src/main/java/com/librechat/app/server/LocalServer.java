package com.librechat.app.server;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public class LocalServer extends NanoHTTPD {

    private static final String TAG = "LocalServer";
    private final Context context;
    private final LocalDatabaseHelper dbHelper;
    private final OkHttpClient httpClient;
    private JSONArray cachedModels = null;
    private long lastModelsFetchTime = 0;

    public LocalServer(Context context, int port) {
        super(port);
        this.context = context;
        this.dbHelper = new LocalDatabaseHelper(context);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public LocalDatabaseHelper getDbHelper() {
        return dbHelper;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        // Handle CORS Preflight
        if (Method.OPTIONS.equals(method)) {
            Response resp = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
            addCorsHeaders(resp);
            return resp;
        }

        try {
            // API routing
            if (uri.startsWith("/api/")) {
                Response apiResp = handleApi(session, uri, method);
                addCorsHeaders(apiResp);
                return apiResp;
            }

            // Static asset serving from assets/www
            return serveStatic(uri);
        } catch (Exception e) {
            Log.e(TAG, "Error serving request for " + uri, e);
            Response errResp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"" + e.getMessage() + "\"}");
            addCorsHeaders(errResp);
            return errResp;
        }
    }

    private Response handleApi(IHTTPSession session, String uri, Method method) throws Exception {
        Map<String, String> body = new HashMap<>();
        if (Method.POST.equals(method) || Method.PUT.equals(method)) {
            session.parseBody(body);
        }
        String postData = body.get("postData");

        // 1. Config
        if (uri.equals("/api/config")) {
            JSONObject config = new JSONObject();
            config.put("appTitle", "LibreChat");
            config.put("serverDomain", "http://127.0.0.1:" + getListeningPort());
            config.put("registrationEnabled", false);
            config.put("socialLogins", new JSONArray());

            JSONObject endpoints = new JSONObject();

            JSONObject llmGateway = new JSONObject();
            llmGateway.put("type", "custom");
            llmGateway.put("userProvide", true);
            llmGateway.put("modelDisplayLabel", "LLM Gateway");
            endpoints.put("LLM Gateway", llmGateway);

            JSONObject devPass = new JSONObject();
            devPass.put("type", "custom");
            devPass.put("userProvide", true);
            devPass.put("modelDisplayLabel", "DevPass");
            endpoints.put("DevPass", devPass);

            config.put("endpoints", endpoints);

            return newFixedLengthResponse(Response.Status.OK, "application/json", config.toString());
        }

        // 2. Endpoints
        if (uri.equals("/api/endpoints")) {
            JSONObject endpoints = new JSONObject();

            JSONObject llmGateway = new JSONObject();
            llmGateway.put("type", "custom");
            llmGateway.put("userProvide", true);
            llmGateway.put("modelDisplayLabel", "LLM Gateway");
            endpoints.put("LLM Gateway", llmGateway);

            JSONObject devPass = new JSONObject();
            devPass.put("type", "custom");
            devPass.put("userProvide", true);
            devPass.put("modelDisplayLabel", "DevPass");
            endpoints.put("DevPass", devPass);

            return newFixedLengthResponse(Response.Status.OK, "application/json", endpoints.toString());
        }

        // 3. Models
        if (uri.equals("/api/models")) {
            JSONArray modelsList = fetchModelsFromGateway();
            JSONObject modelsObj = new JSONObject();
            modelsObj.put("LLM Gateway", modelsList);
            modelsObj.put("DevPass", modelsList);
            return newFixedLengthResponse(Response.Status.OK, "application/json", modelsObj.toString());
        }

        // 4. User profile
        if (uri.equals("/api/user")) {
            JSONObject user = new JSONObject();
            user.put("id", "local-user");
            user.put("_id", "local-user");
            user.put("name", "Local User");
            user.put("username", "user");
            user.put("email", "user@librechat.local");
            user.put("role", "USER");
            user.put("plugins", new JSONArray());
            return newFixedLengthResponse(Response.Status.OK, "application/json", user.toString());
        }

        // 5. Conversations
        if (uri.equals("/api/convos")) {
            if (Method.GET.equals(method)) {
                JSONObject res = new JSONObject();
                res.put("conversations", dbHelper.getConversationsJson());
                res.put("pages", 1);
                res.put("pageNumber", 1);
                return newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
            }
        }

        // 6. Delete conversation
        if (uri.startsWith("/api/convos/") && Method.DELETE.equals(method)) {
            String convoId = uri.substring("/api/convos/".length());
            dbHelper.deleteConversation(convoId);
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"message\":\"Deleted\"}");
        }

        // 7. Messages
        if (uri.startsWith("/api/messages/")) {
            String convoId = uri.substring("/api/messages/".length());
            JSONObject res = new JSONObject();
            res.put("messages", dbHelper.getMessagesJson(convoId));
            return newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
        }

        // 8. Keys
        if (uri.startsWith("/api/keys")) {
            if (Method.POST.equals(method) && postData != null) {
                try {
                    JSONObject keyObj = new JSONObject(postData);
                    String endpoint = keyObj.optString("endpoint", "LLM Gateway");
                    String key = keyObj.optString("apiKey", "");
                    if (keyObj.has("key")) {
                        key = keyObj.optString("key", "");
                    }
                    dbHelper.setSetting("key_" + endpoint, key);
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"message\":\"Key saved\"}");
                } catch (Exception e) {
                    Log.e(TAG, "Error saving key", e);
                }
            } else if (Method.GET.equals(method)) {
                JSONObject keys = new JSONObject();
                keys.put("LLM Gateway", dbHelper.getSetting("key_LLM Gateway", "").isEmpty() ? false : "valid");
                keys.put("DevPass", dbHelper.getSetting("key_DevPass", "").isEmpty() ? false : "valid");
                return newFixedLengthResponse(Response.Status.OK, "application/json", keys.toString());
            }
        }

        // 9. Chat Streaming Proxy (/api/ask/custom or /api/ask or /api/chat/completions)
        if (uri.startsWith("/api/ask") || uri.startsWith("/api/chat")) {
            return handleChatStream(postData);
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", "{}");
    }

    private Response handleChatStream(String postData) {
        try {
            JSONObject reqJson = new JSONObject(postData != null ? postData : "{}");
            String endpoint = reqJson.optString("endpoint", "LLM Gateway");
            String model = reqJson.optString("model", "gpt-4o-mini");
            String prompt = reqJson.optString("text", "");
            String conversationId = reqJson.optString("conversationId", UUID.randomUUID().toString());
            String parentMessageId = reqJson.optString("parentMessageId", "00000000-0000-0000-0000-000000000000");
            String messageId = UUID.randomUUID().toString();

            // Save user message
            dbHelper.saveConversation(conversationId, prompt.length() > 30 ? prompt.substring(0, 30) + "..." : prompt,
                    endpoint, model);
            dbHelper.saveMessage(UUID.randomUUID().toString(), conversationId, parentMessageId, "User", prompt, true, false);

            // Determine API Token
            String token = reqJson.optString("apiKey", "");
            if (token.isEmpty()) {
                token = dbHelper.getSetting("key_" + endpoint, "");
            }
            if (token.isEmpty()) {
                token = dbHelper.getSetting("key_DevPass", "");
            }
            if (token.isEmpty()) {
                token = dbHelper.getSetting("key_LLM Gateway", "");
            }

            // Prepare outbound OpenAI-compatible Chat Completion Payload
            JSONObject outboundPayload = new JSONObject();
            outboundPayload.put("model", model);
            outboundPayload.put("stream", true);

            JSONArray messages = new JSONArray();
            // System message
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", "You are a helpful AI assistant.");
            messages.put(sysMsg);

            // User message
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.put(userMsg);

            outboundPayload.put("messages", messages);

            Request.Builder reqBuilder = new Request.Builder()
                    .url("https://api.llmgateway.io/v1/chat/completions")
                    .addHeader("x-source", "devpass-code")
                    .post(RequestBody.create(MediaType.parse("application/json"), outboundPayload.toString()));

            if (!token.isEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer " + token);
            }

            PipedInputStream in = new PipedInputStream();
            PipedOutputStream out = new PipedOutputStream(in);

            new Thread(() -> {
                StringBuilder fullResponse = new StringBuilder();
                try (okhttp3.Response okResp = httpClient.newCall(reqBuilder.build()).execute()) {
                    if (!okResp.isSuccessful() || okResp.body() == null) {
                        String errMsg = "Error from LLM Gateway: " + okResp.code() + " " + okResp.message();
                        if (okResp.code() == 401) {
                            errMsg = "Invalid API Token or Key Required. Please set your token in Settings -> Provider Keys.";
                        }
                        String sseErr = "event: message\ndata: " + new JSONObject()
                                .put("text", errMsg)
                                .put("messageId", messageId)
                                .put("conversationId", conversationId)
                                .put("error", true)
                                .toString() + "\n\n";
                        out.write(sseErr.getBytes(StandardCharsets.UTF_8));
                        out.write("event: error\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        out.close();
                        return;
                    }

                    ResponseBody rb = okResp.body();
                    InputStream is = rb.byteStream();
                    byte[] buffer = new byte[1024];
                    int read;
                    StringBuilder lineBuffer = new StringBuilder();

                    while ((read = is.read(buffer)) != -1) {
                        String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                        lineBuffer.append(chunk);

                        int newlineIndex;
                        while ((newlineIndex = lineBuffer.indexOf("\n")) != -1) {
                            String line = lineBuffer.substring(0, newlineIndex).trim();
                            lineBuffer.delete(0, newlineIndex + 1);

                            if (line.startsWith("data: ")) {
                                String dataStr = line.substring(6).trim();
                                if (dataStr.equals("[DONE]")) {
                                    continue;
                                }
                                try {
                                    JSONObject deltaObj = new JSONObject(dataStr);
                                    JSONArray choices = deltaObj.optJSONArray("choices");
                                    if (choices != null && choices.length() > 0) {
                                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                                        if (delta != null && delta.has("content")) {
                                            String content = delta.getString("content");
                                            fullResponse.append(content);

                                            JSONObject sseData = new JSONObject();
                                            sseData.put("text", fullResponse.toString());
                                            sseData.put("messageId", messageId);
                                            sseData.put("conversationId", conversationId);
                                            sseData.put("sender", model);

                                            String sseMsg = "event: message\ndata: " + sseData.toString() + "\n\n";
                                            out.write(sseMsg.getBytes(StandardCharsets.UTF_8));
                                            out.flush();
                                        }
                                    }
                                } catch (Exception parseErr) {
                                    // ignore unparseable lines
                                }
                            }
                        }
                    }

                    // Save assistant message to local database
                    dbHelper.saveMessage(messageId, conversationId, parentMessageId, model, fullResponse.toString(), false, false);

                    out.write("event: message\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    out.close();
                } catch (Exception streamErr) {
                    Log.e(TAG, "Streaming error", streamErr);
                    try {
                        String sseErr = "event: message\ndata: " + new JSONObject()
                                .put("text", "Streaming error: " + streamErr.getMessage())
                                .put("messageId", messageId)
                                .put("conversationId", conversationId)
                                .put("error", true)
                                .toString() + "\n\n";
                        out.write(sseErr.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        out.close();
                    } catch (Exception ignored) {}
                }
            }).start();

            Response streamResp = newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", in);
            streamResp.addHeader("Cache-Control", "no-cache");
            streamResp.addHeader("Connection", "keep-alive");
            return streamResp;

        } catch (Exception e) {
            Log.e(TAG, "Chat handling error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private JSONArray fetchModelsFromGateway() {
        long now = System.currentTimeMillis();
        if (cachedModels != null && (now - lastModelsFetchTime) < 300000) { // 5 min cache
            return cachedModels;
        }

        JSONArray result = new JSONArray();
        try {
            Request req = new Request.Builder()
                    .url("https://api.llmgateway.io/v1/models")
                    .addHeader("x-source", "devpass-code")
                    .get()
                    .build();

            try (okhttp3.Response resp = httpClient.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    String json = resp.body().string();
                    JSONObject parsed = new JSONObject(json);
                    JSONArray data = parsed.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject m = data.getJSONObject(i);
                            result.put(m.optString("id"));
                        }
                        cachedModels = result;
                        lastModelsFetchTime = now;
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching models from gateway", e);
        }

        // Fallbacks
        result.put("gpt-4o");
        result.put("gpt-4o-mini");
        result.put("claude-sonnet-4-5");
        result.put("o1");
        result.put("o3-mini");
        return result;
    }

    private Response serveStatic(String uri) {
        if (uri.equals("/") || uri.isEmpty()) {
            uri = "/index.html";
        }
        String assetPath = "www" + uri;
        AssetManager am = context.getAssets();

        try {
            InputStream is = am.open(assetPath);
            String mime = resolveMimeType(uri);
            Response resp = newChunkedResponse(Response.Status.OK, mime, is);
            addCorsHeaders(resp);
            return resp;
        } catch (IOException e) {
            // SPA fallback: return index.html for unknown frontend routes
            try {
                InputStream is = am.open("www/index.html");
                Response resp = newChunkedResponse(Response.Status.OK, "text/html", is);
                addCorsHeaders(resp);
                return resp;
            } catch (IOException fallbackErr) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found: " + uri);
            }
        }
    }

    private String resolveMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json") || lower.endsWith(".webmanifest")) return "application/json";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    private void addCorsHeaders(Response resp) {
        resp.addHeader("Access-Control-Allow-Origin", "*");
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, x-source, x-api-key");
    }
}
