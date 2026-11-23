import contextlib
import http.server
import json
import os
from pathlib import Path
import socket, ssl, subprocess, sys, threading, time, uuid
from contextlib import ExitStack

class Origin(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *args):
        pass
    def do_GET(self):
        mode=Path('scenario').read_text()
        if self.path == '/baseline':
            time.sleep(1.3 if mode=='budget' else 0.060)
            self.send_response(204)
            self.end_headers()
        else:
            self.send_response(200)
            self.send_header('Content-Length', '65536')
            self.end_headers()
            self.wfile.write(b'x'*8192)
            self.wfile.flush()
            if mode=='stall':
                time.sleep(.72)
            if mode=='truncated':
                self.close_connection=True
            else:
                self.wfile.write(b'x'*(65536-8192))
    def do_HEAD(self):
        self.send_response(403)
        self.send_header('Content-Length','0')
        self.end_headers()

tls_server=dict(enabled=True,certificate_path='lab.crt',key_path='lab.key')
tls_client=dict(enabled=True,server_name='localhost',certificate_path='lab.crt')
pw='lab'
user={'username':'test','password':pw}
uid=str(uuid.uuid4())
servers=[]
clients=[]
out=[]
rules=[]
for i,t in enumerate(['tuic','hysteria2','naive','naive']):
    tag=f'p{i}'
    base={'type':t,'tls':tls_server,'listen':'127.0.0.1','listen_port':23021+i}
    conf={'type':t,'tag':tag,'tls':tls_client,'server':'127.0.0.1','server_port':23021+i,'password':pw}
    if t=='tuic':
        base['users']=[{'uuid':uid,'password':pw}]
        conf['uuid']=uid
    elif t=='hysteria2':
        base.update(users=[{'password':pw}],up_mbps=60,down_mbps=60)
        conf.update(up_mbps=30,down_mbps=30)
    else:
        base.update(users=[user],network='tcp' if i==2 else 'udp')
        conf.update(username='test',quic=i==3)
        if i==3:
            base['listen']='::1'
            conf['server']='::1'
    servers.append(base)
    clients.append({'type':'http','tag':'in'+tag,'listen':'127.0.0.1','listen_port':23011+i,'users':[{'username':'quality','password':pw}]})
    rules.append({'inbound':'in'+tag,'action':'route','outbound':tag})
    out.append(conf)
core=next(Path('.').glob('sing-box-*/sing-box')).absolute()
with ExitStack() as cleanup:
    for port in (23101,23102,23103):
        server=http.server.ThreadingHTTPServer(('127.0.0.1',port),Origin)
        server.handle_error=lambda *args: None
        tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain('lab.crt','lab.key')
        server.socket=tls.wrap_socket(server.socket,server_side=True)
        threading.Thread(target=server.serve_forever,daemon=True).start()
        cleanup.callback(server.server_close)
    for label,config in [('server',{'inbounds':servers,'outbounds':[{'type':'direct'}]}),('client',{'inbounds':clients,'outbounds':out,'route':{'rules':rules}})]:
        config['log']={'level':'debug'}
        Path(label+'.json').write_text(json.dumps(config))
        with open(label+'.log','w') as log:
            p=subprocess.Popen([core,'run','-c',label+'.json'],stdout=log,stderr=log)
        cleanup.callback(p.wait)
        cleanup.callback(p.terminate)
        time.sleep(.25)
        if p.poll() is not None:
            raise RuntimeError(Path(label+'.log').read_text()[-2500:])
    cp='compiled:deps/*:kotlinc/lib/kotlinx-coroutines-core-jvm.jar:kotlinc/lib/kotlin-stdlib.jar'
    def run_matrix(mode):
        env = dict(os.environ, QUALITY_MATRIX=mode)
        done = subprocess.run(['java','-Djavax.net.ssl.trustStore=lab.trust','-Djavax.net.ssl.trustStorePassword=changeit','-cp',cp,'io.nekohasekai.sagernet.bg.test.NetworkLabKt'], env=env, stdout=open('lab-results.txt','w'), stderr=subprocess.STDOUT)
        data = Path('lab-results.txt').read_text()
        print('\n'.join(s for s in data.splitlines() if ' -> ' in s), flush=True)
        if done.returncode:
            print(data[-1600:])
            print(Path('client.log').read_text()[-1200:])
        return done.returncode
    if run_matrix('normal'):
        sys.exit(1)
    # Impair only packets on the test proxies' outer transport, leaving the local HTTP inlet alone.
    def tc(*args):
        subprocess.run(['sudo','tc',*args],check=True)
    tc('qdisc','add','dev','lo','root','handle','1:','prio')
    cleanup.callback(subprocess.run,['sudo','tc','qdisc','del','dev','lo','root'])
    tc('qdisc','add','dev','lo','parent','1:3','handle','30:','netem','delay','30ms','5ms','rate','6mbit')
    for address_family, transport, port in [('ip','udp',23021),('ip','udp',23022),('ip','tcp',23023),('ipv6','udp',23024)]:
        for field in ['src_port','dst_port']:
            tc('filter','add','dev','lo','protocol',address_family,'parent','1:','prio',str(port-23000),'flower','ip_proto',transport,field,str(port),'classid','1:3')
    status = run_matrix('mobile')
    tc('-s','qdisc','show','dev','lo')
    sys.exit(status)
