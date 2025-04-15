package com.example.cbtexambrowser;

import android.Manifest;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_WRITE_SETTINGS = 1001;
    private static final int REQUEST_CODE_SYSTEM_ALERT_WINDOW = 1002;
    private static final int REQUEST_CODE_AUDIO_SETTINGS = 1003;

    private EditText urlEditText;
    private Button startExamButton;
    private WebView examWebView;
    private ProgressBar progressBar;
    private Toolbar toolbar;

    private ToneGenerator toneGenerator;

    private TextView batteryStatusTextView;
    private TextView clockTextView;

    private Handler clockHandler;
    private Runnable clockRunnable;

    private BroadcastReceiver batteryReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Prevent screenshots and screen recording
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.topAppBar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle("CBT Exam Browser");

        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_exit) {
                    showExitConfirmation();
                    return true;
                }
                return false;
            }
        });

        urlEditText = findViewById(R.id.urlEditText);
        startExamButton = findViewById(R.id.startExamButton);
        examWebView = findViewById(R.id.examWebView);
        progressBar = findViewById(R.id.progressBar);

        batteryStatusTextView = findViewById(R.id.batteryStatusTextView);
        clockTextView = findViewById(R.id.clockTextView);

        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);

        startExamButton.setOnClickListener(v -> {
            String url = urlEditText.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(MainActivity.this, "Please enter a valid exam URL", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            startExam(url);
        });

        checkAndRequestPermissions();

        startClock();
        registerBatteryReceiver();
    }

    private void startExam(String url) {
        urlEditText.setVisibility(View.GONE);
        startExamButton.setVisibility(View.GONE);
        examWebView.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);

        WebSettings webSettings = examWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);

        examWebView.setWebViewClient(new SecureWebViewClient(url));

        examWebView.loadUrl(url);

        // Start lock task mode (kiosk mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (!isInLockTaskMode()) {
                startLockTask();
            }
        }
    }

    private boolean isInLockTaskMode() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        } else {
            // For older versions, approximate by checking if lock task is active
            return false;
        }
    }

    private class SecureWebViewClient extends WebViewClient {
        private final String allowedUrlPrefix;

        SecureWebViewClient(String allowedUrlPrefix) {
            this.allowedUrlPrefix = allowedUrlPrefix;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.startsWith(allowedUrlPrefix)) {
                return false; // Allow loading
            } else {
                playAlertSound();
                Toast.makeText(MainActivity.this, "Navigation blocked: Unauthorized URL", Toast.LENGTH_SHORT).show();
                return true; // Block loading
            }
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progressBar.setVisibility(View.GONE);
            super.onPageFinished(view, url);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
            Toast.makeText(MainActivity.this, "Failed to load exam page. Please check your connection.", Toast.LENGTH_LONG).show();
            super.onReceivedError(view, request, error);
        }
    }

    private void playAlertSound() {
        if (toneGenerator != null) {
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500);
        }
    }

    @Override
    public void onBackPressed() {
        // Disable back button to prevent leaving the exam accidentally
        if (examWebView.getVisibility() == View.VISIBLE && examWebView.canGoBack()) {
            examWebView.goBack();
        } else {
            playAlertSound();
            Toast.makeText(this, "Back navigation is disabled during the exam.", Toast.LENGTH_SHORT).show();
            // Do not call super.onBackPressed() to prevent exit
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Play alert sound if user tries to leave app (home button, recent apps)
        playAlertSound();
        Toast.makeText(this, "Leaving the exam is not allowed.", Toast.LENGTH_SHORT).show();
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_WRITE_SETTINGS);
            }
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_SYSTEM_ALERT_WINDOW);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.MODIFY_AUDIO_SETTINGS}, REQUEST_CODE_AUDIO_SETTINGS);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_WRITE_SETTINGS) {
            if (!Settings.System.canWrite(this)) {
                Toast.makeText(this, "Write settings permission is required for full security.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_CODE_SYSTEM_ALERT_WINDOW) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission is required for full security.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Block volume keys and other keys if needed
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
            playAlertSound();
            Toast.makeText(this, "Volume keys are disabled during the exam.", Toast.LENGTH_SHORT).show();
            return true; // Consume event
        }
        return super.dispatchKeyEvent(event);
    }

    private void startClock() {
        clockHandler = new Handler();
        clockRunnable = new Runnable() {
            @Override
            public void run() {
                String currentTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                clockTextView.setText(currentTime);
                clockHandler.postDelayed(this, 60000); // Update every minute
            }
        };
        clockHandler.post(clockRunnable);
    }

    private void registerBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int batteryPct = (int) ((level / (float) scale) * 100);
                batteryStatusTextView.setText("Battery: " + batteryPct + "%");
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, filter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (clockHandler != null && clockRunnable != null) {
            clockHandler.removeCallbacks(clockRunnable);
        }
        if (batteryReceiver != null) {
            unregisterReceiver(batteryReceiver);
        }
        if (toneGenerator != null) {
            toneGenerator.release();
        }
    }

    private void showExitConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("Exit Exam")
                .setMessage("Are you sure you want to exit the exam?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        stopLockTask();
                    }
                    finish();
                })
                .setNegativeButton("No", null)
                .show();
    }
}
