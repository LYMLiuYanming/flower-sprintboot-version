/**
 * 立体展台装配层（J01–J08、J12）：把 flower3d.js 的渲染核心接成一个页面可直接嵌入的组件。
 *
 * 页面侧只需要一段带 data-* 的容器（见 templates/common/stage3d.html），本脚本负责：
 *   进入视口才解析映射并拉 GLB（J07）· 进度与失败重试（J05）· 品类→模型解析（J01）
 *   转速调节（J02）· 截图导出 PNG（J03）· 材质/素模/线框（J04）· 键盘操作（J08）
 *   模型与实拍在同一面板内用 Tab 切换（J12）
 * 移动端旋转与双指缩放由 flower3d.js 的 pointer 事件 + CSS touch-action 承担（J06）。
 *
 * 任何一步拿不到可用模型（没配映射 / WebGL 不可用 / 拉取失败）都落到 2D 实拍图集，
 * 这是可验证的真实行为，而不是一个转不完的加载动画。
 */
import { mount, supported, PRESETS, RENDER_MODES } from '/js/flower3d.js';

const PRESET_ORDER = ['full', 'head', 'leaf', 'top', 'vase'];
const RESOLVE_API = '/api/media/models/resolve';

/** 已挂载实例，页面卸载时统一释放显存 */
const instances = [];

function toast(message, type) {
    if (typeof window.huToast === 'function') {
        window.huToast(message, type);
    }
}

function activate() {
    document.querySelectorAll('[data-hu-stage]').forEach(root => {
        if (root.__huStage) return;
        root.__huStage = new Stage(root);
        instances.push(root.__huStage);
    });
}

class Stage {
    constructor(root) {
        this.root = root;
        this.api = null;
        this.started = false;
        this.disposed = false;
        this.attempt = 0;
        this.tab = root.dataset.tab === 'photo' ? 'photo' : 'model';
        this.cfg = {
            model: (root.dataset.model || '').trim(),
            category: root.dataset.category || '',
            keyword: root.dataset.keyword || '',
            preset: root.dataset.preset || 'full',
            resolve: root.dataset.resolve !== 'off'
        };
        this.el = {
            canvas: root.querySelector('[data-hu-canvas]'),
            overlay: root.querySelector('[data-hu-overlay]'),
            bar: root.querySelector('[data-hu-bar]'),
            status: root.querySelector('[data-hu-status]'),
            presets: root.querySelector('[data-hu-presets]'),
            photos: root.querySelector('[data-hu-photos]'),
            viewport: root.querySelector('[data-hu-viewport]'),
            spin: root.querySelector('[data-hu-spin]'),
            capture: root.querySelector('[data-hu-capture]'),
            reset: root.querySelector('[data-hu-reset]'),
            modes: root.querySelectorAll('[data-hu-mode]'),
            tabs: root.querySelectorAll('[data-hu-tab]'),
            license: root.querySelector('[data-hu-license]')
        };
        if (!this.el.canvas) return;

        this.buildPresets();
        this.bindTabs();
        this.bindTools();
        this.bindKeyboard();
        this.showTab(this.tab, true);
        this.observe();
    }

    /** J07 懒加载：滚动进视口才解析映射、才拉 1.8MB 的 GLB */
    observe() {
        if (!('IntersectionObserver' in window)) {
            this.start();
            return;
        }
        this.io = new IntersectionObserver(entries => {
            if (entries.some(entry => entry.isIntersecting)) {
                this.io.disconnect();
                this.io = null;
                this.start();
            }
        }, { rootMargin: '200px 0px' });
        this.io.observe(this.root);
    }

    start() {
        if (this.started || this.disposed) return;
        this.started = true;
        if (this.cfg.model) {
            this.mountModel(this.cfg.model, {});
            return;
        }
        if (!this.cfg.resolve) {
            this.fallback('本页未指定立体模型');
            return;
        }
        // J01：模型地址由映射表下发，页面不写死 GLB 路径
        this.status('正在匹配本品类的三维模型…');
        const params = new URLSearchParams();
        if (this.cfg.category) params.set('category', this.cfg.category);
        if (this.cfg.keyword) params.set('keyword', this.cfg.keyword);
        fetch(RESOLVE_API + '?' + params.toString(), { headers: { Accept: 'application/json' } })
            .then(res => res.ok ? res.json() : Promise.reject(new Error('HTTP ' + res.status)))
            .then(body => {
                const data = body && body.code === 200 ? body.data : null;
                if (data && data.modelUrl) {
                    this.describe(data);
                    this.mountModel(data.modelUrl, { preset: data.preset, spinSpeed: data.spinSpeed });
                } else {
                    this.fallback((data && data.reason) || '该品类暂未配置三维模型');
                }
            })
            .catch(err => this.fallback('模型映射接口不可用（' + (err.message || '网络异常') + '）'));
    }

