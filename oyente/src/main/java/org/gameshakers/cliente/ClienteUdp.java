package org.gameshakers.cliente;

import org.gameshakers.comun.Datagrama;
import org.gameshakers.comun.Protocolo;
import org.gameshakers.comun.Seguridad;

import java.io.IOException;
import java.net.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Conexión UDP del oyente con la emisora. */
public class ClienteUdp {

    /** Lo que la ventana necesita saber de la conexión. */
    public interface Eventos {
        void conectado(String nombreEmisora);

        void info(String titulo, boolean microfono, int oyentes);

        /** La conexión terminó; {@code motivo} explica por qué (null si fue el usuario). */
        void desconectado(String motivo);
    }

    private final Eventos eventos;
    private final Reproductor reproductor;
    private DatagramSocket socket;
    private InetSocketAddress emisora;
    private String nombre;
    private String contrasena;
    private volatile boolean activo;
    private volatile boolean bienvenido;
    private volatile long ultimoPaquete;

    private volatile long recibidos = 0, perdidos = 0;
    private int esperado = -1;

    public ClienteUdp(Eventos eventos, Reproductor reproductor) {
        this.eventos = eventos;
        this.reproductor = reproductor;
    }

    /**
     * @param host       IP o nombre de dominio de la emisora
     * @param contrasena vacía si la emisora no tiene
     */
    public void conectar(String host, int puerto, String nombre, String contrasena) throws IOException {
        this.emisora = new InetSocketAddress(InetAddress.getByName(host), puerto);
        this.nombre = nombre.replace(Protocolo.SEP, " ");
        this.contrasena = contrasena == null ? "" : contrasena;
        socket = new DatagramSocket();
        socket.setReceiveBufferSize(1 << 20);
        socket.setSoTimeout(1000);
        activo = true;
        ultimoPaquete = System.currentTimeMillis();
        enviar(Protocolo.HOLA + Protocolo.SEP + this.nombre);

        Thread receptor = new Thread(this::recibir, "oyente-receptor");
        receptor.setDaemon(true);
        receptor.start();
        Thread ping = new Thread(this::latido, "oyente-ping");
        ping.setDaemon(true);
        ping.start();
    }

    /** Desconexión pedida por el usuario: avisa a la emisora con CHAO. */
    public void desconectar() {
        if (!activo) return;
        enviar(Protocolo.CHAO);
        cerrar(null);
    }

    public boolean activo() {
        return activo;
    }

    public long recibidos() {
        return recibidos;
    }

    public long perdidos() {
        return perdidos;
    }

    private void cerrar(String motivo) {
        if (!activo) return;
        activo = false;
        socket.close();
        eventos.desconectado(motivo);
    }

    private void recibir() {
        byte[] buf = new byte[Protocolo.TAM_MAX_PAQUETE];
        while (activo) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                revisarConexion();
                continue;
            } catch (IOException e) {
                // p. ej. "puerto inalcanzable" si la emisora no está encendida
                revisarConexion();
                continue;
            }
            if (p.getPort() != emisora.getPort()) continue;    // solo se escucha a la emisora
            ultimoPaquete = System.currentTimeMillis();

            if (Datagrama.esAudio(p)) {
                int seq = Datagrama.leerSecuencia(p);
                if (esperado >= 0 && seq > esperado) perdidos += seq - esperado;
                if (esperado >= 0 && seq < esperado && esperado - seq < 1000) continue;   // llegó tarde y desordenado
                esperado = seq + 1;
                recibidos++;
                byte[] mp3 = new byte[p.getLength() - Protocolo.CABECERA_AUDIO];
                System.arraycopy(p.getData(), p.getOffset() + Protocolo.CABECERA_AUDIO, mp3, 0, mp3.length);
                reproductor.recibir(mp3);
                continue;
            }

