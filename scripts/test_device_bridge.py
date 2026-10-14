"""Offline bridge failure checks: python3 -m unittest discover -s scripts -p 'test_*.py'."""
import os
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import URLError, HTTPError
from uuid import uuid4
import device_bridge as bridge

class DeviceBridgeTest(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory()
        self.path=self.directory.name+'/queue.sqlite'
        self.db=bridge.connect(self.path)
        self.source={'occurredAt':'2026-10-14T12:00:00Z','data':{'person':'badge','kind':'IN'}}
    def tearDown(self):self.db.close();self.directory.cleanup()
    def test_restart_preserves_event_and_sequence(self):
        event=bridge.enqueue(self.db,self.source)
        self.db.close();self.db=bridge.connect(self.path)
        self.assertEqual(bridge.enqueue(self.db,self.source,event),event)
        self.assertEqual(self.db.execute('select count(*) from outbox').fetchone()[0],1)
        self.assertEqual(self.db.execute('select sequence from metadata').fetchone()[0],1)
    def test_payload_change_cannot_reuse_identifier(self):
        event=bridge.enqueue(self.db,self.source)
        with self.assertRaises(ValueError):bridge.enqueue(self.db,{**self.source,'data':{'person':'other','kind':'IN'}},event)
        self.assertEqual(self.db.execute('select count(*) from outbox').fetchone()[0],1)
    def test_https_and_bearer_required_outside_demo(self):
        with patch.dict(os.environ,{'PLASTIFLOW_DEVICE_TOKEN':'','DEVICE_PASSWORD':'fixture'},clear=True):
            with self.assertRaises(ValueError):bridge.credential('http://example.com',True)
            with self.assertRaises(ValueError):bridge.credential('https://example.com',True)
            self.assertTrue(bridge.credential('http://127.0.0.1:8080',True).startswith('Basic '))
    def test_network_failure_retains_original_payload_with_backoff(self):
        bridge.enqueue(self.db,self.source);old=self.db.execute('select payload from outbox').fetchone()[0]
        opener=unittest.mock.Mock();opener.open.side_effect=URLError('offline')
        with patch.dict(os.environ,{'PLASTIFLOW_DEVICE_TOKEN':'fixture'}):result=bridge.flush(self.db,'https://factory.test',opener=opener)
        self.assertEqual(result['queued'],1)
        payload,attempts,next_at=self.db.execute('select payload,attempts,next_attempt from outbox').fetchone()
        self.assertEqual(payload,old);self.assertEqual(attempts,1);self.assertGreater(next_at,0)
    def test_rejected_event_does_not_loop(self):
        bridge.enqueue(self.db,self.source)
        opener=unittest.mock.Mock();opener.open.side_effect=HTTPError('https://factory.test',409,'conflict',{},None)
        with patch.dict(os.environ,{'PLASTIFLOW_DEVICE_TOKEN':'fixture'}):result=bridge.flush(self.db,'https://factory.test',opener=opener)
        self.assertEqual(result['dead'],1);self.assertEqual(result['queued'],0)
    def test_redirects_do_not_forward_credentials(self):
        self.assertIsNone(bridge.NoRedirect().redirect_request(None,None,302,'redirect',{},'https://other.test'))
    def test_credentials_can_be_rotated_without_losing_event(self):
        bridge.enqueue(self.db,self.source)
        opener=unittest.mock.Mock();opener.open.side_effect=HTTPError('https://factory.test',401,'expired',{},None)
        with patch.dict(os.environ,{'PLASTIFLOW_DEVICE_TOKEN':'expired-fixture'}):result=bridge.flush(self.db,'https://factory.test',opener=opener)
        self.assertEqual(result['queued'],1);self.assertEqual(result['dead'],0)
    def test_biometric_template_is_not_a_supported_event(self):
        with self.assertRaises(ValueError):bridge.enqueue(self.db,{'occurredAt':'2026-10-14T12:00:00Z','data':{'fingerprintTemplate':'biometric-data'}})
        self.assertEqual(self.db.execute('select count(*) from outbox').fetchone()[0],0)
if __name__=='__main__':unittest.main()
