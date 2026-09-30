/**
 * 全站图片增强（J09 放大镜 / J10 全屏浏览 / J11 缩略图、懒加载与失败占位）
 *
 * 声明式接入，页面只加属性、不写脚本：
 *   <img data-hu-img data-hu-thumb="320">                       走 /img/thumb 重采样 + 懒加载 + 坏图占位（J11）
 *   <img data-hu-zoom="3">                                     桌面悬停放大镜；触屏点击改为进全屏（J09）
 *   <div data-hu-gallery> [data-hu-main] + [data-hu-thumbs] </div>  缩略图切换主图（键盘可换）
 *   <img data-hu-viewer data-hu-group="pdp" data-hu-caption=""> 点开全屏浏览，同组前后翻页（J10）
 *
 * 全屏层全站共用一个节点，反复开关不重复建 DOM。键盘：← → 翻页、Home/End 首末、Esc 关闭、
 * +/- 缩放、放大后用方向键平移；触屏支持左右滑动翻页与双指张合缩放。
 */
(function (window, document) {
    'use strict';

    if (window.huMedia) {
        return;
    }

    var PLACEHOLDER = '/img/placeholder.svg';
    var THUMB_API = '/img/thumb';
    var viewer = null;
    var state = { items: [], index: 0, scale: 1, x: 0, y: 0, lastFocus: null };

    /**
     * 拿到图片真正的全分辨率地址：
     * data-hu-full 优先，其次记录在 __huFullSrc 上的原图，最后从 /img/thumb?path= 里反解出来
     */
    function fullSrc(el) {
        var explicit = el.getAttribute && el.getAttribute('data-hu-full');
        if (explicit) {
            return explicit;
        }
        if (el.__huFullSrc) {
            return el.__huFullSrc;
        }
        var src = (el.getAttribute && el.getAttribute('src')) || el.src || '';
        if (src.indexOf(THUMB_API) === 0) {
            var match = /[?&]path=([^&]+)/.exec(src);
            if (match) {
                try {
                    return decodeURIComponent(match[1]);
                } catch (err) {
                    return src;
                }
            }
        }
        return src;
    }

    /** 与服务端 MediaController 同一份白名单，客户端先筛掉必然 400 的请求（图标、脚本等） */
    var THUMB_ALLOWED_PREFIX = ['/img/photos/', '/img/products/', '/img/banners/'];
    var THUMB_ALLOWED_FILES = ['/img/placeholder.svg', '/img/product-placeholder.svg', '/img/favicon.svg'];

    function thumbable(src) {
        if (!src || src.charAt(0) !== '/') {
            return false;
        }
        for (var i = 0; i < THUMB_ALLOWED_PREFIX.length; i++) {
            if (src.indexOf(THUMB_ALLOWED_PREFIX[i]) === 0) {
                return true;
            }
        }
        return THUMB_ALLOWED_FILES.indexOf(src) !== -1;
    }

    function thumbUrl(src, width) {
        if (!src || !thumbable(src) || src.indexOf(THUMB_API) === 0) {
            return src;
        }
        return THUMB_API + '?path=' + encodeURIComponent(src) + '&w=' + Math.round(width);
    }

    // ------------------------------------------------------------------ J11

    function markBroken(img) {
        if (img.classList.contains('is-broken')) {
            return;
        }
        img.classList.add('is-broken');
        img.setAttribute('data-hu-origin', img.getAttribute('data-hu-origin') || img.getAttribute('src') || '');
        img.src = img.getAttribute('data-hu-placeholder') || PLACEHOLDER;
        if (img.parentElement) {
            img.parentElement.classList.add('hu-img-broken');
        }
    }

    function enhanceImg(img) {
        if (img.__huImgDone) {
            return;
        }
        img.__huImgDone = true;
        img.setAttribute('loading', img.getAttribute('loading') || 'lazy');
        img.setAttribute('decoding', 'async');
        if (!img.hasAttribute('alt')) {
            img.setAttribute('alt', img.getAttribute('data-hu-alt') || '');
        }

        var base = img.getAttribute('src') || '';
        img.__huFullSrc = img.getAttribute('data-hu-full') || base;
        var width = parseInt(img.getAttribute('data-hu-thumb') || '0', 10);
        // 小屏直接拉小图，2x 档交给 srcset，省下来的字节列表页是成倍收益
        if (width > 0 && base) {
            var one = thumbUrl(base, width);
            var two = thumbUrl(base, Math.min(width * 2, 1600));
            img.setAttribute('src', one);
            if (two !== one) {
                img.setAttribute('srcset', one + ' 1x, ' + two + ' 2x');
            }
        }

        var deferred = img.getAttribute('data-hu-src');
        if (deferred) {
            if ('IntersectionObserver' in window) {
                var io = new IntersectionObserver(function (entries) {
                    entries.forEach(function (entry) {
                        if (!entry.isIntersecting) {
                            return;
                        }
                        io.disconnect();
                        img.removeAttribute('data-hu-src');
                        img.__huFullSrc = img.getAttribute('data-hu-full') || deferred;
                        img.src = deferred;
                    });
                }, { rootMargin: '300px 0px' });
                io.observe(img);
            } else {
                img.removeAttribute('data-hu-src');
                img.src = deferred;
            }
        }

        img.addEventListener('error', function () {
            markBroken(img);
        });
        img.addEventListener('load', function () {
            img.classList.remove('is-broken');
            if (img.parentElement) {
                img.parentElement.classList.remove('hu-img-broken');
            }
        });
        // 脚本比图片慢半拍时 error 已经错过，用 complete + naturalWidth 补判一次
        if (img.complete && img.naturalWidth === 0 && base) {
            markBroken(img);
        }
        // 坏图不留空洞：点一下原地重试（带时间戳绕过 404 缓存）
        img.addEventListener('click', function () {
            if (!img.classList.contains('is-broken')) {
                return;
            }
            var origin = img.getAttribute('data-hu-origin');
            if (!origin) {
                return;
            }
            img.classList.remove('is-broken');
            if (img.parentElement) {
                img.parentElement.classList.remove('hu-img-broken');
            }
            img.src = origin + (origin.indexOf('?') === -1 ? '?' : '&') + 'retry=' + Date.now();
        }, true);
    }

    // ------------------------------------------------------------------ J09

    function finePointer() {
        return !(window.matchMedia && window.matchMedia('(hover: none)').matches);
    }

    function bindZoom(img) {
        if (img.__huZoomDone) {
            return;
        }
        img.__huZoomDone = true;
        var host = img.parentElement;
        if (!host) {
            return;
        }
        host.classList.add('hu-zoomable');
        if (!finePointer()) {
            // 触屏没有悬停语义，放大镜改成「点一下进全屏看大图」，比一个跟不上的圆圈有用
            host.classList.add('is-tap-zoom');
            return;
        }
        var lens = document.createElement('span');
        lens.className = 'hu-zoom-lens';
        lens.hidden = true;
        lens.setAttribute('aria-hidden', 'true');
        host.appendChild(lens);

        var zoom = Math.max(1.6, Math.min(6, parseFloat(img.getAttribute('data-hu-zoom') || '2.6')));

        function hide() {
            lens.hidden = true;
            host.classList.remove('is-zooming');
        }

        function move(event) {
            var rect = img.getBoundingClientRect();
            var x = event.clientX - rect.left;
            var y = event.clientY - rect.top;
            if (x < 0 || y < 0 || x > rect.width || y > rect.height) {
                hide();
                return;
            }
            var size = Math.max(96, Math.min(180, rect.width / 3));
            lens.hidden = false;
            lens.style.width = size + 'px';
            lens.style.height = size + 'px';
            lens.style.left = (x - size / 2) + 'px';
            lens.style.top = (y - size / 2) + 'px';
            lens.style.backgroundImage = 'url("' + fullSrc(img) + '")';
            lens.style.backgroundSize = (rect.width * zoom) + 'px ' + (rect.height * zoom) + 'px';
            lens.style.backgroundPosition = (-(x * zoom - size / 2)) + 'px ' + (-(y * zoom - size / 2)) + 'px';
            host.classList.add('is-zooming');
        }

        host.addEventListener('pointermove', move);
        host.addEventListener('pointerleave', hide);
        host.addEventListener('pointerup', hide);
        img.title = img.getAttribute('data-hu-hint') || '悬停放大细节，点击看全屏';
    }

    // ------------------------------------------------------------------ J10

    /** 同组元素：显式 data-hu-group 按名字收，否则就近在图册/展台容器里收 */
    function collectGroup(el) {
        var name = el.getAttribute('data-hu-group');
        var list;
        if (name) {
            list = document.querySelectorAll('[data-hu-group="' + name + '"]');
        } else {
            var scope = el.closest('[data-hu-gallery], [data-hu-stage], .hu-pdp, section') || document;
            // 缩略图本身也算一组：点主图进全屏后可以顺着缩略图的顺序翻页
            list = scope.querySelectorAll('[data-hu-viewer], [data-hu-thumbs] img');
        }
        var items = Array.prototype.slice.call(list);
        if (items.indexOf(el) === -1) {
            items.unshift(el);
        }
        return items;
    }

    function ensureViewer() {
        if (viewer) {
            return viewer;
        }
        viewer = document.createElement('div');
        viewer.className = 'hu-viewer';
        viewer.hidden = true;
        viewer.tabIndex = -1;
        viewer.setAttribute('role', 'dialog');
        viewer.setAttribute('aria-modal', 'true');
        viewer.setAttribute('aria-label', '图片全屏浏览');
        viewer.innerHTML = [
            '<div class="hu-viewer__stage" data-hv-stage>',
            '<img class="hu-viewer__img" alt="">',
            '<p class="hu-viewer__hint">双击放大 · 放大后方向键平移 · 左右键翻页 · Esc 关闭</p>',
            '</div>',
            '<p class="hu-viewer__caption" data-hv-caption></p>',
            '<div class="hu-viewer__bar">',
            '<button type="button" class="hu-viewer__nav" data-hv="prev" aria-label="上一张"><i class="fa fa-chevron-left"></i></button>',
            '<span class="hu-viewer__counter" data-hv-counter></span>',
            '<button type="button" class="hu-viewer__nav" data-hv="next" aria-label="下一张"><i class="fa fa-chevron-right"></i></button>',
            '<span class="hu-viewer__gap"></span>',
            '<button type="button" class="hu-viewer__nav" data-hv="out" aria-label="缩小"><i class="fa fa-search-minus"></i></button>',
            '<button type="button" class="hu-viewer__nav" data-hv="in" aria-label="放大"><i class="fa fa-search-plus"></i></button>',
            '<a class="hu-viewer__nav hu-viewer__open" data-hv="open" href="#" target="_blank" rel="noopener" aria-label="在新标签页打开原图"><i class="fa fa-external-link"></i></a>',
            '<button type="button" class="hu-viewer__nav" data-hv="close" aria-label="关闭全屏浏览"><i class="fa fa-times"></i></button>',
            '</div>'
        ].join('');
        document.body.appendChild(viewer);

        viewer.addEventListener('click', function (event) {
            var action = event.target.closest ? event.target.closest('[data-hv]') : null;
            if (!action) {
                if (event.target === viewer || event.target.hasAttribute('data-hv-stage')) {
                    close();
                }
                return;
            }
            var name = action.getAttribute('data-hv');
            if (name === 'prev') {
                step(-1);
            } else if (name === 'next') {
                step(1);
            } else if (name === 'close') {
                close();
            } else if (name === 'in') {
                setScale(state.scale * 1.4);
            } else if (name === 'out') {
                setScale(state.scale / 1.4);
            }
        });
        viewer.addEventListener('dblclick', function (event) {
            if (event.target.classList.contains('hu-viewer__img')) {
                setScale(state.scale > 1 ? 1 : 2.4);
            }
        });
        viewer.addEventListener('keydown', onViewerKey);
        document.addEventListener('keydown', onGlobalKey);
        bindViewerTouch(viewer);
        return viewer;
    }

    function bindViewerTouch(root) {
        var stage = root.querySelector('[data-hv-stage]');
        var touches = {};
        var span = 0;
        var startX = 0;
        var startScale = 1;
        stage.addEventListener('pointerdown', function (event) {
            if (event.pointerType !== 'touch') {
                return;
            }
            touches[event.pointerId] = { x: event.clientX, y: event.clientY };
            startX = event.clientX;
            startScale = state.scale;
            if (Object.keys(touches).length === 2) {
                var list = Object.keys(touches).map(function (key) { return touches[key]; });
                span = Math.max(1, Math.hypot(list[0].x - list[1].x, list[0].y - list[1].y));
            }
        });
        stage.addEventListener('pointermove', function (event) {
            if (event.pointerType !== 'touch' || !touches[event.pointerId]) {
                return;
            }
            touches[event.pointerId] = { x: event.clientX, y: event.clientY };
            var keys = Object.keys(touches);
            if (keys.length === 2 && span > 0) {
                var list = keys.map(function (key) { return touches[key]; });
                var now = Math.max(1, Math.hypot(list[0].x - list[1].x, list[0].y - list[1].y));
                setScale(startScale * (now / span));
                event.preventDefault();
            }
        });
        stage.addEventListener('pointerup', function (event) {
            var only = Object.keys(touches).length === 1;
            delete touches[event.pointerId];
            if (Object.keys(touches).length < 2) {
                span = 0;
            }
            // 单指横滑且已经是最小倍率时按翻页处理，放大状态下不抢平移手势
            if (only && event.pointerType === 'touch' && state.scale <= 1) {
                var dx = event.clientX - startX;
                if (Math.abs(dx) > 60) {
                    step(dx < 0 ? 1 : -1);
                }
            }
        });
        stage.addEventListener('pointercancel', function (event) {
            delete touches[event.pointerId];
            span = 0;
        });
    }

    function onGlobalKey(event) {
        if (!viewer || viewer.hidden) {
            return;
        }
        // 焦点可能落在缩略图等弹层外的元素上，这里兜住键盘翻页与关闭
        switch (event.key) {
            case 'Escape':
                event.preventDefault();
                close();
                break;
            case 'ArrowRight':
                if (state.scale <= 1) {
                    event.preventDefault();
                    step(1);
                }
                break;
            case 'ArrowLeft':
                if (state.scale <= 1) {
                    event.preventDefault();
                    step(-1);
                }
                break;
            case 'Home':
                event.preventDefault();
                show(0);
                break;
            case 'End':
                event.preventDefault();
                show(state.items.length - 1);
                break;
            case '+':
            case '=':
                event.preventDefault();
                setScale(state.scale * 1.3);
                break;
            case '-':
            case '_':
                event.preventDefault();
                setScale(state.scale / 1.3);
                break;
            case 'Tab':
                trapFocus(event);
                break;
            default:
                break;
        }
    }

    function onViewerKey(event) {
        if (state.scale <= 1 || event.key.indexOf('Arrow') !== 0) {
            return;
        }
        var pan = 56;
        if (event.key === 'ArrowUp') state.y += pan;
        else if (event.key === 'ArrowDown') state.y -= pan;
        else if (event.key === 'ArrowLeft') state.x += pan;
        else if (event.key === 'ArrowRight') state.x -= pan;
        clampPan();
        paint();
        // 放大后方向键归平移，别再冒泡去翻页
        event.preventDefault();
        event.stopPropagation();
    }

    function trapFocus(event) {
        var focusables = viewer.querySelectorAll('button, a[href]');
        if (!focusables.length) {
            return;
        }
        var first = focusables[0];
        var last = focusables[focusables.length - 1];
        if (!viewer.contains(document.activeElement)) {
            event.preventDefault();
            first.focus();
        } else if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    }

    function open(el) {
        var items = collectGroup(el);
        ensureViewer();
        state.items = items.map(function (node) {
            return {
                src: fullSrc(node),
                caption: node.getAttribute('data-hu-caption') || node.getAttribute('alt') || '',
                el: node
            };
        });
        state.lastFocus = el;
        viewer.hidden = false;
        document.documentElement.classList.add('hu-viewer-open');
        requestAnimationFrame(function () {
            viewer.classList.add('is-in');
        });
        show(items.indexOf(el));
        var closeBtn = viewer.querySelector('[data-hv="close"]');
        if (closeBtn) {
            closeBtn.focus();
        }
    }

    function show(index) {
        if (!viewer || !state.items.length) {
            return;
        }
        var total = state.items.length;
        state.index = ((index < 0 ? 0 : index) % total + total) % total;
        var item = state.items[state.index];
        var image = viewer.querySelector('.hu-viewer__img');
        state.scale = 1;
        state.x = 0;
        state.y = 0;
        image.src = item.src;
        image.alt = item.caption || '全屏浏览图片 ' + (state.index + 1);
        viewer.querySelector('[data-hv-caption]').textContent = item.caption || '';
        viewer.querySelector('[data-hv-counter]').textContent = (state.index + 1) + ' / ' + total;
        viewer.querySelector('[data-hv="open"]').setAttribute('href', item.src);
        var nav = total < 2 ? 'hidden' : '';
        viewer.querySelector('[data-hv="prev"]').style.visibility = nav;
        viewer.querySelector('[data-hv="next"]').style.visibility = nav;
        paint();
    }

    function step(delta) {
        show(state.index + delta);
    }

    function setScale(value) {
        state.scale = Math.max(1, Math.min(6, value));
        if (state.scale === 1) {
            state.x = 0;
            state.y = 0;
        }
        clampPan();
        paint();
    }

    function clampPan() {
        var bound = 40 * state.scale;
        state.x = Math.max(-bound, Math.min(bound, state.x));
        state.y = Math.max(-bound, Math.min(bound, state.y));
    }

    function paint() {
        var image = viewer.querySelector('.hu-viewer__img');
        image.style.transform = 'translate3d(' + state.x + 'px,' + state.y + 'px,0) scale(' + state.scale + ')';
        viewer.classList.toggle('is-zoomed', state.scale > 1);
    }

    function close() {
        if (!viewer || viewer.hidden) {
            return;
        }
        viewer.classList.remove('is-in');
        viewer.hidden = true;
        document.documentElement.classList.remove('hu-viewer-open');
        if (state.lastFocus && state.lastFocus.focus) {
            state.lastFocus.focus();
        }
    }

    // ------------------------------------------------------------------ 缩略图切主图

    function bindGallery(host) {
        if (host.__huGalleryDone) {
            return;
        }
        host.__huGalleryDone = true;
        var main = host.querySelector('[data-hu-main]');
        var strip = host.querySelector('[data-hu-thumbs]');
        if (!main || !strip) {
            return;
        }
        var thumbs = Array.prototype.slice.call(strip.querySelectorAll('img'));
        thumbs.forEach(function (thumb, index) {
            if (!thumb.hasAttribute('tabindex')) {
                thumb.tabIndex = 0;
            }
            thumb.setAttribute('role', 'button');
            thumb.setAttribute('aria-label', '查看第 ' + (index + 1) + ' 张实拍图');
            thumb.addEventListener('click', function () {
                pick(main, thumbs, thumb);
            });
            thumb.addEventListener('keydown', function (event) {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    pick(main, thumbs, thumb);
                } else if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
                    event.preventDefault();
                    var next = (index + (event.key === 'ArrowRight' ? 1 : -1) + thumbs.length) % thumbs.length;
                    thumbs[next].focus();
                    pick(main, thumbs, thumbs[next]);
                }
            });
        });
    }

    function pick(main, thumbs, thumb) {
        var full = fullSrc(thumb);
        if (!full) {
            return;
        }
        var width = parseInt(main.getAttribute('data-hu-thumb') || '0', 10);
        main.__huFullSrc = full;
        main.setAttribute('data-hu-full', full);
        main.src = width ? thumbUrl(full, width) : full;
        if (thumb.hasAttribute('data-hu-caption')) {
            main.setAttribute('data-hu-caption', thumb.getAttribute('data-hu-caption'));
            var line = main.parentElement ? main.parentElement.querySelector('[data-hu-shot-caption]') : null;
            if (line) {
                line.textContent = thumb.getAttribute('data-hu-caption');
            }
        }
        thumbs.forEach(function (node) {
            node.classList.toggle('is-active', node === thumb);
            node.setAttribute('aria-current', node === thumb ? 'true' : 'false');
        });
    }

    // ------------------------------------------------------------------ 启动

    function scan(root) {
        var scope = root || document;
        var selector = '[data-hu-img], [data-hu-gallery] img, [data-hu-zoom] img, img[data-hu-zoom], img[data-hu-viewer]';
        Array.prototype.forEach.call(scope.querySelectorAll(selector), enhanceImg);
        if (scope.nodeType === 1 && scope.matches('[data-hu-img]')) {
            enhanceImg(scope);
        }
        Array.prototype.forEach.call(scope.querySelectorAll('img[data-hu-zoom], [data-hu-zoom] > img'), bindZoom);
        if (scope.nodeType === 1 && scope.matches('[data-hu-zoom]')) {
            bindZoom(scope);
        }
        Array.prototype.forEach.call(scope.querySelectorAll('[data-hu-gallery]'), bindGallery);
        if (scope.nodeType === 1 && scope.matches('[data-hu-gallery]')) {
            bindGallery(scope);
        }
    }

    document.addEventListener('click', function (event) {
        var target = event.target.closest ? event.target.closest('[data-hu-viewer]') : null;
        if (!target) {
            return;
        }
        // 触屏放大镜的落点就是全屏（J09 与 J10 在移动端合成一个动作）
        event.preventDefault();
        open(target);
    });

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () { scan(); }, { once: true });
    } else {
        scan();
    }

    window.huMedia = {
        scan: scan,
        enhance: enhanceImg,
        zoom: bindZoom,
        open: open,
        close: close,
        thumbUrl: thumbUrl,
        fullSrc: fullSrc
    };
})(window, document);
