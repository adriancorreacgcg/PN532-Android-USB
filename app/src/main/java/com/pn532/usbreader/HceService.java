package com.pn532.usbreader;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

public class HceService extends HostApduService {

    private static final byte[] NO_ACTIVO = { 0x6F, 0x00 };
    private static final byte[] SIN_TARJETA = { 0x6A, (byte) 0x82 };
    private static final byte[] FALLA = { 0x6F, 0x00 };

    @Override public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (!Puente.I.activo()) return NO_ACTIVO;
        if (Puente.I.estatico()) {
            int ins = apdu.length > 1 ? (apdu[1] & 0xff) : 0;
            int modo = Perfil.varianteActual();
            byte[] r;
            if (modo == 3 && ins == 0xA4 && Perfil.esAid(apdu)) {
                r = Perfil.fciBanda();
            } else if (modo == 3 && ins == 0xA8) {
                r = Perfil.gpoBanda();
            } else if (modo == 3 && ins == 0xAE) {
                r = FALLA;
            } else {
                byte[] guardado = Aprendiz.buscar(apdu);
                if (guardado == null && modo == 3 && ins == 0xB2) guardado = Perfil.registro();
                r = guardado != null ? guardado : Perfil.responder(apdu);
            }
            r = Datos.aplicar(r);
            Puente.I.anotar("> " + Pn532Protocol.hex(apdu));
            Puente.I.anotar("< " + Pn532Protocol.hex(r));
            return r;
        }
        byte[] r = Puente.I.intercambiar(apdu, 2500);
        return r == null ? SIN_TARJETA : r;
    }

    @Override public void onDeactivated(int reason) { }
}