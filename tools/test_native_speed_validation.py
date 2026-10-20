import unittest
from native_speed_validation import check_benchmark, identity_properties, REGIONS


def evidence():
    labels=['quality-java','quality-native','x60-y52-java','x60-y52-native']+[
        f'{region}-{v}-{i}' for region in REGIONS for v in ('old','java','native') for i in range(2)]
    b={'checks':'passed','timings':'controlled','quiet_seconds':15,'runs':{},'comparisons':{}}
    for label in labels:
        b['runs'][label]={'exit_code':0,'memory_floor_stop':False,'disk_floor_stop':False,
            'explorer_transfer_bytes':0,'seconds':80 if 'native' in label else 100,
            'peak_rss_mib':2500 if 'native' in label else 1500,'minimum_available_mib':8192}
        b['comparisons'][label]={'integrity':'ok','raw_identical_tiles':10,'sha256':'a'*64}
    return b


class NativeSpeedAdmissionTest(unittest.TestCase):
    def test_more_rss_is_allowed_with_measured_headroom_and_speed(self):
        check_benchmark(evidence())

    def test_requires_every_workload(self):
        b=evidence();del b['comparisons']['x60-y52-native']
        with self.assertRaises(ValueError):check_benchmark(b)

    def test_output_difference_is_rejected(self):
        b=evidence();b['comparisons']['x45-y25-native-1']['sha256']='b'*64
        with self.assertRaises(ValueError):check_benchmark(b)

    def test_requires_speed_improvement_in_each_region(self):
        b=evidence()
        for i in range(2):b['runs'][f'x45-y25-native-{i}']['seconds']=95
        with self.assertRaises(ValueError):check_benchmark(b)

    def test_memory_envelope_is_absolute_not_relative(self):
        for key,value in [('peak_rss_mib',6145),('minimum_available_mib',4095)]:
            b=evidence();b['runs']['quality-native'][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):check_benchmark(b)

    def test_no_failed_or_guard_stopped_run(self):
        for key,value in [('exit_code',1),('memory_floor_stop',True),('disk_floor_stop',True)]:
            b=evidence();b['runs']['quality-native'][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):check_benchmark(b)

    def test_diagnostic_or_contended_trials_are_not_admitted(self):
        b=evidence();b['timings']='diagnostic'
        with self.assertRaises(ValueError):check_benchmark(b)
        b=evidence();b['runs']['quality-native']['explorer_transfer_bytes']=300*2**20
        with self.assertRaises(ValueError):check_benchmark(b)

    def test_identity_includes_only_pre_jar_decoder_property(self):
        cmd=['java','-Xmx6g','-Dvectorvector.webpDecoder=native','-jar','app.jar','--profile','rbt']
        self.assertEqual(identity_properties(cmd),['-Dvectorvector.webpDecoder=native'])
        self.assertEqual(identity_properties(['java','-jar','app.jar']),[])


if __name__=='__main__':unittest.main()
