'use strict';
// ── Stun 通用 SQLite 管理台前端 ──
// 与后端 DbWebServer 的 /api/* 契约对齐：会话 cookie 自动携带，401 回到登录。
const I18N = {
  'zh-CN': {
    page_title: 'SQLite 管理台', app_name: 'Stun 数据库管理台',
    label_user: '用户名', label_pass: '密码', btn_login: '登录', btn_logout: '退出',
    login_failed: '用户名或密码错误', too_many_attempts: '失败次数过多，请 {sec} 秒后重试',
    tables: '表', filter_tables: '过滤表名', tab_browse: '浏览', tab_sql: 'SQL 控制台',
    search_ph: '搜索值', btn_search: '搜索', btn_add_row: '＋ 新增行', btn_refresh: '刷新',
    pick_table: '选择左侧表以浏览数据', btn_prev: '上一页', btn_next: '下一页',
    rows_page: '{from}-{to} / 共 {total}', actions: '操作',
    btn_run: '执行', sql_danger: '⚠ 可执行任意 SQL，含 DROP/ALTER，会破坏数据库结构，请谨慎。',
    btn_cancel: '取消', btn_save: '保存',
    add_row_title: '新增行：{table}', edit_row_title: '编辑行：{table}',
    set_null: '置为 NULL', confirm_delete: '确定删除该行？此操作不可撤销。',
    saved: '已保存', deleted: '已删除', inserted: '已新增', load_failed: '加载失败',
    no_pks: '该表无主键，无法通过界面编辑/删除', sql_error: 'SQL 错误', affected: '影响 {n} 行',
    col_all: '全部列', invalid_json: '响应解析失败',
    tab_backup: '备份', btn_export_json: '导出整库 JSON', btn_export_sql: '导出整库 SQL', btn_export_csv: '导出当前表 CSV',
    pick_table_first: '请先在"浏览"页选择一张表', backup_name_ph: '备份名称（可选）', btn_backup_create: '创建备份', btn_import: '导入备份文件',
    backup_note: '⚠ 恢复/导入会清空并替换同名表的全部数据，操作前建议先导出。', no_backups: '暂无备份',
    col_file: '文件', col_size: '大小', col_time: '时间', btn_restore: '恢复', btn_download: '下载', btn_delete: '删除',
    confirm_restore: '确定用该备份恢复数据库？同名表的全部数据将被替换，且不可撤销。',
    confirm_import: '确定导入该备份文件？同名表的全部数据将被替换，且不可撤销。', confirm_delete_backup: '删除该备份文件？',
    backup_created: '备份已创建', backup_restored: '恢复完成', backup_imported: '导入完成', backup_deleted: '已删除',
  },
  'zh-TW': {
    page_title: 'SQLite 管理台', app_name: 'Stun 資料庫管理台',
    label_user: '使用者名稱', label_pass: '密碼', btn_login: '登入', btn_logout: '登出',
    login_failed: '使用者名稱或密碼錯誤', too_many_attempts: '失敗次數過多，請 {sec} 秒後重試',
    tables: '資料表', filter_tables: '過濾資料表名', tab_browse: '瀏覽', tab_sql: 'SQL 主控台',
    search_ph: '搜尋值', btn_search: '搜尋', btn_add_row: '＋ 新增列', btn_refresh: '重新整理',
    pick_table: '選擇左側資料表以瀏覽資料', btn_prev: '上一頁', btn_next: '下一頁',
    rows_page: '{from}-{to} / 共 {total}', actions: '操作',
    btn_run: '執行', sql_danger: '⚠ 可執行任意 SQL，含 DROP/ALTER，會破壞資料庫結構，請謹慎。',
    btn_cancel: '取消', btn_save: '儲存',
    add_row_title: '新增列：{table}', edit_row_title: '編輯列：{table}',
    set_null: '設為 NULL', confirm_delete: '確定刪除該列？此操作無法復原。',
    saved: '已儲存', deleted: '已刪除', inserted: '已新增', load_failed: '載入失敗',
    no_pks: '該資料表無主鍵，無法透過介面編輯/刪除', sql_error: 'SQL 錯誤', affected: '影響 {n} 列',
    col_all: '全部欄位', invalid_json: '回應解析失敗',
    tab_backup: '備份', btn_export_json: '匯出整庫 JSON', btn_export_sql: '匯出整庫 SQL', btn_export_csv: '匯出目前資料表 CSV',
    pick_table_first: '請先在「瀏覽」頁選擇一個資料表', backup_name_ph: '備份名稱（選填）', btn_backup_create: '建立備份', btn_import: '匯入備份檔案',
    backup_note: '⚠ 恢復/匯入會清空並取代同名資料表的全部資料，操作前建議先匯出。', no_backups: '尚無備份',
    col_file: '檔案', col_size: '大小', col_time: '時間', btn_restore: '恢復', btn_download: '下載', btn_delete: '刪除',
    confirm_restore: '確定用該備份恢復資料庫？同名資料表的全部資料將被取代，且無法復原。',
    confirm_import: '確定匯入該備份檔案？同名資料表的全部資料將被取代，且無法復原。', confirm_delete_backup: '刪除該備份檔案？',
    backup_created: '備份已建立', backup_restored: '恢復完成', backup_imported: '匯入完成', backup_deleted: '已刪除',
  },
  'en': {
    page_title: 'SQLite Console', app_name: 'Stun Database Console',
    label_user: 'Username', label_pass: 'Password', btn_login: 'Sign in', btn_logout: 'Sign out',
    login_failed: 'Invalid username or password', too_many_attempts: 'Too many attempts, retry in {sec}s',
    tables: 'Tables', filter_tables: 'Filter tables', tab_browse: 'Browse', tab_sql: 'SQL Console',
    search_ph: 'search value', btn_search: 'Search', btn_add_row: '＋ New row', btn_refresh: 'Refresh',
    pick_table: 'Pick a table on the left to browse data', btn_prev: 'Prev', btn_next: 'Next',
    rows_page: '{from}-{to} of {total}', actions: 'Actions',
    btn_run: 'Run', sql_danger: '⚠ Arbitrary SQL allowed, including DROP/ALTER, which can damage the schema. Use with care.',
    btn_cancel: 'Cancel', btn_save: 'Save',
    add_row_title: 'New row: {table}', edit_row_title: 'Edit row: {table}',
    set_null: 'Set NULL', confirm_delete: 'Delete this row? This cannot be undone.',
    saved: 'Saved', deleted: 'Deleted', inserted: 'Inserted', load_failed: 'Load failed',
    no_pks: 'This table has no primary key; edit/delete via UI unavailable', sql_error: 'SQL error', affected: '{n} row(s) affected',
    col_all: 'All columns', invalid_json: 'Response parse failed',
    tab_backup: 'Backups', btn_export_json: 'Export DB (JSON)', btn_export_sql: 'Export DB (SQL)', btn_export_csv: 'Export current table (CSV)',
    pick_table_first: 'Pick a table in Browse first', backup_name_ph: 'backup name (optional)', btn_backup_create: 'Create backup', btn_import: 'Import backup file',
    backup_note: '⚠ Restore/import replaces all rows of matching tables. Export first if unsure.', no_backups: 'No backups yet',
    col_file: 'File', col_size: 'Size', col_time: 'Time', btn_restore: 'Restore', btn_download: 'Download', btn_delete: 'Delete',
    confirm_restore: 'Restore this backup? All rows of matching tables will be replaced. This cannot be undone.',
    confirm_import: 'Import this backup file? All rows of matching tables will be replaced. This cannot be undone.', confirm_delete_backup: 'Delete this backup file?',
    backup_created: 'Backup created', backup_restored: 'Restore done', backup_imported: 'Import done', backup_deleted: 'Deleted',
  },
  'ja': {
    page_title: 'SQLite コンソール', app_name: 'Stun データベースコンソール',
    label_user: 'ユーザー名', label_pass: 'パスワード', btn_login: 'ログイン', btn_logout: 'ログアウト',
    login_failed: 'ユーザー名またはパスワードが違います', too_many_attempts: '試行回数が多すぎます。{sec} 秒後に再試行',
    tables: 'テーブル', filter_tables: 'テーブルを絞り込み', tab_browse: '閲覧', tab_sql: 'SQL コンソール',
    search_ph: '値を検索', btn_search: '検索', btn_add_row: '＋ 行を追加', btn_refresh: '更新',
    pick_table: '左のテーブルを選択してデータを閲覧', btn_prev: '前へ', btn_next: '次へ',
    rows_page: '{from}-{to} / 全 {total} 件', actions: '操作',
    btn_run: '実行', sql_danger: '⚠ DROP/ALTER を含む任意の SQL を実行できます。構造を破壊する恐れがあり注意してください。',
    btn_cancel: 'キャンセル', btn_save: '保存',
    add_row_title: '行を追加：{table}', edit_row_title: '行を編集：{table}',
    set_null: 'NULL に設定', confirm_delete: 'この行を削除しますか？元に戻せません。',
    saved: '保存しました', deleted: '削除しました', inserted: '追加しました', load_failed: '読み込みに失敗',
    no_pks: 'このテーブルは主キーが無く、UI からの編集/削除はできません', sql_error: 'SQL エラー', affected: '{n} 行に影響',
    col_all: '全列', invalid_json: '応答の解析に失敗',
    tab_backup: 'バックアップ', btn_export_json: 'DB 全体をエクスポート (JSON)', btn_export_sql: 'DB 全体をエクスポート (SQL)', btn_export_csv: '現在のテーブルをエクスポート (CSV)',
    pick_table_first: 'まず「閲覧」でテーブルを選択してください', backup_name_ph: 'バックアップ名（任意）', btn_backup_create: 'バックアップ作成', btn_import: 'バックアップファイルをインポート',
    backup_note: '⚠ 復元/インポートは同名テーブルの全データを消去・置換します。不安なら先にエクスポートしてください。', no_backups: 'バックアップはまだありません',
    col_file: 'ファイル', col_size: 'サイズ', col_time: '日時', btn_restore: '復元', btn_download: 'ダウンロード', btn_delete: '削除',
    confirm_restore: 'このバックアップで復元しますか？同名テーブルの全データが置換され、元に戻せません。',
    confirm_import: 'このバックアップファイルをインポートしますか？同名テーブルの全データが置換され、元に戻せません。', confirm_delete_backup: 'このバックアップファイルを削除しますか？',
    backup_created: 'バックアップを作成しました', backup_restored: '復元完了', backup_imported: 'インポート完了', backup_deleted: '削除しました',
  },
  'de': {
    page_title: 'SQLite-Konsole', app_name: 'Stun-Datenbankkonsole',
    label_user: 'Benutzername', label_pass: 'Passwort', btn_login: 'Anmelden', btn_logout: 'Abmelden',
    login_failed: 'Ungültiger Benutzername oder Passwort', too_many_attempts: 'Zu viele Versuche, erneut in {sec}s',
    tables: 'Tabellen', filter_tables: 'Tabellen filtern', tab_browse: 'Durchsuchen', tab_sql: 'SQL-Konsole',
    search_ph: 'Wert durchsuchen', btn_search: 'Suchen', btn_add_row: '＋ Zeile hinzufügen', btn_refresh: 'Aktualisieren',
    pick_table: 'Links eine Tabelle wählen, um Daten zu durchsuchen', btn_prev: 'Zurück', btn_next: 'Weiter',
    rows_page: '{from}-{to} von {total}', actions: 'Aktionen',
    btn_run: 'Ausführen', sql_danger: '⚠ Beliebige SQL möglich, inkl. DROP/ALTER – kann das Schema beschädigen. Vorsicht.',
    btn_cancel: 'Abbrechen', btn_save: 'Speichern',
    add_row_title: 'Neue Zeile: {table}', edit_row_title: 'Zeile bearbeiten: {table}',
    set_null: 'Auf NULL setzen', confirm_delete: 'Diese Zeile löschen? Nicht widerrufbar.',
    saved: 'Gespeichert', deleted: 'Gelöscht', inserted: 'Eingefügt', load_failed: 'Laden fehlgeschlagen',
    no_pks: 'Diese Tabelle hat keinen Primärschlüssel; Bearbeitung/Löschung per UI nicht möglich', sql_error: 'SQL-Fehler', affected: '{n} Zeile(n) betroffen',
    col_all: 'Alle Spalten', invalid_json: 'Antwort konnte nicht analysiert werden',
    tab_backup: 'Backups', btn_export_json: 'DB exportieren (JSON)', btn_export_sql: 'DB exportieren (SQL)', btn_export_csv: 'Aktuelle Tabelle exportieren (CSV)',
    pick_table_first: 'Bitte zuerst in „Durchsuchen“ eine Tabelle wählen', backup_name_ph: 'Backup-Name (optional)', btn_backup_create: 'Backup erstellen', btn_import: 'Backup-Datei importieren',
    backup_note: '⚠ Wiederherstellen/Importieren ersetzt alle Zeilen passender Tabellen. Im Zweifel vorher exportieren.', no_backups: 'Noch keine Backups',
    col_file: 'Datei', col_size: 'Größe', col_time: 'Zeit', btn_restore: 'Wiederherstellen', btn_download: 'Herunterladen', btn_delete: 'Löschen',
    confirm_restore: 'Dieses Backup wiederherstellen? Alle Zeilen passender Tabellen werden ersetzt. Nicht widerrufbar.',
    confirm_import: 'Diese Backup-Datei importieren? Alle Zeilen passender Tabellen werden ersetzt. Nicht widerrufbar.', confirm_delete_backup: 'Diese Backup-Datei löschen?',
    backup_created: 'Backup erstellt', backup_restored: 'Wiederherstellung fertig', backup_imported: 'Import fertig', backup_deleted: 'Gelöscht',
  },
  'fr': {
    page_title: 'Console SQLite', app_name: 'Console de base Stun',
    label_user: "Nom d'utilisateur", label_pass: 'Mot de passe', btn_login: 'Connexion', btn_logout: 'Déconnexion',
    login_failed: "Nom d'utilisateur ou mot de passe incorrect", too_many_attempts: 'Trop de tentatives, réessayez dans {sec}s',
    tables: 'Tables', filter_tables: 'Filtrer les tables', tab_browse: 'Parcourir', tab_sql: 'Console SQL',
    search_ph: 'valeur à rechercher', btn_search: 'Rechercher', btn_add_row: '＋ Nouvelle ligne', btn_refresh: 'Actualiser',
    pick_table: 'Choisissez une table à gauche pour parcourir les données', btn_prev: 'Précédent', btn_next: 'Suivant',
    rows_page: '{from}-{to} sur {total}', actions: 'Actions',
    btn_run: 'Exécuter', sql_danger: "⚠ SQL arbitraire autorisé, y compris DROP/ALTER, pouvant endommager le schéma. À utiliser avec prudence.",
    btn_cancel: 'Annuler', btn_save: 'Enregistrer',
    add_row_title: 'Nouvelle ligne : {table}', edit_row_title: 'Modifier la ligne : {table}',
    set_null: 'Mettre NULL', confirm_delete: 'Supprimer cette ligne ? Irréversible.',
    saved: 'Enregistré', deleted: 'Supprimé', inserted: 'Inséré', load_failed: 'Échec du chargement',
    no_pks: "Cette table n'a pas de clé primaire ; édition/suppression via l'interface impossible", sql_error: 'Erreur SQL', affected: '{n} ligne(s) affectée(s)',
    col_all: 'Toutes les colonnes', invalid_json: 'Échec de analyse de la réponse',
    tab_backup: 'Sauvegardes', btn_export_json: 'Exporter la base (JSON)', btn_export_sql: 'Exporter la base (SQL)', btn_export_csv: 'Exporter la table actuelle (CSV)',
    pick_table_first: "Choisissez d'abord une table dans Parcourir", backup_name_ph: 'nom de sauvegarde (facultatif)', btn_backup_create: 'Créer une sauvegarde', btn_import: 'Importer un fichier de sauvegarde',
    backup_note: "⚠ Restaurer/importer remplace toutes les lignes des tables correspondantes. Exportez d'abord en cas de doute.", no_backups: 'Aucune sauvegarde',
    col_file: 'Fichier', col_size: 'Taille', col_time: 'Date', btn_restore: 'Restaurer', btn_download: 'Télécharger', btn_delete: 'Supprimer',
    confirm_restore: 'Restaurer cette sauvegarde ? Toutes les lignes des tables correspondantes seront remplacées. Irréversible.',
    confirm_import: 'Importer ce fichier de sauvegarde ? Toutes les lignes des tables correspondantes seront remplacées. Irréversible.', confirm_delete_backup: 'Supprimer ce fichier de sauvegarde ?',
    backup_created: 'Sauvegarde créée', backup_restored: 'Restauration terminée', backup_imported: 'Import terminé', backup_deleted: 'Supprimé',
  }
};