            String[] partes = Datagrama.leerTexto(p).split(Protocolo.SEP_REGEX, -1);
            switch (partes[0]) {
                case Protocolo.RETO -> {
                    if (partes.length < 3) break;
                    if ("1".equals(partes[2]) && contrasena.isEmpty()) {
                        cerrar("Esta emisora pide contraseña. Escríbela e intenta de nuevo.");
                        break;
                    }
                    // se responde con el código recibido: así la emisora sabe que esta dirección es real
                    enviar(Protocolo.UNIRSE + Protocolo.SEP + partes[1] + Protocolo.SEP
                            + Seguridad.prueba(contrasena, partes[1]) + Protocolo.SEP + nombre);
                }
                case Protocolo.RECHAZO -> cerrar("La emisora rechazó la conexión: "
                        + (partes.length > 1 ? partes[1] : "sin motivo"));
                case Protocolo.BIENVENIDO -> {
                    esperado = -1;
                    if (!bienvenido) {
                        bienvenido = true;
                        eventos.conectado(partes.length > 1 ? partes[1] : "Emisora");
                    }
                }
                case Protocolo.INFO -> {
                    if (partes.length >= 4) {
                        int oyentes;
                        try {
                            oyentes = Integer.parseInt(partes[3]);
                        } catch (NumberFormatException e) {
                            oyentes = 0;
                        }
                        eventos.info(partes[1], "1".equals(partes[2]), oyentes);
                    }
                }
                case Protocolo.QUIEN -> enviar(Protocolo.HOLA + Protocolo.SEP + nombre);
                case Protocolo.ADIOS -> cerrar("La emisora se apagó.");
                default -> { }
            }
        }
    }

    private void revisarConexion() {
        long sin = System.currentTimeMillis() - ultimoPaquete;
        if (!bienvenido && sin > 8_000) {
            cerrar("No hubo respuesta de " + emisora.getAddress().getHostAddress() + ":" + emisora.getPort()
                    + ". Revisa la IP y el puerto, que la emisora esté encendida, el firewall y (por internet) "
                    + "la redirección del puerto en el router del locutor.");
        } else if (bienvenido && sin > Protocolo.TIEMPO_MAX_SIN_EMISORA_MS) {
            cerrar("Se perdió la conexión con la emisora.");
        }
    }

    private void latido() {
        while (activo) {
            try {
                Thread.sleep(Protocolo.INTERVALO_PING_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!activo) return;
            // mientras no haya respuesta se repite el saludo (UDP puede perderlo)
            enviar(bienvenido ? Protocolo.PING : Protocolo.HOLA + Protocolo.SEP + nombre);
        }
    }

    private void enviar(String msg) {
        try {
            socket.send(Datagrama.texto(emisora, msg));
        } catch (IOException ignorada) {
            // si no sale, el siguiente PING lo intentará otra vez
        }
    }

    /**
     * Busca emisoras en la red local enviando BUSCAR por broadcast al puerto predeterminado.
     * (El broadcast no sale de la red local: por internet hay que escribir la IP.)
     * @return "ip:puerto" -> nombre de la emisora
     */
    public static Map<String, String> buscarEmisoras(int esperaMs) {
        Map<String, String> encontradas = new LinkedHashMap<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(200);
            for (InetAddress destino : destinosBroadcast()) {
                try {
                    s.send(Datagrama.texto(new InetSocketAddress(destino, Protocolo.PUERTO_PREDETERMINADO), Protocolo.BUSCAR));
                } catch (IOException ignorada) {
                    // esa interfaz no permite broadcast
                }
            }
            long fin = System.currentTimeMillis() + esperaMs;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < fin) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String[] partes = Datagrama.leerTexto(p).split(Protocolo.SEP_REGEX);
                if (partes[0].equals(Protocolo.AQUI)) {
                    String puerto = partes.length > 2 ? partes[2] : String.valueOf(Protocolo.PUERTO_PREDETERMINADO);
                    encontradas.put(p.getAddress().getHostAddress() + ":" + puerto, partes.length > 1 ? partes[1] : "Emisora");
                }
            }
        } catch (IOException ignorada) {
            // sin red: no se encuentra nada
        }
        return encontradas;
    }

    private static java.util.List<InetAddress> destinosBroadcast() throws SocketException {
        java.util.List<InetAddress> destinos = new java.util.ArrayList<>();
        for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp()) continue;
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getBroadcast() != null) destinos.add(ia.getBroadcast());
            }
        }
        try {
            destinos.add(InetAddress.getByName("255.255.255.255"));
            destinos.add(InetAddress.getLoopbackAddress());   // por si la emisora está en este mismo equipo
        } catch (UnknownHostException ignorada) {
            // no ocurre con direcciones literales
        }
        return destinos;
    }
}
