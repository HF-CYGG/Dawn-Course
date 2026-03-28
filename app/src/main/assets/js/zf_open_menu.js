// 正方菜单入口脚本：尝试点击“课表查询”相关菜单
(function(){
  try{
    const navLink = (function(){
      const r = document.evaluate('/html/body/div[3]/div/nav/ul/li[3]/ul/li[1]/a', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
      if (r?.singleNodeValue) { return r.singleNodeValue; }
      return document.querySelector('body > div:nth-of-type(3) > div > nav > ul > li:nth-child(3) > ul > li:nth-child(1) > a');
    })();
    if(navLink){ navLink.click(); return 'ok'; }
    const link = Array.from(document.querySelectorAll('a')).find(a=>{
      const href=(a.getAttribute('href')||'').toLowerCase();
      const txt=(a.textContent||'').trim();
      return href.includes('xskbcx') || href.includes('kbcx') || href.includes('grkb')
        || txt.includes('个人课表查询') || txt.includes('课表查询') || txt.includes('课表');
    });
    if(link){ link.click(); return 'ok'; }
    return 'none';
  }catch(error){ return 'err:' + (error?.message || ''); }
})();
