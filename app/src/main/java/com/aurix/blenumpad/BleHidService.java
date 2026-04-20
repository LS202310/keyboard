package com.aurix.blenumpad;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.Arrays;
import java.util.UUID;
import java.util.Queue;
import java.util.LinkedList; // 或其他实现类

import java.util.concurrent.CountDownLatch;//added 20260413
/**
 * BLE HID Peripheral Service
 * 模拟标准 BLE HID 数字键盘，供 ESP32S3 作为 BLE Host 连接
 *
 * 服务结构：
 *  - Generic Access (0x1800)
 *  - Generic Attribute (0x1801)
 *  - Device Information (0x180A)
 *  - Battery Service (0x180F)
 *  - HID Service (0x1812)  ← 核心
 */
@SuppressLint("MissingPermission")
public class BleHidService {

    private static final String TAG = "BleHidService";

    // ── Standard BLE UUIDs ──────────────────────────────────────────────────
    private static final UUID UUID_GENERIC_ACCESS        = uuid16(0x1800);
    private static final UUID UUID_GENERIC_ATTRIBUTE     = uuid16(0x1801);
    private static final UUID UUID_DEVICE_INFO           = uuid16(0x180A);
    private static final UUID UUID_BATTERY_SERVICE       = uuid16(0x180F);
    private static final UUID UUID_HID_SERVICE           = uuid16(0x1812);

    // Generic Access characteristics
    private static final UUID UUID_DEVICE_NAME           = uuid16(0x2A00);
    private static final UUID UUID_APPEARANCE            = uuid16(0x2A01);

    // Device Information characteristics
    private static final UUID UUID_MANUFACTURER_NAME     = uuid16(0x2A29);
    private static final UUID UUID_PNP_ID                = uuid16(0x2A50);

    // Battery characteristics
    private static final UUID UUID_BATTERY_LEVEL         = uuid16(0x2A19);

    // HID characteristics
    private static final UUID UUID_HID_INFO              = uuid16(0x2A4A);
    private static final UUID UUID_REPORT_MAP            = uuid16(0x2A4B);
    private static final UUID UUID_HID_CONTROL_POINT     = uuid16(0x2A4C);
    private static final UUID UUID_REPORT                = uuid16(0x2A4D);
    private static final UUID UUID_PROTOCOL_MODE         = uuid16(0x2A4E);

    // Descriptors
    private static final UUID UUID_CLIENT_CHAR_CONFIG    = uuid16(0x2902); // CCCD
    private static final UUID UUID_REPORT_REFERENCE      = uuid16(0x2908);
    private String pendingDeviceName = ""; //added 2020413
    // ── HID Report Map ───────────────────────────────────────────────────────
    // 标准数字键盘 HID Report Descriptor
    // Usage Page: Generic Desktop (0x01)
    // Usage: Keyboard (0x06)
    // 8字节输入报告：[modifier, reserved, key0~key5]
    private static final byte[] REPORT_MAP = {
            (byte)0x05, (byte)0x01,  // Usage Page (Generic Desktop)
            (byte)0x09, (byte)0x06,  // Usage (Keyboard)
            (byte)0xA1, (byte)0x01,  // Collection (Application)
            //(byte)0x85, (byte)0x01,  //   Report ID (1)
            // ❌ 删除这两行: (byte)0x85, (byte)0x01,  // Report ID (1)  ← 删掉！
            // Modifier byte
            // Modifier byte
            (byte)0x05, (byte)0x07,  //   Usage Page (Key Codes)
            (byte)0x19, (byte)0xE0,  //   Usage Minimum (224) - Left Ctrl
            (byte)0x29, (byte)0xE7,  //   Usage Maximum (231) - Right GUI
            (byte)0x15, (byte)0x00,  //   Logical Minimum (0)
            (byte)0x25, (byte)0x01,  //   Logical Maximum (1)
            (byte)0x75, (byte)0x01,  //   Report Size (1)
            (byte)0x95, (byte)0x08,  //   Report Count (8)
            (byte)0x81, (byte)0x02,  //   Input (Data, Variable, Absolute)
            // Reserved byte
            (byte)0x95, (byte)0x01,  //   Report Count (1)
            (byte)0x75, (byte)0x08,  //   Report Size (8)
            (byte)0x81, (byte)0x01,  //   Input (Constant)
            // Key array (6 keys)
            (byte)0x95, (byte)0x06,  //   Report Count (6)
            (byte)0x75, (byte)0x08,  //   Report Size (8)
            (byte)0x15, (byte)0x00,  //   Logical Minimum (0)
            (byte)0x25, (byte)0x65,  //   Logical Maximum (101)
            (byte)0x05, (byte)0x07,  //   Usage Page (Key Codes)
            (byte)0x19, (byte)0x00,  //   Usage Minimum (0)
            (byte)0x29, (byte)0x65,  //   Usage Maximum (101)
            (byte)0x81, (byte)0x00,  //   Input (Data, Array)
            (byte)0xC0               // End Collection
    };