let currentLang = 'zh-CN';
function detectLang(){
  const saved = localStorage.getItem('dbweb_lang');
  if (saved && I18N[saved]) return saved;
  const nav = (navigator.language || 'en');
  if (nav.startsWith('zh-TW') || nav.startsWith('zh-HK')) return 'zh-TW';
  if (nav.startsWith('zh')) return 'zh-CN';
  if (nav.startsWith('ja')) return 'ja';
  if (nav.startsWith('de')) return 'de';
  if (nav.startsWith('fr')) return 'fr';
  return 'en';
}
function t(key, params){
  let s = (I18N[currentLang] && I18N[currentLang][key]) || (I18N['en'][key]) || key;
  if (params) for (const k in params) s = s.replace(new RegExp('\\{'+k+'\\}','g'), params[k]);
  return s;
}
function applyI18n(){
  document.documentElement.lang = currentLang;
  document.querySelectorAll('[data-i18n]').forEach(el => { el.textContent = t(el.getAttribute('data-i18n')); });
  document.querySelectorAll('[data-i18n-ph]').forEach(el => { el.setAttribute('placeholder', t(el.getAttribute('data-i18n-ph'))); });
  document.title = t('page_title');
}

// ── theme ──
const LIGHT_MQ = matchMedia('(prefers-color-scheme: light)');
function resolvedTheme(mode){ return mode === 'auto' ? (LIGHT_MQ.matches ? 'light' : 'dark') : mode; }
function applyTheme(mode){
  const theme = resolvedTheme(mode);
  document.documentElement.setAttribute('data-theme', theme);
  const btn = document.getElementById('theme-btn');
  if (btn){
    btn.textContent = mode === 'auto' ? '🌗' : (theme === 'light' ? '☀️' : '🌙');
    btn.title = mode;
  }
}
let themeMode = localStorage.getItem('dbweb_theme') || 'auto';
// auto 模式下跟随系统主题实时切换
LIGHT_MQ.addEventListener('change', ()=>{ if (themeMode === 'auto') applyTheme('auto'); });

