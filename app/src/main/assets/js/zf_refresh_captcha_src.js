// 正方验证码刷新：获取验证码图片的完整地址
(function(){
  const i = document.querySelector('#yzmPic');
  if (!i) return '';
  const s = i.getAttribute('src') || '';
  const a = document.createElement('a');
  a.href = s;
  return a.href || s;
})()
