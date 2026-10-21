// Muse Voice bridge — chay ben trong WebView trang Muse.
// Nhiem vu:
//  1) window.MuseVoiceSend(text): dien text vao o chat cua Muse va bam Gui.
//  2) Theo doi tin nhan MOI cua tro ly -> goi MuseVoiceNative.speak(text)
//     de app doc to thanh tieng.
// Luu y: DOM cua web co the thay doi theo phien ban. Cac selector o day la
// heuristic nhieu lop; neu gui tu dong that bai, app se bao de nguoi dung
// dan tay (noi MuseVoiceNative.onSendFailed).
(function () {
  if (window.__museVoiceBridge) return;
  window.__museVoiceBridge = true;

  function norm(s) { return (s || '').replace(/\s+/g, ' ').trim(); }

  // ---------- 1) Gui tin nhan ----------
  function findInput() {
    var sels = [
      'textarea',
      '[contenteditable="true"]',
      'div[role="textbox"]'
    ];
    for (var i = 0; i < sels.length; i++) {
      var els = document.querySelectorAll(sels[i]);
      for (var j = els.length - 1; j >= 0; j--) {
        var el = els[j];
        var r = el.getBoundingClientRect();
        if (r.width > 80 && r.height > 10 && r.bottom > 0) return el;
      }
    }
    return null;
  }

  function setText(el, text) {
    el.focus();
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      var proto = el.tagName === 'TEXTAREA'
        ? window.HTMLTextAreaElement.prototype
        : window.HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value');
      if (setter && setter.set) setter.set.call(el, text);
      else el.value = text;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } else {
      // contenteditable: dung execCommand de React nhan su kien dung cach
      document.execCommand('selectAll', false, null);
      document.execCommand('insertText', false, text);
      el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
    }
  }

  function findSendButton() {
    var btns = document.querySelectorAll('button, [role="button"]');
    for (var i = btns.length - 1; i >= 0; i--) {
      var b = btns[i];
      var label = norm(b.getAttribute('aria-label') || b.title || b.textContent).toLowerCase();
      if (label === 'send' || label.indexOf('gửi') >= 0 || label.indexOf('gui tin') >= 0 ||
          label.indexOf('send message') >= 0) {
        if (!b.disabled) return b;
      }
    }
    return null;
  }

  window.MuseVoiceSend = function (text) {
    try {
      var el = findInput();
      if (!el) { MuseVoiceNative.onSendFailed(text); return false; }
      setText(el, text);
      setTimeout(function () {
        var btn = findSendButton();
        if (btn) { btn.click(); MuseVoiceNative.onSendOk(text); }
        else {
          // Thu Enter nhu mot lan gui
          var ev = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true });
          el.dispatchEvent(ev);
          setTimeout(function () {
            var v = norm(el.value || el.textContent);
            if (v === '') MuseVoiceNative.onSendOk(text);
            else MuseVoiceNative.onSendFailed(text);
          }, 600);
        }
      }, 450);
      return true;
    } catch (e) {
      MuseVoiceNative.onSendFailed(text);
      return false;
    }
  };

  // ---------- 2) Theo doi tra loi moi cua tro ly ----------
  // Heuristic: thu thap cac khoi van ban "giong tin nhan" tren trang,
  // nho khoi cuoi cung da doc; khi xuat hien khoi moi dai hon / khac,
  // cho on dinh 1.2s (het streaming) roi moi doc.
  var lastSpoken = '';
  var pendingText = '';
  var pendingTimer = null;

  function collectText() {
    var sels = [
      '[data-testid*="message"]',
      '[class*="message"]',
      '[class*="Message"]',
      'article'
    ];
    var best = '';
    for (var i = 0; i < sels.length; i++) {
      var els = document.querySelectorAll(sels[i]);
      for (var j = 0; j < els.length; j++) {
        var t = norm(els[j].innerText);
        if (t.length > best.length) best = t;
      }
      if (best) break;
    }
    if (!best) {
      // Fallback: toan bo text trang (cat gon) — chi dung de phat hien thay doi
      best = norm(document.body ? document.body.innerText : '').slice(-2000);
    }
    return best;
  }

  function scheduleSpeak() {
    var now = collectText();
    if (!now || now === lastSpoken) return;
    pendingText = now;
    if (pendingTimer) clearTimeout(pendingTimer);
    pendingTimer = setTimeout(function () {
      // Chi doc phan MOI so voi lan doc truoc (neu la ban mo rong)
      var fresh = pendingText;
      if (lastSpoken && fresh.indexOf(lastSpoken) === 0) {
        fresh = fresh.slice(lastSpoken.length).trim();
      }
      lastSpoken = pendingText;
      if (fresh && fresh.length >= 2) {
        // Cat bot phan giao dien thuong gap o dau/cuoi khoi
        MuseVoiceNative.speak(fresh.slice(-1500));
      }
    }, 1400);
  }

  var obs = new MutationObserver(function () { scheduleSpeak(); });
  function startObserve() {
    if (document.body) {
      lastSpoken = collectText();
      obs.observe(document.body, { childList: true, subtree: true, characterData: true });
    } else {
      setTimeout(startObserve, 800);
    }
  }
  startObserve();
})();