    /** 把映射表里的说明如实写进面板：通用示意就写通用示意，不假装是本品建模 */
    describe(data) {
        if (data.label) {
            const title = this.root.querySelector('[data-hu-title]');
            if (title) title.textContent = data.label;
        }
        if (this.el.license && data.caption) {
            this.el.license.textContent = data.caption;
        }
    }

    mountModel(url, options) {
        if (!supported) {
            this.fallback('当前浏览器不支持 WebGL，已切到实拍图集');
            return;
        }
        this.attempt += 1;
        this.lastModel = url;
        this.lastOptions = options || {};
        this.status('准备载入立体模型…');
        this.el.overlay.hidden = false;
        this.el.overlay.dataset.state = 'loading';
        const opts = { preset: this.cfg.preset || 'full', capture: true };
        // 只覆盖接口真的给了值的字段：undefined 会把 flower3d 里的默认转速顶掉，导致不转
        if (this.lastOptions.preset) opts.preset = this.lastOptions.preset;
        if (this.lastOptions.spinSpeed != null && isFinite(this.lastOptions.spinSpeed)) {
            opts.spinSpeed = Number(this.lastOptions.spinSpeed);
        }
        this.listen();
        this.api = mount(this.el.canvas, opts);
        if (!this.api) {
            this.fallback('WebGL 上下文创建失败，已切到实拍图集');
        }
    }

    /** flower3d 用自定义事件回报进度与就绪，事件绑在容器上，重试后不需要重新接 */
    listen() {
        if (this.listening) return;
        this.listening = true;
        this.el.canvas.addEventListener('flower3d:progress', e => {
            const detail = e.detail || {};
            if (detail.pct) {
                this.setProgress(detail.pct);
                this.status('正在载入立体模型… ' + detail.pct + '%');
            } else if (detail.loaded) {
                // 服务端没给 Content-Length 时按已下载量给个不封顶的反馈，总比转圈不吭声好
                this.status('正在载入立体模型… 已下载 ' + Math.round(detail.loaded / 1024) + 'KB');
                this.setProgress(null);
            }
        });
        this.el.canvas.addEventListener('flower3d:ready', () => this.onReady());
        this.el.canvas.addEventListener('flower3d:change', () => this.syncState());
        this.el.canvas.addEventListener('flower3d:error', () => this.onLoadError());
    }

    onReady() {
        this.el.overlay.dataset.state = 'ready';
        this.el.overlay.hidden = true;
        this.setProgress(100);
        this.root.classList.add('is-ready');
        this.syncState();
    }

    /** J05 失败重试：保留面板，给出可点的重试按钮与失败原因 */
    onLoadError() {
        this.api = null;
        this.el.overlay.hidden = false;
        this.el.overlay.dataset.state = 'failed';
        this.status('模型没拉下来（第 ' + this.attempt + ' 次），网络或显存占用都可能导致；可重试或直接看实拍图集。');
        this.setRetry(true);
    }

    fallback(reason) {
        this.api = null;
        this.el.overlay.hidden = false;
        this.el.overlay.dataset.state = 'none';
        this.status(reason || '立体模型不可用，已切到实拍图集');
        this.setRetry(false);
        // 模型页仍留在 Tab 上：点回去能看到具体原因，而不是一个凭空消失的入口
        this.root.classList.add('is-2d-only');
        this.showTab('photo');
    }

    setRetry(show) {
        const retry = this.root.querySelector('[data-hu-retry]');
        if (retry) retry.hidden = !show;
        const photo = this.root.querySelector('[data-hu-fallback-cta]');
        if (photo) photo.hidden = !show;
    }

    setProgress(pct) {
        if (!this.el.bar) return;
        this.el.bar.style.width = (pct == null ? 12 : Math.max(6, pct)) + '%';
        this.el.bar.classList.toggle('is-indeterminate', pct == null);
    }

    status(text) {
        if (this.el.status) this.el.status.textContent = text;
    }

    buildPresets() {
        const host = this.el.presets;
        if (!host) return;
        host.innerHTML = '';
        PRESET_ORDER.forEach(code => {
            const preset = PRESETS[code];
            if (!preset) return;
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'hu-preset';
            button.dataset.preset = code;
            button.setAttribute('aria-pressed', 'false');
            button.innerHTML = '<i class="fa fa-circle-o"></i>' + preset.label;
            button.addEventListener('click', () => {
                if (!this.api) return;
                this.api.setPreset(code);
                this.showTab('model');
                this.syncState();
            });
            host.appendChild(button);
        });
    }

