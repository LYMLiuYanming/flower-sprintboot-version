/* ============================================================================
 * home-anim.js · 首页滚动叙事动效驱动（defer，仅首页 body[data-hu-home] 生效）
 *
 * 三件事：
 * 1) 进入视口时揭示（IntersectionObserver，一次性，不再反向隐藏——来回抖动比不 animating 更廉价）；
 * 2) 数字滚动累加（读接口回填的数字要在文本变化后重新计一次，否则永远停在 0）；
 * 3) 指针微倾斜与视差，只写 transform，绝不碰布局属性（避免和入场动效抢同一个 transition）。
 * 动态注入的卡片（分类、榜单）由 MutationObserver 兜住，页面不需要在回调里手动补挂。
 * ==========================================================================*/
(function () {
    'use strict';

    var body = document.body;
    if (!body || !body.hasAttribute('data-hu-home')) return;

    var REDUCE = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var FINE_POINTER = window.matchMedia && window.matchMedia('(pointer: fine)').matches;

    var STEP = 78;          // 组内级联步长（ms）
    var CHAR_STEP = 26;     // 逐字级联步长（ms）
    var revealed = new WeakSet();
    var observed = new WeakSet();

    var io = null;
    if (!REDUCE && 'IntersectionObserver' in window) {
        io = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (!entry.isIntersecting || revealed.has(entry.target)) return;
                show(entry.target);
                io.unobserve(entry.target);
            });
        }, {rootMargin: '0px 0px -10% 0px', threshold: 0.08});
    }

    function show(el) {
        if (revealed.has(el)) return;
        revealed.add(el);
        el.classList.add('is-revealed');
        if (el.hasAttribute('data-hu-count')) countUp(el);
    }

    function reveal(el, index) {
        var group = el.parentNode && el.parentNode.getAttribute('data-hu-anim-group');
        if (group && !el.getAttribute('data-hu-anim')) el.setAttribute('data-hu-anim', group);
        if (group && !el.style.getPropertyValue('--hu-anim-delay')) {
            var step = Number(el.parentNode.getAttribute('data-hu-anim-step')) || STEP;
            var ord = index == null ? ordinal(el) : index;
            el.style.setProperty('--hu-anim-delay', Math.min(ord * step, 900) + 'ms');
        }
        if (REDUCE || !io) {
            show(el);
            return;
        }
        if (observed.has(el)) return;
        observed.add(el);
        io.observe(el);
    }

    function ordinal(el) {
        var i = 0, node = el;
        while (node && node.previousElementSibling) {
            node = node.previousElementSibling;
            i++;
        }
        return i;
    }

    function scan(root) {
        var scope = root && root.querySelectorAll ? root : document;
        var list = [];
        // 带 data-hu-anim 的自声明元素 + 组内子项，两类一起收进观察队列
        collect(scope.querySelectorAll('[data-hu-anim]'), list);
        collect(scope.querySelectorAll('[data-hu-anim-group] > *'), list);
        collect(scope.querySelectorAll('[data-hu-count]'), list);
        if (scope.matches && scope.matches('[data-hu-anim],[data-hu-count]')) list.push(scope);
        list.forEach(function (el) {
            reveal(el, el.parentNode && el.parentNode.hasAttribute('data-hu-anim-group') ? ordinal(el) : null);
        });
        var titles = scope.querySelectorAll ? scope.querySelectorAll('[data-hu-anim="chars"]') : [];
        for (var t = 0; t < titles.length; t++) splitChars(titles[t]);
    }

    function collect(nodes, out) {
        for (var i = 0; i < nodes.length; i++) if (!revealed.has(nodes[i])) out.push(nodes[i]);
    }

    /** 逐字上浮：整段文字改成 aria-label，字符层对读屏隐藏，避免一个字一个字念 */
    function splitChars(el) {
        if (el.dataset.huCharked) return;
        el.dataset.huCharked = '1';
        var text = (el.textContent || '').replace(/\s+/g, ' ').trim();
        if (!text) return;
        el.setAttribute('aria-label', text);
        el.textContent = '';
        var box = document.createElement('span');
        box.className = 'hu-chars';
        box.setAttribute('aria-hidden', 'true');
        Array.prototype.forEach.call(text, function (ch, i) {
            var s = document.createElement('span');
            s.className = 'hu-char';
            s.textContent = ch === ' ' ? ' ' : ch;
            s.style.setProperty('--hu-anim-delay', Math.min(i * CHAR_STEP, 1200) + 'ms');
            box.appendChild(s);
        });
        el.appendChild(box);
    }

    /* ---------------- 数字滚动 ---------------- */
    function countUp(el) {
        if (el.dataset.huCounted) return;
        el.dataset.huCounted = '1';
        var raw = el.getAttribute('data-hu-count-to');
        var suffix = el.getAttribute('data-hu-count-suffix') || '';
        var decimals = Number(el.getAttribute('data-hu-count-decimals') || 0);
        var target;
        if (raw !== null && raw !== '') {
            target = Number(raw);
        } else {
            // 从现有文本里剥出目标值：'12,000,000+' → 12000000，尾随 '+' 归进后缀
            var text = (el.textContent || '').trim();
            var num = text.replace(/[^\d.\-]/g, '');
            target = num === '' ? 0 : parseFloat(num);
            var tail = text.match(/[^\d.,]+$/);
            if (tail && !suffix) suffix = tail[0];
        }
        if (!isFinite(target) || target <= 0 || REDUCE) return;
        var dur = Math.min(2200, 700 + target.toString().replace(/\D/g, '').length * 140);
        var start = 0;
        function frame(now) {
            if (!start) start = now;
            var p = Math.min(1, (now - start) / dur);
            var eased = 1 - Math.pow(1 - p, 3);
            el.textContent = format(target * eased, decimals) + suffix;
            if (p < 1) requestAnimationFrame(frame);
        }
        requestAnimationFrame(frame);
    }

    function format(value, decimals) {
        return value.toLocaleString('en-US', {
            minimumFractionDigits: decimals, maximumFractionDigits: decimals
        });
    }

    /* ---------------- 阅读进度 ---------------- */
    var bar = null;
    if (!REDUCE) {
        bar = document.createElement('div');
        bar.className = 'hu-progress';
        bar.setAttribute('aria-hidden', 'true');
        body.insertBefore(bar, body.firstChild);
    }

    /* ---------------- 视差与滚动同步 ---------------- */
    var layers = Array.prototype.slice.call(document.querySelectorAll('[data-hu-parallax]'));
    var ticking = false;

    function onScroll() {
        if (ticking) return;
        ticking = true;
        requestAnimationFrame(function () {
            ticking = false;
            var doc = document.documentElement;
            var max = doc.scrollHeight - window.innerHeight;
            if (bar) bar.style.transform = 'scaleX(' + (max > 0 ? Math.min(1, window.pageYOffset / max) : 0) + ')';
            if (REDUCE) return;
            var vh = window.innerHeight;
            layers.forEach(function (el) {
                var rect = el.getBoundingClientRect();
                if (rect.bottom < 0 || rect.top > vh) return;   // 不在视口内就不写样式，省合成开销
                var ratio = Number(el.getAttribute('data-hu-parallax')) || 0.12;
                var offset = (rect.top + rect.height / 2 - vh / 2) * ratio;
                el.style.transform = 'translate3d(0,' + offset.toFixed(2) + 'px,0)';
            });
        });
    }

    window.addEventListener('scroll', onScroll, {passive: true});
    window.addEventListener('resize', onScroll, {passive: true});

    /* ---------------- 指针微倾斜 ---------------- */
    function bindTilt(el) {
        if (el.dataset.huTilt) return;
        el.dataset.huTilt = '1';
        if (getComputedStyle(el).position === 'static') el.style.position = 'relative';
        el.classList.add('hu-tilt');
        var glare = document.createElement('span');
        glare.className = 'hu-tilt__glare';
        glare.setAttribute('aria-hidden', 'true');
        el.appendChild(glare);
        var max = Number(el.getAttribute('data-hu-tilt')) || 4;

        el.addEventListener('pointermove', function (e) {
            var r = el.getBoundingClientRect();
            var px = (e.clientX - r.left) / r.width;
            var py = (e.clientY - r.top) / r.height;
            el.classList.add('is-tilting');
            el.style.setProperty('--hu-mx', (px * 100).toFixed(1) + '%');
            el.style.setProperty('--hu-my', (py * 100).toFixed(1) + '%');
            el.style.transform = 'rotateX(' + ((0.5 - py) * max * 2).toFixed(2) + 'deg) rotateY(' + ((px - 0.5) * max * 2).toFixed(2) + 'deg)';
        });
        el.addEventListener('pointerleave', function () {
            el.classList.remove('is-tilting');
            el.style.transform = '';
        });
    }

    /* ---------------- 启动 ---------------- */
    scan(document);

    if (!REDUCE && FINE_POINTER) {
        document.querySelectorAll('[data-hu-tilt]').forEach(bindTilt);
    }

    // 分类与榜单是 AJAX 注入的，注入后自动补挂揭示与倾斜，页面回调无需关心
    if ('MutationObserver' in window) {
        new MutationObserver(function (records) {
            records.forEach(function (rec) {
                Array.prototype.forEach.call(rec.addedNodes, function (node) {
                    if (node.nodeType !== 1) return;
                    scan(node.parentNode || node);
                    if (!REDUCE && FINE_POINTER) {
                        if (node.hasAttribute && node.hasAttribute('data-hu-tilt')) bindTilt(node);
                        if (node.querySelectorAll) node.querySelectorAll('[data-hu-tilt]').forEach(bindTilt);
                    }
                });
            });
        }).observe(document.body, {childList: true, subtree: true});
    }

    requestAnimationFrame(function () {
        document.querySelector('.hu-hero') && document.querySelector('.hu-hero').classList.add('is-hero-in');
        onScroll();
    });

    // 数字条要等接口回填，回填后重跑一次计数
    window.huAnim = {
        scan: scan,
        recount: function (root) {
            var scope = root && root.querySelectorAll ? root : document;
            scope.querySelectorAll && scope.querySelectorAll('[data-hu-count]').forEach(function (el) {
                delete el.dataset.huCounted;
                if (revealed.has(el)) countUp(el);
            });
        }
    };
})();
