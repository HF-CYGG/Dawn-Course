/**
 * JS 解析器周次解析回归测试（issue #109：只上一周的课导入失败）
 *
 * 纯 Node、零依赖。用 vm 模块按真实拼接顺序（common_parser_utils.js + 具体解析脚本）
 * 加载脚本，断言 parseWeeks / extractWeeksStr 的行为。
 *
 * 运行：node feature/import/src/test/js/parse_weeks.test.cjs
 * 由 Gradle 任务 :feature:import:jsParserTest 调用，并挂在 testDebugUnitTest / check 上。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const parsersDir = path.resolve(__dirname, '../../main/assets/parsers');
const commonSrc = fs.readFileSync(path.join(parsersDir, 'common_parser_utils.js'), 'utf8');
const zhengfangSrc = fs.readFileSync(path.join(parsersDir, 'zhengfang.js'), 'utf8');

let failures = 0;
let passes = 0;

function range(a, b) {
    const out = [];
    for (let i = a; i <= b; i++) out.push(i);
    return out;
}

function eq(actual, expected, label) {
    const a = JSON.stringify(actual);
    const e = JSON.stringify(expected);
    if (a === e) {
        passes++;
        // console.log('ok   ' + label);
    } else {
        failures++;
        console.error('FAIL ' + label + '\n     got  ' + a + '\n     want ' + e);
    }
}

/** 按真实运行方式加载：common_parser_utils.js 在前，具体解析脚本在后 */
function loadScope(extraSrc) {
    const ctx = { console: console };
    vm.createContext(ctx);
    vm.runInContext(commonSrc + '\n' + (extraSrc || ''), ctx, { filename: 'bundle.js' });
    return ctx;
}

// 两种作用域：只有 common，以及 common + zhengfang（验证 zhengfang 删除本地副本后
// parseWeeks / extractWeeksStr 落到 common 的加固版本，且行为一致）
const scopes = [
    ['common', loadScope('')],
    ['zhengfang', loadScope(zhengfangSrc)],
];

