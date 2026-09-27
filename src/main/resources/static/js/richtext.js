/**
 * 轻量富文本编辑器（无第三方依赖）：工具栏 + contenteditable
 * 产出的 HTML 仅含 b/i/em/strong/h3/p/ul/ol/li/blockquote/a/br，
 * 服务端入库前仍会用 HtmlUtil.filter 做白名单清洗，前端只负责"不产生垃圾标签"。
 */
(function (window) {
    'use strict';

    function exec(command, value) {        document.execCommand(command, false, value || null);
    }

    /** 粘贴时只保留纯文本，避免从 Word/网页带进 style/class 等噪声标签 */
    function pastePlain(editor) {
        editor.addEventListener('paste', function (e) {
            e.preventDefault();
            var text = (e.clipboardData || window.clipboardData).getData('text/plain');
            exec('insertText', text);
        });
    }

    function button(label, title, handler) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'hr-btn';
        btn.textContent = label;
        btn.title = title;
        btn.addEventListener('mousedown', function (e) {
            e.preventDefault();
            handler();
        });
        return btn;
    }

    /**
     * @param options.container 工具栏与编辑区的挂载容器
     * @param options.html      初始 HTML
     * @param options.height    编辑区高度
     */
    function create(options) {
        var box = options.container;
        box.className = (box.className ? box.className + ' ' : '') + 'hu-rich';

        var toolbar = document.createElement('div');
        toolbar.className = 'hr-toolbar';
        var editor = document.createElement('div');
        editor.className = 'hr-editor';
        editor.contentEditable = 'true';
        editor.style.height = (options.height || 220) + 'px';
        editor.innerHTML = options.html || '';
        editor.dataset.placeholder = options.placeholder || '请输入内容';

        function block(tag) {
            exec('formatBlock', '<' + tag + '>');
        }

        toolbar.appendChild(button('加粗', '加粗（Ctrl+B）', function () { exec('bold'); }));
        toolbar.appendChild(button('斜体', '斜体（Ctrl+I）', function () { exec('italic'); }));
        toolbar.appendChild(button('小标题', '设为小标题', function () { block('h3'); }));
        toolbar.appendChild(button('正文', '恢复为正文', function () { block('p'); }));
        toolbar.appendChild(button('引用', '公告说明段落', function () { block('blockquote'); }));
        toolbar.appendChild(button('• 列表', '无序列表', function () { exec('insertUnorderedList'); }));
        toolbar.appendChild(button('1. 列表', '有序列表', function () { exec('insertOrderedList'); }));
        toolbar.appendChild(button('链接', '为选中文字加链接（仅站内路径或 http/https）', function () {
            var sel = document.getSelection().toString();
            var url = window.prompt('链接地址，如 /products 或 https://example.com');
            if (!url) return;
            if (!/^(\/[^/]|https?:\/\/)/i.test(url)) {
                window.alert('仅支持站内路径（/开头）或 http(s) 链接');
                return;
            }
            if (sel) exec('createLink', url);
            else exec('insertHTML', '<a href="' + url.replace(/"/g, '&quot;') + '">' + url + '</a>');
        }));
        toolbar.appendChild(button('清除格式', '移除加粗、标题等样式', function () { exec('removeFormat'); }));

        var counter = document.createElement('span');
        counter.className = 'hr-count';
        function updateCount() {
            var len = (editor.textContent || '').length;
            counter.textContent = len + ' 字';
            counter.classList.toggle('is-over', len > options.maxChars);
        }
        editor.addEventListener('input', updateCount);
        pastePlain(editor);
        updateCount();

        box.appendChild(toolbar);
        box.appendChild(editor);
        box.appendChild(counter);

        return {
            element: editor,
            /** 空内容统一返回空串，便于必填校验 */
            html: function () {
                var text = (editor.textContent || '').replace(/\u00a0/g, ' ').trim();
                return text ? editor.innerHTML : '';
            },
            text: function () {
                return (editor.textContent || '').replace(/\u00a0/g, ' ').trim();
            }
        };
    }

    /** 后台列表展示用：把已清洗的 HTML 压成一行摘要 */
    function summary(html, max) {
        var div = document.createElement('div');
        div.innerHTML = html || '';
        var text = (div.textContent || '').replace(/\s+/g, ' ').trim();
        return text.length > max ? text.slice(0, max) + '…' : text;
    }

    window.huRichText = {create: create, summary: summary};
})(window);
