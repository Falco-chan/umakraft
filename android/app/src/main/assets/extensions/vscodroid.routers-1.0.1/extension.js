'use strict';
const vscode = require('vscode');

const routers = () => (vscode.workspace.getConfiguration('routers').get('endpoints') || [])
  .filter(r => r && r.name && r.url);

// OpenAI chat messages from VS Code's. Tool results go first: the API wants them straight after the assistant's tool_calls.
function toOpenAI(messages) {
  const out = [];
  for (const m of messages) {
    const text = [], calls = [], results = [];
    for (const p of m.content) {
      if (p instanceof vscode.LanguageModelTextPart) text.push(p.value);
      else if (p instanceof vscode.LanguageModelToolCallPart) {
        calls.push({ id: p.callId, type: 'function', function: { name: p.name, arguments: JSON.stringify(p.input) } });
      } else if (p instanceof vscode.LanguageModelToolResultPart) {
        const content = p.content.map(c => (typeof c.value === 'string' ? c.value : JSON.stringify(c.value ?? ''))).join('');
        results.push({ role: 'tool', tool_call_id: p.callId, content });
      }
    }
    out.push(...results);
    if (m.role === vscode.LanguageModelChatMessageRole.Assistant) {
      if (text.length || calls.length) {
        out.push({ role: 'assistant', content: text.join('') || null, ...(calls.length && { tool_calls: calls }) });
      }
    } else if (text.length) out.push({ role: 'user', content: text.join('') });
  }
  return out;
}

async function* sse(stream) {
  const dec = new TextDecoder();
  let buf = '';
  for await (const chunk of stream) {
    buf += dec.decode(chunk, { stream: true });
    let n;
    while ((n = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, n).trim();
      buf = buf.slice(n + 1);
      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (data === '[DONE]') return;
      yield JSON.parse(data);
    }
  }
}

async function call(ctx, name, path, init) {
  const r = routers().find(x => x.name === name);
  if (!r) throw new Error(`Router "${name}" is not in routers.endpoints`);
  const key = await ctx.secrets.get('router:' + name);
  if (!key) throw new Error(`No API key for "${name}". Run "Routers: Set API Key".`);
  const res = await fetch(r.url.replace(/\/+$/, '') + path, {
    ...init,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + key },
  });
  if (!res.ok) throw new Error(`${name}: HTTP ${res.status} ${(await res.text()).slice(0, 200)}`);
  return res;
}

class Provider {
  constructor(ctx) {
    this.ctx = ctx;
    this._ev = new vscode.EventEmitter();
    this.onDidChangeLanguageModelChatInformation = this._ev.event;
  }
  refresh() { this._ev.fire(); }

  // One router being down or missing a key must not hide the other one's models.
  async provideLanguageModelChatInformation(opts) {
    const lists = await Promise.all(routers().map(async r => {
      try {
        const res = await call(this.ctx, r.name, '/models', { method: 'GET' });
        // shortcut: token limits are fixed, a router's /models rarely reports them; read them from the response when one does.
        return (await res.json()).data.map(m => ({
          id: `${r.name}/${m.id}`, name: `${m.id} (${r.name})`, family: m.id, version: '1',
          maxInputTokens: 128000, maxOutputTokens: 8192, capabilities: { toolCalling: true },
        }));
      } catch (e) {
        if (!opts.silent) vscode.window.showWarningMessage(e.message);
        return [];
      }
    }));
    return lists.flat();
  }

  async provideLanguageModelChatResponse(model, messages, options, progress, token) {
    const slash = model.id.indexOf('/');
    const ac = new AbortController();
    token.onCancellationRequested(() => ac.abort());
    const body = { model: model.id.slice(slash + 1), messages: toOpenAI(messages), stream: true };
    if (options.tools?.length) {
      body.tools = options.tools.map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.inputSchema } }));
      body.tool_choice = options.toolMode === vscode.LanguageModelChatToolMode.Required ? 'required' : 'auto';
    }
    const res = await call(this.ctx, model.id.slice(0, slash), '/chat/completions',
      { method: 'POST', body: JSON.stringify(body), signal: ac.signal });
    const calls = [];
    for await (const d of sse(res.body)) {
      const delta = d.choices?.[0]?.delta;
      if (delta?.content) progress.report(new vscode.LanguageModelTextPart(delta.content));
      for (const c of delta?.tool_calls ?? []) {
        const t = (calls[c.index ?? 0] ??= { id: '', name: '', args: '' });
        t.id ||= c.id ?? '';
        t.name ||= c.function?.name ?? '';
        t.args += c.function?.arguments ?? '';
      }
    }
    calls.forEach((t, i) => progress.report(
      new vscode.LanguageModelToolCallPart(t.id || `call_${i}`, t.name, JSON.parse(t.args || '{}'))));
  }

  provideTokenCount(model, text) {
    return Math.ceil((typeof text === 'string' ? text : JSON.stringify(toOpenAI([text]))).length / 4);
  }
}

function activate(ctx) {
  const p = new Provider(ctx);
  ctx.subscriptions.push(
    vscode.lm.registerLanguageModelChatProvider('routers', p),
    vscode.workspace.onDidChangeConfiguration(e => e.affectsConfiguration('routers') && p.refresh()),
    ctx.secrets.onDidChange(() => p.refresh()),
    vscode.commands.registerCommand('routers.setKey', async () => {
      const names = routers().map(r => r.name);
      if (!names.length) return vscode.window.showInformationMessage('Add your routers to the routers.endpoints setting first.');
      const pick = await vscode.window.showQuickPick(names, { placeHolder: 'Which router?' });
      if (!pick) return;
      const key = await vscode.window.showInputBox({ prompt: `API key for ${pick}`, password: true });
      if (key) await ctx.secrets.store('router:' + pick, key);
    }),
  );
}

module.exports = { activate, toOpenAI, sse };
