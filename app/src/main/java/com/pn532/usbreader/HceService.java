package com.pn532.usbreader;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

public class HceService extends HostApduService {

    private static final byte[] NO_ACTIVO = { 0x6F, 0x00 };
    private static final byte[] SIN_TARJETA = { 0x6A, (byte) 0x82 };

    @Override public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (!Puente.I.activo()) return NO_ACTIVO;
        if (Puente.I.estatico()) {
            byte[] r = Perfil.responder(apdu);
            Puente.I.anotar("> " + Pn532Protocol.hex(apdu));
            Puente.I.anotar("< " + Pn532Protocol.hex(r));
            return r;
        }
        byte[] r = Puente.I.intercambiar(apdu, 2500);
        return r == null ? SIN_TARJETA : r;
    }

    @Override public void onDeactivated(int reason) { }
}