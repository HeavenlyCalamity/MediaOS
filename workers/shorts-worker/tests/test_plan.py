import unittest

from shorts_worker.plan import create_demo_plan


class DemoPlanTests(unittest.TestCase):
    def test_plan_is_deterministic_and_requires_human_review(self):
        first = create_demo_plan("cosmic-curiosity", "Moon horizon illusion")
        second = create_demo_plan("cosmic-curiosity", "Moon horizon illusion")
        self.assertEqual(first, second)
        self.assertFalse(first["publishing"]["enabled"])
        self.assertTrue(first["publishing"]["approval_required"])

    def test_blank_inputs_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "brand_id"):
            create_demo_plan(" ", "brief")
        with self.assertRaisesRegex(ValueError, "brief"):
            create_demo_plan("history-uncovered", " ")


if __name__ == "__main__":
    unittest.main()