    /** J12：模型与实拍在同一面板内切换，不新开区块 */
    bindTabs() {
        (this.el.tabs || []).forEach(tab => {
            tab.addEventListener('click', () => this.showTab(tab.dataset.huTab));
        });
    }

    showTab(name, silent) {
        const target = name === 'photo' ? 'photo' : 'model';
        this.tab = target;
        (this.el.tabs || []).forEach(tab => {
            const active = tab.dataset.huTab === target;
            tab.classList.toggle('is-active', active);
            tab.setAttribute('aria-selected', String(active));
            tab.tabIndex = active ? 0 : -1;
        });
        if (this.el.viewport) this.el.viewport.hidden = target !== 'model';
        if (this.el.photos) this.el.photos.hidden = target !== 'photo';
        const side = this.root.querySelector('[data-hu-side]');
        if (side) side.hidden = target !== 'model';
        // 切回模型页时把键盘焦点交还画布，方向键才立刻可用
        if (target === 'model' && !silent && this.api) {
            this.el.canvas.focus({ preventScroll: true });
        }
    }

    bindTools() {
        const spin = this.el.spin;
        if (spin) {
            const apply = () => {
                if (!this.api) return;
                this.api.setSpinSpeed(Number(spin.value) / 100 * 1.2);
                this.syncState();
            };
            spin.addEventListener('input', apply);
            spin.addEventListener('change', apply);
        }
        (this.el.modes || []).forEach(button => {
            button.addEventListener('click', () => {
                if (!this.api) return;
                this.api.setRenderMode(button.dataset.huMode);
                this.syncState();
            });
        });
        if (this.el.capture) {
            this.el.capture.addEventListener('click', () => this.capture());
        }
        if (this.el.reset) {
            this.el.reset.addEventListener('click', () => {
                if (!this.api) return;
                this.api.reset();
                if (this.el.spin) this.el.spin.value = String(Math.round((this.api.state().spinSpeed || 0) / 1.2 * 100));
                this.syncState();
            });
        }
        const retry = this.root.querySelector('[data-hu-retry]');
        if (retry) {
            retry.addEventListener('click', () => {
                /* 重试要先把上一次的 WebGL 上下文释放干净，否则反复点会累积上下文的数量上限 */
                if (this.api) {
                    this.api.dispose();
                    this.api = null;
                }
                if (this.lastModel) {
                    this.mountModel(this.lastModel, this.lastOptions);
                } else {
                    this.start();
                }
            });
        }
        const cta = this.root.querySelector('[data-hu-fallback-cta]');
        if (cta) cta.addEventListener('click', () => this.showTab('photo'));
    }

    /** J03 截图：GLB 画布是透明的，先铺一层影棚底色再合成，分享出去才不会是个黑块 */
    capture() {
        if (!this.api) return;
        const shot = this.api.capture();
        if (!shot || !shot.dataUrl) {
            toast('当前显卡驱动不允许导出画布，可用浏览器截图（Ctrl+Shift+S）', 'err');
            return;
        }
        const image = new Image();
        image.onload = () => {
            const canvas = document.createElement('canvas');
            canvas.width = shot.width;
            canvas.height = shot.height;
            const ctx = canvas.getContext('2d');
            const gradient = ctx.createLinearGradient(0, 0, canvas.width, canvas.height);
            gradient.addColorStop(0, '#1b2331');
            gradient.addColorStop(1, '#0c111a');
            ctx.fillStyle = gradient;
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            const glow = ctx.createRadialGradient(canvas.width / 2, canvas.height * 0.42, 0,
                canvas.width / 2, canvas.height * 0.42, canvas.width * 0.6);
            glow.addColorStop(0, 'rgba(15,123,99,.18)');
            glow.addColorStop(1, 'rgba(15,123,99,0)');
            ctx.fillStyle = glow;
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            ctx.drawImage(image, 0, 0);
            this.exportCanvas(canvas);
        };
        image.onerror = () => this.exportFallback(shot.dataUrl);
        image.src = shot.dataUrl;
    }

    exportFallback(dataUrl) {
        this.download(dataUrl, this.fileName());
        toast('已导出当前视角 PNG', 'ok');
    }

