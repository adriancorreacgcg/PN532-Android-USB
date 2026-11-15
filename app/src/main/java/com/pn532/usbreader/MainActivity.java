package com.pn532.usbreader;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

public class MainActivity extends Activity implements Pn532.Log {

    private static final String USB_PERMISSION_ACTION = "com.pn532.usbreader.USB_PERMISSION";

    private UsbManager usbManager;
    private TextView statusText;
    private TextView cardInfo;
    private TextView logText;
    private final StringBuilder logBuffer = new StringBuilder();
    private Pn532 pn532;
    private volatile boolean relay;
    private Thread hiloRelay;
    private UsbDevice pendiente;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!USB_PERMISSION_ACTION.equals(intent.getAction())) return;
            if (pendiente != null && usbManager.hasPermission(pendiente)) {
                abrir(pendiente);
            } else {
                showStatus("Permiso USB denegado");
            }
            pendiente = null;
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        statusText = findViewById(R.id.status);
        cardInfo = findViewById(R.id.cardInfo);
        logText = findViewById(R.id.logText);
        pn532 = new Pn532();

        findViewById(R.id.connectButton).setOnClickListener(v -> {
            if (pn532.abierto()) { detenerRelay(); pn532.cerrar(); showStatus("Sin conexión USB"); }
            else conectar();
        });
        findViewById(R.id.relayButton).setOnClickListener(v -> iniciarRelay());
        findViewById(R.id.stopButton).setOnClickListener(v -> detenerRelay());
        findViewById(R.id.firmwareButton).setOnClickListener(v -> firmware());
        findViewById(R.id.clearButton).setOnClickListener(v -> {
            logBuffer.setLength(0);
            logText.setText("");
        });

        IntentFilter filter = new IntentFilter(USB_PERMISSION_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permissionReceiver, filter);
        }
        log("Conecta el CP210x por OTG y presiona Conectar.");
    }

    private void conectar() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            showStatus("No se encontró adaptador");
            return;
        }
        UsbDevice elegido = drivers.get(0).getDevice();
        for (UsbSerialDriver d : drivers) {
            if (d.getDevice().getVendorId() == 0x10c4) { elegido = d.getDevice(); break; }
        }
        if (!usbManager.hasPermission(elegido)) {
            pendiente = elegido;
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(USB_PERMISSION_ACTION).setPackage(getPackageName()), flags);
            usbManager.requestPermission(elegido, pi);
            showStatus("Esperando permiso USB…");
            return;
        }
        abrir(elegido);
    }

    private void abrir(UsbDevice dispositivo) {
        try {
            pn532.abrir(usbManager, dispositivo, this);
            showStatus("USB conectado · 115200");
            log("USB conectado");
        } catch (Exception e) {
            log("Error al conectar: " + e.getMessage());
            showStatus("Error de conexión");
        }
    }

    private void iniciarRelay() {
        if (!pn532.abierto()) { showStatus("Conecta el USB primero"); return; }
        if (relay) return;
        Puente.I.activar();
        relay = true;
        hiloRelay = new Thread(this::bucleRelay);
        hiloRelay.start();
        preferir(true);
        showStatus("Relay activo");
        log("Relay activo: tarjeta en el lector, teléfono en la terminal.");
    }

    private void detenerRelay() {
        relay = false;
        Puente.I.desactivar();
        hiloRelay = null;
        preferir(false);
        showStatus("Relay detenido");
        log("Relay detenido");
    }

    private void preferir(boolean activo) {
        NfcAdapter adaptador = NfcAdapter.getDefaultAdapter(this);
        if (adaptador == null) return;
        CardEmulation ce = CardEmulation.getInstance(adaptador);
        try {
            if (activo) {
                ce.setPreferredService(this, new ComponentName(this, HceService.class));
            } else {
                ce.unsetPreferredService(this);
            }
        } catch (Exception e) {
            log("Aviso preferred service: " + e.getMessage());
        }
    }

    private void limpiarChip() {
        pn532.transceive(Pn532Protocol.commandRaw(0x52, new byte[] { 1 }), 300);
        pn532.transceive(Pn532Protocol.commandRaw(0x14, new byte[] { 1, 0x14, 1 }), 600);
    }

    private void bucleRelay() {
        int tg = 0;
        int fallos = 0;
        log("Limpiando estado del chip");
        limpiarChip();
        while (relay) {
            try {
                if (tg == 0) {
                    byte[] r = pn532.transceive(
                            Pn532Protocol.commandRaw(0x4A, new byte[] { 1, 0 }), 3000);
                    boolean hay = r != null && r.length > 2
                            && (r[1] & 0xff) == 0x4b && (r[2] & 0xff) == 1;
                    if (hay) {
                        tg = 1;
                        fallos = 0;
                        log("Tarjeta activada");
                        runOnUiThread(() -> cardInfo.setText("Tarjeta activada"));
                    } else {
                        fallos++;
                        if (fallos == 1 || fallos % 5 == 0) {
                            log("Sin tarjeta, intento " + fallos
                                    + (r == null ? " (el PN532 no contestó)" : " rx " + Pn532Protocol.hex(r)));
                        }
                        if (fallos % 15 == 0) {
                            log("Reinicializando chip");
                            limpiarChip();
                        }
                        Thread.sleep(300);
                        continue;
                    }
                }
                byte[] apdu = Puente.I.siguiente(200);
                if (apdu == null) continue;
                byte[] params = Pn532Protocol.concat(new byte[] { (byte) tg }, apdu);
                byte[] r = pn532.transceive(Pn532Protocol.commandRaw(0x40, params), 1500);
                if (r == null || r.length < 3 || (r[1] & 0xff) != 0x41) {
                    Puente.I.responder(new byte[] { 0x6F, 0x00 });
                    tg = 0;
                    log("Sin respuesta de la tarjeta");
                    continue;
                }
                int estado = r[2] & 0xff;
                if (estado != 0) {
                    Puente.I.responder(new byte[] { 0x6F, 0x00 });
                    tg = 0;
                    log("Estado PN532 " + estado);
                    continue;
                }
                byte[] datos = Arrays.copyOfRange(r, 3, r.length);
                if (datos.length == 0) {
                    Puente.I.responder(new byte[] { 0x6F, 0x00 });
                    continue;
                }
                Puente.I.responder(datos);
                log("APDU " + Pn532Protocol.hex(apdu) + " -> " + Pn532Protocol.hex(datos));
            } catch (Exception e) {
                log("Error relay: " + e.getMessage());
            }
        }
        try { pn532.transceive(Pn532Protocol.commandRaw(0x52, new byte[] { 1 }), 300); }
        catch (Exception ignored) { }
    }

    private void firmware() {
        if (!pn532.abierto()) { showStatus("Conecta el USB primero"); return; }
        new Thread(() -> {
            byte[] r = pn532.transceive(Pn532Protocol.commandRaw(0x02, new byte[0]), 1500);
            if (r == null) { log("SIN RESPUESTA al firmware"); return; }
            log("RX: " + Pn532Protocol.hex(r));
            if (r.length >= 5 && (r[1] & 0xff) == 0x03) {
                showStatus("PN532 firmware " + (r[3] & 0xff) + "." + (r[4] & 0xff));
            }
        }).start();
    }

    private void showStatus(String texto) {
        runOnUiThread(() -> statusText.setText(texto));
    }

    @Override public void onLog(String texto) {
        log(texto);
    }

    private void log(String mensaje) {
        runOnUiThread(() -> {
            logBuffer.append(mensaje).append('\n');
            if (logBuffer.length() > 12000) logBuffer.delete(0, logBuffer.length() - 8000);
            logText.setText(logBuffer.toString());
        });
    }

    @Override protected void onDestroy() {
        relay = false;
        Puente.I.desactivar();
        pn532.cerrar();
        try { unregisterReceiver(permissionReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }
}