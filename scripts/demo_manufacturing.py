#!/usr/bin/env python3
"""Create one real API-driven manufacturing example. Requires a running demo stack.
Run from the project root: python scripts/demo_manufacturing.py
Creates records on each run; references use a new suffix. No passwords are logged.
"""
import base64
import json
import os
from pathlib import Path
from datetime import datetime, timezone, timedelta
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4

env = {}
if Path('.env').exists():
    for line in Path('.env').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            env[key.strip()] = value.strip().strip('"').strip("'")
roles = {'manager':'CATALOG_MANAGER_PASSWORD','production':'PRODUCTION_PASSWORD','quality':'QUALITY_PASSWORD','workforce':'WORKFORCE_PASSWORD'}
passwords = {name: os.environ.get(key, env.get(key, '')) for name, key in roles.items()}
if not all(passwords.values()):
    raise SystemExit('Set the four manager passwords in .env or environment variables first.')
base = os.environ.get('PLASTIFLOW_API_URL', 'http://127.0.0.1:8080')
suffix = uuid4().hex[:8].upper()

def call(role, method, route, payload=None):
    auth = base64.b64encode((role+':'+passwords[role]).encode()).decode()
    body = json.dumps(payload).encode() if payload is not None else None
    request = Request(base+route, data=body, method=method, headers={'Authorization':'Basic '+auth,'Content-Type':'application/json'})
    try:
        with urlopen(request, timeout=20) as response:
            data = response.read()
            return json.loads(data) if data else None
    except HTTPError as error:
        raise SystemExit(f'{method} {route}: HTTP {error.code}: {error.read().decode()}') from None

def create(role, resource, payload):
    return call(role, 'POST', '/api/v1/manufacturing/'+resource, payload)['id']

def put(role, resource, payload):
    return call(role, 'PUT', '/api/v1/manufacturing/'+resource, payload)

material = call('manager','POST','/api/v1/products',{'sku':'PP-'+suffix,'name':'PP resin','kind':'RAW_MATERIAL','unit':'KG'})['id']
product = call('manager','POST','/api/v1/products',{'sku':'BOX-'+suffix,'name':'Plastic container','kind':'FINISHED_GOOD','unit':'PIECE'})['id']
machine = create('production','machines',{'code':'IM-'+suffix,'name':'Injection machine','process':'INJECTION','capacityKg':1000})
mold = create('production','molds',{'code':'MOLD-'+suffix,'name':'Container mold','cavities':4})
put('production',f'machines/{machine}/molds/{mold}',None)
worker = create('workforce','employees',{'code':'EMP-'+suffix,'name':'Example operator','userSubject':'operator-'+suffix})
now = datetime.now(timezone.utc)
put('workforce',f'employees/{worker}/qualifications',{'machineId':machine,'validUntil':(now+timedelta(days=30)).date().isoformat()})
shift = create('workforce','shifts',{'employeeId':worker,'start':(now-timedelta(minutes=10)).isoformat(),'end':(now+timedelta(hours=8)).isoformat()})
recipe = create('production','recipes',{'productId':product,'process':'INJECTION','yieldFactor':0.9,'settings':{'temperatureC':'210'},'packaging':'100 pieces per carton','components':[{'materialId':material,'kgPerUnit':0.01}],'operations':[{'name':'Molding','minutesPerUnit':0.01}]})
plan = create('quality','inspection-plans',{'productId':product,'metrics':[{'name':'weight','unit':'g','minimum':9,'maximum':11}]})
order = create('production','orders',{'code':'PO-'+suffix,'salesReference':'EXTERNAL-SALES-'+suffix,'recipeId':recipe,'inspectionPlanId':plan,'plannedQty':100})
lot = create('production','lots',{'code':'LOT-'+suffix,'materialId':material,'supplierReference':'EXTERNAL-SUPPLIER-'+suffix,'location':'RAW-A','receivedKg':2})
put('quality',f'lots/{lot}/quality',{'quality':'APPROVED','reason':'Incoming checks reviewed'})
put('production',f'orders/{order}/schedule',{'machineId':machine,'moldId':mold,'employeeId':worker,'shiftId':shift,'start':(now-timedelta(minutes=1)).isoformat(),'end':(now+timedelta(hours=2)).isoformat()})
call('production','POST',f'/api/v1/manufacturing/orders/{order}/start')
call('production','POST',f'/api/v1/manufacturing/orders/{order}/consumptions',{'eventId':str(uuid4()),'lotId':lot,'kg':1.12})
call('production','POST',f'/api/v1/manufacturing/orders/{order}/outputs',{'eventId':str(uuid4()),'goodQty':100,'rejectQty':3,'reason':'Startup scrap'})
batch = call('production','POST',f'/api/v1/manufacturing/orders/{order}/complete')['id']
create('quality',f'batches/{batch}/inspections',{'values':{'weight':10}})
put('quality',f'batches/{batch}/quality',{'quality':'APPROVED','reason':'Passed inspection'})
pallet = create('production','pallets',{'code':'PALLET-'+suffix,'batchId':batch,'qty':100})
create('production','dispatch-references',{'palletId':pallet,'shipmentReference':'EXTERNAL-SHIPMENT-'+suffix,'customerReference':'EXTERNAL-CUSTOMER-'+suffix})
trace = call('production','GET',f'/api/v1/manufacturing/batches/{batch}/trace')
assert len(trace['source_lots']) == 1 and len(trace['pallets']) == 1 and len(trace['dispatch_references']) == 1
print(json.dumps({'order':order,'batch':batch,'pallet':pallet,'trace_verified':True},indent=2))
