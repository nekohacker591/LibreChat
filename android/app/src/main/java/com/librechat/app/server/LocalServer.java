package com.librechat.app.server;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
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
    private static final String X_SOURCE_HEADER = "opencode";
    private static final String USER_AGENT_OPENCODE = "opencode/1.18.30";
    private final Context context;
    private final LocalDatabaseHelper dbHelper;
    private final OkHttpClient httpClient;
    private final Object modelsLock = new Object();
    private volatile JSONArray cachedLlmGatewayModels = getDefaultLlmGatewayModels();
    private volatile JSONArray cachedDevPassModels = getDefaultDevPassModels();
    private volatile JSONArray cachedOpenCodeGoModels = getDefaultOpenCodeGoModels();
    private volatile JSONArray cachedOpenCodeZenModels = getDefaultOpenCodeZenModels();
    private volatile boolean isFetchingModels = false;
    private volatile boolean modelsLoaded = false;
    private long lastModelsFetchTime = 0;

    private static JSONArray getDefaultOpenCodeGoModels() {
        JSONArray arr = new JSONArray();
        arr.put("minimax-m3");
        arr.put("kimi-k3");
        arr.put("glm-5.3");
        arr.put("deepseek-v4-flash");
        arr.put("qwen3.8-max");
        arr.put("gpt-5.6-luna");
        arr.put("grok-4.6");
        return arr;
    }

    private static JSONArray getDefaultOpenCodeZenModels() {
        JSONArray arr = new JSONArray();
        arr.put("claude-sonnet-4-5");
        arr.put("gpt-5.4");
        arr.put("gemini-3-flash");
        arr.put("deepseek-v4-flash");
        arr.put("grok-4.6");
        return arr;
    }

    private static JSONArray getDefaultLlmGatewayModels() {
        JSONArray arr = new JSONArray();
        arr.put("openai/gpt-4o");
        arr.put("openai/gpt-4o-mini");
        arr.put("azure/gpt-4o");
        arr.put("azure/gpt-4o-mini");
        arr.put("anthropic/claude-3-5-sonnet-20241022");
        arr.put("anthropic/claude-3-5-haiku-20241022");
        arr.put("google/gemini-2.0-flash");
        arr.put("deepseek/deepseek-chat");
        arr.put("meta/llama-3.3-70b-instruct");
        return arr;
    }

    private static JSONArray getDefaultDevPassModels() {
        JSONArray arr = new JSONArray();
        arr.put("gpt-4o");
        arr.put("gpt-4o-mini");
        arr.put("claude-3-5-sonnet-20241022");
        arr.put("claude-3-5-haiku-20241022");
        arr.put("gemini-2.0-flash");
        arr.put("gemini-1.5-flash");
        arr.put("llama-3.3-70b-instruct");
        arr.put("deepseek-chat");
        return arr;
    }

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
        final String reasoningEffort;
        final long createdAt;
        volatile boolean aborted = false;
        volatile Call activeCall = null;
        volatile PipedOutputStream streamOut = null;

        public ActiveGeneration(String streamId, String conversationId, String userMessageId,
                                String responseMessageId, String parentMessageId,
                                String model, String endpoint, String prompt, String apiKey,
                                String reasoningEffort, long createdAt) {
            this.streamId = streamId;
            this.conversationId = conversationId;
            this.userMessageId = userMessageId;
            this.responseMessageId = responseMessageId;
            this.parentMessageId = parentMessageId;
            this.model = model;
            this.endpoint = endpoint;
            this.prompt = prompt;
            this.apiKey = apiKey;
            this.reasoningEffort = reasoningEffort;
            this.createdAt = createdAt;
        }
    }

    /**
     * Forwards the composer's reasoning level to the gateway. Anthropic Messages
     * and OpenAI-compatible chat completions accept the shared `reasoning_effort`
     * field; the Responses API takes `reasoning: { effort }`, matching the
     * desktop server.
     */
    private static void applyReasoningEffort(JSONObject payload, String reasoningEffort,
                                             boolean isResponsesModel) throws JSONException {
        if (reasoningEffort == null || reasoningEffort.isEmpty()
                || "unset".equalsIgnoreCase(reasoningEffort)) {
            return;
        }
        if (isResponsesModel) {
            JSONObject reasoning = new JSONObject();
            reasoning.put("effort", reasoningEffort);
            payload.put("reasoning", reasoning);
        } else {
            payload.put("reasoning_effort", reasoningEffort);
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
        // Eagerly index models from gateway on server startup
        new Thread(this::fetchModelsFromGateway).start();
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

            JSONObject opencodeGo = new JSONObject();
            opencodeGo.put("type", "custom");
            opencodeGo.put("userProvide", true);
            opencodeGo.put("modelDisplayLabel", "OpenCode Go");
            endpoints.put("OpenCode Go", opencodeGo);

            JSONObject opencodeZen = new JSONObject();
            opencodeZen.put("type", "custom");
            opencodeZen.put("userProvide", true);
            opencodeZen.put("modelDisplayLabel", "OpenCode Zen");
            endpoints.put("OpenCode Zen", opencodeZen);

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

            JSONObject opencodeGo = new JSONObject();
            opencodeGo.put("type", "custom");
            opencodeGo.put("userProvide", true);
            opencodeGo.put("modelDisplayLabel", "OpenCode Go");
            endpoints.put("OpenCode Go", opencodeGo);

            JSONObject opencodeZen = new JSONObject();
            opencodeZen.put("type", "custom");
            opencodeZen.put("userProvide", true);
            opencodeZen.put("modelDisplayLabel", "OpenCode Zen");
            endpoints.put("OpenCode Zen", opencodeZen);

            return newFixedLengthResponse(Response.Status.OK, "application/json", endpoints.toString());
        }

        // 5. Models
        if (uri.equals("/api/models")) {
            long now = System.currentTimeMillis();
            if ((now - lastModelsFetchTime) > 300000 && !isFetchingModels) {
                new Thread(this::fetchModelsFromGateway).start();
            }
            if (!modelsLoaded && isFetchingModels) {
                synchronized (modelsLock) {
                    try {
                        modelsLock.wait(2500);
                    } catch (InterruptedException ignored) {}
                }
            }
            JSONObject modelsObj = new JSONObject();
            modelsObj.put("LLM Gateway", cachedLlmGatewayModels);
            modelsObj.put("DevPass", cachedDevPassModels);
            modelsObj.put("OpenCode Go", cachedOpenCodeGoModels);
            modelsObj.put("OpenCode Zen", cachedOpenCodeZenModels);
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

        // 16. Conversations List & Delete
        if (uri.equals("/api/convos") || uri.equals("/api/convos/")) {
            if (Method.DELETE.equals(method)) {
                if (postData != null) {
                    try {
                        JSONObject delReq = new JSONObject(postData);
                        JSONObject arg = delReq.optJSONObject("arg");
                        if (arg != null) {
                            String cid = arg.optString("conversationId", "");
                            if (!cid.isEmpty()) {
                                dbHelper.deleteConversation(cid);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"message\":\"Deleted\"}");
            }
            if (Method.GET.equals(method)) {
                Map<String, String> parms = session.getParms();
                boolean isPinned = parms != null && "true".equalsIgnoreCase(parms.get("pinned"));
                boolean isArchived = parms != null && "true".equalsIgnoreCase(parms.get("isArchived"));

                JSONObject res = new JSONObject();
                if (isPinned || isArchived) {
                    res.put("conversations", new JSONArray());
                } else {
                    res.put("conversations", dbHelper.getConversationsJson());
                }
                res.put("pages", 1);
                res.put("pageNumber", 1);
                res.put("pageSize", 25);
                res.put("nextCursor", JSONObject.NULL);
                return newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
            }
        }

        // 16b. Delete All / Clear
        if (uri.equals("/api/convos/all") || uri.equals("/api/convos/clear")) {
            dbHelper.clearAllConversations();
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"message\":\"All conversations deleted\"}");
        }

        // 16c. Archive routes
        if (uri.startsWith("/api/convos/archive")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}");
        }

        // 16d. Pin routes
        if (uri.startsWith("/api/convos/pin")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}");
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
            String reasoningEffort = reqJson.optString("reasoning_effort", "");

            ActiveGeneration gen = new ActiveGeneration(streamId, conversationId, userMessageId,
                    responseMessageId, parentMessageId, model, endpoint, prompt, apiKey,
                    reasoningEffort, now);
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
                    String targetModel = gen.model;
                    if ("DevPass".equalsIgnoreCase(gen.endpoint)) {
                        if (targetModel != null && targetModel.contains("/")) {
                            targetModel = targetModel.substring(targetModel.lastIndexOf('/') + 1);
                        }
                    }
                    outboundPayload.put("model", targetModel);
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

                    String modelLower = gen.model != null ? gen.model.toLowerCase() : "";
                    boolean isOpenCode = "OpenCode Go".equalsIgnoreCase(gen.endpoint) || "OpenCode Zen".equalsIgnoreCase(gen.endpoint);
                    boolean isGo = "OpenCode Go".equalsIgnoreCase(gen.endpoint);

                    boolean isAnthropicModel = isOpenCode && (isGo
                        ? (modelLower.startsWith("minimax") || modelLower.startsWith("qwen") || modelLower.startsWith("claude"))
                        : (modelLower.startsWith("claude") || modelLower.startsWith("qwen")));

                    boolean isResponsesModel = isOpenCode && (modelLower.startsWith("muse") || modelLower.startsWith("gpt") || modelLower.startsWith("grok"));

                    String completionsUrl = "https://api.llmgateway.io/v1/chat/completions";
                    if (isGo) {
                        if (isResponsesModel) {
                            completionsUrl = "https://opencode.ai/zen/go/v1/responses";
                        } else if (isAnthropicModel) {
                            completionsUrl = "https://opencode.ai/zen/go/v1/messages";
                        } else {
                            completionsUrl = "https://opencode.ai/zen/go/v1/chat/completions";
                        }
                    } else if ("OpenCode Zen".equalsIgnoreCase(gen.endpoint)) {
                        if (isResponsesModel) {
                            completionsUrl = "https://opencode.ai/zen/v1/responses";
                        } else if (isAnthropicModel) {
                            completionsUrl = "https://opencode.ai/zen/v1/messages";
                        } else {
                            completionsUrl = "https://opencode.ai/zen/v1/chat/completions";
                        }
                    }

                    JSONObject requestPayload = new JSONObject();
                    requestPayload.put("model", gen.model);
                    requestPayload.put("stream", true);

                    if (isAnthropicModel) {
                        requestPayload.put("max_tokens", 4096);
                        requestPayload.put("messages", messages);
                    } else if (isResponsesModel) {
                        requestPayload.put("input", messages);
                    } else {
                        requestPayload.put("messages", messages);
                    }

                    applyReasoningEffort(requestPayload, gen.reasoningEffort, isResponsesModel);

                    Request.Builder reqBuilder = new Request.Builder()
                            .url(completionsUrl)
                            .addHeader("x-source", X_SOURCE_HEADER)
                            .addHeader("User-Agent", USER_AGENT_OPENCODE)
                            .post(RequestBody.create(MediaType.parse("application/json"), requestPayload.toString()));

                    if (isOpenCode) {
                        String sessionId = gen.conversationId != null && !gen.conversationId.isEmpty() ? gen.conversationId : UUID.randomUUID().toString();
                        reqBuilder.addHeader("x-opencode-session", sessionId);
                    }

                    if (!token.isEmpty()) {
                        if (isAnthropicModel) {
                            reqBuilder.addHeader("x-api-key", token);
                            reqBuilder.addHeader("anthropic-version", "2023-06-01");
                        }
                        reqBuilder.addHeader("Authorization", "Bearer " + token);
                    }

                    Call call = httpClient.newCall(reqBuilder.build());
                    gen.activeCall = call;

                    try (okhttp3.Response okResp = call.execute()) {
                        if (!okResp.isSuccessful() || okResp.body() == null) {
                            String errMsg = "Error from " + gen.endpoint + ": " + okResp.code() + " " + okResp.message();
                            if (okResp.code() == 401) {
                                errMsg = "Invalid API Key or Token Required for " + gen.endpoint + ". Please set your key in Settings -> Provider Keys.";
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
                                            String chunkText = null;

                                            // 1. OpenAI Chat Completions format
                                            JSONArray choices = deltaObj.optJSONArray("choices");
                                            if (choices != null && choices.length() > 0) {
                                                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                                                if (delta != null && delta.has("content")) {
                                                    chunkText = delta.getString("content");
                                                }
                                            }

                                            // 2. OpenAI Responses API format (response.output_text.delta)
                                            if (chunkText == null && "response.output_text.delta".equals(deltaObj.optString("type"))) {
                                                chunkText = deltaObj.optString("delta", "");
                                            }

                                            // 3. Anthropic Messages API format (content_block_delta)
                                            if (chunkText == null && "content_block_delta".equals(deltaObj.optString("type"))) {
                                                JSONObject delta = deltaObj.optJSONObject("delta");
                                                if (delta != null && delta.has("text")) {
                                                    chunkText = delta.getString("text");
                                                }
                                            }

                                            if (chunkText != null && !chunkText.isEmpty()) {
                                                fullResponse.append(chunkText);

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
            String outboundModel = model;
            if ("DevPass".equalsIgnoreCase(endpoint)) {
                if (outboundModel != null && outboundModel.contains("/")) {
                    outboundModel = outboundModel.substring(outboundModel.lastIndexOf('/') + 1);
                }
            }
            outboundPayload.put("model", outboundModel);
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

            String modelLower = outboundModel != null ? outboundModel.toLowerCase() : "";
            boolean isOpenCode = "OpenCode Go".equalsIgnoreCase(endpoint) || "OpenCode Zen".equalsIgnoreCase(endpoint);
            boolean isGo = "OpenCode Go".equalsIgnoreCase(endpoint);

            boolean isAnthropicModel = isOpenCode && (isGo
                ? (modelLower.startsWith("minimax") || modelLower.startsWith("qwen") || modelLower.startsWith("claude"))
                : (modelLower.startsWith("claude") || modelLower.startsWith("qwen")));

            boolean isResponsesModel = isOpenCode && (modelLower.startsWith("muse") || modelLower.startsWith("gpt") || modelLower.startsWith("grok"));

            String completionsUrl = "https://api.llmgateway.io/v1/chat/completions";
            if (isGo) {
                if (isResponsesModel) {
                    completionsUrl = "https://opencode.ai/zen/go/v1/responses";
                } else if (isAnthropicModel) {
                    completionsUrl = "https://opencode.ai/zen/go/v1/messages";
                } else {
                    completionsUrl = "https://opencode.ai/zen/go/v1/chat/completions";
                }
            } else if ("OpenCode Zen".equalsIgnoreCase(endpoint)) {
                if (isResponsesModel) {
                    completionsUrl = "https://opencode.ai/zen/v1/responses";
                } else if (isAnthropicModel) {
                    completionsUrl = "https://opencode.ai/zen/v1/messages";
                } else {
                    completionsUrl = "https://opencode.ai/zen/v1/chat/completions";
                }
            }

            JSONObject requestPayload = new JSONObject();
            requestPayload.put("model", outboundModel);
            requestPayload.put("stream", true);

            if (isAnthropicModel) {
                requestPayload.put("max_tokens", 4096);
                requestPayload.put("messages", messages);
            } else if (isResponsesModel) {
                requestPayload.put("input", messages);
            } else {
                requestPayload.put("messages", messages);
            }

            applyReasoningEffort(requestPayload, reqJson.optString("reasoning_effort", ""), isResponsesModel);

            Request.Builder reqBuilder = new Request.Builder()
                    .url(completionsUrl)
                    .addHeader("x-source", X_SOURCE_HEADER)
                    .addHeader("User-Agent", USER_AGENT_OPENCODE)
                    .post(RequestBody.create(MediaType.parse("application/json"), requestPayload.toString()));

            if (isOpenCode) {
                String sessionId = conversationId != null && !conversationId.isEmpty() ? conversationId : UUID.randomUUID().toString();
                reqBuilder.addHeader("x-opencode-session", sessionId);
            }

            if (!token.isEmpty()) {
                if (isAnthropicModel) {
                    reqBuilder.addHeader("x-api-key", token);
                    reqBuilder.addHeader("anthropic-version", "2023-06-01");
                }
                reqBuilder.addHeader("Authorization", "Bearer " + token);
            }

            PipedInputStream in = new PipedInputStream();
            PipedOutputStream out = new PipedOutputStream(in);

            new Thread(() -> {
                StringBuilder fullResponse = new StringBuilder();
                try (okhttp3.Response okResp = httpClient.newCall(reqBuilder.build()).execute()) {
                    if (!okResp.isSuccessful() || okResp.body() == null) {
                        String errMsg = "Error from " + endpoint + ": " + okResp.code() + " " + okResp.message();
                        if (okResp.code() == 401) {
                            errMsg = "Invalid API Key or Token Required for " + endpoint + ". Please set your key in Settings -> Provider Keys.";
                        }
                        String sseErr = "event: message\ndata: " + new JSONObject()
                                .put("text", errMsg)
                                .put("messageId", messageId)
                                .put("conversationId", conversationId)
                                .put("sender", model)
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
                                    String chunkText = null;

                                    // 1. OpenAI Chat Completions format
                                    JSONArray choices = deltaObj.optJSONArray("choices");
                                    if (choices != null && choices.length() > 0) {
                                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                                        if (delta != null && delta.has("content")) {
                                            chunkText = delta.getString("content");
                                        }
                                    }

                                    // 2. OpenAI Responses API format (response.output_text.delta)
                                    if (chunkText == null && "response.output_text.delta".equals(deltaObj.optString("type"))) {
                                        chunkText = deltaObj.optString("delta", "");
                                    }

                                    // 3. Anthropic Messages API format (content_block_delta)
                                    if (chunkText == null && "content_block_delta".equals(deltaObj.optString("type"))) {
                                        JSONObject delta = deltaObj.optJSONObject("delta");
                                        if (delta != null && delta.has("text")) {
                                            chunkText = delta.getString("text");
                                        }
                                    }

                                    if (chunkText != null && !chunkText.isEmpty()) {
                                        fullResponse.append(chunkText);

                                        JSONObject sseData = new JSONObject();
                                        sseData.put("text", fullResponse.toString());
                                        sseData.put("messageId", messageId);
                                        sseData.put("conversationId", conversationId);
                                        sseData.put("sender", model);

                                        String sseMsg = "event: message\ndata: " + sseData.toString() + "\n\n";
                                        out.write(sseMsg.getBytes(StandardCharsets.UTF_8));
                                        out.flush();
                                    }
                                } catch (Exception ignored) {}
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

    private boolean isNonChatDevPassModel(String modelId, JSONObject architecture) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase(java.util.Locale.ROOT);
        // Embeddings & rerank
        if (lower.contains("embed") || lower.contains("bge-") || lower.contains("rerank")) {
            return true;
        }
        // Image generation
        if (lower.contains("image") ||
            lower.contains("dall-e") ||
            lower.contains("dalle") ||
            lower.contains("flux") ||
            lower.contains("diffusion") ||
            lower.contains("midjourney") ||
            lower.contains("imagen") ||
            lower.contains("cogview") ||
            lower.contains("seedream")) {
            return true;
        }
        // Video generation
        if (lower.contains("video") ||
            lower.contains("veo") ||
            lower.contains("kling") ||
            lower.contains("wan-") ||
            lower.contains("seedance") ||
            lower.contains("hailuo") ||
            lower.contains("sora") ||
            lower.contains("luma") ||
            lower.contains("gen-2") ||
            lower.contains("gen-3") ||
            lower.contains("runway") ||
            lower.contains("animate") ||
            lower.contains("svd")) {
            return true;
        }

        if (architecture != null) {
            JSONArray out = architecture.optJSONArray("output_modalities");
            if (out != null) {
                for (int i = 0; i < out.length(); i++) {
                    String mod = out.optString(i, "").toLowerCase(java.util.Locale.ROOT);
                    if (mod.equals("embedding") || mod.equals("rerank") || mod.equals("image") || mod.equals("video")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void fetchModelsFromGateway() {
        synchronized (modelsLock) {
            if (isFetchingModels) {
                return;
            }
            isFetchingModels = true;
        }

        try {
            Request req = new Request.Builder()
                    .url("https://api.llmgateway.io/v1/models?mapped=true")
                    .addHeader("x-source", X_SOURCE_HEADER)
                    .addHeader("User-Agent", USER_AGENT_OPENCODE)
                    .get()
                    .build();

            try (okhttp3.Response resp = httpClient.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    String bodyStr = resp.body().string();
                    JSONObject json = new JSONObject(bodyStr);
                    JSONArray data = json.optJSONArray("data");
                    if (data != null && data.length() > 0) {
                        JSONArray gatewayList = new JSONArray();
                        java.util.LinkedHashSet<String> devPassSet = new java.util.LinkedHashSet<>();

                        for (int i = 0; i < data.length(); i++) {
                            JSONObject m = data.getJSONObject(i);
                            String id = m.optString("id", "").trim();
                            if (id.isEmpty() || id.equalsIgnoreCase("custom") || id.equalsIgnoreCase("llmgateway/custom")) {
                                continue;
                            }

                            // LLM Gateway (Pay as you go): includes all models with provider name (e.g. openai/gpt-4o, openai/gpt-image-2)
                            gatewayList.put(id);

                            // DevPass: exclude embeddings, image generation, and video generation
                            JSONObject arch = m.optJSONObject("architecture");
                            if (isNonChatDevPassModel(id, arch)) {
                                continue;
                            }

                            // DevPass: remove the provider name (e.g. openai/gpt-4o -> gpt-4o)
                            String stripped = id;
                            if (stripped.contains("/")) {
                                stripped = stripped.substring(stripped.lastIndexOf('/') + 1);
                            }
                            if (!stripped.isEmpty() && !stripped.equalsIgnoreCase("custom") && !isNonChatDevPassModel(stripped, arch)) {
                                devPassSet.add(stripped);
                            }
                        }

                        if (gatewayList.length() > 0) {
                            cachedLlmGatewayModels = gatewayList;
                        }
                        if (!devPassSet.isEmpty()) {
                            JSONArray devList = new JSONArray();
                            for (String modelName : devPassSet) {
                                devList.put(modelName);
                            }
                            cachedDevPassModels = devList;
                        }

                        // Fetch OpenCode Go models dynamically
                        try {
                            Request goReq = new Request.Builder()
                                    .url("https://opencode.ai/zen/go/v1/models")
                                    .addHeader("x-source", X_SOURCE_HEADER)
                                    .addHeader("User-Agent", USER_AGENT_OPENCODE)
                                    .get()
                                    .build();
                            try (okhttp3.Response goResp = httpClient.newCall(goReq).execute()) {
                                if (goResp.isSuccessful() && goResp.body() != null) {
                                    JSONObject goJson = new JSONObject(goResp.body().string());
                                    JSONArray goData = goJson.optJSONArray("data");
                                    if (goData != null && goData.length() > 0) {
                                        JSONArray goList = new JSONArray();
                                        for (int i = 0; i < goData.length(); i++) {
                                            JSONObject m = goData.getJSONObject(i);
                                            String id = m.optString("id", "").trim();
                                            if (!id.isEmpty()) {
                                                goList.put(id);
                                            }
                                        }
                                        if (goList.length() > 0) {
                                            cachedOpenCodeGoModels = goList;
                                            Log.i(TAG, "Indexed " + goList.length() + " OpenCode Go models");
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to fetch OpenCode Go models", e);
                        }

                        // Fetch OpenCode Zen models dynamically
                        try {
                            Request zenReq = new Request.Builder()
                                    .url("https://opencode.ai/zen/v1/models")
                                    .addHeader("x-source", X_SOURCE_HEADER)
                                    .addHeader("User-Agent", USER_AGENT_OPENCODE)
                                    .get()
                                    .build();
                            try (okhttp3.Response zenResp = httpClient.newCall(zenReq).execute()) {
                                if (zenResp.isSuccessful() && zenResp.body() != null) {
                                    JSONObject zenJson = new JSONObject(zenResp.body().string());
                                    JSONArray zenData = zenJson.optJSONArray("data");
                                    if (zenData != null && zenData.length() > 0) {
                                        JSONArray zenList = new JSONArray();
                                        for (int i = 0; i < zenData.length(); i++) {
                                            JSONObject m = zenData.getJSONObject(i);
                                            String id = m.optString("id", "").trim();
                                            if (!id.isEmpty()) {
                                                zenList.put(id);
                                            }
                                        }
                                        if (zenList.length() > 0) {
                                            cachedOpenCodeZenModels = zenList;
                                            Log.i(TAG, "Indexed " + zenList.length() + " OpenCode Zen models");
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to fetch OpenCode Zen models", e);
                        }

                        lastModelsFetchTime = System.currentTimeMillis();
                        modelsLoaded = true;
                        Log.i(TAG, "Indexed models: " + gatewayList.length() + " LLM Gateway models, " + devPassSet.size() + " DevPass models, " + cachedOpenCodeGoModels.length() + " Go models, " + cachedOpenCodeZenModels.length() + " Zen models");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to background fetch models from gateway", e);
        } finally {
            synchronized (modelsLock) {
                isFetchingModels = false;
                modelsLock.notifyAll();
            }
        }
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
