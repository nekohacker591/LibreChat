package com.librechat.app.server;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
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
    /** JSON bodies are small; anything larger is a mistake or an attack. */
    private static final int MAX_REQUEST_BODY_BYTES = 25 * 1024 * 1024;
    /** Images above this are not inlined into model requests. */
    private static final long MAX_ATTACHMENT_BYTES = 12L * 1024 * 1024;
    private static final String X_SOURCE_HEADER = "opencode";
    private static final String USER_AGENT_OPENCODE = "opencode/1.18.30";
    private final Context context;
    private final LocalDatabaseHelper dbHelper;
    private final OkHttpClient httpClient;
    /** Model listings are small JSON calls; time them out fast so a dead
     *  network cannot hold fetch threads for minutes. */
    private final OkHttpClient modelFetchClient;
    private final java.util.concurrent.ScheduledExecutorService watchdog =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
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
        final JSONArray fileRefs;
        final boolean webSearch;
        volatile boolean aborted = false;
        volatile Call activeCall = null;
        final SseSink sink = new SseSink();
        /** True once the upstream call is running: reconnects attach to the
         *  sink instead of starting a second (duplicate, paid) generation. */
        volatile boolean streaming = false;
        /** Latest cumulative text, replayed to a client that reconnects. */
        volatile String streamedText = "";

        public ActiveGeneration(String streamId, String conversationId, String userMessageId,
                                String responseMessageId, String parentMessageId,
                                String model, String endpoint, String prompt, String apiKey,
                                String reasoningEffort, JSONArray fileRefs, boolean webSearch,
                                long createdAt) {
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
            this.fileRefs = fileRefs;
            this.webSearch = webSearch;
            this.createdAt = createdAt;
        }
    }

    /**
     * Disconnect-tolerant SSE output.
     *
     * The old code wrote straight into a PipedOutputStream: when the WebView
     * went away without closing the socket (backgrounding, navigation), the
     * pipe filled, the write blocked forever, the cleanup never ran and the
     * generation - plus its buffers - leaked. A failed write now detaches the
     * pipe instead, and the stall watchdog closes a pipe that has not accepted
     * a byte for 45 s so a blocked write fails fast.
     */
    private static final class SseSink {
        static final long STALL_TIMEOUT_MS = 45_000L;
        private volatile PipedOutputStream out;
        private volatile long lastWriteAttemptAt;
        private volatile long lastSuccessfulWriteAt = System.currentTimeMillis();

        void attach(PipedOutputStream pipe) {
            PipedOutputStream previous = out;
            out = pipe;
            lastWriteAttemptAt = 0;
            lastSuccessfulWriteAt = System.currentTimeMillis();
            if (previous != null && previous != pipe) {
                try {
                    previous.close();
                } catch (Exception ignored) {
                }
            }
        }

        boolean isAttached() {
            return out != null;
        }

        boolean send(String event, JSONObject payload) {
            PipedOutputStream pipe = out;
            if (pipe == null) {
                return false;
            }
            lastWriteAttemptAt = System.currentTimeMillis();
            try {
                pipe.write(("event: " + event + "\ndata: " + payload.toString() + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                pipe.flush();
                lastSuccessfulWriteAt = System.currentTimeMillis();
                return true;
            } catch (Exception e) {
                detach();
                return false;
            }
        }

        boolean sendRaw(String chunk) {
            PipedOutputStream pipe = out;
            if (pipe == null) {
                return false;
            }
            lastWriteAttemptAt = System.currentTimeMillis();
            try {
                pipe.write(chunk.getBytes(StandardCharsets.UTF_8));
                pipe.flush();
                lastSuccessfulWriteAt = System.currentTimeMillis();
                return true;
            } catch (Exception e) {
                detach();
                return false;
            }
        }

        void detach() {
            PipedOutputStream pipe = out;
            out = null;
            if (pipe != null) {
                try {
                    pipe.close();
                } catch (Exception ignored) {
                }
            }
        }

        /** Watchdog entry: closes a write that has been stuck past the timeout. */
        boolean reapIfStalled(long now) {
            PipedOutputStream pipe = out;
            if (pipe == null || lastWriteAttemptAt == 0) {
                return false;
            }
            if (now - lastSuccessfulWriteAt <= STALL_TIMEOUT_MS) {
                return false;
            }
            out = null;
            try {
                pipe.close();
            } catch (Exception ignored) {
            }
            return true;
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

    /** Human-readable key state for the legacy summary response. */
    private Object describeKey(String endpoint) {
        return dbHelper.getSetting("key_" + endpoint, "").isEmpty() ? false : "valid";
    }

    /**
     * Reads the body of a mutating request.
     *
     * NanoHTTPD only exposes a raw body for POST requests through the
     * `postData` file entry: PUT bodies are written to a temporary file under
     * `content`, and PATCH bodies are consumed and discarded by parseBody
     * entirely. Reads that went through `postData` alone therefore saw `null`
     * for both PUT and PATCH - which is why Set Key (a PUT) silently stored
     * nothing while POST endpoints kept working.
     */
    private String readRequestBody(IHTTPSession session, Method method, Map<String, String> parsedFiles)
            throws Exception {
        if (Method.PATCH.equals(method)) {
            /** The body is still on the stream: parseBody would throw it away. */
            return readBodyFromStream(session);
        }
        if (!Method.POST.equals(method) && !Method.PUT.equals(method)) {
            return null;
        }
        session.parseBody(parsedFiles);
        String postData = parsedFiles.get("postData");
        if (postData == null && Method.PUT.equals(method)) {
            String contentPath = parsedFiles.get("content");
            if (contentPath != null) {
                postData = readBodyFromFile(contentPath);
            }
        }
        return postData;
    }

    private static String readBodyFromFile(String path) {
        try {
            File file = new File(path);
            if (file.length() > MAX_REQUEST_BODY_BYTES) {
                appendStaticLog("Rejected oversized request body (" + file.length() + " bytes)");
                return null;
            }
            try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
                byte[] data = new byte[(int) file.length()];
                int read = in.read(data);
                return read > 0 ? new String(data, 0, read, StandardCharsets.UTF_8) : "";
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading request body file", e);
            return null;
        }
    }

    private static String readBodyFromStream(IHTTPSession session) {
        String lengthHeader = session.getHeaders().get("content-length");
        int length;
        try {
            length = lengthHeader != null ? Integer.parseInt(lengthHeader.trim()) : 0;
        } catch (NumberFormatException e) {
            return null;
        }
        if (length <= 0) {
            return null;
        }
        if (length > MAX_REQUEST_BODY_BYTES) {
            appendStaticLog("Rejected oversized PATCH body (" + length + " bytes)");
            return null;
        }
        byte[] data = new byte[length];
        int offset = 0;
        try {
            InputStream in = session.getInputStream();
            while (offset < length) {
                int read = in.read(data, offset, length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading request body stream", e);
            return null;
        }
        return new String(data, 0, offset, StandardCharsets.UTF_8);
    }

    /** Static-safe runtime log for helpers without an instance (body guards). */
    private static void appendStaticLog(String message) {
        Log.w(TAG, message);
    }

    // ------------------------------------------------------------------
    // File attachments
    // ------------------------------------------------------------------

    private File getUploadsDir() {
        File dir = new File(context.getFilesDir(), "uploads");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Stores one multipart upload. NanoHTTPD hands multipart file parts back as
     * temporary file paths keyed by field name, and the remaining form fields
     * via {@code session.getParms()}; previously this route answered with an
     * empty array so every attachment silently lost its server identity.
     */
    private Response handleFileUpload(IHTTPSession session, Map<String, String> parsedFiles) {
        try {
            Map<String, String> parms = session.getParms();
            String tempPath = parsedFiles.get("file");
            if (tempPath == null) {
                for (Map.Entry<String, String> entry : parsedFiles.entrySet()) {
                    String candidate = entry.getValue();
                    if (!"postData".equals(entry.getKey()) && candidate != null
                            && new File(candidate).isFile()) {
                        tempPath = candidate;
                        break;
                    }
                }
            }
            if (tempPath == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                        "{\"error\":\"No file part in the request\"}");
            }
            File source = new File(tempPath);
            String requestedId = parms != null ? parms.get("file_id") : null;
            String fileId = requestedId != null && !requestedId.isEmpty()
                    ? requestedId : UUID.randomUUID().toString();
            String mime = sniffMimeType(source);
            String filename = parms != null && parms.get("filename") != null && !parms.get("filename").isEmpty()
                    ? parms.get("filename")
                    : "attachment-" + System.currentTimeMillis() + extensionFor(mime);

            File target = new File(getUploadsDir(), fileId);
            try (InputStream in = new java.io.FileInputStream(source);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            int width = parseIntSafe(parms != null ? parms.get("width") : null);
            int height = parseIntSafe(parms != null ? parms.get("height") : null);
            dbHelper.saveFile(fileId, filename, mime, target.length(), target.getAbsolutePath(), width, height);

            JSONObject res = new JSONObject();
            res.put("file_id", fileId);
            res.put("temp_file_id", requestedId != null && !requestedId.isEmpty() ? requestedId : fileId);
            res.put("filepath", "/api/files/" + fileId);
            res.put("filename", filename);
            res.put("type", mime);
            res.put("bytes", target.length());
            res.put("width", width);
            res.put("height", height);
            res.put("source", "local");
            res.put("object", "file");
            res.put("usage", 0);
            res.put("createdAt", isoNow());
            return newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
        } catch (Exception e) {
            Log.e(TAG, "File upload failed", e);
            appendRuntimeLog("File upload failed: " + e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"Upload failed\"}");
        }
    }

    private Response serveUploadedFile(String fileId) {
        try {
            JSONObject record = dbHelper.getFileRecord(fileId);
            if (record == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                        "{\"error\":\"File not found\"}");
            }
            File file = new File(record.optString("path", ""));
            if (!file.isFile()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                        "{\"error\":\"File missing\"}");
            }
            String mime = record.optString("mime", "application/octet-stream");
            InputStream in = new java.io.FileInputStream(file);
            Response resp = newChunkedResponse(Response.Status.OK, mime, in);
            resp.addHeader("Cache-Control", "private, max-age=86400");
            return resp;
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"Could not read file\"}");
        }
    }

    /** Reads a stored file as a `data:` URL, or null when unavailable/too big. */
    private String readFileAsDataUrl(JSONObject record) {
        try {
            File file = new File(record.optString("path", ""));
            if (!file.isFile() || file.length() > MAX_ATTACHMENT_BYTES) {
                return null;
            }
            byte[] data = new byte[(int) file.length()];
            try (InputStream in = new java.io.FileInputStream(file)) {
                int offset = 0;
                while (offset < data.length) {
                    int read = in.read(data, offset, data.length - offset);
                    if (read < 0) {
                        break;
                    }
                    offset += read;
                }
            }
            String mime = record.optString("mime", "image/jpeg");
            return "data:" + mime + ";base64,"
                    + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sniffMimeType(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] head = new byte[16];
            int read = in.read(head);
            if (read >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return "image/png";
            }
            if (read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) {
                return "image/jpeg";
            }
            if (read >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
                return "image/gif";
            }
            if (read >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
                return "image/webp";
            }
            if (read >= 4 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F') {
                return "application/pdf";
            }
            if (read >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
                String brand = new String(head, 8, Math.min(4, Math.max(read - 8, 0)), StandardCharsets.US_ASCII);
                if (brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("msf")) {
                    return "image/heic";
                }
                return "video/mp4";
            }
        } catch (Exception ignored) {
        }
        return "application/octet-stream";
    }

    private static String extensionFor(String mime) {
        switch (mime) {
            case "image/png":
                return ".png";
            case "image/jpeg":
                return ".jpg";
            case "image/gif":
                return ".gif";
            case "image/webp":
                return ".webp";
            case "image/heic":
                return ".heic";
            case "application/pdf":
                return ".pdf";
            case "video/mp4":
                return ".mp4";
            default:
                return ".bin";
        }
    }

    private static int parseIntSafe(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String isoNow() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new java.util.Date());
    }

    // ------------------------------------------------------------------
    // Attachments & context accounting
    // ------------------------------------------------------------------

    /** Keeps only the fields the UI needs to render an attachment later. */
    private static JSONArray sanitizeFileRefs(JSONArray raw) {
        JSONArray refs = new JSONArray();
        if (raw == null) {
            return refs;
        }
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String fileId = item.optString("file_id", "");
            if (fileId.isEmpty()) {
                continue;
            }
            try {
                JSONObject ref = new JSONObject();
                ref.put("file_id", fileId);
                ref.put("filename", item.optString("filename", "attachment"));
                ref.put("type", item.optString("type", "application/octet-stream"));
                ref.put("filepath", item.optString("filepath", "/api/files/" + fileId));
                if (item.has("width")) {
                    ref.put("width", item.optInt("width", 0));
                }
                if (item.has("height")) {
                    ref.put("height", item.optInt("height", 0));
                }
                refs.put(ref);
            } catch (Exception ignored) {
            }
        }
        return refs;
    }

    /** Vision parts for stored image attachments, shaped for the route. */
    private JSONArray buildImageParts(JSONArray fileRefs, String routeKind) {
        if (fileRefs == null || fileRefs.length() == 0) {
            return null;
        }
        JSONArray parts = new JSONArray();
        for (int i = 0; i < fileRefs.length(); i++) {
            JSONObject ref = fileRefs.optJSONObject(i);
            if (ref == null) {
                continue;
            }
            JSONObject record = dbHelper.getFileRecord(ref.optString("file_id", ""));
            if (record == null) {
                continue;
            }
            String mime = record.optString("mime", "");
            if (!mime.startsWith("image/")) {
                continue;
            }
            String dataUrl = readFileAsDataUrl(record);
            if (dataUrl == null) {
                continue;
            }
            try {
                if ("anthropic".equals(routeKind)) {
                    JSONObject part = new JSONObject();
                    part.put("type", "image");
                    JSONObject source = new JSONObject();
                    source.put("type", "base64");
                    source.put("media_type", mime);
                    source.put("data", dataUrl.substring(dataUrl.indexOf(',') + 1));
                    part.put("source", source);
                    parts.put(part);
                } else if ("responses".equals(routeKind)) {
                    JSONObject part = new JSONObject();
                    part.put("type", "input_image");
                    part.put("image_url", dataUrl);
                    parts.put(part);
                } else {
                    JSONObject part = new JSONObject();
                    part.put("type", "image_url");
                    JSONObject url = new JSONObject();
                    url.put("url", dataUrl);
                    part.put("image_url", url);
                    parts.put(part);
                }
            } catch (Exception ignored) {
            }
        }
        return parts.length() > 0 ? parts : null;
    }

    /** Plain text when there are no images, a content-part array otherwise. */
    private Object composeUserContent(String prompt, JSONArray fileRefs, String routeKind)
            throws JSONException {
        JSONArray images = buildImageParts(fileRefs, routeKind);
        if (images == null) {
            return prompt;
        }
        JSONArray content = new JSONArray();
        JSONObject textPart = new JSONObject();
        textPart.put("type", "responses".equals(routeKind) ? "input_text" : "text");
        textPart.put("text", prompt);
        content.put(textPart);
        for (int i = 0; i < images.length(); i++) {
            content.put(images.get(i));
        }
        return content;
    }

    /** Best-effort context window for the tracker when the gateway is silent. */
    private static int estimateContextWindow(String model) {
        String m = model != null ? model.toLowerCase(java.util.Locale.ROOT) : "";
        if (m.contains("gemini")) {
            return 1048576;
        }
        return 131072;
    }

    /**
     * Minimal on-device web search for the composer's web-search badge. The
     * desktop app configures a search provider; here the top DuckDuckGo results
     * are fetched directly (no API key) and appended as model context.
     */
    private String buildSearchContext(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        try {
            Request request = new Request.Builder()
                    .url("https://html.duckduckgo.com/html/?q="
                            + java.net.URLEncoder.encode(query.trim(), "UTF-8"))
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .get()
                    .build();
            try (okhttp3.Response resp = modelFetchClient.newCall(request).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    return null;
                }
                String html = resp.body().string();
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("result__a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                                java.util.regex.Pattern.DOTALL)
                        .matcher(html);
                StringBuilder context = new StringBuilder();
                int count = 0;
                while (matcher.find() && count < 5) {
                    String url = matcher.group(1);
                    String title = matcher.group(2).replaceAll("<[^>]+>", "").trim();
                    if (title.isEmpty() || url.isEmpty()) {
                        continue;
                    }
                    context.append("- ").append(title).append(" (").append(url).append(")\n");
                    count++;
                }
                if (context.length() == 0) {
                    return null;
                }
                return "Web search results for \"" + query.trim() + "\":\n" + context;
            }
        } catch (Exception e) {
            appendRuntimeLog("Web search failed: " + e);
            return null;
        }
    }

    /**
     * Resolves the outbound API key: the value on the request first, then the
     * stored per-endpoint user key (falling back to DevPass and LLM Gateway,
     * matching the settings screen the user saved from).
     */
    private String resolveApiToken(String endpoint, String requestToken) {
        String token = requestToken != null ? requestToken : "";
        if (token.isEmpty()) {
            token = dbHelper.getSetting("key_" + endpoint, "");
        }
        if (token.isEmpty()) {
            token = dbHelper.getSetting("key_DevPass", "");
        }
        if (token.isEmpty()) {
            token = dbHelper.getSetting("key_LLM Gateway", "");
        }
        return extractApiKey(token);
    }

    /**
     * The Set Key dialog stores custom-endpoint keys as the JSON object it
     * submits (`{"apiKey":"...","baseURL":"..."}`), so callers need the inner
     * apiKey rather than the serialized wrapper. Plain string keys (and
     * Bedrock credential objects) pass through unchanged.
     */
    private static String extractApiKey(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        String trimmed = stored.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject obj = new JSONObject(trimmed);
                String apiKey = obj.optString("apiKey", "");
                if (!apiKey.isEmpty()) {
                    return apiKey;
                }
                String accessKeyId = obj.optString("accessKeyId", "");
                if (!accessKeyId.isEmpty()) {
                    return trimmed;
                }
            } catch (JSONException ignored) {
                /* not the dialog's JSON shape; fall through to the raw value */
            }
        }
        return trimmed;
    }

    /**
     * Returns a JSON string value, or null when the key is absent, JSON null,
     * or not a string. Android's JSONObject.getString()/optString() stringify
     * JSON null to the literal "null", which made every reasoning chunk - they
     * carry `content: null` next to `reasoning_content` - append "null" to the
     * answer.
     */
    private static String jsonString(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) {
            return null;
        }
        Object value = obj.opt(key);
        return value instanceof String ? (String) value : null;
    }

    /**
     * Reasoning models stream their thinking separately from the answer. The
     * client renders the legacy `:::thinking ... :::` text form as the
     * collapsible chain-of-thought box, so the two are merged until the answer
     * begins.
     */
    private static String composeStreamedText(StringBuilder reasoning, StringBuilder answer) {
        if (reasoning == null || reasoning.length() == 0) {
            return answer.toString();
        }
        return ":::thinking\n" + reasoning + "\n:::\n" + answer;
    }

    private final ConcurrentHashMap<String, ActiveGeneration> activeGenerations = new ConcurrentHashMap<>();

    public LocalServer(Context context, int port) {
        /** Loopback only: this server exists for the bundled WebView and used
         *  to be reachable from the whole LAN without authentication. */
        super("127.0.0.1", port);
        this.context = context;
        this.dbHelper = new LocalDatabaseHelper(context);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                /** Model replies can pause for a long time while reasoning; a
                 *  read timeout here aborted healthy streams. */
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.modelFetchClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
        loadCachedModels();
        watchdog.scheduleWithFixedDelay(this::reapStalledStreams, 15, 15, TimeUnit.SECONDS);
        watchdog.schedule(() -> {
            lastModelsFetchTime = System.currentTimeMillis();
            fetchModelsFromGateway();
        }, 2, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        watchdog.shutdownNow();
        for (ActiveGeneration gen : activeGenerations.values()) {
            try {
                if (gen.activeCall != null) {
                    gen.activeCall.cancel();
                }
            } catch (Exception ignored) {
            }
            gen.sink.detach();
        }
        activeGenerations.clear();
        try {
            dbHelper.close();
        } catch (Exception ignored) {
        }
        super.stop();
    }

    private void reapStalledStreams() {
        try {
            long now = System.currentTimeMillis();
            for (ActiveGeneration gen : activeGenerations.values()) {
                if (gen.sink.reapIfStalled(now)) {
                    appendRuntimeLog("Dropped stalled stream " + gen.streamId + " ("
                            + gen.endpoint + "/" + gen.model + ")");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "watchdog error", t);
        }
    }

    /** Appends a line to logs/runtime.log (rotated once) for post-mortems. */
    void appendRuntimeLog(String message) {
        try {
            File dir = new File(context.getFilesDir(), "logs");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, "runtime.log");
            if (file.length() > 256 * 1024) {
                File rotated = new File(dir, "runtime.1.log");
                if (rotated.exists()) {
                    rotated.delete();
                }
                file.renameTo(rotated);
            }
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + " " + message + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Instantly serve the last successful listing after a restart. */
    private void loadCachedModels() {
        try {
            String gateway = dbHelper.getSetting("models_llm_gateway", "");
            String devpass = dbHelper.getSetting("models_devpass", "");
            String go = dbHelper.getSetting("models_go", "");
            String zen = dbHelper.getSetting("models_zen", "");
            if (!gateway.isEmpty()) cachedLlmGatewayModels = new JSONArray(gateway);
            if (!devpass.isEmpty()) cachedDevPassModels = new JSONArray(devpass);
            if (!go.isEmpty()) cachedOpenCodeGoModels = new JSONArray(go);
            if (!zen.isEmpty()) cachedOpenCodeZenModels = new JSONArray(zen);
            modelsLoaded = !gateway.isEmpty() || !go.isEmpty() || !zen.isEmpty();
        } catch (Throwable ignored) {
        }
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
        Map<String, String> parsedFiles = new HashMap<>();
        String postData = readRequestBody(session, method, parsedFiles);

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

        /** Endpoint token config: powers the context tracker's percentage and
         *  limit display. Filled from the model list with a sane default. */
        if (uri.startsWith("/api/endpoints/token-config")) {
            JSONObject tokenConfig = new JSONObject();
            JSONObject perModel = new JSONObject();
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
            for (JSONArray list : new JSONArray[]{
                    cachedOpenCodeGoModels, cachedOpenCodeZenModels,
                    cachedLlmGatewayModels, cachedDevPassModels}) {
                for (int i = 0; i < list.length(); i++) {
                    all.add(list.optString(i));
                }
            }
            for (String m : all) {
                if (m.isEmpty()) {
                    continue;
                }
                JSONObject cfg = new JSONObject();
                cfg.put("context", estimateContextWindow(m));
                perModel.put(m, cfg);
            }
            tokenConfig.put("OpenCode Go", perModel);
            tokenConfig.put("OpenCode Zen", perModel);
            tokenConfig.put("LLM Gateway", perModel);
            tokenConfig.put("DevPass", perModel);
            return newFixedLengthResponse(Response.Status.OK, "application/json", tokenConfig.toString());
        }

        /** Roles: the client hides every tool unless the role grants it. The
         *  on-device server is single-user, so grant the full set and let the
         *  capability list decide what is actually offered. */
        if (uri.startsWith("/api/roles")) {
            JSONObject permissions = new JSONObject();
            String[] types = {"PROMPTS", "BOOKMARKS", "AGENTS", "MEMORIES", "MULTI_CONVO",
                    "TEMPORARY_CHAT", "RUN_CODE", "WEB_SEARCH", "PEOPLE_PICKER", "MARKETPLACE",
                    "FILE_SEARCH", "FILE_CITATIONS", "MCP_SERVERS", "REMOTE_AGENTS", "SKILLS",
                    "SHARED_LINKS", "SCHEDULES"};
            for (String type : types) {
                JSONObject grant = new JSONObject();
                grant.put("USE", true);
                grant.put("CREATE", true);
                grant.put("UPDATE", true);
                grant.put("READ", true);
                grant.put("READ_AUTHOR", true);
                grant.put("SHARE", true);
                grant.put("OPT_OUT", true);
                grant.put("VIEW_USERS", true);
                grant.put("VIEW_GROUPS", true);
                grant.put("VIEW_ROLES", true);
                grant.put("SHARE_PUBLIC", true);
                permissions.put(type, grant);
            }
            JSONObject role = new JSONObject();
            role.put("name", uri.substring("/api/roles".length()) .replaceFirst("^/", ""));
            role.put("permissions", permissions);
            return newFixedLengthResponse(Response.Status.OK, "application/json", role.toString());
        }

        // 5. Models
        if (uri.equals("/api/models")) {
            long now = System.currentTimeMillis();
            /** Throttle attempts, not only successes: a failing gateway used to
             *  leave the timestamp at zero, so every request spawned another
             *  round of slow fetches. */
            long interval = modelsLoaded ? 300000L : 60000L;
            if ((now - lastModelsFetchTime) > interval && !isFetchingModels) {
                lastModelsFetchTime = now;
                isFetchingModels = true;
                new Thread(this::fetchModelsFromGateway).start();
            }
            /** Never block the response: the client polls, and a slow fetch
             *  should not freeze the model picker. */
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

        // 14. Files (attachments: multipart upload, config, download/preview)
        if (uri.startsWith("/api/files")) {
            String filePath = uri.length() > "/api/files".length()
                    ? uri.substring("/api/files".length()) : "";
            if (filePath.startsWith("/config")) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{}");
            }
            if (filePath.startsWith("/usage")) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"usage\":0}");
            }
            if (Method.POST.equals(method) || Method.PUT.equals(method)) {
                return handleFileUpload(session, parsedFiles);
            }
            if (Method.GET.equals(method) && filePath.startsWith("/") && filePath.length() > 1) {
                String fileId = filePath.substring(1);
                if (fileId.endsWith("/preview")) {
                    fileId = fileId.substring(0, fileId.length() - "/preview".length());
                }
                if (fileId.endsWith("/download")) {
                    fileId = fileId.substring(0, fileId.length() - "/download".length());
                }
                return serveUploadedFile(fileId);
            }
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

        // 19. Keys (user-provided provider keys)
        if (uri.startsWith("/api/keys")) {
            Map<String, String> parms = session.getParms();
            if (Method.PUT.equals(method) || Method.POST.equals(method)) {
                try {
                    JSONObject keyObj = new JSONObject(postData != null ? postData : "{}");
                    String name = keyObj.optString("name", keyObj.optString("endpoint", ""));
                    String value = keyObj.has("value")
                            ? keyObj.optString("value", "")
                            : keyObj.optString("apiKey", keyObj.optString("key", ""));
                    String expiresAt = keyObj.optString("expiresAt", "");
                    if (name.isEmpty()) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                                "{\"error\":\"name is required\"}");
                    }
                    dbHelper.setSetting("key_" + name, value);
                    if (expiresAt.isEmpty()) {
                        dbHelper.deleteSetting("key_expiry_" + name);
                    } else {
                        dbHelper.setSetting("key_expiry_" + name, expiresAt);
                    }
                    return newFixedLengthResponse(Response.Status.CREATED, "application/json", "{}");
                } catch (Exception e) {
                    Log.e(TAG, "Error saving key", e);
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                            "{\"error\":\"Invalid request body\"}");
                }
            } else if (Method.GET.equals(method)) {
                JSONObject keys = new JSONObject();
                String name = parms != null ? parms.get("name") : null;
                if (name != null && !name.isEmpty()) {
                    /** The client's Set Key dialog reads `expiresAt` for this one
                     *  name to label the key and enable revoke. */
                    if (dbHelper.getSetting("key_" + name, "").isEmpty()) {
                        keys.put("expiresAt", JSONObject.NULL);
                    } else {
                        String expiry = dbHelper.getSetting("key_expiry_" + name, "");
                        keys.put("expiresAt", expiry.isEmpty() ? "never" : expiry);
                    }
                } else {
                    keys.put("LLM Gateway", describeKey("LLM Gateway"));
                    keys.put("DevPass", describeKey("DevPass"));
                    keys.put("OpenCode Go", describeKey("OpenCode Go"));
                    keys.put("OpenCode Zen", describeKey("OpenCode Zen"));
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", keys.toString());
            } else if (Method.DELETE.equals(method)) {
                String all = parms != null ? parms.get("all") : null;
                if ("true".equals(all)) {
                    dbHelper.deleteSettingsByPrefix("key_");
                } else if (uri.length() > "/api/keys/".length()) {
                    String name = uri.substring("/api/keys/".length());
                    try {
                        name = URLDecoder.decode(name, StandardCharsets.UTF_8.name());
                    } catch (Exception ignored) {}
                    dbHelper.deleteSetting("key_" + name);
                    dbHelper.deleteSetting("key_expiry_" + name);
                }
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "");
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

            /** Attachment references travel with the message; the bytes live in
             *  the files table and are inlined for the model at request time. */
            JSONArray fileRefs = sanitizeFileRefs(reqJson.optJSONArray("files"));
            String filesJson = fileRefs.length() > 0 ? fileRefs.toString() : null;
            dbHelper.saveMessage(userMessageId, conversationId, parentMessageId, "User", prompt, true, false,
                    filesJson, 0, null);

            String apiKey = reqJson.optString("apiKey", "");
            String reasoningEffort = reqJson.optString("reasoning_effort", "");
            JSONObject ephemeralAgent = reqJson.optJSONObject("ephemeralAgent");
            boolean webSearch = ephemeralAgent != null && ephemeralAgent.optBoolean("web_search", false);

            /** A resend in the same conversation supersedes the old generation. */
            for (ActiveGeneration existing : activeGenerations.values()) {
                if (conversationId.equals(existing.conversationId)) {
                    existing.aborted = true;
                    if (existing.activeCall != null) {
                        try {
                            existing.activeCall.cancel();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            ActiveGeneration gen = new ActiveGeneration(streamId, conversationId, userMessageId,
                    responseMessageId, parentMessageId, model, endpoint, prompt, apiKey,
                    reasoningEffort, fileRefs, webSearch, now);
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
            gen.sink.attach(out);

            if (gen.streaming) {
                /** A reconnect attaches to the running generation and replays the
                 *  text so far - it must not start a second upstream call. */
                appendRuntimeLog("Re-attached to stream " + gen.streamId);
                JSONObject snapshot = new JSONObject();
                snapshot.put("message", true);
                snapshot.put("initial", false);
                snapshot.put("text", gen.streamedText);
                snapshot.put("messageId", gen.responseMessageId);
                snapshot.put("parentMessageId", gen.userMessageId);
                snapshot.put("conversationId", gen.conversationId);
                snapshot.put("sender", gen.model);
                gen.sink.send("message", snapshot);

                Response reconnectResp = newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", in);
                reconnectResp.addHeader("Cache-Control", "no-cache, no-transform");
                reconnectResp.addHeader("Connection", "keep-alive");
                reconnectResp.addHeader("X-Generation-Protocol-Version", "2");
                return reconnectResp;
            }

            gen.streaming = true;
            new Thread(() -> {
                StringBuilder fullResponse = new StringBuilder();
                StringBuilder reasoningResponse = new StringBuilder();
                int usageInput = 0;
                int usageOutput = 0;
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

                    gen.sink.send("message", createdData);

                    // 2. Determine API Token
                    String token = resolveApiToken(gen.endpoint, gen.apiKey);

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

                    /** Composer web-search badge: hand the model fresh results. */
                    if (gen.webSearch) {
                        String searchContext = buildSearchContext(gen.prompt);
                        if (searchContext != null) {
                            sysMsg.put("content", sysMsg.optString("content", "") + "\n\n" + searchContext);
                        }
                    }

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

                    /** Attached images ride with the current user turn, in the
                     *  shape this route understands. */
                    String routeKind = isResponsesModel ? "responses" : (isAnthropicModel ? "anthropic" : "chat");
                    if (gen.fileRefs != null && gen.fileRefs.length() > 0) {
                        currentPromptMsg.put("content", composeUserContent(gen.prompt, gen.fileRefs, routeKind));
                    }

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

                    if (!isAnthropicModel && !isResponsesModel) {
                        /** Ask the gateway for a final usage chunk so the context
                         *  tracker has real token counts instead of nothing. */
                        JSONObject streamOptions = new JSONObject();
                        streamOptions.put("include_usage", true);
                        requestPayload.put("stream_options", streamOptions);
                    }

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
                            gen.streamedText = composeStreamedText(reasoningResponse, fullResponse);

                            JSONObject chunk = new JSONObject();
                            chunk.put("message", true);
                            chunk.put("initial", false);
                            chunk.put("text", gen.streamedText);
                            chunk.put("messageId", gen.responseMessageId);
                            chunk.put("parentMessageId", gen.userMessageId);
                            chunk.put("conversationId", gen.conversationId);
                            chunk.put("sender", gen.model);

                            gen.sink.send("message", chunk);
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
                                            String chunkReasoning = null;

                                            /** Token usage: OpenAI sends a final
                                             *  usage chunk, Anthropic reports on
                                             *  message_start/message_delta and the
                                             *  Responses API on response.completed. */
                                            JSONObject usageObj = deltaObj.optJSONObject("usage");
                                            if (usageObj != null) {
                                                usageInput = usageObj.optInt("input_tokens",
                                                        usageObj.optInt("prompt_tokens", usageInput));
                                                usageOutput = usageObj.optInt("output_tokens",
                                                        usageObj.optInt("completion_tokens", usageOutput));
                                            }
                                            JSONObject usageMessage = deltaObj.optJSONObject("message");
                                            if (usageMessage != null) {
                                                JSONObject inner = usageMessage.optJSONObject("usage");
                                                if (inner != null) {
                                                    usageInput = inner.optInt("input_tokens", usageInput);
                                                    usageOutput = inner.optInt("output_tokens", usageOutput);
                                                }
                                            }
                                            JSONObject usageResponse = deltaObj.optJSONObject("response");
                                            if (usageResponse != null) {
                                                JSONObject inner = usageResponse.optJSONObject("usage");
                                                if (inner != null) {
                                                    usageInput = inner.optInt("input_tokens", usageInput);
                                                    usageOutput = inner.optInt("output_tokens", usageOutput);
                                                }
                                            }

                                            // 1. OpenAI Chat Completions format
                                            JSONArray choices = deltaObj.optJSONArray("choices");
                                            if (choices != null && choices.length() > 0) {
                                                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                                                if (delta != null) {
                                                    chunkText = jsonString(delta, "content");
                                                    chunkReasoning = jsonString(delta, "reasoning_content");
                                                    if (chunkReasoning == null) {
                                                        chunkReasoning = jsonString(delta, "reasoning");
                                                    }
                                                }
                                            }

                                            String streamType = deltaObj.optString("type", "");

                                            // 2. OpenAI Responses API format (response.output_text.delta)
                                            if (chunkText == null && "response.output_text.delta".equals(streamType)) {
                                                chunkText = jsonString(deltaObj, "delta");
                                            }
                                            if (chunkReasoning == null
                                                    && ("response.reasoning_summary_text.delta".equals(streamType)
                                                        || "response.reasoning_text.delta".equals(streamType))) {
                                                chunkReasoning = jsonString(deltaObj, "delta");
                                            }

                                            // 3. Anthropic Messages API format (content_block_delta)
                                            if ("content_block_delta".equals(streamType)) {
                                                JSONObject delta = deltaObj.optJSONObject("delta");
                                                if (delta != null) {
                                                    if (chunkText == null) {
                                                        chunkText = jsonString(delta, "text");
                                                    }
                                                    if (chunkReasoning == null) {
                                                        chunkReasoning = jsonString(delta, "thinking");
                                                    }
                                                }
                                            }

                                            boolean hasText = chunkText != null && !chunkText.isEmpty();
                                            boolean hasReasoning = chunkReasoning != null && !chunkReasoning.isEmpty();
                                            if (hasReasoning) {
                                                reasoningResponse.append(chunkReasoning);
                                            }
                                            if (hasText) {
                                                fullResponse.append(chunkText);
                                            }

                                            if (hasText || hasReasoning) {
                                                gen.streamedText = composeStreamedText(reasoningResponse, fullResponse);
                                                JSONObject sseData = new JSONObject();
                                                sseData.put("message", true);
                                                sseData.put("initial", false);
                                                sseData.put("text", gen.streamedText);
                                                sseData.put("messageId", gen.responseMessageId);
                                                sseData.put("parentMessageId", gen.userMessageId);
                                                sseData.put("conversationId", gen.conversationId);
                                                sseData.put("sender", gen.model);

                                                gen.sink.send("message", sseData);
                                            }
                                        } catch (Exception ignored) {}
                                    }
                                }
                            }
                        }
                    }

                    // Token accounting for the context tracker
                    int totalTokens = usageInput + usageOutput;
                    if (totalTokens <= 0) {
                        totalTokens = Math.max(1, (gen.prompt.length() + fullResponse.length()
                                + reasoningResponse.length()) / 4);
                    }
                    int maxContext = estimateContextWindow(gen.model);
                    JSONObject contextData = new JSONObject();
                    JSONObject breakdown = new JSONObject();
                    breakdown.put("maxContextTokens", maxContext);
                    breakdown.put("messageTokens", totalTokens);
                    breakdown.put("messageCount", 1);
                    contextData.put("breakdown", breakdown);
                    contextData.put("contextBudget", maxContext);
                    contextData.put("remainingContextTokens", Math.max(0, maxContext - totalTokens));
                    contextData.put("model", gen.model);
                    contextData.put("provider", gen.endpoint);

                    JSONObject metadataObj = new JSONObject();
                    metadataObj.put("usage", new JSONObject().put("input", usageInput).put("output", usageOutput));
                    metadataObj.put("contextUsage", contextData);
                    String metadataJson = metadataObj.toString();

                    // Save assistant message to SQLite (reasoning stays in the
                    // client's :::thinking wrapper so it survives a reload)
                    if (fullResponse.length() > 0 || reasoningResponse.length() > 0) {
                        dbHelper.saveMessage(gen.responseMessageId, gen.conversationId, gen.userMessageId, gen.model,
                                composeStreamedText(reasoningResponse, fullResponse), false, false,
                                null, totalTokens, metadataJson);
                    }

                    /** Live tracker events, emitted before FINAL so the gauge
                     *  updates while the response is still on screen. */
                    JSONObject usageEvent = new JSONObject();
                    usageEvent.put("event", "on_token_usage");
                    JSONObject usageData = new JSONObject();
                    usageData.put("input_tokens", usageInput);
                    usageData.put("output_tokens", usageOutput);
                    usageData.put("total_tokens", totalTokens);
                    usageData.put("model", gen.model);
                    usageData.put("provider", gen.endpoint);
                    usageData.put("usage_type", "message");
                    usageEvent.put("data", usageData);
                    gen.sink.send("message", usageEvent);

                    JSONObject contextEvent = new JSONObject();
                    contextEvent.put("event", "on_context_usage");
                    contextEvent.put("data", contextData);
                    gen.sink.send("message", contextEvent);

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
                    respMsg.put("text", composeStreamedText(reasoningResponse, fullResponse));
                    respMsg.put("sender", gen.model);
                    respMsg.put("isCreatedByUser", false);
                    respMsg.put("tokenCount", totalTokens);
                    respMsg.put("metadata", metadataObj);
                    if (gen.aborted) {
                        respMsg.put("unfinished", true);
                    }
                    finalData.put("responseMessage", respMsg);

                    gen.sink.send("message", finalData);

                } catch (Exception e) {
                    Log.e(TAG, "Streaming error in thread", e);
                    appendRuntimeLog("Generation error (" + gen.endpoint + "/" + gen.model + "): " + e);
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
                        gen.sink.send("message", errObj);
                    } catch (Exception ignored) {}
                } finally {
                    gen.streaming = false;
                    activeGenerations.remove(gen.streamId);
                    gen.sink.detach();
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
            String token = resolveApiToken(endpoint, reqJson.optString("apiKey", ""));

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

            PipedInputStream in = new PipedInputStream(32768);
            PipedOutputStream out = new PipedOutputStream(in);
            final SseSink sink = new SseSink();
            sink.attach(out);

            new Thread(() -> {
                StringBuilder fullResponse = new StringBuilder();
                StringBuilder reasoningResponse = new StringBuilder();
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
                        sink.sendRaw(sseErr);
                        sink.sendRaw("event: error\ndata: [DONE]\n\n");
                        sink.detach();
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
                                    String chunkReasoning = null;

                                    // 1. OpenAI Chat Completions format
                                    JSONArray choices = deltaObj.optJSONArray("choices");
                                    if (choices != null && choices.length() > 0) {
                                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                                        if (delta != null) {
                                            chunkText = jsonString(delta, "content");
                                            chunkReasoning = jsonString(delta, "reasoning_content");
                                            if (chunkReasoning == null) {
                                                chunkReasoning = jsonString(delta, "reasoning");
                                            }
                                        }
                                    }

                                    String streamType = deltaObj.optString("type", "");

                                    // 2. OpenAI Responses API format (response.output_text.delta)
                                    if (chunkText == null && "response.output_text.delta".equals(streamType)) {
                                        chunkText = jsonString(deltaObj, "delta");
                                    }
                                    if (chunkReasoning == null
                                            && ("response.reasoning_summary_text.delta".equals(streamType)
                                                || "response.reasoning_text.delta".equals(streamType))) {
                                        chunkReasoning = jsonString(deltaObj, "delta");
                                    }

                                    // 3. Anthropic Messages API format (content_block_delta)
                                    if ("content_block_delta".equals(streamType)) {
                                        JSONObject delta = deltaObj.optJSONObject("delta");
                                        if (delta != null) {
                                            if (chunkText == null) {
                                                chunkText = jsonString(delta, "text");
                                            }
                                            if (chunkReasoning == null) {
                                                chunkReasoning = jsonString(delta, "thinking");
                                            }
                                        }
                                    }

                                    boolean hasText = chunkText != null && !chunkText.isEmpty();
                                    boolean hasReasoning = chunkReasoning != null && !chunkReasoning.isEmpty();
                                    if (hasReasoning) {
                                        reasoningResponse.append(chunkReasoning);
                                    }
                                    if (hasText) {
                                        fullResponse.append(chunkText);
                                    }

                                    if (hasText || hasReasoning) {
                                        JSONObject sseData = new JSONObject();
                                        sseData.put("text", composeStreamedText(reasoningResponse, fullResponse));
                                        sseData.put("messageId", messageId);
                                        sseData.put("conversationId", conversationId);
                                        sseData.put("sender", model);

                                        sink.send("message", sseData);
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    // Save assistant message to local database
                    dbHelper.saveMessage(messageId, conversationId, parentMessageId, model, composeStreamedText(reasoningResponse, fullResponse), false, false);

                    sink.sendRaw("event: message\ndata: [DONE]\n\n");
                    sink.detach();
                } catch (Exception streamErr) {
                    Log.e(TAG, "Streaming error", streamErr);
                    appendRuntimeLog("Legacy stream error (" + endpoint + "/" + model + "): " + streamErr);
                    try {
                        String sseErr = "event: message\ndata: " + new JSONObject()
                                .put("text", "Streaming error: " + streamErr.getMessage())
                                .put("messageId", messageId)
                                .put("conversationId", conversationId)
                                .put("error", true)
                                .toString() + "\n\n";
                        sink.sendRaw(sseErr);
                        sink.detach();
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

            try (okhttp3.Response resp = modelFetchClient.newCall(req).execute()) {
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
                            try (okhttp3.Response goResp = modelFetchClient.newCall(goReq).execute()) {
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
                            try (okhttp3.Response zenResp = modelFetchClient.newCall(zenReq).execute()) {
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

                        modelsLoaded = true;
                        try {
                            dbHelper.setSetting("models_llm_gateway", gatewayList.toString());
                            dbHelper.setSetting("models_devpass", new JSONArray(new java.util.ArrayList<>(devPassSet)).toString());
                            dbHelper.setSetting("models_go", cachedOpenCodeGoModels.toString());
                            dbHelper.setSetting("models_zen", cachedOpenCodeZenModels.toString());
                        } catch (Exception ignored) {
                        }
                        Log.i(TAG, "Indexed models: " + gatewayList.length() + " LLM Gateway models, " + devPassSet.size() + " DevPass models, " + cachedOpenCodeGoModels.length() + " Go models, " + cachedOpenCodeZenModels.length() + " Zen models");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to background fetch models from gateway", e);
            appendRuntimeLog("Model fetch failed: " + e);
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
