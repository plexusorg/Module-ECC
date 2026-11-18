// Run the existing stub and real Paper/plugin through localhost processes, without a container network.
import mineflayer from "mineflayer";
import {copyFileSync,readFileSync,writeFileSync,existsSync,unlinkSync,realpathSync} from "node:fs";
import {join,resolve} from "node:path";
const requested=process.env.NATIVE_DATA;
if(!requested)throw Error("Set NATIVE_DATA to the marked synthetic Paper runtime copy");
const data=realpathSync(resolve(requested));
const marker=JSON.parse(readFileSync(join(data,".ecc-native-test.json"),"utf8"));
if(marker.purpose!=="ecc-native-integration"||typeof marker.runtime!=="string"||realpathSync(resolve(marker.runtime))!==data)throw Error("Synthetic runtime marker mismatch");
const properties=readFileSync(join(data,"server.properties"),"utf8").split(/\r?\n/).map(line=>line.trim()).filter(line=>line&&!/^[#!]/.test(line));
// Refuse escaped keys and continuations so Java cannot read a second spelling of a guarded property.
if(properties.some(line=>line.split(/[\s=:]/,1)[0].includes("\\")||line.endsWith("\\")))throw Error("Ambiguous synthetic server properties");
for(const [key,value]of Object.entries({"online-mode":"false","server-ip":"127.0.0.1","server-port":"25599","enable-rcon":"false"})){
 const found=properties.filter(line=>line.split(/[\s=:]/,1)[0]===key);
 if(found.length!==1||found[0]!==key+"="+value)throw Error("Unsafe synthetic server property: "+key);
}
const moduleText=readFileSync(join(data,"plugins/Plex/modules/Module-ECC/config.yml"),"utf8");
// Keep the guarded YAML keys plain and unique; refuse duplicate blocks rather than guessing.
const configLines=moduleText.split(/\r?\n/);
if(configLines.some(line=>!line.trimStart().startsWith("#")&&/^\s*[^:]+:/.test(line)&&!/^\s*[a-z_]+:/.test(line)))throw Error("Ambiguous synthetic module config");
if(configLines.filter(line=>/^chat_safety:/.test(line)).length!==1)throw Error("Ambiguous synthetic chat_safety config");
const config=Bun.YAML.parse(moduleText) as any;
for(const [key,value]of Object.entries({model_host:"http://127.0.0.1:8799",token:"e2e-token"})){
 const found=configLines.filter(line=>new RegExp("^\\s*"+key+"\\s*:").test(line));
 if(found.length!==1||config?.chat_safety?.[key]!==value)throw Error("Unsafe synthetic module config: "+key);
}
const tag=process.env.PROOF_TAG??"red";
if(!/^[a-z0-9-]+$/.test(tag))throw Error("Use a plain proof filename tag");
const evidence=join(data,"..");
const log=join(evidence,`native-race-${tag}.log`);
const nonce=crypto.randomUUID();
const stub=Bun.spawn([process.execPath,join(import.meta.dir,"stub.ts")],{cwd:import.meta.dir,env:{...Bun.env,PORT:"8799",STUB_HOST:"127.0.0.1",ECC_TOKEN:"e2e-token",HOLD_MARKER:"holdme",STUB_RUN_NONCE:nonce},stdout:"inherit",stderr:"inherit"});
let java:any;
const players:any[]=[];
try {
 const readyUntil=Date.now()+10000;
 let ready=false;
 while(Date.now()<readyUntil){
  if(stub.exitCode!==null)throw Error("Native stub did not bind its port");
  try{
   const response=await fetch("http://127.0.0.1:8799/stub/identity",{redirect:"error",signal:AbortSignal.timeout(500)});
   const identity=await response.json();
   if(!response.ok||identity.nonce!==nonce||identity.pid!==stub.pid)throw Error("Stub endpoint belongs to another process");
   ready=true;break;
  }catch(error){
   if(error instanceof Error&&error.message==="Stub endpoint belongs to another process")throw error;
   await Bun.sleep(50);
  }
 }
 if(!ready||stub.exitCode!==null)throw Error("Native stub identity was not established");
 copyFileSync(process.env.MODULE_JAR??join(import.meta.dir,"../build/libs/Module-ECC.jar"),join(data,"plugins/Plex/modules/Module-ECC.jar"));
 // Remove only the previous scenario ban in the marked synthetic runtime.
 const bans=join(data,"plugins/Plex/indefbans.yml");if(existsSync(bans))unlinkSync(bans);
 java=Bun.spawn(["java","-Xms256M","-Xmx1536M","-jar","paper-26.2-135.jar","nogui"],{cwd:data,stdin:"pipe",stdout:Bun.file(log),stderr:"inherit"});
 const deadline=Date.now()+240000;
 while(Date.now()<deadline) {
  if(existsSync(log)&&readFileSync(log,"utf8").includes("Done ("))break;
  if(java.exitCode!==null)throw Error(`Paper stopped: ${java.exitCode}; see ${log}`);
  await Bun.sleep(1000);
 }
 if(!readFileSync(log,"utf8").includes("Done ("))throw Error(`Paper did not start; see ${log}`);
 async function joinBot(name:string) {
  const bot=mineflayer.createBot({host:"127.0.0.1",port:25599,username:name,version:"26.1"});
  const messages:string[]=[];bot.on("message",m=>messages.push(m.toString()));
  players.push(bot);
  await new Promise<void>((res,rej)=>{bot.once("spawn",()=>res());bot.once("error",rej);setTimeout(()=>rej(Error(`join ${name} timeout`)),20000)});
  return {bot,messages};
 }
 const alice=await joinBot("RaceAlice");const bob=await joinBot("RaceBob");
 java.stdin.write("lp group default permission set essentials.msg true\n");
 await Bun.sleep(1500);
 const cases:any[]=[];
 alice.bot.chat("/msg RaceBob clean order-before-release");
 await Bun.sleep(100);
 alice.bot.chat("/msg RaceBob holdme race-before-verdict-order");
 await Bun.sleep(2000);
 const first=bob.messages.findIndex(s=>s.includes("clean order-before-release"));
 const second=bob.messages.findIndex(s=>s.includes("holdme race-before-verdict-order"));
 cases.push({name:"pending-delivery-order",copies:1,ordered:first>=0&&second>first,first,second});
 // Paper decorates vanilla command messages asynchronously after dispatch. Record that final order separately.
 alice.bot.chat("/minecraft:tell RaceBob clean vanilla-order-before-release");
 await Bun.sleep(100);
 alice.bot.chat("/minecraft:tell RaceBob holdme race-before-verdict-vanilla-order");
 await Bun.sleep(2000);
 const vanillaFirst=bob.messages.findIndex(s=>s.includes("clean vanilla-order-before-release"));
 const vanillaSecond=bob.messages.findIndex(s=>s.includes("holdme race-before-verdict-vanilla-order"));
 const diagnostics={vanillaDeliveryOrder:{ordered:vanillaFirst>=0&&vanillaSecond>vanillaFirst,first:vanillaFirst,second:vanillaSecond}};
 cases.push({name:"vanilla-pending-delivery",copies:bob.messages.filter(s=>s.includes("holdme race-before-verdict-vanilla-order")).length,captured:vanillaFirst>=0});

 for(const [name,message] of [["async-public","holdme race-before-verdict"],["main-thread-private","/minecraft:tell RaceBob holdme race-before-verdict-private"]]) {
  const marker=message.replace(/^\/minecraft:tell RaceBob /,"");
  const before=bob.messages.length;
  alice.bot.chat(message);
  await Bun.sleep(2500);
  const copies=bob.messages.slice(before).filter(s=>s.includes(marker)).length;
  cases.push({name,copies});
 }
 for(const command of ["msg","tell","w","minecraft:msg","minecraft:tell","minecraft:w"]) {
  const marker=`holdme alias-${command.replace(":","-")}`;
  alice.bot.chat(`/${command} RaceBob ${marker}`);
  await Bun.sleep(750);
  const leaked=bob.messages.some(s=>s.includes(marker));
  const seen=await (await fetch("http://127.0.0.1:8799/stub/lines")).json();
  const line=seen.find((l:any)=>l.text===marker&&l.channel==="msg");
  if(line) {
   await fetch("http://127.0.0.1:8799/stub/action",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({type:"release",uuid:line.speaker_uuid,account:line.speaker,ids:[line.id]})});
   for(let i=0;i<50&&!bob.messages.some(s=>s.includes(marker));i++)await Bun.sleep(100);
  }
  cases.push({name:command,copies:bob.messages.filter(s=>s.includes(marker)).length,leaked,captured:!!line});
 }
 await fetch("http://127.0.0.1:8799/stub/slow",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({millis:1500,seconds:2})});
 const timeoutMarker="clean timeout retry";
 alice.bot.chat(`/minecraft:tell RaceBob ${timeoutMarker}`);
 await Bun.sleep(6000);
 const retried=await (await fetch("http://127.0.0.1:8799/stub/lines")).json();
 const timeoutLine=retried.find((l:any)=>l.text===timeoutMarker);
 cases.push({name:"timeout-retry",copies:bob.messages.filter(s=>s.includes(timeoutMarker)).length,retried:timeoutLine?.asked>=2});
 await fetch("http://127.0.0.1:8799/stub/slow",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({millis:0,seconds:0})});
 for(const [name,marker,command] of [
  ["reject-await","rejectme public","rejectme public"],
  ["reject-check","rejectme private","/minecraft:tell RaceBob rejectme private"],
  ["reject-after-timeout","rejectme retry","/minecraft:tell RaceBob rejectme retry"],
 ]) {
  if(name==="reject-after-timeout")await fetch("http://127.0.0.1:8799/stub/slow",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({millis:1500,seconds:2})});
  const before=bob.messages.length;
  alice.bot.chat(command);
  await Bun.sleep(name==="reject-after-timeout"?6000:1500);
  const seen=await (await fetch("http://127.0.0.1:8799/stub/lines")).json();
  const line=seen.find((l:any)=>l.text===marker);
  cases.push({name,copies:bob.messages.slice(before).filter(s=>s.includes(marker)).length,expectedCopies:0,captured:line?.verdict==="reject",retried:name!=="reject-after-timeout"||line?.asked>=2});
 }
 await fetch("http://127.0.0.1:8799/stub/slow",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({millis:0,seconds:0})});
 const actionable=alice.messages.filter(s=>s.includes("Shorten this text, then try again.")).length;
 java.stdin.write("lp user RaceAlice permission set plex.ecc.chat.bypass true\n");await Bun.sleep(500);
 const trustedMarker="rejectme trusted exemption";
 alice.bot.chat(trustedMarker);await Bun.sleep(1500);
 const trustedSeen=await (await fetch("http://127.0.0.1:8799/stub/lines")).json();
 cases.push({name:"reject-trusted-exemption",copies:bob.messages.filter(s=>s.includes(trustedMarker)).length,captured:trustedSeen.some((l:any)=>l.text===trustedMarker&&l.trusted&&l.verdict==="deliver")});
 java.stdin.write("lp user RaceAlice permission unset plex.ecc.chat.bypass\n");await Bun.sleep(500);
 const banPlayer=await joinBot("RaceBan");
 let kicked=false;banPlayer.bot.on("kicked",()=>kicked=true);
 await Bun.sleep(1000);
 banPlayer.bot.chat("holdme ban-before-verdict");
 await Bun.sleep(4000);
 const acks=await (await fetch("http://127.0.0.1:8799/stub/acks")).json();
 const lines=await (await fetch("http://127.0.0.1:8799/stub/lines")).json();
 java.stdin.write("ecc status\n");await Bun.sleep(500);
 const status=readFileSync(log,"utf8").replace(/\x1b\[[0-9;]*m/g,"").split("\n").filter(l=>l.includes("Held lines:"));
 const result={tag,cases,diagnostics,actionable,kicked,acks,status,lines:lines.map((l:any)=>({id:l.id,channel:l.channel,verdict:l.verdict,asked:l.asked,responseMillis:l.responded-l.received})),alice:alice.messages,bob:bob.messages};
 writeFileSync(join(evidence,`native-race-${tag}.json`),JSON.stringify(result,null,2));
 console.log(JSON.stringify({cases,diagnostics,actionable,kicked,acks,status}));
 const releases=acks.filter((a:any)=>String(a.detail).includes("of 1 lines"));
 const pass=cases.every(c=>c.copies===(c.expectedCopies??1)&&c.leaked!==true&&c.captured!==false&&c.ordered!==false&&c.retried!==false)&&releases.length===10&&releases.every((a:any)=>a.ok&&a.detail==="delivered 1 of 1 lines")
  &&actionable===3&&kicked&&acks.some((a:any)=>String(a.detail).includes("dropped 1 held lines"))&&status.some(s=>s.includes("Held lines: 0,"));
 if(!pass)throw Error("early release/ban must deliver once or drop without resurrecting held entries");
 console.log("PASS: delivery, rejection, trusted bypass and retry checks passed; held count zero");
} finally {
 try{
  for(const bot of players)bot.quit();
  if(java&&java.exitCode===null){
   java.stdin.write("stop\n");
   await Promise.race([java.exited,Bun.sleep(20000)]);
   if(java.exitCode===null){java.kill();await java.exited;}
  }
 }finally{
  if(stub.exitCode===null)stub.kill();
  await stub.exited;
 }
}
