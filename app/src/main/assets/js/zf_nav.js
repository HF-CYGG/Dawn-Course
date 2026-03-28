/**
 * 正方教务系统自动导航脚本
 * 负责自动点击菜单进入课表页面，并自动选择学年学期后查询。
 */
async function waitForSelector(sel, timeout){
  return new Promise((resolve)=>{
    let t = 0;
    const it = setInterval(function(){
      const el = document.querySelector(sel);
      if(el){ clearInterval(it); resolve(el); return; }
      t += 200;
      if(t>=timeout){ clearInterval(it); resolve(null); }
    },200);
  });
}
async function waitForCourseData(timeout){
  return new Promise((resolve)=>{
    let t = 0;
    const it = setInterval(function(){
      const hasItem = !!document.querySelector('.timetable_con')
        || !!document.querySelector('#kblist_table .timetable_con')
        || !!document.querySelector('#kbgrid_table_0 td[id] .timetable_con')
        || !!document.querySelector('td[id^="1-"] .timetable_con');
      if(hasItem){ clearInterval(it); resolve(true); return; }
      t += 200;
      if(t>=timeout){ clearInterval(it); resolve(false); }
    },200);
  });
}
const isTimetablePage = function(){
  return !!document.querySelector('#ylkbTable')
    || !!document.querySelector('#ajaxForm')
    || !!document.querySelector('#kbtable')
    || !!document.querySelector('#kblist')
    || !!document.querySelector('#kblist_table')
    || !!document.querySelector('#kbgrid_table_0');
};
const findNavLink = function(){
  const r = document.evaluate('/html/body/div[3]/div/nav/ul/li[3]/ul/li[1]/a', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
  if(r?.singleNodeValue){ return r.singleNodeValue; }
  return document.querySelector('body > div:nth-of-type(3) > div > nav > ul > li:nth-child(3) > ul > li:nth-child(1) > a');
};
const findMenuLink = function(){
  return Array.from(document.querySelectorAll('a')).find(a=>{
    const href=(a.getAttribute('href')||'').toLowerCase();
    const txt=(a.textContent||'').trim();
    return href.includes('xskbcx') || href.includes('kbcx') || href.includes('grkb')
      || txt.includes('个人课表查询') || txt.includes('课表查询') || txt.includes('课表');
  });
};
const ensureTimetablePage = async function(){
  if(isTimetablePage()) return true;
  const navLink = findNavLink();
  if(navLink){ navLink.click(); }
  if(!navLink){
    const link = findMenuLink();
    if(link){ link.click(); }
  }
  await waitForSelector('#ajaxForm, #ylkbTable, #kbtable, #kblist, #kblist_table, #kbgrid_table_0', 8000);
  return isTimetablePage();
};
const setSelectValue = function(selectEl, targetValue){
  if(!selectEl) return false;
  if(targetValue){ selectEl.value = targetValue; }
  if(!selectEl.value && selectEl.options?.length){
    for(let i=selectEl.options.length-1;i>=0;i--){ if(selectEl.options[i].value){ selectEl.value=selectEl.options[i].value; break; } }
  }
  selectEl.dispatchEvent(new Event('change',{bubbles:true}));
  return true;
};
const selectYearTerm = function(){
  const xnm=document.querySelector('#xnm') || document.querySelector('select[name="xnm"]');
  const xqm=document.querySelector('#xqm') || document.querySelector('select[name="xqm"]');
  const fixedYear='{{YEAR}}';
  const fixedTerm='{{TERM}}';
  const yearValue = fixedYear.length===4 ? fixedYear : '';
  if(xnm){ setSelectValue(xnm, yearValue); }
  if(xqm){ setSelectValue(xqm, fixedTerm || ''); }
};
const clickSearch = function(){
  const btn=document.querySelector('#search_go') || Array.from(document.querySelectorAll('button')).find(b=>(b.textContent||'').includes('查询'));
  if(btn){ btn.click(); }
};
async function openZfTimetableAndQuery(){
  await ensureTimetablePage();
  selectYearTerm();
  clickSearch();
  await waitForSelector('#ylkbTable, #kbtable, #kblist, #kblist_table, #kbgrid_table_0', 8000);
  await waitForCourseData(12000);
}
