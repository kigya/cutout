const { test } = require('node:test');
const assert = require('node:assert/strict');
const { reviewAudit } = require('./audit-dependencies.cjs');
const policy = require('../security/dependency-reviews.json');
const url = 'https://github.com/advisories/GHSA-vfj7-8cjw-p6xm';
const now = new Date('2026-10-17');
function fixture() { return {
 report: { auditReportVersion: 2, vulnerabilities: { braces: { nodes: ['node_modules/braces'], via: [{ url, severity: 'high' }] }, tailwindcss: { via: ['braces'] } } },
 lock: { packages: { 'node_modules/braces': { version: '3.0.3' } } },
 maps: { android: { version: 3, sources: ['index.js'] }, ios: { version: 3, sources: ['index.js'] } },
}; }
function run(f, p = policy, date = now) { return reviewAudit(f.report, f.lock, f.maps, p, date); }
test('reports exact reviewed transitive tooling advisory without calling it clean', () => assert.deepEqual(run(fixture()), [url]));
test('unknown advisories, changed severity and versions fail closed', () => {
 for (const change of [f => f.report.vulnerabilities.braces.via[0].url = 'new', f => f.report.vulnerabilities.braces.via[0].severity = 'critical', f => f.lock.packages['node_modules/braces'].version = '3.0.2']) {
  const f = fixture(); change(f); assert.throws(() => run(f));
 }
});
test('both runtime bundles are required and affected tooling cannot ship', () => {
 for (const change of [f => delete f.maps.ios, f => f.maps.android.sources = [], f => f.maps.ios.sources.push('node_modules/braces/index.js')]) {
  const f = fixture(); change(f); assert.throws(() => run(f));
 }
});
test('invalid reports, incomplete graph, missing versions and expired reviews fail closed', () => {
 for (const change of [f => f.report.error = {}, f => f.report.auditReportVersion = 3, f => delete f.report.vulnerabilities.braces, f => delete f.lock.packages['node_modules/braces'], f => f.report.vulnerabilities.braces.nodes = []]) {
  const f = fixture(); change(f); assert.throws(() => run(f));
 }
 assert.throws(() => run(fixture(), policy, new Date('2026-11-02')));
});
test('a reviewed test formatter cannot become production', () => {
 const f = fixture(); const formatter = 'https://github.com/advisories/GHSA-hp3w-g68c-fv3c';
 f.report.vulnerabilities['sprintf-js'] = {nodes: ['node_modules/sprintf-js'], via: [{url: formatter, severity: 'moderate'}]};
 f.lock.packages['node_modules/sprintf-js'] = {version: '1.0.3', dev: true};
 assert.equal(run(f).length, 2); delete f.lock.packages['node_modules/sprintf-js'].dev; assert.throws(() => run(f));
});

test('Metro indexed maps inspect every section and sourceRoot and reject external sections', () => {
 const f = fixture();
 f.maps.android = {version: 3, sections: [{map: f.maps.android}, {map: {version: 3, sources: ['src/App.tsx']}}]};
 assert.deepEqual(run(f), [url]);
 f.maps.android.sections[1].map = {version: 3, sourceRoot: 'node_modules/braces', sources: ['index.js']};
 assert.throws(() => run(f));
 f.maps.android.sections[1] = {url: 'unread.map'};
 assert.throws(() => run(f));
 f.maps.android = {version: 3, sections: []};
 assert.throws(() => run(f));
});

test('cyclic npm metavulnerability graphs must still resolve to reviewed advisories', () => {
 const f = fixture();
 f.report.vulnerabilities.tailwindcss.via.push('metro-config');
 f.report.vulnerabilities['metro-config'] = {via: ['tailwindcss']};
 assert.deepEqual(run(f), [url]);
 f.report.vulnerabilities.tailwindcss.via = ['metro-config'];
 assert.throws(() => run(f), /Missing advisory provenance/);
});

test('parent traversal and Windows separators cannot hide a runtime package', () => {
 for (const source of ['node_modules/x/../braces/index.js', 'node_modules\\braces\\index.js']) {
  const f = fixture(); f.maps.android.sources.push(source); assert.throws(() => run(f));
 }
});

test('unknown policy disposition cannot silently inherit a weaker boundary', () => {
 const changed = structuredClone(policy);
 changed.advisories[url].disposition = 'test-toolonly';
 assert.throws(() => run(fixture(), changed), /Unknown dependency risk disposition/);
});
