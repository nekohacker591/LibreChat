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
import java.net.URLDecoder;
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

    public static class ActiveGeneration {
        final String streamId;
        final String conversationId;
        final String userMessageId;
        final String responseMessageId;
        final String parentMessageId;
        final String model;
        final String endpoint;
        final String prompt;
        final String apiKey;
        final long createdAt;
        volatile boolean aborted = false;
        volatile Call activeCall = null;
        volatile PipedOutputStream streamOut = null;

        public ActiveGeneration(String streamId, String conversationId, String userMessageId,
                                String responseMessageId, String parentMessageId,
                                String model, String endpoint, String prompt, String apiKey, long createdAt) {
            this.streamId = streamId;
            this.conversationId = conversationId;
            this.userMessageId = userMessageId;
            this.responseMessageId = responseMessageId;
            this.parentMessageId = parentMessageId;
            this.model = model;
            this.endpoint = endpoint;
            this.prompt = prompt;
            this.apiKey = apiKey;
            this.createdAt = createdAt;
        }
    }

    private final ConcurrentHashMap<String, ActiveGeneration> activeGenerations = new ConcurrentHashMap<>();

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
        if (Method.POST.equals(method) || Method.PUT.equals(method) || Method.PATCH.equals(method)) {
            session.parseBody(body);
        }
        String postData = body.get("postData");

        // 1. Config
        if (uri.equals("/api/config")) {
            JSONObject config = new JSONObject();
            config.put("appTitle", "LibreChat");
            config.put("serverDomain", "http://127.0.0.1:" + getListeningPort());
            config.put("emailLoginEnabled", false);
            config.put("registrationEnabled", false);
            config.put("socialLoginEnabled", false);
            config.put("socialLogins", new JSONArray());
            config.put("sharedLinksEnabled", false);
            config.put("publicSharedLinksEnabled", false);
            config.put("openidLoginEnabled", false);
            config.put("samlLoginEnabled", false);
            config.put("passwordResetEnabled", false);

            JSONObject interfaceObj = new JSONObject();
            interfaceObj.put("endpointsMenu", true);
            interfaceObj.put("modelSelect", true);
            interfaceObj.put("parameters", true);
            interfaceObj.put("sidePanel", true);
            interfaceObj.put("presets", true);
            interfaceObj.put("prompts", true);
            interfaceObj.put("bookmarks", true);
            interfaceObj.put("multiConvo", false);
            config.put("interface", interfaceObj);

            JSONObject modelSpecs = new JSONObject();
            modelSpecs.put("list", new JSONArray());
            config.put("modelSpecs", modelSpecs);

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

        // 2. Auth: Refresh, Login, Logout
        if (uri.equals("/api/auth/refresh") || uri.equals("/api/auth/login")) {
            JSONObject authResp = new JSONObject();
            authResp.put("token", "local-session-token-librechat");
            JSONObject user = new JSONObject();
            user.put("id", "local-user");
            user.put("_id", "local-user");
            user.put("name", "Local User");
            user.put("username", "user");
            user.put("email", "user@librechat.local");
            user.put("role", "USER");
            user.put("plugins", new JSONArray());
            authResp.put("user", user);
            return newFixedLengthResponse(Response.Status.OK, "application/json", authResp.toString());
        }
        if (uri.equals("/api/auth/logout")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"message\":\"Logged out\"}");
        }

        // 3. User profile
        if (uri.equals("/api/user")) {
            JSONObject user = new JSONObject();
            user.put("id", "local-user");
            user.put("_id", "local-user");
            user.put("name", "Local User");
            user.put("username", "user");
            user.put("email", "user@librechat.local");
            user.put("avatar", "");
            user.put("role", "USER");
            user.put("provider", "local");
            user.put("plugins", new JSONArray());
            return newFixedLengthResponse(Response.Status.OK, "application/json", user.toString());
        }

        // 3b. User Settings & Preferences
        if (uri.startsWith("/api/user/settings") || uri.startsWith("/api/favorites")) {
            if (Method.POST.equals(method) || Method.PUT.equals(method) || Method.PATCH.equals(method)) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", "[]");
            }
            if (uri.contains("pinned-order") || uri.contains("favorites") || uri.contains("tool")) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", "[]");
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{}");
        }

        // 4. Endpoints
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

        // 5. Models
        if (uri.equals("/api/models")) {
            JSONArray modelsList = fetchModelsFromGateway();
            JSONObject modelsObj = new JSONObject();
            modelsObj.put("LLM Gateway", modelsList);
            modelsObj.put("DevPass", modelsList);
            return newFixedLengthResponse(Response.Status.OK, "application/json", modelsObj.toString());
        }

        // 6. Presets
        if (uri.equals("/api/presets")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "[]");
        }

        // 7. Prompts
        if (uri.startsWith("/api/prompts")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "[]");
        }

        // 8. Banner
        if (uri.equals("/api/banner")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"display\":false}");
        }

        // 9. Balance
        if (uri.equals("/api/balance")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"balance\":\"0\"}");
        }

        // 10. Search enabled
        if (uri.equals("/api/search/enable")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "false");
        }

        // 11. Plugins
        if (uri.startsWith("/api/plugins") || uri.equals("/api/user/plugins")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "[]");
        }

        // 12. Actions
        if (uri.startsWith("/api/actions")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "[]");
        }

        // ----------------------------------------------------
        // RESUMABLE GENERATION PROTOCOL (Chat Routes)
        // MUST BE EVALUATED BEFORE GENERIC /api/agents HANDLER
        // ----------------------------------------------------

        // A. Abort running generation
        if (uri.equals("/api/agents/chat/abort") && Method.POST.equals(method)) {
            JSONObject abortReq = new JSONObject(postData != null ? postData : "{}");
            String streamId = abortReq.optString("streamId", "");
            String convoId = abortReq.optString("conversationId", "");
            ActiveGeneration gen = null;
            if (!streamId.isEmpty()) {
                gen = activeGenerations.get(streamId);
            }
            if (gen == null && !convoId.isEmpty()) {
                for (ActiveGeneration g : activeGenerations.values()) {
                    if (convoId.equals(g.conversationId) && !g.aborted) {
                        gen = g;
                        break;
                    }
                }
            }
            if (gen != null) {
                gen.aborted = true;
                if (gen.activeCall != null) {
                    gen.activeCall.cancel();
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true,\"aborted\":true}");
        }

        // B. Stream status
        if (uri.startsWith("/api/agents/chat/status/") && Method.GET.equals(method)) {
            String convoId = uri.substring("/api/agents/chat/status/".length());
            try {
                convoId = URLDecoder.decode(convoId, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {}
            ActiveGeneration active = null;
            for (ActiveGeneration g : activeGenerations.values()) {
                if (convoId.equals(g.conversationId) && !g.aborted) {
                    active = g;
                    break;
                }
            }
            JSONObject statusResp = new JSONObject();
            if (active != null) {
                statusResp.put("active", true);
                statusResp.put("status", "running");
                statusResp.put("streamId", active.streamId);
                statusResp.put("createdAt", active.createdAt);
                statusResp.put("generationProtocolVersion", 2);
            } else {
                statusResp.put("active", false);
                statusResp.put("generationProtocolVersion", 2);
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", statusResp.toString());
        }

        // C. SSE Stream Connection
        if (uri.startsWith("/api/agents/chat/stream/") && Method.GET.equals(method)) {
            return handleStreamConnection(uri);
        }

        // D. Start generation (POST /api/agents/chat/:endpoint)
        if (uri.startsWith("/api/agents/chat/") && Method.POST.equals(method)) {
            return handleStartGeneration(uri, postData);
        }

        // E. Legacy ask / chat routes
        if (uri.startsWith("/api/ask") || uri.startsWith("/api/chat")) {
            return handleChatStream(postData);
        }

        // ----------------------------------------------------
        // Generic Agents & Assistants (fallback after chat routes)
        // ----------------------------------------------------
        if (uri.startsWith("/api/agents") || uri.startsWith("/api/assistants")) {
            if (uri.contains("/active")) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"activeJobIds\":[]}");
            }
            if (uri.contains("/categories") || uri.contains("/tools")) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", "[]");
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"object\":\"list\",\"data\":[],\"has_more\":false,\"first_id\":null,\"last_id\":null}");
        }

        // 13b. Title Generation (/api/convos/gen_title/:conversationId)
        if (uri.startsWith("/api/convos/gen_title/")) {
            String convoId = uri.substring("/api/convos/gen_title/".length());
            try {
                convoId = URLDecoder.decode(convoId, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {}
            JSONObject convo = dbHelper.getConversationJson(convoId);
            JSONObject res = new JSONObject();
            res.put("title", convo != null ? convo.optString("title", "New Chat") : "New Chat");
            return newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
        }

        // 13c. Update Conversation (/api/convos/update)
        if (uri.equals("/api/convos/update") || uri.startsWith("/api/convos/update")) {
            if (postData != null) {
                try {
                    JSONObject updateReq = new JSONObject(postData);
                    JSONObject arg = updateReq.optJSONObject("arg");
                    if (arg != null) {
                        String cid = arg.optString("conversationId", "");
                        String newTitle = arg.optString("title", "");
                        if (!cid.isEmpty() && !newTitle.isEmpty()) {
                            dbHelper.updateConversationTitle(cid, newTitle);
                        }
                    }
                } catch (Exception ignored) {}
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}");
        }

        // 14. Files
        if (uri.startsWith("/api/files")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "[]");
        }

        // 14b. Projects
        if (uri.startsWith("/api/projects")) {
            JSONObject res = new JSONObject();
            res.put("projects", new JSONArray());
            res.put("nextCursor", JSONObject.NULL);
            return newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
        }

        // 14c. Skills
        if (uri.startsWith("/api/skills")) {
            JSONObject res = new JSONObject();
            res.put("skills", new JSONArray());
            res.put("has_more", false);
            res.put("after", JSONObject.NULL);
            return newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
        }

        // 14d. Schedules
        if (uri.startsWith("/api/schedules")) {
            JSONObject res = new JSONObject();
            res.put("schedules", new JSONArray());
            return newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
        }

        // 14e. Share
        if (uri.startsWith("/api/share")) {
            JSONObject res = new JSONObject();
            res.put("links", new JSONArray());
            res.put("nextCursor", JSONObject.NULL);
            res.put("hasNextPage", false);
            return newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
        }

        // 14f. MCP
        if (uri.startsWith("/api/mcp")) {
            if (uri.contains("/tools")) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"servers\":{}}");
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{}");
        }

        // 14g. Categories, Tags, Code Environments
        if (uri.startsWith("/api/categories") || uri.startsWith("/api/tags") || uri.startsWith("/api/code-environments")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "[]");
        }

        // 14h. Token config
        if (uri.equals("/api/endpoints/token-config")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{}");
        }

        // 15. Tokenizer
        if (uri.equals("/api/tokenizer")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"count\":1}");
        }

        // 16. Conversations List
        if (uri.equals("/api/convos") || uri.equals("/api/convos/")) {
            if (Method.GET.equals(method)) {
                JSONObject res = new JSONObject();
                res.put("conversations", dbHelper.getConversationsJson());
                res.put("pages", 1);
                res.put("pageNumber", 1);
                res.put("pageSize", 25);
                return newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
            }
        }

        // 17. Single Conversation (GET or DELETE /api/convos/:id)
        if (uri.startsWith("/api/convos/")) {
            String convoId = uri.substring("/api/convos/".length());
            try {
                convoId = URLDecoder.decode(convoId, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {}
            if (Method.DELETE.equals(method)) {
                dbHelper.deleteConversation(convoId);
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"message\":\"Deleted\"}");
            } else if (Method.GET.equals(method)) {
                JSONObject convo = dbHelper.getConversationJson(convoId);
                if (convo != null) {
                    return newFixedLengthResponse(Response.Status.OK, "application/json", convo.toString());
                } else {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Conversation not found\"}");
                }
            }
        }

        // 18. Messages (/api/messages/:conversationId) -> returns array directly
        if (uri.startsWith("/api/messages/")) {
            String convoId = uri.substring("/api/messages/".length());
            try {
                convoId = URLDecoder.decode(convoId, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {}
            JSONArray msgs = dbHelper.getMessagesJson(convoId);
            return newFixedLengthResponse(Response.Status.OK, "application/json", msgs.toString());
        }

        // 19. Keys
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

        return newFixedLengthResponse(Response.Status.OK, "application/json", "{}");
    }

    private Response handleStartGeneration(String uri, String postData) {
        try {
            JSONObject reqJson = new JSONObject(postData != null ? postData : "{}");
            String endpoint = reqJson.optString("endpoint", "");
            if (endpoint.isEmpty()) {
                String sub = uri.substring("/api/agents/chat/".length());
                try {
                    endpoint = URLDecoder.decode(sub, StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    endpoint = sub;
                }
            }
            if (endpoint.isEmpty()) {
                endpoint = "LLM Gateway";
            }

            String model = reqJson.optString("model", "gpt-4o-mini");
            String prompt = reqJson.optString("text", "");
            String conversationId = reqJson.optString("conversationId", "");
            if (conversationId.isEmpty() || conversationId.equals("new") || conversationId.equals("-1")) {
                conversationId = UUID.randomUUID().toString();
            }
            String parentMessageId = reqJson.optString("parentMessageId", "00000000-0000-0000-0000-000000000000");
            String userMessageId = reqJson.optString("messageId", "");
            if (userMessageId.isEmpty()) {
                userMessageId = UUID.randomUUID().toString();
            }
            String responseMessageId = UUID.randomUUID().toString();
            String streamId = "stream-" + UUID.randomUUID().toString();
            long now = System.currentTimeMillis();

            // Save conversation and user message in local DB
            String convoTitle = prompt.length() > 30 ? prompt.substring(0, 30) + "..." : prompt;
            dbHelper.saveConversation(conversationId, convoTitle, endpoint, model);
            dbHelper.saveMessage(userMessageId, conversationId, parentMessageId, "User", prompt, true, false);

            String apiKey = reqJson.optString("apiKey", "");

            ActiveGeneration gen = new ActiveGeneration(streamId, conversationId, userMessageId,
                    responseMessageId, parentMessageId, model, endpoint, prompt, apiKey, now);
            activeGenerations.put(streamId, gen);

            JSONObject startResp = new JSONObject();
            startResp.put("status", "started");
            startResp.put("streamId", streamId);
            startResp.put("conversationId", conversationId);
            startResp.put("generationCreatedAt", now);
            startResp.put("generationProtocolVersion", 2);

            return newFixedLengthResponse(Response.Status.OK, "application/json", startResp.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error starting generation", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handleStreamConnection(String uri) {
        String streamId = uri.substring("/api/agents/chat/stream/".length());
        try {
            streamId = URLDecoder.decode(streamId, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {}
        if (streamId.endsWith("/")) {
            streamId = streamId.substring(0, streamId.length() - 1);
        }

        ActiveGeneration gen = activeGenerations.get(streamId);
        if (gen == null) {
            Log.w(TAG, "Requested stream not found: " + streamId);
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Stream not found\"}");
        }

        try {
            PipedInputStream in = new PipedInputStream(32768);
            PipedOutputStream out = new PipedOutputStream(in);
            gen.streamOut = out;

            new Thread(() -> {
                StringBuilder fullResponse = new StringBuilder();
                try {
                    // 1. Emit CREATED event
                    JSONObject createdData = new JSONObject();
                    createdData.put("created", true);
                    createdData.put("streamId", gen.streamId);
                    JSONObject userMsgObj = new JSONObject();
                    userMsgObj.put("messageId", gen.userMessageId);
                    userMsgObj.put("parentMessageId", gen.parentMessageId);
                    userMsgObj.put("conversationId", gen.conversationId);
                    userMsgObj.put("text", gen.prompt);
                    userMsgObj.put("sender", "User");
                    userMsgObj.put("isCreatedByUser", true);
                    createdData.put("message", userMsgObj);

                    out.write(("event: message\ndata: " + createdData.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    // 2. Determine API Token
                    String token = gen.apiKey;
                    if (token == null || token.isEmpty()) {
                        token = dbHelper.getSetting("key_" + gen.endpoint, "");
                    }
                    if (token.isEmpty()) {
                        token = dbHelper.getSetting("key_DevPass", "");
                    }
                    if (token.isEmpty()) {
                        token = dbHelper.getSetting("key_LLM Gateway", "");
                    }

                    // 3. Build outbound OpenAI request
                    JSONObject outboundPayload = new JSONObject();
                    outboundPayload.put("model", gen.model);
                    outboundPayload.put("stream", true);

                    JSONArray messages = new JSONArray();
                    JSONObject sysMsg = new JSONObject();
                    sysMsg.put("role", "system");
                    sysMsg.put("content", "You are a helpful AI assistant.");
                    messages.put(sysMsg);

                    // Fetch conversation history from SQLite
                    JSONArray hist = dbHelper.getMessagesJson(gen.conversationId);
                    if (hist != null && hist.length() > 0) {
                        int startIdx = Math.max(0, hist.length() - 20);
                        for (int i = startIdx; i < hist.length(); i++) {
                            JSONObject m = hist.getJSONObject(i);
                            String msgText = m.optString("text", "");
                            if (msgText.isEmpty()) continue;
                            String mId = m.optString("messageId", "");
                            if (mId.equals(gen.userMessageId)) continue;
                            boolean isUser = m.optBoolean("isCreatedByUser", false);
                            JSONObject historyMsg = new JSONObject();
                            historyMsg.put("role", isUser ? "user" : "assistant");
                            historyMsg.put("content", msgText);
                            messages.put(historyMsg);
                        }
                    }

                    // Add current user prompt
                    JSONObject currentPromptMsg = new JSONObject();
                    currentPromptMsg.put("role", "user");
                    currentPromptMsg.put("content", gen.prompt);
                    messages.put(currentPromptMsg);

                    outboundPayload.put("messages", messages);

                    Request.Builder reqBuilder = new Request.Builder()
                            .url("https://api.llmgateway.io/v1/chat/completions")
                            .addHeader("x-source", "devpass-code")
                            .post(RequestBody.create(MediaType.parse("application/json"), outboundPayload.toString()));

                    if (!token.isEmpty()) {
                        reqBuilder.addHeader("Authorization", "Bearer " + token);
                    }

                    Call call = httpClient.newCall(reqBuilder.build());
                    gen.activeCall = call;

                    try (okhttp3.Response okResp = call.execute()) {
                        if (!okResp.isSuccessful() || okResp.body() == null) {
                            String errMsg = "Error from LLM Gateway: " + okResp.code() + " " + okResp.message();
                            if (okResp.code() == 401) {
                                errMsg = "Invalid API Token or Key Required. Please set your token in Settings -> Provider Keys.";
                            }
                            fullResponse.append(errMsg);

                            JSONObject chunk = new JSONObject();
                            chunk.put("message", true);
                            chunk.put("initial", false);
                            chunk.put("text", fullResponse.toString());
                            chunk.put("messageId", gen.responseMessageId);
                            chunk.put("parentMessageId", gen.userMessageId);
                            chunk.put("conversationId", gen.conversationId);
                            chunk.put("sender", gen.model);

                            out.write(("event: message\ndata: " + chunk.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        } else {
                            ResponseBody rb = okResp.body();
                            InputStream is = rb.byteStream();
                            byte[] buf = new byte[2048];
                            int read;
                            StringBuilder lineBuf = new StringBuilder();

                            while (!gen.aborted && (read = is.read(buf)) != -1) {
                                String piece = new String(buf, 0, read, StandardCharsets.UTF_8);
                                lineBuf.append(piece);

                                int newlineIndex;
                                while ((newlineIndex = lineBuf.indexOf("\n")) != -1) {
                                    String line = lineBuf.substring(0, newlineIndex).trim();
                                    lineBuf.delete(0, newlineIndex + 1);

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
                                                    sseData.put("message", true);
                                                    sseData.put("initial", false);
                                                    sseData.put("text", fullResponse.toString());
                                                    sseData.put("messageId", gen.responseMessageId);
                                                    sseData.put("parentMessageId", gen.userMessageId);
                                                    sseData.put("conversationId", gen.conversationId);
                                                    sseData.put("sender", gen.model);

                                                    out.write(("event: message\ndata: " + sseData.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                                                    out.flush();
                                                }
                                            }
                                        } catch (Exception ignored) {}
                                    }
                                }
                            }
                        }
                    }

                    // Save assistant message to SQLite
                    if (fullResponse.length() > 0) {
                        dbHelper.saveMessage(gen.responseMessageId, gen.conversationId, gen.userMessageId, gen.model, fullResponse.toString(), false, false);
                    }

                    // Emit FINAL event
                    JSONObject finalData = new JSONObject();
                    finalData.put("final", true);
                    if (gen.aborted) {
                        finalData.put("aborted", true);
                    }

                    JSONObject convoObj = new JSONObject();
                    convoObj.put("conversationId", gen.conversationId);
                    convoObj.put("endpoint", gen.endpoint);
                    convoObj.put("model", gen.model);
                    finalData.put("conversation", convoObj);

                    JSONObject reqMsg = new JSONObject();
                    reqMsg.put("messageId", gen.userMessageId);
                    reqMsg.put("parentMessageId", gen.parentMessageId);
                    reqMsg.put("conversationId", gen.conversationId);
                    reqMsg.put("text", gen.prompt);
                    reqMsg.put("sender", "User");
                    reqMsg.put("isCreatedByUser", true);
                    finalData.put("requestMessage", reqMsg);

                    JSONObject respMsg = new JSONObject();
                    respMsg.put("messageId", gen.responseMessageId);
                    respMsg.put("parentMessageId", gen.userMessageId);
                    respMsg.put("conversationId", gen.conversationId);
                    respMsg.put("text", fullResponse.toString());
                    respMsg.put("sender", gen.model);
                    respMsg.put("isCreatedByUser", false);
                    if (gen.aborted) {
                        respMsg.put("unfinished", true);
                    }
                    finalData.put("responseMessage", respMsg);

                    out.write(("event: message\ndata: " + finalData.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();

                } catch (Exception e) {
                    Log.e(TAG, "Streaming error in thread", e);
                    try {
                        JSONObject errObj = new JSONObject();
                        errObj.put("final", true);
                        errObj.put("error", true);
                        JSONObject cObj = new JSONObject();
                        cObj.put("conversationId", gen.conversationId);
                        errObj.put("conversation", cObj);
                        JSONObject rObj = new JSONObject();
                        rObj.put("messageId", gen.responseMessageId);
                        rObj.put("parentMessageId", gen.userMessageId);
                        rObj.put("conversationId", gen.conversationId);
                        rObj.put("text", "Generation error: " + e.getMessage());
                        rObj.put("sender", gen.model);
                        rObj.put("isCreatedByUser", false);
                        errObj.put("responseMessage", rObj);
                        out.write(("event: message\ndata: " + errObj.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception ignored) {}
                } finally {
                    activeGenerations.remove(gen.streamId);
                    try {
                        out.close();
                    } catch (Exception ignored) {}
                }
            }).start();

            Response streamResp = newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", in);
            streamResp.addHeader("Cache-Control", "no-cache, no-transform");
            streamResp.addHeader("Connection", "keep-alive");
            streamResp.addHeader("X-Generation-Protocol-Version", "2");
            return streamResp;

        } catch (Exception e) {
            Log.e(TAG, "Error establishing stream connection", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"" + e.getMessage() + "\"}");
        }
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
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", "You are a helpful AI assistant.");
            messages.put(sysMsg);

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
                    String bodyStr = resp.body().string();
                    JSONObject json = new JSONObject(bodyStr);
                    JSONArray data = json.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject m = data.getJSONObject(i);
                            String id = m.optString("id");
                            if (!id.isEmpty()) {
                                result.put(id);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch models from gateway", e);
        }

        if (result.length() == 0) {
            result.put("gpt-4o");
            result.put("gpt-4o-mini");
            result.put("claude-3-5-sonnet-20241022");
            result.put("claude-3-5-haiku-20241022");
            result.put("gemini-2.0-flash-exp");
            result.put("llama-3.3-70b-instruct");
        }

        cachedModels = result;
        lastModelsFetchTime = now;
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
            String mime = resolveMimeType(assetPath);
            Response resp = newChunkedResponse(Response.Status.OK, mime, is);
            addCorsHeaders(resp);
            return resp;
        } catch (IOException e) {
            // SPA routing fallback: return index.html for non-asset routes
            if (!uri.contains(".") || uri.endsWith(".html")) {
                try {
                    InputStream fallback = am.open("www/index.html");
                    Response resp = newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", fallback);
                    addCorsHeaders(resp);
                    return resp;
                } catch (IOException fallbackErr) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
                }
            } else {
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
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH");
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, x-source, x-api-key, X-Generation-Protocol-Version");
        resp.addHeader("Access-Control-Expose-Headers", "X-Generation-Protocol-Version");
    }
}
