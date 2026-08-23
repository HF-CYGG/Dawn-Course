/**
 * Dawn Course 脚本执行契约（共享 Harness）
 *
 * 设计目标：
 * 让「设备端 QuickJS」与「服务端 Node 沙箱」使用同一份入口探测、调用编排与结果校验实现，
 * 从而保证「服务端沙箱验证通过」等价于「设备上能跑通」。此前两端各自内联实现，
 * 契约存在漂移，导致自愈闭环里 LLM 修复出的脚本可能带着绿灯发布却在设备上失败。
 *
 * 兼容性约束：
 * 必须保持与小爱课程表脚本生态的函数签名兼容，即 scheduleHtmlProvider / scheduleHtmlParser /
 * scheduleTimer / parse 的调用方式不得改变。新能力只能以「叠加可选入口」的方式演进。
 *
 * 宿主使用方式（两端一致）：
 *   1. 先 evaluate 本文件，得到 globalThis.__dawnHost
 *   2. evaluate 依赖脚本与目标脚本（使其入口函数挂到某个作用域对象上）
 *   3. __dawnHost.begin(scope, input, options) -> 返回 true 表示异步未完成，需要泵微任务
 *   4. 同步完成时直接取 __dawnHost.result() / __dawnHost.resultJson()
 *      异步场景：QuickJS 侧泵微任务后轮询 result()；Node 侧 await __dawnHost.settled()
 */
