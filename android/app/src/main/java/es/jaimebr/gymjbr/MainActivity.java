package es.jaimebr.gymjbr;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import android.media.AudioFormat;
import android.media.AudioTrack;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    static final String URL = "https://jaimebr-gym.github.io/gym-jbr/";
    static final String ALT = "https://jaimebr-gym.github.io/Gym-jbr/";
    static final int REQ_MIC = 7, REQ_FILE = 8;
    boolean triedAlt = false;

    WebView web;
    TextToSpeech tts;
    boolean ttsReady = false;
    final ArrayList<String[]> pending = new ArrayList<>();
    SpeechRecognizer rec;
    String pendingLang = null;
    boolean pendingInterim = false, listening = false, muted = false;
    AudioManager am;
    AudioFocusRequest focus;
    int queued = 0;
    ValueCallback<Uri[]> fileCb;
    Model model;
    SpeechService vosk;
    boolean cmdOn = false, voskFailed = false;
    static final String GRAMMAR = "[\"para\",\"pausa\",\"espera\",\"alto\",\"más despacio\",\"despacio\",\"más lento\",\"más rápido\",\"ritmo normal\",\"repite\",\"otra vez\",\"siguiente\",\"saltar\",\"sigue\",\"seguimos\",\"continúa\",\"listo\",\"ya estoy\",\"adelante\",\"vamos\",\"empezamos\",\"otra serie\",\"una más\",\"una serie más\",\"muy pesado\",\"demasiado pesado\",\"pesado\",\"no he podido\",\"no puedo\",\"imposible\",\"fácil\",\"muy fácil\",\"me duele\",\"molestia\",\"dolor\",\"no me gusta\",\"bien\",\"perfecto\",\"[unk]\"]";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "GymNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) v.loadUrl("file:///android_asset/offline.html");
            }
            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, android.webkit.WebResourceResponse res) {
                if (r.isForMainFrame() && res.getStatusCode() == 404 && !triedAlt) { triedAlt = true; v.loadUrl(ALT); }
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("jaimebr-gym.github.io".equalsIgnoreCase(u.getHost()) || "file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ex) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        tts = new TextToSpeech(this, st -> {
            if (st != TextToSpeech.SUCCESS) return;
            tts.setLanguage(new Locale("es", "ES"));
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { js("window.__ttsStart&&__ttsStart('" + id + "')"); }
                @Override public void onDone(String id) { done(id); }
                @Override public void onError(String id) { done(id); }
                @Override public void onStop(String id, boolean interrupted) { done(id); }
            });
            ttsReady = true;
            for (String[] p : pending) speakNow(p[0], p[1], Float.parseFloat(p[2]), Float.parseFloat(p[3]), p[4]);
            pending.clear();
        });
        web.loadUrl(URL);
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            StorageService.unpack(this, "model-es", "model", m -> { model = m; if (cmdOn) startVosk(); }, e -> { voskFailed = true; });
        } catch (Throwable t) { voskFailed = true; }
    }

    void startVosk() {
        if (model == null) return;
        if (vosk != null) return;
        try {
            Recognizer r = new Recognizer(model, 16000.0f, GRAMMAR);
            r.setWords(true);
            vosk = new SpeechService(r, 16000.0f);
            vosk.startListening(new org.vosk.android.RecognitionListener() {
                public void onPartialResult(String h) { }
                public void onResult(String h) { deliver(h); }
                public void onFinalResult(String h) { deliver(h); }
                public void onError(Exception e) { stopVosk(); }
                public void onTimeout() { }
            });
        } catch (Exception e) { voskFailed = true; vosk = null; }
    }

    void stopVosk() {
        if (vosk != null) { try { vosk.stop(); vosk.shutdown(); } catch (Exception e) { } vosk = null; }
    }

    void deliver(String json) {
        if (!cmdOn) return;
        try {
            JSONObject o = new JSONObject(json);
            StringBuilder b = new StringBuilder();
            JSONArray w = o.optJSONArray("result");
            if (w != null) {
                for (int i = 0; i < w.length(); i++) {
                    JSONObject x = w.getJSONObject(i);
                    String wd = x.optString("word");
                    if (x.optDouble("conf", 1) >= 0.8 && !"[unk]".equals(wd)) b.append(b.length() > 0 ? " " : "").append(wd);
                }
            }
            String t = b.toString().trim();
            if (t.isEmpty()) return;
            JSONArray a = new JSONArray(); a.put(t);
            js("window.__srResult&&__srResult(" + JSONObject.quote(a.toString()) + ",true);window.__srEnd&&__srEnd()");
        } catch (Exception e) { }
    }

    void ping(double f, double vol, double dec) {
        new Thread(() -> {
            try {
                int sr = 22050, n = (int) (sr * Math.max(0.15, Math.min(1.5, dec)));
                short[] buf = new short[n];
                double v = Math.max(0.05, Math.min(1, vol)) * 0.9 * 32767;
                for (int i = 0; i < n; i++) {
                    double t = (double) i / sr, env = Math.min(1, t / 0.004) * Math.exp(-t * 5.0 / dec);
                    buf[i] = (short) (v * env * (Math.sin(2 * Math.PI * f * t) + 0.25 * Math.sin(4 * Math.PI * f * t)) / 1.25);
                }
                AudioTrack at = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
                at.write(buf, 0, n);
                at.play();
                Thread.sleep((long) (n * 1000L / sr) + 100);
                at.release();
            } catch (Exception e) { }
        }).start();
    }

    void done(String id) {
        js("window.__ttsEnd&&__ttsEnd('" + id + "')");
        runOnUiThread(() -> { queued = Math.max(0, queued - 1); });
    }

    void js(String code) { runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    void takeFocus() {
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build();
        }
        am.requestAudioFocus(focus);
    }
    void dropFocus() { if (focus != null) am.abandonAudioFocusRequest(focus); }

    void speakNow(String id, String text, float rate, float pitch, String voice) {
        if (!ttsReady) { pending.add(new String[]{id, text, "" + rate, "" + pitch, voice}); return; }
        try {
            if (voice != null && voice.length() > 0) {
                Set<Voice> vs = tts.getVoices();
                if (vs != null) for (Voice v : vs) if (v.getName().equals(voice)) { tts.setVoice(v); break; }
            }
        } catch (Exception e) { }
        tts.setSpeechRate(rate);
        tts.setPitch(pitch);
        queued++;
        Bundle p = new Bundle();
        tts.speak(text, TextToSpeech.QUEUE_ADD, p, id);
    }

    void mute(boolean on) {
        if (muted == on) return;
        muted = on;
        int dir = on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
        try { am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0); } catch (Exception e) { }
        try { am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0); } catch (Exception e) { }
    }

    void startListening(String lang, boolean interim) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLang = lang; pendingInterim = interim;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { js("__srError('service-not-allowed');__srEnd()"); return; }
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float v) { }
                public void onBufferReceived(byte[] b) { }
                public void onEndOfSpeech() { }
                public void onEvent(int t, Bundle b) { }
                public void onPartialResults(Bundle b) { send(b, false); }
                public void onResults(Bundle b) { send(b, true); srDone(null); }
                public void onError(int e) {
                    String m = e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "not-allowed"
                            : (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) ? "no-speech"
                            : e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ? "network" : "aborted";
                    srDone(m);
                }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        mute(true);
        listening = true;
        try { rec.startListening(i); } catch (Exception e) { srDone("aborted"); }
    }

    void send(Bundle b, boolean fin) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r == null || r.isEmpty()) return;
        js("window.__srResult&&__srResult(" + JSONObject.quote(new JSONArray(r).toString()) + "," + fin + ")");
    }

    void srDone(String err) {
        listening = false;
        web.postDelayed(() -> { if (!listening) mute(false); }, 1500);
        if (err != null) js("window.__srError&&__srError('" + err + "')");
        js("window.__srEnd&&__srEnd()");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_MIC) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED && "cmd".equals(pendingLang)) { cmdOn = true; startVosk(); }
        else if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED && pendingLang != null) startListening(pendingLang, pendingInterim);
        else js("window.__srError&&__srError('not-allowed');window.__srEnd&&__srEnd()");
        pendingLang = null;
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCb = null;
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(function(){var m=document.getElementById('modal');if(m&&m.classList.contains('show')){var c=document.querySelector('#sheet .backbtn')||null;if(c){c.click();}else{m.classList.remove('show');}return 1;}return 0;})()", v -> {
            if (!"1".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onPause() { super.onPause(); mute(false); }

    @Override
    protected void onDestroy() {
        mute(false);
        if (rec != null) rec.destroy();
        stopVosk();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    class Bridge {
        @JavascriptInterface public void speak(String id, String text, double rate, double pitch, String voice) {
            runOnUiThread(() -> speakNow(id, text, (float) rate, (float) pitch, voice));
        }
        @JavascriptInterface public void stop() { runOnUiThread(() -> { if (tts != null) tts.stop(); queued = 0; dropFocus(); }); }
        @JavascriptInterface public String voices() {
            JSONArray a = new JSONArray();
            try {
                ArrayList<String> names = new ArrayList<>();
                Set<Voice> vs = tts != null ? tts.getVoices() : null;
                if (vs != null) for (Voice v : vs) {
                    Locale l = v.getLocale();
                    if ("es".equals(l.getLanguage()) && "ES".equals(l.getCountry()) && !v.getFeatures().contains("notInstalled")) names.add(v.getName());
                }
                Collections.sort(names);
                for (String n : names) a.put(n);
            } catch (Exception e) { }
            return a.toString();
        }
        @JavascriptInterface public void listen(String lang, boolean interim, String mode) {
            runOnUiThread(() -> {
                if ("cmd".equals(mode) && !voskFailed) {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        pendingLang = "cmd"; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC); return;
                    }
                    cmdOn = true; startVosk();
                } else { cmdOn = false; stopVosk(); startListening(lang, interim); }
            });
        }
        @JavascriptInterface public void stopListening() {
            runOnUiThread(() -> {
                cmdOn = false; stopVosk();
                if (rec != null && listening) { try { rec.cancel(); } catch (Exception e) { } srDone(null); }
            });
        }
        @JavascriptInterface public void ping(double f, double vol, double dec) { MainActivity.this.ping(f, vol, dec); }
        @JavascriptInterface public boolean saveFile(String name, String data) {
            try {
                byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(bytes); }
                } else {
                    File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Guardado en Descargas", Toast.LENGTH_SHORT).show());
                return true;
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public String version() { return "apk"; }
    }
}