// ── api ──
async function api(path, opts){
  const res = await fetch(path, Object.assign({headers:{'Content-Type':'application/json'}}, opts||{}));
  if (res.status === 401){ showLogin(); throw new Error('unauthorized'); }
  let data; try { data = await res.json(); } catch(_) { throw new Error(t('invalid_json')); }
  return data;
}

let toastTimer;
function toast(msg, kind){
  const el = document.getElementById('toast');
  el.textContent = msg; el.className = 'toast' + (kind ? ' '+kind : ''); el.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(()=>{ el.hidden = true; }, 2600);
}

// ── state ──
const S = { table:'', cols:[], pks:[], page:1, size:50, searchCol:'', searchTerm:'', sortBy:'', desc:false, total:0, tables:[] };

// ── auth views ──
function showLogin(){ document.getElementById('login-view').hidden=false; document.getElementById('app-view').hidden=true; }
function showApp(){ document.getElementById('login-view').hidden=true; document.getElementById('app-view').hidden=false; }

async function boot(){
  try {
    const me = await api('/api/me');
    document.getElementById('who').textContent = '👤 ' + (me.user||'');
    showApp(); await loadTables();
  } catch(_) { showLogin(); }
}

async function loadTables(){
  const d = await api('/api/tables'); S.tables = d.tables || []; renderTableList();
  if (!S.table && S.tables.length) selectTable(S.tables[0].name);
}
function renderTableList(){
  const filter = (document.getElementById('table-filter').value||'').toLowerCase();
  const list = document.getElementById('table-list'); list.innerHTML = '';
  S.tables.filter(x=>x.name.toLowerCase().includes(filter)).forEach(x=>{
    const div = document.createElement('div');
    div.className = 'table-item' + (x.name===S.table?' active':'');
    div.innerHTML = '<span>'+esc(x.name)+'</span><span class="cnt">'+x.rows+'</span>';
    div.onclick = ()=> selectTable(x.name);
    list.appendChild(div);
  });
}

