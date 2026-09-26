// Optional compatibility check against the actual installed harness descriptors.
// Usage: node tools/verify-harness-contract.mjs /path/to/node_modules
import { pathToFileURL } from 'node:url';
import { readFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import assert from 'node:assert/strict';

const modules = process.argv[2];
if (!modules) throw new Error('Pass the harness node_modules directory as the first argument.');
const root = resolve(modules, '@deepseek-ai');
const descriptors = [];
for (const name of ['dsh-api-session-controller', 'dsh-api-workspace-controller', 'dsh-agent-presets']) {
  const { TYPERT_REMOTE } = await import(pathToFileURL(join(root, name, 'lib/typert.remote-client.js')));
  descriptors.push(...TYPERT_REMOTE.descriptors);
}
const requests = JSON.parse(await readFile(new URL('../fixtures/requests.json', import.meta.url), 'utf8'));
for (const { endpoint, args } of requests) {
  const descriptor = descriptors.find(d => `${d.namespace}/${d.method}` === endpoint);
  assert.ok(descriptor, `Endpoint exists: ${endpoint}`);
  const expected = descriptor.parameters.map(p => p.wire);
  assert.ok(Object.keys(args).every(key => expected.includes(key)), `Named args match: ${endpoint}`);
  for (const parameter of descriptor.parameters) {
    parameter.codec.create().parse(args[parameter.wire]);
  }
  console.log(`PASS ${endpoint}`);
}
const protocol = await import(pathToFileURL(join(root, 'dsh-api-gateway/lib/types/stream-protocol.js')));
protocol.parseRemoteStreamClientMessage(JSON.stringify({type: 'open', streamId: 'events', endpoint: '$events', payload: {args: {}}}));
protocol.parseRemoteStreamClientMessage(JSON.stringify({type: 'cancel', streamId: 'chat'}));
for (const value of ['allowed-once', 'rejected', {answers: [{id: 'question-1', selected: ['继续'], custom: '保留现有文件'}]}]) {
  protocol.parseRemoteEventResult({clientId: 'client-1', eventId: 'event-1', outcome: {kind: 'result', value}});
}
const metadata = JSON.parse(await readFile(join(root, 'dsh-api-session-controller/package.json'), 'utf8'));
console.log(`Verified ${requests.length} requests, mux frames and event replies against harness ${metadata.version}.`);
