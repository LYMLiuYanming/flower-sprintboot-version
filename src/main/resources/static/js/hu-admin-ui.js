/* 花语轩 · 后台列表与表单通用行为（G 组）
 * 收口几件在各后台页重复出现的事：
 *   G02 列自定义显示/隐藏（记住到 localStorage，服务端接口不受影响）
 *   G03 表头排序折叠成服务端 sort/order 参数
 *   G29 分页大小可选 + 页码跳转
 *   G30 列表空态/错误态统一（优先复用 site.js 的 huEmpty / huError）
 *   G31 服务端校验错误定位到具体字段
 *   G32 危险操作二次确认要点名后果
 * 依赖：layui（table/form/layer）+ jQuery；缺失时相应能力自动降级，不抛错。
 */
(function (window) {
    'use strict';

    var PAGE_LIMITS = [10, 20, 30, 50, 100];
    var NONE_CLASS = 'layui-none';
    var retryHooks = {};
    var clearOnInputBound = false;

    function L() {
        return (window.layui && layui.layer) || null;
    }

    function T() {
        return (window.layui && layui.table) || null;
    }

    function esc(value) {
        if (window.huAdmin && window.huAdmin.esc) return window.huAdmin.esc(value);
        return value === null || value === undefined ? '' : String(value);
    }

    function store(key, value) {
        try {
            if (value === undefined) window.localStorage.removeItem(key);
            else window.localStorage.setItem(key, value);
        } catch (e) { /* 隐私模式写不进去：本次会话仍然生效，不该因此打断操作 */ }
    }

    function readHidden(storageKey) {
        try {
            var list = JSON.parse(window.localStorage.getItem('hu-admin-cols-' + storageKey) || '[]');
            return Array.isArray(list) ? list : [];
        } catch (e) { return []; }
    }

    /* ============================ G29 分页 ============================ */

    /**
     * table.render 的 page 配置：页码条固定带「每页条数」下拉与跳页输入框。
     * 与 huAdmin.listState 共用记忆器，从详情页返回列表时停在原来那一页。
     */
    function pageConfig(state, options) {
        var o = options || {};
        var limits = o.limits || PAGE_LIMITS;
        var base = state ? state.pageConfig() : true;
        var cfg = (base && typeof base === 'object') ? base : {};
        cfg.limits = limits;
        cfg.limit = Number(cfg.limit) || (state ? state.limit(limits[0]) : limits[0]);
        cfg.groups = o.groups || 5;
        cfg.layout = o.layout || ['count', 'prev', 'page', 'next', 'limit', 'skip'];
        cfg.theme = o.theme || '#6f7d5c';
        return cfg;
    }

    function reload(tableId, options) {
        var table = T();
        if (table) table.reload(tableId, options || {});
    }

    /* ====================== G03 表头排序 → 查询串 ====================== */

    /**
     * 折叠成服务端认识的 sort/order：map 用来把列字段名翻译成后台排序键
     * （例如 rating → ratingDesc），没有映射的字段按原名透传，非法键由服务端回落默认序
     */
    function sortParams(sort, map) {
        if (!sort || !sort.field || !sort.type) return {};
        var key = (map && map[sort.field]) || sort.field;
        return {sort: key, order: sort.type === 'asc' ? 'asc' : 'desc'};
    }

    /* ======================= G30 空态 / 错误态 ======================= */

    /**
     * 找到表格视图：优先按 lay-id（Layui 会把表格 id 写在视图上），
     * 退化时按原 table 元素的兄弟节点找——后台每屏只有一个主表格，兜底足够可靠
     */
    function viewOf(tableId) {
        var view = document.querySelector('.layui-table-view[lay-id="' + tableId + '"]');
        if (view) return view;
        var elem = document.getElementById(tableId);
        if (!elem) return null;
        view = elem.closest ? elem.closest('.layui-table-view') : null;
        if (view) return view;
        return (elem.parentNode || document).querySelector('.layui-table-view');
    }

    function mainOf(tableId) {
        var view = viewOf(tableId);
        return view ? view.querySelector('.layui-table-main') : null;
    }

    function clearNone(tableId) {
        var main = mainOf(tableId);
        if (!main) return;
        var old = main.querySelectorAll('.' + NONE_CLASS);
        for (var i = 0; i < old.length; i++) old[i].parentNode.removeChild(old[i]);
    }

    function setNone(tableId, html) {
        var main = mainOf(tableId);
        if (!main) return;
        clearNone(tableId);
        var div = document.createElement('div');
        div.className = NONE_CLASS + ' fg-tablenone';
        div.setAttribute('data-fg-table', tableId);
        div.innerHTML = html;
        main.appendChild(div);
    }

    function emptyHtml(kind, options) {
        var o = options || {};
        if (window.huEmpty && window.huEmpty.html) {
            return '<div class="hu-empty fg-empty">' + window.huEmpty.html(kind, {
                icon: o.icon, title: o.title, desc: o.desc,
                actionText: o.actionText, actionHref: o.actionHref
            }) + '</div>';
        }
        return '<div class="fg-empty"><strong>' + esc(o.title || '没有匹配的记录') + '</strong>'
            + '<span>' + esc(o.desc || '换个条件再看看') + '</span></div>';
    }

    function errorHtml(message) {
        var msg = message || '列表加载失败';
        if (window.huError && window.huError.html) {
            return '<div class="fg-errwrap">' + window.huError.html({
                message: msg, hint: '网络或服务端刚才没响应，点重试再看一次。', retryText: '重新加载'
            }) + '</div>';
        }
        return '<div class="fg-errstate"><strong>' + esc(msg) + '</strong>'
            + '<button type="button" class="layui-btn layui-btn-sm layui-btn-primary">重新加载</button></div>';
    }

    /** 有筛选条件的空和本来就没数据是两种下一步，文案要分开说 */
    function showEmpty(tableId, options) {
        var o = options || {};
        var filtered = !!o.hasFilter;
        setNone(tableId, emptyHtml(filtered ? 'filter' : 'default', {
            icon: filtered ? 'fa-filter' : 'fa-inbox',
            title: filtered ? '没有符合当前条件的记录' : '这里还没有数据',
            desc: filtered ? '放宽一两个条件，或点「重置」清空筛选。' : '新增一条记录，或换个筛选条件看看。'
        }));
    }

    function showError(tableId, message, retry) {
        retryHooks[tableId] = retry;
        setNone(tableId, errorHtml(message));
    }

    if (window.jQuery) {
        jQuery(document).on('click', '.fg-tablenone button', function () {
            var host = this.closest ? this.closest('.fg-tablenone') : null;
            var key = host && host.getAttribute('data-fg-table');
            var fn = key && retryHooks[key];
            if (typeof fn === 'function') fn();
        });
    }

    /**
     * 把 done / error 接到状态机上。options: {url, state, hasFilter}
     * state 是 huAdmin.listState() 的返回值——页码记忆仍由它负责，这里只叠加状态展示
     */
    function tableHooks(tableId, options) {
        var o = options || {};
        var remember = o.state ? o.state.done(o.url, function () {}) : null;
        return {
            done: function (res, curr, count) {
                if (remember) remember(res, curr, count);
                if (res && res.code && res.code !== 200) {
                    showError(tableId, res.msg || '列表加载失败', function () { reload(tableId); });
                    return;
                }
                if (Number(count) > 0) { clearNone(tableId); return; }
                showEmpty(tableId, {hasFilter: !!(o.hasFilter && o.hasFilter())});
            },
            error: function (xhr, msg) {
                showError(tableId, msg || '网络异常，列表没有取回来', function () { reload(tableId); });
            }
        };
    }

    /* ========================= G02 列自定义 ========================= */

    /** 渲染前套用上次隐藏的列：列定义由页面声明，接口字段一个都不少，只是不显示 */
    function applyHidden(cols, storageKey) {
        var hidden = readHidden(storageKey);
        (cols || []).forEach(function (col) {
            if (col.field && hidden.indexOf(col.field) >= 0) col.hide = true;
        });
        return cols;
    }

    function pickable(cols) {
        return (cols || []).filter(function (col) { return col.field && col.title; });
    }

    /**
     * 列设置面板（G02）：显隐状态唯一来源是 localStorage，重开面板不会和上次不一致。
     * 只改前端列的显示，不改请求参数——导出与分页仍以服务端为准。
     */
    function openColumnPicker(tableId, cols, storageKey) {
        var layer = L();
        var table = T();
        if (!layer || !table) return;
        var items = pickable(cols);
        var hidden = readHidden(storageKey);
        var html = '<div class="fg-colpick">'
            + '<div class="fg-colpick__head"><span>勾选要显示的列（共 ' + items.length + ' 列）</span>'
            + '<span class="fg-colpick__quick"><a href="javascript:;" data-fg-all="1">全选</a>'
            + '<a href="javascript:;" data-fg-all="0">全不选</a></span></div>'
            + '<div class="fg-colpick__grid">'
            + items.map(function (col) {
                return '<label class="fg-colpick__item"><input type="checkbox" data-field="' + esc(col.field)
                    + '"' + (hidden.indexOf(col.field) >= 0 ? '' : ' checked') + '><span>'
                    + esc(col.title) + '</span></label>';
            }).join('')
            + '</div>'
            + '<div class="fg-colpick__foot">设置只对本机浏览器生效，不影响他人，也不改变导出的字段。</div></div>';

        layer.open({
            type: 1, title: '列设置', area: ['480px', 'auto'], content: html,
            btn: ['应用', '取消'],
            success: function (layero) {
                jQuery(layero).on('click', '[data-fg-all]', function () {
                    jQuery(layero).find('input[data-field]').prop('checked',
                        this.getAttribute('data-fg-all') === '1');
                });
            },
            yes: function (index, layero) {
                var next = [];
                jQuery(layero).find('input[data-field]').each(function () {
                    if (!this.checked) next.push(this.getAttribute('data-field'));
                });
                table.hideCol(tableId, items.map(function (col) {
                    return {field: col.field, hide: next.indexOf(col.field) >= 0};
                }));
                store('hu-admin-cols-' + storageKey, JSON.stringify(next));
                layer.close(index);
                layer.msg('列显示已更新，下次进入仍保持', {icon: 1});
            }
        });
    }

    /* ====================== G31 字段级校验错误 ====================== */

    function rootOf(scope) {
        return scope ? jQuery(scope) : jQuery(document);
    }

    function clearFields(scope) {
        var root = rootOf(scope);
        root.find('.fg-fielderr').remove();
        root.find('.layui-form-danger').removeClass('layui-form-danger');
        root.find('[aria-invalid]').removeAttr('aria-invalid');
        root.find('.fg-formerr').remove();
        if (!clearOnInputBound && window.jQuery) {
            clearOnInputBound = true;
            // 改完就撤掉标记，否则红边会一直挂着，运营分不清哪条已经修好
            jQuery(document).on('input change', '.layui-form-danger', function () {
                var input = jQuery(this);
                input.removeClass('layui-form-danger').removeAttr('aria-invalid');
                input.closest('.layui-input-block, .layui-input-inline').find('.fg-fielderr').remove();
            });
        }
    }

    /**
     * 错误钉在字段上：红边 + 就近文案 + aria-invalid，并返回输入节点供调用方聚焦
     */
    function markField(scope, name, message) {
        var input = rootOf(scope).find('[name="' + name + '"]').first();
        if (!input.length) return null;
        input.addClass('layui-form-danger').attr('aria-invalid', 'true');
        var anchor = input.closest('.layui-input-block, .layui-input-inline');
        if (!anchor.length) anchor = input.parent();
        if (!anchor.find('.fg-fielderr').length) {
            anchor.append('<p class="fg-fielderr" role="alert"></p>');
        }
        if (message) anchor.find('.fg-fielderr').text(message);
        return input;
    }

    /**
     * 服务端中文校验语料 → 字段：rules = [{field:'price', test:/售价|价格/}]。
     * 一条都没对上时退化成表单顶部横幅（仍然不是 toast），保证错误文案一定看得见
     */
    function applyServerErrors(scope, res, rules) {
        var msg = (res && res.msg) ? String(res.msg) : '提交没有成功，请稍后重试';
        clearFields(scope);
        var first = null;
        jQuery.each(msg.split('；'), function (_, text) {
            var value = jQuery.trim(text);
            if (!value) return;
            var field = null;
            jQuery.each(rules || [], function (__, rule) {
                if (rule.test.test(value)) { field = rule.field; return false; }
            });
            if (!field) return;
            var node = markField(scope, field, value);
            if (node && !first) first = node;
        });
        if (first) {
            try { first[0].focus({preventScroll: true}); } catch (e) { first[0].focus(); }
            return true;
        }
        var root = rootOf(scope);
        var form = root.is('form') ? root
            : (root.find('form').length ? root.find('form').first() : root.closest('form'));
        if (form.length) form.first().prepend('<div class="fg-formerr" role="alert">' + esc(msg) + '</div>');
        else if (L()) L().msg(msg, {icon: 2});
        return false;
    }

    /* ======================= G32 危险操作确认 ======================= */

    /**
     * options: {title, subject, consequences[], note, confirmText, requireAck, onOk}
     * 与 layer.confirm 的分别就在这儿：后果逐条写出来并勾选「我已了解」才放行，
     * 删除、批量改价这种按下去回不去的操作不该只有一句「确定吗」
     */
    function confirmDanger(options) {
        var o = options || {};
        var layer = L();
        if (!layer) { if (o.onOk) o.onOk(); return; }
        var list = (o.consequences || []).filter(Boolean);
        var ack = o.requireAck !== false;
        var html = '<div class="fg-danger">'
            + '<p class="fg-danger__subject">' + esc(o.subject || '此操作不可撤销') + '</p>'
            + (list.length ? '<ul class="fg-danger__list">' + list.map(function (item) {
                return '<li>' + esc(item) + '</li>';
            }).join('') + '</ul>' : '')
            + (o.note ? '<p class="fg-danger__note">' + esc(o.note) + '</p>' : '')
            + (ack ? '<label class="fg-danger__ack"><input type="checkbox" data-fg-ack="1">'
                + '<span>我已了解上述影响，确认继续</span></label>' : '')
            + '</div>';
        layer.open({
            type: 1, title: o.title || '请再次确认', area: ['500px', 'auto'], content: html,
            btn: [o.confirmText || '确认执行', '取消'],
            yes: function (index, layero) {
                if (ack && !jQuery(layero).find('[data-fg-ack]').prop('checked')) {
                    layer.msg('请先勾选「我已了解上述影响」', {icon: 7});
                    return;
                }
                layer.close(index);
                if (o.onOk) o.onOk();
            }
        });
    }

    /**
     * 批量结果回执：服务端逐条给了成败与原因，页面就逐条列出来，
     * 只报「成功 N 条」等于让运营以为剩下的也都改好了
     */
    function batchReceipt(res, retry) {
        var layer = L();
        if (!layer) return;
        var data = (res && res.data) || {};
        var items = data.items || [];
        var failed = items.filter(function (item) { return !item.ok; });
        var rows = items.map(function (item) {
            return '<tr><td>' + esc(item.name || item.id || '-') + '</td><td>'
                + (item.ok ? '<span class="fg-ok">✓ ' + esc(item.reason || '已处理') + '</span>'
                    : '<span class="fg-bad">✕ ' + esc(item.reason || '失败') + '</span>') + '</td></tr>';
        }).join('');
        layer.open({
            type: 1,
            title: failed.length
                ? ('批量结果：' + (data.succeeded || 0) + ' 条完成，' + (data.failed || 0) + ' 条未成功')
                : ('批量结果：' + (data.succeeded || 0) + ' 条完成'),
            area: ['560px', 'auto'],
            content: '<div class="fg-receipt"><table class="layui-table" lay-size="sm"><tbody>'
                + rows + '</tbody></table>'
                + (failed.length ? '<p class="fg-receipt__tip">未成功的记录没有被部分写入，修正原因后可重新执行。</p>'
                    : '') + '</div>',
            btn: failed.length && retry ? ['重试失败项', '知道了'] : ['知道了'],
            yes: function (index) {
                layer.close(index);
                if (failed.length && retry) retry(failed);
            }
        });
    }

    window.huAdminUI = {
        PAGE_LIMITS: PAGE_LIMITS,
        pageConfig: pageConfig,
        reload: reload,
        sortParams: sortParams,
        tableHooks: tableHooks,
        showEmpty: showEmpty,
        showError: showError,
        clearNone: clearNone,
        applyHidden: applyHidden,
        openColumnPicker: openColumnPicker,
        clearFields: clearFields,
        markField: markField,
        applyServerErrors: applyServerErrors,
        confirmDanger: confirmDanger,
        batchReceipt: batchReceipt,
        emptyHtml: emptyHtml
    };
})(window);
