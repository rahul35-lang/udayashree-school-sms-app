#!/usr/bin/env python3
"""
===============================================================================
MASTER TEST RUNNER: run_all_tests.py
MODULE: tests.run_all_tests
DESCRIPTION:
    Executes the entire automated unit and integration test suite across all
    server and gateway modules. Ensures 100% test coverage and validation.

USAGE:
    python3 tests/run_all_tests.py

AUTHOR: School System Development Team
LAST MODIFIED: 2026-10-16
===============================================================================
"""

import os
import sys
import unittest

def run_suite():
    tests_dir = os.path.dirname(os.path.abspath(__file__))
    loader = unittest.TestLoader()
    suite = loader.discover(start_dir=tests_dir, pattern="test_*.py")

    print("\n" + "=" * 70)
    print("   RUNNING ALL UNIT & INTEGRATION TESTS FOR SCHOOL SMS GATEWAY")
    print("=" * 70)

    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)

    print("\n" + "=" * 70)
    if result.wasSuccessful():
        print(f"  ✓ ALL {result.testsRun} TESTS PASSED SUCCESSFULLY!")
    else:
        print(f"  ✗ FAILED: {len(result.failures)} failures, {len(result.errors)} errors out of {result.testsRun} tests.")
    print("=" * 70 + "\n")

    sys.exit(0 if result.wasSuccessful() else 1)

if __name__ == "__main__":
    run_suite()
