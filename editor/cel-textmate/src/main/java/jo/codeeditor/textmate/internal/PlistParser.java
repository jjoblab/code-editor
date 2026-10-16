package jo.codeeditor.textmate.internal;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Parseur plist XML (C3-bis, compatibilité grammaires tierces) : les
 * grammaires TextMate historiques — {@code .tmLanguage} sans extension
 * JSON, dont le kotlin tierce — sont des documents
 * plist, pas du JSON.
 *
 * <p>Produit le MÊME modèle que {@link JsonLite#parseObject} : dictionnaire
 * → {@code Map<String, Object>}, tableau → {@code List<Object>},
 * chaîne/entier/réel → {@code String}, booléen → {@code Boolean}. Les
 * {@code <data>} (base64) et {@code <date>} sont ignorés — aucun champ
 * de grammaire ne s'en sert.</p>
 *
 * <p>Entités XML décodées par le parseur DOM ({@code &amp;} → {@code &}).
 * Désactivation des entités externes (XXE) — les grammaires du dépôt
 * n'en contiennent aucune, la prudence reste de mise sur des sources
 * tierces.</p>
 */
public final class PlistParser {

    private PlistParser() {}

    /**
     * Parse une source plist XML. Leve {@link IllegalArgumentException}
     * sur XML malformé ou racine non {@code plist}.
     */
    public static Map<String, Object> parseObject(String source) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Doctype interdit (XXE bloqué à la source) ; les accès
            // externes coupés si la propriété est supportée (constantes
            // littérales : ACCESS_EXTERNAL_DTD n'est pas exposé par le
            // jar Android, la valeur est la même côté JVM).
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            try {
                factory.setAttribute(
                        "http://javax.xml.XMLConstants/property/accessExternalDTD", "");
                factory.setAttribute(
                        "http://javax.xml.XMLConstants/property/accessExternalSchema", "");
            } catch (IllegalArgumentException unsupported) {
                // Propriété absente de cette fabrique — le refus de
                // doctype reste la garde principale.
            }
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();
            if (!"plist".equals(root.getNodeName())) {
                throw new IllegalArgumentException("racine non plist : " + root.getNodeName());
            }
            Element dict = firstChildElement(root, "dict");
            if (dict == null) {
                throw new IllegalArgumentException("plist sans dict racine");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) parseValue(dict);
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("plist illisible : " + e.getMessage(), e);
        }
    }

    /** Vrai si la source ressemble à un document XML (plist). */
    public static boolean looksLikeXml(String source) {
        String trimmed = source.stripLeading();
        return trimmed.startsWith("<?xml") || trimmed.startsWith("<plist")
                || trimmed.startsWith("<!DOCTYPE plist");
    }

    // ── Conversion DOM → modèle JsonLite ──────────────────────────

    private static Object parseValue(Element element) {
        switch (element.getNodeName()) {
            case "dict":
                return parseDict(element);
            case "array":
                return parseArray(element);
            case "string":
                return textOf(element);
            case "integer":
            case "real":
                return textOf(element);
            case "true":
                return Boolean.TRUE;
            case "false":
                return Boolean.FALSE;
            default:
                // <data>, <date> et inconnus : ignorés (null).
                return null;
        }
    }

    private static Map<String, Object> parseDict(Element dict) {
        Map<String, Object> out = new LinkedHashMap<>();
        NodeList children = dict.getChildNodes();
        String pendingKey = null;
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            if ("key".equals(e.getNodeName())) {
                pendingKey = textOf(e);
            } else if (pendingKey != null) {
                out.put(pendingKey, parseValue(e));
                pendingKey = null;
            }
        }
        return out;
    }

    private static List<Object> parseArray(Element array) {
        List<Object> out = new ArrayList<>();
        NodeList children = array.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element) {
                out.add(parseValue((Element) n));
            }
        }
        return out;
    }

    private static String textOf(Element element) {
        StringBuilder sb = new StringBuilder();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString();
    }

    private static Element firstChildElement(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }
}