async function selectTable(name){
  S.table = name; S.page = 1; S.searchCol=''; S.searchTerm=''; S.sortBy=''; S.desc=false;
  document.getElementById('search-term').value='';
  renderTableList();
  await loadRows();
}

async function loadRows(){
  const q = new URLSearchParams({table:S.table, page:String(S.page), size:String(S.size)});
  if (S.searchCol && S.searchTerm){ q.set('searchCol',S.searchCol); q.set('searchTerm',S.searchTerm); }
  if (S.sortBy){ q.set('sortBy',S.sortBy); q.set('desc',String(S.desc)); }
  let d; try { d = await api('/api/rows?'+q.toString()); } catch(e){ toast(t('load_failed'),'err'); return; }
  S.cols = d.columns||[]; S.pks = d.pks||[]; S.total = d.total||0;
  buildSearchCols();
  renderGrid(d);
  renderPager();
}

function buildSearchCols(){
  const sel = document.getElementById('search-col');
  sel.innerHTML = '<option value="">'+esc(t('col_all'))+'</option>' + S.cols.map(c=>'<option value="'+esc(c)+'">'+esc(c)+'</option>').join('');
  sel.value = S.searchCol || '';
}

function renderGrid(d){
  const wrap = document.getElementById('grid-wrap');
  const rows = d.rows||[];
  if (!rows.length && !rows.total){ wrap.innerHTML = '<div class="empty">—</div>'; return; }
  let html = '<table class="grid"><thead><tr>';
  S.cols.forEach(c=>{ const arrow = (S.sortBy===c)?(S.desc?' ▼':' ▲'):''; html += '<th data-col="'+esc(c)+'">'+esc(c)+arrow+'</th>'; });
  html += '<th>'+esc(t('actions'))+'</th></tr></thead><tbody>';
  rows.forEach(r=>{
    html += '<tr>';
    r.forEach(cell=>{
      if (cell===null){ html += '<td class="null">NULL</td>'; }
      else if (typeof cell === 'number'){ html += '<td class="num">'+cell+'</td>'; }
      else { html += '<td title="'+esc(String(cell))+'">'+esc(String(cell))+'</td>'; }
    });
    html += '<td><div class="row-act"><button class="edit">✎</button>'+(S.pks.length?'<button class="del">✕</button>':'')+'</div></td></tr>';
  });
  html += '</tbody></table>';
  wrap.innerHTML = html;
  wrap.querySelectorAll('th[data-col]').forEach(th=> th.onclick = ()=>{
    const c = th.getAttribute('data-col');
    if (S.sortBy===c) S.desc=!S.desc; else { S.sortBy=c; S.desc=false; }
    loadRows();
  });
  wrap.querySelectorAll('tbody tr').forEach((tr,i)=>{
    tr.querySelector('.edit').onclick = ()=> openRowModal('edit', rows[i]);
    const del = tr.querySelector('.del'); if (del) del.onclick = ()=> deleteRow(rows[i]);
  });
}

