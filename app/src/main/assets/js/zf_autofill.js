// 正方自动填充脚本：填充账号密码并在可点击时触发登录
// 占位变量：{{USERNAME}} / {{PASSWORD}} / {{CLICK_LOGIN}}
(function(){
  try {
    const U = '{{USERNAME}}';
    const P = '{{PASSWORD}}';
    const clickLogin = '{{CLICK_LOGIN}}' === 'true';
    const userEl = document.getElementById('yhm') || document.querySelector('input[name="yhm"]');
    const passEl = document.getElementById('mm') || document.querySelector('input[name="mm"]');
    const hidMm = document.getElementById('hidMm') || document.querySelector('input[id*="hidMm"]');
    const loginBtn = document.getElementById('dl') || document.querySelector('#dl');
    const setValue = function(el, value){
      if (el) el.value = value;
    };
    const triggerEvents = function(el){
      if (!el || typeof el.dispatchEvent !== 'function') return;
      el.dispatchEvent(new Event('input', {bubbles:true}));
      el.dispatchEvent(new Event('change', {bubbles:true}));
      el.dispatchEvent(new Event('blur', {bubbles:true}));
    };
    const isVisible = function(el){
      if (!el) return false;
      const style = getComputedStyle(el);
      return style.display !== 'none' && style.visibility !== 'hidden';
    };
    setValue(userEl, U);
    setValue(passEl, P);
    setValue(hidMm, P);
    triggerEvents(userEl);
    triggerEvents(passEl);
    triggerEvents(hidMm);
    const yzmEl = document.getElementById('yzm') || document.querySelector('input[name="yzm"]');
    const yzmDiv = document.getElementById('yzmDiv') || document.querySelector('#yzmDiv');
    const needCaptcha = isVisible(yzmEl) || isVisible(yzmDiv);
    if (needCaptcha || !clickLogin) return "filled_no_click";
    if (loginBtn) {
      setTimeout(function(){ loginBtn.click(); }, 500);
      return "clicked";
    }
    const form = document.querySelector('form');
    if (form) { form.submit(); return "submitted"; }
    return "no_button";
  } catch(error) {
    return "error: " + (error?.message || "");
  }
})();
