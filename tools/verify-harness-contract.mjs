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
for (const name of ['dsh-api-session-controller', 'dsh-api-workspace-controller', 'dsh-agent-presets', 'dsh-commands']) {
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
const { handleFileUploadHttp } = await import(pathToFileURL(join(root, 'dsh-client-file-upload/lib/types/http-route.js')));
const response = await handleFileUploadHttp({uploadStream: async request => {
  assert.equal(request.sessionId, 'android-test');
  assert.equal(request.name, 'test & report.pdf');
  const bytes = [];
  for await (const chunk of request.data) bytes.push(...chunk);
  assert.deepEqual(bytes, [1, 2, 3]);
  return {receiptId: 'receipt-1', file: {attachmentId: 'test-file', name: request.name, bytes: bytes.length}};
}}, new Request('http://example.test/api/session/uploadFileBinary?sessionId=android-test&name=test%20%26%20report.pdf', {
  method: 'POST', headers: {'content-type': 'application/octet-stream'}, body: new Uint8Array([1, 2, 3]),
}));
assert.equal((await response.json()).value.receiptId, 'receipt-1');
console.log('PASS actual raw file-upload handler');
console.log(`Verified ${requests.length} requests, mux frames and event replies against harness ${metadata.version}.`);