function renderPager(){
  const from = S.total===0?0:(S.page-1)*S.size+1;
  const to = Math.min(S.page*S.size, S.total);
  document.getElementById('page-info').textContent = t('rows_page',{from,to,total:S.total});
  document.getElementById('btn-prev').disabled = S.page<=1;
  document.getElementById('btn-next').disabled = to>=S.total;
}

function cellToInput(colName, v){ return v===null ? '' : String(v); }

let modalCtx = null; // {mode, row}
function openRowModal(mode, row){
  if (!S.pks.length && mode!=='add'){ toast(t('no_pks'),'err'); return; }
  modalCtx = { mode, row };
  document.getElementById('row-modal-title').textContent = mode==='add' ? t('add_row_title',{table:S.table}) : t('edit_row_title',{table:S.table});
  const form = document.getElementById('row-form'); form.innerHTML='';
  S.cols.forEach((c, idx)=>{
    const meta = row ? cellToInput(c, row[idx]) : '';
    const f = document.createElement('div'); f.className='rf-field';
    f.innerHTML = '<label>'+esc(c)+'<span class="rf-null"><input type="checkbox" class="nullchk" data-name="'+esc(c)+'"> '+esc(t('set_null'))+'</span></label>';
    const inp = document.createElement('textarea'); inp.className='val'; inp.value = meta; inp.style.height='38px';
    f.appendChild(inp); form.appendChild(f);
  });
  document.getElementById('row-modal').hidden = false;
}
function closeRowModal(){ document.getElementById('row-modal').hidden = true; modalCtx=null; }

