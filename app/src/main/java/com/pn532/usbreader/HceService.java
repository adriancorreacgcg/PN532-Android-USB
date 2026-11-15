package com.pn532.usbreader;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

public class HceService extends HostApduService {

    private static final byte[] NO_ACTIVO = { 0x6F, 0x00 };
    private static final byte[] SIN_TARJETA = { 0x6A, (byte) 0x82 };

    @Override public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (!Puente.I.activo()) return NO_ACTIVO;
        byte[] r = Puente.I.intercambiar(apdu, 1500);
        return r == null ? SIN_TARJETA : r;
    }

    @Override public void onDeactivated(int reason) { }
}