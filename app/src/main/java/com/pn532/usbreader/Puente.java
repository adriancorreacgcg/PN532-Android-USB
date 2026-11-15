package com.pn532.usbreader;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class Puente {

    public static final Puente I = new Puente();

    private final LinkedBlockingQueue<byte[]> cola = new LinkedBlockingQueue<>();
    private final Object lock = new Object();
    private byte[] respuesta;
    private volatile boolean activo;
    private volatile boolean estatico;

    private Puente() { }

    public boolean activo() { return activo; }
    public boolean estatico() { return estatico; }

    public void activar() {
        cola.clear();
        synchronized (lock) { respuesta = null; }
        estatico = false;
        activo = true;
    }

    public void activarEstatico() {
        cola.clear();
        synchronized (lock) { respuesta = null; }
        estatico = true;
        activo = true;
    }

    public void desactivar() {
        activo = false;
        cola.clear();
        synchronized (lock) {
            respuesta = null;
            lock.notifyAll();
        }
    }

    public byte[] intercambiar(byte[] apdu, int timeoutMs) {
        if (!activo) return null;
        synchronized (lock) { respuesta = null; }
        try {
            cola.put(apdu);
        } catch (InterruptedException e) {
            return null;
        }
        long fin = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (respuesta == null) {
                long resto = fin - System.currentTimeMillis();
                if (resto <= 0) return null;
                try {
                    lock.wait(resto);
                } catch (InterruptedException e) {
                    return null;
                }
            }
            return respuesta;
        }
    }

    public byte[] siguiente(int ms) throws InterruptedException {
        return cola.poll(ms, TimeUnit.MILLISECONDS);
    }

    public void responder(byte[] r) {
        synchronized (lock) {
            respuesta = r;
            lock.notifyAll();
        }
    }
}