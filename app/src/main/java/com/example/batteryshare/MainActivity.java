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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends android.app.Activity {

    private static final UUID APP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long WRITE_INTERVAL_MS = 2000;

    private BluetoothAdapter bluetoothAdapter;
    private ExecutorService executor;

    private TextView statusText;
    private TextView remoteBatteryText;
    private TextView remoteBatteryPercent;
    private TextView remoteBatteryLabel;
    private TextView ownBatteryText;
    private ListView deviceList;
    private Button reconnectButton;
    private ArrayAdapter<String> deviceAdapter;
    private ArrayList<BluetoothDevice> pairedDevices;

    // Connection state
    private AtomicBoolean connected = new AtomicBoolean(false);
    private BluetoothSocket activeSocket;
    private BluetoothDevice remoteDevice;

    // Thread control
    private volatile boolean writerRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        remoteBatteryText = findViewById(R.id.remoteBatteryText);
        remoteBatteryPercent = findViewById(R.id.remoteBatteryPercent);
        remoteBatteryLabel = findViewById(R.id.remoteBatteryLabel);
        ownBatteryText = findViewById(R.id.ownBatteryText);
        deviceList = findViewById(R.id.deviceList);
        reconnectButton = findViewById(R.id.reconnectButton);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        executor = Executors.newCachedThreadPool();
        pairedDevices = new ArrayList<>();
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        deviceList.setAdapter(deviceAdapter);

        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < pairedDevices.size()) {
                BluetoothDevice selected = pairedDevices.get(position);
                startConnection(selected);
            }
        });

        reconnectButton.setOnClickListener(v -> {
            reconnectButton.setVisibility(Button.GONE);
            deviceList.setVisibility(ListView.VISIBLE);
            refreshDeviceList();
        });

        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        updateOwnBattery();
        refreshDeviceList();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            }
        }
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateOwnBattery();
        }
    };

    private void updateOwnBattery() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int level;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } else {
            Intent batIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int levelInt = batIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            level = (scale > 0 && levelInt >= 0) ? (int) ((levelInt / (float) scale) * 100) : 0;
        }
        ownBatteryText.setText("You: " + level + "%");
    }

    private void refreshDeviceList() {
        pairedDevices.clear();
        deviceAdapter.clear();
        if (bluetoothAdapter == null) {
            statusText.setText("Bluetooth not available");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            statusText.setText("Bluetooth is off");
            Toast.makeText(this, "Turn on Bluetooth in Settings", Toast.LENGTH_LONG).show();
            return;
        }
        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
            pairedDevices.add(device);
            deviceAdapter.add(device.getName() + "\n(" + device.getAddress() + ")");
        }
        if (pairedDevices.isEmpty()) {
            deviceAdapter.add("No paired devices");
            statusText.setText("Pair a device first in Bluetooth settings");
        } else {
            statusText.setText("Select a paired device");
        }
        deviceList.setAdapter(deviceAdapter);
    }

    @SuppressLint("MissingPermission")
    private void startConnection(BluetoothDevice target) {
        statusText.setText("Connecting...");
        remoteBatteryText.setVisibility(TextView.INVISIBLE);
        remoteBatteryPercent.setVisibility(TextView.INVISIBLE);
        remoteBatteryLabel.setVisibility(TextView.INVISIBLE);
        deviceList.setVisibility(ListView.INVISIBLE);
        reconnectButton.setVisibility(Button.GONE);
        connected.set(false);
        remoteDevice = target;

        // Start both server and client threads simultaneously
        executor.submit(this::runServer);
        executor.submit(() -> runClient(target));
    }

    @SuppressLint("MissingPermission")
    private void runServer() {
        BluetoothServerSocket serverSocket = null;
        try {
            serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("BatteryShare", APP_UUID);
            // Wait up to 30 seconds for a connection
            BluetoothSocket socket = serverSocket.accept(30000);
            if (socket != null && connected.compareAndSet(false, true)) {
                activeSocket = socket;
                onConnectionEstablished(bluetoothAdapter.getRemoteDevice(
                    socket.getRemoteDevice().getAddress()), "Server");
            }
        } catch (IOException e) {
            // Expected: client won the race and we got cancelled
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
            socket = target.createRfcommSocketToServiceRecord(APP_UUID);
            socket.connect();
            if (connected.compareAndSet(false, true)) {
                activeSocket = socket;
                onConnectionEstablished(target, "Client");
            }
        } catch (IOException e) {
            // Expected: server won the race
        }
    }

    private void onConnectionEstablished(BluetoothDevice peer, String role) {
        runOnUiThread(() -> {
            statusText.setText("Connected (via " + role + ")");
        });
        startReader();
        startWriter();
    }

    private void startReader() {
        executor.submit(() -> {
            try (InputStream input = activeSocket.getInputStream()) {
                byte[] buffer = new byte[256];
                while (connected.get()) {
                    int bytes = input.read(buffer);
                    if (bytes < 0) break;
                    String line = new String(buffer, 0, bytes).trim();
                    int battery = Integer.parseInt(line);
                    mainHandler.post(() -> {
                        remoteBatteryText.setText(String.valueOf(battery));
                        remoteBatteryText.setVisibility(TextView.VISIBLE);
                        remoteBatteryPercent.setVisibility(TextView.VISIBLE);
                        remoteBatteryLabel.setVisibility(TextView.VISIBLE);
                    });
                }
            } catch (IOException e) {
                // Connection dropped
            }
            mainHandler.post(this::onDisconnected);
        });
    }

    private void startWriter() {
        writerRunning = true;
        executor.submit(() -> {
            try (OutputStream output = activeSocket.getOutputStream()) {
                while (writerRunning && connected.get()) {
                    int battery = getBatteryLevel();
                    output.write((battery + "\n").getBytes());
                    output.flush();
                    Thread.sleep(WRITE_INTERVAL_MS);
                }
            } catch (IOException | InterruptedException e) {
                // Connection dropped or interrupted
            }
            writerRunning = false;
        });
    }

    private int getBatteryLevel() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        Intent batIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = batIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return (scale > 0 && level >= 0) ? (int) ((level / (float) scale) * 100) : 0;
    }

    private void onDisconnected() {
        try {
            if (activeSocket != null && activeSocket.isConnected()) {
                activeSocket.close();
            }
        } catch (IOException ignored) {}
        activeSocket = null;
        connected.set(false);
        writerRunning = false;
        runOnUiThread(() -> {
            statusText.setText("Disconnected \u2013 Tap to reconnect");
            remoteBatteryText.setVisibility(TextView.INVISIBLE);
            remoteBatteryPercent.setVisibility(TextView.INVISIBLE);
            remoteBatteryLabel.setVisibility(TextView.INVISIBLE);
            deviceList.setVisibility(ListView.INVISIBLE);
            reconnectButton.setVisibility(Button.VISIBLE);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        writerRunning = false;
        connected.set(false);
        if (activeSocket != null) {
            try { activeSocket.close(); } catch (IOException ignored) {}
        }
        unregisterReceiver(batteryReceiver);
        executor.shutdownNow();
    }
}
