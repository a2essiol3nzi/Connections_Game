package protocol.payload;

import java.util.List;

/**
 * Singolo gruppo della soluzione di una partita conclusa 
 * (tema nascosto + le 4 parole che lo compongono).
 */
public class GroupPayload {
    public String theme;
    public List<String> words;
}