for (const [name, ctx] of scopes) {
    const parseWeeks = ctx.parseWeeks;
    const extractWeeksStr = ctx.extractWeeksStr;

    if (typeof parseWeeks !== 'function' || typeof extractWeeksStr !== 'function') {
        failures++;
        console.error('FAIL ' + name + ': parseWeeks / extractWeeksStr 未定义');
        continue;
    }

    // ---- parseWeeks ----
    eq(parseWeeks('9周'), [9], name + ' parseWeeks("9周")');
    eq(parseWeeks('9'), [9], name + ' parseWeeks("9")');
    eq(parseWeeks('9周(1-2节)'), [9], name + ' parseWeeks("9周(1-2节)") 节次粘连');
    eq(parseWeeks('第9周'), [9], name + ' parseWeeks("第9周")');
    eq(parseWeeks('9-9周'), [9], name + ' parseWeeks("9-9周")');
    eq(parseWeeks('1-16周'), range(1, 16), name + ' parseWeeks("1-16周")');
    eq(parseWeeks('1-16周(1-2节)'), range(1, 16), name + ' parseWeeks("1-16周(1-2节)") 区间+节次粘连');
    eq(parseWeeks('1-16周(单)'), [1, 3, 5, 7, 9, 11, 13, 15], name + ' parseWeeks("1-16周(单)")');
    eq(parseWeeks('1-16周(双)'), [2, 4, 6, 8, 10, 12, 14, 16], name + ' parseWeeks("1-16周(双)")');
    eq(parseWeeks('1,3,5周'), [1, 3, 5], name + ' parseWeeks("1,3,5周")');
    eq(parseWeeks('1-4,10周'), [1, 2, 3, 4, 10], name + ' parseWeeks("1-4,10周")');
    eq(parseWeeks('16-9周'), range(9, 16), name + ' parseWeeks("16-9周") 写反的区间');
    eq(parseWeeks('周数：第9周'), [9], name + ' parseWeeks("周数：第9周")');
    eq(parseWeeks(''), [], name + ' parseWeeks("")');
    eq(parseWeeks('单周'), [], name + ' parseWeeks("单周") 无数字');
    eq(parseWeeks('99周'), [], name + ' parseWeeks("99周") 越界钳制');
    // 整体被括号包住的周次：不能被 stripWeekNoise 一起清掉（Codex PR #112 P1）
    eq(parseWeeks('(1-16周)'), range(1, 16), name + ' parseWeeks("(1-16周)") 括号包裹');
    eq(parseWeeks('（9周）'), [9], name + ' parseWeeks("（9周）") 全角括号包裹');
    eq(parseWeeks('1-4周(1-2节),6-8周'), [1, 2, 3, 4, 6, 7, 8], name + ' parseWeeks 区间+节次括号+区间');
    // 方括号形式的节次后缀：Kingosoft 列表周次格常见写法（Codex PR #112 P1）。
    // 之前只清圆括号，方括号会与周次数字粘连（"5周[1-2节]" -> "51-2" -> 误判成第 2-51 周）。
    eq(parseWeeks('5周[1-2节]'), [5], name + ' parseWeeks("5周[1-2节]") 方括号节次粘连');
    eq(parseWeeks('1-16周[03-04节]'), range(1, 16), name + ' parseWeeks("1-16周[03-04节]") 区间+方括号节次粘连');

    // ---- extractWeeksStr ----
    eq(extractWeeksStr('周次:5'), '5', name + ' extractWeeksStr("周次:5") 无 周 字');
    eq(extractWeeksStr('周数：5'), '5', name + ' extractWeeksStr("周数：5")');
    eq(extractWeeksStr('周数：第5周 节次:1-2节'), '5周', name + ' extractWeeksStr("周数：第5周 节次:1-2节")');
    eq(extractWeeksStr('5周(1-2节)'), '5周', name + ' extractWeeksStr("5周(1-2节)") 不吞节次');
    eq(extractWeeksStr('1-16周'), '1-16周', name + ' extractWeeksStr("1-16周")');
    eq(extractWeeksStr('1-16周(单)'), '1-16周(单)', name + ' extractWeeksStr("1-16周(单)")');
    eq(extractWeeksStr('教师:张三 上课时间:1-2节'), '', name + ' extractWeeksStr(无周次信息) -> ""');
    // 无冒号的 "周数" 标签 + 逗号列表：必须保留完整列表（Codex PR #112 P1）
    eq(extractWeeksStr('周数 1,3,5周'), '1,3,5周', name + ' extractWeeksStr("周数 1,3,5周") 无冒号+列表');
    eq(extractWeeksStr('周数1-8,10-16周'), '1-8,10-16周', name + ' extractWeeksStr("周数1-8,10-16周") 无冒号+多区间');
    // 每段自带 "周" 后缀的列表：必须完整保留（Codex PR #112 P1 二次）
    eq(extractWeeksStr('周次:1-8周,10-16周'), '1-8周,10-16周', name + ' extractWeeksStr("周次:1-8周,10-16周") 每段带周');
    eq(extractWeeksStr('1-8周,10-16周'), '1-8周,10-16周', name + ' extractWeeksStr(无标签 每段带周)');
    eq(extractWeeksStr('1-8,10-16周 教师：张三'), '1-8,10-16周', name + ' extractWeeksStr(无标签+多区间) 不吞教师');
    eq(extractWeeksStr('节次1-2 上课时间1-3节'), '', name + ' extractWeeksStr(纯节次 不以周收尾) -> ""');
    eq(extractWeeksStr('(1-16周)'), '1-16周', name + ' extractWeeksStr("(1-16周)") 括号包裹');
    eq(extractWeeksStr('（9周）'), '9周', name + ' extractWeeksStr("（9周）") 全角括号');
    // 单/双标记出现在周次列表之前（Codex PR #112 P1）
    eq(extractWeeksStr('周数：(单)1-16周'), '1-16周(单)', name + ' extractWeeksStr("周数：(单)1-16周") 前置单周标记');
    eq(extractWeeksStr('周数：(双)2-16周'), '2-16周(双)', name + ' extractWeeksStr("周数：(双)2-16周") 前置双周标记');
    eq(extractWeeksStr('(单) 1-16周'), '1-16周(单)', name + ' extractWeeksStr("(单) 1-16周") 无标签前置单周标记');
    // 分隔符包裹的单/双标记（Codex PR #112 P1）
    eq(extractWeeksStr('周数:1-16周|单周'), '1-16周(单)', name + ' extractWeeksStr("...|单周") 竖线分隔');
    eq(extractWeeksStr('1-16周/双周'), '1-16周(双)', name + ' extractWeeksStr(".../双周") 斜杠分隔');
    eq(extractWeeksStr('1-16周[单]'), '1-16周(单)', name + ' extractWeeksStr("...[单]") 方括号');
    // 裸露的"单"/"双"不该被误当标记：后面跟的是普通地名/词语，不是分隔符包裹或 "单周"/"双周"
    eq(extractWeeksStr('1-16周 双创楼'), '1-16周', name + ' extractWeeksStr("...双创楼") 不误判"双"');
    eq(extractWeeksStr('1-16周 单元楼101'), '1-16周', name + ' extractWeeksStr("...单元楼") 不误判"单"');
    eq(extractWeeksStr('1-16周单周'), '1-16周(单)', name + ' extractWeeksStr("...单周") 无分隔符但成词仍识别');
    // 包裹符内部带 "周" 的前置单双周标记：(单周)/（双周）（Codex PR #112 P1 三次）
    eq(extractWeeksStr('周数：(单周)1-16周'), '1-16周(单)', name + ' extractWeeksStr("周数：(单周)1-16周") 括号内带周');
    eq(extractWeeksStr('（双周）2-16周'), '2-16周(双)', name + ' extractWeeksStr("（双周）2-16周") 无标签+括号内带周');

    // 端到端：extractWeeksStr -> parseWeeks
    eq(parseWeeks(extractWeeksStr('周次:5 节次:1-2节')), [5], name + ' e2e 单周("周次:5 节次:1-2节")');
    eq(parseWeeks(extractWeeksStr('5周(1-2节)')), [5], name + ' e2e 单周("5周(1-2节)")');
    const w1to8_10to16 = [1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15, 16];
    eq(
        parseWeeks(extractWeeksStr('周数 1-8,10-16周 教师：张三')),
        w1to8_10to16,
        name + ' e2e 无冒号多区间不丢周次'
    );
    eq(
        parseWeeks(extractWeeksStr('周次:1-8周,10-16周 节次:1-2节')),
        w1to8_10to16,
        name + ' e2e 每段带周 不丢周次'
    );
    eq(
        parseWeeks(extractWeeksStr('1-8,10-16周(单)')),
        [1, 3, 5, 7, 11, 13, 15],
        name + ' e2e 多区间带单周标记'
    );
    eq(
        parseWeeks(extractWeeksStr('周数：(单)1-16周 节次:1-2节')),
        [1, 3, 5, 7, 9, 11, 13, 15],
        name + ' e2e 前置单周标记'
    );
    eq(
        parseWeeks(extractWeeksStr('1-16周 双创楼 节次:1-2节')),
        range(1, 16),
        name + ' e2e "双创楼" 不触发误判过滤，保留全部周次'
    );
}

console.log('\nJS 解析器测试：' + passes + ' 通过，' + failures + ' 失败');
if (failures > 0) {
    process.exit(1);
}
