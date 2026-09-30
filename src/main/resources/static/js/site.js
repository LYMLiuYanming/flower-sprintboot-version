/* 花语轩 · 前台公共行为脚本 */
(function () {
    // 导航滚动态
    var nav = document.querySelector('.hu-nav');
    var onScroll = function () {
        if (!nav) return;
        if (window.scrollY > 12) nav.classList.add('is-scrolled');
        else nav.classList.remove('is-scrolled');
        var bt = document.getElementById('huBackTop');
        if (bt) {
            if (window.scrollY > 600) bt.classList.add('is-show');
            else bt.classList.remove('is-show');
        }
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();

    document.addEventListener('click', function (e) {
        var bt = e.target.closest && e.target.closest('#huBackTop');
        if (bt) window.scrollTo({ top: 0, behavior: (window.huReducedMotion && window.huReducedMotion()) ? 'auto' : 'smooth' });
    });

    // 移动端抽屉 + 购物车角标（抽屉的具体行为收口到文件末尾的 K12 实现）
    document.addEventListener('DOMContentLoaded', function () {
        if (window.huInitDrawer) window.huInitDrawer();
        huLoadCartCount();
    });

    // 平滑锚点
    document.querySelectorAll('a[href^="#"]').forEach(function (a) {
        a.addEventListener('click', function (e) {
            var sel = a.getAttribute('href');
            if (sel.length > 1) {
                var t = document.querySelector(sel);
                if (t) {
                    e.preventDefault();
                    t.scrollIntoView({
                        behavior: (window.huReducedMotion && window.huReducedMotion()) ? 'auto' : 'smooth',
                        block: 'start'
                    });
                }
            }
        });
    });
})();

/* 购物车角标（依赖 jQuery，前台页均已引入） */
function huLoadCartCount() {
    if (!window.jQuery) return;
    jQuery.get('/api/cart/count', function (res) {
        if (res.code === 200) {
            jQuery('.hu-cart-count').text(res.data || 0).toggle(!!res.data);
        }
    });
}

/* 未登录时跳登录页，并带上当前页作为登录后的回跳目标 */
function huToLogin() {
    window.location.href = '/auth/login?redirect=' +
        encodeURIComponent(window.location.pathname + window.location.search);
}

/**
 * 统一 API 调用：JSON 出入参 + code 分派，401 自动跳登录并保留回跳地址
 * options: {url, type, data, ok: 成功回调(res.data,res), fail, always, silent,
 *           errorHost: 失败后就地渲染「加载失败 + 重试」的容器（选择器或元素）}
 */
function huApi(options) {
    if (!window.jQuery) return;
    var settings = {
        url: options.url,
        type: options.type || 'GET',
        dataType: 'json',
        success: function (res) {
            if (res.code === 200) {
                if (options.errorHost && window.huError) window.huError.clear(options.errorHost);
                if (options.ok) options.ok(res.data, res);
                return;
            }
            if (res.code === 401) {
                huToLogin();
                return;
            }
            if (!options.silent && window.huToast) window.huToast(res.msg || '操作失败', 'err');
            if (options.fail) options.fail(res);
        },
        error: function (xhr) {
            if (xhr.status === 401 || xhr.status === 403) {
                huToLogin();
                return;
            }
            if (!options.silent && window.huToast) window.huToast('网络异常，请稍后重试', 'err');
            // K05：网络类失败留一个可点的重试，而不是把「稍后再试」甩给用户
            if (options.errorHost && window.huError) {
                window.huError.show(options.errorHost, {
                    message: '网络连接失败了',
                    hint: '检查一下网络，或者稍后再拉一次',
                    retry: function () { huApi(options); }
                });
            }
            if (options.fail) options.fail({code: xhr.status, msg: '网络异常'});
        },
        complete: function () {
            if (options.always) options.always();
        }
    };
    if (options.data !== undefined) {
        if (settings.type === 'GET') {
            settings.data = options.data;
        } else {
            settings.contentType = 'application/json;charset=UTF-8';
            settings.data = JSON.stringify(options.data);
        }
    }
    jQuery.ajax(settings);
}

function huAddToCart(productId, onDone) {
    if (!window.jQuery) return;
    huApi({
        url: '/api/cart/add?productId=' + encodeURIComponent(productId) + '&quantity=1',
        type: 'POST',
        data: {},
        ok: function (data, res) {
            jQuery('.hu-cart-count').text(res.itemCount || 0).show();
            if (window.huToast) window.huToast('已加入购物车', 'ok');
            if (onDone) onDone(res);
        }
    });
}

/* 收藏按钮：任意带 data-favorite="商品ID" 的元素都可挂接 */
function huBindFavorite() {
    if (!window.jQuery) return;
    jQuery(document).on('click', '[data-favorite]', function (e) {
        e.preventDefault();
        var el = jQuery(this);
        huApi({
            url: '/api/favorites/toggle?productId=' + encodeURIComponent(el.data('favorite')),
            type: 'POST',
            data: {},
            ok: function (favorited) {
                el.toggleClass('is-favorited', favorited);
                el.find('i').toggleClass('fa-heart-o', !favorited).toggleClass('fa-heart', favorited);
                el.attr('title', favorited ? '取消收藏' : '收藏');
                if (window.huToast) window.huToast(favorited ? '已加入收藏' : '已取消收藏', 'ok');
            }
        });
    });
}
if (window.jQuery) window.jQuery(function () { huBindFavorite(); });

/* 前端渲染公共方法 */
function huEscape(value) {
    if (value === null || value === undefined) return '';
    return String(value).replace(/[&<>"']/g, function (c) {
        return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
    });
}

/**
 * K14 金额显示：千分位 + 两位小数（与后端 DisplayFormat.money 同规则）
 * options: {cents:false|digits:n 控制小数位, strip:true 去掉整元尾零}
 */
function huMoney(value, options) {
    var opts = options || {};
    var n = Number(value);
    if (!isFinite(n)) n = 0;
    var digits = (typeof opts.digits === 'number') ? opts.digits : (opts.cents === false ? 0 : 2);
    var neg = n < 0;
    var fixed = Math.abs(n).toFixed(digits);
    var dot = fixed.indexOf('.');
    var intPart = fixed.slice(0, dot < 0 ? fixed.length : dot).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    var out = dot < 0 ? intPart : intPart + fixed.slice(dot);
    // 只在有小数时削尾零，否则 1200.00 会被削成 12
    if (opts.strip && dot >= 0) out = out.replace(/0+$/, '').replace(/\.$/, '');
    return (neg && Number(out) !== 0 ? '-' : '') + out;
}

/** 列表 / 卡片用简写：1,299 与 199.5 */
function huPrice(value) {
    return huMoney(value, {strip: true});
}

/* 轻量 toast：队列、去重与 aria-live 收口在文件末尾的 K06 实现里 */

/**
 * 营销位联动：把「领券中心」的可用券渲染到首页 / 商品详情页的推广条上。
 * options: {chipsId, categoryIds?, limit?} —— 传 categoryIds（商品分类 + 其父分类）时，
 * 分类专享券只保留适用范围命中的那张。
 */
function huBindCouponPromo(options) {
    var host = document.getElementById(options.chipsId);
    if (!host || !window.jQuery) return;
    huApi({
        url: '/api/coupons/receivable',
        silent: true,
        ok: function (list) {
            var scopeIds = (options.categoryIds || []).map(String);
            var items = (list || []).filter(function (c) {
                return !scopeIds.length || c.scope !== 'category' || scopeIds.indexOf(String(c.categoryId)) >= 0;
            }).slice(0, options.limit || 4);
            if (!items.length) return;
            host.innerHTML = items.map(function (c) {
                return '<span class="hu-promo__chip">' +
                    '<b class="hu-promo__rule">' + huEscape(c.ruleText || c.name) + '</b>' +
                    '<em class="hu-promo__name">' + huEscape(c.name) + '</em>' +
                    '<button type="button" class="hu-promo__btn" data-coupon-id="' + huEscape(c.id) + '">领取</button>' +
                    '</span>';
            }).join('');
            var bar = host.closest('.hu-promo');
            if (bar) bar.hidden = false;
        }
    });
}

if (window.jQuery) window.jQuery(function () {
    jQuery(document).on('click', '.hu-promo__btn', function () {
        var btn = jQuery(this);
        if (btn.prop('disabled')) return;
        btn.prop('disabled', true);
        huApi({
            url: '/api/coupons/claim',
            type: 'POST',
            data: {couponId: btn.data('coupon-id')},
            ok: function () {
                btn.addClass('is-done').text('已领取');
                if (window.huToast) window.huToast('领取成功，结算时可用', 'ok');
            },
            fail: function () { btn.prop('disabled', false); }
        });
    });
});

/* ============================================================
   K 组 · 通用体验与可访问性（K01–K18）
   能力全部用 data-hu-* 声明式启用：页面加一个属性即可生效，不需要改结构；
   纯原生 DOM 实现，不依赖 jQuery，后台页只引 site.js 也能复用。
   ============================================================ */
(function (window, document) {
    'use strict';

    /* ---------- 基础工具 ---------- */
    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }

    function el(x, root) {
        if (!x) return null;
        if (typeof x === 'string') return (root || document).querySelector(x);
        if (x.nodeType) return x;
        if (typeof x.length === 'number') return x[0] || null;
        return x;
    }

    function attr(node, name, dflt) {
        if (!node || !node.getAttribute) return dflt;
        var v = node.getAttribute(name);
        return (v === null || v === undefined || v === '') ? dflt : v;
    }

    function flag(node, name) {
        return attr(node, name, 'true') !== 'false';
    }

    /** 减少动效偏好：抽屉、toast、滚动一律降级为即时切换 */
    function reducedMotion() {
        return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    }

    /** 'load' / 'ns.load' / 函数 → 可调用对象；找不到返回 null，交给 hu:retry 事件兜底 */
    function resolveFn(ref) {
        if (typeof ref === 'function') return ref;
        if (!ref || typeof ref !== 'string') return null;
        var cur = window;
        var parts = ref.split('.');
        for (var i = 0; i < parts.length; i++) {
            if (cur === null || cur === undefined) return null;
            cur = cur[parts[i]];
        }
        return typeof cur === 'function' ? cur : null;
    }

    function store(key, value) {
        try { window.localStorage.setItem(key, value); } catch (e) { /* 隐私模式下静默降级 */ }
    }

    function read(key) {
        try { return window.localStorage.getItem(key); } catch (e) { return null; }
    }

    /* ---------- 滚动锁定：引用计数，抽屉与弹层叠加时不会互相解锁 ---------- */
    var locks = Object.create(null);

    function lockScroll(key) {
        locks[key || 'anon'] = true;
        var pad = window.innerWidth - document.documentElement.clientWidth;
        document.documentElement.style.setProperty('--hu-lock-pad', (pad > 0 ? pad : 0) + 'px');
        document.documentElement.classList.add('hu-locked');
    }

    function unlockScroll(key) {
        delete locks[key || 'anon'];
        if (!Object.keys(locks).length) document.documentElement.classList.remove('hu-locked');
    }

    /* ---------- K06 Toast：队列 + 去重 + aria-live ---------- */
    var TOAST_MAX = 3;
    var toastHost = null;
    var toasts = [];
    var toastQueue = [];
    var toastRecent = Object.create(null);

    function toastHostEl() {
        if (toastHost && toastHost.isConnected) return toastHost;
        toastHost = document.createElement('div');
        toastHost.className = 'hu-toasts';
        toastHost.setAttribute('aria-live', 'polite');
        toastHost.setAttribute('aria-relevant', 'additions');
        document.body.appendChild(toastHost);
        return toastHost;
    }

    function toastRestart(node, item) {
        clearTimeout(node.__huTimer);
        node.__huTimer = setTimeout(function () { toastDismiss(node); }, item.duration);
    }

    function toastDismiss(node) {
        var idx = toasts.indexOf(node);
        if (idx >= 0) toasts.splice(idx, 1);
        clearTimeout(node.__huTimer);
        if (node.__huKey) delete toastRecent[node.__huKey];
        node.classList.remove('is-show');
        setTimeout(function () {
            if (node.parentNode) node.parentNode.removeChild(node);
            toastPump();
        }, reducedMotion() ? 0 : 300);
    }

    function toastPump() {
        while (toasts.length < TOAST_MAX && toastQueue.length) toastMount(toastQueue.shift());
    }

    function toastMount(item) {
        var node = document.createElement('div');
        node.className = 'hu-toast hu-toast--' + item.type + (item.type === 'err' ? ' err' : '');
        node.setAttribute('role', item.type === 'err' ? 'alert' : 'status');
        var msg = document.createElement('span');
        msg.className = 'hu-toast__msg';
        msg.textContent = item.msg;
        node.appendChild(msg);
        var n = document.createElement('span');
        n.className = 'hu-toast__n';
        n.textContent = item.n > 1 ? '×' + item.n : '';
        node.appendChild(n);
        var x = document.createElement('button');
        x.type = 'button';
        x.className = 'hu-toast__x';
        x.setAttribute('aria-label', '关闭提示');
        x.textContent = '×';
        x.addEventListener('click', function () { toastDismiss(node); });
        node.appendChild(x);
        toastHostEl().appendChild(node);
        toasts.push(node);
        node.__huKey = item.key;
        requestAnimationFrame(function () { node.classList.add('is-show'); });
        toastRestart(node, item);
        // 悬停暂停倒计时，长文案不至于读一半就消失
        node.addEventListener('mouseenter', function () { clearTimeout(node.__huTimer); });
        node.addEventListener('mouseleave', function () { toastRestart(node, item); });
        return node;
    }

    /**
     * huToast(msg, type, options)：type = ok|err|warn|info
     * 同文案 1.8s 内重复触发只留一条并累加 ×N，超过 3 条排队，连点不再刷屏。
     */
    function huToast(msg, type, options) {
        if (!document.body) {
            ready(function () { huToast(msg, type, options); });
            return;
        }
        var opts = options || {};
        var kind = (type === 'err' || type === 'error') ? 'err'
            : (type === 'warn' ? 'warn' : (type === 'info' ? 'info' : 'ok'));
        var text = (msg === null || msg === undefined) ? '' : String(msg);
        var key = kind + '::' + text;
        var live = toastRecent[key];
        if (opts.dedupe !== false && live && live.node && live.node.isConnected) {
            live.n += 1;
            live.duration = opts.duration || live.duration;
            var badge = live.node.querySelector('.hu-toast__n');
            if (badge) badge.textContent = '×' + live.n;
            live.node.classList.remove('is-bump');
            void live.node.offsetWidth;
            live.node.classList.add('is-bump');
            toastRestart(live.node, live);
            return live.node;
        }
        var item = {msg: text, type: kind, key: key, n: 1, duration: opts.duration || (kind === 'err' ? 3200 : 2200)};
        if (toasts.length >= TOAST_MAX) {
            toastQueue.push(item);
            if (toastQueue.length > 8) toastQueue.shift();
            return null;
        }
        var node = toastMount(item);
        toastRecent[key] = {node: node, n: 1, duration: item.duration};
        return node;
    }

    huToast.clear = function () {
        toastQueue.length = 0;
        toasts.slice().forEach(toastDismiss);
    };

    /* ---------- K10 无障碍播报：给复制、重试等瞬时反馈用 ---------- */
    var liveRegion = null;

    function huAnnounce(message) {
        if (!document.body) return;
        if (!liveRegion || !liveRegion.isConnected) {
            liveRegion = document.createElement('div');
            liveRegion.className = 'hu-sr-only hu-sr-live';
            liveRegion.setAttribute('aria-live', 'polite');
            document.body.appendChild(liveRegion);
        }
        liveRegion.textContent = '';
        setTimeout(function () { liveRegion.textContent = message; }, 30);
    }

    /* ---------- K15 时间：相对时间显示 + 绝对时间 tooltip（与 DisplayFormat 同规则） ---------- */
    function pad2(n) { return n < 10 ? '0' + n : '' + n; }

    function parseTime(v) {
        if (v === null || v === undefined || v === '') return null;
        if (v instanceof Date) return isNaN(v.getTime()) ? null : v;
        if (typeof v === 'number') return new Date(v < 1e12 ? v * 1000 : v);
        var s = String(v).trim();
        if (/^\d{10}$/.test(s)) return new Date(Number(s) * 1000);
        if (/^\d{13}$/.test(s)) return new Date(Number(s));
        // 服务端 "2026-05-01 14:30:00" 直接 new Date 在 Safari 下会得到 Invalid Date
        var d = new Date(s.replace(' ', 'T'));
        if (isNaN(d.getTime())) d = new Date(s);
        return isNaN(d.getTime()) ? null : d;
    }

    function huAbsTime(value, withSeconds) {
        var d = parseTime(value);
        if (!d) return '';
        var base = d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
            + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
        return withSeconds ? base + ':' + pad2(d.getSeconds()) : base;
    }

    function huFromNow(value, nowValue) {
        var d = parseTime(value);
        if (!d) return '';
        var now = parseTime(nowValue === undefined ? new Date() : nowValue) || new Date();
        var seconds = Math.floor((now.getTime() - d.getTime()) / 1000);
        if (seconds < 0) return huAbsTime(d);
        if (seconds < 10) return '刚刚';
        if (seconds < 60) return seconds + ' 秒前';
        var minutes = Math.floor(seconds / 60);
        if (minutes < 60) return minutes + ' 分钟前';
        var hours = Math.floor(minutes / 60);
        if (hours < 24) return hours + ' 小时前';
        var days = Math.floor(hours / 24);
        var yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
        if (days < 2 && d.getFullYear() === yesterday.getFullYear()
            && d.getMonth() === yesterday.getMonth() && d.getDate() === yesterday.getDate()) {
            return '昨天 ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
        }
        if (days < 7) return days + ' 天前';
        if (d.getFullYear() === now.getFullYear()) {
            return pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
        }
        return huAbsTime(d);
    }

    function upgradeTime(node) {
        if (!node || !node.getAttribute) return;
        var raw = attr(node, 'data-hu-time', null);
        if (raw === null) raw = node.__huTimeRaw || (node.textContent || '').trim();
        var d = parseTime(raw);
        if (!d) return;
        var abs = huAbsTime(d);
        node.setAttribute('title', attr(node, 'data-hu-time-title', abs));
        node.setAttribute('datetime', d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
            + 'T' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds()));
        node.classList.add('hu-time');
        node.__huTimeRaw = raw;
        node.textContent = attr(node, 'data-hu-time-mode', 'rel') === 'abs' ? abs : huFromNow(d);
    }

    /** 元素自身也可能带 data-hu-* —— querySelectorAll 不含 root，这里补上 */
    function forEachMatch(root, sel, fn) {
        if (!root || !root.querySelectorAll) return;
        if (root.matches && root.matches(sel)) fn(root);
        root.querySelectorAll(sel).forEach(fn);
    }

    function refreshTimes(root) {
        var scope = root || document;
        if (scope === document || !scope.nodeType) document.querySelectorAll('[data-hu-time]').forEach(upgradeTime);
        else forEachMatch(scope, '[data-hu-time]', upgradeTime);
    }

    var clockTimer = null;

    function startClock() {
        if (clockTimer) return;
        clockTimer = setInterval(function () {
            // 页面在后台时不刷新，省掉移动端不必要的重排
            if (document.visibilityState === 'visible') refreshTimes(document);
        }, 60000);
    }

    /* ---------- K03 骨架屏：多种形态收口到一处 ---------- */
    var SKELETONS = {
        product: function () {
            return '<div class="hu-product hu-product--ghost">' +
                '<div class="hu-skeleton hu-skeleton--media"></div><div style="padding:18px 18px 22px">' +
                '<div class="hu-skeleton hu-skeleton--line w40"></div>' +
                '<div class="hu-skeleton hu-skeleton--line w60"></div>' +
                '<div class="hu-skeleton hu-skeleton--line" style="width:80%"></div></div></div>';
        },
        card: function () {
            return '<div class="hu-panel hu-panel--ghost hu-skeleton-card">' +
                '<div class="hu-skeleton hu-skeleton--line" style="width:35%;margin:0"></div>' +
                '<div class="hu-skeleton hu-skeleton--line w60"></div>' +
                '<div class="hu-skeleton hu-skeleton--line w40"></div></div>';
        },
        rows: function () {
            return '<div class="hu-skeleton-row">' +
                '<div class="hu-skeleton" style="width:56px;height:56px;border-radius:12px;flex:0 0 auto"></div>' +
                '<div style="flex:1 1 auto;min-width:0">' +
                '<div class="hu-skeleton hu-skeleton--line" style="width:45%;margin:0 0 10px"></div>' +
                '<div class="hu-skeleton hu-skeleton--line w60" style="margin:0"></div></div></div>';
        },
        order: function () {
            return '<div class="hu-ordercard hu-panel--ghost"><div class="hu-ordercard__head">' +
                '<div class="hu-skeleton hu-skeleton--line" style="width:220px;margin:0"></div></div>' +
                '<div class="hu-ordercard__body">' +
                '<div class="hu-skeleton hu-skeleton--line" style="width:60%"></div>' +
                '<div class="hu-skeleton hu-skeleton--line" style="width:40%;margin-top:14px"></div></div></div>';
        },
        lines: function () {
            return '<div class="hu-skeleton hu-skeleton--line" style="margin:0 0 12px"></div>';
        },
        table: function () {
            return '<tr class="hu-panel--ghost"><td colspan="6">' +
                '<div class="hu-skeleton hu-skeleton--line" style="margin:0"></div></td></tr>';
        },
        coupon: function () {
            return '<div class="hu-coupon hu-panel--ghost"><div class="hu-coupon__stub">' +
                '<div class="hu-skeleton hu-skeleton--line" style="width:60%;margin:0;background:rgba(255,255,255,.35)"></div></div>' +
                '<div class="hu-coupon__body"><div class="hu-skeleton hu-skeleton--line w60" style="margin:0 0 10px"></div>' +
                '<div class="hu-skeleton hu-skeleton--line w40" style="margin:0"></div></div></div>';
        }
    };

    function huSkeletonHtml(kind, count) {
        var build = SKELETONS[kind] || SKELETONS.lines;
        var n = Math.max(1, Number(count) || 1);
        var html = '';
        for (var i = 0; i < n; i++) html += build();
        return html;
    }

    var huSkeleton = {
        html: huSkeletonHtml,
        /** 就地铺骨架；container 内原有内容会被暂存，clear 时还原 */
        show: function (container, kind, count) {
            var host = el(container);
            if (!host) return;
            if (!host.__huSkeletonBackup) {
                host.__huSkeletonBackup = host.innerHTML;
                host.setAttribute('aria-busy', 'true');
            }
            host.classList.add('is-loading');
            host.innerHTML = huSkeletonHtml(kind, count || attr(host, 'data-hu-skeleton-count', 3));
        },
        clear: function (container) {
            var host = el(container);
            if (!host || !host.__huSkeletonBackup) return;
            host.innerHTML = host.__huSkeletonBackup;
            host.__huSkeletonBackup = null;
            host.classList.remove('is-loading');
            host.removeAttribute('aria-busy');
        }
    };

    /* ---------- K04 空态：图标 + 主副文案 + 行动点 ---------- */
    var EMPTY_COPY = {
        'default': {icon: 'fa-inbox', title: '这里还是空的', desc: '稍后再回来看看'},
        search: {icon: 'fa-search', title: '没有找到匹配的花礼', desc: '换个关键词，或清空筛选条件再看看'},
        filter: {icon: 'fa-filter', title: '当前筛选没有结果', desc: '放宽一两个条件，选择会多起来'},
        cart: {icon: 'fa-shopping-bag', title: '购物车还空着', desc: '挑一束喜欢的花，让它去见想见的人'},
        order: {icon: 'fa-file-text-o', title: '暂无相关订单', desc: '挑一束合心意的花，让今天有点不一样'},
        favorite: {icon: 'fa-heart-o', title: '还没有收藏', desc: '看到心动的花礼，点一下卡片右上角的心形'},
        coupon: {icon: 'fa-ticket', title: '券包空空的', desc: '去领券中心看看今天有什么心意'},
        address: {icon: 'fa-map-marker', title: '还没有收货地址', desc: '添加一个地址，结算时会自动带出'},
        review: {icon: 'fa-comment-o', title: '还没有评价', desc: '收到花之后，来写下第一手感受'},
        notice: {icon: 'fa-bullhorn', title: '暂无公告', desc: '新的活动与配送说明会出现在这里'},
        garden: {icon: 'fa-leaf', title: '花田还没开垦', desc: '种下第一颗种子，每天浇水等它开花'},
        point: {icon: 'fa-diamond', title: '还没有积分记录', desc: '下单、评价都会慢慢攒下阳光与雨露'},
        trace: {icon: 'fa-truck', title: '暂无物流信息', desc: '花束出库后会第一时间更新轨迹'},
        msg: {icon: 'fa-envelope-o', title: '暂无消息', desc: '订单与配送提醒会出现在这里'}
    };

    function huEmptyHtml(state, opts) {
        var o = opts || {};
        var c = EMPTY_COPY[state] || EMPTY_COPY['default'];
        var icon = o.icon || c.icon;
        var title = o.title || c.title;
        var desc = (o.desc === undefined ? c.desc : o.desc) || '';
        var html = '<i class="fa ' + icon + ' hu-empty__icon" aria-hidden="true"></i>' +
            '<strong class="hu-empty__title">' + huEscape(title) + '</strong>';
        if (desc) html += '<span class="hu-empty__desc">' + huEscape(desc) + '</span>';
        if (o.actionText && o.actionHref) {
            html += '<a class="hu-btn hu-btn--ghost hu-btn--sm hu-empty__action" href="' + huEscape(o.actionHref) + '">' +
                huEscape(o.actionText) + '</a>';
        } else if (o.actionText && o.actionRetry) {
            html += '<button type="button" class="hu-btn hu-btn--ghost hu-btn--sm hu-empty__action" ' +
                'data-hu-retry="' + huEscape(o.actionRetry) + '"><i class="fa fa-refresh" aria-hidden="true"></i>' +
                huEscape(o.actionText) + '</button>';
        }
        return html;
    }

    var huEmpty = {
        html: huEmptyHtml,
        copy: EMPTY_COPY,
        show: function (container, state, opts) {
            var host = el(container);
            if (!host) return;
            var o = opts || {};
            var oState = attr(host, 'data-hu-empty', state || 'default');
            var filled = huEmptyHtml(oState, {
                icon: o.icon || attr(host, 'data-hu-empty-icon', null),
                title: o.title || attr(host, 'data-hu-empty-title', null),
                desc: (o.desc !== undefined ? o.desc : attr(host, 'data-hu-empty-desc', undefined)),
                actionText: o.actionText || attr(host, 'data-hu-empty-action', null),
                actionHref: o.actionHref || attr(host, 'data-hu-empty-href', null),
                actionRetry: o.actionRetry || attr(host, 'data-hu-empty-retry', null)
            });
            if (!host.__huEmptyBackup) host.__huEmptyBackup = host.innerHTML;
            host.innerHTML = filled;
            host.classList.add('hu-empty');
            host.removeAttribute('aria-hidden');
        }
    };

    function upgradeEmpty(node) {
        // 只在容器确实是空的时候填模板，避免覆盖页面自己写好的文案
        if (node.childNodes.length > 0 && node.textContent.trim() !== '') return;
        huEmpty.show(node, attr(node, 'data-hu-empty', 'default'));
    }

    /* ---------- K05 错误态：网络失败给一个能点的重试 ---------- */
    var retryFns = Object.create(null);

    function huErrorHtml(opts) {
        var o = opts || {};
        var retry = typeof o.retry === 'string' ? o.retry : null;
        return '<div class="hu-errorstate">' +
            '<span class="hu-errorstate__icon"><i class="fa fa-exclamation-triangle" aria-hidden="true"></i></span>' +
            '<strong class="hu-errorstate__title">' + huEscape(o.message || '这一屏没能加载出来') + '</strong>' +
            '<span class="hu-errorstate__desc">' + huEscape(o.hint || '网络稍不稳定，点下面重试一次就好') + '</span>' +
            '<button type="button" class="hu-btn hu-btn--ghost hu-btn--sm hu-errorstate__btn" ' +
            (retry ? 'data-hu-retry="' + huEscape(retry) + '" ' : '') + '>' +
            '<i class="fa fa-refresh" aria-hidden="true"></i>' + huEscape(o.retryText || '重试') + '</button>' +
            '</div>';
    }

    var huError = {
        html: huErrorHtml,
        show: function (container, opts) {
            var host = el(container);
            if (!host) return;
            if (!host.__huErrorBackup) {
                host.__huErrorBackup = host.innerHTML;
                host.classList.add('is-errorstate-host');
            }
            host.innerHTML = huErrorHtml(opts);
            host.removeAttribute('hidden');
            host.style.display = '';
            host.setAttribute('aria-live', 'polite');
        },
        clear: function (container) {
            var host = el(container);
            if (!host || !host.classList.contains('is-errorstate-host')) return;
            host.innerHTML = host.__huErrorBackup || '';
            host.__huErrorBackup = null;
            host.classList.remove('is-errorstate-host');
            host.removeAttribute('aria-live');
        }
    };

    /** 重试动作注册表：页面写 huRetry.register('load', load) 后，模板里 data-hu-retry="load" 即可 */
    var huRetry = {
        register: function (name, fn) { retryFns[name] = fn; },
        run: function (name, button) {
            var fn = retryFns[name] || resolveFn(name);
            if (fn) {
                var r = fn();
                if (r && typeof r.then === 'function') r.then(function () { release(); }, function () { release(); });
                else setTimeout(release, 500);
                return true;
            }
            function release() {
                if (!button) return;
                button.disabled = false;
                button.removeAttribute('aria-busy');
                button.classList.remove('is-busy');
            }

            return false;
        }
    };

    document.addEventListener('click', function (e) {
        var btn = e.target && e.target.closest ? e.target.closest('[data-hu-retry]') : null;
        if (!btn) return;
        var name = attr(btn, 'data-hu-retry', '');
        btn.disabled = true;
        btn.setAttribute('aria-busy', 'true');
        btn.classList.add('is-busy');
        var matched = huRetry.run(name, btn);
        if (!matched) {
            // 没有注册同名函数时交给页面自己监听 hu:retry，两条路都通
            var handled = btn.dispatchEvent(new CustomEvent('hu:retry', {
                bubbles: true, cancelable: true, detail: {action: name, button: btn}
            }));
            if (!handled || !retryFns[name] && !resolveFn(name)) {
                setTimeout(function () {
                    btn.disabled = false;
                    btn.removeAttribute('aria-busy');
                    btn.classList.remove('is-busy');
                }, 400);
            }
        }
    });

    /* ---------- K13 分页：窗口化页码 + 省略号，前台所有列表共用 ---------- */
    function pagerWindow(page, pages) {
        var out = [];
        if (pages <= 7) {
            for (var i = 1; i <= pages; i++) out.push(i);
            return out;
        }
        out.push(1);
        var from = Math.max(2, page - 1);
        var to = Math.min(pages - 1, page + 1);
        if (from > 2) out.push('…');
        for (var p = from; p <= to; p++) out.push(p);
        if (to < pages - 1) out.push('…');
        out.push(pages);
        return out;
    }

    function huPagerHtml(st) {
        var s = st || {};
        var pages = Math.max(1, Number(s.pages) || 1);
        var page = Math.min(Math.max(1, Number(s.page) || 1), pages);
        var href = typeof s.href === 'function' ? s.href : null;
        var html = '<nav class="hu-pager__nav" aria-label="' + huEscape(s.label || '分页导航') + '">';
        html += '<button type="button" class="hu-pager__edge" data-hu-pager-page="' + (page - 1) + '"' +
            (page <= 1 ? ' disabled' : '') + ' aria-label="上一页"><i class="fa fa-chevron-left" aria-hidden="true"></i></button>';
        pagerWindow(page, pages).forEach(function (item) {
            if (item === '…') {
                html += '<span class="hu-ellipsis" aria-hidden="true">…</span>' +
                    '<span class="hu-sr-only">省略部分页码</span>';
                return;
            }
            var active = item === page;
            var link = href ? href(item) : null;
            if (link) {
                html += '<a href="' + huEscape(link) + '"' + (active ? ' class="is-active" aria-current="page"' : '') +
                    ' data-hu-pager-page="' + item + '" aria-label="第 ' + item + ' 页">' + item + '</a>';
            } else {
                html += '<button type="button" data-hu-pager-page="' + item + '"' +
                    (active ? ' class="is-active" aria-current="page"' : '') +
                    ' aria-label="第 ' + item + ' 页">' + item + '</button>';
            }
        });
        html += '<button type="button" class="hu-pager__edge" data-hu-pager-page="' + (page + 1) + '"' +
            (page >= pages ? ' disabled' : '') + ' aria-label="下一页"><i class="fa fa-chevron-right" aria-hidden="true"></i></button>';
        html += '</nav>';
        if (s.total !== undefined && s.total !== null) {
            html += '<p class="hu-pager__meta">共 <b>' + huEscape(huMoney(s.total, {cents: false})) + '</b> 条 · 第 ' +
                page + ' / ' + pages + ' 页</p>';
        }
        return html;
    }

    var pagerHooks = new WeakMap();

    var huPager = {
        html: huPagerHtml,
        /**
         * render(host, {page, pages, total, onGo(page), href(page)})
         * 传 onGo 时点击不外跳，只回调；不传则按 href 生成链接（服务端分页可用）。
         */
        render: function (host, state) {
            var node = el(host);
            if (!node) return;
            var s = state || {};
            pagerHooks.set(node, s);
            node.classList.add('hu-pager');
            if (Number(s.pages) > 1) {
                node.innerHTML = huPagerHtml(s);
                node.removeAttribute('hidden');
            } else {
                node.innerHTML = '';
                node.setAttribute('hidden', 'hidden');
            }
        },
        go: function (host, page) {
            var node = el(host);
            if (!node) return;
            var s = pagerHooks.get(node) || {};
            var pages = Math.max(1, Number(s.pages) || 1);
            var next = Math.min(Math.max(1, Number(page) || 1), pages);
            if (next === Number(s.page)) return;
            if (typeof s.onGo === 'function') {
                s.page = next;
                s.onGo(next);
                return;
            }
            if (typeof s.href === 'function') {
                window.location.href = s.href(next);
                return;
            }
            node.dispatchEvent(new CustomEvent('hu:page', {bubbles: true, cancelable: true, detail: {page: next}}));
        }
    };

    document.addEventListener('click', function (e) {
        var btn = e.target && e.target.closest ? e.target.closest('[data-hu-pager-page]') : null;
        if (!btn) return;
        var host = btn.closest('.hu-pager');
        if (!host) return;
        e.preventDefault();
        huPager.go(host, btn.getAttribute('data-hu-pager-page'));
    });

    /* ---------- K16 复制：订单号 / 券码 / 物流单号 ---------- */
    function copyText(text) {
        return new Promise(function (resolve, reject) {
            var value = String(text === null || text === undefined ? '' : text);
            if (navigator.clipboard && window.isSecureContext) {
                navigator.clipboard.writeText(value).then(resolve, function () { legacyCopy(value, resolve, reject); });
                return;
            }
            legacyCopy(value, resolve, reject);
        });
    }

    function legacyCopy(value, resolve, reject) {
        var ta = document.createElement('textarea');
        ta.value = value;
        ta.setAttribute('readonly', 'readonly');
        ta.className = 'hu-sr-only';
        ta.style.position = 'fixed';
        ta.style.top = '-1000px';
        document.body.appendChild(ta);
        ta.select();
        var ok = false;
        try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
        document.body.removeChild(ta);
        if (ok) resolve();
        else reject(new Error('copy-unavailable'));
    }

    function copySourceText(btn) {
        var literal = attr(btn, 'data-hu-copy', null);
        if (literal !== null) return literal;
        var sel = attr(btn, 'data-hu-copy-target', null);
        if (!sel) return '';
        var scope = btn.closest('[data-hu-copy-scope]') || document;
        var src = scope.querySelector(sel) || document.querySelector(sel);
        if (!src) return '';
        var raw = ('value' in src && src.value) ? src.value : (src.textContent || '').trim();
        var prefix = attr(btn, 'data-hu-copy-strip', null);
        if (prefix && raw.indexOf(prefix) === 0) raw = raw.slice(prefix.length).trim();
        return raw;
    }

    function huCopy(text, label) {
        var value = String(text === null || text === undefined ? '' : text).trim();
        if (!value) {
            huToast('没有可复制的内容', 'err');
            return Promise.reject(new Error('empty'));
        }
        return copyText(value).then(function () {
            huToast((label || value) + ' 已复制', 'ok');
            huAnnounce('已复制 ' + value);
            return true;
        }, function () {
            huToast('浏览器不允许自动复制，请长按选中后复制', 'err');
            return false;
        });
    }

    document.addEventListener('click', function (e) {
        var btn = e.target && e.target.closest ? e.target.closest('[data-hu-copy],[data-hu-copy-target]') : null;
        if (!btn) return;
        var value = copySourceText(btn);
        var label = attr(btn, 'data-hu-copy-label', '');
        copyText(value).then(function () {
            btn.classList.add('is-copied');
            clearTimeout(btn.__huCopyTimer);
            btn.__huCopyTimer = setTimeout(function () { btn.classList.remove('is-copied'); }, 1600);
            huAnnounce('已复制 ' + value);
            huToast((label ? label + ' ' : '') + '已复制', 'ok');
        }, function () {
            huToast('复制失败，请手动选中复制', 'err');
        });
    });

    /* ---------- K09 弹层：焦点陷阱 + Esc 关闭 + 滚动锁定 ---------- */
    var FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]):not([type=hidden]),' +
        'select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';
    var dialogObservers = new WeakMap();
    var dialogSeq = 0;
    var lastEscOpen = [];

    function dialogPanel(mask) {
        return mask.querySelector('.hu-dialog, [role="dialog"], [role="alertdialog"], [data-hu-dialog-body]') ||
            mask.firstElementChild || mask;
    }

    function focusables(root) {
        return Array.prototype.filter.call(root.querySelectorAll(FOCUSABLE), function (n) {
            // 隐藏域与 visibility:hidden 的项不参与 Tab 环，否则焦点会掉进看不见的地方
            return n.offsetWidth || n.offsetHeight || n.getClientRects().length;
        });
    }

    function visibleDialogs() {
        return Array.prototype.slice.call(
            document.querySelectorAll('.hu-mask.is-open, [data-hu-dialog].is-open'));
    }

    function topDialog() {
        var list = visibleDialogs();
        return list.length ? list[list.length - 1] : null;
    }

    function dialogOnOpen(mask) {
        if (mask.__huDialogOpen) return;
        mask.__huDialogOpen = true;
        mask.__huReturn = document.activeElement;
        // 锁的 key 必须开合一致，否则无 id 的弹层解锁失败会把页面永久锁死
        mask.__huLockKey = mask.__huLockKey || ('dialog:' + (mask.id || (++dialogSeq)));
        lockScroll(mask.__huLockKey);
        var panel = dialogPanel(mask);
        panel.setAttribute('tabindex', '-1');
        var list = focusables(panel);
        var wanted = panel.querySelector('[data-hu-autofocus]');
        if (!panel.contains(document.activeElement)) {
            if (wanted) wanted.focus();
            else {
                // 表单弹窗优先落到第一个输入框，纯确认框落在主按钮上
                var field = list.filter(function (n) {
                    return /^(INPUT|TEXTAREA|SELECT)$/.test(n.tagName);
                })[0];
                (field || list[0] || panel).focus();
            }
        }
    }

    function dialogOnClose(mask) {
        if (!mask.__huDialogOpen) return;
        mask.__huDialogOpen = false;
        unlockScroll(mask.__huLockKey || 'dialog:' + mask.id);
        var back = mask.__huReturn;
        mask.__huReturn = null;
        if (back && back.focus && back.isConnected && !mask.contains(document.activeElement)) back.focus();
    }

    /**
     * 关闭优先“按下页面自己的关闭按钮”，让页面自己维护的状态（如弹窗计数、滚动锁）不失同步；
     * 页面没有留关闭按钮时才直接摘掉 is-open。
     */
    function requestDialogClose(mask) {
        if (!mask) return;
        var closer = mask.querySelector('[data-hu-close]') ||
            mask.querySelector('.hu-panel-head .hu-icon-btn[title*="关闭"], .hu-panel-head .hu-icon-btn[aria-label*="关闭"]') ||
            Array.prototype.filter.call(mask.querySelectorAll('button'), function (b) {
                return /^取\s*消$/.test((b.textContent || '').trim());
            })[0];
        if (closer && mask.__huDialogOpen) {
            closer.click();
            if (mask.classList.contains('is-open')) mask.classList.remove('is-open');
            return;
        }
        mask.classList.remove('is-open');
        mask.dispatchEvent(new CustomEvent('hu:dialogclose', {bubbles: true}));
    }

    function observeDialog(mask) {
        if (dialogObservers.has(mask)) return;
        var observer = new MutationObserver(function () {
            if (mask.classList.contains('is-open')) dialogOnOpen(mask);
            else dialogOnClose(mask);
        });
        observer.observe(mask, {attributes: true, attributeFilter: ['class']});
        dialogObservers.set(mask, observer);
        if (mask.classList.contains('is-open')) dialogOnOpen(mask);
    }

    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Tab') return;
        var top = topDialog();
        if (!top) return;
        var panel = dialogPanel(top);
        var list = focusables(panel);
        if (!list.length) {
            e.preventDefault();
            panel.focus();
            return;
        }
        var first = list[0];
        var last = list[list.length - 1];
        var active = document.activeElement;
        if (!panel.contains(active)) {
            // 焦点已经溜出弹窗（例如点了背景遮罩），按 Tab 时拉回来
            e.preventDefault();
            (e.shiftKey ? last : first).focus();
            return;
        }
        if (e.shiftKey && active === first) {
            e.preventDefault();
            last.focus();
        } else if (!e.shiftKey && active === last) {
            e.preventDefault();
            first.focus();
        }
    });

    // 捕获阶段先记快照：若页面的 Esc 处理已经关掉了一层，document 阶段就不再重复关第二层
    window.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' || e.keyCode === 27) lastEscOpen = visibleDialogs();
    }, true);

    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape' && e.keyCode !== 27) return;
        var list = visibleDialogs();
        if (!list.length) return;
        if (list.length !== lastEscOpen.length) return;
        var top = list[list.length - 1];
        if (!flag(top, 'data-hu-esc')) return;
        requestDialogClose(top);
        lastEscOpen = [];
    });

    // 点遮罩空白处关闭：页面自己绑了 mousedown 的（如地址弹层）会被快照式跳过
    document.addEventListener('mousedown', function (e) {
        var mask = e.target && e.target.closest ? e.target.closest('.hu-mask.is-open, [data-hu-dialog].is-open') : null;
        if (!mask || mask !== e.target) return;
        if (!flag(mask, 'data-hu-dismiss')) return;
        mask.__huBackdropAt = Date.now();
    });

    document.addEventListener('click', function (e) {
        var mask = e.target && e.target.closest ? e.target.closest('.hu-mask.is-open, [data-hu-dialog].is-open') : null;
        if (!mask || mask !== e.target || !mask.__huBackdropAt) return;
        // 260ms 内页面自身的 mousedown 处理若已关掉，这里就不重复关
        if (Date.now() - mask.__huBackdropAt > 400) return;
        if (!mask.classList.contains('is-open')) return;
        requestDialogClose(mask);
    });

    document.addEventListener('click', function (e) {
        var trigger = e.target && e.target.closest ? e.target.closest('[data-hu-dialog-open]') : null;
        if (!trigger) return;
        var mask = document.getElementById(trigger.getAttribute('data-hu-dialog-open')) ||
            document.querySelector(attr(trigger, 'data-hu-dialog-open', ''));
        if (!mask) return;
        e.preventDefault();
        mask.classList.add('is-open');
    });

    document.addEventListener('click', function (e) {
        var closer = e.target && e.target.closest ? e.target.closest('[data-hu-close]') : null;
        if (!closer) return;
        var mask = closer.closest('.hu-mask, [data-hu-dialog]');
        if (mask) requestDialogClose(mask);
    });

    /* ---------- K12 移动端抽屉：动画 + 滚动锁定 + 可退出 ---------- */
    var drawerApi = null;

    function initDrawer() {
        var burger = document.getElementById('huMenuBtn');
        var drawer = document.getElementById('huMobileMenu');
        if (!burger || !drawer) return null;
        if (drawerApi) return drawerApi;
        var scrim = document.createElement('div');
        scrim.className = 'hu-scrim hu-drawer-scrim';
        scrim.setAttribute('aria-hidden', 'true');
        if (!drawer.getAttribute('aria-label')) drawer.setAttribute('aria-label', '移动端导航');
        var open = false;

        function set(next) {
            open = !!next;
            drawer.classList.toggle('is-open', open);
            burger.classList.toggle('is-open', open);
            burger.setAttribute('aria-expanded', String(open));
            drawer.setAttribute('aria-hidden', open ? 'false' : 'true');
            if (open) {
                if (!scrim.isConnected) document.body.appendChild(scrim);
                lockScroll('drawer');
            } else {
                if (scrim.isConnected) scrim.parentNode.removeChild(scrim);
                unlockScroll('drawer');
            }
        }

        burger.addEventListener('click', function () { set(!open); });
        scrim.addEventListener('click', function () { set(false); });
        drawer.addEventListener('click', function (e) {
            if (e.target && e.target.closest('a')) set(false);
        });
        document.addEventListener('keydown', function (e) {
            if (open && (e.key === 'Escape' || e.keyCode === 27)) {
                set(false);
                burger.focus();
            }
        });
        window.addEventListener('resize', function () {
            if (open && window.innerWidth > 900) set(false);
        }, {passive: true});
        drawerApi = {open: function () { set(true); }, close: function () { set(false); }, isOpen: function () { return open; }};
        return drawerApi;
    }

    /* ---------- K01 导航选中态：按「路径 + 查询参数包含」挑最贴合的那一项 ---------- */
    function linkParts(href) {
        var a = document.createElement('a');
        a.href = href;
        var search = a.search || '';
        return {
            path: a.pathname,
            params: search.replace(/^\?/, '').split('&').filter(Boolean)
        };
    }

    function scoreLink(href) {
        if (!href || href === '#' || /^(javascript|mailto|tel):/i.test(href)) return -1;
        var here = linkParts(window.location.href);
        var there = linkParts(href);
        if (here.path !== there.path) return -1;
        var score = there.path.length * 10;
        var hit = 0;
        there.params.forEach(function (kv) {
            if (here.params.indexOf(kv) >= 0) hit++;
        });
        // 带参数却没命中的链接（如 /products?sort=sales 停在 /products）不算选中
        if (there.params.length && hit !== there.params.length) return -1;
        return score + hit * 20;
    }

    function markActiveNav() {
        // 顶部导航：服务端不写选中态，这里全权接管（K01 一致性）
        var managed = '.hu-menu a, .hu-mobile-menu a';
        // 账户栏与分类侧栏：服务端已经用 th:classappend 标好，JS 只补 aria-current，不清类名
        var annotated = '.hu-navtabs a, .hu-catlist a';

        function bestOf(scopeSel) {
            var best = null;
            var bestScore = -1;
            document.querySelectorAll(scopeSel).forEach(function (a) {
                var score = scoreLink(a.getAttribute('href'));
                if (score > bestScore) {
                    bestScore = score;
                    best = a;
                }
            });
            return best;
        }

        var topNav = bestOf(managed);
        document.querySelectorAll(managed).forEach(function (a) {
            var active = a === topNav;
            a.classList.toggle('is-active', active);
            if (active) a.setAttribute('aria-current', 'page');
            else a.removeAttribute('aria-current');
        });
        var sideNav = bestOf(annotated);
        document.querySelectorAll(annotated).forEach(function (a) {
            if (a === sideNav) a.setAttribute('aria-current', 'page');
        });
    }

    /* ---------- K17 返回上级：能回退就走历史，不能才跳到声明的父页 ---------- */
    document.addEventListener('click', function (e) {
        var btn = e.target && e.target.closest ? e.target.closest('[data-hu-back]') : null;
        if (!btn) return;
        var href = attr(btn, 'data-hu-back', '') || (btn.getAttribute ? btn.getAttribute('href') : '');
        var sameOriginRef = document.referrer && document.referrer.indexOf(window.location.origin) === 0;
        if (flag(btn, 'data-hu-back-history') && sameOriginRef && window.history.length > 1) {
            e.preventDefault();
            window.history.back();
            return;
        }
        if (href) {
            e.preventDefault();
            window.location.href = href;
        }
    });

    /* ---------- K07 首个错误字段聚焦 ---------- */
    var FORM_ERRORABLE = '.hu-input,.hu-select,.hu-textarea,input,select,textarea';

    function focusFirstError(scope) {
        var root = el(scope) || document;
        var field = root.querySelector('.hu-field.has-err, .has-err');
        var control = null;
        if (field) {
            control = field.querySelector(FORM_ERRORABLE);
        } else {
            control = root.querySelector('[aria-invalid="true"]');
        }
        if (!control || control.disabled) return false;
        try {
            control.scrollIntoView({
                behavior: reducedMotion() ? 'auto' : 'smooth',
                block: 'center'
            });
        } catch (e) { /* 老浏览器不支持 options 对象 */ }
        control.focus({preventScroll: true});
        huAnnounce('表单有 ' + root.querySelectorAll('.has-err').length + ' 处需要修改，已定位到第一处');
        return true;
    }

    /*
     * 声明式接入：<form data-hu-validate> 提交后自动同步 aria 并把焦点送到第一个错误字段；
     * data-hu-validate="auto" 再多盯几个时间点，覆盖「服务端异步回字段错误」的场景。
     * 文档级监听挂在冒泡阶段，页面自己在 form 上的 handler 已经先跑完。
     */
    document.addEventListener('submit', function (e) {
        var form = e.target;
        if (!form || !form.matches || !form.matches('form[data-hu-validate]')) return;
        var async = attr(form, 'data-hu-validate', '') === 'auto';
        setTimeout(function () {
            syncFieldValidity(form);
            if (form.querySelector('.has-err')) focusFirstError(form);
        }, 0);
        if (!async) return;
        [400, 1200, 2400].forEach(function (delay) {
            setTimeout(function () {
                if (!form.isConnected) return;
                // 服务端异步回字段错误时，用户还没自己动手改就把焦点送过去
                if (document.activeElement && document.activeElement !== document.body) return;
                syncFieldValidity(form);
                if (form.querySelector('.hu-field.has-err')) focusFirstError(form);
            }, delay);
        });
    });

    function syncFieldValidity(scope) {
        var root = el(scope) || document;
        root.querySelectorAll('.hu-field').forEach(function (field) {
            var err = field.querySelector('.hu-field__err');
            var control = field.querySelector(FORM_ERRORABLE);
            if (!control) return;
            var invalid = field.classList.contains('has-err');
            if (invalid) control.setAttribute('aria-invalid', 'true');
            else control.removeAttribute('aria-invalid');
            if (err && !err.id && invalid) {
                err.id = 'hu-err-' + Math.random().toString(36).slice(2, 8);
            }
            if (err && err.id) {
                var described = (control.getAttribute('aria-describedby') || '').split(/\s+/).filter(function (t) {
                    return t && (t !== err.id || invalid);
                });
                if (invalid) described.push(err.id);
                if (described.length) control.setAttribute('aria-describedby', described.join(' '));
            }
            var required = field.querySelector('.hu-field__req') && !control.hasAttribute('data-hu-optional');
            if (required) control.setAttribute('aria-required', 'true');
        });
    }

    /* ---------- K10 图标控件补可访问名 + label 绑定 ---------- */
    var ICON_LABELS = {
        'fa-search': '搜索', 'fa-bars': '打开菜单', 'fa-times': '关闭', 'fa-close': '关闭',
        'fa-heart-o': '收藏', 'fa-heart': '取消收藏', 'fa-user-o': '个人中心', 'fa-user': '个人中心',
        'fa-shopping-bag': '购物车', 'fa-arrow-up': '返回顶部', 'fa-chevron-left': '上一页',
        'fa-chevron-right': '下一页', 'fa-refresh': '刷新', 'fa-copy': '复制', 'fa-clipboard': '复制',
        'fa-map-marker': '位置', 'fa-ticket': '优惠券', 'fa-angle-down': '展开', 'fa-angle-right': '展开'
    };

    function iconLabel(node) {
        var i = node.querySelector('i[class*="fa-"], svg');
        if (!i) return '';
        var cls = i.getAttribute('class') || '';
        var found = '';
        Object.keys(ICON_LABELS).forEach(function (key) {
            if (!found && cls.indexOf(key) >= 0) found = ICON_LABELS[key];
        });
        return found;
    }

    function hasText(node) {
        return (node.textContent || '').replace(/\s+/g, '').length > 0;
    }

    function a11yScan(scope) {
        var root = el(scope) || document;
        // 纯装饰图标先隐藏，避免读屏念出「font awesome」
        root.querySelectorAll('i[class*="fa-"]').forEach(function (i) {
            if (!i.hasAttribute('aria-hidden') && !i.hasAttribute('role')) i.setAttribute('aria-hidden', 'true');
        });
        root.querySelectorAll('svg').forEach(function (s) {
            if (!s.hasAttribute('aria-hidden') && !s.hasAttribute('role')) s.setAttribute('aria-hidden', 'true');
            if (!s.hasAttribute('focusable')) s.setAttribute('focusable', 'false');
        });
        root.querySelectorAll('button, a').forEach(function (node) {
            if (node.hasAttribute('aria-label') || node.hasAttribute('aria-labelledby') || node.hasAttribute('title')) {
                // 只有 title 的图标控件补 aria-label：读屏不总会念 title
                if (!node.hasAttribute('aria-label') && node.getAttribute('title')) {
                    node.setAttribute('aria-label', node.getAttribute('title'));
                }
                return;
            }
            if (hasText(node)) return;
            var label = attr(node, 'data-hu-label', '') || iconLabel(node);
            if (label) node.setAttribute('aria-label', label);
        });
        root.querySelectorAll('.hu-field > label').forEach(function (label) {
            if (label.getAttribute('for')) return;
            var control = label.parentElement.querySelector('input, select, textarea');
            if (control && control.id) label.setAttribute('for', control.id);
        });
        root.querySelectorAll('table.hu-table').forEach(function (t) {
            if (!t.getAttribute('aria-label') && !t.querySelector('caption')) {
                var named = attr(t, 'data-hu-label', '数据表格');
                t.setAttribute('aria-label', named);
            }
        });
        syncFieldValidity(root);
    }

    function injectSkipLink() {
        if (document.querySelector('.hu-skip')) return;
        // 跳过页头与页脚：找不到 main 时退到第一个页面头部区，再退到正文容器
        var candidates = [document.querySelector('main'), document.querySelector('.hu-pagehead')]
            .concat(Array.prototype.slice.call(document.querySelectorAll('.hu-container'))
                .filter(function (n) {
                    return !n.closest('.hu-nav') && !n.closest('.hu-footer');
                }));
        var target = null;
        for (var i = 0; i < candidates.length; i++) {
            if (candidates[i]) {
                target = candidates[i];
                break;
            }
        }
        if (!target) return;
        if (!target.id) target.id = 'hu-main';
        var link = document.createElement('a');
        link.className = 'hu-skip';
        link.href = '#' + target.id;
        link.textContent = '跳到主内容';
        document.body.insertBefore(link, document.body.firstChild);
    }

    /* ---------- K08 图片兜底：占位 + 有限次重试 ---------- */
    var IMG_FALLBACK = '/img/product-placeholder.svg';

    function imgPlaceholderFor(img) {
        return attr(img, 'data-hu-img-fallback', attr(document.documentElement, 'data-hu-img-fallback', IMG_FALLBACK));
    }

    function imgFailed(img) {
        if (img.__huImgDone) return;
        var maxTries = Number(attr(img, 'data-hu-img-retry', '1'));
        var tries = img.__huTries || 0;
        var src = img.__huSrc || img.getAttribute('src') || '';
        if (tries < maxTries && src && src.indexOf(imgPlaceholderFor(img)) < 0) {
            img.__huTries = tries + 1;
            img.__huSrc = src;
            setTimeout(function () {
                img.src = src + (src.indexOf('?') < 0 ? '?' : '&') + 'huRetry=' + (tries + 1);
            }, 600 * (tries + 1));
            return;
        }
        img.__huImgDone = true;
        img.classList.add('is-broken');
        var ph = imgPlaceholderFor(img);
        if (ph && src !== ph) img.src = ph;
        if (!img.hasAttribute('alt')) img.setAttribute('alt', '图片加载失败');
        if (!img.hasAttribute('title')) img.setAttribute('title', '图片加载失败，可刷新页面重试');
    }

    // error 不冒泡，只能在捕获阶段统一接住，页面里就不用再各写一份 onerror
    document.addEventListener('error', function (e) {
        var t = e.target;
        if (t && t.tagName === 'IMG') imgFailed(t);
    }, true);

    function imgScan(scope) {
        (el(scope) || document).querySelectorAll('img').forEach(function (img) {
            if (img.complete && img.naturalWidth === 0) imgFailed(img);
        });
    }

    /* ---------- K18 深色模式变量预留：默认仍为浅色，只有显式切换才生效 ---------- */
    function applyTheme(mode) {
        var applied = mode;
        if (mode === 'auto') {
            applied = (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light';
        }
        document.documentElement.setAttribute('data-hu-theme', applied);
        var meta = document.querySelector('meta[name="color-scheme"]');
        if (meta) meta.setAttribute('content', applied === 'dark' ? 'dark light' : 'light');
        return applied;
    }

    function huSetTheme(mode) {
        store('hu.theme', mode);
        return applyTheme(mode);
    }

    function huTheme() {
        return document.documentElement.getAttribute('data-hu-theme') || 'light';
    }

    function initTheme() {
        var saved = read('hu.theme') || attr(document.documentElement, 'data-hu-theme', 'light');
        applyTheme(saved);
    }

    /* ---------- 声明式扫描：新节点插进来也自动获得上面的能力 ---------- */
    function scan(scope) {
        var root = el(scope) || document;
        forEachMatch(root, '[data-hu-money]', function (node) {
            var raw = attr(node, 'data-hu-money', null);
            var text = raw === null ? (node.textContent || '').trim() : raw;
            var num = Number(String(text).replace(/[^\d.\-]/g, ''));
            if (!isFinite(num)) return;
            node.textContent = huMoney(num, {
                cents: attr(node, 'data-hu-money-cents', '1') !== '0',
                strip: attr(node, 'data-hu-money-strip', '0') === '1'
            });
            node.classList.add('hu-money');
        });
        forEachMatch(root, '[data-hu-time]', upgradeTime);
        forEachMatch(root, '[data-hu-empty]', upgradeEmpty);
        forEachMatch(root, '[data-hu-skeleton]', function (node) {
            if (node.children.length) return;
            huSkeleton.show(node, attr(node, 'data-hu-skeleton', 'lines'), attr(node, 'data-hu-skeleton-count', 3));
        });
        forEachMatch(root, '.hu-mask, [data-hu-dialog]', observeDialog);
        a11yScan(root);
        imgScan(root);
        syncFieldValidity(root);
    }

    var scanPending = null;

    function scanSoon(node) {
        if (scanPending) scanPending.push(node);
        else {
            scanPending = [node];
            setTimeout(function () {
                var list = scanPending;
                scanPending = null;
                list.forEach(function (n) {
                    if (!n || !n.querySelectorAll) return;
                    if (n.nodeType === 1 && !n.isConnected) return;
                    scan(n);
                });
            }, 80);
        }
    }

    function observeDom() {
        new MutationObserver(function (mutations) {
            for (var i = 0; i < mutations.length; i++) {
                var added = mutations[i].addedNodes;
                for (var j = 0; j < added.length; j++) {
                    if (added[j].nodeType === 1 && !added[j].classList.contains('hu-toast')
                        && !added[j].classList.contains('hu-toasts')) {
                        scanSoon(added[j]);
                    }
                }
            }
        }).observe(document.body, {childList: true, subtree: true});
    }

    /* ---------- 启动 ---------- */
    ready(function () {
        initTheme();
        scan(document);
        initDrawer();
        markActiveNav();
        startClock();
        injectSkipLink();
        observeDom();
        // 图片、3D 展台等异步内容会改变可访问名，load 后再补扫一遍
        window.addEventListener('load', function () { a11yScan(document); imgScan(document); });
        if (window.jQuery) window.jQuery(document).on('ajaxSuccess', function () { scanSoon(document); });
    });

    /* ---------- 对外导出 ---------- */
    window.huReducedMotion = reducedMotion;
    window.huScrollTo = function (target) {
        var node = el(target);
        if (node) node.scrollIntoView({behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start'});
    };
    window.huLockScroll = lockScroll;
    window.huUnlockScroll = unlockScroll;
    window.huToast = huToast;
    window.huAnnounce = huAnnounce;
    window.huAbsTime = huAbsTime;
    window.huFromNow = huFromNow;
    window.huTime = huFromNow;
    window.huParseTime = parseTime;
    window.huRefreshTimes = refreshTimes;
    window.huSkeleton = huSkeleton;
    window.huEmpty = huEmpty;
    window.huError = huError;
    window.huRetry = huRetry;
    window.huPager = huPager;
    window.huCopy = huCopy;
    window.huCloseDialog = requestDialogClose;
    window.huOpenDialog = function (mask) {
        var node = el(mask);
        if (node) node.classList.add('is-open');
    };
    window.huTopDialog = topDialog;
    window.huDrawer = initDrawer;
    window.huMarkActiveNav = markActiveNav;
    window.huInitDrawer = initDrawer;
    window.huFocusFirstError = focusFirstError;
    window.huA11yScan = a11yScan;
    window.huScan = scan;
    window.huSetTheme = huSetTheme;
    window.huTheme = huTheme;
    window.huImageFallback = imgFailed;
    window.hu = {
        money: huMoney,
        price: huPrice,
        time: huFromNow,
        absTime: huAbsTime,
        toast: huToast,
        announce: huAnnounce,
        skeleton: huSkeleton,
        empty: huEmpty,
        error: huError,
        retry: huRetry,
        pager: huPager,
        copy: huCopy,
        dialog: {open: window.huOpenDialog, close: requestDialogClose, top: topDialog},
        drawer: initDrawer,
        a11y: {scan: a11yScan, focusFirstError: focusFirstError, announce: huAnnounce},
        theme: {set: huSetTheme, get: huTheme},
        reducedMotion: reducedMotion,
        scan: scan
    };
})(window, document);
