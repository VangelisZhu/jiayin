let c;
let offset=0;
export function initOwnership(context){c=context;}
const statuses={draft:'待提交',pending:'待审核',published:'已发布',partial:'部分通过',rejected:'已拒绝',withdrawn:'已撤回',conflict:'内容冲突',undone:'已撤销发布'};
export async function ownershipPanels(current){
 const {api,el,text,actions,button,badge,dialog,notice}=c;
 const items=await api('ownership/requests?offset='+offset);
 if(!current())return null;
 const entry=e=>el('article',{class:'change'},el('h3',{},e.title),text((e.before_target?.name||e.original_artists.join(' / '))+' → '+e.target.name),text('保留演唱署名：'+e.credit),e.result?text(e.result==='published'?'已通过':'未通过'):null);
 const review=item=>{
  const checks=item.entries.map(e=>({e,input:el('input',{type:'checkbox',checked:true,'aria-label':e.title})}));
  const reason=el('textarea',{rows:3,'aria-label':'作品归属审核说明'});
  const selected=el('input',{type:'checkbox',checked:true,'aria-label':'全选歌曲'});
  selected.addEventListener('change',()=>checks.forEach(x=>x.input.checked=selected.checked));
  dialog('审核作品归属 · '+item.entries.length+' 首',el('div',{},text('通过后发布到同一曲库的家音客户端。个人设置优先；原有演唱署名保留。'),el('label',{class:'checkfield'},selected,'全选歌曲'),...checks.map(x=>el('section',{class:'panel'},el('label',{class:'checkfield'},x.input,x.e.title),entry(x.e))),reason),[
   button('拒绝申请',async()=>{await api('ownership/'+item.id+'/review','POST',{accept:false,reason:reason.value});document.querySelector('#drawer').close();await c.refresh();}),
   button('批准选中并发布',async()=>{
    const ids=checks.filter(x=>x.input.checked).map(x=>x.e.track_id);if(!ids.length)throw Error('至少选择一首歌曲');
    await api('ownership/'+item.id+'/review','POST',{accept:true,track_ids:ids,reason:reason.value});document.querySelector('#drawer').close();notice('归属已发布，无需写入音乐文件或等待扫描');await c.refresh();
   },true)
  ]);
 };
 return el('section',{class:'panel'},el('h2',{},'作品归属审核'),text('调整家音中的作品浏览归属；保留合作演唱署名。所有成员的共享申请均需管理员审核。'),
  !items.length?text('暂无作品归属申请'):null,
  ...items.map(item=>el('section',{class:'panel'},actions(el('strong',{},item.username),badge(statuses[item.status]||item.status)),text((item.note||'作品归属整理')+' · '+item.entry_count+' 首'),...item.entries.map(entry),item.reason?text('审核说明：'+item.reason):null,
   actions(button('查看全部 '+item.entry_count+' 首',async()=>{const detail=await api('ownership/requests/'+item.id);dialog('作品归属明细',el('div',{},...detail.entries.map(entry)),[button('关闭',()=>document.querySelector('#drawer').close())]);}),
    item.status==='pending'?button('审核归属',async()=>review(await api('ownership/requests/'+item.id)),true):null,
    ['published','partial'].includes(item.status)?button('撤销发布…',()=>dialog('撤销作品归属发布',text('恢复本次修改前的共享归属。如果后续已修改相同歌曲，需重新核对。'),[button('取消',()=>document.querySelector('#drawer').close()),button('确认撤销',async()=>{await api('ownership/'+item.id+'/undo','POST',{});document.querySelector('#drawer').close();notice('已发布撤销版本');await c.refresh();})])):null
   )
  )),actions(offset>0?button('较新申请',async()=>{offset=Math.max(0,offset-50);await c.refresh();}):null,items.length===50?button('更早申请',async()=>{offset+=50;await c.refresh();}):null));
}
