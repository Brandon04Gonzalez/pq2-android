package com.pq2port.app;

import java.util.Locale;

/** Comprueba que un archivo sea Persona Q2 (USA) descifrado, por su cabecera. */
final class Rom {
    static final long PROGRAM_ID = 0x00040000001D7100L;
    /** cuántos bytes del principio del archivo hacen falta para la comprobación */
    static final int HEADER = 0x10000;

    private Rom() {}

    private static long le(byte[] data, int offset, int bytes) {
        long value = 0;
        for (int i = bytes - 1; i >= 0; i--) value = value << 8 | (data[offset + i] & 0xFF);
        return value;
    }

    private static long be(byte[] data, int offset, int bytes) {
        long value = 0;
        for (int i = 0; i < bytes; i++) value = value << 8 | (data[offset + i] & 0xFF);
        return value;
    }

    private static long align(long value, long to) {
        return (value + to - 1) / to * to;
    }

    private static boolean magic(byte[] data, long offset, String word) {
        if (offset < 0 || offset + 4 > data.length) return false;
        for (int i = 0; i < 4; i++) if (data[(int) offset + i] != word.charAt(i)) return false;
        return true;
    }

    /** null si está bien, o lo que falla, para mostrarlo. */
    static String check(byte[] header, long size) {
        if (size < 64L * 1024 * 1024) return "El archivo es demasiado pequeño para ser Persona Q2.";
        long ncch;
        if (magic(header, 0x100, "NCSD")) {
            ncch = le(header, 0x120, 4) * 0x200;
        } else if (magic(header, 0x100, "NCCH")) {
            ncch = 0;
        } else if (le(header, 0, 4) == 0x2020) {
            // CIA: cabecera, certificados, ticket, TMD y luego los contenidos
            long certs = le(header, 0x08, 4), ticket = le(header, 0x0C, 4), tmd = le(header, 0x10, 4);
            long tmdOffset = align(align(align(0x2020, 64) + certs, 64) + ticket, 64);
            ncch = align(tmdOffset + tmd, 64);
            if (tmdOffset + 0x200 < header.length) {
                long signature = be(header, (int) tmdOffset, 4);
                long body = tmdOffset + 4 + (signature == 0x10003 ? 0x23C : signature == 0x10004 ? 0x13C : 0x7C);
                if (body + 0xC4 + 0x24 * 64 + 0x10 <= header.length) {
                    long record = body + 0xC4 + 0x24 * 64;
                    int type = (int) be(header, (int) record + 6, 2);
                    if ((type & 1) != 0) return "Este .cia está cifrado. Necesitas una copia descifrada (\"decrypted\") de Persona Q2.";
                }
            }
        } else {
            return "No es un juego de 3DS (.cia, .3ds o .cxi).";
        }
        if (!magic(header, ncch + 0x100, "NCCH")) return "No se encontró el ejecutable del juego dentro del archivo.";
        int flags = (int) ncch + 0x188;
        boolean noCrypto = (header[flags + 7] & 0x04) != 0;
        if (!noCrypto && header[flags + 3] != 0) return "El juego está cifrado. Necesitas una copia descifrada de Persona Q2.";
        long programId = le(header, (int) ncch + 0x118, 8);
        if (programId != PROGRAM_ID) {
            return String.format(Locale.US,
                "Este archivo es el título %016X, no Persona Q2 (USA, 00040000001D7100). El port y la traducción son para la versión USA.",
                programId);
        }
        return null;
    }
}
