// 正方页面状态检测脚本：识别登录页、验证码与课表页状态
// 返回 JSON 字符串供 WebView 端解析
(function(){
  try{
    const getText = function(el){
      return (el?.innerText || '').trim();
    };
    const hasVisible = function(el){
      if (!el) return false;
      const cs = getComputedStyle(el);
      return cs.display !== 'none' && cs.visibility !== 'hidden';
    };
    const resolveImgSrc = function(img){
      if (!img) return '';
      const s = img.getAttribute('src') || '';
      if (!s) return '';
      const a = document.createElement('a');
      a.href = s;
      return a.href || s;
    };
    const findNavLink = function(){
      const r = document.evaluate('/html/body/div[3]/div/nav/ul/li[3]/ul/li[1]/a', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
      if (r?.singleNodeValue) return r.singleNodeValue;
      return document.querySelector('body > div:nth-of-type(3) > div > nav > ul > li:nth-child(3) > ul > li:nth-child(1) > a');
    };
    const findMenuLink = function(){
      return Array.from(document.querySelectorAll('a')).find(a=>{
        const href = (a.getAttribute('href') || '').toLowerCase();
        const txt = (a.textContent || '').trim();
        return href.includes('xskbcx') || href.includes('kbcx') || href.includes('grkb')
          || txt.includes('个人课表查询') || txt.includes('课表查询') || txt.includes('课表');
      });
    };
    const findLogoutLink = function(){
      return Array.from(document.querySelectorAll('a,button')).find(function(el){
        const txt = (el.textContent || '').trim();
        const href = (el.getAttribute?.('href') || '').toLowerCase();
        return txt.includes('退出') || txt.includes('注销') || href.includes('logout') || href.includes('logoff') || href.includes('exit');
      });
    };
    const tips = document.querySelector('#tips');
    const bootbox = document.querySelector('.bootbox-body');
    let tipTxt = getText(tips);
    if (bootbox && bootbox.innerText.length > 0) {
      tipTxt = bootbox.innerText.trim();
    }
    let hasWrong = tipTxt.includes('用户名或密码不正确') || tipTxt.includes('错误') || tipTxt.includes('不存在');
    if (tips && tips.style.display !== 'none' && tipTxt.length > 0) {
      hasWrong = true;
    }
    const yzmDiv = document.querySelector('#yzmDiv');
    const yzmInput = document.querySelector('#yzm') || document.querySelector('input[name="yzm"]') || document.querySelector('input[id*="yzm"]');
    const yzmImg = document.querySelector('#yzmPic') || document.querySelector('img[src*="yzm"]') || document.querySelector('img[src*="captcha"]') || document.querySelector('img[src*="validate"]');
    const yzmVis = hasVisible(yzmDiv) || hasVisible(yzmInput);
    const yzmSrc = (yzmImg && yzmVis) ? resolveImgSrc(yzmImg) : '';
    const userEl = document.querySelector('#yhm')
      || document.querySelector('input[name="yhm"]')
      || document.querySelector('input[name="username"]')
      || document.querySelector('input[name="user"]')
      || document.querySelector('input[name*="account"]')
      || document.querySelector('input[id*="user"]')
      || document.querySelector('input[id*="account"]')
      || document.querySelector('input[name="xh"]')
      || document.querySelector('input[id*="xh"]');
    const passEl = document.querySelector('#mm')
      || document.querySelector('input[name="mm"]')
      || document.querySelector('input[name*="pwd"]')
      || document.querySelector('input[id*="pwd"]')
      || document.querySelector('input[type="password"]');
    const loginBtn = document.querySelector('#dl')
      || document.querySelector('#login')
      || document.querySelector('button[type="submit"]')
      || document.querySelector('input[type="submit"]')
      || document.querySelector('button[id*="login"]')
      || document.querySelector('button[name*="login"]')
      || document.querySelector('input[id*="login"]')
      || document.querySelector('input[name*="login"]');
    const isLogin = !!document.querySelector('#yhm') || !!document.querySelector('#dl') || (!!passEl && (!!userEl || !!loginBtn));
    const isKebiao = !!document.querySelector('#ylkbTable')
      || !!document.querySelector('#ajaxForm')
      || !!document.querySelector('#kbtable')
      || !!document.querySelector('#kblist')
      || !!document.querySelector('#kblist_table')
      || !!document.querySelector('#kbgrid_table_0');
    const y = document.querySelector('#xnm') || document.querySelector('select[name="xnm"]');
    const t = document.querySelector('#xqm') || document.querySelector('select[name="xqm"]');
    const hasSelect = !!(y || t);
    const navLink = findNavLink();
    const menuLink = findMenuLink();
    const hasMenu = !!(navLink || menuLink);
    const logoutLink = findLogoutLink();
    const loggedIn = !!logoutLink;
    const href = location.href;
    return JSON.stringify({wrong:!!hasWrong, errorMsg:tipTxt, yzm:!!yzmVis, yzmSrc:yzmSrc, isLogin:isLogin, isKebiao:isKebiao, hasSelect:hasSelect, hasMenu:hasMenu, loggedIn:loggedIn, href:href});
  }catch(error){ return JSON.stringify({wrong:false,errorMsg:'',yzm:false,yzmSrc:'',isLogin:false,isKebiao:false,href:'', errorMsgDetail:(error?.message)||''}); }
})();
