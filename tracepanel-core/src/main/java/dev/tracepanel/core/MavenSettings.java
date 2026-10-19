package dev.tracepanel.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The mirror of Maven Central that Maven's settings (~/.m2/settings.xml) name, as a company's Artifactory or Nexus is
 * set up: its URL, and the login of the server with its id. A password Maven encrypted ({...}) can't be read here: the
 * mirror is used without it.
 */
final class MavenSettings {
    private static final Pattern ENV = Pattern.compile("\\$\\{env\\.([A-Za-z_][A-Za-z0-9_]*)}");

    private MavenSettings() {}

    /** The mirror, or null: no settings, none that mirrors Central, or settings that can't be read. */
    static AgentDownload.Repository mirror(Path settings, Map<String, String> env) {
        if (!Files.isRegularFile(settings)) return null;
        try (InputStream in = Files.newInputStream(settings)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(in);
            for (Element mirror : children(document.getDocumentElement(), "mirrors", "mirror")) {
                String url = text(mirror, "url", env);
                if (url == null || !mirrorsCentral(text(mirror, "mirrorOf", env))) continue;
                return new AgentDownload.Repository(URI.create(url), login(document, text(mirror, "id", env), env));
            }
        } catch (IOException | RuntimeException | ParserConfigurationException | SAXException e) {
            Agent.warn("couldn't read " + settings + ": " + e.getMessage());
        }
        return null;
    }

    /** Whether a mirror's mirrorOf ("*", "central", "external:*", "*,!central", "repo1,central") takes in Central. */
    static boolean mirrorsCentral(String mirrorOf) {
        if (mirrorOf == null) return false;
        boolean taken = false;
        for (String entry : mirrorOf.split(",")) {
            String id = entry.trim();
            if (id.equals("!central")) return false;
            if (id.equals("*") || id.equals("central") || id.equals("external:*") || id.equals("external:https:*")) {
                taken = true;
            }
        }
        return taken;
    }

    private static String login(Document document, String id, Map<String, String> env) {
        if (id == null) return null;
        for (Element server : children(document.getDocumentElement(), "servers", "server")) {
            if (!id.equals(text(server, "id", env))) continue;
            String user = text(server, "username", env);
            String password = text(server, "password", env);
            if (user == null || password == null || (password.startsWith("{") && password.endsWith("}"))) return null;
            return AgentDownload.basic(user + ":" + password);
        }
        return null;
    }

    private static List<Element> children(Element root, String list, String item) {
        List<Element> found = new ArrayList<>();
        for (Element group : direct(root, list)) found.addAll(direct(group, item));
        return found;
    }

    private static List<Element> direct(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                found.add(element);
            }
        }
        return found;
    }

    /** An element's text, with ${env.NAME} filled in from the environment, as Maven does; null when empty. */
    private static String text(Element parent, String name, Map<String, String> env) {
        List<Element> found = direct(parent, name);
        if (found.isEmpty()) return null;
        Matcher matcher = ENV.matcher(found.get(0).getTextContent().trim());
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(filled, Matcher.quoteReplacement(env.getOrDefault(matcher.group(1), "")));
        }
        matcher.appendTail(filled);
        return filled.isEmpty() ? null : filled.toString();
    }
}
