/* 花语轩 · 后台公共行为脚本
 * 1）把 Layui 表格的响应约定对齐后端 Result{code:200,msg,data,count}
 * 2）统一 JSON 请求与 401/403 处理，避免每个页面重复写 error 分支
 */
(function (window) {
    'use strict';

    function initLayuiDefaults() {
        if (!window.layui) return;
        layui.use(['table'], function () {
            // 后端业务码固定 200 表示成功，覆盖 Layui 默认的 code:0
            layui.table.set({
                response: {statusName: 'code', statusCode: 200, msgName: 'msg', dataName: 'data', countName: 'count'},
                parseData: null
            });
        });
    }

    // Layui 的 layer 只在 use 之后才挂到 layui 上，这里统一做延迟解析
    function L() {
        return (window.layui && layui.layer) || window.layer || null;
    }

    function toLogin() {
        var layer = L();
        if (layer && layer.msg) layer.msg('登录状态已失效，正在跳转登录页', {icon: 2, time: 1200}, function () {
            window.location.href = '/auth/login';
        });
        else window.location.href = '/auth/login';
    }

    function toast(msg, type) {
        var layer = L();
        if (layer && layer.msg) layer.msg(msg, {icon: type === 'err' ? 2 : 1});
        else if (window.console) console.log(msg);
    }

    /**
     * options: {url, type, data, ok(res), fail(res), always, silent}
     * data 为对象时非 GET 请求自动序列化 JSON；always 在成功/失败后都会执行，供批量调用汇总结果
     */
    function request(options) {
        if (!window.jQuery) return;
        var settings = {
            url: options.url,
            type: options.type || 'GET',
            dataType: 'json',
            success: function (res) {
                if (res.code === 200) {
                    if (options.ok) options.ok(res);
                    else if (!options.silent) toast(res.msg || '操作成功');
                    return;
                }
                if (!options.silent) toast(res.msg || '操作失败', 'err');
                if (options.fail) options.fail(res);
            },
            error: function (xhr) {
                if (xhr.status === 401 || xhr.status === 403) {
                    toLogin();
                    return;
                }
                if (!options.silent) toast('网络异常，请稍后重试', 'err');
                if (options.fail) options.fail({code: xhr.status, msg: '网络异常'});
            },
            complete: function () {
                if (options.always) options.always();
            }
        };
        if (options.data !== undefined) {
            if (settings.type === 'GET') settings.data = options.data;
            else {
                settings.contentType = 'application/json;charset=UTF-8';
                settings.data = JSON.stringify(options.data);
            }
        }
        jQuery.ajax(settings);
    }

    /**
     * 带确认框的删除：POST 风格统一走 DELETE
     */
    function confirmDelete(message, url, onDone) {
        var layer = L();
        if (!layer) return;
        layer.confirm(message, {icon: 3, title: '请确认'}, function (index) {
            request({
                url: url, type: 'DELETE', silent: true,
                ok: function (res) {
                    layer.close(index);
                    toast(res.msg || '删除成功');
                    if (onDone) onDone(res);
                },
                fail: function (res) { toast(res.msg || '删除失败', 'err'); }
            });
        });
    }

    /** HTML 转义：所有拼进 innerHTML 的动态文本都要过一遍 */
    function esc(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
        });
    }

    /** 站内相对路径或 http(s) 才允许作为图片/链接来源 */
    function safeUrl(url) {
        if (!url) return '';
        if (url.charAt(0) === '/') return url;
        return /^https?:\/\//i.test(url) ? url : '';
    }

    /** 后端 BigDecimal 序列化会丢掉末尾 0（283.1），金额统一补齐两位并加分位符 */
    function money(value) {
        var n = Number(value);
        return isFinite(n) ? n.toLocaleString('zh-CN', {minimumFractionDigits: 2, maximumFractionDigits: 2}) : '0.00';
    }

    function param(name) {
        return new URLSearchParams(window.location.search).get(name);
    }

    /* Layui 表格自己发请求，页码/每页条数只在请求参数里，这里旁路记录一份供列表页恢复用 */
    var lastQueryByUrl = {};

    function captureQuery(options) {
        if ((options.type || 'GET').toUpperCase() !== 'GET') return;
        var data = options.data;
        if (!data) return;
        if (typeof data === 'string') lastQueryByUrl[options.url] = data;
        else if (typeof data === 'object') {
            var pairs = [];
            for (var k in data) {
                if (Object.prototype.hasOwnProperty.call(data, k)) {
                    pairs.push(encodeURIComponent(k) + '=' + encodeURIComponent(data[k] == null ? '' : data[k]));
                }
            }
            lastQueryByUrl[options.url] = pairs.join('&');
        }
    }

    function queryOf(url) {
        var out = {};
        (lastQueryByUrl[url] || '').split('&').forEach(function (pair) {
            if (!pair) return;
            var i = pair.indexOf('=');
            var key = i < 0 ? pair : pair.slice(0, i);
            out[decodeURIComponent(key)] = i < 0 ? '' : decodeURIComponent(pair.slice(i + 1).replace(/\+/g, ' '));
        });
        return out;
    }

    /**
     * 列表页状态记忆（同一 tab 内返回保留页码/每页条数；筛选条件由各页 FILTER_KEY 自行保存）
     *   var state = huAdmin.listState('product-list');
     *   table.render({url: '/api/admin/products', page: state.pageConfig(), done: state.done('/api/admin/products')});
     */
    function listState(key) {
        var storageKey = 'hu-admin-page-' + key;

        function read() {
            try { return JSON.parse(sessionStorage.getItem(storageKey) || '{}'); } catch (e) { return {}; }
        }

        return {
            /** 作为 table.render 的 page 配置；无历史时返回 true 走 Layui 默认分页 */
            pageConfig: function () {
                var curr = Number(read().page) || 0;
                return curr > 1 ? {curr: curr} : true;
            },
            /** 作为 table.render 的 limit：Layui 的每页条数取顶层 limit，不在 page 对象里 */
            limit: function (fallback) {
                return Number(read().limit) || fallback || 10;
            },
            done: function (url, original) {
                return function (res, curr, count) {
                    var q = queryOf(url);
                    try {
                        sessionStorage.setItem(storageKey, JSON.stringify({
                            page: Number(q.page) || Number(curr) || 1,
                            limit: Number(q.limit) || 10
                        }));
                    } catch (e) { /* 隐私模式下 sessionStorage 不可写，忽略 */ }
                    if (original) original.apply(this, arguments);
                };
            },
            reset: function () {
                try { sessionStorage.removeItem(storageKey); } catch (e) { /* 同上 */ }
            }
        };
    }

    window.huAdmin = {
        init: initLayuiDefaults,
        request: request,
        confirmDelete: confirmDelete,
        toast: toast,
        toLogin: toLogin,
        esc: esc,
        money: money,
        safeUrl: safeUrl,
        param: param,
        listState: listState
    };

    if (window.jQuery) jQuery.ajaxPrefilter(captureQuery);

    initLayuiDefaults();
})(window);