async function saveRowModal(){
  const form = document.getElementById('row-form');
  const values = {};
  form.querySelectorAll('.rf-field').forEach(f=>{
    const name = f.querySelector('.val').previousElementSibling.querySelector('[data-name]').getAttribute('data-name');
    const isNull = f.querySelector('.nullchk').checked;
    const val = f.querySelector('.val').value;
    values[name] = isNull ? null : val;
  });
  try {
    if (modalCtx.mode==='add'){ await api('/api/row/insert',{method:'POST',body:JSON.stringify({table:S.table, values})}); toast(t('inserted'),'ok'); }
    else {
      const pk = {}; S.pks.forEach(p=>{ const i=S.cols.indexOf(p); if(i>=0) pk[p]=modalCtx.row[i]; });
      await api('/api/row/update',{method:'POST',body:JSON.stringify({table:S.table, values, pk})}); toast(t('saved'),'ok');
    }
    closeRowModal(); await loadTables(); await loadRows();
  } catch(e){ toast(t('load_failed'),'err'); }
}

async function deleteRow(row){
  if (!confirm(t('confirm_delete'))) return;
  const pk = {}; S.pks.forEach(p=>{ const i=S.cols.indexOf(p); if(i>=0) pk[p]=row[i]; });
  try { await api('/api/row/delete',{method:'POST',body:JSON.stringify({table:S.table, pk})}); toast(t('deleted'),'ok'); await loadTables(); await loadRows(); }
  catch(e){ toast(t('load_failed'),'err'); }
}

