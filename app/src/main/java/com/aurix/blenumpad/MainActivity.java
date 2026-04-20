package com.aurix.blenumpad;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity implements BleHidService.StatusListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int REQUEST_ENABLE_BT   = 1002;

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView  tvStatus;
    private TextView  tvLog;
    private Button    btnToggle;
    private View      connectionDot;
    private ScrollView scrollLog;

    // ── BLE ───────────────────────────────────────────────────────────────────
    private BleHidService bleHidService;
    private boolean isRunning = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();

    // ── Device name（ESP32 会扫描这个名字）────────────────────────────────────
    private static final String DEVICE_NAME = "aurix_btkbd";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        bleHidService = new BleHidService(this);
        bleHidService.setStatusListener(this);

        checkPermissionsAndStart();
    }

    private void initViews() {
        tvStatus      = findViewById(R.id.tv_status);
        tvLog         = findViewById(R.id.tv_log);
        btnToggle     = findViewById(R.id.btn_toggle);
        connectionDot = findViewById(R.id.connection_dot);
        scrollLog     = findViewById(R.id.scroll_log);

        btnToggle.setOnClickListener(v -> {
            if (isRunning) {
                stopBle();
            } else {
                startBle();
            }
        });

        // 键盘按钮
        setupNumpadButtons();
    }

    private void setupNumpadButtons() {
        // 数字键 0-9
        int[] numBtnIds = {
                R.id.btn_0, R.id.btn_1, R.id.btn_2, R.id.btn_3,
                R.id.btn_4, R.id.btn_5, R.id.btn_6, R.id.btn_7,
                R.id.btn_8, R.id.btn_9
        };
        byte[] numKeyCodes = {
                HidUsage.KEY_KP_0, HidUsage.KEY_KP_1, HidUsage.KEY_KP_2, HidUsage.KEY_KP_3,
                HidUsage.KEY_KP_4, HidUsage.KEY_KP_5, HidUsage.KEY_KP_6, HidUsage.KEY_KP_7,
                HidUsage.KEY_KP_8, HidUsage.KEY_KP_9
        };
        String[] numLabels = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};

        for (int i = 0; i < numBtnIds.length; i++) {
            final byte keyCode = numKeyCodes[i];
            final String label = numLabels[i];
            Button btn = findViewById(numBtnIds[i]);
            if (btn != null) {
                btn.setOnClickListener(v -> sendKey(HidUsage.MOD_NONE, keyCode, label));
            }
        }

        // 功能键
        setupFuncBtn(R.id.btn_slash,     HidUsage.KEY_KP_SLASH,  "/");
        setupFuncBtn(R.id.btn_enter,     HidUsage.KEY_KP_ENTER,  "Enter");
        setupFuncBtn(R.id.btn_backspace, HidUsage.KEY_BACKSPACE,  "⌫");
        setupFuncBtn(R.id.btn_dot,       HidUsage.KEY_KP_DOT,    ".");
        setupFuncBtn(R.id.btn_plus,      HidUsage.KEY_KP_PLUS,   "+");
        setupFuncBtn(R.id.btn_minus,     HidUsage.KEY_KP_MINUS,  "-");
        setupFuncBtn(R.id.btn_star,      HidUsage.KEY_KP_STAR,   "*");

        // NumLock
        Button btnNumlock = findViewById(R.id.btn_numlock);
        if (btnNumlock != null) {
            btnNumlock.setOnClickListener(v -> {
                sendKey(HidUsage.MOD_NONE, HidUsage.KEY_NUMLOCK, "NumLock");
            });
        }
    }

    private void setupFuncBtn(int resId, byte keyCode, String label) {
        Button btn = findViewById(resId);
        if (btn != null) {
            btn.setOnClickListener(v -> sendKey(HidUsage.MOD_NONE, keyCode, label));
        }
    }

    // ── BLE 操作 ──────────────────────────────────────────────────────────────
    private void startBle() {
        if (!bleHidService.initialize()) return;

        // 检查蓝牙是否开启
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            return;
        }

        bleHidService.startAdvertising(DEVICE_NAME);
        isRunning = true;
        updateUI_Running(true);
        appendLog("▶ 开始广播，设备名: " + DEVICE_NAME);
    }

    private void stopBle() {
        // ✅ 先把 isRunning 置 false，再调 stopAdvertising
        isRunning = false;              // ✅ 先置 false，onDeviceDisconnected 不会重广播
        updateUI_Running(false);
        appendLog("■ 停止中...");
        bleHidService.stopAdvertising(); // 异步执行，断开完成后会回调 onDeviceDisconnected
    }

    private void sendKey(byte modifier, byte keyCode, String label) {
        if (!bleHidService.isConnected()) {
            appendLog("⚠ 未连接，无法发送: " + label);
            Toast.makeText(this, "未连接到 ESP32", Toast.LENGTH_SHORT).show();
            return;
        }
        bleHidService.sendKey(modifier, keyCode);
        appendLog("→ 发送按键: " + label + String.format(" (0x%02X)", keyCode));

        // 按键视觉反馈
        View btn = getCurrentFocus();
    }

    // ── BleHidService.StatusListener ─────────────────────────────────────────
    @Override
    public void onAdvertisingStarted() {
        setStatus("广播中，等待 ESP32 连接...", false);
        appendLog("📡 广播已启动");
    }

    @Override
    public void onDeviceConnected(String deviceName, String address) {
        setStatus("已连接: " + deviceName, true);
        appendLog("✅ ESP32 已连接: " + deviceName + " [" + address + "]");
        connectionDot.setBackgroundResource(R.drawable.dot_connected);
        Toast.makeText(this, "ESP32 已连接！", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDeviceDisconnected() {

        connectionDot.setBackgroundResource(R.drawable.dot_disconnected);

        if (isRunning) {
            // Passive disconnect: auto restart advertising
            setStatus("Disconnected, restarting advertising...", false);
            appendLog("❌ ESP32 disconnected, restarting advertising...");
            handler.postDelayed(() -> {
                if (isRunning && !bleHidService.isConnected()) {
                    // ✅ 重广播前先完整停止旧的 GattServer，再重新开
                    bleHidService.stopAdvertising();  // 确保旧资源释放
                    handler.postDelayed(() -> {
                        if (isRunning) {
                            bleHidService.startAdvertising(DEVICE_NAME);
                            appendLog("🔄 Restarting advertising...");
                        }
                    }, 500);  // 等 stopAdvertising 异步完成
                }
            }, 1000);
        } else {
            // User manually stopped: update final status here
            setStatus("Stopped", false);
            appendLog("■ Advertising stopped and disconnected");
        }
    }

    @Override
    public void onKeyReceived(String keyName) {}

    @Override
    public void onError(String message) {
        //appendLog("⛔ 错误: " + message);
        appendLog("⛔ err: " + message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ── UI 辅助 ───────────────────────────────────────────────────────────────
    private void updateUI_Running(boolean running) {
        //btnToggle.setText(running ? "停止广播" : "开始广播");
        btnToggle.setText(running ? "Stop Advertising" : "Start Advertising");
        btnToggle.setBackgroundTintList(
                ContextCompat.getColorStateList(this,
                        running ? R.color.btn_stop : R.color.btn_start));
    }

    private void setStatus(String text, boolean connected) {
        runOnUiThread(() -> {
            tvStatus.setText(text);
            tvStatus.setTextColor(ContextCompat.getColor(this,
                    connected ? R.color.status_connected : R.color.status_normal));
        });
    }

    private void appendLog(String msg) {
        runOnUiThread(() -> {
            logBuffer.append(msg).append("\n");
            // 最多保留 50 行
            String full = logBuffer.toString();
            String[] lines = full.split("\n");
            if (lines.length > 50) {
                logBuffer.setLength(0);
                for (int i = lines.length - 50; i < lines.length; i++) {
                    logBuffer.append(lines[i]).append("\n");
                }
            }
            tvLog.setText(logBuffer.toString());
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ── 权限处理 ──────────────────────────────────────────────────────────────
    private void checkPermissionsAndStart() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        } else {
            // Android 11 及以下
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            //appendLog("✅ 权限已就绪，点击\"开始广播\"");
            appendLog("✅ Permissions granted. Tap \"Start Advertising\"");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            /*
            if (allGranted) {
                appendLog("✅ 权限已获取");
            } else {
                appendLog("⛔ 权限被拒绝，部分功能不可用");
                Toast.makeText(this, "需要蓝牙权限才能工作", Toast.LENGTH_LONG).show();
            }
            */
            if (allGranted) {
                appendLog("✅ Permissions granted");
            } else {
                appendLog("⛔ Permissions denied, some features unavailable");
                Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            startBle();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleHidService != null) {
            bleHidService.stopAdvertising();
        }
    }
}