(function (globalScope) {
  'use strict';

  // 已存在同版本或更高版本时不重复安装，避免宿主重复注入导致状态被覆盖
  if (globalScope.__dawnHost && Number(globalScope.__dawnHost.CONTRACT_VERSION) >= 1) {
    return;
  }

  /** 契约版本号：与客户端 SUPPORTED_RUNNER_CONTRACT_VERSION、manifest 的 runnerContractVersion 对齐 */
  var CONTRACT_VERSION = 1;

  /**
   * 统一错误码表
   *
   * 设备端与服务端共用同一套字符串，保证上报到 /api/v1/parse/report 的 safeErrorCode
   * 与服务端 runner_reports 的 errorCode 可直接比对与聚合。
   */
  var ERROR_CODES = {
    OK: '',
    NO_ENTRY: 'no_entry',
    SCRIPT_EXCEPTION: 'script_exception',
    TIMEOUT: 'timeout',
    EMPTY_RESULT: 'empty_result',
    SCHEMA_INVALID: 'schema_invalid',
    DUPLICATE_RATIO_HIGH: 'duplicate_ratio_high',
    DO_NOT_CONTINUE: 'do_not_continue'
  };

  /** 解析器入口候选名（顺序即优先级），保持小爱生态既有约定 */
  var PARSER_ENTRIES = ['scheduleHtmlParser', 'parse'];
  /** 学期提取入口候选名 */
  var TERM_ENTRIES = ['extractTermOptions', 'run', 'parse'];
  /** 导航/页面状态入口候选名 */
  var NAVIGATION_ENTRIES = ['navigateToSchedule', 'detectPageState', 'run', 'parse'];

  // ------------------------------------------------------------------
  // 基础工具
  // ------------------------------------------------------------------

  /**
   * 将任意返回值归一为字符串
   *
   * 该逻辑自设备端 ScriptEngine 的 __dc_normalize 平移而来，是设备端下游
   * parseParsedCoursesFromRaw / parseXiaoaiProviderResult 的输入前提，不可改变语义。
   */
  function normalizeToString(value) {
    if (typeof value === 'string') return value;
    if (value === undefined || value === null) return '';
    try {
      return JSON.stringify(value);
    } catch (error) {
      return String(value);
    }
  }

  function isPromise(value) {
    return !!value && typeof value.then === 'function';
  }

  function isNonEmptyString(value) {
    return String(value === undefined || value === null ? '' : value).trim().length > 0;
  }

  function isPositiveInt(value) {
    var num = Number(value);
    return isFinite(num) && num > 0;
  }

  function isValidDay(value) {
    var num = Number(value);
    return isFinite(num) && Math.floor(num) === num && num >= 1 && num <= 7;
  }

  /** 安全读取入口函数：优先脚本作用域，其次宿主全局作用域 */
  function resolveFunction(scope, name) {
    if (scope && typeof scope[name] === 'function') return scope[name];
    if (globalScope && typeof globalScope[name] === 'function') return globalScope[name];
    return null;
  }

  // ------------------------------------------------------------------
  // 结果结构校验
  //
  // 口径来源：设备端 ImportModels.kt 的实际接受规则（parseParsedCourseArray /
  // parseXiaoaiCourses）。服务端此前只认 Dawn 原生形态，会把合法的小爱格式
  // 输出误判为 schema_invalid，这里统一为两种形态都接受。
  // ------------------------------------------------------------------

  /** 尝试把字符串解析为 JSON，失败返回 undefined */
  function tryParseJson(text) {
    if (typeof text !== 'string') return undefined;
    var trimmed = text.trim();
    if (!trimmed) return undefined;
    if (trimmed.charAt(0) !== '[' && trimmed.charAt(0) !== '{') return undefined;
    try {
      return JSON.parse(trimmed);
    } catch (error) {
      return undefined;
    }
  }

  /**
   * 从任意结构中提取课程数组
   *
   * 兼容设备端支持的嵌套解包：courses / courseInfos / parsedCourses / result / data / payload，
   * 且允许这些字段本身是 JSON 字符串。depth 上限与设备端保持一致（4 层）。
   */
  function extractCourseArray(value, depth) {
    var currentDepth = depth || 0;
    if (currentDepth > 4) return [];
    if (Array.isArray(value)) return value;

    if (typeof value === 'string') {
      var parsed = tryParseJson(value);
      return parsed === undefined ? [] : extractCourseArray(parsed, currentDepth + 1);
    }
    if (!value || typeof value !== 'object') return [];

    var arrayKeys = ['courses', 'courseInfos', 'parsedCourses', 'result', 'data'];
    for (var i = 0; i < arrayKeys.length; i++) {
      var candidate = value[arrayKeys[i]];
      if (Array.isArray(candidate) && candidate.length) return candidate;
    }
    var nestedKeys = ['data', 'result', 'payload'];
    for (var j = 0; j < nestedKeys.length; j++) {
      var nested = value[nestedKeys[j]];
      if (nested === undefined || nested === null) continue;
      var found = extractCourseArray(nested, currentDepth + 1);
      if (found.length) return found;
    }
    return [];
  }

  /** 判断单个课程对象是否符合 Dawn 原生形态 */
  function isValidDawnCourse(course) {
    if (!course || typeof course !== 'object') return false;
    return isNonEmptyString(course.name)
      && isValidDay(course.dayOfWeek)
      && isPositiveInt(course.startSection)
      && isPositiveInt(course.duration)
      && isPositiveInt(course.startWeek)
      && isPositiveInt(course.endWeek);
  }

  /** 判断单个课程对象是否符合小爱形态（离散 weeks / sections 数组） */
  function isValidXiaoaiCourse(course) {
    if (!course || typeof course !== 'object') return false;
    var day = course.day === undefined ? course.dayOfWeek : course.day;
    if (!isValidDay(day)) return false;
    var weeks = Array.isArray(course.weeks) ? course.weeks : [];
    var sections = Array.isArray(course.sections) ? course.sections : [];
    var hasWeek = false;
    for (var i = 0; i < weeks.length; i++) {
      if (isPositiveInt(weeks[i])) { hasWeek = true; break; }
    }
    var hasSection = false;
    for (var j = 0; j < sections.length; j++) {
      var item = sections[j];
      // 小爱的 sections 元素既可能是数字，也可能是 { section: n } 形式
      var sectionValue = (item && typeof item === 'object') ? item.section : item;
      if (isPositiveInt(sectionValue)) { hasSection = true; break; }
    }
    return hasWeek && hasSection;
  }

  /** 计算重复课程占比，用于识别「解析器把同一格子重复抓取」这类退化输出 */
  function duplicateRatio(courses) {
    if (courses.length <= 1) return 0;
    var seen = {};
    var unique = 0;
    for (var i = 0; i < courses.length; i++) {
      var course = courses[i] || {};
      var day = course.dayOfWeek === undefined ? course.day : course.dayOfWeek;
      var key = [
        course.name === undefined ? '' : course.name,
        day === undefined ? '' : day,
        course.startSection === undefined ? '' : course.startSection,
        course.startWeek === undefined ? '' : course.startWeek,
        course.endWeek === undefined ? '' : course.endWeek,
        Array.isArray(course.weeks) ? course.weeks.join(',') : '',
        Array.isArray(course.sections) ? course.sections.join(',') : ''
      ].join('|');
      if (!Object.prototype.hasOwnProperty.call(seen, key)) {
        seen[key] = true;
        unique += 1;
      }
    }
    return 1 - unique / courses.length;
  }

  /**
   * 校验解析器输出
   *
   * @param payload 归一后的字符串或原始结构
   * @returns { resultCount, schemaValid, errorCode, errorMessage }
   */
  function inspectParserPayload(payload) {
    var text = typeof payload === 'string' ? payload.trim() : '';
    // "do not continue" 是小爱生态约定的「此页面不处理」信号，不属于失败
    if (text === 'do not continue') {
      return {
        resultCount: 0,
        schemaValid: true,
        errorCode: ERROR_CODES.DO_NOT_CONTINUE,
        errorMessage: 'script requested to skip this page'
      };
    }

    var structured = typeof payload === 'string' ? tryParseJson(payload) : payload;
    if (structured === undefined && !text) {
      return {
        resultCount: 0,
        schemaValid: false,
        errorCode: ERROR_CODES.EMPTY_RESULT,
        errorMessage: 'parser returned empty result'
      };
    }

    var courses = extractCourseArray(structured === undefined ? text : structured, 0);
    if (!courses.length) {
      return {
        resultCount: 0,
        schemaValid: false,
        errorCode: ERROR_CODES.EMPTY_RESULT,
        errorMessage: 'parser returned empty courses'
      };
    }

    var validCount = 0;
    for (var i = 0; i < courses.length; i++) {
      if (isValidDawnCourse(courses[i]) || isValidXiaoaiCourse(courses[i])) validCount += 1;
    }
    if (validCount === 0) {
      return {
        resultCount: courses.length,
        schemaValid: false,
        errorCode: ERROR_CODES.SCHEMA_INVALID,
        errorMessage: 'no course matched dawn or xiaoai schema'
      };
    }
    if (duplicateRatio(courses) > 0.2) {
      return {
        resultCount: courses.length,
        schemaValid: false,
        errorCode: ERROR_CODES.DUPLICATE_RATIO_HIGH,
        errorMessage: 'duplicate course ratio is too high'
      };
    }
    return {
      resultCount: validCount,
      schemaValid: true,
      errorCode: ERROR_CODES.OK,
      errorMessage: ''
    };
  }

  /** 校验学期提取结果 */
  function inspectTermPayload(payload) {
    var structured = typeof payload === 'string' ? tryParseJson(payload) : payload;
    var source = structured && !Array.isArray(structured)
      ? (structured.terms || structured.options || structured)
      : structured;
    var options = Array.isArray(source) ? source : [];
    if (!options.length) {
      return {
        resultCount: 0,
        schemaValid: false,
        errorCode: ERROR_CODES.EMPTY_RESULT,
        errorMessage: 'term extractor returned empty options'
      };
    }
    var validCount = 0;
    for (var i = 0; i < options.length; i++) {
      var option = options[i] || {};
      var label = String(option.label === undefined ? (option.name === undefined ? '' : option.name) : option.label);
      var value = String(option.value === undefined
        ? (option.id === undefined ? (option.termId === undefined ? '' : option.termId) : option.id)
        : option.value);
      if (isNonEmptyString(label) && isNonEmptyString(value) && /\d{4}|semester|term/i.test(label + ' ' + value)) {
        validCount += 1;
      }
    }
    return validCount > 0
      ? { resultCount: options.length, schemaValid: true, errorCode: ERROR_CODES.OK, errorMessage: '' }
      : {
        resultCount: options.length,
        schemaValid: false,
        errorCode: ERROR_CODES.SCHEMA_INVALID,
        errorMessage: 'term option schema invalid'
      };
  }

  /** 校验导航/页面状态结果 */
  function inspectNavigationPayload(payload) {
    var structured = typeof payload === 'string' ? tryParseJson(payload) : payload;
    if (!structured || typeof structured !== 'object') {
      return {
        resultCount: 0,
        schemaValid: false,
        errorCode: ERROR_CODES.EMPTY_RESULT,
        errorMessage: 'navigation returned empty result'
      };
    }
    var action = String(structured.action || structured.type || structured.kind || '');
    var target = String(structured.url || structured.targetUrl || structured.path || structured.menuPath || structured.selector || '');
    var lowerTarget = target.toLowerCase();
    var validAction = /navigate|click|open|redirect|state|menu|detect/i.test(action);
    var validTarget = isNonEmptyString(target)
      && (lowerTarget.indexOf('http://') === 0
        || lowerTarget.indexOf('https://') === 0
        || target.charAt(0) === '/'
        || lowerTarget.indexOf('xskb') >= 0
        || lowerTarget.indexOf('schedule') >= 0
        || lowerTarget.indexOf('timetable') >= 0);
    return validAction && validTarget
      ? { resultCount: 1, schemaValid: true, errorCode: ERROR_CODES.OK, errorMessage: '' }
      : {
        resultCount: 0,
        schemaValid: false,
        errorCode: ERROR_CODES.SCHEMA_INVALID,
        errorMessage: 'navigation action or target invalid'
      };
  }

  /** 按目标类型分发校验 */
  function inspectPayload(targetType, payload) {
    if (targetType === 'term_extractor') return inspectTermPayload(payload);
    if (targetType === 'navigation') return inspectNavigationPayload(payload);
    return inspectParserPayload(payload);
  }

  // ------------------------------------------------------------------
  // 入口探测与调用编排
  //
  // 逻辑自设备端 ScriptEngine 的 __dc_runAfterProvider / __dc_finalize 平移而来。
  // 关键语义（务必保持）：当脚本同时定义 provider 与 parser 时，最终结果取 provider
  // 的返回值，parser 与 timer 的返回值仅用于补全 timetable 字段——这是小爱生态
  // 「provider 产出数据、parser/timer 仅转发」的既定约定。
  // ------------------------------------------------------------------

  /** 把 timer 产出的 timetable 合并进 provider 的 JSON 结果 */
  function mergeTimetable(providerValue, timerValue) {
    var providerText = normalizeToString(providerValue);
    if (!providerText || providerText === 'do not continue' || timerValue === undefined) {
      return providerText;
    }
    try {
      var obj = JSON.parse(providerText);
      if (obj && typeof obj === 'object' && obj.timetable === undefined) {
        obj.timetable = timerValue;
        return JSON.stringify(obj);
      }
    } catch (error) {
      // provider 返回的不是 JSON（例如原始 HTML 文本），保持原样返回
    }
    return providerText;
  }

  /** 汇总 provider / parser / timer 三者的最终字符串结果 */
  function finalize(providerValue, parserValue, timerValue) {
    if (providerValue !== undefined) return mergeTimetable(providerValue, timerValue);
    if (parserValue !== undefined) return normalizeToString(parserValue);
    return normalizeToString(providerValue);
  }

  /** provider 返回后继续执行 parser → timer 链，返回字符串或 Promise<字符串> */
  function runAfterProvider(entries, providerValue) {
    var parserValue = entries.parser ? entries.parser(providerValue) : undefined;

    function continueWithParser(resolvedParser) {
      var timerValue = entries.timer
        ? entries.timer({ providerRes: normalizeToString(providerValue), parserRes: resolvedParser })
        : undefined;
      if (isPromise(timerValue)) {
        return Promise.resolve(timerValue).then(function (resolvedTimer) {
          return finalize(providerValue, resolvedParser, resolvedTimer);
        });
      }
      return finalize(providerValue, resolvedParser, timerValue);
    }

    if (isPromise(parserValue)) {
      return Promise.resolve(parserValue).then(continueWithParser);
    }
    return continueWithParser(parserValue);
  }

  /**
   * 探测脚本提供的入口函数
   *
   * @returns { provider, parser, timer, single, entryUsed } —— single 为非 provider 链的单入口
   */
  function detectEntries(scope, options) {
    var targetType = options.targetType || 'parser';
    var preferred = options.entry ? [options.entry] : [];

    // 显式指定的入口优先（服务端针对特定 targetType 定向验证时使用）
    for (var i = 0; i < preferred.length; i++) {
      var explicit = resolveFunction(scope, preferred[i]);
      if (explicit) {
        return { single: explicit, entryUsed: preferred[i] };
      }
    }

    if (targetType === 'parser') {
      var provider = resolveFunction(scope, 'scheduleHtmlProvider');
      var parser = resolveFunction(scope, 'scheduleHtmlParser');
      // 与设备端一致：仅当 provider 存在时 timer 才参与编排
      var timer = provider ? resolveFunction(scope, 'scheduleTimer') : null;
      if (provider) {
        return { provider: provider, parser: parser, timer: timer, entryUsed: 'scheduleHtmlProvider' };
      }
      for (var j = 0; j < PARSER_ENTRIES.length; j++) {
        var parserEntry = resolveFunction(scope, PARSER_ENTRIES[j]);
        if (parserEntry) return { single: parserEntry, entryUsed: PARSER_ENTRIES[j] };
      }
      return null;
    }

    var names = targetType === 'term_extractor' ? TERM_ENTRIES : NAVIGATION_ENTRIES;
    for (var k = 0; k < names.length; k++) {
      var fn = resolveFunction(scope, names[k]);
      if (fn) return { single: fn, entryUsed: names[k] };
    }
    return null;
  }

  // ------------------------------------------------------------------
  // 执行状态机
  // ------------------------------------------------------------------

  /** 单次执行状态。两端宿主均为「一个实例/进程执行一个脚本」，无需并发管理。 */
  var state = null;

  function buildResult(fields) {
    var errorCode = fields.errorCode || ERROR_CODES.OK;
    var ok = !!fields.ok;
    var status = fields.status;
    if (!status) {
      if (ok) status = 'passed';
      else if (errorCode === ERROR_CODES.TIMEOUT) status = 'timeout';
      else if (errorCode === ERROR_CODES.SCRIPT_EXCEPTION || errorCode === ERROR_CODES.NO_ENTRY) status = 'failed';
      else status = 'invalid';
    }
    return {
      ok: ok,
      status: status,
      schemaValid: !!fields.schemaValid,
      resultCount: Number(fields.resultCount || 0),
      errorCode: errorCode,
      errorMessage: String(fields.errorMessage || ''),
      entryUsed: String(fields.entryUsed || ''),
      raw: typeof fields.raw === 'string' ? fields.raw : '',
      contractVersion: CONTRACT_VERSION
    };
  }

  /** 结算：对归一字符串做结构校验并落库到 state */
  function settle(rawValue, entryUsed, targetType) {
    var raw = normalizeToString(rawValue);
    var inspection = inspectPayload(targetType, raw);
    var ok = inspection.schemaValid && inspection.resultCount > 0;
    state.result = buildResult({
      ok: ok,
      schemaValid: inspection.schemaValid,
      resultCount: inspection.resultCount,
      errorCode: inspection.errorCode,
      errorMessage: inspection.errorMessage,
      entryUsed: entryUsed,
      raw: raw
    });
    state.settled = true;
  }

  /** 结算为异常 */
  function settleError(error, entryUsed, errorCode) {
    state.result = buildResult({
      ok: false,
      schemaValid: false,
      resultCount: 0,
      errorCode: errorCode || ERROR_CODES.SCRIPT_EXCEPTION,
      errorMessage: error && error.message ? String(error.message) : String(error),
      entryUsed: entryUsed,
      raw: ''
    });
    state.settled = true;
  }

  /**
   * 开始执行脚本入口
   *
   * @param scope   脚本入口函数所在作用域（QuickJS 传 globalThis；Node 沙箱传导出对象）
   * @param input   输入内容（课表 HTML 或样例文本）
   * @param options { targetType, entry, dom, deadlineAt }
   * @returns true 表示尚未完成（存在挂起的 Promise），宿主需要泵微任务后轮询 result()
   */
  function begin(scope, input, options) {
    var opts = options || {};
    var targetType = opts.targetType || 'parser';
    state = {
      settled: false,
      result: null,
      deadlineAt: Number(opts.deadlineAt || 0),
      promise: null
    };

    var entries;
    try {
      entries = detectEntries(scope, opts);
    } catch (error) {
      settleError(error, '', ERROR_CODES.SCRIPT_EXCEPTION);
      return false;
    }
    if (!entries) {
      settleError(new Error('no compatible entry function found'), '', ERROR_CODES.NO_ENTRY);
      return false;
    }

    var entryUsed = entries.entryUsed;
    var text = input === undefined || input === null ? '' : String(input);
    var dom = opts.dom === undefined ? null : opts.dom;

    var entryResult;
    try {
      if (entries.provider) {
        // 保持小爱既有签名：scheduleHtmlProvider(iframeContent, frameContent, dom)
        entryResult = entries.provider(text, '', dom);
      } else {
        entryResult = entries.single(text);
      }
    } catch (error) {
      settleError(error, entryUsed, ERROR_CODES.SCRIPT_EXCEPTION);
      return false;
    }

    // 同步路径：直接结算
    if (!isPromise(entryResult)) {
      try {
        if (entries.provider) {
          var flow = runAfterProvider(entries, entryResult);
          if (!isPromise(flow)) {
            settle(flow, entryUsed, targetType);
            return false;
          }
          state.promise = Promise.resolve(flow).then(function (finalValue) {
            settle(finalValue, entryUsed, targetType);
            return state.result;
          }, function (error) {
            settleError(error, entryUsed, ERROR_CODES.SCRIPT_EXCEPTION);
            return state.result;
          });
          return true;
        }
        settle(entryResult, entryUsed, targetType);
        return false;
      } catch (error) {
        settleError(error, entryUsed, ERROR_CODES.SCRIPT_EXCEPTION);
        return false;
      }
    }

    // 异步路径
    state.promise = Promise.resolve(entryResult).then(function (resolvedEntry) {
      if (!entries.provider) {
        settle(resolvedEntry, entryUsed, targetType);
        return state.result;
      }
      var flow = runAfterProvider(entries, resolvedEntry);
      if (!isPromise(flow)) {
        settle(flow, entryUsed, targetType);
        return state.result;
      }
      return Promise.resolve(flow).then(function (finalValue) {
        settle(finalValue, entryUsed, targetType);
        return state.result;
      });
    }).then(null, function (error) {
      settleError(error, entryUsed, ERROR_CODES.SCRIPT_EXCEPTION);
      return state.result;
    });
    return true;
  }

  /** 是否已结算 */
  function isSettled() {
    return !!(state && state.settled);
  }

  /**
   * 判断是否已超过宿主给定的执行预算
   *
   * 说明：本预算只能在「微任务泵」这一层生效。若脚本在同步代码里死循环，
   * JS 侧无法自我中断，必须由宿主侧（设备端为独立线程 + 超时；服务端为子进程 kill）兜底。
   */
  function isExpired() {
    if (!state || !state.deadlineAt) return false;
    return Date.now() >= state.deadlineAt;
  }

  /** 把当前状态强制结算为超时 */
  function abortAsTimeout(entryUsed) {
    if (!state || state.settled) return;
    state.result = buildResult({
      ok: false,
      schemaValid: false,
      resultCount: 0,
      errorCode: ERROR_CODES.TIMEOUT,
      errorMessage: 'script execution exceeded time budget',
      entryUsed: entryUsed || '',
      raw: ''
    });
    state.settled = true;
  }

  /** 取结果对象；未结算返回 null */
  function result() {
    return state && state.settled ? state.result : null;
  }

  /**
   * 取结果的 JSON 字符串
   *
   * QuickJS 侧宿主使用：对象跨语言编组不可靠，统一用字符串回传。
   */
  function resultJson() {
    var current = result();
    if (!current) return '';
    try {
      return JSON.stringify(current);
    } catch (error) {
      return '';
    }
  }

  /** Node 侧使用：等待异步结算完成 */
  function settled() {
    if (!state) return Promise.resolve(null);
    if (state.settled) return Promise.resolve(state.result);
    if (state.promise) {
      return state.promise.then(function () {
        return state.result;
      });
    }
    return Promise.resolve(state.result);
  }

  globalScope.__dawnHost = {
    CONTRACT_VERSION: CONTRACT_VERSION,
    ERROR_CODES: ERROR_CODES,
    begin: begin,
    result: result,
    resultJson: resultJson,
    settled: settled,
    isSettled: isSettled,
    isExpired: isExpired,
    abortAsTimeout: abortAsTimeout,
    // 以下为纯函数，导出便于两端与测试直接复用
    normalizeToString: normalizeToString,
    inspectPayload: inspectPayload,
    extractCourseArray: extractCourseArray
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
