const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const importsPath = path.join(
  root,
  'main',
  'resources',
  'META-INF',
  'spring',
  'org.springframework.boot.autoconfigure.AutoConfiguration.imports'
);

const requiredImports = [
  'ai.abandonware.nova.autoconfig.NovaDebugPortAutoConfiguration',
  'ai.abandonware.nova.autoconfig.NovaOrchestrationAutoConfiguration',
  'ai.abandonware.nova.autoconfig.NovaOpsStabilizationAutoConfiguration',
];

if (!fs.existsSync(importsPath)) {
  throw new Error(`[AWX][chat-ui][autoconfig] missing active imports file: ${path.relative(root, importsPath)}`);
}

const lines = fs
  .readFileSync(importsPath, 'utf8')
  .split(/\r?\n/)
  .map((line) => line.trim())
  .filter((line) => line && !line.startsWith('#'));

const missing = requiredImports.filter((entry) => !lines.includes(entry));
if (missing.length > 0) {
  throw new Error(`[AWX][chat-ui][autoconfig] missing required imports: ${missing.join(',')}`);
}

const duplicate = lines.find((entry, index) => lines.indexOf(entry) !== index);
if (duplicate) {
  throw new Error(`[AWX][chat-ui][autoconfig] duplicate import: ${duplicate}`);
}

console.log('[AWX][chat-ui][autoconfig] contract OK');
