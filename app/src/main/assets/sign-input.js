(() => {
  if (window.__nbuSignInput) return;
  const trusted = () => location.origin === 'https://attendance.nbu.edu.cn' && location.pathname.replace(/\/$/, '') === '/h5/student/sign';
  window.__nbuSignInput = {
    read() {
      if (!trusted()) return null;
      const box = document.querySelector('.code-view');
      if (!box) return null;
      const code = box.textContent.replace(/[\s\u00a0]/g, '');
      return /^[0-9]{0,4}$/.test(code) ? code : null;
    },
    click(key) {
      if (!trusted()) return false;
      const button = [...document.querySelectorAll('.key-board span,.key-board button,.key-board div')]
        .find(e => e.childElementCount === 0 && e.textContent.trim() === key && e.getClientRects().length);
      if (!button) return false;
      button.click();
      return true;
    }
  };
})();
