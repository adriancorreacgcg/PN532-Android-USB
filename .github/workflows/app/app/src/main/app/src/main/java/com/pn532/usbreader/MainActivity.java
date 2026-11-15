package com.pn532.usbreader;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SerialInputOutputManager.Listener {
    private static final String USB_PERMISSION_ACTION = "com.pn532.usbreader.USB_PERMISSION";
    private UsbManager usbManager;
    private UsbSerialPort port;
    private SerialInputOutputManager io;
    private TextView statusText;
    private TextView cardInfo;
    private TextView logText;
    private final StringBuilder logBuffer = new StringBuilder();
    private Pn532Protocol.Parser parser;
    private UsbSerialDriver pendingDriver;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!USB_PERMISSION_ACTION.equals(intent.getAction())) return;
            if (pendingDriver != null && usbManager.hasPermission(pendingDriver.getDevice())) {
                openDriver(pendingDriver);
            } else {
                showStatus("Permiso USB denegado");
                log("El usuario no concedió permiso al adaptador USB.");
            }
            pendingDriver = null;
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        statusText = findViewById(R.id.status);
        cardInfo = findViewById(R.id.cardInfo);
        logText = findViewById(R.id.logText);
        findViewById(R.id.connectButton).setOnClickListener(v -> {
            if (port != null) closePort(); else connect();
        });
        findViewById(R.id.firmwareButton).setOnClickListener(v ->
                sendCommand("Firmware", 0x02));
        findViewById(R.id.initButton).setOnClickListener(v ->
                sendCommand("SAM Configuration", 0x14, 0x01, 0x14, 0x01));
        findViewById(R.id.readButton).setOnClickListener(v -> {
            cardInfo.setText("Acerca una tarjeta a la antena...");
            sendCommand("Buscar NFC", 0x4a, 0x01, 0x00);
        });
        findViewById(R.id.clearButton).setOnClickListener(v -> {
            logBuffer.setLength(0);
            logText.setText("");
        });
        parser = new Pn532Protocol.Parser(new Pn532Protocol.Listener() {
            @Override public void onAck() { log("RX ACK: 00 00 FF 00 FF 00"); }
            @Override public void onInvalidFrame() { log("RX: trama incompleta o inválida"); }
            @Override public void onFrame(byte[] data) {
                log("RX: " + Pn532Protocol.hex(data));
                parseResponse(data);
            }
        });
        IntentFilter filter = new IntentFilter(USB_PERMISSION_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permissionReceiver, filter);
        }
        log("Listo. Conecta el CP210x por OTG y presiona Conectar.");
    }

    private void connect() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            showStatus("No se encontró adaptador USB-serial");
            log("Conecta el CP210x por OTG y verifica que Android lo detecte.");
            return;
        }
        UsbSerialDriver selected = drivers.get(0);
        for (UsbSerialDriver driver : drivers) {
            if (driver.getDevice().getVendorId() == 0x10c4) { // Silicon Labs / CP210x
                selected = driver;
                break;
            }
        }
        if (!usbManager.hasPermission(selected.getDevice())) {
            pendingDriver = selected;
            Intent intent = new Intent(USB_PERMISSION_ACTION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags);
            usbManager.requestPermission(selected.getDevice(), pi);
            showStatus("Esperando permiso USB...");
            return;
        }
        openDriver(selected);
    }

    private void openDriver(UsbSerialDriver driver) {
        try {
            UsbDevice device = driver.getDevice();
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) throw new IOException("No se pudo abrir dispositivo USB");
            port = driver.getPorts().get(0);
            port.open(connection);
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            port.setFlowControl(UsbSerialPort.FlowControl.NONE);
            parser.clear();
            io = new SerialInputOutputManager(port, this);
            io.start();
            showStatus("USB conectado · 115200 baudios");
            log(String.format(Locale.US, "USB conectado (VID=%04X PID=%04X)",
                    device.getVendorId(), device.getProductId()));
            log("Usa Firmware → Inicializar → Leer NFC.");
        } catch (Exception e) {
            log("Error al conectar: " + e.getMessage());
            showStatus("Error de conexión USB");
            closePort();
        }
    }

    private void sendCommand(String label, int command, int... parameters) {
        if (io == null) {
            showStatus("Primero conecta el USB");
            return;
        }
        byte[] p = new byte[parameters.length];
        for (int i = 0; i < parameters.length; i++) p[i] = (byte) parameters[i];
        byte[] frame = Pn532Protocol.concat(Pn532Protocol.WAKE_UP,
                Pn532Protocol.command(command, p));
        io.writeAsync(frame);
        log("TX " + label + ": " + Pn532Protocol.hex(frame));
    }

    private void parseResponse(byte[] data) {
        if (data.length < 2 || (data[0] & 0xff) != 0xd5) return;
        switch (data[1] & 0xff) {
            case 0x03:
                if (data.length >= 6) {
                    showStatus("PN532 conectado · firmware " + (data[3] & 0xff) + "." +
                            (data[4] & 0xff));
                    log(String.format(Locale.US, "Chip=0x%02X, soporte=0x%02X",
                            data[2] & 0xff, data[5] & 0xff));
                }
                break;
            case 0x15:
                showStatus("PN532 inicializado · listo para NFC");
                break;
            case 0x4b:
                String result = Pn532Protocol.describeCard(data);
                runOnUiThread(() -> cardInfo.setText(result));
                showStatus("Lectura NFC terminada");
                break;
            default:
                log("Respuesta PN532: D5 " + String.format(Locale.US, "%02X", data[1] & 0xff));
        }
    }

    @Override public void onNewData(byte[] data) {
        parser.feed(data);
    }

    @Override public void onRunError(Exception e) {
        log("Error serial: " + e.getMessage());
        showStatus("Puerto serial desconectado");
        runOnUiThread(this::closePort);
    }

    private void showStatus(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    private void log(String message) {
        runOnUiThread(() -> {
            logBuffer.append(message).append('\n');
            if (logBuffer.length() > 9000) logBuffer.delete(0, logBuffer.length() - 6500);
            logText.setText(logBuffer.toString());
        });
    }

    private void closePort() {
        if (io != null) {
            io.setListener(null);
            io.stop();
            io = null;
        }
        if (port != null) {
            try { port.close(); } catch (IOException ignored) { }
            port = null;
        }
        showStatus("Sin conexión USB");
    }

    @Override protected void onDestroy() {
        closePort();
        unregisterReceiver(permissionReceiver);
        super.onDestroy();
    }
}
