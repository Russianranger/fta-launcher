#!/usr/bin/env python3
"""Capture read-only GT query evidence from an isolated, hash-verified Thor backup.

Never starts or edits the supplied backup in place. No seed replay or original
SQL changes. Captured coefficient rows stay private; public evidence is bounded
to counts, fingerprints and unchanged three-schema metadata.
"""
from pathlib import Path
import json,hashlib,zipfile,shutil,secrets,socket,subprocess,time,os,re,argparse,tempfile
repo=Path(__file__).resolve().parents[1]
from run_database_gate import DatabaseCLI
from create_world_updater_lock import postconditions
def sha(p):
 with Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('backup','source-zip','report','server','client','snapshots','output'):
     parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args()
    backup=args.backup
    assert sha(backup)=='0b5fb6ef6912add622f6371d5a1cfcbde3bd63155d03b95ecec6e6d0fa36df8f'
    temporary=tempfile.TemporaryDirectory(prefix='fta-dbc-backup-');work=Path(temporary.name);work.chmod(0o700)
    report=json.loads(args.report.read_text())
    assert report['apkSha256']=='4f3f224abaae30837ded42fbccfbefcf52f07df68a5a07fa8a7a76400555bdb5'
    cases=[p for p in report['gameDataDiagnostic']['problems'] if p['asset'].startswith('dbc/gt')]
    from fta_source import SOURCE_SHA256,SOURCE_ROOT
    assert sha(args.source_zip)==SOURCE_SHA256
    with zipfile.ZipFile(args.source_zip) as source_zip:
     source=source_zip.read(SOURCE_ROOT+'/src/server/game/DataStores/DBCStores.cpp').decode()
    tablemap={name:table for name,table in re.findall(r'LOAD_DBC\(\w+,\s*"([^"]+)",\s*"([^"]+)"\)',source)}
    private=args.snapshots;private.mkdir(mode=0o700,parents=True,exist_ok=True)
    with zipfile.ZipFile(backup) as z:
     props=dict(l.split('=',1) for l in z.read('fta-backup.properties').decode().splitlines() if '=' in l and not l.startswith('#'))
     assert props['format']=='FTA_COLD_REALM_1' and props['count']=='1365'
     checked=0
     for i in range(int(props['count'])):
      name=props[f'{i}.path'];p=work/'restored'/name
      assert not Path(name).is_absolute() and '..' not in Path(name).parts
      if props[f'{i}.directory']=='true':p.mkdir(parents=True,exist_ok=True);continue
      p.parent.mkdir(parents=True,exist_ok=True)
      with z.open(name) as src,p.open('wb') as dest:shutil.copyfileobj(src,dest,1024*1024)
      assert p.stat().st_size==int(props[f'{i}.size']) and sha(p)==props[f'{i}.sha256']
      checked+=1
    server=args.server.resolve();base=server.parent.parent
    assert sha(server)=='6ce57fb4ff16449fab69cd9e9079a37b1a18c517a9f293a90205cd843b32bbee'
    with socket.socket() as reserve:reserve.bind(('127.0.0.1',0));port=reserve.getsockname()[1]
    secret=secrets.token_hex(24);defaults=work/'client.cnf';defaults.write_text('[client]\nuser=fta_dbc_host\npassword='+secret+'\n');defaults.chmod(0o600)
    init=work/'init.sql';init.write_text("CREATE USER 'fta_dbc_host'@'127.0.0.1' IDENTIFIED BY '"+secret+"';\nGRANT ALL PRIVILEGES ON *.* TO 'fta_dbc_host'@'127.0.0.1';\n");init.chmod(0o600)
    cli=DatabaseCLI(str(args.client.resolve()),work/'unused.sock',work,port=port,defaults_file=defaults)
    server_args=[str(server),'--no-defaults','--basedir='+str(base),'--datadir='+str(work/'restored/database'),'--socket=','--bind-address=127.0.0.1','--port='+str(port),'--pid-file='+str(work/'db.pid'),'--init-file='+str(init),'--skip-name-resolve','--performance-schema=OFF','--innodb-buffer-pool-size=64M','--innodb-log-file-size=16M','--aria-pagecache-buffer-size=16M','--key-buffer-size=8M','--max-connections=8','--thread-cache-size=2']
    if os.geteuid()==0:server_args.append('--user=root')
    proof={'backupSha256':sha(backup),'backupFilesVerified':checked,'serverSha256':sha(server),'tables':{}}
    with (work/'server.log').open('w') as log:
     process=subprocess.Popen(server_args,stdout=log,stderr=subprocess.STDOUT)
     try:
      deadline=time.monotonic()+45
      while True:
       assert process.poll() is None,'host SQL exited; inspect private log'
       try:
        if cli.query('SELECT 1;')=='1':break
       except Exception:pass
       assert time.monotonic()<deadline,'host readiness deadline';time.sleep(.15)
      assert cli.query('SELECT VERSION();')=='12.3.2-MariaDB'
      expected=json.loads((repo/'manifests/world-updater-lock.json').read_text())['schemas']
      before=postconditions(cli);assert before==expected,'retained metadata mismatch'
      for case in cases:
       name=case['asset'].split('/')[-1];table=tablemap[name];rows=case['observedHeader']['rows']
       stats=cli.query(f'SELECT COUNT(*),MIN(ID),MAX(ID),COUNT(DISTINCT ID),SUM(Data IS NULL) FROM acore_world.`{table}`;')
       values=list(map(int,stats.split('\t')))
       print(table,values,'fileRows',rows,flush=True)
       assert values==[rows,0,rows-1,rows,0],table
       columns=cli.query(f"SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='acore_world' AND TABLE_NAME='{table}' ORDER BY ORDINAL_POSITION;")
       assert columns.splitlines()==['ID','Data']
       # Exact unchanged query constructed by the original DBCDatabaseLoader, using its world connection.
       query=f'SELECT * FROM `{table}` ORDER BY `ID` DESC'
       data=cli.run(sql=query,schema='acore_world')+'\n';p=private/(table+'.tsv');p.write_text(data);p.chmod(0o600)
       proof['tables'][table]={'rows':rows,'minimumId':0,'maximumId':rows-1,'allFileRowIndicesCovered':True,'columns':['ID','Data'],'privateQueryResultSha256':sha(p),'querySha256':hashlib.sha256(query.encode()).hexdigest()}
      assert postconditions(cli)==before
      proof.update(retainedMetadataMatchesBeforeAndAfter=True,sourceSqlRewritten=False,originalDataZipAvailable=False,hostOnly=True)
      cli.run(sql='SHUTDOWN;',capture=False);assert process.wait(timeout=30)==0
      proof['cleanSqlShutdown']=True
     finally:
      if process.poll() is None:process.terminate();process.wait(timeout=20)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(proof,indent=2)+'\n')
    temporary.cleanup()
    print('Read-only GT coverage passed on isolated verified Thor backup',flush=True)


if __name__ == "__main__":
    main()
