// Allow Gradle Tooling API callers to select Node without depending on a shell PATH.
const fs = require('node:fs');
const path = require('node:path');
for (const library of ['react-native-reanimated', 'react-native-worklets']) {
  const target = path.join(__dirname, '..', 'node_modules', library, 'android', 'build.gradle.kts');
  const source = fs.readFileSync(target, 'utf8');
  const marker = 'commandLine(providers.gradleProperty("cutout.nodeBinary").orElse("node").get(),';
  if (source.includes(marker)) continue;
  if (!source.includes('commandLine("node",')) throw new Error(`Review the Node resolution patch for ${library}`);
  fs.writeFileSync(target, source.replaceAll('commandLine("node",', marker));
}
