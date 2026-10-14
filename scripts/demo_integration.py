#!/usr/bin/env python3
"""Fictional HTTP acceptance example, run from project root on a fresh local demo stack.
Includes demo_financial.py, then device outbox, partner isolation, reports and a local AI draft.
No paid AI request is sent. No camera, fingerprint reader, scale or machinery is operated.
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4

env={}
if Path('.env').exists():
    for line in Path('.env').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key,value=line.split('=',1);env[key.strip()]=value.strip().strip('"').strip("'")
keys={'integration':'INTEGRATION_PASSWORD','reports':'REPORTS_PASSWORD','device':'DEVICE_PASSWORD','customer':'CUSTOMER_PASSWORD','customer2':'CUSTOMER2_PASSWORD','supplier':'SUPPLIER_PASSWORD','manager':'CATALOG_MANAGER_PASSWORD','workforce':'WORKFORCE_PASSWORD'}
passwords={r:os.environ.get(k,env.get(k,'')) for r,k in keys.items()}
if not all(passwords.values()):raise SystemExit('Configure all integration and earlier demo passwords in .env or environment.')
base=os.environ.get('PLASTIFLOW_API_URL','http://127.0.0.1:8080')
suffix=uuid4().hex[:8].upper()
def call(role,method,path,body=None,expected=None,text=False):
    req=Request(base+path,method=method,data=json.dumps(body).encode() if body is not None else None,headers={'Authorization':'Basic '+base64.b64encode((role+':'+passwords[role]).encode()).decode(),'Content-Type':'application/json'})
    try:
        with urlopen(req,timeout=30) as response:
            if expected is not None:assert response.status==expected
            raw=response.read().decode()
            return raw if text else json.loads(raw) if raw else None
    except HTTPError as ex:
        if expected==ex.code:return json.loads(ex.read())
        raise SystemExit(f'{method} {path}: HTTP {ex.code}: {ex.read().decode()}') from None
B='/api/v1/integration'
def post(path,body=None):return call('integration','POST',B+path,body)
initial=call('integration','GET',B+'/overview')
if any(d['principal']=='device' for d in initial['device']):
    raise SystemExit('This example requires an unbound demo device account; use a fresh disposable stack or inspect existing records.')
upstream=subprocess.run([sys.executable,'scripts/demo_financial.py'],capture_output=True,text=True)
if upstream.returncode:raise SystemExit(upstream.stderr or upstream.stdout)
finance=json.loads(upstream.stdout)
employee=call('workforce','POST','/api/v1/manufacturing/employees',{'code':'IOT-EMP-'+suffix,'name':'Fictional device employee','userSubject':'iot-employee-'+suffix})['id']
device=post('/devices',{'code':'CLOCK-'+suffix,'name':'Example attendance bridge','kind':'ATTENDANCE','principal':'device'})['id']
post(f'/devices/{device}/identities',{'externalPerson':'badge-'+suffix,'employeeId':employee})
with tempfile.TemporaryDirectory() as directory:
    outbox=directory+'/outbox.sqlite';payload=directory+'/event.json'
    Path(payload).write_text(json.dumps({'occurredAt':(datetime.now(timezone.utc)-timedelta(minutes=1)).isoformat(),'data':{'person':'badge-'+suffix,'kind':'IN'}}))
    bridge_env=os.environ.copy();bridge_env['DEVICE_PASSWORD']=passwords['device']
    result=subprocess.run([sys.executable,'scripts/device_bridge.py','--outbox',outbox,'enqueue','--file',payload],env=bridge_env,capture_output=True,text=True,check=True)
    event=json.loads(result.stdout)['event_id']
    result=subprocess.run([sys.executable,'scripts/device_bridge.py','--outbox',outbox,'flush','--url',base,'--allow-local-demo'],env=bridge_env,capture_output=True,text=True,check=True)
    assert json.loads(result.stdout)['sent']==1
    applied=post(f'/events/{event}/apply')
    assert applied['state']=='APPLIED'
    assert post(f'/events/{event}/apply')['state']=='APPLIED'
attendance=call('workforce','GET','/api/v1/payroll/attendance-overview')
assert sum(e['employee_id']==employee for e in attendance['clockEvents'])==1
assert not any(e['employee_id']==employee for e in attendance['attendance'])
product=call('manager','POST','/api/v1/products',{'sku':'PORTAL-BOX-'+suffix,'name':'Published example container','kind':'FINISHED_GOOD','unit':'PIECE'})['id']
post('/published-products/'+product)
for username,kind,reference in [('customer','CUSTOMER','DEMO-PARTY'),('customer2','CUSTOMER','OTHER-'+suffix),('supplier','SUPPLIER','SUPPLIER-'+suffix)]:
    partner=post('/partners',{'code':username.upper()+'-'+suffix,'name':username+' example company','kind':kind,'partyReference':reference})['id']
    post(f'/partners/{partner}/users',{'principal':username})
request=call('customer','POST','/api/v1/portal/requests',{'eventId':str(uuid4()),'kind':'SALES_REQUEST','reference':'RFQ-'+suffix,'description':'Please confirm 100 example containers','lines':[{'productId':product,'quantity':100}]})
call('customer2','GET','/api/v1/portal/requests/'+request['id'],expected=404)
post('/portal-requests/'+request['id']+'/decision',{'accepted':True,'notes':'Reviewed; commercial confirmation is separate'})
customer=call('customer','GET','/api/v1/portal/overview')
other=call('customer2','GET','/api/v1/portal/overview')
assert customer['requests'][0]['state']=='ACKNOWLEDGED' and len(customer['invoices'])==1
assert not other['requests'] and not other['invoices']
call('supplier','POST','/api/v1/portal/requests',{'eventId':str(uuid4()),'kind':'SUPPLY_OFFER','reference':'OFFER-'+suffix,'description':'Example available supply','lines':[{'productId':product,'quantity':50}]})
day=datetime.now(timezone.utc).date();tomorrow=day+timedelta(days=1)
query=f'?from={day.isoformat()}&to={tomorrow.isoformat()}'
report=call('reports','GET',B+'/reports/production'+query)
assert report['output_events']>=1 and report['products']
csv=call('reports','GET',B+'/reports/production.csv'+query,text=True)
assert csv.startswith('sku,name,unit,good_qty,reject_qty')
draft=call('reports','POST',B+'/ai/jobs',{'eventId':str(uuid4()),'from':day.isoformat(),'to':tomorrow.isoformat()})
assert draft['state']=='QUEUED' and draft['output'] is None
print(json.dumps({'upstream_financial_verified':finance['trial_balance_balanced'],'device_event':event,'raw_clock_applied_once':True,'attendance_automatically_approved':False,'partner_isolation_verified':True,'production_report_events':report['output_events'],'csv_export_verified':True,'ai_job':draft['id'],'ai_state':'QUEUED','live_ai_call_made':False},indent=2))
