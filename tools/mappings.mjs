import {readdirSync, readFileSync} from 'fs';
import {join} from 'path';

/**
 * 扫描 controller 层，把「HTTP 方法 + 完整路径」拼出来找重复。
 * Spring 在同一路径+方法上挂两个 handler 会直接以 Ambiguous mapping 启动失败，
 * 多批次并行写接口时这是最容易攒出来、又最晚才炸的问题。
 */
const dir = process.argv[2] || 'src/main/java/org/liuym/flowerv1springboot/controller';
const VERB = {Get: 'GET', Post: 'POST', Put: 'PUT', Delete: 'DELETE', Patch: 'PATCH'};
const rows = [];

for (const name of readdirSync(dir).filter(f => f.endsWith('.java'))) {
    const src = readFileSync(join(dir, name), 'utf8');
    const clean = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*/g, '');
    const base = firstPaths(clean.match(/@RequestMapping\(([^)]*)\)/)?.[1] || '')[0] || '';
    for (const m of clean.matchAll(/@(Get|Post|Put|Delete|Patch)Mapping\b(\(([^)]*)\))?/g)) {
        const paths = firstPaths(m[3] || '');
        const verb = VERB[m[1]];
        (paths.length ? paths : ['']).forEach(p => {
            rows.push({key: verb + ' ' + norm(base + p), file: name});
        });
    }
}

function firstPaths(arg) {
    const out = [];
    for (const m of (arg || '').matchAll(/"([^"]*)"/g)) out.push(m[1]);
    return out;
}

function norm(path) {
    return path.replace(/\/+/g, '/').replace(/\/$/, '') || '/';
}

const byKey = new Map();
for (const r of rows) {
    if (!byKey.has(r.key)) byKey.set(r.key, []);
    byKey.get(r.key).push(r.file);
}

const dupes = [...byKey.entries()].filter(([, files]) => new Set(files).size > 1 || files.length > 1);
console.log('scanned ' + rows.length + ' mappings');
if (!dupes.length) console.log('OK: 无重复的 方法+路径 组合');
for (const [key, files] of dupes) console.log('DUP  ' + key + '  <= ' + files.join(', '));