async function runSql(){
  const sql = document.getElementById('sql-input').value.trim();
  const box = document.getElementById('sql-result');
  if (!sql){ box.innerHTML=''; return; }
  let d; try { d = await api('/api/sql',{method:'POST',body:JSON.stringify({sql})}); } catch(e){ box.innerHTML='<div class="empty">'+esc(t('load_failed'))+'</div>'; return; }
  if (!d.ok){ box.innerHTML = '<div class="empty" style="color:var(--danger)">'+esc(t('sql_error'))+': '+esc(d.error||'')+'</div>'; return; }
  if (d.kind==='update'){ box.innerHTML = '<div class="empty" style="color:var(--ok)">'+esc(t('affected',{n:d.changed!=null?d.changed:'?'}))+'</div>'; return; }
  // result set
  const cols = d.columns||[]; const rows = d.rows||[];
  let html = '<table class="grid"><thead><tr>'; cols.forEach(c=> html+='<th>'+esc(c)+'</th>'); html+='</tr></thead><tbody>';
  rows.forEach(r=>{ html+='<tr>'; r.forEach(cell=>{ if(cell===null) html+='<td class="null">NULL</td>'; else if(typeof cell==='number') html+='<td class="num">'+cell+'</td>'; else html+='<td>'+esc(String(cell))+'</td>'; }); html+='</tr>'; });
  html+='</tbody></table>';
  box.innerHTML = html;
}

function esc(s){ return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }

// ── backup / export / import ──
function downloadExport(format){
  const q = format==='csv' ? '?format=csv&table='+encodeURIComponent(S.table) : '?format='+format;
  location.href = '/api/export'+q;
}
function fmtSize(n){
  if (n==null || isNaN(n)) return '—';
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n/1024).toFixed(1) + ' KB';
  return (n/1048576).toFixed(2) + ' MB';
}
async function loadBackups(){
  let d; try { d = await api('/api/backup/list'); } catch(e){ return; }
  const box = document.getElementById('backup-list');
  const list = d.backups || [];
  if (!list.length){ box.innerHTML = '<div class="empty">'+esc(t('no_backups'))+'</div>'; return; }
  let html = '<table class="grid"><thead><tr><th>'+esc(t('col_file'))+'</th><th>'+esc(t('col_size'))+'</th><th>'+esc(t('col_time'))+'</th><th>'+esc(t('actions'))+'</th></tr></thead><tbody>';
  list.forEach(b=>{
    html += '<tr><td title="'+esc(b.file)+'">'+esc(b.file)+'</td><td>'+fmtSize(b.size)+'</td><td>'+new Date(b.mtime).toLocaleString()+'</td><td><div class="row-act">'+
      '<button class="rst">'+esc(t('btn_restore'))+'</button>'+
      '<button class="dl">'+esc(t('btn_download'))+'</button>'+
      '<button class="del">'+esc(t('btn_delete'))+'</button></div></td></tr>';
  });
  html += '</tbody></table>';
  box.innerHTML = html;
  box.querySelectorAll('tbody tr').forEach((tr,i)=>{
    const file = list[i].file;
    tr.querySelector('.rst').onclick = ()=> restoreBackup(file);
    tr.querySelector('.dl').onclick = ()=>{ location.href = '/api/backup/download?file='+encodeURIComponent(file); };
    tr.querySelector('.del').onclick = ()=> deleteBackup(file);
  });
}
async function createBackup(){
  const name = document.getElementById('backup-name').value.trim();
  try { await api('/api/backup/create',{method:'POST',body:JSON.stringify({name})}); toast(t('backup_created'),'ok'); document.getElementById('backup-name').value=''; loadBackups(); }
  catch(e){ toast(t('load_failed'),'err'); }
}
async function restoreBackup(file){
  if (!confirm(t('confirm_restore'))) return;
  try { const d = await api('/api/backup/restore',{method:'POST',body:JSON.stringify({file})}); toast(t('backup_restored')+' ('+(d.restored||[]).length+')','ok'); await loadTables(); await loadRows(); }
  catch(e){ toast(t('load_failed'),'err'); }
}
async function deleteBackup(file){
  if (!confirm(t('confirm_delete_backup'))) return;
  try { await api('/api/backup/delete',{method:'POST',body:JSON.stringify({file})}); toast(t('backup_deleted'),'ok'); loadBackups(); }
  catch(e){ toast(t('load_failed'),'err'); }
}
async function importBackupFile(input){
  const f = input.files && input.files[0]; input.value = '';
  if (!f) return;
  let text; try { text = await f.text(); } catch(e){ toast(t('load_failed'),'err'); return; }
  if (!confirm(t('confirm_import'))) return;
  try { const d = await api('/api/import',{method:'POST',body:JSON.stringify({data:text})}); toast(t('backup_imported')+' ('+(d.restored||[]).length+')','ok'); await loadTables(); await loadRows(); }
  catch(e){ toast(t('load_failed'),'err'); }
}

