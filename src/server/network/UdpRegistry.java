package server.network;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Registro endpoint UDP per partecipanti.
public class UdpRegistry {
    
    private static class Endpoint {
        final InetAddress address;
        final int port;
        
        Endpoint(InetAddress address, int port) {
            this.address = address;
            this.port = port;
        }
    }
    
    private final Map<Integer, Endpoint> endpoints = new ConcurrentHashMap<>();
    
    // Registra endpoint UDP per un utente online.
    public void register(int userId, InetAddress address, int udpPort) {
        if ((address != null) && (udpPort > 0) && (udpPort <= 65535))
            endpoints.put(userId, new Endpoint(address, udpPort));
    }
    
    // Rimuove l'endpoint di un utente (al logout).
    public void unregister(int userId) {
        endpoints.remove(userId);
    }
    
    /**
     * Recupera l'endpoint per un utente, se registrato.
     * Restituisce [InetAddress, port], o null se non trovato.
     */
    public Object[] getEndpoint(int userId) {
        Endpoint ep = endpoints.get(userId);
        if (ep == null) return null;
        return new Object[] { ep.address, ep.port };
    }
    
    // Svuota il registro (es. al shutdown).
    public void clear() {
        endpoints.clear();
    }
}
