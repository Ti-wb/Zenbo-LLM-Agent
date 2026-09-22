import { createRenderer, h, nextTick, reactive } from 'vue';
import * as Vue from 'vue';
import { readFileSync } from 'node:fs';
import { compileScript, parse } from '@vue/compiler-sfc';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as qrHelpers from '../services/lanControlQr';

// Compile the actual client template for Vue's synthetic host; Vitest's Node
// environment otherwise supplies an SSR-only component, without event handlers.
const { descriptor } = parse(readFileSync(new URL('./LanControlQr.vue', import.meta.url), 'utf8'));
const clientComponent = compileScript(descriptor, { id: 'lan-qr-test', inlineTemplate: true }).content
  .replace(/import \{([^}]+)\} from ["']vue["'];?/g, (_, names) => `const {${names.replace(/\bas\b/g, ':')}} = Vue;`)
  .replace(/import \{([^}]+)\} from ["']\.\.\/services\/lanControlQr["'];?/, 'const {$1} = qrHelpers;')
  .replace('export default', 'return');
const LanControlQr = new Function('Vue', 'qrHelpers', clientComponent)(Vue, qrHelpers);
const { createLanControlQr } = qrHelpers;

const apps = [];
afterEach(() => apps.splice(0).forEach((app) => app.unmount()));

function mount(input = {}) {
  const props = reactive({ active: true, enabled: true, urls: ['http://192.168.1.23:8788/'], ...input });
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null });
  const root = node('root');
  const renderer = createRenderer({
    createElement: node, createText: (text) => node('#text', text), createComment: (text) => node('#comment', text),
    insert(child, parent, anchor) {
      if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1);
      child.parent = parent;
      const index = anchor ? parent.children.indexOf(anchor) : -1;
      if (index < 0) parent.children.push(child); else parent.children.splice(index, 0, child);
    },
    remove(child) { child.parent?.children.splice(child.parent.children.indexOf(child), 1); child.parent = null; },
    setElementText(element, text) { element.text = text; element.children = []; },
    setText(element, text) { element.text = text; },
    patchProp(element, key, _, value) { element.props[key] = value; },
    setScopeId() {},
    parentNode: (element) => element.parent,
    nextSibling: (element) => element.parent?.children[element.parent.children.indexOf(element) + 1] || null,
  });
  const stop = vi.fn();
  const app = renderer.createApp({ render: () => h(LanControlQr, { ...props, onStop: stop }) });
  app.mount(root); apps.push(app);
  function all(element = root) { return [element, ...element.children.flatMap((child) => all(child))]; }
  const get = (tag) => all().find((element) => element.tag === tag);
  const button = (text) => all().find((element) => element.tag === 'button' && element.text === text);
  async function show() { button('顯示 QR code').props.onClick(); await nextTick(); }
  return { props, get, button, show, stop };
}

describe('LAN QR display lifecycle', () => {
  it('keeps QR visible across identical status polls, clears it on address or authority changes, and preserves stop', async () => {
    const page = mount();
    await page.show();
    const originalPath = page.get('path').props.d;
    page.props.urls = [...page.props.urls];
    await nextTick();
    expect(page.get('path').props.d).toBe(originalPath);
    expect(page.button('收起 QR code')).toBeDefined();
    page.button('■ 停止移動').props.onClick();
    expect(page.stop).toHaveBeenCalledOnce();

    page.props.urls = ['http://10.0.0.2:8788/'];
    await nextTick();
    expect(page.get('svg')).toBeUndefined();
    await page.show();
    expect(page.get('path').props.d).toBe(createLanControlQr(page.props.urls[0]).path);
    expect(page.get('path').props.d).not.toBe(originalPath);
    for (const field of ['enabled', 'active']) {
      page.props[field] = false; await nextTick();
      expect(page.get('svg')).toBeUndefined();
      page.props[field] = true; await nextTick();
      expect(page.get('svg')).toBeUndefined();
      await page.show();
    }
    page.button('收起 QR code').props.onClick(); await nextTick();
    expect(page.get('svg')).toBeUndefined();
  });

  it('does not offer a code without a valid LAN address', async () => {
    const page = mount({ urls: ['http://192.168.1.23:8788/?token=secret'] });
    expect(page.button('顯示 QR code').props.disabled).toBe(true);
    await page.show();
    expect(page.get('svg')).toBeUndefined();
  });
});
