package com.pn532.usbreader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** PN532 standard UART/HSU framing (normal-length frames). */
public final class Pn532Protocol {
    private Pn532Protocol() {}

    public interface Listener {
        void onAck();
        void onFrame(byte[] data);
        void onInvalidFrame();
    }

    public static final byte[] WAKE_UP = new byte[] {
        0x55, 0x55, 0, 0, 0, 0
    };

    public static byte[] command(int command, byte... parameters) {
        int len = 2 + parameters.length; // TFI D4 + command + parameters
        if (len > 255) throw new IllegalArgumentException("PN532 frame too large");
        byte[] result = new byte[7 + len];
        result[0] = 0;
        result[1] = 0;
        result[2] = (byte) 0xff;
        result[3] = (byte) len;
        result[4] = (byte) (-len);
        result[5] = (byte) 0xd4;
        result[6] = (byte) command;
        int sum = 0xd4 + (command & 0xff);
        for (int i = 0; i < parameters.length; i++) {
            result[7 + i] = parameters[i];
            sum += parameters[i] & 0xff;
        }
        result[5 + len] = (byte) (-sum);
        result[6 + len] = 0;
        return result;
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) {
            if (s.length() > 0) s.append(' ');
            s.append(String.format(Locale.US, "%02X", b & 0xff));
        }
        return s.toString();
    }

    public static String describeCard(byte[] data) {
        // InListPassiveTarget response: D5 4B NbTg Tg SENS_RES[2] SEL_RES NFCIDLen NFCID ...
        if (data.length < 8 || (data[0] & 0xff) != 0xd5 || (data[1] & 0xff) != 0x4b)
            return "Respuesta NFC no válida";
        int count = data[2] & 0xff;
        if (count == 0) return "No se detectó ninguna tarjeta";
        int uidLen = data[7] & 0xff;
        int start = 8;
        if (uidLen < 1 || start + uidLen > data.length) return "Respuesta incompleta: " + hex(data);
        StringBuilder s = new StringBuilder();
        s.append("Tarjeta detectada\n");
        s.append("UID: ").append(hex(Arrays.copyOfRange(data, start, start + uidLen))).append('\n');
        s.append("ATQA: ").append(hex(Arrays.copyOfRange(data, 4, 6))).append('\n');
        s.append("SAK: ").append(hex(Arrays.copyOfRange(data, 6, 7)));
        int next = start + uidLen;
        if (next < data.length) {
            int atsLen = data[next] & 0xff;
            if (atsLen > 0 && next + 1 + atsLen <= data.length)
                s.append("\nATS: ").append(hex(Arrays.copyOfRange(data, next + 1, next + 1 + atsLen)));
        }
        return s.toString();
    }

    /** Handles fragmented and coalesced byte chunks from USB serial. */
    public static final class Parser {
        private final List<Byte> buffer = new ArrayList<>();
        private final Listener listener;

        public Parser(Listener listener) {
            this.listener = listener;
        }

        public void clear() { buffer.clear(); }

        public void feed(byte[] chunk) {
            for (byte b : chunk) buffer.add(b);
            while (true) {
                while (buffer.size() >= 3 &&
                        !(u(0) == 0 && u(1) == 0 && u(2) == 0xff)) {
                    buffer.remove(0);
                }
                if (buffer.size() < 5) {
                    // Keep at most 2 possible start-of-frame bytes.
                    if (buffer.size() > 2 &&
                            !(u(0) == 0 && u(1) == 0 && u(2) == 0xff)) {
                        while (buffer.size() > 2) buffer.remove(0);
                    }
                    return;
                }
                int len = u(3);
                int lcs = u(4);
                if (len == 0 && lcs == 0xff) {
                    if (buffer.size() < 6) return;
                    if (u(5) == 0) {
                        discard(6);
                        listener.onAck();
                    } else {
                        buffer.remove(0);
                        listener.onInvalidFrame();
                    }
                    continue;
                }
                if (((len + lcs) & 0xff) != 0 || len == 0) {
                    buffer.remove(0);
                    listener.onInvalidFrame();
                    continue;
                }
                int total = 7 + len;
                if (buffer.size() < total) return;
                int sum = 0;
                for (int i = 0; i < len; i++) sum += u(5 + i);
                boolean ok = ((sum + u(5 + len)) & 0xff) == 0 && u(6 + len) == 0;
                if (!ok) {
                    buffer.remove(0);
                    listener.onInvalidFrame();
                    continue;
                }
                byte[] payload = new byte[len];
                for (int i = 0; i < len; i++) payload[i] = buffer.get(5 + i);
                discard(total);
                listener.onFrame(payload);
            }
        }

        private int u(int n) { return buffer.get(n) & 0xff; }
        private void discard(int count) { buffer.subList(0, count).clear(); }
    }
}
