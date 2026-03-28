// 正方课表页状态检测：检查学年学期选择与课表表格是否存在
(function(){
  try{
    const y = document.querySelector('#xnm') || document.querySelector('select[name="xnm"]');
    const t = document.querySelector('#xqm') || document.querySelector('select[name="xqm"]');
    const hasTable = !!document.querySelector('#kbtable') || !!document.querySelector('#kblist') || !!document.querySelector('#ylkbTable') || !!document.querySelector('#ajaxForm');
    return JSON.stringify({hasSelect:!!(y||t), hasTable:!!hasTable});
  }catch(error){
    return JSON.stringify({hasSelect:false, hasTable:false, errorMsg:(error&&error.message)||''});
  }
})();
