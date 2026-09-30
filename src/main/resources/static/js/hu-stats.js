/* 花语轩 · 看板与报表前端（H 组）
 * 1）统一时间范围状态（H01）与图表渲染，index.html 与 report.html 共用一份
 * 2）图表一律用站内已有的 ECharts（/js/echarts.min.js），不引任何外网依赖
 * 3）环比异常高亮（H14）与「数据更新于 HH:mm」（H13）在这里出，页面只负责容器
 */
(function (window) {
    'use strict';

    var charts = {};

    function num(v) {
        var n = Number(v);
        return isFinite(n) ? n : 0;
    }

    function money(v, digits) {
        var fix = digits === 0 ? 0 : 2;
        return '¥' + num(v).toLocaleString('zh-CN', {minimumFractionDigits: fix, maximumFractionDigits: fix});
    }

    function int(v) {
        return num(v).toLocaleString('zh-CN', {maximumFractionDigits: 0});
    }

    /** 小时数换成人能读的口径：鲜花配送看「天」比看「27.5 小时」直观 */
    function hours(v) {
        var n = num(v);
        if (n <= 0) return '—';
        if (n < 24) return n.toFixed(1) + ' 小时';
        return (n / 24).toFixed(1) + ' 天';
    }

    /** changeRate 为 null 表示上期无数据，必须与「持平」区分开 */
    function rateText(rate) {
        if (rate === null || rate === undefined) return '—';
        var pct = (num(rate) * 100).toFixed(1);
        return (num(rate) >= 0 ? '+' : '') + pct + '%';
    }

    function deltaHtml(delta, suffix) {
        if (!delta) return '';
        var level = delta.level || 'flat';
        var cls = level === 'down' ? 'is-down' : level === 'up' ? 'is-up' : '';
        return '<span class="h-delta ' + cls + '">' + rateText(delta.changeRate)
            + (suffix ? ' <em>' + suffix + '</em>' : '') + '</span>';
    }

    /** 异常标记条（H14）：只在真的有异常时占版面 */
    function anomalyBar(list) {
        if (!list || !list.length) {
            return '<div class="h-alert h-alert--ok"><i class="fas fa-check-circle"></i> 关键指标环比平稳，未触发异常阈值</div>';
        }
        var html = '';
        for (var i = 0; i < list.length; i++) {
            var a = list[i];
            var danger = a.level === 'down';
            html += '<div class="h-alert ' + (danger ? 'h-alert--danger' : 'h-alert--warn') + '">'
                + '<i class="fas ' + (danger ? 'fa-arrow-trend-down' : 'fa-arrow-trend-up') + '"></i> '
                + '<b>' + esc(a.metric) + '</b><span>' + esc(a.text) + '</span></div>';
        }
        return html;
    }

    function esc(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
        });
    }

    /* ── 图表容器：先 dispose 再 init，容器里可能残留占位文案 ── */
    function mount(id) {
        var el = typeof id === 'string' ? document.getElementById(id) : id;
        if (!el || !window.echarts) return null;
        if (charts[el.id]) charts[el.id].dispose();
        var jq = window.jQuery(el);
        jq.removeClass('h-chart--empty').empty();
        el.style.height = '';
        charts[el.id] = window.echarts.init(el);
        return charts[el.id];
    }

    function empty(id, text) {
        var el = typeof id === 'string' ? document.getElementById(id) : id;
        if (!el) return;
        if (charts[el.id]) {
            charts[el.id].dispose();
            delete charts[el.id];
        }
        window.jQuery(el).addClass('h-chart--empty').text(text || '暂无数据');
    }

    function resizeAll() {
        for (var k in charts) {
            if (charts[k] && !charts[k].isDisposed()) charts[k].resize();
        }
    }

    var INK = '#101623', MUTED = '#8d97a8', LINE = '#eef0f4', ACCENT = '#0f7b63', GOLD = '#c08a33', RED = '#c53030';
    var AXIS_LABEL = {color: MUTED, fontSize: 11.5};
    var TOOLTIP = {
        backgroundColor: 'rgba(255,255,255,.98)', borderColor: LINE, borderWidth: 1, padding: [10, 14],
        textStyle: {color: INK, fontSize: 12.5},
        extraCssText: 'box-shadow:0 12px 30px -18px rgba(16,22,35,.4);border-radius:10px;'
    };

    /* H02：成交额折线 + 单量柱，双 Y 轴；客单价只进 tooltip，避免和单量抢同一条轴 */
    function renderTrend(id, rows) {
        if (!window.echarts) return empty(id, '图表组件未加载');
        if (!rows || !rows.length) return empty(id);
        var chart = mount(id);
        var labels = [], amounts = [], counts = [], avgs = [];
        for (var i = 0; i < rows.length; i++) {
            var r = rows[i];
            labels.push(shortLabel(r.label));
            amounts.push(num(r.amount));
            counts.push(num(r.orders));
            avgs.push(r.avgOrderAmount === null || r.avgOrderAmount === undefined ? null : num(r.avgOrderAmount));
        }
        chart.setOption({
            color: [ACCENT, '#d6e7e1'],
            tooltip: Object.assign({trigger: 'axis', axisPointer: {type: 'cross', crossStyle: {color: '#c9d1dd'}}}, TOOLTIP, {
                formatter: function (params) {
                    var idx = params[0].dataIndex;
                    var html = '<div style="font-weight:600;margin-bottom:6px">' + params[0].axisValue + '</div>';
                    for (var i = 0; i < params.length; i++) {
                        var p = params[i];
                        var val = p.seriesName === '订单量' ? p.value + ' 单' : money(p.value);
                        html += '<div style="display:flex;align-items:center;gap:8px;line-height:20px">' + p.marker
                            + '<span style="color:' + MUTED + '">' + p.seriesName + '</span>'
                            + '<b style="margin-left:auto;font-variant-numeric:tabular-nums">' + val + '</b></div>';
                    }
                    html += '<div style="display:flex;gap:8px;line-height:20px"><span style="color:' + MUTED + '">客单价</span>'
                        + '<b style="margin-left:auto">' + (avgs[idx] === null ? '—' : money(avgs[idx])) + '</b></div>';
                    return html;
                }
            }),
            legend: {data: ['成交额', '订单量'], left: 0, top: 0, icon: 'roundRect',
                itemWidth: 14, itemHeight: 8, itemGap: 16, textStyle: {color: MUTED, fontSize: 12}},
            grid: {left: 6, right: 6, top: 40, bottom: 2, containLabel: true},
            xAxis: {type: 'category', data: labels, axisLine: {lineStyle: {color: LINE}},
                axisTick: {show: false}, axisLabel: Object.assign({hideOverlap: true}, AXIS_LABEL)},
            yAxis: [
                {type: 'value', axisLine: {show: false}, axisTick: {show: false},
                 axisLabel: Object.assign({formatter: function (v) {return v >= 10000 ? (v / 10000) + '万' : v;}}, AXIS_LABEL),
                 splitLine: {lineStyle: {color: LINE, type: 'dashed'}}},
                {type: 'value', minInterval: 1, axisLine: {show: false}, axisTick: {show: false},
                 axisLabel: AXIS_LABEL, splitLine: {show: false}}
            ],
            series: [
                {name: '订单量', type: 'bar', yAxisIndex: 1, data: counts, barMaxWidth: 16,
                 itemStyle: {color: '#e6efe9', borderRadius: [4, 4, 0, 0]}, emphasis: {itemStyle: {color: '#cfe3db'}}},
                {name: '成交额', type: 'line', smooth: 0.32, showSymbol: false, symbol: 'circle', symbolSize: 6,
                 data: amounts, lineStyle: {width: 2.4}, itemStyle: {color: ACCENT, borderColor: '#fff', borderWidth: 1.5},
                 areaStyle: {color: new window.echarts.graphic.LinearGradient(0, 0, 0, 1, [
                     {offset: 0, color: 'rgba(15,123,99,.22)'}, {offset: 1, color: 'rgba(15,123,99,.02)'}])}}
            ]
        }, true);
    }

    /** 轴标签压缩：周/月桶显示到最小必要粒度，日桶只留 M/D */
    function shortLabel(label) {
        var text = String(label || '');
        if (text.length === 7) return text.slice(2).replace('-', '/');
        if (text.length >= 10) return text.slice(5).replace('-', '/');
        return text;
    }

    /* 横向柱图：商品榜 / 地区榜 / 产地榜 / 券榜通用 */
    function renderHBar(id, rows, opts) {
        opts = opts || {};
        if (!window.echarts) return empty(id, '图表组件未加载');
        if (!rows || !rows.length) return empty(id, opts.empty || '暂无数据');
        var chart = mount(id);
        var el = document.getElementById(id);
        /* 条数少时按行数收高，避免固定高度里柱子被拉得过散 */
        el.style.height = Math.max(180, rows.length * 42 + 24) + 'px';
        chart.resize();
        var items = rows.slice().reverse();
        var names = [], values = [];
        for (var i = 0; i < items.length; i++) {
            names.push(String(items[i][opts.labelKey || 'productName'] || '—'));
            values.push(num(items[i][opts.valueKey || 'amount']));
        }
        chart.setOption({
            tooltip: Object.assign({trigger: 'axis', axisPointer: {type: 'shadow'},
                formatter: function (params) {
                    var d = items[params[0].dataIndex] || {};
                    var html = '<div style="font-weight:600;margin-bottom:6px;max-width:220px;white-space:normal">'
                        + esc(d[opts.labelKey || 'productName']) + '</div>';
                    var extra = opts.tooltip ? opts.tooltip(d) : '';
                    return html + extra;
                }}, TOOLTIP),
            grid: {left: 4, right: 72, top: 6, bottom: 2, containLabel: true},
            xAxis: {type: 'value', axisLine: {show: false}, axisTick: {show: false},
                    axisLabel: {show: false}, splitLine: {show: false}},
            yAxis: {type: 'category', data: names, axisLine: {show: false}, axisTick: {show: false},
                    axisLabel: {color: '#39424f', fontSize: 12, width: 104, overflow: 'truncate'}},
            series: [{
                type: 'bar', barMaxWidth: 13, data: values,
                /* 榜首用金色强调，其余同色系递减，避免一片同色读不出层次 */
                itemStyle: {borderRadius: [0, 6, 6, 0], color: function (p) {
                    return new window.echarts.graphic.LinearGradient(0, 0, 1, 0, [
                        {offset: 0, color: p.dataIndex === values.length - 1 ? '#e0b774' : '#9dc7ba'},
                        {offset: 1, color: p.dataIndex === values.length - 1 ? GOLD : ACCENT}]);
                }},
                label: {show: true, position: 'right', distance: 8, color: '#39424f', fontSize: 11.5,
                        fontWeight: 600, formatter: function (p) { return opts.format ? opts.format(p.value) : money(p.value, 0); }}
            }]
        }, true);
    }

    /* H04 品类占比环图，tooltip 里带同比 */
    function renderPie(id, rows, opts) {
        opts = opts || {};
        if (!window.echarts) return empty(id, '图表组件未加载');
        if (!rows || !rows.length) return empty(id, opts.empty || '暂无成交');
        var chart = mount(id);
        var data = [];
        for (var i = 0; i < rows.length; i++) {
            data.push({name: rows[i][opts.nameKey || 'category'], value: num(rows[i][opts.valueKey || 'amount']), raw: rows[i]});
        }
        chart.setOption({
            color: ['#0f7b63', '#1c4b8a', '#c08a33', '#c15b6b', '#6f4fb0', '#2f8553', '#d69e2e', '#798394'],
            tooltip: Object.assign({trigger: 'item'}, TOOLTIP, {
                formatter: function (p) {
                    var d = p.data.raw || {};
                    var html = '<div style="font-weight:600;margin-bottom:6px">' + esc(p.name) + '</div>'
                        + '<div style="color:' + MUTED + '">成交额 <b style="color:' + INK + '">' + money(p.value) + '</b></div>'
                        + '<div style="color:' + MUTED + '">占比 <b style="color:' + INK + '">' + num(p.percent).toFixed(1) + '%</b></div>';
                    if (opts.yoy) {
                        html += '<div style="color:' + MUTED + '">同比 <b style="color:' + (d.yoyRate == null ? MUTED : (d.yoyRate < 0 ? RED : ACCENT)) + '">'
                            + rateText(d.yoyRate) + '</b></div>';
                    }
                    if (opts.extra) html += opts.extra(d);
                    return html;
                }
            }),
            legend: {type: 'scroll', bottom: 0, itemWidth: 10, itemHeight: 8, textStyle: {color: MUTED, fontSize: 11.5}},
            series: [{
                type: 'pie', radius: ['48%', '72%'], center: ['50%', '46%'], avoidLabelOverlap: true,
                itemStyle: {borderColor: '#fff', borderWidth: 2, borderRadius: 4},
                label: {show: true, formatter: '{b}\n{d}%', color: '#39424f', fontSize: 11.5, lineHeight: 15},
                labelLine: {length: 8, length2: 8},
                data: data
            }]
        }, true);
    }

    /* H09 时效：三段 P25/P50/P90 分组柱 */
    function renderTiming(id, stages) {
        if (!window.echarts) return empty(id, '图表组件未加载');
        var rows = (stages || []).filter(function (s) { return s.samples > 0; });
        if (!rows.length) return empty(id, '本期暂无发货数据');
        var chart = mount(id);
        chart.setOption({
            color: ['#cfe3db', ACCENT, '#c15b6b'],
            tooltip: Object.assign({trigger: 'axis', axisPointer: {type: 'shadow'}}, TOOLTIP, {
                formatter: function (params) {
                    var html = '<div style="font-weight:600;margin-bottom:6px">' + params[0].axisValue + '</div>';
                    for (var i = 0; i < params.length; i++) {
                        var p = params[i];
                        html += '<div style="display:flex;gap:8px;line-height:20px">' + p.marker
                            + '<span style="color:' + MUTED + '">' + p.seriesName + '</span>'
                            + '<b style="margin-left:auto">' + hours(p.value) + '</b></div>';
                    }
                    return html;
                }
            }),
            legend: {left: 0, top: 0, itemWidth: 12, itemHeight: 8, textStyle: {color: MUTED, fontSize: 12}},
            grid: {left: 6, right: 6, top: 38, bottom: 2, containLabel: true},
            xAxis: {type: 'category', data: rows.map(function (s) { return s.label; }),
                    axisLine: {lineStyle: {color: LINE}}, axisTick: {show: false},
                    axisLabel: Object.assign({interval: 0, width: 76, overflow: 'truncate'}, AXIS_LABEL)},
            yAxis: {type: 'value', axisLine: {show: false}, axisTick: {show: false},
                    axisLabel: Object.assign({formatter: '{value} h'}, AXIS_LABEL),
                    splitLine: {lineStyle: {color: LINE, type: 'dashed'}}},
            series: [
                {name: 'P25', type: 'bar', barMaxWidth: 12, data: rows.map(function (s) { return s.p25 || 0; }),
                 itemStyle: {borderRadius: [3, 3, 0, 0]}},
                {name: '中位 P50', type: 'bar', barMaxWidth: 12, data: rows.map(function (s) { return s.p50 || 0; }),
                 itemStyle: {borderRadius: [3, 3, 0, 0]}},
                {name: 'P90', type: 'bar', barMaxWidth: 12, data: rows.map(function (s) { return s.p90 || 0; }),
                 itemStyle: {borderRadius: [3, 3, 0, 0]}}
            ]
        }, true);
    }

    /* H10 库存周转：销量柱 + 可售天数折线（右轴） */
    function renderTurnover(id, rows) {
        if (!window.echarts) return empty(id, '图表组件未加载');
        if (!rows || !rows.length) return empty(id, '本期暂无动销');
        var chart = mount(id);
        chart.setOption({
            color: [ACCENT, GOLD],
            tooltip: Object.assign({trigger: 'axis'}, TOOLTIP),
            legend: {left: 0, top: 0, itemWidth: 12, itemHeight: 8, textStyle: {color: MUTED, fontSize: 12}},
            grid: {left: 6, right: 6, top: 38, bottom: 2, containLabel: true},
            xAxis: {type: 'category', data: rows.map(function (r) { return r.productName; }),
                    axisLine: {lineStyle: {color: LINE}}, axisTick: {show: false},
                    axisLabel: Object.assign({interval: 0, width: 60, overflow: 'truncate'}, AXIS_LABEL)},
            yAxis: [
                {type: 'value', name: '件', axisLine: {show: false}, axisTick: {show: false},
                 axisLabel: AXIS_LABEL, splitLine: {lineStyle: {color: LINE, type: 'dashed'}}},
                {type: 'value', name: '天', axisLine: {show: false}, axisTick: {show: false},
                 axisLabel: AXIS_LABEL, splitLine: {show: false}}
            ],
            series: [
                {name: '窗口销量', type: 'bar', barMaxWidth: 18, data: rows.map(function (r) { return num(r.units); }),
                 itemStyle: {color: '#cfe3db', borderRadius: [4, 4, 0, 0]}, emphasis: {itemStyle: {color: ACCENT}}},
                {name: '可售天数', type: 'line', yAxisIndex: 1, smooth: 0.3, connectNulls: true,
                 data: rows.map(function (r) { return r.coverDays === null || r.coverDays === undefined ? null : num(r.coverDays); }),
                 lineStyle: {width: 2, color: GOLD}, itemStyle: {color: GOLD}, symbolSize: 6}
            ]
        }, true);
    }

    /* H11 新客/老客构成 */
    function renderMix(id, mix) {
        if (!window.echarts) return empty(id, '图表组件未加载');
        if (!mix || !((mix.newUsers || 0) + (mix.oldUsers || 0))) return empty(id, '本期暂无成交用户');
        renderPie(id, [
            {name: '新客', amount: mix.newAmount || 0, users: mix.newUsers},
            {name: '老客', amount: mix.oldAmount || 0, users: mix.oldUsers}
        ], {nameKey: 'name', valueKey: 'amount',
            extra: function (d) {
                return '<div style="color:' + MUTED + '">人数 <b style="color:' + INK + '">' + int(d.users) + '</b></div>';
            }});
    }

    function request(options) {
        if (window.huAdmin && window.huAdmin.request) window.huAdmin.request(options);
    }

    /** 相对今天的 ISO 日期：自定义范围首次展开时给个默认区间 */
    function todayOffset(days) {
        var d = new Date();
        d.setDate(d.getDate() + (days || 0));
        var m = String(d.getMonth() + 1);
        var day = String(d.getDate());
        return d.getFullYear() + '-' + (m.length < 2 ? '0' + m : m) + '-' + (day.length < 2 ? '0' + day : day);
    }

    window.huStats = {
        jQuery: window.jQuery,
        num: num,
        int: int,
        money: money,
        hours: hours,
        rateText: rateText,
        deltaHtml: deltaHtml,
        anomalyBar: anomalyBar,
        esc: esc,
        mount: mount,
        empty: empty,
        resizeAll: resizeAll,
        todayOffset: todayOffset,
        render: {
            trend: renderTrend,
            hBar: renderHBar,
            pie: renderPie,
            timing: renderTiming,
            turnover: renderTurnover,
            mix: renderMix
        },
        request: request
    };

    var timer = null;
    window.jQuery(window).on('resize', function () {
        clearTimeout(timer);
        timer = setTimeout(resizeAll, 180);
    });
})(window);
