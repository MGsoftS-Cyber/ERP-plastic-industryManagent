#!/usr/bin/env python3
"""Starts a disposable H2 API for browser/API acceptance and always stops it.
Prerequisite: mvn test-compile dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test
Commands: --browser for Playwright; --demo for the integration HTTP example.
Never points at an existing factory database; credentials are synthetic test settings.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.request import build_opener,ProxyHandler

ROOT=Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--browser',action='store_true');parser.add_argument('--demo',action='store_true')
args=parser.parse_args()
cpfile=ROOT/'target/test-classpath.txt'
if not cpfile.exists():parser.error('Build the test classpath using the Maven command in this script.')
cp=os.pathsep.join([str(ROOT/'target/classes'),str(ROOT/'target/test-classes'),cpfile.read_text().strip()])
env=os.environ.copy();env.update(dict(re.findall(r'^([A-Z0-9_]+_PASSWORD):\s*(.+)$',(ROOT/'src/test/resources/application-test.yaml').read_text(),re.M)))
env.update(PLASTIFLOW_API_URL='http://127.0.0.1:8090',PLASTIFLOW_TEST_API='http://127.0.0.1:8090',NO_PROXY='127.0.0.1,localhost',no_proxy='127.0.0.1,localhost')
result_dir=ROOT/'acceptance-results';result_dir.mkdir(exist_ok=True)
fixture=result_dir/'browser-fixture.json'
if fixture.exists():fixture.unlink()
with (result_dir/'api.log').open('w') as log:
    server=subprocess.Popen(['java','-cp',cp,'com.plastiflow.PlastiFlowApplication','--spring.profiles.active=demo,test','--server.address=127.0.0.1','--server.port=8090','--PLASTIFLOW_AI_ENABLED=false','--spring.datasource.url=jdbc:h2:mem:acceptance;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1','--spring.datasource.username=sa','--spring.datasource.password='],cwd=ROOT,env=env,stdout=log,stderr=log)
    opener=build_opener(ProxyHandler({}))
    try:
        for _ in range(120):
            if server.poll() is not None:raise RuntimeError('Acceptance API stopped; see acceptance-results/api.log')
            try:
                with opener.open(env['PLASTIFLOW_API_URL']+'/actuator/health',timeout=1) as response:
                    if response.status==200:break
            except Exception:time.sleep(.25)
        else:raise RuntimeError('Acceptance API did not become healthy')
        if args.demo:subprocess.run(['python3','scripts/demo_integration.py'],cwd=ROOT,env=env,check=True)
        if args.browser:subprocess.run(['npm','run','test:e2e'],cwd=ROOT/'frontend',env=env,check=True)
        if not args.browser and not args.demo:parser.error('Select --browser or --demo')
    finally:
        server.terminate()
        try:server.wait(timeout=8)
        except subprocess.TimeoutExpired:server.kill();server.wait(timeout=5)
