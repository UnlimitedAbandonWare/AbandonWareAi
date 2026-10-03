package com.example.lms.web;
import org.junit.jupiter.api.Test;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class SettingsRoutingPageTest{
 @Test void pageKeepsRoutingReadonlyExecutionAndKeyboardLabels() throws Exception{
  String html=Files.readString(Path.of("main/resources/templates/settings.html"));String js=Files.readString(Path.of("main/resources/static/js/settings-routing.js"));String css=Files.readString(Path.of("main/resources/static/css/settings-page.css"));
  assertTrue(html.contains("/js/settings-routing.js"));assertTrue(html.contains("읽기 전용"));assertTrue(js.contains("textContent"));assertFalse(js.contains("innerHTML"));
  assertTrue(js.contains("label"));assertTrue(css.contains("@media"));assertFalse(Files.readString(Path.of("main/resources/templates/chat-ui.html")).contains("settings-routing.js"));
 }
}
