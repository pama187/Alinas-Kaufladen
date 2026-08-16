package de.kleinermarkt.kasse;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Size;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
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
import androidx.core.content.FileProvider;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {

    private static final int CAMERA_PERMISSION_CODE = 1001;
    private static final int FILE_CHOOSER_CODE = 2001;

    private WebView webView;
    private PreviewView previewView;
    private ValueCallback<Uri[]> filePathCallback;
    private String cameraPhotoPath;
    private PermissionRequest pendingWebPermissionRequest;
    private boolean pendingContinuousScanStart = false;

    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private ProcessCameraProvider cameraProvider;
    private volatile boolean scanningActive = false;
    private volatile String activeCode = null;
    private volatile long lastSeenTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            initApp();
        } catch (Throwable t) {
            showError(t);
        }
    }

    private void initApp() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }

        FrameLayout root = new FrameLayout(this);

        // Kamera-Vorschau liegt UNTER der WebView - wird nur an der Stelle
        // sichtbar, wo die Webseite selbst transparent ist (#reader-Box).
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setVisibility(View.GONE);
        previewView.setClipToOutline(true);
        previewView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 40f);
            }
        });
        root.addView(previewView, new FrameLayout.LayoutParams(0, 0));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void startContinuousScan() {
                runOnUiThread(() -> beginContinuousScan());
            }

            @JavascriptInterface
            public void stopContinuousScan() {
                runOnUiThread(() -> endContinuousScan());
            }

            @JavascriptInterface
            public void setPreviewRect(int x, int y, int width, int height) {
                runOnUiThread(() -> updatePreviewRect(x, y, width, height));
            }
        }, "AndroidScanner");

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        pendingWebPermissionRequest = request;
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                              FileChooserParams params) {
                try {
                    filePathCallback = callback;

                    Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                        File photoFile = null;
                        try {
                            photoFile = createImageFile();
                        } catch (IOException ex) {
                            photoFile = null;
                        }
                        if (photoFile != null) {
                            cameraPhotoPath = "file:" + photoFile.getAbsolutePath();
                            Uri photoUri = FileProvider.getUriForFile(MainActivity.this,
                                    "de.kleinermarkt.kasse.fileprovider", photoFile);
                            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                        }
                    }

                    Intent pickIntent = new Intent(Intent.ACTION_GET_CONTENT);
                    pickIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    pickIntent.setType("image/*");

                    Intent chooserIntent = Intent.createChooser(pickIntent, "Foto auswählen");
                    if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{takePictureIntent});
                    }

                    startActivityForResult(chooserIntent, FILE_CHOOSER_CODE);
                    return true;
                } catch (Throwable t) {
                    showError(t);
                    return false;
                }
            }
        });

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    /* ---------- Dauerhafter Kamera-Scanner ---------- */

    private void beginContinuousScan() {
        if (scanningActive) return;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pendingContinuousScanStart = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
            return;
        }

        scanningActive = true;
        activeCode = null;
        previewView.setVisibility(View.VISIBLE);

        cameraExecutor = Executors.newSingleThreadExecutor();
        if (barcodeScanner == null) {
            BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build();
            barcodeScanner = BarcodeScanning.getClient(options);
        }

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();

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
                scanningActive = false;
                previewView.setVisibility(View.GONE);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void endContinuousScan() {
        scanningActive = false;
        activeCode = null;
        if (previewView != null) previewView.setVisibility(View.GONE);
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
            cameraExecutor = null;
        }
    }

    private void updatePreviewRect(int x, int y, int width, int height) {
        if (previewView == null || width <= 0 || height <= 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewView.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(width, height);
        } else {
            lp.width = width;
            lp.height = height;
        }
        lp.leftMargin = x;
        lp.topMargin = y;
        previewView.setLayoutParams(lp);
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(ImageProxy imageProxy) {
        if (!scanningActive) {
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
                                    // Nur auslösen, wenn sich der Code geändert hat -
                                    // solange derselbe Code im Bild bleibt, passiert nichts.
                                    if (!raw.equals(activeCode)) {
                                        activeCode = raw;
                                        deliverScannedCode(raw);
                                    }
                                }
                            } else if (activeCode != null && now - lastSeenTime > 600) {
                                // Kein Code mehr seit 600ms im Bild -> gilt als "entfernt",
                                // ein erneutes Zeigen darf wieder auslösen.
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

    private void deliverScannedCode(String code) {
        runOnUiThread(() -> {
            if (webView != null) {
                String escaped = code.replace("\\", "\\\\").replace("'", "\\'");
                webView.evaluateJavascript("handleScannedCode('" + escaped + "')", null);
            }
        });
    }

    /* ---------- Foto-Aufnahme für Produkte ---------- */

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(new Date());
        File storageDir = getExternalCacheDir();
        return File.createTempFile("PHOTO_" + timeStamp, ".jpg", storageDir);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

            if (pendingWebPermissionRequest != null) {
                if (granted) pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
                else pendingWebPermissionRequest.deny();
                pendingWebPermissionRequest = null;
            }

            if (pendingContinuousScanStart) {
                pendingContinuousScanStart = false;
                if (granted) beginContinuousScan();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER_CODE || filePathCallback == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }

        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            } else if (cameraPhotoPath != null) {
                results = new Uri[]{Uri.parse(cameraPhotoPath)};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
        cameraPhotoPath = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Sicherheitsnetz: Kamera IMMER abschalten, sobald die App
        // in den Hintergrund geht - unabhängig davon, was JS gerade macht.
        endContinuousScan();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void showError(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));

        TextView tv = new TextView(this);
        tv.setText("Fehler:\n\n" + sw.toString());
        tv.setTextColor(Color.WHITE);
        tv.setBackgroundColor(Color.BLACK);
        tv.setPadding(24, 60, 24, 24);
        tv.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        setContentView(scroll);
    }
}
