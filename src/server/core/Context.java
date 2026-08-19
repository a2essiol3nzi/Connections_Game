package server.core;

import server.loader.GameLoader;
import server.network.UdpNotifier;
import server.persistence.UserStore;

/**
 * Contenitore delle risorse condivise del server, passato agli handler e agli
 * altri componenti. Evita oggett globali: un'unica istanza creata in
 * ServerMain e condivisa per referenza. Raggruppa loader/store/manager/notifier.
 */
public class Context {
    public final ServerConfig cfg;
    public final UserStore users;
    public final GameManager games;
    public final UdpNotifier notifier;

    public Context(ServerConfig cfg, GameLoader loader) {
        this.cfg = cfg;
        this.users = new UserStore(cfg.persistFile);
        this.games = new GameManager(loader, cfg.gameDurationSec);
        this.notifier = new UdpNotifier(cfg.udpPort);
    }
}
