/**
 * JS 解析器去重 / 双采集回归测试（课表整体重复的根因修复）
 *
 * 纯 Node、零依赖。用 vm 模块按真实拼接顺序加载脚本，断言：
 *  1. 新版正方课表页（二维表 + 列表同时存在）经 qiangzhi.js / zhengfang.js 解析后，
 *     课程数等于页面实际课程段数，而不是两倍；
 *  2. 只有列表、没有二维表的页面仍能通过列表兜底分支解析出课程；
 *  3. common_parser_utils.js 的 dedupeCourses：teacher / position 清洗力度不同的两条记录
 *     被判为同一门课并合并。
 *
 * 运行：node feature/import/src/test/js/dedupe_courses.test.cjs
 * 由 Gradle 任务 :feature:import:jsParserTest 调用。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const parsersDir = path.resolve(__dirname, '../../main/assets/parsers');
const commonSrc = fs.readFileSync(path.join(parsersDir, 'common_parser_utils.js'), 'utf8');
const repoRoot = path.resolve(__dirname, '../../../../..');
const zhengfangPageMd = path.join(repoRoot, 'docs/zhengfang/正方网页（个人课表）.md');

let failures = 0;
let passes = 0;

function ok(cond, label) {
    if (cond) { passes++; } else { failures++; console.error('FAIL ' + label); }
}
function eq(actual, expected, label) {
    const a = JSON.stringify(actual);
    const e = JSON.stringify(expected);
    if (a === e) { passes++; } else {
        failures++;
        console.error('FAIL ' + label + '\n     got  ' + a + '\n     want ' + e);
    }
}

function loadParser(name) {
    const src = fs.readFileSync(path.join(parsersDir, name), 'utf8');
    const ctx = { console };
    vm.createContext(ctx);
    vm.runInContext(commonSrc + '\n' + src, ctx, { filename: name });
    return ctx;
}

function parseCount(ctx, html) {
    const out = ctx.scheduleHtmlParser(html);
    const arr = typeof out === 'string' ? JSON.parse(out) : out;
    return arr;
}

function identityKey(c) {
    return [c.name, c.day, (c.weeks || []).join(','), (c.sections || []).join(',')].join('|');
}

// ---------------- 1 & 2：双采集收敛 + 列表兜底 ----------------

if (fs.existsSync(zhengfangPageMd)) {
    const pageHtml = fs.readFileSync(zhengfangPageMd, 'utf8');
    for (const name of ['qiangzhi.js', 'zhengfang.js']) {
        const ctx = loadParser(name);
        const arr = parseCount(ctx, pageHtml);
        // 该页面实际有 19 门课程段（二维表 19 + 列表 18，列表是二维表的子集）。
        eq(arr.length, 19, name + ' 新版正方页解析条数 == 页面实际（不是 37）');
        const keys = new Set(arr.map(identityKey));
        eq(keys.size, arr.length, name + ' 无 name|day|weeks|sections 重复键');
        ok(arr.every(c => c._src === undefined), name + ' 返回结果已剥离 _src 诊断标记');

        // 只保留 jc_ 列表、剥掉二维表单元格（<td id="d-s"> / timetable_con 网格）后，
        // 列表兜底分支仍应解析出课程。
        const listOnly = pageHtml.replace(/<td[^>]*\bid\s*=\s*["']?\d+-\d+["']?[\s\S]*?<\/td>/gi, '<td></td>');
        const listArr = parseCount(loadParser(name), listOnly);
        ok(listArr.length > 0, name + ' 仅列表页面（无二维表）仍能通过兜底分支解析出课程');
        ok(listArr.length <= arr.length, name + ' 列表兜底条数不超过完整页面');
    }
} else {
    console.error('WARN 找不到 fixture：' + zhengfangPageMd + '（跳过页面级断言）');
}

// ---------------- 3：dedupeCourses 归一化合并 ----------------

const common = (() => {
    const ctx = { console, module: { exports: {} } };
    ctx.globalThis = ctx;
    vm.createContext(ctx);
    vm.runInContext(commonSrc, ctx, { filename: 'common_parser_utils.js' });
    return ctx.module.exports;
})();

{
    const dedupeCourses = common.dedupeCourses;
    const base = { name: '传感器与检测技术', day: 2, weeks: [1, 2, 3], sections: [7, 8] };
    const merged = dedupeCourses([
        Object.assign({}, base, { teacher: '张磊', position: 'A101' }),
        Object.assign({}, base, {
            teacher: '张磊 教学班:传感器与检测技术-0031B 教学班组成:机械2201 学分:3.0',
            position: '本部 A101',
        }),
    ]);
    eq(merged.length, 1, 'dedupeCourses：teacher/position 清洗力度不同的两条 -> 合并为 1 条');
    eq(merged[0].teacher, '张磊', 'dedupeCourses：teacher 归一化到 "张磊"');
    eq(merged[0].position, 'A101', 'dedupeCourses：position 归一化到 "A101"（剥掉 "本部 "）');

    // 不同教师应合并成 "," 连接
    const twoTeachers = dedupeCourses([
        Object.assign({}, base, { teacher: '陈艳霜', position: 'J3-306' }),
        Object.assign({}, base, { teacher: '解嵘', position: 'J3-306' }),
    ]);
    eq(twoTeachers.length, 1, 'dedupeCourses：同一门课两位老师 -> 合并为 1 条');
    eq(twoTeachers[0].teacher, '陈艳霜,解嵘', 'dedupeCourses：两位老师用 "," 连接');

    // 不同课程（name 不同）不应被合并
    const distinct = dedupeCourses([
        Object.assign({}, base, { name: 'A', teacher: 'x', position: 'p' }),
        Object.assign({}, base, { name: 'B', teacher: 'y', position: 'q' }),
    ]);
    eq(distinct.length, 2, 'dedupeCourses：name 不同的两门课不合并');
}

console.log('\nJS 去重/双采集测试：' + passes + ' 通过，' + failures + ' 失败');
if (failures > 0) process.exit(1);
