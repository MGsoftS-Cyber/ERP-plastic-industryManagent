#!/usr/bin/env python3
"""Vendor-neutral device outbox. Does not capture biometrics or operate equipment.
A vendor adapter supplies {occurredAt: ISO timestamp, data: registered-device fields}.
Enqueue persists an event UUID/sequence once; flush retries the same payload.
Production credentials: PLASTIFLOW_DEVICE_TOKEN (OIDC bearer). Local demo only:
DEVICE_PASSWORD + --allow-local-demo on a loopback URL.
"""
import argparse
import base64
import json
import os
import sqlite3
import time
from datetime import datetime
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler
from uuid import uuid4, UUID

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def connect(path):
    if str(path)!=':memory:':
        try:
            fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_RDWR,0o600);os.close(fd)
        except FileExistsError:pass
    db=sqlite3.connect(path)
    db.execute('PRAGMA busy_timeout=5000')
    db.execute('CREATE TABLE IF NOT EXISTS metadata (id INTEGER PRIMARY KEY, sequence INTEGER NOT NULL)')
    db.execute('INSERT OR IGNORE INTO metadata VALUES(1,0)')
    db.execute('CREATE TABLE IF NOT EXISTS outbox (event_id TEXT PRIMARY KEY, sequence INTEGER UNIQUE NOT NULL, payload TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL, next_attempt REAL NOT NULL, error TEXT)')
    db.execute("CREATE INDEX IF NOT EXISTS ix_outbox_delivery ON outbox(state,next_attempt,sequence)")
    db.commit()
    return db

def enqueue(db, source, event_id=None):
    if not isinstance(source,dict) or set(source)!={'occurredAt','data'} or not isinstance(source['data'],dict):
        raise ValueError('Source must contain only occurredAt and data.')
    legal=[{'person','kind'},{'state'},{'value'},{'visitId','kind','kg'}]
    if set(source['data']) not in legal or not all(isinstance(v,str) and 0<len(v)<=240 for v in source['data'].values()):
        raise ValueError('Use the registered device fields as short strings; biometric images/templates are not accepted.')
    at=datetime.fromisoformat(source['occurredAt'].replace('Z','+00:00'))
    if at.tzinfo is None: raise ValueError('Timestamp must include its timezone.')
    event_id=str(UUID(event_id)) if event_id else str(uuid4())
    db.execute('BEGIN IMMEDIATE')
    try:
        old=db.execute('SELECT sequence,payload FROM outbox WHERE event_id=?',(event_id,)).fetchone()
        if old:
            payload={'eventId':event_id,'sequence':old[0],**source}
            if json.loads(old[1])!=payload: raise ValueError('Event ID already has another payload.')
        else:
            sequence=db.execute('SELECT sequence FROM metadata WHERE id=1').fetchone()[0]+1
            payload={'eventId':event_id,'sequence':sequence,**source}
            db.execute('UPDATE metadata SET sequence=? WHERE id=1',(sequence,))
            db.execute('INSERT INTO outbox VALUES(?,?,?,\'QUEUED\',0,0,null)',(event_id,sequence,json.dumps(payload)))
        db.commit()
        return event_id
    except Exception:
        db.rollback()
        raise

def credential(url, local_demo=False):
    parsed=urlsplit(url)
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Use a base URL without credentials, query or fragment.')
    if parsed.scheme!='https' and not (local_demo and parsed.scheme=='http' and parsed.hostname in {'localhost','127.0.0.1','::1'}):
        raise ValueError('HTTPS is required except for explicitly enabled loopback demonstration.')
    token=os.environ.get('PLASTIFLOW_DEVICE_TOKEN','')
    if token: return 'Bearer '+token
    password=os.environ.get('DEVICE_PASSWORD','')
    if local_demo and parsed.hostname in {'localhost','127.0.0.1','::1'} and password:
        return 'Basic '+base64.b64encode(('device:'+password).encode()).decode()
    raise ValueError('Set the device OIDC token, or the local demo device password.')

def flush(db, url, local_demo=False, limit=100, opener=None):
    auth=credential(url,local_demo)
    opener=opener or build_opener(NoRedirect())
    rows=db.execute("SELECT event_id,payload,attempts FROM outbox WHERE state='QUEUED' AND next_attempt<=? ORDER BY sequence LIMIT ?",(time.time(),limit)).fetchall()
    sent=dead=0
    for event_id,payload,attempts in rows:
        req=Request(url.rstrip('/')+'/api/v1/integration/device-events',data=payload.encode(),method='POST',headers={'Authorization':auth,'Content-Type':'application/json'})
        try:
            with opener.open(req,timeout=25) as response:
                raw=response.read(65537)
                if response.status!=201 or len(raw)>65536:raise ValueError('Invalid acknowledgement')
                result=json.loads(raw)
                if result.get('id')!=event_id:raise ValueError('Acknowledgement ID differs')
            db.execute("UPDATE outbox SET state='SENT',attempts=attempts+1,error=null WHERE event_id=?",(event_id,));sent+=1
        except HTTPError as ex:
            permanent=(300<=ex.code<500 and ex.code not in {401,403,408,429})
            db.execute('UPDATE outbox SET state=?,attempts=attempts+1,next_attempt=?,error=? WHERE event_id=?',('DEAD' if permanent else 'QUEUED',time.time()+min(300,2**min(attempts+1,8)),'HTTP_'+str(ex.code),event_id))
            dead+=int(permanent)
            if ex.code in {401,403}:db.commit();break
        except (URLError,TimeoutError,OSError,ValueError):
            db.execute("UPDATE outbox SET attempts=attempts+1,next_attempt=?,error='TRANSPORT_OR_ACK_FAILURE' WHERE event_id=?",(time.time()+min(300,2**min(attempts+1,8)),event_id))
        db.commit()
    return {'sent':sent,'dead':dead,'queued':db.execute("SELECT count(*) FROM outbox WHERE state='QUEUED'").fetchone()[0]}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--outbox',default='device-outbox.sqlite')
    commands=parser.add_subparsers(dest='command',required=True)
    add=commands.add_parser('enqueue');add.add_argument('--file',required=True);add.add_argument('--event-id')
    send=commands.add_parser('flush');send.add_argument('--url',required=True);send.add_argument('--allow-local-demo',action='store_true');send.add_argument('--limit',type=int,default=100)
    retry=commands.add_parser('retry');retry.add_argument('--event-id',required=True)
    commands.add_parser('status')
    args=parser.parse_args()
    with connect(args.outbox) as db:
        try:
            if args.command=='enqueue':print(json.dumps({'event_id':enqueue(db,json.loads(Path(args.file).read_text()),args.event_id)}))
            elif args.command=='flush':
                if not 1<=args.limit<=200:raise ValueError('Limit must be between 1 and 200.')
                print(json.dumps(flush(db,args.url,args.allow_local_demo,args.limit)))
            elif args.command=='retry':
                result=db.execute("UPDATE outbox SET state='QUEUED',next_attempt=0,error=null WHERE event_id=? AND state='DEAD'",(str(UUID(args.event_id)),))
                db.commit();print(json.dumps({'requeued':result.rowcount}))
            else:print(json.dumps(dict(db.execute('SELECT state,count(*) FROM outbox GROUP BY state').fetchall())))
        except (ValueError,OSError) as ex:parser.error(str(ex))
if __name__=='__main__':main()
