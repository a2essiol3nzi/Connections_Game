package server.model;

import java.util.List;

/**
 * Classe POJO semplice per JSON delle partite:
 * [ { "gameId": 0, "groups": [ {"theme": "...", "words":[4]}, ... ] }, ... ]
 * Il campo "theme" (la categoria nascosta) NON viene mai inviata al client.
 */
public class GameData {

    public int gameId;
    public List<Group> groups;

    public static class Group {
        public String theme;
        public List<String> words;
    }
}
