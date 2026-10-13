
package com.umrah.guide;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView webView;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;

    private static final int SPEECH_REQUEST = 1001;
    private static final int MIC_PERMISSION_REQUEST = 1002;

    private String pendingLanguage = "ur-PK";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient());

        webView.addJavascriptInterface(
            new VoiceBridge(), "AndroidVoice"
        );

        textToSpeech = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    public class VoiceBridge {

        @JavascriptInterface
        public void listen(String language) {
            runOnUiThread(() -> startListening(language));
        }

        @JavascriptInterface
        public void speak(String text, String language) {
            runOnUiThread(() -> speakText(text, language));
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> {
                if (textToSpeech != null) {
                    textToSpeech.stop();
                }
            });
        }
    }

    private void startListening(String language) {

        pendingLanguage = language;

        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                MIC_PERMISSION_REQUEST
            );
            return;
        }

        launchSpeechRecognition();
    }

    private void launchSpeechRecognition() {

        Intent intent = new Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        );

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE,
            pendingLanguage
        );

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
            pendingLanguage
        );

        intent.putExtra(
            RecognizerIntent.EXTRA_MAX_RESULTS,
            1
        );

        try {
            startActivityForResult(intent, SPEECH_REQUEST);
        } catch (ActivityNotFoundException e) {
            sendSpeechError(
                "Speech Recognition اس موبائل پر دستیاب نہیں۔"
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        );

        if (requestCode == MIC_PERMISSION_REQUEST) {
            if (grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                launchSpeechRecognition();

            } else {
                sendSpeechError("مائیکروفون کی اجازت نہیں ملی۔");
            }
        }
    }

    @Override
    protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {
        super.onActivityResult(
            requestCode, resultCode, data
        );

        if (requestCode == SPEECH_REQUEST) {

            if (resultCode == RESULT_OK && data != null) {

                ArrayList<String> results =
                    data.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                    );

                if (results != null && !results.isEmpty()) {
                    sendSpeechResult(results.get(0));
                    return;
                }
            }

            sendSpeechError(
                "آواز پہچانی نہیں جا سکی۔ دوبارہ کوشش کریں۔"
            );
        }
    }

    private void speakText(String text, String language) {

        if (textToSpeech == null || !ttsReady) {
            sendSpeechError("Voice Engine ابھی تیار نہیں۔");
            return;
        }

        Locale locale = Locale.forLanguageTag(language);
        int result = textToSpeech.setLanguage(locale);

        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED) {

            sendSpeechError(
                "اس زبان کی آواز موبائل میں انسٹال نہیں۔"
            );
            return;
        }

        textToSpeech.setSpeechRate(0.85f);

        textToSpeech.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "UmrahConversation"
        );
    }

    private void sendSpeechResult(String result) {
        if (webView == null) return;

        String script =
            "window.onNativeSpeechResult(" +
            org.json.JSONObject.quote(result) +
            ");";

        webView.evaluateJavascript(script, null);
    }

    private void sendSpeechError(String error) {
        if (webView == null) return;

        String script =
            "window.onNativeSpeechError(" +
            org.json.JSONObject.quote(error) +
            ");";

        webView.evaluateJavascript(script, null);
    }

    @Override
    protected void onDestroy() {

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }

        if (webView != null) {
            webView.removeJavascriptInterface("AndroidVoice");
            webView.destroy();
        }

        super.onDestroy();
    }
}