    exportCanvas(canvas) {
        const dataUrl = canvas.toDataURL('image/png');
        this.download(dataUrl, this.fileName());
        toast('已导出当前视角 PNG', 'ok');
        // 支持剪贴板时顺手复制一份，方便直接贴到聊天里分享
        if (navigator.clipboard && window.ClipboardItem) {
            canvas.toBlob(blob => {
                if (!blob) return;
                navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })])
                    .then(() => toast('同时已复制到剪贴板', 'ok'))
                    .catch(() => { /* 用户没聚焦页面时剪贴板会拒绝，静默即可 */ });
            }, 'image/png');
        }
    }

    download(dataUrl, name) {
        const link = document.createElement('a');
        link.href = dataUrl;
        link.download = name;
        document.body.appendChild(link);
        link.click();
        link.remove();
    }

    fileName() {
        const state = this.api ? this.api.state() : {};
        const product = (this.root.dataset.product || '花礼').replace(/[\\/:*?"<>|]/g, '');
        return product + '-立体展台-' + (state.label || '全景') + '.png';
    }

    /** J08 键盘可达：方向键环绕、上下键俯仰、+/- 拉近推远、0 复位、W 切视图 */
    bindKeyboard() {
        const canvas = this.el.canvas;
        canvas.setAttribute('tabindex', '0');
        canvas.setAttribute('role', 'application');
        if (!canvas.getAttribute('aria-label')) {
            canvas.setAttribute('aria-label', '花束三维模型：方向键环绕，加减号缩放，0 复位');
        }
        canvas.addEventListener('keydown', event => {
            if (!this.api || this.tab !== 'model') return;
            const step = event.shiftKey ? 0.42 : 0.18;
            let handled = true;
            switch (event.key) {
                case 'ArrowLeft': this.api.orbit(-step, 0); break;
                case 'ArrowRight': this.api.orbit(step, 0); break;
                case 'ArrowUp': this.api.orbit(0, event.shiftKey ? -0.14 : -0.08); break;
                case 'ArrowDown': this.api.orbit(0, event.shiftKey ? 0.14 : 0.08); break;
                case '+':
                case '=': this.api.zoom(-0.12); break;
                case '-':
                case '_': this.api.zoom(0.12); break;
                case '0':
                case 'Home': this.api.reset(); break;
                case 'w':
                case 'W': this.cycleMode(); break;
                default: handled = false;
            }
            if (handled) {
                event.preventDefault();
                this.syncState();
            }
        });
        canvas.addEventListener('blur', () => this.root.classList.remove('is-kbd'));
        canvas.addEventListener('focus', () => this.root.classList.add('is-kbd'));
    }

    cycleMode() {
        const keys = Object.keys(RENDER_MODES);
        const current = this.api.state().mode || 'shaded';
        const next = keys[(keys.indexOf(current) + 1) % keys.length];
        this.api.setRenderMode(next);
        this.syncState();
    }

    /** 面板里的取景/转速/视图状态都从 api.state() 回读，避免两处各写一份 */
    syncState() {
        if (!this.api) return;
        const state = this.api.state();
        (this.el.presets ? this.el.presets.querySelectorAll('[data-preset]') : []).forEach(button => {
            const active = button.dataset.preset === state.preset;
            button.classList.toggle('is-active', active);
            button.setAttribute('aria-pressed', String(active));
        });
        (this.el.modes || []).forEach(button => {
            const active = button.dataset.huMode === state.mode;
            button.classList.toggle('is-active', active);
            button.setAttribute('aria-pressed', String(active));
        });
        if (this.el.spin && document.activeElement !== this.el.spin) {
            this.el.spin.value = String(Math.round((state.spinSpeed || 0) / 1.2 * 100));
        }
        const parts = ['当前取景：' + state.label, '远近 ' + state.distance + '%', state.modeLabel];
        if (state.spinning) {
            parts.push('自动旋转中');
            if (this.el.spin) this.el.spin.setAttribute('aria-label', '自动旋转速度，当前 ' + Math.round(state.spinSpeed * 100) / 100);
        } else {
            parts.push('已停止旋转');
            if (this.el.spin) this.el.spin.setAttribute('aria-label', '自动旋转速度，当前 0（停止）');
        }
        const line = this.root.querySelector('[data-hu-captionline]');
        if (line) line.textContent = parts.join(' · ');
        const canvas = this.el.canvas;
        if (canvas) canvas.setAttribute('aria-label', '花束三维模型，' + parts.join('，') + '。方向键环绕，加减号缩放，0 复位');
    }

    dispose() {
        this.disposed = true;
        if (this.io) this.io.disconnect();
        if (this.api) this.api.dispose();
        this.api = null;
    }
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', activate, { once: true });
} else {
    activate();
}

window.addEventListener('pagehide', () => {
    instances.forEach(stage => stage.dispose());
    instances.length = 0;
}, { once: true });

window.huStage3D = {
    activate,
    /** 页面动态插入展台片段后调用，给新容器接上行为 */
    add(root) {
        if (root.__huStage) return root.__huStage;
        const stage = new Stage(root);
        instances.push(stage);
        return stage;
    }
};

export { activate };