// ── wire up ──
document.addEventListener('DOMContentLoaded', async ()=>{
  currentLang = detectLang(); applyI18n(); applyTheme(themeMode);
  document.getElementById('lang-select').value = currentLang;
  document.getElementById('lang-select').onchange = (e)=>{ currentLang = e.target.value; localStorage.setItem('dbweb_lang', currentLang); applyI18n(); if(!document.getElementById('app-view').hidden){ renderTableList(); if(!document.getElementById('tab-backup').hidden) loadBackups(); } };
  // 循环 auto→light→dark→auto：保证每次点击都有可见变化（auto 且系统深色时，旧顺序第一下会变成 dark 而画面不动）
  document.getElementById('theme-btn').onclick = ()=>{ themeMode = (themeMode==='auto')?'light':(themeMode==='light'?'dark':'auto'); localStorage.setItem('dbweb_theme', themeMode); applyTheme(themeMode); };

  document.getElementById('table-filter').oninput = renderTableList;
  document.getElementById('btn-refresh').onclick = ()=>{ loadTables(); loadRows(); };
  document.getElementById('btn-add').onclick = ()=> openRowModal('add', null);
  document.getElementById('btn-search').onclick = ()=>{ S.searchCol = document.getElementById('search-col').value; S.searchTerm = document.getElementById('search-term').value; S.page=1; loadRows(); };
  document.getElementById('search-term').onkeydown = (e)=>{ if(e.key==='Enter'){ e.preventDefault(); document.getElementById('btn-search').click(); } };
  document.getElementById('page-size').onchange = (e)=>{ S.size=parseInt(e.target.value)||50; S.page=1; loadRows(); };
  document.getElementById('btn-prev').onclick = ()=>{ if(S.page>1){ S.page--; loadRows(); } };
  document.getElementById('btn-next').onclick = ()=>{ S.page++; loadRows(); };
  document.getElementById('btn-run').onclick = runSql;

  document.getElementById('row-modal-x').onclick = closeRowModal;
  document.getElementById('row-cancel').onclick = closeRowModal;
  document.getElementById('row-save').onclick = saveRowModal;

  document.querySelectorAll('.tab').forEach(b=> b.onclick = ()=>{
    document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active')); b.classList.add('active');
    const tab=b.getAttribute('data-tab');
    document.querySelectorAll('.tab-panel').forEach(p=> p.hidden = (p.id !== 'tab-'+tab));
    if (tab==='backup') loadBackups();
  });

  document.getElementById('btn-exp-json').onclick = ()=> downloadExport('json');
  document.getElementById('btn-exp-sql').onclick = ()=> downloadExport('sql');
  document.getElementById('btn-exp-csv').onclick = ()=>{ if(!S.table){ toast(t('pick_table_first'),'err'); return; } downloadExport('csv'); };
  document.getElementById('btn-backup-create').onclick = createBackup;
  document.getElementById('btn-import').onclick = ()=> document.getElementById('import-file').click();
  document.getElementById('import-file').onchange = (e)=> importBackupFile(e.target);

  document.getElementById('login-form').onsubmit = async (e)=>{
    e.preventDefault();
    const err = document.getElementById('login-err'); err.hidden=true;
    const user = document.getElementById('login-user').value.trim();
    const pass = document.getElementById('login-pass').value;
    try {
      const res = await fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({user,pass})});
      if (res.status===429){ const d=await res.json().catch(()=>({})); err.textContent=t('too_many_attempts',{sec:d.retryInSec||30}); err.hidden=false; return; }
      const d = await res.json(); if (!d.ok){ err.textContent=t('login_failed'); err.hidden=false; return; }
      document.getElementById('who').textContent='👤 '+(d.user||''); showApp(); await loadTables();
    } catch(_){ err.textContent=t('load_failed'); err.hidden=false; }
  };
  document.getElementById('logout-btn').onclick = async ()=>{ try{ await api('/api/logout',{method:'POST'});}catch(_){} showLogin(); };

  await boot();
});
