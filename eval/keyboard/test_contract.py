import copy
import importlib.util
import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

SPEC = importlib.util.spec_from_file_location('lip_keyboard_qualify', Path(__file__).with_name('qualify.py'))
Q = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(Q)


class ContractGuards(unittest.TestCase):
    def test_missing_consumer_providers_never_qualify(self):
        result = Q.qualify(None)
        self.assertFalse(result['qualified'])
        self.assertTrue(all(r['status'] == 'NOT_RUN' for r in result['results']))
        self.assertEqual([], result['contract_failures'])

    def test_mutable_progress_does_not_change_frozen_criteria(self):
        contract, failures = Q.check_contract()
        self.assertEqual([], failures)
        changed = copy.deepcopy(contract['criteria'])
        changed[0]['status'] = 'PASS_PENDING_FINAL'
        changed[0]['last_verification'] = 'bounded receipt'
        self.assertEqual(Q.immutable_criteria(contract['criteria']), Q.immutable_criteria(changed))
        changed[0]['statement'] = 'weaker'
        self.assertNotEqual(Q.immutable_criteria(contract['criteria']), Q.immutable_criteria(changed))

    def test_scope_mutation_is_red(self):
        contract, _ = Q.check_contract()
        lock = json.loads((Q.ROOT / 'loop/contract-lock.json').read_text())
        with TemporaryDirectory() as d:
            root = Path(d)
            for path in ['loop/acceptance.json', 'loop/contract-lock.json', *lock['sha256']]:
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((Q.ROOT / path).read_bytes())
            rubric = root / 'loop/RUBRIC.md'
            rubric.write_text(rubric.read_text().replace('9.2', '9.0'))
            _, failures = Q.check_contract(root)
            self.assertIn('scope source changed: loop/RUBRIC.md', failures)
            self.assertFalse(Q.qualify('AC-11', root=root)['qualified'])

    def test_root_authority_controls_cannot_be_changed_silently(self):
        lock = json.loads((Q.ROOT / 'loop/contract-lock.json').read_text())
        for key, value in [('threshold', 0), ('phone_testing', 'released'), ('review_authorized', False)]:
            with TemporaryDirectory() as d:
                root = Path(d)
                for path in ['loop/acceptance.json', 'loop/contract-lock.json', *lock['sha256']]:
                    target = root / path; target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes((Q.ROOT / path).read_bytes())
                path = root / 'loop/acceptance.json'; contract = json.loads(path.read_text())
                contract[key] = value; path.write_text(json.dumps(contract))
                self.assertIn('immutable root controls or criteria changed', Q.check_contract(root)[1])
                self.assertFalse(Q.qualify('AC-11', root=root)['qualified'])

    def test_unknown_criterion_and_build_only_status_cannot_pass(self):
        self.assertFalse(Q.qualify('not-an-id')['qualified'])
        self.assertFalse(Q.qualify('AC-04')['qualified'])


if __name__ == '__main__':
    unittest.main()
