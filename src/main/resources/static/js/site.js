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
        if (bt) window.scrollTo({ top: 0, behavior: 'smooth' });
    });

    // 移动端抽屉 + 购物车角标
    document.addEventListener('DOMContentLoaded', function () {
        var burger = document.getElementById('huMenuBtn');
        var drawer = document.getElementById('huMobileMenu');
        if (burger && drawer) burger.addEventListener('click', function () { drawer.classList.toggle('is-open'); });
        huLoadCartCount();
    });

    // 平滑锚点
    document.querySelectorAll('a[href^="#"]').forEach(function (a) {
        a.addEventListener('click', function (e) {
            var sel = a.getAttribute('href');
            if (sel.length > 1) {
                var t = document.querySelector(sel);
                if (t) { e.preventDefault(); t.scrollIntoView({ behavior: 'smooth' }); }
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
 * options: {url, type, data, ok: 成功回调(res.data,res), fail, always, silent}
 */
function huApi(options) {
    if (!window.jQuery) return;
    var settings = {
        url: options.url,
        type: options.type || 'GET',
        dataType: 'json',
        success: function (res) {
            if (res.code === 200) {
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

/* 去掉浮点尾零，保证 ¥199.00 显示为 199、¥199.50 显示为 199.5 时仍可控 */
function huMoney(value) {
    var n = Number(value);
    return isNaN(n) ? '0.00' : n.toFixed(2);
}

/* 轻量 toast */
(function () {
    var style = document.createElement('style');
    style.textContent = '.hu-toast{position:fixed;left:50%;bottom:40px;transform:translateX(-50%) translateY(20px);' +
        'background:#101623;color:#fff;padding:12px 22px;border-radius:12px;font-size:14px;letter-spacing:.01em;' +
        'box-shadow:0 26px 60px -22px rgba(16,22,35,.5);opacity:0;transition:all .35s cubic-bezier(.4,.08,.24,1);z-index:9999;}' +
        '.hu-toast.show{opacity:1;transform:translateX(-50%) translateY(0);}.hu-toast.err{background:#c15b6b;}';
    document.head.appendChild(style);
    window.huToast = function (msg, type) {
        var el = document.createElement('div');
        el.className = 'hu-toast' + (type === 'err' ? ' err' : '');
        el.textContent = msg;
        document.body.appendChild(el);
        requestAnimationFrame(function () { el.classList.add('show'); });
        setTimeout(function () { el.classList.remove('show'); setTimeout(function () { el.remove(); }, 400); }, 1900);
    };
})();

/**
 * 营销位联动：把「领券中心」的可用券渲染到首页 / 商品详情页的推广条上。
 * options: {chipsId, categoryId?, limit?} —— 传 categoryId 时分类专享券只保留命中的那张。
 */
function huBindCouponPromo(options) {
    var host = document.getElementById(options.chipsId);
    if (!host || !window.jQuery) return;
    huApi({
        url: '/api/coupons/receivable',
        silent: true,
        ok: function (list) {
            var items = (list || []).filter(function (c) {
                return !options.categoryId || c.scope !== 'category' || String(c.categoryId) === String(options.categoryId);
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
