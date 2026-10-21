// Muse Voice bridge v6 — chay ben trong WebView trang Muse.
// V6 (thay chi ra nguyen nhan that): trang Muse lien tuc ve lai cau tra
// loi (hinh "media" nhap nhay che cho icon Muse) nen chu KHONG BAO GIO
// dung yen -> bo canh v4 cho chu on dinh ~1,8s la cho vo han -> khong bao
// gio doc. V6 canh theo dau hieu "da tra loi xong": nut Stop (■) trong
// khung soan bien mat = doc ngay khoi chu MOI dai nhat; on dinh chi la du
// phong. Them: thay GO TAY roi Enter/bam Gui tren trang thi cau noi cung
// tu canh doc (khong can nut giong noi cua app); cong tac tat tu doc o
// phia app dat co window.__museVoiceAutoRead.
// V5 (sua loi "khong dinh kem duoc anh"): anh chup that cua thay cho thay
// khung soan muse.ai chi co nut "+" (khong ghi chu dinh kem), bam vao co
// the mo MENU chon nguon anh chu khong tao ngay o chon file. V5 nhan ca
// nut "+", bam tiep muc tai anh trong menu, cho o chon file toi 3s, va
// them duong dan/tha file thang vao o nhap (co kiem chung anh da vao).
// V4 (sua loi "khong tu doc cau tra loi"): v3 lay "khoi chu cuoi cung tren
// trang" lam cau tra loi -> trang chat that luon co chu rac sau cau tra loi
// (nut bam, dong luu y, thanh cong cu) nen doc nham hoac cho on dinh mai
// khong xong -> im lang. V4: ngay khi gui xong, chup MOC toan bo khoi chu
// da co tren trang; sau do chi khoi chu MOI xuat hien (khong co trong moc),
// dai nhat va on dinh ~2 luot quet moi la cau tra loi -> doc khoi do.
// V3: quet khong doi nam trong khung hinh, nguong 12 ky tu, khong thay thi
// bao ly do ve native (reportNoReply). MuseVoiceAttach: gan anh vao trang.
(function () {
  if (window.__museVoiceBridgeV6) return;
  window.__museVoiceBridgeV6 = true;
  window.__museVoiceBridgeV5 = true;
  window.__museVoiceBridgeV4 = true;
  window.__museVoiceBridgeV3 = true;
  window.__museVoiceBridge = true;

  function norm(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function inView(el) {
    if (!el) return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < (window.innerHeight || 800);
  }
  function hasSize(el) {
    if (!el) return false;
    var r = el.getBoundingClientRect();
    return r.width > 40 && r.height > 0;
  }

  // ---------- tim o nhap (composer): cham diem theo goi y + vi tri ----------
  function findComposer() {
    var els = document.querySelectorAll('textarea, input[type="text"], input:not([type]), [contenteditable="true"], [contenteditable=""], [role="textbox"]');
    var best = null, bestScore = -1;
    var vh = window.innerHeight || 800;
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (!inView(el)) continue;
      var r = el.getBoundingClientRect();
      if (r.width < 60) continue;
      var score = 0;
      var hint = ((el.getAttribute('placeholder') || '') + ' ' +
                  (el.getAttribute('aria-label') || '') + ' ' +
                  (el.getAttribute('data-placeholder') || '')).toLowerCase();
      if (/message|chat|ask|nhắn|tin nhắn|hỏi|viết|write|prompt/.test(hint)) score += 50;
      if (r.bottom > vh * 0.5) score += 30;              // composer nam nua duoi
      if (r.width > 200) score += 15;
      if (el.tagName === 'TEXTAREA') score += 10;
      if (el.isContentEditable) score += 10;
      score += Math.min(20, r.width / 30);
      if (score > bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  // ---------- dat chu vao o nhap (nhieu chien luoc) ----------
  function setEditorText(el, text) {
    try { el.click(); } catch (e) {}
    el.focus();
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype
                                            : window.HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value');
      if (setter && setter.set) setter.set.call(el, text); else el.value = text;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return norm(el.value) === norm(text);
    }
    // contenteditable: chon het -> insertText (ProseMirror/Lexical/Slate deu nhan)
    try {
      var sel = window.getSelection();
      sel.removeAllRanges();
      var range = document.createRange();
      range.selectNodeContents(el);
      sel.addRange(range);
      document.execCommand('insertText', false, text);
    } catch (e) {}
    if (norm(el.innerText).indexOf(norm(text)) >= 0) return true;
    // du phong: su kien paste + input
    try {
      var dt = new DataTransfer();
      dt.setData('text/plain', text);
      el.dispatchEvent(new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: dt }));
    } catch (e) {}
    el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
    return norm(el.innerText).indexOf(norm(text)) >= 0;
  }

  // ---------- tim nut Gui quanh o nhap ----------
  function findSendNear(el) {
    var node = el;
    for (var up = 0; up < 6 && node; up++) {
      node = node.parentElement;
      if (!node) break;
      var btns = node.querySelectorAll('button, [role="button"]');
      var sameRow = null;
      for (var i = 0; i < btns.length; i++) {
        var b = btns[i];
        if (b.disabled || !inView(b)) continue;
        var lab = norm(b.getAttribute('aria-label') || b.title || b.textContent).toLowerCase();
        if (lab === 'send' || lab.indexOf('send message') >= 0 ||
            lab.indexOf('gửi') >= 0 || lab.indexOf('gui tin') >= 0) return b;
        var r = b.getBoundingClientRect(), er = el.getBoundingClientRect();
        if (Math.abs(r.top - er.top) < 70) sameRow = b;   // nut cung hang voi o nhap
      }
      if (sameRow) return sameRow;
    }
    return null;
  }

  function pressEnter(el) {
    ['keydown', 'keypress', 'keyup'].forEach(function (type) {
      el.dispatchEvent(new KeyboardEvent(type, {
        key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
        bubbles: true, cancelable: true
      }));
    });
  }

  // ---------- quet khoi cau tra loi ----------
  // useCut=true: chi lay khoi phia tren o nhap (tranh dinh phan tu duoi chat).
  // Khong doi khoi nam trong khung hinh: trang chat cuon, cau tra loi co the
  // nam ngoai vung nhin. Nguong 12 ky tu de cau tra loi ngan van doc duoc.
  function textBlocks(excludeEl, useCut) {
    var all = document.querySelectorAll('div, article, section, li, p');
    var raw = [];
    var compRect = (useCut && excludeEl) ? excludeEl.getBoundingClientRect() : null;
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (excludeEl && (e === excludeEl || e.contains(excludeEl) || excludeEl.contains(e))) continue;
      if (!hasSize(e)) continue;
      var r = e.getBoundingClientRect();
      if (compRect && r.bottom > compRect.bottom + 24) continue;
      var t = norm(e.innerText);
      if (t.length < 12) continue;
      raw.push({ el: e, text: t });
    }
    // giu khoi "la" (khong chua khoi ung vien khac) de tranh trung noi dung cha-con
    return raw.filter(function (b) {
      return !raw.some(function (o) { return o !== b && b.el.contains(o.el); });
    });
  }

  function deliverReply(t) {
    // Doc TRUOC, ghi nho sau (phong thu tu nguoc lai bi coi la da doc)
    MuseVoiceNative.speak(t.slice(-1800));
    try { MuseVoiceNative.noteReply(t); } catch (e) {}
  }

  // ---------- canh cau tra loi sau khi gui (v6: moc + dau hieu xong) ----------
  var expect = null;   // {sent, since, base:{text:true}, bestText, bestStable, sawStreaming}
  var lastBase = null; // moc cua lan gui gan nhat (cho nut Doc lai)

  // Chup moc: tap hop moi khoi chu dang co tren trang (tru vung o nhap)
  function snapshotTexts() {
    var set = {};
    var blocks = textBlocks(findComposer(), false);
    for (var i = 0; i < blocks.length; i++) set[blocks[i].text] = true;
    return set;
  }

  // Trang dang tra loi khi khung soan con nut Stop (■ "stop/dung")
  function isStreaming(comp) {
    var btns = areaButtons(comp || findComposer());
    for (var i = 0; i < btns.length; i++) {
      var lab = low(btns[i].getAttribute('aria-label') || btns[i].title || btns[i].textContent);
      if (lab.indexOf('stop') >= 0 || lab.indexOf('dung') >= 0) return true;
    }
    return false;
  }

  function finishExpect(t) {
    expect = null;
    if (window.__museVoiceAutoRead === false) return;  // thay da tat tu doc trong Cai dat
    deliverReply(t);
  }

  function pollReply() {
    if (!expect) return;
    if (Date.now() - expect.since > 120000) { expect = null; return; }
    var comp = findComposer();
    var streaming = isStreaming(comp);
    if (streaming) expect.sawStreaming = true;
    var blocks = textBlocks(comp, false);
    var best = null;
    for (var i = 0; i < blocks.length; i++) {
      var t = blocks[i].text;
      if (expect.base[t]) continue;                 // da co truoc khi gui: menu, chan trang, cau tra loi cu...
      if (t === expect.sent) continue;               // chinh cau hoi cua thay
      if (expect.sent.length > 20 && t.indexOf(expect.sent) >= 0) continue; // khoi chua nguyen cau hoi
      if (!best || t.length > best.text.length) best = blocks[i];
    }
    if (!best) return;
    // Dau hieu chinh: vua thay trang dang tra loi, gio nut Stop da bien mat
    // -> Muse tra loi xong, doc ngay (du chu con nhap nhay cung doc).
    if (expect.sawStreaming && !streaming) { finishExpect(best.text); return; }
    // Du phong: khoi chu dung yen ~2,7s
    if (best.text === expect.bestText) {
      expect.bestStable++;
      if (expect.bestStable >= 3) finishExpect(best.text);
    } else {
      expect.bestText = best.text;
      expect.bestStable = 0;
    }
  }
  setInterval(pollReply, 900);

  function armExpect(sentText) {
    setTimeout(function () {
      var base = snapshotTexts();
      lastBase = base;
      expect = { sent: sentText, since: Date.now(), base: base,
                 bestText: '', bestStable: 0, sawStreaming: false };
    }, 500);
  }

  // Thay GO TAY tren trang roi gui (khong qua nut cua app): nghe Enter /
  // bam nut Gui cua trang de tu canh cau tra loi ma doc to.
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' || e.shiftKey) return;
    var comp = findComposer();
    if (!comp || !(e.target === comp || comp.contains(e.target))) return;
    var txt = norm(comp.value !== undefined ? comp.value : comp.innerText);
    if (txt) armExpect(txt);
  }, true);
  document.addEventListener('click', function (e) {
    var comp = findComposer();
    if (!comp) return;
    var btns = areaButtons(comp);
    for (var i = 0; i < btns.length; i++) {
      if (btns[i].contains(e.target) && isSendBtn(btns[i])) {
        var txt = norm(comp.value !== undefined ? comp.value : comp.innerText);
        if (txt) armExpect(txt);
        break;
      }
    }
  }, true);

  // Doc cau tra loi gan nhat (nut "Doc lai" ben app goi vao): uu tien khoi
  // MOI dai nhat so voi moc lan gui gan nhat; chua co moc thi lay khoi dai
  // nhat toan trang (cau tra loi thuong dai hon han chu giao dien).
  // Khong thay gi -> bao ly do ve native de app NOI TO thay vi im lang.
  window.MuseVoiceReadLast = function () {
    var blocks = textBlocks(findComposer(), false);
    if (!blocks.length) {
      try { MuseVoiceNative.reportNoReply(); } catch (e) {}
      return false;
    }
    var best = null;
    if (lastBase) {
      for (var i = 0; i < blocks.length; i++) {
        if (lastBase[blocks[i].text]) continue;
        if (!best || blocks[i].text.length > best.text.length) best = blocks[i];
      }
    }
    if (!best) {
      for (var j = 0; j < blocks.length; j++) {
        if (!best || blocks[j].text.length > best.text.length) best = blocks[j];
      }
    }
    deliverReply(best.text);
    return true;
  };

  // ---------- dinh kem anh vao trang chat (v5) ----------
  function low(s) {
    return norm(s).toLowerCase().normalize('NFD')
      .replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd');
  }
  // (khoang dau ket hop U+0300-U+036F duoc xoa de khop nhan tieng Viet khong dau)

  function findFileInput() {
    var ins = document.querySelectorAll('input[type="file"]');
    var best = null;
    for (var i = 0; i < ins.length; i++) {
      var acc = (ins[i].getAttribute('accept') || '').toLowerCase();
      if (acc.indexOf('image') >= 0) return ins[i];
      if (!best) best = ins[i];
    }
    return best;
  }

  function areaButtons(comp) {
    var node = comp, area = null;
    for (var up = 0; up < 6 && node; up++) {
      node = node.parentElement;
      if (!node) break;
      if (node.querySelectorAll('button, [role="button"]').length >= 2) { area = node; break; }
    }
    if (!area) area = comp.parentElement || document.body;
    var btns = area.querySelectorAll('button, [role="button"]');
    var out = [];
    for (var i = 0; i < btns.length; i++) {
      if (!btns[i].disabled && inView(btns[i])) out.push(btns[i]);
    }
    return out;
  }

  function isSendBtn(b) {
    var lab = low(b.getAttribute('aria-label') || b.title || b.textContent);
    return lab === 'send' || lab.indexOf('send message') >= 0 ||
           lab.indexOf('gui') === 0 || lab.indexOf('stop') >= 0;
  }

  function findAttachButton(comp) {
    var btns = areaButtons(comp);
    var cr = comp.getBoundingClientRect();
    var leftBtn = null;
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      var lab = low(b.getAttribute('aria-label') || b.title || '');
      if (/attach|dinh kem|upload|tai len|them anh|add photo|image|photo|tep|file/.test(lab)) return b;
      if (norm(b.textContent) === '+') return b;   // nut "+" cua muse.ai
      var r = b.getBoundingClientRect();
      if (!isSendBtn(b) && r.right <= cr.left + 24 && !leftBtn) leftBtn = b;
    }
    if (leftBtn) return leftBtn;
    for (var j = 0; j < btns.length; j++) if (!isSendBtn(btns[j])) return btns[j];
    return null;
  }

  // Menu vua mo sau khi bam nut dinh kem: bam tiep muc tai anh len
  function tryClickMenuItem() {
    var els = document.querySelectorAll('[role="menuitem"], [role="option"], [role="menu"] *, [role="dialog"] *');
    for (var pass = 0; pass < 2; pass++) {
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        if (el.tagName === 'INPUT' || !inView(el)) continue;
        if (pass === 1) {
          var r = el.getBoundingClientRect();
          if (r.top < (window.innerHeight || 800) * 0.4) continue;  // chi quet nua duoi (gan khung soan)
        }
        var t = low(el.textContent);
        if (t.length > 45) continue;
        if (/tai anh|tai len|upload|photo|hinh anh|thu vien|gallery|tu thiet bi|chon anh|tep/.test(t)) {
          el.click();
          return true;
        }
      }
      els = document.querySelectorAll('button, [role="button"], li');
    }
    return false;
  }

  // Kiem chung anh da that su vao trang (anh xem truoc blob: hoac chip ten file)
  function attachLanded(filename) {
    var imgs = document.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      var src = imgs[i].getAttribute('src') || '';
      if (src.indexOf('blob:') === 0 && inView(imgs[i])) return true;
    }
    if (filename) {
      var all = document.querySelectorAll('[data-testid], [aria-label]');
      for (var j = 0; j < all.length; j++) {
        var lab = (all[j].getAttribute('aria-label') || '') + ' ' +
                  (all[j].getAttribute('data-testid') || '');
        if (lab.indexOf(filename) >= 0) return true;
      }
    }
    return false;
  }

  function pasteFile(target, file) {
    try {
      var dt = new DataTransfer();
      dt.items.add(file);
      target.dispatchEvent(new ClipboardEvent('paste',
        { bubbles: true, cancelable: true, clipboardData: dt }));
      var opts = { bubbles: true, cancelable: true, dataTransfer: dt };
      target.dispatchEvent(new DragEvent('dragenter', opts));
      target.dispatchEvent(new DragEvent('dragover', opts));
      target.dispatchEvent(new DragEvent('drop', opts));
      return true;
    } catch (e) { return false; }
  }

  function attachWithInput(input, file) {
    try {
      var dt = new DataTransfer();
      dt.items.add(file);
      input.files = dt.files;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      return !!(input.files && input.files.length > 0);
    } catch (e) { return false; }
  }

  window.MuseVoiceAttach = function (b64, filename) {
    try {
      var bin = atob(b64);
      var arr = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
      var file = new File([arr], filename || 'musevoice.jpg', { type: 'image/jpeg' });

      var input = findFileInput();
      if (input) {
        var ok = attachWithInput(input, file);
        MuseVoiceNative.onAttachResult(ok, ok ? 'attached' : 'input-reject');
        return ok;
      }
      var comp = findComposer();
      if (!comp) { MuseVoiceNative.onAttachResult(false, 'no-composer'); return false; }

      // Duong 1: dan/tha file thang vao o nhap, kiem chung sau 0,9s
      pasteFile(comp, file);
      setTimeout(function () {
        if (attachLanded(file.name)) {
          MuseVoiceNative.onAttachResult(true, 'pasted');
          return;
        }
        // Duong 2: bam nut dinh kem (ke ca nut "+") -> menu -> o chon file
        var btn = findAttachButton(comp);
        if (!btn) { MuseVoiceNative.onAttachResult(false, 'no-input-no-button'); return; }
        btn.click();
        var waited = 0, menuTried = false;
        var iv = setInterval(function () {
          waited += 300;
          var inp = findFileInput();
          if (inp) {
            clearInterval(iv);
            var ok2 = attachWithInput(inp, file);
            MuseVoiceNative.onAttachResult(ok2, ok2 ? 'attached-after-click' : 'input-reject-after-click');
            return;
          }
          if (attachLanded(file.name)) {
            clearInterval(iv);
            MuseVoiceNative.onAttachResult(true, 'menu-attached');
            return;
          }
          if (!menuTried && waited >= 900) { menuTried = true; tryClickMenuItem(); }
          if (waited >= 3000) {
            clearInterval(iv);
            MuseVoiceNative.onAttachResult(false, 'no-input-after-click');
          }
        }, 300);
      }, 900);
      return true;
    } catch (e) {
      try { MuseVoiceNative.onAttachResult(false, 'exception'); } catch (e2) {}
      return false;
    }
  };

  // ---------- gui tin nhan ----------
  window.MuseVoiceSend = function (text) {
    try {
      var el = findComposer();
      if (!el) { MuseVoiceNative.onSendFailed(text); return false; }
      if (!setEditorText(el, text)) { MuseVoiceNative.onSendFailed(text); return false; }
      setTimeout(function () {
        var btn = findSendNear(el);
        if (btn) btn.click(); else pressEnter(el);
        var tries = 0;
        var iv = setInterval(function () {
          tries++;
          var v = norm(el.value !== undefined ? el.value : el.innerText);
          if (v === '' || v !== norm(text)) {
            clearInterval(iv);
            // Gui xong moi chup moc: luc nay cau hoi cua thay da hien tren
            // trang nen nam trong moc; chi cau tra loi sap toi la "moi".
            var base = snapshotTexts();
            lastBase = base;
            expect = { sent: norm(text), since: Date.now(), base: base, bestText: '', bestStable: 0, sawStreaming: false };
            MuseVoiceNative.onSendOk(text);
          } else if (tries >= 5) {
            clearInterval(iv);
            MuseVoiceNative.onSendFailed(text);
          }
        }, 400);
      }, 350);
      return true;
    } catch (e) {
      MuseVoiceNative.onSendFailed(text);
      return false;
    }
  };
})();
