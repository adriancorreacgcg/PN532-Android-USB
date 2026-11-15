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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
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
    private final List<byte[]> historial = new ArrayList<>();
    private Pn532 pn532;
    private volatile boolean relay;
    private Thread hiloRelay;
    private UsbDevice pendiente;
    private volatile int ultimoEstado = -1;

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
        findViewById(R.id.firmwareButton).setOnClickListener(v -> prueba());
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
        historial.clear();
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

    private boolean activarTarjeta() {
        byte[] p = pn532.transceive(Pn532Protocol.commandRaw(0x4A, new byte[] { 1, 0 }), 3000);
        if (p == null) { log("  poll sin respuesta"); return false; }
        log("  poll: " + Pn532Protocol.hex(p));
        return p.length > 2 && (p[1] & 0xff) == 0x4b && (p[2] & 0xff) >= 1;
    }

    private byte[] enviarCon(byte[] apdu, int tg, int ms) {
        ultimoEstado = -1;
        byte[] params = Pn532Protocol.concat(new byte[] { (byte) tg }, apdu);
        long t0 = System.currentTimeMillis();
        byte[] r = pn532.transceive(Pn532Protocol.commandRaw(0x40, params), ms);
        long transcurrido = System.currentTimeMillis() - t0;
        if (r == null) {
            ultimoEstado = -2;
            log("  esperé " + transcurrido + " ms y nada");
            return null;
        }
        if (r.length < 3 || (r[1] & 0xff) != 0x41) { ultimoEstado = -3; return null; }
        ultimoEstado = r[2] & 0xff;
        if (ultimoEstado != 0) return null;
        byte[] datos = Arrays.copyOfRange(r, 3, r.length);
        return datos.length == 0 ? null : datos;
    }

    private byte[] enviar(byte[] apdu, int tg) {
        return enviarCon(apdu, tg, 1500);
    }

    private byte[] leerRegistroCon(byte[] apdu, int tg, int le, String etiqueta) {
        byte[] mod = apdu.clone();
        mod[4] = (byte) le;
        ByteArrayOutputStream acum = new ByteArrayOutputStream();
        byte[] r = enviarCon(mod, tg, 6000);
        if (r == null) {
            log("  trozo " + etiqueta + " sin respuesta (estado " + ultimoEstado + ")");
            return null;
        }
        for (int vuelta = 0; vuelta < 8; vuelta++) {
            if (r.length < 2) return null;
            int sw1 = r[r.length - 2] & 0xff;
            int sw2 = r[r.length - 1] & 0xff;
            log("  trozo " + etiqueta + " len=" + r.length + " sw=" + Integer.toHexString((sw1 << 8) | sw2));
            if (sw1 == 0x90 && sw2 == 0x00) {
                acum.write(r, 0, r.length - 2);
                byte[] cuerpo = acum.toByteArray();
                byte[] fin = Arrays.copyOf(cuerpo, cuerpo.length + 2);
                fin[cuerpo.length] = (byte) 0x90;
                fin[cuerpo.length + 1] = 0x00;
                return fin;
            }
            if (sw1 == 0x61) {
                acum.write(r, 0, r.length - 2);
                byte[] gr = new byte[] { 0x00, (byte) 0xC0, 0x00, 0x00, (byte) sw2 };
                r = enviarCon(gr, tg, 3000);
                if (r == null) { log("  get response sin respuesta"); return null; }
                continue;
            }
            if (sw1 == 0x6C) {
                mod[4] = (byte) sw2;
                r = enviarCon(mod, tg, 3000);
                if (r == null) return null;
                continue;
            }
            return r;
        }
        return null;
    }

    private byte[] leerRegistro(byte[] apdu, int tg) {
        if (apdu.length < 5) return null;
        byte[] r = leerRegistroCon(apdu, tg, 0x40, "64");
        if (r == null) r = leerRegistroCon(apdu, tg, 0x20, "32");
        return r;
    }

    private byte[] intercambiar(byte[] apdu, int tg) {
        if ((apdu[1] & 0xff) == 0xB2) return leerRegistro(apdu, tg);
        return enviar(apdu, tg);
    }

    private boolean recuperar() {
        pn532.transceive(Pn532Protocol.commandRaw(0x14, new byte[] { 1, 0x14, 1 }), 600);
        if (!activarTarjeta()) {
            log("No pude reactivar la tarjeta");
            return false;
        }
        if (historial.isEmpty()) return true;
        log("Reactivo y repongo " + historial.size() + " comando(s) de contexto");
        for (byte[] h : historial) {
            byte[] resp = enviar(h, 1);
            log("  repongo " + Pn532Protocol.hex(h) + (resp == null ? " falló (" + ultimoEstado + ")" : " ok"));
            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
        }
        return true;
    }

    private void bucleRelay() {
        int tg = 0;
        int fallos = 0;
        log("Limpiando estado del chip");
        limpiarChip();
        while (relay) {
            try {
                if (tg == 0) {
                    if (activarTarjeta()) {
                        tg = 1;
                        fallos = 0;
                        log("Tarjeta activada");
                        runOnUiThread(() -> cardInfo.setText("Tarjeta activada"));
                    } else {
                        fallos++;
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
                Thread.sleep(40);

                byte[] datos = intercambiar(apdu, tg);
                if (datos == null) {
                    log("Reintento 1 sin respuesta en " + Pn532Protocol.hex(apdu) + " (estado " + ultimoEstado + ")");
                    for (int intento = 2; intento <= 3 && datos == null; intento++) {
                        Thread.sleep(150);
                        if (!recuperar()) { tg = 0; continue; }
                        tg = 1;
                        datos = intercambiar(apdu, tg);
                        if (datos == null) log("Reintento " + intento + " sin respuesta en "
                                + Pn532Protocol.hex(apdu) + " (estado " + ultimoEstado + ")");
                    }
                }

                if (datos == null) {
                    if ((apdu[1] & 0xff) == 0xB2) {
                        Puente.I.responder(new byte[] { 0x6A, (byte) 0x83 });
                        log("Falló definitivo " + Pn532Protocol.hex(apdu) + " (estado " + ultimoEstado + "), contesto 6A83");
                    } else {
                        Puente.I.responder(new byte[] { 0x6F, 0x00 });
                        log("Falló definitivo " + Pn532Protocol.hex(apdu) + " (estado " + ultimoEstado + ")");
                        tg = 0;
                    }
                    continue;
                }

                if ((apdu[1] & 0xff) == 0xA4) {
                    historial.clear();
                    historial.add(apdu);
                } else if ((apdu[1] & 0xff) == 0xA8) {
                    historial.add(apdu);
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

    /** Mini sesión de prueba: firmware, init, select, gpo y el registro que falla. */
    private void prueba() {
        if (!pn532.abierto()) { showStatus("Conecta el USB primero"); return; }
        new Thread(() -> {
            log("== prueba ==");
            byte[] f = pn532.transceive(Pn532Protocol.commandRaw(0x02, new byte[0]), 1500);
            log("firmware: " + (f == null ? "sin respuesta" : Pn532Protocol.hex(f)));
            log("init: " + (pn532.transceive(Pn532Protocol.commandRaw(0x14, new byte[] { 1, 0x14, 1 }), 800) == null ? "sin respuesta" : "ok"));
            if (!activarTarjeta()) { log("sin tarjeta en el lector"); return; }
            log("tarjeta activada");
            byte[] sel = new byte[] { 0x00, (byte) 0xA4, 0x04, 0x00, 0x07,
                    (byte) 0xA0, 0x00, 0x00, 0x00, 0x04, 0x10, 0x10, 0x00 };
            log("select: " + (enviar(sel, 1) == null ? "falló (" + ultimoEstado + ")" : "ok"));
            byte[] gpo = new byte[] { (byte) 0x80, (byte) 0xA8, 0x00, 0x00, 0x02, (byte) 0x83, 0x00, 0x00 };
            log("gpo: " + (enviar(gpo, 1) == null ? "falló (" + ultimoEstado + ")" : "ok"));
            byte[] r6 = new byte[] { 0x00, (byte) 0xB2, 0x01, 0x34, 0x00 };
            byte[] d6 = leerRegistro(r6, 1);
            log("registro SFI6 r1: " + (d6 == null ? "falló (" + ultimoEstado + ")" : Pn532Protocol.hex(d6)));
            byte[] r3 = new byte[] { 0x00, (byte) 0xB2, 0x01, 0x1C, 0x00 };
            byte[] d3 = leerRegistro(r3, 1);
            log("registro SFI3 r1 (control): " + (d3 == null ? "falló (" + ultimoEstado + ")" : "ok " + d3.length + " bytes"));
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
            if (logBuffer.length() > 16000) logBuffer.delete(0, logBuffer.length() - 10000);
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