    // HID Info: [bcdHID(2), bCountryCode(1), Flags(1)]
    // bcdHID=0x0111(HID 1.11), country=0x00, flags=0x02(normally connectable)
    private static final byte[] HID_INFO = { 0x11, 0x01, 0x00, 0x02 };

    // PnP ID: [VendorIDSource(1), VendorID(2), ProductID(2), ProductVersion(2)]
    // VendorIDSource=0x02(USB), VendorID=0x045E(Microsoft), ProductID=0x0750, Version=0x0100
    private static final byte[] PNP_ID = { 0x02, 0x5E, 0x04, 0x50, 0x07, 0x00, 0x01 };

    // ── Members ──────────────────────────────────────────────────────────────
    private final Context context;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothDevice connectedDevice;
    private BluetoothGattCharacteristic reportCharacteristic;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StatusListener listener;
    private boolean isAdvertising = false;
    private volatile boolean userStopped = false; // 用户主动停止标志，防止误触发自动重广播

    // ── Callback Interface ───────────────────────────────────────────────────
    public interface StatusListener {
        void onAdvertisingStarted();
        void onDeviceConnected(String deviceName, String address);
        void onDeviceDisconnected();
        void onKeyReceived(String keyName);  // 可选：用于UI显示
        void onError(String message);
    }

    public BleHidService(Context context) {
        this.context = context;
    }

    public void setStatusListener(StatusListener listener) {
        this.listener = listener;
    }

