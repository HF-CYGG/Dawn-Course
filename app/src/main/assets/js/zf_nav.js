/**
 * 正方教务系统自动导航脚本
 * 负责自动点击菜单进入课表页面，并自动选择学年学期后查询。
 */
async function waitForSelector(sel, timeout){
  return new Promise((resolve,reject)=>{
    var t=0; var it=setInterval(function(){
      var el=document.querySelector(sel);
      if(el){ clearInterval(it); resolve(el); }
      t+=200; if(t>=timeout){ clearInterval(it); resolve(null); }
    },200);
  });
}
async function waitForCourseData(timeout){
  return new Promise((resolve,reject)=>{
    var t=0; var it=setInterval(function(){
      var hasItem = !!document.querySelector('.timetable_con')
        || !!document.querySelector('#kblist_table .timetable_con')
        || !!document.querySelector('#kbgrid_table_0 td[id] .timetable_con')
        || !!document.querySelector('td[id^="1-"] .timetable_con');
      if(hasItem){ clearInterval(it); resolve(true); }
      t+=200; if(t>=timeout){ clearInterval(it); resolve(false); }
    },200);
  });
}
async function openZfTimetableAndQuery(){
  if(document.querySelector('#ylkbTable')
    || document.querySelector('#ajaxForm')
    || document.querySelector('#kbtable')
    || document.querySelector('#kblist')
    || document.querySelector('#kblist_table')
    || document.querySelector('#kbgrid_table_0')){
  }else{
    var navLink = (function(){
      try{
        var r = document.evaluate('/html/body/div[3]/div/nav/ul/li[3]/ul/li[1]/a', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
        if(r && r.singleNodeValue){ return r.singleNodeValue; }
      }catch(e){}
      return document.querySelector('body > div:nth-of-type(3) > div > nav > ul > li:nth-child(3) > ul > li:nth-child(1) > a');
    })();
    if(navLink){ navLink.click(); }
    if(!navLink){
      var link = Array.from(document.querySelectorAll('a')).find(a=>{
        var href=(a.getAttribute('href')||'').toLowerCase();
        var txt=(a.textContent||'').trim();
      return href.indexOf('xskbcx')>=0 || href.indexOf('kbcx')>=0 || href.indexOf('grkb')>=0
        || txt.indexOf('个人课表查询')>=0 || txt.indexOf('课表查询')>=0 || txt.indexOf('课表')>=0;
      });
      if(link){ link.click(); }
    }
    await waitForSelector('#ajaxForm, #ylkbTable, #kbtable, #kblist, #kblist_table, #kbgrid_table_0', 8000);
  }
  
  try{
    // 修复：同时兼容 id 与 name 的学年学期下拉框
    var xnm=document.querySelector('#xnm') || document.querySelector('select[name="xnm"]');
    var xqm=document.querySelector('#xqm') || document.querySelector('select[name="xqm"]');
    var fixedYear='{{YEAR}}';
    var fixedTerm='{{TERM}}';
    if(xnm){
      if(fixedYear && fixedYear.length===4){ xnm.value=fixedYear; }
      if(!xnm.value){ for(var i=xnm.options.length-1;i>=0;i--){ if(xnm.options[i].value){ xnm.value=xnm.options[i].value; break; } } }
      xnm.dispatchEvent(new Event('change',{bubbles:true}));
    }
    if(xqm){
      if(fixedTerm){ xqm.value=fixedTerm; }
      if(!xqm.value){ for(var j=xqm.options.length-1;j>=0;j--){ if(xqm.options[j].value){ xqm.value=xqm.options[j].value; break; } } }
      xqm.dispatchEvent(new Event('change',{bubbles:true}));
    }
  }catch(e){}
  
  // 4) 点击查询按钮
  var btn=document.querySelector('#search_go') || Array.from(document.querySelectorAll('button')).find(b=>(b.textContent||'').indexOf('查询')>=0);
  if(btn){ btn.click(); }
  
  // 5) 等待表格渲染
  await waitForSelector('#ylkbTable, #kbtable, #kblist, #kblist_table, #kbgrid_table_0', 8000);
  await waitForCourseData(12000);
}
