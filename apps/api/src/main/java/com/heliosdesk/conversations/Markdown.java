package com.heliosdesk.conversations;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

public final class Markdown {
  private static final Parser PARSER = Parser.builder().build();
  private static final HtmlRenderer RENDERER =
      HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build();
  private static final PolicyFactory POLICY =
      new HtmlPolicyBuilder()
          .allowElements(
              "p",
              "br",
              "em",
              "strong",
              "code",
              "pre",
              "blockquote",
              "ul",
              "ol",
              "li",
              "hr",
              "h1",
              "h2",
              "h3",
              "h4",
              "h5",
              "h6",
              "a")
          .allowAttributes("href")
          .matching((element, attribute, value) -> value.isBlank() ? null : value)
          .onElements("a")
          .allowAttributes("title")
          .onElements("a")
          .allowUrlProtocols("https", "http", "mailto")
          .requireRelNofollowOnLinks()
          .toFactory();

  private Markdown() {}

  public static String render(String source) {
    return POLICY.sanitize(RENDERER.render(PARSER.parse(source)));
  }
}
