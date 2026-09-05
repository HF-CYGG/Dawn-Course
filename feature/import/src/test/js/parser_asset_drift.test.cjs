/**
 * 解析器脚本漂移守卫
 *
 * 联网设备跑的是服务端 `server/html/scripts/` 下的脚本；App 内置 assets 副本只是断网兜底。
 * 两侧漂移会让「App 里修好的解析器」对联网用户完全无效（这正是「课表整体重复」反复复发的原因）。
 * 此测试逐字节（规范化 CRLF 后）比对两侧，任何不一致即失败。
 *
 * 运行：node feature/import/src/test/js/parser_asset_drift.test.cjs
 * 由 Gradle 任务 :feature:import:jsParserTest 调用。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const repoRoot = path.resolve(__dirname, '../../../../..');
const assetsParsers = path.join(repoRoot, 'feature/import/src/main/assets/parsers');
const serverParsers = path.join(repoRoot, 'server/html/scripts/parsers');
const appRuntime = path.join(repoRoot, 'app/src/main/assets/runtime');
const serverRuntime = path.join(repoRoot, 'server/html/scripts/runtime');

const pairs = [
    ['parsers/common_parser_utils.js', path.join(assetsParsers, 'common_parser_utils.js'), path.join(serverParsers, 'common_parser_utils.js')],
    ['parsers/zhengfang.js', path.join(assetsParsers, 'zhengfang.js'), path.join(serverParsers, 'zhengfang.js')],
    ['parsers/qiangzhi.js', path.join(assetsParsers, 'qiangzhi.js'), path.join(serverParsers, 'qiangzhi.js')],
    ['parsers/kingosoft.js', path.join(assetsParsers, 'kingosoft.js'), path.join(serverParsers, 'kingosoft.js')],
    ['runtime/script_host.js', path.join(appRuntime, 'script_host.js'), path.join(serverRuntime, 'script_host.js')],
];

const norm = (p) => fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n');

let failures = 0;
for (const [label, a, b] of pairs) {
    // server 为独立发布仓，独立检出中可能没有对侧；缺失则跳过而非失败。
    if (!fs.existsSync(a) || !fs.existsSync(b)) {
        console.error('WARN 跳过（一侧缺失）：' + label);
        continue;
    }
    if (norm(a) !== norm(b)) {
        failures++;
        console.error('FAIL 漂移：' + label + '\n     ' + a + '\n     ' + b +
            '\n     两侧内容不一致，请同步后再提交（server 侧以 assets/app 修复版为准）。');
    } else {
        console.log('ok   ' + label);
    }
}

if (failures > 0) {
    console.error('\n解析器脚本漂移守卫：' + failures + ' 处不一致');
    process.exit(1);
}
console.log('\n解析器脚本漂移守卫：两侧一致');
