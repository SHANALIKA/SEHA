package com.example.seha;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FaceScanActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 100;

    PreviewView previewView;
    TextView tvStatus;
    Button btnCapture, btnSwitchCamera;
    ImageView btnBack;

    ImageCapture imageCapture;
    ExecutorService cameraExecutor;
    ProcessCameraProvider cameraProvider;
    boolean useBackCamera = true;  // start with back camera

    OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_face_scan);

        previewView = findViewById(R.id.previewView);
        tvStatus = findViewById(R.id.tvStatus);
        btnCapture = findViewById(R.id.btnCapture);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        btnBack = findViewById(R.id.btnBack);

        cameraExecutor = Executors.newSingleThreadExecutor();

        btnBack.setOnClickListener(v -> finish());
        btnCapture.setOnClickListener(v -> captureAndRecognize());

        // Server IP changes with every Wi-Fi / hotspot -> change it inside the app (no rebuild)
        tvStatus.setOnLongClickListener(v -> {
            AppConfig.promptFaceServer(this);
            return true;
        });
        // the scanner may not be logged in -> sign in anonymously so Firestore can be read
        Fs.ensureSignedIn(() -> { });

        if (btnSwitchCamera != null) {
            btnSwitchCamera.setText("Front");
            btnSwitchCamera.setOnClickListener(v -> {
                useBackCamera = !useBackCamera;
                btnSwitchCamera.setText(useBackCamera ? "Front" : "Back");
                startCamera();
            });
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_CODE);
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector selector = useBackCamera
                        ? CameraSelector.DEFAULT_BACK_CAMERA
                        : CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);

            } catch (Exception e) {
                Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureAndRecognize() {
        if (imageCapture == null) return;

        btnCapture.setEnabled(false);
        tvStatus.setText("Scanning...");
        startScanTicker();

        File photoFile = new File(getCacheDir(), "scan_" + System.currentTimeMillis() + ".jpg");

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(options, cameraExecutor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        sendToServer(photoFile);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        runOnUiThread(() -> {
                            tvStatus.setText("Capture failed");
                            btnCapture.setEnabled(true);
                            Toast.makeText(FaceScanActivity.this,
                                    exception.getMessage(), Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private final android.os.Handler tickHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long scanStartedAt = 0;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (btnCapture.isEnabled()) return;          // finished (every result re-enables the button)
            long sec = (System.currentTimeMillis() - scanStartedAt) / 1000;
            tvStatus.setText("Scanning... " + sec + "s" + (sec > 8 ? "  (please wait, matching face)" : ""));
            tickHandler.postDelayed(this, 1000);
        }
    };

    private void startScanTicker() {
        scanStartedAt = System.currentTimeMillis();
        tickHandler.removeCallbacks(ticker);
        tickHandler.postDelayed(ticker, 1000);
    }

    // ---- person not registered: call 1990 or send the nearest ambulance
    private final androidx.activity.result.ActivityResultLauncher<String[]> locationPerm =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts
                    .RequestMultiplePermissions(), r -> {
                if (EmergencyDispatch.hasLocationPermission(this)) dispatchUnknown();
                else Toast.makeText(this, "Location permission is needed to send an ambulance",
                        Toast.LENGTH_LONG).show();
            });

    private void showUnknownDialog(String detail) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Person not registered")
                .setMessage("This face is not registered in SEHA, so there are no medical details.\n\n"
                        + detail + "\n\n"
                        + "You can send the nearest ambulance or call 1990.")
                .setPositiveButton("🚑 Send nearest ambulance", (d, w) -> {
                    if (EmergencyDispatch.hasLocationPermission(this)) dispatchUnknown();
                    else locationPerm.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION});
                })
                .setNeutralButton("📞 Call 1990", (d, w) -> startActivity(
                        new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:1990"))))
                .setNegativeButton("Scan again", null)
                .show();
    }

    private void dispatchUnknown() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("type", "unknown_scan");
        m.put("requesterRole", "public");
        m.put("patientName", "Unknown person");
        m.put("condition", "Unidentified (face not registered)");
        EmergencyDispatch.run(this, m, TripMapActivity.ROLE_PATIENT, null);
    }

    private void sendToServer(File photoFile) {
        try {
            // 12 MP camera photo -> small upright JPEG (upload is 20x smaller = much faster scan)
            File upload = ImageUtil.prepareForUpload(this, photoFile, 800, 85);
            RequestBody fileBody = RequestBody.create(upload, MediaType.parse("image/jpeg"));

            MultipartBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", upload.getName(), fileBody)
                    .build();

            Request request = new Request.Builder()
                    .url(AppConfig.recognizeUrl(this))
                    .post(requestBody)
                    .build();

            Response response = client.newCall(request).execute();
            String json = response.body() != null ? response.body().string() : "";
            Log.d("FaceScan", "HTTP " + response.code() + " body: " + json);

            JSONObject obj;
            try {
                obj = new JSONObject(json);
            } catch (Exception parseError) {
                final String msg = AppConfig.describeBadReply(response.code(), json,
                        AppConfig.recognizeUrl(this));
                runOnUiThread(() -> {
                    btnCapture.setEnabled(true);
                    tvStatus.setText("Server error (" + response.code() + ") - long-press to change address");
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
                return;
            }
            String status = obj.optString("status");

            runOnUiThread(() -> {
                btnCapture.setEnabled(true);

                if ("success".equals(status)) {
                    String personName = obj.optString("person");
                    tvStatus.setText("Found: " + personName);

                    Intent intent = new Intent(FaceScanActivity.this, PatientDetailsActivity.class);
                    intent.putExtra("person", personName);
                    // optional: if the server sends an id, use it instead of the (non-unique) name
                    String sid = obj.optString("uid", obj.optString("id", ""));
                    if (!sid.isEmpty() && !"null".equals(sid)) intent.putExtra("uid", sid);
                    startActivity(intent);
                    finish();

                } else if ("not_found".equals(status)) {
                    double closest = obj.optDouble("closest", -1);
                    String closestPerson = obj.optString("closest_person", "");
                    if ("null".equals(closestPerson)) closestPerson = "";
                    String detail = closest >= 0
                            ? String.format(java.util.Locale.US, "Closest match: %s (distance %.2f, limit %.2f)",
                            closestPerson.isEmpty() ? "?" : closestPerson, closest, obj.optDouble("limit", 0.68))
                            : "No registered face is close to this one.";
                    tvStatus.setText("Person not registered");
                    showUnknownDialog(detail);
                } else {
                    tvStatus.setText("Error");
                    Toast.makeText(this, obj.optString("message"), Toast.LENGTH_LONG).show();
                }
            });

        } catch (Exception e) {
            Log.e("FaceScan", "Error", e);
            final String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            runOnUiThread(() -> {
                btnCapture.setEnabled(true);
                tvStatus.setText("Server error");
                Toast.makeText(this,
                        "Cannot reach server.\n" + reason + "\nURL: " + AppConfig.recognizeUrl(this)
                                + "\n(Long-press the status text to change the server address)",
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission needed", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}