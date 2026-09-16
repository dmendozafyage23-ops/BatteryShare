package com.example.batteryshare;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final UUID APP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long WRITE_INTERVAL_MS = 2000;

    private static final int REQ_BT_PERMISSIONS = 101;

    private BluetoothAdapter bluetoothAdapter;
    private ExecutorService executor;

    // Header views
    private TextView statusText;
    private MaterialButton btnRefresh;

    // Local device views
    private TextView localDeviceNameText;
    private TextView ownBatteryText;

    // Remote card views
    private MaterialCardView remoteCard;
    private TextView remoteDeviceNameText;
    private TextView remoteBatteryText;
    private TextView remoteBatteryPercent;
    private TextView remoteStatusSubtitle;
    private MaterialButton btnDisconnect;

    // Reconnect banner views
    private MaterialCardView reconnectCard;
    private TextView reconnectNoticeText;
    private MaterialButton btnReconnect;

    // Device list views
    private TextView emptyDevicesText;
    private ListView deviceList;
    private MaterialButton btnOpenBtSettings;

    private DeviceAdapter deviceAdapter;
    private final List<BluetoothDevice> pairedDevices = new ArrayList<>();

    // Connection state
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private BluetoothSocket activeSocket;
    private BluetoothDevice lastTargetDevice;

    // Thread control
    private volatile boolean writerRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupListeners();

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        executor = Executors.newCachedThreadPool();

        deviceAdapter = new DeviceAdapter();
        deviceList.setAdapter(deviceAdapter);

        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registerReceiver(bluetoothStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        updateOwnBattery();
        updateLocalDeviceName();

        checkPermissionsAndRefresh();
    }

    private void initViews() {
        statusText = findViewById(R.id.statusText);
        btnRefresh = findViewById(R.id.btnRefresh);

        localDeviceNameText = findViewById(R.id.localDeviceNameText);
        ownBatteryText = findViewById(R.id.ownBatteryText);

        remoteCard = findViewById(R.id.remoteCard);
        remoteDeviceNameText = findViewById(R.id.remoteDeviceNameText);
        remoteBatteryText = findViewById(R.id.remoteBatteryText);
        remoteBatteryPercent = findViewById(R.id.remoteBatteryPercent);
        remoteStatusSubtitle = findViewById(R.id.remoteStatusSubtitle);
        btnDisconnect = findViewById(R.id.btnDisconnect);

        reconnectCard = findViewById(R.id.reconnectCard);
        reconnectNoticeText = findViewById(R.id.reconnectNoticeText);
        btnReconnect = findViewById(R.id.btnReconnect);

        emptyDevicesText = findViewById(R.id.emptyDevicesText);
        deviceList = findViewById(R.id.deviceList);
        btnOpenBtSettings = findViewById(R.id.btnOpenBtSettings);
    }

    private void setupListeners() {
        btnRefresh.setOnClickListener(v -> checkPermissionsAndRefresh());

        btnOpenBtSettings.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Could not open Bluetooth settings", Toast.LENGTH_SHORT).show();
            }
        });

        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < pairedDevices.size()) {
                BluetoothDevice target = pairedDevices.get(position);
                startConnection(target);
            }
        });

        btnDisconnect.setOnClickListener(v -> disconnectManually());

        btnReconnect.setOnClickListener(v -> {
            if (lastTargetDevice != null) {
                startConnection(lastTargetDevice);
            } else {
                refreshDeviceList();
            }
        });
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                   checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void checkPermissionsAndRefresh() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<String> needed = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), REQ_BT_PERMISSIONS);
                return;
            }
        }
        refreshDeviceList();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            refreshDeviceList();
        }
    }

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    updateLocalDeviceName();
                    refreshDeviceList();
                } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    onDisconnected("Bluetooth turned off");
                    refreshDeviceList();
                }
            }
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateOwnBattery();
        }
    };

    private void updateLocalDeviceName() {
        if (bluetoothAdapter != null && hasBluetoothPermissions()) {
            @SuppressLint("MissingPermission")
            String name = bluetoothAdapter.getName();
            if (name != null && !name.trim().isEmpty()) {
                localDeviceNameText.setText(name);
                return;
            }
        }
        localDeviceNameText.setText(Build.MODEL);
    }

    private int getBatteryLevel() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && bm != null) {
            int cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (cap >= 0) return cap;
        }
        Intent batIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batIntent != null) {
            int level = batIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (scale > 0 && level >= 0) {
                return (int) ((level / (float) scale) * 100);
            }
        }
        return 0;
    }

    private void updateOwnBattery() {
        int level = getBatteryLevel();
        ownBatteryText.setText(level + "%");
        if (level > 40) {
            ownBatteryText.setTextColor(0xFF059669); // Emerald green
        } else if (level > 20) {
            ownBatteryText.setTextColor(0xFFD97706); // Amber
        } else {
            ownBatteryText.setTextColor(0xFFDC2626); // Red
        }
    }

    @SuppressLint("MissingPermission")
    private void refreshDeviceList() {
        pairedDevices.clear();
        deviceAdapter.notifyDataSetChanged();

        if (bluetoothAdapter == null) {
            statusText.setText("Bluetooth unavailable on this device");
            emptyDevicesText.setVisibility(View.VISIBLE);
            emptyDevicesText.setText("Bluetooth is not supported on this device.");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            statusText.setText("Bluetooth is off");
            emptyDevicesText.setVisibility(View.VISIBLE);
            emptyDevicesText.setText("Bluetooth is turned off.\nPlease turn it on in Settings.");
            return;
        }

        if (!hasBluetoothPermissions()) {
            statusText.setText("Permission required");
            emptyDevicesText.setVisibility(View.VISIBLE);
            emptyDevicesText.setText("Bluetooth permissions are required to show paired devices.");
            return;
        }

        Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
        if (bonded != null && !bonded.isEmpty()) {
            pairedDevices.addAll(bonded);
            emptyDevicesText.setVisibility(View.GONE);
            statusText.setText("Tap a device to connect");
        } else {
            emptyDevicesText.setVisibility(View.VISIBLE);
            emptyDevicesText.setText("No paired Bluetooth devices found.\nPair phones first in Bluetooth Settings.");
            statusText.setText("No paired devices");
        }

        deviceAdapter.notifyDataSetChanged();
    }

    @SuppressLint("MissingPermission")
    private void startConnection(BluetoothDevice target) {
        if (!hasBluetoothPermissions()) {
            checkPermissionsAndRefresh();
            return;
        }

        lastTargetDevice = target;

        // Cleanup prior connection safely
        closeCurrentConnectionSilently();

        // UI Updates for Connecting
        statusText.setText("Connecting to " + getSafeDeviceName(target) + "...");
        reconnectCard.setVisibility(View.GONE);
        remoteCard.setVisibility(View.VISIBLE);
        remoteDeviceNameText.setText(getSafeDeviceName(target));
        remoteBatteryText.setText("--");
        remoteBatteryPercent.setVisibility(View.VISIBLE);
        remoteStatusSubtitle.setText("Connecting...");
        remoteStatusSubtitle.setTextColor(0xFF2563EB);

        connected.set(false);

        // Start race: listen as server AND dial as client
        executor.submit(this::runServer);
        executor.submit(() -> runClient(target));
    }

    @SuppressLint("MissingPermission")
    private void runServer() {
        BluetoothServerSocket serverSocket = null;
        try {
            serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("BatteryShare", APP_UUID);
            BluetoothSocket socket = serverSocket.accept(25000); // 25s timeout
            if (socket != null && connected.compareAndSet(false, true)) {
                activeSocket = socket;
                BluetoothDevice peer = socket.getRemoteDevice();
                onConnectionEstablished(peer, "Server");
            } else if (socket != null) {
                closeQuietly(socket);
            }
        } catch (IOException ignored) {
            // Expected if client won race or timeout
        } finally {
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (IOException ignored) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void runClient(BluetoothDevice target) {
        BluetoothSocket socket = null;
        try {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            socket = target.createRfcommSocketToServiceRecord(APP_UUID);
            socket.connect();
            if (connected.compareAndSet(false, true)) {
                activeSocket = socket;
                onConnectionEstablished(target, "Client");
            } else {
                closeQuietly(socket);
            }
        } catch (IOException ignored) {
            // Expected if server won race or peer unreachable
        }
    }

    private void onConnectionEstablished(BluetoothDevice peer, String role) {
        runOnUiThread(() -> {
            statusText.setText("Connected");
            remoteCard.setVisibility(View.VISIBLE);
            reconnectCard.setVisibility(View.GONE);
            remoteDeviceNameText.setText(getSafeDeviceName(peer));
            remoteStatusSubtitle.setText("Connected \u2022 Live sync");
            remoteStatusSubtitle.setTextColor(0xFF16A34A); // Green
        });

        startReader();
        startWriter();
    }

    private void startReader() {
        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(activeSocket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (connected.get() && (line = reader.readLine()) != null) {
                    String clean = line.trim();
                    if (clean.isEmpty()) continue;
                    try {
                        int battery = Integer.parseInt(clean);
                        mainHandler.post(() -> showRemoteBattery(battery));
                    } catch (NumberFormatException ignored) {}
                }
            } catch (IOException ignored) {
                // Socket disconnected
            }
            mainHandler.post(() -> onDisconnected("Connection ended"));
        });
    }

    private void showRemoteBattery(int battery) {
        remoteBatteryText.setText(String.valueOf(battery));
        remoteBatteryPercent.setVisibility(View.VISIBLE);
        if (battery > 40) {
            remoteBatteryText.setTextColor(0xFF1E293B);
        } else if (battery > 20) {
            remoteBatteryText.setTextColor(0xFFD97706);
        } else {
            remoteBatteryText.setTextColor(0xFFDC2626);
        }
    }

    private void startWriter() {
        writerRunning = true;
        executor.submit(() -> {
            try {
                OutputStream output = activeSocket.getOutputStream();
                int firstLevel = getBatteryLevel();
                output.write((firstLevel + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();

                while (writerRunning && connected.get()) {
                    Thread.sleep(WRITE_INTERVAL_MS);
                    if (!writerRunning || !connected.get()) break;
                    int level = getBatteryLevel();
                    output.write((level + "\n").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                // Connection dropped
            }
            writerRunning = false;
        });
    }

    private void disconnectManually() {
        closeCurrentConnectionSilently();
        runOnUiThread(() -> {
            statusText.setText("Disconnected");
            remoteCard.setVisibility(View.GONE);
            reconnectCard.setVisibility(View.GONE);
            refreshDeviceList();
        });
    }

    private void closeCurrentConnectionSilently() {
        connected.set(false);
        writerRunning = false;
        if (activeSocket != null) {
            closeQuietly(activeSocket);
            activeSocket = null;
        }
    }

    private void onDisconnected(String reason) {
        closeCurrentConnectionSilently();
        runOnUiThread(() -> {
            statusText.setText("Disconnected");
            remoteCard.setVisibility(View.GONE);
            reconnectCard.setVisibility(View.VISIBLE);
            reconnectNoticeText.setText(reason != null ? reason : "Connection lost");
        });
    }

    @SuppressLint("MissingPermission")
    private String getSafeDeviceName(BluetoothDevice device) {
        if (device == null) return "Unknown";
        if (hasBluetoothPermissions()) {
            try {
                String name = device.getName();
                if (name != null && !name.trim().isEmpty()) return name;
            } catch (Exception ignored) {}
        }
        return device.getAddress();
    }

    private void closeQuietly(BluetoothSocket socket) {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCurrentConnectionSilently();
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(bluetoothStateReceiver); } catch (Exception ignored) {}
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // Clean adapter for paired devices list
    private class DeviceAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return pairedDevices.size();
        }

        @Override
        public BluetoothDevice getItem(int position) {
            return pairedDevices.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @SuppressLint("MissingPermission")
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_device, parent, false);
            }
            BluetoothDevice device = getItem(position);
            TextView name = convertView.findViewById(R.id.deviceName);
            TextView addr = convertView.findViewById(R.id.deviceAddress);

            name.setText(getSafeDeviceName(device));
            addr.setText(device.getAddress());

            return convertView;
        }
    }
}
