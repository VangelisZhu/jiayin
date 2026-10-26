let c;
export function initCorrections(context){c=context;}
export async function sourcesPage(){
 const {api,el,text,actions,button,shell,notice}=c;
 const config=await api('sources-config');
 const choices=[['qq','QQ 音乐'],['netease','网易云音乐']].map(([id,label])=>({id,label,input:el('input',{type:'checkbox',checked:config.enabled.includes(id),'aria-label':label})}));
 shell('资料来源','只在主动搜索时查询所选来源；关闭两项后仍可手动编辑。',el('section',{class:'panel'},...choices.map(x=>el('label',{class:'checkfield'},x.input,x.label)),text('公开接口可能限流或变化，来源故障不会阻止播放。AI 设置独立管理。'),button('保存来源',async()=>{await api('sources-config','PUT',{enabled:choices.filter(x=>x.input.checked).map(x=>x.id)});notice('资料来源已更新');},true)));
}
export async function membersPage(){
 const {api,el,text,actions,button,shell,notice}=c;
 const members=await api('members');
 shell('成员与权限','成员先在家音设置连接后台，再由管理员批准。直接修改会影响家庭共享音乐文件。',el('div',{},...members.map(m=>{
  const role=el('select',{'aria-label':'权限 '+m.username},...Object.entries({readonly:'只读',request:'提交申请',direct:'直接修改'}).map(([id,label])=>el('option',{value:id},label)));role.value=m.role;
  return el('section',{class:'panel'},el('h2',{},m.username),text(m.approved?(m.enabled?'已批准':'已停用'):'等待批准加入'),actions(role,button('批准 / 保存权限',async()=>{await api('members/'+m.id,'PUT',{role:role.value,enabled:true});notice('权限已更新');await membersPage();},true),button('停用',async()=>{await api('members/'+m.id,'PUT',{role:role.value,enabled:false});await membersPage();})),...m.devices.map(d=>el('div',{class:'issue'},text(d.name+' · '+(d.revoked?'已撤销':'有效')),d.revoked?null:button('撤销设备',async()=>{await api('devices/'+d.id+'/revoke','POST',{});await membersPage();}))));
 })),[button('刷新',membersPage)]);
}
export async function correctionsPage(){
 const {api,el,text,actions,button,shell,badge,dialog,notice,diff,names}=c;
 const items=await api('corrections');
 shell('资料纠正与审核','选择 QQ／网易云候选或手动编辑。申请不会自动写入文件；目录移动请到“分类与目录”单独审批。',el('div',{},...items.slice().reverse().map(item=>el('section',{class:'panel'},actions(el('strong',{},item.username),badge(({pending:'待审核',approved:'已批准',executing:'正在执行',draft:'待提交'})[item.status]||item.status)),text(item.note||'歌曲资料纠正'),item.source?text('来源：'+item.source.provider+' · '+item.source.url):null,...item.preview.map(p=>el('article',{class:'change'},el('h3',{},p.title),diff(p),p.cover?text('包括内嵌封面；不替换共用目录封面。'):null)),item.error?text(item.error):null,item.message?text(item.message):null,item.status==='executing'&&item.stage?text(item.stage):null,item.review?text('审核说明：'+(item.review.reason||'已核对')):null,
 actions(item.status==='draft'?button('确认并应用',async()=>{await api('corrections/'+item.id+'/submit','POST',{});notice('已提交后台任务');await correctionsPage();},true):null,item.job_id&&['failed','paused'].includes(item.status)?button('继续已批准任务',async()=>{await api('corrections/'+item.id+'/retry','POST',{});await correctionsPage();}):['pending','failed','paused'].includes(item.status)?button('审核',()=>{
  const checks=item.preview.map(p=>({p,input:el('input',{type:'checkbox',checked:true,'aria-label':p.title+' '+p.kind}),fields:Object.keys(p.values).concat(p.cover?['cover']:[]).map(key=>({key,input:el('input',{type:'checkbox',checked:true,'aria-label':p.title+' '+(names[key]||key)})}))}));
  const reason=el('textarea',{rows:3,'aria-label':'审核说明'});
  dialog('审核修改申请',el('div',{},text('仅执行勾选项目。变更后的文件需扫描核对；原始文件会备份。'),...checks.map(({p,input,fields})=>el('section',{class:'panel'},el('label',{class:'checkfield'},input,p.title+' · '+({tags:'标签',artwork:'封面',lyrics:'歌词'})[p.kind]),...fields.map(f=>el('label',{class:'checkfield'},f.input,names[f.key]||'封面')))),reason),[button('拒绝',async()=>{await api('corrections/'+item.id+'/review','POST',{accept:false,reason:reason.value});document.querySelector('#drawer').close();await correctionsPage();}),button('批准选中修改',async()=>{const ids=checks.filter(x=>x.input.checked).map(x=>x.p.id);if(!ids.length)throw Error('至少选择一项');await api('corrections/'+item.id+'/review','POST',{accept:true,change_ids:ids,fields:Object.fromEntries(checks.filter(x=>x.input.checked).map(x=>[x.p.id,x.fields.filter(f=>f.input.checked).map(f=>f.key)])),reason:reason.value});document.querySelector('#drawer').close();await correctionsPage();},true)]);
 }):null)))),[button('刷新',correctionsPage)]);
}
export async function sourceCorrection(track){
 const {api,el,text,field,actions,button,dialog,notice,names}=c;
 if(!track.server_id)throw Error('请先核对该曲目的音乐服务器身份');
 const query=el('input',{value:track.metadata.title+' '+track.metadata.artists.join(' '),'aria-label':'检索关键词'});
 const kind=el('select',{'aria-label':'检索对象'},el('option',{value:'song'},'歌曲'),el('option',{value:'album'},'专辑'),el('option',{value:'artist'},'歌手'));
 const results=el('div',{});
 const search=async()=>{const term=query.value,type=kind.value;results.replaceChildren(text('正在查询：'+term));await Promise.all(['qq','netease'].map(async source=>{try{const r=await api('sources/search','POST',{query:term,kind:type,source});results.append(...r.errors.map(e=>text(e.source+'：'+e.message)),...r.items.map(item=>el('section',{class:'panel'},el('h3',{},item.title),text(item.source+' · '+(item.values.artists||[]).join(' / ')+' · '+(item.values.album||'')),button('查看字段与歌词',()=>chooseSource(track,item),true))));}catch(e){results.append(text(source+'：'+e.message));}}));};
 dialog('QQ / 网易云纠正 · '+track.metadata.title,el('div',{},text('默认只处理当前歌曲。来源资料可能属于其他发行版本，请核对。'),field('关键词',query),actions(kind,button('搜索候选',search,true)),button('手动纠正',()=>chooseSource(track,null)),results));
}
async function chooseSource(track,chosen){
 const {api,el,text,field,actions,button,dialog,notice,names}=c;
 const item=chosen?await api('sources/'+chosen.id):{values:{}};
 const values={},inputs={};
 const arrays=['artists','album_artists','genres'];
 for(const key of ['title','artists','album','album_artists','date','genres','language','region','album_version']){
  const before=track.metadata[key];
  const selected=el('input',{type:'checkbox','aria-label':'修改 '+names[key]});
  const value=item.values[key]??before;
  const input=el('textarea',{rows:1,'aria-label':names[key]},Array.isArray(value)?value.join('\n'):value);
  input.addEventListener('input',()=>selected.checked=true);inputs[key]={selected,input};
  values[key]=el('section',{class:'change'},el('label',{},selected,' '+names[key]),text('原值：'+(Array.isArray(before)?before.join(' / '):before)),input);
 }
 const lyrics=el('textarea',{rows:7,'aria-label':'原文歌词'},item.lyrics||'');const translation=el('textarea',{rows:5,'aria-label':'中文译文'},item.translation||'');
 const lc=el('input',{type:'checkbox','aria-label':'更新原文歌词'}),tc=el('input',{type:'checkbox','aria-label':'更新中文译文'}),cover=el('input',{type:'checkbox','aria-label':'更新当前歌曲内嵌封面'});
 const content=el('div',{},...(item.warnings||[]).map(w=>text(w)),text('逐项选择需要修改的字段；未勾选内容保持不变。修改共享文件会影响所有家庭成员。'),text('时长：本地 '+track.duration+' 秒 / 来源 '+(item.duration||'未知')+' 秒'),...Object.values(values),el('label',{},lc,'更新原文歌词'),lyrics,el('label',{},tc,'更新中文译文（独立轨道）'),translation,item.cover_url?el('div',{},el('img',{src:'/api/sources/'+item.id+'/cover',alt:'来源封面',width:160,height:160}),el('label',{},cover,'更新当前歌曲内嵌封面')):null);
 dialog('字段对照 · '+track.metadata.title,content,[button('生成修改预览',async()=>{
  const selected={};for(const [key,v] of Object.entries(inputs))if(v.selected.checked)selected[key]=arrays.includes(key)?v.input.value.split('\n').map(s=>s.trim()).filter(Boolean):v.input.value;
  const body={ids:[track.server_id],values:selected,candidate_id:item.id||'',cover:cover.checked};if(lc.checked)body.lyrics=lyrics.value;if(tc.checked)body.translation=translation.value;
  await api('corrections/preview','POST',body);document.querySelector('#drawer').close();await c.go('corrections');
 },true)]);
}
