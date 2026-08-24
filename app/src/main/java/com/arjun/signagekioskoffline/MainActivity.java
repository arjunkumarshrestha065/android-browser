package com.arjun.signagekioskoffline;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

public class MainActivity extends Activity {
    private static final int PICK_USB_FOLDER = 201;
    private static final String PREFS = "offline_signage";
    private static final String P_SERVER = "server";
    private static final String P_TOKEN = "token";
    private static final String P_TREE = "tree";
    private static final String PLAYLIST_FILE = "playlist.json";

    private WebView webView;
    private View setupPanel;
    private EditText serverInput, tokenInput;
    private TextView storageText, statusText;
    private Button chooseStorageButton, saveButton, syncButton, setupButton;
    private SharedPreferences prefs;
    private Uri treeUri;
    private volatile boolean syncing = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        hideSystemUi();
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        webView = findViewById(R.id.webView);
        setupPanel = findViewById(R.id.setupPanel);
        serverInput = findViewById(R.id.serverInput);
        tokenInput = findViewById(R.id.tokenInput);
        storageText = findViewById(R.id.storageText);
        statusText = findViewById(R.id.statusText);
        chooseStorageButton = findViewById(R.id.chooseStorageButton);
        saveButton = findViewById(R.id.saveButton);
        syncButton = findViewById(R.id.syncButton);
        setupButton = findViewById(R.id.setupButton);

        configureWebView();
        serverInput.setText(prefs.getString(P_SERVER, ""));
        tokenInput.setText(prefs.getString(P_TOKEN, ""));
        String savedTree = prefs.getString(P_TREE, "");
        if (!savedTree.isEmpty()) treeUri = Uri.parse(savedTree);
        updateStorageLabel();

        chooseStorageButton.setOnClickListener(v -> chooseUsbFolder());
        saveButton.setOnClickListener(v -> saveSettingsAndStart());
        syncButton.setOnClickListener(v -> syncNow());
        setupButton.setOnClickListener(v -> showSetup());

