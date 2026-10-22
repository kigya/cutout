// Keep the complete npm report visible. Permit only reviewed, time-limited tooling
// findings after checking the exact installed versions and both runtime bundles.
const fs = require('node:fs');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
// Metro emits ECMA-426 index maps; inspect every embedded section.
function mapSources(map) {
  if (!map || map.version !== 3) throw new Error('Invalid runtime source map');
  if (Array.isArray(map.sections)) {
    if (!map.sections.length || map.sources) throw new Error('Invalid index source map');
    return map.sections.flatMap(section => {
      if (!section.map || section.url) throw new Error('External source-map sections are not accepted');
      return mapSources(section.map);
    });
  }
  if (!Array.isArray(map.sources) || map.sources.some(s => typeof s !== 'string')) throw new Error('Missing runtime sources');
  return map.sources.map(source => {
    const combined = `${map.sourceRoot || ''}/${source}`.replaceAll('\\', '/');
    if (combined.split('/').includes('..')) throw new Error('Parent traversal in runtime source map');
    return path.posix.normalize(combined);
  });
}
function reviewAudit(report, lock, maps, policy, now = new Date()) {
  if (report.error || report.auditReportVersion !== 2 || !report.vulnerabilities) throw new Error('Invalid npm audit report');
  if (now.toISOString().slice(0, 10) > policy.expiresOn) throw new Error('Dependency reviews expired');
  for (const platform of ['android', 'ios']) {
    const sources = mapSources(maps[platform]);
    if (!sources.some(s => /(?:^|\/)index\.js$/.test(s))) throw new Error(`Missing ${platform} runtime entry point`);
    for (const source of sources) {
      if (/(?:^|\/)node_modules\/(?:braces|sprintf-js|postcss-selector-parser)\//.test(source.replaceAll('\\', '/'))) throw new Error(`Affected tooling found in ${platform} runtime bundle`);
    }
  }
  for (const accepted of Object.values(policy.advisories)) {
    if (!['build-input-only', 'test-tool-only'].includes(accepted.disposition)) throw new Error('Unknown dependency risk disposition');
  }
  const reviewed = new Set();
  function check(root) {
    const seen = new Set();
    const pending = [root];
    let advisoryFound = false;
    while (pending.length) {
      const name = pending.pop();
      if (seen.has(name)) continue;
      seen.add(name);
      const finding = report.vulnerabilities[name];
      if (!finding || !Array.isArray(finding.via) || !finding.via.length) throw new Error(`Unclassified dependency: ${name}`);
      for (const via of finding.via) {
        if (typeof via === 'string') { pending.push(via); continue; }
        const accepted = policy.advisories[via.url];
        if (!accepted || accepted.package !== name || accepted.severity !== via.severity) throw new Error(`Unreviewed advisory: ${via.url}`);
        if (!Array.isArray(finding.nodes) || !finding.nodes.length) throw new Error(`Missing installed paths: ${name}`);
        for (const path of finding.nodes) {
          const installed = lock.packages[path];
          if (!installed || !accepted.versions.includes(installed.version)) throw new Error(`Unreviewed version: ${path}`);
          if (accepted.disposition === 'test-tool-only' && !installed.dev) throw new Error(`Test dependency became production: ${path}`);
        }
        reviewed.add(via.url);
        advisoryFound = true;
      }
    }
    if (!advisoryFound) throw new Error(`Missing advisory provenance: ${root}`);
  }
  Object.keys(report.vulnerabilities).forEach(name => check(name));
  return [...reviewed];
}
function inputFingerprint() {
  const hash = crypto.createHash('sha256');
  function visit(file) {
    if (fs.statSync(file).isDirectory()) fs.readdirSync(file).sort().forEach(child => visit(path.join(file, child)));
    else { hash.update(file); hash.update(fs.readFileSync(file)); }
  }
  ['src', 'index.js', 'package.json', 'package-lock.json', 'metro.config.js', 'tailwind.config.js', 'babel.config.js', 'global.css'].forEach(visit);
  return hash.digest('hex');
}
function buildRuntimeMaps() {
  const before = inputFingerprint();
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'cutout-audit-'));
  const cli = path.join(path.dirname(require.resolve('react-native/package.json')), 'cli.js');
  const maps = {};
  for (const platform of ['android', 'ios']) {
    const map = path.join(directory, `${platform}.map`);
    const result = spawnSync(process.execPath, [cli, 'bundle', '--entry-file', 'index.js', '--platform', platform, '--dev', 'false', '--max-workers', '2', '--bundle-output', path.join(directory, `${platform}.js`), '--sourcemap-output', map], { stdio: 'inherit', env: { ...process.env, PATH: `${path.dirname(process.execPath)}${path.delimiter}${process.env.PATH}` } });
    if (result.error || result.status !== 0) throw result.error || new Error(`${platform} bundle generation failed`);
    maps[platform] = JSON.parse(fs.readFileSync(map));
  }
  if (inputFingerprint() !== before) throw new Error('Build inputs changed while generating runtime evidence');
  fs.writeFileSync(path.join(directory, 'provenance.json'), JSON.stringify({ inputFingerprint: before, generatedAt: new Date().toISOString(), lockSha256: crypto.createHash('sha256').update(fs.readFileSync('package-lock.json')).digest('hex') }, null, 2));
  console.log(`Fresh runtime evidence: ${directory}`);
  return maps;
}
if (require.main === module) {
  try {
    const auditedFingerprint = inputFingerprint();
    const audit = spawnSync('npm', ['audit', '--json'], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
    if (audit.error || ![0, 1].includes(audit.status)) throw audit.error || new Error('npm audit failed to run');
    const report = JSON.parse(audit.stdout);
    console.log(JSON.stringify(report, null, 2));
    const maps = buildRuntimeMaps();
    if (inputFingerprint() !== auditedFingerprint) throw new Error('Build inputs changed during dependency audit');
    const reviewed = reviewAudit(report, JSON.parse(fs.readFileSync('package-lock.json')), maps, JSON.parse(fs.readFileSync('security/dependency-reviews.json')));
    console.log(reviewed.length ? `PASS with reviewed tooling risks (npm audit is not clean): ${reviewed.join(', ')}` : 'PASS: npm audit is clean');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { reviewAudit };
