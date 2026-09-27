const fs = require('fs');
const path = require('path');

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

const root = path.resolve(__dirname, '..');
const webMvcConfigPath = path.join(root, 'main', 'java', 'com', 'example', 'lms', 'config', 'WebMvcConfig.java');
const chatUiViewConfigPath = path.join(root, 'main', 'java', 'com', 'example', 'lms', 'config', 'ChatUiViewConfig.java');
const staleWebMvcViewTestPath = path.join(root, 'src', 'test', 'java', 'com', 'example', 'lms', 'config', 'WebMvcConfigChatUiViewTest.java');
const chatUiViewTestPath = path.join(root, 'src', 'test', 'java', 'com', 'example', 'lms', 'config', 'ChatUiViewConfigTest.java');
const focusedChatUiTestPath = path.join(root, 'src', 'chatUiTest', 'java', 'com', 'example', 'lms', 'config', 'ChatUiViewConfigFocusedTest.java');
const gradleBuildPath = path.join(root, 'build.gradle.kts');

assert(fs.existsSync(webMvcConfigPath), 'WebMvcConfig.java is missing');
assert(fs.existsSync(chatUiViewConfigPath), 'ChatUiViewConfig.java must own chat-ui rendering');
assert(fs.existsSync(gradleBuildPath), 'build.gradle.kts is missing');

const webMvcConfig = fs.readFileSync(webMvcConfigPath, 'utf8');
const chatUiViewConfig = fs.readFileSync(chatUiViewConfigPath, 'utf8');
const gradleBuild = fs.readFileSync(gradleBuildPath, 'utf8');

assert(
  !webMvcConfig.includes('chatUiResourceViewResolver') &&
    !webMvcConfig.includes('ClasspathHtmlView') &&
    !webMvcConfig.includes('templates/chat-ui.html'),
  'WebMvcConfig should not own chat-ui view rendering internals'
);

assert(
  chatUiViewConfig.includes('chatUiResourceViewResolver') &&
    chatUiViewConfig.includes('templates/chat-ui.html') &&
    chatUiViewConfig.includes('renderCsrfMeta') &&
    chatUiViewConfig.includes('renderModelSelect'),
  'ChatUiViewConfig should render chat-ui model and CSRF placeholders'
);

assert(!fs.existsSync(staleWebMvcViewTestPath), 'stale WebMvcConfigChatUiViewTest.java should be renamed');
assert(fs.existsSync(chatUiViewTestPath), 'ChatUiViewConfigTest.java should cover chat-ui rendering ownership');
assert(
  fs.readFileSync(chatUiViewTestPath, 'utf8').includes('class ChatUiViewConfigTest'),
  'ChatUiViewConfigTest.java should declare ChatUiViewConfigTest'
);
assert(
  gradleBuild.includes('src/chatUiTest/java') &&
    gradleBuild.includes('tasks.register<Test>("chatUiTest")'),
  'build.gradle.kts should expose a focused chatUiTest task that avoids stale broad test compilation'
);
assert(fs.existsSync(focusedChatUiTestPath), 'ChatUiViewConfigFocusedTest.java should back the focused chatUiTest sourceSet');
assert(
  fs.readFileSync(focusedChatUiTestPath, 'utf8').includes('class ChatUiViewConfigFocusedTest'),
  'ChatUiViewConfigFocusedTest.java should declare ChatUiViewConfigFocusedTest'
);

console.log('[AWX][chat-ui][view-layer] contract OK');
