package com.umrah.guide;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private TextToSpeech textToSpeech;
    private SpeechRecognizer recognizer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean ttsReady = false;
    private boolean conversationActive = false;
    private boolean listening = false;
    private boolean waitingForTranslation = false;
    private int failures = 0;
    private String currentLanguage = "ur-PK";
    private String pendingLanguage = "ur-PK";
    private static final int MIC_PERMISSION_REQUEST = 1002;
    private static final int SPEECH_REQUEST = 1001;
    private boolean singleShot = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new VoiceBridge(), "AndroidVoice");
        textToSpeech = new TextToSpeech(this, status -> ttsReady = status == TextToSpeech.SUCCESS);
        if (textToSpeech != null) {
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) {
                    handler.post(() -> {
                        if (conversationActive && id.startsWith("conversation-")) {
                            waitingForTranslation = false;
                            currentLanguage = currentLanguage.equals("ur-PK") ? "ar-SA" : "ur-PK";
                            scheduleListen(500);
                        }
                    });
                }
                @Override public void onError(String id) {
                    handler.post(() -> {
                        if (conversationActive && id.startsWith("conversation-")) {
                            stopConversation();
                            sendSpeechError("آواز سنانے میں مسئلہ ہے۔ گفتگو دوبارہ شروع کریں۔");
                        }
                    });
                }
            });
        }
        webView.loadUrl("file:///android_asset/index.html");
    }

    public class VoiceBridge {
        @JavascriptInterface public void startConversation(String firstLanguage) {
            runOnUiThread(() -> {
                stopConversation();
                currentLanguage = firstLanguage.equals("ar-SA") ? "ar-SA" : "ur-PK";
                conversationActive = true;
                singleShot = false;
                ensureMicPermission();
            });
        }
        @JavascriptInterface public void stopConversation() { runOnUiThread(() -> MainActivity.this.stopConversation()); }
        @JavascriptInterface public void resumeConversation() {
            runOnUiThread(() -> { if (conversationActive && waitingForTranslation) { waitingForTranslation = false; scheduleListen(500); } });
        }
        @JavascriptInterface public void listen(String language) {
            runOnUiThread(() -> {
                stopConversation();
                pendingLanguage = language;
                singleShot = true;
                ensureMicPermission();
            });
        }
        @JavascriptInterface public void speak(String text, String language) {
            runOnUiThread(() -> speakText(text, language, conversationActive));
        }
        @JavascriptInterface public void stop() {
            runOnUiThread(() -> { stopConversation(); if (textToSpeech != null) textToSpeech.stop(); });
        }
    }

    private void ensureMicPermission() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST);
            return;
        }
        if (conversationActive) scheduleListen(150);
        else if (singleShot) launchSingleShot();
    }

    private void scheduleListen(long delay) {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> { if (conversationActive && !waitingForTranslation) startContinuousListening(); }, delay);
    }

    private void startContinuousListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopConversation(); sendSpeechError("Speech Recognition اس موبائل پر دستیاب نہیں۔"); return;
        }
        destroyRecognizer();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        final SpeechRecognizer activeRecognizer = recognizer;
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { sendStatus(currentLanguage.equals("ur-PK") ? "اردو سن رہے ہیں…" : "عربی سن رہے ہیں…"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle results) { }
            @Override public void onEvent(int eventType, Bundle params) { }
            @Override public void onResults(Bundle results) {
                if (recognizer != activeRecognizer) return;
                listening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (!conversationActive) return;
                if (matches != null && !matches.isEmpty() && !matches.get(0).trim().isEmpty()) {
                    failures = 0;
                    waitingForTranslation = true;
                    sendSpeechResult(matches.get(0));
                } else retryListening();
            }
            @Override public void onError(int error) {
                if (recognizer != activeRecognizer) return;
                listening = false;
                if (!conversationActive) return;
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechRecognizer.ERROR_CLIENT) {
                    stopConversation(); sendSpeechError("مائیک یا Speech Recognition میں مسئلہ ہے۔");
                } else retryListening();
            }
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try { listening = true; recognizer.startListening(intent); }
        catch (Exception e) { listening = false; stopConversation(); sendSpeechError("مائیک شروع نہیں ہو سکا۔"); }
    }

    private void retryListening() {
        failures++;
        if (failures >= 5) {
            stopConversation(); sendSpeechError("آواز نہیں مل رہی۔ دوبارہ Start دبائیں۔");
        } else scheduleListen(900);
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        listening = false;
    }

    private void stopConversation() {
        conversationActive = false;
        waitingForTranslation = false;
        singleShot = false;
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        if (textToSpeech != null) textToSpeech.stop();
    }

    private void launchSingleShot() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, pendingLanguage);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, pendingLanguage);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try { startActivityForResult(intent, SPEECH_REQUEST); }
        catch (ActivityNotFoundException e) { sendSpeechError("Speech Recognition دستیاب نہیں۔"); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == MIC_PERMISSION_REQUEST) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) ensureMicPermission();
            else { stopConversation(); sendSpeechError("مائیکروفون کی اجازت نہیں ملی۔"); }
        }
    }

    @Override protected void onActivityResult(int code, int resultCode, Intent data) {
        super.onActivityResult(code, resultCode, data);
        if (code == SPEECH_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (results != null && !results.isEmpty()) { sendSpeechResult(results.get(0)); return; }
            }
            sendSpeechError("آواز پہچانی نہیں گئی۔");
        }
    }

    private void speakText(String text, String language, boolean conversation) {
        if (textToSpeech == null || !ttsReady) {
            if (conversation) stopConversation();
            sendSpeechError("Voice Engine ابھی تیار نہیں۔"); return;
        }
        Locale locale = Locale.forLanguageTag(language);
        int result = textToSpeech.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (conversation) stopConversation();
            sendSpeechError("اس زبان کی آواز موبائل میں انسٹال نہیں۔"); return;
        }
        textToSpeech.setSpeechRate(0.85f);
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, conversation ? "conversation-translation" : "manual-translation");
    }

    private void sendJS(String js) { if (webView != null) webView.evaluateJavascript(js, null); }
    private void sendSpeechResult(String result) { sendJS("window.onNativeSpeechResult(" + org.json.JSONObject.quote(result) + ");"); }
    private void sendSpeechError(String error) { sendJS("window.onNativeSpeechError(" + org.json.JSONObject.quote(error) + ");"); }
    private void sendStatus(String message) { sendJS("window.onNativeSpeechStatus&&window.onNativeSpeechStatus(" + org.json.JSONObject.quote(message) + ");"); }

    @Override protected void onPause() {
        super.onPause();
        if (conversationActive) { stopConversation(); sendStatus("گفتگو رک گئی۔ دوبارہ Start دبائیں۔"); }
    }
    @Override protected void onDestroy() {
        stopConversation();
        if (textToSpeech != null) { textToSpeech.stop(); textToSpeech.shutdown(); }
        if (webView != null) { webView.removeJavascriptInterface("AndroidVoice"); webView.destroy(); }
        super.onDestroy();
    }
}
