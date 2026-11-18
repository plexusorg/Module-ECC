// Real Paper/module -> localhost delay proxy -> real ECC-Server HTTP/core coverage guard. No model inference.
import mineflayer from "mineflayer";
import {copyFileSync,readFileSync,writeFileSync,existsSync,unlinkSync,realpathSync} from "node:fs";
import {join,resolve} from "node:path";
const requested=process.env.NATIVE_DATA;
if(!requested)throw Error("Set NATIVE_DATA to the marked synthetic Paper runtime copy");
const data=realpathSync(resolve(requested));
const marker=JSON.parse(readFileSync(join(data,".ecc-native-test.json"),"utf8"));
if(marker.purpose!=="ecc-native-integration"||realpathSync(marker.runtime)!==data)throw Error("Synthetic runtime marker mismatch");
const properties=readFileSync(join(data,"server.properties"),"utf8");
for(const [key,value]of Object.entries({"online-mode":"false","server-ip":"127.0.0.1","server-port":"25599","enable-rcon":"false"})){
 const found=properties.split(/\r?\n/).map(line=>line.trim()).filter(line=>new RegExp("^"+key+"[\\s:=]").test(line));
 if(found.length!==1||found[0]!==key+"="+value)throw Error("Unsafe synthetic server property: "+key);
}
const moduleConfig=readFileSync(join(data,"plugins/Plex/modules/Module-ECC/config.yml"),"utf8");
for(const [key,value]of Object.entries({model_host:"http://127.0.0.1:8799",token:"e2e-token"})){
 const found=moduleConfig.split(/\r?\n/).filter(line=>new RegExp("^\\s*"+key+"\\s*:").test(line));
 if(found.length!==1||found[0].slice(found[0].indexOf(":")+1).trim().replace(/^['"]|['"]$/g,"")!==value)throw Error("Unsafe synthetic module config: "+key);
}
const evidence=join(data,"..");
const prefix=process.env.NATIVE_PROOF_PREFIX??"native-coverage";
if(!/^[a-z0-9-]+$/.test(prefix))throw Error("Use a plain proof filename prefix");
const endpointPath=process.env.NATIVE_ENDPOINT??join(evidence,"coverage-proof/endpoint.json");
const endpoint=JSON.parse(readFileSync(endpointPath,"utf8"));
const endpointUrl=new URL(endpoint.url);
if(endpointUrl.protocol!=="http:"||!["127.0.0.1","[::1]","localhost"].includes(endpointUrl.hostname))throw Error("Use a loopback HTTP proof endpoint");
const statusResponse=await fetch(endpointUrl.origin+"/status",{redirect:"error",headers:{Authorization:`Bearer ${endpoint.token}`}});
const initialStatus=await statusResponse.json();
if(!statusResponse.ok||initialStatus.status!=="ready"||initialStatus.version!=="coverage-rig"||initialStatus.scorer!=="real core with rig word scorer")throw Error("Endpoint is not the expected inference-free proof rig");
const log=join(evidence,prefix+".log");
// Remove only the prior scenario ban in this synthetic runtime copy.
const bans=join(data,"plugins/Plex/indefbans.yml");if(existsSync(bans))unlinkSync(bans);
const requests:any[]=[];
const attempts=new Map<string,number>();
const proxy=Bun.serve({hostname:"127.0.0.1",port:8799,idleTimeout:60,async fetch(request){
 if(request.headers.get("Authorization")!=="Bearer e2e-token")return new Response("unauthorized",{status:401});
 const url=new URL(request.url);const body=request.method==="POST"?await request.text():undefined;
 const response=await fetch(endpoint.url+url.pathname,{method:request.method,headers:{"Authorization":`Bearer ${endpoint.token}`,"Content-Type":"application/json"},body});
 const text=await response.text();
 if(url.pathname==="/line"&&body){
  const line=JSON.parse(body);const n=(attempts.get(line.id)??0)+1;attempts.set(line.id,n);
  requests.push({id:line.id,channel:line.channel,text:line.text,length:line.text.length,trusted:line.trusted,attempt:n,status:response.status,response:JSON.parse(text)});
  if(line.text.includes("retryguard")&&n===1)await Bun.sleep(1400);
 }
 return new Response(text,{status:response.status,headers:{"Content-Type":"application/json"}});
}});
copyFileSync(join(import.meta.dir,"../build/libs/Module-ECC.jar"),join(data,"plugins/Plex/modules/Module-ECC.jar"));
writeFileSync(log, "");
const java=Bun.spawn(["java","-Xms256M","-Xmx1536M","-jar","paper-26.2-135.jar","nogui"],{cwd:data,stdin:"pipe",stdout:Bun.file(log),stderr:"inherit"});
const bots:any[]=[];
const cases:any[]=[];
function assert(v:unknown,m:string):asserts v{if(!v)throw Error(m);}
async function consoleCommand(command:string){const start=existsSync(log)?readFileSync(log,"utf8").length:0;java.stdin.write(command+"\n");await Bun.sleep(650);return readFileSync(log,"utf8").slice(start).replace(/\x1b\[[0-9;]*m/g,"");}
try{
 const until=Date.now()+240000;
 while(Date.now()<until){if(existsSync(log)&&readFileSync(log,"utf8").includes("Done ("))break;if(java.exitCode!==null)throw Error("Paper stopped");await Bun.sleep(1000);}
 assert(readFileSync(log,"utf8").includes("Done ("),"Paper startup timeout");
 async function joinBot(name:string){
  const bot=mineflayer.createBot({host:"127.0.0.1",port:25599,username:name,version:"26.1"});const messages:string[]=[];bot.on("message",m=>messages.push(m.toString()));bots.push(bot);
  await new Promise<void>((ok,no)=>{bot.once("spawn",()=>ok());bot.once("error",no);bot.once("kicked",reason=>no(Error(`join rejected: ${reason}`)));setTimeout(()=>no(Error("join timeout")),20000)});return{bot,messages};
 }
 const alice=await joinBot("ProofAlice");const bob=await joinBot("ProofBob");await Bun.sleep(2000);
 const longUrl="https://native-proof.invalid/"+"a".repeat(80);
 for(const [name,text,command]of [
  ["await-reject","publicproof "+longUrl,"publicproof "+longUrl],
  ["check-reject","privateproof "+longUrl,"/minecraft:tell ProofBob privateproof "+longUrl],
  ["retry-reject","retryguard "+longUrl,"/minecraft:tell ProofBob retryguard "+longUrl],
 ]){
  const before=bob.messages.length;alice.bot.chat(command);await Bun.sleep(name==="retry-reject"?6500:1800);
  const seen=requests.filter(r=>r.text===text);const leak=bob.messages.slice(before).some(m=>m.includes(text));
  const pass=seen.length>0&&seen.every(r=>r.status===200&&r.response.verdict==="reject")&&!leak&&(name!=="retry-reject"||seen.length>=2);
  cases.push({name,pass,leak,requests:seen.length});assert(pass,name+" failed");
 }
 await consoleCommand("minecraft:item replace entity ProofAlice hotbar.1 with minecraft:writable_book");
 const query='data get entity ProofAlice Inventory[{Slot:1b}]';
 const beforeBook=await consoleCommand(query);
 const itemNBT=(reply:string)=>reply.match(/has the following entity data: ([^\r\n]+)/)?.[1];
 const beforeItem=itemNBT(beforeBook);
 assert(beforeItem&&/id:\s*"minecraft:writable_book"/.test(beforeItem),"Server did not have a writable book in Slot1 before the edit");
 const pages=["Ordinary harmless building notes. ".repeat(24),"More ordinary harmless building notes. ".repeat(24)];
 await Promise.race([alice.bot.writeBook(37,pages).catch(()=>undefined),Bun.sleep(2500)]);await Bun.sleep(1500);
 const afterBook=await consoleCommand(query);
 const bookRequests=requests.filter(r=>r.channel==="book");
 const afterItem=itemNBT(afterBook);
 const bookPass=bookRequests.length===1&&bookRequests[0].length>1000&&bookRequests[0].response.verdict==="reject"&&afterItem===beforeItem;
 cases.push({name:"actual-oversized-book",pass:bookPass,length:bookRequests[0]?.length,beforeBook,afterBook,beforeItem,afterItem});assert(bookPass,"book payload was not rejected or changed server book");
 await consoleCommand("lp user ProofAlice permission set plex.ecc.chat.bypass true");
 const trusted="trustedproof "+longUrl;alice.bot.chat(trusted);await Bun.sleep(1800);
 const trustedPass=bob.messages.some(m=>m.includes(trusted))&&requests.some(r=>r.text===trusted&&r.trusted&&r.response.verdict==="deliver");cases.push({name:"trusted-exemption",pass:trustedPass});assert(trustedPass,"trusted exemption failed");
 await consoleCommand("lp user ProofAlice permission unset plex.ecc.chat.bypass");
 alice.bot.chat("ordinary native coverage proof");await Bun.sleep(1800);const clean=bob.messages.filter(m=>m.includes("ordinary native coverage proof")).length===1;cases.push({name:"clean-after-rejection",pass:clean});assert(clean,"clean chat failed after reject");
 const status=await consoleCommand("ecc status");const actionable=alice.messages.filter(m=>m.includes("Shorten this text, then try again.")).length;
 assert(actionable===4,"expected four actionable rejection notices");assert(status.includes("Held lines: 0,"),"rejected payload retained");
 const backendStatus=await(await fetch(endpoint.url+"/status",{headers:{Authorization:`Bearer ${endpoint.token}`}})).json();
 const result={cases,actionable,status,initialStatus,backendStatus,requests,alice:alice.messages,bob:bob.messages};
 writeFileSync(join(evidence,prefix+"-result.json"),JSON.stringify(result,null,2));
 console.log(JSON.stringify({cases:cases.map(c=>({name:c.name,pass:c.pass,length:c.length})),actionable,status}));
 console.log("PASS: actual Go coverage guard and real Paper/module rejected complete oversized payloads without delivery");
}catch(error){writeFileSync(join(evidence,prefix+"-failure.json"),JSON.stringify({cases,requests,error:String(error)},null,2));throw error;}
finally{for(const bot of bots)if(typeof bot.quit==="function")bot.quit();java.stdin.write("stop\n");await Promise.race([java.exited,Bun.sleep(20000)]);if(java.exitCode===null)java.kill();proxy.stop(true);}
