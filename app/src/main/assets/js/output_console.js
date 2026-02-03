/**
 * 输出提示栏 需要有dom环境
 * @version 0.9
 */
async function newLogFrame() {
  // 删除已存在frame
  document.querySelectorAll(".xiaoai-schedule-parser-log-base")?.forEach(e => e.remove());
  // 设置背景
  const baseElement = document.createElement("div");
  document.body.append(baseElement);
  baseElement.className = "xiaoai-schedule-parser-log-base";
  baseElement.style.cssText = `
      position: fixed;
      top: 0;
      left: 0;
      width: 100%;
      height: 100%;
      background: rgba(127, 127, 127, 0.28);
      backdrop-filter: saturate(120%) blur(8px);
      display: flex;
      justify-content: center;
      align-items: center;
      z-index: ${getMaxZIndex() + 1};
    `;
  function getMaxZIndex() {
    return [...document.querySelectorAll("*")].reduce((r, e) => Math.max(r, +window.getComputedStyle(e).zIndex || 0), 0) || 1
  }
  // 设置卡片
  const cardElement = document.createElement("iframe");
  // cardElement.src = "about:blank";
  baseElement.append(cardElement);
  // await new Promise(r => {
  //   const interval = setInterval(() => {
  //     console.log(1)
  //     if (cardElement.contentDocument || cardElement.contentWindow?.document) {
  //       clearInterval(interval);
  //       r();
  //     }
  //   }, 1);
  // });
  cardElement.style.cssText = `
    display: block;
    width: 100vh;
    max-width: calc(80% - 40px);
    max-height: calc(80% - 40px);
    border: none;
    border-radius: 10px;
    padding: 20px;
    background: rgba(255, 255, 255, 0.9);
    box-shadow: 0 4px 10px rgba(0, 0, 0, 0.1);
    box-sizing: content-box;
    overflow: hidden;
    scroll-behavior: smooth;
  `;
  const iframeDocument = cardElement.contentDocument || cardElement.contentWindow?.document;
  // 自动调整高度
  const observer = new MutationObserver(() => {
    cardElement.style.height = (iframeDocument.body.scrollHeight + 1) + "px"; // 有小数部分所以+1
  });
  observer.observe(iframeDocument.body, { childList: true, subtree: true, attributes: true });
  
  // 点击外围退出
  baseElement.addEventListener("click", e => {
    if (!cardElement.contains(e.target)) {
      baseElement.remove();
    }
  });

  function log(...msgs) {
    for (const msg of msgs) {
      console.log(msg);
      if (typeof msg === "string") {
        const e = document.createElement("span");
        e.innerHTML = msg;
        iframeDocument.body.append(...e.childNodes);
      }
      else {
        iframeDocument.body.append(msg);
      }
    }
    // 自动滚动到底部
    iframeDocument.body.scrollTop = iframeDocument.body.scrollHeight;
    if(iframeDocument.documentElement) iframeDocument.documentElement.scrollTop = iframeDocument.body.scrollHeight;
  }

  function copyButton(str) {
    const copyButton = document.createElement("button");
    copyButton.textContent = "点击复制";
    copyButton.style.cssText = "margin: 0 4px; padding: 2px 6px; border-radius: 4px; border: 1px solid #ccc; background: #fff; cursor: pointer;";
    copyButton.addEventListener("click", async e => {
      const clipboard = navigator.clipboard ?? {
        writeText: async (s) => new Promise(resolve => {
          const input = document.createElement('input');
          input.style.position = "absolute";
          input.style.left = "-100vw";
          input.value = s;
          document.body.append(input);
          input.select();
          document.execCommand('copy');
          input.remove();
          resolve();
        })
      };
      await clipboard.writeText(String(str));
      e.target.textContent = "已复制";
      setTimeout(() => e.target.textContent = "点击复制", 2000);
    });
    return copyButton;
  }

  function codeBlock(str) {
    const code = document.createElement("code");
    code.style.cssText = `
      background-color: #eee;
      border-radius: 0.25em;
      font-family: monospace;
      padding: 0 0.5em;
    `;
    code.textContent = String(str);
    return code;
  }

  function codeBlockShort(str) {
    return codeBlock(String(str).replace(/^([\s\S]{10})[\s\S]*$/, "$1..."));
  }

  function repoLink() {
    const e = document.createElement("span");
    e.append("如果你需要查看本适配项目的源代码: ");
    e.append(codeBlock("https://github.com/kqakqakqa/xiaoai-schedule-parser"));
    e.append(" ");
    e.append(copyButton("https://github.com/kqakqakqa/xiaoai-schedule-parser"));
    return e;
  }

  function jumpToPage() {
    const input = document.createElement("input");
    input.type = "text";
    input.placeholder = "网页地址";

    const button = document.createElement("button");
    button.textContent = "跳转";
    button.addEventListener("click", () => {
      const url = input.value.trim();
      if (url) {
        const validUrl = /^https?:\/\//.test(url) ? url : "https://" + url;
        window.location.href = validUrl;
      }
    });

    const e = document.createElement("span");
    e.append("如果你需要跳转到别的地址: ", input, " ", button);
    return e;
  }

  baseElement.log = log;
  baseElement.copyButton = copyButton;
  baseElement.codeBlock = codeBlock;
  baseElement.codeBlockShort = codeBlockShort;
  baseElement.repoLink = repoLink;
  baseElement.jumpToPage = jumpToPage;
  
  // 注入样式到 iframe
  const style = document.createElement('style');
  style.textContent = `
    body { font-family: sans-serif; line-height: 1.5; color: #333; word-break: break-all; }
    button { cursor: pointer; }
  `;
  iframeDocument.head.append(style);

  return baseElement;

}
