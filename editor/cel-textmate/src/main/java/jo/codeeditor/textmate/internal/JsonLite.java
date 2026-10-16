package jo.codeeditor.textmate.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parseur JSON minimal du module de grammaires (ADR-002, C3).
 *
 * <p>Le module {@code :cel-textmate} dépend de {@code :cel-core} SEUL
 * (décision D1-a) : plutôt que d'ajouter une dépendance de parseur
 * JSON, ce composant interne couvre le sous-ensemble exact des
 * grammaires embarquées et tierces :</p>
 * <ul>
 *   <li>objets ({@code {}), tableaux ({@code []}), chaînes
 *       (échappements usuels : guillemet, backslash, solide, contrôles,
 *       et séquences unicode à quatre chiffres hexadécimaux),</li>
 *   <li>nombres, {@code true}/{@code false}/{@code null},</li>
 *   <li>tolérance JSONC : commentaires {@code //} et
 *       {@code /* … *&#47;} HORS chaînes (les grammaires de l'écosystème
 *       en contiennent souvent), virgule finale tolérée.</li>
 * </ul>
 *
 * <p>Classe interne non publique hors module : les valeurs exposées
 * sont {@link Map}&lt;String,Object&gt;, {@link List}&lt;Object&gt;,
 * {@link String}, {@link Double}, {@link Boolean}, {@code null}. Tout
 * écart de syntaxe lève une {@link JsonLiteException} avec la position
 * — une grammaire invalide doit échouer au CHARGEMENT (bruit immédiat),
 * jamais en cours de coloration.</p>
 */
public final class JsonLite {

    /** Erreur de syntaxe — position et extrait fournis. */
    public static final class JsonLiteException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        JsonLiteException(String message) {
            super(message);
        }
    }

    private final String src;
    private int pos;

    private JsonLite(String src) {
        this.src = src;
    }

    /**
     * Parse une source JSON/JSONC. L'objet racine doit être un objet
     * (les grammaires TextMate le sont).
     */
    public static Map<String, Object> parseObject(String source) {
        if (source == null) throw new JsonLiteException("source null");
        JsonLite p = new JsonLite(source);
        p.skipWs();
        if (p.eof() || p.peek() != '{') {
            throw p.error("objet racine attendu");
        }
        Object value = p.parseValue();
        p.skipWs();
        if (!p.eof()) throw p.error("contenu après la racine");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    // ── Machine ──────────────────────────────────────────────────

    private boolean eof() {
        return pos >= src.length();
    }

    private char peek() {
        return src.charAt(pos);
    }

    private JsonLiteException error(String why) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < pos && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        String excerpt = src.substring(Math.max(0, pos - 12), Math.min(src.length(), pos + 12));
        return new JsonLiteException(why + " (ligne " + line + ", colonne " + col
                + ", près de « " + excerpt + " »)");
    }

    /** Saute espaces et commentaires (hors chaînes). */
    private void skipWs() {
        while (!eof()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                while (!eof() && peek() != '\n') pos++;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                pos += 2;
                while (!eof() && !(peek() == '*' && pos + 1 < src.length()
                        && src.charAt(pos + 1) == '/')) {
                    pos++;
                }
                if (eof()) throw error("commentaire /* non fermé");
                pos += 2;
            } else {
                break;
            }
        }
    }

    private Object parseValue() {
        skipWs();
        if (eof()) throw error("valeur attendue");
        char c = peek();
        switch (c) {
            case '{': return parseObjectValue();
            case '[': return parseArrayValue();
            case '"': return parseString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                throw error("valeur inattendue");
        }
    }

    private void expect(String word) {
        if (!src.startsWith(word, pos)) throw error("« " + word + " » attendu");
        pos += word.length();
    }

    private Map<String, Object> parseObjectValue() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (!eof() && peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWs();
            if (eof() || peek() != '"') throw error("clé de chaîne attendue");
            String key = parseString();
            skipWs();
            if (eof() || peek() != ':') throw error("« : » attendu");
            pos++;
            Object value = parseValue();
            out.put(key, value);
            skipWs();
            if (eof()) throw error("objet non fermé");
            if (peek() == ',') {
                pos++;
                skipWs();
                if (!eof() && peek() == '}') { // virgule finale tolérée
                    pos++;
                    return out;
                }
                continue;
            }
            if (peek() == '}') {
                pos++;
                return out;
            }
            throw error("« , » ou « } » attendu");
        }
    }

    private List<Object> parseArrayValue() {
        List<Object> out = new ArrayList<>();
        pos++; // [
        skipWs();
        if (!eof() && peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            Object value = parseValue();
            out.add(value);
            skipWs();
            if (eof()) throw error("tableau non fermé");
            if (peek() == ',') {
                pos++;
                skipWs();
                if (!eof() && peek() == ']') { // virgule finale tolérée
                    pos++;
                    return out;
                }
                continue;
            }
            if (peek() == ']') {
                pos++;
                return out;
            }
            throw error("« , » ou « ] » attendu");
        }
    }

    private String parseString() {
        StringBuilder sb = new StringBuilder();
        pos++; // "
        while (true) {
            if (eof()) throw error("chaîne non fermée");
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (eof()) throw error("échappement non terminé");
                char e = src.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) throw error("\\uXXXX incomplet");
                        String hex = src.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException nfe) {
                            throw error("\\uXXXX invalide : " + hex);
                        }
                        pos += 4;
                        break;
                    default:
                        throw error("échappement inconnu : \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Double parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (!eof()) {
            char c = peek();
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                    || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        String num = src.substring(start, pos);
        try {
            return Double.parseDouble(num);
        } catch (NumberFormatException nfe) {
            throw error("nombre invalide : " + num);
        }
    }

    // ── Accès typés pratiques (null-sûrs) ────────────────────────

    /** Chaîne ou null. */
    public static String str(Object value) {
        return value instanceof String ? (String) value : null;
    }

    /** Liste (vide si absent ou d'un autre type). */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    /** Objet (vide si absent ou d'un autre type). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }
}
