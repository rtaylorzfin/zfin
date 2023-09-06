package org.zfin.framework.presentation;

import lombok.Getter;
import lombok.Setter;

import java.util.*;

@Getter
@Setter
public class Hyperlink {

    private final String text;
    private final Map<String, String> attributes;

    public Hyperlink(String href, String text) {
        this.text = text;
        this.attributes = new HashMap<>();
        setAttribute("href", href);
    }

    public void setAttribute(String key, String value) {
        attributes.put(key, value);
    }

    public String getAttribute(String key) {
        return attributes.get(key);
    }

    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    public String removeAttribute(String key) {
        return attributes.remove(key);
    }

    public String toHtml() {
        setExternalAutomatically();

        StringBuilder html = new StringBuilder("<a href=\"" + href() + "\"");

        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            html.append(" ").append(String.format("%s=\"%s\"", entry.getKey(), entry.getValue()));
        }

        html.append(">").append(text).append("</a>");

        return html.toString();
    }

    private void setExternalAutomatically() {
        String href = href();
        if (href == null) return;

        if (href.startsWith("http") && !href.contains("zfin.org")) {
            setExternal(true);
        } else {
            setExternal(false);
        }
    }

    public String href() {
        return getAttribute("href");
    }

    public void setExternal(boolean external) {
        if (external) {
            setAttribute("target", "_blank");
            setAttribute("rel", "noopener noreferrer");
            addClass("external");
        } else {
            removeAttribute("target");
            removeAttribute("rel");
            removeClass("external");
        }
    }

    // Methods to handle the class attribute
    public boolean hasClass(String className) {
        String classAttr = attributes.get("class");
        if (classAttr == null) return false;

        Set<String> classes = new HashSet<>(Arrays.asList(classAttr.split("\\s+")));
        return classes.contains(className);
    }

    public void setClass(String className) {
        attributes.put("class", className);
    }

    public void addClass(String className) {
        String classAttr = attributes.get("class");
        Set<String> classes = (classAttr == null)
                ? new HashSet<>()
                : new HashSet<>(Arrays.asList(classAttr.split("\\s+")));

        classes.add(className);
        attributes.put("class", String.join(" ", classes));
    }

    public void removeClass(String className) {
        String classAttr = attributes.get("class");
        if (classAttr == null) return;

        Set<String> classes = new HashSet<>(Arrays.asList(classAttr.split("\\s+")));
        classes.remove(className);
        if (classes.isEmpty()) {
            attributes.remove("class");
        } else {
            attributes.put("class", String.join(" ", classes));
        }
    }

}