        if (isConfigured()) {
            showPlayer();
            syncNow();
        } else showSetup();
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setBackgroundColor(Color.BLACK);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new PlayerBridge(), "AndroidSignage");
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("signage-media".equals(u.getScheme())) {
                    String id = u.getHost();
                    try {
                        InputStream in = getContentResolver().openInputStream(Uri.parse(Uri.decode(id)));
                        String mime = getContentResolver().getType(Uri.parse(Uri.decode(id)));
                        return new WebResourceResponse(mime == null ? "application/octet-stream" : mime, null, in);
                    } catch (Exception e) { return null; }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
    }

    private void chooseUsbFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, PICK_USB_FOLDER);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_USB_FOLDER && result == RESULT_OK && data != null && data.getData() != null) {
            treeUri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(treeUri, flags); } catch (Exception ignored) { }
            prefs.edit().putString(P_TREE, treeUri.toString()).apply();
            try { ensureFolder(treeUri, "SignageKiosk"); } catch (Exception e) { setStatus("Storage error: " + e.getMessage()); }
            updateStorageLabel();
        }
    }

    private void saveSettingsAndStart() {
        String server = serverInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();
        if (!server.startsWith("http://") && !server.startsWith("https://")) server = "http://" + server;
        while (server.endsWith("/")) server = server.substring(0, server.length()-1);
        if (server.isEmpty() || token.isEmpty() || treeUri == null) {
            Toast.makeText(this, "Enter server URL, client token, and select USB folder", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putString(P_SERVER, server).putString(P_TOKEN, token).putString(P_TREE, treeUri.toString()).apply();
        hideKeyboard();
        showPlayer();
        syncNow();
    }

    private boolean isConfigured() {
        return !prefs.getString(P_SERVER, "").isEmpty() && !prefs.getString(P_TOKEN, "").isEmpty() && treeUri != null;
    }

    private void showSetup() { setupPanel.setVisibility(View.VISIBLE); webView.setVisibility(View.GONE); }
    private void showPlayer() { setupPanel.setVisibility(View.GONE); webView.setVisibility(View.VISIBLE); webView.loadUrl("file:///android_asset/player.html"); }

    private void syncNow() {
        if (!isConfigured() || syncing) return;
        syncing = true;
        setStatus("Checking playlist...");
        new Thread(() -> {
            try {
                String server = prefs.getString(P_SERVER, "");
                String token = prefs.getString(P_TOKEN, "");
                String json = httpGet(server + "/api/client/playlist/" + URLEncoder.encode(token, "UTF-8"));
                JSONObject remote = new JSONObject(json);
                JSONArray items = remote.optJSONArray("items");
                if (items == null) items = new JSONArray();
                Uri appFolder = ensureFolder(treeUri, "SignageKiosk");
                Uri mediaFolder = ensureFolder(appFolder, "media");
                Uri dataFolder = ensureFolder(appFolder, "data");
                Uri tempFolder = ensureFolder(appFolder, "temp");
                JSONArray localItems = new JSONArray();
                Set<String> keepNames = new HashSet<>();
                for (int n=0; n<items.length(); n++) {
                    JSONObject item = items.getJSONObject(n);
                    String remoteUrl = item.optString("full_url", item.optString("file_url", ""));
                    if (remoteUrl.startsWith("/")) remoteUrl = server + remoteUrl;
                    String originalName = item.optString("file_name", item.optString("original_name", "media_" + item.optInt("id", n)));
                    String safeName = safeFileName(item.optInt("id", n) + "_" + originalName);
                    keepNames.add(safeName);
                    String mime = item.optString("file_type", "video").equals("image") ? guessImageMime(safeName) : "video/mp4";
                    Uri finalUri = findChild(mediaFolder, safeName);
                    if (finalUri == null) {
                        setStatus("Downloading " + (n+1) + " of " + items.length() + ": " + originalName);
                        Uri temp = createOrReplaceFile(tempFolder, safeName + ".part", "application/octet-stream");
                        downloadToUri(remoteUrl, temp);
                        finalUri = createOrReplaceFile(mediaFolder, safeName, mime);
                        copyUri(temp, finalUri);
                        deleteUri(temp);
                    }
                    JSONObject local = new JSONObject();
                    local.put("name", originalName);
                    local.put("type", item.optString("file_type", extensionType(safeName)));
                    local.put("duration", item.optInt("duration", 10));
                    local.put("uri", finalUri.toString());
                    localItems.put(local);
                }
                JSONObject saved = new JSONObject();
                saved.put("playlist_id", remote.optInt("playlist_id", 1));
                saved.put("playlist_source", remote.optString("playlist_source", "client"));
                saved.put("items", localItems);
                writeTextFile(dataFolder, PLAYLIST_FILE, saved.toString());
                cleanupUnused(mediaFolder, keepNames);
                setStatus("Playlist ready: " + localItems.length() + " files");
                runOnUiThread(() -> webView.reload());
            } catch (Exception e) {
                setStatus("Offline mode: " + e.getMessage());
                runOnUiThread(() -> webView.reload());
            } finally { syncing = false; }
        }).start();
    }

    private String readLocalPlaylist() {
        if (treeUri == null) return "{\"items\":[]}";
        try {
            Uri app = findChild(treeUri, "SignageKiosk"); if (app == null) return "{\"items\":[]}";
            Uri data = findChild(app, "data"); if (data == null) return "{\"items\":[]}";
            Uri file = findChild(data, PLAYLIST_FILE); if (file == null) return "{\"items\":[]}";
            return readText(file);
        } catch (Exception e) { return "{\"items\":[]}"; }
    }

    public class PlayerBridge {
        @JavascriptInterface public String getPlaylist() { return readLocalPlaylist(); }
        @JavascriptInterface public void requestSync() { runOnUiThread(() -> syncNow()); }
        @JavascriptInterface public String mediaUrl(String contentUri) { return "signage-media://" + Uri.encode(contentUri); }
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setRequestProperty("Cache-Control", "no-cache");
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = readStream(in); c.disconnect();
        if (code < 200 || code >= 300) throw new IOException("Server HTTP " + code + " " + body);
        return body;
    }

    private void downloadToUri(String url, Uri destination) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(120000); c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) throw new IOException("Media HTTP " + c.getResponseCode());
        try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
            if (out == null) throw new IOException("Cannot write USB file");
            byte[] b = new byte[64*1024]; int r; while ((r=in.read(b))!=-1) out.write(b,0,r); out.flush();
        } finally { c.disconnect(); }
    }

    private Uri ensureFolder(Uri parent, String name) throws Exception {
        Uri child = findChild(parent, name); if (child != null) return child;
        Uri created = DocumentsContract.createDocument(getContentResolver(), parentDocumentUri(parent), DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IOException("Cannot create folder " + name); return created;
    }

    private Uri createOrReplaceFile(Uri parent, String name, String mime) throws Exception {
        Uri old = findChild(parent, name); if (old != null) deleteUri(old);
        Uri created = DocumentsContract.createDocument(getContentResolver(), parentDocumentUri(parent), mime, name);
        if (created == null) throw new IOException("Cannot create " + name); return created;
    }

    private Uri findChild(Uri parent, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent));
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            while (c != null && c.moveToNext()) if (name.equals(c.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0));
        } catch (Exception ignored) { }
        return null;
    }

    private Uri parentDocumentUri(Uri parent) { return DocumentsContract.buildDocumentUriUsingTree(parent, DocumentsContract.getDocumentId(parent)); }
    private void deleteUri(Uri u) { try { DocumentsContract.deleteDocument(getContentResolver(), u); } catch (Exception ignored) { } }
    private void copyUri(Uri from, Uri to) throws Exception { try(InputStream in=getContentResolver().openInputStream(from); OutputStream out=getContentResolver().openOutputStream(to,"w")){ if(in==null||out==null)throw new IOException("USB copy failed"); byte[]b=new byte[65536];int r;while((r=in.read(b))!=-1)out.write(b,0,r); } }
    private void writeTextFile(Uri folder,String name,String text)throws Exception{Uri u=createOrReplaceFile(folder,name,"application/json");try(OutputStream o=getContentResolver().openOutputStream(u,"w")){if(o==null)throw new IOException("Cannot save playlist");o.write(text.getBytes("UTF-8"));}}
    private String readText(Uri u)throws Exception{try(InputStream in=getContentResolver().openInputStream(u)){return readStream(in);}}
    private String readStream(InputStream in)throws Exception{if(in==null)return"";ByteArrayOutputStream o=new ByteArrayOutputStream();byte[]b=new byte[8192];int r;while((r=in.read(b))!=-1)o.write(b,0,r);return o.toString("UTF-8");}

    private void cleanupUnused(Uri mediaFolder, Set<String> keep) {
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(mediaFolder,DocumentsContract.getDocumentId(mediaFolder));
        String[] cols={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try(Cursor c=getContentResolver().query(children,cols,null,null,null)){while(c!=null&&c.moveToNext()){String name=c.getString(1);if(!keep.contains(name))deleteUri(DocumentsContract.buildDocumentUriUsingTree(mediaFolder,c.getString(0)));}}catch(Exception ignored){}
    }

    private String safeFileName(String s){return s.replaceAll("[^a-zA-Z0-9._-]","_");}
    private String extensionType(String s){String x=s.toLowerCase();return(x.endsWith(".jpg")||x.endsWith(".jpeg")||x.endsWith(".png")||x.endsWith(".webp")||x.endsWith(".gif"))?"image":"video";}
    private String guessImageMime(String s){String x=s.toLowerCase();if(x.endsWith(".png"))return"image/png";if(x.endsWith(".webp"))return"image/webp";if(x.endsWith(".gif"))return"image/gif";return"image/jpeg";}
    private void setStatus(String s){runOnUiThread(()->statusText.setText(s));}
    private void updateStorageLabel(){storageText.setText(treeUri==null?"No USB folder selected":"USB folder selected and remembered");}
    private void hideKeyboard(){InputMethodManager i=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(i!=null)i.hideSoftInputFromWindow(serverInput.getWindowToken(),0);}
    private void hideSystemUi(){getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
    @Override public void onWindowFocusChanged(boolean h){super.onWindowFocusChanged(h);if(h)hideSystemUi();}
    @Override public boolean onKeyDown(int k,KeyEvent e){if(k==KeyEvent.KEYCODE_MENU||k==KeyEvent.KEYCODE_SETTINGS){showSetup();return true;}if(k==KeyEvent.KEYCODE_BACK){if(setupPanel.getVisibility()==View.VISIBLE&&isConfigured()){showPlayer();return true;}return true;}return super.onKeyDown(k,e);}
}
