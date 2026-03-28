// 自动更新提取脚本：根据是否正方决定抓取策略
// 占位变量：{{IS_ZF}}
(function(){
  const isZf = '{{IS_ZF}}' === 'true';
  const finish = function(result){
    globalThis.__dawnResult = result;
    globalThis.__dawnReady = true;
  };
  const runProvider = function(){
    if (typeof scheduleHtmlProvider !== 'function') return Promise.resolve('skip');
    return Promise.resolve(scheduleHtmlProvider());
  };
  const runZf = function(){
    if (!isZf || typeof openZfTimetableAndQuery !== 'function') return Promise.resolve();
    return Promise.resolve(openZfTimetableAndQuery());
  };

  runProvider()
    .then(function(result){
      if (!isZf && result !== 'do not continue') {
        finish(result);
        return 'done';
      }
      return runZf().then(function(){ return 'done'; });
    })
    .catch(function(error){
      finish(document.documentElement.outerHTML);
      return error;
    })
    .then(function(state){
      if (state !== 'done') finish(document.documentElement.outerHTML);
    });
})();
