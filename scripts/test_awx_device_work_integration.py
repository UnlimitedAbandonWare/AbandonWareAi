"""Real CLI and catalog entry points, with shared-read and legacy contract bounds."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT=Path(__file__).absolute().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
import awx_mcp_stdio_server as server
import awx_mcp_toolbox as toolbox


class DeviceToolIntegrationTest(unittest.TestCase):
    def test_registered_catalog_and_producer_dependencies(self):
        catalog=server.registry()
        self.assertTrue(catalog['health']['ok'],catalog['health'])
        entry=next(t for t in catalog['tools'] if t['name']=='device_work')
        self.assertFalse(entry['readOnly'])
        self.assertEqual(set(entry['input_schema']['properties']['action']['enum']),
                         {'status','probe','enqueue','route','claim','next','start','complete'})
        source=(ROOT/'scripts/awx_mcp_toolbox.py').read_text(encoding='utf-8')
        for name in ['awx_device_work.py','awx_device_policy.py']:
            self.assertIn('"scripts/'+name+'"',source)

    def test_real_toolbox_cli_status_and_invalid_envelope(self):
        with tempfile.TemporaryDirectory() as directory:
            for extra,ok in [({},True),({'command':'arbitrary'},False)]:
                result=subprocess.run([sys.executable,'-B',str(ROOT/'scripts/awx_mcp_toolbox.py'),'device_work'],
                    input=json.dumps(dict(root=directory,action='status',**extra)),capture_output=True,text=True,timeout=20)
                output=json.loads(result.stdout)
                self.assertEqual(output['ok'],ok)
                self.assertFalse(output['sourceMutation'])
            self.assertFalse((Path(directory)/'data').exists())

    def test_stdio_schema_and_closed_output_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            result=server.call_tool({'name':'device_work','arguments':{'action':'status','root':directory}})
            self.assertFalse(result.get('isError',False),result)
            output=json.loads(result['content'][0]['text'])
            self.assertTrue(output['ok']); self.assertEqual(output['result']['tasks'],[])
        with self.assertRaises(server.ProtocolError):
            server.validate_tool_call({'name':'device_work','arguments':{'action':'status','command':'x'}})

    def test_shared_read_mcp_rejects_artifact_mutation(self):
        with mock.patch.dict(os.environ,{'AWX_MCP_SOURCE_ACCESS':'shared-read'}):
            with self.assertRaises(server.ProtocolError):
                server.validate_tool_call({'name':'device_work','arguments':{'action':'probe'}})

    def test_direct_module_cli_rejects_bad_json(self):
        result=subprocess.run([sys.executable,'-B',str(ROOT/'scripts/awx_device_work.py')],
                              input='not-json',capture_output=True,text=True,timeout=10)
        self.assertEqual(result.returncode,2)
        self.assertEqual(json.loads(result.stdout)['reasonCode'],'invalid-input-json')


if __name__=='__main__': unittest.main()
