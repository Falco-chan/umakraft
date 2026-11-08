'use strict';
// Self-check for the routers extension: message conversion and SSE parsing, with `vscode` stubbed.
const Module = require('module');
const assert = require('assert');
const path = require('path');

class Text { constructor(value) { this.value = value; } }
class Call { constructor(callId, name, input) { Object.assign(this, { callId, name, input }); } }
class Result { constructor(callId, content) { Object.assign(this, { callId, content }); } }
const vscode = {
  LanguageModelTextPart: Text, LanguageModelToolCallPart: Call, LanguageModelToolResultPart: Result,
  LanguageModelChatMessageRole: { User: 1, Assistant: 2 },
};
const load = Module._load;
Module._load = (r, ...a) => (r === 'vscode' ? vscode : load(r, ...a));
const { toOpenAI, sse } = require(path.join(__dirname,
  '../android/app/src/main/assets/extensions/vscodroid.routers-1.0.0/extension.js'));

assert.deepStrictEqual(toOpenAI([
  { role: 1, content: [new Text('hi')] },
  { role: 2, content: [new Call('c1', 'ls', { a: 1 })] },
  { role: 1, content: [new Result('c1', [new Text('ok')])] },
]), [
  { role: 'user', content: 'hi' },
  { role: 'assistant', content: null, tool_calls: [{ id: 'c1', type: 'function', function: { name: 'ls', arguments: '{"a":1}' } }] },
  { role: 'tool', tool_call_id: 'c1', content: 'ok' },
]);

(async () => {
  // A line split across two chunks must still parse; [DONE] ends the stream.
  const chunks = [Buffer.from('data: {"a":'), Buffer.from('1}\n\ndata: {"b":2}\ndata: [DONE]\ndata: {"c":3}\n')];
  const got = [];
  for await (const d of sse(chunks)) got.push(d);
  assert.deepStrictEqual(got, [{ a: 1 }, { b: 2 }]);
  console.log('test-routers: ok');
})();
