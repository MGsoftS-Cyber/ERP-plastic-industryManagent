#!/usr/bin/env python3
"""Run four example workflows against the local demo stack. Creates new records.
From project root: python scripts/demo_factory_operations.py
Requires passwords in .env or environment variables. No secrets are printed.
"""
import base64
import json
import os
from pathlib import Path
from datetime import datetime, timezone, timedelta
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from uuid import uuid4

env = {}
if Path('.env').exists():
    for line in Path('.env').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            env[key.strip()] = value.strip().strip('"').strip("'")
roles = {'production':'PRODUCTION_PASSWORD','maintenance':'MAINTENANCE_PASSWORD','facilities':'FACILITIES_PASSWORD','logistics':'LOGISTICS_PASSWORD','security':'SECURITY_PASSWORD','gate':'GATE_PASSWORD'}
passwords = {name:os.environ.get(key,env.get(key,'')) for name,key in roles.items()}
if not all(passwords.values()):
    raise SystemExit('Set production, maintenance, facilities, logistics, security and gate passwords first.')
base = os.environ.get('PLASTIFLOW_API_URL','http://127.0.0.1:8080')
suffix = uuid4().hex[:8].upper()
now = datetime.now(timezone.utc)

def call(role, method, path, payload=None):
    auth = base64.b64encode((role+':'+passwords[role]).encode()).decode()
    body = json.dumps(payload).encode() if payload is not None else None
    request = Request(base+path,data=body,method=method,headers={'Authorization':'Basic '+auth,'Content-Type':'application/json'})
    try:
        with urlopen(request,timeout=20) as response:
            body = response.read()
            return json.loads(body) if body else None
    except HTTPError as error:
        raise SystemExit(f'{method} {path}: HTTP {error.code}: {error.read().decode()}') from None
    except URLError:
        raise SystemExit('Cannot reach the API. Start the demo stack and check PLASTIFLOW_API_URL.') from None

def op(role, method, path, payload=None):
    return call(role,method,'/api/v1/operations'+path,payload)

def create(role,path,payload):
    return op(role,'POST',path,payload)['id']

# Machine repair: holds -> assigned repair -> review -> return to service.
machine = call('production','POST','/api/v1/manufacturing/machines',{'code':'IM-'+suffix,'name':'Demo injection machine','process':'INJECTION','capacityKg':1000})['id']
work = create('maintenance','/work-orders',{'code':'WO-'+suffix,'machineId':machine,'kind':'BREAKDOWN','description':'Inspect hydraulic leak','currency':'DZD'})
tech = create('maintenance','/technicians',{'code':'TECH-'+suffix,'name':'Demo technician','userSubject':'demo-technician-'+suffix})
op('maintenance','PUT',f'/work-orders/{work}/assignment',{'technicianId':tech})
op('maintenance','POST',f'/work-orders/{work}/start')
op('maintenance','POST',f'/work-orders/{work}/labor',{'eventId':str(uuid4()),'hours':2,'hourlyRate':100})
op('maintenance','POST',f'/work-orders/{work}/submit',{'notes':'Leak repaired; functional checks recorded'})
op('maintenance','POST',f'/work-orders/{work}/release',{'notes':'Manager approved return to service'})
assert op('maintenance','GET',f'/work-orders/{work}')['state']=='CLOSED'

# Infrastructure asset and service contract.
area = create('facilities','/areas',{'code':'AREA-'+suffix,'name':'Utilities'})
asset = create('facilities','/assets',{'code':'ASSET-'+suffix,'areaId':area,'name':'Air compressor','kind':'COMPRESSED_AIR'})
create('facilities',f'/assets/{asset}/inspections',{'passed':True,'notes':'Initial condition inspection passed'})
create('facilities','/service-contracts',{'assetId':asset,'supplierReference':'EXTERNAL-CONTRACT-'+suffix,'startsOn':now.date().isoformat(),'endsOn':(now+timedelta(days=365)).date().isoformat(),'amount':1000,'currency':'DZD'})

# Truck: appointment -> checked entry -> dock -> handling -> documents -> departure.
dock = create('logistics','/yard/spaces',{'code':'DOCK-'+suffix,'kind':'DOCK'})
truck = create('logistics','/truck-visits',{'reference':'TRUCK-'+suffix,'carrier':'Example carrier','driver':'Example driver','tractorPlate':'TRACTOR-'+suffix,'trailerPlate':'TRAILER-'+suffix,'purpose':'COLLECTION','documentReference':'EXTERNAL-SHIPMENT-'+suffix,'plannedStart':(now-timedelta(minutes=1)).isoformat(),'plannedEnd':(now+timedelta(hours=2)).isoformat()})
op('logistics','PUT',f'/truck-visits/{truck}/booking',{'spaceId':dock,'start':(now-timedelta(minutes=1)).isoformat(),'end':(now+timedelta(hours=2)).isoformat()})
op('gate','POST',f'/truck-visits/{truck}/arrive',{'notes':'Truck arrived'})
op('gate','POST',f'/truck-visits/{truck}/gate-check',{'accepted':True,'notes':'Driver and documents checked'})
op('logistics','PUT',f'/truck-visits/{truck}/space',{'spaceId':dock})
op('logistics','POST',f'/truck-visits/{truck}/handling',{'notes':'Loading started'})
for kind,kg in [('TARE',10000),('GROSS',12000)]:
    op('gate','POST',f'/truck-visits/{truck}/weights',{'eventId':str(uuid4()),'kind':kind,'kg':kg,'measuredAt':datetime.now(timezone.utc).isoformat()})
op('logistics','POST',f'/truck-visits/{truck}/finish',{'documentsChecked':True,'notes':'Loading and documents verified'})
op('gate','POST',f'/truck-visits/{truck}/depart',{'notes':'Exit confirmed'})
assert op('logistics','GET',f'/truck-visits/{truck}')['net_kg']==2000

# Visitor: briefing, badge, approved zone, exit, badge return.
zone = create('security','/zones',{'code':'ZONE-'+suffix,'name':'Contractor work area'})
badge = 'BADGE-'+suffix
op('security','POST','/badges',{'code':badge})
visitor = create('security','/visitors',{'reference':'VISITOR-'+suffix,'name':'Example contractor','company':'Service company','hostReference':'HOST-EXTERNAL','validFrom':(now-timedelta(minutes=1)).isoformat(),'validTo':(now+timedelta(hours=2)).isoformat(),'zoneIds':[zone]})
op('gate','POST',f'/visitors/{visitor}/check-in',{'badge':badge,'safetyBriefed':True})
for direction in ['ENTER','EXIT']:
    op('gate','POST',f'/visitors/{visitor}/access',{'eventId':str(uuid4()),'zoneId':zone,'direction':direction})
op('gate','POST',f'/visitors/{visitor}/check-out',{'badgeReturned':True})
assert op('gate','GET',f'/visitors/{visitor}')['state']=='CHECKED_OUT'
create('security','/cameras',{'code':'CAM-'+suffix,'zoneId':zone,'name':'Contractor gate camera','vmsReference':'EXTERNAL-VMS-'+suffix})
print(json.dumps({'work_order':work,'facility_asset':asset,'truck_visit':truck,'visitor':visitor,'four_workflows_verified':True},indent=2))
