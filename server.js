const http=require('http'),fs=require('fs'),path=require('path');
const port=process.env.PORT||3000;
http.createServer((req,res)=>{
  const u=new URL(req.url,'http://localhost');
  let f=u.pathname==='/logo.png'?'logo.png':'index.html';
  const p=path.join(__dirname,'public',f);
  fs.readFile(p,(e,d)=>{if(e){res.writeHead(404);return res.end('Not found')}res.writeHead(200,{'Content-Type':f.endsWith('.png')?'image/png':'text/html; charset=utf-8'});res.end(d)});
}).listen(port,()=>console.log('SPFO Initial Mail running on '+port));
