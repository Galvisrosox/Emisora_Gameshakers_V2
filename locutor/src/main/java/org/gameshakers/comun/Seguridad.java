package org.gameshakers.comun;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Funciones de seguridad compartidas por la emisora y el oyente. */
public final class Seguridad {

    /** HMAC-SHA256 en hexadecimal. */
    public static String hmac(byte[] clave, String mensaje) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clave, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(mensaje.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }

    /**
     * Prueba de que el oyente conoce la contraseña sin enviarla por la red.
     * Vacía cuando la emisora no tiene contraseña.
     */
    public static String prueba(String contrasena, String codigo) {
        if (contrasena == null || contrasena.isEmpty()) return "";
        return hmac(contrasena.getBytes(StandardCharsets.UTF_8), "emisora|" + codigo);
    }

    /** Compara en tiempo constante (no revela cuántos caracteres coinciden). */
    public static boolean iguales(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private Seguridad() {
    }
}