    // ── 初始化和启动 ──────────────────────────────────────────────────────────
    public boolean initialize() {
        bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            notifyError("不支持蓝牙");
            return false;
        }
        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            notifyError("蓝牙未开启");
            return false;
        }
        if (!bluetoothAdapter.isMultipleAdvertisementSupported()) {
            notifyError("不支持 BLE 广播");
            return false;
        }
        return true;
    }

    // startAdvertising() 里只做准备，不立即开广播
    private volatile CountDownLatch serviceAddLatch;

    private void openGattServerSync() {
        // openGattServer 必须在主线程调用
        final CountDownLatch openLatch = new CountDownLatch(1);
        handler.post(() -> {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
            openLatch.countDown();
        });
        try { openLatch.await(3, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { return; }

        if (gattServer == null) {
            notifyError("无法打开 GATT Server");
            return;
        }

        // 每个服务顺序添加，等待 onServiceAdded 回调后再添加下一个
        addServiceSync(this::addGenericAccessService);
        addServiceSync(this::addDeviceInfoService);
        addServiceSync(this::addBatteryService);
        addServiceSync(this::addHidService);

        Log.i(TAG, "所有 GATT Service 注册完成");
    }

    private void addServiceSync(Runnable addServiceCall) {
        serviceAddLatch = new CountDownLatch(1);
        // addService 必须在主线程调用
        handler.post(addServiceCall);
        try { serviceAddLatch.await(3, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        serviceAddLatch = null;
    }
    public void startAdvertising(String deviceName) {
        // ✅ 防重入：已有 GattServer 运行则直接返回
        if (gattServer != null) {
            Log.w(TAG, "startAdvertising: GattServer 已存在，跳过重复初始化");
            return;
        }
        this.pendingDeviceName = deviceName;
        bluetoothAdapter.setName(deviceName);

        // ✅ 在后台线程里顺序完成所有初始化，最后在主线程开广播
        new Thread(() -> {
            openGattServerSync();
            // openGattServerSync 里所有服务加完后，回到主线程开广播
            handler.post(this::doStartAdvertising);
        }, "ble-init").start();
    }
    // 把真正开广播的逻辑抽成一个方法
    private void doStartAdvertising() {

        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            notifyError("无法获取 BLE Advertiser");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        // 主广播包：只放 HID UUID，不放设备名
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)           // ← 改为 false
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(UUID_HID_SERVICE))
                .build();

        // Scan Response：放设备名，ESP32 主动扫描（ACTIVE scan）时会读取
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)            // ← 改为 true
                .build();

        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
        //Log.i(TAG, "开始广播，设备名: " + pendingDeviceName);
        Log.i(TAG, "Start advertising, device name: " + pendingDeviceName);
    }
    // 只停广播，保留 GATT 连接
    private void stopAdvertisingOnly() {
        if (advertiser != null && isAdvertising) {
            advertiser.stopAdvertising(advertiseCallback);
            isAdvertising = false;
        }
    }

    // 完全关闭，断开连接（用户主动停止时调用）
    public void stopAdvertising() {
        userStopped = true;

        serviceQueue.clear();
        if (serviceAddLatch != null) serviceAddLatch.countDown();

        // 第一步：停止广播
        if (advertiser != null && isAdvertising) {
            advertiser.stopAdvertising(advertiseCallback);
            isAdvertising = false;
        }

        // 第二步：有连接时，发断开请求，等回调确认后再 close
        //         没有连接时，直接 close
        if (connectedDevice != null && gattServer != null) {
            Log.i(TAG, "主动断开已连接设备: " + connectedDevice.getAddress());

            // ✅ 关键：先取消配对（如果已配对）
            if (connectedDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
                try {
                    // 反射调用 removeBond（Android 隐藏 API）
                    java.lang.reflect.Method m = connectedDevice.getClass()
                            .getMethod("removeBond", (Class<?>[]) null);
                    m.invoke(connectedDevice, (Object[]) null);
                    Log.i(TAG, "已移除配对信息");
                    // 给系统一点时间处理解绑
                    Thread.sleep(200);
                } catch (Exception e) {
                    Log.e(TAG, "移除配对失败", e);
                }
            }

            gattServer.cancelConnection(connectedDevice);
            // ✅ 不再 sleep，close 的时机移到 onConnectionStateChange 回调里

            // ✅ 等待断开回调，设置超时保护
            handler.postDelayed(() -> {
                if (connectedDevice != null) {
                    Log.w(TAG, "断开超时，强制关闭 GATT Server");
                    closeGattServer();
                }
            }, 1000);

        } else {
            closeGattServer();
        }
    }
    // ✅ 新增：统一的 close 方法
    private void closeGattServer() {
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
        }
        connectedDevice = null;
        reportCharacteristic = null;
        isAdvertising = false;   // ✅ 补上这个，防止状态残留
        userStopped = false;
        Log.i(TAG, "GATT Server 已关闭");
    }
    // ── 发送按键 ──────────────────────────────────────────────────────────────
    public void sendKey(byte modifier, byte keycode) {
        if (connectedDevice == null || reportCharacteristic == null) {
            Log.w(TAG, "没有连接的设备，无法发送按键");
            return;
        }

        // 发送 key down
        byte[] report = HidUsage.makeReport(modifier, keycode);
        sendReport(report);

        // 延迟 30ms 后发送 key up（模拟真实按键）
        handler.postDelayed(() -> {
            byte[] emptyReport = HidUsage.makeEmptyReport();
            sendReport(emptyReport);
        }, 30);
    }

    private void sendReport(byte[] report) {
        if (connectedDevice == null || reportCharacteristic == null || gattServer == null) return;
        reportCharacteristic.setValue(report);
        boolean ok = gattServer.notifyCharacteristicChanged(connectedDevice, reportCharacteristic, false);
        Log.d(TAG, "sendReport: " + bytesToHex(report) + " ok=" + ok);
    }

    public boolean isConnected() {
        return connectedDevice != null;
    }

    public boolean isAdvertising() {
        return isAdvertising;
    }

    //added 20260413
    private final Queue<Runnable> serviceQueue = new LinkedList<>();
    private void openGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
        if (gattServer == null) {
            notifyError("无法打开 GATT Server");
            return;
        }

        serviceQueue.clear();
        serviceQueue.add(this::addGenericAccessService);
        serviceQueue.add(this::addDeviceInfoService);
        serviceQueue.add(this::addBatteryService);
        serviceQueue.add(this::addHidService);

        // ✅ 用 handler.post 确保在主线程消息队列里执行，
        //    让当前调用栈先返回，避免阻塞回调投递
        handler.post(this::addNextService);
    }
    // ✅ 抽成独立方法，onServiceAdded 和 openGattServer 都用它
    private void addNextService() {
        Runnable next = serviceQueue.poll();
        if (next != null) {
            next.run();
        } else {
            Log.i(TAG, "所有 GATT Service 注册完成");
            doStartAdvertising();
        }
    }
    private void addGenericAccessService() {
        BluetoothGattService service = new BluetoothGattService(
                UUID_GENERIC_ACCESS, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Device Name
        BluetoothGattCharacteristic deviceName = new BluetoothGattCharacteristic(
                UUID_DEVICE_NAME,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        deviceName.setValue("btkbd");
        service.addCharacteristic(deviceName);

        // Appearance: 0x03C1 = Keyboard
        BluetoothGattCharacteristic appearance = new BluetoothGattCharacteristic(
                UUID_APPEARANCE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        appearance.setValue(new byte[]{(byte)0xC1, 0x03});
        service.addCharacteristic(appearance);

        gattServer.addService(service);
    }

    private void addDeviceInfoService() {
        BluetoothGattService service = new BluetoothGattService(
                UUID_DEVICE_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic manufacturer = new BluetoothGattCharacteristic(
                UUID_MANUFACTURER_NAME,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        manufacturer.setValue("Aurix");
        service.addCharacteristic(manufacturer);

        BluetoothGattCharacteristic pnp = new BluetoothGattCharacteristic(
                UUID_PNP_ID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        pnp.setValue(PNP_ID);
        service.addCharacteristic(pnp);

        gattServer.addService(service);
    }

    private void addBatteryService() {
        BluetoothGattService service = new BluetoothGattService(
                UUID_BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic battery = new BluetoothGattCharacteristic(
                UUID_BATTERY_LEVEL,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        battery.setValue(new byte[]{(byte) 100}); // 100%

        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                UUID_CLIENT_CHAR_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        battery.addDescriptor(cccd);

        service.addCharacteristic(battery);
        gattServer.addService(service);
    }

    private void addHidService() {
        BluetoothGattService service = new BluetoothGattService(
                UUID_HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // HID Info (READ)
        BluetoothGattCharacteristic hidInfo = new BluetoothGattCharacteristic(
                UUID_HID_INFO,
                BluetoothGattCharacteristic.PROPERTY_READ,
                //BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
                BluetoothGattCharacteristic.PERMISSION_READ);  // ✅ 去掉 _ENCRYPTED
        hidInfo.setValue(HID_INFO);
        service.addCharacteristic(hidInfo);

        // Report Map (READ)
        BluetoothGattCharacteristic reportMap = new BluetoothGattCharacteristic(
                UUID_REPORT_MAP,
                BluetoothGattCharacteristic.PROPERTY_READ,
                //BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
                BluetoothGattCharacteristic.PERMISSION_READ);  // ✅ 去掉 _ENCRYPTED
        reportMap.setValue(REPORT_MAP);
        service.addCharacteristic(reportMap);

        // HID Control Point (WRITE_NO_RESPONSE)
        BluetoothGattCharacteristic controlPoint = new BluetoothGattCharacteristic(
                UUID_HID_CONTROL_POINT,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                //    BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
                BluetoothGattCharacteristic.PERMISSION_WRITE);  // ✅ 去掉 _ENCRYPTED

        controlPoint.setValue(new byte[]{0x00});
        service.addCharacteristic(controlPoint);

        // Protocol Mode (READ | WRITE_NO_RESPONSE)
        BluetoothGattCharacteristic protocolMode = new BluetoothGattCharacteristic(
                UUID_PROTOCOL_MODE,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                //BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED | BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);  // ✅
        protocolMode.setValue(new byte[]{0x01}); // Report Protocol Mode
        service.addCharacteristic(protocolMode);

        // Input Report (READ | NOTIFY) ← 这是发送按键数据的关键特征
        reportCharacteristic = new BluetoothGattCharacteristic(
                UUID_REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                // BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
                BluetoothGattCharacteristic.PERMISSION_READ);  // ✅ 去掉 _ENCRYPTED
        reportCharacteristic.setValue(HidUsage.makeEmptyReport());

        // CCCD：ESP32 Host 会写入 0x0001 开启通知
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                UUID_CLIENT_CHAR_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        reportCharacteristic.addDescriptor(cccd);

        service.addCharacteristic(reportCharacteristic);
        gattServer.addService(service);

        Log.i(TAG, "HID Service 添加完成");
    }

    // ── Advertise Callback ────────────────────────────────────────────────────
    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            isAdvertising = true;
            Log.i(TAG, "广播启动成功");
            if (listener != null) listener.onAdvertisingStarted();
        }

        @Override
        public void onStartFailure(int errorCode) {
            isAdvertising = false;
            String msg = "广播启动失败，errorCode=" + errorCode;
            Log.e(TAG, msg);
            notifyError(msg);
        }
    };



    // ── GATT Server Callback ──────────────────────────────────────────────────
    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            Log.i(TAG, "连接状态变化: " + device.getAddress() + " status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device;
                // ✅ 连接后只停广播，不关 GATT Server
                stopAdvertisingOnly();

                // ✅ 关键：连接后立即设置配对确认，避免Android发起Passkey流程
                // 如果已绑定则跳过，如果未绑定则用Just Works
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "设备已绑定，跳过配对");
                } else {
                    Log.i(TAG, "设备未绑定，等待ESP32发起配对");
                    // ✅ 不主动 createBond，让 ESP32 Host 主导配对流程
                    // Android 作为 Peripheral 只需要响应 ESP32 的配对请求
                }

                String name = device.getName() != null ? device.getName() : device.getAddress();
                Log.i(TAG, "设备已连接: " + name);
                handler.post(() -> {
                    if (listener != null) listener.onDeviceConnected(name, device.getAddress());
                });

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                Log.i(TAG, "设备断开: " + device.getAddress()
                        + " userStopped=" + userStopped);
                 if (device.equals(connectedDevice) || connectedDevice == null) {
                    //Log.i(TAG, "设备已断开: " + device.getAddress() + " userStopped=" + userStopped);

                    BluetoothDevice oldDevice = connectedDevice;
                    connectedDevice = null;

                    if (userStopped) {
                        // ✅ 用户主动停止：关闭 GATT Server
                        // 取消超时任务（如果有）
                        handler.removeCallbacksAndMessages(null);
                        closeGattServer();
                    }

                    handler.post(() -> {
                        if (listener != null) listener.onDeviceDisconnected();
                    });
                }
            }
        }

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            Log.d(TAG, "Service added: " + service.getUuid() + " status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service 添加失败: " + service.getUuid() + " status=" + status);
                notifyError("GATT Service 注册失败");
            }
            // ✅ 无论成功失败都释放，让 addServiceSync 可以继续Start Broadcasting
            if (serviceAddLatch != null) {
                serviceAddLatch.countDown();
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                int offset, BluetoothGattCharacteristic characteristic) {
            //Log.d(TAG, "Read request: " + characteristic.getUuid());
            // ✅ 加详细日志
            Log.i(TAG, "Characteristic read request: uuid=" + characteristic.getUuid());
            byte[] value = characteristic.getValue();
            if (value == null) value = new byte[0];
            if (offset > value.length) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            } else {
                byte[] resp = Arrays.copyOfRange(value, offset, value.length);
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, resp);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                                                 boolean responseNeeded, int offset, byte[] value) {
            Log.d(TAG, "Write request: " + characteristic.getUuid() + " value=" + bytesToHex(value));
            characteristic.setValue(value);
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                            int offset, BluetoothGattDescriptor descriptor) {
            //Log.d(TAG, "Descriptor read: " + descriptor.getUuid());
            // ✅ 加详细日志
            Log.i(TAG, "Descriptor read request: uuid=" + descriptor.getUuid()
                    + " char=" + descriptor.getCharacteristic().getUuid());
            byte[] value = descriptor.getValue();
            if (value == null) value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor, boolean preparedWrite,
                                             boolean responseNeeded, int offset, byte[] value) {
            Log.d(TAG, "Descriptor write: " + descriptor.getUuid() + " value=" + bytesToHex(value));

            // ESP32 Host 写入 CCCD 0x0001 表示订阅通知
            if (UUID_CLIENT_CHAR_CONFIG.equals(descriptor.getUuid())) {
                descriptor.setValue(value);
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.i(TAG, "Host 已启用 HID Input Report 通知 ✅");
                }
            }
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            Log.d(TAG, "Notification sent, status=" + status);
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            Log.d(TAG, "MTU changed: " + mtu);
        }
    };

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static UUID uuid16(int id) {
        return UUID.fromString(String.format("%08X-0000-1000-8000-00805F9B34FB", id));
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    private void notifyError(String msg) {
        handler.post(() -> {
            if (listener != null) listener.onError(msg);
        });
    }
}
