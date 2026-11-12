package com.heliosdesk.conversations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MarkdownTest {
  @Test
  void preservesFormattingAndSafeLinks() {
    assertThat(
            Markdown.render("**Clear** _reply_ with `code` and [docs](https://example.test/help)"))
        .contains(
            "<strong>Clear</strong>",
            "<em>reply</em>",
            "<code>code</code>",
            "href=\"https://example.test/help\"",
            "rel=\"nofollow\"");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[click](javascript:alert%281%29)",
        "[click](data:text/html,attack)",
        "[click](vbscript:attack)",
        "[click](java&#x73;cript:attack)"
      })
  void disallowsScriptableLinks(String source) {
    assertThat(Markdown.render(source)).doesNotContain("href=", "<script", "<iframe");
  }

  @Test
  void escapesRawHtmlAndDropsRemoteImages() {
    String rendered =
        Markdown.render(
            "<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n![track](https://tracker.test/image.png)");
    assertThat(rendered).doesNotContain("<script", "<img", "<svg").contains("&lt;script&gt;");
  }
}
