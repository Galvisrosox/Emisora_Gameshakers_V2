package org.gameshakers.servidor;

import org.gameshakers.comun.Datagrama;
import org.gameshakers.comun.Protocolo;
import org.gameshakers.comun.Seguridad;

import java.io.IOException;
import java.net.*;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Socket UDP de la emisora: atiende a los oyentes y les reparte el audio.
 * Cada paquete de audio se envía a cada oyente registrado (unicast).
 *
 * Medidas para exponerla a internet:
 *  - Conexión en dos pasos (HOLA -> RETO -> UNIRSE): solo se transmite a direcciones que
 *    demostraron recibir el código, así nadie puede dirigir el audio a una dirección falsificada.
 *    El código se calcula (HMAC con una clave aleatoria), no se guarda: un aluvión de HOLA no gasta memoria.
 *  - Contraseña opcional: el oyente envía una prueba HMAC, nunca la contraseña.
 *  - Máximo de oyentes.
 *  - Tras {@link #MAX_FALLOS} contraseñas incorrectas, la IP queda bloqueada {@link #BLOQUEO_MS} ms.
 */
public class ServidorUdp {

    private static final int MAX_FALLOS = 5;
    private static final long BLOQUEO_MS = 60_000;
    private static final long VENTANA_CODIGO_MS = 30_000;
    private static final int TAM_MAX_MENSAJE = 512;

    private final Map<InetSocketAddress, Oyente> oyentes = new ConcurrentHashMap<>();
    private final Map<InetAddress, Intentos> intentos = new ConcurrentHashMap<>();
    private final byte[] secreto = new byte[32];
    private final Consumer<String> registro;
    private final Runnable alCambiarOyentes;
    private final Supplier<String> info;

    private DatagramSocket socket;
    private ScheduledExecutorService tareas;
    private volatile boolean activo;
    private int secuencia = 0;
    private int puerto;
    private String contrasena = "";
    private int maxOyentes = Protocolo.MAX_OYENTES_PREDETERMINADO;

    private static class Intentos {
        int fallos;
        long bloqueadoHasta;
    }

    /**
     * @param registro         recibe mensajes para mostrar en la bitácora
     * @param alCambiarOyentes se llama cuando alguien entra o sale
     * @param info             texto "titulo|mic" que se envía en los mensajes INFO
     */
    public ServidorUdp(Consumer<String> registro, Runnable alCambiarOyentes, Supplier<String> info) {
        this.registro = registro;
        this.alCambiarOyentes = alCambiarOyentes;
        this.info = info;
    }

    public void iniciar(int puerto, String contrasena, int maxOyentes) throws SocketException {
        this.puerto = puerto;
        this.contrasena = contrasena == null ? "" : contrasena;
        this.maxOyentes = maxOyentes;
        new SecureRandom().nextBytes(secreto);   // códigos de sesiones anteriores dejan de valer
        intentos.clear();

        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(puerto));
        socket.setSendBufferSize(1 << 20);
        activo = true;

        Thread receptor = new Thread(this::recibir, "emisora-receptor");
        receptor.setDaemon(true);
        receptor.start();

        tareas = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "emisora-tareas");
            t.setDaemon(true);
            return t;
        });
        tareas.scheduleAtFixedRate(this::revisarOyentes, 1, 1, TimeUnit.SECONDS);
        tareas.scheduleAtFixedRate(this::enviarInfo, 2, 2, TimeUnit.SECONDS);
    }

    public void detener() {
        if (!activo) return;
        activo = false;
        for (Oyente o : oyentes.values()) enviarTexto(o.direccion, Protocolo.ADIOS);
        oyentes.clear();
        alCambiarOyentes.run();
        if (tareas != null) tareas.shutdownNow();
        if (socket != null) socket.close();
    }

    public boolean activo() {
        return activo;
    }

    public int puerto() {
        return puerto;
    }

    public boolean tieneContrasena() {
        return !contrasena.isEmpty();
    }

    public int maxOyentes() {
        return maxOyentes;
    }

    public List<Oyente> oyentes() {
        List<Oyente> lista = new ArrayList<>(oyentes.values());
        lista.sort((a, b) -> a.nombre.compareToIgnoreCase(b.nombre));
        return lista;
    }

    /** Envía el mismo bloque MP3 a todos los oyentes. */
    public void enviarAudio(byte[] mp3) {
        if (!activo || oyentes.isEmpty()) return;
        byte[] datos = Datagrama.audio(secuencia++, mp3, mp3.length);
        for (Oyente o : oyentes.values()) {
            try {
                socket.send(new DatagramPacket(datos, datos.length, o.direccion));
            } catch (IOException e) {
                // un oyente con problemas no detiene el envío a los demás
            }
        }
    }

    /** Envía de inmediato la información de "sonando ahora" a todos. */
    public void enviarInfo() {
        if (!activo) return;
        String msg = Protocolo.INFO + Protocolo.SEP + info.get() + Protocolo.SEP + oyentes.size();
        for (Oyente o : oyentes.values()) enviarTexto(o.direccion, msg);
    }

    private void recibir() {
        byte[] buf = new byte[TAM_MAX_MENSAJE + 1];
        while (activo) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (IOException e) {
                if (activo) registro.accept("Error al recibir: " + e.getMessage());
                continue;
            }
            if (p.getLength() > TAM_MAX_MENSAJE) continue;   // ningún mensaje válido es tan largo
            InetSocketAddress origen = (InetSocketAddress) p.getSocketAddress();
            String[] partes = Datagrama.leerTexto(p).split(Protocolo.SEP_REGEX, -1);
            switch (partes[0]) {
                case Protocolo.HOLA -> enviarTexto(origen, Protocolo.RETO + Protocolo.SEP + codigo(origen, 0)
                        + Protocolo.SEP + (tieneContrasena() ? "1" : "0"));
                case Protocolo.UNIRSE -> unirse(origen, partes);
                case Protocolo.PING -> {
                    Oyente o = oyentes.get(origen);
                    if (o != null) o.ultimaSenal = System.currentTimeMillis();
                    else enviarTexto(origen, Protocolo.QUIEN);
                }
                case Protocolo.CHAO -> {
                    Oyente o = oyentes.remove(origen);
                    if (o != null) {
                        registro.accept("< Se desconectó " + o.nombre);
                        cambiaronOyentes();
                    }
                }
                case Protocolo.BUSCAR -> enviarTexto(origen, Protocolo.AQUI + Protocolo.SEP + Protocolo.NOMBRE_EMISORA
                        + Protocolo.SEP + puerto);
                default -> { /* mensaje desconocido: se ignora */ }
            }
        }
    }

    /** UNIRSE|codigo|prueba|nombre */
    private void unirse(InetSocketAddress origen, String[] partes) {
        if (partes.length < 4) return;
        String cod = partes[1];
        // el código demuestra que el oyente recibe en esta dirección; si no coincide, se ignora en silencio
        if (!Seguridad.iguales(cod, codigo(origen, 0)) && !Seguridad.iguales(cod, codigo(origen, 1))) return;

        long ahora = System.currentTimeMillis();
        Intentos in = intentos.computeIfAbsent(origen.getAddress(), k -> new Intentos());
        synchronized (in) {
            if (ahora < in.bloqueadoHasta) {
                enviarTexto(origen, Protocolo.RECHAZO + Protocolo.SEP + "Demasiados intentos fallidos. Espera un minuto.");
                return;
            }
            if (tieneContrasena() && !Seguridad.iguales(partes[2], Seguridad.prueba(contrasena, cod))) {
                in.fallos++;
                String ip = origen.getAddress().getHostAddress();
                if (in.fallos >= MAX_FALLOS) {
                    in.bloqueadoHasta = ahora + BLOQUEO_MS;
                    in.fallos = 0;
                    registro.accept("! " + ip + " bloqueada 1 minuto por contraseñas incorrectas");
                } else {
                    registro.accept("! Contraseña incorrecta desde " + ip);
                }
                enviarTexto(origen, Protocolo.RECHAZO + Protocolo.SEP + "Contraseña incorrecta.");
                return;
            }
            in.fallos = 0;
        }

        String nombre = !partes[3].isBlank() ? limpiar(partes[3]) : "Oyente";
        Oyente previo = oyentes.get(origen);
        if (previo == null && oyentes.size() >= maxOyentes) {
            enviarTexto(origen, Protocolo.RECHAZO + Protocolo.SEP + "La emisora está llena (" + maxOyentes + " oyentes).");
            registro.accept("! " + nombre + " no pudo entrar: emisora llena");
            return;
        }
        if (previo != null) {
            previo.nombre = nombre;
            previo.ultimaSenal = ahora;
        } else {
            oyentes.put(origen, new Oyente(origen, nombre));
        }
        enviarTexto(origen, Protocolo.BIENVENIDO + Protocolo.SEP + Protocolo.NOMBRE_EMISORA);
        if (previo == null) {
            registro.accept("> Se conectó " + nombre + " desde " + origen.getAddress().getHostAddress());
            cambiaronOyentes();
        }
        enviarInfo();
    }

    /** Código para una dirección: HMAC(secreto, ip:puerto:ventana). {@code atras}=1 acepta la ventana anterior. */
    private String codigo(InetSocketAddress dir, int atras) {
        long ventana = System.currentTimeMillis() / VENTANA_CODIGO_MS - atras;
        return Seguridad.hmac(secreto, dir.getAddress().getHostAddress() + ":" + dir.getPort() + ":" + ventana)
                .substring(0, 32);
    }

    /** Saca de la lista a quien lleva demasiado tiempo sin enviar PING y limpia bloqueos vencidos. */
    private void revisarOyentes() {
        long ahora = System.currentTimeMillis();
        boolean cambio = false;
        for (Oyente o : oyentes.values()) {
            if (ahora - o.ultimaSenal > Protocolo.TIEMPO_MAX_SIN_PING_MS && oyentes.remove(o.direccion, o)) {
                registro.accept("< Se desconectó " + o.nombre + " (sin respuesta)");
                cambio = true;
            }
        }
        intentos.values().removeIf(in -> in.fallos == 0 && ahora > in.bloqueadoHasta);
        if (cambio) cambiaronOyentes();
    }

    private void cambiaronOyentes() {
        List<Oyente> lista = oyentes();
        StringBuilder sb = new StringBuilder("   Oyentes conectados (" + lista.size() + "): ");
        if (lista.isEmpty()) sb.append("nadie");
        for (int i = 0; i < lista.size(); i++) sb.append(i > 0 ? ", " : "").append(lista.get(i).nombre);
        registro.accept(sb.toString());
        alCambiarOyentes.run();
    }

    private void enviarTexto(SocketAddress destino, String msg) {
        try {
            socket.send(Datagrama.texto(destino, msg));
        } catch (IOException ignorada) {
            // UDP no garantiza entrega; el siguiente mensaje lo intentará otra vez
        }
    }

    private static String limpiar(String nombre) {
        String n = nombre.replace(Protocolo.SEP, " ").replaceAll("\\p{Cntrl}", "").strip();
        return n.length() > 30 ? n.substring(0, 30) : n;
    }
}
