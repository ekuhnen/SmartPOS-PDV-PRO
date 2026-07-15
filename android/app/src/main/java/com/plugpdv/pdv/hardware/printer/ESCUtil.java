package com.plugpdv.pdv.hardware.printer;

public class ESCUtil {

    /**
     * Comando para cortar o papel.
     * GS V m
     * 0: Full cut (corte total)
     * 1: Partial cut (corte parcial)
     */
    public static byte[] cutPaper() {
        // Comando para corte total
        return new byte[]{0x1D, 0x56, 0x00};
    }

    /**
     * Comando para ativar negrito
     */
    public static byte[] boldOn() {
        return new byte[]{0x1B, 0x45, 0x01};
    }

    /**
     * Comando para desativar negrito
     */
    public static byte[] boldOff() {
        return new byte[]{0x1B, 0x45, 0x00};
    }
}
