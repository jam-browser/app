// 미러링 시험 조각(avc.jsonl · vp8.jsonl) 다시 만들기: <데스크톱>/node_modules/electron/dist/electron.exe main.js <이 폴더 절대 경로>
// 미러링 시험용 영상 조각 만들기: 데스크톱 mirror.js 와 같은 WebCodecs 설정으로 캔버스 애니메이션을 인코딩
// electron.exe main.js <출력폴더>
const { app, BrowserWindow, ipcMain } = require('electron');
const fs = require('fs');
const path = require('path');
const out = process.argv[process.argv.length - 1];
app.whenReady().then(() => {
  const w = new BrowserWindow({ show: false, webPreferences: { nodeIntegration: true, contextIsolation: false, backgroundThrottling: false } });
  ipcMain.on('done', (_e, name, lines) => {
    fs.writeFileSync(path.join(out, name), lines.join('\n') + '\n');
    console.log('wrote', name, lines.length);
  });
  ipcMain.on('log', (_e, m) => console.log(m));
  ipcMain.on('quit', () => app.quit());
  w.loadFile(path.join(__dirname, 'enc.html'));
});
