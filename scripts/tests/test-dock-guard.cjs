const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const {test}=require('node:test');
const source=fs.readFileSync(path.join(__dirname,'../../common/src/main/assets/voyah-helper/dock-guard.js'),'utf8');
function fixture(setup={}) {
 let now=10000,poll,originalCalls=0;
 const base={version:'1.1',pid:1026,now_ms:10000,lease_until_ms:25000,lease_active:true,boot_ready:true,subscription_valid:true};
 const values=new Map([['voyahplay_tweaks_bounds_test','1|25000'],['voyahplay_tweaks_bounds_probe',JSON.stringify(base)]]);
 const method={call() {originalCalls++;return 'OEM-return';}};
 if(setup.localHook) method.implementation=setup.localHook;
 const previous=method.implementation;
 let top='com.shilapi.xcertplay/com.shilapi.xcertplay.CarPlayHostActivity',topError=false;
 const types={
  'com.qinggan.mainlauncher.navigation.NavigationBar':{show:{overload:()=>method},class:{getDeclaredMethods:()=>setup.missingMethod?[]:[{
   getName:()=> 'show',getParameterTypes:()=>[],getModifiers:()=>setup.nativeHook?257:1}]}},
  'com.qinggan.launcher.base.utils.AppUtils':{getTopAppInfo(context,display,kind) {assert.equal(display,0);assert.equal(kind,4);if(topError) throw Error('QG service unavailable');return top;}},
  'android.os.SystemClock':{elapsedRealtime:()=>now},
  'android.provider.Settings$Global':{getString:(_,key)=>values.get(key)??null,putString:(_,key,value)=>values.set(key,value)},
  'android.app.ActivityThread':{currentApplication:()=>({getContentResolver:()=>({})})}
 };
 vm.runInNewContext(source,{Java:{perform:fn=>fn(),use:name=>{assert(types[name],name);return types[name];}},Process:{id:2345},setInterval:fn=>{poll=fn;},console:{log(){}}});
 return {values,base,clock:n=>{now=n;},poll:()=>poll(),original:()=>originalCalls,
  top:s=>{top=s;},topError:()=>{topError=true;},baseChange:c=>values.set('voyahplay_tweaks_bounds_probe',JSON.stringify({...base,...c})),
  replacement:()=>method.implementation,previous,
  invoke(options={}) {
   const bar={mScreenId:{value:options.screen??0},mBarName:{value:options.name??'MainScreenNavigationBar'},
    mContext:{value:options.noContext?null:{getPackageName:()=>options.package??'com.qinggan.app.launcher'}}};
   return method.implementation.call(bar);
  },probe:()=>JSON.parse(values.get('voyahplay_dock_probe'))};
}
test('driver show is blocked before OEM window code, for both supported foreground activities',()=>{
 for(const activity of ['CarPlayHostActivity','DiPlayActivity']) {
  const f=fixture();f.top('com.shilapi.xcertplay/com.shilapi.xcertplay.'+activity);
  assert.equal(f.invoke(),undefined);assert.equal(f.original(),0);f.poll();assert.equal(f.probe().blocked,1);
 }
});
test('repeated popup show requests cannot start a show animation while main activity stays VoyahPlay',()=>{
 const f=fixture();for(let i=0;i<8;i++)f.invoke();f.poll();assert.equal(f.probe().blocked,8);assert.equal(f.original(),0);
});
test('passenger, unknown bars, contexts, and unrelated foreground activities call OEM exactly once',()=>{
 for(const options of [{screen:1},{name:'SecondScreenNavigationBar'},{package:'other'},{noContext:true}]) {
  const f=fixture();assert.equal(f.invoke(options),'OEM-return');assert.equal(f.original(),1);
 }
 for(const top of [null,'','com.shilapi.xcertplay/com.shilapi.xcertplay.OtherActivity',
  'com.shilapi.xcertplay.fake/com.shilapi.xcertplay.CarPlayHostActivity',
  'com.qinggan.app.launcher/com.qinggan.app.launcher.activity.MainActivity',
  'com.qinggan.app.camera/CameraActivity','com.android.permissioncontroller/GrantPermissionsActivity']) {
  const f=fixture();f.top(top);assert.equal(f.invoke(),'OEM-return');assert.equal(f.original(),1);
 }
});
test('HOME restores OEM behavior immediately even before the outgoing lease is cleared',()=>{
 const f=fixture();f.invoke();f.top('com.qinggan.app.launcher/com.qinggan.app.launcher.activity.MainActivity');
 assert.equal(f.invoke(),'OEM-return');assert.equal(f.original(),1);
});
test('cleared or expired lease takes effect between timer samples',()=>{
 for(const change of [f=>f.values.delete('voyahplay_tweaks_bounds_test'),f=>f.clock(25000)]) {
  const f=fixture();change(f);assert.equal(f.invoke(),'OEM-return');assert.equal(f.original(),1);
 }
});
test('boot, license, freshness, process and bounded deadline gates remain mandatory',()=>{
 for(const change of [{boot_ready:false},{subscription_valid:false},{lease_active:false},{pid:0},{pid:'1026'},
  {version:'old'},{now_ms:4999},{now_ms:10001},{now_ms:'10000'},{lease_until_ms:10000},{lease_until_ms:40001}]) {
  const f=fixture();f.baseChange(change);f.poll();assert.equal(f.invoke(),'OEM-return',JSON.stringify(change));assert.equal(f.original(),1);
 }
});
test('missing probe, malformed leases and stale cached state never suppress OEM behavior',()=>{
 for(const raw of [null,'','broken','{}','null']) {
  const f=fixture();if(raw===null)f.values.delete('voyahplay_tweaks_bounds_probe');else f.values.set('voyahplay_tweaks_bounds_probe',raw);
  f.poll();assert.equal(f.invoke(),'OEM-return');
 }
 for(const raw of ['1|0','1|40001','1|25000|extra','0|25000','1|99999999999999999']) {
  const f=fixture();f.values.set('voyahplay_tweaks_bounds_test',raw);f.poll();assert.equal(f.invoke(),'OEM-return');
 }
 const stale=fixture();stale.clock(15001);assert.equal(stale.invoke(),'OEM-return');
});
test('foreground query failure preserves OEM return and appears in diagnostics',()=>{
 const f=fixture();f.topError();assert.equal(f.invoke(),'OEM-return');assert.equal(f.original(),1);
 f.poll();assert(f.probe().last_show_error.includes('QG service unavailable'));
});
test('existing local or native replacements are not overwritten; conflicts stay in diagnostics',()=>{
 const existing=()=> 'existing-hook';
 for(const setup of [{localHook:existing},{nativeHook:true}]) {
  const f=fixture(setup);assert.equal(f.replacement(),f.previous);assert.equal(f.probe().installed,false);
  assert.equal(f.probe().active,false);assert(f.probe().error.includes('DOCK_GUARD_CONFLICT'));
  f.poll();assert(f.probe().error.includes('DOCK_GUARD_CONFLICT'));
 }
});
test('an uninspectable method is left untouched',()=>{
 const f=fixture({missingMethod:true});assert.equal(f.replacement(),undefined);
 assert.equal(f.probe().installed,false);assert(f.probe().error.includes('DOCK_GUARD_METHOD'));
});
