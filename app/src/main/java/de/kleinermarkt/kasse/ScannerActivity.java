package de.kleinermarkt.kasse;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScannerActivity extends ComponentActivity {

    private static final int CAMERA_PERM_CODE = 5001;

    private static final int COLOR_CREAM = Color.parseColor("#FFF8EC");
    private static final int COLOR_BROWN = Color.parseColor("#7A4E2D");
    private static final int COLOR_BROWN_DARK = Color.parseColor("#4E3218");
    private static final int COLOR_GREEN = Color.parseColor("#3F9463");

    public static ScannerActivity instance;

    private PreviewView previewView;
    private TextView feedbackText;
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private volatile String activeCode = null;
    private volatile long lastSeenTime = 0;
    private volatile boolean running = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        try {
            initScanner();
        } catch (Throwable t) {
            showError(t);
        }
    }

    private void initScanner() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_CREAM);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(COLOR_BROWN);
        topBar.setPadding(28, 36, 20, 20);
        TextView title = new TextView(this);
        title.setText("🧾 Scanne die Artikel");
        title.setTextColor(COLOR_CREAM);
        title.setTextSize(19);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        feedbackText = new TextView(this);
        feedbackText.setText("Halte einen QR-Code vor die Kamera");
        feedbackText.setTextColor(COLOR_BROWN_DARK);
        feedbackText.setTextSize(17);
        feedbackText.setGravity(Gravity.CENTER);
        feedbackText.setPadding(24, 28, 24, 20);
        root.addView(feedbackText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout borderFrame = new FrameLayout(this);
        GradientDrawable borderBg = new GradientDrawable();
        borderBg.setColor(COLOR_BROWN);
        borderBg.setCornerRadius(44f);
        borderFrame.setBackground(borderBg);
        borderFrame.setPadding(10, 10, 10, 10);

        previewView = new PreviewView(this);
        previewView.setClipToOutline(true);
        previewView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 36f);
            }
        });
        borderFrame.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams borderParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        borderParams.leftMargin = 28;
        borderParams.rightMargin = 28;
        borderParams.bottomMargin = 24;
        root.addView(borderFrame, borderParams);

        Button doneBtn = new Button(this);
        doneBtn.setText("✅ Fertig");
        doneBtn.setAllCaps(false);
        doneBtn.setTextColor(Color.WHITE);
        doneBtn.setTextSize(17);
        GradientDrawable doneBg = new GradientDrawable();
        doneBg.setColor(COLOR_GREEN);
        doneBg.setCornerRadius(32f);
        doneBtn.setBackground(doneBg);
        doneBtn.setPadding(40, 28, 40, 28);
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        doneParams.leftMargin = 28;
        doneParams.rightMargin = 28;
        doneParams.bottomMargin = 36;
        doneBtn.setOnClickListener(v -> finish());
        root.addView(doneBtn, doneParams);

        setContentView(root);

        cameraExecutor = Executors.newSingleThreadExecutor();
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERM_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERM_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                finish();
            }
        }
    }

    private void startCamera() {
        try {
            ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
            future.addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider = future.get();

                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    ImageAnalysis analysis = new ImageAnalysis.Builder()
                            .setTargetResolution(new Size(1920, 1080))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                    analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                    cameraProvider.unbindAll();
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                } catch (Throwable t) {
                    runOnUiThread(() -> showError(t));
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (Throwable t) {
            showError(t);
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(ImageProxy imageProxy) {
        if (!running) {
            imageProxy.close();
            return;
        }
        try {
            if (imageProxy.getImage() != null) {
                InputImage image = InputImage.fromMediaImage(
                        imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
                barcodeScanner.process(image)
                        .addOnSuccessListener(barcodes -> {
                            long now = System.currentTimeMillis();
                            if (!barcodes.isEmpty()) {
                                String raw = barcodes.get(0).getRawValue();
                                if (raw != null && !raw.isEmpty()) {
                                    lastSeenTime = now;
                                    if (!raw.equals(activeCode)) {
                                        activeCode = raw;
                                        if (MainActivity.instance != null) {
                                            MainActivity.instance.deliverScannedCode(raw);
                                        }
                                    }
                                }
                            } else if (activeCode != null && now - lastSeenTime > 600) {
                                activeCode = null;
                            }
                        })
                        .addOnCompleteListener(task -> imageProxy.close());
            } else {
                imageProxy.close();
            }
        } catch (Throwable t) {
            imageProxy.close();
        }
    }

    // Wird von MainActivity aufgerufen, nachdem JS den Code verarbeitet hat
    public void showFeedback(String message) {
        runOnUiThread(() -> {
            if (feedbackText != null) feedbackText.setText(message);
        });
    }

    private void showError(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));

        TextView tv = new TextView(this);
        tv.setText("Scanner-Fehler:\n\n" + sw.toString());
        tv.setTextColor(Color.WHITE);
        tv.setBackgroundColor(Color.BLACK);
        tv.setPadding(24, 60, 24, 24);
        tv.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        setContentView(scroll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        if (instance == this) instance = null;
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (barcodeScanner != null) barcodeScanner.close();
    }
}
